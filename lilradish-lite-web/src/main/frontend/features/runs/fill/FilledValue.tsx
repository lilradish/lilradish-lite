import Box from "@mui/material/Box";
import Button from "@mui/material/Button";
import Typography from "@mui/material/Typography";
import {
  createContext,
  Fragment,
  useContext,
  useId,
  useState,
  type ReactNode,
} from "react";

import {
  isDownloadableFileValue,
  isWithheldLeaf,
  type FileValue,
  type ReadField,
  type ShownValue,
  type ShownValues,
} from "../../../api/filling";
import type { ShownOrKept } from "../../../api/shownValues";
import { say, type MessageId } from "../../../i18n/app";
import { intl, readersIntl } from "../../../i18n/intl";
import { isolatedInText } from "../../../lib/direction/isolated";
import {
  codePointsIn,
  dayParts,
  momentParts,
  numberWritten,
  type DayParts,
  type MomentParts,
} from "../../../lib/filling/writing";
import { Field, Fields } from "../../../lib/form/Fields";
import { FieldName } from "../../../lib/form/FieldName";
import { QUIET_SX } from "../../../lib/layout/parts";
import { HiddenShown } from "../../../lib/text/HiddenShown";
import {
  ModelProse,
  ProseScope,
  FormattedProseAcross,
} from "../../../lib/text/ModelProse";
import { spokenName } from "../steps/stepTitle";
import { FileCard, fileEndingOf } from "./FileCard";
import { ValueCard, LONGEST_OPEN_TEXT } from "./ValueCard";

// Parts, not a style: a style takes no fraction of a second, and every moment is drawn alike, with one or without.
const CLOCK = {
  year: "numeric",
  month: "short",
  day: "numeric",
  hour: "numeric",
  minute: "2-digit",
  second: "2-digit",
} as const;

const TEXT_SX = { whiteSpace: "pre-wrap" };

const MANY_SX = { m: 0, pl: 2 };

// A span set as a block: a value may stand within a line, where a paragraph may not.
const SENTENCE_SX = { ...QUIET_SX, display: "block" };

const LABELLED: MessageId = "question.labelledValue";

type FileEndingReader = (
  file: FileValue,
  field: ReadField,
) => ReturnType<typeof fileEndingOf>;

const FileEndingsContext = createContext<FileEndingReader | null>(null);
const ValueCardsContext = createContext(false);

const unindexedFileEndings: FileEndingReader = () => {
  throw new Error("A displayed file was not included in its value's text.");
};

function preparingFileEndings() {
  const endings = new WeakMap<
    ReadField,
    WeakMap<FileValue, ReturnType<typeof fileEndingOf>>
  >();
  const select: FileEndingReader = (file, field) => {
    let files = endings.get(field);
    if (files === undefined) {
      files = new WeakMap();
      endings.set(field, files);
    }
    let ending = files.get(file);
    if (ending === undefined) {
      ending = fileEndingOf(file.name, field);
      files.set(file, ending);
    }
    return ending;
  };
  const read: FileEndingReader = (file, field) => {
    const ending = endings.get(field)?.get(file);
    return ending ?? unindexedFileEndings(file, field);
  };
  return { select, read };
}

/**
 * One value as it was filled, drawn as its field's kind is read: none said as none, one withheld said so, one kept
 * in a shape the step had before drawn as it was kept, and one shaped as its field never is, or of a kind this build
 * does not know, said to be something it cannot say yet. Every text within it is marked as one, by
 * `HiddenShownAcross`, unless it stands within a value that is.
 */
