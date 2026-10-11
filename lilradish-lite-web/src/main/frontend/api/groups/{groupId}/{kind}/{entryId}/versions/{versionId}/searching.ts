import { putDocument } from "../../../../../../../lib/request/http";
import { atVersion } from "../../versions";
import { questionFrom, type QuestionVersion } from "../{versionId}";

/** Naming a search way also refreshes its declared answer; clearing it keeps the fields. Both use the revision read. */
export function searching(
  groupId: string,
  entryId: string,
  versionId: string,
  revision: number,
  searchWay: string | null,
  signal: AbortSignal,
): Promise<QuestionVersion> {
  return atVersion(groupId, "question", entryId, versionId, (version) =>
    putDocument(
      `${version}/searching`,
      { revision, searchWay },
      signal,
      questionFrom,
    ),
  );
}
