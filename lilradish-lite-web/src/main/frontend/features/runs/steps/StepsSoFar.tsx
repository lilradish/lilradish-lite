import Box from "@mui/material/Box";
import Button from "@mui/material/Button";
import Typography from "@mui/material/Typography";
import { useId } from "react";

import type { StepRow } from "../../../api/groups/{groupId}/runs/{runId}/steps";
import { say } from "../../../i18n/app";
import { isolatedInText } from "../../../lib/direction/isolated";
import { columnsBesideAt } from "../../../lib/layout/TwoColumns";
import { INNER_PART_SX } from "../../../lib/layout/parts";
import { stepAnchor } from "../runAddress";
import { StepStateBadge } from "./StepStateBadge";
import { stepTitle } from "./stepTitle";

const LIST_SX = { m: 0, pl: 2 };
const ITEM_SX = { mb: 1 };
const LINK_SX = {
  textAlign: "start",
  display: "inline-flex",
  flexDirection: "column",
  alignItems: "flex-start",
};

export function StepsSoFar({
  steps,
  wideAtLeast,
}: {
  readonly steps: readonly StepRow[];
  readonly wideAtLeast: number;
}) {
  const titleId = useId();
  return (
    <Box
      component="nav"
      aria-labelledby={titleId}
      sx={{
        ...INNER_PART_SX,
        [`@container (min-width: ${columnsBesideAt(wideAtLeast)}px)`]: {
          position: "sticky",
          top: 64,
          maxHeight: "calc(100dvh - 80px)",
          overflowY: "auto",
        },
      }}
    >
      <Typography id={titleId} variant="h3">
        {say("step.stepsSoFar")}
      </Typography>
      <Box component="ol" sx={LIST_SX}>
        {steps.map((step) => (
          <Box component="li" key={step.stepId} sx={ITEM_SX}>
            {step.state === "not_started" ? (
              <>
                <Typography component="span">
                  {isolatedInText(stepTitle(step))}
                </Typography>
                <StepStateBadge state={step.state} />
              </>
            ) : (
              <Button
                variant="outlined"
                color="inherit"
                size="small"
                sx={LINK_SX}
                onFocus={(event) =>
                  event.currentTarget.scrollIntoView({ block: "nearest" })
                }
                onClick={() => {
                  const message = document.getElementById(
                    stepAnchor(step.stepId),
                  );
                  message?.scrollIntoView({ block: "nearest" });
                  message?.focus({ preventScroll: true });
                }}
              >
                {isolatedInText(stepTitle(step))}
                <StepStateBadge state={step.state} />
              </Button>
            )}
          </Box>
        ))}
      </Box>
    </Box>
  );
}
