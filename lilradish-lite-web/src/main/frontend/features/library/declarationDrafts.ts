import {
  fileTypeFits,
  MOST_FILE_TYPES,
  type DeclaredField,
  FieldKind,
  FieldStanding,
  SentField,
  type TextLines,
} from "../../api/declaration";
import type { MessageId } from "../../i18n/app";
import { fieldNameFits, labelFits, lineFits } from "../../lib/text/legibility";

export type FileSizeUnit = "bytes" | "KB" | "MB" | "GB";

export const FILE_SIZE_UNITS = {
  bytes: 1,
  KB: 1024,
  MB: 1024 ** 2,
  GB: 1024 ** 3,
} as const satisfies Record<FileSizeUnit, number>;

/**
 * One field as typed, "" where nothing is. What its kind or depth does not take is kept, so switching back
 * finds it; only `sentOf` leaves it out.
 */
export interface DraftField {
  /** Which row this is, for as long as the builder holds it; never sent. */
  readonly key: string;
  /** The key the server read it under, sent back so a pin it holds is kept; none for a row added. */
  readonly fieldId: string | null;
  readonly name: string;
  readonly label: string;
  readonly help: string;
  readonly kind: FieldKind;
  readonly longest: string;
  readonly lines: TextLines;
  /** The version a field of terms pins, by its identifier. */
  readonly list: string;
  readonly fileTypes: string;
  readonly fileSize: string;
  readonly fileSizeUnit: FileSizeUnit;
  readonly many: boolean;
  readonly most: string;
  readonly mustBeGiven: boolean;
  readonly stands: FieldStanding | "";
  readonly floor: string;
  readonly fields: readonly DraftField[];
}

/** Where a row is: its place among its fellows at each level, from the first down. */
export type Place = readonly number[];

/**
 * What a field is asked on a level beside whether it must be given, which every field is asked: nothing more,
 * or what it takes to stand. Spelt as the server's `Demands`.
 */
export type DemandKind = "given" | "stands";

/** What the host asks of a field on the first level and below it, paired with the server's by a shared table. */
export interface Demands {
  readonly first: DemandKind;
  readonly below: DemandKind;
}

/** The first thing keeping a half from being sent, and the row it is in. */
export interface Held {
  readonly why: MessageId;
  readonly place: Place;
}

// A limit the server takes: one up to the largest whole number it stores.
const WHOLE = /^[1-9][0-9]*$/;

export const LARGEST_LIMIT = 2147483647;

const HIGHEST_FLOOR = 100;

// Level with the server's Declaration: how deep a field is held, how long the
// names down to it run joined by dots, and how many fields a half holds.
const MOST_LEVELS = 32;

const LONGEST_PATH = 1023;

const MOST_FIELDS = 256;

export function draftOf(field: DeclaredField): DraftField {
  return {
    key: field.fieldId,
    fieldId: field.fieldId,
    name: field.name,
    label: field.label ?? "",
    help: field.help ?? "",
    kind: field.kind,
    longest: field.longest?.toString() ?? "",
    lines: field.lines ?? "several",
    list: field.list?.versionId ?? "",
    fileTypes: field.types?.join("\n") ?? "",
    ...fileSizeOf(field.maximumBytes),
    many: field.many,
    most: field.most?.toString() ?? "",
    mustBeGiven: field.mustBeGiven,
    stands: field.stands ?? "",
    floor: field.floor?.toString() ?? "",
    fields: (field.fields ?? []).map(draftOf),
  };
}

export function freshDraft(key: string): DraftField {
  return {
    key,
    fieldId: null,
    name: "",
    label: "",
    help: "",
    kind: "text",
    longest: "",
    lines: "one",
    list: "",
    fileTypes: "",
    fileSize: "",
    fileSizeUnit: "bytes",
    many: false,
    most: "",
    mustBeGiven: true,
    stands: "",
    floor: "",
    fields: [],
  };
}

export function demandAt(demands: Demands, first: boolean): DemandKind {
  return first ? demands.first : demands.below;
}

/**
 * Exactly the members its kind and depth take, null where nothing is chosen; only of drafts `heldFor`
 * holds nothing against, whose limits are then whole numbers or nothing.
 */
