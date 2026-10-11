import type { WordedRefusal } from "../../../../../../../i18n/app";
import { atSegment } from "../../../../../../../lib/request/address";
import { put } from "../../../../../../../lib/request/http";
import { servedAmong, type Problem } from "../../../../../../problem";
import { atRun } from "../../../{runId}";
import { stepAnswerFrom, type StepAnswer } from "../{stepId}";

const CHANGED: ReadonlySet<string> = new Set([
  "STEP_MOVED_ON",
  "TRY_SENDING_NOT_OFFERED",
  "RUN_STOPPED",
  "ENTRY_STOPPED",
] satisfies readonly WordedRefusal[]);

const MOVED_ON: ReadonlySet<string> = new Set([
  "STEP_MOVED_ON",
] satisfies readonly WordedRefusal[]);

/** Sending follows the answer, so the step returned may still stand as it did before the press. */
export function trySending(
  groupId: string,
  runId: string,
  stepId: string,
  signal: AbortSignal,
): Promise<StepAnswer> {
  return atRun(groupId, runId, (run) =>
    atSegment(`${run}/steps`, stepId, (step) =>
      put(`${step}/sending`, signal, stepAnswerFrom),
    ),
  );
}

/**
 * Sending stopped being offered since the read: somebody acted first, no model
 * try awaits sending, or the run or its entry stopped.
 */
export function refusedAsChanged(problem: Problem): boolean {
  return servedAmong(problem, CHANGED);
}

export function refusedAsMovedOn(problem: Problem): boolean {
  return servedAmong(problem, MOVED_ON);
}
