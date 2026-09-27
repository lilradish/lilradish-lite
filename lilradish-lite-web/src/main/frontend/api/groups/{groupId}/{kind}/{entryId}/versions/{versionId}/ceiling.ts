import { putDocument } from "../../../../../../../lib/request/http";
import { atVersion } from "../../versions";
import { workflowFrom, type WorkflowVersion } from "../{versionId}";

/**
 * The most a run of a workflow draft may spend, in digits or null for none, and whether a run keeps its own and a
 * raise waits on approving, naming the revision the page read; answered with the version as it then reads.
 */
export function writeCeiling(
  groupId: string,
  entryId: string,
  versionId: string,
  revision: number,
  ceiling: string | null,
  keepsOwnCeiling: boolean,
  raiseNeedsApproval: boolean,
  signal: AbortSignal,
): Promise<WorkflowVersion> {
  return atVersion(groupId, "workflow", entryId, versionId, (version) =>
    putDocument(
      `${version}/ceiling`,
      { revision, ceiling, keepsOwnCeiling, raiseNeedsApproval },
      signal,
      workflowFrom,
    ),
  );
}
