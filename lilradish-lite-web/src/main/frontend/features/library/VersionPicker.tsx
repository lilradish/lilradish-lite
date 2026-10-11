import Autocomplete from "@mui/material/Autocomplete";
import Box from "@mui/material/Box";
import TextField from "@mui/material/TextField";
import { useCallback, useMemo, useState, type ReactNode } from "react";

import {
  matchVersionNames,
  type EntryKind,
} from "../../api/groups/{groupId}/{kind}";
import { ProblemView } from "../../app/ProblemView";
import { say } from "../../i18n/app";
import { useResource } from "../../lib/request/useResource";

const NONE: readonly string[] = [];

export interface VersionChoice {
  readonly versionId: string;
  readonly label: string;
  readonly shown: ReactNode;
  readonly disabled?: boolean;
}

/** The loaded choices stay authoritative: a matching response can only narrow their IDs, never add any. */
export function VersionPicker({
  groupId,
  kind,
  choices,
  value,
  onChange,
  label,
  hint,
  noneLabel,
  autoFocus,
  disabled,
  readOnly,
}: {
  readonly groupId: string;
  readonly kind: EntryKind;
  readonly choices: readonly VersionChoice[];
  readonly value: string;
  readonly onChange: (versionId: string) => void;
  readonly label: string;
  readonly hint?: string;
  readonly noneLabel?: string;
  readonly autoFocus?: boolean;
  readonly disabled?: boolean;
  readonly readOnly?: boolean;
}) {
  const [typed, setTyped] = useState("");
  const [input, setInput] = useState(
    choices.find((choice) => choice.versionId === value)?.label ??
      (value === "" ? (noneLabel ?? "") : ""),
  );
  const ids = JSON.stringify(
    choices.map((choice) => choice.versionId).filter((id) => id !== ""),
  );
  const selectedLabel =
    choices.find((choice) => choice.versionId === value)?.label ??
    (value === "" ? (noneLabel ?? "") : "");
  const owner = `${groupId}:${kind}:${value}:${ids}:${selectedLabel}`;
  const [readAs, setReadAs] = useState(owner);
  if (owner !== readAs) {
    setReadAs(owner);
    setInput(selectedLabel);
    setTyped("");
  }
  const load = useCallback(
    async (signal: AbortSignal) => {
      if (typed === "") return JSON.parse(ids) as string[];
      await new Promise<void>((resolve, reject) => {
        const aborted = () => {
          clearTimeout(timer);
          reject(new DOMException("Aborted", "AbortError"));
        };
        const timer = setTimeout(() => {
          signal.removeEventListener("abort", aborted);
          resolve();
        }, 150);
        signal.addEventListener("abort", aborted, { once: true });
        if (signal.aborted) aborted();
      });
      return matchVersionNames(
        groupId,
        kind,
        JSON.parse(ids) as string[],
        typed,
        signal,
      );
    },
    [groupId, kind, ids, typed],
  );
  const matched = useResource(load, NONE);
  const matchedIds = new Set(matched.value);
  const narrowed =
    typed === ""
      ? choices
      : choices.filter((choice) => matchedIds.has(choice.versionId));
  const none = useMemo<VersionChoice[]>(
    () =>
      noneLabel === undefined
        ? []
        : [{ versionId: "", label: noneLabel, shown: noneLabel }],
    [noneLabel],
  );
  const selected =
    choices.find((choice) => choice.versionId === value) ??
    (value === "" ? none[0] : null) ??
    null;
  const options = [...none, ...narrowed];
  if (
    selected !== null &&
    !options.some((choice) => choice.versionId === selected.versionId)
  )
    options.push(selected);
  return (
    <>
      <Autocomplete
        fullWidth
        size="small"
        options={options}
        value={selected}
        inputValue={input}
        disabled={disabled}
        readOnly={readOnly}
        disableClearable={noneLabel === undefined}
        isOptionEqualToValue={(choice, picked) =>
          choice.versionId === picked.versionId
        }
        getOptionLabel={(choice) => choice.label}
        getOptionKey={(choice) => choice.versionId}
        getOptionDisabled={(choice) => choice.disabled === true}
        disabledItemsFocusable
        clearOnBlur={false}
        onBlur={() => {
          setInput(selected?.label ?? "");
          setTyped("");
        }}
        filterOptions={(all) => all}
        onInputChange={(event, next, reason) => {
          if (reason === "reset" && event === null) return;
          setInput(next);
          setTyped(reason === "input" ? next : "");
        }}
        onChange={(_event, next) => {
          if (next === null) {
            if (noneLabel !== undefined) onChange("");
          } else if (!next.disabled) onChange(next.versionId);
          setTyped("");
        }}
        noOptionsText={say("versionPicker.noMatch")}
        renderOption={(props, choice) => (
          <Box component="li" {...props} key={choice.versionId}>
            {choice.shown}
          </Box>
        )}
        renderInput={(params) => (
          <TextField
            {...params}
            label={label}
            autoFocus={autoFocus}
            placeholder={say("versionPicker.placeholder")}
            helperText={
              typed !== "" &&
              !matched.loading &&
              matched.problem === null &&
              narrowed.length === 0
                ? say("versionPicker.noMatch")
                : hint
            }
          />
        )}
      />
      {matched.problem === null ? null : (
        <ProblemView problem={matched.problem} />
      )}
    </>
  );
}
