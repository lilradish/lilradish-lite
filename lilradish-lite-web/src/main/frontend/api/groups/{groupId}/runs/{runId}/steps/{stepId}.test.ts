import { describe, expect, it } from "vitest";

import { answering, refusalOf } from "../../../../../../testutil/answering";
import { NOT_A_PROBLEM_DOCUMENT, NOT_AN_ADDRESS } from "../../../../../problem";
import { readStep } from "./{stepId}";

const GROUP = "00000003-0000-4000-8000-000000000c81";

const RUN = "00000008-0000-4000-8000-000000000c81";

const VERSION = "00000007-0000-4000-8000-000000000c81";

const STEP = "00000009-0000-4000-8000-000000000c81";

const SUMMARY = {
  name: "summary",
  kind: "text",
  longest: 1000,
  mustBeGiven: true,
};

const HEADER = {
  runId: RUN,
  number: 3,
  versionId: VERSION,
  state: "running",
  progress: { done: 0, of: 1 },
};

const ROW = {
  stepId: STEP,
  order: 1,
  name: "summarise",
  runs: { kind: "question", name: "Summarise", version: 2, versionId: VERSION },
  producer: { kind: "person" },
  reviewer: { kind: "person" },
  state: "waiting",
  takesFrom: [{ input: "text", from: { kind: "run_input", path: "ticket" } }],
  cost: { callsAModel: false },
  acts: ["answer"],
  withheld: [],
};

/** Cat's answer, refused on review by somebody no name is held for. */
const REFUSED_TRY = {
  number: 1,
  beyond: false,
  askedBy: { userId: "000c81", displayName: "Ada" },
  producedBy: { kind: "person", person: { userId: "000c82" } },
  why: "Read it twice.",
  values: [
    {
      field: "summary",
      value: "A fire.",
      now: "refused",
      decision: { outcome: "refused", why: "Too short." },
    },
  ],
  review: { asked: true, by: { kind: "person", person: { userId: "000c83" } } },
  ended: "refused_on_review",
  cost: { callsAModel: false },
};

/** What a model said and why its reviewer refused it, both withheld from this reader, and the review gone wrong. */
const WITHHELD_TRY = {
  number: 2,
  beyond: true,
  producedBy: { kind: "model", model: "small" },
  values: [
    {
      field: "summary",
      withheld: true,
      now: "refused",
      decision: { outcome: "refused", withheld: true },
    },
  ],
  review: {
    asked: true,
    by: { kind: "model", model: "judge" },
    wentWrong: true,
  },
  ended: "refused_on_review",
  wentWrong: { detail: "Timed out.", cut: false },
  cost: {
    callsAModel: true,
    sent: "10",
    cameBack: "0",
    spent: "10",
    cameBackUnknown: true,
  },
};

/** A model's try as a reader who may read what it said reads it: how sure it was of each value it was asked of. */
const SURE_TRY = {
  number: 1,
  beyond: false,
  producedBy: { kind: "model", model: "small", mode: "careful" },
  values: [
    { field: "summary", value: "A fire.", now: "stands", confidence: 87 },
    { field: "reply", value: "Sorry.", now: "stands" },
  ],
  review: { asked: false },
  ended: "stands",
  cost: {
    callsAModel: true,
    sent: "12",
    cameBack: "4",
    spent: "16",
    cameBackUnknown: false,
  },
};

/**
 * A model's try whose answer was cut short at the most it may give back, sent once it had been turned away twice:
 * once saying why, cut, and once saying nothing; what its call spent this system measured.
 */
const MISFIT_TRY = {
  number: 1,
  beyond: false,
  producedBy: { kind: "model", model: "small" },
  values: [],
  review: { asked: false },
  ended: "did_not_fit",
  didNotFit: "cut_off",
  turnedAway: [
    {
      at: "2026-09-26T09:01:00Z",
      said: "Too many asks.",
      cut: true,
      sentAgain: true,
    },
    { at: "2026-09-26T09:02:00Z", sentAgain: true },
  ],
  cost: {
    callsAModel: true,
    sent: "12",
    cameBack: "4",
    spent: "16",
    cameBackUnknown: false,
    measuredHere: true,
  },
};

