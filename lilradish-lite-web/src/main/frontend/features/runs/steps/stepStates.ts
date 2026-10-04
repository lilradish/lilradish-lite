import Link from "@mui/material/Link";
import {
  Fragment,
  createElement,
  type ReactElement,
  type ReactNode,
} from "react";
import type { Theme } from "@mui/material/styles";
import { Link as RouterLink } from "react-router";

import type { ReadField } from "../../../api/filling";
import type {
  Declared,
  GivesOtherwise,
  FailureReason,
  HoldReason,
  RunningOn,
  ReviewSending,
  StepAct,
  StepRow,
  StepState,
  StoppedWhat,
  Turnaway,
  WaitsOn,
  Where,
  WhereKind,
  Who,
  WithheldRefusal,
} from "../../../api/groups/{groupId}/runs/{runId}/steps";
import type {
  DidNotFitReason,
  Ended,
  ErroredFor,
  ErroredForReason,
  Standing,
  TriedValue,
  Try,
} from "../../../api/groups/{groupId}/runs/{runId}/steps/{stepId}";
import { say, type MessageId } from "../../../i18n/app";
import { visible } from "../../../i18n/visible";
import { intl } from "../../../i18n/intl";
import { isolatedInText } from "../../../lib/direction/isolated";
import { HiddenShownIn, SystemHint } from "../../../lib/text/HiddenShownIn";
import type { ProseRefusal } from "../../../lib/text/legibility";
import { nameOrNumber } from "../../../lib/text/names";
import { whenText } from "../../../lib/time/When";
import { listQuery, stepHref, WITHIN_THE_RUN } from "../runAddress";
import {
  declaredFor,
  labelAt,
  laterStepSaid,
  outputSaid,
  type Declaring,
} from "./declared";
import { spokenName } from "./stepTitle";

/** The words each state is said in, closed over the states the server works out. */
const STATES = {
  not_started: "step.notStarted",
  running: "step.running",
  held_back: "step.heldBack",
  waiting: "step.waiting",
  failed: "step.failed",
  done: "step.done",
} as const satisfies Record<StepState, MessageId>;

const STANDINGS = {
  stands: "step.stands",
  waiting_on_review: "step.waitsOnReview",
  refused: "step.refusedOnReview",
  refused_for_length: "step.refusedForLength",
  none: "step.nowNone",
} as const satisfies Record<Standing, MessageId>;

const ENDINGS = {
  open: "step.endedOpen",
  stands: "step.stands",
  refused_on_review: "step.refusedOnReview",
  refused_for_length: "step.refusedForLength",
  waiting: "step.waitsOnReview",
  did_not_fit: "step.didNotFit",
  errored: "step.errored",
  nothing_came_back: "step.nothingCameBack",
} as const satisfies Record<Ended, MessageId>;

const REVIEW_SENDING = {
  unsent: "step.reviewUnsent",
  out: "step.reviewOut",
  turned_away: "step.reviewTurnedAway",
  too_long: "step.reviewTooLong",
  list_not_here: "step.reviewListNotHere",
  takes_no_longer_declared: "step.reviewTakesNoLongerDeclared",
  no_longer_declared: "step.reviewNoLongerDeclared",
  holds_a_file: "step.reviewHoldsAFile",
} as const satisfies Record<ReviewSending, MessageId>;

