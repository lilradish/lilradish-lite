import { describe, expect, it } from "vitest";

import { addedFrom, declaredFrom, listVersionFrom } from "./declaration";

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

describe("declaredFrom", () => {
  it("reads every member of a field that arrived, and the fields it holds", () => {
    expect(declaredFrom(CATEGORY)).toEqual(CATEGORY);
    expect(declaredFrom(SENDER)).toEqual(SENDER);
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

describe("addedFrom", () => {
  const ADDED = {
    name: "category",
    kind: "term",
    many: true,
    most: 2,
    mustBeGiven: true,
    terms: [{ term: "Billing", meaning: "A charge." }],
    note: "Choose one.",
    confidence: true,
    fields: [
      {
        name: "sku",
        kind: "text",
        longest: 32,
        many: false,
        mustBeGiven: false,
        confidence: false,
      },
    ],
  };

  it("reads every member of what a model is told that arrived, and leaves out the rest", () => {
    expect(addedFrom(ADDED)).toEqual(ADDED);
    expect(
      Object.keys(
        addedFrom({
          name: "n",
          kind: "date",
          many: false,
          mustBeGiven: false,
          confidence: false,
        })!,
      ).toSorted(),
    ).toEqual(["confidence", "kind", "many", "mustBeGiven", "name"]);
  });

  it.each([
    [
      "no word on whether it must be given",
      { ...ADDED, mustBeGiven: undefined },
    ],
    ["no word on a confidence", { ...ADDED, confidence: undefined }],
    ["a term with no meaning", { ...ADDED, terms: [{ term: "Billing" }] }],
    ["a kind this build does not know", { ...ADDED, kind: "prose" }],
    ["a note that is no text", { ...ADDED, note: 3 }],
  ])("reads nothing out of a field with %s", (_case, body) => {
    expect(addedFrom(body)).toBeNull();
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
    expect(listVersionFrom({ ...list, name: undefined })).toBeNull();
  });
});
