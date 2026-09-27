import { describe, expect, it } from "vitest";

import { orderAsked, orderSpelled, type Ordering } from "./ordering";

type Column = "userId" | "displayName" | "groupCount";

const SORTABLE = {
  userId: true,
  displayName: true,
  groupCount: true,
} satisfies Record<Column, true>;

const OPENING: Ordering<Column> = { column: "userId", descending: false };

describe("orderAsked", () => {
  it.each([
    [null, "userId", false],
    ["userId", "userId", false],
    ["-userId", "userId", true],
    ["displayName", "displayName", false],
    ["-groupCount", "groupCount", true],
  ] as const)("reads %o as %s, descending %s", (sort, column, descending) => {
    expect(orderAsked(sort, SORTABLE, OPENING)).toEqual({ column, descending });
  });

  /** An address is the reader's to edit, and none of these is a column the table has. */
  it.each([
    "",
    "-",
    "--userId",
    "estateRoles",
    "UserId",
    "toString",
    "-constructor",
    "userId ",
    "userId,displayName",
  ])("reads %o as the order the list opens in", (sort) => {
    expect(orderAsked(sort, SORTABLE, OPENING)).toBe(OPENING);
  });
});

describe("orderSpelled", () => {
  it.each([
    [{ column: "userId", descending: false }, "userId"],
    [{ column: "displayName", descending: true }, "-displayName"],
    [{ column: "groupCount", descending: true }, "-groupCount"],
  ] as const)("spells %o as %s, as the server takes it", (order, spelled) => {
    expect(orderSpelled(order)).toBe(spelled);
  });

  it.each([
    { column: "userId", descending: true },
    { column: "groupCount", descending: false },
  ] as const)(
    "spells %o so that reading it back asks for it again",
    (order) => {
      expect(orderAsked(orderSpelled(order), SORTABLE, OPENING)).toEqual(order);
    },
  );
});
