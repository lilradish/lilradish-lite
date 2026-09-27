import type { ReadField } from "../../../api/filling";
import type {
  FailureReason,
  HoldReason,
  RunningOn,
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
  Standing,
  TriedValue,
  Try,
} from "../../../api/groups/{groupId}/runs/{runId}/steps/{stepId}";
import { say, type MessageId } from "../../../i18n/app";
import { isolatedInText } from "../../../lib/direction/isolated";
import type { ProseRefusal } from "../../../lib/text/legibility";
import { nameOrNumber } from "../../../lib/text/names";
import { whenText } from "../../../lib/time/When";
import { labelAt } from "./declared";

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

/** Which way a model's answer did not fit, in the reader's words and never as its spelling. */
const MISFITS = {
  not_the_shape: "step.misfitNotTheShape",
  field_missing: "step.misfitFieldMissing",
  field_unknown: "step.misfitFieldUnknown",
  nothing_given: "step.misfitNothingGiven",
  not_its_kind: "step.misfitNotItsKind",
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
} as const satisfies Record<HoldReason, MessageId>;

/** A stop holding a step back: the run's own workflow, which holds whatever it runs, or only what it runs. */
const STOPPED = {
  workflow: "step.heldWorkflowStopped",
  entry: "step.heldEntryStopped",
} as const satisfies Record<StoppedWhat, MessageId>;

/** A route's unclaimed value is never said without the value, which the wire does not carry yet. */
const FAILURES = {
  tries_spent: "step.failedTriesSpent",
  uncuttable_length: "step.failedUncuttable",
  unclaimed_value: "step.unknown",
  model_not_deployed: "step.failedModelNotHeld",
} as const satisfies Record<FailureReason, MessageId>;

