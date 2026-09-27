import { put } from "../../../../../../../lib/request/http";
import { entryFrom, type Entry, type EntryKind } from "../../../../{kind}";
import { atVersion } from "../../versions";

/** A submitted version put into service. */
export function approve(
  groupId: string,
  kind: EntryKind,
  entryId: string,
  versionId: string,
  signal: AbortSignal,
): Promise<Entry> {
  return atVersion(groupId, kind, entryId, versionId, (version) =>
    put(`${version}/approval`, signal, entryFrom),
  );
}
