import { putDocument } from "../../../../../../../lib/request/http";
import { atVersion } from "../../versions";
import { workflowFrom, type WorkflowVersion } from "../{versionId}";

/**
 * Ceiling is digits, or null for none. Names the revision read and returns the
 * version as it then reads.
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
