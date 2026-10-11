import Box from "@mui/material/Box";
import Button from "@mui/material/Button";
import DeleteOutlineIcon from "@mui/icons-material/DeleteOutlined";
import Checkbox from "@mui/material/Checkbox";
import FormControl from "@mui/material/FormControl";
import FormControlLabel from "@mui/material/FormControlLabel";
import FormHelperText from "@mui/material/FormHelperText";
import FormLabel from "@mui/material/FormLabel";
import MenuItem from "@mui/material/MenuItem";
import Radio from "@mui/material/Radio";
import RadioGroup from "@mui/material/RadioGroup";
import Stack from "@mui/material/Stack";
import TextField from "@mui/material/TextField";
import Typography from "@mui/material/Typography";
import {
  memo,
  useEffect,
  useId,
  useLayoutEffect,
  useMemo,
  useRef,
  useState,
  type FocusEvent,
  type ReactNode,
} from "react";

import {
  pathKey,
  type FillField,
  type FillPath,
  type FillReason,
} from "../../../api/filling";
import { say, type MessageId } from "../../../i18n/app";
import { visible } from "../../../i18n/visible";
import { Press } from "../../../lib/action/Press";
import { IconPress } from "../../../lib/action/IconPress";
import { FieldName } from "../../../lib/form/FieldName";
import { isolated, isolatedInText } from "../../../lib/direction/isolated";
import { drawingOf } from "../../../lib/text/hidden";
import { SystemHint } from "../../../lib/text/HiddenShownIn";
import { sizeSaid } from "../../../lib/text/sizeSaid";
import {
  codePointsIn,
  hasLineBreak,
  FIRST_YEAR,
  FURTHEST_OFFSET_MINUTES,
  LAST_YEAR,
  MOST_DIGITS,
} from "../../../lib/filling/writing";
import { BLOCK_SX, INSET_SX, QUIET_SX } from "../../../lib/layout/parts";
import { FileCard } from "./FileCard";
import {
  fileIssueOf,
  offsetOf,
  offsetWritten,
  oneDraftOf,
  type FileDraft,
  type FileIssue,
  type FieldDraft,
  type LevelDraft,
  type MomentDraft,
  type OneDraft,
} from "./fillDrafts";

const REASONS = {
  missing: "fill.missing",
  malformed: "fill.malformed",
  line_break: "fill.line_break",
  too_long: "fill.too_long",
  too_many: "fill.too_many",
  not_a_term: "fill.not_a_term",
  crlf: "fill.crlf",
  direction_control: "fill.direction_control",
  tag: "fill.tag",
  unusable: "fill.unusable",
} as const satisfies Record<FillReason, MessageId>;

const FIRST_DAY = `${String(FIRST_YEAR).padStart(4, "0")}-01-01`;

const LAST_DAY = `${String(LAST_YEAR).padStart(4, "0")}-12-31`;

const OFFSET_STEP_MINUTES = 15;

const ROW_SX = { display: "flex", flexWrap: "wrap", gap: 1, mt: 1 };

const ITEM_SX = { display: "flex", alignItems: "flex-start", gap: 1, mt: 1 };

const ELEMENT_SX = { flex: "1 1 auto", minWidth: 0 };

const OWN_LINE_SX = { display: "block" };

const REJECTED_PAGE_SIZE = 50;

const MEANING_SX = { ...QUIET_SX, display: "block", mb: 0 };

const TIME_INPUT = {
  inputLabel: { shrink: true },
  htmlInput: { inputMode: "numeric", dir: "ltr" },
};

const DATE_INPUT = {
  formHelperText: { component: "div" as const },
  inputLabel: { shrink: true },
  htmlInput: { min: FIRST_DAY, max: LAST_DAY },
};

// Text may run either way, so its box takes its direction from what is typed.
const TEXT_INPUT = {
  htmlInput: { dir: "auto" },
  formHelperText: { component: "div" as const },
};

// Not decimal: a keyboard for decimals may have no minus key, and a number may start with one.
const NUMBER_INPUT = {
  htmlInput: { inputMode: "text", dir: "ltr" },
  formHelperText: { component: "div" as const },
};

const QUARTER_HOUR_OFFSETS: readonly string[] = Array.from(
  { length: (2 * FURTHEST_OFFSET_MINUTES) / OFFSET_STEP_MINUTES + 1 },
  (_, index) =>
    offsetWritten(-FURTHEST_OFFSET_MINUTES + index * OFFSET_STEP_MINUTES),
);

/**
 * What now stands at a place the reader changed, which may now say what it lacks; and, where one of many was taken
 * away, its index, so what is kept of each place after it can move down with it.
 */
export type Drafted = (
  path: FillPath,
  next: FieldDraft,
  takenAway?: number,
) => void;

/** The place the keyboard has left, which may now say why what is in it does not fit. */
export type Left = (left: FillPath) => void;

/** Whether the browser holds at a place what was typed and it could not read, which it gives as nothing. */
export type Unread = (path: FillPath, unreadable: boolean) => void;

type Landing =
  | { readonly at: "control"; readonly index: number }
  | { readonly at: "takeAway"; readonly key: number }
  | { readonly at: "many" };

/** A key to each one of many, given as it is added and kept while it is held. */
interface ElementKeys {
  readonly held: readonly number[];
  readonly next: number;
}

/** What every control is handed, wherever it stands; each is the same from one keystroke to the next. */
interface Placed {
  readonly formId: string;
  readonly path: FillPath;
  readonly onDraft: Drafted;
  readonly onLeave: Left;
  readonly onUnread: Unread;
}

interface Holding extends Placed {
  readonly marks: ReadonlyMap<string, FillReason>;
}

