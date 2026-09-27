import Typography from "@mui/material/Typography";

import type { StepRow } from "../../../api/groups/{groupId}/runs/{runId}/steps";
import { say } from "../../../i18n/app";
import { QUIET_SX } from "../../../lib/layout/parts";
import { joinedInSentence } from "../../people/wordLists";
import { stepTitle } from "./stepTitle";

/** One faint line naming the steps not reached yet, in the order they run; nothing where there are none. */
export function WhatComesNext({
  steps,
}: {
  readonly steps: readonly StepRow[];
}) {
  const titles = steps
    .filter((step) => step.state === "not_started")
    .map(stepTitle);
  return titles.length === 0 ? null : (
    <Typography variant="body2" sx={QUIET_SX}>
      {say("step.comesNext", { steps: joinedInSentence(titles) })}
    </Typography>
  );
}
