import { describe, expect, it } from "vitest";

import type { FieldKind } from "../../api/declaration";
import type { FillReason } from "../../api/filling";
import CASES from "./writing.cases.json";
import {
  codePointsIn,
  holdsNothing,
  valueRefused,
  type WrittenField,
} from "./writing";

type WrittenKind = Exclude<FieldKind, "fields">;

/**
 * The page's half of how a value is written, run over the one table the server's half is run over too
 * (`FillingTypedIntegrationSpec`), each row as the one field that must be given.
 */
interface Case {
  readonly typed: string;
  readonly times?: number;
  readonly longest?: number;
  readonly refused: FillReason | null;
}

const TABLE: Readonly<Record<WrittenKind, readonly Case[]>> = CASES as Record<
  WrittenKind,
  readonly Case[]
>;

const KINDS = Object.keys(TABLE) as WrittenKind[];

const ROWS = KINDS.flatMap((kind) =>
  TABLE[kind].map((each) => ({ kind, ...each })),
);

function fieldOf(
  row: Case & { readonly kind: WrittenKind },
  mustBeGiven: boolean,
): WrittenField {
  return {
    name: "value",
    kind: row.kind,
    mustBeGiven,
    ...(row.longest === undefined ? {} : { longest: row.longest }),
    ...(row.kind === "term"
      ? {
          terms: {
            terms: [
              { term: "Billing", meaning: "A charge is disputed." },
              { term: "Delivery", meaning: "It came late." },
            ],
          },
        }
      : {}),
  };
}

function typedIn(row: Case): string {
  return row.typed.repeat(row.times ?? 1);
}

describe("holdsNothing", () => {
  it.each(ROWS)(
    "reads %j as no value exactly where the table finds it missing",
    (row) => {
      expect(holdsNothing(typedIn(row))).toBe(row.refused === "missing");
    },
  );
});

describe("valueRefused", () => {
  it("holds cases of every kind one value is written as, an empty table agreeing with anything", () => {
    expect(KINDS.toSorted()).toEqual(
      ["date", "moment", "number", "term", "text", "yes_no"].toSorted(),
    );
    for (const kind of KINDS) {
      expect(TABLE[kind].length).toBeGreaterThan(0);
    }
  });

  it.each(ROWS)(
    "refuses %j, where it must be given, as the server does",
    (row) => {
      expect(valueRefused(fieldOf(row, true), typedIn(row))).toBe(row.refused);
    },
  );

  it.each(ROWS)(
    "takes %j as no value where it need not be given exactly where it is missing where it must",
    (row) => {
      expect(valueRefused(fieldOf(row, false), typedIn(row))).toBe(
        row.refused === "missing" ? null : row.refused,
      );
    },
  );

  // Out of the table because the reader's JSON loader refuses half a pair outright.
  it("refuses text holding half a pair as unusable, and the whole pair as nothing", () => {
    const field = fieldOf(
      { kind: "text", typed: "", refused: null, longest: 20 },
      true,
    );

    expect(valueRefused(field, `Half${String.fromCharCode(0xd83d)}`)).toBe(
      "unusable",
    );
    expect(valueRefused(field, `Whole${String.fromCodePoint(0x1f600)}`)).toBe(
      null,
    );
  });
});

describe("codePointsIn", () => {
  it("counts a character beyond the basic plane once, and each half of a broken pair once", () => {
    const grinning = String.fromCodePoint(0x1f600);
    const halfPair = String.fromCharCode(0xd83d);

    expect(codePointsIn(`a${grinning}b`)).toBe(3);
    expect(codePointsIn(`${halfPair}a`)).toBe(2);
    expect(codePointsIn("")).toBe(0);
  });
});
