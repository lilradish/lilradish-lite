import { describe, expect, it } from "vitest";

import {
  answering,
  refusalOf,
} from "../../../../../../../../../testutil/answering";
import { NOT_AN_ADDRESS, type Problem } from "../../../../../../../../problem";
import {
  refusedAsMovedOn,
  refusedAsWithdrawn,
  refusedForTheReason,
  reviewTry,
} from "./review";

const GROUP = "00000003-0000-4000-8000-000000000cc1";

const RUN = "00000008-0000-4000-8000-000000000cc1";

const STEP = "00000009-0000-4000-8000-000000000cc1";

const VERSION = "00000007-0000-4000-8000-000000000cc1";

const DAN = { userId: "000cc2", displayName: "Dan" };

/** One value assured and the other refused, which the run goes on from to ask the step again. */
const DECISIONS = {
  summary: { outcome: "assured" },
  reply: { outcome: "refused", why: "Too curt." },
} as const;

/** The step as the server reads it once the review has landed and the run has gone on. */
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
        { name: "reply", kind: "text", longest: 100, mustBeGiven: true },
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
      kind: "owed_try",
      open: false,
      beyond: false,
      since: "2026-09-26T10:00:00Z",
      waitsOn: "answer_step",
    },
    takesFrom: [],
    tries: { current: 1, declared: 2, beyond: false },
    cost: { callsAModel: false },
    gaveBack: [
      { field: "summary", value: "A fire.", now: "stands" },
      { field: "reply", value: "Sorry.", now: "refused" },
    ],
    next: { number: 2, beyond: false },
    acts: ["answer", "ask_again"],
    withheld: [],
  },
  cameOut: [
    {
      field: "summary",
      standing: { number: 1, value: "A fire." },
      now: "stands",
    },
    { field: "reply", now: "refused" },
  ],
  triesMade: [
    {
      number: 1,
      beyond: false,
      producedBy: { kind: "person", person: { userId: "000cc1" } },
      values: [
        {
          field: "summary",
          value: "A fire.",
          now: "stands",
          decision: { outcome: "assured" },
        },
        {
          field: "reply",
          value: "Sorry.",
          now: "refused",
          decision: { outcome: "refused", why: "Too curt." },
        },
      ],
      review: { asked: true, by: { kind: "person", person: DAN } },
      ended: "refused_on_review",
      cost: { callsAModel: false },
    },
  ],
};

function served(code: string, status: number): Problem {
  return { status, code };
}

describe("reviewTry", () => {
  it("asks PUT of the try's review, sending every decision by its field's name and nothing else, and reads the step it answers with", async () => {
    const sent = answering([JSON.stringify(ANSWERED), 200]);

    const read = await reviewTry(
      GROUP,
      RUN,
      STEP,
      1,
      DECISIONS,
      new AbortController().signal,
    );

    const [address, init] = sent.mock.calls[0]!;
    expect(address).toBe(
      `/api/groups/${GROUP}/runs/${RUN}/steps/${STEP}/tries/1/review`,
    );
    expect(init?.method).toBe("PUT");
    expect(JSON.parse(String(init?.body))).toEqual({ decisions: DECISIONS });
    expect(sent).toHaveBeenCalledTimes(1);
    expect(read.step).toMatchObject({
      state: "waiting",
      acts: ["answer", "ask_again"],
    });
    expect(read.triesMade[0]!.review.by).toEqual({
      kind: "person",
      person: DAN,
    });
    expect(read.step).not.toHaveProperty("wentIn");
  });

  it("escapes the step as one segment of the address", async () => {
    const sent = answering([JSON.stringify(ANSWERED), 200]);

    await reviewTry(
      GROUP,
      RUN,
      "a/b",
      2,
      DECISIONS,
      new AbortController().signal,
    );

    expect(sent.mock.calls[0]![0]).toBe(
      `/api/groups/${GROUP}/runs/${RUN}/steps/a%2Fb/tries/2/review`,
    );
  });

  it("refuses a step that names no address, asking nothing", async () => {
    const sent = answering([JSON.stringify(ANSWERED), 200]);

    const failure = await refusalOf(
      reviewTry(GROUP, RUN, "..", 1, DECISIONS, new AbortController().signal),
    );

    expect(failure.problem).toEqual({ code: NOT_AN_ADDRESS });
    expect(sent).not.toHaveBeenCalled();
  });

  it("hands on the server's refusal as it came", async () => {
    answering([JSON.stringify({ code: "STEP_MOVED_ON" }), 409]);

    const failure = await refusalOf(
      reviewTry(GROUP, RUN, STEP, 1, DECISIONS, new AbortController().signal),
    );

    expect(failure.problem).toMatchObject({
      status: 409,
      code: "STEP_MOVED_ON",
    });
  });
});

describe("the refusals a review is told apart by", () => {
  it.each([
    ["STEP_MOVED_ON", 409, [true, false, false]],
    ["RUN_STOPPED", 409, [false, true, false]],
    ["ENTRY_STOPPED", 409, [false, false, false]],
    ["REASON_MISSING", 400, [false, false, true]],
    ["REASON_UNUSABLE", 400, [false, false, true]],
    ["PROSE_TAG_CHARACTER", 400, [false, false, true]],
    ["PROSE_DIRECTION_CONTROL", 400, [false, false, true]],
    ["PROSE_LINE_BREAK_CRLF", 400, [false, false, true]],
    ["REVIEW_INCOMPLETE", 400, [false, false, false]],
    ["REVIEW_OWN_PRODUCTION", 403, [false, false, false]],
    ["REVIEW_NOT_A_PERSONS", 409, [false, true, false]],
    ["ACT_NOT_PERMITTED", 403, [false, false, false]],
  ] as const)(
    "tells %s apart as moved on, withdrawn and a reason refused say",
    (code, status, [movedOn, withdrawn, reason]) => {
      const problem = served(code, status);

      expect([
        refusedAsMovedOn(problem),
        refusedAsWithdrawn(problem),
        refusedForTheReason(problem),
      ]).toEqual([movedOn, withdrawn, reason]);
    },
  );

  it.each([
    "STEP_MOVED_ON",
    "RUN_STOPPED",
    "REVIEW_NOT_A_PERSONS",
    "REASON_MISSING",
  ])("takes %s minted here, with no status, as none of them", (code) => {
    const problem: Problem = { code };

    expect([
      refusedAsMovedOn(problem),
      refusedAsWithdrawn(problem),
      refusedForTheReason(problem),
    ]).toEqual([false, false, false]);
  });
});