/** Which way a model's answer did not fit, in the reader's words and never as its spelling. */
const MISFITS = {
  not_the_shape: "step.misfitNotTheShape",
  model_declined: "step.misfitModelDeclined",
  field_missing: "step.misfitFieldMissing",
  field_unknown: "step.misfitFieldUnknown",
  nothing_given: "step.misfitNothingGiven",
  not_its_kind: "step.misfitNotItsKind",
  line_break: "step.misfitLineBreak",
  too_long: "step.misfitTooLong",
  too_many: "step.misfitTooMany",
  not_a_term: "step.misfitNotATerm",
  unkeepable: "step.misfitUnkeepable",
  too_long_to_keep: "step.misfitTooLongToKeep",
  confidence_missing: "step.misfitConfidenceMissing",
  confidence_unasked: "step.misfitConfidenceUnasked",
  confidence_not_a_percent: "step.misfitConfidenceNotAPercent",
  undecided: "step.misfitUndecided",
  words_missing: "step.misfitWordsMissing",
  words_too_long: "step.misfitWordsTooLong",
  words_unkeepable: "step.misfitWordsUnkeepable",
  cut_off: "step.misfitCutOff",
  not_kept_as_it_came: "step.misfitNotKeptAsItCame",
} as const satisfies Record<DidNotFitReason, MessageId>;

/** Which way code went wrong where this system found it so, in the reader's words and never as its spelling. */
const ERRORED_FOR = {
  gave_nothing: "step.erroredForGaveNothing",
  not_declared: "step.erroredForNotDeclared",
  nothing_given: "step.erroredForNothingGiven",
  not_its_kind: "step.erroredForNotItsKind",
  line_break: "step.erroredForLineBreak",
  too_long: "step.erroredForTooLong",
  too_many: "step.erroredForTooMany",
  not_a_term: "step.erroredForNotATerm",
  unkeepable: "step.erroredForUnkeepable",
  too_long_to_keep: "step.erroredForTooLongToKeep",
  file_type_unusable: "step.erroredForFileTypeUnusable",
  file_name_unusable: "step.erroredForFileNameUnusable",
  file_too_large: "step.erroredForFileTooLarge",
  file_total_too_large: "step.erroredForFileTotalTooLarge",
  takes_a_list_not_here: "step.erroredForTakesAListNotHere",
  gives_a_list_not_here: "step.erroredForGivesAListNotHere",
  takes_otherwise: "step.erroredForTakesOtherwise",
  gives_otherwise: "step.erroredForGivesOtherwise",
  failed_on_this_side: "step.erroredForFailedOnThisSide",
  said_nothing: "step.erroredForSaidNothing",
} as const satisfies Record<ErroredForReason, MessageId>;

/** The reasons whose sentence names no field of the code step's. */
const NAMING_NO_FIELD: ReadonlySet<string> = new Set([
  "gave_nothing",
  "file_total_too_large",
  "failed_on_this_side",
  "said_nothing",
] satisfies readonly ErroredForReason[]);

/** The reasons about a field of what the code step takes; every other field named is one of what it gives back. */
const ABOUT_WHAT_IT_TAKES: ReadonlySet<string> = new Set([
  "takes_a_list_not_here",
  "takes_otherwise",
] satisfies readonly ErroredForReason[]);

const WAITERS = {
  review_at_gate: "step.waitsOnReviewer",
  model: "step.waitsOnModel",
  answer_step: "step.waitsOnAnswerer",
  starter: "step.waitsOnStarter",
} as const satisfies Record<WaitsOn, MessageId>;

const RUNNING = {
  code: "step.runningCode",
  call: "step.runningCall",
  next_try: "step.runningNextTry",
} as const satisfies Record<RunningOn, MessageId>;

/** What holds a step back, as the thing that went wrong and never as its spelling. */
const HOLDS = {
  entry_stopped: "step.heldEntryStopped",
  too_long: "step.heldTooLong",
  turned_away: "step.heldTurnedAway",
  code_step_not_held: "step.heldCodeStepNotHeld",
  search_way_not_held: "step.heldSearchWayNotHeld",
} as const satisfies Record<HoldReason, MessageId>;

/** A stop holding a step back: the run's own workflow, which holds whatever it runs, or only what it runs. */
const STOPPED = {
  workflow: "step.heldWorkflowStopped",
  entry: "step.heldEntryStopped",
} as const satisfies Record<StoppedWhat, MessageId>;

