import { atSegment } from "../../../../../../lib/request/address";
import {
  allMembersRead,
  countFrom,
  isUnchecked,
  listOf,
  optional,
  present,
  textFrom,
} from "../../../../../../lib/request/document";
import { get } from "../../../../../../lib/request/http";
import {
  fillFieldsFrom,
  isDownloadableFileValue,
  readFieldsFrom,
  type FillField,
} from "../../../../../filling";
import { personFrom, type Person } from "../../../../../person";
import { atRun, DIGITS, type NumberedRun } from "../../{runId}";
import {
  costFrom,
  declarationsFrom,
  flagFrom,
  readByFrom,
  trueFrom,
  rereadFrom,
  runHeaderFrom,
  shownOrKeptFrom,
  stepRowFrom,
  turnawayFrom,
  wentInFrom,
  whoFrom,
  type Cost,
  type Declared,
  type ReadBy,
  type RunHeader,
  type ShownOrKept,
  type StepRow,
  type Turnaway,
  type WentIn,
  type Who,
} from "../steps";

/** Where a value stands within its try, spelt and held level as `RunState` is; none only of what came out. */
export type Standing =
  "stands" | "waiting_on_review" | "refused" | "refused_for_length" | "none";

/** How a try ended, spelt and held level as `Standing` is. */
export type Ended =
  | "open"
  | "stands"
  | "refused_on_review"
  | "refused_for_length"
  | "waiting"
  | "did_not_fit"
  | "errored"
  | "nothing_came_back";

/** Why a model's answer did not fit, spelt and held level as `Standing` is. */
export type DidNotFitReason =
  | "not_the_shape"
  | "model_declined"
  | "field_missing"
  | "field_unknown"
  | "nothing_given"
  | "not_its_kind"
  | "line_break"
  | "too_long"
  | "too_many"
  | "not_a_term"
  | "unkeepable"
  | "too_long_to_keep"
  | "confidence_missing"
  | "confidence_unasked"
  | "confidence_not_a_percent"
  | "undecided"
  | "words_missing"
  | "words_too_long"
  | "words_unkeepable"
  | "cut_off"
  | "not_kept_as_it_came";

/** What went wrong in code, or what a model's call said went wrong, which is withheld where models are not read. */
export type WentWrong =
  | { readonly detail: string; readonly cut: boolean }
  | { readonly withheld: true };

/** Why this system ended a try of code as gone wrong, spelt and held level as `Standing` is. */
export type ErroredForReason =
  | "gave_nothing"
  | "not_declared"
  | "nothing_given"
  | "not_its_kind"
  | "line_break"
  | "too_long"
  | "too_many"
  | "not_a_term"
  | "unkeepable"
  | "too_long_to_keep"
  | "file_type_unusable"
  | "file_name_unusable"
  | "file_too_large"
  | "file_total_too_large"
  | "takes_a_list_not_here"
  | "gives_a_list_not_here"
  | "takes_otherwise"
  | "gives_otherwise"
  | "failed_on_this_side"
  | "said_nothing";

/** Why code went wrong where this system found it so, rather than the code saying; kept as it came, as `didNotFit` is. */
export interface ErroredFor {
  readonly reason: string;
  /** The code step's field it is about, its names joined by dots. */
  readonly field?: string;
  /** A member nothing declares, as the code named it: it may be empty, and memberCut alone says whether it was cut. */
  readonly member?: string;
  readonly memberCut?: true;
  readonly readBy?: ReadBy;
  readonly returnedNotKept?: true;
}

export interface CameOut {
  readonly field: string;
  /** Only where it stands, in the try it stands in. */
  readonly standing?: ShownOrKept;
  readonly fromRun?: NumberedRun;
  readonly now: string;
}

export type TriedValue = ShownOrKept & {
  readonly field: string;
  readonly now: string;
  /** How sure a model was of it, from 0 to 100; only where it was asked, and never where what it said is withheld. */
  readonly confidence?: number;
  readonly decision?: Decision;
};

