import Typography from "@mui/material/Typography";
import { useId } from "react";

import type { ReadField } from "../../../api/filling";
import type { Run } from "../../../api/groups/{groupId}/runs/{runId}";
import { say } from "../../../i18n/app";
import { isolatedInText } from "../../../lib/direction/isolated";
import { Part } from "../../../lib/layout/Part";
import { FilledFields } from "../fill/FilledValue";
import { GaveBackPart } from "./GaveBackPart";
import { spokenName } from "./stepTitle";
import { StepsTable } from "./StepsTable";
import { StartedWithSources } from "./StartedWithSources";
import type { RunReading } from "./useRunRead";

export function RunInFullDetail({
  groupId,
  reading,
}: {
  readonly groupId: string;
  readonly reading: RunReading;
}) {
  const stepsId = useId();
  const { run, steps } = reading;
  const declared = steps.declarations.get(steps.run.versionId);
  return (
    <>
      <Part title={say("step.startedWith")} level="h2" depth={1}>
        <StartedWith run={run} takes={declared?.takes} />
        <StartedWithSources
          groupId={groupId}
          run={run}
          takes={declared?.takes}
          declarations={steps.declarations}
          versionId={steps.run.versionId}
        />
      </Part>
      <Part title={say("step.gaveBack")} level="h2" depth={1}>
        <GaveBackPart gaveBack={steps.gaveBack} gives={declared?.gives} />
      </Part>
      <Part
        title={say("step.theSteps")}
        level="h2"
        depth={1}
        headingId={stepsId}
      >
        <StepsTable groupId={groupId} reading={steps} labelledBy={stepsId} />
      </Part>
    </>
  );
}

/** Each value under its label. */
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
        {run.from === undefined
          ? say("step.unknown")
          : say("step.startedAboveWith", {
              step: isolatedInText(spokenName(run.from.stepName)),
              number: run.from.number,
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
