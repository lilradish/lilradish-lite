import { describe, expect, it } from "vitest";

import { answering, refusalOf } from "../../../../testutil/answering";
import { NOT_A_PROBLEM_DOCUMENT, NOT_AN_ADDRESS } from "../../../problem";
import {
  atRun,
  readRun,
  refusedForWhatMoved,
  renameRun,
  runFrom,
} from "./{runId}";

const GROUP = "00000003-0000-4000-8000-000000000c11";

const RUN = "00000008-0000-4000-8000-000000000c11";

const ABOVE = "00000008-0000-4000-8000-000000000c12";

const AVO = { userId: "000c11", displayName: "Avo Lumerace" };

const EVERYTHING = {
  runId: RUN,
  number: 7,
  name: "Claim from Avo",
  workflow: {
    versionName: null,
    entryId: "00000006-0000-4000-8000-000000000c11",
    name: "Handle a claim",
    version: 3,
  },
  startedBy: AVO,
  runsAbove: [],
  startedAt: "2026-09-25T08:00:00Z",
  conversationEvents: [],
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
    measuredHere: "partly",
  },
  ceiling: {
    inForce: "9007199254740990",
    raiseNeedsApproval: true,
    waiting: {
      changeId: "0000000b-0000-4000-8000-000000000c11",
      to: "9007199254740991",
      askedBy: AVO,
      askedAt: "2026-09-25T08:02:00Z",
    },
  },
  stateActs: ["open_again", "withdraw_raise"],
};

/** Beneath another, stopped by a ceiling, held to the one at the top, and carrying nothing it may leave out. */
const BARE = {
  runId: RUN,
  number: 8,
  above: ABOVE,
  runsAbove: [{ runId: ABOVE, number: 7 }],
  from: {
    runId: ABOVE,
    number: 7,
    stepId: "00000009-0000-4000-8000-000000000c11",
    stepName: "route",
  },
  workflow: {
    versionName: null,
    entryId: "00000006-0000-4000-8000-000000000c11",
    name: "Handle a claim",
    version: 3,
  },
  startedAt: "2026-09-25T08:00:00Z",
  conversationEvents: [],
  state: "stopped",
  stopped: {
    at: "2026-09-25T08:01:00Z",
    ceilingOf: { runId: ABOVE, number: 7 },
  },
  spend: { sent: "0", cameBack: "0", spent: "0", cameBackUnknown: false },
  ceiling: { heldBy: { runId: ABOVE, number: 7 } },
  stateActs: [],
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
      stateActs: new Set(["open_again", "withdraw_raise"]),
    });
  });

  it("reads a child whose parent and starting step name the same run", async () => {
    answering([JSON.stringify(BARE), 200]);

    const read = await readRun(GROUP, RUN, new AbortController().signal);

    expect(read).toEqual({ ...BARE, stateActs: new Set() });
    expect(read.above).toBe(ABOVE);
    expect(read.from?.runId).toBe(ABOVE);
  });

  it.each([
    ["a parent without its starting step", { from: undefined }],
    ["a starting step without its parent", { above: undefined }],
  ])("refuses %s at the GET response boundary", async (_case, missing) => {
    answering([JSON.stringify({ ...BARE, ...missing }), 200]);

    const failure = await refusalOf(
      readRun(GROUP, RUN, new AbortController().signal),
    );

    expect(failure.problem).toEqual({
      status: 200,
      code: NOT_A_PROBLEM_DOCUMENT,
    });
  });

  it("refuses a child whose starting step names another parent at the GET response boundary", async () => {
    answering([
      JSON.stringify({ ...BARE, from: { ...BARE.from, runId: RUN } }),
      200,
    ]);

    const failure = await refusalOf(
      readRun(GROUP, RUN, new AbortController().signal),
    );

    expect(failure.problem).toEqual({
      status: 200,
      code: NOT_A_PROBLEM_DOCUMENT,
    });
  });

  it("refuses a run it cannot draw, as a document this side cannot read", async () => {
    answering([JSON.stringify({ ...EVERYTHING, stateActs: undefined }), 200]);

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
      "Claim from Avo, again",
      new AbortController().signal,
    );

    const [address, init] = sent.mock.calls[0]!;
    expect(address).toBe(`/api/groups/${GROUP}/runs/${RUN}`);
    expect(init?.method).toBe("PATCH");
    expect(JSON.parse(String(init?.body))).toEqual({
      name: "Claim from Avo, again",
    });
    expect(read.runId).toBe(RUN);
  });
});

