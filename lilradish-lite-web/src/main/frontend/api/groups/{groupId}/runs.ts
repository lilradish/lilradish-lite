import { atSegment } from "../../../lib/request/address";
import {
  countFrom,
  isUnchecked,
  optional,
  textFrom,
  type Unchecked,
} from "../../../lib/request/document";
import { post } from "../../../lib/request/http";
import type { Page } from "../../../lib/request/page";
import {
  pagedLoaderSaying,
  type ListQuery,
} from "../../../lib/request/pagedLoader";
import type { FillValues } from "../../filling";
import { GROUPS } from "../../groups";
import { personFrom, type Person } from "../../person";
import { servedAmong, type Problem } from "../../problem";

/** The columns a group's runs can be sorted by, spelt as the server takes them; where a run is, is none of them. */
export type RunSortColumn =
  "run" | "name" | "workflow" | "started" | "startedBy" | "lastHappened";

/** One column, led by a hyphen for descending. */
export type RunOrder = RunSortColumn | `-${RunSortColumn}`;

export type RunQuery = ListQuery<RunOrder>;

/** Which of the group's runs the server read for the reader: all of them, the ones they started, or none. */
export type RunReading = "all" | "own" | "none";

/** A page of runs, saying which runs the server read them from. */
export interface RunsPage extends Page<RunRow> {
  readonly reading: RunReading;
}

/** One run at the top of a group. `runId` addresses the row and is never shown. */
export interface RunRow {
  readonly runId: string;
  readonly number: number;
  /** Shown isolated wherever it is shown: a name may carry a directional control. */
  readonly name: string;
  readonly workflow: { readonly name: string; readonly version: number };
  readonly startedBy: Person;
  /** ISO 8601. */
  readonly startedAt: string;
  /** ISO 8601. */
  readonly lastHappenedAt: string;
  readonly state: string;
  /** What its workflow calls the step it is on; only while it is running. */
  readonly at?: string;
}

const READINGS: Readonly<Record<RunReading, true>> = {
  all: true,
  own: true,
  none: true,
};

const NOT_OFFERED: ReadonlySet<string> = new Set(["WORKFLOW_NOT_OFFERED"]);

const NAME_UNUSABLE: ReadonlySet<string> = new Set(["RUN_NAME_UNUSABLE"]);

/** A run to start: what it is called, the version it runs, and every field that version takes. */
interface RunStart {
  readonly name: string;
  readonly versionId: string;
  readonly values: FillValues;
}

/**
 * A run begun, the number it took, and whether the reader may read it, as the server judges. Nothing more is
 * answered, since whoever may start a run need not be one who may read it.
 */
export interface StartedRun {
  readonly runId: string;
  readonly number: number;
  readonly readable: boolean;
}

export function startRun(
  groupId: string,
  start: RunStart,
  signal: AbortSignal,
): Promise<StartedRun> {
  return atSegment(GROUPS, groupId, (group) =>
    post(`${group}/runs`, start, signal, startedFrom),
  );
}

/** Whether the version asked for is not one this group may start any more, if it ever was. */
export function refusedAsNotOffered(problem: Problem): boolean {
  return servedAmong(problem, NOT_OFFERED);
}

/** Whether the name asked for is none a run may be called. */
export function refusedForTheName(problem: Problem): boolean {
  return servedAmong(problem, NAME_UNUSABLE);
}

export function runsLoader(
  groupId: string,
  query: RunQuery,
): (cursor: string | null, signal: AbortSignal) => Promise<RunsPage> {
  return (cursor, signal) =>
    atSegment(GROUPS, groupId, (group) =>
      pagedLoaderSaying(
        `${group}/runs`,
        runRowFrom,
        query,
        readingFrom,
      )(cursor, signal),
    );
}

/** A reading this build has never heard of is not one it can say anything true about. */
function readingFrom(body: Unchecked): { reading: RunReading } | null {
  const { reading } = body;
  return typeof reading === "string" && Object.hasOwn(READINGS, reading)
    ? { reading: reading as RunReading }
    : null;
}

/** Built member by member; a row missing any of what it is drawn from is not one this side can show. */
function runRowFrom(body: unknown): RunRow | null {
  if (!isUnchecked(body)) {
    return null;
  }
  const { runId, number, name, startedAt, lastHappenedAt, state } = body;
  const workflow = workflowFrom(body.workflow);
  const startedBy = personFrom(body.startedBy);
  const at = optional(body, "at", textFrom);
  if (
    typeof runId !== "string" ||
    !Number.isSafeInteger(number) ||
    typeof name !== "string" ||
    typeof startedAt !== "string" ||
    typeof lastHappenedAt !== "string" ||
    typeof state !== "string" ||
    at === null ||
    workflow === null ||
    startedBy === null
  ) {
    return null;
  }
  return {
    runId,
    number: number as number,
    name,
    workflow,
    startedBy,
    startedAt,
    lastHappenedAt,
    state,
    ...(at === undefined ? {} : { at }),
  };
}

function startedFrom(body: unknown): StartedRun | null {
  if (!isUnchecked(body)) {
    return null;
  }
  const { runId, readable } = body;
  const number = countFrom(body.number);
  return typeof runId === "string" &&
    number !== null &&
    typeof readable === "boolean"
    ? { runId, number, readable }
    : null;
}

function workflowFrom(body: unknown): RunRow["workflow"] | null {
  if (!isUnchecked(body)) {
    return null;
  }
  const { name, version } = body;
  return typeof name === "string" && Number.isSafeInteger(version)
    ? { name, version: version as number }
    : null;
}
