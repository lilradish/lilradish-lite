import type { WordedRefusal } from "../../../../../../../../i18n/app";
import { atSegment } from "../../../../../../../../lib/request/address";
import { get, put } from "../../../../../../../../lib/request/http";
import { servedAmong, type Problem } from "../../../../../../../problem";
import { atRun } from "../../../../{runId}";
import { stepAnswerFrom, type StepAnswer } from "../../{stepId}";
import {
  tryDetailsFrom,
  type TryDetails,
} from "../../../../../../../stepTries";

const CHANGED: ReadonlySet<string> = new Set([
  "STEP_MOVED_ON",
  "RUN_STOPPED",
  "ENTRY_STOPPED",
  "ASK_AGAIN_NOT_OFFERED",
] satisfies readonly WordedRefusal[]);

const MOVED_ON: ReadonlySet<string> = new Set([
  "STEP_MOVED_ON",
] satisfies readonly WordedRefusal[]);

export function readTry(
  groupId: string,
  runId: string,
  stepId: string,
  number: number,
  signal: AbortSignal,
): Promise<TryDetails> {
  return atRun(groupId, runId, (run) =>
    atSegment(`${run}/steps`, stepId, (step) =>
      get(`${step}/tries/${number}`, signal, (body) => {
        const read = tryDetailsFrom(body);
        return read?.number === number ? read : null;
      }),
    ),
  );
}

/**
 * Try `number`, asked of whoever the step names to produce it.
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
 * Since the step was read, it no longer owes or accepts that try: somebody acted first,
 * the run or its entry stopped, or the next try can only be a person's answer.
 */
export function refusedAsChanged(problem: Problem): boolean {
  return servedAmong(problem, CHANGED);
}

/** Whether an answer or an ask was refused because somebody acted on that try first. */
export function refusedAsMovedOn(problem: Problem): boolean {
  return servedAmong(problem, MOVED_ON);
}
