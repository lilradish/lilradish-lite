import Stack from "@mui/material/Stack";
import Tooltip from "@mui/material/Tooltip";
import { useId, useState } from "react";

import type { Described, Entry } from "../../api/groups/{groupId}/{kind}";
import { ProblemView } from "../../app/ProblemView";
import type { GroupRule } from "../../app/standing/actRules";
import { say, type MessageId } from "../../i18n/app";
import { InlineEdit } from "../../lib/action/InlineEdit";
import { ActDialog } from "../../lib/action/ActDialog";
import type { Reason } from "../../lib/action/Press";
import { LabelledTextField } from "../../lib/form/LabelledTextField";
import type { Action } from "../../lib/request/useAction";
import { lineFits, nameFits } from "../../lib/text/legibility";

const FIELDS_SX = { pt: 1 };

// Either may carry a bidirectional control, so each box takes its direction from what is in it.
const TYPED_INPUT = { htmlInput: { dir: "auto" } };

/**
 * An entry's name and what it is for, asked for as the one act of making an
 * entry or of editing one. Each limit is checked where it is typed, and again
 * by the server, whose check is the one that holds; an entry's name is held to
 * the rule every name is. Nothing typed of what it is for is sent as none.
 *
 * It runs under the action handed in, which its holder owns and settles, so
 * it outlives this opening; it shuts once what it sent lands. Only a refusal
 * asked from this opening is said, in the words of the rule the act asks.
 */
export function DescribeEntry({
  open,
  editing,
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
  readonly editing?: "name" | "purpose";
  readonly title: string;
  readonly actLabel: string;
  readonly underway: MessageId;
  readonly rule: GroupRule;
  /** As it stands, or empty for an entry not yet made; sending it unchanged is held where it stands. */
  readonly described: { readonly name: string; readonly purpose?: string };
  readonly send: (described: Described, signal: AbortSignal) => Promise<Entry>;
  readonly action: Action<Entry>;
  readonly onShut: (returnFocus?: boolean) => void;
}) {
  const [name, setName] = useState(described.name);
  const [purpose, setPurpose] = useState(described.purpose ?? "");
  const [asked, setAsked] = useState(false);
  const purposeId = useId();
  const purposeHintId = `${purposeId}-hint`;
  const nameHolds = nameFits(name);
  const purposeHolds = purpose === "" || lineFits(purpose);
  const unchanged =
    described.name !== "" &&
    name === described.name &&
    purpose === (described.purpose ?? "");

  const act = (signal: AbortSignal) => {
    setAsked(true);
    return send(
      { name, purpose: purpose === "" ? null : purpose },
      signal,
    ).then((answered) => {
      if (editing === undefined) onShut();
      return answered;
    });
  };
  const refusal =
    !asked || action.problem === null ? null : (
      <ProblemView problem={action.problem} rule={{ inGroup: rule }} />
    );
  const reason = heldFor(nameHolds, purposeHolds, unchanged);
  const fields = (
    <Stack spacing={2} sx={editing === undefined ? FIELDS_SX : undefined}>
      {editing === "purpose" ? null : (
        <LabelledTextField
          multiline={editing !== undefined}
          label={editing === undefined ? say("entry.name") : undefined}
          value={name}
          onChange={(edit) => setName(edit.target.value)}
          error={!nameHolds}
          helperText={!nameHolds ? say("entry.nameLimit") : undefined}
          slotProps={{
            htmlInput: {
              ...TYPED_INPUT.htmlInput,
              "aria-label": say("entry.name"),
            },
          }}
          autoFocus
          fullWidth
        />
      )}
      {editing === "name" ? null : (
        <>
          <span id={purposeHintId} hidden>
            {say("entry.purposeHint")}
          </span>
          <Tooltip title={say("entry.purposeHint")} describeChild>
            <LabelledTextField
              multiline={editing !== undefined}
              id={purposeId}
              label={editing === undefined ? say("entry.purpose") : undefined}
              value={purpose}
              onChange={(edit) => setPurpose(edit.target.value)}
              error={!purposeHolds}
              helperText={
                purposeHolds
                  ? editing === undefined
                    ? say("entry.purposeHint")
                    : undefined
                  : say("entry.purposeLimit")
              }
              slotProps={{
                htmlInput: {
                  ...TYPED_INPUT.htmlInput,
                  "aria-label": say("entry.purpose"),
                  "aria-describedby": !purposeHolds
                    ? `${purposeHintId} ${purposeId}-helper-text`
                    : editing === undefined
                      ? `${purposeId}-helper-text`
                      : purposeHintId,
                },
              }}
              autoFocus={editing === "purpose"}
              fullWidth
            />
          </Tooltip>
        </>
      )}
    </Stack>
  );
  return editing === undefined ? (
    <ActDialog
      open={open}
      title={title}
      onShut={onShut}
      action={action}
      act={act}
      actLabel={actLabel}
      reason={reason}
      abandonLabel={say("entry.abandon")}
      underway={say(underway)}
      refusal={refusal}
    >
      {fields}
    </ActDialog>
  ) : (
    <InlineEdit
      characters={editing === "name" ? 128 : 512}
      readValue={
        editing === "name"
          ? described.name
          : (described.purpose ?? say("entry.saysNothing"))
      }
      underway={say(underway)}
      open={open}
      onShut={onShut}
      action={action}
      act={act}
      reason={reason}
      refusal={refusal}
      unchanged={unchanged}
    >
      {fields}
    </InlineEdit>
  );
}

/** The first thing that keeps these from being sent, in the order the fields ask for them. */
function heldFor(
  nameHolds: boolean,
  purposeHolds: boolean,
  unchanged: boolean,
): Reason | undefined {
  if (!nameHolds) {
    return { severity: "info", words: say("entry.nameLimit") };
  }
  if (!purposeHolds) {
    return { severity: "info", words: say("entry.purposeLimit") };
  }
  return unchanged
    ? { severity: "info", words: say("entry.unchanged") }
    : undefined;
}
