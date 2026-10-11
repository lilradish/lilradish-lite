import Typography from "@mui/material/Typography";
import { useId } from "react";

import type { ReadField } from "../../../api/filling";
import type { Run } from "../../../api/groups/{groupId}/runs/{runId}";
import type { GroupStanding } from "../../../api/standing";
import { say } from "../../../i18n/app";
import { isolatedInText } from "../../../lib/direction/isolated";
import { Part } from "../../../lib/layout/Part";
import { FilledFields } from "../fill/FilledValue";
import { GaveBackPart } from "./GaveBackPart";
import { spokenName } from "./stepTitle";
import { StepsTable } from "./StepsTable";
import { StartedWithSources } from "./StartedWithSources";
import type { RunReading } from "./useRunRead";
import { ProseScope } from "../../../lib/text/ModelProse";
import { Message } from "./Message";

export function RunInFullDetail({
  group,
  reading,
}: {
  readonly group: GroupStanding;
  readonly reading: RunReading;
}) {
  const stepsId = useId();
  const { run, steps } = reading;
  const declared = steps.declarations.get(steps.run.versionId);
  return (
    <>
      <Message
        title={say("step.startedWith")}
        component="section"
        conversation={false}
      >
        <ProseScope>
          <StartedWith run={run} takes={declared?.takes} />
          <StartedWithSources
            group={group}
            run={run}
            takes={declared?.takes}
            declarations={steps.declarations}
            versionId={steps.run.versionId}
          />
        </ProseScope>
      </Message>
      <Message
        title={say("step.gaveBack")}
        component="section"
        conversation={false}
      >
        <GaveBackPart gaveBack={steps.gaveBack} gives={declared?.gives} />
      </Message>
      <Part
        title={say("step.theSteps")}
        level="h2"
        depth={1}
        headingId={stepsId}
      >
        <StepsTable group={group} reading={steps} labelledBy={stepsId} />
      </Part>
    </>
  );
}

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
              step: isolatedInText(
                run.from.label ?? spokenName(run.from.stepName),
              ),
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
      cards
      fields={takes === undefined || takes.length === 0 ? undefined : takes}
      values={values}
      shut={false}
    />
  );
}
