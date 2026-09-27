import { describe, expect, it } from "vitest";

import { answering, refusalOf } from "../../../../testutil/answering";
import { NOT_A_PROBLEM_DOCUMENT, NOT_AN_ADDRESS } from "../../../problem";
import {
  atRun,
  offers,
  readRun,
  refusedForWhatMoved,
  renameRun,
  runFrom,
} from "./{runId}";

const GROUP = "00000003-0000-4000-8000-000000000c11";

const RUN = "00000008-0000-4000-8000-000000000c11";

const ABOVE = "00000008-0000-4000-8000-000000000c12";

const ADA = { userId: "000c11", displayName: "Ada Lovelace" };

/** Every member a run can carry, each count past what a number holds exactly. */
const EVERYTHING = {
  runId: RUN,
  number: 7,
  name: "Claim from Ada",
  workflow: {
    entryId: "00000006-0000-4000-8000-000000000c11",
    name: "Handle a claim",
    version: 3,
  },
  startedBy: ADA,
  startedAt: "2026-09-25T08:00:00Z",
  startedWith: {
    complaint: "Kettle leaks",
    due: null,
    items: [{ name: "Kettle", count: "1" }],
  },
  state: "stopped",
  stopped: { at: "2026-09-25T08:01:00Z", by: { userId: "000c12" } },
  spend: {
    sent: "9007199254740993",
    cameBack: "20",
    spent: "9007199254741013",
    cameBackUnknown: true,
    measuredHere: true,
  },
  ceiling: {
    inForce: "9007199254740990",
    raiseNeedsApproval: true,
    waiting: {
      changeId: "0000000b-0000-4000-8000-000000000c11",
      to: "9007199254740991",
      askedBy: ADA,
      askedAt: "2026-09-25T08:02:00Z",
    },
  },
  acts: ["open_again", "withdraw_raise"],
};

/** Beneath another, stopped by a ceiling, held to the one at the top, and carrying nothing it may leave out. */
const BARE = {
  runId: RUN,
  number: 8,
  above: ABOVE,
  workflow: {
    entryId: "00000006-0000-4000-8000-000000000c11",
    name: "Handle a claim",
    version: 3,
  },
  startedAt: "2026-09-25T08:00:00Z",
  state: "stopped",
  stopped: {
    at: "2026-09-25T08:01:00Z",
    ceilingOf: { runId: ABOVE, number: 7 },
  },
  spend: { sent: "0", cameBack: "0", spent: "0", cameBackUnknown: false },
  ceiling: { heldBy: { runId: ABOVE, number: 7 } },
  acts: [],
};

describe("atRun", () => {
  it("hands over the run's address under its group, each identifier escaped", async () => {
    const addresses = [RUN, "a/b"].map((run) =>
      atRun(GROUP, run, (address) => Promise.resolve(address)),
    );

    expect(await Promise.all(addresses)).toEqual([
      `/api/groups/${GROUP}/runs/${RUN}`,
      `/api/groups/${GROUP}/runs/a%2Fb`,
    ]);
  });

  it("refuses a run that names no address, asking nothing", async () => {
    const failure = await refusalOf(
      atRun(GROUP, "..", () => Promise.resolve("asked")),
    );

    expect(failure.problem).toEqual({ code: NOT_AN_ADDRESS });
  });
});

describe("readRun", () => {
  it("reads the run at its address, every count kept as the digits it came as", async () => {
    const sent = answering([JSON.stringify(EVERYTHING), 200]);

    const read = await readRun(GROUP, RUN, new AbortController().signal);

    expect(sent.mock.calls[0]![0]).toBe(`/api/groups/${GROUP}/runs/${RUN}`);
    expect(read).toEqual({
      ...EVERYTHING,
      acts: new Set(["open_again", "withdraw_raise"]),
    });
  });

  it("refuses a run it cannot draw, as a document this side cannot read", async () => {
    answering([JSON.stringify({ ...EVERYTHING, acts: undefined }), 200]);

    const failure = await refusalOf(
      readRun(GROUP, RUN, new AbortController().signal),
    );

    expect(failure.problem).toEqual({
      status: 200,
      code: NOT_A_PROBLEM_DOCUMENT,
    });
  });
});

describe("renameRun", () => {
  it("asks PATCH of the run with its name and nothing else, and reads the run it answers with", async () => {
    const sent = answering([JSON.stringify(EVERYTHING), 200]);

    const read = await renameRun(
      GROUP,
      RUN,
      "Claim from Ada, again",
      new AbortController().signal,
    );

    const [address, init] = sent.mock.calls[0]!;
    expect(address).toBe(`/api/groups/${GROUP}/runs/${RUN}`);
    expect(init?.method).toBe("PATCH");
    expect(JSON.parse(String(init?.body))).toEqual({
      name: "Claim from Ada, again",
    });
    expect(read.runId).toBe(RUN);
  });
});

describe("offers", () => {
  it.each([
    ["open_again", true],
    ["withdraw_raise", true],
    ["stop", false],
    ["approve_raise", false],
  ] as const)("says whether the server offers %s", (act, offered) => {
    expect(offers(runFrom(EVERYTHING)!, act)).toBe(offered);
  });
});

