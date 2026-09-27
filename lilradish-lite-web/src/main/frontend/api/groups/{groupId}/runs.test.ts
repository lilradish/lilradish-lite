import { describe, expect, it } from "vitest";

import { answering, refusalOf } from "../../../testutil/answering";
import { NOT_A_PROBLEM_DOCUMENT, NOT_AN_ADDRESS } from "../../problem";
import {
  refusedAsNotOffered,
  refusedForTheName,
  runsLoader,
  startRun,
} from "./runs";

const GROUP = "00000003-0000-4000-8000-000000000c21";

const STOPPED = {
  runId: "00000008-0000-4000-8000-000000000c21",
  number: 2,
  name: "Refund for Grace",
  workflow: { name: "Pay a refund", version: 3 },
  startedBy: { userId: "000c21", displayName: "Ann Example" },
  startedAt: "2026-09-25T09:00:00Z",
  lastHappenedAt: "2026-09-25T09:30:00Z",
  state: "stopped",
};

/** Running, on its first step. */
const NAMELESS_STARTER = {
  runId: "00000008-0000-4000-8000-000000000c22",
  number: 1,
  name: "Claim from Ada",
  workflow: { name: "Handle a claim", version: 1 },
  startedBy: { userId: "000c22" },
  startedAt: "2026-09-25T08:00:00Z",
  lastHappenedAt: "2026-09-25T08:00:00Z",
  state: "running",
  at: "summarise",
};

describe("startRun", () => {
  const START = {
    name: "Kettle arrived broken",
    versionId: "00000007-0000-4000-8000-000000000c21",
    values: {
      complaint: "It leaks.",
      amount: "12.50",
      urgent: "true",
      due: null,
      tags: [],
      contact: { email: "ada@example.org", phones: ["0123"] },
    },
  };

  it("asks POST of the group's runs with the name, the version and every value as written, and reads the run begun", async () => {
    const sent = answering([
      JSON.stringify({ runId: STOPPED.runId, number: 7, readable: true }),
      201,
    ]);

    const started = await startRun(GROUP, START, new AbortController().signal);

    const [address, init] = sent.mock.calls[0]!;
    expect(address).toBe(`/api/groups/${GROUP}/runs`);
    expect(init?.method).toBe("POST");
    expect(JSON.parse(String(init?.body))).toEqual(START);
    expect(started).toEqual({
      runId: STOPPED.runId,
      number: 7,
      readable: true,
    });
  });

  it("reads a run begun that the reader may not read as one they may not", async () => {
    answering([
      JSON.stringify({ runId: STOPPED.runId, number: 7, readable: false }),
      201,
    ]);

    const started = await startRun(GROUP, START, new AbortController().signal);

    expect(started).toEqual({
      runId: STOPPED.runId,
      number: 7,
      readable: false,
    });
  });

  it("refuses a group that names no address, asking nothing", async () => {
    const sent = answering(['{"runId":"r","number":1,"readable":true}', 201]);

    const failure = await refusalOf(
      startRun("..", START, new AbortController().signal),
    );

    expect(failure.problem).toEqual({ code: NOT_AN_ADDRESS });
    expect(sent).not.toHaveBeenCalled();
  });

  it.each([
    ["no run", { number: 7, readable: true }],
    ["no number", { runId: STOPPED.runId, readable: true }],
    [
      "a number that is no whole number",
      { runId: STOPPED.runId, number: 1.5, readable: true },
    ],
    ["no word on whether it may be read", { runId: STOPPED.runId, number: 7 }],
    [
      "whether it may be read written as text",
      { runId: STOPPED.runId, number: 7, readable: "true" },
    ],
  ])(
    "refuses an answer holding %s, as a document this side cannot read",
    async (_case, body) => {
      answering([JSON.stringify(body), 201]);

      const failure = await refusalOf(
        startRun(GROUP, START, new AbortController().signal),
      );

      expect(failure.problem).toEqual({
        status: 201,
        code: NOT_A_PROBLEM_DOCUMENT,
      });
    },
  );
});

describe("refusedAsNotOffered and refusedForTheName", () => {
  it.each([
    [{ status: 409, code: "WORKFLOW_NOT_OFFERED" }, true, false],
    [{ status: 400, code: "RUN_NAME_UNUSABLE" }, false, true],
    [{ status: 400, code: "VALUE_DOES_NOT_FIT" }, false, false],
    [{ code: "WORKFLOW_NOT_OFFERED" }, false, false],
    [{ code: "RUN_NAME_UNUSABLE" }, false, false],
  ])(
    "reads %j as a version not offered: %s, and as a name refused: %s",
    (problem, notOffered, nameRefused) => {
      expect(refusedAsNotOffered(problem)).toBe(notOffered);
      expect(refusedForTheName(problem)).toBe(nameRefused);
    },
  );
});

