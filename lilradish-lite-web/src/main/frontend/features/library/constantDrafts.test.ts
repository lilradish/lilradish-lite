import { describe, expect, it } from "vitest";

import type { FieldKind } from "../../api/declaration";
import type { FillReason } from "../../api/filling";
import type { MessageId } from "../../i18n/app";
import WRITING from "../../lib/filling/writing.cases.json";
import { numberWrittenPlainly } from "../../lib/filling/writing";
import CASES from "./constantDrafts.cases.json";
import { constantKindOf, constantRead, constantTyped } from "./constantDrafts";
import { termFits, type Shape } from "./workflowDrafts";

/**
 * One case of the table the server's half is run over too; `times` repeats what is typed, and `kind` is what one
 * value typed fills, where the case is of one, `mustBeGiven` what that field demands, given where absent.
 */
interface Case {
  readonly kind?: FieldKind;
  readonly typed: string;
  readonly times?: number;
  readonly mustBeGiven?: boolean;
  readonly accepted: boolean;
}

function typedIn(each: Pick<Case, "typed" | "times">): string {
  return each.typed.repeat(each.times ?? 1);
}

function demanded(
  cases: readonly Case[],
): readonly (Case & { readonly mustBeGiven: boolean })[] {
  return cases.map((each) => ({
    ...each,
    mustBeGiven: each.mustBeGiven ?? true,
  }));
}

const TEXT: Shape = {
  name: "said",
  kind: "text",
  many: false,
  mustBeGiven: true,
};

const NUMBER: Shape = { ...TEXT, kind: "number" };

const NUMBERS: Shape = { ...NUMBER, many: true };

const YES_NO: Shape = { ...TEXT, kind: "yes_no" };

const DATE: Shape = { ...TEXT, kind: "date" };

const MOMENT: Shape = { ...TEXT, kind: "moment" };

const FIELDS: Shape = { ...TEXT, kind: "fields", fields: [TEXT] };

const SMILE = String.fromCodePoint(0x1f600);

const DIRECTION_CONTROL = String.fromCodePoint(0x202e);

const TAG = String.fromCodePoint(0xe0041);

const NUL = String.fromCodePoint(0);

const BACKSLASH = String.fromCodePoint(0x5c);

const DIGITS_38 = "12345678901234567890123456789012345678";

/** Numbers of 38 digits past their point, before it, and past it with one before, each written plainly. */
const EITHER_SIDE_38 =
  "[0." +
  "0".repeat(37) +
  "1,-0." +
  "0".repeat(37) +
  "1,1" +
  "0".repeat(37) +
  ",1." +
  "0".repeat(38) +
  "]";

/** Numbers of 38 digits in all, a zero before the point counted, as one number filled in is. */
const IN_ALL_38 =
  "[0." +
  "0".repeat(36) +
  "1,-0." +
  "0".repeat(36) +
  "1,1" +
  "0".repeat(37) +
  ",1." +
  "0".repeat(37) +
  "]";

describe("constantKindOf", () => {
  it.each([
    ["one text", TEXT, "text"],
    ["one term", { ...TEXT, kind: "term" }, "text"],
    ["one date", { ...TEXT, kind: "date" }, "text"],
    ["one moment", { ...TEXT, kind: "moment" }, "text"],
    ["one number", NUMBER, "number"],
    ["one yes or no", YES_NO, "yes_no"],
    ["many texts", { ...TEXT, many: true }, "written"],
    ["many numbers", NUMBERS, "written"],
    ["fields", FIELDS, "written"],
    ["what the page does not know", undefined, "written"],
  ] as const)("types a constant filling %s as %s", (_case, target, kind) => {
    expect(constantKindOf(target)).toBe(kind);
  });
});

describe("constantTyped", () => {
  it.each([
    ["text filling text as itself", '"urgent"', TEXT, "urgent"],
    [
      "text holding a quote filling text as itself",
      '"a' + BACKSLASH + '"b"',
      TEXT,
      'a"b',
    ],
    [
      "text filling what is not known as written",
      '"urgent"',
      undefined,
      '"urgent"',
    ],
    ["a number as written, its zero kept", "12.50", NUMBER, "12.50"],
    ["a number of 38 digits to the digit", DIGITS_38, NUMBER, DIGITS_38],
    ["yes as written", "true", YES_NO, "true"],
    ["null filling text as written", "null", TEXT, "null"],
    ["a list as written", '["a",true]', { ...TEXT, many: true }, '["a",true]'],
  ] as const)("types %s", (_case, written, target, typed) => {
    expect(constantTyped(written, target)).toBe(typed);
  });
});

