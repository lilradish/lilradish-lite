import Box from "@mui/material/Box";

import { say } from "../../i18n/app";
import { visible } from "../../i18n/visible";
import { isolatedInText } from "../../lib/direction/isolated";
import { MARKED_WORDS_SX } from "../../lib/layout/parts";
import { runStateMark } from "./runStates";

export function RunStateBadge({
  state,
  at,
}: {
  readonly state: string;
  readonly at?: string;
}) {
  const mark = runStateMark(state);
  if (mark === null) {
    return say("runState.unknown");
  }
  const Icon = mark.icon;
  return (
    <Box component="span" sx={{ ...MARKED_WORDS_SX, color: mark.colour }}>
      <Icon fontSize="inherit" />
      {state === "running" && at !== undefined
        ? visible("runState.runningAtVisible", {
            hint: (
              <Box component="span" sx={{ typography: "systemHint" }}>
                {say("runState.runningAtHint")}
              </Box>
            ),
            step: <bdi>{isolatedInText(at)}</bdi>,
          })
        : say(mark.words)}
    </Box>
  );
}