/** `withheld` says words were refused with that the reader may not read, where a model said them. */
interface Decision {
  readonly outcome: string;
  readonly why?: string;
  readonly withheld?: true;
}

export interface Searched {
  readonly searchedFor?: string;
  readonly found?: string;
}

export type SearchSaid =
  | { readonly withheld: true }
  | {
      readonly question: string;
      readonly searches?: readonly Searched[];
      readonly searchesUnsaid?: true;
      readonly searchesCut?: true;
      readonly searchesNotAsItCame?: true;
      readonly searchesLeftOut?: string;
    };

export interface SearchFee {
  readonly currency: string;
  readonly perUse: string;
  readonly amount: string;
}

export type NoSearchFee =
  | { readonly reason: "not_in_currency"; readonly currency: string }
  | { readonly reason: "many_currencies" | "no_price" };

export type SearchesCounted = {
  readonly uses: string;
  readonly usesCounted: "said" | "at_most";
} & ({ readonly fee: SearchFee } | { readonly noFee: NoSearchFee });

export interface TriedSearch {
  readonly said: SearchSaid;
  readonly counted?: SearchesCounted;
}

export type SearchQuestion =
  | { readonly text: string; readonly sent: boolean }
  | { readonly withheld: true };

export interface KeptCodeFile {
  readonly path: string;
  readonly id: string;
  readonly name: string;
  readonly type: string;
  readonly size: number;
}

export interface DeclinedText {
  readonly text: string;
  readonly altered: boolean;
  readonly truncated: boolean;
}

export type Declined =
  | { readonly withheld: true }
  | { readonly words: DeclinedText; readonly content?: DeclinedText }
  | { readonly words?: DeclinedText; readonly content: DeclinedText };

export interface Try {
  readonly number: number;
  readonly beyond: boolean;
  /** Absent where the system asked. */
  readonly askedBy?: Person;
  readonly producedBy: Who;
  readonly why?: string;
  readonly values: readonly TriedValue[];
  readonly review: {
    readonly asked: boolean;
    readonly by?: Who;
    readonly wentWrong?: boolean;
    readonly lost?: string;
    readonly didNotFit?: string;
    readonly declined?: Declined;
    readonly unreadableResponse?: DeclinedText;
  };
  readonly ended: string;
  /** Why a model's answer did not fit, kept as it came, a spelling unknown here included. */
  readonly didNotFit?: string;
  readonly declined?: Declined;
  readonly unreadableResponse?: DeclinedText;
  readonly wentWrong?: WentWrong;
  readonly erroredFor?: ErroredFor;
  /** What code gave back that did not fit, as it came back. */
  readonly returned?: string;
  /** Files held by this run even though this code try ended errored. */
  readonly keptFiles?: readonly KeptCodeFile[];
  /** Each time a call to produce it was turned away, oldest first. */
  readonly turnedAway?: readonly Turnaway[];
  readonly cost: Cost;
  readonly search?: TriedSearch;
}

/** What answering the step here puts to the reader: the try an answer fills, and a control to each field it gives. */
export interface Answering {
  readonly searchQuestion?: SearchQuestion;
  readonly number: number;
  readonly beyond: boolean;
  /** What the question tells whoever answers it; a code step tells nothing. */
  readonly instruction?: string;
  /** For a code step, as its release declares them now. */
  readonly gives: readonly FillField[];
  /** The try refused before it, each value with the words it was refused with. */
  readonly lastRefused?: {
    readonly number: number;
    readonly values: readonly TriedValue[];
  };
}

export interface StepAnswer {
  readonly run: RunHeader;
  readonly declarations: ReadonlyMap<string, Declared>;
  readonly step: StepRow;
  readonly wentIn?: readonly WentIn[];
  readonly wentInFrom?: string;
  readonly cameOut?: readonly CameOut[];
  /** Oldest first. */
  readonly triesMade: readonly Try[];
  /** Only where the reader may answer it, and this build can draw every field it gives. */
  readonly answering?: Answering;
  /** How the question the step pins lays out what it takes, as its version holds it; only where it says. */
  readonly drawing?: string;
  /** Only while the run is running; never under a second, which would read without pause. */
  readonly rereadAfterSeconds?: number;
}

