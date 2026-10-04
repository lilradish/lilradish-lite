import Box from "@mui/material/Box";
import FormControlLabel from "@mui/material/FormControlLabel";
import MenuItem from "@mui/material/MenuItem";
import Switch from "@mui/material/Switch";
import TextField from "@mui/material/TextField";
import Typography from "@mui/material/Typography";
import { useId, useState, type ReactElement } from "react";

import { say, type MessageId } from "../../i18n/app";
import { visible } from "../../i18n/visible";
import { isolatedInText } from "../../lib/direction/isolated";
import { DEPTH_SX, HEADING_SX, INSET_SX } from "../../lib/layout/parts";
import { Notice } from "../../lib/notice/Notice";
import { hasLineBreak } from "../../lib/filling/writing";
import {
  constantKindOf,
  constantHasLineBreak,
  constantRead,
  WRITTEN_LIMITS,
  type ConstantKind,
} from "./constantDrafts";
import {
  pointerFits,
  pointers,
  type BindingDraft,
  type Shape,
  type SourceDraft,
  type SourceOffered,
  type TargetRow,
} from "./workflowDrafts";

const ROW_SX = {
  display: "flex",
  flexWrap: "wrap",
  gap: 2,
  alignItems: "center",
  mb: 1,
};

const EDITED_ROW_SX = { ...ROW_SX, alignItems: "flex-start" };

const QUIET_SX = { color: "text.secondary" };

const NOTE_SX = { display: "block" };

const CONSTANT_WORDS = {
  text: "workflow.constant",
  number: "workflow.constantNumber",
  written: "workflow.constantWritten",
} as const satisfies Record<Exclude<ConstantKind, "yes_no">, MessageId>;

/**
 * What fills one input or output: where it comes from, then what within it or the constant, offering only fields that
 * fit and keeping one chosen in sight. What does not hold is said under the last text box or list drawn.
 */