export function FilledValue({
  field,
  label,
  shown,
  shut,
  inLine = false,
  formatted = true,
  cards,
  status,
  foot,
}: {
  /** Absent where nothing the read holds declares it: drawn by its shape alone. */
  readonly field?: ReadField;
  /** What a long text's control names it by; shown as given, so a name in it arrives isolated. */
  readonly label: string;
  readonly shown: ShownOrKept;
  /** Collapses long text in the compact reading; value cards own their expansion. */
  readonly shut: boolean;
  readonly inLine?: boolean;
  readonly formatted?: boolean;
  readonly cards?: boolean;
  readonly status?: string;
  readonly foot?: ReactNode;
}) {
  const inheritedEndings = useContext(FileEndingsContext);
  const inheritedCards = useContext(ValueCardsContext);
  const asCards = cards ?? inheritedCards;
  if ("withheld" in shown) {
    const withheld = (
      <Typography variant="body2" component="span" sx={SENTENCE_SX}>
        {say("step.withheld")}
      </Typography>
    );
    return asCards && !inLine ? (
      <ValueCard label={label} written={undefined} status={status} foot={foot}>
        {withheld}
      </ValueCard>
    ) : (
      withheld
    );
  }
  // A shut leaf has no displayed characters yet. Its reader establishes the value budget when it opens.
  if (
    shut &&
    !asCards &&
    "value" in shown &&
    typeof shown.value === "string" &&
    (field === undefined || field.kind === "text")
  ) {
    return (
      <ProseScope>
        <ShutText
          label={label}
          value={shown.value}
          formatted={formatted && field?.lines === "several" && !inLine}
        />
      </ProseScope>
    );
  }
  // Fill the local index before passing it to children; inherited indexes are read-only during rendering.
  const preparation: { index?: ReturnType<typeof preparingFileEndings> } = {};
  const text =
    "asKept" in shown
      ? shown.asKept
      : visibleTextOf(
          field,
          shown.value,
          inheritedEndings ??
            ((file, declared) =>
              (preparation.index ??= preparingFileEndings()).select(
                file,
                declared,
              )),
        );
  const drawn = (
    <ProseScope>
      <FormattedProseAcross value={text}>
        {"asKept" in shown ? (
          asCards && !inLine ? (
            <ValueCard
              label={label}
              written={shown.asKept ?? ""}
              copyable={field?.kind !== "file"}
              status={status}
              foot={foot}
            >
              <KeptEarlier label={label} text={shown.asKept} shut={false} />
            </ValueCard>
          ) : (
            <KeptEarlier label={label} text={shown.asKept} shut={shut} />
          )
        ) : (
          valueDrawn(
            field,
            label,
            shown.value,
            shut,
            inLine,
            formatted,
            asCards,
            status,
            foot,
          )
        )}
      </FormattedProseAcross>
    </ProseScope>
  );
  // Keep the reader's boundary in place when files disappear, so unchanged siblings retain their state.
  return inheritedEndings !== null ? (
    drawn
  ) : (
    <FileEndingsContext.Provider
      value={preparation.index?.read ?? unindexedFileEndings}
    >
      {drawn}
    </FileEndingsContext.Provider>
  );
}

function visibleTextOf(
  field: ReadField | undefined,
  value: ShownValue,
  endingOf: FileEndingReader,
): unknown {
  if (isWithheldLeaf(value)) {
    return null;
  }
  if (field?.kind === "file") {
    if (Array.isArray(value)) {
      return field.most === undefined
        ? null
        : value.map((each) => visibleFileTextOf(each, field, endingOf));
    }
    return field.most !== undefined && value !== null
      ? null
      : visibleFileTextOf(value, field, endingOf);
  }
  if (Array.isArray(value)) {
    return value.map((each) => visibleTextOf(field, each, endingOf));
  }
  if (field?.kind === "fields" && value !== null && typeof value === "object") {
    return Object.fromEntries(
      Object.entries(value).map(([name, each]) => [
        name,
        visibleTextOf(
          field.fields?.find((declared) => declared.name === name),
          each,
          endingOf,
        ),
      ]),
    );
  }
  return value;
}

function visibleFileTextOf(
  value: ShownValue,
  field: ReadField,
  endingOf: FileEndingReader,
): unknown {
  if (!isDownloadableFileValue(value)) return null;
  endingOf(value, field);
  return { name: value.name };
}

