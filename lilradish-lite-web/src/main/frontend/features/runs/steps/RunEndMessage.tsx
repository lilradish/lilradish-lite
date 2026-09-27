import Typography from "@mui/material/Typography";

import type { Run } from "../../../api/groups/{groupId}/runs/{runId}";
import type {
  RunSteps,
  StepRow,
} from "../../../api/groups/{groupId}/runs/{runId}/steps";
import { say } from "../../../i18n/app";
import { isolatedInText } from "../../../lib/direction/isolated";
import { QUIET_SX } from "../../../lib/layout/parts";
import { runWhereSaid, stateSaid } from "../runStates";
import { declaredFor } from "./declared";
import { Message } from "./Message";
import { GaveBackPart } from "./RunInDetail";
import { whereSaid } from "./stepStates";
import { stepTitle } from "./stepTitle";

export function RunEndMessage({
  run,
  reading,
}: {
  readonly run: Run;
  readonly reading: RunSteps;
}) {
  const gives = reading.declarations.get(reading.run.versionId)?.gives;
  const actsRatherThanAnswers = reading.gaveBack.declares === "nothing";
  if (run.state === "done") {
    return (
      <Message title={stateSaid(run.state)}>
        {actsRatherThanAnswers ? null : (
          <Typography variant="subtitle2" component="h4">
            {say("step.gaveBack")}
          </Typography>
        )}
        <GaveBackPart gaveBack={reading.gaveBack} gives={gives} />
      </Message>
    );
  }
  if (run.state !== "stopped" && run.state !== "failed") {
    return null;
  }
  const failed =
    run.state === "failed"
      ? reading.steps.find((step) => step.state === "failed")
      : undefined;
  return (
    <Message
      title={run.state === "stopped" ? runWhereSaid(run) : stateSaid(run.state)}
    >
      {failed === undefined ? null : (
        <FailedAt step={failed} reading={reading} />
      )}
      {actsRatherThanAnswers ? null : (
        <>
          <Typography variant="subtitle2" component="h4">
            {say("step.stoodByThen")}
          </Typography>
          <GaveBackPart gaveBack={reading.gaveBack} gives={gives} />
        </>
      )}
    </Message>
  );
}

/** The step that failed, by its message's title, and why it goes no further. */
function FailedAt({
  step,
  reading,
}: {
  readonly step: StepRow;
  readonly reading: RunSteps;
}) {
  const [, ...why] = whereSaid(
    step,
    declaredFor(step, reading.declarations)?.gives,
  );
  return (
    <>
      <Typography variant="body1">
        {say("step.failedAt", { step: isolatedInText(stepTitle(step)) })}
      </Typography>
      {why.map((line, at) => (
        <Typography key={at} variant="body2" sx={QUIET_SX}>
          {line}
        </Typography>
      ))}
    </>
  );
}
