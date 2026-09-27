import { put, remove } from "../../../../../lib/request/http";
import { entryFrom, type Entry, type EntryKind } from "../../{kind}";
import { atEntry } from "../{entryId}";

/** Taking effect at once; stopped already, it stays as it was stopped. */
export function stopEntry(
  groupId: string,
  kind: EntryKind,
  entryId: string,
  signal: AbortSignal,
): Promise<Entry> {
  return atEntry(groupId, kind, entryId, (entry) =>
    put(`${entry}/stop`, signal, entryFrom),
  );
}

export function letEntryGo(
  groupId: string,
  kind: EntryKind,
  entryId: string,
  signal: AbortSignal,
): Promise<Entry> {
  return atEntry(groupId, kind, entryId, (entry) =>
    remove(`${entry}/stop`, signal, entryFrom),
  );
}
