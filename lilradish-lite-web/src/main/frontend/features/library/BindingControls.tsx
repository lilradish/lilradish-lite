import Box from "@mui/material/Box";
import { FieldName } from "../../lib/form/FieldName";
import MenuItem from "@mui/material/MenuItem";
import Switch from "@mui/material/Switch";
import TextField from "@mui/material/TextField";
import Typography from "@mui/material/Typography";
import {
  memo,
  useCallback,
  useId,
  useLayoutEffect,
  useRef,
  useState,
  type ReactElement,
  type ReactNode,
} from "react";

import { say, type MessageId } from "../../i18n/app";
import { visible } from "../../i18n/visible";
import { decimalSaid } from "../../i18n/intl";
import { DEPTH_SX, HEADING_SX } from "../../lib/layout/parts";
import { useNarrowerThan } from "../../lib/layout/useNarrowerThan";
import { NATIVE_TABLE_SX, TABLE_GEOMETRY } from "../../lib/collection/table";
import { KeyTag } from "../../lib/text/KeyTag";
import { Notice } from "../../lib/notice/Notice";
import { TOKENS } from "../../lib/theme/tokens";
import { hasLineBreak } from "../../lib/filling/writing";
import {
  constantKindOf,
  constantHasLineBreak,
  constantRead,
  WRITTEN_LIMITS,
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

const QUIET_SX = { color: "text.secondary" };
const LABEL_SX = {
  typography: "controlLabel",
  color: "text.secondary",
  mb: "4px",
};
const TABLE_SX = {
  ...NATIVE_TABLE_SX,
  maxWidth: 860,
  "& td:not([colspan]):first-of-type": { pr: "8px" },
  "& td:not([colspan]):last-of-type": { pl: "8px" },
};
const NARROW_GAP_SX = {
  "& td:not([colspan]):first-of-type": { pr: "4px" },
  "& td:not([colspan]):last-of-type": { pl: "4px" },
};
const NARROW_TABLE_SX = {
  display: "block",
  "& tbody": { display: "block" },
  "& tr": {
    display: "grid",
    gridTemplateColumns: "repeat(2,minmax(0,1fr))",
    borderBottomWidth: TABLE_GEOMETRY.bodyRule,
    borderBottomStyle: "solid",
    borderBottomColor: "surface.rule",
  },
  "& td": { display: "block", height: "auto", borderBottomStyle: "none" },
  '& td[colspan="2"]': { gridColumn: "1 / -1" },
  "& tr:first-of-type td": { pb: 0 },
  "& tr:not(:first-of-type) td": { pt: "6px" },
};

interface BindingProps {
  readonly label: string;
  readonly target: Shape;
  readonly binding: BindingDraft | undefined;
  readonly sources: readonly SourceOffered[];
  readonly constant: boolean;
  readonly editable: boolean;
  readonly flags: readonly MessageId[];
  readonly onChange: (source: SourceDraft) => void;
}

interface SourceCatalog {
  readonly sources: readonly SourceOffered[];
  readonly entries: readonly {
    readonly value: string;
    readonly reached: ReturnType<typeof pointers>;
    readonly fieldsByPath: ReadonlyMap<string, Shape>;
  }[];
}

function catalogOf(sources: readonly SourceOffered[]): SourceCatalog {
  return {
    sources,
    entries: sources.map((source) => {
      const reached =
        source.fields === undefined ? [] : pointers(source.fields);
      const fieldsByPath = new Map<string, Shape>();
      for (const pointer of reached) {
        if (!fieldsByPath.has(pointer.path)) {
          fieldsByPath.set(pointer.path, pointer.field);
        }
      }
      return { value: source.value, reached, fieldsByPath };
    }),
  };
}

function useSourceCatalog(sources: readonly SourceOffered[]): SourceCatalog {
  // Owners rebuild the bounded declaration shapes on every edit; identity alone would expand every source again.
  const key = JSON.stringify(sources);
  const [held, hold] = useState(() => ({ key, catalog: catalogOf(sources) }));
  if (held.key !== key) {
    const next = { key, catalog: catalogOf(sources) };
    hold(next);
    return next.catalog;
  }
  return held.catalog;
}

/**
 * What fills one input or output: where it comes from, then what within it or the constant, offering only fields that
 * fit and keeping one chosen in sight. A row's problems describe both controls beneath them.
 */
export function BindingControls(props: BindingProps) {
  const container = useRef<HTMLDivElement>(null);
  const narrow = useNarrowerThan(
    container,
    Number.parseFloat(TOKENS.table.rowsBelow),
  );
  const catalog = useSourceCatalog(props.sources);
  return (
    <Box ref={container}>
      <Box
        component="table"
        sx={[TABLE_SX, NARROW_TABLE_SX, narrow ? NARROW_GAP_SX : {}]}
      >
        <BindingRow
          {...props}
          catalog={catalog}
          name={props.label}
          narrow={narrow}
          labels
        />
      </Box>
    </Box>
  );
}

function BindingRow({
  label,
  target,
  binding,
  catalog,
  constant,
  editable,
  flags,
  onChange,
  name,
  fieldLabel,
  depth = 0,
  narrow,
  labels = false,
  path: rowPath,
}: Omit<BindingProps, "sources"> & {
  readonly catalog: SourceCatalog;
  readonly name: string;
  readonly fieldLabel?: string;
  readonly depth?: number;
  readonly narrow: boolean;
  readonly labels?: boolean;
  /** The field it fills, which a reader taken to the row by its change finds it by. */
  readonly path?: string;
}) {
  const sources = catalog.sources;
  const noteId = useId();
  const writtenHintId = useId();
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
  const entry = catalog.entries.find((each) => each.value === picked);
  const reached = entry?.reached ?? [];
  const fieldsByPath = entry?.fieldsByPath;
  const fitting = reached
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
  const valueName = say("workflow.valueFor", { field: label });
  const described = notes.length === 0 ? undefined : noteId;
  const valueDescription =
    [kind === "written" ? writtenHintId : undefined, described]
      .filter(Boolean)
      .join(" ") || undefined;
  const layout = (from: ReactNode, which: ReactNode) => {
    const title = (
      <Box
        sx={{ typography: "fieldName", pl: `${depth * (narrow ? 16 : 24)}px` }}
      >
        <FieldName name={name} label={fieldLabel} context="author" />
      </Box>
    );
    const fromCell = (
      <>
        {narrow || labels ? (
          <Box sx={LABEL_SX}>{say("workflow.from")}</Box>
        ) : null}
        {from}
      </>
    );
    const whichCell =
      which === null ? null : (
        <>
          {narrow || labels ? (
            <Box sx={LABEL_SX}>{say("workflow.pointer")}</Box>
          ) : null}
          {which}
        </>
      );
    const problems =
      notes.length === 0 ? null : (
        <Box
          id={noteId}
          sx={{
            color: editable ? "error.main" : undefined,
            typography: "supportingProse",
            mt: labels ? "6px" : undefined,
          }}
        >
          {notes.map((note) =>
            editable ? (
              <div key={note}>{say(note, WRITTEN_LIMITS)}</div>
            ) : (
              <Notice key={note} severity="warning">
                {say(note, WRITTEN_LIMITS)}
              </Notice>
            ),
          )}
        </Box>
      );
    return (
      <tbody aria-label={label}>
        <tr>
          <td colSpan={2}>{title}</td>
        </tr>
        {labels ? (
          <tr data-path={rowPath}>
            <td colSpan={2}>
              <Box
                role="group"
                aria-label={label}
                sx={{ pl: narrow ? `${depth * 16}px` : 0 }}
              >
                <Box
                  sx={{
                    display: "grid",
                    gridTemplateColumns: "repeat(2,minmax(0,1fr))",
                    gap: narrow ? 1 : 2,
                  }}
                >
                  <div>{fromCell}</div>
                  {whichCell === null ? null : <div>{whichCell}</div>}
                </Box>
                {problems}
              </Box>
            </td>
          </tr>
        ) : (
          <>
            <Box
              component="tr"
              data-path={rowPath}
              sx={{ pl: narrow ? `${depth * 16}px` : 0 }}
            >
              <td>{fromCell}</td>
              <td>{whichCell}</td>
            </Box>
            {problems === null ? null : (
              <tr>
                <td colSpan={2}>{problems}</td>
              </tr>
            )}
          </>
        )}
      </tbody>
    );
  };
  const pointerVisible = (choice: string) => {
    const field = fieldsByPath?.get(choice);
    return field?.label && field.label !== choice ? (
      <Box component="span" sx={{ overflowWrap: "anywhere" }}>
        <bdi>{field.label}</bdi> <KeyTag>{choice}</KeyTag>
      </Box>
    ) : (
      <bdi>{choice}</bdi>
    );
  };
  if (!editable) {
    return layout(
      source.from === "input" || source.from === "step"
        ? sourceVisibleOf(
            sources.find(
              (each) =>
                each.value ===
                (source.from === "input" ? "input" : source.stepKey),
            ) ?? {
              value: "",
              words: say("workflow.fromNothing"),
              fields: [],
            },
            false,
          )
        : source.from === "constant"
          ? say("workflow.fromConstant")
          : say("workflow.fromNothing"),
      source.from === "input" || source.from === "step" ? (
        pointerVisible(source.path)
      ) : source.from === "constant" ? (
        kind === "yes_no" ? (
          say(
            source.typed === "null"
              ? "workflow.constantNothing"
              : source.typed === "true"
                ? "workflow.yes"
                : "workflow.no",
          )
        ) : (
          <bdi>
            {kind === "number" &&
            source.typed !== "null" &&
            "written" in constantRead(source.typed, target)
              ? decimalSaid(source.typed)
              : source.typed}
          </bdi>
        )
      ) : null,
    );
  }
  const noted = { error: notes.length > 0 };
  const second =
    source.from === "input" || source.from === "step" ? (
      <TextField
        data-binding-control="which"
        select
        size="small"
        fullWidth
        value={path}
        {...noted}
        slotProps={{
          select: {
            "aria-label": say("workflow.pointerFor", { field: label }),
            "aria-describedby": described,
          },
        }}
        onChange={(edit) => onChange({ ...source, path: edit.target.value })}
      >
        <MenuItem value="">{say("workflow.pointerNone")}</MenuItem>
        {choices.map((choice) => (
          <MenuItem key={choice} value={choice}>
            {pointerVisible(choice)}
          </MenuItem>
        ))}
      </TextField>
    ) : source.from === "constant" && kind !== "yes_no" ? (
      <>
        {kind === "written" ? (
          <Box
            id={writtenHintId}
            sx={{
              typography: "supportingProse",
              color: "text.secondary",
              mb: "4px",
            }}
          >
            {say("workflow.constantWrittenHint")}
          </Box>
        ) : null}
        <TextField
          data-binding-control="typed"
          size="small"
          fullWidth
          value={source.typed}
          {...noted}
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
            htmlInput: {
              "aria-label": valueName,
              "aria-describedby": valueDescription,
              ...(kind === "number"
                ? { inputMode: "decimal" }
                : { dir: "auto" }),
            },
          }}
        />
      </>
    ) : null;
  return layout(
    <TextField
      data-binding-control="from"
      select
      size="small"
      fullWidth
      slotProps={{
        select: {
          "aria-label": say("workflow.fromFor", { field: label }),
          "aria-describedby": described,
        },
      }}
      value={picked}
      {...noted}
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
    </TextField>,
    source.from === "constant" && kind === "yes_no" && !target.mustBeGiven ? (
      <TextField
        data-binding-control="typed"
        select
        size="small"
        fullWidth
        slotProps={{
          select: { "aria-label": valueName, "aria-describedby": described },
        }}
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
      <Switch
        data-binding-control="typed"
        slotProps={{
          input: { "aria-label": valueName, "aria-describedby": described },
        }}
        checked={source.typed === "true"}
        onChange={(_event, checked) =>
          onChange({ from: "constant", typed: String(checked) })
        }
      />
    ) : (
      second
    ),
  );
}

