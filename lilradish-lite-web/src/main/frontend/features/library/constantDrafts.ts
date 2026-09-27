import type { FieldKind } from "../../api/declaration";
import type { MessageId } from "../../i18n/app";
import {
  FIRST_YEAR,
  FURTHEST_OFFSET_MINUTES,
  LAST_YEAR,
  MOST_DIGITS,
  MOST_FRACTION_DIGITS,
  numberWrittenPlainly,
  valueRefused,
} from "../../lib/filling/writing";
import { concealingIn } from "../../lib/text/legibility";
import type { Shape } from "./workflowDrafts";

// Level with the server's ConstantFit.
const MOST_TEXT = 8192;

/** The figures the words for a value not written as its kind is say, drawn from the rules that judge it. */
export const WRITTEN_LIMITS = {
  digits: MOST_DIGITS,
  firstYear: String(FIRST_YEAR).padStart(4, "0"),
  lastYear: LAST_YEAR,
  fraction: MOST_FRACTION_DIGITS,
  furthest: `${FURTHEST_OFFSET_MINUTES / 60}:00`,
} as const;

// Each string is matched whole before a number can be, so no digit inside one is taken for a number.
const STRING_OR_NUMBER =
  /"(?:[^"\\]|\\.)*"|-?(?:0|[1-9][0-9]*)(?:\.[0-9]+)?(?:[eE][+-]?[0-9]+)?/g;

const TYPED_AS_TEXT: ReadonlySet<FieldKind> = new Set([
  "text",
  "term",
  "date",
  "moment",
]);

/**
 * A value as JSON.parse reads it, looked into only for its text: each number in it has been through a double,
 * so the text it was read from is what is sent.
 */
type Parsed =
  | null
  | boolean
  | number
  | string
  | readonly Parsed[]
  | { readonly [member: string]: Parsed };

export type ConstantKind = "text" | "number" | "yes_no" | "written";

export function constantKindOf(target: Shape | undefined): ConstantKind {
  if (target === undefined || target.many) {
    return "written";
  }
  if (TYPED_AS_TEXT.has(target.kind)) {
    return "text";
  }
  return target.kind === "number" || target.kind === "yes_no"
    ? target.kind
    : "written";
}

/**
 * A constant's JSON text as a person reads and edits it filling {@code target}: text as itself, anything else
 * as it is written.
 */
export function constantTyped(
  written: string,
  target: Shape | undefined,
): string {
  return constantKindOf(target) === "text" && written.startsWith('"')
    ? (JSON.parse(written) as string)
    : written;
}

// Text or a term typed as null is the word; one of these so typed is no value, which ConstantFit
// takes wherever the field need not be given.
const NULL_WHEN_TYPED: ReadonlySet<FieldKind> = new Set([
  "number",
  "yes_no",
  "date",
  "moment",
]);

/**
 * The JSON text a constant typed is sent as, or the first reason the server
 * would refuse it, checked in the order the server checks them.
 */
export function constantRead(
  typed: string,
  target: Shape | undefined,
): { readonly written: string } | { readonly why: MessageId } {
  if (
    typed === "null" &&
    target?.many === false &&
    !target.mustBeGiven &&
    NULL_WHEN_TYPED.has(target.kind)
  ) {
    return { written: typed };
  }
  const kind = constantKindOf(target);
  if (kind === "number") {
    return numberRead(typed);
  }
  const written =
    kind === "text"
      ? JSON.stringify(typed)
      : kind === "yes_no"
        ? yesOrNo(typed)
        : typed;
  const value = written === undefined ? undefined : parsed(written);
  if (written === undefined || value === undefined) {
    return { why: "workflow.constantUnreadable" };
  }
  const numbers = numbersIn(written);
  if (numbers.some((number) => !numberWrittenPlainly(number))) {
    return { why: "workflow.numberUnreadable" };
  }
  const concealing = concealedIn(value);
  if (concealing !== null) {
    return {
      why:
        concealing === "tag"
          ? "refusal.PROSE_TAG_CHARACTER"
          : "refusal.PROSE_DIRECTION_CONTROL",
    };
  }
  const unwritten =
    kind === "text"
      ? formWhy(typed, target)
      : target?.many === true && target.kind === "number"
        ? numbersWhy(value, numbers, target.mustBeGiven)
        : null;
  if (unwritten !== null) {
    return { why: unwritten };
  }
  return numbers.some(pastDigits) ||
    holdsNul(value) ||
    textIn(value) > MOST_TEXT
    ? { why: "refusal.CONSTANT_DOES_NOT_FIT" }
    : { written };
}

