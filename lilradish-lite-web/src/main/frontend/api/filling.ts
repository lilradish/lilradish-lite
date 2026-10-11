// A vocabulary, not an endpoint: what any endpoint putting fields to a person to fill in says of them.

import {
  countFrom,
  isUnchecked,
  listOf,
  optional,
  present,
  textFrom,
} from "../lib/request/document";
import { oneLineWithin, showsInProse } from "../lib/text/legibility";
import {
  fileTypeFits,
  MOST_FILE_TYPES,
  textLinesFrom,
  type FieldKind,
  type TextLines,
} from "./declaration";
import { servedAmong, type Problem } from "./problem";

/** Why one filled value does not fit its field, spelt as the server publishes it. */
export type FillReason =
  | "missing"
  | "malformed"
  | "line_break"
  | "too_long"
  | "too_many"
  | "not_a_term"
  | "crlf"
  | "direction_control"
  | "tag"
  | "unusable";

const KNOWN_REASONS = {
  missing: true,
  malformed: true,
  line_break: true,
  too_long: true,
  too_many: true,
  not_a_term: true,
  crlf: true,
  direction_control: true,
  tag: true,
  unusable: true,
} satisfies Record<FillReason, true>;

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

const FILE_ID =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

const VALUE_DOES_NOT_FIT: ReadonlySet<string> = new Set(["VALUE_DOES_NOT_FIT"]);

/** Both are shown isolated: a term may carry a direction mark. */
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
  readonly lines?: TextLines;
  readonly most?: number;
  readonly types?: readonly string[];
  readonly maximumBytes?: number;
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
export type FillValue =
  string | null | FileValue | readonly FillValue[] | FillValues;

export interface FileValue {
  readonly id: string;
  readonly name: string;
  readonly size: number;
}

/** A read may withhold one value within fields or a list without hiding its neighbours. */
export interface WithheldLeaf {
  readonly withheld: true;
}

export type ShownValue =
  | string
  | null
  | FileValue
  | WithheldLeaf
  | readonly ShownValue[]
  | ShownValues;

export interface ShownValues {
  readonly [name: string]: ShownValue;
}

export type FillSubmissionValue =
  | string
  | null
  | { readonly upload: number; readonly name: string }
  | { readonly keep: number }
  | readonly FillSubmissionValue[]
  | FillSubmissionValues;

export interface FillSubmissionValues {
  readonly [name: string]: FillSubmissionValue;
}

export interface FillSubmission {
  readonly values: FillSubmissionValues;
  readonly uploads: readonly File[];
  readonly hasFilePositions: boolean;
}

export function multipartOf(
  document: unknown,
  uploads: readonly File[],
): FormData {
  const body = new FormData();
  body.append("document", JSON.stringify(document));
  uploads.forEach((file, index) => body.append(`upload${index}`, file, "file"));
  return body;
}

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
 * As `readFieldsFrom`, but unknown kinds at any depth refuse a document intended for filling.
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
  const lines = optional(body, "lines", textLinesFrom);
  const most = optional(body, "most", countFrom);
  const types = optional(body, "types", fileTypesFrom);
  const maximumBytes = optional(body, "maximumBytes", countFrom);
  const terms = optional(body, "terms", termsFrom);
  const fields = optional(body, "fields", readFieldsFrom);
  if (
    typeof name !== "string" ||
    typeof kind !== "string" ||
    typeof mustBeGiven !== "boolean" ||
    label === null ||
    help === null ||
    longest === null ||
    lines === null ||
    most === null ||
    types === null ||
    maximumBytes === null ||
    terms === null ||
    fields === null ||
    (Object.hasOwn(KNOWN_KINDS, kind) &&
      ((kind === "text") !== (longest !== undefined) ||
        (kind !== "text" && lines !== undefined) ||
        (kind === "term") !== (terms !== undefined) ||
        (kind === "fields") !== (fields?.length ?? 0) > 0 ||
        (kind === "file") !==
          (types !== undefined &&
            maximumBytes !== undefined &&
            maximumBytes !== null &&
            maximumBytes > 0) ||
        (kind !== "file" &&
          (types !== undefined || maximumBytes !== undefined)) ||
        (kind === "file" && most !== undefined && most !== null && most < 1)))
  ) {
    return null;
  }
  return {
    name,
    kind,
    mustBeGiven,
    ...present({ label, help, longest, lines, most, types, maximumBytes }),
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

/** The total found may exceed the listed places. */
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
  if (Array.isArray(said)) {
    return said.every(isFillValue);
  }
  if (!isUnchecked(said)) {
    return false;
  }
  return typeof said.size === "number"
    ? isFileValue(said)
    : Object.values(said).every(isFillValue);
}

export function isWithheldLeaf(value: unknown): value is WithheldLeaf {
  return (
    isUnchecked(value) &&
    Object.keys(value).length === 1 &&
    value.withheld === true
  );
}

/** Only read-side values may contain a withheld leaf; submissions keep their own narrower shape. */
export function isShownValue(value: unknown): value is ShownValue {
  if (isWithheldLeaf(value)) {
    return true;
  }
  if (value === null || typeof value === "string") {
    return true;
  }
  if (Array.isArray(value)) {
    return value.every(isShownValue);
  }
  if (!isUnchecked(value)) {
    return false;
  }
  return typeof value.size === "number"
    ? isFileValue(value)
    : Object.values(value).every(isShownValue);
}

function fileTypesFrom(body: unknown): string[] | null {
  const types = listOf(body, textFrom);
  return types !== null &&
    types.length > 0 &&
    types.length <= MOST_FILE_TYPES &&
    new Set(types).size === types.length &&
    types.every(fileTypeFits)
    ? types
    : null;
}

export function isFileValue(body: unknown): body is FileValue {
  return (
    isUnchecked(body) &&
    Object.keys(body).length === 3 &&
    typeof body.id === "string" &&
    body.id.length > 0 &&
    typeof body.name === "string" &&
    oneLineWithin(body.name, 255) &&
    showsInProse(body.name) &&
    Number.isSafeInteger(body.size) &&
    (body.size as number) >= 0
  );
}

/** File metadata may become a download link only with the identifier the file route accepts. */
export function isDownloadableFileValue(body: unknown): body is FileValue {
  return isFileValue(body) && FILE_ID.test(body.id);
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