describe("runFrom", () => {
  it("reads a run leaving out what it may, and adds nothing in its place", () => {
    const read = runFrom(BARE);

    expect(read).toEqual({ ...BARE, acts: new Set() });
    expect(Object.keys(read!).toSorted()).toEqual(Object.keys(BARE).toSorted());
    expect(read!.spend).not.toHaveProperty("measuredHere");
  });

  it("reads the step a running run is on, by its name", () => {
    const { stopped: _stopped, ...unstopped } = BARE;
    const running = {
      ...unstopped,
      state: "running",
      at: { stepId: "00000009-0000-4000-8000-000000000c11", name: "triage" },
    };

    const read = runFrom(running);

    expect(read?.at).toEqual(running.at);
    expect(read?.state).toBe("running");
    expect(read).not.toHaveProperty("stopped");
  });

  it("reads a run whose workflow takes nothing as started with no fields, which is not none said", () => {
    const read = runFrom({ ...EVERYTHING, startedWith: {} });

    expect(read?.startedWith).toEqual({});
    expect(runFrom(BARE)).not.toHaveProperty("startedWith");
  });

  it.each([
    ["no object at all", "run"],
    [
      "a count sent as a number",
      { ...BARE, spend: { ...BARE.spend, sent: 0 } },
    ],
    [
      "a count that is no digits",
      { ...BARE, spend: { ...BARE.spend, spent: "-1" } },
    ],
    [
      "a count leading with a zero",
      { ...BARE, spend: { ...BARE.spend, cameBack: "01" } },
    ],
    [
      "a spend said to be measured here as false, which is only ever left out",
      { ...BARE, spend: { ...BARE.spend, measuredHere: false } },
    ],
    [
      "a spend said to be measured here in text",
      { ...BARE, spend: { ...BARE.spend, measuredHere: "true" } },
    ],
    [
      "a ceiling sent as a number",
      { ...BARE, ceiling: { raiseNeedsApproval: false, inForce: 5 } },
    ],
    ["a ceiling saying nothing of approval", { ...BARE, ceiling: {} }],
    [
      "a ceiling held at the top and of its own at once",
      {
        ...BARE,
        ceiling: { ...BARE.ceiling, raiseNeedsApproval: false },
      },
    ],
    [
      "a ceiling held at the top by no run",
      { ...BARE, ceiling: { heldBy: { number: 7 } } },
    ],
    [
      "a stop by somebody and by a ceiling",
      { ...BARE, stopped: { ...BARE.stopped, by: ADA } },
    ],
    [
      "a stop by nobody and by no ceiling",
      { ...BARE, stopped: { at: "2026-09-25T08:01:00Z" } },
    ],
    ["a stop sent as null", { ...BARE, stopped: null }],
    [
      "a step it is on with no name",
      { ...BARE, at: { stepId: "00000009-0000-4000-8000-000000000c11" } },
    ],
    ["a step it is on sent as null", { ...BARE, at: null }],
    [
      "a raise asked by nobody",
      {
        ...EVERYTHING,
        ceiling: {
          ...EVERYTHING.ceiling,
          waiting: { ...EVERYTHING.ceiling.waiting, askedBy: {} },
        },
      },
    ],
    [
      "a raise to a count that is no digits",
      {
        ...EVERYTHING,
        ceiling: {
          ...EVERYTHING.ceiling,
          waiting: { ...EVERYTHING.ceiling.waiting, to: 5 },
        },
      },
    ],
    [
      "a starter with a name that is no text",
      { ...EVERYTHING, startedBy: { userId: "000c11", displayName: 7 } },
    ],
    ["a name that is no text", { ...EVERYTHING, name: 7 }],
    [
      "what it was started with holding a number sent as one",
      { ...EVERYTHING, startedWith: { count: 3 } },
    ],
    [
      "what it was started with sent as a list",
      { ...EVERYTHING, startedWith: ["Kettle leaks"] },
    ],
    [
      "what it was started with sent as null",
      { ...EVERYTHING, startedWith: null },
    ],
    [
      "a workflow with no version",
      { ...BARE, workflow: { ...BARE.workflow, version: "3" } },
    ],
    ["a number that is no whole number", { ...BARE, number: 7.5 }],
  ])("refuses %s rather than drawing part of it", (_case, body) => {
    expect(runFrom(body)).toBeNull();
  });
});

describe("refusedForWhatMoved", () => {
  it.each([
    "RUN_NOT_IN_VIEW",
    "RUN_BENEATH_ANOTHER",
    "CEILING_RAISE_NOT_WAITING",
    "CEILING_RAISE_ASKED_BY_CALLER",
    "CEILING_RAISE_ASKED_BY_ANOTHER",
  ])(
    "says the run moved under the page where the server answered %s",
    (code) => {
      expect(refusedForWhatMoved({ status: 409, code })).toBe(true);
    },
  );

  it.each([
    ["a refusal of the act", { status: 403, code: "ACT_NOT_PERMITTED" }],
    ["a ceiling refused", { status: 400, code: "CEILING_UNUSABLE" }],
    [
      "a code of the same spelling this side minted",
      { code: "RUN_NOT_IN_VIEW" },
    ],
  ])("says nothing moved for %s", (_case, problem) => {
    expect(refusedForWhatMoved(problem)).toBe(false);
  });
});
