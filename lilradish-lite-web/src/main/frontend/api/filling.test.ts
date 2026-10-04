import { describe, expect, it } from "vitest";

import {
  fillFieldFrom,
  fillFieldsFrom,
  fillProblemsIn,
  isDownloadableFileValue,
  isFillValue,
  isShownValue,
  isWithheldLeaf,
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

const FILE = {
  name: "attachment",
  kind: "file",
  mustBeGiven: true,
  types: ["application/pdf", "image/png"],
  maximumBytes: 1048576,
};

const FILE_VALUE = {
  id: "file-one",
  name: "Demo Sample.pdf",
  type: "application/pdf",
  size: 42,
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

  it.each(["one", "several"])(
    "reads %s lines in a form and a saved declaration",
    (lines) => {
      const text = { ...COMPLAINT, lines };
      expect(fillFieldFrom(text)).toEqual(text);
      expect(readFieldsFrom([{ ...CONTACTS, fields: [text] }])).toEqual([
        { ...CONTACTS, fields: [text] },
      ]);
      expect(fillFieldFrom({ ...text, lines: "later" })).toBeNull();
      expect(fillFieldFrom({ ...CATEGORY, lines })).toBeNull();
    },
  );

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

  it("reads file limits at the top level and inside fields holding many", () => {
    const nested = { ...CONTACTS, fields: [FILE, COMPLAINT] };

    expect(readFieldsFrom([FILE, nested])).toEqual([FILE, nested]);
    expect(fillFieldFrom(FILE)).toEqual(FILE);
    expect(fillFieldsFrom([FILE, nested])).toEqual([FILE, nested]);
  });

  it("reads a syntactically valid type beyond the common examples", () => {
    const field = {
      ...FILE,
      types: ["application/vnd.demo-sample+json"],
      maximumBytes: 100_000_000,
      most: 100,
    };

    expect(readFieldsFrom([field])).toEqual([field]);
    expect(fillFieldFrom(field)).toEqual(field);
    expect(isFillValue({ ...FILE_VALUE, type: field.types[0] })).toBe(true);
  });

  it.each([
    ["no allowed types", { ...FILE, types: undefined }],
    ["empty allowed types", { ...FILE, types: [] }],
    ["duplicate allowed types", { ...FILE, types: ["image/png", "image/png"] }],
    [
      "case-variant duplicate types",
      { ...FILE, types: ["image/png", "IMAGE/PNG"] },
    ],
    ["media type missing its slash", { ...FILE, types: ["applicationpdf"] }],
    ["media type containing a space", { ...FILE, types: ["application/ pdf"] }],
    [
      "media type beginning with punctuation",
      { ...FILE, types: ["-application/pdf"] },
    ],
    ["non-text allowed type", { ...FILE, types: ["image/png", 1] }],
    ["no byte limit", { ...FILE, maximumBytes: undefined }],
    ["zero byte limit", { ...FILE, maximumBytes: 0 }],
    ["zero files allowed", { ...FILE, most: 0 }],
    ["negative byte limit", { ...FILE, maximumBytes: -1 }],
    ["fractional byte limit", { ...FILE, maximumBytes: 1.5 }],
    ["terms on a file", { ...FILE, terms: CATEGORY.terms }],
    ["text length on a file", { ...FILE, longest: 10 }],
    ["fields within a file", { ...FILE, fields: [COMPLAINT] }],
    ["file limits on text", { ...COMPLAINT, types: FILE.types }],
    ["file size on fields", { ...CONTACTS, maximumBytes: 100 }],
  ])("refuses %s", (_case, body) => {
    expect(readFieldsFrom([body])).toBeNull();
    expect(fillFieldFrom(body)).toBeNull();
  });

  it("refuses a malformed file nested among valid fields", () => {
    const nested = { ...CONTACTS, fields: [COMPLAINT, { ...FILE, types: [] }] };

    expect(readFieldsFrom([nested])).toBeNull();
    expect(fillFieldsFrom([nested])).toBeNull();
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

  it("keeps a line break refusal and its nested place without confusing it with a missing value", () => {
    const problems = [
      { path: ["contacts", 1, "complaint"], reason: "line_break" },
    ];
    expect(fillProblemsIn(REFUSED(problems))).toEqual({
      listed: problems,
      found: 1,
    });
    expect(
      fillProblemsIn(REFUSED([{ ...problems[0], reason: "later" }])),
    ).toBeNull();
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
    ["fields, one of many within them", { name: "Avo", phones: ["1", "2"] }],
    ["fields of many", [{ name: "Avo" }, { name: "Glinn" }]],
    ["no fields", {}],
    [
      "fields named like file metadata",
      { id: "one", name: "Demo Sample", type: "plain" },
    ],
    [
      "four text fields named like file metadata",
      { id: "one", name: "Demo Sample", type: "plain", size: "large" },
    ],
  ])("takes %s", (_case, said) => {
    expect(isFillValue(said)).toBe(true);
  });

  it("takes file metadata alone, among many and within fields", () => {
    expect(isFillValue(FILE_VALUE)).toBe(true);
    expect(
      isFillValue([FILE_VALUE, { ...FILE_VALUE, id: "file-two", size: 0 }]),
    ).toBe(true);
    expect(isFillValue({ details: { attachments: [FILE_VALUE] } })).toBe(true);
  });

  it.each([
    ["missing id", { name: FILE_VALUE.name, type: FILE_VALUE.type, size: 42 }],
    ["missing name", { id: FILE_VALUE.id, type: FILE_VALUE.type, size: 42 }],
    ["missing type", { id: FILE_VALUE.id, name: FILE_VALUE.name, size: 42 }],
    ["empty id", { ...FILE_VALUE, id: "" }],
    ["empty name", { ...FILE_VALUE, name: "" }],
    ["name on two lines", { ...FILE_VALUE, name: "Demo\nSample.pdf" }],
    ["name beyond the limit", { ...FILE_VALUE, name: "A".repeat(256) }],
    ["malformed type", { ...FILE_VALUE, type: "application//pdf" }],
    ["negative size", { ...FILE_VALUE, size: -1 }],
    ["fractional size", { ...FILE_VALUE, size: 1.5 }],
    ["unexpected member", { ...FILE_VALUE, content: "private" }],
  ])("refuses file metadata with %s", (_case, said) => {
    expect(isFillValue(said)).toBe(false);
    expect(isFillValue({ nested: [said] })).toBe(false);
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

describe("isDownloadableFileValue", () => {
  const downloadable = {
    ...FILE_VALUE,
    id: "0000000f-0000-4000-8000-000000000001",
  };

  it("accepts canonical file metadata with an identifier the download route can read", () => {
    expect(isDownloadableFileValue(downloadable)).toBe(true);
    expect(isDownloadableFileValue({ ...downloadable, id: "not-a-uuid" })).toBe(
      false,
    );
  });

  it.each([
    ["an unusable name", { ...downloadable, name: "Demo\nSample.pdf" }],
    ["an unusable type", { ...downloadable, type: "application//pdf" }],
    ["a negative size", { ...downloadable, size: -1 }],
    ["an extra path", { ...downloadable, path: "attachments[0]" }],
  ])("does not make %s downloadable", (_case, value) => {
    expect(isDownloadableFileValue(value)).toBe(false);
  });
});

describe("isShownValue", () => {
  it("reads a hidden leaf beside readable text and file metadata without allowing it in a submitted value", () => {
    const shown = {
      details: { privateNote: { withheld: true }, visibleNote: "Demo Sample" },
      attachment: FILE_VALUE,
    };

    expect(isShownValue(shown)).toBe(true);
    expect(isFillValue(shown)).toBe(false);
    expect(isWithheldLeaf(shown.details.privateNote)).toBe(true);
    expect(isWithheldLeaf({ withheld: true, extra: "text" })).toBe(false);
    expect(
      isShownValue({ privateNote: { withheld: true, extra: "text" } }),
    ).toBe(false);
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
