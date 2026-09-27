import Stack from "@mui/material/Stack";
import TextField from "@mui/material/TextField";
import { useState } from "react";

import { renameRun, type Run } from "../../api/groups/{groupId}/runs/{runId}";
import { ProblemView } from "../../app/ProblemView";
import { say } from "../../i18n/app";
import { ActDialog } from "../../lib/action/ActDialog";
import type { Reason } from "../../lib/action/Press";
import type { Action } from "../../lib/request/useAction";
import { RUN_ACT_RULES } from "./actRules";
import { runNameFits } from "./runName";

const FIELDS_SX = { pt: 1 };

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
  readonly onShut: () => void;
}) {
  const named = run.name ?? "";
  const [name, setName] = useState(named);
  const [asked, setAsked] = useState(false);
  const fits = runNameFits(name);

  return (
    <ActDialog
      open={open}
      title={say("runName.title")}
      onShut={onShut}
      action={changing}
      act={(signal) => {
        setAsked(true);
        return renameRun(groupId, run.runId, name, signal).then((renamed) => {
          onShut();
          return renamed;
        });
      }}
      actLabel={say("run.rename")}
      reason={heldFor(fits, name === named)}
      abandonLabel={say("run.abandon")}
      underway={say("runName.underway")}
      refusal={
        !asked || changing.problem === null ? null : (
          <ProblemView
            problem={changing.problem}
            rule={{ inGroup: RUN_ACT_RULES.rename }}
          />
        )
      }
    >
      <Stack spacing={1} sx={FIELDS_SX}>
        <TextField
          label={say("runName.label")}
          value={name}
          onChange={(edit) => setName(edit.target.value)}
          error={name !== "" && !fits}
          helperText={name !== "" && !fits ? say("runName.limit") : undefined}
          slotProps={NAME_INPUT}
          autoFocus
          fullWidth
        />
      </Stack>
    </ActDialog>
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