export function readStep(
  groupId: string,
  runId: string,
  stepId: string,
  signal: AbortSignal,
): Promise<StepAnswer> {
  return atRun(groupId, runId, (run) =>
    atSegment(`${run}/steps`, stepId, (step) =>
      get(step, signal, stepAnswerFrom),
    ),
  );
}

export function stepAnswerFrom(body: unknown): StepAnswer | null {
  if (!isUnchecked(body)) {
    return null;
  }
  const run = runHeaderFrom(body.run);
  const declarations = declarationsFrom(body.declarations);
  const step = stepRowFrom(body.step);
  const wentIn = optional(body, "wentIn", (listed) =>
    listOf(listed, wentInFrom),
  );
  const wentInFromSaid = optional(body, "wentInFrom", textFrom);
  const cameOut = optional(body, "cameOut", (listed) =>
    listOf(listed, cameOutFrom),
  );
  const triesMade = listOf(body.triesMade, tryFrom);
  const answering = optional(body, "answering", answeringFrom);
  const drawing = optional(body, "drawing", (said) =>
    said === "" ? null : textFrom(said),
  );
  const rereadAfterSeconds = rereadFrom(body);
  const members = {
    wentIn,
    wentInFrom: wentInFromSaid,
    cameOut,
    answering,
    drawing,
    rereadAfterSeconds,
  };
  if (
    run === null ||
    declarations === null ||
    step === null ||
    triesMade === null ||
    (triesMade !== null &&
      triesMade.some(
        (tried) =>
          tried.search !== undefined &&
          (step?.runs.kind !== "search" || tried.producedBy.kind !== "model"),
      )) ||
    (answering != null &&
      answering.searchQuestion !== undefined &&
      step?.runs.kind !== "search") ||
    !allMembersRead(members)
  ) {
    return null;
  }
  return {
    run,
    declarations,
    step,
    triesMade,
    ...present(members),
  };
}

function cameOutFrom(body: unknown): CameOut | null {
  if (
    !isUnchecked(body) ||
    typeof body.field !== "string" ||
    typeof body.now !== "string"
  ) {
    return null;
  }
  const standing = optional(body, "standing", (said) =>
    isUnchecked(said) ? shownOrKeptFrom(said) : null,
  );
  const fromRun = optional(body, "fromRun", numberedRunFrom);
  if (standing === null || fromRun === null) {
    return null;
  }
  return {
    field: body.field,
    now: body.now,
    ...present({ standing, fromRun }),
  };
}

function numberedRunFrom(body: unknown): NumberedRun | null {
  if (!isUnchecked(body) || typeof body.runId !== "string") {
    return null;
  }
  const number = countFrom(body.number);
  return number === null ? null : { runId: body.runId, number };
}