/** A code step's try whose code gave back what did not fit, kept as it came back. */
const ERRORED_TRY = {
  number: 1,
  beyond: false,
  producedBy: { kind: "code" },
  values: [],
  review: { asked: false },
  ended: "errored",
  wentWrong: { detail: "The reference it gave back is not text.", cut: false },
  returned: '{"reference": 7}',
  cost: { callsAModel: false },
};

const PAGE = {
  run: HEADER,
  declarations: { [VERSION]: { takes: [], gives: [SUMMARY] } },
  step: ROW,
  wentIn: [
    {
      input: "text",
      from: { kind: "run_input", path: "ticket" },
      value: "Fire.",
    },
  ],
  wentInFrom: "try",
  cameOut: [
    { field: "summary", now: "refused" },
    { field: "reply", standing: { number: 1, value: null }, now: "stands" },
  ],
  triesMade: [REFUSED_TRY, WITHHELD_TRY],
};

/** What a page carries that this build reads nothing of yet: what may be done to the run. */
const NOT_READ_YET = { run: { ...HEADER, acts: ["stop"] } };

const TERMS = {
  name: "priority",
  label: "Priority",
  kind: "term",
  mustBeGiven: true,
  terms: { terms: [{ term: "high", meaning: "Today." }] },
};

/** What a question puts to one who may answer it once try 1 was refused: its instruction, and what it gives. */
const ANSWERING = {
  number: 2,
  beyond: false,
  instruction: "Say what the ticket is about.",
  gives: [SUMMARY, TERMS],
  lastRefused: {
    number: 1,
    values: [
      {
        field: "summary",
        value: "A fire.",
        now: "refused",
        decision: { outcome: "refused", why: "Too short." },
      },
      {
        field: "priority",
        value: "high",
        now: "stands",
        decision: { outcome: "assured" },
      },
    ],
  },
};

/** Not started: nothing has gone in, nothing has come out, and nobody may answer it. */
const NOT_STARTED = {
  run: HEADER,
  declarations: {},
  step: { ...ROW, state: "not_started" },
  triesMade: [],
};

/** Try 1 of a code step whose release has changed since: each value given as it was kept, the reference refused. */
const KEPT_TRY = {
  number: 1,
  beyond: false,
  producedBy: { kind: "code" },
  values: [
    {
      field: "reference",
      value: JSON.stringify("R-7"),
      now: "refused",
      decision: { outcome: "refused", why: "Not a reference." },
      earlierShape: true,
    },
    {
      field: "folder",
      value: null,
      now: "stands",
      decision: { outcome: "assured" },
      earlierShape: true,
    },
  ],
  review: { asked: true, by: { kind: "person", person: { userId: "000c83" } } },
  ended: "refused_on_review",
  cost: { callsAModel: false },
};

/**
 * The code step's own page, answered here as its release declares it now: what went in, what stands and each
 * value of the try refused before given as each was kept.
 */
const KEPT_PAGE = {
  run: HEADER,
  declarations: { [VERSION]: { takes: [], gives: [SUMMARY] } },
  step: {
    ...ROW,
    runs: { kind: "code_step", codeStep: "file_claim" },
    producer: { kind: "code" },
  },
  wentIn: [
    {
      input: "ticket_ref",
      from: { kind: "run_input", path: "ticket" },
      value: JSON.stringify(["Fire."]),
      earlierShape: true,
    },
  ],
  wentInFrom: "try",
  cameOut: [
    { field: "reference", now: "refused" },
    {
      field: "folder",
      standing: { number: 1, value: null, earlierShape: true },
      now: "stands",
    },
  ],
  triesMade: [KEPT_TRY],
  answering: {
    number: 2,
    beyond: false,
    gives: [SUMMARY],
    lastRefused: { number: 1, values: KEPT_TRY.values },
  },
};

