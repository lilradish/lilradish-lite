import { atSegment } from "../../../../../lib/request/address";
import {
  isUnchecked,
  listOf,
  optional,
  present,
  textFrom,
} from "../../../../../lib/request/document";
import { postNothing } from "../../../../../lib/request/http";
import { servedAmong, type Problem } from "../../../../problem";
import { entryFrom, type Entry, type EntryKind } from "../../{kind}";
import { atEntry } from "../{entryId}";

const PINS_RETIRED: ReadonlySet<string> = new Set(["VERSION_PINS_RETIRED"]);

const CONTENT_REFUSED: ReadonlySet<string> = new Set([
  "VERSION_CONTENT_DOES_NOT_HOLD",
]);

/**
 * What is wrong with a place in a version's content, spelt the way the server
 * spells it, and held level with the server's list for the reason `SurfaceAct` gives.
 */
export type ContentProblemCode =
  | "instruction_missing"
  | "nothing_given_back"
  | "name_repeated"
  | "longest_missing"
  | "list_missing"
  | "most_missing"
  | "limit_past_largest"
  | "no_fields_held"
  | "standing_missing"
  | "floor_missing"
  | "no_steps"
  | "step_name_repeated"
  | "runs_missing"
  | "pin_elsewhere"
  | "code_step_not_published"
  | "code_step_not_declared"
  | "code_step_takes_and_gives_nothing"
  | "code_step_list_missing"
  | "code_step_list_not_yet_in_service"
  | "code_step_list_retired"
  | "producer_missing"
  | "producer_not_held"
  | "producer_mode_not_offered"
  | "tries_missing"
  | "reviewer_not_held"
  | "reviewer_mode_not_offered"
  | "model_gives_nothing"
  | "discriminator_missing"
  | "discriminator_not_term"
  | "no_cases"
  | "case_target_missing"
  | "case_not_offered"
  | "case_repeated"
  | "case_gives_otherwise"
  | "input_unbound"
  | "target_unknown"
  | "target_bound_twice"
  | "pointer_into_many"
  | "source_unknown"
  | "source_not_earlier"
  | "source_does_not_fit"
  | "source_may_be_empty"
  | "constant_does_not_fit"
  | "constant_too_long"
  | "constant_conceals"
  | "output_unbound"
  | "output_not_from_step"
  | "helper_missing"
  | "helper_not_held"
  | "helper_mode_not_offered"
  | "no_terms"
  | "term_repeated"
  | "asking_past_largest"
  | "takes_past_largest"
  | "code_step_review_past_largest";

/** Which part of a version's content a problem is in, spelt and held level as `ContentProblemCode` is. */
export type ContentPart =
  "instruction" | "takes" | "gives" | "steps" | "helper" | "terms" | "asking";

/**
 * One place a submission was refused for, or a reading finds, words kept as the server sent them: one this
 * build has none for is still named, and said generally. Each key is present only where the place names it.
 */
export interface ContentProblem {
  readonly code: string;
  readonly part: string;
  /**
   * A field's key: of the version's own halves, or of a route's, where no step is named; an input of what the
   * named step, or its named case, leads to where one is. Changes each time what holds it is written.
   */
  readonly fieldId?: string;
  /** The term's key where a term is the place, which it keeps until it is removed. */
  readonly termId?: string;
  readonly stepId?: string;
  readonly caseId?: string;
  readonly bindingId?: string;
  /** How far past its bound a size runs, only for a problem of size. */
  readonly excess?: number;
}

/** One version of an entry, by the number a member reads it by. */
export interface NumberedVersion {
  readonly versionId: string;
  readonly number: number;
}

/**
 * A version a submission or an approval was refused for pinning, retired
 * since: the entry by kind and name, and the newest of it in service where
 * one is, which is what would take its place.
 */
export interface RetiredPin {
  readonly entryId: string;
  readonly kind: string;
  readonly name: string;
  readonly pinned: NumberedVersion;
  readonly newestInService?: NumberedVersion;
}

