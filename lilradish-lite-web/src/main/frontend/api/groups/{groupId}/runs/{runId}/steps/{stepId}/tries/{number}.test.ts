import { describe, expect, it } from "vitest";

import {
  answering,
  refusalOf,
} from "../../../../../../../../testutil/answering";
import { NOT_AN_ADDRESS, type Problem } from "../../../../../../../problem";
import { askAgain, refusedAsChanged, refusedAsMovedOn } from "./{number}";

const GROUP = "00000003-0000-4000-8000-000000000ce1";

const RUN = "00000008-0000-4000-8000-000000000ce1";

const STEP = "00000009-0000-4000-8000-000000000ce1";

const VERSION = "00000007-0000-4000-8000-000000000ce1";

const CAT = { userId: "000ce1", displayName: "Cat" };

/** The step as the server reads it once try 2 was asked of the person it names, who has not answered yet. */
const ASKED = {
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
      open: true,
      beyond: false,
      since: "2026-09-26T10:00:00Z",
      waitsOn: "answer_step",
    },
    takesFrom: [],
    tries: { current: 2, declared: 2, beyond: false },
    cost: { callsAModel: false },
    gaveBack: [{ field: "summary", value: "A fire.", now: "refused" }],
    next: { number: 2, beyond: false },
    acts: ["answer"],
    withheld: [],
  },
  cameOut: [{ field: "summary", now: "refused" }],
  triesMade: [
    {
      number: 1,
      beyond: false,
      producedBy: { kind: "person", person: CAT },
      values: [
        {
          field: "summary",
          value: "A fire.",
          now: "refused",
          decision: { outcome: "refused", why: "Too short." },
        },
      ],
      review: { asked: true, by: { kind: "person" } },
      ended: "refused_on_review",
      cost: { callsAModel: false },
    },
    {
      number: 2,
      beyond: false,
      askedBy: CAT,
      producedBy: { kind: "person" },
      values: [],
      review: { asked: false },
      ended: "open",
      cost: { callsAModel: false },
    },
  ],
  answering: {
    number: 2,
    beyond: false,
    instruction: "Summarise the ticket.",
    gives: [{ name: "summary", kind: "text", longest: 100, mustBeGiven: true }],
    lastRefused: {
      number: 1,
      values: [
        {
          field: "summary",
          value: "A fire.",
          now: "refused",
          decision: { outcome: "refused", why: "Too short." },
        },
      ],
    },
  },
};

describe("askAgain", () => {
  it("asks PUT of the try itself, sending no body and no media type, and reads the step it answers with", async () => {
    const sent = answering([JSON.stringify(ASKED), 200]);

    const read = await askAgain(
      GROUP,
      RUN,
      STEP,
      2,
      new AbortController().signal,
    );

    const [address, init] = sent.mock.calls[0]!;
    expect(address).toBe(
      `/api/groups/${GROUP}/runs/${RUN}/steps/${STEP}/tries/2`,
    );
    expect(init?.method).toBe("PUT");
    expect(init?.body).toBeUndefined();
    expect(new Headers(init?.headers).has("Content-Type")).toBe(false);
    expect(sent).toHaveBeenCalledTimes(1);
    expect(read.triesMade.map((each) => each.ended)).toEqual([
      "refused_on_review",
      "open",
    ]);
    expect(read.triesMade[1]!.askedBy).toEqual(CAT);
    expect(read.step.acts).toEqual(["answer"]);
    expect(read.answering?.number).toBe(2);
  });

  it("escapes the step as one segment of the address", async () => {
    const sent = answering([JSON.stringify(ASKED), 200]);

    await askAgain(GROUP, RUN, "a/b", 3, new AbortController().signal);

    expect(sent.mock.calls[0]![0]).toBe(
      `/api/groups/${GROUP}/runs/${RUN}/steps/a%2Fb/tries/3`,
    );
  });

  it("refuses a step that names no address, asking nothing", async () => {
    const sent = answering([JSON.stringify(ASKED), 200]);

    const failure = await refusalOf(
      askAgain(GROUP, RUN, "..", 2, new AbortController().signal),
    );

    expect(failure.problem).toEqual({ code: NOT_AN_ADDRESS });
    expect(sent).not.toHaveBeenCalled();
  });

  it("hands on the server's refusal as it came", async () => {
    answering([JSON.stringify({ code: "ASK_AGAIN_NOT_OFFERED" }), 409]);

    const failure = await refusalOf(
      askAgain(GROUP, RUN, STEP, 2, new AbortController().signal),
    );

    expect(failure.problem).toMatchObject({
      status: 409,
      code: "ASK_AGAIN_NOT_OFFERED",
    });
  });
});

describe("the refusal asking again is told apart by", () => {
  it.each([
    ["STEP_MOVED_ON", 409, true, true],
    ["RUN_STOPPED", 409, true, false],
    ["ENTRY_STOPPED", 409, true, false],
    ["ASK_AGAIN_NOT_OFFERED", 409, true, false],
    ["ACT_NOT_PERMITTED", 403, false, false],
    ["BODY_UNUSABLE", 400, false, false],
    ["STEP_NOT_IN_VIEW", 404, false, false],
    ["REVIEW_NOT_A_PERSONS", 409, false, false],
  ] as const)(
    "tells %s apart as changed since it was read, and as acted on first, or not",
    (code, status, changed, movedOn) => {
      const problem: Problem = { status, code };

      expect(refusedAsChanged(problem)).toBe(changed);
      expect(refusedAsMovedOn(problem)).toBe(movedOn);
    },
  );

  it("takes STEP_MOVED_ON minted here, with no status, as no change and nobody acting first", () => {
    expect(refusedAsChanged({ code: "STEP_MOVED_ON" })).toBe(false);
    expect(refusedAsMovedOn({ code: "STEP_MOVED_ON" })).toBe(false);
  });
});
