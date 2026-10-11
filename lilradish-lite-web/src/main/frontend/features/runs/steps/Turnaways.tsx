import Box from "@mui/material/Box";
import Typography from "@mui/material/Typography";

import type { Turnaway } from "../../../api/stepTries";
import { say } from "../../../i18n/app";
import { QUIET_SX } from "../../../lib/layout/parts";
import { HiddenShownIn } from "../../../lib/text/HiddenShownIn";
import { useCalendarNow } from "../../../lib/time/When";
import { turnawaySaid } from "./stepTries";

const LIST_SX = { m: 0, pl: 2 };

// A turnaway is outside the numbered tries and carries no try cost.
export function Turnaways({
  turnedAway,
}: {
  readonly turnedAway: readonly Turnaway[];
}) {
  const now = useCalendarNow();
  return (
    <>
      <Box component="ul" sx={LIST_SX}>
        {turnedAway.map((each, at) => {
          const [when, ...said] = turnawaySaid(each, now);
          const { said: words, cut, withheld } = each;
          return (
            <li key={at}>
              {when}
              {words === undefined || withheld === true ? (
                said.map((line) => (
                  <Typography key={line} variant="body2" sx={QUIET_SX}>
                    {line}
                  </Typography>
                ))
              ) : (
                <Typography variant="body2" sx={QUIET_SX}>
                  <HiddenShownIn
                    hint
                    sentence={(place) =>
                      say(
                        cut === true
                          ? "step.turnedAwaySaidCut"
                          : "step.turnedAwaySaid",
                        { said: place },
                      )
                    }
                    text={words}
                  />
                </Typography>
              )}
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
