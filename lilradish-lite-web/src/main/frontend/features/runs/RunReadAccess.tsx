import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from "react";

import { asProblem, servedAmong, type Problem } from "../../api/problem";

const RUN_REFUSAL = new Set(["RUN_NOT_IN_VIEW"]);

interface Access {
  readonly problem: Problem | null;
  readonly closed: () => boolean;
  readonly read: <T>(signal: AbortSignal, load: () => Promise<T>) => Promise<T>;
}

const AccessContext = createContext<Access | null>(null);

/** A refusal of the run itself closes every read beneath this route identity. */
export function RunReadAccess({ children }: { readonly children: ReactNode }) {
  const [problem, setProblem] = useState<Problem | null>(null);
  const closed = useRef(false);
  const isClosed = useCallback(() => closed.current, []);
  const present = useRef(true);
  useEffect(() => {
    present.current = true;
    return () => {
      present.current = false;
    };
  }, []);
  const read = useCallback(
    async <T,>(signal: AbortSignal, load: () => Promise<T>): Promise<T> => {
      try {
        return await load();
      } catch (failure) {
        const refused = asProblem(failure, signal);
        if (
          present.current &&
          !closed.current &&
          servedAmong(refused, RUN_REFUSAL)
        ) {
          closed.current = true;
          setProblem(refused);
        }
        throw failure;
      }
    },
    [],
  );
  const access = useMemo(
    () => ({ problem, closed: isClosed, read }),
    [problem, isClosed, read],
  );
  return (
    <AccessContext.Provider value={access}>{children}</AccessContext.Provider>
  );
}

export function useRunReadAccess(): Access {
  const access = useContext(AccessContext);
  if (access === null) {
    throw new Error("Run read access is missing from the run page");
  }
  return access;
}
