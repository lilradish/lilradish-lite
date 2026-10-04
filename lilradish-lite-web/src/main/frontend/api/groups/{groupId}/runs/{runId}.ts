import type { WordedRefusal } from "../../../../i18n/app";
import { atSegment } from "../../../../lib/request/address";
import {
  isUnchecked,
  listOf,
  optional,
  textFrom,
  wordsIn,
} from "../../../../lib/request/document";
import { get, patch } from "../../../../lib/request/http";
import { isShownValue, type ShownValues } from "../../../filling";
import { GROUPS } from "../../../groups";
import { personFrom, type Person } from "../../../person";
import { servedAmong, type Problem } from "../../../problem";
import { inputFrom, type InputFrom } from "./{runId}/steps";

/** What may be done to a run, spelt and held level as `EntryKind` is. */
export type RunAct =
  | "stop"
  | "open_again"
  | "rename"
  | "change_ceiling"
  | "approve_raise"
  | "refuse_raise"
  | "withdraw_raise";

/** Where a run is, spelt and held level as `EntryKind` is. */
export type RunState = "running" | "stopped" | "failed" | "done";

/** The step a running run is on, by what its workflow calls it. */
export interface RunAt {
  readonly stepId: string;
  readonly name: string;
}

/** Another run of the tree, by the number a reader knows it by. */
export interface NumberedRun {
  readonly runId: string;
  readonly number: number;
}

/**
 * Every count is digits in a string, as the server sends it: one past what a
 * number holds exactly would arrive already rounded.
 */
export interface Spend {
  readonly sent: string;
  readonly cameBack: string;
  /** What was sent and what came back, added; what a ceiling is held against. */
  readonly spent: string;
  /** Whether a call counted came back saying nothing, or is not back yet. */
  readonly cameBackUnknown: boolean;
  /** Whether every call, or only some, was counted by this system rather than a model. */
  readonly measuredHere?: "wholly" | "partly";
}

/** A raise of the ceiling, or its taking away, waiting on approval: not in force. */
export interface Raise {
  readonly changeId: string;
  /** Absent where it takes the ceiling away. */
  readonly to?: string;
  readonly askedBy: Person;
  /** ISO 8601. */
  readonly askedAt: string;
}

/** Somebody pressing Stop, or a ceiling reached, which names nobody. */
export type Stopped =
  | { readonly at: string; readonly by: Person }
  | {
      readonly at: string;
      readonly ceilingOf: NumberedRun;
      readonly ceilings?: readonly NumberedRun[];
    };

/** A ceiling the run holds of its own. */
export interface OwnCeiling {
  /** Absent where the run may spend without limit. */
  readonly inForce?: string;
  readonly raiseNeedsApproval: boolean;
  readonly waiting?: Raise;
}

/** Its own, or the one at the top of its tree, where a run beneath keeps none of its own. */
export type RunCeiling = OwnCeiling | { readonly heldBy: NumberedRun };

export interface CeilingBeneath {
  readonly run: NumberedRun;
  readonly ceiling: OwnCeiling;
  readonly acts: ReadonlySet<string>;
}

/** A run above another, by its number, and by its name where it holds one, which only the one at the top does. */
export interface RunAbove extends NumberedRun {
  readonly name?: string;
}

export interface FromRun extends NumberedRun {
  readonly stepId: string;
  readonly stepName: string;
}

interface DatedEvent {
  readonly id: string;
  readonly at: string;
}

type EventValue = { readonly field: string } & (
  { readonly value: ShownValues[string] } | { readonly asKept: string | null }
);

export type ReviewDecision =
  | { readonly field: string; readonly outcome: "assured" }
  | {
      readonly field: string;
      readonly outcome: "refused";
      readonly why: string;
    };

interface HeldStep {
  readonly run: NumberedRun;
  readonly stepId: string;
  readonly stepName: string;
}

interface OnStep extends DatedEvent {
  readonly stepId: string;
  readonly run: NumberedRun;
}

interface ByPerson extends DatedEvent {
  readonly by: Person;
  readonly run: NumberedRun;
}

interface CeilingEvent extends ByPerson {
  readonly kind:
    | "ceiling_changed"
    | "ceiling_asked"
    | "ceiling_approved"
    | "ceiling_refused"
    | "ceiling_withdrawn";
  readonly from?: string;
  readonly to?: string;
}

