import { put } from "../../../../../../../lib/request/http";
import { entryFrom, type Entry, type EntryKind } from "../../../../{kind}";
import { atVersion } from "../../versions";

/** A version in service retired: offered to nothing new, and still resolving for whatever names it. */
export function retire(
  groupId: string,
  kind: EntryKind,
  entryId: string,
  versionId: string,
  signal: AbortSignal,
): Promise<Entry> {
  return atVersion(groupId, kind, entryId, versionId, (version) =>
    put(`${version}/retirement`, signal, entryFrom),
  );
}