/**
 * What each place's control is drawn under by the form drawn under `formId`, so the keyboard can be taken there:
 * one id to each place, as `pathKey` keys one, with nothing in it an id may not hold.
 */
export function placeId(formId: string, path: FillPath): string {
  return `${formId}-${encodeURIComponent(pathKey(path))}`;
}

/**
 * Fields and nested fields follow declaration order; `marks` identifies problems by path.
 * Memoised controls require stable handlers to avoid redrawing unchanged values.
 */
export function FillForm({
  formId,
  fields,
  draft,
  onDraft,
  onLeave,
  onUnread,
  marks,
  beside,
}: {
  readonly formId: string;
  readonly fields: readonly FillField[];
  readonly draft: LevelDraft;
  readonly onDraft: Drafted;
  readonly onLeave: Left;
  readonly onUnread: Unread;
  readonly marks: ReadonlyMap<string, FillReason>;
  /**
   * Words said of a field of the first level, by its name, said under its control, which is described by them; the
   * same map while they are the same, as a handler stays.
   */
  readonly beside?: ReadonlyMap<string, ReactNode>;
}) {
  return (
    <Level
      formId={formId}
      fields={fields}
      draft={draft}
      path={[]}
      onDraft={onDraft}
      onLeave={onLeave}
      onUnread={onUnread}
      marks={marks}
      beside={beside}
    />
  );
}

function Level({
  fields,
  draft,
  beside,
  ...holding
}: Holding & {
  readonly fields: readonly FillField[];
  readonly draft: LevelDraft;
  readonly beside?: ReadonlyMap<string, ReactNode>;
}) {
  return (
    <Stack spacing={2}>
      {fields.map((field) => {
        const held = draft[field.name]!;
        const placed: Holding = {
          ...holding,
          path: [...holding.path, field.name],
        };
        const said = beside?.get(field.name);
        return (
          <Box key={field.name} sx={BLOCK_SX}>
            {Array.isArray(held) ? (
              field.kind === "file" ? (
                <FileMany
                  key={field.name}
                  field={field}
                  items={held as readonly FileDraft[]}
                  said={said}
                  {...placed}
                />
              ) : field.kind === "term" ? (
                <TermMany
                  key={field.name}
                  field={field}
                  items={held as readonly string[]}
                  said={said}
                  {...placed}
                />
              ) : (
                <Many
                  key={field.name}
                  field={field}
                  items={held}
                  said={said}
                  {...placed}
                />
              )
            ) : (
              <Element
                key={field.name}
                field={field}
                label={labelOf(field)}
                held={held as OneDraft}
                said={said}
                {...placed}
              />
            )}
          </Box>
        );
      })}
    </Stack>
  );
}

function Element({
  field,
  label,
  held,
  marks,
  ...placed
}: Holding & {
  readonly field: FillField;
  readonly label: string;
  readonly held: OneDraft;
  readonly said?: ReactNode;
}) {
  return field.kind === "fields" ? (
    <Fields
      field={field}
      label={label}
      held={held as LevelDraft}
      marks={marks}
      {...placed}
    />
  ) : (
    <One
      field={field}
      label={label}
      held={held}
      mark={marks.get(pathKey(placed.path))}
      {...placed}
    />
  );
}

const Many = memo(function Many({
  field,
  items,
  formId,
  path,
  onDraft,
  onLeave,
  onUnread,
  marks,
  said,
}: Holding & {
  readonly field: FillField;
  readonly items: readonly OneDraft[];
  readonly said?: ReactNode;
}) {
  const mark = marks.get(pathKey(path));
  const most = field.most ?? 0;
  const label = labelOf(field);
  const id = placeId(formId, path);
  const legendId = useId();
  const helperId = useId();
  const [keys, setKeys] = useState(() => keysFor(items.length, 0));
  const elements = useRef(new Map<number, HTMLElement>());
  const landing = useRef<Landing | null>(null);
  // Said once, over all of them, rather than again under each; an empty one is missing whatever the field's demand.
  const each = useMemo((): FillField => {
    const { help: _help, ...rest } = field;
    return { ...rest, mustBeGiven: true };
  }, [field]);
  // Items replaced from outside with a different count are given fresh keys.
  const shown =
    keys.held.length === items.length ? keys : keysFor(items.length, keys.next);
  if (shown !== keys) {
    setKeys(shown);
  }

  useEffect(() => {
    const to = landing.current;
    landing.current = null;
    if (to === null) {
      return;
    }
    const target =
      to.at === "control"
        ? document.getElementById(placeId(formId, [...path, to.index]))
        : to.at === "takeAway"
          ? elements.current
              .get(to.key)
              ?.querySelector<HTMLButtonElement>(":scope > button")
          : document.getElementById(id);
    target?.focus();
  });

  const addOne = () => {
    landing.current = { at: "control", index: items.length };
    setKeys({ held: [...shown.held, shown.next], next: shown.next + 1 });
    onDraft(path, [...items, oneDraftOf(field)]);
  };
  const takeAway = (index: number) => {
    const following = shown.held[index + 1];
    landing.current =
      following === undefined
        ? { at: "many" }
        : { at: "takeAway", key: following };
    setKeys({ ...shown, held: shown.held.toSpliced(index, 1) });
    onDraft(path, items.toSpliced(index, 1), index);
  };

  return (
    <FormControl
      component="fieldset"
      id={id}
      tabIndex={-1}
      aria-labelledby={legendId}
      aria-describedby={helperId}
      error={mark !== undefined}
      required={field.mustBeGiven}
    >
      <FormLabel component="legend" id={legendId}>
        <FieldName name={field.name} label={field.label}>
          {label}
        </FieldName>
      </FormLabel>
      <FormHelperText component="div" id={helperId}>
        <Under
          mark={mark}
          said={said}
          help={field.help}
          also={say("fill.count", { count: items.length, most })}
        />
      </FormHelperText>
      {items.length === 0 ? (
        <Typography variant="body2" sx={QUIET_SX}>
          {say("fill.none")}
        </Typography>
      ) : null}
      {items.map((item, index) => {
        const key = shown.held[index]!;
        const element = say("fill.element", {
          label: isolatedInText(label),
          position: index + 1,
        });
        return (
          <Box
            key={key}
            ref={(node: HTMLElement) => {
              elements.current.set(key, node);
              return () => {
                elements.current.delete(key);
              };
            }}
            sx={ITEM_SX}
          >
            <Box sx={ELEMENT_SX}>
              <Element
                field={each}
                label={element}
                held={item}
                formId={formId}
                path={[...path, index]}
                onDraft={onDraft}
                onLeave={onLeave}
                onUnread={onUnread}
                marks={marks}
              />
            </Box>
            <IconPress
              icon={<DeleteOutlineIcon />}
              unavailable={false}
              label={say("fill.takeAwayOne", { element })}
              onPress={() => takeAway(index)}
            >
              {say("fill.takeAway")}
            </IconPress>
          </Box>
        );
      })}
      {items.length < most ? (
        <Box sx={ROW_SX}>
          <Press onPress={addOne}>
            {say("fill.addOne", { label: isolatedInText(label) })}
          </Press>
        </Box>
      ) : null}
    </FormControl>
  );
}, samePlace);

