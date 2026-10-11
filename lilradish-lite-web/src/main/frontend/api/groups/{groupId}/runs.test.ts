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
  name: "Refund for Glinn",
  workflow: { versionName: null, name: "Pay a refund", version: 3 },
  startedBy: { userId: "000c21", displayName: "Ann Example" },
  startedAt: "2026-09-25T09:00:00Z",
  state: "stopped",
};

/** Running, on its first step. */
const NAMELESS_STARTER = {
  runId: "00000008-0000-4000-8000-000000000c22",
  number: 1,
  name: "Claim from Avo",
  workflow: { versionName: null, name: "Handle a claim", version: 1 },
  startedBy: { userId: "000c22" },
  startedAt: "2026-09-25T08:00:00Z",
  state: "running",
  at: "summarise",
};

describe("startRun", () => {
  const START = {
    name: "Kettle arrived broken",
    versionId: "00000007-0000-4000-8000-000000000c21",
    uploads: [],
    hasFilePositions: false,
    values: {
      complaint: "It leaks.",
      amount: "12.50",
      urgent: "true",
      due: null,
      tags: [],
      contact: { email: "avo@example.org", phones: ["0123"] },
    },
  };

  it("asks POST of the group's runs with the name, the version and every value as written, and reads the run begun", async () => {
    const sent = answering([
      JSON.stringify({ runId: STOPPED.runId, number: 7 }),
      201,
    ]);
    const controller = new AbortController();

    const started = await startRun(GROUP, START, controller.signal);

    expect(sent).toHaveBeenCalledTimes(1);
    const [address, init] = sent.mock.calls[0]!;
    expect(address).toBe(`/api/groups/${GROUP}/runs`);
    expect(init?.method).toBe("POST");
    expect(init?.signal).toBe(controller.signal);
    expect(JSON.parse(String(init?.body))).toEqual({
      name: START.name,
      versionId: START.versionId,
      values: START.values,
    });
    expect(started).toEqual({
      runId: STOPPED.runId,
      number: 7,
    });
  });

  it("sends a file run as a first JSON document and one fixed-name part without a client file id", async () => {
    const sent = answering([
      JSON.stringify({ runId: STOPPED.runId, number: 7 }),
      201,
    ]);
    const file = new File(["data"], 'Demo Sample %"/文.pdf', {
      type: "application/pdf",
    });
    const start = {
      name: "Demo Sample",
      versionId: START.versionId,
      values: {
        attachment: { upload: 0, name: file.name },
      },
      uploads: [file],
      hasFilePositions: true,
    };

    await startRun(GROUP, start, new AbortController().signal);

    const [address, init] = sent.mock.calls[0]!;
    expect(address).toBe(`/api/groups/${GROUP}/runs`);
    expect(init?.method).toBe("POST");
    expect(init?.headers).toEqual({ Accept: "application/json" });
    expect(init?.body).toBeInstanceOf(FormData);
    const parts = [...(init!.body as FormData).entries()];
    expect(parts.map(([name]) => name)).toEqual(["document", "upload0"]);
    expect(JSON.parse(String(parts[0]![1]))).toEqual({
      name: start.name,
      versionId: start.versionId,
      values: { attachment: { upload: 0, name: file.name } },
    });
    expect(JSON.stringify(parts[0]![1])).not.toContain("id");
    expect(parts[1]![1]).toBeInstanceOf(File);
    expect((parts[1]![1] as File).name).toBe("file");
    expect((parts[1]![1] as File).type).toBe("application/pdf");
  });

  it("reads only the new run identity without a reader-specific decision", async () => {
    answering([JSON.stringify({ runId: STOPPED.runId, number: 7 }), 201]);

    const started = await startRun(GROUP, START, new AbortController().signal);

    expect(started).toEqual({
      runId: STOPPED.runId,
      number: 7,
    });
  });

  it.each([undefined, true, false, "true"])(
    "ignores an extra readable member %s",
    async (readable) => {
      answering([
        JSON.stringify({ runId: STOPPED.runId, number: 7, readable }),
        201,
      ]);
      expect(
        await startRun(GROUP, START, new AbortController().signal),
      ).toEqual({ runId: STOPPED.runId, number: 7 });
    },
  );

  it("refuses a group that names no address, asking nothing", async () => {
    const sent = answering(['{"runId":"r","number":1}', 201]);

    const failure = await refusalOf(
      startRun("..", START, new AbortController().signal),
    );

    expect(failure.problem).toEqual({ code: NOT_AN_ADDRESS });
    expect(sent).not.toHaveBeenCalled();
  });

  it.each([
    ["no run", { number: 7 }],
    ["no number", { runId: STOPPED.runId }],
    ["a number that is no whole number", { runId: STOPPED.runId, number: 1.5 }],
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
  it("reads the current step's display name separately from its machine name", async () => {
    answering([
      JSON.stringify({
        items: [{ ...NAMELESS_STARTER, atLabel: "審査する\u202e" }],
        reading: "own",
      }),
      200,
    ]);

    const page = await runsLoader(GROUP, { filter: "", order: "-started" })(
      null,
      new AbortController().signal,
    );

    expect(page).toEqual({
      items: [{ ...NAMELESS_STARTER, atLabel: "審査する\u202e" }],
    });
    expect(page.items[0]!.at).toBe("summarise");
    expect(page.items[0]!.atLabel).toBe("審査する\u202e");
  });
  it("asks for the group's runs in the order and with the filter asked, from where the page left off", async () => {
    const sent = answering(['{"items":[]}', 200]);

    await runsLoader(GROUP, { filter: "", order: "-started" })(
      null,
      new AbortController().signal,
    );
    await runsLoader("a/b", { filter: "Avo", order: "startedBy" })(
      "c1",
      new AbortController().signal,
    );

    expect(sent.mock.calls.map(([address]) => address)).toEqual([
      `/api/groups/${GROUP}/runs?sort=-started`,
      "/api/groups/a%2Fb/runs?sort=startedBy&filter=Avo&cursor=c1",
    ]);
  });

  it("reads every run, a starter's name only where one arrived, and the step only a running run is on", async () => {
    answering([
      JSON.stringify({
        items: [STOPPED, NAMELESS_STARTER],
        nextCursor: "c2",
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
    });
    expect(Object.keys(page.items[1]!.startedBy)).toEqual(["userId"]);
    expect(Object.hasOwn(page.items[0]!, "at")).toBe(false);
  });

  it.each(["failed", "done", "paused"])(
    "reads a run that has %s, on no step, whether or not this build has heard of it",
    async (state) => {
      answering([JSON.stringify({ items: [{ ...STOPPED, state }] }), 200]);

      const page = await runsLoader(GROUP, { filter: "", order: "-started" })(
        null,
        new AbortController().signal,
      );

      expect(page.items).toEqual([{ ...STOPPED, state }]);
    },
  );

  it("refuses a group that names no address, asking nothing", async () => {
    const sent = answering(['{"items":[]}', 200]);

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
    [
      "a step display name written as null",
      { ...NAMELESS_STARTER, atLabel: null },
    ],
    [
      "a step display name that is a number",
      { ...NAMELESS_STARTER, atLabel: 1 },
    ],
    [
      "a step display name that is a boolean",
      { ...NAMELESS_STARTER, atLabel: false },
    ],
    [
      "a step display name that is an object",
      { ...NAMELESS_STARTER, atLabel: {} },
    ],
    [
      "a step display name that is an array",
      { ...NAMELESS_STARTER, atLabel: [] },
    ],
    [
      "a display name without its machine step",
      { ...STOPPED, atLabel: "審査" },
    ],
    ["no time it started", { ...STOPPED, startedAt: 9 }],
  ])(
    "refuses a page holding a run with %s, as a document this side cannot read",
    async (_case, row) => {
      answering([JSON.stringify({ items: [NAMELESS_STARTER, row] }), 200]);

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
    "reads a page saying %s without using its reading member",
    async (_case, body) => {
      answering([JSON.stringify(body), 200]);

      const page = await runsLoader(GROUP, { filter: "", order: "-started" })(
        null,
        new AbortController().signal,
      );
      expect(page).toEqual({ items: [STOPPED] });
    },
  );
});