export function sourceVisibleOf(
  source: SourceOffered,
  binding = true,
): ReactElement {
  const step = source.step;
  if (step === undefined) return <bdi>{source.words}</bdi>;
  return (
    <>
      {visible("workflow.fromStep", {
        position: step.position,
        name:
          step.label !== "" ? (
            binding ? (
              <FieldName name={step.name} label={step.label} context="author" />
            ) : (
              <bdi>{step.label}</bdi>
            )
          ) : step.name === "" ? (
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

interface BoundRowProps {
  readonly row: TargetRow;
  readonly rowKey: string;
  readonly catalog: SourceCatalog;
  readonly narrow: boolean;
  readonly constant: boolean;
  readonly editable: boolean;
  readonly change: (target: string, source: SourceDraft) => void;
}

const BoundRow = memo(
  function BoundRow({
    row,
    catalog,
    narrow,
    constant,
    editable,
    change,
  }: BoundRowProps) {
    const name = row.path.split(".").at(-1)!;
    return (
      <BindingRow
        path={row.path}
        label={row.label || name}
        name={name}
        fieldLabel={row.label}
        depth={row.depth}
        narrow={narrow}
        target={row.target}
        binding={row.binding}
        catalog={catalog}
        constant={constant}
        editable={editable}
        flags={row.flags}
        onChange={(source) => change(row.path, source)}
      />
    );
  },
  (before, after) =>
    before.rowKey === after.rowKey &&
    before.catalog === after.catalog &&
    before.narrow === after.narrow &&
    before.constant === after.constant &&
    before.editable === after.editable &&
    before.change === after.change,
);

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
  const container = useRef<HTMLDivElement>(null);
  const narrow = useNarrowerThan(
    container,
    Number.parseFloat(TOKENS.table.rowsBelow),
  );
  const catalog = useSourceCatalog(sources);
  const receiver = useRef(change);
  useLayoutEffect(() => {
    receiver.current = change;
  }, [change]);
  const changeBinding = useCallback(
    (target: string, source: SourceDraft) => receiver.current(target, source),
    [],
  );
  return (
    <Box ref={container}>
      <Box
        component="table"
        sx={[
          TABLE_SX,
          narrow ? NARROW_TABLE_SX : {},
          narrow ? NARROW_GAP_SX : {},
        ]}
      >
        {narrow ? null : (
          <>
            <colgroup>
              <col style={{ width: "50%" }} />
              <col style={{ width: "50%" }} />
            </colgroup>
            <thead>
              <tr>
                <th scope="col">{say("workflow.from")}</th>
                <th scope="col">{say("workflow.pointer")}</th>
              </tr>
            </thead>
          </>
        )}
        {rows.map((row) => (
          <BoundRow
            key={row.path}
            row={row}
            rowKey={JSON.stringify(row)}
            catalog={catalog}
            narrow={narrow}
            constant={constant}
            editable={editable}
            change={changeBinding}
          />
        ))}
      </Box>
    </Box>
  );
}