const One = memo(function One({
  field,
  label,
  held,
  mark,
  formId,
  path,
  onDraft,
  onLeave,
  onUnread,
  said,
}: Placed & {
  readonly field: FillField;
  readonly label: string;
  readonly held: OneDraft;
  readonly mark: FillReason | undefined;
  readonly said?: ReactNode;
}) {
  const id = placeId(formId, path);
  const [rejected, setRejected] = useState<{
    readonly held: OneDraft;
    readonly field: FillField;
    readonly formId: string;
    readonly reason: FillReason;
  } | null>(null);
  const typed = (next: string) => {
    if (field.kind === "text" && codePointsIn(next) > (field.longest ?? 0)) {
      setRejected({ held: next, field, formId, reason: "too_long" });
      onDraft(path, next);
      return;
    }
    setRejected(null);
    onDraft(path, next);
  };
  const left = () => onLeave(path);
  const oneLineText = field.kind === "text" && field.lines === "one";
  const shownMark =
    rejected?.held === held &&
    rejected.field === field &&
    rejected.formId === formId
      ? rejected.reason
      : mark;
  switch (field.kind) {
    case "text":
      return (
        <TextField
          id={id}
          label={
            <FieldName name={field.name} label={field.label}>
              {label}
            </FieldName>
          }
          value={held as string}
          onChange={(edit) => typed(edit.target.value)}
          onPaste={(event) => {
            if (
              oneLineText &&
              hasLineBreak(event.clipboardData.getData("text"))
            ) {
              event.preventDefault();
              setRejected({ held, field, formId, reason: "line_break" });
            }
          }}
          onBeforeInput={(event) => {
            const next = (event as typeof event & { data?: string | null })
              .data;
            if (oneLineText && typeof next === "string" && hasLineBreak(next)) {
              event.preventDefault();
              setRejected({ held, field, formId, reason: "line_break" });
            }
          }}
          onDrop={(event) => {
            if (
              oneLineText &&
              hasLineBreak(event.dataTransfer.getData("text/plain"))
            ) {
              event.preventDefault();
              setRejected({ held, field, formId, reason: "line_break" });
            }
          }}
          onBlur={left}
          required={field.mustBeGiven}
          error={shownMark !== undefined}
          helperText={
            <Under
              mark={shownMark}
              said={said}
              help={field.help}
              also={say("fill.count", {
                count: codePointsIn(held as string),
                most: field.longest ?? 0,
              })}
            />
          }
          slotProps={TEXT_INPUT}
          multiline={!oneLineText}
          minRows={oneLineText ? undefined : 3}
          fullWidth
        />
      );
    case "number":
      return (
        <TextField
          id={id}
          label={
            <FieldName name={field.name} label={field.label}>
              {label}
            </FieldName>
          }
          value={held as string}
          onChange={(edit) => typed(edit.target.value)}
          onBlur={left}
          required={field.mustBeGiven}
          error={mark !== undefined}
          helperText={
            <Under
              mark={mark}
              said={said}
              help={field.help}
              also={say("fill.numberHow", { digits: MOST_DIGITS })}
            />
          }
          slotProps={NUMBER_INPUT}
        />
      );
    case "date":
      return (
        <DateControl
          field={field}
          label={label}
          id={id}
          day={held as string}
          mark={mark}
          said={said}
          onDay={typed}
          onLeave={left}
          onUnread={(unreadable) => onUnread(path, unreadable)}
        />
      );
    case "moment":
      return (
        <Moment
          field={field}
          label={label}
          id={id}
          moment={held as MomentDraft}
          mark={mark}
          said={said}
          onMoment={(next) => onDraft(path, next)}
          onLeave={left}
          onUnread={(unreadable) => onUnread(path, unreadable)}
        />
      );
    case "yes_no":
      return (
        <Choice
          field={field}
          label={label}
          id={id}
          chosen={held as string}
          mark={mark}
          said={said}
          onChoose={typed}
          choices={[
            { value: "true", words: say("fill.yes") },
            { value: "false", words: say("fill.no") },
          ]}
          row
        />
      );
    case "term":
      return (
        <Choice
          field={field}
          label={label}
          id={id}
          chosen={held as string}
          mark={mark}
          said={said}
          onChoose={typed}
          note={field.terms?.note}
          choices={(field.terms?.terms ?? []).map(({ term, meaning }) => ({
            value: term,
            words: (
              <>
                <Typography component="span" sx={OWN_LINE_SX}>
                  {isolated(term)}
                </Typography>
                <Typography variant="body2" component="span" sx={MEANING_SX}>
                  {isolated(meaning)}
                </Typography>
              </>
            ),
          }))}
        />
      );
    case "file":
      return (
        <FileOne
          field={field}
          label={label}
          held={held as FileDraft}
          mark={mark}
          said={said}
          formId={formId}
          path={path}
          onDraft={onDraft}
          onLeave={onLeave}
          onUnread={onUnread}
        />
      );
    case "fields":
      return null;
  }
}, samePlace);

