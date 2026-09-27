import { putDocument } from "../../../../../../../lib/request/http";
import { atVersion } from "../../versions";
import { referenceListFrom, type ReferenceListVersion } from "../{versionId}";

/**
 * A reference list draft's note, null where it is to say nothing, written over
 * the revision the page read; answered with the list as it then reads.
 */
export function writeNote(
  groupId: string,
  entryId: string,
  versionId: string,
  revision: number,
  note: string | null,
  signal: AbortSignal,
): Promise<ReferenceListVersion> {
  return atVersion(groupId, "reference_list", entryId, versionId, (version) =>
    putDocument(
      `${version}/note`,
      { revision, note },
      signal,
      referenceListFrom,
    ),
  );
}
