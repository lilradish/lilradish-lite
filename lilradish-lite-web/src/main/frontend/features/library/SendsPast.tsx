import Box from "@mui/material/Box";
import Typography from "@mui/material/Typography";
import { useId } from "react";

import type {
  SendingRole,
  SendPast,
  Step,
} from "../../api/groups/{groupId}/{kind}/{entryId}/versions/{versionId}";
import { say, type MessageId } from "../../i18n/app";
import { isolatedInText } from "../../lib/direction/isolated";

// Its markers taken away, so it is named a list outright.
const LIST_SX = { listStyleType: "none", m: 0, mb: 2, p: 0 };

const QUIET_SX = { color: "text.secondary" };

const SAID = {
  producing: "sendsPast.producing",
  reviewing: "sendsPast.reviewing",
} as const satisfies Record<SendingRole, MessageId>;

const SAID_UNCOUNTED = {
  producing: "sendsPast.producingUncounted",
  reviewing: "sendsPast.reviewingUncounted",
} as const satisfies Record<SendingRole, MessageId>;

/**
 * Each step that could send one of its models more than that model takes, named as the steps read, beside what
 * submitting would refuse and never among it; nothing where no step could.
 */
export function SendsPast({
  sendsPast,
  steps,
}: {
  readonly sendsPast: readonly SendPast[];
  readonly steps: readonly Step[];
}) {
  const labelId = useId();
  if (sendsPast.length === 0) {
    return null;
  }
  return (
    <>
      <Typography id={labelId} variant="subtitle2" component="p">
        {say("sendsPast.label")}
      </Typography>
      <Typography variant="body2" sx={QUIET_SX}>
        {say("sendsPast.hint")}
      </Typography>
      <Box component="ul" role="list" aria-labelledby={labelId} sx={LIST_SX}>
        {sendsPast.map((each) => (
          <li key={`${each.stepId}:${each.role}`}>{pastSaid(each, steps)}</li>
        ))}
      </Box>
    </>
  );
}

function pastSaid(each: SendPast, steps: readonly Step[]): string {
  const step = isolatedInText(
    steps.find((held) => held.stepId === each.stepId)?.name ?? each.stepId,
  );
  const model = isolatedInText(each.model);
  // Past a double's exact integers the count arrived rounded, so only that it passed them is said.
  return each.past > Number.MAX_SAFE_INTEGER
    ? say(SAID_UNCOUNTED[each.role], {
        step,
        model,
        max: Number.MAX_SAFE_INTEGER,
      })
    : say(SAID[each.role], { step, model, past: each.past });
}