const FAILURES = {
  tries_spent: "step.failedTriesSpent",
  uncuttable_length: "step.failedUncuttable",
  unclaimed_value: "step.failedUnclaimed",
  model_not_deployed: "step.failedModelNotHeld",
} as const satisfies Record<
  Exclude<
    FailureReason,
    | "too_deep"
    | "tool_not_offered"
    | "source_no_longer_matches"
    | "source_list_not_here"
  >,
  MessageId
>;

/** These failures can be mended by sending; spending every try instead calls for another try. */
const MENDED_BY_SENDING: ReadonlySet<string> = new Set<FailureReason>([
  "uncuttable_length",
  "model_not_deployed",
  "tool_not_offered",
  "source_no_longer_matches",
  "source_list_not_here",
]);

/** An act the reader's roles do not reach; whom it waits on instead is said where the step is. */
const NOT_PERMITTED = {
  answer: "step.mayNotAnswer",
  ask_again: "step.mayNotAskAgain",
  review: "step.mayNotReview",
  try_sending: "step.mayNotTrySending",
} as const satisfies Record<StepAct, MessageId>;

/** Every other refusal an act is withheld for, said as pressing it would be refused. */
const WITHHELD_FOR = {
  RUN_STOPPED: "refusal.RUN_STOPPED",
  ENTRY_STOPPED: "refusal.ENTRY_STOPPED",
  REVIEW_NOT_A_PERSONS: "refusal.REVIEW_NOT_A_PERSONS",
  REVIEW_OWN_PRODUCTION: "step.reviewsOwn",
  ASK_AGAIN_NOT_OFFERED: "refusal.ASK_AGAIN_NOT_OFFERED",
  CODE_STEP_GIVES_OTHERWISE: "refusal.CODE_STEP_GIVES_OTHERWISE",
} as const satisfies Record<
  Exclude<WithheldRefusal, "ACT_NOT_PERMITTED">,
  MessageId
>;

/** What the reader is told of a reason the page refuses, closed over the reasons. */
export const REASON_WORDS = {
  missing: "refusal.REASON_MISSING",
  crlf: "refusal.PROSE_LINE_BREAK_CRLF",
  direction_control: "refusal.PROSE_DIRECTION_CONTROL",
  tag: "refusal.PROSE_TAG_CHARACTER",
  unusable: "refusal.REASON_UNUSABLE",
} as const satisfies Record<ProseRefusal | "missing", MessageId>;

/** Words this build has for a spelling, or a plain saying that it has none; never the server's spelling. */
function worded<T extends Record<string, MessageId>>(
  words: T,
  spelt: string,
): string {
  return wordedIfKnown(words, spelt) ?? say("step.unknown");
}

function wordedIfKnown<T extends Record<string, MessageId>>(
  words: T,
  spelt: string,
): string | null {
  return Object.hasOwn(words, spelt) ? say(words[spelt as keyof T]!) : null;
}

function stepStateSaid(state: string): string {
  return worded(STATES, state);
}

/** The words for a state this build has words for, and null for any other. */
export function stepStateWorded(state: string): string | null {
  return wordedIfKnown(STATES, state);
}

export function stepStateColour(state: StepState, theme: Theme): string {
  return theme.palette.stepState[state];
}

export function standingSaid(now: string): string {
  return worded(STANDINGS, now);
}

export function endedSaid(ended: string): string {
  return worded(ENDINGS, ended);
}

export function tryEndedSaid(aTry: Try): string {
  switch (aTry.review.lost) {
    case undefined:
      return endedSaid(aTry.ended);
    case "did_not_fit":
      return say("step.reviewDidNotFit");
    case "nothing_came_back":
      return say("step.reviewNothingCameBack");
    case "errored":
      return reviewSaid(aTry.review);
    default:
      return say("step.unknown");
  }
}

