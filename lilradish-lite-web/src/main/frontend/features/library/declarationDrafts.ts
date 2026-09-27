import type {
  DeclaredField,
  FieldKind,
  FieldStanding,
  SentField,
} from "../../api/declaration";
import type { MessageId } from "../../i18n/app";
import { fieldNameFits, labelFits, lineFits } from "../../lib/text/legibility";

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
  /** The version a field of terms pins, by its identifier. */
  readonly list: string;
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

const LARGEST_LIMIT = 2147483647;

const HIGHEST_FLOOR = 100;

// Level with the server's Declaration: how deep a field is held, how long the
// names down to it run joined by dots, and how many fields a half holds.
const MOST_LEVELS = 32;

const LONGEST_PATH = 1023;

const MOST_FIELDS = 256;

/** A field as the server read it, held as a builder writes it. */
export function draftOf(field: DeclaredField): DraftField {
  return {
    key: field.fieldId,
    fieldId: field.fieldId,
    name: field.name,
    label: field.label ?? "",
    help: field.help ?? "",
    kind: field.kind,
    longest: field.longest?.toString() ?? "",
    list: field.list?.versionId ?? "",
    many: field.many,
    most: field.most?.toString() ?? "",
    mustBeGiven: field.mustBeGiven,
    stands: field.stands ?? "",
    floor: field.floor?.toString() ?? "",
    fields: (field.fields ?? []).map(draftOf),
  };
}

/** A field nothing is written in yet: text, holding one, and to be given. */
export function freshDraft(key: string): DraftField {
  return {
    key,
    fieldId: null,
    name: "",
    label: "",
    help: "",
    kind: "text",
    longest: "",
    list: "",
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
    ...(draft.kind === "text" ? { longest: numberOf(draft.longest) } : {}),
    ...(draft.kind === "term"
      ? { list: draft.list === "" ? null : draft.list }
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
  if (
    demand === "stands" &&
    draft.stands === "above_confidence" &&
    !floorFits(draft.floor)
  ) {
    return "declaration.floorLimit";
  }
  return null;
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

/** The half with the row at `place` changed as `change` says, every other row as it was. */
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

/** The half without the row at `place`, and the rows it held with it. */
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

/** The half with the row at `place` swapped with the one `by` rows from it among its fellows; none past either end. */
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

/** The half with `added` last among the rows the row at `place` holds, or last on the first level. */
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