function tryFrom(body: unknown): Try | null {
  if (!isUnchecked(body) || !isUnchecked(body.review)) {
    return null;
  }
  const { ended } = body;
  const number = countFrom(body.number);
  const beyond = flagFrom(body.beyond);
  const askedBy = optional(body, "askedBy", personFrom);
  const producedBy = whoFrom(body.producedBy);
  const why = optional(body, "why", textFrom);
  const values = listOf(body.values, triedValueFrom);
  const asked = flagFrom(body.review.asked);
  const by = optional(body.review, "by", whoFrom);
  const reviewWentWrong = optional(body.review, "wentWrong", flagFrom);
  const reviewLost = optional(body.review, "lost", textFrom);
  const reviewDidNotFit = optional(body.review, "didNotFit", textFrom);
  const reviewDeclined = optional(body.review, "declined", declinedFrom);
  const reviewUnreadableResponse = optional(
    body.review,
    "unreadableResponse",
    declinedTextFrom,
  );
  const didNotFit = optional(body, "didNotFit", textFrom);
  const declined = optional(body, "declined", declinedFrom);
  const unreadableResponse = optional(
    body,
    "unreadableResponse",
    declinedTextFrom,
  );
  const wentWrong = optional(body, "wentWrong", wentWrongFrom);
  const erroredFor = optional(body, "erroredFor", erroredForFrom);
  const returned = optional(body, "returned", textFrom);
  const keptFiles = optional(body, "keptFiles", (listed) =>
    listOf(listed, keptCodeFileFrom),
  );
  const turnedAway = optional(body, "turnedAway", (listed) =>
    listOf(listed, turnawayFrom),
  );
  const cost = costFrom(body.cost);
  const search = optional(body, "search", triedSearchFrom);
  const reviewMembers = {
    by,
    wentWrong: reviewWentWrong,
    lost: reviewLost,
    didNotFit: reviewDidNotFit,
    declined: reviewDeclined,
    unreadableResponse: reviewUnreadableResponse,
  };
  const members = {
    askedBy,
    why,
    didNotFit,
    declined,
    unreadableResponse,
    wentWrong,
    erroredFor,
    returned,
    keptFiles,
    turnedAway,
    search,
  };
  if (
    typeof ended !== "string" ||
    number === null ||
    beyond === null ||
    producedBy === null ||
    values === null ||
    asked === null ||
    cost === null ||
    !allMembersRead(reviewMembers) ||
    !allMembersRead(members)
  ) {
    return null;
  }
  return {
    number,
    beyond,
    producedBy,
    values,
    review: {
      asked,
      ...present(reviewMembers),
    },
    ended,
    cost,
    ...present(members),
  };
}

function declinedFrom(body: unknown): Declined | null {
  if (!isUnchecked(body)) {
    return null;
  }
  const keys = Object.keys(body);
  if ("withheld" in body) {
    return keys.length === 1 && body.withheld === true
      ? { withheld: true }
      : null;
  }
  if (
    keys.length === 0 ||
    keys.some((key) => key !== "words" && key !== "content")
  ) {
    return null;
  }
  const words = optional(body, "words", declinedTextFrom);
  const content = optional(body, "content", declinedTextFrom);
  if (words === null || content === null) {
    return null;
  }
  return words === undefined
    ? { content: content! }
    : content === undefined
      ? { words }
      : { words, content };
}

function declinedTextFrom(body: unknown): DeclinedText | null {
  return isUnchecked(body) &&
    Object.keys(body).length === 3 &&
    typeof body.text === "string" &&
    body.text.length > 0 &&
    typeof body.altered === "boolean" &&
    typeof body.truncated === "boolean"
    ? {
        text: body.text,
        altered: body.altered,
        truncated: body.truncated,
      }
    : null;
}

