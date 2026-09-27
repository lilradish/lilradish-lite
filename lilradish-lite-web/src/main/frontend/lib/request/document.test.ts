import { describe, expect, it } from "vitest";

import {
  countFrom,
  isUnchecked,
  listOf,
  optional,
  present,
  textFrom,
  wordsIn,
} from "./document";

function positive(value: unknown): number | null {
  return typeof value === "number" && value > 0 ? value : null;
}

describe("optional", () => {
  it.each([
    ["left out", {}, undefined],
    ["arrived and read", { count: 3 }, 3],
    ["arrived and unreadable", { count: -1 }, null],
    ["arrived as null, which is not leaving it out", { count: null }, null],
  ])("answers a member %s", (_case, body, read) => {
    expect(optional(body, "count", positive)).toBe(read);
  });
});

describe("listOf", () => {
  it("reads every item of a list, an empty one included", () => {
    expect(listOf([1, 2], positive)).toEqual([1, 2]);
    expect(listOf([], positive)).toEqual([]);
  });

  it.each([
    ["no list", { items: [1] }],
    ["a list with one item unreadable", [1, -2, 3]],
  ])("reads %s as nothing", (_case, listed) => {
    expect(listOf(listed, positive)).toBeNull();
  });
});

describe("isUnchecked", () => {
  it("takes an object with members as a document", () => {
    expect(isUnchecked({ items: [] })).toBe(true);
    expect(isUnchecked({})).toBe(true);
  });

  it.each([null, undefined, [], ["items"], "items", 7, true])(
    "takes %o as no document at all",
    (value) => {
      expect(isUnchecked(value)).toBe(false);
    },
  );
});

describe("textFrom", () => {
  it.each([
    ["", ""],
    ["said", "said"],
    [7, null],
    [null, null],
  ])("keeps %j as text only where it is text", (said, read) => {
    expect(textFrom(said)).toBe(read);
  });
});

describe("countFrom", () => {
  it.each([
    [0, 0],
    [Number.MAX_SAFE_INTEGER, Number.MAX_SAFE_INTEGER],
    [Number.MAX_SAFE_INTEGER + 1, null],
    [2.5, null],
    ["2", null],
  ])(
    "keeps %j only where a double holds it as a whole number",
    (said, read) => {
      expect(countFrom(said)).toBe(read);
    },
  );
});

describe("present", () => {
  it("keeps what arrived, and leaves out what did not rather than holding it as undefined", () => {
    const kept = present({ said: "a", counted: 0, missing: undefined });

    expect(kept).toEqual({ said: "a", counted: 0 });
    expect(Object.hasOwn(kept, "missing")).toBe(false);
  });
});

describe("wordsIn", () => {
  it("keeps every word listed, one nobody here knows included, and drops what is no word", () => {
    expect(wordsIn(["watcher", "auditor", 7, null, "steward"])).toEqual([
      "watcher",
      "auditor",
      "steward",
    ]);
  });

  it("reads an empty list as listing nothing, which is an answer", () => {
    expect(wordsIn([])).toEqual([]);
  });

  /**
   * A document short of a list is not a list of nothing: the first is a
   * document missing a member, the second an answer.
   */
  it.each([undefined, null, "steward", { steward: true }])(
    "reads %o as no list at all rather than as listing nothing",
    (listed) => {
      expect(wordsIn(listed)).toBeNull();
    },
  );
});
