import { describe, expect, it } from "vitest";

import { answering, refusalOf } from "../../../../../testutil/answering";
import { NOT_AN_ADDRESS } from "../../../../problem";
import {
  atVersion,
  contentProblemsIn,
  retiredPinsIn,
  startDraft,
} from "./versions";

const REPLACED = {
  entryId: "00000006-0000-4000-8000-000000000aa2",
  kind: "reference_list",
  name: "Regions",
  pinned: { versionId: "00000007-0000-4000-8000-000000000aa2", number: 1 },
  newestInService: {
    versionId: "00000007-0000-4000-8000-000000000aa3",
    number: 3,
  },
};

const UNREPLACED = {
  entryId: "00000006-0000-4000-8000-000000000aa4",
  kind: "question",
  name: "Triage",
  pinned: { versionId: "00000007-0000-4000-8000-000000000aa4", number: 2 },
};

function pinsRetired(pins: unknown) {
  return { status: 409, code: "VERSION_PINS_RETIRED", extensions: { pins } };
}

const WHOLE = { code: "instruction_missing", part: "instruction" };

const AT_FIELD = {
  code: "longest_missing",
  part: "gives",
  fieldId: "0000000b-0000-4000-8000-000000000aa1",
};

const AT_CASE_INPUT = {
  code: "input_unbound",
  part: "steps",
  stepId: "0000000c-0000-4000-8000-000000000aa1",
  caseId: "0000000d-0000-4000-8000-000000000aa1",
  fieldId: "0000000b-0000-4000-8000-000000000aa2",
};

const AT_BINDING = {
  code: "source_not_earlier",
  part: "steps",
  stepId: "0000000c-0000-4000-8000-000000000aa1",
  bindingId: "0000000e-0000-4000-8000-000000000aa1",
};

const TOO_LONG = {
  code: "instruction_too_long",
  part: "instruction",
  excess: 12,
};

const AT_TERM = {
  code: "term_repeated",
  part: "terms",
  termId: "0000000a-0000-4000-8000-000000000aa1",
};

const PAST_ITS_BOUND = {
  code: "limit_past_largest",
  part: "gives",
  fieldId: "0000000b-0000-4000-8000-000000000aa1",
  excess: 12,
};

const PAST = { code: "asking_past_largest", part: "asking", excess: 1234567 };

function contentRefused(problems: unknown) {
  return {
    status: 409,
    code: "VERSION_CONTENT_DOES_NOT_HOLD",
    extensions: { problems },
  };
}

describe("retiredPinsIn", () => {
  it("reads every pin a refusal for pins retired since names, the replacement only where one arrived", () => {
    const pins = retiredPinsIn(pinsRetired([REPLACED, UNREPLACED]))!;

    expect(pins).toEqual([REPLACED, UNREPLACED]);
    expect("newestInService" in pins[1]!).toBe(false);
  });

  it("reads the pins a refusal for what a version holds names beside its places", () => {
    const refused = contentRefused([WHOLE]);

    expect(
      retiredPinsIn({
        ...refused,
        extensions: { ...refused.extensions, pins: [UNREPLACED] },
      }),
    ).toEqual([UNREPLACED]);
  });

  it.each([
    [
      "a refusal for what a version holds naming no pins",
      contentRefused([WHOLE]),
    ],
    [
      "one naming a pin that is none",
      {
        ...contentRefused([WHOLE]),
        extensions: { problems: [WHOLE], pins: ["Regions"] },
      },
    ],
    [
      "one naming an empty list of pins",
      {
        ...contentRefused([WHOLE]),
        extensions: { problems: [WHOLE], pins: [] },
      },
    ],
  ])("reads no pins out of %s", (_case, problem) => {
    expect(retiredPinsIn(problem)).toBeNull();
  });

  it.each([
    [
      "another refusal",
      {
        status: 409,
        code: "VERSION_STANDING_REFUSES",
        extensions: { pins: [REPLACED] },
      },
    ],
    [
      "a code of that spelling minted here",
      { code: "VERSION_PINS_RETIRED", extensions: { pins: [REPLACED] } },
    ],
    ["one carrying no pins", { status: 409, code: "VERSION_PINS_RETIRED" }],
    [
      "one with a pin of no number",
      pinsRetired([{ ...REPLACED, pinned: { versionId: "v" } }]),
    ],
    [
      "one with a replacement of no identifier",
      pinsRetired([{ ...REPLACED, newestInService: { number: 3 } }]),
    ],
    [
      "one with a pin naming no entry",
      pinsRetired([{ ...UNREPLACED, name: null }]),
    ],
    ["one with a pin that is none", pinsRetired(["Regions"])],
  ])("reads nothing out of %s", (_case, problem) => {
    expect(retiredPinsIn(problem)).toBeNull();
  });
});

