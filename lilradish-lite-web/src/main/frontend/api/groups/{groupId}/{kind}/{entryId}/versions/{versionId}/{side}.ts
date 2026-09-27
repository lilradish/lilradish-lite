import { putDocument } from "../../../../../../../lib/request/http";
import type { DeclarationSide, SentField } from "../../../../../../declaration";
import { atVersion } from "../../versions";
import {
  questionFrom,
  workflowFrom,
  type QuestionVersion,
  type WorkflowVersion,
} from "../{versionId}";

/**
 * Half of what a question draft declares, written whole in place of what it
 * held over the revision the page read; answered with the version as it then reads.
 */
export function declare(
  groupId: string,
  entryId: string,
  versionId: string,
  side: DeclarationSide,
  revision: number,
  fields: readonly SentField[],
  signal: AbortSignal,
): Promise<QuestionVersion> {
  return atVersion(groupId, "question", entryId, versionId, (version) =>
    putDocument(
      `${version}/${side}`,
      { revision, fields },
      signal,
      questionFrom,
    ),
  );
}

/**
 * Half of what a workflow draft declares, written whole in place of what it held, naming the revision the page
 * read; answered with the version as it then reads.
 */
export function declareWorkflow(
  groupId: string,
  entryId: string,
  versionId: string,
  side: DeclarationSide,
  revision: number,
  fields: readonly SentField[],
  signal: AbortSignal,
): Promise<WorkflowVersion> {
  return atVersion(groupId, "workflow", entryId, versionId, (version) =>
    putDocument(
      `${version}/${side}`,
      { revision, fields },
      signal,
      workflowFrom,
    ),
  );
}
