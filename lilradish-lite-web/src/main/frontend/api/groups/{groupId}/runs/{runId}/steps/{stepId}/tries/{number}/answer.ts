import type { WordedRefusal } from "../../../../../../../../../i18n/app";
import { atSegment } from "../../../../../../../../../lib/request/address";
import { putDocument } from "../../../../../../../../../lib/request/http";
import type { FillValues } from "../../../../../../../../filling";
import { servedAmong, type Problem } from "../../../../../../../../problem";
import { atRun } from "../../../../../{runId}";
import { stepAnswerFrom, type StepAnswer } from "../../../{stepId}";

const CHANGED: ReadonlySet<string> = new Set([
  "STEP_MOVED_ON",
  "RUN_STOPPED",
  "ENTRY_STOPPED",
  "CODE_STEP_GIVES_OTHERWISE",
] satisfies readonly WordedRefusal[]);

const MOVED_ON: ReadonlySet<string> = new Set([
  "STEP_MOVED_ON",
] satisfies readonly WordedRefusal[]);

const REASON_REFUSED: ReadonlySet<string> = new Set([
  "REASON_MISSING",
  "REASON_UNUSABLE",
  "PROSE_TAG_CHARACTER",
  "PROSE_DIRECTION_CONTROL",
  "PROSE_LINE_BREAK_CRLF",
] satisfies readonly WordedRefusal[]);

/**
 * A person's answer filling try `number`: a value for every field it gives, in the words a start sends them, and
 * why. Answered with the step as it stands once the run has gone on as far as it goes by itself.
 */
export function answerTry(
  groupId: string,
  runId: string,
  stepId: string,
  number: number,
  values: FillValues,
  why: string,
  signal: AbortSignal,
): Promise<StepAnswer> {
  return atRun(groupId, runId, (run) =>
    atSegment(`${run}/steps`, stepId, (step) =>
      putDocument(
        `${step}/tries/${number}/answer`,
        { values, why },
        signal,
        stepAnswerFrom,
      ),
    ),
  );
}

/**
 * Whether the step no longer took that answer since it was read: somebody acted on it first, the run or what it
 * runs was stopped, or what a code step gives back no longer matches what reads it.
 */
export function refusedAsChanged(problem: Problem): boolean {
  return servedAmong(problem, CHANGED);
}

/** Whether somebody acted on that try first, as an answer to it is refused by. */
export function refusedAsMovedOn(problem: Problem): boolean {
  return servedAmong(problem, MOVED_ON);
}

/** Whether the reason sent was refused; the refusal names no value, only why. */
export function refusedForTheReason(problem: Problem): boolean {
  return servedAmong(problem, REASON_REFUSED);
}