describe("constantRead", () => {
  it.each([
    [
      "text as itself, quoted",
      '"quoted"',
      TEXT,
      '"' + BACKSLASH + '"quoted' + BACKSLASH + '""',
    ],
    ["no text at all as text", "", TEXT, '""'],
    ["a term as itself", "urgent", { ...TEXT, kind: "term" }, '"urgent"'],
    ["a number", "-12.5", NUMBER, "-12.5"],
    ["a number, its zeros either end kept", "100.500", NUMBER, "100.500"],
    ["a number of 38 digits to the digit", DIGITS_38, NUMBER, DIGITS_38],
    [
      "a number of 38 digits in all, 37 of them past its point",
      "0." + "1".repeat(37),
      NUMBER,
      "0." + "1".repeat(37),
    ],
    ["a date, quoted", "2024-02-29", DATE, '"2024-02-29"'],
    [
      "a moment at its furthest, quoted",
      "9999-12-31T23:59:59.999999-14:00",
      MOMENT,
      '"9999-12-31T23:59:59.999999-14:00"',
    ],
    ["yes", "true", YES_NO, "true"],
    ["no", "false", YES_NO, "false"],
    [
      "a date typed as null, as null, where it need not be given",
      "null",
      { ...DATE, mustBeGiven: false },
      "null",
    ],
    [
      "text typed as null as the word, where it need not be given",
      "null",
      { ...TEXT, mustBeGiven: false },
      '"null"',
    ],
    [
      "no numbers at all where they need not be given",
      "[]",
      { ...NUMBERS, mustBeGiven: false },
      "[]",
    ],
    ["a value written out", '{"said":"a"}', FIELDS, '{"said":"a"}'],
    [
      "written out where nothing is known, as typed",
      "[1, null]",
      undefined,
      "[1, null]",
    ],
    [
      "numbers written out as far as 38 digits either side of their point, where nothing is known",
      EITHER_SIDE_38,
      undefined,
      EITHER_SIDE_38,
    ],
    [
      "many numbers of 38 digits in all, counted as one number is",
      IN_ALL_38,
      NUMBERS,
      IN_ALL_38,
    ],
    [
      "a number written out with zeros before its first digit, which count for nothing where nothing is known",
      "[0.000" + "1".repeat(35) + "]",
      undefined,
      "[0.000" + "1".repeat(35) + "]",
    ],
    [
      "digits inside text, which are no number",
      '["' + "1".repeat(50) + '"]',
      { ...TEXT, many: true },
      '["' + "1".repeat(50) + '"]',
    ],
    [
      "text of 8192 characters counted in code points",
      SMILE.repeat(8192),
      TEXT,
      '"' + SMILE.repeat(8192) + '"',
    ],
  ] as const)("reads %s", (_case, typed, target, written) => {
    expect(constantRead(typed, target)).toEqual({ written });
  });

  it.each([
    ["a number with an exponent", "1e5", NUMBER, "workflow.numberUnreadable"],
    ["a number with a leading zero", "01", NUMBER, "workflow.numberUnreadable"],
    ["a number ending in its point", "1.", NUMBER, "workflow.numberUnreadable"],
    ["a zero with a minus sign", "-0.0", NUMBER, "workflow.numberUnreadable"],
    ["no number at all", "", NUMBER, "workflow.numberUnreadable"],
    [
      "a blank number filling a field that need not be given",
      " ",
      { ...NUMBER, mustBeGiven: false },
      "workflow.numberUnreadable",
    ],
    [
      "a blank date filling a field that need not be given",
      "\t",
      { ...DATE, mustBeGiven: false },
      "workflow.dateUnreadable",
    ],
    [
      "yes or no left blank where it need not be given",
      "",
      { ...YES_NO, mustBeGiven: false },
      "workflow.constantUnreadable",
    ],
    [
      "a number of 39 digits before its point",
      "1" + "0".repeat(38),
      NUMBER,
      "workflow.numberPastDigits",
    ],
    [
      "a number of 39 digits in all, 38 of them past its point",
      "0." + "1".repeat(38),
      NUMBER,
      "workflow.numberPastDigits",
    ],
    [
      "a number of 39 digits past its point",
      "0." + "0".repeat(38) + "1",
      NUMBER,
      "workflow.numberPastDigits",
    ],
    [
      "a number of 39 digits past its point, all but one of them trailing zeros",
      "1." + "0".repeat(39),
      NUMBER,
      "workflow.numberPastDigits",
    ],
    [
      "a date not written year-month-day",
      "2024-3-31",
      DATE,
      "workflow.dateUnreadable",
    ],
    ["a date no calendar holds", "2023-02-29", DATE, "workflow.dateUnreadable"],
    [
      "a moment whose offset is Z",
      "2024-03-31T09:30:00Z",
      MOMENT,
      "workflow.momentUnreadable",
    ],
    [
      "a date holding a direction control, which is named before its form",
      "2024-03-31" + DIRECTION_CONTROL,
      DATE,
      "refusal.PROSE_DIRECTION_CONTROL",
    ],
    [
      "yes or no as anything else",
      "yes",
      YES_NO,
      "workflow.constantUnreadable",
    ],
    ["a value not written out", "{", FIELDS, "workflow.constantUnreadable"],
    [
      "text holding a direction control",
      "a" + DIRECTION_CONTROL + "b",
      TEXT,
      "refusal.PROSE_DIRECTION_CONTROL",
    ],
    [
      "a value holding a tag character deep in it",
      '{"said":["a","' + TAG + '"]}',
      FIELDS,
      "refusal.PROSE_TAG_CHARACTER",
    ],
    [
      "text holding a NUL",
      "a" + NUL + "b",
      TEXT,
      "refusal.CONSTANT_DOES_NOT_FIT",
    ],
    [
      "a member named with a NUL",
      '{"a' + BACKSLASH + 'u0000":"b"}',
      FIELDS,
      "refusal.CONSTANT_DOES_NOT_FIT",
    ],
    [
      "text of 8193 characters",
      "a".repeat(8193),
      TEXT,
      "refusal.CONSTANT_DOES_NOT_FIT",
    ],
    [
      "text past 8192 characters in all, many held together",
      JSON.stringify(["a".repeat(4096), "a".repeat(4097)]),
      { ...TEXT, many: true },
      "refusal.CONSTANT_DOES_NOT_FIT",
    ],
    [
      "a number written out no double holds, where nothing is known",
      "[1" + "0".repeat(400) + "]",
      undefined,
      "refusal.CONSTANT_DOES_NOT_FIT",
    ],
    [
      "a number written out past 38 digits beyond its point, where nothing is known",
      "[-0." + "0".repeat(38) + "1]",
      undefined,
      "refusal.CONSTANT_DOES_NOT_FIT",
    ],
    [
      "a number written out past 38 digits beyond its point, its zeros counted, where nothing is known",
      "[1." + "0".repeat(39) + "]",
      undefined,
      "refusal.CONSTANT_DOES_NOT_FIT",
    ],
    [
      "a number written out past 38 digits before its point, where nothing is known",
      "[1" + "0".repeat(38) + "]",
      undefined,
      "refusal.CONSTANT_DOES_NOT_FIT",
    ],
    [
      "many numbers as far as 38 digits either side of their point, past 38 in all as one number is counted",
      EITHER_SIDE_38,
      NUMBERS,
      "workflow.numberPastDigits",
    ],
    [
      "many numbers, one of 39 digits before its point",
      "[1, 1" + "0".repeat(38) + "]",
      NUMBERS,
      "workflow.numberPastDigits",
    ],
    [
      "many numbers, one past 38 digits and one with an exponent, the exponent named first",
      "[1" + "0".repeat(38) + ", 1e5]",
      NUMBERS,
      "workflow.numberUnreadable",
    ],
    [
      "a number written out with an exponent, whatever it is worth",
      "[1.0e1]",
      NUMBERS,
      "workflow.numberUnreadable",
    ],
    [
      "a number deep in what is written out with an exponent",
      '{"said":[15E-1]}',
      FIELDS,
      "workflow.numberUnreadable",
    ],
    [
      "a zero written out with a minus sign, not taken for the zero it is worth",
      "[1, -0.00]",
      NUMBERS,
      "workflow.numberUnreadable",
    ],
    [
      "a number with an exponent, named before a direction control",
      '["' + DIRECTION_CONTROL + '",1e5]',
      { ...TEXT, many: true },
      "workflow.numberUnreadable",
    ],
    [
      "a direction control, named before a number past 38 digits among many",
      '["' + DIRECTION_CONTROL + '", 1' + "0".repeat(38) + "]",
      NUMBERS,
      "refusal.PROSE_DIRECTION_CONTROL",
    ],
    [
      "many numbers holding one written as text",
      '["5"]',
      NUMBERS,
      "workflow.numberUnreadable",
    ],
    [
      "no numbers at all where they must be given",
      "[]",
      NUMBERS,
      "workflow.numberUnreadable",
    ],
    [
      "a date typed as null where it must be given",
      "null",
      DATE,
      "workflow.dateUnreadable",
    ],
  ] as const)("refuses %s, sending nothing", (_case, typed, target, why) => {
    expect(constantRead(typed, target)).toEqual({ why });
  });

  it("names what a model may not be sent before anything the value is too large for", () => {
    expect(constantRead(DIRECTION_CONTROL + "a".repeat(8193), TEXT)).toEqual({
      why: "refusal.PROSE_DIRECTION_CONTROL",
    });
  });
});

