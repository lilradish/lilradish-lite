import Typography from "@mui/material/Typography";

import type { Spend } from "../../api/groups/{groupId}/runs/{runId}";
import type { Cost } from "../../api/groups/{groupId}/runs/{runId}/steps";
import { say } from "../../i18n/app";
import { readersIntl } from "../../i18n/intl";
import { QUIET_SX } from "../../lib/layout/parts";

/** Digits as the server sent them, grouped in the reader's own way and never rounded on the way. */
export function countSaid(digits: string): string {
  return readersIntl.formatNumber(BigInt(digits));
}

/**
 * What was spent, sent and came back; beneath it, who counted it where the model did not, and what is not known.
 * The wire says whether any call was measured here, never whether all were, so a try's figure is said as a total's.
 */
export function Spent({ spend }: { readonly spend: Spend }) {
  return (
    <>
      {say("run.spent", {
        spent: countSaid(spend.spent),
        sent: countSaid(spend.sent),
        cameBack: countSaid(spend.cameBack),
      })}
      {spend.measuredHere === undefined ? null : (
        <Typography variant="body2" sx={QUIET_SX}>
          {say("run.someMeasuredHere")}
        </Typography>
      )}
      {spend.cameBackUnknown ? (
        <Typography variant="body2" sx={QUIET_SX}>
          {say("run.cameBackUnknown")}
        </Typography>
      ) : null}
    </>
  );
}

/** What a step or a try spent as a run's spend is drawn, or that it calls no model, which is not a count of nought. */
export function CostDrawn({ cost }: { readonly cost: Cost }) {
  return cost.spend === undefined ? (
    say("step.noCost")
  ) : (
    <Spent spend={cost.spend} />
  );
}