const FILE_ISSUE_HINTS = {
  name: "fill.fileNameHint",
  type_missing: "fill.fileTypeMissingHint",
  type_not_taken: "fill.fileTypeNotTakenHint",
  too_large: "fill.fileTooLargeHint",
  too_many: "fill.fileTooManyHint",
} as const satisfies Record<FileIssue | "too_many", MessageId>;

function fileNameOf(held: FileDraft): string | undefined {
  return held === null
    ? undefined
    : "pending" in held
      ? held.pending.name
      : held.kept.name;
}

function shownFileName(name: string, drawing = drawingOf([name])): string {
  if ("kept" in drawing) {
    return isolatedInText(
      `${drawing.kept.get(name) ?? ""} ${say("text.leftOut", { count: drawing.leftOut })}`,
    );
  }
  const pieces = drawing.pieces.get(name) ?? [];
  return isolatedInText(
    pieces
      .map((piece) =>
        "text" in piece
          ? piece.text
          : say("text.marker", {
              code: piece.codePoint.toString(16).toUpperCase().padStart(4, "0"),
              count: piece.count,
            }),
      )
      .join(""),
  );
}

function fileNameVisible(name: string) {
  const drawing = drawingOf([name]);
  if ("kept" in drawing) {
    return (
      <>
        <bdi>{isolatedInText(drawing.kept.get(name) ?? "")}</bdi>{" "}
        <SystemHint>
          {say("text.leftOut", { count: drawing.leftOut })}
        </SystemHint>
      </>
    );
  }
  return <bdi>{shownFileName(name, drawing)}</bdi>;
}

function fileStatusVisible(name: string, hint: MessageId): ReactNode {
  return visible("fill.fileStatusVisible", {
    name: fileNameVisible(name),
    hint: (
      <Box component="span" sx={{ typography: "systemHint" }}>
        {say(hint)}
      </Box>
    ),
  });
}

function fileIssueVisible(
  name: string,
  issue: FileIssue | "too_many",
): ReactNode {
  return fileStatusVisible(name, FILE_ISSUE_HINTS[issue]);
}

const FileOne = memo(function FileOne({
  field,
  label,
  held,
  mark,
  said,
  formId,
  path,
  onDraft,
  onLeave,
}: Placed & {
  readonly field: FillField;
  readonly label: string;
  readonly held: FileDraft;
  readonly mark: FillReason | undefined;
  readonly said?: ReactNode;
}) {
  const id = placeId(formId, path);
  const helperId = useId();
  const dropId = useId();
  const input = useRef<HTMLInputElement>(null);
  const chooser = useRef<HTMLButtonElement>(null);
  const [rejected, setRejected] = useState<{
    readonly name: string;
    readonly issue: FileIssue | "too_many";
  } | null>(null);
  const name = fileNameOf(held);
  const issue =
    held !== null && "pending" in held
      ? fileIssueOf(field, held.pending)
      : null;
  const status =
    name === undefined
      ? undefined
      : issue !== null
        ? fileIssueVisible(name, issue)
        : undefined;
  const describedBy =
    (held === null && mark !== undefined) ||
    said !== undefined ||
    field.help !== undefined ||
    rejected !== null ||
    status !== undefined
      ? `${dropId} ${helperId}`
      : dropId;
  const choose = (chosen: readonly File[]) => {
    const file = chosen[0];
    if (file === undefined) return;
    const refused = chosen.length > 1 ? "too_many" : fileIssueOf(field, file);
    if (refused === null) {
      setRejected(null);
      onDraft(path, { pending: file });
    } else setRejected({ name: file.name, issue: refused });
    onLeave(path);
  };
  return (
    <FormControl
      error={mark !== undefined || issue !== null || rejected !== null}
      required={field.mustBeGiven}
      fullWidth
      sx={{
        "&:focus-within": {
          outline: "2px solid",
          outlineColor: "primary.main",
          outlineOffset: 2,
          borderRadius: 1,
        },
      }}
      onDragOver={(event) => {
        event.preventDefault();
        event.dataTransfer.dropEffect = "copy";
      }}
      onDrop={(event) => {
        event.preventDefault();
        choose(Array.from(event.dataTransfer.files));
      }}
    >
      <FormLabel htmlFor={`${id}-native`}>
        <FieldName name={field.name} label={field.label}>
          {label}
        </FieldName>
      </FormLabel>
      <Typography id={dropId} variant="body2" sx={QUIET_SX}>
        {say("fill.fileDropOne", {
          endings: isolatedInText((field.types ?? []).join(", ")),
          size: sizeSaid(field.maximumBytes ?? 0),
        })}
      </Typography>
      <Button
        id={id}
        ref={chooser}
        aria-describedby={describedBy}
        onClick={() => input.current?.click()}
      >
        {say("fill.chooseFile")}
      </Button>
      <input
        id={`${id}-native`}
        ref={input}
        type="file"
        tabIndex={-1}
        style={{ position: "absolute", width: 1, height: 1, opacity: 0 }}
        accept={field.types?.join(",")}
        aria-describedby={describedBy}
        aria-invalid={
          mark !== undefined || issue !== null || rejected !== null
            ? true
            : undefined
        }
        aria-required={field.mustBeGiven ? true : undefined}
        onChange={(event) => {
          choose(Array.from(event.currentTarget.files ?? []));
          event.currentTarget.value = "";
        }}
        onBlur={() => onLeave(path)}
      />
      <FormHelperText component="div" id={helperId}>
        <Under
          mark={held === null ? mark : undefined}
          said={said}
          help={field.help}
        />
        {rejected === null ? null : (
          <Box component="span" sx={OWN_LINE_SX}>
            {fileIssueVisible(rejected.name, rejected.issue)}
          </Box>
        )}
        {status === undefined ? null : (
          <Box component="span" sx={OWN_LINE_SX}>
            {status}
          </Box>
        )}
      </FormHelperText>
      {rejected === null ? null : (
        <Press
          tier="neutral"
          onPress={() => {
            setRejected(null);
            chooser.current?.focus();
          }}
        >
          {say("fill.fileDismiss")}
        </Press>
      )}
      {name === undefined || held === null ? null : (
        <FileCard
          file={"pending" in held ? held.pending : held.kept}
          field={field}
          onRemove={() => {
            setRejected(null);
            onDraft(path, null);
            onLeave(path);
            chooser.current?.focus();
          }}
        />
      )}
    </FormControl>
  );
}, samePlace);