/** A picked value speaks for its own standing; a try without that value speaks for itself. */
export function tryResultSaid(aTry: Try, field: string | undefined): string {
  const value = pickedValueOf(aTry, field);
  return value === undefined ? tryEndedSaid(aTry) : standingSaid(value.now);
}

function pickedValueOf(
  aTry: Try,
  field: string | undefined,
): Try["values"][number] | undefined {
  return field === undefined
    ? undefined
    : aTry.values.find((each) => each.field === field);
}

export function misfitSaid(didNotFit: string): string {
  return worded(MISFITS, didNotFit);
}

/**
 * Why code went wrong where this system found it so, its field labelled as the code step declares it now; a sentence
 * whose field, member or reader did not come is one this page cannot say.
 */
export function erroredForSaid(
  erroredFor: ErroredFor,
  declared: Declared | undefined,
  declaring: Declaring,
): string {
  const { reason, field, member, readBy } = erroredFor;
  if (!Object.hasOwn(ERRORED_FOR, reason)) {
    return say("step.unknown");
  }
  const known = reason as ErroredForReason;
  if (NAMING_NO_FIELD.has(known)) {
    return say(ERRORED_FOR[known]);
  }
  const named =
    field === undefined
      ? undefined
      : labelAt(
          ABOUT_WHAT_IT_TAKES.has(known) ? declared?.takes : declared?.gives,
          field,
        );
  if (known === "not_declared") {
    if (member === undefined) {
      return say("step.unknown");
    }
    return named === undefined
      ? say(ERRORED_FOR.not_declared, {
          member: memberSaid(member, erroredFor.memberCut),
        })
      : say("step.erroredForNotDeclaredWithin", {
          member: memberSaid(member, erroredFor.memberCut),
          field: named,
        });
  }
  if (named === undefined) {
    return say("step.unknown");
  }
  if (known !== "gives_otherwise") {
    return say(ERRORED_FOR[known], { field: named });
  }
  if (readBy === undefined) {
    return say("step.unknown");
  }
  if ("step" in readBy) {
    const step = laterStepSaid(readBy.step, declaring);
    return step === null
      ? say("step.unknown")
      : say(ERRORED_FOR.gives_otherwise, { field: named, step });
  }
  return say("step.erroredForGivesOtherwiseOutput", {
    field: named,
    output: outputSaid(readBy.output, declaring),
  });
}

/** A member nothing declares, as the code named it; a name cut, or none that can be shown, said so. */
function memberSaid(member: string, cut: true | undefined): string {
  if (member === "") {
    return say("step.erroredForUnnamedMember");
  }
  return say(cut ? "step.erroredForMemberCut" : "step.erroredForMember", {
    name: isolatedInText(member),
  });
}

/**
 * The field of what a code step gives back that refuses an answer here, and what reads it.
 * A field with no reader is refused for the list its terms come from, which nothing reads.
 */
export function givesOtherwiseSaid(
  refused: GivesOtherwise,
  declared: Declared | undefined,
  declaring: Declaring,
): string {
  const { readBy } = refused;
  const field = labelAt(declared?.gives, refused.field);
  if (readBy === undefined) {
    return say("step.erroredForNowGivesAListNotHere", { field });
  }
  if ("step" in readBy) {
    const step = laterStepSaid(readBy.step, declaring);
    return step === null
      ? say("step.unknown")
      : say("step.erroredForNowGivesOtherwise", { field, step });
  }
  return say("step.erroredForNowGivesOtherwiseOutput", {
    field,
    output: outputSaid(readBy.output, declaring),
  });
}

/** A line of where a step is: words, or words with a value a model may have written drawn in them, never to be joined. */
export type WhereLine = string | ReactElement;

/**
 * Where a step is, first; then why, each value waiting by its label in what the step gives back; then whom it waits
 * on, or who can deal with one held back or failed. A running step says only what it has out, where it knows it.
 */
