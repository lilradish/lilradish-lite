import { useCallback, useEffect, useMemo, useState } from "react";

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
 * What a screen reads because it opened: re-issued when `load` changes and when
 * `reload` is called, abandoned when the screen goes.
 *
 * `load` must be stable — a module constant, or the caller's own `useCallback`.
 * It is what the read is keyed on, so one rebuilt each render reads forever. A
 * ref holding the current `load` is not the way out: it would have to be
 * written during render, and what varies belongs in the caller's dependency
 * list where the linter can still read it.
 *
 * A refusal takes the last answer with it. What was refused may be the
 * authority itself, and rows fetched under an authority since taken away must
 * not sit on screen looking current. The reason goes when the reader asks
 * again, because it was the answer to the read being replaced.
 */
export function useResource<T>(
  load: (signal: AbortSignal) => Promise<T>,
  empty: T,
): Resource<T> {
  // Held in state rather than aliased: a caller who builds the empty value
  // inline would otherwise see `value` change identity at every source reset,
  // and this is also what the effect below can name — `empty` itself would
  // re-key the read on every render.
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

  useEffect(() => {
    const controller = new AbortController();
    // Aborting stops the request, the flag stops the settlement, and it takes
    // both: a caller may chain work onto the read, and that chain runs whether
    // or not the request it was chained to was cut off.
    let abandoned = false;
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
      abandoned = true;
      controller.abort();
    };
  }, [load, blank, attempt]);

  const reload = useCallback(() => {
    setSettled((prior) => ({ ...prior, problem: null, loading: true }));
    setAttempt((count) => count + 1);
  }, []);
  // Kept while nothing settles: a frame that re-renders on every move hands it
  // down through a context, and a new object re-renders every reader of it.
  const resource = useMemo(() => ({ ...settled, reload }), [settled, reload]);

  // Let go of the previous resource here rather than in an effect: an effect
  // runs after the commit, so a whole paint would show the rows and the refusal
  // of the resource just left behind, under the heading of the one replacing it.
  if (source.load !== load) {
    const fresh = { value: blank, problem: null, loading: true };
    setSource({ load });
    setSettled(fresh);
    return { ...fresh, reload };
  }

  return resource;
}