const FileMany = memo(function FileMany({
  field,
  items,
  said,
  formId,
  path,
  onDraft,
  onLeave,
  onUnread,
  marks,
}: Holding & {
  readonly field: FillField;
  readonly items: readonly FileDraft[];
  readonly said?: ReactNode;
}) {
  const id = placeId(formId, path);
  const legendId = useId();
  const helperId = useId();
  const dropId = useId();
  const input = useRef<HTMLInputElement>(null);
  const chooser = useRef<HTMLButtonElement>(null);
  const [rejected, setRejected] = useState<
    readonly { readonly name: string; readonly issue: FileIssue | "too_many" }[]
  >([]);
  const [rejectedFrom, setRejectedFrom] = useState(0);
  const mark = marks.get(pathKey(path));
  const most = field.most ?? 0;
  const choose = (chosen: readonly File[]) => {
    const refused = chosen
      .map((file, index) => ({
        name: file.name,
        issue:
          items.length + index >= most
            ? ("too_many" as const)
            : fileIssueOf(field, file),
      }))
      .filter(
        (entry): entry is { name: string; issue: FileIssue | "too_many" } =>
          entry.issue !== null,
      );
    if (refused.length > 0) {
      setRejected(refused);
      setRejectedFrom(0);
      onUnread(path, true);
    } else if (chosen.length > 0) {
      setRejected([]);
      onUnread(path, false);
      onDraft(path, [...items, ...chosen.map((file) => ({ pending: file }))]);
    }
    onLeave(path);
  };

  return (
    <FormControl
      component="fieldset"
      fullWidth
      sx={{
        "&:focus-within": {
          outline: "2px solid",
          outlineColor: "primary.main",
          outlineOffset: 2,
          borderRadius: 1,
        },
      }}
      onDragOver={(event) => {
        event.preventDefault();
        event.dataTransfer.dropEffect = "copy";
      }}
      onDrop={(event) => {
        event.preventDefault();
        choose(Array.from(event.dataTransfer.files));
      }}

      error={mark !== undefined || rejected.length > 0}
      required={field.mustBeGiven}
      aria-labelledby={legendId}
      aria-describedby={`${dropId} ${helperId}`}
    >
      <FormLabel component="legend" id={legendId}>
        <FieldName name={field.name} label={field.label} />
      </FormLabel>
      <Typography id={dropId} variant="body2" sx={QUIET_SX}>
        {say("fill.fileDropMany", {
          endings: isolatedInText((field.types ?? []).join(", ")),
          count: most,
          size: sizeSaid(field.maximumBytes ?? 0),
        })}
      </Typography>
      <Button
        id={id}
        ref={chooser}
        aria-describedby={`${dropId} ${helperId}`}
        onClick={() => input.current?.click()}
      >
        {say("fill.chooseFiles")}
      </Button>
      <input
        id={`${id}-native`}
        ref={input}
        type="file"
        tabIndex={-1}
        style={{ position: "absolute", width: 1, height: 1, opacity: 0 }}
        multiple
        accept={field.types?.join(",")}
        aria-label={say("fill.fileChooseMany", {
          label: isolatedInText(labelOf(field)),
        })}
        aria-describedby={`${dropId} ${helperId}`}
        aria-required={field.mustBeGiven ? true : undefined}
        aria-invalid={
          mark !== undefined || rejected.length > 0 ? true : undefined
        }
        onChange={(event) => {
          choose(Array.from(event.currentTarget.files ?? []));
          event.currentTarget.value = "";
        }}
        onBlur={() => onLeave(path)}
      />
      <FormHelperText component="div" id={helperId}>
        <Under
          mark={rejected.length > 0 ? undefined : mark}
          said={said}
          help={field.help}
          also={say("fill.count", { count: items.length, most })}
        />
        {rejected.length === 0 ? null : (
          <>
            <Box component="span" sx={OWN_LINE_SX}>
              {say("fill.fileBatchRejected")}
            </Box>
            {rejected
              .slice(rejectedFrom, rejectedFrom + REJECTED_PAGE_SIZE)
              .map(({ name, issue }, index) => (
                <Box
                  component="span"
                  sx={OWN_LINE_SX}
                  key={rejectedFrom + index}
                >
                  {fileIssueVisible(name, issue)}
                </Box>
              ))}
            {rejected.length <= REJECTED_PAGE_SIZE ? null : (
              <>
                <Press
                  tier="quiet"
                  unavailable={rejectedFrom === 0}
                  onPress={() =>
                    setRejectedFrom((from) =>
                      Math.max(0, from - REJECTED_PAGE_SIZE),
                    )
                  }
                >
                  {say("fill.fileShowPrevious")}
                </Press>
                <Press
                  tier="quiet"
                  unavailable={
                    rejectedFrom + REJECTED_PAGE_SIZE >= rejected.length
                  }
                  onPress={() =>
                    setRejectedFrom((from) => from + REJECTED_PAGE_SIZE)
                  }
                >
                  {rejectedFrom + REJECTED_PAGE_SIZE >= rejected.length
                    ? say("fill.fileAllShown")
                    : say("fill.fileShowMore", {
                        count: Math.min(
                          REJECTED_PAGE_SIZE,
                          rejected.length - rejectedFrom - REJECTED_PAGE_SIZE,
                        ),
                        remaining:
                          rejected.length - rejectedFrom - REJECTED_PAGE_SIZE,
                      })}
                </Press>
              </>
            )}
          </>
        )}
      </FormHelperText>
      <Box sx={{ display: "flex", flexWrap: "wrap", gap: 1 }}>
        {items.map((item, index) => {
          const name = fileNameOf(item);
          return (
            <Box key={index} sx={ITEM_SX}>
              {item === null ? (
                <Typography>{say("fill.none")}</Typography>
              ) : (
                <FileCard
                  file={"pending" in item ? item.pending : item.kept}
                  field={field}
                  onRemove={() => {
                    setRejected([]);
                    onUnread(path, false);
                    onDraft(path, items.toSpliced(index, 1), index);
                    chooser.current?.focus();
                  }}
                />
              )}
              {item !== null ? null : (
                <IconPress
                  icon={<DeleteOutlineIcon />}
                  unavailable={false}
                  label={say("fill.removeFile", {
                    name: shownFileName(name ?? labelOf(field)),
                  })}
                  onPress={() => {
                    setRejected([]);
                    onUnread(path, false);
                    onDraft(path, items.toSpliced(index, 1), index);
                    chooser.current?.focus();
                  }}
                >
                  {say("fill.takeAway")}
                </IconPress>
              )}
            </Box>
          );
        })}
      </Box>
      {rejected.length === 0 ? null : (
        <Press
          tier="neutral"
          onPress={() => {
            setRejected([]);
            onUnread(path, false);
            chooser.current?.focus();
          }}
        >
          {say("fill.fileDismiss")}
        </Press>
      )}
    </FormControl>
  );
}, samePlace);

