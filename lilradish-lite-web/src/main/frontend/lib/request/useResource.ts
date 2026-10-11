import { useCallback, useEffect, useMemo, useRef, useState } from "react";

import { asProblem, RequestFailed, type Problem } from "../../api/problem";
import { reportCaught } from "../report/reportCaught";

interface Settled<T> {
  readonly value: T;
  readonly problem: Problem | null;
  readonly loading: boolean;
}

export interface Resource<T> extends Settled<T> {
  readonly reload: () => void;
}

/**
 * Re-issued on `load` changes or `reload`, abandoned when the screen goes.
 *
 * `load` must be stable — a module constant, or the caller's own `useCallback`.
 * It keys the read, so rebuilding it each render reads forever. A ref would
 * need writing during render; varying inputs belong in the caller's dependency
 * list, where the linter can check them.
 *
 * A refusal clears the last answer: its authority may have been withdrawn,
 * so old rows must not look current. Asking again clears the old refusal,
 * which belonged to the read being replaced.
 */
export function useResource<T>(
  load: (signal: AbortSignal) => Promise<T>,
  empty: T,
): Resource<T> {
  // State keeps an inline-built empty value stable across source resets;
  // the effect names `blank`, since depending on `empty` could re-read each render.
  const [blank] = useState(() => empty);
  const [settled, setSettled] = useState<Settled<T>>({
    value: blank,
    problem: null,
    loading: true,
  });
  // Read by nothing here: it re-keys the effect below, which is what re-issues
  // the read. `exhaustive-deps` is blind to its removal; the tests are not.
  const [attempt, setAttempt] = useState(0);
  // Boxed because React calls a bare function handed to `useState` or to a
  // setter: unwrapped, `load` would run during render, without a signal.
  const [source, setSource] = useState({ load });
  const abandonRead = useRef<(() => void) | null>(null);

  useEffect(() => {
    const controller = new AbortController();
    // Aborting stops the request, the flag stops the settlement, and it takes
    // both: a caller may chain work onto the read, and that chain runs whether
    // or not the request it was chained to was cut off.
    let abandoned = false;
    const abandon = () => {
      abandoned = true;
      controller.abort();
    };
    abandonRead.current = abandon;
    const refuse = (failure: unknown) => {
      if (!(controller.signal.aborted || failure instanceof RequestFailed)) {
        reportCaught(failure);
      }
      if (!abandoned) {
        setSettled({
          value: blank,
          // Never an abandoned signal: the flag above is what turns those away.
          problem: asProblem(failure, controller.signal),
          loading: false,
        });
      }
    };
    // A `load` that throws where it should reject escapes into the effect, where
    // React answers it by taking the screen down instead of reporting a refusal.
    try {
      load(controller.signal).then((value) => {
        if (!abandoned) {
          setSettled({ value, problem: null, loading: false });
        }
      }, refuse);
    } catch (failure) {
      refuse(failure);
    }
    return () => {
      if (abandonRead.current === abandon) {
        abandonRead.current = null;
      }
      abandon();
    };
  }, [load, blank, attempt]);

  const reload = useCallback(() => {
    abandonRead.current?.();
    setSettled((prior) => ({ ...prior, problem: null, loading: true }));
    setAttempt((count) => count + 1);
  }, []);
  // Kept while nothing settles: a frame that re-renders on every move hands it
  // down through a context, and a new object re-renders every reader of it.
  const resource = useMemo(() => ({ ...settled, reload }), [settled, reload]);

  // Reset during render: waiting for an effect could paint old rows and refusal
  // under the replacement resource's heading.
  if (source.load !== load) {
    const fresh = { value: blank, problem: null, loading: true };
    setSource({ load });
    setSettled(fresh);
    return { ...fresh, reload };
  }

  return resource;
}
