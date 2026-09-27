import { describe, expect, it } from "vitest";

import { answering, refusalOf } from "../../../../../../../testutil/answering";
import { NOT_AN_ADDRESS, type Problem } from "../../../../../../problem";
import { refusedAsChanged, refusedAsMovedOn, trySending } from "./sending";

const GROUP = "00000003-0000-4000-8000-000000000cf1";

const RUN = "00000008-0000-4000-8000-000000000cf1";

const STEP = "00000009-0000-4000-8000-000000000cf1";

const VERSION = "00000007-0000-4000-8000-000000000cf1";

const NOTHING_SPENT = {
  callsAModel: true,
  sent: "0",
  cameBack: "0",
  spent: "0",
  cameBackUnknown: false,
};

/** The step as the server reads it before what was pressed is sent: held back, its one try turned away. */
const HELD = {
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
    producer: { kind: "model", model: "small" },
    state: "held_back",
    where: {
      kind: "held_back",
      reason: "turned_away",
      since: "2026-09-26T10:00:00Z",
      waitsOn: "starter",
    },
    takesFrom: [],
    tries: { current: 1, declared: 2, beyond: false },
    cost: NOTHING_SPENT,
    acts: ["try_sending"],
    withheld: [],
  },
  triesMade: [
    {
      number: 1,
      beyond: false,
      producedBy: { kind: "model", model: "small" },
      values: [],
      review: { asked: false },
      ended: "open",
      cost: NOTHING_SPENT,
    },
  ],
};

describe("trySending", () => {
  it("asks PUT of the step's sending, sending no body and no media type, and reads the step it answers with", async () => {
    const sent = answering([JSON.stringify(HELD), 200]);

    const read = await trySending(
      GROUP,
      RUN,
      STEP,
      new AbortController().signal,
    );

    const [address, init] = sent.mock.calls[0]!;
    expect(address).toBe(
      `/api/groups/${GROUP}/runs/${RUN}/steps/${STEP}/sending`,
    );
    expect(init?.method).toBe("PUT");
    expect(init?.body).toBeUndefined();
    expect(new Headers(init?.headers).has("Content-Type")).toBe(false);
    expect(sent).toHaveBeenCalledTimes(1);
    expect(read.step.where?.reason).toBe("turned_away");
    expect(read.step.acts).toEqual(["try_sending"]);
    expect(read.triesMade.map((each) => each.ended)).toEqual(["open"]);
  });

  it("escapes the step as one segment of the address", async () => {
    const sent = answering([JSON.stringify(HELD), 200]);

    await trySending(GROUP, RUN, "a/b", new AbortController().signal);

    expect(sent.mock.calls[0]![0]).toBe(
      `/api/groups/${GROUP}/runs/${RUN}/steps/a%2Fb/sending`,
    );
  });

  it("refuses a step that names no address, asking nothing", async () => {
    const sent = answering([JSON.stringify(HELD), 200]);

    const failure = await refusalOf(
      trySending(GROUP, RUN, "..", new AbortController().signal),
    );

    expect(failure.problem).toEqual({ code: NOT_AN_ADDRESS });
    expect(sent).not.toHaveBeenCalled();
  });

  it("hands on the server's refusal as it came", async () => {
    answering([JSON.stringify({ code: "TRY_SENDING_NOT_OFFERED" }), 409]);

    const failure = await refusalOf(
      trySending(GROUP, RUN, STEP, new AbortController().signal),
    );

    expect(failure.problem).toMatchObject({
      status: 409,
      code: "TRY_SENDING_NOT_OFFERED",
    });
  });
});

describe("the refusal trying to send is told apart by", () => {
  it.each([
    ["STEP_MOVED_ON", 409, true, true],
    ["TRY_SENDING_NOT_OFFERED", 409, true, false],
    ["RUN_STOPPED", 409, true, false],
    ["ENTRY_STOPPED", 409, true, false],
    ["ACT_NOT_PERMITTED", 403, false, false],
    ["BODY_UNUSABLE", 400, false, false],
    ["STEP_NOT_IN_VIEW", 404, false, false],
    ["ASK_AGAIN_NOT_OFFERED", 409, false, false],
  ] as const)(
    "tells %s apart as changed since it was read, and as moved on, or not",
    (code, status, changed, movedOn) => {
      const problem: Problem = { status, code };

      expect(refusedAsChanged(problem)).toBe(changed);
      expect(refusedAsMovedOn(problem)).toBe(movedOn);
    },
  );

  it("takes STEP_MOVED_ON minted here, with no status, as no change and nothing moved on", () => {
    expect(refusedAsChanged({ code: "STEP_MOVED_ON" })).toBe(false);
    expect(refusedAsMovedOn({ code: "STEP_MOVED_ON" })).toBe(false);
  });
});