const Fields = memo(function Fields({
  field,
  label,
  held,
  said,
  ...holding
}: Holding & {
  readonly field: FillField;
  readonly label: string;
  readonly held: LevelDraft;
  readonly said?: ReactNode;
}) {
  const mark = holding.marks.get(pathKey(holding.path));
  const legendId = useId();
  const helperId = useId();
  return (
    <FormControl
      component="fieldset"
      id={placeId(holding.formId, holding.path)}
      tabIndex={-1}
      aria-labelledby={legendId}
      aria-describedby={helperId}
      error={mark !== undefined}
      required={field.mustBeGiven}
      fullWidth
    >
      <FormLabel component="legend" id={legendId}>
        <FieldName name={field.name} label={field.label}>
          {label}
        </FieldName>
      </FormLabel>
      <FormHelperText component="div" id={helperId}>
        <Under mark={mark} said={said} help={field.help} />
      </FormHelperText>
      <Box sx={INSET_SX}>
        <Level fields={field.fields ?? []} draft={held} {...holding} />
      </Box>
    </FormControl>
  );
}, samePlace);

/**
 * A day, which the browser gives as nothing while what is typed in it is not yet one; that is said of it whenever
 * it is drawn, as it is left, and wherever it comes to stand.
 */
function DateControl({
  field,
  label,
  id,
  day,
  mark,
  said,
  onDay,
  onLeave,
  onUnread,
}: {
  readonly field: FillField;
  readonly label: string;
  readonly id: string;
  readonly day: string;
  readonly mark: FillReason | undefined;
  readonly said: ReactNode;
  readonly onDay: (next: string) => void;
  readonly onLeave: () => void;
  readonly onUnread: (unreadable: boolean) => void;
}) {
  const input = useRef<HTMLInputElement>(null);
  useLayoutEffect(() => {
    onUnread(input.current?.validity.badInput === true);
  });
  return (
    <TextField
      id={id}
      type="date"
      label={
        <FieldName name={field.name} label={field.label}>
          {label}
        </FieldName>
      }
      value={day}
      inputRef={input}
      onChange={(edit) => onDay(edit.target.value)}
      onBlur={(event) => {
        onUnread(event.target.validity.badInput);
        onLeave();
      }}
      required={field.mustBeGiven}
      error={mark !== undefined}
      helperText={<Under mark={mark} said={said} help={field.help} />}
      slotProps={DATE_INPUT}
    />
  );
}

