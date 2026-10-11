import {
  countFrom,
  isUnchecked,
  listOf,
  optional,
  present,
  textFrom,
} from "../lib/request/document";
import type { VersionStanding } from "./groups/{groupId}/{kind}";
import { isVersionName } from "./groups/{groupId}/{kind}";

/**
 * Field kinds use the server's spelling, held level as `SurfaceAct` is.
 */
export type FieldKind =
  "text" | "number" | "date" | "moment" | "yes_no" | "term" | "fields" | "file";

/** What lets a value stand without review, spelt and held level as `FieldKind` is. */
export type FieldStanding = "always" | "never" | "above_confidence";

export type TextLines = "one" | "several";

/** The two halves of a declaration, each addressed by its spelling, held level as `FieldKind` is. */
export type DeclarationSide = "takes" | "gives";

/** Every kind, closed over the union in both directions. */
const KNOWN_KINDS = {
  text: true,
  number: true,
  date: true,
  moment: true,
  yes_no: true,
  term: true,
  fields: true,
  file: true,
} satisfies Record<FieldKind, true>;

/** Every standing, closed over the union in both directions. */
const KNOWN_STANDINGS = {
  always: true,
  never: true,
  above_confidence: true,
} satisfies Record<FieldStanding, true>;

/** One version of a reference list, by the number a member reads it by. */
export interface ListVersion {
  readonly versionName: string | null;
  /** Shown isolated wherever it is shown, for the reason `EntryRow` gives. */
  readonly name: string;
  readonly versionId: string;
  readonly number: number;
}

/**
 * The list a field of terms pins, and the newest of that list in service where
 * that is another. A standing this build does not know is kept as it arrived.
 */
export interface PinnedList extends ListVersion {
  readonly standing: VersionStanding | (string & {});
  readonly newer?: {
    readonly versionId: string;
    readonly number: number;
    readonly versionName: string | null;
  };
}

/**
 * Whatever a draft has not chosen yet is absent, as is whatever its kind,
 * half or depth does not take.
 */
export interface DeclaredField {
  /** The key it is stored under; a full rewrite of its declaration half replaces it. */
  readonly fieldId: string;
  readonly name: string;
  readonly label?: string;
  readonly help?: string;
  readonly kind: FieldKind;
  readonly longest?: number;
  readonly lines?: TextLines;
  readonly list?: PinnedList;
  readonly types?: readonly string[];
  readonly maximumBytes?: number;
  readonly many: boolean;
  readonly most?: number;
  readonly mustBeGiven: boolean;
  /** Only on a value given back that no field holds. */
  readonly stands?: FieldStanding;
  readonly floor?: number;
  /** Only on a field holding fields. */
  readonly fields?: readonly DeclaredField[];
}

/**
 * One field as it is sent: every member its kind, half and depth take, and no
 * other, what is not chosen yet sent as null.
 */
export interface SentField {
  /** The key it was read under, which keeps a pin it holds; none for a field added. */
  readonly fieldId: string | null;
  readonly name: string;
  readonly label: string | null;
  readonly help: string | null;
  readonly kind: FieldKind;
  readonly many: boolean;
  readonly most: number | null;
  readonly longest?: number | null;
  readonly lines?: TextLines;
  readonly list?: string | null;
  readonly types?: readonly string[];
  readonly maximumBytes?: number | null;
  readonly fields?: readonly SentField[];
  readonly mustBeGiven: boolean;
  readonly stands?: FieldStanding | null;
  readonly floor?: number | null;
}

const KINDS: ReadonlySet<string> = new Set(Object.keys(KNOWN_KINDS));

const STANDINGS: ReadonlySet<string> = new Set(Object.keys(KNOWN_STANDINGS));

/**
 * An unknown kind or standing cannot be drawn or sent back, so refuses the document.
 */
