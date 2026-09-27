import Box from "@mui/material/Box";
import Typography from "@mui/material/Typography";
import { useId } from "react";

import type { ReadField } from "../../../api/filling";
import type { Run } from "../../../api/groups/{groupId}/runs/{runId}";
import type { GaveBack } from "../../../api/groups/{groupId}/runs/{runId}/steps";
import { say } from "../../../i18n/app";
import { isolatedInText } from "../../../lib/direction/isolated";
import { Field, Fields } from "../../../lib/form/Fields";
import { FilledFields, FilledValue } from "../fill/FilledValue";
import { fieldAt, labelAt } from "./declared";
import { StepsTable } from "./StepsTable";
import type { RunReading } from "./useRunRead";

const PART_SX = { mt: 3 };

/** Under the run's header as it is drawn in detail: what it was started with, what it gave back, and its steps. */
export function RunInDetail({
  groupId,
  reading,
}: {
  readonly groupId: string;
  readonly reading: RunReading;
}) {
  const startedId = useId();
  const gaveBackId = useId();
  const stepsId = useId();
  const { run, steps } = reading;
  const declared = steps.declarations.get(steps.run.versionId);
  return (
    <>
      <Box component="section" aria-labelledby={startedId} sx={PART_SX}>
        <Typography id={startedId} variant="h6" component="h3">
          {say("step.startedWith")}
        </Typography>
        <StartedWith run={run} takes={declared?.takes} />
      </Box>
      <Box component="section" aria-labelledby={gaveBackId} sx={PART_SX}>
        <Typography id={gaveBackId} variant="h6" component="h3">
          {say("step.gaveBack")}
        </Typography>
        <GaveBackPart gaveBack={steps.gaveBack} gives={declared?.gives} />
      </Box>
      <Box component="section" aria-labelledby={stepsId} sx={PART_SX}>
        <Typography id={stepsId} variant="h6" component="h3">
          {say("step.theSteps")}
        </Typography>
        <StepsTable groupId={groupId} reading={steps} labelledBy={stepsId} />
      </Box>
    </>
  );
}

/** Each value under its label; where none is published, which is only beneath another run, who started it. */
function StartedWith({
  run,
  takes,
}: {
  readonly run: Run;
  readonly takes: readonly ReadField[] | undefined;
}) {
  const values = run.startedWith;
  if (values === undefined) {
    return (
      <Typography variant="body2">
        {say("step.startedAboveWith", {
          workflow: isolatedInText(run.workflow.name),
        })}
      </Typography>
    );
  }
  if ((takes ?? []).length === 0 && Object.keys(values).length === 0) {
    return (
      <Typography variant="body2">{say("step.startedWithNothing")}</Typography>
    );
  }
  return (
    <FilledFields
      fields={takes === undefined || takes.length === 0 ? undefined : takes}
      values={values}
      shut={false}
    />
  );
}

/** Each value that stands under its label; where none stands yet, or none is given back, that is said, never an empty list. */
export function GaveBackPart({
  gaveBack,
  gives,
}: {
  readonly gaveBack: GaveBack;
  readonly gives: readonly ReadField[] | undefined;
}) {
  if (gaveBack.declares === "nothing") {
    return <Typography variant="body2">{say("step.givesNothing")}</Typography>;
  }
  if (gaveBack.declares !== "values") {
    return <Typography variant="body2">{say("step.unknown")}</Typography>;
  }
  const standing = gaveBack.standing ?? [];
  if (standing.length === 0) {
    return (
      <Typography variant="body2">{say("step.nothingStandsYet")}</Typography>
    );
  }
  return (
    <Fields>
      {standing.map((each) => (
        <Field key={each.field} label={labelAt(gives, each.field)}>
          <FilledValue
            field={fieldAt(gives, each.field)}
            label={labelAt(gives, each.field)}
            shown={each}
            shut={false}
          />
        </Field>
      ))}
    </Fields>
  );
}
