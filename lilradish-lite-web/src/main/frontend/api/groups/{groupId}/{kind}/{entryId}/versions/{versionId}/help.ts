import { putDocument } from "../../../../../../../lib/request/http";
import { atVersion } from "../../versions";
import { workflowFrom, type WorkflowVersion } from "../{versionId}";
import type { SentChoice } from "./steps";

/**
 * Helper is null if helping is disallowed or no model is chosen. Names the
 * revision read and returns the version as it then reads.
 */
export function writeHelp(
  groupId: string,
  entryId: string,
  versionId: string,
  revision: number,
  mayBeHelped: boolean,
  helper: SentChoice | null,
  signal: AbortSignal,
): Promise<WorkflowVersion> {
  return atVersion(groupId, "workflow", entryId, versionId, (version) =>
    putDocument(
      `${version}/help`,
      { revision, mayBeHelped, helper },
      signal,
      workflowFrom,
    ),
  );
}
