import Stack from "@mui/material/Stack";
import TextField from "@mui/material/TextField";
import { useState } from "react";

import type { Described, Entry } from "../../api/groups/{groupId}/{kind}";
import { ProblemView } from "../../app/ProblemView";
import type { GroupRule } from "../../app/standing/actRules";
import { say, type MessageId } from "../../i18n/app";
import { ActDialog } from "../../lib/action/ActDialog";
import type { Reason } from "../../lib/action/Press";
import type { Action } from "../../lib/request/useAction";
import { lineFits, nameFits } from "../../lib/text/legibility";

const FIELDS_SX = { pt: 1 };

// Either may carry a bidirectional control, so each box takes its direction from what is in it.
const TYPED_INPUT = { htmlInput: { dir: "auto" } };

/**
 * An entry's name and what it is for, asked for as the one act of starting an
 * entry or of renaming one. Each limit is checked where it is typed, and again
 * by the server, whose check is the one that holds; an entry's name is held to
 * the rule every name is. Nothing typed of what it is for is sent as none.
 *
 * It runs under the action handed in, which its holder owns and settles, so
 * it outlives this dialog; it shuts once what it sent lands. Only a refusal
 * asked from this opening is said, in the words of the rule the act asks.
 */
export function DescribeEntry({
  open,
  title,
  actLabel,
  underway,
  rule,
  described,
  send,
  action,
  onShut,
}: {
  readonly open: boolean;
  readonly title: string;
  readonly actLabel: string;
  readonly underway: MessageId;
  /** What the act asks, which a refusal of it is said as. */
  readonly rule: GroupRule;
  /** As it stands, or empty for an entry not yet made; sending it unchanged is held where it stands. */
  readonly described: { readonly name: string; readonly purpose?: string };
  readonly send: (described: Described, signal: AbortSignal) => Promise<Entry>;
  readonly action: Action<Entry>;
  readonly onShut: () => void;
}) {
  const [name, setName] = useState(described.name);
  const [purpose, setPurpose] = useState(described.purpose ?? "");
  const [asked, setAsked] = useState(false);
  const nameHolds = nameFits(name);
  const purposeHolds = purpose === "" || lineFits(purpose);
  const standing =
    described.name !== "" &&
    name === described.name &&
    purpose === (described.purpose ?? "");

  return (
    <ActDialog
      open={open}
      title={title}
      onShut={onShut}
      action={action}
      act={(signal) => {
        setAsked(true);
        return send(
          { name, purpose: purpose === "" ? null : purpose },
          signal,
        ).then((answered) => {
          onShut();
          return answered;
        });
      }}
      actLabel={actLabel}
      reason={heldFor(nameHolds, purposeHolds, standing)}
      abandonLabel={say("entry.abandon")}
      underway={say(underway)}
      refusal={
        !asked || action.problem === null ? null : (
          <ProblemView problem={action.problem} rule={{ inGroup: rule }} />
        )
      }
    >
      <Stack spacing={2} sx={FIELDS_SX}>
        <TextField
          label={say("entry.name")}
          value={name}
          onChange={(edit) => setName(edit.target.value)}
          error={name !== "" && !nameHolds}
          helperText={
            name !== "" && !nameHolds ? say("entry.nameLimit") : undefined
          }
          slotProps={TYPED_INPUT}
          autoFocus
          fullWidth
        />
        <TextField
          label={say("entry.purpose")}
          value={purpose}
          onChange={(edit) => setPurpose(edit.target.value)}
          error={!purposeHolds}
          helperText={say(
            purposeHolds ? "entry.purposeHint" : "entry.purposeLimit",
          )}
          slotProps={TYPED_INPUT}
          fullWidth
        />
      </Stack>
    </ActDialog>
  );
}

/** The first thing that keeps these from being sent, in the order the fields ask for them. */
function heldFor(
  nameHolds: boolean,
  purposeHolds: boolean,
  standing: boolean,
): Reason | undefined {
  if (!nameHolds) {
    return { severity: "info", words: say("entry.nameLimit") };
  }
  if (!purposeHolds) {
    return { severity: "info", words: say("entry.purposeLimit") };
  }
  return standing
    ? { severity: "info", words: say("entry.unchanged") }
    : undefined;
}
