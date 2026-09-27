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

/**
 * The try the step is held back or failed on, handed over to be sent to its model. Nothing is sent with it. The
 * step it is answered with is almost always as it stood before, since sending comes after the answer.
 */
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
 * Whether the step no longer offered sending since it was read: somebody acted on it first, it no longer holds a
 * try a model could not be sent, or the run or what it runs was stopped.
 */
export function refusedAsChanged(problem: Problem): boolean {
  return servedAmong(problem, CHANGED);
}

/** Whether the step had moved on from where it was held or failed since it was read. */
export function refusedAsMovedOn(problem: Problem): boolean {
  return servedAmong(problem, MOVED_ON);
}