describe("run state facts", () => {
  it.each([
    ["open_again", true],
    ["withdraw_raise", true],
    ["stop", false],
    ["approve_raise", false],
  ] as const)("says whether the server offers %s", (act, offered) => {
    expect(runFrom(EVERYTHING)!.stateActs?.has(act)).toBe(offered);
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

describe("runFrom", () => {
  it.each([null, []])(
    "keeps mandatory nullable state acts %j distinct",
    (stateActs) => {
      expect(runFrom({ ...EVERYTHING, stateActs })?.stateActs).toEqual(
        stateActs === null ? null : new Set(),
      );
    },
  );

  it.each([undefined, "stop", {}, ["stop", 7]])(
    "rejects malformed run state acts %j",
    (stateActs) => {
      expect(runFrom({ ...EVERYTHING, stateActs })).toBeNull();
    },
  );

  it("keeps unknown resource state words without granting a known control", () => {
    expect(
      runFrom({ ...EVERYTHING, stateActs: ["future_action"] })?.stateActs,
    ).toEqual(new Set(["future_action"]));
  });

  it("keeps parent and current display names beside their distinct machine identifiers", () => {
    const { stopped: _stopped, ...moving } = BARE;
    const body = {
      ...moving,
      state: "running",
      from: { ...BARE.from, label: "Parent 元\u202e" },
      at: { stepId: "current-step", name: "file_claim", label: "Current 元" },
    };

    const run = runFrom(body);

    expect(run?.from).toEqual({
      runId: ABOVE,
      number: 7,
      stepId: BARE.from.stepId,
      stepName: "route",
      label: "Parent 元\u202e",
    });
    expect(run?.at).toEqual({
      stepId: "current-step",
      name: "file_claim",
      label: "Current 元",
    });
    expect(runFrom(BARE)?.from).toEqual(BARE.from);
    expect(Object.hasOwn(runFrom(BARE)!.from!, "label")).toBe(false);
  });

  it.each([null, 7, false, [], { value: "Shown" }])(
    "refuses malformed parent or current step display metadata: %j",
    (label) => {
      const { stopped: _stopped, ...moving } = BARE;
      expect(runFrom({ ...BARE, from: { ...BARE.from, label } })).toBeNull();
      expect(
        runFrom({
          ...moving,
          state: "running",
          at: { stepId: "current-step", name: "file_claim", label },
        }),
      ).toBeNull();
    },
  );
  it("refuses parent or current display text without its machine name", () => {
    const { stopped: _stopped, ...moving } = BARE;

    expect(
      runFrom({
        ...BARE,
        from: { ...BARE.from, stepName: undefined, label: "Parent 元" },
      }),
    ).toBeNull();
    expect(
      runFrom({
        ...moving,
        state: "running",
        at: { stepId: "current-step", label: "Current 元" },
      }),
    ).toBeNull();
  });
  it("refuses a run whose conversation facts are absent", () => {
    const { conversationEvents: _missing, ...withoutEvents } = EVERYTHING;

    expect(runFrom(withoutEvents)).toBeNull();
    expect(runFrom(EVERYTHING)?.conversationEvents).toEqual([]);
  });

  it("reads dated conversation facts, keeping every decision in one review and absent members absent", () => {
    const events = [
      {
        id: "step:1",
        kind: "step_reached",
        at: "2026-09-25T08:00:01Z",
        stepId: "step-1",
        run: { runId: RUN, number: 7 },
      },
      {
        id: "answer:1",
        kind: "answered_by_hand",
        at: "2026-09-25T08:00:02Z",
        stepId: "step-1",
        by: AVO,
        number: 2,
        values: [{ field: "answer", value: "Demo Sample" }],
        run: { runId: RUN, number: 7 },
      },
      {
        id: "review:1",
        kind: "reviewed",
        at: "2026-09-25T08:00:03Z",
        stepId: "step-1",
        by: AVO,
        number: 2,
        decisions: [
          { field: "first", outcome: "assured" },
          {
            field: "second",
            outcome: "refused",
            why: "Too little detail",
            whyLines: "several",
          },
        ],
        run: { runId: RUN, number: 7 },
      },
      {
        id: "ceiling:1",
        kind: "ceiling_changed",
        at: "2026-09-25T08:00:04Z",
        by: AVO,
        run: { runId: ABOVE, number: 8 },
        to: "9007199254740993",
      },
    ];

    const read = runFrom({ ...EVERYTHING, conversationEvents: events });

    expect(read?.conversationEvents).toEqual(events);
    expect(read?.conversationEvents?.[2]).toMatchObject({
      kind: "reviewed",
      decisions: events[2]?.decisions,
    });
    expect(read?.conversationEvents?.[0]).not.toHaveProperty("by");
    expect(read?.conversationEvents?.[3]).not.toHaveProperty("from");
  });

  it("requires the held step for a length refusal so its explanation can link to it", () => {
    const refusal = {
      id: "length:1",
      kind: "refused_for_length",
      at: "2026-09-25T08:00:03Z",
      stepId: "step-1",
      by: AVO,
      number: 1,
      decisions: [
        {
          field: "answer",
          outcome: "refused",
          why: "Too long",
          whyLines: "several",
          lengthUnits: "10",
          excessUnits: "1",
        },
      ],
      run: { runId: RUN, number: 7 },
      held: {
        run: { runId: ABOVE, number: 8 },
        stepId: "held-step",
        stepName: "file_it",
      },
    };

    expect(
      runFrom({ ...EVERYTHING, conversationEvents: [refusal] })
        ?.conversationEvents?.[0],
    ).toEqual(refusal);
    expect(
      runFrom({
        ...EVERYTHING,
        conversationEvents: [{ ...refusal, held: undefined }],
      }),
    ).toBeNull();
    expect(
      runFrom({
        ...EVERYTHING,
        conversationEvents: [
          {
            ...refusal,
            decisions: [{ field: "answer", outcome: "assured" }],
          },
        ],
      }),
    ).toBeNull();
    expect(
      runFrom({
        ...EVERYTHING,
        conversationEvents: [{ ...refusal, decisions: [] }],
      }),
    ).toBeNull();
  });

  it("refuses a review without a reviewer or any decisions, keeping a complete review", () => {
    const review = {
      id: "review:1",
      kind: "reviewed",
      at: "2026-09-25T08:00:03Z",
      stepId: "step-1",
      by: AVO,
      number: 1,
      decisions: [{ field: "answer", outcome: "assured" }],
      run: { runId: RUN, number: 7 },
    };

    expect(
      runFrom({ ...EVERYTHING, conversationEvents: [review] })
        ?.conversationEvents,
    ).toEqual([review]);
    expect(
      runFrom({
        ...EVERYTHING,
        conversationEvents: [{ ...review, decisions: [] }],
      }),
    ).toBeNull();
    expect(
      runFrom({
        ...EVERYTHING,
        conversationEvents: [{ ...review, by: undefined }],
      }),
    ).toBeNull();
    expect(
      runFrom({ ...EVERYTHING, conversationEvents: [] })?.conversationEvents,
    ).toEqual([]);
  });

  it("refuses a review decision without its refusal reason or with a reason on assurance", () => {
    const review = {
      id: "review:2",
      kind: "reviewed",
      at: "2026-09-25T08:00:03Z",
      stepId: "step-1",
      by: AVO,
      number: 2,
      run: { runId: RUN, number: 7 },
      decisions: [{ field: "answer", outcome: "refused" }],
    };

    expect(runFrom({ ...EVERYTHING, conversationEvents: [review] })).toBeNull();
    expect(
      runFrom({
        ...EVERYTHING,
        conversationEvents: [
          {
            ...review,
            decisions: [
              {
                field: "answer",
                outcome: "refused",
                why: "  ",
                whyLines: "several",
              },
            ],
          },
        ],
      }),
    ).toBeNull();
    expect(
      runFrom({
        ...EVERYTHING,
        conversationEvents: [
          {
            ...review,
            decisions: [
              { field: "answer", outcome: "assured", why: "Unexpected" },
            ],
          },
        ],
      }),
    ).toBeNull();
  });

  it("refuses an unknown conversation kind and a system ceiling stop attributed to a person", () => {
    const system = {
      id: "stop:1",
      kind: "stopped_by_ceiling",
      at: "2026-09-25T08:00:04Z",
      run: { runId: RUN, number: 7 },
      ceilings: [{ runId: RUN, number: 7 }],
    };

    expect(
      runFrom({
        ...EVERYTHING,
        conversationEvents: [{ ...system, kind: "some_new_act" }],
      }),
    ).toBeNull();
    expect(
      runFrom({
        ...EVERYTHING,
        conversationEvents: [{ ...system, by: AVO }],
      }),
    ).toBeNull();
    expect(
      runFrom({ ...EVERYTHING, conversationEvents: [system] })
        ?.conversationEvents,
    ).toEqual([system]);
  });

  it.each([
    "ceiling_changed",
    "ceiling_asked",
    "ceiling_approved",
    "ceiling_refused",
    "ceiling_withdrawn",
    "stopped",
    "opened_again",
  ])(
    "reads %s with its actor and target, and refuses each missing required fact",
    (kind) => {
      const event = {
        id: `${kind}:1`,
        kind,
        at: "2026-09-25T08:00:04Z",
        by: AVO,
        run: { runId: RUN, number: 7 },
        ...(kind.startsWith("ceiling_") ? { from: "10", to: "12" } : {}),
      };

      expect(
        runFrom({ ...EVERYTHING, conversationEvents: [event] })
          ?.conversationEvents,
      ).toEqual([event]);
      for (const missing of ["id", "at", "by", "run"]) {
        const incomplete = Object.fromEntries(
          Object.entries(event).filter(([member]) => member !== missing),
        );
        expect(
          runFrom({ ...EVERYTHING, conversationEvents: [incomplete] }),
        ).toBeNull();
      }
    },
  );

  it("reads a run leaving out what it may, and adds nothing in its place", () => {
    const read = runFrom(BARE);

    expect(read).toEqual({ ...BARE, stateActs: new Set() });
    expect(Object.keys(read!).toSorted()).toEqual(Object.keys(BARE).toSorted());
    expect(read!.spend).not.toHaveProperty("measuredHere");
  });

  it.each(["wholly", "partly"])(
    "reads spend measured here as %s without changing its counts",
    (measuredHere) => {
      const spend = { ...BARE.spend, measuredHere };
      expect(runFrom({ ...BARE, spend })?.spend).toEqual(spend);
    },
  );

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
      "a spend carrying an unknown measurement spelling",
      { ...BARE, spend: { ...BARE.spend, measuredHere: "true" } },
    ],
    [
      "a measurement sent as a boolean",
      { ...BARE, spend: { ...BARE.spend, measuredHere: true } },
    ],
    [
      "a null measurement",
      { ...BARE, spend: { ...BARE.spend, measuredHere: null } },
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
      { ...BARE, stopped: { ...BARE.stopped, by: AVO } },
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

describe("runFrom child reading", () => {
  it("reads its launch, live inputs and their source while preserving absent optional inputs", () => {
    const child = {
      ...BARE,
      from: {
        runId: ABOVE,
        number: 7,
        stepId: "00000009-0000-4000-8000-000000000c11",
        stepName: "route",
      },
      startedWith: { complaint: "Demo Sample" },
      startedWithFrom: [
        {
          input: "complaint",
          from: {
            kind: "step",
            runId: ABOVE,
            runNumber: 7,
            stepId: "00000009-0000-4000-8000-000000000c12",
            name: "made",
            path: "answer",
          },
        },
      ],
    };
    const read = runFrom(child);

    expect(read?.from).toEqual(child.from);
    expect(read?.startedWith).toEqual(child.startedWith);
    expect(read?.startedWithFrom).toEqual(child.startedWithFrom);
    expect(runFrom(EVERYTHING)).not.toHaveProperty("from");
    expect(runFrom(BARE)).not.toHaveProperty("startedWithFrom");
    expect(
      runFrom({ ...child, from: { ...child.from, stepName: null } }),
    ).toBeNull();
  });

  it("reads a child starting value with a hidden leaf, visible file and composed sources", () => {
    const file = {
      id: "0000000f-0000-4000-8000-000000000001",
      name: "Demo Sample.pdf",
      size: 5,
    };
    const sources = [
      { targetPath: "privateNote", from: { kind: "constant" } },
      { targetPath: "attachment", from: { kind: "constant" } },
    ];
    const read = runFrom({
      ...BARE,
      startedWith: {
        package: { privateNote: { withheld: true }, attachment: file },
      },
      startedWithFrom: [{ input: "package", sources }],
    });

    expect(read?.startedWith).toEqual({
      package: { privateNote: { withheld: true }, attachment: file },
    });
    expect(read?.startedWithFrom).toEqual([{ input: "package", sources }]);
    expect(
      runFrom({
        ...BARE,
        startedWith: {
          package: { privateNote: { withheld: true, extra: "text" } },
        },
      }),
    ).toBeNull();
  });

  it("refuses an entire hidden marker at the starting-value root while accepting fields named withheld", () => {
    expect(runFrom({ ...BARE, startedWith: { withheld: true } })).toBeNull();
    expect(
      runFrom({ ...BARE, startedWith: { withheld: "Demo Sample" } })
        ?.startedWith,
    ).toEqual({
      withheld: "Demo Sample",
    });
    expect(
      runFrom({ ...BARE, startedWith: { details: { withheld: true } } })
        ?.startedWith,
    ).toEqual({
      details: { withheld: true },
    });
  });

  it("reads a child run's composed starting value without a false whole source", () => {
    const sources = [
      { targetPath: "name", from: { kind: "constant" } },
      {
        targetPath: "id",
        from: { kind: "run_input", runId: ABOVE, runNumber: 7, path: "ticket" },
      },
    ];
    const read = runFrom({
      ...BARE,
      startedWith: { customer: { name: "Demo Sample", id: "7" } },
      startedWithFrom: [{ input: "customer", sources }],
    });

    expect(read?.startedWithFrom).toEqual([{ input: "customer", sources }]);
    expect(read?.startedWithFrom?.[0]).not.toHaveProperty("from");
    expect(read?.startedWith).toEqual({
      customer: { name: "Demo Sample", id: "7" },
    });
  });

  it.each([
    [
      "both source shapes",
      {
        from: { kind: "constant" },
        sources: [{ targetPath: "name", from: { kind: "constant" } }],
      },
    ],
    ["no source", {}],
    ["empty sources", { sources: [] }],
    ["a bad part", { sources: [{ targetPath: "name", from: null }] }],
    [
      "a step maker without a run",
      {
        sources: [
          {
            targetPath: "name",
            from: {
              kind: "step",
              stepId: "00000009-0000-4000-8000-000000000c12",
              name: "made",
              path: "answer",
            },
          },
        ],
      },
    ],
    [
      "a step maker without its step identifier",
      {
        sources: [
          {
            targetPath: "name",
            from: {
              kind: "step",
              runId: ABOVE,
              runNumber: 7,
              name: "made",
              path: "answer",
            },
          },
        ],
      },
    ],
    [
      "a constant posing as a step value",
      {
        sources: [
          {
            targetPath: "name",
            from: {
              kind: "constant",
              runId: ABOVE,
              runNumber: 7,
              stepId: "step-1",
            },
          },
        ],
      },
    ],
  ])("refuses a child starting input with %s", (_case, source) => {
    expect(
      runFrom({ ...BARE, startedWithFrom: [{ input: "customer", ...source }] }),
    ).toBeNull();
  });

  it.each([
    ["a parent without its starting step", { from: undefined }],
    ["a starting step without its parent", { above: undefined }],
  ])("refuses %s rather than drawing a partial child", (_case, missing) => {
    expect(runFrom({ ...BARE, ...missing })).toBeNull();
  });

  it("reads every run above a child from the top down, a name only where one arrived", () => {
    const top = "00000008-0000-4000-8000-000000000c13";

    const read = runFrom({
      ...BARE,
      runsAbove: [
        { runId: top, number: 5, name: "Claim from Avo" },
        { runId: ABOVE, number: 7 },
      ],
    });

    expect(read?.runsAbove).toEqual([
      { runId: top, number: 5, name: "Claim from Avo" },
      { runId: ABOVE, number: 7 },
    ]);
    expect(Object.hasOwn(read!.runsAbove[1]!, "name")).toBe(false);
  });

  it.each([
    ["no runs above at all", { runsAbove: undefined }],
    ["none above, though it names a parent", { runsAbove: [] }],
    [
      "a last run above other than its parent",
      { runsAbove: [{ runId: RUN, number: 8 }] },
    ],
    [
      "a run above whose name is no word",
      { runsAbove: [{ runId: ABOVE, number: 7, name: 7 }] },
    ],
    [
      "a run above with no whole number",
      { runsAbove: [{ runId: ABOVE, number: 7.5 }] },
    ],
  ])("refuses a child with %s", (_case, changed) => {
    expect(runFrom({ ...BARE, ...changed })).toBeNull();
  });

  it("refuses a run at the top that names a run above it", () => {
    expect(
      runFrom({ ...EVERYTHING, runsAbove: [{ runId: ABOVE, number: 7 }] }),
    ).toBeNull();
  });

  it("refuses a child whose starting step names another parent", () => {
    expect(runFrom({ ...BARE, from: { ...BARE.from, runId: RUN } })).toBeNull();
  });

  it("reads the full ceiling stop and child-owned ceiling actions independently", () => {
    const ceilings = [
      { runId: ABOVE, number: 7 },
      { runId: RUN, number: 8 },
    ];
    const child = {
      run: { runId: RUN, number: 8 },
      ceiling: { raiseNeedsApproval: true },
      acts: ["change_ceiling"],
    };
    const read = runFrom({
      ...EVERYTHING,
      stopped: { at: "2026-09-25T08:01:00Z", ceilingOf: ceilings[0], ceilings },
      ceilingsBeneath: [child],
    });
    expect(read?.stopped).toEqual({
      at: "2026-09-25T08:01:00Z",
      ceilingOf: ceilings[0],
      ceilings,
    });
    expect(read?.ceilingsBeneath).toEqual([
      { ...child, acts: new Set(["change_ceiling"]) },
    ]);
    expect(read?.stateActs?.has("change_ceiling")).toBe(false);
    expect(
      runFrom({
        ...EVERYTHING,
        stopped: {
          at: "2026-09-25T08:01:00Z",
          ceilingOf: ceilings[0],
          ceilings: [],
        },
      }),
    ).toBeNull();
    expect(
      runFrom({
        ...EVERYTHING,
        stopped: {
          at: "2026-09-25T08:01:00Z",
          ceilingOf: ceilings[1],
          ceilings,
        },
      }),
    ).toBeNull();
  });
});
