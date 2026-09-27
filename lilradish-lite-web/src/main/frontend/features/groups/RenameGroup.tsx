import Stack from "@mui/material/Stack";
import TextField from "@mui/material/TextField";
import Typography from "@mui/material/Typography";
import { useState } from "react";

import type { RegisteredGroup } from "../../api/groups";
import { renameGroup } from "../../api/groups/{groupId}";
import { ProblemView } from "../../app/ProblemView";
import { say } from "../../i18n/app";
import { ActDialog } from "../../lib/action/ActDialog";
import type { Reason } from "../../lib/action/Press";
import { Field, Fields } from "../../lib/form/Fields";
import type { Action } from "../../lib/request/useAction";
import type { Outcome } from "./changes";
import { nameFits } from "../../lib/text/legibility";

const FIELDS_SX = { pt: 1 };

const KEY_KEPT_SX = { color: "text.secondary" };

// A name may carry a bidirectional control, so its box takes its direction from it.
const NAME_INPUT = { htmlInput: { dir: "auto" } };

/**
 * A new name for one group, the key beside it as text and not as a box: a key
 * is given once, and anything already citing it outside this system cannot be
 * rewritten. Nothing here enters the group.
 *
 * Renaming runs under the action handed in, as creating does. Only a refusal
 * asked from this opening is said.
 */
export function RenameGroup({
  group,
  open,
  changing,
  onShut,
}: {
  readonly group: RegisteredGroup;
  readonly open: boolean;
  readonly changing: Action<Outcome>;
  readonly onShut: () => void;
}) {
  const [name, setName] = useState(group.name);
  const [asked, setAsked] = useState(false);
  const holds = nameFits(name);

  return (
    <ActDialog
      open={open}
      title={say("group.renameTitle")}
      onShut={onShut}
      action={changing}
      act={(signal) => {
        setAsked(true);
        return renameGroup(group.groupId, name, signal).then((renamed) => {
          onShut();
          return { renamed };
        });
      }}
      actLabel={say("group.rename")}
      reason={heldFor(holds, name === group.name)}
      abandonLabel={say("group.abandon")}
      underway={say("group.renameUnderway")}
      refusal={
        !asked || changing.problem === null ? null : (
          <ProblemView
            problem={changing.problem}
            rule={{ act: "keep_group_register" }}
          />
        )
      }
    >
      <Stack spacing={1} sx={FIELDS_SX}>
        <TextField
          label={say("group.name")}
          value={name}
          onChange={(edit) => setName(edit.target.value)}
          error={!holds}
          helperText={holds ? undefined : say("group.nameLimit")}
          slotProps={NAME_INPUT}
          autoFocus
          fullWidth
        />
        <Fields>
          <Field label={say("group.key")}>
            <bdi>{group.key}</bdi>
          </Field>
        </Fields>
        <Typography variant="body2" sx={KEY_KEPT_SX}>
          {say("group.keyKept")}
        </Typography>
      </Stack>
    </ActDialog>
  );
}

/** A name the group cannot have, and the name it has, are both nothing to rename to. */
function heldFor(holds: boolean, unchanged: boolean): Reason | undefined {
  if (!holds) {
    return { severity: "info", words: say("group.nameLimit") };
  }
  return unchanged
    ? { severity: "info", words: say("group.nameUnchanged") }
    : undefined;
}