export function whereSaid(
  step: StepRow,
  gives: readonly ReadField[] | undefined,
  groupId?: string,
  now = new Date(),
  search = "",
): WhereLine[] {
  const said: WhereLine[] = [stepStateSaid(step.state)];
  const where = step.where;
  if (where === undefined) {
    return said;
  }
  if (where.kind === "running") {
    const out =
      where.on === undefined ? null : wordedIfKnown(RUNNING, where.on);
    return out === null ? said : [...said, out];
  }
  const why = whySaid(where, step.next?.number, gives, groupId, now, search);
  if (why === null) {
    return Object.hasOwn(STATES, step.state)
      ? [...said, say("step.unknown")]
      : said;
  }
  if (where.kind === "held_back" || where.kind === "failed") {
    const dealt = dealtSaid(step, where);
    return dealt === null ? [...said, ...why] : [...said, ...why, dealt];
  }
  if (
    where.kind === "waiting_on_review" &&
    where.review !== undefined &&
    where.waitsOn !== undefined
  ) {
    return [...said, ...why];
  }
  return [
    ...said,
    ...why,
    where.waitsOn === undefined
      ? say("step.waitsOnNobody")
      : worded(WAITERS, where.waitsOn),
  ];
}

/**
 * Who can deal with a step held back or failed, which waits on nobody: none while the run is stopped; else who lets
 * a stop said go, then who may send or try it again; unknown reasons and terminal failures promise no way on.
 */
function dealtSaid(step: StepRow, where: Where): string | null {
  if (!hasWayOn(where)) {
    return null;
  }
  if (where.waitsOn === undefined) {
    return say("step.nobodyWhileStopped");
  }
  if (
    where.reason === "code_step_not_held" ||
    where.reason === "search_way_not_held"
  ) {
    return null;
  }
  if (where.kind === "failed" && where.reason === "tries_spent") {
    return say(stopSaid(where) ? "step.letGoThenTryAgain" : "step.tryAgainBy");
  }
  const sending =
    step.acts.includes("try_sending") ||
    step.withheld.some(({ act }) => act === "try_sending");
  if (stopSaid(where)) {
    return say(sending ? "step.letGoThenTrySending" : "step.letGo");
  }
  return sending ? say("step.trySendingBy") : null;
}

/** Why each act is withheld, said once; code's mismatched field is named beside the refusal. */
export function withheldSaid(step: StepRow, declaring: Declaring): string[] {
  const saidWhere = stopsSaidWhere(step.where);
  const said = step.withheld
    .filter(({ refusal }) => !saidWhere.includes(refusal))
    .flatMap((withheld) => {
      const { act, refusal } = withheld;
      if (refusal === ("ACT_NOT_PERMITTED" satisfies WithheldRefusal)) {
        return [worded(NOT_PERMITTED, act)];
      }
      const which =
        refusal === ("CODE_STEP_GIVES_OTHERWISE" satisfies WithheldRefusal) &&
        withheld.givesOtherwise !== undefined
          ? givesOtherwiseSaid(
              withheld.givesOtherwise,
              declaredFor(step, declaring.declarations),
              declaring,
            )
          : null;
      return which === null
        ? [worded(WITHHELD_FOR, refusal)]
        : [worded(WITHHELD_FOR, refusal), which];
    });
  return [...new Set(said)];
}

/**
 * The refusals `whereSaid` already says: the run's stop, as whom it waits on or that nobody can act on it; a model's
 * review, as whom it waits on; and a stop of what it runs, as what holds it or the stop it says first.
 */
function stopsSaidWhere(where: Where | undefined): readonly string[] {
  if (where === undefined || !isKnownWhere(where.kind)) {
    return [];
  }
  const said: WithheldRefusal[] = [
    ...(where.waitsOn === undefined &&
    (where.kind === "waiting_on_review" ||
      where.kind === "owed_try" ||
      hasWayOn(where))
      ? ["RUN_STOPPED" as const]
      : []),
    ...(where.waitsOn === "model" ? ["REVIEW_NOT_A_PERSONS" as const] : []),
    ...((where.kind === "held_back" && where.reason === "entry_stopped") ||
    stopSaid(where)
      ? ["ENTRY_STOPPED" as const]
      : []),
  ];
  return said;
}

