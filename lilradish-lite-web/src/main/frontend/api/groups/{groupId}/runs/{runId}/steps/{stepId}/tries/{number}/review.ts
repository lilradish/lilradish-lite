import type { WordedRefusal } from "../../../../../../../../../i18n/app";
import { atSegment } from "../../../../../../../../../lib/request/address";
import { putDocument } from "../../../../../../../../../lib/request/http";
import { servedAmong, type Problem } from "../../../../../../../../problem";
import { atRun } from "../../../../../{runId}";
import { stepAnswerFrom, type StepAnswer } from "../../../{stepId}";

/** What one value's review says of it: a refusal says why, and nothing else does. */
export type ReviewDecision =
  | { readonly outcome: "assured" }
  | { readonly outcome: "refused"; readonly why: string };

const MOVED_ON: ReadonlySet<string> = new Set([
  "STEP_MOVED_ON",
] satisfies readonly WordedRefusal[]);

const WITHDRAWN: ReadonlySet<string> = new Set([
  "RUN_STOPPED",
  "REVIEW_NOT_A_PERSONS",
] satisfies readonly WordedRefusal[]);

const REASON_REFUSED: ReadonlySet<string> = new Set([
  "REASON_MISSING",
  "REASON_UNUSABLE",
  "PROSE_TAG_CHARACTER",
  "PROSE_DIRECTION_CONTROL",
  "PROSE_LINE_BREAK_CRLF",
] satisfies readonly WordedRefusal[]);

/**
 * One review deciding every value of try `number` waiting on it, each by its field's name, and naming nothing
 * else. Answered with the step as it stands once the run has gone on as far as it goes by itself.
 */
export function reviewTry(
  groupId: string,
  runId: string,
  stepId: string,
  number: number,
  decisions: Readonly<Record<string, ReviewDecision>>,
  signal: AbortSignal,
): Promise<StepAnswer> {
  return atRun(groupId, runId, (run) =>
    atSegment(`${run}/steps`, stepId, (step) =>
      putDocument(
        `${step}/tries/${number}/review`,
        { decisions },
        signal,
        stepAnswerFrom,
      ),
    ),
  );
}

/** Whether the try no longer waited on review when the review came: somebody acted on it first. */
export function refusedAsMovedOn(problem: Problem): boolean {
  return servedAmong(problem, MOVED_ON);
}

/** Whether the review was taken from every person since the step was read: the run stopped, or a model reviews. */
export function refusedAsWithdrawn(problem: Problem): boolean {
  return servedAmong(problem, WITHDRAWN);
}

/** Whether a reason sent was refused; the refusal names no field, only why. */
export function refusedForTheReason(problem: Problem): boolean {
  return servedAmong(problem, REASON_REFUSED);
}
