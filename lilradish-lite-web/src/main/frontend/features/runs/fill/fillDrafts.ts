import {
  isBareMediaType,
  isFileValue,
  pathKey,
  type FileValue,
  type FillField,
  type FillPath,
  type FillProblem,
  type FillReason,
  type FillValue,
  type FillValues,
  type FillSubmissionValue,
  type FillSubmissionValues,
} from "../../../api/filling";
import {
  holdsNothingFor,
  momentParts,
  valueRefused,
  type WrittenField,
} from "../../../lib/filling/writing";
import { oneLineWithin, showsInProse } from "../../../lib/text/legibility";

/** A moment as its controls hold it; an offset of none follows the reader's own at the date and time chosen. */
export interface MomentDraft {
  readonly date: string;
  readonly time: string;
  readonly offset: string | null;
}

export type FileDraft =
  | { readonly pending: File }
  | { readonly kept: FileValue; readonly originalIndex: number }
  | null;

export type FileIssue =
  "name" | "type_missing" | "type_malformed" | "type_not_taken" | "too_large";

export type OneDraft = string | MomentDraft | LevelDraft | FileDraft;

/** One value, or many where the field holds many. */
export type FieldDraft = OneDraft | readonly OneDraft[];

/** Every field of one level by name. */
export interface LevelDraft {
  readonly [name: string]: FieldDraft;
}

/** Everything filled as the server would read it: each value as it is sent, and every one that does not fit. */
interface DraftRead {
  readonly values: FillSubmissionValues;
  readonly uploads: readonly File[];
  readonly hasFilePositions: boolean;
  /** In the order the server names them, so a place is named once whoever finds it. */
  readonly problems: readonly FillProblem[];
}

/**
 * Reads a draft as `draftReader` says. A place keyed in `unreadable` holds what the browser was given and could
 * not read, which is not nothing, and so does not fit.
 */
export type DraftReader = (
  fields: readonly FillField[],
  draft: LevelDraft,
  unreadable: ReadonlySet<string>,
) => DraftRead;

/** What one place was last read as, which holds while its field and what it holds are the ones it was read from. */
interface Verdict {
  readonly field: FillField;
  readonly held: OneDraft;
  /** Undefined where it holds nothing. */
  readonly read: string | undefined;
  readonly reason: FillReason | null;
}

/** Everything a reader carries from one reading to the next. */
interface Reading {
  readonly unreadable: ReadonlySet<string>;
  readonly allowKept: boolean;
  readonly uploads: File[];
  hasFilePositions: boolean;
  readonly verdicts: Map<string, Verdict>;
  readonly problems: FillProblem[];
}

const HOURS_AND_MINUTES = /^[0-9]{2}:[0-9]{2}$/;

const DAY = /^([0-9]{4})-([0-9]{2})-([0-9]{2})$/;

const TIME = /^([0-9]{2}):([0-9]{2})(?::([0-9]{2}))?/;

