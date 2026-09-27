import type { WordedRefusal } from "../../../../../../../../i18n/app";
import { atSegment } from "../../../../../../../../lib/request/address";
import { put } from "../../../../../../../../lib/request/http";
import { servedAmong, type Problem } from "../../../../../../../problem";
import { atRun } from "../../../../{runId}";
import { stepAnswerFrom, type StepAnswer } from "../../{stepId}";

const CHANGED: ReadonlySet<string> = new Set([
  "STEP_MOVED_ON",
  "RUN_STOPPED",
  "ENTRY_STOPPED",
  "ASK_AGAIN_NOT_OFFERED",
] satisfies readonly WordedRefusal[]);

const MOVED_ON: ReadonlySet<string> = new Set([
  "STEP_MOVED_ON",
] satisfies readonly WordedRefusal[]);

/**
 * Try `number`, asked of whoever the step names to produce it. Nothing is sent with it: the address says it all.
 * Answered with the step as it stands once the run has gone on as far as it goes by itself.
 */
export function askAgain(
  groupId: string,
  runId: string,
  stepId: string,
  number: number,
  signal: AbortSignal,
): Promise<StepAnswer> {
  return atRun(groupId, runId, (run) =>
    atSegment(`${run}/steps`, stepId, (step) =>
      put(`${step}/tries/${number}`, signal, stepAnswerFrom),
    ),
  );
}

/**
 * Whether the step no longer owed that try, or no longer took one asked for, since it was read: somebody acted on
 * it first, the run or what it runs was stopped, or its next try is only ever somebody's answer.
 */
export function refusedAsChanged(problem: Problem): boolean {
  return servedAmong(problem, CHANGED);
}

/** Whether somebody acted on that try first, as an answer to it, or an ask for it, is refused by. */
export function refusedAsMovedOn(problem: Problem): boolean {
  return servedAmong(problem, MOVED_ON);
}