/** A date and a time on the reader's clock, and the offset they are at, which follows the reader's own until chosen. */
function Moment({
  field,
  label,
  id,
  moment,
  mark,
  said,
  onMoment,
  onLeave,
  onUnread,
}: {
  readonly field: FillField;
  readonly label: string;
  /** Of the date's control, which the keyboard is taken to. */
  readonly id: string;
  readonly moment: MomentDraft;
  readonly mark: FillReason | undefined;
  readonly said: ReactNode;
  readonly onMoment: (next: MomentDraft) => void;
  readonly onLeave: () => void;
  readonly onUnread: (unreadable: boolean) => void;
}) {
  const helperId = useId();
  const dateInput = useRef<HTMLInputElement>(null);
  const timeInput = useRef<HTMLInputElement>(null);
  // The offsets are offered in a menu drawn outside the fieldset, which the keyboard goes into without leaving.
  const choosingOffset = useRef(false);
  const offset = offsetOf(moment);
  const unreadable = () =>
    dateInput.current?.validity.badInput === true ||
    timeInput.current?.validity.badInput === true;
  useLayoutEffect(() => {
    onUnread(unreadable());
  });
  // Left only once the keyboard is out of all three: a date with no time yet is not written as a moment.
  const leaving = (event: FocusEvent<HTMLElement>) => {
    if (
      !choosingOffset.current &&
      !event.currentTarget.contains(event.relatedTarget as Node | null)
    ) {
      onUnread(unreadable());
      onLeave();
    }
  };
  const described = { "aria-describedby": helperId };
  return (
    <FormControl
      component="fieldset"
      error={mark !== undefined}
      required={field.mustBeGiven}
      onBlur={leaving}
    >
      <FormLabel component="legend">
        <FieldName name={field.name} label={field.label}>
          {label}
        </FieldName>
      </FormLabel>
      <Box sx={ROW_SX}>
        <TextField
          id={id}
          type="date"
          size="small"
          label={say("fill.date")}
          value={moment.date}
          inputRef={dateInput}
          onChange={(edit) => onMoment({ ...moment, date: edit.target.value })}
          error={mark !== undefined}
          slotProps={{
            ...DATE_INPUT,
            htmlInput: { ...DATE_INPUT.htmlInput, ...described },
          }}
        />
        <TextField
          type="text"
          size="small"
          label={say("fill.time")}
          value={moment.time}
          inputRef={timeInput}
          onChange={(edit) => onMoment({ ...moment, time: edit.target.value })}
          error={mark !== undefined}
          slotProps={{
            ...TIME_INPUT,
            htmlInput: { ...TIME_INPUT.htmlInput, ...described },
          }}
        />
        <TextField
          select
          size="small"
          label={say("fill.offset")}
          value={offset}
          onChange={(edit) =>
            onMoment({ ...moment, offset: edit.target.value })
          }
          error={mark !== undefined}
          slotProps={{
            select: {
              ...described,
              onOpen: () => {
                choosingOffset.current = true;
              },
              onClose: () => {
                choosingOffset.current = false;
              },
            },
          }}
        >
          {offsetsBeside(offset).map((each) => (
            <MenuItem key={each} value={each}>
              {each}
            </MenuItem>
          ))}
        </TextField>
      </Box>
      <FormHelperText component="div" id={helperId}>
        <Under
          mark={mark}
          said={said}
          help={field.help}
          also={say("fill.timeHow")}
        />
      </FormHelperText>
    </FormControl>
  );
}

function TermMany({
  field,
  items,
  said,
  formId,
  path,
  onDraft,
  onLeave,
  marks,
}: Holding & {
  readonly field: FillField;
  readonly items: readonly string[];
  readonly said?: ReactNode;
}) {
  const id = placeId(formId, path);
  const labelId = useId();
  const helpId = useId();
  const mark = marks.get(pathKey(path));
  const terms = field.terms?.terms ?? [];
  const most = field.most ?? 0;
  const choose = (term: string) => {
    onDraft(
      path,
      items.includes(term)
        ? items.filter((each) => each !== term)
        : [...items, term],
    );
    onLeave(path);
  };
  return (
    <FormControl
      component="fieldset"
      aria-describedby={helpId}
      fullWidth
      error={mark !== undefined}
      required={field.mustBeGiven}
    >
      <FormLabel component="legend" id={labelId}>
        <FieldName name={field.name} label={field.label} />
      </FormLabel>
      {field.terms?.note === undefined ? null : (
        <Typography
          variant="body2"
          sx={{ ...QUIET_SX, typography: "systemHint", mt: 1, mb: 2 }}
        >
          {isolated(field.terms.note)}
        </Typography>
      )}
      {terms.length > 5 ? (
        <TextField
          select
          id={`${id}-native`}
          value={items}
          slotProps={{
            select: {
              multiple: true,
              labelId,
              SelectDisplayProps: { id },
              "aria-describedby": helpId,
              onChange: (event) => {
                const next = event.target.value;
                if (
                  Array.isArray(next) &&
                  next.every((term): term is string => typeof term === "string")
                ) {
                  onDraft(path, next);
                  onLeave(path);
                }
              },
            },
          }}
        >
          {terms.map(({ term, meaning }) => (
            <MenuItem
              key={term}
              value={term}
              disabled={!items.includes(term) && items.length >= most}
            >
              <Checkbox checked={items.includes(term)} />
              {isolated(term)} — {isolated(meaning)}
            </MenuItem>
          ))}
        </TextField>
      ) : (
        <Box sx={ROW_SX}>
          {terms.map(({ term, meaning }, index) => (
            <FormControlLabel
              key={term}
              sx={{
                ...BLOCK_SX,
                m: 0,
                borderColor: items.includes(term) ? "primary.main" : "divider",
              }}
              control={
                <Checkbox
                  id={index === 0 ? id : undefined}
                  checked={items.includes(term)}
                  disabled={!items.includes(term) && items.length >= most}
                  onChange={() => choose(term)}
                />
              }
              label={
                <>
                  <Typography component="span" sx={OWN_LINE_SX}>
                    {isolated(term)}
                  </Typography>
                  <Typography component="span" variant="body2" sx={MEANING_SX}>
                    {isolated(meaning)}
                  </Typography>
                </>
              }
            />
          ))}
        </Box>
      )}
      <FormHelperText component="div" id={helpId}>
        <Under
          mark={mark}
          said={said}
          help={field.help}
          also={say("fill.count", { count: items.length, most })}
        />
      </FormHelperText>
    </FormControl>
  );
}

