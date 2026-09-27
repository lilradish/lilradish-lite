import { describe, expect, it } from "vitest";

import { fillProblemsIn } from "../../../../../../../../filling";
import {
  answering,
  refusalOf,
} from "../../../../../../../../../testutil/answering";
import { NOT_AN_ADDRESS, type Problem } from "../../../../../../../../problem";
import {
  answerTry,
  refusedAsChanged,
  refusedAsMovedOn,
  refusedForTheReason,
} from "./answer";

const GROUP = "00000003-0000-4000-8000-000000000cf1";

const RUN = "00000008-0000-4000-8000-000000000cf1";

const STEP = "00000009-0000-4000-8000-000000000cf1";

const VERSION = "00000007-0000-4000-8000-000000000cf1";

const DAN = { userId: "000cf2", displayName: "Dan" };

/** Every field the question gives, in the words a start sends them: none as null, many as a list. */
const VALUES = { summary: "A printer fire.", note: null, tags: ["a", "b"] };

/** The step as the server reads it once Dan's answer filled try 2, its summary waiting on review. */
const ANSWERED = {
  run: {
    runId: RUN,
    number: 3,
    versionId: VERSION,
    state: "running",
    at: { stepId: STEP, name: "summarise" },
    acts: ["stop"],
    progress: { done: 0, of: 1 },
  },
  declarations: {
    [VERSION]: {
      takes: [],
      gives: [
        { name: "summary", kind: "text", longest: 100, mustBeGiven: true },
        { name: "note", kind: "text", longest: 100, mustBeGiven: false },
        {
          name: "tags",
          kind: "text",
          longest: 10,
          most: 3,
          mustBeGiven: false,
        },
      ],
    },
  },
  step: {
    stepId: STEP,
    order: 1,
    name: "summarise",
    runs: {
      kind: "question",
      name: "Summarise",
      version: 2,
      versionId: VERSION,
    },
    producer: { kind: "person" },
    reviewer: { kind: "person" },
    state: "waiting",
    where: {
      kind: "waiting_on_review",
      number: 2,
      values: [{ field: "summary", on: "review_at_gate" }],
      since: "2026-09-26T10:00:00Z",
      waitsOn: "review_at_gate",
    },
    takesFrom: [],
    tries: { current: 2, declared: 2, beyond: false },
    cost: { callsAModel: false },
    gaveBack: [
      { field: "summary", value: "A printer fire.", now: "waiting_on_review" },
      { field: "note", value: null, now: "stands" },
      { field: "tags", value: ["a", "b"], now: "stands" },
    ],
    acts: [],
    withheld: [{ act: "review", refusal: "REVIEW_OWN_PRODUCTION" }],
  },
  cameOut: [
    { field: "summary", now: "waiting_on_review" },
    { field: "note", standing: { number: 2, value: null }, now: "stands" },
    {
      field: "tags",
      standing: { number: 2, value: ["a", "b"] },
      now: "stands",
    },
  ],
  triesMade: [
    {
      number: 2,
      beyond: false,
      producedBy: { kind: "person", person: DAN },
      why: "Read it twice.",
      values: [
        {
          field: "summary",
          value: "A printer fire.",
          now: "waiting_on_review",
        },
        { field: "note", value: null, now: "stands" },
        { field: "tags", value: ["a", "b"], now: "stands" },
      ],
      review: { asked: true },
      ended: "waiting",
      cost: { callsAModel: false },
    },
  ],
};