function triedSearchFrom(body: unknown): TriedSearch | null {
  if (!isUnchecked(body)) {
    return null;
  }
  const withheld = optional(body, "withheld", trueFrom);
  const question = optional(body, "question", textFrom);
  const searches = optional(body, "searches", (listed) =>
    listOf(listed, searchSaidFrom),
  );
  const searchesUnsaid = optional(body, "searchesUnsaid", trueFrom);
  const searchesCut = optional(body, "searchesCut", trueFrom);
  const searchesNotAsItCame = optional(body, "searchesNotAsItCame", trueFrom);
  const searchesLeftOut = optional(body, "searchesLeftOut", (said) =>
    digitsFrom(said) === null || said === "0" ? null : (said as string),
  );
  const uses = optional(body, "uses", digitsFrom);
  const usesCounted = optional<SearchesCounted["usesCounted"]>(
    body,
    "usesCounted",
    (said) => (said === "said" || said === "at_most" ? said : null),
  );
  const fee = optional(body, "fee", searchFeeFrom);
  const noFee = optional(body, "noFee", noSearchFeeFrom);
  const records = {
    question,
    searches,
    searchesUnsaid,
    searchesCut,
    searchesNotAsItCame,
    searchesLeftOut,
  };
  if (
    !allMembersRead(records) ||
    [withheld, uses, usesCounted, fee, noFee].includes(null) ||
    (withheld === undefined && question === undefined) ||
    (withheld !== undefined &&
      Object.values(records).some((value) => value !== undefined)) ||
    (searches != null &&
      (searches.length === 0 || searchesUnsaid !== undefined)) ||
    (searchesUnsaid !== undefined &&
      (searchesCut !== undefined || searchesLeftOut !== undefined)) ||
    (uses !== undefined) !== (usesCounted !== undefined) ||
    (fee !== undefined && noFee !== undefined) ||
    (uses !== undefined) !== (fee !== undefined || noFee !== undefined)
  ) {
    return null;
  }
  const said: SearchSaid =
    withheld !== undefined
      ? { withheld: withheld! }
      : {
          question: question!,
          ...present({
            searches: records.searches,
            searchesUnsaid: records.searchesUnsaid,
            searchesCut: records.searchesCut,
            searchesNotAsItCame: records.searchesNotAsItCame,
            searchesLeftOut: records.searchesLeftOut,
          }),
        };
  if (uses === undefined) {
    return { said };
  }
  const counted: SearchesCounted =
    fee !== undefined
      ? { uses: uses!, usesCounted: usesCounted!, fee: fee! }
      : { uses: uses!, usesCounted: usesCounted!, noFee: noFee! };
  return { said, counted };
}

function searchSaidFrom(body: unknown): Searched | null {
  if (!isUnchecked(body)) {
    return null;
  }
  const searchedFor = optional(body, "searchedFor", textFrom);
  const found = optional(body, "found", textFrom);
  return searchedFor === null ||
    found === null ||
    (searchedFor === undefined && found === undefined)
    ? null
    : { ...present({ searchedFor, found }) };
}

function digitsFrom(said: unknown): string | null {
  return typeof said === "string" && DIGITS.test(said) ? said : null;
}

function decimalFrom(said: unknown): string | null {
  return typeof said === "string" && /^(0|[1-9][0-9]*)(\.[0-9]+)?$/.test(said)
    ? said
    : null;
}

function currencyFrom(said: unknown): string | null {
  return typeof said === "string" && /^[A-Z]{3}$/.test(said) ? said : null;
}

function searchFeeFrom(body: unknown): SearchFee | null {
  if (!isUnchecked(body)) {
    return null;
  }
  const currency = currencyFrom(body.currency);
  const perUse = decimalFrom(body.perUse);
  const amount = decimalFrom(body.amount);
  return currency === null || perUse === null || amount === null
    ? null
    : { currency, perUse, amount };
}

function noSearchFeeFrom(body: unknown): NoSearchFee | null {
  if (!isUnchecked(body)) {
    return null;
  }
  if (body.reason === "not_in_currency") {
    const currency = currencyFrom(body.currency);
    return currency === null ? null : { reason: body.reason, currency };
  }
  return (body.reason === "many_currencies" || body.reason === "no_price") &&
    !("currency" in body)
    ? { reason: body.reason }
    : null;
}

function keptCodeFileFrom(body: unknown): KeptCodeFile | null {
  if (!isUnchecked(body) || typeof body.path !== "string") {
    return null;
  }
  const file = {
    id: body.id,
    name: body.name,
    type: body.type,
    size: body.size,
  };
  return isDownloadableFileValue(file) ? { path: body.path, ...file } : null;
}

function triedValueFrom(body: unknown): TriedValue | null {
  if (
    !isUnchecked(body) ||
    typeof body.field !== "string" ||
    typeof body.now !== "string"
  ) {
    return null;
  }
  const shown = shownOrKeptFrom(body, [
    "field",
    "now",
    "confidence",
    "decision",
  ]);
  const confidence = optional(body, "confidence", confidenceFrom);
  const decision = optional(body, "decision", decisionFrom);
  if (shown === null || confidence === null || decision === null) {
    return null;
  }
  return {
    field: body.field,
    now: body.now,
    ...shown,
    ...present({ confidence, decision }),
  };
}