export function sentOf(
  draft: DraftField,
  demands: Demands,
  first = true,
): SentField {
  const demand = demandAt(demands, first);
  return {
    fieldId: draft.fieldId,
    name: draft.name,
    label: draft.label === "" ? null : draft.label,
    help: draft.help === "" ? null : draft.help,
    kind: draft.kind,
    many: draft.many,
    most: draft.many ? numberOf(draft.most) : null,
    ...(draft.kind === "text"
      ? { longest: numberOf(draft.longest), lines: draft.lines }
      : {}),
    ...(draft.kind === "term"
      ? { list: draft.list === "" ? null : draft.list }
      : {}),
    ...(draft.kind === "file"
      ? {
          types: fileTypesOf(draft.fileTypes),
          maximumBytes: fileBytesOf(draft.fileSize, draft.fileSizeUnit),
        }
      : {}),
    ...(draft.kind === "fields"
      ? { fields: draft.fields.map((held) => sentOf(held, demands, false)) }
      : {}),
    mustBeGiven: draft.mustBeGiven,
    ...(demand === "stands"
      ? {
          stands: draft.stands === "" ? null : draft.stands,
          floor:
            draft.stands === "above_confidence" ? numberOf(draft.floor) : null,
        }
      : {}),
  };
}

/**
 * The first thing, in reading order, the server would refuse on arrival, and its row. Only what would be
 * sent is judged; what is only not chosen yet is submitting's to name.
 */
export function heldFor(
  drafts: readonly DraftField[],
  demands: Demands,
): Held | null {
  return heldAmong(drafts, demands, [], 0, { fields: 0 });
}

function heldAmong(
  drafts: readonly DraftField[],
  demands: Demands,
  above: Place,
  pathAbove: number,
  counted: { fields: number },
): Held | null {
  for (const [index, draft] of drafts.entries()) {
    const place = [...above, index];
    counted.fields += 1;
    const path = (pathAbove === 0 ? 0 : pathAbove + 1) + draft.name.length;
    const why =
      place.length > MOST_LEVELS
        ? "refusal.DECLARATION_TOO_DEEP"
        : counted.fields > MOST_FIELDS
          ? "refusal.DECLARATION_TOO_LARGE"
          : (heldForOne(draft, demandAt(demands, above.length === 0)) ??
            (path > LONGEST_PATH ? "refusal.DECLARATION_TOO_DEEP" : null));
    if (why !== null) {
      return { why, place };
    }
    const below =
      draft.kind === "fields"
        ? heldAmong(draft.fields, demands, place, path, counted)
        : null;
    if (below !== null) {
      return below;
    }
  }
  return null;
}

function heldForOne(draft: DraftField, demand: DemandKind): MessageId | null {
  if (!fieldNameFits(draft.name)) {
    return "refusal.FIELD_NAME_UNUSABLE";
  }
  if (draft.label !== "" && !labelFits(draft.label)) {
    return "declaration.labelLimit";
  }
  if (draft.help !== "" && !lineFits(draft.help)) {
    return "declaration.helpLimit";
  }
  if (
    (draft.kind === "text" && !limitFits(draft.longest)) ||
    (draft.many && !limitFits(draft.most))
  ) {
    return "declaration.limit";
  }
  if (draft.kind === "file") {
    if (fileTypesTooMany(draft.fileTypes)) {
      return "refusal.FILE_TYPES_TOO_MANY";
    }
    if (!fileTypesFit(draft.fileTypes)) {
      return "declaration.fileTypesInvalid";
    }
    if (!fileSizeFits(draft.fileSize, draft.fileSizeUnit)) {
      return "refusal.FILE_SIZE_LIMIT_UNUSABLE";
    }
  }
  if (
    demand === "stands" &&
    draft.stands === "above_confidence" &&
    !floorFits(draft.floor)
  ) {
    return "declaration.floorLimit";
  }
  return null;
}

export function fileTypesTooMany(typed: string): boolean {
  let count = 1;
  for (
    let index = typed.indexOf("\n");
    index >= 0;
    index = typed.indexOf("\n", index + 1)
  ) {
    if (++count > MOST_FILE_TYPES) {
      return true;
    }
  }
  return false;
}

export function fileTypesFit(typed: string): boolean {
  if (fileTypesTooMany(typed)) {
    return false;
  }
  const types = fileTypesOf(typed);
  return (
    types.length > 0 &&
    types.every(fileTypeFits) &&
    new Set(types).size === types.length
  );
}

export function fileTypesOf(typed: string): string[] {
  return typed
    .split("\n")
    .map((type) =>
      type.trim().replace(/[A-Z]/g, (letter) => letter.toLowerCase()),
    );
}

export function fileSizeFits(typed: string, unit: FileSizeUnit): boolean {
  return (
    (typed === "" || WHOLE.test(typed)) &&
    Number.isSafeInteger(Number(typed) * FILE_SIZE_UNITS[unit])
  );
}

export function fileBytesOf(typed: string, unit: FileSizeUnit): number | null {
  return typed !== "" && fileSizeFits(typed, unit)
    ? Number(typed) * FILE_SIZE_UNITS[unit]
    : null;
}

