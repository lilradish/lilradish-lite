import { describe, expect, it } from "vitest";

import { declaredFrom, listVersionFrom, MOST_FILE_TYPES } from "./declaration";

const PINNED = {
  name: "Categories",
  versionId: "v1",
  number: 1,
  standing: "in_service",
  newer: { versionId: "v2", number: 2 },
};

const CATEGORY = {
  fieldId: "f1",
  name: "category",
  label: "Category",
  help: "What is to be put right.",
  kind: "term",
  list: PINNED,
  many: true,
  most: 2,
  mustBeGiven: true,
  stands: "above_confidence",
  floor: 80,
};

const SENDER = {
  fieldId: "f2",
  name: "sender",
  kind: "fields",
  many: false,
  mustBeGiven: false,
  fields: [
    {
      fieldId: "f3",
      name: "name",
      kind: "text",
      longest: 128,
      many: false,
      mustBeGiven: true,
    },
  ],
};

const FILE_TYPES_64 = Array.from(
  { length: MOST_FILE_TYPES },
  (_, index) => `application/x${index}`,
);

describe("declaredFrom", () => {
  it("reads every member of a field that arrived, and the fields it holds", () => {
    expect(declaredFrom(CATEGORY)).toEqual(CATEGORY);
    expect(declaredFrom(SENDER)).toEqual(SENDER);
  });

  it.each(["one", "several"])("reads %s lines at every text depth", (lines) => {
    const text = { ...SENDER.fields[0], lines };
    expect(declaredFrom(text)).toEqual(text);
    expect(declaredFrom({ ...SENDER, fields: [text] })?.fields).toEqual([text]);
    expect(declaredFrom({ ...text, lines: "later" })).toBeNull();
    expect(declaredFrom({ ...CATEGORY, lines })).toBeNull();
  });

  it("reads an ordered file type list and byte limit", () => {
    const declared = {
      fieldId: "f4",
      name: "documents",
      kind: "file",
      types: ["application/pdf", "image/png"],
      maximumBytes: 1048576,
      many: true,
      most: 3,
      mustBeGiven: true,
    };
    expect(declaredFrom(declared)).toEqual(declared);
  });

  it("reads exactly sixty-four types and the largest exact byte count, and refuses either one past", () => {
    const file = {
      fieldId: "f4",
      name: "documents",
      kind: "file",
      types: FILE_TYPES_64,
      maximumBytes: Number.MAX_SAFE_INTEGER,
      many: false,
      mustBeGiven: false,
    };

    expect(declaredFrom(JSON.parse(JSON.stringify(file)))).toEqual(file);
    expect(
      declaredFrom({ ...file, types: [...FILE_TYPES_64, "application/extra"] }),
    ).toBeNull();
    expect(
      declaredFrom({ ...file, maximumBytes: Number.MAX_SAFE_INTEGER + 1 }),
    ).toBeNull();
  });

  it("reads a file draft without a byte limit but refuses a limit outside positive safe bytes", () => {
    const file = {
      fieldId: "f4",
      name: "documents",
      kind: "file",
      types: ["application/pdf"],
      many: false,
      mustBeGiven: false,
    };
    expect(declaredFrom(file)).toEqual(file);
    for (const maximumBytes of [0, -1, 1.5, Number.MAX_SAFE_INTEGER + 1]) {
      expect(declaredFrom({ ...file, maximumBytes })).toBeNull();
    }
    expect(declaredFrom({ ...file, maximumBytes: 1 })).toEqual({
      ...file,
      maximumBytes: 1,
    });
    expect(
      declaredFrom({ ...file, maximumBytes: Number.MAX_SAFE_INTEGER }),
    ).toEqual({ ...file, maximumBytes: Number.MAX_SAFE_INTEGER });
    expect(declaredFrom({ ...file, kind: "text" })).toBeNull();
    expect(declaredFrom({ ...CATEGORY, maximumBytes: 1 })).toBeNull();
  });

  it.each([
    ["missing types", { maximumBytes: 1048576 }],
    ["empty types", { types: [], maximumBytes: 1048576 }],
    [
      "duplicate types",
      { types: ["image/png", "image/png"], maximumBytes: 1048576 },
    ],
    [
      "parameterized type",
      { types: ["image/png; charset=utf-8"], maximumBytes: 1048576 },
    ],
    ["zero size", { types: ["image/png"], maximumBytes: 0 }],
  ])("refuses a file field with %s", (_case, limits) => {
    expect(
      declaredFrom({
        fieldId: "f4",
        name: "documents",
        kind: "file",
        many: false,
        mustBeGiven: false,
        ...limits,
      }),
    ).toBeNull();
  });

  /** Absent stays absent rather than arriving as undefined, which `in` would tell apart. */
  it("leaves out every member that did not arrive", () => {
    const read = declaredFrom({
      fieldId: "f4",
      name: "urgent",
      kind: "yes_no",
      many: false,
      mustBeGiven: false,
    })!;

    expect(Object.keys(read).toSorted()).toEqual([
      "fieldId",
      "kind",
      "many",
      "mustBeGiven",
      "name",
    ]);
  });

  it.each([
    ["a kind this build does not know", { ...CATEGORY, kind: "prose" }],
    [
      "a standing this build does not know",
      { ...CATEGORY, stands: "sometimes" },
    ],
    ["no key", { ...CATEGORY, fieldId: undefined }],
    ["a limit that is no whole number", { ...CATEGORY, most: 1.5 }],
    ["a floor that is text", { ...CATEGORY, floor: "80" }],
    ["a label that is null", { ...CATEGORY, label: null }],
    [
      "a list of no standing",
      { ...CATEGORY, list: { ...PINNED, standing: 1 } },
    ],
    [
      "a newer list of no number",
      { ...CATEGORY, list: { ...PINNED, newer: { versionId: "v2" } } },
    ],
    ["a held field that is none", { ...SENDER, fields: ["name"] }],
    ["whether it must be given as text", { ...SENDER, mustBeGiven: "no" }],
    [
      "no word of whether it must be given",
      { ...SENDER, mustBeGiven: undefined },
    ],
    ["no field at all", "category"],
  ])("reads nothing out of a field with %s", (_case, body) => {
    expect(declaredFrom(body)).toBeNull();
  });
});

describe("listVersionFrom", () => {
  it("reads a list version by its name, identifier and number, or nothing of one short of any", () => {
    const list = {
      name: "Categories",
      versionId: "v1",
      number: 1,
    };

    expect(listVersionFrom(list)).toEqual(list);
    expect(listVersionFrom({ ...list, number: "1" })).toBeNull();
    expect(listVersionFrom({ ...list, number: 0 })).toBeNull();
    expect(listVersionFrom({ ...list, number: -1 })).toBeNull();
    expect(listVersionFrom({ ...list, name: undefined })).toBeNull();
  });

  it("keeps an unknown standing on a pinned list but refuses a bad version number", () => {
    const pinned = { ...PINNED, standing: "withdrawn_later" };
    expect(declaredFrom({ ...CATEGORY, list: pinned })?.list).toEqual(pinned);
    expect(
      declaredFrom({
        ...CATEGORY,
        list: { ...PINNED, newer: { versionId: "v2", number: 0 } },
      }),
    ).toBeNull();
  });
});
