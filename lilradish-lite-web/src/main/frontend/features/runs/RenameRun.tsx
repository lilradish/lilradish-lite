import TextField from "@mui/material/TextField";
import { useState } from "react";

import { renameRun, type Run } from "../../api/groups/{groupId}/runs/{runId}";
import { ProblemView } from "../../app/ProblemView";
import { say } from "../../i18n/app";
import { InlineEdit } from "../../lib/action/InlineEdit";
import type { Reason } from "../../lib/action/Press";
import type { Action } from "../../lib/request/useAction";
import { RUN_ACT_RULES } from "./actRules";
import { MOST_IN_A_RUN_NAME, runNameFits } from "./runName";

// A name may carry a bidirectional control, so its box takes its direction from it.
const NAME_INPUT = { htmlInput: { dir: "auto" } };

/**
 * A new name for one run, checked where it is typed and again by the server. It runs under the action handed
 * in and shuts once the name lands; only a refusal asked from this opening is said.
 */
export function RenameRun({
  groupId,
  run,
  open,
  changing,
  onShut,
}: {
  readonly groupId: string;
  readonly run: Run;
  readonly open: boolean;
  readonly changing: Action<Run>;
  readonly onShut: (returnFocus?: boolean) => void;
}) {
  const named = run.name ?? "";
  const [name, setName] = useState(named);
  const [asked, setAsked] = useState(false);
  const fits = runNameFits(name);

  return (
    <InlineEdit
      characters={MOST_IN_A_RUN_NAME}
      readValue={run.name ?? say("run.title", { number: run.number })}
      underway={say("runName.underway")}
      open={open}
      onShut={onShut}
      action={changing}
      unchanged={name === named}
      act={(signal) => {
        setAsked(true);
        return renameRun(groupId, run.runId, name, signal);
      }}
      reason={heldFor(fits, name === named)}
      refusal={
        !asked || changing.problem === null ? null : (
          <ProblemView
            problem={changing.problem}
            rule={{ inGroup: RUN_ACT_RULES.rename }}
          />
        )
      }
    >
      <TextField
        multiline
        value={name}
        onChange={(edit) => setName(edit.target.value)}
        error={!fits}
        helperText={!fits ? say("runName.limit") : undefined}
        slotProps={{
          htmlInput: {
            ...NAME_INPUT.htmlInput,
            "aria-label": say("runName.label"),
          },
        }}
        autoFocus
        fullWidth
      />
    </InlineEdit>
  );
}

/** A name the server would refuse, and the name it has, are both nothing to rename to. */
function heldFor(fits: boolean, unchanged: boolean): Reason | undefined {
  if (!fits) {
    return { severity: "info", words: say("runName.limit") };
  }
  return unchanged
    ? { severity: "info", words: say("runName.unchanged") }
    : undefined;
}
