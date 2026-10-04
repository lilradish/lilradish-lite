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
import { say } from "../../../i18n/app";
import { QUIET_SX, TITLE_LINE_SX } from "../../../lib/layout/parts";
import { useCalendarNow } from "../../../lib/time/When";
import { StepKindTag } from "../../library/StepKindTag";
import { listQuery, stepHref, WITHIN_THE_RUN } from "../runAddress";
import { CostDrawn } from "../spend";
import { BeneathLinks } from "./BeneathLinks";
import { declaredFor, takenSaid, type Declaring } from "./declared";
import { StepStateBadge } from "./StepStateBadge";
import { whereSaid } from "./stepStates";
import { spokenName } from "./stepTitle";

const TAKEN_SX = { m: 0, pl: 2 };

/** One row per step in the order they run, which is what the order means, so nothing sorts it. */
export function StepsTable({
  groupId,
  reading,
  labelledBy,
}: {
  readonly groupId: string;
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
          <TableCell>{say("step.order")}</TableCell>
          <TableCell>{say("step.name")}</TableCell>
          <TableCell>{say("step.takesFrom")}</TableCell>
          <TableCell>{say("run.where")}</TableCell>
          <TableCell>{say("step.cost")}</TableCell>
        </TableRow>
      </TableHead>
      <TableBody>
        {steps.map((step) => {
          const [, ...why] = whereSaid(
            step,
            declaredFor(step, declarations)?.gives,
            undefined,
            now,
          );
          return (
            <TableRow key={step.stepId}>
              <TableCell>{step.order}</TableCell>
              <TableCell component="th" scope="row">
                <Box sx={TITLE_LINE_SX}>
                  <Link
                    component={RouterLink}
                    to={`${stepHref(groupId, reading.run.runId, step.stepId)}${listQuery(search)}`}
                    state={WITHIN_THE_RUN}
                  >
                    <bdi>{spokenName(step.name)}</bdi>
                  </Link>
                  <StepKindTag kind={step.runs.kind} />
                </Box>
              </TableCell>
              <TableCell>
                {step.takesFrom.length === 0 ? (
                  say("step.takesNothing")
                ) : (
                  <Box component="ul" sx={TAKEN_SX}>
                    {step.takesFrom.map((taken) => (
                      <li key={taken.sourcePartId ?? taken.input}>
                        {takenSaid(taken, step, declaring)}
                      </li>
                    ))}
                  </Box>
                )}
              </TableCell>
              <TableCell>
                <StepStateBadge state={step.state} />
                {why.map((line, at) => (
                  <Typography key={at} variant="body2" sx={QUIET_SX}>
                    {line}
                  </Typography>
                ))}
                {step.beneath === undefined ? null : (
                  <Typography variant="body2">
                    <BeneathLinks groupId={groupId} beneath={step.beneath} />
                  </Typography>
                )}
              </TableCell>
              <TableCell>
                <CostDrawn cost={step.cost} />
              </TableCell>
            </TableRow>
          );
        })}
      </TableBody>
    </Table>
  );
}