/** The page's half of the rules a constant and a term are held to, run over the table the server's half is. */
describe("the table shared with the server", () => {
  it("holds cases for every rule, an empty table agreeing with anything", () => {
    expect(CASES.written.length).toBeGreaterThan(0);
    expect(CASES.text.length).toBeGreaterThan(0);
    expect(CASES.one.length).toBeGreaterThan(0);
    expect(CASES.many.length).toBeGreaterThan(0);
    expect(CASES.term.length).toBeGreaterThan(0);
  });

  it.each(CASES.written as readonly Case[])(
    "takes a constant written out as $typed exactly where the table says: $accepted",
    (each) => {
      expect("written" in constantRead(typedIn(each), undefined)).toBe(
        each.accepted,
      );
    },
  );

  it.each(CASES.text as readonly Case[])(
    "takes text of $typed $times times exactly where the table says: $accepted",
    (each) => {
      expect("written" in constantRead(typedIn(each), TEXT)).toBe(
        each.accepted,
      );
    },
  );

  it.each(demanded(CASES.one as readonly Case[]))(
    "takes one $kind typed as $typed $times times, must be given $mustBeGiven, exactly where the table says: $accepted",
    (each) => {
      expect(
        "written" in
          constantRead(typedIn(each), {
            ...TEXT,
            kind: each.kind!,
            mustBeGiven: each.mustBeGiven,
          }),
      ).toBe(each.accepted);
    },
  );

  it.each(demanded(CASES.many as readonly Case[]))(
    "takes many numbers written out as $typed, must be given $mustBeGiven, exactly where the table says: $accepted",
    (each) => {
      expect(
        "written" in
          constantRead(typedIn(each), {
            ...NUMBERS,
            mustBeGiven: each.mustBeGiven,
          }),
      ).toBe(each.accepted);
    },
  );

  it.each(CASES.term as readonly Case[])(
    "takes a case's term $typed exactly where the table says: $accepted",
    (each) => {
      expect(termFits(typedIn(each))).toBe(each.accepted);
    },
  );
});

