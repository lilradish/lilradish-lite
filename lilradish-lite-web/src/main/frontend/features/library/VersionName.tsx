import Box from "@mui/material/Box";
import Tooltip from "@mui/material/Tooltip";
import TextField from "@mui/material/TextField";
import { useId, useLayoutEffect, useRef, useState } from "react";

import { isVersionName, type Entry } from "../../api/groups/{groupId}/{kind}";
import { ProblemView } from "../../app/ProblemView";
import { say } from "../../i18n/app";
import { InlineEdit, inlineEditWidth } from "../../lib/action/InlineEdit";
import { InlineEditableValue } from "../../lib/action/InlineEditableValue";
import type { Action } from "../../lib/request/useAction";
import { ENTRY_ACT_RULES } from "./actRules";

const SLOT_SX = {
  color: "text.secondary",
  display: "inline-flex",
  alignItems: "center",
  minHeight: 32,
  minWidth: 0,
  flex: "1 1 64ch",
  maxWidth: inlineEditWidth(64),
  typography: "versionName",
};

/** Naming has its own write, independent of content, approval and the version's standing. */
export function VersionName({
  number,
  value,
  action,
  send,
}: {
  readonly number: number;
  readonly value: string | null;
  readonly action: Action<Entry>;
  readonly send: (name: string, signal: AbortSignal) => Promise<Entry>;
}) {
  const [open, setOpen] = useState(false);
  const [name, setName] = useState(value ?? "");
  const [asked, setAsked] = useState(false);
  const inputId = useId();
  const hintId = `${inputId}-hint`;
  const trigger = useRef<HTMLButtonElement>(null);
  const restoring = useRef(false);
  useLayoutEffect(() => {
    if (open || action.running || !restoring.current) return;
    restoring.current = false;
    // A successful Enter removes the input before the replacement trigger is
    // enabled. Restore only its owned keyboard position after that commit.
    if (document.activeElement === document.body) trigger.current?.focus();
  }, [open, action.running, value]);
  const holds = name === "" || isVersionName(name);
  const edit = () => {
    setName(value ?? "");
    setAsked(false);
    setOpen(true);
  };
  if (!open) {
    return (
      <Box
        component="span"
        sx={{
          ...SLOT_SX,
          typography: value === null ? "versionNameEmpty" : "versionName",
        }}
      >
        {value === null ? (
          <>
            {" "}
            <InlineEditableValue
              ref={trigger}
              label={say("version.addNameForNumber", { number })}
              characters={64}
              value={say("version.addName")}
              disabled={action.running}
              onEdit={edit}
            />
          </>
        ) : (
          <>
            <Box component="span" sx={{ whiteSpace: "pre" }}>
              {" · "}
            </Box>
            <InlineEditableValue
              ref={trigger}
              characters={64}
              value={value}
              disabled={action.running}
              onEdit={edit}
            />
          </>
        )}
      </Box>
    );
  }
  return (
    <Box
      component="span"
      sx={{
        ...SLOT_SX,
        typography: value === null ? "versionNameEmpty" : "versionName",
      }}
    >
      <Box component="span" sx={{ whiteSpace: "pre" }}>
        {value === null ? " " : " · "}
      </Box>
      <InlineEdit
        characters={64}
        readValue={value ?? say("version.addName")}
        open
        onShut={(returning) => {
          restoring.current = returning === true;
          setOpen(false);
        }}
        action={action}
        unchanged={name === (value ?? "")}
        reason={
          holds
            ? undefined
            : { severity: "info", words: say("version.nameLimit") }
        }
        act={(signal) => {
          setAsked(true);
          return send(name, signal);
        }}
        refusal={
          !asked || action.problem === null ? null : (
            <ProblemView
              problem={action.problem}
              rule={{ inGroup: ENTRY_ACT_RULES.rename }}
            />
          )
        }
      >
        <span id={hintId} hidden>
          {say("version.nameHint")}
        </span>
        <Tooltip title={say("version.nameHint")} describeChild>
          <TextField
            multiline
            id={inputId}
            value={name}
            onChange={(event) => {
              setAsked(false);
              setName(event.target.value);
            }}
            error={!holds}
            helperText={holds ? undefined : say("version.nameLimit")}
            slotProps={{
              htmlInput: {
                dir: "auto",
                "aria-label": say("version.name"),
                "aria-describedby": `${hintId}${holds ? "" : ` ${inputId}-helper-text`}`,
              },
            }}
            autoFocus
            fullWidth
          />
        </Tooltip>
      </InlineEdit>
    </Box>
  );
}
