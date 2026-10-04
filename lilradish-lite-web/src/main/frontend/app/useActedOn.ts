import { useEffect, useState } from "react";

import type { Problem } from "../api/problem";
import { useAction, type Action } from "../lib/request/useAction";
import type { Resource } from "../lib/request/useResource";
import { movesTheReader } from "./refusal";
import { useStandingRead } from "./standing/StandingContext";

/**
 * What a page reads and acts on, shown as the last read or act's answer. A refusal, the act's or one met
 * elsewhere on the page, reads the thing again where it says either moved, and the standing where the reader did.
 */
export function useActedOn<T>(
  read: Resource<T>,
  refusedForWhatMoved: (problem: Problem) => boolean,
  after: {
    readonly answered?: (answer: T) => void;
    readonly readAgain?: () => void;
  } = {},
): {
  readonly shown: T;
  readonly showingAnswerWhileReading: boolean;
  readonly changing: Action<T>;
  readonly refused: (problem: Problem) => void;
} {
  const reloadStanding = useStandingRead().reload;
  const reloadRead = read.reload;
  // Boxed, so a value that is itself a function is held rather than called.
  const [shown, setShown] = useState({ value: read.value });
  const [readShown, setReadShown] = useState(read.value);
  const [guardingAnswer, setGuardingAnswer] = useState(false);
  if (read.value !== readShown || (guardingAnswer && !read.loading)) {
    setReadShown(read.value);
    if (!guardingAnswer || !read.loading) {
      setShown({ value: read.value });
      if (guardingAnswer) {
        setGuardingAnswer(false);
      }
    }
  }

  useEffect(() => {
    if (read.problem !== null && movesTheReader(read.problem)) {
      reloadStanding();
    }
  }, [read.problem, reloadStanding]);

  const refused = (problem: Problem) => {
    const moved = movesTheReader(problem);
    if (moved) {
      reloadStanding();
    }
    if (moved || refusedForWhatMoved(problem)) {
      reloadRead();
      after.readAgain?.();
    }
  };

  const changing = useAction<T>((answer) => {
    if (read.loading) {
      setGuardingAnswer(true);
      reloadRead();
    }
    setShown({ value: answer });
    after.answered?.(answer);
  }, refused);

  return {
    shown: shown.value,
    showingAnswerWhileReading: guardingAnswer && read.loading,
    changing,
    refused,
  };
}
