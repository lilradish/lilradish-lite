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
import { QUIET_SX } from "../../../lib/layout/parts";
import { listQuery, stepHref, WITHIN_THE_RUN } from "../runAddress";
import { CostDrawn } from "../spend";
import { declaredFor, takenSaid, type Declaring } from "./declared";
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
  const { declarations, steps } = reading;
  const declaring: Declaring = {
    declarations,
    runVersionId: reading.run.versionId,
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
          const [state, ...why] = whereSaid(
            step,
            declaredFor(step, declarations)?.gives,
          );
          return (
            <TableRow key={step.stepId}>
              <TableCell>{step.order}</TableCell>
              <TableCell component="th" scope="row">
                <Link
                  component={RouterLink}
                  to={`${stepHref(groupId, reading.run.runId, step.stepId)}${listQuery(search)}`}
                  state={WITHIN_THE_RUN}
                >
                  <bdi>{spokenName(step.name)}</bdi>
                </Link>
              </TableCell>
              <TableCell>
                {step.takesFrom.length === 0 ? (
                  say("step.takesNothing")
                ) : (
                  <Box component="ul" sx={TAKEN_SX}>
                    {step.takesFrom.map((taken) => (
                      <li key={taken.input}>
                        {takenSaid(taken, step, declaring)}
                      </li>
                    ))}
                  </Box>
                )}
              </TableCell>
              <TableCell>
                {state}
                {why.map((line, at) => (
                  <Typography key={at} variant="body2" sx={QUIET_SX}>
                    {line}
                  </Typography>
                ))}
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