/**
 * The pins retired since a refusal names, beside its places for a content refusal, or null where it names
 * none or not all can be read.
 */
export function retiredPinsIn(problem: Problem): RetiredPin[] | null {
  if (servedAmong(problem, PINS_RETIRED)) {
    return listOf(problem.extensions?.pins, retiredPinFrom);
  }
  if (!servedAmong(problem, CONTENT_REFUSED)) {
    return null;
  }
  const pins = problem.extensions?.pins;
  const named = pins === undefined ? null : listOf(pins, retiredPinFrom);
  return named === null || named.length === 0 ? null : named;
}

/**
 * Every place a refusal for what a version holds names, in the order it names
 * them, or null for any other refusal and for one whose places cannot all be read.
 */
export function contentProblemsIn(problem: Problem): ContentProblem[] | null {
  if (!servedAmong(problem, CONTENT_REFUSED)) {
    return null;
  }
  const places = listOf(problem.extensions?.problems, contentProblemFrom);
  return places === null || places.length === 0 ? null : places;
}

/** Built member by member; a key or a figure that arrived and cannot be read refuses the whole place. */
export function contentProblemFrom(body: unknown): ContentProblem | null {
  if (!isUnchecked(body)) {
    return null;
  }
  const { code, part } = body;
  const keys = {
    fieldId: optional(body, "fieldId", textFrom),
    termId: optional(body, "termId", textFrom),
    stepId: optional(body, "stepId", textFrom),
    caseId: optional(body, "caseId", textFrom),
    bindingId: optional(body, "bindingId", textFrom),
    excess: optional(body, "excess", excessFrom),
  };
  if (
    typeof code !== "string" ||
    typeof part !== "string" ||
    Object.values(keys).includes(null)
  ) {
    return null;
  }
  return { code, part, ...present(keys) };
}

/** A whole number above none, kept as it arrived: one past a double's exact integers is still read, rounded. */
function excessFrom(said: unknown): number | null {
  return typeof said === "number" && Number.isInteger(said) && said > 0
    ? said
    : null;
}

function retiredPinFrom(body: unknown): RetiredPin | null {
  if (!isUnchecked(body)) {
    return null;
  }
  const { entryId, kind, name } = body;
  const pinned = numberedFrom(body.pinned);
  const newest = optional(body, "newestInService", numberedFrom);
  if (
    typeof entryId !== "string" ||
    typeof kind !== "string" ||
    typeof name !== "string" ||
    pinned === null ||
    newest === null
  ) {
    return null;
  }
  const pin = { entryId, kind, name, pinned };
  return newest === undefined ? pin : { ...pin, newestInService: newest };
}

function numberedFrom(body: unknown): NumberedVersion | null {
  return isUnchecked(body) &&
    typeof body.versionId === "string" &&
    Number.isSafeInteger(body.number)
    ? { versionId: body.versionId, number: body.number as number }
    : null;
}

/** The entry's versions' address handed to `ask`. */
function atVersions<T>(
  groupId: string,
  kind: EntryKind,
  entryId: string,
  ask: (address: string) => Promise<T>,
): Promise<T> {
  return atEntry(groupId, kind, entryId, (entry) => ask(`${entry}/versions`));
}

/** One version's address handed to `ask`, each identifier escaped as `atSegment` escapes one. */
export function atVersion<T>(
  groupId: string,
  kind: EntryKind,
  entryId: string,
  versionId: string,
  ask: (address: string) => Promise<T>,
): Promise<T> {
  return atVersions(groupId, kind, entryId, (versions) =>
    atSegment(versions, versionId, ask),
  );
}

/** The entry's next version, a draft; answered with the entry, the draft newest in it. */
export function startDraft(
  groupId: string,
  kind: EntryKind,
  entryId: string,
  signal: AbortSignal,
): Promise<Entry> {
  return atVersions(groupId, kind, entryId, (versions) =>
    postNothing(versions, signal, entryFrom),
  );
}
