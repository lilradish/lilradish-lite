import type { Standing } from "../../../api/stepTries";
import type { ErroredForReason } from "../../../api/stepTries";
import type { Ended } from "../../../api/stepTries";
import type { DidNotFitReason } from "../../../api/stepTries";
import {
  Fragment,
  createElement,
  type ReactElement,
  type ReactNode,
} from "react";

import type { Declared } from "../../../api/groups/{groupId}/runs/{runId}/steps";

import type {
  ErroredFor,
  TriedValue,
  Try,
  Turnaway,
} from "../../../api/stepTries";
import { say, type MessageId } from "../../../i18n/app";
import { visible } from "../../../i18n/visible";
import { isolatedInText } from "../../../lib/direction/isolated";
import { SystemHint } from "../../../lib/text/HiddenShownIn";
import { whenText } from "../../../lib/time/When";
import { labelAt, laterStepSaid, outputSaid, type Declaring } from "./declared";
import { whoSaid } from "./stepProducersAndReviewers";
import { worded } from "./stepStates";

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

export function standingSaid(now: string): string {
  return worded(STANDINGS, now);
}

function endedSaid(ended: string): string {
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

/** A member nothing declares, as the code named it. */
function memberSaid(member: string, cut: true | undefined): string {
  if (member === "") {
    return say("step.erroredForUnnamedMember");
  }
  return say(cut ? "step.erroredForMemberCut" : "step.erroredForMember", {
    name: isolatedInText(member),
  });
}

function reviewSaid(review: Try["review"]): string {
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

/** Refusal words are excluded from this plain-text summary so their drawing can expose hidden characters. */
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

/** What it said is never said here: only `Turnaways` draws it, showing what in it hides. */
export function turnawaySaid(turnaway: Turnaway, now = new Date()): string[] {
  const when = say(
    turnaway.sentAgain ? "step.turnedAwaySentAgain" : "step.turnedAwayAt",
    { when: whenText(turnaway.at, now) },
  );
  return turnaway.withheld === true
    ? [when, say("step.turnedAwayWithheld")]
    : [when];
}
