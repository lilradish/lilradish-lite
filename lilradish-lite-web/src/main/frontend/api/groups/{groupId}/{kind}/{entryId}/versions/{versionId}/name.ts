import { putDocument } from "../../../../../../../lib/request/http";
import { entryFrom, type Entry, type EntryKind } from "../../../../{kind}";
import { atVersion } from "../../versions";

/** Naming is independent of content and standing; the empty exact string clears the name. */
export function nameVersion(
  groupId: string,
  kind: EntryKind,
  entryId: string,
  versionId: string,
  name: string,
  signal: AbortSignal,
): Promise<Entry> {
  return atVersion(groupId, kind, entryId, versionId, (address) =>
    putDocument(`${address}/name`, { name }, signal, entryFrom),
  );
}
