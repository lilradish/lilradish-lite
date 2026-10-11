import type { WordedRefusal } from "../../../../../../../../i18n/app";
import { atSegment } from "../../../../../../../../lib/request/address";
import { putDocument } from "../../../../../../../../lib/request/http";
import { servedAmong, type Problem } from "../../../../../../../problem";
import { atRun } from "../../../../{runId}";
import { stepAnswerFrom, type StepAnswer } from "../../{stepId}";

const CHANGED: ReadonlySet<string> = new Set([
  "LENGTH_VALUE_USED",
  "LENGTH_VALUE_NOT_STANDING",
  "STEP_NOT_HELD_FOR_LENGTH",
] satisfies readonly WordedRefusal[]);

/** One source rejected for the hold the reader saw; quantities and the reason are the server's. */
export function refuseForLength(
  groupId: string,
  runId: string,
  stepId: string,
  holdId: string,
  valueId: string,
  signal: AbortSignal,
): Promise<StepAnswer> {
  return atRun(groupId, runId, (run) =>
    atSegment(`${run}/steps`, stepId, (step) =>
      putDocument(
        `${step}/length/refusal`,
        { holdId, valueId },
        signal,
        stepAnswerFrom,
      ),
    ),
  );
}

/** The confirmation's source or hold has changed, so the page must read what stands now. */
export function refusedAsChanged(problem: Problem): boolean {
  return servedAmong(problem, CHANGED);
}
