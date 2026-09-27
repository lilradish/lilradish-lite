import Link from "@mui/material/Link";
import Typography from "@mui/material/Typography";
import { Link as RouterLink, useLocation } from "react-router";

import type { ReadField } from "../../../api/filling";
import type { Run } from "../../../api/groups/{groupId}/runs/{runId}";
import { say } from "../../../i18n/app";
import { isolatedInText } from "../../../lib/direction/isolated";
import { nameOrNumber } from "../../../lib/text/names";
import { whenText } from "../../../lib/time/When";
import { FilledFields } from "../fill/FilledValue";
import { listQuery, runHref } from "../runAddress";
import { Message } from "./Message";

/** Beneath another run no value it was started with is published, so none is drawn. */
export function StartedWithMessage({
  groupId,
  run,
  takes,
}: {
  readonly groupId: string;
  readonly run: Run;
  /** What the run's version takes; absent where the read does not name it. */
  readonly takes: readonly ReadField[] | undefined;
}) {
  const search = listQuery(useLocation().search);
  const values = run.startedWith;
  const declared =
    takes === undefined || takes.length === 0 ? undefined : takes;
  const said = {
    when: whenText(run.startedAt),
    workflow: isolatedInText(run.workflow.name),
    version: run.workflow.version,
  };
  return (
    <Message title={say("step.startedWith")}>
      <Typography variant="body1">
        {run.startedBy === undefined
          ? say("step.startedAboveOn", said)
          : say("step.startedByOn", {
              ...said,
              name: isolatedInText(nameOrNumber(run.startedBy)),
            })}
      </Typography>
      {run.above === undefined ? null : (
        <Link
          component={RouterLink}
          to={`${runHref(groupId, run.above)}${search}`}
        >
          {say("run.openAbove")}
        </Link>
      )}
      {values === undefined ? null : declared === undefined &&
        Object.keys(values).length === 0 ? (
        <Typography variant="body2">
          {say("step.startedWithNothing")}
        </Typography>
      ) : (
        <FilledFields fields={declared} values={values} shut={true} />
      )}
    </Message>
  );
}
