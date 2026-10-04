import { describe, expect, it } from "vitest";

import { answering, refusalOf } from "../../../../../../testutil/answering";
import { NOT_A_PROBLEM_DOCUMENT, NOT_AN_ADDRESS } from "../../../../../problem";
import { readStep, stepAnswerFrom } from "./{stepId}";

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

const REFUSED_TRY = {
  number: 1,
  beyond: false,
  askedBy: { userId: "000c81", displayName: "Avo" },
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
    measuredHere: "partly",
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

const CHECKING_STEP = "0000000a-0000-4000-8000-000000000c81";

/** A code step's try never run, as what its code gives back no longer matched what a later step reads from it. */
const MISREAD_TRY = {
  ...ERRORED_TRY,
  wentWrong: undefined,
  returned: undefined,
  erroredFor: {
    reason: "gives_otherwise",
    field: "receipt.total",
    readBy: { step: CHECKING_STEP },
  },
};

const UNNAMED_TRY = {
  ...MISREAD_TRY,
  erroredFor: { reason: "not_declared", member: "", returnedNotKept: true },
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

const WORDS = { text: "I cannot answer.", altered: false, truncated: true };
const CONTENT = { text: "Demo Sample", altered: true, truncated: false };

describe("a model declining in production or review", () => {
  const modelTry = {
    ...SURE_TRY,
    didNotFit: "model_declined",
    review: {
      asked: true,
      by: { kind: "model", model: "judge" },
      didNotFit: "model_declined",
    },
  };
  const pageWith = (producer: unknown, reviewer: unknown) => ({
    ...PAGE,
    triesMade: [
      {
        ...modelTry,
        declined: producer,
        review: { ...modelTry.review, declined: reviewer },
      },
    ],
  });

  it("keeps the two sources and both exact text blocks independent", () => {
    const answer = stepAnswerFrom(
      pageWith({ words: WORDS, content: CONTENT }, { content: WORDS }),
    );
    expect(answer?.triesMade[0]?.declined).toEqual({
      words: WORDS,
      content: CONTENT,
    });
    expect(answer?.triesMade[0]?.review.declined).toEqual({ content: WORDS });
  });

  it("accepts a single block, withheld content, and an omitted record", () => {
    expect(
      stepAnswerFrom(pageWith({ words: WORDS }, { withheld: true }))
        ?.triesMade[0],
    ).toMatchObject({
      declined: { words: WORDS },
      review: { declined: { withheld: true } },
    });
    expect(
      stepAnswerFrom(
        pageWith({ words: { ...WORDS, text: " " } }, { withheld: true }),
      )?.triesMade[0]?.declined,
    ).toEqual({
      words: { ...WORDS, text: " " },
    });
    const absent = stepAnswerFrom({ ...PAGE, triesMade: [modelTry] })
      ?.triesMade[0];
    expect(absent).not.toHaveProperty("declined");
    expect(absent?.review).not.toHaveProperty("declined");
  });

  it.each([
    {},
    { withheld: false },
    { withheld: true, words: WORDS },
    { words: null },
    { words: { ...WORDS, text: "" } },
    { words: { ...WORDS, altered: undefined } },
    { content: { ...CONTENT, truncated: "false" } },
    { words: "raw" },
  ])(
    "refuses the whole answer when a declined record cannot be read: %j",
    (bad) => {
      expect(stepAnswerFrom(pageWith(bad, { content: CONTENT }))).toBeNull();
      expect(stepAnswerFrom(pageWith({ words: WORDS }, bad))).toBeNull();
    },
  );
});

describe("an unreadable model response", () => {
  const pageWith = (producer: unknown, reviewer: unknown) => ({
    ...PAGE,
    triesMade: [
      {
        ...SURE_TRY,
        unreadableResponse: producer,
        review: { ...SURE_TRY.review, unreadableResponse: reviewer },
      },
    ],
  });

  it("keeps production and review text and flags independently", () => {
    const answer = stepAnswerFrom(pageWith(WORDS, CONTENT));
    expect(answer?.triesMade[0]?.unreadableResponse).toEqual(WORDS);
    expect(answer?.triesMade[0]?.review.unreadableResponse).toEqual(CONTENT);
    const absent = stepAnswerFrom({ ...PAGE, triesMade: [SURE_TRY] })
      ?.triesMade[0];
    expect(absent).not.toHaveProperty("unreadableResponse");
    expect(absent?.review).not.toHaveProperty("unreadableResponse");
  });

  it.each([
    null,
    {},
    { ...WORDS, text: "" },
    { ...WORDS, altered: undefined },
    { ...WORDS, truncated: "false" },
    { ...WORDS, extra: true },
  ])(
    "refuses an unreadable response of bad shape from either source: %j",
    (bad) => {
      expect(stepAnswerFrom(pageWith(bad, CONTENT))).toBeNull();
      expect(stepAnswerFrom(pageWith(WORDS, bad))).toBeNull();
    },
  );
});

function withErroredFor(erroredFor: unknown) {
  return { ...PAGE, triesMade: [{ ...MISREAD_TRY, erroredFor }] };
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

describe("a held source on a step's page", () => {
  const source = {
    runId: "00000008-0000-4000-8000-000000000c82",
    runNumber: 9,
    stepId: "00000009-0000-4000-8000-000000000c82",
    name: "tidy_it",
    codeStep: "tidy",
  };

  it("reads the code source in another run", () => {
    const where = { kind: "held_back", reason: "code_step_not_held", source };
    expect(
      stepAnswerFrom({ ...PAGE, step: { ...ROW, state: "held_back", where } })
        ?.step.where,
    ).toEqual(where);
  });

  it.each(Object.keys(source))(
    "refuses a page whose source omits %s",
    (member) => {
      const incomplete = Object.fromEntries(
        Object.entries(source).filter(([key]) => key !== member),
      );
      expect(
        stepAnswerFrom({
          ...PAGE,
          step: {
            ...ROW,
            state: "held_back",
            where: {
              kind: "held_back",
              reason: "code_step_not_held",
              source: incomplete,
            },
          },
        }),
      ).toBeNull();
    },
  );

  it("refuses a page that attributes a source hold to a review", () => {
    expect(
      stepAnswerFrom({
        ...PAGE,
        step: {
          ...ROW,
          where: {
            kind: "waiting_on_review",
            reason: "code_step_not_held",
            source,
          },
        },
      }),
    ).toBeNull();
  });

  it("reads a source mismatch as a failure with the kept source field", () => {
    const where = {
      kind: "failed",
      reason: "source_no_longer_matches",
      failedSourceNoLongerMatches: { source, field: "receipt.number" },
    };
    expect(
      stepAnswerFrom({ ...PAGE, step: { ...ROW, state: "failed", where } })
        ?.step.where,
    ).toEqual(where);
  });

  it("refuses a failure page with a source record meant for a hold", () => {
    expect(
      stepAnswerFrom({
        ...PAGE,
        step: {
          ...ROW,
          state: "failed",
          where: {
            kind: "failed",
            reason: "source_no_longer_matches",
            source,
          },
        },
      }),
    ).toBeNull();
  });
});

describe("a detail page with a source list failure", () => {
  const failure = {
    source: {
      runId: "other-run",
      runNumber: 8,
      stepId: "source-step",
      name: "source_step",
      codeStep: "tidy",
    },
    field: "receipt.category",
  };

  it("reads the stored list failure without a shape-mismatch member", () => {
    const where = {
      kind: "failed",
      reason: "source_list_not_here",
      failedSourceListNotHere: failure,
    };
    expect(
      stepAnswerFrom({ ...PAGE, step: { ...ROW, state: "failed", where } })
        ?.step.where,
    ).toEqual(where);
  });

  it.each([
    { kind: "failed", reason: "source_list_not_here" },
    {
      kind: "failed",
      reason: "source_list_not_here",
      failedSourceListNotHere: { source: failure.source },
    },
    {
      kind: "failed",
      reason: "source_list_not_here",
      failedSourceListNotHere: {
        ...failure,
        source: { ...failure.source, codeStep: null },
      },
    },
    {
      kind: "held_back",
      reason: "source_list_not_here",
      failedSourceListNotHere: failure,
    },
    {
      kind: "failed",
      reason: "source_no_longer_matches",
      failedSourceListNotHere: failure,
    },
    {
      kind: "failed",
      reason: "source_list_not_here",
      failedSourceNoLongerMatches: failure,
    },
    {
      kind: "failed",
      reason: "source_list_not_here",
      failedSourceListNotHere: failure,
      failedSourceNoLongerMatches: failure,
    },
  ])("refuses an invalid list failure detail: %j", (where) => {
    expect(
      stepAnswerFrom({ ...PAGE, step: { ...ROW, state: "failed", where } }),
    ).toBeNull();
  });
});

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

  it.each(["did_not_fit", "nothing_came_back", "errored", "later"])(
    "reads a review lost as %s separately from how its production ended",
    async (lost) => {
      const review = {
        asked: true,
        by: { kind: "model", model: "small" },
        wentWrong: true,
        lost,
        didNotFit: "words_missing",
      };
      const [tried] = (
        await reading({ ...PAGE, triesMade: [{ ...SURE_TRY, review }] }).read
      ).triesMade;
      expect(tried!.review).toEqual(review);
      expect(tried!.ended).toBe(SURE_TRY.ended);
      expect(tried).not.toHaveProperty("didNotFit");
    },
  );

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

  it("reads line break outcomes beside the retained model and code evidence", async () => {
    const model = { ...MISFIT_TRY, didNotFit: "line_break" };
    const code = {
      ...ERRORED_TRY,
      erroredFor: { reason: "line_break", field: "receipt" },
    };
    const { triesMade } = await reading({ ...PAGE, triesMade: [model, code] })
      .read;

    expect(triesMade[0]?.didNotFit).toBe("line_break");
    expect(triesMade[0]?.values).toEqual([]);
    expect(triesMade[1]?.erroredFor).toEqual(code.erroredFor);
    expect(triesMade[1]?.returned).toBe(code.returned);
    expect(triesMade[1]).not.toHaveProperty("didNotFit");
  });

  it("reads trusted kept file metadata on an errored code try independently of raw returned JSON", async () => {
    const keptFiles = [
      {
        path: "attachments[2]",
        id: "11111111-1111-4111-8111-111111111111",
        name: "kept.txt",
        type: "text/plain",
        size: 7,
      },
    ];
    const [errored] = (
      await reading({ ...PAGE, triesMade: [{ ...ERRORED_TRY, keptFiles }] })
        .read
    ).triesMade;

    expect(errored!.keptFiles).toEqual(keptFiles);
    expect(errored!.returned).toBe('{"reference": 7}');
  });

  it.each([
    ["identifier", { id: "not-a-uuid" }],
    ["name", { name: "Demo\nSample.txt" }],
    ["type", { type: "text//plain" }],
  ])(
    "refuses kept code file metadata with an unusable %s",
    (_case, changed) => {
      const file = {
        path: "attachments[0]",
        id: "11111111-1111-4111-8111-111111111111",
        name: "Demo Sample.txt",
        type: "text/plain",
        size: 7,
        ...changed,
      };

      expect(stepAnswerFrom(withTry({ keptFiles: [file] }))).toBeNull();
    },
  );

  it("leaves out what code gave back where a try carries none", async () => {
    const [refused] = (await reading(PAGE).read).triesMade;

    expect(refused).not.toHaveProperty("returned");
  });

  it("reads why this system found code went wrong, the field it is about and the later step reading it", async () => {
    const [misread] = (
      await reading({ ...PAGE, triesMade: [MISREAD_TRY] }).read
    ).triesMade;

    expect(misread).toEqual({
      ...ERRORED_TRY,
      wentWrong: undefined,
      returned: undefined,
      erroredFor: {
        reason: "gives_otherwise",
        field: "receipt.total",
        readBy: { step: CHECKING_STEP },
      },
      cost: {},
    });
  });

  it("reads what a workflow gives back as what reads the field, by its names joined by dots", async () => {
    const [misread] = (
      await reading(
        withErroredFor({
          ...MISREAD_TRY.erroredFor,
          readBy: { output: "claim.total" },
        }),
      ).read
    ).triesMade;

    expect(misread!.erroredFor!.readBy).toEqual({ output: "claim.total" });
  });

  it("reads a member nothing declares with no name as the empty name it came as, and that what came back was not kept, naming no field", async () => {
    const [unnamed] = (
      await reading({ ...PAGE, triesMade: [UNNAMED_TRY] }).read
    ).triesMade;

    expect(unnamed!.erroredFor).toEqual({
      reason: "not_declared",
      member: "",
      returnedNotKept: true,
    });
  });

  it("reads a member's explicit cut flag without changing its name", async () => {
    const member = "𝒜".repeat(64);
    const [cut] = (
      await reading(
        withErroredFor({ reason: "not_declared", member, memberCut: true }),
      ).read
    ).triesMade;
    expect(cut!.erroredFor).toEqual({
      reason: "not_declared",
      member,
      memberCut: true,
    });
  });

  it("does not infer a cut flag from a member name's ending", async () => {
    const [uncut] = (
      await reading(
        withErroredFor({ reason: "not_declared", member: "items…" }),
      ).read
    ).triesMade;
    expect(uncut!.erroredFor).toEqual({
      reason: "not_declared",
      member: "items…",
    });
  });

  it("keeps a reason code went wrong for that this build does not know as it came", async () => {
    const [later] = (
      await reading(withErroredFor({ reason: "ran_too_hot" })).read
    ).triesMade;

    expect(later!.erroredFor).toEqual({ reason: "ran_too_hot" });
  });

  it("leaves out why code went wrong where a try carries none", async () => {
    const tries = (await reading(PAGE).read).triesMade;

    expect(tries.filter((each) => "erroredFor" in each)).toEqual([]);
    expect(tries).toHaveLength(2);
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
          measuredHere: "partly",
        },
      },
    });
  });

  it("reads a model's declined answer as one spent try with its reason and cost", async () => {
    const declined = { ...MISFIT_TRY, didNotFit: "model_declined" };
    const { sent, read } = reading({ ...PAGE, triesMade: [declined] });

    const answer = await read;

    expect(sent).toHaveBeenCalledTimes(1);
    expect(answer.triesMade).toEqual([
      {
        ...declined,
        cost: {
          spend: {
            sent: "12",
            cameBack: "4",
            spent: "16",
            cameBackUnknown: false,
            measuredHere: "partly",
          },
        },
      },
    ]);
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

  it("reads how the question the step pins is drawn as it was written, leaving it out where the page says nothing of it", async () => {
    const drawing =
      "# {{title}}\n\n\\{{ <b>x</b> ![a](https://e.example/a.png)";

    const page = await reading({ ...PAGE, drawing }).read;

    expect(page.drawing).toBe(drawing);
    expect(page).toEqual({ ...(await reading(PAGE).read), drawing });
    expect(await reading(PAGE).read).not.toHaveProperty("drawing");
  });

  it("reads a drawing of one character as it came, the fewest a drawing is written in", async () => {
    const page = await reading({ ...PAGE, drawing: " " }).read;

    expect(page.drawing).toBe(" ");
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

  it("reads a whole withheld input with its source but no value or earlier shape", async () => {
    const input = {
      input: "text",
      from: { kind: "run_input", path: "ticket" },
      withheld: true,
    };
    const page = await reading({ ...PAGE, wentIn: [input] }).read;

    expect(page.wentIn).toEqual([input]);
    expect(page.wentIn?.[0]).not.toHaveProperty("value");
  });

  it.each([
    { value: "Fire." },
    { earlierShape: true },
    { withheldParts: ["/text"] },
    { extra: "a private fact" },
  ])(
    "refuses a mixed whole withheld input instead of drawing part: %j",
    async (mixed) => {
      const input = {
        input: "text",
        from: { kind: "run_input", path: "ticket" },
        withheld: true,
        ...mixed,
      };
      const failure = await refusalOf(
        reading({ ...PAGE, wentIn: [input] }).read,
      );
      expect(failure.problem).toEqual({
        status: 200,
        code: NOT_A_PROBLEM_DOCUMENT,
      });
    },
  );

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
      "a review lost in no text",
      withTry({ review: { asked: true, lost: true } }),
    ],
    [
      "a review misfit in no text",
      withTry({ review: { asked: true, didNotFit: 2 } }),
    ],
    [
      "a review gone wrong said in text",
      withTry({ review: { asked: true, wentWrong: "yes" } }),
    ],
    ["a try saying nothing of its cost", withTry({ cost: undefined })],
    ["a try numbered in text", withTry({ number: "1" })],
    ["a try produced by nobody", withTry({ producedBy: undefined })],
    [
      "a try asked by nobody who can be shown",
      withTry({ askedBy: { displayName: "Avo" } }),
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
    ["how it is drawn written as other than text", { ...PAGE, drawing: 7 }],
    ["how it is drawn sent as null", { ...PAGE, drawing: null }],
    [
      "how it is drawn sent empty, which would draw nothing that went in",
      { ...PAGE, drawing: "" },
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
    [
      "why code went wrong naming no reason",
      withErroredFor({ field: "receipt" }),
    ],
    [
      "why code went wrong for a reason in no word",
      withErroredFor({ reason: 3 }),
    ],
    [
      "the field code went wrong at named in no text",
      withErroredFor({ reason: "too_long", field: ["receipt"] }),
    ],
    [
      "a member nothing declares named in no text",
      withErroredFor({ reason: "not_declared", member: null }),
    ],
    [
      "what reads a field named as a step and as an output both",
      withErroredFor({
        reason: "gives_otherwise",
        field: "receipt",
        readBy: { step: CHECKING_STEP, output: "total" },
      }),
    ],
    [
      "what reads a field named as neither a step nor an output",
      withErroredFor({
        reason: "gives_otherwise",
        field: "receipt",
        readBy: {},
      }),
    ],
    [
      "what reads a field named as a step in no text",
      withErroredFor({
        reason: "gives_otherwise",
        field: "receipt",
        readBy: { step: 7 },
      }),
    ],
    [
      "a member cut flag sent as false",
      withErroredFor({
        reason: "not_declared",
        member: "items",
        memberCut: false,
      }),
    ],
    [
      "a member cut flag sent as text",
      withErroredFor({
        reason: "not_declared",
        member: "items",
        memberCut: "true",
      }),
    ],
    [
      "what came back said to be not kept as false",
      withErroredFor({
        reason: "too_long",
        field: "receipt",
        returnedNotKept: false,
      }),
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

describe("originating run of a standing output", () => {
  it("reads the actual maker separately from this step's run", () => {
    const maker = { runId: "00000008-0000-4000-8000-000000000c82", number: 4 };
    const page = stepAnswerFrom({
      ...PAGE,
      cameOut: [
        {
          field: "summary",
          now: "stands",
          value: "Demo Sample",
          fromRun: maker,
        },
      ],
    });
    expect(page?.cameOut?.[0]?.fromRun).toEqual(maker);
    expect(page?.run.runId).toBe(RUN);
    expect(stepAnswerFrom(PAGE)?.cameOut?.[0]).not.toHaveProperty("fromRun");
    expect(
      stepAnswerFrom({
        ...PAGE,
        cameOut: [
          {
            field: "summary",
            now: "stands",
            value: "Demo Sample",
            fromRun: { ...maker, number: "four" },
          },
        ],
      }),
    ).toBeNull();
  });
});

describe("searching tries and answering", () => {
  const SEARCH_STEP = {
    ...ROW,
    runs: {
      kind: "search",
      searchWay: "sample_web_search",
      tool: "web_search",
    },
  };
  const FEE = { currency: "USD", perUse: "0.0100", amount: "0.0200" };
  const COUNTED = { uses: "2", usesCounted: "said", fee: FEE };
  const SAID = {
    question: "Find Demo Sample  ",
    searches: [
      { searchedFor: "Demo Sample", found: "Found.\n" },
      { found: "Elsewhere" },
    ],
    searchesCut: true,
    searchesNotAsItCame: true,
    searchesLeftOut: "3",
    ...COUNTED,
  };
  const pageWith = (
    search: unknown,
    producedBy: unknown = SURE_TRY.producedBy,
  ) => ({
    ...PAGE,
    step: SEARCH_STEP,
    triesMade: [{ ...SURE_TRY, producedBy, search }],
  });

  it("reads the question and record exactly as they came beside decimal count and fee", () => {
    const page = stepAnswerFrom(pageWith(SAID));
    expect(page?.triesMade[0]?.search).toEqual({
      said: {
        question: SAID.question,
        searches: SAID.searches,
        searchesCut: true,
        searchesNotAsItCame: true,
        searchesLeftOut: "3",
      },
      counted: COUNTED,
    });
    expect(page?.triesMade[0]?.search?.said).not.toHaveProperty("withheld");
    expect(page?.triesMade[0]?.search?.counted).not.toHaveProperty("noFee");
  });

  it("keeps counts and money beside a withheld record and drops unknown members", () => {
    const page = stepAnswerFrom(
      pageWith({ withheld: true, ...COUNTED, later: "not read" }),
    );
    expect(page?.triesMade[0]?.search).toEqual({
      said: { withheld: true },
      counted: COUNTED,
    });
    expect(page?.triesMade[0]?.search?.said).not.toHaveProperty("question");
    expect(page?.triesMade[0]?.search?.said).not.toHaveProperty("searches");
  });

  it.each([
    [
      {
        question: "Find",
        searchesUnsaid: true,
        uses: "0",
        usesCounted: "said",
        noFee: { reason: "no_price" },
      },
      {
        said: { question: "Find", searchesUnsaid: true },
        counted: {
          uses: "0",
          usesCounted: "said",
          noFee: { reason: "no_price" },
        },
      },
    ],
    [
      {
        question: "Find",
        uses: "9007199254740993",
        usesCounted: "at_most",
        noFee: { reason: "many_currencies" },
      },
      {
        said: { question: "Find" },
        counted: {
          uses: "9007199254740993",
          usesCounted: "at_most",
          noFee: { reason: "many_currencies" },
        },
      },
    ],
    [
      {
        withheld: true,
        uses: "4",
        usesCounted: "at_most",
        noFee: { reason: "not_in_currency", currency: "EUR" },
      },
      {
        said: { withheld: true },
        counted: {
          uses: "4",
          usesCounted: "at_most",
          noFee: { reason: "not_in_currency", currency: "EUR" },
        },
      },
    ],
    [{ question: "Find" }, { said: { question: "Find" } }],
    [{ withheld: true }, { said: { withheld: true } }],
  ])(
    "reads accepted counts or an open uncounted call: %j",
    (search, expected) => {
      expect(stepAnswerFrom(pageWith(search))?.triesMade[0]?.search).toEqual(
        expected,
      );
    },
  );

  it("keeps an explicitly empty half, but not an empty record", () => {
    expect(
      stepAnswerFrom(
        pageWith({
          question: "Find",
          searches: [{ searchedFor: "", found: "" }],
        }),
      )?.triesMade[0]?.search,
    ).toEqual({
      said: { question: "Find", searches: [{ searchedFor: "", found: "" }] },
    });
    expect(stepAnswerFrom(pageWith({ searches: [{}] }))).toBeNull();
  });

  it.each([
    { withheld: false },
    ...[
      "question",
      "searches",
      "searchesUnsaid",
      "searchesCut",
      "searchesNotAsItCame",
      "searchesLeftOut",
    ].map((name) => ({
      withheld: true,
      [name]: SAID[name as keyof typeof SAID] ?? true,
    })),
    { searches: [] },
    { searches: [{ found: 3 }] },
    { searches: [{ found: "Found" }], searchesUnsaid: true },
    { searchesUnsaid: true, searchesCut: true },
    { searchesUnsaid: true, searchesLeftOut: "1" },
    { searchesCut: false },
    { searchesNotAsItCame: false },
    { searchesLeftOut: "0" },
    { searchesLeftOut: "01" },
    { searchesLeftOut: 1 },
    { uses: "2" },
    { usesCounted: "said" },
    { fee: FEE },
    { noFee: { reason: "no_price" } },
    { ...COUNTED, noFee: { reason: "no_price" } },
    { ...COUNTED, usesCounted: "estimated" },
    ...[2, "-1", "01", "1.0", "1e3", ""].map((uses) => ({ ...COUNTED, uses })),
    ...[0.01, "-1", "01.2", "1e-2", "1.", ".2", ""].map((perUse) => ({
      ...COUNTED,
      fee: { ...FEE, perUse },
    })),
    { ...COUNTED, fee: { ...FEE, amount: "1e2" } },
    { ...COUNTED, fee: { ...FEE, currency: "usd" } },
    { uses: "2", usesCounted: "said", noFee: { reason: "not_in_currency" } },
    {
      uses: "2",
      usesCounted: "said",
      noFee: { reason: "many_currencies", currency: "USD" },
    },
    {
      uses: "2",
      usesCounted: "said",
      noFee: { reason: "no_price", currency: "USD" },
    },
    { uses: "2", usesCounted: "said", noFee: { reason: "unknown" } },
  ])("refuses incomplete or contradictory search facts: %j", (search) => {
    const given =
      typeof search === "object" && search !== null && !("withheld" in search)
        ? { question: "Find", ...search }
        : search;
    expect(stepAnswerFrom(pageWith(given))).toBeNull();
  });

  it.each([null, [], {}, { ...COUNTED }])(
    "refuses a missing question or non-object search: %j",
    (search) => {
      expect(stepAnswerFrom(pageWith(search))).toBeNull();
    },
  );

  it("refuses a record on a person-produced try or on a step that does not search", () => {
    expect(stepAnswerFrom(pageWith(SAID, { kind: "person" }))).toBeNull();
    expect(stepAnswerFrom({ ...pageWith(SAID), step: ROW })).toBeNull();
    expect(
      stepAnswerFrom({ ...PAGE, step: SEARCH_STEP })?.triesMade[0],
    ).not.toHaveProperty("search");
  });

  it.each([true, false])(
    "reads whether the answering question was sent: %s",
    (searchQuestionSent) => {
      const answering = {
        number: 3,
        beyond: false,
        gives: [SUMMARY],
        searchQuestion: "Find Demo Sample  ",
        searchQuestionSent,
      };
      const page = stepAnswerFrom({ ...PAGE, step: SEARCH_STEP, answering });
      expect(page?.answering).toEqual({
        number: 3,
        beyond: false,
        gives: [SUMMARY],
        searchQuestion: {
          text: "Find Demo Sample  ",
          sent: searchQuestionSent,
        },
      });
      expect(page?.answering?.searchQuestion).not.toHaveProperty("withheld");
    },
  );

  it("withholds the answering question without inventing whether it was sent", () => {
    const answering = {
      number: 3,
      beyond: false,
      gives: [SUMMARY],
      searchQuestionWithheld: true,
    };
    const page = stepAnswerFrom({ ...PAGE, step: SEARCH_STEP, answering });
    expect(page?.answering).toEqual({
      number: 3,
      beyond: false,
      gives: [SUMMARY],
      searchQuestion: { withheld: true },
    });
    expect(page?.answering?.searchQuestion).not.toHaveProperty("text");
    expect(page?.answering?.searchQuestion).not.toHaveProperty("sent");
  });

  it.each([
    { searchQuestion: "Find" },
    { searchQuestionSent: false },
    { searchQuestion: "Find", searchQuestionSent: "true" },
    {
      searchQuestion: "Find",
      searchQuestionSent: true,
      searchQuestionWithheld: true,
    },
    { searchQuestionWithheld: false },
    { searchQuestion: null, searchQuestionSent: true },
  ])(
    "refuses an incomplete or contradictory answering question: %j",
    (question) => {
      expect(
        stepAnswerFrom({
          ...PAGE,
          step: SEARCH_STEP,
          answering: {
            number: 3,
            beyond: false,
            gives: [SUMMARY],
            ...question,
          },
        }),
      ).toBeNull();
    },
  );
});
