import {
  countFrom,
  isUnchecked,
  listOf,
  optional,
  present,
  textFrom,
} from "../lib/request/document";
import type { VersionStanding } from "./groups/{groupId}/{kind}";

/**
 * What a field may be, spelt the way the server spells it, and held level
 * with the server's list for the reason `SurfaceAct` gives.
 */
export type FieldKind =
  "text" | "number" | "date" | "moment" | "yes_no" | "term" | "fields";

/** What lets a value stand without review, spelt and held level as `FieldKind` is. */
export type FieldStanding = "always" | "never" | "above_confidence";

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
} satisfies Record<FieldKind, true>;

/** Every standing, closed over the union in both directions. */
const KNOWN_STANDINGS = {
  always: true,
  never: true,
  above_confidence: true,
} satisfies Record<FieldStanding, true>;

/** One version of a reference list, by the number a member reads it by. */
export interface ListVersion {
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
  readonly newer?: { readonly versionId: string; readonly number: number };
}

/**
 * One field as the server reads it. Whatever a draft has not chosen yet is
 * absent, and so is whatever its kind, its half or its depth does not take.
 */
export interface DeclaredField {
  /** The key it is stored under, which changes each time its half is written. */
  readonly fieldId: string;
  readonly name: string;
  readonly label?: string;
  readonly help?: string;
  readonly kind: FieldKind;
  readonly longest?: number;
  readonly list?: PinnedList;
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
  readonly list?: string | null;
  readonly fields?: readonly SentField[];
  readonly mustBeGiven: boolean;
  readonly stands?: FieldStanding | null;
  readonly floor?: number | null;
}

/**
 * One field as a model is told it when it is asked: never a label, a help or
 * the figure a value stands above.
 */
export interface AddedField {
  readonly name: string;
  readonly kind: FieldKind;
  readonly longest?: number;
  readonly many: boolean;
  readonly most?: number;
  /** Whether it is told it may not give nothing. */
  readonly mustBeGiven: boolean;
  readonly terms?: readonly {
    readonly term: string;
    readonly meaning: string;
  }[];
  readonly note?: string;
  /** Whether it is asked how sure its maker was; never above what. */
  readonly confidence: boolean;
  readonly fields?: readonly AddedField[];
}

const KINDS: ReadonlySet<string> = new Set(Object.keys(KNOWN_KINDS));

const STANDINGS: ReadonlySet<string> = new Set(Object.keys(KNOWN_STANDINGS));

/**
 * Built member by member; a field of a kind or a standing this build does not
 * know is not one it can draw or send back, so the document is refused.
 */
export function declaredFrom(body: unknown): DeclaredField | null {
  if (!isUnchecked(body)) {
    return null;
  }
  const { fieldId, name, kind, many, mustBeGiven } = body;
  const label = optional(body, "label", textFrom);
  const help = optional(body, "help", textFrom);
  const longest = optional(body, "longest", countFrom);
  const list = optional(body, "list", pinnedFrom);
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
    [label, help, longest, list, most, stands, floor].includes(null) ||
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
    ...present({ label, help, longest, list, most, stands }),
    ...present({ floor, fields }),
  };
}

/** Built member by member, as `declaredFrom` builds a field. */
export function addedFrom(body: unknown): AddedField | null {
  if (!isUnchecked(body)) {
    return null;
  }
  const { name, kind, many, mustBeGiven, confidence } = body;
  const longest = optional(body, "longest", countFrom);
  const most = optional(body, "most", countFrom);
  const note = optional(body, "note", textFrom);
  const terms = optional(body, "terms", (listed) => listOf(listed, termFrom));
  const fields = optional(body, "fields", (held) => listOf(held, addedFrom));
  if (
    typeof name !== "string" ||
    typeof kind !== "string" ||
    !KINDS.has(kind) ||
    typeof many !== "boolean" ||
    typeof mustBeGiven !== "boolean" ||
    typeof confidence !== "boolean" ||
    [longest, most, note, terms, fields].includes(null)
  ) {
    return null;
  }
  return {
    name,
    kind: kind as FieldKind,
    many,
    mustBeGiven,
    confidence,
    ...present({ longest, most, note }),
    ...present({ terms, fields }),
  };
}

export function listVersionFrom(body: unknown): ListVersion | null {
  if (!isUnchecked(body)) {
    return null;
  }
  const { name, versionId, number } = body;
  return typeof name === "string" &&
    typeof versionId === "string" &&
    Number.isSafeInteger(number)
    ? { name, versionId, number: number as number }
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
    Number.isSafeInteger(said.number)
      ? { versionId: said.versionId, number: said.number as number }
      : null,
  );
  if (typeof body.standing !== "string" || newer === null) {
    return null;
  }
  const pinned = { ...listed, standing: body.standing };
  return newer === undefined ? pinned : { ...pinned, newer };
}

function termFrom(body: unknown): { term: string; meaning: string } | null {
  return isUnchecked(body) &&
    typeof body.term === "string" &&
    typeof body.meaning === "string"
    ? { term: body.term, meaning: body.meaning }
    : null;
}

function standingFrom(said: unknown): FieldStanding | null {
  return typeof said === "string" && STANDINGS.has(said)
    ? (said as FieldStanding)
    : null;
}
