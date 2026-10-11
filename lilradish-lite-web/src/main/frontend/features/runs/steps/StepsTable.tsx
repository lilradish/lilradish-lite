import Box from "@mui/material/Box";
import Link from "@mui/material/Link";
import Table from "@mui/material/Table";
import TableBody from "@mui/material/TableBody";
import TableCell from "@mui/material/TableCell";
import TableHead from "@mui/material/TableHead";
import TableRow from "@mui/material/TableRow";
import Typography from "@mui/material/Typography";
import { Link as RouterLink, useLocation } from "react-router";

import type { RunSteps } from "../../../api/groups/{groupId}/runs/{runId}/steps";
import type { GroupStanding } from "../../../api/standing";
import { say } from "../../../i18n/app";
import { readersIntl } from "../../../i18n/intl";
import { QUIET_SX, TITLE_LINE_SX } from "../../../lib/layout/parts";
import { useCalendarNow } from "../../../lib/time/When";
import { StepKindTag } from "../../library/StepKindTag";
import { listQuery, stepHref, WITHIN_THE_RUN } from "../runAddress";
import { CostDrawn } from "../spend";
import { BeneathLinks } from "./BeneathLinks";
import { declaredFor, labelAt, fromSaid, type Declaring } from "./declared";
import { Field, Fields } from "../../../lib/form/Fields";
import { StepStateBadge } from "./StepStateBadge";
import { spokenName } from "./stepTitle";
import { whereSaid } from "./stepWhereabouts";

/** One row per step in the order they run, which is what the order means, so nothing sorts it. */
export function StepsTable({
  group,
  reading,
  labelledBy,
}: {
  readonly group: GroupStanding;
  readonly reading: RunSteps;
  readonly labelledBy: string;
}) {
  const { search } = useLocation();
  const now = useCalendarNow();
  const { declarations, steps } = reading;
  const declaring: Declaring = {
    runId: reading.run.runId,
    declarations,
    runVersionId: reading.run.versionId,
    steps,
  };
  return (
    <Table size="small" aria-labelledby={labelledBy}>
      <TableHead>
        <TableRow>
          <TableCell data-compact="true">{say("step.order")}</TableCell>
          <TableCell>{say("step.name")}</TableCell>
          <TableCell>{say("step.takesFrom")}</TableCell>
          <TableCell data-compact="true">{say("run.where")}</TableCell>
          <TableCell data-compact="true">{say("run.usage")}</TableCell>
        </TableRow>
      </TableHead>
      <TableBody>
        {steps.map((step) => {
          const [, ...why] = whereSaid(
            step,
            declaredFor(step, declarations)?.gives,
            undefined,
            now,
            "",
          );
          return (
            <TableRow key={step.stepId}>
              <TableCell data-compact="true">
                {readersIntl.formatNumber(step.order)}
              </TableCell>
              <TableCell component="th" scope="row">
                <Box sx={TITLE_LINE_SX}>
                  <Link
                    component={RouterLink}
                    to={`${stepHref(group, reading.run.runId, step.stepId)}${listQuery(search)}`}
                    state={WITHIN_THE_RUN}
                  >
                    <bdi>{step.label ?? spokenName(step.name)}</bdi>
                  </Link>
                  <StepKindTag kind={step.runs.kind} />
                </Box>
              </TableCell>
              <TableCell>
                {step.takesFrom.length === 0 ? (
                  say("step.takesNothing")
                ) : (
                  <Fields>
                    {step.takesFrom.map((taken) => (
                      <Field
                        key={taken.input}
                        label={labelAt(
                          declaredFor(step, declarations)?.takes,
                          taken.input,
                        )}
                      >
                        {fromSaid(taken.from, declaring)}
                      </Field>
                    ))}
                  </Fields>
                )}
              </TableCell>
              <TableCell data-compact="true">
                <StepStateBadge state={step.state} />
                {why.map((line, at) => (
                  <Typography key={at} variant="body2" sx={QUIET_SX}>
                    {line}
                  </Typography>
                ))}
                {step.beneath === undefined ? null : (
                  <Typography variant="body2">
                    <BeneathLinks group={group} beneath={step.beneath} />
                  </Typography>
                )}
              </TableCell>
              <TableCell data-compact="true">
                <CostDrawn cost={step.cost} />
              </TableCell>
            </TableRow>
          );
        })}
      </TableBody>
    </Table>
  );
}
