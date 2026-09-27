import { atSegment } from "../../../lib/request/address";
import {
  isUnchecked,
  listOf,
  optional,
  textFrom,
  wordsIn,
} from "../../../lib/request/document";
import { post } from "../../../lib/request/http";
import type { Page } from "../../../lib/request/page";
import { pagedLoader, type ListQuery } from "../../../lib/request/pagedLoader";
import { GROUPS } from "../../groups";
import { personFrom, type Person } from "../../person";

/**
 * The kinds an entry may be, spelt the way the server spells them, and held
 * level with the server's list for the reason `SurfaceAct` gives.
 */
export type EntryKind = "workflow" | "question" | "reference_list";

/** The segment each kind's entries are addressed under, held level as `EntryKind` is. */
export type EntrySegment = "workflows" | "questions" | "reference-lists";

/** The standings a version may be at, spelt and held level as `EntryKind` is. */
export type VersionStanding = "draft" | "submitted" | "in_service" | "retired";

/** What may be done to an entry as a whole, spelt and held level as `EntryKind` is. */
export type EntryAct = "start_draft" | "rename" | "stop" | "let_go";

/** What may be done to one version, spelt and held level as `EntryKind` is. */
export type VersionAct = "write" | "submit" | "withdraw" | "approve" | "retire";

/** The columns a group's entries of one kind can be sorted by, spelt as the server takes them. */
export type LibrarySortColumn = "name" | "inService" | "submitted" | "stopped";

/** One column, led by a hyphen for descending. */
export type LibraryOrder = LibrarySortColumn | `-${LibrarySortColumn}`;

export type LibraryQuery = ListQuery<LibraryOrder>;

/** Where each kind's entries are, which is where a reader's page of them is too. */
export const SEGMENTS = {
  workflow: "workflows",
  question: "questions",
  reference_list: "reference-lists",
} as const satisfies Record<EntryKind, EntrySegment>;

/** One entry of the kind listed. `entryId` addresses the row and is never shown. */
export interface EntryRow {
  readonly entryId: string;
  /** Shown isolated wherever it is shown: a name may carry a directional control. */
  readonly name: string;
  /** The number of the newest version in service; absent where none is. */
  readonly inService?: number;
  readonly submitted: boolean;
  readonly stopped: boolean;
}

/** A version pinning another, which is in service. */
export interface PinnedBy {
  readonly entryId: string;
  readonly kind: string;
  readonly name: string;
  readonly versionId: string;
  readonly number: number;
}

/**
 * One version of an entry. Words are kept as the server sent them, for the
 * reason `Standing` gives: an act this build does not know offers nothing.
 */
export interface Version {
  readonly versionId: string;
  readonly number: number;
  readonly standing: string;
  /** Everyone who wrote it, in the order they first did; none only where a migration did. */
  readonly writers: readonly Person[];
  /** Whether a migration started it, which nobody here did. */
  readonly writtenByMigration: boolean;
  /**
   * Absent where it was never put into service. Present with nobody named
   * where a migration put it there, which nobody did.
   */
  readonly approval?: { readonly approver?: Person };
  readonly acts: ReadonlySet<string>;
  /** Every version in service pinning it. */
  readonly pinnedBy: readonly PinnedBy[];
}

/** An entry as the library reads it, which is what every change to it answers with too. */
export interface Entry {
  readonly entryId: string;
  readonly kind: string;
  readonly name: string;
  /** Absent where it says nothing of what it is for. */
  readonly purpose?: string;
  /** Absent where it may be run. `at` is ISO 8601. */
  readonly stopped?: { readonly by: Person; readonly at: string };
  readonly acts: ReadonlySet<string>;
  /** Newest first. */
  readonly versions: readonly Version[];
}

/** What an entry is called and what it is for, which says nothing where it is null. */
export interface Described {
  readonly name: string;
  readonly purpose: string | null;
}

/** The group's entries of one kind, their address handed to `ask` as `atSegment` makes one. */
export function atLibrary<T>(
  groupId: string,
  kind: EntryKind,
  ask: (address: string) => Promise<T>,
): Promise<T> {
  return atSegment(GROUPS, groupId, (group) =>
    ask(`${group}/${SEGMENTS[kind]}`),
  );
}

