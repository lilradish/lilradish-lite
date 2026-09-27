import Box from "@mui/material/Box";

import type { RunRow, RunSortColumn } from "../../api/groups/{groupId}/runs";
import { say } from "../../i18n/app";
import type { Ordering } from "../../lib/collection/ordering";
import { isolatedInText } from "../../lib/direction/isolated";
import { nameOrNumber } from "../../lib/text/names";
import { When } from "../../lib/time/When";
import { stateSaid } from "./runStates";

/** What the table of runs sorts by: every order the server takes but what last happened, which heads no column. */
type RunColumn = Exclude<RunSortColumn, "lastHappened">;

/** Every column the table of runs can be sorted by, closed over its columns in both directions. */
export const RUNS_SORTABLE = {
  run: true,
  name: true,
  workflow: true,
  started: true,
  startedBy: true,
} satisfies Record<RunColumn, true>;

/** On a column no row leaves empty, the newest first. */
export const NEWEST_FIRST: Ordering<RunColumn> = {
  column: "started",
  descending: true,
};

/** The run something last happened in first, the order the runs are listed in as a conversation. */
export const LAST_HAPPENED_FIRST: Ordering<RunSortColumn> = {
  column: "lastHappened",
  descending: true,
};

const KEY_SX = { typography: "identifier" };

/** Where a run is, and the step a running one is on where the server named it. */
export function listedWhereSaid(row: RunRow): string {
  return row.state === "running" && row.at !== undefined
    ? say("runState.runningAt", { step: isolatedInText(row.at) })
    : stateSaid(row.state);
}

/** The workflow a run runs, and the version it ran. */
export function workflowSaid(row: RunRow): string {
  return say("run.workflowVersion", {
    name: isolatedInText(row.workflow.name),
    version: row.workflow.version,
  });
}

/** One row per run, the first cell the link that opens it; every cell a fact, none left empty. */
export function runColumns(groupKey: string) {
  return [
    {
      label: say("work.run"),
      cell: (row: RunRow) => (
        <Box component="span" sx={KEY_SX}>
          {say("work.runKey", { key: groupKey, number: row.number })}
        </Box>
      ),
      sortKey: "run",
    },
    {
      label: say("work.name"),
      cell: (row: RunRow) => row.name,
      sortKey: "name",
    },
    {
      label: say("run.workflow"),
      cell: workflowSaid,
      sortKey: "workflow",
    },
    {
      label: say("run.started"),
      cell: (row: RunRow) => <When iso={row.startedAt} />,
      sortKey: "started",
    },
    {
      label: say("work.startedBy"),
      cell: (row: RunRow) => nameOrNumber(row.startedBy),
      sortKey: "startedBy",
    },
    {
      label: say("run.where"),
      cell: listedWhereSaid,
    },
  ] as const;
}
