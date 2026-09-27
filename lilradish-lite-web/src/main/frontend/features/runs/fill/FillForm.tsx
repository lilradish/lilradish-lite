import Box from "@mui/material/Box";
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
import { Press } from "../../../lib/action/Press";
import { isolated, isolatedInText } from "../../../lib/direction/isolated";
import {
  codePointsIn,
  FIRST_YEAR,
  FURTHEST_OFFSET_MINUTES,
  LAST_YEAR,
  MOST_DIGITS,
} from "../../../lib/filling/writing";
import { QUIET_SX } from "../../../lib/layout/parts";
import {
  offsetOf,
  offsetWritten,
  oneDraftOf,
  type FieldDraft,
  type LevelDraft,
  type MomentDraft,
  type OneDraft,
} from "./fillDrafts";

/** What the reader is told of each reason a value does not fit, closed over the reasons. */
const REASONS = {
  missing: "fill.missing",
  malformed: "fill.malformed",
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

const HELD_SX = {
  pl: 2,
  borderLeftWidth: 2,
  borderLeftStyle: "solid",
  borderLeftColor: "divider",
};

const ROW_SX = { display: "flex", flexWrap: "wrap", gap: 1, mt: 1 };

const ITEM_SX = { display: "flex", alignItems: "flex-start", gap: 1, mt: 1 };

const ELEMENT_SX = { flex: "1 1 auto", minWidth: 0 };

const BLOCK_SX = { display: "block" };

const MEANING_SX = { ...QUIET_SX, display: "block", mb: 0 };

const TIME_INPUT = { inputLabel: { shrink: true }, htmlInput: { step: 1 } };

const DATE_INPUT = {
  inputLabel: { shrink: true },
  htmlInput: { min: FIRST_DAY, max: LAST_DAY },
};

// Text may run either way, so its box takes its direction from what is typed.
const TEXT_INPUT = { htmlInput: { dir: "auto" } };

// Not decimal: a keyboard for decimals may have no minus key, and a number may start with one.
const NUMBER_INPUT = { htmlInput: { inputMode: "text", dir: "ltr" } };

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

/** Where the keyboard goes once many has changed under it. */
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

/** What a place holding others is handed besides, to say of each why it does not fit. */
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
 * One control to each field, in declared order, the fields a field holds drawn under it; each says what it is
 * called with its help under it, and why what is in it does not fit where `marks` says so, by where it stands.
 * A control is drawn again only where what it is handed has changed, so every handler has to stay the same.
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
  /** Words said of a field of the first level, by its name, said under its control, which is described by them. */
  readonly beside?: ReadonlyMap<string, string>;
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
  readonly beside?: ReadonlyMap<string, string>;
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
        return Array.isArray(held) ? (
          <Many
            key={field.name}
            field={field}
            items={held}
            said={said}
            {...placed}
          />
        ) : (
          <Element
            key={field.name}
            field={field}
            label={labelOf(field)}
            held={held as OneDraft}
            said={said}
            {...placed}
          />
        );
      })}
    </Stack>
  );
}

/** One value of a field: fields drawn under it, or one control; `said` is said under either. */
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
  readonly said?: string;
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

/** Many of one field: none at first, one more while it holds fewer than its most, and any one taken away. */
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
  readonly said?: string;
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
  // Held anew from outside, as when the form starts again: keyed afresh.
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
              ?.lastElementChild?.querySelector("button")
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
        {isolated(label)}
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
            <Press
              label={say("fill.takeAwayOne", { element })}
              onPress={() => takeAway(index)}
            >
              {say("fill.takeAway")}
            </Press>
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

/** One value, by the control its kind asks for. */
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
  readonly said?: string;
}) {
  const id = placeId(formId, path);
  const typed = (next: string) => onDraft(path, next);
  const left = () => onLeave(path);
  switch (field.kind) {
    case "text":
      return (
        <TextField
          id={id}
          label={isolated(label)}
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
              also={say("fill.count", {
                count: codePointsIn(held as string),
                most: field.longest ?? 0,
              })}
            />
          }
          slotProps={TEXT_INPUT}
          multiline
          fullWidth
        />
      );
    case "number":
      return (
        <TextField
          id={id}
          label={isolated(label)}
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
                <Typography component="span" sx={BLOCK_SX}>
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
    case "fields":
      return null;
  }
}, samePlace);

/** The fields a field holds, drawn under it and named by it. */
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
  readonly said?: string;
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
        {isolated(label)}
      </FormLabel>
      <FormHelperText component="div" id={helperId}>
        <Under mark={mark} said={said} help={field.help} />
      </FormHelperText>
      <Box sx={HELD_SX}>
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
  readonly said: string | undefined;
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
      label={isolated(label)}
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
  readonly said: string | undefined;
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
      <FormLabel component="legend">{isolated(label)}</FormLabel>
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
          type="time"
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
        <Under mark={mark} said={said} help={field.help} />
      </FormHelperText>
    </FormControl>
  );
}

/** One of what is offered, chosen and never typed; a field that need not be given may be left empty. */
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
  readonly said: string | undefined;
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
        {isolated(label)}
      </FormLabel>
      {note === undefined ? null : (
        <Typography variant="body2" sx={QUIET_SX}>
          {isolated(note)}
        </Typography>
      )}
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
      <FormHelperText component="div" id={helperId}>
        <Under mark={mark} said={said} help={field.help} />
      </FormHelperText>
    </FormControl>
  );
}

/**
 * Why it does not fit first, then what its caller says of it, then its help, then what else its kind says of it,
 * each on a line of its own.
 */
function Under({
  mark,
  said,
  help,
  also,
}: {
  readonly mark: FillReason | undefined;
  readonly said?: string | undefined;
  readonly help: string | undefined;
  readonly also?: string;
}) {
  return (
    <>
      {mark === undefined ? null : (
        <Box component="span" sx={BLOCK_SX}>
          {say(REASONS[mark])}
        </Box>
      )}
      {said === undefined ? null : (
        <Box component="span" sx={BLOCK_SX}>
          {said}
        </Box>
      )}
      {help === undefined ? null : (
        <Box component="span" sx={BLOCK_SX}>
          {isolated(help)}
        </Box>
      )}
      {also === undefined ? null : (
        <Box component="span" sx={BLOCK_SX}>
          {also}
        </Box>
      )}
    </>
  );
}

/** Whether a control would be drawn as it was: every prop the same, a path by the place it names. */
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

/** Its label, or its name with each underscore read as a space where it has none. */
function labelOf(field: FillField): string {
  return field.label ?? field.name.replaceAll("_", " ");
}

/** Every quarter hour either way as far as an offset goes, and the one standing where it falls between them. */
function offsetsBeside(standing: string): string[] {
  const offsets: string[] = [];
  for (
    let minutes = -FURTHEST_OFFSET_MINUTES;
    minutes <= FURTHEST_OFFSET_MINUTES;
    minutes += OFFSET_STEP_MINUTES
  ) {
    offsets.push(offsetWritten(minutes));
  }
  if (!offsets.includes(standing)) {
    offsets.push(standing);
    offsets.sort((one, other) => minutesOf(one) - minutesOf(other));
  }
  return offsets;
}

function minutesOf(offset: string): number {
  const away = Number(offset.slice(1, 3)) * 60 + Number(offset.slice(4, 6));
  return offset.startsWith("-") ? -away : away;
}