/** A whole number from 0 to 100, the one scale a model says how sure it is on. */
function confidenceFrom(said: unknown): number | null {
  const percent = countFrom(said);
  return percent !== null && percent >= 0 && percent <= 100 ? percent : null;
}

function decisionFrom(body: unknown): Decision | null {
  if (!isUnchecked(body) || typeof body.outcome !== "string") {
    return null;
  }
  const why = optional(body, "why", textFrom);
  const withheld = optional(body, "withheld", trueFrom);
  if (why === null || withheld === null) {
    return null;
  }
  return { outcome: body.outcome, ...present({ why, withheld }) };
}

/** Said with whether it was cut, or withheld with neither: exactly one of the two. */
function wentWrongFrom(body: unknown): WentWrong | null {
  if (!isUnchecked(body)) {
    return null;
  }
  if ("withheld" in body) {
    return body.withheld === true && !("detail" in body) && !("cut" in body)
      ? { withheld: true }
      : null;
  }
  return typeof body.detail === "string" && typeof body.cut === "boolean"
    ? { detail: body.detail, cut: body.cut }
    : null;
}

function erroredForFrom(body: unknown): ErroredFor | null {
  if (!isUnchecked(body) || typeof body.reason !== "string") {
    return null;
  }
  const field = optional(body, "field", textFrom);
  const member = optional(body, "member", textFrom);
  const memberCut = optional(body, "memberCut", trueFrom);
  const readBy = optional(body, "readBy", readByFrom);
  const returnedNotKept = optional(body, "returnedNotKept", trueFrom);
  const members = { field, member, memberCut, readBy, returnedNotKept };
  if (!allMembersRead(members)) {
    return null;
  }
  return {
    reason: body.reason,
    ...present(members),
  };
}

/**
 * None where a field it gives is one this build cannot draw, as an answer here fills every one of them, so the
 * answer is not offered; the rest of the page is read all the same.
 */
function answeringFrom(body: unknown): Answering | undefined | null {
  if (!isUnchecked(body)) {
    return null;
  }
  const number = countFrom(body.number);
  const beyond = flagFrom(body.beyond);
  const instruction = optional(body, "instruction", textFrom);
  const searchQuestion = optional(body, "searchQuestion", textFrom);
  const searchQuestionSent = optional(body, "searchQuestionSent", flagFrom);
  const searchQuestionWithheld = optional(
    body,
    "searchQuestionWithheld",
    trueFrom,
  );
  const lastRefused = optional(body, "lastRefused", lastRefusedFrom);
  const members = { instruction, lastRefused };
  if (
    number === null ||
    beyond === null ||
    readFieldsFrom(body.gives) === null ||
    !allMembersRead(members) ||
    [searchQuestion, searchQuestionSent, searchQuestionWithheld].includes(
      null,
    ) ||
    (searchQuestion !== undefined) !== (searchQuestionSent !== undefined) ||
    (searchQuestionWithheld !== undefined && searchQuestion !== undefined)
  ) {
    return null;
  }
  const gives = fillFieldsFrom(body.gives);
  const asked: SearchQuestion | undefined =
    searchQuestionWithheld !== undefined
      ? { withheld: searchQuestionWithheld! }
      : searchQuestion === undefined
        ? undefined
        : { text: searchQuestion!, sent: searchQuestionSent! };
  return gives === null
    ? undefined
    : {
        number,
        beyond,
        gives,
        ...present({ ...members, searchQuestion: asked }),
      };
}

function lastRefusedFrom(body: unknown): Answering["lastRefused"] | null {
  if (!isUnchecked(body)) {
    return null;
  }
  const number = countFrom(body.number);
  const values = listOf(body.values, triedValueFrom);
  return number === null || values === null ? null : { number, values };
}