function yesOrNo(typed: string): string | undefined {
  return refusedAs("yes_no", typed) ? undefined : typed;
}

function parsed(written: string): Parsed | undefined {
  try {
    return JSON.parse(written) as Parsed;
  } catch {
    return undefined;
  }
}

/** Written plainly, its digits either side of its point counted together, as the server's number is. */
function numberRead(
  typed: string,
): { readonly written: string } | { readonly why: MessageId } {
  if (!refusedAs("number", typed)) {
    return { written: typed };
  }
  return {
    why: numberWrittenPlainly(typed)
      ? "workflow.numberPastDigits"
      : "workflow.numberUnreadable",
  };
}

/** Why one date or moment typed is not written as its kind is; none where it is, or is of another kind. */
function formWhy(typed: string, target: Shape | undefined): MessageId | null {
  if (target?.kind === "date") {
    return refusedAs("date", typed) ? "workflow.dateUnreadable" : null;
  }
  if (target?.kind === "moment") {
    return refusedAs("moment", typed) ? "workflow.momentUnreadable" : null;
  }
  return null;
}

/** Why many numbers written out fit no field of them, as ConstantFit judges them; none where they fit. */
function numbersWhy(
  value: Parsed,
  numbers: readonly string[],
  mustBeGiven: boolean,
): MessageId | null {
  if (value === null && !mustBeGiven) {
    return null;
  }
  if (
    !Array.isArray(value) ||
    value.some((item) => typeof item !== "number") ||
    (mustBeGiven && value.length === 0)
  ) {
    return "workflow.numberUnreadable";
  }
  return numbers.some((number) => refusedAs("number", number))
    ? "workflow.numberPastDigits"
    : null;
}

/** Whether one value typed is not written as a value of its kind is, judged as a value filled in is. */
function refusedAs(
  kind: "number" | "date" | "moment" | "yes_no",
  typed: string,
): boolean {
  // One number, date, moment or yes/no is never typed blank, whatever the field demands; null is read apart.
  return (
    valueRefused({ name: "constant", kind, mustBeGiven: true }, typed) !== null
  );
}

/** Each number JSON text holds, as the text it is written in. */
function numbersIn(written: string): string[] {
  return [...written.matchAll(STRING_OR_NUMBER)]
    .map(([token]) => token)
    .filter((token) => !token.startsWith('"'));
}

/**
 * Counted as the server's decimal is, of a number written with no exponent: a trailing zero past the point is a
 * digit, a leading zero never one.
 */
function pastDigits(number: string): boolean {
  const [whole = "", part = ""] = number.replace(/^-/, "").split(".");
  const precision = Math.max(1, (whole + part).replace(/^0+/, "").length);
  return precision - part.length > MOST_DIGITS || part.length > MOST_DIGITS;
}

function concealedIn(value: Parsed): "direction_control" | "tag" | null {
  if (typeof value === "string") {
    return concealingIn(value);
  }
  for (const each of heldIn(value)) {
    const found = concealedIn(each);
    if (found !== null) {
      return found;
    }
  }
  return null;
}

function holdsNul(value: Parsed): boolean {
  if (typeof value === "string") {
    return value.includes("\0");
  }
  const named =
    value !== null && typeof value === "object" && !Array.isArray(value)
      ? Object.keys(value)
      : [];
  return (
    named.some((name) => name.includes("\0")) || heldIn(value).some(holdsNul)
  );
}

/** Characters of text a value holds, member names aside, each counted as a code point. */
function textIn(value: Parsed): number {
  return typeof value === "string"
    ? [...value].length
    : heldIn(value).reduce<number>((sum, each) => sum + textIn(each), 0);
}

function heldIn(value: Parsed): readonly Parsed[] {
  if (value === null || typeof value !== "object") {
    return [];
  }
  return Array.isArray(value)
    ? (value as readonly Parsed[])
    : Object.values(value);
}