export type ConversationEvent =
  | (OnStep & { readonly kind: "step_reached" })
  | (OnStep & {
      readonly kind: "answered_by_hand";
      readonly by: Person;
      readonly number: number;
      readonly values: readonly EventValue[];
      readonly why?: string;
    })
  | (OnStep & {
      readonly kind: "reviewed";
      readonly by: Person;
      readonly number: number;
      readonly decisions: readonly ReviewDecision[];
    })
  | (OnStep & {
      readonly kind: "refused_for_length";
      readonly held: HeldStep;
      readonly by: Person;
      readonly number: number;
      readonly decisions: readonly ReviewDecision[];
    })
  | CeilingEvent
  | (DatedEvent & {
      readonly kind: "ceiling_superseded";
      readonly run: NumberedRun;
      readonly from?: string;
      readonly to?: string;
    })
  | (ByPerson & { readonly kind: "stopped" | "opened_again" })
  | (DatedEvent & {
      readonly kind: "stopped_by_ceiling";
      readonly run: NumberedRun;
      readonly ceilings: readonly NumberedRun[];
    });

/** One run as the server reads it, which every act on it answers with too. */
export interface Run {
  readonly runId: string;
  readonly number: number;
  /** Absent beneath another run, which the run above started rather than anybody. */
  readonly name?: string;
  /** The run above; absent at the top. */
  readonly above?: string;
  readonly from?: FromRun;
  /** Every run above it, from the top down; none at the top. */
  readonly runsAbove: readonly RunAbove[];
  readonly workflow: {
    readonly entryId: string;
    readonly name: string;
    readonly version: number;
  };
  readonly startedBy?: Person;
  /** ISO 8601. */
  readonly startedAt: string;
  /** What was filled when it started, including values passed into a child run. */
  readonly startedWith?: ShownValues;
  readonly startedWithFrom?: readonly InputFrom[];
  readonly state: string;
  /** Only while it is running. */
  readonly at?: RunAt;
  readonly stopped?: Stopped;
  readonly spend: Spend;
  readonly ceiling: RunCeiling;
  readonly ceilingsBeneath?: readonly CeilingBeneath[];
  readonly acts: ReadonlySet<string>;
  readonly conversationEvents: readonly ConversationEvent[];
}

/**
 * Each says the run moved under the page since it was read: what it offered is
 * no longer what the server holds, so reading it again is what shows why.
 */
const MOVED: ReadonlySet<string> = new Set([
  "RUN_NOT_IN_VIEW",
  "RUN_BENEATH_ANOTHER",
  "CEILING_RAISE_NOT_WAITING",
  "CEILING_RAISE_ASKED_BY_CALLER",
  "CEILING_RAISE_ASKED_BY_ANOTHER",
] satisfies readonly WordedRefusal[]);

export const DIGITS = /^(0|[1-9][0-9]*)$/;

/** The run's address handed to `ask`, each identifier escaped as `atSegment` escapes one. */
export function atRun<T>(
  groupId: string,
  runId: string,
  ask: (address: string) => Promise<T>,
): Promise<T> {
  return atSegment(GROUPS, groupId, (group) =>
    atSegment(`${group}/runs`, runId, ask),
  );
}

export function readRun(
  groupId: string,
  runId: string,
  signal: AbortSignal,
): Promise<Run> {
  return atRun(groupId, runId, (address) => get(address, signal, runFrom));
}

/** Its name, and nothing else. */
export function renameRun(
  groupId: string,
  runId: string,
  name: string,
  signal: AbortSignal,
): Promise<Run> {
  return atRun(groupId, runId, (address) =>
    patch(address, { name }, signal, runFrom),
  );
}

/** Whether the server offers the reader this act on the run as it now stands. */
export function offers(run: Run, act: RunAct): boolean {
  return run.acts.has(act);
}

/** Whether a change was refused for how the run stands now, which somebody else moved since the page read it. */
export function refusedForWhatMoved(problem: Problem): boolean {
  return servedAmong(problem, MOVED);
}

