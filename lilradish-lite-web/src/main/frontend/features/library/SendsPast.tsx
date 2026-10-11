import Box from "@mui/material/Box";
import Typography from "@mui/material/Typography";
import { Children, useId } from "react";

import type {
  SendingRole,
  SendPast,
  Step,
} from "../../api/groups/{groupId}/{kind}/{entryId}/versions/{versionId}";
import { say, type MessageId } from "../../i18n/app";
import { visible } from "../../i18n/visible";
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
      <Typography id={labelId} variant="subtitle2">
        {say("sendsPast.label")}
      </Typography>
      <Typography
        variant="body2"
        sx={{ ...QUIET_SX, typography: "systemHint" }}
      >
        {say("sendsPast.hint")}
      </Typography>
      <Box component="ul" role="list" aria-labelledby={labelId} sx={LIST_SX}>
        {sendsPast.map((each) => {
          const past = pastSaid(each, steps);
          return (
            <li key={`${each.stepId}:${each.role}`} aria-label={past.words}>
              {past.node}
            </li>
          );
        })}
      </Box>
    </>
  );
}

function pastSaid(each: SendPast, steps: readonly Step[]) {
  const found = steps.find((held) => held.stepId === each.stepId);
  const step = found?.label ?? found?.name.replaceAll("_", " ") ?? each.stepId;
  const model = each.model;
  // Past a double's exact integers the count arrived rounded, so only that it passed them is said.
  const id =
    each.past > Number.MAX_SAFE_INTEGER
      ? SAID_UNCOUNTED[each.role]
      : SAID[each.role];
  const figures: Readonly<Record<string, number>> =
    each.past > Number.MAX_SAFE_INTEGER
      ? { max: Number.MAX_SAFE_INTEGER }
      : { past: each.past };
  return {
    words: say(id, {
      ...figures,
      step: isolatedInText(step),
      model: isolatedInText(model),
    }),
    node: Children.map(
      visible(id, {
        ...figures,
        step: <bdi>{step}</bdi>,
        model: <bdi>{model}</bdi>,
      }),
      (part) =>
        typeof part === "string" ? (
          <Box component="span" sx={{ typography: "systemHint" }}>
            {part}
          </Box>
        ) : (
          part
        ),
    ),
  };
}