/** The spelling offered for copying never includes a withheld leaf or a file's internal identifier. */
export function writtenValueOf(
  field: ReadField | undefined,
  shown: ShownOrKept,
): string | undefined {
  if ("withheld" in shown) return undefined;
  if ("asKept" in shown) return shown.asKept ?? "null";
  const write = (
    declared: ReadField | undefined,
    value: ShownValue,
  ): unknown => {
    if (isWithheldLeaf(value)) return undefined;
    if (value === null) return null;
    if (Array.isArray(value))
      return value
        .map((each) => write(declared, each))
        .filter((each) => each !== undefined);
    if (declared === undefined && isDownloadableFileValue(value))
      return value.name;
    if (declared?.kind === "file")
      return isDownloadableFileValue(value) ? value.name : undefined;
    if (typeof value === "string")
      return declared?.kind === "fields" ? undefined : value;
    if (declared !== undefined && declared.kind !== "fields") return undefined;
    const values = value as ShownValues;
    const entries =
      declared?.fields === undefined
        ? Object.entries(values)
        : declared.fields.map(
            (each) => [each.name, values[each.name] ?? null] as const,
          );
    return Object.fromEntries(
      entries
        .map(([name, each]) => [
          name,
          write(
            declared?.fields?.find((inner) => inner.name === name),
            each,
          ),
        ])
        .filter(([, each]) => each !== undefined),
    );
  };
  const written = write(field, shown.value);
  return written === undefined
    ? undefined
    : typeof written === "string"
      ? written
      : JSON.stringify(written, null, 2);
}

function valueDrawn(
  field: ReadField | undefined,
  label: string,
  value: ShownValue,
  shut: boolean,
  inLine: boolean,
  formatted: boolean,
  cards: boolean,
  status?: string,
  foot?: ReactNode,
): ReactNode {
  if (
    field?.kind === "file" &&
    value !== null &&
    Array.isArray(value) !== (field.most !== undefined)
  ) {
    return cards && !inLine ? (
      <ValueCard label={label} written={undefined} status={status} foot={foot}>
        {say("step.unknown")}
      </ValueCard>
    ) : (
      say("step.unknown")
    );
  }
  if (!Array.isArray(value)) {
    return (
      <One
        field={field}
        label={label}
        value={value}
        shut={shut}
        inLine={inLine}
        formatted={formatted}
        cards={cards}
        status={status}
        foot={foot}
      />
    );
  }
  if (field !== undefined && field.most === undefined) {
    return cards && !inLine ? (
      <ValueCard label={label} written={undefined} status={status} foot={foot}>
        {say("step.unknown")}
      </ValueCard>
    ) : (
      say("step.unknown")
    );
  }
  if (value.length === 0) {
    return cards && !inLine ? (
      <ValueCard
        label={label}
        written="[]"
        copyable={field?.kind !== "file"}
        status={status}
        foot={foot}
      >
        {say("step.none")}
      </ValueCard>
    ) : (
      say("step.none")
    );
  }
  if (inLine && field?.kind === "file") {
    return value.map((each, at) => (
      <One
        key={at}
        field={field}
        label={label}
        value={each}
        shut={shut}
        inLine
        formatted={formatted}
      />
    ));
  }
  if (inLine && field?.kind !== "file") {
    return inOneLine(
      value.map((each, at) => (
        <One
          key={at}
          field={field}
          label={say("step.itemOf", { field: label, number: at + 1 })}
          value={each}
          shut={shut}
          inLine
          formatted={formatted}
        />
      )),
    );
  }
  return (
    <Box
      component="ul"
      role={cards ? "list" : undefined}
      sx={
        cards
          ? {
              ...MANY_SX,
              listStyle: "none",
              p: 0,
              ...(field?.kind === "file"
                ? { display: "flex", flexWrap: "wrap", gap: 1 }
                : {}),
            }
          : MANY_SX
      }
    >
      {value.map((each, at) => (
        <li key={at}>
          <One
            field={field}
            label={say("step.itemOf", { field: label, number: at + 1 })}
            value={each}
            shut={shut}
            inLine={false}
            formatted={formatted}
            cards={cards}
            status={status}
            foot={foot}
          />
        </li>
      ))}
    </Box>
  );
}