export function BindingControls({
  label,
  target,
  binding,
  sources,
  constant,
  editable,
  flags,
  onChange,
}: {
  /** What is filled, as a reader reads it. */
  readonly label: string;
  readonly target: Shape;
  readonly binding: BindingDraft | undefined;
  /** Where it may come from, in the order offered: what the workflow takes first, then each step. */
  readonly sources: readonly SourceOffered[];
  readonly constant: boolean;
  readonly editable: boolean;
  /** What does not hold here, each said against it. */
  readonly flags: readonly MessageId[];
  readonly onChange: (source: SourceDraft) => void;
}) {
  const fromId = useId();
  const [rejected, setRejected] = useState<{
    readonly typed: string;
    readonly lines: Shape["lines"];
  } | null>(null);
  const source: SourceDraft = binding?.source ?? { from: "none" };
  const pastedLineBreak =
    source.from === "constant" &&
    rejected?.typed === source.typed &&
    rejected.lines === target.lines;
  const picked =
    source.from === "input"
      ? "input"
      : source.from === "step"
        ? source.stepKey
        : source.from;
  const offered = sources.find((each) => each.value === picked);
  const path =
    source.from === "input" || source.from === "step" ? source.path : "";
  const fitting = (
    offered?.fields === undefined ? [] : pointers(offered.fields)
  )
    .filter((pointer) => pointerFits(pointer, target))
    .map((pointer) => pointer.path);
  const choices =
    path === "" || fitting.includes(path) ? fitting : [path, ...fitting];
  const kind = constantKindOf(target);
  const oneLineText =
    target.kind === "text" && !target.many && target.lines === "one";
  const read =
    source.from !== "constant"
      ? null
      : source.read !== undefined
        ? constantHasLineBreak(source.read, target)
          ? { why: "fill.line_break" as const }
          : null
        : constantRead(source.typed, target);
  const notes = [
    ...new Set([
      ...flags,
      ...(pastedLineBreak && oneLineText && source.from === "constant"
        ? (["fill.line_break"] as const)
        : []),
      ...(read !== null && "why" in read ? [read.why] : []),
      ...(offered !== undefined && path === "" && fitting.length === 0
        ? (["workflow.nothingFits"] as const)
        : []),
    ]),
  ];
  if (!editable) {
    return (
      <Box sx={ROW_SX}>
        <Typography variant="body2">
          <bdi>{label}</bdi>
          {": "}
          <span aria-label={sourceSaid(source, sources, kind)}>
            {sourceVisible(source, sources, kind)}
          </span>
        </Typography>
        {notes.map((note) => (
          <Notice key={note} severity="warning">
            {say(note, WRITTEN_LIMITS)}
          </Notice>
        ))}
      </Box>
    );
  }
  const noted =
    notes.length === 0
      ? {}
      : {
          error: true,
          helperText: notes.map((note) => (
            <Box key={note} component="span" sx={NOTE_SX}>
              {say(note, WRITTEN_LIMITS)}
            </Box>
          )),
        };
  const second =
    source.from === "input" || source.from === "step"
      ? (said: typeof noted) => (
          <TextField
            select
            size="small"
            label={say("workflow.pointer")}
            value={path}
            {...said}
            onChange={(edit) =>
              onChange({ ...source, path: edit.target.value })
            }
          >
            <MenuItem value="">{say("workflow.pointerNone")}</MenuItem>
            {choices.map((choice) => (
              <MenuItem key={choice} value={choice}>
                {choice}
              </MenuItem>
            ))}
          </TextField>
        )
      : source.from === "constant" && kind !== "yes_no"
        ? (said: typeof noted) => (
            <TextField
              size="small"
              label={say(CONSTANT_WORDS[kind])}
              value={source.typed}
              {...said}
              multiline={target.kind === "text" && !oneLineText}
              minRows={target.kind === "text" && !oneLineText ? 3 : undefined}
              onChange={(edit) => {
                setRejected(null);
                onChange({ from: "constant", typed: edit.target.value });
              }}
              onPaste={(event) => {
                if (
                  oneLineText &&
                  hasLineBreak(event.clipboardData.getData("text"))
                ) {
                  event.preventDefault();
                  setRejected({ typed: source.typed, lines: target.lines });
                }
              }}
              onBeforeInput={(event) => {
                const typed = (event as typeof event & { data?: string | null })
                  .data;
                if (
                  oneLineText &&
                  typeof typed === "string" &&
                  hasLineBreak(typed)
                ) {
                  event.preventDefault();
                  setRejected({ typed: source.typed, lines: target.lines });
                }
              }}
              onDrop={(event) => {
                if (
                  oneLineText &&
                  hasLineBreak(event.dataTransfer.getData("text/plain"))
                ) {
                  event.preventDefault();
                  setRejected({ typed: source.typed, lines: target.lines });
                }
              }}
              slotProps={{
                htmlInput:
                  kind === "number"
                    ? { inputMode: "decimal" }
                    : { dir: "auto" },
              }}
            />
          )
        : null;
  return (
    <Box role="group" aria-labelledby={fromId} sx={{ mb: 2 }}>
      <Typography id={fromId} variant="subtitle2">
        <bdi>{label}</bdi>
      </Typography>
      <Box sx={EDITED_ROW_SX}>
        <TextField
          select
          size="small"
          label={say("workflow.from")}
          value={picked}
          {...(second === null &&
          !(
            source.from === "constant" &&
            kind === "yes_no" &&
            !target.mustBeGiven
          )
            ? noted
            : {})}
          onChange={(edit) => {
            setRejected(null);
            const chosen = edit.target.value;
            onChange(
              chosen === "none"
                ? { from: "none" }
                : chosen === "constant"
                  ? {
                      from: "constant",
                      typed:
                        kind === "yes_no"
                          ? target.mustBeGiven
                            ? "false"
                            : "null"
                          : "",
                    }
                  : chosen === "input"
                    ? { from: "input", path: "" }
                    : { from: "step", stepKey: chosen, path: "" },
            );
          }}
        >
          <MenuItem value="none">{say("workflow.fromNothing")}</MenuItem>
          {sources.map((each) => (
            <MenuItem key={each.value} value={each.value}>
              {sourceVisibleOf(each)}
            </MenuItem>
          ))}
          {constant ? (
            <MenuItem value="constant">{say("workflow.fromConstant")}</MenuItem>
          ) : null}
        </TextField>
        {source.from === "constant" &&
        kind === "yes_no" &&
        !target.mustBeGiven ? (
          <TextField
            select
            size="small"
            label={say("workflow.constant")}
            value={source.typed}
            {...noted}
            onChange={(edit) =>
              onChange({ from: "constant", typed: edit.target.value })
            }
          >
            <MenuItem value="null">{say("workflow.constantNothing")}</MenuItem>
            <MenuItem value="true">{say("workflow.yes")}</MenuItem>
            <MenuItem value="false">{say("workflow.no")}</MenuItem>
          </TextField>
        ) : source.from === "constant" && kind === "yes_no" ? (
          <FormControlLabel
            control={
              <Switch
                checked={source.typed === "true"}
                onChange={(_event, checked) =>
                  onChange({ from: "constant", typed: String(checked) })
                }
              />
            }
            label={say("workflow.yes")}
          />
        ) : null}
        {second?.(noted)}
      </Box>
    </Box>
  );
}

