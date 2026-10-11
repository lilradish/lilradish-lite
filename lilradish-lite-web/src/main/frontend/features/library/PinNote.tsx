import Box from "@mui/material/Box";
import Typography from "@mui/material/Typography";

import { say } from "../../i18n/app";
import { visible } from "../../i18n/visible";
import { Press } from "../../lib/action/Press";
import type { Named } from "./workflowDrafts";
import { VersionLabel, versionLabelText } from "../../lib/text/VersionLabel";

const QUIET_SX = { color: "text.secondary", overflowWrap: "anywhere" };

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
    <Box
      sx={{
        minWidth: 0,
        "& button": {
          maxWidth: "100%",
          whiteSpace: "normal",
          overflowWrap: "anywhere",
          textAlign: "start",
        },
        "& button > span": { minWidth: 0 },
      }}
    >
      <Typography
        component="p"
        sx={{ ...QUIET_SX, typography: "supportingProse" }}
      >
        {retired
          ? say("workflow.pinRetired")
          : visible("workflow.newerInService", {
              label: (
                <VersionLabel
                  entry={pinned.name}
                  number={newer!.number}
                  name={newer!.versionName}
                />
              ),
            })}
      </Typography>
      {newer === undefined || take === undefined ? null : (
        <Press
          label={
            called === undefined
              ? undefined
              : say("workflow.takeNewerFor", {
                  label: versionLabelText({
                    number: newer.number,
                    name: newer.versionName,
                  }),
                  name: called,
                })
          }
          onPress={() => take(newer.versionId)}
        >
          {visible("workflow.takeNewer", {
            label: (
              <VersionLabel number={newer.number} name={newer.versionName} />
            ),
          })}
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
        label: versionLabelText({
          entry: named.name,
          number: named.number,
          name: named.versionName,
        }),
      });
}

export function versionShown(named: Named | undefined, versionId: string) {
  return named === undefined
    ? versionSaid(named, versionId)
    : visible("workflow.runsVersion", {
        label: (
          <VersionLabel
            entry={named.name}
            number={named.number}
            name={named.versionName}
          />
        ),
      });
}