const MIME_PARAMETERS =
  /^(?:[ \t]*;[ \t]*[!#$%&'*+.^_`|~A-Za-z0-9-]+=(?:[!#$%&'*+.^_`|~A-Za-z0-9-]+|"(?:[\t !#-[\]-~\x80-\xff]|\\[\t -~\x80-\xff])*"))*[ \t]*$/;

/** Every field as nothing has been filled yet: many hold none, and fields hold their own fields empty. */
export function draftOf(fields: readonly FillField[]): LevelDraft {
  return Object.fromEntries(
    fields.map((field) => [
      field.name,
      field.most === undefined ? oneDraftOf(field) : [],
    ]),
  );
}

/** One value of a field as nothing has been filled yet, as each one added to many starts. */
export function oneDraftOf(field: FillField): OneDraft {
  if (field.kind === "fields") {
    return draftOf(field.fields ?? []);
  }
  if (field.kind === "file") {
    return null;
  }
  return field.kind === "moment" ? { date: "", time: "", offset: null } : "";
}

/**
 * Every field holding what `values` gives it, as its control holds it: nothing given, or what no control of the
 * field could hold, is left as `draftOf` leaves it, so the reader's reading says what it lacks.
 */
export function draftFrom(
  fields: readonly FillField[],
  values: FillValues,
): LevelDraft {
  return Object.fromEntries(
    fields.map((field) => {
      const given = values[field.name];
      if (field.most === undefined) {
        return [field.name, oneDraftFrom(field, given, 0)];
      }
      return [
        field.name,
        Array.isArray(given)
          ? given.map((each: FillValue, index: number) =>
              oneDraftFrom(field, each, index),
            )
          : [],
      ];
    }),
  );
}

function oneDraftFrom(
  field: FillField,
  given: FillValue | undefined,
  originalIndex: number,
): OneDraft {
  if (field.kind === "fields") {
    return typeof given === "object" && given !== null && !Array.isArray(given)
      ? draftFrom(field.fields ?? [], given as FillValues)
      : oneDraftOf(field);
  }
  if (field.kind === "file") {
    return isFileValue(given) ? { kept: given, originalIndex } : null;
  }
  if (typeof given !== "string") {
    return oneDraftOf(field);
  }
  if (field.kind !== "moment") {
    return given;
  }
  const parts = momentParts(given);
  if (parts === null) {
    return oneDraftOf(field);
  }
  const fraction = parts.fraction === undefined ? "" : `.${parts.fraction}`;
  return {
    date: `${parts.year}-${parts.month}-${parts.day}`,
    time: `${parts.hour}:${parts.minute}:${parts.second}${fraction}`,
    offset: `${parts.sign}${parts.hours}:${parts.minutes}`,
  };
}

/** The draft with what stands at `path` in it replaced, and nothing off that path anew. */
export function placedAt(
  draft: LevelDraft,
  path: FillPath,
  next: FieldDraft,
): LevelDraft {
  return placedWithin(draft, path, 0, next) as LevelDraft;
}

/**
 * One reading of drafts after another, as the server reads what is sent: text of nothing that shows, and fields
 * holding nothing, are no value; each place is named for the first reason found there, a field before what it
 * holds. A place holding what it held when last read is not judged again.
 */
export function draftReader(allowKept = false): DraftReader {
  const verdicts = new Map<string, Verdict>();
  return (fields, draft, unreadable) => {
    const problems: FillProblem[] = [];
    const reading: Reading = {
      unreadable,
      allowKept,
      uploads: [],
      hasFilePositions: false,
      verdicts,
      problems,
    };
    const values = level(fields, draft, [], reading);
    return {
      values,
      uploads: reading.uploads,
      hasFilePositions: reading.hasFilePositions,
      problems,
    };
  };
}

/** The offset a moment is written with: the one chosen, or the reader's own at the date and time chosen. */
export function offsetOf(moment: MomentDraft): string {
  return moment.offset ?? ownOffsetAt(moment.date, moment.time);
}

/** An offset as a moment is written with it, of a whole number of minutes east of UTC. */
export function offsetWritten(minutesEast: number): string {
  const away = Math.abs(minutesEast);
  const hours = String(Math.floor(away / 60)).padStart(2, "0");
  const minutes = String(away % 60).padStart(2, "0");
  return `${minutesEast < 0 ? "-" : "+"}${hours}:${minutes}`;
}

function placedWithin(
  held: FieldDraft,
  path: FillPath,
  depth: number,
  next: FieldDraft,
): FieldDraft {
  if (depth === path.length) {
    return next;
  }
  const step = path[depth]!;
  if (typeof step === "number") {
    const items = held as readonly OneDraft[];
    return items.with(
      step,
      placedWithin(items[step]!, path, depth + 1, next) as OneDraft,
    );
  }
  const fields = held as LevelDraft;
  return {
    ...fields,
    [step]: placedWithin(fields[step]!, path, depth + 1, next),
  };
}

function level(
  fields: readonly FillField[],
  draft: LevelDraft,
  at: FillPath,
  reading: Reading,
): FillSubmissionValues {
  return Object.fromEntries(
    fields.map((field) => {
      const path = [...at, field.name];
      const held = draft[field.name]!;
      return [
        field.name,
        Array.isArray(held)
          ? many(field, held, path, reading)
          : one(field, held as OneDraft, path, reading),
      ];
    }),
  );
}

function one(
  field: FillField,
  held: OneDraft,
  path: FillPath,
  reading: Reading,
): FillSubmissionValue {
  const read = element(field, held, path, reading);
  if (read === undefined) {
    if (field.mustBeGiven) {
      reading.problems.push({ path, reason: "missing" });
    }
    return null;
  }
  return read;
}

function many(
  field: FillField,
  items: readonly OneDraft[],
  path: FillPath,
  reading: Reading,
): FillSubmissionValue {
  const { problems } = reading;
  if (field.kind === "file" && reading.unreadable.has(pathKey(path))) {
    problems.push({ path, reason: "malformed" });
    return null;
  }
  if (items.length === 0 && field.mustBeGiven) {
    problems.push({ path, reason: "missing" });
  } else if (items.length > (field.most ?? 0)) {
    // No element read: one far past its most would otherwise be answered with a problem for each.
    problems.push({ path, reason: "too_many" });
    return null;
  }
  return items.map((item, index) => {
    const at = [...path, index];
    const read = element(field, item, at, reading);
    if (read === undefined) {
      problems.push({ path: at, reason: "missing" });
      return null;
    }
    return read;
  });
}

/** Undefined where it holds nothing, which its field's demand decides; null where it does not fit. */
function element(
  field: FillField,
  held: OneDraft,
  path: FillPath,
  reading: Reading,
): FillSubmissionValue | undefined {
  const { problems } = reading;
  if (field.kind === "fields") {
    const before = problems.length;
    const read = level(field.fields ?? [], held as LevelDraft, path, reading);
    return heldNothing(read, problems, before) ? undefined : read;
  }
  if (field.kind === "file") {
    if (held === null) {
      return undefined;
    }
    const pending =
      typeof held === "object" && "pending" in held ? held.pending : undefined;
    if (pending instanceof File && fileIssueOf(field, pending) === null) {
      const upload = reading.uploads.length;
      reading.uploads.push(pending);
      reading.hasFilePositions = true;
      return { upload, name: pending.name };
    }
    const kept =
      typeof held === "object" && "kept" in held ? held.kept : undefined;
    const originalIndex =
      typeof held === "object" && "originalIndex" in held
        ? held.originalIndex
        : undefined;
    if (
      reading.allowKept &&
      isFileValue(kept) &&
      typeof originalIndex === "number" &&
      Number.isSafeInteger(originalIndex) &&
      originalIndex >= 0
    ) {
      reading.hasFilePositions = true;
      return { keep: originalIndex };
    }
    problems.push({ path, reason: "malformed" });
    return null;
  }
  const key = pathKey(path);
  if (reading.unreadable.has(key)) {
    problems.push({ path, reason: "malformed" });
    return null;
  }
  const kept = reading.verdicts.get(key);
  const verdict =
    kept !== undefined && kept.field === field && kept.held === held
      ? kept
      : judged(field, held);
  reading.verdicts.set(key, verdict);
  if (verdict.reason !== null) {
    problems.push({ path, reason: verdict.reason });
    return null;
  }
  return verdict.read;
}

function judged(field: FillField, held: OneDraft): Verdict {
  const typed =
    field.kind === "moment"
      ? momentWritten(held as MomentDraft)
      : (held as string);
  if (typed === null) {
    return { field, held, read: undefined, reason: "malformed" };
  }
  if (holdsNothingFor(field as WrittenField, typed)) {
    return { field, held, read: undefined, reason: null };
  }
  const reason = valueRefused(
    { ...(field as WrittenField), mustBeGiven: true },
    typed,
  );
  return { field, held, read: typed, reason };
}

function mediaTypeEssence(type: string): string | null {
  const separator = type.indexOf(";");
  const bare = (separator < 0 ? type : type.slice(0, separator)).replace(
    /^[ \t]+|[ \t]+$/g,
    "",
  );
  const parameters = separator < 0 ? "" : type.slice(separator);
  return isBareMediaType(bare) && MIME_PARAMETERS.test(parameters)
    ? bare.toLowerCase()
    : null;
}

export function fileIssueOf(field: FillField, file: File): FileIssue | null {
  if (!oneLineWithin(file.name, 255) || !showsInProse(file.name)) {
    return "name";
  }
  if (file.type === "") {
    return "type_missing";
  }
  const essence = mediaTypeEssence(file.type);
  if (essence === null) {
    return "type_malformed";
  }
  if (!field.types?.some((type) => type.toLowerCase() === essence)) {
    return "type_not_taken";
  }
  return file.size > (field.maximumBytes ?? 0) ? "too_large" : null;
}

/** Fields holding nothing are no value, so what is missing within them is dropped for their own demand. */
function heldNothing(
  read: FillSubmissionValues,
  problems: FillProblem[],
  before: number,
): boolean {
  const nothing =
    Object.values(read).every(
      (value) => value === null || (Array.isArray(value) && value.length === 0),
    ) && problems.slice(before).every(({ reason }) => reason === "missing");
  if (nothing) {
    problems.splice(before);
  }
  return nothing;
}

/**
 * Nothing where neither a date nor a time is chosen; a time chosen to the minute is written to the second. Null
 * where the reader's own offset is followed and their clock never reads that time on that day.
 */
function momentWritten(moment: MomentDraft): string | null {
  if (moment.date === "" && moment.time === "") {
    return "";
  }
  if (moment.offset === null && skippedOnTheClock(moment.date, moment.time)) {
    return null;
  }
  const time = HOURS_AND_MINUTES.test(moment.time)
    ? `${moment.time}:00`
    : moment.time;
  return `${moment.date}T${time}${offsetOf(moment)}`;
}

/** The reader's own offset at a date and time on their clock, or at this moment where either is not chosen. */
function ownOffsetAt(date: string, time: string): string {
  const at = onTheClock(date, time) ?? new Date();
  // An offset kept to the second, as some zones kept one before standard time, is written to the minute.
  return offsetWritten(Math.round(-at.getTimezoneOffset()));
}

/** Whether the clock moves past the time chosen on the day chosen, as it does where it springs forward. */
function skippedOnTheClock(date: string, time: string): boolean {
  const clock = TIME.exec(time);
  const at = onTheClock(date, time);
  return (
    clock !== null &&
    at !== null &&
    (at.getHours() !== Number(clock[1]) || at.getMinutes() !== Number(clock[2]))
  );
}

/** A date and time on the reader's clock as the instant it is, which moves on past a time the clock skips. */
function onTheClock(date: string, time: string): Date | null {
  const day = DAY.exec(date);
  const clock = TIME.exec(time);
  if (day === null || clock === null) {
    return null;
  }
  const at = new Date();
  // Not the constructor: it reads a year below 100 as one in the 1900s.
  at.setFullYear(Number(day[1]), Number(day[2]) - 1, Number(day[3]));
  at.setHours(Number(clock[1]), Number(clock[2]), Number(clock[3] ?? 0), 0);
  return at;
}
