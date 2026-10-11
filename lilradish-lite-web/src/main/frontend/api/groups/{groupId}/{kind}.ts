import { atSegment } from "../../../lib/request/address";
import { nameFits } from "../../../lib/text/legibility";
import {
  isUnchecked,
  listOf,
  optional,
  textFrom,
} from "../../../lib/request/document";
import { post } from "../../../lib/request/http";
import type { Page } from "../../../lib/request/page";
import { pagedLoader, type ListQuery } from "../../../lib/request/pagedLoader";
import { GROUPS } from "../../groups";
import { personFrom, type Person } from "../../person";

/** Spelt and held level with the server's entry kinds, for the reason `SurfaceAct` gives. */
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
export type LibrarySortColumn = "name" | "inService" | "status";

/** One column, led by a hyphen for descending. */
export type LibraryOrder = LibrarySortColumn | `-${LibrarySortColumn}`;

export type LibraryQuery = ListQuery<LibraryOrder>;

/** Where each kind's entries are, which is where a reader's page of them is too. */
export const SEGMENTS = {
  workflow: "workflows",
  question: "questions",
  reference_list: "reference-lists",
} as const satisfies Record<EntryKind, EntrySegment>;

/** `entryId` addresses the row and is never shown. */
export interface EntryRow {
  readonly versionName: string | null;
  readonly entryId: string;
  /** Shown isolated wherever it is shown: a name may carry a directional control. */
  readonly name: string;
  readonly purpose?: string;
  /** The number of the newest version in service; absent where none is. */
  readonly inService?: number;
  readonly submitted: boolean;
  readonly stopped: boolean;
}

/** A version pinning another, which is in service. */
export interface PinnedBy {
  readonly versionName: string | null;
  readonly entryId: string;
  readonly kind: string;
  readonly name: string;
  readonly versionId: string;
  readonly number: number;
}

/**
 * Words are kept as the server sent them, for the
 * reason `Standing` gives: an act this build does not know offers nothing.
 */
export interface Version {
  readonly versionName: string | null;
  readonly versionId: string;
  readonly number: number;
  readonly standing: string;
  /** Everyone who wrote it, in the order they first did; none only where a migration did. */
  readonly writers: readonly Person[];
  readonly writtenByMigration: boolean;
  /**
   * Absent where it was never put into service. Present with nobody named
   * where a migration put it there, which nobody did.
   */
  readonly approval?: { readonly approver?: Person };
  readonly stateActs: ReadonlySet<string> | null;
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
  readonly stateActs: ReadonlySet<string> | null;
  /** Newest first. */
  readonly versions: readonly Version[];
}

/** A null purpose says nothing of what the entry is for. */
export interface Described {
  readonly name: string;
  readonly purpose: string | null;
}

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

/** Its first version is a draft. */
export function makeEntry(
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
  const stateActs =
    body.stateActs === null ? null : listOf(body.stateActs, textFrom);
  const versions = listOf(body.versions, versionFrom);
  const purpose = optional(body, "purpose", textFrom);
  const stopped = optional(body, "stopped", stopFrom);
  if (
    typeof entryId !== "string" ||
    typeof kind !== "string" ||
    typeof name !== "string" ||
    (stateActs === null && body.stateActs !== null) ||
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
    stateActs: stateActs === null ? null : new Set(stateActs),
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
  const purpose = optional(row, "purpose", textFrom);
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
    !isVersionName(row.versionName) ||
    purpose === null ||
    inService === null
  ) {
    return null;
  }
  const listed = {
    entryId,
    name,
    submitted,
    stopped,
    versionName: row.versionName,
  };
  return {
    ...listed,
    ...(purpose === undefined ? {} : { purpose }),
    ...(inService === undefined ? {} : { inService }),
  };
}

function versionFrom(body: unknown): Version | null {
  if (!isUnchecked(body)) {
    return null;
  }
  const { versionId, number, standing, writtenByMigration } = body;
  const stateActs =
    body.stateActs === null ? null : listOf(body.stateActs, textFrom);
  const writers = listOf(body.writers, personFrom);
  const pinnedBy = listOf(body.pinnedBy, pinFrom);
  const approval = optional(body, "approval", approvalFrom);
  if (
    typeof versionId !== "string" ||
    !Number.isSafeInteger(number) ||
    (number as number) <= 0 ||
    typeof standing !== "string" ||
    typeof writtenByMigration !== "boolean" ||
    !isVersionName(body.versionName) ||
    (stateActs === null && body.stateActs !== null) ||
    writers === null ||
    (writers.length === 0 && !writtenByMigration) ||
    pinnedBy === null ||
    approval === null
  ) {
    return null;
  }
  const version: Version = {
    versionName: body.versionName,
    versionId,
    number: number as number,
    standing,
    writers,
    writtenByMigration,
    stateActs: stateActs === null ? null : new Set(stateActs),
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
    isVersionName(body.versionName) &&
    Number.isSafeInteger(number) &&
    (number as number) > 0
    ? {
        entryId,
        kind,
        name,
        versionId,
        number: number as number,
        versionName: body.versionName,
      }
    : null;
}

/** A mandatory nullable display name; missing, empty or malformed text cannot become an unnamed version. */
export function isVersionName(said: unknown): said is string | null {
  return (
    said === null ||
    (typeof said === "string" && nameFits(said) && [...said].length <= 64)
  );
}

/** Matches only the loaded versions; the server checks their actual group, kind and entry before matching. */
export function matchVersionNames(
  groupId: string,
  kind: EntryKind,
  versions: readonly string[],
  text: string,
  signal: AbortSignal,
): Promise<readonly string[]> {
  return atLibrary(groupId, kind, (library) =>
    post(
      `${library}/version-name-matches`,
      { text, versions },
      signal,
      (body) => (isUnchecked(body) ? listOf(body.versions, textFrom) : null),
    ),
  );
}