function One({
  field,
  label,
  value,
  shut,
  inLine,
  formatted,
  cards = false,
  status,
  foot,
}: {
  readonly field: ReadField | undefined;
  readonly label: string;
  readonly value: ShownValue;
  readonly shut: boolean;
  readonly inLine: boolean;
  readonly formatted: boolean;
  readonly cards?: boolean;
  readonly status?: string;
  readonly foot?: ReactNode;
}): ReactNode {
  const endingOf = useContext(FileEndingsContext);
  const withinCard = useContext(ValueCardsContext);
  if (cards && !inLine) {
    return (
      <ValueCard
        label={
          field === undefined ? (
            label
          ) : (
            <FieldName name={field.name} label={field.label} context="reading">
              {label}
            </FieldName>
          )
        }
        written={writtenValueOf(field, { value })}
        copyable={field?.kind !== "file"}
        status={status}
        foot={foot}
      >
        <ValueCardsContext.Provider value={true}>
          <One
            field={field}
            label={label}
            value={value}
            shut={false}
            inLine={false}
            formatted={formatted}
          />
        </ValueCardsContext.Provider>
      </ValueCard>
    );
  }
  if (isWithheldLeaf(value)) {
    return field?.kind === "file" ? (
      say("step.unknown")
    ) : (
      <Typography variant="body2" component="span" sx={SENTENCE_SX}>
        {say("step.withheld")}
      </Typography>
    );
  }
  if (value === null) {
    return say("step.none");
  }
  if (Array.isArray(value)) {
    return say("step.unknown");
  }
  if (typeof value !== "string") {
    if (field?.kind === "file") {
      return isDownloadableFileValue(value) ? (
        <FileCard
          file={value}
          field={field}
          preparedEnding={endingOf?.(value, field)}
          withinValueCard={withinCard}
        />
      ) : (
        say("step.unknown")
      );
    }
    return field === undefined || field.kind === "fields" ? (
      <FilledFields
        fields={field?.fields}
        values={value as ShownValues}
        shut={shut}
        outer={label}
        inLine={inLine}
        formatted={formatted}
      />
    ) : (
      say("step.unknown")
    );
  }
  switch (field?.kind) {
    case undefined:
    case "text":
      if (shut) {
        return (
          <ShutText
            label={label}
            value={value}
            formatted={formatted && field?.lines === "several" && !inLine}
          />
        );
      }
      return formatted && field?.lines === "several" && !inLine ? (
        <ModelProse text={value} plain={drawnText(value)} />
      ) : (
        drawnText(value)
      );
    case "number":
      return numberWritten(value) ? (
        readersIntl.formatNumber(value as `${number}`, asWritten(value))
      ) : (
        <Unreadable value={value} />
      );
    case "date": {
      const day = dayParts(value);
      return day === null ? <Unreadable value={value} /> : <Day parts={day} />;
    }
    case "moment": {
      const moment = momentParts(value);
      return moment === null ? (
        <Unreadable value={value} />
      ) : (
        <Moment parts={moment} />
      );
    }
    case "yes_no":
      return value === "true" || value === "false" ? (
        say(value === "true" ? "fill.yes" : "fill.no")
      ) : (
        <Unreadable value={value} />
      );
    case "term": {
      const offered = field.terms?.terms.find((each) => each.term === value);
      return offered === undefined ? (
        <Unreadable value={value} />
      ) : (
        say("step.term", {
          term: isolatedInText(offered.term),
          meaning: isolatedInText(offered.meaning),
        })
      );
    }
    default:
      return say("step.unknown");
  }
}

