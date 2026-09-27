import { putDocument } from "../../../../../../../lib/request/http";
import { atVersion } from "../../versions";
import { workflowFrom, type WorkflowVersion } from "../{versionId}";
import type { SentChoice } from "./steps";

/**
 * Whether runs of a workflow draft may be helped, and the model helping them, null where they may not be or none
 * is chosen yet, naming the revision the page read; answered with the version as it then reads.
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
