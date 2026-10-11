import Tooltip from "@mui/material/Tooltip";
import TextField from "@mui/material/TextField";
import { useState } from "react";

import type { RegisteredGroup } from "../../api/groups";
import { renameGroup } from "../../api/groups/{groupId}";
import { ProblemView } from "../../app/ProblemView";
import { say } from "../../i18n/app";
import { InlineEdit } from "../../lib/action/InlineEdit";
import type { Reason } from "../../lib/action/Press";
import type { Action } from "../../lib/request/useAction";
import type { Outcome } from "./changes";
import { nameFits } from "../../lib/text/legibility";

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
  readonly onShut: (returnFocus?: boolean) => void;
}) {
  const [name, setName] = useState(group.name);
  const [asked, setAsked] = useState(false);
  const holds = nameFits(name);

  return (
    <InlineEdit
      characters={128}
      readValue={group.name}
      underway={say("group.renameUnderway")}
      open={open}
      onShut={onShut}
      action={changing}
      unchanged={name === group.name}
      act={(signal) => {
        setAsked(true);
        return renameGroup(group.groupId, name, signal).then((renamed) => {
          return { renamed };
        });
      }}
      reason={heldFor(holds, name === group.name)}
      refusal={
        !asked || changing.problem === null ? null : (
          <ProblemView
            problem={changing.problem}
            rule={{ act: "keep_group_register" }}
          />
        )
      }
    >
      <Tooltip
        title={
          <>
            <bdi>{group.key}</bdi>
            {" · "}
            {say("group.keyKept")}
          </>
        }
        describeChild
      >
        <TextField
          multiline
          value={name}
          onChange={(edit) => setName(edit.target.value)}
          error={!holds}
          helperText={holds ? undefined : say("group.nameLimit")}
          slotProps={{
            htmlInput: {
              ...NAME_INPUT.htmlInput,
              "aria-label": say("group.name"),
            },
          }}
          autoFocus
          fullWidth
        />
      </Tooltip>
    </InlineEdit>
  );
}

function heldFor(holds: boolean, unchanged: boolean): Reason | undefined {
  if (!holds) {
    return { severity: "info", words: say("group.nameLimit") };
  }
  return unchanged
    ? { severity: "info", words: say("group.nameUnchanged") }
    : undefined;
}