function drawnText(value: string) {
  return (
    <Box component="bdi" sx={TEXT_SX}>
      <HiddenShown text={value} />
    </Box>
  );
}

/**
 * Long is past the longest in code points, as the server counts, or more than one line.
 * The control's name stays as given; whether the text is open is said apart.
 */
function ShutText({
  label,
  value,
  formatted = false,
}: {
  readonly label: string;
  readonly value: string;
  readonly formatted?: boolean;
}) {
  const [open, setOpen] = useState(false);
  const textId = useId();
  const drawn = formatted ? (
    <ModelProse text={value} plain={drawnText(value)} />
  ) : (
    drawnText(value)
  );
  if (
    value.length <= LONGEST_OPEN_TEXT * 2 &&
    codePointsIn(value) <= LONGEST_OPEN_TEXT &&
    !/[\n\r]/.test(value)
  ) {
    return drawn;
  }
  return (
    <>
      <Button
        variant="text"
        color="inherit"
        size="small"
        aria-expanded={open}
        aria-controls={textId}
        onClick={() => setOpen((was) => !was)}
      >
        {say("step.inFull", { field: label })}
      </Button>
      <div id={textId} hidden={!open}>
        {open ? drawn : null}
      </div>
    </>
  );
}

/** Fields under their labels in declared order, or under their names where nothing declares them. */
export function FilledFields({
  fields,
  values,
  shut,
  outer,
  inLine = false,
  formatted = true,
  cards,
}: {
  readonly fields: readonly ReadField[] | undefined;
  readonly values: ShownValues;
  readonly shut: boolean;
  /** What the value holding these fields is named by; absent at the top. */
  readonly outer?: string;
  readonly inLine?: boolean;
  readonly formatted?: boolean;
  readonly cards?: boolean;
}) {
  const inheritedCards = useContext(ValueCardsContext);
  const asCards = cards ?? inheritedCards;
  const within = (inner: string) =>
    outer === undefined
      ? isolatedInText(inner)
      : say("step.fieldWithin", { outer, inner: isolatedInText(inner) });
  const each =
    fields === undefined
      ? Object.entries(values).map(([name, value]) => ({
          name,
          said: spokenName(name),
          field: undefined,
          value,
        }))
      : fields.map((declared) => ({
          name: declared.name,
          said: declared.label || spokenName(declared.name),
          field: declared,
          value: Object.hasOwn(values, declared.name)
            ? values[declared.name]!
            : null,
        }));
  const drawn = each.map(({ said, field, value }) => (
    <FilledValue
      field={field}
      label={within(said)}
      shown={{ value }}
      shut={shut}
      inLine={inLine}
      formatted={formatted}
      cards={asCards}
    />
  ));
  if (inLine) {
    return inOneLine(
      each.map(({ said }, at) => labelledInLine(<bdi>{said}</bdi>, drawn[at])),
    );
  }
  if (asCards) {
    return (
      <Box>
        {each.map(({ name }, at) => (
          <Fragment key={name}>{drawn[at]}</Fragment>
        ))}
      </Box>
    );
  }
  return (
    <Fields>
      {each.map(({ name, said }, at) => (
        <Field
          key={name}
          label={
            <FieldName name={name} label={each[at]?.field?.label}>
              {said}
            </FieldName>
          }
        >
          {drawn[at]}
        </Field>
      ))}
    </Fields>
  );
}

export function inOneLine(items: readonly ReactNode[]): ReactNode {
  const joined = readersIntl.formatList(
    items.map((item, at) => <Fragment key={at}>{item}</Fragment>),
    { type: "conjunction" },
  );
  return Array.isArray(joined)
    ? joined.map((part, at) => <Fragment key={at}>{part}</Fragment>)
    : joined;
}

export function labelledInLine(label: ReactNode, value: ReactNode): ReactNode {
  return intl.formatMessage({ id: LABELLED }, { label, value });
}

