import Tooltip from "@mui/material/Tooltip";
import TextField from "@mui/material/TextField";
import { useId, useState } from "react";

import type { OwnCeiling, Run } from "../../api/groups/{groupId}/runs/{runId}";
import { changeCeiling } from "../../api/groups/{groupId}/runs/{runId}/ceiling";
import { ProblemView } from "../../app/ProblemView";
import { say } from "../../i18n/app";
import { InlineEdit } from "../../lib/action/InlineEdit";
import type { Reason } from "../../lib/action/Press";
import type { Action } from "../../lib/request/useAction";
import { RUN_ACT_RULES } from "./actRules";
import { ceilingFits } from "./ceilingTyped";
import { countSaid } from "./spend";

// Digits alone, so a phone offers its number pad; the box stays text, a number box rounding what it holds.
const DIGITS_INPUT = { htmlInput: { inputMode: "numeric" } } as const;

/**
 * A new ceiling for one run, nothing typed meaning none. Whether it holds at
 * once or waits on approval is the server's to judge; this only says when it might.
 */
export function ChangeCeiling({
  groupId,
  runId,
  targetRunId,
  ceiling,
  open,
  changing,
  onShut,
}: {
  readonly groupId: string;
  readonly runId: string;
  readonly targetRunId?: string;
  readonly ceiling: OwnCeiling;
  readonly open: boolean;
  readonly changing: Action<Run>;
  readonly onShut: (returnFocus?: boolean) => void;
}) {
  const inForce = ceiling.inForce ?? "";
  const [typed, setTyped] = useState(inForce);
  const [asked, setAsked] = useState(false);
  const fits = typed === "" || ceilingFits(typed);
  const inputId = useId();
  const hintId = `${inputId}-hint`;
  const hint = (
    <>
      {say("ceiling.hint")}
      {ceiling.raiseNeedsApproval ? <> {say("ceiling.approval")}</> : null}
      {ceiling.waiting === undefined ? null : <> {say("ceiling.withdraws")}</>}
    </>
  );

  return (
    <InlineEdit
      characters={String(Number.MAX_SAFE_INTEGER).length}
      readValue={
        ceiling.inForce === undefined
          ? say("run.noCeiling")
          : countSaid(ceiling.inForce)
      }
      underway={say("ceiling.underway")}
      open={open}
      onShut={onShut}
      action={changing}
      unchanged={typed === inForce}
      act={(signal) => {
        setAsked(true);
        return changeCeiling(
          groupId,
          runId,
          typed === "" ? null : typed,
          signal,
          targetRunId,
        );
      }}
      reason={heldFor(fits, typed === inForce)}
      refusal={
        !asked || changing.problem === null ? null : (
          <ProblemView
            problem={changing.problem}
            rule={{ inGroup: RUN_ACT_RULES.change_ceiling }}
          />
        )
      }
    >
      <span id={hintId} hidden>
        {hint}
      </span>
      <Tooltip title={hint} describeChild>
        <TextField
          id={inputId}
          value={typed}
          onChange={(edit) => setTyped(edit.target.value)}
          error={!fits}
          helperText={fits ? undefined : say("ceiling.limit")}
          slotProps={{
            htmlInput: {
              ...DIGITS_INPUT.htmlInput,
              "aria-label": say("ceiling.label"),
              "aria-describedby": `${hintId}${fits ? "" : ` ${inputId}-helper-text`}`,
            },
          }}
          autoFocus
          fullWidth
        />
      </Tooltip>
    </InlineEdit>
  );
}

function heldFor(fits: boolean, unchanged: boolean): Reason | undefined {
  if (!fits) {
    return { severity: "warning", words: say("ceiling.limit") };
  }
  return unchanged
    ? { severity: "info", words: say("ceiling.unchanged") }
    : undefined;
}