export function declaredFrom(body: unknown): DeclaredField | null {
  if (!isUnchecked(body)) {
    return null;
  }
  const { fieldId, name, kind, many, mustBeGiven } = body;
  const label = optional(body, "label", textFrom);
  const help = optional(body, "help", textFrom);
  const longest = optional(body, "longest", countFrom);
  const lines = optional(body, "lines", textLinesFrom);
  const list = optional(body, "list", pinnedFrom);
  const types = optional(body, "types", fileTypesFrom);
  const maximumBytes = optional(body, "maximumBytes", countFrom);
  const most = optional(body, "most", countFrom);
  const stands = optional(body, "stands", standingFrom);
  const floor = optional(body, "floor", countFrom);
  const fields = optional(body, "fields", (held) => listOf(held, declaredFrom));
  if (
    typeof fieldId !== "string" ||
    typeof name !== "string" ||
    typeof kind !== "string" ||
    !KINDS.has(kind) ||
    typeof many !== "boolean" ||
    typeof mustBeGiven !== "boolean" ||
    label === null ||
    help === null ||
    longest === null ||
    lines === null ||
    list === null ||
    types === null ||
    maximumBytes === null ||
    most === null ||
    stands === null ||
    floor === null ||
    (kind !== "text" && lines !== undefined) ||
    (kind !== "file" && (types !== undefined || maximumBytes !== undefined)) ||
    (kind === "file" &&
      (types === undefined ||
        (maximumBytes !== undefined &&
          (maximumBytes === null || maximumBytes < 1)))) ||
    fields === null
  ) {
    return null;
  }
  return {
    fieldId,
    name,
    kind: kind as FieldKind,
    many,
    mustBeGiven,
    ...present({
      label,
      help,
      longest,
      lines,
      list,
      types,
      maximumBytes,
      most,
      stands,
    }),
    ...present({ floor, fields }),
  };
}

export function textLinesFrom(value: unknown): TextLines | null {
  return value === "one" || value === "several" ? value : null;
}

export function listVersionFrom(body: unknown): ListVersion | null {
  if (!isUnchecked(body)) {
    return null;
  }
  const { name, versionId, number } = body;
  return typeof name === "string" &&
    typeof versionId === "string" &&
    isVersionName(body.versionName) &&
    Number.isSafeInteger(number) &&
    (number as number) > 0
    ? {
        name,
        versionId,
        number: number as number,
        versionName: body.versionName,
      }
    : null;
}

/** A version something pins, as `PinnedList` reads one; a list's or any other kind's alike. */
export function pinnedFrom(body: unknown): PinnedList | null {
  const listed = listVersionFrom(body);
  if (listed === null || !isUnchecked(body)) {
    return null;
  }
  const newer = optional(body, "newer", (said) =>
    isUnchecked(said) &&
    typeof said.versionId === "string" &&
    isVersionName(said.versionName) &&
    Number.isSafeInteger(said.number) &&
    (said.number as number) > 0
      ? {
          versionId: said.versionId,
          number: said.number as number,
          versionName: said.versionName,
        }
      : null,
  );
  if (typeof body.standing !== "string" || newer === null) {
    return null;
  }
  const pinned = { ...listed, standing: body.standing };
  return newer === undefined ? pinned : { ...pinned, newer };
}

/** Each list's terms in server order, shared by content reads and independent pin details. */
export function termsFrom(
  body: unknown,
): Map<string, readonly string[]> | null {
  if (!isUnchecked(body)) return null;
  const terms = new Map<string, readonly string[]>();
  for (const [list, offered] of Object.entries(body)) {
    const read = listOf(offered, textFrom);
    if (read === null) return null;
    terms.set(list, read);
  }
  return terms;
}

function standingFrom(said: unknown): FieldStanding | null {
  return typeof said === "string" && STANDINGS.has(said)
    ? (said as FieldStanding)
    : null;
}

export const MOST_FILE_TYPES = 64;

const FILE_TYPE = /^\.[a-z0-9]+(?:\.[a-z0-9]+)*$/;

export function fileTypeFits(type: string): boolean {
  return type.length <= 33 && FILE_TYPE.exec(type)?.[0] === type;
}

function fileTypesFrom(body: unknown): readonly string[] | null {
  if (!Array.isArray(body) || body.length > MOST_FILE_TYPES) {
    return null;
  }
  const types = listOf(body, textFrom);
  return types !== null &&
    types.length > 0 &&
    types.every(fileTypeFits) &&
    new Set(types).size === types.length
    ? types
    : null;
}
