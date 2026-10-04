import { putDocument } from "../../../../../../../lib/request/http";
import { atVersion } from "../../versions";
import { questionFrom, type QuestionVersion } from "../{versionId}";

/** A question draft's optional Markdown drawing, written over the revision the page read. */
export function draw(
  groupId: string,
  entryId: string,
  versionId: string,
  revision: number,
  drawing: string | null,
  signal: AbortSignal,
): Promise<QuestionVersion> {
  return atVersion(groupId, "question", entryId, versionId, (version) =>
    putDocument(
      `${version}/drawing`,
      { revision, drawing },
      signal,
      questionFrom,
    ),
  );
}