/** The failures Try sending may mend, so a stop stands in front of them; the rest are final, whatever is stopped. */
const MENDED_BY_SENDING: ReadonlySet<string> = new Set<FailureReason>([
  "uncuttable_length",
  "model_not_deployed",
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

export function standingSaid(now: string): string {
  return worded(STANDINGS, now);
}

export function endedSaid(ended: string): string {
  return worded(ENDINGS, ended);
}

export function misfitSaid(didNotFit: string): string {
  return worded(MISFITS, didNotFit);
}

/**
 * Where a step is, first; then why, each value waiting by its label in what the step gives back; then whom it waits
 * on, or who can deal with one held back or failed. A running step says only what it has out, where it knows it.
 */
export function whereSaid(
  step: StepRow,
  gives: readonly ReadField[] | undefined,
): string[] {
  const said = [stepStateSaid(step.state)];
  const where = step.where;
  if (where === undefined) {
    return said;
  }
  if (where.kind === "running") {
    const out =
      where.on === undefined ? null : wordedIfKnown(RUNNING, where.on);
    return out === null ? said : [...said, out];
  }
  const why = whySaid(where, step.next?.number, gives);
  if (why === null) {
    return Object.hasOwn(STATES, step.state)
      ? [...said, say("step.unknown")]
      : said;
  }
  if (where.kind === "held_back" || where.kind === "failed") {
    const dealt = dealtSaid(step, where);
    return dealt === null ? [...said, ...why] : [...said, ...why, dealt];
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
 * a stop said go, then who may send it where it offers sending; nothing where nothing mends it.
 */
function dealtSaid(step: StepRow, where: Where): string | null {
  if (where.waitsOn === undefined) {
    return say("step.nobodyWhileStopped");
  }
  const sending =
    step.acts.includes("try_sending") ||
    step.withheld.some(({ act }) => act === "try_sending");
  if (stopSaid(where)) {
    return say(sending ? "step.letGoThenTrySending" : "step.letGo");
  }
  return sending ? say("step.trySendingBy") : null;
}

/**
 * Why each act the step holds back from the reader is held back, each saying once however many acts it holds, and
 * none that where it is already says: a stop, or a model's review, said there is not said again.
 */
export function withheldSaid(step: StepRow): string[] {
  const saidWhere = stopsSaidWhere(step.where);
  const said = step.withheld
    .filter(({ refusal }) => !saidWhere.includes(refusal))
    .map(({ act, refusal }) =>
      refusal === ("ACT_NOT_PERMITTED" satisfies WithheldRefusal)
        ? worded(NOT_PERMITTED, act)
        : worded(WITHHELD_FOR, refusal),
    );
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
    ...(where.waitsOn === undefined ? ["RUN_STOPPED" as const] : []),
    ...(where.waitsOn === "model" ? ["REVIEW_NOT_A_PERSONS" as const] : []),
    ...((where.kind === "held_back" && where.reason === "entry_stopped") ||
    stopSaid(where)
      ? ["ENTRY_STOPPED" as const]
      : []),
  ];
  return said;
}

/** Whether a stop in force is said first: wherever it holds a step back, and on a failure sending may mend. */
function stopSaid(where: Where): boolean {
  return (
    where.stopped !== undefined &&
    (where.kind === "held_back" ||
      (where.kind === "failed" &&
        where.reason !== undefined &&
        MENDED_BY_SENDING.has(where.reason)))
  );
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
): string[] | null {
  const { kind } = where;
  if (!isKnownWhere(kind)) {
    return null;
  }
  switch (kind) {
    case "waiting_on_review":
      return (where.values ?? []).map(({ field }) =>
        say("step.notReviewedYetOf", { field: labelAt(gives, field) }),
      );
    case "held_back":
      return stoppedFirst(where, heldSaid(where));
    case "failed":
      return stoppedFirst(where, failedSaid(where));
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
function stoppedFirst(where: Where, why: string): string[] {
  const { stopped } = where;
  if (stopped === undefined || !stopSaid(where)) {
    return [why];
  }
  const stop = [
    wordedIfKnown(STOPPED, stopped.what) ?? say(HOLDS.entry_stopped),
    stoppedSaid(stopped),
  ];
  return where.reason === "entry_stopped" ? stop : [...stop, why];
}

/** Why a step failed; a model not held by its name and mode, and whether it was to produce or to review. */
function failedSaid(where: Where): string {
  const { reason, model, mode } = where;
  if (reason === "model_not_deployed" && model !== undefined) {
    return say(
      where.reviewing === true
        ? "step.failedReviewerNotHeld"
        : "step.failedProducerNotHeld",
      { model: whoSaid({ kind: "model", model, mode }) },
    );
  }
  return reason === undefined ? say("step.unknown") : worded(FAILURES, reason);
}

/**
 * What holds a step back; a model turning it away as its spending is used up, as that; a stop no longer in force
 * holds it only until the run next goes on.
 */
function heldSaid(where: Where): string {
  const { reason } = where;
  if (reason === "turned_away" && where.spentUp === true) {
    return say("step.heldSpentUp");
  }
  if (reason === "entry_stopped" && where.stopped === undefined) {
    return say("step.heldStopLetGo");
  }
  return reason === undefined ? say("step.unknown") : worded(HOLDS, reason);
}

/** Who stopped what holds a step back, and when; one no longer to be found is said to be so. */
function stoppedSaid(stopped: NonNullable<Where["stopped"]>): string {
  const when = whenText(stopped.at);
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

/** What what they said was: assured, or refused and in which words, which are withheld where a model said them. */
export function decisionSaid(
  decision: TriedValue["decision"] & object,
): string {
  if (decision.outcome === "assured") {
    return say("step.assured");
  }
  if (decision.outcome !== "refused") {
    return say("step.unknown");
  }
  if (decision.why !== undefined) {
    return say("step.refusedWith", { why: isolatedInText(decision.why) });
  }
  return say(decision.withheld === true ? "step.whyWithheld" : "step.refused");
}

/** When a model turned a call away, and whether it was sent again by itself; then what it said, or that that is withheld. */
export function turnawaySaid(turnaway: Turnaway): string[] {
  const when = say(
    turnaway.sentAgain ? "step.turnedAwaySentAgain" : "step.turnedAwayAt",
    { when: whenText(turnaway.at) },
  );
  if (turnaway.withheld === true) {
    return [when, say("step.turnedAwayWithheld")];
  }
  if (turnaway.said === undefined) {
    return [when];
  }
  const said = isolatedInText(turnaway.said);
  return [
    when,
    say(
      turnaway.cut === true ? "step.turnedAwaySaidCut" : "step.turnedAwaySaid",
      { said },
    ),
  ];
}
