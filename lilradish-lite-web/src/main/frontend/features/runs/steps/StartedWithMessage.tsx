import Typography from "@mui/material/Typography";

import type { ReadField } from "../../../api/filling";
import type { Run } from "../../../api/groups/{groupId}/runs/{runId}";
import type { Declared } from "../../../api/groups/{groupId}/runs/{runId}/steps";
import { isTheReader } from "../../../api/standing";
import { useStanding } from "../../../app/standing/StandingContext";
import { say } from "../../../i18n/app";
import { isolatedInText } from "../../../lib/direction/isolated";
import { nameOrNumber } from "../../../lib/text/names";
import { spokenName } from "./stepTitle";
import { FilledFields } from "../fill/FilledValue";
import { Message } from "./Message";
import { StartedWithSources } from "./StartedWithSources";

export function StartedWithMessage({
  groupId,
  run,
  takes,
  declarations,
  versionId,
}: {
  readonly groupId: string;
  readonly run: Run;
  readonly takes: readonly ReadField[] | undefined;
  readonly declarations: ReadonlyMap<string, Declared>;
  readonly versionId: string;
}) {
  const standing = useStanding();
  const values = run.startedWith;
  const declared =
    takes === undefined || takes.length === 0 ? undefined : takes;
  const workflow = isolatedInText(run.workflow.name);
  return (
    <Message
      title={say("step.startedWith")}
      byTheReader={isTheReader(standing, run.startedBy)}
    >
      <Typography variant="body1">
        {run.above === undefined
          ? run.startedBy === undefined
            ? say("step.startedByGoneWith", { workflow })
            : say("step.startedByWith", {
                workflow,
                name: isolatedInText(nameOrNumber(run.startedBy)),
              })
          : say("step.startedAboveWith", {
              workflow,
              step: isolatedInText(spokenName(run.from!.stepName)),
              number: run.from!.number,
            })}
      </Typography>
      {values === undefined ? null : declared === undefined &&
        Object.keys(values).length === 0 ? (
        <Typography variant="body2">
          {say("step.startedWithNothing")}
        </Typography>
      ) : (
        <FilledFields fields={declared} values={values} shut={true} />
      )}
      <StartedWithSources
        groupId={groupId}
        run={run}
        takes={takes}
        declarations={declarations}
        versionId={versionId}
      />
    </Message>
  );
}
