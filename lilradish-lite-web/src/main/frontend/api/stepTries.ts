import {
  allMembersRead,
  countFrom,
  flagFrom,
  severalFrom,
  trueFrom,
  isUnchecked,
  listOf,
  optional,
  present,
  textFrom,
} from "../lib/request/document";
import { isDownloadableFileValue } from "./filling";
import { heldFrom, type HeldStep } from "./conversationEvents";
import { digitsFrom, spendFrom, type Spend } from "./runFacts";
import { personFrom, type Person } from "./person";
import { shownOrKeptFrom, type ShownOrKept } from "./shownValues";
import { whoFrom, type Who } from "./stepProducersAndReviewers";

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

/** A later step reading the field, or the workflow output it is read into, by its field names joined by dots. */
export type ReadBy = { readonly step: string } | { readonly output: string };

/**
 * One time a model turned a call away, which spent no try and cost nothing. `said` is what the model said, with
 * whether it was cut to its bound; `withheld` stands in for it where the reader may not read what a model said.
 */
export interface Turnaway {
  /** ISO 8601. */
  readonly at: string;
  readonly said?: string;
  readonly cut?: boolean;
  readonly withheld?: true;
  readonly sentAgain: boolean;
}

/** No spend where it calls no model: it cost nothing, which is not a count of nought. */
export interface Cost {
  readonly spend?: Spend;
}

/** What went wrong in code, or what a model's call said went wrong, which is withheld where models are not read. */
export type WentWrong =
  | { readonly detail: string; readonly cut: boolean }
  | { readonly withheld: true };

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

export type TriedValue = ShownOrKept & {
  readonly field: string;
  readonly now: string;
  /** A whole-number confidence from 0 to 100; only where asked, never where the model's words are withheld. */
  readonly confidence?: number;
  readonly decision?: Decision;
};

/** `withheld` says words were refused with that the reader may not read, where a model said them. */
interface Decision {
  readonly outcome: string;
  readonly why?: string;
  readonly whyLines?: "several";
  readonly withheld?: true;
  readonly lengthUnits?: string;
  readonly excessUnits?: string;
  readonly lengthHeld?: HeldStep;
}

export interface Searched {
  readonly searchedFor?: string;
  readonly found?: string;
}

export type SearchSaid =
  | { readonly withheld: true }
  | {
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

export interface KeptCodeFile {
  readonly path: string;
  readonly id: string;
  readonly name: string;
  readonly size: number;
}

export interface DeclinedText {
  readonly text: string;
  readonly altered: boolean;
  readonly truncated: boolean;
}

/** Evidence kept before a model service response ended or was stopped as unreadable. */
export interface UnreadableResponse extends DeclinedText {
  readonly stopped: boolean;
}

export type Declined =
  | { readonly withheld: true }
  | {
      readonly words: DeclinedText & { readonly lines: "several" };
      readonly content?: DeclinedText;
    }
  | {
      readonly words?: DeclinedText & { readonly lines: "several" };
      readonly content: DeclinedText;
    };

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
    readonly declinedPresent: boolean;
    readonly unreadableResponsePresent: boolean;
    readonly modelReplyPresent: boolean;
  };
  readonly ended: string;
  /** Why a model's answer did not fit, kept as it came, a spelling unknown here included. */
  readonly didNotFit?: string;
  readonly wentWrong?: WentWrong;
  readonly wentWrongPresent: boolean;
  readonly erroredFor?: ErroredFor;
  readonly returnedPresent: boolean;
  readonly declinedPresent: boolean;
  readonly unreadableResponsePresent: boolean;
  readonly modelReplyPresent: boolean;
  /** Files held by this run even though this code try ended errored. */
  readonly keptFiles?: readonly KeptCodeFile[];
  /** Each time a call to produce it was turned away, oldest first. */
  readonly turnedAway?: readonly Turnaway[];
  readonly cost: Cost;
  readonly search?: { readonly counted?: SearchesCounted };
}

/** Original evidence of one admitted number, kept apart from the step's summary. */
export interface TryDetails {
  readonly number: number;
  readonly wentWrong?: WentWrong;
  readonly returned?: string;
  readonly declined?: Declined;
  readonly unreadableResponse?: UnreadableResponse;
  readonly modelReply?: DeclinedText;
  readonly review?: {
    readonly declined?: Declined;
    readonly unreadableResponse?: UnreadableResponse;
    readonly modelReply?: DeclinedText;
  };
  readonly search?: TriedSearch;
}

