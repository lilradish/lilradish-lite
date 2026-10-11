import { atSegment } from "../../../lib/request/address";
import {
  countFrom,
  isUnchecked,
  optional,
  textFrom,
} from "../../../lib/request/document";
import { post, postForm } from "../../../lib/request/http";
import type { Page } from "../../../lib/request/page";
import { pagedLoader, type ListQuery } from "../../../lib/request/pagedLoader";
import { multipartOf, type FillSubmission } from "../../filling";
import { GROUPS } from "../../groups";
import { personFrom, type Person } from "../../person";
import { servedAmong, type Problem } from "../../problem";
import { isVersionName } from "./{kind}";

/** The columns a group's runs can be sorted by, spelt as the server takes them; where a run is, is none of them. */
export type RunSortColumn =
  "run" | "name" | "workflow" | "started" | "startedBy";

/** One column, led by a hyphen for descending. */
export type RunOrder = RunSortColumn | `-${RunSortColumn}`;

export type RunQuery = ListQuery<RunOrder>;

/** A page of runs already limited to what its reader may read. */
export type RunsPage = Page<RunRow>;

/** One run at the top of a group. `runId` addresses the row and is never shown. */
export interface RunRow {
  readonly runId: string;
  readonly number: number;
  /** Shown isolated wherever it is shown: a name may carry a directional control. */
  readonly name: string;
  readonly workflow: {
    readonly name: string;
    readonly version: number;
    readonly versionName: string | null;
  };
  readonly startedBy: Person;
  /** ISO 8601. */
  readonly startedAt: string;
  readonly state: string;
  /** What its workflow calls the step it is on; only while it is running. */
  readonly at?: string;
  readonly atLabel?: string;
}

const NOT_OFFERED: ReadonlySet<string> = new Set(["WORKFLOW_NOT_OFFERED"]);

const NAME_UNUSABLE: ReadonlySet<string> = new Set(["RUN_NAME_UNUSABLE"]);

interface RunStart extends FillSubmission {
  readonly name: string;
  readonly versionId: string;
}

/** Read permission is held in the reader's standing. */
export interface StartedRun {
  readonly runId: string;
  readonly number: number;
}

export function startRun(
  groupId: string,
  start: RunStart,
  signal: AbortSignal,
): Promise<StartedRun> {
  return atSegment(GROUPS, groupId, (group) => {
    const document = {
      name: start.name,
      versionId: start.versionId,
      values: start.values,
    };
    return start.hasFilePositions
      ? postForm(
          `${group}/runs`,
          multipartOf(document, start.uploads),
          signal,
          startedFrom,
        )
      : post(`${group}/runs`, document, signal, startedFrom);
  });
}

/** Whether the problem says the version asked for is not one this group may start. */
export function refusedAsNotOffered(problem: Problem): boolean {
  return servedAmong(problem, NOT_OFFERED);
}

export function refusedForTheName(problem: Problem): boolean {
  return servedAmong(problem, NAME_UNUSABLE);
}

export function runsLoader(
  groupId: string,
  query: RunQuery,
): (cursor: string | null, signal: AbortSignal) => Promise<RunsPage> {
  return (cursor, signal) =>
    atSegment(GROUPS, groupId, (group) =>
      pagedLoader(`${group}/runs`, runRowFrom, query)(cursor, signal),
    );
}

function runRowFrom(body: unknown): RunRow | null {
  if (!isUnchecked(body)) {
    return null;
  }
  const { runId, number, name, startedAt, state } = body;
  const workflow = workflowFrom(body.workflow);
  const startedBy = personFrom(body.startedBy);
  const at = optional(body, "at", textFrom);
  const atLabel = optional(body, "atLabel", textFrom);
  if (
    typeof runId !== "string" ||
    !Number.isSafeInteger(number) ||
    typeof name !== "string" ||
    typeof startedAt !== "string" ||
    typeof state !== "string" ||
    at === null ||
    atLabel === null ||
    (atLabel !== undefined && at === undefined) ||
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
    state,
    ...(at === undefined ? {} : { at }),
    ...(atLabel === undefined ? {} : { atLabel }),
  };
}

function startedFrom(body: unknown): StartedRun | null {
  if (!isUnchecked(body)) {
    return null;
  }
  const { runId } = body;
  const number = countFrom(body.number);
  return typeof runId === "string" && number !== null
    ? { runId, number }
    : null;
}

function workflowFrom(body: unknown): RunRow["workflow"] | null {
  if (!isUnchecked(body)) {
    return null;
  }
  const { name, version } = body;
  return typeof name === "string" &&
    Number.isSafeInteger(version) &&
    isVersionName(body.versionName)
    ? { name, version: version as number, versionName: body.versionName }
    : null;
}