function Choice({
  field,
  label,
  id,
  chosen,
  mark,
  said,
  onChoose,
  choices,
  note,
  row,
}: {
  readonly field: FillField;
  readonly label: string;
  /** Of the first choice's control, which the keyboard is taken to. */
  readonly id: string;
  readonly chosen: string;
  readonly mark: FillReason | undefined;
  readonly said: ReactNode;
  readonly onChoose: (value: string) => void;
  readonly choices: readonly {
    readonly value: string;
    readonly words: ReactNode;
  }[];
  readonly note?: string;
  readonly row?: boolean;
}) {
  const legendId = useId();
  const helperId = useId();
  return (
    <FormControl
      component="fieldset"
      error={mark !== undefined}
      required={field.mustBeGiven}
    >
      <FormLabel component="legend" id={legendId}>
        <FieldName name={field.name} label={field.label}>
          {label}
        </FieldName>
      </FormLabel>
      {note === undefined ? null : (
        <Typography
          variant="body2"
          sx={{ ...QUIET_SX, typography: "systemHint", mt: 1, mb: 2 }}
        >
          {isolated(note)}
        </Typography>
      )}
      {field.kind === "term" && choices.length > 5 ? (
        <TextField
          select
          id={`${id}-native`}
          value={chosen}
          onChange={(event) => onChoose(event.target.value)}
          slotProps={{
            select: {
              labelId: legendId,
              SelectDisplayProps: { id },
              "aria-describedby": helperId,
              "aria-invalid": mark === undefined ? undefined : true,
            },
          }}
        >
          {field.mustBeGiven ? null : (
            <MenuItem value="">{say("fill.leaveEmpty")}</MenuItem>
          )}
          {choices.map((choice) => (
            <MenuItem key={choice.value} value={choice.value}>
              {choice.words}
            </MenuItem>
          ))}
        </TextField>
      ) : (
        <RadioGroup
          value={chosen}
          onChange={(_event, value) => onChoose(value)}
          row={row}
          aria-labelledby={legendId}
          aria-describedby={helperId}
          aria-invalid={mark === undefined ? undefined : true}
          aria-required={field.mustBeGiven ? true : undefined}
        >
          {choices.map((choice, index) => (
            <FormControlLabel
              key={choice.value}
              value={choice.value}
              control={<Radio id={index === 0 ? id : undefined} />}
              label={choice.words}
              sx={
                field.kind === "term"
                  ? {
                      ...BLOCK_SX,
                      m: 0,
                      mt: 1,
                      borderColor:
                        chosen === choice.value ? "primary.main" : "divider",
                    }
                  : undefined
              }
            />
          ))}
          {field.mustBeGiven ? null : (
            <FormControlLabel
              value=""
              control={<Radio />}
              label={say("fill.leaveEmpty")}
            />
          )}
        </RadioGroup>
      )}
      <FormHelperText component="div" id={helperId}>
        <Under mark={mark} said={said} help={field.help} />
      </FormHelperText>
    </FormControl>
  );
}

function Under({
  mark,
  said,
  help,
  also,
}: {
  readonly mark: FillReason | undefined;
  readonly said?: ReactNode;
  readonly help: string | undefined;
  readonly also?: string;
}) {
  return (
    <>
      {mark === undefined ? null : (
        <Box component="span" sx={OWN_LINE_SX}>
          {say(REASONS[mark])}
        </Box>
      )}
      {said === undefined ? null : (
        <Box component="div" sx={OWN_LINE_SX}>
          {said}
        </Box>
      )}
      <Box
        component="span"
        sx={{
          display: "flex",
          justifyContent: "space-between",
          gap: 2,
          alignItems: "baseline",
          typography: "caption",
          color: mark === undefined ? "text.secondary" : "warning.main",
        }}
      >
        {help === undefined ? (
          <span />
        ) : (
          <Box component="span">{isolated(help)}</Box>
        )}{" "}
        {also === undefined ? null : (
          <Box component="span" sx={{ flexShrink: 0 }}>
            {also}
          </Box>
        )}
      </Box>
    </>
  );
}

function samePlace<Props extends Placed>(before: Props, after: Props): boolean {
  const keys = Object.keys(after) as (keyof Props)[];
  return (
    keys.length === Object.keys(before).length &&
    keys.every((key) =>
      key === "path"
        ? pathKey(before.path) === pathKey(after.path)
        : Object.is(before[key], after[key]),
    )
  );
}

function keysFor(count: number, from: number): ElementKeys {
  return {
    held: Array.from({ length: count }, (_, index) => from + index),
    next: from + count,
  };
}

function labelOf(field: FillField): string {
  return field.label ?? field.name.replaceAll("_", " ");
}

function offsetsBeside(standing: string): readonly string[] {
  if (QUARTER_HOUR_OFFSETS.includes(standing)) {
    return QUARTER_HOUR_OFFSETS;
  }
  return [...QUARTER_HOUR_OFFSETS, standing].sort(
    (one, other) => minutesOf(one) - minutesOf(other),
  );
}

function minutesOf(offset: string): number {
  const away = Number(offset.slice(1, 3)) * 60 + Number(offset.slice(4, 6));
  return offset.startsWith("-") ? -away : away;
}