describe("runsLoader", () => {
  it("asks for the group's runs in the order and with the filter asked, from where the page left off", async () => {
    const sent = answering(['{"items":[],"reading":"all"}', 200]);

    await runsLoader(GROUP, { filter: "", order: "-lastHappened" })(
      null,
      new AbortController().signal,
    );
    await runsLoader("a/b", { filter: "Ada", order: "startedBy" })(
      "c1",
      new AbortController().signal,
    );

    expect(sent.mock.calls.map(([address]) => address)).toEqual([
      `/api/groups/${GROUP}/runs?sort=-lastHappened`,
      "/api/groups/a%2Fb/runs?sort=startedBy&filter=Ada&cursor=c1",
    ]);
  });

  it("reads every run and which runs the server read, a starter's name only where one arrived, and the step only a running run is on", async () => {
    answering([
      JSON.stringify({
        items: [STOPPED, NAMELESS_STARTER],
        nextCursor: "c2",
        reading: "own",
      }),
      200,
    ]);

    const page = await runsLoader(GROUP, { filter: "", order: "-started" })(
      null,
      new AbortController().signal,
    );

    expect(page).toEqual({
      items: [STOPPED, NAMELESS_STARTER],
      nextCursor: "c2",
      reading: "own",
    });
    expect(Object.keys(page.items[1]!.startedBy)).toEqual(["userId"]);
    expect(Object.hasOwn(page.items[0]!, "at")).toBe(false);
  });

  it.each(["failed", "done", "paused"])(
    "reads a run that has %s, on no step, whether or not this build has heard of it",
    async (state) => {
      answering([
        JSON.stringify({ items: [{ ...STOPPED, state }], reading: "all" }),
        200,
      ]);

      const page = await runsLoader(GROUP, { filter: "", order: "-started" })(
        null,
        new AbortController().signal,
      );

      expect(page.items).toEqual([{ ...STOPPED, state }]);
    },
  );

  it("refuses a group that names no address, asking nothing", async () => {
    const sent = answering(['{"items":[],"reading":"all"}', 200]);

    const failure = await refusalOf(
      runsLoader("..", { filter: "", order: "-started" })(
        null,
        new AbortController().signal,
      ),
    );

    expect(failure.problem).toEqual({ code: NOT_AN_ADDRESS });
    expect(sent).not.toHaveBeenCalled();
  });

  it.each([
    ["a number that is no whole number", { ...STOPPED, number: 2.5 }],
    ["no name", { ...STOPPED, name: undefined }],
    ["a workflow with no version", { ...STOPPED, workflow: { name: "Pay" } }],
    ["nobody who started it", { ...STOPPED, startedBy: undefined }],
    ["no state", { ...STOPPED, state: undefined }],
    ["a state that is no word", { ...STOPPED, state: 1 }],
    ["a step written as null", { ...STOPPED, at: null }],
    ["a step that is no word", { ...STOPPED, at: 1 }],
    ["no time it started", { ...STOPPED, startedAt: 9 }],
    ["no time it was last acted on", { ...STOPPED, lastHappenedAt: undefined }],
  ])(
    "refuses a page holding a run with %s, as a document this side cannot read",
    async (_case, row) => {
      answering([
        JSON.stringify({ items: [NAMELESS_STARTER, row], reading: "all" }),
        200,
      ]);

      const failure = await refusalOf(
        runsLoader(GROUP, { filter: "", order: "-started" })(
          null,
          new AbortController().signal,
        ),
      );

      expect(failure.problem).toEqual({
        status: 200,
        code: NOT_A_PROBLEM_DOCUMENT,
      });
    },
  );

  it.each([
    ["no reading", { items: [STOPPED] }],
    ["a reading not heard of", { items: [STOPPED], reading: "some" }],
    ["a reading that is no word", { items: [STOPPED], reading: 1 }],
  ])(
    "refuses a page saying %s, whose empty list it could not say the truth of",
    async (_case, body) => {
      answering([JSON.stringify(body), 200]);

      const failure = await refusalOf(
        runsLoader(GROUP, { filter: "", order: "-started" })(
          null,
          new AbortController().signal,
        ),
      );

      expect(failure.problem).toEqual({
        status: 200,
        code: NOT_A_PROBLEM_DOCUMENT,
      });
    },
  );
});
