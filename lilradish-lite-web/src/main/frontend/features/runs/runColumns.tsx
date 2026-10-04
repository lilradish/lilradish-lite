import Typography from "@mui/material/Typography";

import type { RunRow, RunSortColumn } from "../../api/groups/{groupId}/runs";
import { say } from "../../i18n/app";
import type { Ordering } from "../../lib/collection/ordering";
import { isolatedInText } from "../../lib/direction/isolated";
import { Identifier } from "../../lib/text/Identifier";
import { nameOrNumber } from "../../lib/text/names";
import { When } from "../../lib/time/When";
import { RunStateBadge } from "./RunStateBadge";

/** Every column the table of runs can be sorted by, closed over its columns in both directions. */
export const RUNS_SORTABLE = {
  run: true,
  name: true,
  workflow: true,
  started: true,
  startedBy: true,
} satisfies Record<RunSortColumn, true>;

/** On a column no row leaves empty, the newest first. */
export const NEWEST_FIRST: Ordering<RunSortColumn> = {
  column: "started",
  descending: true,
};

/** The workflow a run runs, and the version it ran. */
function workflowSaid(row: RunRow): string {
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
        <Identifier
          value={say("work.runKey", { key: groupKey, number: row.number })}
        />
      ),
      sortKey: "run",
    },
    {
      label: say("work.name"),
      cell: (row: RunRow) => (
        <Typography component="strong" variant="subtitle1">
          <bdi>{row.name}</bdi>
        </Typography>
      ),
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
      cell: (row: RunRow) => <RunStateBadge state={row.state} at={row.at} />,
    },
  ] as const;
}
