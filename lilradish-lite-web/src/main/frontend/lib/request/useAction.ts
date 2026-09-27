import {
  useCallback,
  useEffect,
  useLayoutEffect,
  useRef,
  useState,
} from "react";

import { asProblem, RequestFailed, type Problem } from "../../api/problem";
import { reportCaught } from "../report/reportCaught";

export interface Action<T> {
  readonly run: (act: (signal: AbortSignal) => Promise<T>) => void;
  readonly problem: Problem | null;
  readonly running: boolean;
}

/**
 * What the reader asks for, as against what a screen reads because it opened.
 *
 * One act at a time, and asking again abandons what is out. Leaving the screen
 * abandons it too, and nothing settles into a tree that is gone.
 *
 * `onSettled` and `onRefused` are the ones of the render the act ends in, not
 * the one it began in, so an answer is taken into the screen as it now stands.
 */
export function useAction<T>(
  onSettled: (outcome: T) => void,
  onRefused?: (problem: Problem) => void,
): Action<T> {
  const [problem, setProblem] = useState<Problem | null>(null);
  const [running, setRunning] = useState(false);
  // Written from the event that starts an act and from the callbacks that end
  // one, never during render.
  const inFlight = useRef<AbortController | null>(null);
  const latest = useRef({ onSettled, onRefused });
  useLayoutEffect(() => {
    latest.current = { onSettled, onRefused };
  });

  const run = useCallback((act: (signal: AbortSignal) => Promise<T>) => {
    inFlight.current?.abort();
    const controller = new AbortController();
    inFlight.current = controller;
    setProblem(null);
    setRunning(true);
    const refuse = (failure: unknown) => {
      if (!(controller.signal.aborted || failure instanceof RequestFailed)) {
        reportCaught(failure);
      }
      if (inFlight.current === controller) {
        inFlight.current = null;
        // Never an abandoned signal: the check above is what turns those away.
        const refused = asProblem(failure, controller.signal);
        setProblem(refused);
        setRunning(false);
        latest.current.onRefused?.(refused);
      }
    };
    // An `act` that throws where it should reject would leave every button on
    // the screen disabled for good.
    try {
      act(controller.signal).then((outcome) => {
        if (inFlight.current === controller) {
          inFlight.current = null;
          setRunning(false);
          latest.current.onSettled(outcome);
        }
      }, refuse);
    } catch (failure) {
      refuse(failure);
    }
  }, []);

  // Cleared as well as aborted: an abandoned act settles all the same, and
  // failing to recognise itself is what keeps it from being reported.
  useEffect(
    () => () => {
      inFlight.current?.abort();
      inFlight.current = null;
    },
    [],
  );

  return { run, problem, running };
}