describe("answerTry", () => {
  it("asks PUT of the try's answer, sending every value and why and nothing else, and reads the step it answers with", async () => {
    const sent = answering([JSON.stringify(ANSWERED), 200]);

    const read = await answerTry(
      GROUP,
      RUN,
      STEP,
      2,
      VALUES,
      "Read it twice.",
      new AbortController().signal,
    );

    const [address, init] = sent.mock.calls[0]!;
    expect(address).toBe(
      `/api/groups/${GROUP}/runs/${RUN}/steps/${STEP}/tries/2/answer`,
    );
    expect(init?.method).toBe("PUT");
    expect(init?.body).toBe(
      JSON.stringify({ values: VALUES, why: "Read it twice." }),
    );
    expect(sent).toHaveBeenCalledTimes(1);
    expect(read.triesMade[0]!.producedBy).toEqual({
      kind: "person",
      person: DAN,
    });
    expect(read.triesMade[0]!.why).toBe("Read it twice.");
    expect(read).not.toHaveProperty("answering");
  });

  it("escapes the step as one segment of the address", async () => {
    const sent = answering([JSON.stringify(ANSWERED), 200]);

    await answerTry(
      GROUP,
      RUN,
      "a/b",
      3,
      VALUES,
      "Why.",
      new AbortController().signal,
    );

    expect(sent.mock.calls[0]![0]).toBe(
      `/api/groups/${GROUP}/runs/${RUN}/steps/a%2Fb/tries/3/answer`,
    );
  });

  it("refuses a step that names no address, asking nothing", async () => {
    const sent = answering([JSON.stringify(ANSWERED), 200]);

    const failure = await refusalOf(
      answerTry(
        GROUP,
        RUN,
        "..",
        2,
        VALUES,
        "Why.",
        new AbortController().signal,
      ),
    );

    expect(failure.problem).toEqual({ code: NOT_AN_ADDRESS });
    expect(sent).not.toHaveBeenCalled();
  });

  it("hands on a refusal of values with every place it names, as a start's is read", async () => {
    answering([
      JSON.stringify({
        code: "VALUE_DOES_NOT_FIT",
        problems: [
          { path: ["summary"], reason: "too_long" },
          { path: ["tags", 1], reason: "missing" },
        ],
        problemsFound: 2,
      }),
      400,
    ]);

    const failure = await refusalOf(
      answerTry(
        GROUP,
        RUN,
        STEP,
        2,
        VALUES,
        "Why.",
        new AbortController().signal,
      ),
    );

    expect(fillProblemsIn(failure.problem)).toEqual({
      listed: [
        { path: ["summary"], reason: "too_long" },
        { path: ["tags", 1], reason: "missing" },
      ],
      found: 2,
    });
    expect(refusedAsChanged(failure.problem)).toBe(false);
    expect(refusedForTheReason(failure.problem)).toBe(false);
  });
});

describe("the refusals an answer is told apart by", () => {
  it.each([
    ["STEP_MOVED_ON", 409, [true, true, false]],
    ["RUN_STOPPED", 409, [true, false, false]],
    ["ENTRY_STOPPED", 409, [true, false, false]],
    ["CODE_STEP_GIVES_OTHERWISE", 409, [true, false, false]],
    ["REASON_MISSING", 400, [false, false, true]],
    ["REASON_UNUSABLE", 400, [false, false, true]],
    ["PROSE_TAG_CHARACTER", 400, [false, false, true]],
    ["PROSE_DIRECTION_CONTROL", 400, [false, false, true]],
    ["PROSE_LINE_BREAK_CRLF", 400, [false, false, true]],
    ["VALUE_DOES_NOT_FIT", 400, [false, false, false]],
    ["BODY_UNUSABLE", 400, [false, false, false]],
    ["ASK_AGAIN_NOT_OFFERED", 409, [false, false, false]],
    ["ACT_NOT_PERMITTED", 403, [false, false, false]],
  ] as const)(
    "tells %s apart as changed since it was read, moved on, and a reason refused say",
    (code, status, [changed, movedOn, reason]) => {
      const problem: Problem = { status, code };

      expect([
        refusedAsChanged(problem),
        refusedAsMovedOn(problem),
        refusedForTheReason(problem),
      ]).toEqual([changed, movedOn, reason]);
    },
  );

  it.each(["STEP_MOVED_ON", "REASON_MISSING"])(
    "takes %s minted here, with no status, as none of them",
    (code) => {
      const problem: Problem = { code };

      expect([
        refusedAsChanged(problem),
        refusedAsMovedOn(problem),
        refusedForTheReason(problem),
      ]).toEqual([false, false, false]);
    },
  );
});
