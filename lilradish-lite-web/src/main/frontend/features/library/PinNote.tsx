import Box from "@mui/material/Box";
import Typography from "@mui/material/Typography";

import { say } from "../../i18n/app";
import { Press } from "../../lib/action/Press";
import { isolatedInText } from "../../lib/direction/isolated";
import type { Named } from "./workflowDrafts";

const QUIET_SX = { color: "text.secondary" };

export function PinNote({
  pinned,
  take,
  called,
}: {
  readonly pinned: NonNullable<Named["pinned"]>;
  /** None where the draft may not be written, and so nothing newer may be taken. */
  readonly take?: (versionId: string) => void;
  /** What pins it, where the take is offered on each of many rows alike. */
  readonly called?: string;
}) {
  const retired = pinned.standing === "retired";
  const newer = pinned.newer;
  if (!retired && newer === undefined) {
    return null;
  }
  return (
    <Box>
      <Typography variant="body2" sx={QUIET_SX}>
        {retired
          ? say("workflow.pinRetired")
          : say("workflow.newerInService", { number: newer!.number })}
      </Typography>
      {newer === undefined || take === undefined ? null : (
        <Press
          label={
            called === undefined
              ? undefined
              : say("workflow.takeNewerFor", {
                  number: newer.number,
                  name: called,
                })
          }
          onPress={() => take(newer.versionId)}
        >
          {say("workflow.takeNewer", { number: newer.number })}
        </Press>
      )}
    </Box>
  );
}

/** A version by its entry's name and its number, or by its key where the page read nothing of it. */
export function versionSaid(
  named: Named | undefined,
  versionId: string,
): string {
  return named === undefined
    ? versionId === ""
      ? say("workflow.versionNone")
      : versionId
    : say("workflow.runsVersion", {
        name: isolatedInText(named.name),
        number: named.number,
      });
}