describe("contentProblemsIn", () => {
  it("reads every place a refusal for what a version holds names, in order, a field's key only where one arrived", () => {
    const places = contentProblemsIn(contentRefused([WHOLE, AT_FIELD]))!;

    expect(places).toEqual([WHOLE, AT_FIELD]);
    expect("fieldId" in places[0]!).toBe(false);
    expect("termId" in places[1]!).toBe(false);
    expect("excess" in places[0]!).toBe(false);
    expect("excess" in places[1]!).toBe(false);
  });

  it("reads a term's key where a term is the place, and no field's", () => {
    const places = contentProblemsIn(contentRefused([AT_TERM]))!;

    expect(places).toEqual([AT_TERM]);
    expect("fieldId" in places[0]!).toBe(false);
  });

  it("reads how far past its bound a place is only where that arrived", () => {
    const places = contentProblemsIn(
      contentRefused([PAST_ITS_BOUND, AT_FIELD, { ...WHOLE, excess: 1 }]),
    )!;

    expect(places).toEqual([PAST_ITS_BOUND, AT_FIELD, { ...WHOLE, excess: 1 }]);
    expect("excess" in places[1]!).toBe(false);
  });

  it("reads a step's, a case's and a binding's key, and how far past its bound a size runs, only where each arrived", () => {
    const places = contentProblemsIn(
      contentRefused([AT_CASE_INPUT, AT_BINDING, TOO_LONG]),
    )!;

    expect(places).toEqual([AT_CASE_INPUT, AT_BINDING, TOO_LONG]);
    expect("bindingId" in places[0]!).toBe(false);
    expect("excess" in places[1]!).toBe(false);
    expect("stepId" in places[2]!).toBe(false);
  });

  it.each([
    ["a count", 1234567],
    ["one", 1],
  ])(
    "reads by how much an asking runs past where it arrived as %s",
    (_case, excess) => {
      const places = contentProblemsIn(
        contentRefused([WHOLE, { ...PAST, excess }]),
      )!;

      expect(places).toEqual([WHOLE, { ...PAST, excess }]);
      expect("fieldId" in places[1]!).toBe(false);
    },
  );

  it("reads a count past what a double holds exactly as the reply's text arrives, rounded", async () => {
    answering([
      '{"code":"VERSION_CONTENT_DOES_NOT_HOLD","problems":[{"code":"asking_past_largest","part":"asking","excess":9007199254740993}]}',
      409,
    ]);

    const refused = await refusalOf(
      startDraft(GROUP, "question", ENTRY, new AbortController().signal),
    );

    expect(contentProblemsIn(refused.problem)).toEqual([
      { ...PAST, excess: 9007199254740992 },
    ]);
  });

  it.each([
    [
      "another refusal",
      { ...contentRefused([WHOLE]), code: "VERSION_PINS_RETIRED" },
    ],
    [
      "a code of that spelling minted here",
      {
        code: "VERSION_CONTENT_DOES_NOT_HOLD",
        extensions: { problems: [WHOLE] },
      },
    ],
    ["one naming no place", contentRefused([])],
    [
      "one carrying no places",
      { status: 409, code: "VERSION_CONTENT_DOES_NOT_HOLD" },
    ],
    [
      "one with a place of no part",
      contentRefused([{ code: "instruction_missing" }]),
    ],
    [
      "one with a key that is no identifier",
      contentRefused([{ ...AT_FIELD, fieldId: 3 }]),
    ],
    [
      "one with a step's key that is no identifier",
      contentRefused([{ ...AT_BINDING, stepId: null }]),
    ],
    [
      "one with a case's key that is no identifier",
      contentRefused([{ ...AT_CASE_INPUT, caseId: 7 }]),
    ],
    [
      "one with a binding's key that is no identifier",
      contentRefused([{ ...AT_BINDING, bindingId: {} }]),
    ],
    [
      "one with a term's key that is no identifier",
      contentRefused([{ ...AT_TERM, termId: 3 }]),
    ],
    [
      "one with an excess that is no number",
      contentRefused([{ ...PAST_ITS_BOUND, excess: "12" }]),
    ],
    [
      "one with an excess that is no whole number",
      contentRefused([{ ...PAST_ITS_BOUND, excess: 1.5 }]),
    ],
    [
      "one with an excess that arrived empty",
      contentRefused([{ ...PAST_ITS_BOUND, excess: null }]),
    ],
    [
      "one saying by how much in words",
      contentRefused([{ ...PAST, excess: "1234567" }]),
    ],
    ["one saying by nothing", contentRefused([{ ...PAST, excess: 0 }])],
    [
      "one saying by less than nothing",
      contentRefused([{ ...PAST, excess: -1 }]),
    ],
    ["one saying by a fraction", contentRefused([{ ...PAST, excess: 1.5 }])],
    ["one saying by none at all", contentRefused([{ ...PAST, excess: null }])],
  ])("reads nothing out of %s", (_case, problem) => {
    expect(contentProblemsIn(problem)).toBeNull();
  });
});

const GROUP = "00000003-0000-4000-8000-000000000aa1";

const ENTRY = "00000006-0000-4000-8000-000000000aa1";

const VERSION = "00000007-0000-4000-8000-000000000aa1";

describe("atVersion", () => {
  it("hands over the version's address under its entry, each identifier escaped", async () => {
    const addresses = [VERSION, "a/b"].map((version) =>
      atVersion(GROUP, "question", ENTRY, version, (address) =>
        Promise.resolve(address),
      ),
    );

    expect(await Promise.all(addresses)).toEqual([
      `/api/groups/${GROUP}/questions/${ENTRY}/versions/${VERSION}`,
      `/api/groups/${GROUP}/questions/${ENTRY}/versions/a%2Fb`,
    ]);
  });

  it("refuses a version that names no address, asking nothing", async () => {
    const failure = await refusalOf(
      atVersion(GROUP, "question", ENTRY, "..", () => Promise.resolve("asked")),
    );

    expect(failure.problem).toEqual({ code: NOT_AN_ADDRESS });
  });
});

describe("startDraft", () => {
  it("asks the entry's versions for a new one, sending nothing, and reads the entry it answers with", async () => {
    const sent = answering([
      JSON.stringify({
        entryId: ENTRY,
        kind: "question",
        name: "Triage",
        acts: [],
        versions: [],
      }),
      201,
    ]);

    const read = await startDraft(
      GROUP,
      "question",
      ENTRY,
      new AbortController().signal,
    );

    const [address, init] = sent.mock.calls[0]!;
    expect(address).toBe(`/api/groups/${GROUP}/questions/${ENTRY}/versions`);
    expect(init?.method).toBe("POST");
    expect(init?.body).toBeUndefined();
    expect(read.entryId).toBe(ENTRY);
  });
});
