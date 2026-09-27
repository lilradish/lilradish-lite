import Box from "@mui/material/Box";
import FormControlLabel from "@mui/material/FormControlLabel";
import MenuItem from "@mui/material/MenuItem";
import Switch from "@mui/material/Switch";
import TextField from "@mui/material/TextField";
import Typography from "@mui/material/Typography";
import { useId } from "react";

import { say, type MessageId } from "../../i18n/app";
import { isolatedInText } from "../../lib/direction/isolated";
import { Notice } from "../../lib/notice/Notice";
import {
  constantKindOf,
  constantRead,
  WRITTEN_LIMITS,
  type ConstantKind,
} from "./constantDrafts";
import {
  fits,
  pointers,
  type BindingDraft,
  type Shape,
  type SourceDraft,
  type SourceOffered,
  type TargetRow,
} from "./workflowDrafts";

const PART_SX = { mb: 3 };

const ROW_SX = {
  display: "flex",
  flexWrap: "wrap",
  gap: 2,
  alignItems: "center",
  mb: 1,
};

const QUIET_SX = { color: "text.secondary" };

const CONSTANT_WORDS = {
  text: "workflow.constant",
  number: "workflow.constantNumber",
  written: "workflow.constantWritten",
} as const satisfies Record<Exclude<ConstantKind, "yes_no">, MessageId>;

/**
 * What fills one input or output: one control picks where it comes from, and a second picks what within it or
 * types the constant. The second offers only fields that fit what is filled; one chosen already is kept in sight.
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
  const noteId = useId();
  const source: SourceDraft = binding?.source ?? { from: "none" };
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
    .filter((pointer) => fits(pointer.field, target))
    .map((pointer) => pointer.path);
  const choices =
    path === "" || fitting.includes(path) ? fitting : [path, ...fitting];
  const kind = constantKindOf(target);
  const read =
    source.from !== "constant" || source.read !== undefined
      ? null
      : constantRead(source.typed, target);
  const notes = [
    ...new Set([
      ...flags,
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
          {sourceSaid(source, sources, kind)}
        </Typography>
        {notes.map((note) => (
          <Notice key={note} severity="warning">
            {say(note, WRITTEN_LIMITS)}
          </Notice>
        ))}
      </Box>
    );
  }
  return (
    <Box
      role="group"
      aria-labelledby={fromId}
      aria-describedby={notes.length === 0 ? undefined : noteId}
      sx={{ mb: 2 }}
    >
      <Typography id={fromId} variant="subtitle2" component="p">
        <bdi>{label}</bdi>
      </Typography>
      <Box sx={ROW_SX}>
        <TextField
          select
          size="small"
          label={say("workflow.from")}
          value={picked}
          onChange={(edit) => {
            const chosen = edit.target.value;
            onChange(
              chosen === "none"
                ? { from: "none" }
                : chosen === "constant"
                  ? {
                      from: "constant",
                      typed: kind === "yes_no" ? "false" : "",
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
              {each.words}
            </MenuItem>
          ))}
          {constant ? (
            <MenuItem value="constant">{say("workflow.fromConstant")}</MenuItem>
          ) : null}
        </TextField>
        {source.from === "input" || source.from === "step" ? (
          <TextField
            select
            size="small"
            label={say("workflow.pointer")}
            value={path}
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
        ) : null}
        {source.from !== "constant" ? null : kind === "yes_no" ? (
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
        ) : (
          <TextField
            size="small"
            label={say(CONSTANT_WORDS[kind])}
            value={source.typed}
            error={read !== null && "why" in read}
            onChange={(edit) =>
              onChange({ from: "constant", typed: edit.target.value })
            }
            slotProps={{
              htmlInput:
                kind === "number" ? { inputMode: "decimal" } : { dir: "auto" },
            }}
          />
        )}
      </Box>
      {notes.length === 0 ? null : (
        <Box id={noteId}>
          {notes.map((note) => (
            <Notice key={note} severity="warning">
              {say(note, WRITTEN_LIMITS)}
            </Notice>
          ))}
        </Box>
      )}
    </Box>
  );
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
            ? say(source.typed === "true" ? "workflow.yes" : "workflow.no")
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
  rows,
  sources,
  editable,
  change,
  unknown,
}: {
  readonly heading: MessageId;
  /** One below the heading it is drawn under: a step's panel, or a case among the cases. */
  readonly level: "h5" | "h6";
  /** None where what it takes is not known here. */
  readonly rows: readonly TargetRow[] | undefined;
  readonly sources: readonly SourceOffered[];
  readonly editable: boolean;
  readonly change: (target: string, source: SourceDraft) => void;
  readonly unknown: MessageId;
}) {
  return (
    <Box sx={PART_SX}>
      <Typography variant="subtitle1" component={level}>
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
    <Box key={row.path} sx={{ pl: row.depth * 3 }}>
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
