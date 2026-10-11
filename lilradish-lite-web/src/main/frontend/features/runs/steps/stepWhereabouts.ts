import type { WhereKind } from "../../../api/stepWhereabouts";
import type { WaitsOn } from "../../../api/stepWhereabouts";
import type { StoppedWhat } from "../../../api/stepWhereabouts";
import type { ReviewSending } from "../../../api/stepWhereabouts";
import type { RunningOn } from "../../../api/stepWhereabouts";
import type { HoldReason } from "../../../api/stepWhereabouts";
import type { FailureReason } from "../../../api/stepWhereabouts";
import type { StateRefusalCode } from "../../../api/stepActs";
import Link from "@mui/material/Link";
import { Fragment, createElement, type ReactElement } from "react";
import { Link as RouterLink } from "react-router";

import type { ReadField } from "../../../api/filling";
import type { StepRow } from "../../../api/groups/{groupId}/runs/{runId}/steps";
import type { GroupStanding } from "../../../api/standing";
import type { Where } from "../../../api/stepWhereabouts";
import { say, type MessageId } from "../../../i18n/app";
import { intl } from "../../../i18n/intl";
import { isolatedInText } from "../../../lib/direction/isolated";
import { HiddenShownIn } from "../../../lib/text/HiddenShownIn";
import { versionLabelText } from "../../../lib/text/VersionLabel";
import { nameOrNumber } from "../../../lib/text/names";
import { whenText } from "../../../lib/time/When";
import { listQuery, stepHref, WITHIN_THE_RUN } from "../runAddress";
import { labelAt } from "./declared";
import { whoSaid } from "./stepProducersAndReviewers";
import { STATES, stepStateSaid, worded, wordedIfKnown } from "./stepStates";
import { spokenName } from "./stepTitle";

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
    | "search_question_no_longer_matches"
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
  "search_question_no_longer_matches",
]);

/** A line of where a step is: words, or words with a value a model may have written drawn in them, never to be joined. */
type WhereLine = string | ReactElement;

/**
 * State, reason and waiting values precede whoever waits or can act; running steps name only a known operation.
 */
export function whereSaid(
  step: StepRow,
  gives: readonly ReadField[] | undefined,
  group?: GroupStanding,
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
  const why = whySaid(
    where,
    step.next?.number,
    gives,
    group,
    now,
    search,
    step.runs,
  );
  if (why === null) {
    return Object.hasOwn(STATES, step.state)
      ? [...said, say("step.unknown")]
      : said;
  }
  if (where.kind === "held_back" || where.kind === "failed") {
    const dealt = dealtSaid(step, where);
    return dealt === null ? [...said, ...why] : [...said, ...why, dealt];
  }
  const starter =
    where.waitsOn !== "starter" ||
    (step.waitsOnStarter !== "this_run" &&
      step.waitsOnStarter !== "top_level_run")
      ? []
      : [
          say(
            step.waitsOnStarter === "top_level_run"
              ? "step.waitsOnTopLevelStarter"
              : "step.waitsOnStarter",
          ),
        ];
  if (
    where.kind === "waiting_on_review" &&
    where.review !== undefined &&
    where.waitsOn !== undefined
  ) {
    return [...said, ...why, ...starter];
  }
  return [
    ...said,
    ...why,
    ...(where.waitsOn === "starter"
      ? starter
      : [
          where.waitsOn === undefined
            ? say("step.waitsOnNobody")
            : worded(WAITERS, where.waitsOn),
        ]),
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
    step.stateActs?.has("try_sending") === true ||
    step.letGoThen?.has("try_sending") === true;
  if (stopSaid(where)) {
    return say(sending ? "step.letGoThenTrySending" : "step.letGo");
  }
  return sending ? say("step.trySendingBy") : null;
}

/**
 * The refusals `whereSaid` already says: the run's stop, as whom it waits on or that nobody can act on it; a model's
 * review, as whom it waits on; and a stop of what it runs, as what holds it or the stop it says first.
 */
export function stopsSaidWhere(where: Where | undefined): readonly string[] {
  if (where === undefined || !isKnownWhere(where.kind)) {
    return [];
  }
  const said: StateRefusalCode[] = [
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
  group: GroupStanding | undefined,
  now: Date,
  search: string,
  runs: StepRow["runs"],
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
      return stoppedFirst(where, heldSaid(where, group, search), now);
    case "failed":
      return stoppedFirst(where, failedSaid(where, group, search, runs), now);
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

/** An unclaimed route value is drawn with the same safeguards as a model's words. */
function failedSaid(
  where: Where,
  group: GroupStanding | undefined,
  search: string,
  runs: StepRow["runs"],
): WhereLine {
  const { reason, model, mode } = where;
  if (reason === "search_question_no_longer_matches") {
    if (runs.searchWay === undefined) return say("step.unknown");
    const way = isolatedInText(runs.searchWay);
    return runs.name === undefined || runs.version === undefined
      ? say("step.failedSearchQuestionNoLongerMatchesUnnamed", { way })
      : say("step.failedSearchQuestionNoLongerMatches", {
          question: versionLabelText({
            entry: runs.name,
            number: runs.version,
            name: runs.versionName,
          }),
          way,
        });
  }
  if (reason === "source_no_longer_matches") {
    const mismatch = where.failedSourceNoLongerMatches;
    return mismatch === undefined
      ? say("step.unknown")
      : sourceSaid(
          "step.failedSourceNoLongerMatches",
          mismatch.source,
          group,
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
          group,
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
  group: GroupStanding | undefined,
  search: string,
): WhereLine {
  const { reason } = where;
  if (reason === "code_step_not_held" && where.source !== undefined) {
    return sourceSaid(
      "step.heldSourceCodeStepNotHeld",
      where.source,
      group,
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
  group: GroupStanding | undefined,
  field?: string,
  search = "",
): WhereLine {
  const values: Record<string, string> =
    field === undefined ? {} : { field: isolatedInText(field) };
  if (group === undefined) {
    return say(id, {
      ...values,
      step: isolatedInText(source.label ?? spokenName(source.name)),
    });
  }
  const step = createElement(
    Link<typeof RouterLink>,
    {
      component: RouterLink,
      to: `${stepHref(group, source.runId, source.stepId)}${listQuery(search)}`,
      state: WITHIN_THE_RUN,
      title: say("run.title", { number: source.runNumber }),
    },
    createElement("bdi", null, source.label ?? spokenName(source.name)),
  );
  return createElement(
    Fragment,
    null,
    intl.formatMessage({ id }, { ...values, step }),
  );
}

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