export function fileSizeOf(bytes: number | undefined): {
  fileSize: string;
  fileSizeUnit: FileSizeUnit;
} {
  if (bytes === undefined) {
    return { fileSize: "", fileSizeUnit: "bytes" };
  }
  for (const unit of ["GB", "MB", "KB"] as const) {
    if (bytes % FILE_SIZE_UNITS[unit] === 0) {
      return {
        fileSize: String(bytes / FILE_SIZE_UNITS[unit]),
        fileSizeUnit: unit,
      };
    }
  }
  return { fileSize: String(bytes), fileSizeUnit: "bytes" };
}

/** Nothing typed is a limit not chosen yet, which is sent as none. */
export function limitFits(typed: string): boolean {
  return typed === "" || (WHOLE.test(typed) && Number(typed) <= LARGEST_LIMIT);
}

export function floorFits(typed: string): boolean {
  return typed === "" || (WHOLE.test(typed) && Number(typed) <= HIGHEST_FLOOR);
}

function numberOf(typed: string): number | null {
  return typed === "" ? null : Number(typed);
}

export function changedAt(
  drafts: readonly DraftField[],
  place: Place,
  change: (draft: DraftField) => DraftField,
): DraftField[] {
  const [here, ...below] = place;
  return drafts.map((draft, index) =>
    index !== here
      ? draft
      : below.length === 0
        ? change(draft)
        : { ...draft, fields: changedAt(draft.fields, below, change) },
  );
}

export function removedAt(
  drafts: readonly DraftField[],
  place: Place,
): DraftField[] {
  const [here, ...below] = place;
  if (below.length === 0) {
    return drafts.filter((_draft, index) => index !== here);
  }
  return changedAt(drafts, [here!], (draft) => ({
    ...draft,
    fields: removedAt(draft.fields, below),
  }));
}

export function movedAt(
  drafts: readonly DraftField[],
  place: Place,
  by: -1 | 1,
): DraftField[] {
  const [here, ...below] = place;
  if (below.length > 0) {
    return changedAt(drafts, [here!], (draft) => ({
      ...draft,
      fields: movedAt(draft.fields, below, by),
    }));
  }
  const there = here! + by;
  if (there < 0 || there >= drafts.length) {
    return [...drafts];
  }
  const moved = [...drafts];
  [moved[here!], moved[there]] = [moved[there]!, moved[here!]!];
  return moved;
}

export function addedUnder(
  drafts: readonly DraftField[],
  place: Place | null,
  added: DraftField,
): DraftField[] {
  if (place === null) {
    return [...drafts, added];
  }
  return changedAt(drafts, place, (draft) => ({
    ...draft,
    fields: [...draft.fields, added],
  }));
}

/** A whole-half write includes the selected candidate and keeps other stored facts. */
export function candidateFields(
  stored: readonly DraftField[],
  candidates: readonly DraftField[],
  key: string,
): DraftField[] {
  const selected = candidates.find((field) => field.key === key);
  const existing = stored.find((field) => field.key === key);
  if (selected !== undefined || existing !== undefined) {
    const rest = stored.filter((field) => field.key !== key);
    if (selected === undefined) return rest;
    const field = {
      ...selected,
      fields:
        selected.kind === "fields" && existing?.kind === "fields"
          ? existing.fields
          : selected.fields,
    };
    const index = candidates.indexOf(selected);
    rest.splice(Math.min(index, rest.length), 0, field);
    return rest;
  }
  for (const parent of candidates) {
    if (fieldAtKey(parent.fields, key) !== undefined) {
      const previous = stored.find((field) => field.key === parent.key);
      if (previous === undefined) {
        return candidateFields(stored, candidates, parent.key);
      }
      return stored.map((field) =>
        field.key === parent.key
          ? {
              ...field,
              fields: candidateFields(field.fields, parent.fields, key),
            }
          : field,
      );
    }
  }
  return stored.map((field) => ({
    ...field,
    fields: candidateFields(
      field.fields,
      candidates.find((parent) => parent.key === field.key)?.fields ?? [],
      key,
    ),
  }));
}

export function allFields(fields: readonly DraftField[]): DraftField[] {
  return fields.flatMap((field) => [field, ...allFields(field.fields)]);
}

export function fieldAtKey(
  fields: readonly DraftField[],
  key: string,
): DraftField | undefined {
  for (const field of fields) {
    if (field.key === key) return field;
    const found = fieldAtKey(field.fields, key);
    if (found !== undefined) return found;
  }
  return undefined;
}

