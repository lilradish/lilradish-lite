import Typography from "@mui/material/Typography";

import type { Run } from "../../../api/groups/{groupId}/runs/{runId}";
import type {
  RunSteps,
  StepRow,
} from "../../../api/groups/{groupId}/runs/{runId}/steps";
import { isTheReader } from "../../../api/standing";
import { useStanding } from "../../../app/standing/StandingContext";
import { say } from "../../../i18n/app";
import { isolatedInText } from "../../../lib/direction/isolated";
import { QUIET_SX } from "../../../lib/layout/parts";
import { useCalendarNow } from "../../../lib/time/When";
import { runWhereSaid, stateSaid } from "../runStates";
import { declaredFor } from "./declared";
import { GaveBackPart } from "./GaveBackPart";
import { Message } from "./Message";
import { stepTitle } from "./stepTitle";
import { whereSaid } from "./stepWhereabouts";

export function RunEndMessage({
  run,
  reading,
}: {
  readonly run: Run;
  readonly reading: RunSteps;
}) {
  const standing = useStanding();
  const now = useCalendarNow();
  const gives = reading.declarations.get(reading.run.versionId)?.gives;
  const actsRatherThanAnswers = reading.gaveBack.declares === "nothing";
  if (run.state === "done") {
    return (
      <Message title={stateSaid(run.state)} ending="done">
        {actsRatherThanAnswers ? null : (
          <Typography variant="h3">{say("step.gaveBack")}</Typography>
        )}
        <GaveBackPart gaveBack={reading.gaveBack} gives={gives} />
      </Message>
    );
  }
  const ending = run.state;
  if (ending !== "stopped" && ending !== "failed") {
    return null;
  }
  const failed =
    ending === "failed"
      ? reading.steps.find((step) => step.state === "failed")
      : undefined;
  return (
    <Message
      title={ending === "stopped" ? runWhereSaid(run, now) : stateSaid(ending)}
      ending={ending}
      byTheReader={
        ending === "stopped" &&
        run.stopped !== undefined &&
        "by" in run.stopped &&
        isTheReader(standing, run.stopped.by)
      }
    >
      {failed === undefined ? null : (
        <FailedAt step={failed} reading={reading} now={now} />
      )}
      {actsRatherThanAnswers ? null : (
        <>
          <Typography variant="h3">{say("step.stoodByThen")}</Typography>
          <GaveBackPart gaveBack={reading.gaveBack} gives={gives} />
        </>
      )}
    </Message>
  );
}

function FailedAt({
  step,
  reading,
  now,
}: {
  readonly step: StepRow;
  readonly reading: RunSteps;
  readonly now: Date;
}) {
  const [, ...why] = whereSaid(
    step,
    declaredFor(step, reading.declarations)?.gives,
    undefined,
    now,
    "",
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
