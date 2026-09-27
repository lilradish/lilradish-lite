// A vocabulary, not an endpoint: what any endpoint putting fields to a person to fill in says of them.

import {
  countFrom,
  isUnchecked,
  listOf,
  optional,
  present,
  textFrom,
} from "../lib/request/document";
import type { FieldKind } from "./declaration";
import { servedAmong, type Problem } from "./problem";

/** Why one filled value does not fit its field, spelt as the server publishes it. */
export type FillReason =
  | "missing"
  | "malformed"
  | "too_long"
  | "too_many"
  | "not_a_term"
  | "crlf"
  | "direction_control"
  | "tag"
  | "unusable";

/** Every reason, closed over the union in both directions. */
const KNOWN_REASONS = {
  missing: true,
  malformed: true,
  too_long: true,
  too_many: true,
  not_a_term: true,
  crlf: true,
  direction_control: true,
  tag: true,
  unusable: true,
} satisfies Record<FillReason, true>;

/** Every kind a field may be, closed as `KNOWN_REASONS` is. */
const KNOWN_KINDS = {
  text: true,
  number: true,
  date: true,
  moment: true,
  yes_no: true,
  term: true,
  fields: true,
} satisfies Record<FieldKind, true>;

const VALUE_DOES_NOT_FIT: ReadonlySet<string> = new Set(["VALUE_DOES_NOT_FIT"]);

/** One term a field offers, with what it means. Both are shown isolated: a term may carry a direction mark. */
interface FillTerm {
  readonly term: string;
  readonly meaning: string;
}

/** What a field of terms offers, in its list's order, and what the list says of choosing among them. */
interface FillTerms {
  readonly terms: readonly FillTerm[];
  readonly note?: string;
}

/**
 * One field as a read shows it, of a kind this build may not know. Of a kind it knows, it says how long exactly
 * where it is text, offers terms exactly where it is a term, and holds fields exactly where it is fields.
 */
export interface ReadField {
  readonly name: string;
  /** Absent where it is read by its name. */
  readonly label?: string;
  readonly help?: string;
  readonly kind: string;
  readonly longest?: number;
  readonly most?: number;
  readonly mustBeGiven: boolean;
  readonly terms?: FillTerms;
  /** In declared order. */
  readonly fields?: readonly ReadField[];
}

/** One field as it is put to whoever fills it in: of a kind this build draws, all the way down. */
export interface FillField extends ReadField {
  readonly kind: FieldKind;
  readonly fields?: readonly FillField[];
}

/**
 * A value as it is sent: every one of one field its text, yes and no written `"true"` and `"false"`, none as
 * null; many as a list, none of them an empty one; fields as an object naming every field they hold.
 */
export type FillValue = string | null | readonly FillValue[] | FillValues;

/** Every field of one level by name. */
export interface FillValues {
  readonly [name: string]: FillValue;
}

/** Where a value stands, from the first level down: a field by its name, or a place among many from nought. */
export type FillPath = readonly (string | number)[];

export interface FillProblem {
  readonly path: FillPath;
  readonly reason: FillReason;
}

/**
 * Built as `readFieldsFrom` builds one; a field of a kind this build cannot draw, at any level, is not one it can
 * put to anybody, so the document is refused.
 */
export function fillFieldFrom(body: unknown): FillField | null {
  const field = readFieldFrom(body);
  return field !== null && drawable(field) ? field : null;
}

/** One level of fields in declared order, as `fillFieldFrom` reads each, and refused as `readFieldsFrom` is. */
export function fillFieldsFrom(body: unknown): FillField[] | null {
  return oneLevel(listOf(body, fillFieldFrom));
}

/**
 * One level of fields in declared order, only to be read: a kind this build does not know is kept as it came.
 * A shape no field has, or two called alike, which could not both be filled, refuses the document.
 */
export function readFieldsFrom(body: unknown): ReadField[] | null {
  return oneLevel(listOf(body, readFieldFrom));
}

