import Stack from "@mui/material/Stack";
import TextField from "@mui/material/TextField";
import Typography from "@mui/material/Typography";
import { useState } from "react";

import type { OwnCeiling, Run } from "../../api/groups/{groupId}/runs/{runId}";
import { changeCeiling } from "../../api/groups/{groupId}/runs/{runId}/ceiling";
import { ProblemView } from "../../app/ProblemView";
import { say } from "../../i18n/app";
import { ActDialog } from "../../lib/action/ActDialog";
import type { Reason } from "../../lib/action/Press";
import { QUIET_SX } from "../../lib/layout/parts";
import type { Action } from "../../lib/request/useAction";
import { RUN_ACT_RULES } from "./actRules";
import { ceilingFits } from "./ceilingTyped";

const FIELDS_SX = { pt: 1 };

// Digits alone, so a phone offers its number pad; the box stays text, a number box rounding what it holds.
const DIGITS_INPUT = { htmlInput: { inputMode: "numeric" } } as const;

/**
 * A new ceiling for one run, nothing typed meaning none. Whether it holds at
 * once or waits on approval is the server's to judge; this only says when it might.
 */
export function ChangeCeiling({
  groupId,
  runId,
  ceiling,
  open,
  changing,
  onShut,
}: {
  readonly groupId: string;
  readonly runId: string;
  readonly ceiling: OwnCeiling;
  readonly open: boolean;
  readonly changing: Action<Run>;
  readonly onShut: () => void;
}) {
  const inForce = ceiling.inForce ?? "";
  const [typed, setTyped] = useState(inForce);
  const [asked, setAsked] = useState(false);
  const fits = typed === "" || ceilingFits(typed);

  return (
    <ActDialog
      open={open}
      title={say("ceiling.title")}
      onShut={onShut}
      action={changing}
      act={(signal) => {
        setAsked(true);
        return changeCeiling(
          groupId,
          runId,
          typed === "" ? null : typed,
          signal,
        ).then((changed) => {
          onShut();
          return changed;
        });
      }}
      actLabel={say("ceiling.change")}
      reason={heldFor(fits, typed === inForce)}
      abandonLabel={say("run.abandon")}
      underway={say("ceiling.underway")}
      refusal={
        !asked || changing.problem === null ? null : (
          <ProblemView
            problem={changing.problem}
            rule={{ inGroup: RUN_ACT_RULES.change_ceiling }}
          />
        )
      }
    >
      <Stack spacing={1} sx={FIELDS_SX}>
        <TextField
          label={say("ceiling.label")}
          value={typed}
          onChange={(edit) => setTyped(edit.target.value)}
          error={!fits}
          helperText={fits ? say("ceiling.hint") : say("ceiling.limit")}
          slotProps={DIGITS_INPUT}
          autoFocus
          fullWidth
        />
        {ceiling.raiseNeedsApproval ? (
          <Typography variant="body2" sx={QUIET_SX}>
            {say("ceiling.approval")}
          </Typography>
        ) : null}
        {ceiling.waiting === undefined ? null : (
          <Typography variant="body2" sx={QUIET_SX}>
            {say("ceiling.withdraws")}
          </Typography>
        )}
      </Stack>
    </ActDialog>
  );
}

/** A count the server would refuse, and the ceiling the run has, are both nothing to change to. */
function heldFor(fits: boolean, unchanged: boolean): Reason | undefined {
  if (!fits) {
    return { severity: "info", words: say("ceiling.limit") };
  }
  return unchanged
    ? { severity: "info", words: say("ceiling.unchanged") }
    : undefined;
}
