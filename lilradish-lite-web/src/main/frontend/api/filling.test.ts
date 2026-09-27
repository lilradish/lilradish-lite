import { describe, expect, it } from "vitest";

import {
  fillFieldFrom,
  fillFieldsFrom,
  fillProblemsIn,
  isFillValue,
  pathKey,
  readFieldsFrom,
} from "./filling";

/** Every member a field can carry, held inside fields that hold many. */
const CATEGORY = {
  name: "category",
  label: "Category",
  help: "What the claim is about.",
  kind: "term",
  mustBeGiven: true,
  terms: {
    terms: [
      { term: "Billing", meaning: "A charge is what is disputed." },
      { term: "Delivery", meaning: "It came late or not at all." },
    ],
    note: "Pick the one the customer named first.",
  },
};

const COMPLAINT = {
  name: "complaint",
  kind: "text",
  longest: 2000,
  mustBeGiven: true,
};

/** Of a kind no build has heard of, shaped as a field of that kind would be: it says nothing of how long. */
const SHADE = { name: "shade", kind: "colour", mustBeGiven: false };

const CONTACTS = {
  name: "contacts",
  kind: "fields",
  most: 3,
  mustBeGiven: false,
  fields: [CATEGORY, COMPLAINT],
};

const REFUSED = (problems: unknown, problemsFound: unknown = 1) => ({
  status: 400,
  code: "VALUE_DOES_NOT_FIT",
  extensions: { problems, problemsFound },
});

describe("fillFieldFrom", () => {
  it("reads every member a field carries, and the fields it holds in the order sent", () => {
    expect(fillFieldFrom(CONTACTS)).toEqual(CONTACTS);
  });

  it("leaves out what did not arrive rather than reading it as nothing", () => {
    const read = fillFieldFrom(COMPLAINT);

    expect(read).toEqual(COMPLAINT);
    expect(Object.keys(read!).toSorted()).toEqual(
      Object.keys(COMPLAINT).toSorted(),
    );
  });

  it("reads terms saying nothing of choosing among them without a note", () => {
    const quiet = {
      ...CATEGORY,
      terms: { terms: CATEGORY.terms.terms },
    };

    expect(fillFieldFrom(quiet)?.terms).toEqual({
      terms: CATEGORY.terms.terms,
    });
  });

  it.each([
    ["no name", { ...COMPLAINT, name: undefined }],
    ["a kind never heard of", SHADE],
    [
      "fields holding one of a kind never heard of",
      { ...CONTACTS, fields: [COMPLAINT, SHADE] },
    ],
    ["no word on whether it must be given", { ...COMPLAINT, mustBeGiven: 1 }],
    ["a label that is no text", { ...COMPLAINT, label: 5 }],
    ["a most that is no count", { ...COMPLAINT, most: "3" }],
    ["text saying nothing of how long", { ...COMPLAINT, longest: undefined }],
    ["how long, on what is not text", { ...CATEGORY, longest: 10 }],
    ["a term offering nothing to choose", { ...CATEGORY, terms: undefined }],
    ["terms, on what is not a term", { ...COMPLAINT, terms: CATEGORY.terms }],
    [
      "a term whose meaning is missing",
      { ...CATEGORY, terms: { terms: [{ term: "Billing" }] } },
    ],
    [
      "a term offering an empty list to choose from",
      { ...CATEGORY, terms: { terms: [] } },
    ],
    ["fields holding nothing", { ...CONTACTS, fields: [] }],
    [
      "fields holding two called alike",
      { ...CONTACTS, fields: [COMPLAINT, { ...CATEGORY, name: "complaint" }] },
    ],
    ["fields, on what is not fields", { ...COMPLAINT, fields: [CATEGORY] }],
    [
      "fields holding one it cannot read",
      { ...CONTACTS, fields: [{ ...CATEGORY, kind: 1 }] },
    ],
  ])("refuses a field with %s", (_case, body) => {
    expect(fillFieldFrom(body)).toBeNull();
  });
});

describe("fillFieldsFrom", () => {
  it("reads a level of fields in the order sent", () => {
    expect(fillFieldsFrom([CONTACTS, COMPLAINT])).toEqual([
      CONTACTS,
      COMPLAINT,
    ]);
  });

  it.each([
    [
      "two fields called alike",
      [COMPLAINT, { ...CONTACTS, name: "complaint" }],
    ],
    ["a field it cannot read", [COMPLAINT, { ...CATEGORY, kind: 1 }]],
    ["no list at all", { complaint: COMPLAINT }],
  ])("refuses a level with %s", (_case, body) => {
    expect(fillFieldsFrom(body)).toBeNull();
  });
});