export function runFrom(body: unknown): Run | null {
  if (!isUnchecked(body)) {
    return null;
  }
  const { runId, number, startedAt, state } = body;
  const acts = wordsIn(body.acts);
  const workflow = workflowFrom(body.workflow);
  const spend = spendFrom(body.spend);
  const ceiling = ceilingFrom(body.ceiling);
  const name = optional(body, "name", textFrom);
  const above = optional(body, "above", textFrom);
  const from = optional(body, "from", fromRunFrom);
  const runsAbove = listOf(body.runsAbove, runAboveFrom);
  const startedBy = optional(body, "startedBy", personFrom);
  const startedWith = optional(body, "startedWith", valuesFrom);
  const startedWithFrom = optional(body, "startedWithFrom", (listed) =>
    listOf(listed, inputFrom),
  );
  const stopped = optional(body, "stopped", stoppedFrom);
  const at = optional(body, "at", atFrom);
  const ceilingsBeneath = optional(body, "ceilingsBeneath", (listed) =>
    listOf(listed, ceilingBeneathFrom),
  );
  const conversationEvents = listOf(
    body.conversationEvents,
    conversationEventFrom,
  );
  if (
    typeof runId !== "string" ||
    !Number.isSafeInteger(number) ||
    typeof startedAt !== "string" ||
    typeof state !== "string" ||
    acts === null ||
    workflow === null ||
    spend === null ||
    ceiling === null ||
    name === null ||
    above === null ||
    from === null ||
    (above === undefined) !== (from === undefined) ||
    (above !== undefined && from !== undefined && above !== from.runId) ||
    runsAbove === null ||
    runsAbove.at(-1)?.runId !== above ||
    startedBy === null ||
    startedWith === null ||
    startedWithFrom === null ||
    stopped === null ||
    at === null ||
    ceilingsBeneath === null ||
    conversationEvents === null
  ) {
    return null;
  }
  return {
    runId,
    number: number as number,
    runsAbove,
    workflow,
    startedAt,
    state,
    spend,
    ceiling,
    acts: new Set(acts),
    ...(name === undefined ? {} : { name }),
    ...(above === undefined ? {} : { above }),
    ...(from === undefined ? {} : { from }),
    ...(startedBy === undefined ? {} : { startedBy }),
    ...(startedWith === undefined ? {} : { startedWith }),
    ...(startedWithFrom === undefined ? {} : { startedWithFrom }),
    ...(stopped === undefined ? {} : { stopped }),
    ...(at === undefined ? {} : { at }),
    ...(ceilingsBeneath === undefined ? {} : { ceilingsBeneath }),
    conversationEvents,
  };
}

function conversationEventFrom(body: unknown): ConversationEvent | null {
  if (
    !isUnchecked(body) ||
    typeof body.id !== "string" ||
    typeof body.kind !== "string" ||
    typeof body.at !== "string"
  ) {
    return null;
  }
  const stepId = optional(body, "stepId", textFrom);
  const held = optional(body, "held", heldFrom);
  const by = optional(body, "by", personFrom);
  const number = optional(body, "number", (value) =>
    Number.isSafeInteger(value) && (value as number) > 0
      ? (value as number)
      : null,
  );
  const values = optional(body, "values", (listed) =>
    listOf(listed, eventValueFrom),
  );
  const decisions = optional(body, "decisions", (listed) =>
    listOf(listed, decisionFrom),
  );
  const run = optional(body, "run", numberedFrom);
  const from = optional(body, "from", digitsFrom);
  const to = optional(body, "to", digitsFrom);
  const ceilings = optional(body, "ceilings", (listed) =>
    listOf(listed, numberedFrom),
  );
  const why = optional(body, "why", textFrom);
  if (
    stepId === null ||
    held === null ||
    by === null ||
    number === null ||
    values === null ||
    decisions === null ||
    run === null ||
    from === null ||
    to === null ||
    ceilings === null ||
    why === null
  ) {
    return null;
  }
  const dated = { id: body.id, at: body.at };
  switch (body.kind) {
    case "step_reached":
      return stepId !== undefined && run !== undefined
        ? { ...dated, kind: body.kind, stepId, run }
        : null;
    case "answered_by_hand":
      return stepId !== undefined &&
        by !== undefined &&
        number !== undefined &&
        values !== undefined &&
        run !== undefined
        ? {
            ...dated,
            kind: body.kind,
            stepId,
            by,
            number,
            values,
            run,
            ...(why === undefined ? {} : { why }),
          }
        : null;
    case "reviewed":
      return stepId !== undefined &&
        by !== undefined &&
        number !== undefined &&
        decisions !== undefined &&
        decisions.length > 0 &&
        run !== undefined
        ? { ...dated, kind: body.kind, stepId, by, number, decisions, run }
        : null;
    case "refused_for_length":
      return stepId !== undefined &&
        held !== undefined &&
        by !== undefined &&
        number !== undefined &&
        decisions !== undefined &&
        decisions.length > 0 &&
        decisions.every((decision) => decision.outcome === "refused") &&
        run !== undefined
        ? {
            ...dated,
            kind: body.kind,
            stepId,
            held,
            by,
            number,
            decisions,
            run,
          }
        : null;
    case "ceiling_changed":
    case "ceiling_asked":
    case "ceiling_approved":
    case "ceiling_refused":
    case "ceiling_withdrawn":
      return run !== undefined && by !== undefined
        ? {
            ...dated,
            kind: body.kind,
            run,
            by,
            ...(from === undefined ? {} : { from }),
            ...(to === undefined ? {} : { to }),
          }
        : null;
    case "ceiling_superseded":
      return run !== undefined && by === undefined
        ? {
            ...dated,
            kind: body.kind,
            run,
            ...(from === undefined ? {} : { from }),
            ...(to === undefined ? {} : { to }),
          }
        : null;
    case "stopped":
    case "opened_again":
      return by !== undefined && run !== undefined
        ? { ...dated, kind: body.kind, by, run }
        : null;
    case "stopped_by_ceiling":
      return run !== undefined &&
        by === undefined &&
        ceilings !== undefined &&
        ceilings.length > 0
        ? { ...dated, kind: body.kind, run, ceilings }
        : null;
    default:
      return null;
  }
}

