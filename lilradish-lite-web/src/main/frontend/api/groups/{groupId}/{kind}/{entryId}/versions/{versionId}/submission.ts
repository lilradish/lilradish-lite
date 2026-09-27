import { put, remove } from "../../../../../../../lib/request/http";
import { entryFrom, type Entry, type EntryKind } from "../../../../{kind}";
import { atVersion } from "../../versions";

/** A draft submitted, editable by nobody from then on. */
export function submit(
  groupId: string,
  kind: EntryKind,
  entryId: string,
  versionId: string,
  signal: AbortSignal,
): Promise<Entry> {
  return atVersion(groupId, kind, entryId, versionId, (version) =>
    put(`${version}/submission`, signal, entryFrom),
  );
}

/** A submitted version withdrawn, a draft again. */
export function withdraw(
  groupId: string,
  kind: EntryKind,
  entryId: string,
  versionId: string,
  signal: AbortSignal,
): Promise<Entry> {
  return atVersion(groupId, kind, entryId, versionId, (version) =>
    remove(`${version}/submission`, signal, entryFrom),
  );
}