/** One value of a kind a constant is judged as a value filled in is, over the table that holds that judgement. */
describe("the table a value filled in is held to", () => {
  const KINDS = ["number", "date", "moment", "yes_no"] as const;

  const ROWS = KINDS.flatMap((kind) =>
    (
      WRITING[kind] as readonly {
        readonly typed: string;
        readonly times?: number;
        readonly refused: FillReason | null;
      }[]
    ).map((row) => ({ kind, ...row })),
  );

  const TAKEN = ROWS.filter((row) => row.refused === null);

  const REFUSED = ROWS.filter((row) => row.refused !== null);

  /** A number the table refuses is named past its digits where it is written plainly, and unreadable where not. */
  const UNREADABLE: Readonly<Record<(typeof KINDS)[number], MessageId>> = {
    number: "workflow.numberUnreadable",
    date: "workflow.dateUnreadable",
    moment: "workflow.momentUnreadable",
    yes_no: "workflow.constantUnreadable",
  };

  it("holds cases taken and refused of every kind judged so, an empty table agreeing with anything", () => {
    expect(new Set(TAKEN.map((row) => row.kind))).toEqual(new Set(KINDS));
    expect(new Set(REFUSED.map((row) => row.kind))).toEqual(new Set(KINDS));
  });

  it.each(TAKEN)(
    "takes one $kind typed as $typed $times times, as a value filled in is taken",
    (row) => {
      const typed = typedIn(row);

      expect(constantRead(typed, { ...TEXT, kind: row.kind })).toEqual({
        written:
          row.kind === "date" || row.kind === "moment"
            ? JSON.stringify(typed)
            : typed,
      });
    },
  );

  it.each(REFUSED)(
    "refuses one $kind typed as $typed $times times, as a value filled in is refused for $refused",
    (row) => {
      const typed = typedIn(row);

      expect(constantRead(typed, { ...TEXT, kind: row.kind })).toEqual({
        why:
          row.kind === "number" && numberWrittenPlainly(typed)
            ? "workflow.numberPastDigits"
            : UNREADABLE[row.kind],
      });
    },
  );
});
