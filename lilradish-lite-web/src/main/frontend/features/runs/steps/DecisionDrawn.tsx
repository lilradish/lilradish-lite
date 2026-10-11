import Box from "@mui/material/Box";
import Link from "@mui/material/Link";
import Typography from "@mui/material/Typography";
import { Link as RouterLink, useLocation } from "react-router";
import type { TriedValue } from "../../../api/stepTries";
import type { GroupStanding } from "../../../api/standing";
import { say } from "../../../i18n/app";
import { isolatedInText } from "../../../lib/direction/isolated";
import { HiddenShown } from "../../../lib/text/HiddenShown";
import { ModelProse } from "../../../lib/text/ModelProse";
import { decisionSaid } from "./stepTries";
import { listQuery, stepHref, WITHIN_THE_RUN } from "../runAddress";
import { lengthUnitsSaid } from "./LengthPanel";
import { spokenName } from "./stepTitle";

export function DecisionDrawn({
  decision,
}: {
  readonly decision: TriedValue["decision"] & object;
}) {
  if (
    decision.outcome === "refused" &&
    decision.why !== undefined &&
    decision.whyLines === "several"
  ) {
    return (
      <>
        <Box component="span" sx={{ typography: "valueLabel" }}>
          {say("step.refusedHeading")}
        </Box>{" "}
        <ModelProse
          text={decision.why}
          plain={
            <bdi style={{ whiteSpace: "pre-wrap" }}>
              <HiddenShown text={decision.why} />
            </bdi>
          }
        />
      </>
    );
  }
  return decisionSaid(decision);
}

/** The stored refusal and quantities belong to the held request, rather than its current model configuration. */
export function LengthDecisionDrawn({
  decision,
  group,
  field,
}: {
  readonly decision: TriedValue["decision"] & object;
  readonly group: GroupStanding;
  readonly field: string;
}) {
  const search = listQuery(useLocation().search);
  const held = decision.lengthHeld;
  if (
    held === undefined ||
    decision.lengthUnits === undefined ||
    decision.excessUnits === undefined
  )
    return null;
  return (
    <Box>
      <Typography variant="body2">
        {say("act.lengthDetail", {
          field,
          step: isolatedInText(held.label ?? spokenName(held.stepName)),
          number: held.run.number,
        })}
      </Typography>
      <Typography variant="body2">
        {field}:{" "}
        {say("step.lengthAbout", {
          length: lengthUnitsSaid(decision.lengthUnits),
        })}
      </Typography>
      <Typography variant="body2">
        {say("step.lengthExcess")}:{" "}
        {say("step.lengthAbout", {
          length: lengthUnitsSaid(decision.excessUnits),
        })}
      </Typography>
      {decision.why === undefined ? null : (
        <Typography variant="body2">{isolatedInText(decision.why)}</Typography>
      )}
      <Link
        component={RouterLink}
        to={`${stepHref(group, held.run.runId, held.stepId)}${search}`}
        state={WITHIN_THE_RUN}
      >
        {say("act.openHeldStep", {
          step: isolatedInText(held.label ?? spokenName(held.stepName)),
        })}
      </Link>
    </Box>
  );
}