export function costFrom(body: unknown): Cost | null {
  if (!isUnchecked(body) || typeof body.callsAModel !== "boolean") {
    return null;
  }
  if (!body.callsAModel) {
    return {};
  }
  const spend = spendFrom(body);
  return spend === null ? null : { spend };
}

export function turnawayFrom(body: unknown): Turnaway | null {
  if (
    !isUnchecked(body) ||
    typeof body.at !== "string" ||
    typeof body.sentAgain !== "boolean"
  ) {
    return null;
  }
  const { at, sentAgain } = body;
  const withheld = optional(body, "withheld", trueFrom);
  if (withheld !== undefined) {
    return withheld === null || "said" in body || "cut" in body
      ? null
      : { at, withheld, sentAgain };
  }
  if (!("said" in body)) {
    return "cut" in body ? null : { at, sentAgain };
  }
  return typeof body.said === "string" && typeof body.cut === "boolean"
    ? { at, said: body.said, cut: body.cut, sentAgain }
    : null;
}

export function tryFrom(body: unknown): Try | null {
  if (!isUnchecked(body) || !isUnchecked(body.review)) {
    return null;
  }
  const review = body.review;
  if (
    ["returned", "declined", "unreadableResponse", "modelReply"].some(
      (member) => member in body,
    ) ||
    ["declined", "unreadableResponse", "modelReply"].some(
      (member) => member in review,
    )
  ) {
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
  const reviewDeclinedPresent = flagFrom(body.review.declinedPresent);
  const reviewUnreadableResponsePresent = flagFrom(
    body.review.unreadableResponsePresent,
  );
  const reviewModelReplyPresent = flagFrom(body.review.modelReplyPresent);
  const didNotFit = optional(body, "didNotFit", textFrom);
  const declinedPresent = flagFrom(body.declinedPresent);
  const unreadableResponsePresent = flagFrom(body.unreadableResponsePresent);
  const modelReplyPresent = flagFrom(body.modelReplyPresent);
  const wentWrong = optional(body, "wentWrong", wentWrongFrom);
  const wentWrongPresent = flagFrom(body.wentWrongPresent);
  const erroredFor = optional(body, "erroredFor", erroredForFrom);
  const returnedPresent = flagFrom(body.returnedPresent);
  const keptFiles = optional(body, "keptFiles", (listed) =>
    listOf(listed, keptCodeFileFrom),
  );
  const turnedAway = optional(body, "turnedAway", (listed) =>
    listOf(listed, turnawayFrom),
  );
  const cost = costFrom(body.cost);
  const search = optional(body, "search", searchCountFrom);
  const reviewMembers = {
    by,
    wentWrong: reviewWentWrong,
    lost: reviewLost,
    didNotFit: reviewDidNotFit,
  };
  const members = {
    askedBy,
    why,
    didNotFit,
    wentWrong,
    erroredFor,
    keptFiles,
    turnedAway,
    search,
  };
  if (
    typeof ended !== "string" ||
    number === null ||
    beyond === null ||
    producedBy === null ||
    (producedBy.kind === "code" && wentWrong !== undefined) ||
    values === null ||
    asked === null ||
    reviewDeclinedPresent === null ||
    reviewUnreadableResponsePresent === null ||
    reviewModelReplyPresent === null ||
    declinedPresent === null ||
    unreadableResponsePresent === null ||
    modelReplyPresent === null ||
    wentWrongPresent === null ||
    returnedPresent === null ||
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
      declinedPresent: reviewDeclinedPresent,
      unreadableResponsePresent: reviewUnreadableResponsePresent,
      modelReplyPresent: reviewModelReplyPresent,
      ...present(reviewMembers),
    },
    ended,
    declinedPresent,
    unreadableResponsePresent,
    modelReplyPresent,
    wentWrongPresent,
    returnedPresent,
    cost,
    ...present(members),
  };
}

