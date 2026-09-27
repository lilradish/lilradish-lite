// The page's half of how one value is written, as the server reads a value filled in; the server's half is the
// one that holds, and one table of cases holds the two level.

import type { FieldKind } from "../../api/declaration";
import type { FillField, FillReason } from "../../api/filling";
import { proseCharactersRefused, showsInProse } from "../text/legibility";

/** The kinds one value is written as; fields hold values rather than being written as one. */
type WrittenKind = Exclude<FieldKind, "fields">;

export type WrittenField = FillField & { readonly kind: WrittenKind };

export const MOST_DIGITS = 38;

export const MOST_FRACTION_DIGITS = 6;

export const FIRST_YEAR = 1;

export const LAST_YEAR = 9999;

export const FURTHEST_OFFSET_MINUTES = 14 * 60;

const PLAIN_NUMBER = /^-?(?:0|[1-9][0-9]*)(?:\.[0-9]+)?$/;

const DAY = /^([0-9]{4})-([0-9]{2})-([0-9]{2})$/;

const MOMENT = new RegExp(
  "^([0-9]{4}-[0-9]{2}-[0-9]{2})T([0-9]{2}):([0-9]{2}):([0-9]{2})" +
    `(?:\\.([0-9]{1,${MOST_FRACTION_DIGITS}}))?([+-])([0-9]{2}):([0-9]{2})$`,
);

/**
 * Whether what is typed is no value at all, whatever its field's kind: nothing, or nothing that shows and nothing
 * text is refused for, which is never dropped. Nothing is trimmed, so spacing around what shows still counts.
 */
export function holdsNothing(typed: string): boolean {
  return (
    typed === "" ||
    (!showsInProse(typed) && proseCharactersRefused(typed) === null)
  );
}

/**
 * Why the server would refuse what is typed as the value of one field, or null where it takes it. Nothing typed
 * is refused only where the field must be given.
 */
export function valueRefused(
  field: WrittenField,
  typed: string,
): FillReason | null {
  if (holdsNothing(typed)) {
    return field.mustBeGiven ? "missing" : null;
  }
  switch (field.kind) {
    case "text":
      return (
        proseCharactersRefused(typed) ??
        (codePointsIn(typed) <= (field.longest ?? 0) ? null : "too_long")
      );
    case "number":
      return numberWritten(typed) ? null : "malformed";
    case "date":
      return dated(typed) ? null : "malformed";
    case "moment":
      return momentary(typed) ? null : "malformed";
    case "yes_no":
      return typed === "true" || typed === "false" ? null : "malformed";
    case "term":
      return field.terms?.terms.some((offered) => offered.term === typed)
        ? null
        : "not_a_term";
  }
}

/** How many characters text runs to, counted in code points as the server counts. */
export function codePointsIn(typed: string): number {
  let counted = 0;
  for (
    let at = 0;
    at < typed.length;
    at += (typed.codePointAt(at) ?? 0) > 0xffff ? 2 : 1
  ) {
    counted += 1;
  }
  return counted;
}

/** Plainly, with no exponent, and never a zero with a minus sign, however many digits it runs to. */
export function numberWrittenPlainly(typed: string): boolean {
  return (
    PLAIN_NUMBER.test(typed) && (!typed.startsWith("-") || /[1-9]/.test(typed))
  );
}

/** Plainly, and every digit counted. */
export function numberWritten(typed: string): boolean {
  return (
    numberWrittenPlainly(typed) &&
    typed.replace(/[-.]/g, "").length <= MOST_DIGITS
  );
}

/** A day's digits as they are written. */
export interface DayParts {
  readonly year: string;
  readonly month: string;
  readonly day: string;
}

/** A moment's digits and its offset's sign as they are written; a fraction of a second only where one is. */
export interface MomentParts extends DayParts {
  readonly hour: string;
  readonly minute: string;
  readonly second: string;
  readonly fraction?: string;
  readonly sign: string;
  readonly hours: string;
  readonly minutes: string;
}

/** A day that is on the calendar, within the years a date may be. */
export function dated(typed: string): boolean {
  return dayParts(typed) !== null;
}

/** The parts of a day `dated` takes, and null for anything else. */
export function dayParts(typed: string): DayParts | null {
  const matched = DAY.exec(typed);
  if (matched === null) {
    return null;
  }
  const [, year, month, day] = matched;
  const yearNumber = Number(year);
  const monthNumber = Number(month);
  const dayOfMonth = Number(day);
  return yearNumber >= FIRST_YEAR &&
    yearNumber <= LAST_YEAR &&
    monthNumber >= 1 &&
    monthNumber <= 12 &&
    dayOfMonth >= 1 &&
    dayOfMonth <= daysIn(yearNumber, monthNumber)
    ? { year, month, day }
    : null;
}

function daysIn(year: number, month: number): number {
  if (month === 2) {
    return year % 4 === 0 && (year % 100 !== 0 || year % 400 === 0) ? 29 : 28;
  }
  return [4, 6, 9, 11].includes(month) ? 30 : 31;
}

/** A moment on a day `dated` takes, at an offset one is written at, its fraction of a second bounded. */
export function momentary(typed: string): boolean {
  return momentParts(typed) !== null;
}

/** The parts of a moment `momentary` takes, and null for anything else. */
export function momentParts(typed: string): MomentParts | null {
  const matched = MOMENT.exec(typed);
  const day = matched === null ? null : dayParts(matched[1]!);
  if (matched === null || day === null) {
    return null;
  }
  const [, , hour, minute, second, , sign, hours, minutes] = matched;
  const fraction: string | undefined = matched[5];
  const offsetMinutes = Number(minutes);
  const away = Number(hours) * 60 + offsetMinutes;
  const fits =
    Number(hour) <= 23 &&
    Number(minute) < 60 &&
    Number(second) <= 59 &&
    offsetMinutes < 60 &&
    away <= FURTHEST_OFFSET_MINUTES &&
    // Nothing is written -00:00, so a moment at no offset has the one written form.
    (sign === "+" || away > 0);
  if (!fits) {
    return null;
  }
  return {
    ...day,
    hour,
    minute,
    second,
    sign,
    hours,
    minutes,
    ...(fraction === undefined ? {} : { fraction }),
  };
}