function hasWayOn(where: Where): boolean {
  const { kind, reason } = where;
  return (
    reason !== undefined &&
    (kind === "held_back"
      ? Object.hasOwn(HOLDS, reason)
      : kind === "failed" &&
        (reason === "tries_spent" || MENDED_BY_SENDING.has(reason)))
  );
}

/** A stop matters before a reason that can be mended; a terminal failure cannot be released or tried again. */
function stopSaid(where: Where): boolean {
  return where.stopped !== undefined && hasWayOn(where);
}

type KnownWhere = Exclude<WhereKind, "running">;

/** The kinds of whereabouts this build can say why a step is in; of any other it says nothing, whom it waits on included. */
const KNOWN_WHERE: ReadonlySet<string> = new Set<KnownWhere>([
  "waiting_on_review",
  "held_back",
  "owed_try",
  "failed",
]);

function isKnownWhere(kind: string): kind is KnownWhere {
  return KNOWN_WHERE.has(kind);
}

/** Null for a kind of whereabouts this build does not know. */
function whySaid(
  where: Where,
  next: number | undefined,
  gives: readonly ReadField[] | undefined,
  groupId: string | undefined,
  now: Date,
  search: string,
): WhereLine[] | null {
  const { kind } = where;
  if (!isKnownWhere(kind)) {
    return null;
  }
  switch (kind) {
    case "waiting_on_review": {
      const values = (where.values ?? []).map(({ field }) =>
        say("step.notReviewedYetOf", { field: labelAt(gives, field) }),
      );
      if (where.review === undefined) {
        return values;
      }
      const review =
        where.review === "turned_away" && where.spentUp === true
          ? say("step.reviewSpentUp")
          : worded(REVIEW_SENDING, where.review);
      return where.review === "turned_away" && where.waitsOn !== undefined
        ? [...values, review, say("step.trySendingBy")]
        : [...values, review];
    }
    case "held_back":
      return stoppedFirst(where, heldSaid(where, groupId, search), now);
    case "failed":
      return stoppedFirst(where, failedSaid(where, groupId, search), now);
    case "owed_try":
      return [
        next === undefined
          ? say("step.unknown")
          : say(where.open === true ? "step.owedAsked" : "step.owedUnasked", {
              number: next,
            }),
      ];
  }
}

/**
 * A stop in force said first, by what it stopped, then who stopped it and when; then `why`, what held it back or
 * failed it before, unless the stop is all that holds it.
 */
function stoppedFirst(where: Where, why: WhereLine, now: Date): WhereLine[] {
  const { stopped } = where;
  if (stopped === undefined || !stopSaid(where)) {
    return [why];
  }
  const stop = [worded(STOPPED, stopped.what), stoppedSaid(stopped, now)];
  return where.reason === "entry_stopped" ? stop : [...stop, why];
}

/**
 * Why a step failed; a route's value no branch took drawn as a model's words are; a model not held by its name and
 * mode, and whether it was to produce or to review.
 */
