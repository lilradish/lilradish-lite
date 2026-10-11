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
import {
  conversationEventFrom,
  type ConversationEvent,
} from "../../../conversationEvents";
import {
  digitsFrom,
  numberedRunFrom,
  spendFrom,
  type NumberedRun,
  type Spend,
} from "../../../runFacts";
import { GROUPS } from "../../../groups";
import { personFrom, type Person } from "../../../person";
import { servedAmong, type Problem } from "../../../problem";
import { inputFrom, type InputFrom } from "../../../stepSources";
import { isVersionName } from "../{kind}";

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
  readonly label?: string;
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

export type Stopped =
  | { readonly at: string; readonly by: Person }
  | {
      readonly at: string;
      readonly ceilingOf: NumberedRun;
      readonly ceilings?: readonly NumberedRun[];
    };

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
  readonly label?: string;
}

/** Every act on a run answers with this shape. */
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
    readonly versionName: string | null;
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
  readonly stateActs: ReadonlySet<string> | null;
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

/** Each identifier escaped as `atSegment` escapes one. */
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

export function refusedForWhatMoved(problem: Problem): boolean {
  return servedAmong(problem, MOVED);
}

export function runFrom(body: unknown): Run | null {
  if (!isUnchecked(body)) {
    return null;
  }
  const { runId, number, startedAt, state } = body;
  const stateActs =
    body.stateActs === null ? null : listOf(body.stateActs, textFrom);
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
    (stateActs === null && body.stateActs !== null) ||
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
    stateActs: stateActs === null ? null : new Set(stateActs),
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

function fromRunFrom(body: unknown): FromRun | null {
  const numbered = numberedRunFrom(body);
  const label = isUnchecked(body) ? optional(body, "label", textFrom) : null;
  return numbered !== null &&
    label !== null &&
    isUnchecked(body) &&
    typeof body.stepId === "string" &&
    typeof body.stepName === "string"
    ? {
        ...numbered,
        stepId: body.stepId,
        stepName: body.stepName,
        ...(label === undefined ? {} : { label }),
      }
    : null;
}

function runAboveFrom(body: unknown): RunAbove | null {
  const numbered = numberedRunFrom(body);
  const name = isUnchecked(body) ? optional(body, "name", textFrom) : null;
  return numbered === null || name === null
    ? null
    : { ...numbered, ...(name === undefined ? {} : { name }) };
}

function ceilingBeneathFrom(body: unknown): CeilingBeneath | null {
  if (!isUnchecked(body)) {
    return null;
  }
  const run = numberedRunFrom(body.run);
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
  const label = optional(body, "label", textFrom);
  return typeof stepId === "string" &&
    typeof name === "string" &&
    label !== null
    ? { stepId, name, ...(label === undefined ? {} : { label }) }
    : null;
}

function workflowFrom(body: unknown): Run["workflow"] | null {
  if (!isUnchecked(body)) {
    return null;
  }
  const { entryId, name, version } = body;
  return typeof entryId === "string" &&
    typeof name === "string" &&
    Number.isSafeInteger(version) &&
    isVersionName(body.versionName)
    ? {
        entryId,
        name,
        version: version as number,
        versionName: body.versionName,
      }
    : null;
}

/** Held by the ceiling at the top, or holding one of its own: exactly one of the two. */
function ceilingFrom(body: unknown): RunCeiling | null {
  if (!isUnchecked(body)) {
    return null;
  }
  const heldBy = optional(body, "heldBy", numberedRunFrom);
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
  const ceilingOf = optional(body, "ceilingOf", numberedRunFrom);
  const ceilings = optional(body, "ceilings", (listed) =>
    listOf(listed, numberedRunFrom),
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
