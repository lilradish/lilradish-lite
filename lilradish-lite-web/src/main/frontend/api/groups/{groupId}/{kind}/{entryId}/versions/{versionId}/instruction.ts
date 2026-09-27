import { putDocument } from "../../../../../../../lib/request/http";
import { atVersion } from "../../versions";
import { questionFrom, type QuestionVersion } from "../{versionId}";

/**
 * A question draft's instruction, null where it is to say nothing, written over
 * the revision the page read; answered with the version as it then reads.
 */
export function instruct(
  groupId: string,
  entryId: string,
  versionId: string,
  revision: number,
  instruction: string | null,
  signal: AbortSignal,
): Promise<QuestionVersion> {
  return atVersion(groupId, "question", entryId, versionId, (version) =>
    putDocument(
      `${version}/instruction`,
      { revision, instruction },
      signal,
      questionFrom,
    ),
  );
}