export function sourceVisibleOf(source: SourceOffered): ReactElement {
  const step = source.step;
  if (step === undefined) return <bdi>{source.words}</bdi>;
  return (
    <>
      {visible("workflow.fromStep", {
        position: step.position,
        name:
          step.name === "" ? (
            <>
              {visible("workflow.unnamedVisible", {
                name: (
                  <bdi>
                    {say("workflow.defaultName", { position: step.position })}
                  </bdi>
                ),
                hint: (
                  <Box
                    component="span"
                    sx={{ typography: "systemHint", color: "text.secondary" }}
                  >
                    {say("name.notNamed")}
                  </Box>
                ),
              })}
            </>
          ) : (
            <bdi>{step.name}</bdi>
          ),
      })}
    </>
  );
}

function sourceVisible(
  source: SourceDraft,
  sources: readonly SourceOffered[],
  kind: ConstantKind,
): ReactElement {
  if (source.from === "input" || source.from === "step") {
    const place = sources.find(
      (each) =>
        each.value === (source.from === "input" ? "input" : source.stepKey),
    );
    const called =
      place === undefined ? (
        <>{say("workflow.fromNothing")}</>
      ) : (
        sourceVisibleOf(place)
      );
    return source.path === "" ? (
      called
    ) : (
      <>
        {visible("workflow.readAt", {
          place: called,
          path: <bdi>{source.path}</bdi>,
        })}
      </>
    );
  }
  return <>{sourceSaid(source, sources, kind)}</>;
}

function sourceSaid(
  source: SourceDraft,
  sources: readonly SourceOffered[],
  kind: ConstantKind,
): string {
  switch (source.from) {
    case "none":
      return say("workflow.fromNothing");
    case "constant":
      return say("workflow.constantSaid", {
        typed:
          kind === "yes_no"
            ? say(
                source.typed === "null"
                  ? "workflow.constantNothing"
                  : source.typed === "true"
                    ? "workflow.yes"
                    : "workflow.no",
              )
            : isolatedInText(source.typed),
      });
    case "input":
    case "step": {
      const place =
        sources.find(
          (each) =>
            each.value === (source.from === "input" ? "input" : source.stepKey),
        )?.words ?? say("workflow.fromNothing");
      return source.path === ""
        ? place
        : say("workflow.readAt", { place, path: source.path });
    }
  }
}

/**
 * What fills each field of what is run that a binding may fill, a field held by another set in beneath it; said
 * where the page does not know what it takes, and where it takes nothing.
 */
export function Inputs({
  heading,
  level,
  depth,
  rows,
  sources,
  editable,
  change,
  unknown,
}: {
  readonly heading: MessageId;
  /** One below the heading it is drawn under: a step's panel, or a case among the cases. */
  readonly level: "h5" | "h6";
  /** A group of a step's panel, or set in within a case, which is a group already. */
  readonly depth: 3 | 4;
  /** None where what it takes is not known here. */
  readonly rows: readonly TargetRow[] | undefined;
  readonly sources: readonly SourceOffered[];
  readonly editable: boolean;
  readonly change: (target: string, source: SourceDraft) => void;
  readonly unknown: MessageId;
}) {
  return (
    <Box sx={DEPTH_SX[depth]}>
      <Typography variant={level} sx={HEADING_SX}>
        {say(heading)}
      </Typography>
      {rows === undefined ? (
        <Typography variant="body2" sx={QUIET_SX}>
          {say(unknown)}
        </Typography>
      ) : rows.length === 0 ? (
        <Typography variant="body2" sx={QUIET_SX}>
          {say("workflow.inputsNone")}
        </Typography>
      ) : (
        <BoundRows
          rows={rows}
          sources={sources}
          constant
          editable={editable}
          change={change}
        />
      )}
    </Box>
  );
}

export function BoundRows({
  rows,
  sources,
  constant,
  editable,
  change,
}: {
  readonly rows: readonly TargetRow[];
  readonly sources: readonly SourceOffered[];
  readonly constant: boolean;
  readonly editable: boolean;
  readonly change: (target: string, source: SourceDraft) => void;
}) {
  return rows.map((row) => (
    <Box
      key={row.path}
      sx={
        row.depth === 0
          ? undefined
          : { ...INSET_SX, ml: (row.depth - 1) * INSET_SX.pl }
      }
    >
      <BindingControls
        label={row.path}
        target={row.target}
        binding={row.binding}
        sources={sources}
        constant={constant}
        editable={editable}
        flags={row.flags}
        onChange={(source) => change(row.path, source)}
      />
    </Box>
  ));
}