export function tryDetailsFrom(body: unknown): TryDetails | null {
  if (
    !isUnchecked(body) ||
    Object.keys(body).some(
      (member) =>
        ![
          "number",
          "wentWrong",
          "returned",
          "declined",
          "unreadableResponse",
          "modelReply",
          "review",
          "search",
        ].includes(member),
    )
  ) {
    return null;
  }
  const number = countFrom(body.number);
  const members = {
    wentWrong: optional(body, "wentWrong", (wrong) =>
      isUnchecked(wrong) &&
      Object.keys(wrong).length === 2 &&
      typeof wrong.detail === "string" &&
      typeof wrong.cut === "boolean"
        ? { detail: wrong.detail, cut: wrong.cut }
        : null,
    ),
    returned: optional(body, "returned", textFrom),
    declined: optional(body, "declined", declinedFrom),
    unreadableResponse: optional(
      body,
      "unreadableResponse",
      unreadableResponseFrom,
    ),
    modelReply: optional(body, "modelReply", modelReplyFrom),
    review: optional(body, "review", reviewDetailsFrom),
    search: optional(body, "search", triedSearchFrom),
  };
  return number === null ||
    number < 1 ||
    number > 999_999_999 ||
    !allMembersRead(members) ||
    (members.declined !== undefined &&
      members.declined !== null &&
      "content" in members.declined &&
      members.declined.content !== undefined &&
      members.modelReply !== undefined)
    ? null
    : { number, ...present(members) };
}

function modelReplyFrom(body: unknown): DeclinedText | null {
  return isUnchecked(body) &&
    Object.keys(body).length === 3 &&
    typeof body.text === "string" &&
    typeof body.altered === "boolean" &&
    typeof body.truncated === "boolean"
    ? { text: body.text, altered: body.altered, truncated: body.truncated }
    : null;
}

function reviewDetailsFrom(
  body: unknown,
): NonNullable<TryDetails["review"]> | null {
  if (
    !isUnchecked(body) ||
    Object.keys(body).some(
      (member) =>
        !["declined", "unreadableResponse", "modelReply"].includes(member),
    )
  ) {
    return null;
  }
  const members = {
    declined: optional(body, "declined", declinedFrom),
    unreadableResponse: optional(
      body,
      "unreadableResponse",
      unreadableResponseFrom,
    ),
    modelReply: optional(body, "modelReply", modelReplyFrom),
  };
  return allMembersRead(members) &&
    !(
      members.declined !== undefined &&
      members.declined !== null &&
      "content" in members.declined &&
      members.declined.content !== undefined &&
      members.modelReply !== undefined
    )
    ? present(members)
    : null;
}

/** The summary keeps counts and prices, with no question or search records to load. */
function searchCountFrom(body: unknown): NonNullable<Try["search"]> | null {
  if (
    !isUnchecked(body) ||
    Object.keys(body).some(
      (member) => !["uses", "usesCounted", "fee", "noFee"].includes(member),
    )
  ) {
    return null;
  }
  const uses = optional(body, "uses", digitsFrom);
  const usesCounted = optional<SearchesCounted["usesCounted"]>(
    body,
    "usesCounted",
    (said) => (said === "said" || said === "at_most" ? said : null),
  );
  const fee = optional(body, "fee", searchFeeFrom);
  const noFee = optional(body, "noFee", noSearchFeeFrom);
  if (
    uses === null ||
    usesCounted === null ||
    fee === null ||
    noFee === null ||
    (uses !== undefined) !== (usesCounted !== undefined) ||
    (fee !== undefined && noFee !== undefined) ||
    (uses !== undefined) !== (fee !== undefined || noFee !== undefined)
  ) {
    return null;
  }
  if (uses === undefined) {
    return {};
  }
  if (usesCounted === undefined) {
    return null;
  }
  return fee !== undefined
    ? { counted: { uses, usesCounted, fee } }
    : noFee !== undefined
      ? { counted: { uses, usesCounted, noFee } }
      : null;
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
    keys.some(
      (key) => key !== "words" && key !== "content" && key !== "wordsLines",
    )
  ) {
    return null;
  }
  const words = optional(body, "words", declinedTextFrom);
  const content = optional(body, "content", declinedTextFrom);
  const wordsLines = optional(body, "wordsLines", severalFrom);
  if (
    words === null ||
    content === null ||
    wordsLines === null ||
    (words !== undefined) !== (wordsLines !== undefined)
  ) {
    return null;
  }
  return words === undefined
    ? { content: content! }
    : content === undefined
      ? { words: { ...words, lines: wordsLines! } }
      : { words: { ...words, lines: wordsLines! }, content };
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