export function libraryLoader(
  groupId: string,
  kind: EntryKind,
  query: LibraryQuery,
): (cursor: string | null, signal: AbortSignal) => Promise<Page<EntryRow>> {
  return (cursor, signal) =>
    atLibrary(groupId, kind, (library) =>
      pagedLoader(library, rowFrom, query)(cursor, signal),
    );
}

/** An entry of the kind, whose first version is a draft; built member by member. */
export function startEntry(
  groupId: string,
  kind: EntryKind,
  described: Described,
  signal: AbortSignal,
): Promise<Entry> {
  const { name, purpose } = described;
  return atLibrary(groupId, kind, (library) =>
    post(library, { name, purpose }, signal, entryFrom),
  );
}

/** Built member by member; an entry missing any of what it is drawn from is not one this side can show. */
export function entryFrom(body: unknown): Entry | null {
  if (!isUnchecked(body)) {
    return null;
  }
  const { entryId, kind, name } = body;
  const acts = wordsIn(body.acts);
  const versions = listOf(body.versions, versionFrom);
  const purpose = optional(body, "purpose", textFrom);
  const stopped = optional(body, "stopped", stopFrom);
  if (
    typeof entryId !== "string" ||
    typeof kind !== "string" ||
    typeof name !== "string" ||
    acts === null ||
    versions === null ||
    purpose === null ||
    stopped === null
  ) {
    return null;
  }
  return {
    entryId,
    kind,
    name,
    acts: new Set(acts),
    versions,
    ...(purpose === undefined ? {} : { purpose }),
    ...(stopped === undefined ? {} : { stopped }),
  };
}

function rowFrom(row: unknown): EntryRow | null {
  if (!isUnchecked(row)) {
    return null;
  }
  const { entryId, name, submitted, stopped } = row;
  const inService = optional(row, "inService", (number) =>
    Number.isSafeInteger(number) && (number as number) > 0
      ? (number as number)
      : null,
  );
  if (
    typeof entryId !== "string" ||
    typeof name !== "string" ||
    typeof submitted !== "boolean" ||
    typeof stopped !== "boolean" ||
    inService === null
  ) {
    return null;
  }
  const listed = { entryId, name, submitted, stopped };
  return inService === undefined ? listed : { ...listed, inService };
}

/** Nobody wrote it and no migration did is a version nobody can be named for, and not one to show. */
function versionFrom(body: unknown): Version | null {
  if (!isUnchecked(body)) {
    return null;
  }
  const { versionId, number, standing, writtenByMigration } = body;
  const acts = wordsIn(body.acts);
  const writers = listOf(body.writers, personFrom);
  const pinnedBy = listOf(body.pinnedBy, pinFrom);
  const approval = optional(body, "approval", approvalFrom);
  if (
    typeof versionId !== "string" ||
    !Number.isSafeInteger(number) ||
    typeof standing !== "string" ||
    typeof writtenByMigration !== "boolean" ||
    acts === null ||
    writers === null ||
    (writers.length === 0 && !writtenByMigration) ||
    pinnedBy === null ||
    approval === null
  ) {
    return null;
  }
  const version: Version = {
    versionId,
    number: number as number,
    standing,
    writers,
    writtenByMigration,
    acts: new Set(acts),
    pinnedBy,
  };
  return approval === undefined ? version : { ...version, approval };
}

function approvalFrom(body: unknown): { readonly approver?: Person } | null {
  if (!isUnchecked(body)) {
    return null;
  }
  const approver = optional(body, "approver", personFrom);
  if (approver === null) {
    return null;
  }
  return approver === undefined ? {} : { approver };
}

function stopFrom(body: unknown): Entry["stopped"] | null {
  if (!isUnchecked(body) || typeof body.at !== "string") {
    return null;
  }
  const by = personFrom(body.by);
  return by === null ? null : { by, at: body.at };
}

function pinFrom(body: unknown): PinnedBy | null {
  if (!isUnchecked(body)) {
    return null;
  }
  const { entryId, kind, name, versionId, number } = body;
  return typeof entryId === "string" &&
    typeof kind === "string" &&
    typeof name === "string" &&
    typeof versionId === "string" &&
    Number.isSafeInteger(number)
    ? { entryId, kind, name, versionId, number: number as number }
    : null;
}