describe("readFieldsFrom", () => {
  it("reads a field of a kind never heard of as it came, at any level, beside those it knows", () => {
    const level = [SHADE, { ...CONTACTS, fields: [CATEGORY, SHADE] }];

    expect(readFieldsFrom(level)).toEqual(level);
    expect(fillFieldsFrom(level)).toBeNull();
  });

  it.each([
    ["how long", { ...SHADE, longest: 10 }],
    ["terms", { ...SHADE, terms: CATEGORY.terms }],
    ["fields of its own", { ...SHADE, fields: [COMPLAINT, SHADE] }],
  ])(
    "reads a kind never heard of carrying %s as it came, which it cannot put to anybody",
    (_case, body) => {
      expect(readFieldsFrom([body])).toEqual([body]);
      expect(fillFieldFrom(body)).toBeNull();
    },
  );

  it.each([
    ["two fields called alike", [SHADE, { ...COMPLAINT, name: "shade" }]],
    [
      "a kind never heard of holding two fields called alike",
      [{ ...SHADE, fields: [COMPLAINT, COMPLAINT] }],
    ],
    ["a kind that is no word", [{ ...SHADE, kind: 1 }]],
    ["fields holding nothing", [{ ...CONTACTS, fields: [] }]],
  ])("refuses a level with %s", (_case, body) => {
    expect(readFieldsFrom(body)).toBeNull();
  });
});

describe("fillProblemsIn", () => {
  it("reads every place listed, a field by name and a place among many by number, in the order listed, and how many were found", () => {
    const problems = [
      { path: ["contacts", 0, "category"], reason: "not_a_term" },
      { path: ["complaint"], reason: "missing" },
    ];

    expect(fillProblemsIn(REFUSED(problems, 2))).toEqual({
      listed: problems,
      found: 2,
    });
  });

  it("reads more found than listed as found, listing only what was listed", () => {
    const problems = [{ path: ["complaint"], reason: "missing" }];

    expect(fillProblemsIn(REFUSED(problems, 240))).toEqual({
      listed: problems,
      found: 240,
    });
  });

  it("reads nothing out of another refusal, whatever it carries", () => {
    expect(
      fillProblemsIn({
        status: 400,
        code: "BODY_UNUSABLE",
        extensions: { problems: [{ path: ["a"], reason: "missing" }] },
      }),
    ).toBeNull();
  });

  it("reads nothing out of a code minted here that is spelt like the server's", () => {
    expect(
      fillProblemsIn({
        code: "VALUE_DOES_NOT_FIT",
        extensions: { problems: [{ path: ["a"], reason: "missing" }] },
      }),
    ).toBeNull();
  });

  it.each([
    ["no places at all", undefined],
    ["an empty list of places", []],
    ["a reason never heard of", [{ path: ["a"], reason: "too_blue" }]],
    [
      "a path that starts at a place among many",
      [{ path: [0], reason: "missing" }],
    ],
    ["an empty path", [{ path: [], reason: "missing" }]],
    ["a place counted below nought", [{ path: ["a", -1], reason: "missing" }]],
    [
      "a place that is no whole number",
      [{ path: ["a", 0.5], reason: "missing" }],
    ],
    [
      "one place readable and one not",
      [
        { path: ["a"], reason: "missing" },
        { path: "a", reason: "missing" },
      ],
    ],
  ])("reads nothing out of a refusal naming %s", (_case, problems) => {
    expect(fillProblemsIn(REFUSED(problems))).toBeNull();
  });

  it.each([
    ["no count of what was found", undefined],
    ["a count that is no whole number", 1.5],
    ["a count written as text", "2"],
    ["fewer found than listed", 1],
  ])("reads nothing out of a refusal saying %s", (_case, problemsFound) => {
    const problems = [
      { path: ["a"], reason: "missing" },
      { path: ["b"], reason: "missing" },
    ];

    expect(fillProblemsIn(REFUSED(problems, problemsFound))).toBeNull();
  });
});

describe("isFillValue", () => {
  it.each([
    ["text", "A printer fire."],
    ["none", null],
    ["many", ["a", null]],
    ["fields, one of many within them", { name: "Ada", phones: ["1", "2"] }],
    ["fields of many", [{ name: "Ada" }, { name: "Grace" }]],
    ["no fields", {}],
  ])("takes %s", (_case, said) => {
    expect(isFillValue(said)).toBe(true);
  });

  it.each([
    ["a number, which is sent as its digits", 5],
    ["yes sent as a flag, not written", true],
    ["nothing at all", undefined],
    ["a number deep within fields", { items: [{ count: 3 }] }],
  ])("refuses %s", (_case, said) => {
    expect(isFillValue(said)).toBe(false);
  });
});

describe("pathKey", () => {
  it("keys two paths to one place alike and a name apart from a place of the same digits", () => {
    const path = ["contacts", 0];
    const samePlace = ["contacts", 0];

    expect(pathKey(path)).toBe(pathKey(samePlace));
    expect(pathKey(path)).not.toBe(pathKey(["contacts", "0"]));
  });
});
