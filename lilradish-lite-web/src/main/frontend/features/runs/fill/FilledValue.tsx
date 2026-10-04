import Box from "@mui/material/Box";
import Button from "@mui/material/Button";
import Link from "@mui/material/Link";
import Typography from "@mui/material/Typography";
import { Fragment, useContext, useId, useState, type ReactNode } from "react";

import {
  isDownloadableFileValue,
  isWithheldLeaf,
  type FileValue,
  type ReadField,
  type ShownValue,
  type ShownValues,
} from "../../../api/filling";
import type { ShownOrKept } from "../../../api/groups/{groupId}/runs/{runId}/steps";
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
import { QUIET_SX } from "../../../lib/layout/parts";
import { HiddenShown, HiddenShownAcross } from "../../../lib/text/HiddenShown";
import { ModelProse } from "../../../lib/text/ModelProse";
import { spokenName } from "../steps/stepTitle";
import { CurrentFileRunContext, fileDownloadHref } from "./CurrentFileRun";

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

const LONGEST_OPEN_TEXT = 200;

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
}: {
  /** Absent where nothing the read holds declares it: drawn by its shape alone. */
  readonly field?: ReadField;
  /** What a long text's control names it by; shown as given, so a name in it arrives isolated. */
  readonly label: string;
  readonly shown: ShownOrKept;
  /** Whether a long text is shut until opened: only what a run was started with is, as a reviewer reads in full. */
  readonly shut: boolean;
  /** Whether it stands within a line, as a heading or a link's words: many, and fields, then run on in it. */
  readonly inLine?: boolean;
}) {
  if ("withheld" in shown) {
    return (
      <Typography variant="body2" component="span" sx={SENTENCE_SX}>
        {say("step.withheld")}
      </Typography>
    );
  }
  return (
    <HiddenShownAcross
      value={
        "asKept" in shown ? shown.asKept : visibleTextOf(field, shown.value)
      }
    >
      {"asKept" in shown ? (
        <KeptEarlier label={label} text={shown.asKept} shut={shut} />
      ) : (
        valueDrawn(field, label, shown.value, shut, inLine)
      )}
    </HiddenShownAcross>
  );
}

function visibleTextOf(
  field: ReadField | undefined,
  value: ShownValue,
): unknown {
  if (isWithheldLeaf(value)) {
    return null;
  }
  if (field?.kind === "file") {
    if (Array.isArray(value)) {
      return field.most === undefined
        ? null
        : value.map((each) => visibleFileTextOf(each));
    }
    return field.most !== undefined && value !== null
      ? null
      : visibleFileTextOf(value);
  }
  if (Array.isArray(value)) {
    return value.map((each) => visibleTextOf(field, each));
  }
  if (field?.kind === "fields" && value !== null && typeof value === "object") {
    return Object.fromEntries(
      Object.entries(value).map(([name, each]) => [
        name,
        visibleTextOf(
          field.fields?.find((declared) => declared.name === name),
          each,
        ),
      ]),
    );
  }
  return value;
}

function visibleFileTextOf(value: ShownValue): unknown {
  return isDownloadableFileValue(value)
    ? { name: value.name, type: value.type, size: value.size }
    : null;
}

function valueDrawn(
  field: ReadField | undefined,
  label: string,
  value: ShownValue,
  shut: boolean,
  inLine: boolean,
): ReactNode {
  if (
    field?.kind === "file" &&
    value !== null &&
    Array.isArray(value) !== (field.most !== undefined)
  ) {
    return say("step.unknown");
  }
  if (!Array.isArray(value)) {
    return (
      <One
        field={field}
        label={label}
        value={value}
        shut={shut}
        inLine={inLine}
      />
    );
  }
  if (field !== undefined && field.most === undefined) {
    return say("step.unknown");
  }
  if (value.length === 0) {
    return say("step.none");
  }
  if (inLine) {
    return inOneLine(
      value.map((each, at) => (
        <One
          key={at}
          field={field}
          label={say("step.itemOf", { field: label, number: at + 1 })}
          value={each}
          shut={shut}
          inLine
        />
      )),
    );
  }
  return (
    <Box component="ul" sx={MANY_SX}>
      {value.map((each, at) => (
        <li key={at}>
          <One
            field={field}
            label={say("step.itemOf", { field: label, number: at + 1 })}
            value={each}
            shut={shut}
            inLine={false}
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
}: {
  readonly field: ReadField | undefined;
  readonly label: string;
  readonly value: ShownValue;
  readonly shut: boolean;
  readonly inLine: boolean;
}): ReactNode {
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
        <FileShown value={value} />
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
      />
    ) : (
      say("step.unknown")
    );
  }
  switch (field?.kind) {
    case undefined:
    case "text":
      if (shut) {
        return <ShutText label={label} value={value} />;
      }
      return field?.lines === "several" && !inLine ? (
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

function FileShown({ value }: { readonly value: FileValue }) {
  const currentRun = useContext(CurrentFileRunContext);
  const name = (
    <Box component="bdi">
      <HiddenShown text={value.name} />
    </Box>
  );
  return (
    <Box component="span">
      {currentRun === null ? (
        name
      ) : (
        <Link href={fileDownloadHref(currentRun, value.id)} download>
          {say("step.downloadFile")} {name}
        </Link>
      )}{" "}
      {say("step.fileType", { type: isolatedInText(value.type) })}{" "}
      {say("step.fileSize", { size: value.size })}
    </Box>
  );
}

function drawnText(value: string) {
  return (
    <Box component="bdi" sx={TEXT_SX}>
      <HiddenShown text={value} />
    </Box>
  );
}

/**
 * Long is past the longest in code points, as the server counts, or more than one line. The control's name stays as
 * it is, whether it is open said apart.
 */
function ShutText({
  label,
  value,
}: {
  readonly label: string;
  readonly value: string;
}) {
  const [open, setOpen] = useState(false);
  const textId = useId();
  const drawn = drawnText(value);
  if (codePointsIn(value) <= LONGEST_OPEN_TEXT && !/[\n\r]/.test(value)) {
    return drawn;
  }
  return (
    <>
      <Button
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
}: {
  readonly fields: readonly ReadField[] | undefined;
  readonly values: ShownValues;
  readonly shut: boolean;
  /** What the value holding these fields is named by; absent at the top. */
  readonly outer?: string;
  /** Whether they stand within a line: each then runs on in it after its label. */
  readonly inLine?: boolean;
}) {
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
          said: declared.label ?? declared.name,
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
    />
  ));
  if (inLine) {
    return inOneLine(
      each.map(({ said }, at) => labelledInLine(<bdi>{said}</bdi>, drawn[at])),
    );
  }
  return (
    <Fields>
      {each.map(({ name, said }, at) => (
        <Field key={name} label={<bdi>{said}</bdi>}>
          {drawn[at]}
        </Field>
      ))}
    </Fields>
  );
}

/** Each in one line, joined as the reader's language joins a list of things that all hold. */
export function inOneLine(items: readonly ReactNode[]): ReactNode {
  const joined = readersIntl.formatList(
    items.map((item, at) => <Fragment key={at}>{item}</Fragment>),
    { type: "conjunction" },
  );
  return Array.isArray(joined)
    ? joined.map((part, at) => <Fragment key={at}>{part}</Fragment>)
    : joined;
}

/** A value within a line after what it is called. */
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