/** Each value of `KEPT_TRY` as this build reads it. */
const KEPT_VALUES = [
  {
    field: "reference",
    asKept: '"R-7"',
    now: "refused",
    decision: { outcome: "refused", why: "Not a reference." },
  },
  {
    field: "folder",
    asKept: null,
    now: "stands",
    decision: { outcome: "assured" },
  },
];

function reading(document: unknown, step = STEP) {
  const sent = answering([JSON.stringify(document), 200]);
  return {
    sent,
    read: readStep(GROUP, RUN, step, new AbortController().signal),
  };
}

function withTry(changed: object) {
  return { ...PAGE, triesMade: [{ ...REFUSED_TRY, ...changed }] };
}

/** The page with one model's value, as sure as `confidence` says. */
function sureAs(confidence: unknown) {
  return {
    ...PAGE,
    triesMade: [
      {
        ...SURE_TRY,
        values: [{ ...SURE_TRY.values[0], confidence }],
      },
    ],
  };
}

describe("readStep", () => {
  it("reads one step's page at its address, the step escaped as one segment", async () => {
    const { sent, read } = reading(PAGE, "a/b");

    const page = await read;

    expect(sent.mock.calls[0]![0]).toBe(
      `/api/groups/${GROUP}/runs/${RUN}/steps/a%2Fb`,
    );
    expect(page).toEqual({
      ...PAGE,
      run: HEADER,
      declarations: new Map([[VERSION, { takes: [], gives: [SUMMARY] }]]),
      step: { ...ROW, cost: {} },
      cameOut: [
        { field: "summary", now: "refused" },
        { field: "reply", standing: { value: null }, now: "stands" },
      ],
      triesMade: [
        { ...REFUSED_TRY, cost: {} },
        {
          ...WITHHELD_TRY,
          cost: {
            spend: {
              sent: "10",
              cameBack: "0",
              spent: "10",
              cameBackUnknown: true,
            },
          },
        },
      ],
    });
  });

  it("refuses a step that names no address, asking nothing", async () => {
    const { sent, read } = reading(PAGE, "..");

    const failure = await refusalOf(read);

    expect(failure.problem).toEqual({ code: NOT_AN_ADDRESS });
    expect(sent).not.toHaveBeenCalled();
  });

  it("reads a step nothing has gone into or come out of, leaving out what it may and adding nothing", async () => {
    const page = await reading(NOT_STARTED).read;

    expect(Object.keys(page).toSorted()).toEqual(
      ["declarations", "run", "step", "triesMade"].toSorted(),
    );
    expect(page.triesMade).toEqual([]);
  });

  it("reads what a model said, withheld from the reader, as withheld and never as none, its refusal's words and how sure it was withheld too", async () => {
    const [, withheld] = (await reading(PAGE).read).triesMade;

    expect(withheld!.values[0]).toEqual({
      field: "summary",
      withheld: true,
      now: "refused",
      decision: { outcome: "refused", withheld: true },
    });
    expect(withheld!.values[0]).not.toHaveProperty("value");
    expect(withheld!.values[0]).not.toHaveProperty("confidence");
    expect(withheld!.values[0]!.decision).not.toHaveProperty("why");
  });

  it("reads how sure a model was of each value it was asked of, leaving it out of a value it was not asked of", async () => {
    const [sure] = (await reading({ ...PAGE, triesMade: [SURE_TRY] }).read)
      .triesMade;

    expect(sure!.values).toEqual([
      { field: "summary", value: "A fire.", now: "stands", confidence: 87 },
      { field: "reply", value: "Sorry.", now: "stands" },
    ]);
    expect(sure!.values[1]).not.toHaveProperty("confidence");
  });

  it.each([0, 100])(
    "reads a confidence of %i, each end of the scale a model says it on",
    async (confidence) => {
      const [sure] = (await reading(sureAs(confidence)).read).triesMade;

      expect(sure!.values[0]).toMatchObject({ confidence });
    },
  );

  it("reads what a code step's code gave back that did not fit, as it came back, beside what went wrong", async () => {
    const [errored] = (
      await reading({ ...PAGE, triesMade: [ERRORED_TRY] }).read
    ).triesMade;

    expect(errored).toEqual({ ...ERRORED_TRY, cost: {} });
    expect(errored!.returned).toBe('{"reference": 7}');
  });

  it("leaves out what code gave back where a try carries none", async () => {
    const [refused] = (await reading(PAGE).read).triesMade;

    expect(refused).not.toHaveProperty("returned");
  });

  it("reads which way a model's answer did not fit, each time its call was turned away before it was taken, and that this system measured what it spent", async () => {
    const [misfit] = (await reading({ ...PAGE, triesMade: [MISFIT_TRY] }).read)
      .triesMade;

    expect(misfit).toEqual({
      ...MISFIT_TRY,
      cost: {
        spend: {
          sent: "12",
          cameBack: "4",
          spent: "16",
          cameBackUnknown: false,
          measuredHere: true,
        },
      },
    });
  });

  it("leaves out why a try did not fit, and when it was turned away, where it carries neither", async () => {
    const [refused] = (await reading(PAGE).read).triesMade;

    expect(refused).not.toHaveProperty("didNotFit");
    expect(refused).not.toHaveProperty("turnedAway");
    expect(refused!.cost).toEqual({});
  });

  it("reads what a model's call said went wrong as withheld where it is withheld, and never as nothing said", async () => {
    const [withheld] = (
      await reading(
        withTry({
          ...WITHHELD_TRY,
          wentWrong: { withheld: true },
        }),
      ).read
    ).triesMade;

    expect(withheld!.wentWrong).toEqual({ withheld: true });
    expect(withheld!.wentWrong).not.toHaveProperty("detail");
    expect(withheld!.wentWrong).not.toHaveProperty("cut");
  });

  it("reads how long after it to read the step again, which only a running run's page carries", async () => {
    const page = await reading({ ...PAGE, rereadAfterSeconds: 5 }).read;

    expect(page.rereadAfterSeconds).toBe(5);
    expect(await reading(PAGE).read).not.toHaveProperty("rereadAfterSeconds");
  });

  it("keeps every spelling this build does not know as it came, and passes over members it has never heard of", async () => {
    const later = {
      ...withTry({
        ended: "timed_out",
        didNotFit: "too_proud",
        producedBy: { kind: "panel", seats: 3 },
        values: [{ field: "summary", value: "A fire.", now: "superseded" }],
        heldFor: "budget",
      }),
      wentInFrom: "cached",
      holds: [],
    };

    const page = await reading(later).read;

    expect(page.wentInFrom).toBe("cached");
    expect(page.triesMade).toEqual([
      {
        ...REFUSED_TRY,
        ended: "timed_out",
        didNotFit: "too_proud",
        producedBy: { kind: "panel" },
        values: [{ field: "summary", value: "A fire.", now: "superseded" }],
        cost: {},
      },
    ]);
    expect(page).not.toHaveProperty("holds");
  });

  it("passes over what it reads nothing of yet, however it is written, and reads the rest as it would without it", async () => {
    const page = await reading({ ...PAGE, ...NOT_READ_YET }).read;

    expect(page).toEqual(await reading(PAGE).read);
    expect(page).not.toHaveProperty("answering");
    expect(page.run).not.toHaveProperty("acts");
  });

  it("reads what answering a question puts to the reader: the try it fills, the instruction, each field it gives, and the try refused before it", async () => {
    const page = await reading({ ...PAGE, answering: ANSWERING }).read;

    expect(page.answering).toEqual(ANSWERING);
    expect(page.answering!.lastRefused!.values[0]).toEqual({
      field: "summary",
      value: "A fire.",
      now: "refused",
      decision: { outcome: "refused", why: "Too short." },
    });
  });

  it("reads answering a code step, which tells nothing and follows no refused try, leaving both out rather than as none", async () => {
    const page = await reading({
      ...PAGE,
      answering: { number: 4, beyond: true, gives: [SUMMARY] },
    }).read;

    expect(page.answering).toEqual({
      number: 4,
      beyond: true,
      gives: [SUMMARY],
    });
    expect(page.answering).not.toHaveProperty("instruction");
    expect(page.answering).not.toHaveProperty("lastRefused");
  });

  it("reads each value of a code step kept in a shape the step had before as the text it was kept as, none as none, wherever the page gives one, and never as a value of any field", async () => {
    const page = await reading(KEPT_PAGE).read;

    expect(page.wentIn).toEqual([
      {
        input: "ticket_ref",
        from: { kind: "run_input", path: "ticket" },
        asKept: '["Fire."]',
      },
    ]);
    expect(page.cameOut).toEqual([
      { field: "reference", now: "refused" },
      { field: "folder", standing: { asKept: null }, now: "stands" },
    ]);
    expect(page.triesMade[0]!.values).toEqual(KEPT_VALUES);
    expect(page.answering!.lastRefused!.values).toEqual(KEPT_VALUES);
    expect(
      [
        ...page.wentIn!,
        page.cameOut![1]!.standing!,
        ...page.triesMade[0]!.values,
      ].filter((each) => "value" in each),
    ).toEqual([]);
  });

  it.each([
    ["a field", [SUMMARY, { ...SUMMARY, name: "shade", kind: "colour" }]],
    [
      "a field that fields hold",
      [
        {
          name: "address",
          kind: "fields",
          mustBeGiven: true,
          fields: [{ name: "shade", kind: "colour", mustBeGiven: true }],
        },
      ],
    ],
  ])(
    "leaves answering out where %s it gives is of a kind this build cannot draw, reading the rest as it would without it",
    async (_case, gives) => {
      const page = await reading({
        ...PAGE,
        answering: { ...ANSWERING, gives },
      }).read;

      expect(page).not.toHaveProperty("answering");
      expect(page).toEqual(await reading(PAGE).read);
    },
  );

  it.each([
    ["no object at all", "step"],
    ["a page saying nothing of its tries", { ...PAGE, triesMade: undefined }],
    ["a step it cannot draw", { ...PAGE, step: { ...ROW, order: "1" } }],
    [
      "an input coming from nowhere",
      { ...PAGE, wentIn: [{ input: "text", value: "Fire." }] },
    ],
    [
      "an input neither given nor withheld",
      { ...PAGE, wentIn: [{ ...PAGE.wentIn[0], value: undefined }] },
    ],
    [
      "a value that stands in no try",
      { ...PAGE, cameOut: [{ field: "reply", standing: {}, now: "stands" }] },
    ],
    [
      "a value saying nothing of where it stands",
      { ...PAGE, cameOut: [{ field: "summary" }] },
    ],
    [
      "a value standing in a shape the step had before, kept as other than text",
      {
        ...KEPT_PAGE,
        cameOut: [
          {
            field: "folder",
            standing: { number: 1, value: 7, earlierShape: true },
            now: "stands",
          },
        ],
      },
    ],
    ["a try saying nothing of its review", withTry({ review: undefined })],
    ["a review saying nothing of being asked", withTry({ review: {} })],
    [
      "a review gone wrong said in text",
      withTry({ review: { asked: true, wentWrong: "yes" } }),
    ],
    ["a try saying nothing of its cost", withTry({ cost: undefined })],
    ["a try numbered in text", withTry({ number: "1" })],
    ["a try produced by nobody", withTry({ producedBy: undefined })],
    [
      "a try asked by nobody who can be shown",
      withTry({ askedBy: { displayName: "Ada" } }),
    ],
    [
      "words of a refusal withheld as false",
      withTry({
        values: [
          {
            field: "s",
            value: "a",
            now: "refused",
            decision: { outcome: "refused", withheld: false },
          },
        ],
      }),
    ],
    [
      "a value a try gave in a shape the step had before, and withheld",
      {
        ...KEPT_PAGE,
        triesMade: [
          {
            ...KEPT_TRY,
            values: [
              {
                field: "reference",
                value: "R-7",
                withheld: true,
                now: "refused",
                earlierShape: true,
              },
            ],
          },
        ],
      },
    ],
    [
      "what went wrong saying nothing of being cut",
      withTry({ wentWrong: { detail: "Timed out." } }),
    ],
    [
      "what went wrong both said and withheld",
      withTry({
        wentWrong: { detail: "Timed out.", cut: false, withheld: true },
      }),
    ],
    [
      "what went wrong withheld, yet said",
      withTry({ wentWrong: { detail: "Timed out.", withheld: true } }),
    ],
    [
      "what went wrong withheld, yet saying whether it was cut",
      withTry({ wentWrong: { cut: false, withheld: true } }),
    ],
    [
      "what went wrong withheld as false",
      withTry({ wentWrong: { withheld: false } }),
    ],
    [
      "why an answer did not fit, said in no word",
      { ...PAGE, triesMade: [{ ...MISFIT_TRY, didNotFit: 3 }] },
    ],
    [
      "the times a try was turned away sent as no list",
      { ...PAGE, triesMade: [{ ...MISFIT_TRY, turnedAway: {} }] },
    ],
    [
      "a time a try was turned away saying nothing of whether it was sent again",
      {
        ...PAGE,
        triesMade: [
          { ...MISFIT_TRY, turnedAway: [{ at: "2026-09-26T09:01:00Z" }] },
        ],
      },
    ],
    [
      "a time to read again written in text",
      { ...PAGE, rereadAfterSeconds: "5" },
    ],
    [
      "a time to read again under a second, which would read without pause",
      { ...PAGE, rereadAfterSeconds: 0 },
    ],
    [
      "a time to read again that is no whole number",
      { ...PAGE, rereadAfterSeconds: 1.5 },
    ],
    [
      "what code gave back as other than text",
      { ...PAGE, triesMade: [{ ...ERRORED_TRY, returned: { reference: 7 } }] },
    ],
    ["a confidence below the scale", sureAs(-1)],
    ["a confidence above the scale", sureAs(101)],
    ["a confidence that is no whole number", sureAs(87.5)],
    ["a confidence written in text", sureAs("87")],
    [
      "answering two fields called alike",
      { ...PAGE, answering: { ...ANSWERING, gives: [SUMMARY, SUMMARY] } },
    ],
    [
      "answering a try numbered in text",
      { ...PAGE, answering: { ...ANSWERING, number: "2" } },
    ],
    [
      "answering saying nothing of going beyond",
      { ...PAGE, answering: { ...ANSWERING, beyond: undefined } },
    ],
    [
      "an instruction that is no text",
      { ...PAGE, answering: { ...ANSWERING, instruction: null } },
    ],
    [
      "a refused try numbered in text",
      {
        ...PAGE,
        answering: {
          ...ANSWERING,
          lastRefused: { ...ANSWERING.lastRefused, number: "1" },
        },
      },
    ],
    [
      "a refused try's value neither given nor withheld",
      {
        ...PAGE,
        answering: {
          ...ANSWERING,
          lastRefused: {
            number: 1,
            values: [{ field: "summary", now: "refused" }],
          },
        },
      },
    ],
    [
      "a refused try's value said to be in a shape the step had before as false",
      {
        ...KEPT_PAGE,
        answering: {
          ...KEPT_PAGE.answering,
          lastRefused: {
            number: 1,
            values: [
              {
                field: "reference",
                value: "R-7",
                now: "refused",
                earlierShape: false,
              },
            ],
          },
        },
      },
    ],
  ])("refuses %s rather than drawing part of it", async (_case, body) => {
    const failure = await refusalOf(reading(body).read);

    expect(failure.problem).toEqual({
      status: 200,
      code: NOT_A_PROBLEM_DOCUMENT,
    });
  });
});