function heldFrom(body: unknown): HeldStep | null {
  if (
    !isUnchecked(body) ||
    typeof body.stepId !== "string" ||
    typeof body.stepName !== "string"
  ) {
    return null;
  }
  const run = numberedFrom(body.run);
  return run === null
    ? null
    : { run, stepId: body.stepId, stepName: body.stepName };
}

function eventValueFrom(body: unknown): EventValue | null {
  if (!isUnchecked(body) || typeof body.field !== "string") {
    return null;
  }
  if (body.earlierShape === true && typeof body.value === "string") {
    return { field: body.field, asKept: body.value };
  }
  return isShownValue(body.value)
    ? { field: body.field, value: body.value }
    : null;
}

function decisionFrom(body: unknown): ReviewDecision | null {
  if (
    !isUnchecked(body) ||
    typeof body.field !== "string" ||
    (body.outcome !== "assured" && body.outcome !== "refused")
  ) {
    return null;
  }
  const why = optional(body, "why", textFrom);
  if (why === null) {
    return null;
  }
  if (body.outcome === "assured") {
    return why === undefined ? { field: body.field, outcome: "assured" } : null;
  }
  return why !== undefined && why.trim().length > 0
    ? { field: body.field, outcome: "refused", why }
    : null;
}

function fromRunFrom(body: unknown): FromRun | null {
  const numbered = numberedFrom(body);
  return numbered !== null &&
    isUnchecked(body) &&
    typeof body.stepId === "string" &&
    typeof body.stepName === "string"
    ? { ...numbered, stepId: body.stepId, stepName: body.stepName }
    : null;
}

function runAboveFrom(body: unknown): RunAbove | null {
  const numbered = numberedFrom(body);
  const name = isUnchecked(body) ? optional(body, "name", textFrom) : null;
  return numbered === null || name === null
    ? null
    : { ...numbered, ...(name === undefined ? {} : { name }) };
}

function ceilingBeneathFrom(body: unknown): CeilingBeneath | null {
  if (!isUnchecked(body)) {
    return null;
  }
  const run = numberedFrom(body.run);
  const ceiling = ceilingFrom(body.ceiling);
  const acts = wordsIn(body.acts);
  return run === null ||
    ceiling === null ||
    "heldBy" in ceiling ||
    acts === null
    ? null
    : { run, ceiling, acts: new Set(acts) };
}

function valuesFrom(body: unknown): ShownValues | null {
  return isUnchecked(body) && Object.values(body).every(isShownValue)
    ? (body as ShownValues)
    : null;
}