function unreadableResponseFrom(body: unknown): UnreadableResponse | null {
  return isUnchecked(body) &&
    Object.keys(body).length === 4 &&
    typeof body.text === "string" &&
    body.text.length > 0 &&
    typeof body.altered === "boolean" &&
    typeof body.truncated === "boolean" &&
    typeof body.stopped === "boolean"
    ? {
        text: body.text,
        altered: body.altered,
        truncated: body.truncated,
        stopped: body.stopped,
      }
    : null;
}

function triedSearchFrom(body: unknown): TriedSearch | null {
  if (!isUnchecked(body) || "question" in body || "lines" in body) {
    return null;
  }
  const withheld = optional(body, "withheld", trueFrom);
  const searches = optional(body, "searches", (listed) =>
    listOf(listed, searchSaidFrom),
  );
  const searchesUnsaid = optional(body, "searchesUnsaid", trueFrom);
  const searchesCut = optional(body, "searchesCut", trueFrom);
  const searchesNotAsItCame = optional(body, "searchesNotAsItCame", trueFrom);
  const searchesLeftOut = optional(body, "searchesLeftOut", (said) =>
    digitsFrom(said) === null || said === "0" ? null : (said as string),
  );
  const counted = searchCountFrom(
    Object.fromEntries(
      Object.entries(body).filter(([member]) =>
        ["uses", "usesCounted", "fee", "noFee"].includes(member),
      ),
    ),
  );
  const records = {
    searches,
    searchesUnsaid,
    searchesCut,
    searchesNotAsItCame,
    searchesLeftOut,
  };
  if (
    !allMembersRead(records) ||
    withheld === null ||
    counted === null ||
    (withheld !== undefined &&
      Object.values(records).some((value) => value !== undefined)) ||
    (searches != null &&
      (searches.length === 0 || searchesUnsaid !== undefined)) ||
    (searchesUnsaid !== undefined &&
      (searchesCut !== undefined || searchesLeftOut !== undefined))
  ) {
    return null;
  }
  const said: SearchSaid =
    withheld !== undefined
      ? { withheld: withheld! }
      : {
          ...present({
            searches: records.searches,
            searchesUnsaid: records.searchesUnsaid,
            searchesCut: records.searchesCut,
            searchesNotAsItCame: records.searchesNotAsItCame,
            searchesLeftOut: records.searchesLeftOut,
          }),
        };
  return { said, ...counted };
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
  if (
    !isUnchecked(body) ||
    Object.keys(body).length !== 4 ||
    typeof body.path !== "string"
  ) {
    return null;
  }
  const file = {
    id: body.id,
    name: body.name,
    size: body.size,
  };
  return isDownloadableFileValue(file) ? { path: body.path, ...file } : null;
}

export function triedValueFrom(body: unknown): TriedValue | null {
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

function confidenceFrom(said: unknown): number | null {
  const percent = countFrom(said);
  return percent !== null && percent >= 0 && percent <= 100 ? percent : null;
}

function decisionFrom(body: unknown): Decision | null {
  if (!isUnchecked(body) || typeof body.outcome !== "string") {
    return null;
  }
  const why = optional(body, "why", textFrom);
  const whyLines = optional(body, "whyLines", severalFrom);
  const withheld = optional(body, "withheld", trueFrom);
  const lengthUnits = optional(body, "lengthUnits", digitsFrom);
  const excessUnits = optional(body, "excessUnits", digitsFrom);
  const lengthHeld = optional(body, "lengthHeld", heldFrom);
  if (
    why === null ||
    whyLines === null ||
    withheld === null ||
    lengthUnits === null ||
    excessUnits === null ||
    lengthHeld === null ||
    (lengthUnits !== undefined) !== (excessUnits !== undefined) ||
    (lengthUnits !== undefined) !== (lengthHeld !== undefined) ||
    (lengthUnits !== undefined &&
      (body.outcome !== "refused" || excessUnits === "0")) ||
    (why !== undefined) !== (whyLines !== undefined) ||
    (withheld !== undefined && why !== undefined)
  ) {
    return null;
  }
  return {
    outcome: body.outcome,
    ...present({
      why,
      whyLines,
      withheld,
      lengthUnits,
      excessUnits,
      lengthHeld,
    }),
  };
}

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

export function readByFrom(body: unknown): ReadBy | null {
  if (!isUnchecked(body)) {
    return null;
  }
  const { step, output } = body;
  if (typeof step === "string" && !("output" in body)) {
    return { step };
  }
  return typeof output === "string" && !("step" in body) ? { output } : null;
}