function failedSaid(
  where: Where,
  groupId: string | undefined,
  search: string,
): WhereLine {
  const { reason, model, mode } = where;
  if (reason === "source_no_longer_matches") {
    const mismatch = where.failedSourceNoLongerMatches;
    return mismatch === undefined
      ? say("step.unknown")
      : sourceSaid(
          "step.failedSourceNoLongerMatches",
          mismatch.source,
          groupId,
          mismatch.field,
          search,
        );
  }
  if (reason === "source_list_not_here") {
    const missing = where.failedSourceListNotHere;
    return missing === undefined
      ? say("step.unknown")
      : sourceSaid(
          "step.failedSourceListNotHere",
          missing.source,
          groupId,
          missing.field,
          search,
        );
  }
  if (reason === "tool_not_offered") {
    return model === undefined || where.tool === undefined
      ? say("step.unknown")
      : say("step.failedToolNotOffered", {
          model: whoSaid({ kind: "model", model, mode }),
          tool: isolatedInText(where.tool),
        });
  }
  if (reason === "unclaimed_value") {
    if (where.failedUnclaimed?.withheld === true) {
      return say("step.failedUnclaimedWithheld");
    }
    const term = where.failedUnclaimed?.term;
    return term === null
      ? say("step.failedUnclaimedEmpty")
      : term === undefined
        ? say("step.unknown")
        : createElement(HiddenShownIn, {
            sentence: (place: string) =>
              say("step.failedUnclaimed", { term: place }),
            text: term,
          });
  }
  if (reason === "model_not_deployed" && model !== undefined) {
    return say(
      where.reviewing === true
        ? "step.failedReviewerNotHeld"
        : "step.failedProducerNotHeld",
      { model: whoSaid({ kind: "model", model, mode }) },
    );
  }
  if (
    reason === ("too_deep" satisfies FailureReason) &&
    where.failedTooDeep !== undefined
  ) {
    return say("step.failedTooDeep", { most: where.failedTooDeep.most });
  }
  return reason === undefined ? say("step.unknown") : worded(FAILURES, reason);
}

/**
 * What holds a step back; a model turning it away as its spending is used up, as that; a stop no longer in force
 * holds it only until the run next goes on.
 */
function heldSaid(
  where: Where,
  groupId: string | undefined,
  search: string,
): WhereLine {
  const { reason } = where;
  if (reason === "code_step_not_held" && where.source !== undefined) {
    return sourceSaid(
      "step.heldSourceCodeStepNotHeld",
      where.source,
      groupId,
      undefined,
      search,
    );
  }
  if (reason === "turned_away" && where.spentUp === true) {
    return say("step.heldSpentUp");
  }
  if (reason === "entry_stopped" && where.stopped === undefined) {
    return say("step.heldStopLetGo");
  }
  return reason === undefined ? say("step.unknown") : worded(HOLDS, reason);
}

function sourceSaid(
  id: MessageId,
  source: NonNullable<Where["source"]>,
  groupId: string | undefined,
  field?: string,
  search = "",
): WhereLine {
  const values: Record<string, string> =
    field === undefined ? {} : { field: isolatedInText(field) };
  if (groupId === undefined) {
    return say(id, {
      ...values,
      step: isolatedInText(spokenName(source.name)),
    });
  }
  const step = createElement(
    Link<typeof RouterLink>,
    {
      component: RouterLink,
      to: `${stepHref(groupId, source.runId, source.stepId)}${listQuery(search)}`,
      state: WITHIN_THE_RUN,
      title: say("run.title", { number: source.runNumber }),
    },
    createElement("bdi", null, spokenName(source.name)),
  );
  return createElement(
    Fragment,
    null,
    intl.formatMessage({ id }, { ...values, step }),
  );
}

/** Who stopped what holds a step back, and when; one no longer to be found is said to be so. */
function stoppedSaid(
  stopped: NonNullable<Where["stopped"]>,
  now: Date,
): string {
  const when = whenText(stopped.at, now);
  return stopped.by === undefined
    ? say("step.stoppedByGone", { when })
    : say("step.stoppedBy", {
        name: isolatedInText(nameOrNumber(stopped.by)),
        when,
      });
}

/** A person by name where one is named; a model by its name and mode. */
export function whoSaid(who: Who): string {
  switch (who.kind) {
    case "person":
      return who.person === undefined
        ? say("step.aPerson")
        : isolatedInText(nameOrNumber(who.person));
    case "code":
      return say("step.code");
    case "model":
      if (who.model === undefined) {
        return say("step.unknown");
      }
      return who.mode === undefined
        ? say("workflow.modelAsItIs", { model: who.model })
        : say("workflow.modelInMode", { model: who.model, mode: who.mode });
    default:
      return say("step.unknown");
  }
}