export function fieldPlace(
  fields: readonly DraftField[],
  key: string,
  above: Place = [],
): Place | null {
  for (const [index, field] of fields.entries()) {
    const place = [...above, index];
    if (field.key === key) return place;
    const below = fieldPlace(field.fields, key, place);
    if (below !== null) return below;
  }
  return null;
}

export function fieldAtPlace(
  fields: readonly DraftField[],
  place: Place,
): DraftField | undefined {
  const [at, ...below] = place;
  const field = fields[at!];
  return below.length === 0 || field === undefined
    ? field
    : fieldAtPlace(field.fields, below);
}

export function withoutField(
  fields: readonly DraftField[],
  key: string,
): DraftField[] {
  const place = fieldPlace(fields, key);
  return place === null ? [...fields] : removedAt(fields, place);
}

export function changedKey(
  fields: readonly DraftField[],
  key: string,
  change: (field: DraftField) => DraftField,
): DraftField[] {
  return fields.map((field) =>
    field.key === key
      ? change(field)
      : field.fields.length === 0
        ? field
        : { ...field, fields: changedKey(field.fields, key, change) },
  );
}

/** Confirmation can give fields new keys; their confirmed sibling names still identify the same subtree. */
export function fieldKeysAfter(
  before: readonly DraftField[],
  after: readonly DraftField[],
): ReadonlyMap<string, string> {
  const keys = new Map<string, string>();
  const match = (
    previous: readonly DraftField[],
    next: readonly DraftField[],
  ) => {
    for (const field of previous) {
      const answered =
        next.find((candidate) => candidate.key === field.key) ??
        next.find((candidate) => candidate.name === field.name);
      if (answered === undefined) continue;
      keys.set(field.key, answered.key);
      if (field.kind === "fields" && answered.kind === "fields")
        match(field.fields, answered.fields);
    }
  };
  match(before, after);
  return keys;
}

/** The response replaces only the candidate sent; other closed editors keep their raw input. */
export function rebaseCandidates(
  before: readonly DraftField[],
  after: readonly DraftField[],
  candidates: readonly DraftField[],
  applied: string,
): DraftField[] {
  const keys = fieldKeysAfter(before, after);
  const answered = new Map(allFields(after).map((field) => [field.key, field]));
  const align = (fields: readonly DraftField[]): DraftField[] =>
    fields.map((field) => {
      const canonical = answered.get(keys.get(field.key) ?? field.key);
      return {
        ...field,
        key: canonical?.key ?? field.key,
        fieldId: canonical?.fieldId ?? field.fieldId,
        fields: align(field.fields),
      };
    });
  const alignedBefore = align(before);
  const alignedCandidates = align(candidates);
  const appliedKey = keys.get(applied) ?? applied;
  const previous = new Map(
    allFields(alignedBefore).map((field) => [field.key, field]),
  );
  const next = new Map(
    allFields(alignedCandidates).map((field) => [field.key, field]),
  );
  const previousPositions = positionsOf(alignedBefore);
  const nextPositions = positionsOf(alignedCandidates);
  // A new ancestor is sent with its whole subtree; the response gives every
  // new node its canonical identity, including nodes not selected directly.
  const committed = new Set(
    allFields(candidateFields(alignedBefore, alignedCandidates, appliedKey))
      .filter((field) => !previous.has(field.key))
      .map((field) => field.key),
  );
  committed.add(appliedKey);
  const dirty = new Set([...previous.keys(), ...next.keys()]);
  let rebased = [...after];
  for (const key of dirty) {
    if (committed.has(key)) continue;
    const a = previous.get(key);
    const b = next.get(key);
    const own = (field: DraftField | undefined) =>
      field === undefined ? undefined : { ...field, fields: undefined };
    const changed = JSON.stringify(own(a)) !== JSON.stringify(own(b));
    const moved =
      a !== undefined &&
      b !== undefined &&
      previousPositions.get(key) !== nextPositions.get(key);
    if (moved || a === undefined || b === undefined) {
      if (changed || moved)
        rebased = candidateFields(rebased, alignedCandidates, key);
    } else if (changed) {
      rebased = changedKey(rebased, key, (field) => ({
        ...b,
        fields:
          b.kind === "fields" && field.kind === "fields"
            ? field.fields
            : b.fields,
      }));
    }
  }
  return rebased;
}

function positionsOf(
  fields: readonly DraftField[],
): ReadonlyMap<string, string> {
  const positions = new Map<string, string>();
  function visit(fields: readonly DraftField[], above: string) {
    for (const [index, field] of fields.entries()) {
      const position = `${above}/${index}`;
      positions.set(field.key, position);
      visit(field.fields, position);
    }
  }
  visit(fields, "");
  return positions;
}
