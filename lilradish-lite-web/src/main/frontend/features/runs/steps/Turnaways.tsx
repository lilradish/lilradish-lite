import Box from "@mui/material/Box";
import Typography from "@mui/material/Typography";

import type {
  StepRow,
  Turnaway,
} from "../../../api/groups/{groupId}/runs/{runId}/steps";
import type { Try } from "../../../api/groups/{groupId}/runs/{runId}/steps/{stepId}";
import { say } from "../../../i18n/app";
import { QUIET_SX } from "../../../lib/layout/parts";
import { turnawaySaid } from "./stepStates";

const LIST_SX = { m: 0, pl: 2 };

/**
 * The try a step held back on being turned away holds: never sent, so what turned it away is said with the hold,
 * and never again in the try.
 */
export function heldTry(
  step: StepRow,
  tries: readonly Try[],
): number | undefined {
  return step.where?.kind === "held_back" && step.where.turnedAway !== undefined
    ? tries.at(-1)?.number
    : undefined;
}

/** Each time a model turned a call away, oldest first; none of them is a try, and none cost anything. */
export function Turnaways({
  turnedAway,
}: {
  readonly turnedAway: readonly Turnaway[];
}) {
  return (
    <>
      <Box component="ul" sx={LIST_SX}>
        {turnedAway.map((each, at) => {
          const [when, ...said] = turnawaySaid(each);
          return (
            <li key={at}>
              {when}
              {said.map((line) => (
                <Typography key={line} variant="body2" sx={QUIET_SX}>
                  {line}
                </Typography>
              ))}
            </li>
          );
        })}
      </Box>
      <Typography variant="body2" sx={QUIET_SX}>
        {say("step.turnedAwayCostNothing")}
      </Typography>
    </>
  );
}
