import { atSegment } from "../../../../../../lib/request/address";
import { put } from "../../../../../../lib/request/http";
import { atRun, runFrom, type Run } from "../../{runId}";

/** How a raise waiting on approval is decided: each names the raise, so none decides one its reader never saw. */
export type RaiseDecision = "approval" | "refusal" | "withdrawal";

export function decideRaise(
  groupId: string,
  runId: string,
  changeId: string,
  decision: RaiseDecision,
  signal: AbortSignal,
): Promise<Run> {
  return atRun(groupId, runId, (run) =>
    atSegment(`${run}/ceiling-changes`, changeId, (change) =>
      put(`${change}/${decision}`, signal, runFrom),
    ),
  );
}