function readFieldFrom(body: unknown): ReadField | null {
  if (!isUnchecked(body)) {
    return null;
  }
  const { name, kind, mustBeGiven } = body;
  const label = optional(body, "label", textFrom);
  const help = optional(body, "help", textFrom);
  const longest = optional(body, "longest", countFrom);
  const most = optional(body, "most", countFrom);
  const terms = optional(body, "terms", termsFrom);
  const fields = optional(body, "fields", readFieldsFrom);
  if (
    typeof name !== "string" ||
    typeof kind !== "string" ||
    typeof mustBeGiven !== "boolean" ||
    [label, help, longest, most, terms, fields].includes(null) ||
    (Object.hasOwn(KNOWN_KINDS, kind) &&
      ((kind === "text") !== (longest !== undefined) ||
        (kind === "term") !== (terms !== undefined) ||
        (kind === "fields") !== (fields?.length ?? 0) > 0))
  ) {
    return null;
  }
  return {
    name,
    kind,
    mustBeGiven,
    ...present({ label, help, longest, most }),
    ...present({ terms, fields }),
  };
}

function oneLevel<F extends ReadField>(fields: F[] | null): F[] | null {
  return fields === null ||
    new Set(fields.map(({ name }) => name)).size !== fields.length
    ? null
    : fields;
}

function drawable(field: ReadField): field is FillField {
  return (
    Object.hasOwn(KNOWN_KINDS, field.kind) &&
    (field.fields ?? []).every(drawable)
  );
}

/** What a refusal of values names: the places it lists, and how many it found in all, which may be more. */
export interface FillProblemsNamed {
  readonly listed: readonly FillProblem[];
  readonly found: number;
}

/**
 * Every value a refusal of values lists, where it stands and why, in the order listed, and how many it found;
 * null for any other refusal, for one whose places cannot all be read, and for one finding fewer than it lists.
 */
export function fillProblemsIn(problem: Problem): FillProblemsNamed | null {
  if (!servedAmong(problem, VALUE_DOES_NOT_FIT)) {
    return null;
  }
  const listed = listOf(problem.extensions?.problems, fillProblemFrom);
  const found = countFrom(problem.extensions?.problemsFound);
  return listed === null ||
    listed.length === 0 ||
    found === null ||
    found < listed.length
    ? null
    : { listed, found };
}

/**
 * Whether what arrived holds values as `FillValue` has them, all the way down. Whether each fits the field it is
 * of is the reader's to judge, where a value shaped as none of its field's is said so rather than refused.
 */
export function isFillValue(said: unknown): said is FillValue {
  if (said === null || typeof said === "string") {
    return true;
  }
  return Array.isArray(said)
    ? said.every(isFillValue)
    : isUnchecked(said) && Object.values(said).every(isFillValue);
}

/** Where two paths name one place, as a key a place is looked up by. */
export function pathKey(path: FillPath): string {
  return JSON.stringify(path);
}

function fillProblemFrom(body: unknown): FillProblem | null {
  if (!isUnchecked(body)) {
    return null;
  }
  const { path, reason } = body;
  if (
    !Array.isArray(path) ||
    typeof path[0] !== "string" ||
    !path.every(isStep) ||
    typeof reason !== "string" ||
    !Object.hasOwn(KNOWN_REASONS, reason)
  ) {
    return null;
  }
  return { path: path as FillPath, reason: reason as FillReason };
}

function isStep(step: unknown): boolean {
  return (
    typeof step === "string" ||
    (Number.isSafeInteger(step) && (step as number) >= 0)
  );
}

function termsFrom(body: unknown): FillTerms | null {
  if (!isUnchecked(body)) {
    return null;
  }
  const terms = listOf(body.terms, termFrom);
  const note = optional(body, "note", textFrom);
  if (terms === null || terms.length === 0 || note === null) {
    return null;
  }
  return note === undefined ? { terms } : { terms, note };
}

function termFrom(body: unknown): FillTerm | null {
  return isUnchecked(body) &&
    typeof body.term === "string" &&
    typeof body.meaning === "string"
    ? { term: body.term, meaning: body.meaning }
    : null;
}