/** Who reviewed a try, a review gone wrong included; or that nobody has yet, or that nobody was asked to. */
export function reviewSaid(review: Try["review"]): string {
  if (review.by === undefined) {
    return say(review.asked ? "step.notReviewedYet" : "step.noReviewAsked");
  }
  return review.wentWrong === true
    ? say("step.reviewWentWrong", { who: whoSaid(review.by) })
    : whoSaid(review.by);
}

function hint(words: string): ReactElement {
  return createElement(SystemHint, null, words);
}

export function tryBeyondVisible(number: number, allowed: number): ReactNode {
  return visible("step.tryBeyondVisible", {
    number,
    past: hint(say("step.tryBeyondPast")),
    allowed,
    allows: hint(say("step.tryBeyondAllows")),
  });
}

export function reviewVisible(review: Try["review"]): ReactNode {
  return review.by === undefined || review.wentWrong !== true
    ? reviewSaid(review)
    : visible("step.reviewWentWrongVisible", {
        who: createElement("bdi", null, whoSaid(review.by)),
        hint: hint(say("step.reviewWentWrongHint")),
      });
}

export function tryEndedVisible(aTry: Try): ReactNode {
  return aTry.review.lost === "errored"
    ? reviewVisible(aTry.review)
    : tryEndedSaid(aTry);
}

export function tryResultVisible(
  aTry: Try,
  field: string | undefined,
): ReactNode {
  const value = pickedValueOf(aTry, field);
  return value === undefined ? tryEndedVisible(aTry) : standingSaid(value.now);
}

export function erroredForVisible(
  erroredFor: ErroredFor,
  declared: Declared | undefined,
  declaring: Declaring,
): ReactNode {
  if (
    erroredFor.reason !== "not_declared" ||
    erroredFor.memberCut !== true ||
    erroredFor.member === undefined ||
    erroredFor.member === ""
  ) {
    return erroredForSaid(erroredFor, declared, declaring);
  }
  const field =
    erroredFor.field === undefined
      ? undefined
      : labelAt(declared?.gives, erroredFor.field);
  const member = visible("step.erroredForMemberCutVisible", {
    member: createElement(
      Fragment,
      null,
      visible("step.erroredForMember", {
        name: createElement("bdi", null, isolatedInText(erroredFor.member)),
      }),
    ),
    hint: hint(say("step.erroredForMemberCutHint")),
  });
  return visible(
    field === undefined
      ? "step.erroredForNotDeclared"
      : "step.erroredForNotDeclaredWithin",
    {
      member: createElement(Fragment, null, member),
      ...(field === undefined ? {} : { field }),
    },
  );
}

/**
 * What what they said was: assured, or refused, or refused in words withheld where a model said them. Words a value
 * was refused in are never said here: only `DecisionDrawn` draws them, showing what in them hides.
 */
export function decisionSaid(
  decision: TriedValue["decision"] & object,
): string {
  if (decision.outcome === "assured") {
    return say("step.assured");
  }
  if (decision.outcome !== "refused") {
    return say("step.unknown");
  }
  return say(decision.withheld === true ? "step.whyWithheld" : "step.refused");
}

/**
 * When a model turned a call away, and whether it was sent again by itself; then that what it said is withheld, where
 * it is. What it said is never said here: only `Turnaways` draws it, showing what in it hides.
 */
export function turnawaySaid(turnaway: Turnaway, now = new Date()): string[] {
  const when = say(
    turnaway.sentAgain ? "step.turnedAwaySentAgain" : "step.turnedAwayAt",
    { when: whenText(turnaway.at, now) },
  );
  return turnaway.withheld === true
    ? [when, say("step.turnedAwayWithheld")]
    : [when];
}
