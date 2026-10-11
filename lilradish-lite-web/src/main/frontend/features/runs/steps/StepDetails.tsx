import Box from "@mui/material/Box";
import Link from "@mui/material/Link";
import Table from "@mui/material/Table";
import TableBody from "@mui/material/TableBody";
import TableCell from "@mui/material/TableCell";
import TableHead from "@mui/material/TableHead";
import TableRow from "@mui/material/TableRow";
import Typography from "@mui/material/Typography";
import { Link as RouterLink, useLocation } from "react-router";

import type {
  Declared,
  RunSteps,
  StepRow,
} from "../../../api/groups/{groupId}/runs/{runId}/steps";
import type { StepAnswer } from "../../../api/groups/{groupId}/runs/{runId}/steps/{stepId}";
import type { GroupStanding } from "../../../api/standing";
import { say } from "../../../i18n/app";
import { visible } from "../../../i18n/visible";
import { TABLE_SX, CELL_SX } from "../../../lib/collection/table";
import { Field, Fields } from "../../../lib/form/Fields";
import { ProseScope } from "../../../lib/text/ModelProse";
import { VersionLabel } from "../../../lib/text/VersionLabel";
import { listQuery, stepHref, WITHIN_THE_RUN } from "../runAddress";
import { fromSaid, labelAt, type Declaring } from "./declared";
import { Part, PARTS_SX, TryParts, UsageDrawn } from "./TryPanel";
import { TryMaterial } from "./TryRead";

export function StepDetails({
  group,
  answer,
  reading,
  step,
  shownNumber,
  declared,
  declaring,
}: {
  readonly group: GroupStanding;
  readonly answer: StepAnswer;
  readonly reading: RunSteps;
  readonly step: StepRow;
  readonly shownNumber: number | undefined;
  readonly declared: Declared | undefined;
  readonly declaring: Declaring;
}) {
  const { search } = useLocation();
  const shown = answer.triesMade.find((each) => each.number === shownNumber);
  const inputs = (
    <Part title={say("step.wentIn")} level="h3">
      <Fields properties>
        {step.runs.versionName === null ||
        step.runs.version === undefined ||
        step.runs.name === undefined ? null : (
          <Field label={say("step.runs")}>
            {visible("run.workflowVersion", {
              label: (
                <VersionLabel
                  entry={step.runs.name}
                  number={step.runs.version}
                  name={step.runs.versionName}
                />
              ),
            })}
          </Field>
        )}
      </Fields>
      {step.takesFrom.length === 0 ? (
        <Typography variant="body2">{say("step.takesNothing")}</Typography>
      ) : (
        <Table aria-label={say("step.wentIn")} sx={TABLE_SX}>
          <TableHead>
            <TableRow>
              <TableCell>{say("step.inputColumn")}</TableCell>
              <TableCell>{say("step.fromColumn")}</TableCell>
            </TableRow>
          </TableHead>
          <TableBody sx={CELL_SX}>
            {step.takesFrom.map((taken) => (
              <TableRow key={taken.input}>
                <TableCell component="th" scope="row">
                  {labelAt(declared?.takes, taken.input)}
                </TableCell>
                <TableCell>{fromSaid(taken.from, declaring)}</TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      )}
    </Part>
  );
  return (
    <ProseScope>
      {shown === undefined ? (
        <Box sx={PARTS_SX.message}>
          <section>
            <Fields properties>
              <Field label={say("run.usage")}>
                <UsageDrawn cost={step.cost} />
              </Field>
            </Fields>
            {step.tries === undefined || step.tries.current === 0 ? (
              <Typography variant="body2">{say("step.noTries")}</Typography>
            ) : null}
          </section>
          {inputs}
        </Box>
      ) : (
        <TryMaterial number={shown.number}>
          {(material) => (
            <TryParts
              key={shown.number}
              within="message"
              aTry={shown}
              field={undefined}
              material={material}
              declared={declared}
              declaring={declaring}
              group={group}
              usage={step.cost}
              inputs={inputs}
            />
          )}
        </TryMaterial>
      )}
      <Link
        component={RouterLink}
        to={`${stepHref(group, reading.run.runId, step.stepId)}${listQuery(search)}`}
        state={WITHIN_THE_RUN}
      >
        {say("step.openStep")}
      </Link>
    </ProseScope>
  );
}
