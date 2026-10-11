import type { WordedRefusal } from "../../../../i18n/app";
import { atSegment } from "../../../../lib/request/address";
import { get, patch } from "../../../../lib/request/http";
import { servedAmong, type Problem } from "../../../problem";
import {
  atLibrary,
  entryFrom,
  type Described,
  type Entry,
  type EntryKind,
} from "../{kind}";

/**
 * Each says the entry moved under the page since it was read. A version's pins
 * retired since is not among them: it names what the page is to show instead.
 */
const MOVED: ReadonlySet<string> = new Set([
  "ENTRY_NOT_IN_VIEW",
  "VERSION_NOT_IN_VIEW",
  "DRAFT_ALREADY_STARTED",
  "VERSION_STANDING_REFUSES",
  "APPROVER_WROTE_VERSION",
] satisfies readonly WordedRefusal[]);

/** The entry's address handed to `ask`, each identifier escaped as `atSegment` escapes one. */
export function atEntry<T>(
  groupId: string,
  kind: EntryKind,
  entryId: string,
  ask: (address: string) => Promise<T>,
): Promise<T> {
  return atLibrary(groupId, kind, (library) =>
    atSegment(library, entryId, ask),
  );
}

export function readEntry(
  groupId: string,
  kind: EntryKind,
  entryId: string,
  signal: AbortSignal,
): Promise<Entry> {
  return atEntry(groupId, kind, entryId, (address) =>
    get(address, signal, entryFrom),
  );
}

export function editEntry(
  groupId: string,
  kind: EntryKind,
  entryId: string,
  described: Described,
  signal: AbortSignal,
): Promise<Entry> {
  const { name, purpose } = described;
  return atEntry(groupId, kind, entryId, (address) =>
    patch(address, { name, purpose }, signal, entryFrom),
  );
}

/**
 * Somebody moved the entry since it was read; reading again shows why the change
 * was refused. Read status before code, as `Problem` requires.
 */
export function refusedForWhatMoved(problem: Problem): boolean {
  return servedAmong(problem, MOVED);
}
