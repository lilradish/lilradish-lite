import type { WordedRefusal } from "../../../../i18n/app";
import { atSegment } from "../../../../lib/request/address";
import {
  isUnchecked,
  optional,
  textFrom,
  wordsIn,
} from "../../../../lib/request/document";
import { get, patch } from "../../../../lib/request/http";
import { isFillValue, type FillValues } from "../../../filling";
import { GROUPS } from "../../../groups";
import { personFrom, type Person } from "../../../person";
import { servedAmong, type Problem } from "../../../problem";

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
  /** Where any of it is as this system measured it, the model having counted none of a call. */
  readonly measuredHere?: true;
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
  | { readonly at: string; readonly ceilingOf: NumberedRun };

/** A ceiling the run holds of its own. */
export interface OwnCeiling {
  /** Absent where the run may spend without limit. */
  readonly inForce?: string;
  readonly raiseNeedsApproval: boolean;
  readonly waiting?: Raise;
}

/** Its own, or the one at the top of its tree, where a run beneath keeps none of its own. */
export type RunCeiling = OwnCeiling | { readonly heldBy: NumberedRun };

/**
 * One run as the server reads it, which every act on it answers with too.
 * Words are kept as they came: an act this build does not know offers nothing.
 */
export interface Run {
  readonly runId: string;
  readonly number: number;
  /** Absent beneath another run, which the run above started rather than anybody. */
  readonly name?: string;
  /** The run above; absent at the top. */
  readonly above?: string;
  readonly workflow: {
    readonly entryId: string;
    readonly name: string;
    readonly version: number;
  };
  readonly startedBy?: Person;
  /** ISO 8601. */
  readonly startedAt: string;
  /** What was filled when it was started: only at the top of its tree, and no fields where it takes none. */
  readonly startedWith?: FillValues;
  readonly state: string;
  /** Only while it is running. */
  readonly at?: RunAt;
  readonly stopped?: Stopped;
  readonly spend: Spend;
  readonly ceiling: RunCeiling;
  readonly acts: ReadonlySet<string>;
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

const DIGITS = /^(0|[1-9][0-9]*)$/;

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

/** Built member by member; a run missing any of what it is drawn from is not one this side can show. */
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
  const startedBy = optional(body, "startedBy", personFrom);
  const startedWith = optional(body, "startedWith", valuesFrom);
  const stopped = optional(body, "stopped", stoppedFrom);
  const at = optional(body, "at", atFrom);
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
    startedBy === null ||
    startedWith === null ||
    stopped === null ||
    at === null
  ) {
    return null;
  }
  return {
    runId,
    number: number as number,
    workflow,
    startedAt,
    state,
    spend,
    ceiling,
    acts: new Set(acts),
    ...(name === undefined ? {} : { name }),
    ...(above === undefined ? {} : { above }),
    ...(startedBy === undefined ? {} : { startedBy }),
    ...(startedWith === undefined ? {} : { startedWith }),
    ...(stopped === undefined ? {} : { stopped }),
    ...(at === undefined ? {} : { at }),
  };
}

function valuesFrom(body: unknown): FillValues | null {
  return isUnchecked(body) && isFillValue(body) ? body : null;
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
  const measuredHere = optional(body, "measuredHere", (said): true | null =>
    said === true ? true : null,
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
  if (by === null || ceilingOf === null) {
    return null;
  }
  if (by !== undefined && ceilingOf === undefined) {
    return { at: body.at, by };
  }
  if (by === undefined && ceilingOf !== undefined) {
    return { at: body.at, ceilingOf };
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