function atFrom(body: unknown): RunAt | null {
  if (!isUnchecked(body)) {
    return null;
  }
  const { stepId, name } = body;
  return typeof stepId === "string" && typeof name === "string"
    ? { stepId, name }
    : null;
}

function workflowFrom(body: unknown): Run["workflow"] | null {
  if (!isUnchecked(body)) {
    return null;
  }
  const { entryId, name, version } = body;
  return typeof entryId === "string" &&
    typeof name === "string" &&
    Number.isSafeInteger(version)
    ? { entryId, name, version: version as number }
    : null;
}

export function spendFrom(body: unknown): Spend | null {
  if (!isUnchecked(body)) {
    return null;
  }
  const { sent, cameBack, spent, cameBackUnknown } = body;
  const sentDigits = digitsFrom(sent);
  const cameBackDigits = digitsFrom(cameBack);
  const spentDigits = digitsFrom(spent);
  const measuredHere = optional(
    body,
    "measuredHere",
    (said): Spend["measuredHere"] | null =>
      said === "wholly" || said === "partly" ? said : null,
  );
  return sentDigits !== null &&
    cameBackDigits !== null &&
    spentDigits !== null &&
    typeof cameBackUnknown === "boolean" &&
    measuredHere !== null
    ? {
        sent: sentDigits,
        cameBack: cameBackDigits,
        spent: spentDigits,
        cameBackUnknown,
        ...(measuredHere === undefined ? {} : { measuredHere }),
      }
    : null;
}

/** Held by the ceiling at the top, or holding one of its own: exactly one of the two. */
function ceilingFrom(body: unknown): RunCeiling | null {
  if (!isUnchecked(body)) {
    return null;
  }
  const heldBy = optional(body, "heldBy", numberedFrom);
  if (heldBy === null) {
    return null;
  }
  if (heldBy !== undefined) {
    return Object.keys(body).length === 1 ? { heldBy } : null;
  }
  if (typeof body.raiseNeedsApproval !== "boolean") {
    return null;
  }
  const inForce = optional(body, "inForce", digitsFrom);
  const waiting = optional(body, "waiting", raiseFrom);
  if (inForce === null || waiting === null) {
    return null;
  }
  return {
    raiseNeedsApproval: body.raiseNeedsApproval,
    ...(inForce === undefined ? {} : { inForce }),
    ...(waiting === undefined ? {} : { waiting }),
  };
}

function raiseFrom(body: unknown): Raise | null {
  if (!isUnchecked(body)) {
    return null;
  }
  const { changeId, askedAt } = body;
  const to = optional(body, "to", digitsFrom);
  const askedBy = personFrom(body.askedBy);
  if (
    typeof changeId !== "string" ||
    typeof askedAt !== "string" ||
    to === null ||
    askedBy === null
  ) {
    return null;
  }
  const raise = { changeId, askedBy, askedAt };
  return to === undefined ? raise : { ...raise, to };
}

/** Somebody, or a ceiling reached: exactly one of the two, a stop by neither or by both being none this side can say. */
function stoppedFrom(body: unknown): Stopped | null {
  if (!isUnchecked(body) || typeof body.at !== "string") {
    return null;
  }
  const by = optional(body, "by", personFrom);
  const ceilingOf = optional(body, "ceilingOf", numberedFrom);
  const ceilings = optional(body, "ceilings", (listed) =>
    listOf(listed, numberedFrom),
  );
  if (
    by === null ||
    ceilingOf === null ||
    ceilings === null ||
    ceilings?.length === 0
  ) {
    return null;
  }
  if (by !== undefined && ceilingOf === undefined && ceilings === undefined) {
    return { at: body.at, by };
  }
  if (by === undefined && ceilingOf !== undefined) {
    return ceilings === undefined ||
      (ceilings[0]?.runId === ceilingOf.runId &&
        ceilings[0]?.number === ceilingOf.number)
      ? {
          at: body.at,
          ceilingOf,
          ...(ceilings === undefined ? {} : { ceilings }),
        }
      : null;
  }
  return null;
}

function numberedFrom(body: unknown): NumberedRun | null {
  if (!isUnchecked(body)) {
    return null;
  }
  const { runId, number } = body;
  return typeof runId === "string" && Number.isSafeInteger(number)
    ? { runId, number: number as number }
    : null;
}

function digitsFrom(said: unknown): string | null {
  return typeof said === "string" && DIGITS.test(said) ? said : null;
}