/** As it came, and said to be unreadable rather than read as something it is not. */
function Unreadable({ value }: { readonly value: string }) {
  return (
    <>
      <bdi>
        <HiddenShown text={value} />
      </bdi>
      <Typography variant="body2" component="span" sx={SENTENCE_SX}>
        {say("step.unreadable")}
      </Typography>
    </>
  );
}

/**
 * The text it was kept as, read as no kind and shut where a long text is, or none; and that it is of a shape the step
 * had before.
 */
function KeptEarlier({
  label,
  text,
  shut,
}: {
  readonly label: string;
  readonly text: string | null;
  readonly shut: boolean;
}) {
  return (
    <>
      {text === null ? (
        say("step.none")
      ) : shut ? (
        <ShutText label={label} value={text} />
      ) : (
        drawnText(text)
      )}
      <Typography variant="body2" component="span" sx={SENTENCE_SX}>
        {say("step.earlierShape")}
      </Typography>
    </>
  );
}

/** Every digit written after the point, a nought at the end kept; `numberWritten` holds them under what a format takes. */
function asWritten(written: string) {
  const digits = written.split(".")[1]?.length ?? 0;
  return { minimumFractionDigits: digits, maximumFractionDigits: digits };
}

function Day({ parts }: { readonly parts: DayParts }) {
  const { year, month, day } = parts;
  return (
    <time dateTime={`${year}-${month}-${day}`}>
      {readersIntl.formatDate(onTheClock([year, month, day].map(Number)), {
        dateStyle: "medium",
        timeZone: "UTC",
      })}
    </time>
  );
}

/** In the offset it was given in, and beneath it on the reader's own clock. */
function Moment({ parts }: { readonly parts: MomentParts }) {
  const { year, month, day, hour, minute, second, fraction } = parts;
  const { sign, hours, minutes } = parts;
  const wall = onTheClock([year, month, day, hour, minute, second].map(Number));
  const east = (sign === "+" ? 1 : -1) * (Number(hours) * 60 + Number(minutes));
  const offset = `${sign}${hours}:${minutes}`;
  // The element's own time takes three digits of a second at most: the rest are cut, as rounding could carry.
  const exact = `${year}-${month}-${day}T${hour}:${minute}:${second}${fraction === undefined ? "" : `.${fraction.slice(0, 3)}`}${offset}`;
  return (
    <>
      <time dateTime={exact}>
        {say("step.momentOwn", {
          when: clockSaid(wall, fraction, "UTC"),
          offset,
        })}
      </time>
      <Typography variant="body2" component="span" sx={SENTENCE_SX}>
        {say("step.momentLocal", {
          when: clockSaid(wall.getTime() - east * 60_000, fraction),
        })}
      </Typography>
    </>
  );
}

/**
 * A moment as a clock in `timeZone` reads it, or the reader's own, every digit of a fraction of a second as
 * written, in the reader's own digits as the rest of it is.
 */
function clockSaid(
  at: Date | number,
  fraction: string | undefined,
  timeZone?: string,
): string {
  if (fraction === undefined) {
    return readersIntl.formatDate(at, { ...CLOCK, timeZone });
  }
  const digits = readersIntl.formatNumber(Number(fraction), {
    useGrouping: false,
    minimumIntegerDigits: fraction.length,
  });
  return readersIntl
    .formatDateToParts(at, { ...CLOCK, fractionalSecondDigits: 3, timeZone })
    .map((part) => (part.type === "fractionalSecond" ? digits : part.value))
    .join("");
}

/** A date and time as a clock reads them, held as that reading at UTC. */
function onTheClock([
  year = 0,
  month = 1,
  day = 1,
  hour = 0,
  minute = 0,
  second = 0,
]: readonly number[]): Date {
  const at = new Date(0);
  // Not `Date.UTC`, which reads the years 0 to 99 as 1900 to 1999.
  at.setUTCFullYear(year, month - 1, day);
  at.setUTCHours(hour, minute, second);
  return at;
}
