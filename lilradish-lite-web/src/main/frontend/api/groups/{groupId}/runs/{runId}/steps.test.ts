import { describe, expect, it } from "vitest";

import { answering, refusalOf } from "../../../../../testutil/answering";
import { NOT_A_PROBLEM_DOCUMENT } from "../../../../problem";
import { readSteps, stepRowFrom } from "./steps";

const GROUP = "00000003-0000-4000-8000-000000000c71";

const CODE_SOURCE = {
  runId: "00000008-0000-4000-8000-000000000c73",
  runNumber: 7,
  stepId: "00000009-0000-4000-8000-000000000c73",
  name: "tidy_it",
  codeStep: "tidy",
};

const RUN = "00000008-0000-4000-8000-000000000c71";

const VERSION = "00000007-0000-4000-8000-000000000c71";

const QUESTION_VERSION = "00000007-0000-4000-8000-000000000c72";

const SUMMARISE = "00000009-0000-4000-8000-000000000c71";

const AVO = { userId: "000c71", displayName: "Avo" };

const TICKET = {
  name: "ticket",
  label: "Ticket",
  kind: "text",
  longest: 4000,
  mustBeGiven: true,
};

const SUMMARY = {
  name: "summary",
  kind: "text",
  longest: 1000,
  mustBeGiven: true,
};

const EVERY_MEMBER = {
  stepId: SUMMARISE,
  order: 1,
  name: "summarise",
  runs: {
    kind: "question",
    entryId: "00000006-0000-4000-8000-000000000c71",
    name: "Summarise",
    version: 2,
    versionId: QUESTION_VERSION,
  },
  producer: { kind: "model", model: "small", mode: "careful" },
  reviewer: { kind: "person" },
  state: "waiting",
  where: {
    kind: "waiting_on_review",
    number: 1,
    values: [{ field: "summary" }],
    since: "2026-09-26T09:00:00Z",
    waitsOn: "review_at_gate",
  },
  takesFrom: [{ input: "text", from: { kind: "run_input", path: "ticket" } }],
  tries: { current: 1, declared: 2, beyond: false },
  cost: {
    callsAModel: true,
    sent: "9007199254740993",
    cameBack: "20",
    spent: "9007199254741013",
    cameBackUnknown: false,
    measuredHere: "partly",
  },
  gaveBack: [{ field: "summary", withheld: true, now: "waiting_on_review" }],
  wentIn: [
    {
      input: "text",
      from: { kind: "run_input", path: "ticket" },
      value: "Printer on fire",
    },
  ],
  next: { number: 2, beyond: false },
  acts: ["review"],
  withheld: [{ act: "ask_again", refusal: "ASK_AGAIN_NOT_OFFERED" }],
};

/**
 * The same step as sent, with what it says of whom each value waits on and of where what went in was taken from,
 * that this build reads nothing of.
 */
const EVERY_MEMBER_SENT = {
  ...EVERY_MEMBER,
  where: {
    ...EVERY_MEMBER.where,
    values: [{ field: "summary", on: "review_at_gate" }],
  },
  wentInFrom: "try",
};

/** A step not started, offering no act and carrying nothing it may leave out. */
const NOTHING_LEFT_IN = {
  stepId: "00000009-0000-4000-8000-000000000c72",
  order: 2,
  name: "file_it",
  runs: { kind: "route" },
  state: "not_started",
  takesFrom: [],
  cost: { callsAModel: false },
  acts: [],
  withheld: [],
};

/**
 * A code step whose release has changed since its try: what that try gave back waits on the reader's review, and
 * it and what went in are given as each was kept, a reference kept as text, a folder as none, an input as fields.
 */
const KEPT_EARLIER = {
  runs: { kind: "code_step", codeStep: "file_claim" },
  producer: { kind: "code" },
  reviewer: { kind: "person" },
  state: "waiting",
  where: {
    kind: "waiting_on_review",
    number: 1,
    values: [{ field: "reference", on: "review_at_gate" }],
    since: "2026-09-26T09:00:00Z",
    waitsOn: "review_at_gate",
  },
  gaveBack: [
    {
      field: "reference",
      value: JSON.stringify("R-7"),
      now: "waiting_on_review",
      earlierShape: true,
    },
    { field: "folder", value: null, now: "stands", earlierShape: true },
  ],
  wentIn: [
    {
      input: "ticket_ref",
      from: { kind: "run_input", path: "ticket" },
      value: JSON.stringify({ number: 7 }),
      earlierShape: true,
    },
  ],
  wentInFrom: "try",
  acts: ["review"],
};

/** Held back on what it runs being stopped, by Avo, while the run was running. */
const HELD_ON_A_STOP = {
  kind: "held_back",
  reason: "entry_stopped",
  since: "2026-09-26T09:00:00Z",
  stopped: { what: "entry", by: AVO, at: "2026-09-26T08:55:00Z" },
  waitsOn: "starter",
};

/** Held back as the model would not take it, its spending used up: once saying why, cut, and sent again; once not. */
const TURNED_AWAY = {
  kind: "held_back",
  reason: "turned_away",
  spentUp: true,
  since: "2026-09-26T09:02:00Z",
  turnedAway: [
    {
      at: "2026-09-26T09:01:00Z",
      said: "Too many asks.",
      cut: true,
      sentAgain: true,
    },
    { at: "2026-09-26T09:02:00Z", sentAgain: false },
  ],
  waitsOn: "starter",
};

/** Failed as the model named to review it is not one this deployment holds, in the mode it was named in. */
const REVIEWER_NOT_HELD = {
  kind: "failed",
  reason: "model_not_deployed",
  model: "judge",
  mode: "strict",
  reviewing: true,
  since: "2026-09-26T09:00:00Z",
  waitsOn: "starter",
};

const HEADER = {
  runId: RUN,
  number: 3,
  versionId: VERSION,
  state: "running",
  progress: { done: 0, of: 2 },
};

const RUNNING = {
  run: {
    ...HEADER,
    at: { stepId: SUMMARISE, name: "summarise" },
    acts: ["stop", "rename"],
  },
  declarations: {
    [VERSION]: { takes: [TICKET], gives: [SUMMARY] },
    [QUESTION_VERSION]: { takes: [TICKET], gives: [SUMMARY] },
  },
  gaveBack: {
    declares: "values",
    standing: [{ field: "summary", value: null }],
  },
  steps: [EVERY_MEMBER_SENT, NOTHING_LEFT_IN],
  rereadAfterSeconds: 5,
};

/** Done, its run beneath no step, and nothing left in that it may leave out. */
const DONE = {
  run: {
    runId: RUN,
    number: 3,
    versionId: VERSION,
    state: "done",
    acts: [],
    progress: { done: 1, of: 1 },
  },
  declarations: { [VERSION]: { takes: [], gives: [] } },
  gaveBack: { declares: "nothing" },
  steps: [NOTHING_LEFT_IN],
};

function reading(document: unknown) {
  const sent = answering([JSON.stringify(document), 200]);
  return { sent, read: readSteps(GROUP, RUN, new AbortController().signal) };
}

/** The run done, its one step changed as `changed` says. */
function withStep(changed: object) {
  return { ...DONE, steps: [{ ...NOTHING_LEFT_IN, ...changed }] };
}

/** The run running, what stands of what it gives back as `standing` lists. */
function standingAs(...standing: object[]) {
  return { ...RUNNING, gaveBack: { declares: "values", standing } };
}

/** The run running, its second step the code step whose values are kept in an earlier shape, as `changed` says. */
function keptAs(changed: object) {
  return {
    ...RUNNING,
    steps: [
      EVERY_MEMBER_SENT,
      { ...NOTHING_LEFT_IN, ...KEPT_EARLIER, ...changed },
    ],
  };
}

describe("a held source code step", () => {
  it("keeps the source's own run and step without attributing it to the held step", () => {
    const where = {
      kind: "held_back",
      reason: "code_step_not_held",
      source: CODE_SOURCE,
    };
    expect(
      stepRowFrom({ ...NOTHING_LEFT_IN, state: "held_back", where })?.where,
    ).toEqual(where);
  });

  it("leaves a current code step's hold without a source", () => {
    const where = { kind: "held_back", reason: "code_step_not_held" };
    expect(
      stepRowFrom({ ...NOTHING_LEFT_IN, state: "held_back", where })?.where,
    ).toEqual(where);
  });

  it.each(Object.keys(CODE_SOURCE))("refuses a source missing %s", (member) => {
    const source = Object.fromEntries(
      Object.entries(CODE_SOURCE).filter(([key]) => key !== member),
    );
    expect(
      stepRowFrom({
        ...NOTHING_LEFT_IN,
        state: "held_back",
        where: {
          kind: "held_back",
          reason: "code_step_not_held",
          source,
        },
      }),
    ).toBeNull();
  });

  it.each([
    null,
    [],
    "tidy",
    { ...CODE_SOURCE, runId: null },
    { ...CODE_SOURCE, stepId: 1 },
    { ...CODE_SOURCE, name: false },
    { ...CODE_SOURCE, codeStep: [] },
    { ...CODE_SOURCE, runNumber: 0 },
    { ...CODE_SOURCE, runNumber: -1 },
    { ...CODE_SOURCE, runNumber: 1.5 },
    { ...CODE_SOURCE, runNumber: "7" },
    { ...CODE_SOURCE, runNumber: Number.MAX_SAFE_INTEGER + 1 },
  ])("refuses a malformed held source: %j", (source) => {
    expect(
      stepRowFrom({
        ...NOTHING_LEFT_IN,
        state: "held_back",
        where: {
          kind: "held_back",
          reason: "code_step_not_held",
          source,
        },
      }),
    ).toBeNull();
  });

  it.each([
    ["held_back", "search_way_not_held"],
    ["held_back", "entry_stopped"],
    ["held_back", "turned_away"],
    ["running", "code_step_not_held"],
    ["waiting_on_review", "code_step_not_held"],
    ["failed", "code_step_not_held"],
  ])("refuses a held source on %s with reason %s", (kind, reason) => {
    expect(
      stepRowFrom({
        ...NOTHING_LEFT_IN,
        state: "held_back",
        where: {
          kind,
          reason,
          source: CODE_SOURCE,
        },
      }),
    ).toBeNull();
  });
});

describe("a source value that no longer matches", () => {
  const failure = { source: CODE_SOURCE, field: "receipt.number" };
  const row = { ...NOTHING_LEFT_IN, state: "failed" };

  it("keeps the original source and field under the dedicated failure member", () => {
    const where = {
      kind: "failed",
      reason: "source_no_longer_matches",
      failedSourceNoLongerMatches: failure,
    };
    expect(stepRowFrom({ ...row, where })?.where).toEqual(where);
    expect(stepRowFrom({ ...row, where })?.where).not.toHaveProperty("source");
  });

  it.each([
    undefined,
    null,
    {},
    { source: CODE_SOURCE },
    { field: "receipt" },
    { ...failure, source: null },
    { ...failure, field: null },
    { ...failure, field: 7 },
    { ...failure, source: { ...CODE_SOURCE, runNumber: 0 } },
  ])(
    "refuses an incomplete source failure: %j",
    (failedSourceNoLongerMatches) => {
      expect(
        stepRowFrom({
          ...row,
          where: {
            kind: "failed",
            reason: "source_no_longer_matches",
            ...(failedSourceNoLongerMatches === undefined
              ? {}
              : { failedSourceNoLongerMatches }),
          },
        }),
      ).toBeNull();
    },
  );

  it.each([
    ["held_back", "source_no_longer_matches"],
    ["waiting_on_review", "source_no_longer_matches"],
    ["failed", "tries_spent"],
    ["held_back", "code_step_not_held"],
  ])("refuses a source failure on %s for %s", (kind, reason) => {
    expect(
      stepRowFrom({
        ...row,
        where: { kind, reason, failedSourceNoLongerMatches: failure },
      }),
    ).toBeNull();
  });

  it("refuses a source failure masquerading as a current source hold", () => {
    expect(
      stepRowFrom({
        ...row,
        where: {
          kind: "failed",
          reason: "source_no_longer_matches",
          source: CODE_SOURCE,
        },
      }),
    ).toBeNull();
  });
});

describe("a source term whose reference list is unavailable", () => {
  const failure = { source: CODE_SOURCE, field: "receipt.category" };
  const row = { ...NOTHING_LEFT_IN, state: "failed" };

  it("keeps the stored classification separate from a source shape mismatch", () => {
    const where = {
      kind: "failed",
      reason: "source_list_not_here",
      failedSourceListNotHere: failure,
    };
    expect(stepRowFrom({ ...row, where })?.where).toEqual(where);
    expect(stepRowFrom({ ...row, where })?.where).not.toHaveProperty(
      "failedSourceNoLongerMatches",
    );
  });

  it.each([
    undefined,
    null,
    {},
    { source: CODE_SOURCE },
    { field: "receipt" },
    { ...failure, source: null },
    { ...failure, field: null },
    { ...failure, field: 7 },
    { ...failure, source: { ...CODE_SOURCE, runNumber: 0 } },
    ...["runId", "runNumber", "stepId", "name", "codeStep"].map((member) => ({
      ...failure,
      source: Object.fromEntries(
        Object.entries(CODE_SOURCE).filter(([name]) => name !== member),
      ),
    })),
  ])(
    "refuses an incomplete list source failure: %j",
    (failedSourceListNotHere) => {
      expect(
        stepRowFrom({
          ...row,
          where: {
            kind: "failed",
            reason: "source_list_not_here",
            ...(failedSourceListNotHere === undefined
              ? {}
              : { failedSourceListNotHere }),
          },
        }),
      ).toBeNull();
    },
  );

  it.each([
    {
      kind: "held_back",
      reason: "source_list_not_here",
      failedSourceListNotHere: failure,
    },
    {
      kind: "waiting_on_review",
      reason: "source_list_not_here",
      failedSourceListNotHere: failure,
    },
    { kind: "failed", reason: "tries_spent", failedSourceListNotHere: failure },
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
    ...["source_no_longer_matches", "source_list_not_here", "tries_spent"].map(
      (reason) => ({
        kind: "failed",
        reason,
        failedSourceListNotHere: failure,
        failedSourceNoLongerMatches: failure,
      }),
    ),
  ])(
    "refuses exchanged or simultaneous source failure members: %j",
    (where) => {
      expect(stepRowFrom({ ...row, where })).toBeNull();
    },
  );
});

describe("readSteps", () => {
  it("reads every step of the run at its address, each count kept as the digits it came as, and nothing it does not read yet", async () => {
    const { sent, read } = reading(RUNNING);

    expect(await read).toEqual({
      run: HEADER,
      declarations: new Map([
        [VERSION, { takes: [TICKET], gives: [SUMMARY] }],
        [QUESTION_VERSION, { takes: [TICKET], gives: [SUMMARY] }],
      ]),
      gaveBack: RUNNING.gaveBack,
      steps: [
        {
          ...EVERY_MEMBER,
          cost: {
            spend: {
              sent: "9007199254740993",
              cameBack: "20",
              spent: "9007199254741013",
              cameBackUnknown: false,
              measuredHere: "partly",
            },
          },
        },
        { ...NOTHING_LEFT_IN, cost: {} },
      ],
      rereadAfterSeconds: 5,
    });
    expect(sent.mock.calls[0]![0]).toBe(
      `/api/groups/${GROUP}/runs/${RUN}/steps`,
    );
  });

  it.each([
    ["a complete source run", { runId: RUN, runNumber: 3 }],
    ["declared binding without source run identity", {}],
  ])("reads %s without changing its source", async (_case, identity) => {
    const from = { kind: "run_input", path: "ticket", ...identity };
    const read = await reading({
      ...RUNNING,
      steps: [
        {
          ...EVERY_MEMBER_SENT,
          takesFrom: [{ input: "text", from }],
        },
        NOTHING_LEFT_IN,
      ],
    }).read;

    expect(read.steps[0]?.takesFrom[0]?.from).toEqual(from);
  });

  it("keeps an earlier searching step's declaration namespace", async () => {
    const from = {
      kind: "step",
      path: "title",
      stepId: SUMMARISE,
      name: "look_up",
      searchWay: "web",
    };
    const read = await reading({
      ...RUNNING,
      steps: [{ ...EVERY_MEMBER_SENT, takesFrom: [{ input: "text", from }] }],
    }).read;
    expect(read.steps[0]?.takesFrom[0]?.from).toEqual(from);
  });

  it("keeps each read part identity when two parts read the same path", async () => {
    const from = { kind: "run_input", path: "ticket" };
    const parts = [SUMMARISE, QUESTION_VERSION].map((sourcePartId) => ({
      input: "ticket",
      from,
      sourcePartId,
    }));
    const read = await reading({
      ...RUNNING,
      steps: [{ ...EVERY_MEMBER_SENT, takesFrom: parts }],
    }).read;
    expect(read.steps[0]?.takesFrom).toEqual(parts);
  });

  it("refuses a read part identity of another kind", async () => {
    const failure = await refusalOf(
      reading({
        ...RUNNING,
        steps: [
          {
            ...EVERY_MEMBER_SENT,
            takesFrom: [
              {
                input: "ticket",
                from: { kind: "run_input", path: "ticket" },
                sourcePartId: 7,
              },
            ],
          },
        ],
      }).read,
    );
    expect(failure.problem).toEqual({
      status: 200,
      code: NOT_A_PROBLEM_DOCUMENT,
    });
  });

  it.each([
    { kind: "run_input", searchWay: "web" },
    { kind: "step", searchWay: "web", codeStep: "web" },
    { kind: "step", searchWay: "web", versionId: RUNNING.run.versionId },
    { kind: "step", searchWay: 3 },
  ])("refuses an inconsistent search origin %#", async (from) => {
    const failure = await refusalOf(
      reading({
        ...RUNNING,
        steps: [{ ...EVERY_MEMBER_SENT, takesFrom: [{ input: "text", from }] }],
      }).read,
    );
    expect(failure.problem).toEqual({
      status: 200,
      code: NOT_A_PROBLEM_DOCUMENT,
    });
  });

  it.each([
    ["a run ID without its number", { runId: RUN }],
    ["a run number without its ID", { runNumber: 3 }],
  ])("refuses %s at the steps response boundary", async (_case, identity) => {
    const failure = await refusalOf(
      reading({
        ...RUNNING,
        steps: [
          {
            ...EVERY_MEMBER_SENT,
            takesFrom: [
              {
                input: "text",
                from: { kind: "run_input", path: "ticket", ...identity },
              },
            ],
          },
          NOTHING_LEFT_IN,
        ],
      }).read,
    );

    expect(failure.problem).toEqual({
      status: 200,
      code: NOT_A_PROBLEM_DOCUMENT,
    });
  });

  it("reads mixed hidden and visible leaves in a step value without hiding its file", async () => {
    const file = {
      id: "0000000f-0000-4000-8000-000000000001",
      name: "Demo Sample.pdf",
      type: "application/pdf",
      size: 5,
    };
    const value = {
      privateNote: { withheld: true },
      attachment: file,
      visibleNote: "Demo Sample",
    };
    const read = await reading(
      withStep({
        wentIn: [
          {
            input: "ticket",
            from: { kind: "run_input", path: "ticket" },
            value,
          },
        ],
        gaveBack: [{ field: "summary", value, now: "stands" }],
      }),
    ).read;

    expect(read.steps[0]?.wentIn?.[0]).toMatchObject({
      input: "ticket",
      value,
    });
    expect(read.steps[0]?.gaveBack?.[0]).toMatchObject({
      field: "summary",
      value,
    });
    expect(read.steps[0]?.wentIn?.[0]).not.toHaveProperty("withheld");
  });

  it("reads a run leaving out what it may, and adds nothing in its place", async () => {
    const read = await reading(DONE).read;

    expect(read).not.toHaveProperty("rereadAfterSeconds");
    expect(read.gaveBack).toEqual({ declares: "nothing" });
    expect(Object.keys(read.run).toSorted()).toEqual(
      Object.keys(HEADER).toSorted(),
    );
    expect(Object.keys(read.steps[0]!).toSorted()).toEqual(
      Object.keys(NOTHING_LEFT_IN).toSorted(),
    );
    expect(read.steps[0]).toMatchObject({ withheld: [] });
  });

  it("keeps every spelling this build does not know as it came, and passes over members it has never heard of", async () => {
    const later = {
      ...RUNNING,
      holds: [{ kind: "budget" }],
      steps: [
        {
          ...NOTHING_LEFT_IN,
          runs: { kind: "committee", entryId: "x", seats: 3 },
          producer: { kind: "panel", size: 3 },
          state: "paused",
          where: {
            kind: "on_hold",
            reason: "budget",
            until: "later",
            on: "a_ledger",
            waitsOn: "treasurer",
          },
          takesFrom: [{ input: "a", from: { kind: "archive", shelf: 4 } }],
          gaveBack: [
            { field: "a", value: "b", now: "superseded", confidence: 3 },
          ],
          acts: ["delegate", 4],
          withheld: [{ act: "delegate", refusal: "LATER", until: "never" }],
        },
      ],
    };

    const [step] = (await reading(later).read).steps;

    expect(step).toEqual({
      ...NOTHING_LEFT_IN,
      runs: { kind: "committee", entryId: "x" },
      producer: { kind: "panel" },
      state: "paused",
      where: {
        kind: "on_hold",
        reason: "budget",
        on: "a_ledger",
        waitsOn: "treasurer",
      },
      takesFrom: [{ input: "a", from: { kind: "archive" } }],
      cost: {},
      gaveBack: [{ field: "a", value: "b", now: "superseded" }],
      acts: ["delegate"],
      withheld: [{ act: "delegate", refusal: "LATER" }],
    });
  });

  it("reads what a running step has out", async () => {
    const [step] = (
      await reading({
        ...RUNNING,
        steps: [
          {
            ...NOTHING_LEFT_IN,
            runs: { kind: "code_step", codeStep: "file_claim" },
            producer: { kind: "code" },
            reviewer: { kind: "person" },
            state: "running",
            where: { kind: "running", on: "code" },
            tries: { current: 1, declared: 1, beyond: false },
          },
        ],
      }).read
    ).steps;

    expect(step!.where).toEqual({ kind: "running", on: "code" });
  });

  it("reads the stop holding a step back, what was stopped, by whom and when", async () => {
    const [step] = (
      await reading(withStep({ state: "held_back", where: HELD_ON_A_STOP }))
        .read
    ).steps;

    expect(step!.where).toEqual(HELD_ON_A_STOP);
  });

  it("reads a stop by somebody no longer to be found with nobody named, rather than refusing it", async () => {
    const { by: _gone, ...unnamed } = HELD_ON_A_STOP.stopped;

    const [step] = (
      await reading(
        withStep({
          state: "held_back",
          where: { ...HELD_ON_A_STOP, stopped: unnamed },
        }),
      ).read
    ).steps;

    expect(step!.where!.stopped).toEqual({
      what: "entry",
      at: "2026-09-26T08:55:00Z",
    });
    expect(step!.where!.stopped).not.toHaveProperty("by");
  });

  it.each([
    "unsent",
    "out",
    "turned_away",
    "too_long",
    "list_not_here",
    "takes_no_longer_declared",
    "no_longer_declared",
    "holds_a_file",
    "later",
  ])(
    "reads the model review's %s status without replacing its spelling",
    async (review) => {
      const where = { ...EVERY_MEMBER.where, review };
      const [step] = (await reading(withStep({ where })).read).steps;
      expect(step!.where).toEqual(where);
    },
  );

  it("reads a step held back as the model would not take it, its spending used up, and each time it was turned away with what it said, cut or not", async () => {
    const [step] = (
      await reading(withStep({ state: "held_back", where: TURNED_AWAY })).read
    ).steps;

    expect(step!.where).toEqual(TURNED_AWAY);
    const [, silent] = step!.where!.turnedAway!;
    expect(Object.keys(silent!).toSorted()).toEqual(["at", "sentAgain"]);
  });

  it("reads what a model said in turning a call away as withheld where it is withheld, and never as said nothing", async () => {
    const withheld = {
      ...TURNED_AWAY,
      turnedAway: [
        { at: "2026-09-26T09:01:00Z", withheld: true, sentAgain: false },
      ],
    };

    const [step] = (
      await reading(withStep({ state: "held_back", where: withheld })).read
    ).steps;

    expect(step!.where!.turnedAway).toEqual([
      { at: "2026-09-26T09:01:00Z", withheld: true, sentAgain: false },
    ]);
    expect(step!.where!.turnedAway![0]).not.toHaveProperty("said");
    expect(step!.where!.turnedAway![0]).not.toHaveProperty("cut");
  });

  it("reads the model a step failed for not being held, in the mode it was named in, and that it was the one to review", async () => {
    const [step] = (
      await reading(withStep({ state: "failed", where: REVIEWER_NOT_HELD }))
        .read
    ).steps;

    expect(step!.where).toEqual(REVIEWER_NOT_HELD);
  });

  it("reads a model named to produce as run as it is with neither a mode nor a word of reviewing", async () => {
    const {
      mode: _mode,
      reviewing: _reviewing,
      ...producing
    } = REVIEWER_NOT_HELD;

    const [step] = (
      await reading(withStep({ state: "failed", where: producing })).read
    ).steps;

    expect(step!.where).toEqual(producing);
    expect(step!.where).not.toHaveProperty("mode");
    expect(step!.where).not.toHaveProperty("reviewing");
  });

  it("reads a search's way, current tool and the question held for sending", () => {
    const where = {
      kind: "held_back",
      reason: "too_long",
      searchQuestion: "Find Demo Sample  ",
      waitsOn: "starter",
    };
    const row = stepRowFrom({
      ...NOTHING_LEFT_IN,
      runs: { kind: "search", searchWay: "web", tool: "web_search" },
      state: "held_back",
      where,
    });

    expect(row?.runs).toEqual({
      kind: "search",
      searchWay: "web",
      tool: "web_search",
    });
    expect(row?.where).toEqual(where);
  });

  it("reads the failed search model and the tool it no longer offers", () => {
    const where = {
      kind: "failed",
      reason: "tool_not_offered",
      model: "general",
      mode: "research",
      tool: "web_search",
    };
    const row = stepRowFrom({ ...NOTHING_LEFT_IN, state: "failed", where });
    expect(row?.where).toEqual(where);
  });

  it("withholds a search question without saying it was empty", () => {
    const row = stepRowFrom({
      ...NOTHING_LEFT_IN,
      state: "held_back",
      runs: { kind: "search", searchWay: "web" },
      where: {
        kind: "held_back",
        reason: "turned_away",
        searchQuestionWithheld: true,
      },
    });
    expect(row?.where).toEqual({
      kind: "held_back",
      reason: "turned_away",
      searchQuestionWithheld: true,
    });
    expect(row?.where).not.toHaveProperty("searchQuestion");
  });

  it.each([
    { kind: "failed", reason: "tool_not_offered" },
    {
      kind: "held_back",
      reason: "search_way_not_held",
      searchQuestion: "Find",
    },
    { kind: "failed", reason: "turned_away", searchQuestion: "Find" },
    { kind: "failed", reason: "model_not_deployed", tool: "web_search" },
    {
      kind: "held_back",
      reason: "turned_away",
      searchQuestion: "Find",
      searchQuestionWithheld: true,
    },
    { kind: "held_back", reason: "turned_away", searchQuestionWithheld: false },
  ])("refuses an impossible search whereabouts: $reason", (where) => {
    expect(
      stepRowFrom({ ...NOTHING_LEFT_IN, state: where.kind, where }),
    ).toBeNull();
  });

  it("reads an input from an earlier step with the version it pins, or the code step it runs, by which the read declares it", async () => {
    const takesFrom = [
      {
        input: "text",
        from: {
          kind: "step",
          path: "summary",
          stepId: SUMMARISE,
          name: "summarise",
          versionId: QUESTION_VERSION,
        },
      },
      {
        input: "reference",
        from: {
          kind: "step",
          path: "reference",
          stepId: "00000009-0000-4000-8000-000000000c73",
          name: "file_claim",
          codeStep: "file_claim",
        },
      },
    ];

    const [step] = (await reading(withStep({ takesFrom })).read).steps;

    expect(step!.takesFrom).toEqual(takesFrom);
    expect(step!.takesFrom[0]!.from).not.toHaveProperty("codeStep");
    expect(step!.takesFrom[1]!.from).not.toHaveProperty("versionId");
  });

  it.each([
    [
      "the later step reading it",
      { field: "receipt.total", readBy: { step: SUMMARISE } },
    ],
    [
      "what the workflow gives back of it",
      { field: "receipt.total", readBy: { output: "claim.total" } },
    ],
    ["nothing, as a list its terms come from refuses it", { field: "receipt" }],
  ])(
    "reads answering a code step held back for the field of what it gives back, and %s, beside an act held back naming neither",
    async (_case, named) => {
      const withheld = [
        { act: "answer", refusal: "CODE_STEP_GIVES_OTHERWISE", ...named },
        { act: "ask_again", refusal: "ASK_AGAIN_NOT_OFFERED" },
      ];

      const [step] = (await reading(withStep({ withheld })).read).steps;

      expect(step!.withheld).toEqual([
        {
          act: "answer",
          refusal: "CODE_STEP_GIVES_OTHERWISE",
          givesOtherwise: named,
        },
        { act: "ask_again", refusal: "ASK_AGAIN_NOT_OFFERED" },
      ]);
    },
  );

  it("reads a field declared of a kind this build does not know as it came, rather than refusing the run with it", async () => {
    const shade = { name: "shade", kind: "colour", mustBeGiven: false };

    const read = await reading({
      ...DONE,
      declarations: { [VERSION]: { takes: [TICKET, shade], gives: [] } },
    }).read;

    expect(read.declarations.get(VERSION)).toEqual({
      takes: [TICKET, shade],
      gives: [],
    });
    expect(read.steps).toHaveLength(1);
  });

  it("reads a value withheld from the reader as withheld, and one given as none as none, never either as the other", async () => {
    const both = {
      ...RUNNING,
      gaveBack: {
        declares: "values",
        standing: [
          { field: "summary", withheld: true },
          { field: "reply", value: null },
        ],
      },
    };

    const read = await reading(both).read;

    expect(read.gaveBack.standing).toEqual([
      { field: "summary", withheld: true },
      { field: "reply", value: null },
    ]);
    expect(read.gaveBack.standing![0]).not.toHaveProperty("value");
    expect(read.gaveBack.standing![1]).not.toHaveProperty("withheld");
  });

  it("reads what a code step gave back and what went into it in a shape the step had before as the text each was kept as, none as none, and never as a value of any field", async () => {
    const [, step] = (await reading(keptAs({})).read).steps;

    expect(step!.gaveBack).toEqual([
      { field: "reference", asKept: '"R-7"', now: "waiting_on_review" },
      { field: "folder", asKept: null, now: "stands" },
    ]);
    expect(step!.wentIn).toEqual([
      {
        input: "ticket_ref",
        from: { kind: "run_input", path: "ticket" },
        asKept: '{"number":7}',
      },
    ]);
    expect(step!.gaveBack!.filter((each) => "value" in each)).toEqual([]);
    expect(step!.wentIn![0]).not.toHaveProperty("value");
  });

  it("reads a composed input with its whole value and each actual source in order", async () => {
    const sources = [
      { targetPath: "name", from: { kind: "constant" } },
      {
        targetPath: "id",
        from: {
          kind: "step",
          runId: RUN,
          runNumber: 7,
          stepId: SUMMARISE,
          name: "summarise",
          path: "summary",
        },
      },
    ];
    const read = await reading(
      withStep({
        wentIn: [
          {
            input: "customer",
            sources,
            value: { name: "Demo Sample", id: "7" },
          },
        ],
      }),
    ).read;

    expect(read.steps[0]!.wentIn).toEqual([
      { input: "customer", sources, value: { name: "Demo Sample", id: "7" } },
    ]);
    expect(read.steps[0]!.wentIn![0]).not.toHaveProperty("from");
    expect(read.steps[0]!.takesFrom).toEqual([]);
  });

  it.each([
    [
      "both kinds of source",
      {
        from: { kind: "constant" },
        sources: [{ targetPath: "name", from: { kind: "constant" } }],
      },
    ],
    ["no source", {}],
    ["empty sources", { sources: [] }],
    ["sources not a list", { sources: {} }],
    ["a source with no path", { sources: [{ from: { kind: "constant" } }] }],
    [
      "a path with an empty part",
      { sources: [{ targetPath: "name.", from: { kind: "constant" } }] },
    ],
    ["a source with no maker", { sources: [{ targetPath: "name" }] }],
    [
      "a source of an unknown kind",
      { sources: [{ targetPath: "name", from: { kind: "unknown" } }] },
    ],
    [
      "a maker with only a run identifier",
      { sources: [{ targetPath: "name", from: { kind: "step", runId: RUN } }] },
    ],
    [
      "a step maker with no run",
      {
        sources: [
          {
            targetPath: "name",
            from: {
              kind: "step",
              stepId: SUMMARISE,
              name: "summarise",
              path: "summary",
            },
          },
        ],
      },
    ],
    [
      "a step maker with no step identifier",
      {
        sources: [
          {
            targetPath: "name",
            from: {
              kind: "step",
              runId: RUN,
              runNumber: 7,
              name: "summarise",
              path: "summary",
            },
          },
        ],
      },
    ],
    [
      "a constant posing as a run value",
      {
        sources: [
          {
            targetPath: "name",
            from: { kind: "constant", runId: RUN, runNumber: 7 },
          },
        ],
      },
    ],
    [
      "a run input posing as a step value",
      {
        sources: [
          {
            targetPath: "name",
            from: {
              kind: "run_input",
              runId: RUN,
              runNumber: 7,
              stepId: SUMMARISE,
              path: "ticket",
            },
          },
        ],
      },
    ],
  ])("refuses an input with %s", async (_case, source) => {
    const failure = await refusalOf(
      reading(
        withStep({
          wentIn: [{ input: "customer", value: "Demo Sample", ...source }],
        }),
      ).read,
    );

    expect(failure.problem).toEqual({
      status: 200,
      code: NOT_A_PROBLEM_DOCUMENT,
    });
  });

  it.each([
    ["no object at all", "steps"],
    [
      "a run with nothing of how far it has got",
      { ...DONE, run: { ...DONE.run, progress: undefined } },
    ],
    [
      "a run saying nothing of how many of its steps are done",
      { ...DONE, run: { ...DONE.run, progress: { of: 1 } } },
    ],
    ["a run of no version", { ...DONE, run: { ...DONE.run, versionId: 3 } }],
    [
      "a declaration of a field of a known kind shaped as another",
      {
        ...DONE,
        declarations: {
          [VERSION]: { takes: [{ ...TICKET, kind: "date" }], gives: [] },
        },
      },
    ],
    [
      "a declaration saying nothing of what it gives back",
      { ...DONE, declarations: { [VERSION]: { takes: [] } } },
    ],
    [
      "a value both given and withheld",
      standingAs({ field: "summary", value: "A fire.", withheld: true }),
    ],
    ["a value neither given nor withheld", standingAs({ field: "summary" })],
    [
      "a value withheld as false",
      standingAs({ field: "summary", withheld: false }),
    ],
    [
      "a value holding a number sent as one",
      standingAs({ field: "count", value: 3 }),
    ],
    ["a step with no order", withStep({ order: undefined })],
    ["a step whose order is no whole number", withStep({ order: 1.5 })],
    ["a step saying nothing of what it runs", withStep({ runs: "route" })],
    ["a step whereabouts sent as null", withStep({ where: null })],
    [
      "a value waited on that names no field",
      withStep({
        where: { ...EVERY_MEMBER.where, values: [{ on: "review_at_gate" }] },
      }),
    ],
    [
      "what a running step has out said in no word",
      withStep({ state: "running", where: { kind: "running", on: 1 } }),
    ],
    [
      "whom a step waits on said in no word",
      withStep({ where: { ...EVERY_MEMBER.where, waitsOn: 1 } }),
    ],
    [
      "since when a step is where it is, said as a number",
      withStep({ where: { ...EVERY_MEMBER.where, since: 1 } }),
    ],
    [
      "a stop holding a step back that says nothing of what was stopped",
      withStep({
        where: {
          ...HELD_ON_A_STOP,
          stopped: { by: AVO, at: "2026-09-26T08:55:00Z" },
        },
      }),
    ],
    [
      "a stop holding a step back that says nothing of when",
      withStep({
        where: {
          ...HELD_ON_A_STOP,
          stopped: { what: "entry", by: AVO },
        },
      }),
    ],
    [
      "a stop by nobody who can be shown",
      withStep({
        where: {
          ...HELD_ON_A_STOP,
          stopped: { ...HELD_ON_A_STOP.stopped, by: { displayName: "Avo" } },
        },
      }),
    ],
    [
      "a hold said to be on spending used up as false, which is only ever left out",
      withStep({ where: { ...TURNED_AWAY, spentUp: false } }),
    ],
    [
      "a review status that is not text",
      withStep({ where: { ...EVERY_MEMBER.where, review: true } }),
    ],
    [
      "a failure said to be the reviewer's as false, which is only ever left out",
      withStep({ where: { ...REVIEWER_NOT_HELD, reviewing: false } }),
    ],
    [
      "a model not held named in no word",
      withStep({ where: { ...REVIEWER_NOT_HELD, model: 7 } }),
    ],
    [
      "a model not held in a mode named in no word",
      withStep({ where: { ...REVIEWER_NOT_HELD, mode: 7 } }),
    ],
    [
      "what a model said withheld, yet whether it was cut",
      withStep({
        where: {
          ...TURNED_AWAY,
          turnedAway: [
            {
              at: "2026-09-26T09:01:00Z",
              cut: false,
              withheld: true,
              sentAgain: false,
            },
          ],
        },
      }),
    ],
    [
      "turnaways sent as no list",
      withStep({ where: { ...TURNED_AWAY, turnedAway: {} } }),
    ],
    [
      "a turnaway saying nothing of whether it was sent again",
      withStep({
        where: {
          ...TURNED_AWAY,
          turnedAway: [{ at: "2026-09-26T09:01:00Z" }],
        },
      }),
    ],
    [
      "a turnaway at no time",
      withStep({
        where: { ...TURNED_AWAY, turnedAway: [{ at: 1, sentAgain: false }] },
      }),
    ],
    [
      "what a model said turning a call away, with nothing of whether it was cut",
      withStep({
        where: {
          ...TURNED_AWAY,
          turnedAway: [
            { at: "2026-09-26T09:01:00Z", said: "Busy.", sentAgain: false },
          ],
        },
      }),
    ],
    [
      "whether what a model said was cut, with nothing said",
      withStep({
        where: {
          ...TURNED_AWAY,
          turnedAway: [
            { at: "2026-09-26T09:01:00Z", cut: false, sentAgain: false },
          ],
        },
      }),
    ],
    [
      "what a model said both said and withheld",
      withStep({
        where: {
          ...TURNED_AWAY,
          turnedAway: [
            {
              at: "2026-09-26T09:01:00Z",
              said: "Busy.",
              cut: false,
              withheld: true,
              sentAgain: false,
            },
          ],
        },
      }),
    ],
    [
      "what a model said withheld, yet said",
      withStep({
        where: {
          ...TURNED_AWAY,
          turnedAway: [
            {
              at: "2026-09-26T09:01:00Z",
              said: "Busy.",
              withheld: true,
              sentAgain: false,
            },
          ],
        },
      }),
    ],
    [
      "what a model said withheld as false",
      withStep({
        where: {
          ...TURNED_AWAY,
          turnedAway: [
            { at: "2026-09-26T09:01:00Z", withheld: false, sentAgain: false },
          ],
        },
      }),
    ],
    ["an input coming from nowhere", withStep({ takesFrom: [{ input: "a" }] })],
    [
      "an input from a step whose version is named in no word",
      withStep({
        takesFrom: [
          {
            input: "a",
            from: { kind: "step", path: "a", name: "a", versionId: 7 },
          },
        ],
      }),
    ],
    [
      "an input from a code step named in no word",
      withStep({
        takesFrom: [
          {
            input: "a",
            from: { kind: "step", path: "a", name: "a", codeStep: null },
          },
        ],
      }),
    ],
    [
      "a cost calling a model with nothing of what it spent",
      withStep({ cost: { callsAModel: true } }),
    ],
    ["a cost saying nothing of calling a model", withStep({ cost: {} })],
    [
      "a producer named by nobody who can be shown",
      withStep({
        producer: { kind: "person", person: { displayName: "Avo" } },
      }),
    ],
    [
      "tries that say nothing of going beyond",
      withStep({ tries: { current: 1, declared: 2 } }),
    ],
    [
      "a value given back saying nothing of where it stands",
      withStep({ gaveBack: [{ field: "summary", value: "A fire." }] }),
    ],
    [
      "a value given back neither given nor withheld",
      withStep({ gaveBack: [{ field: "summary", now: "stands" }] }),
    ],
    [
      "a value given back in a shape the step had before, and withheld",
      keptAs({
        gaveBack: [
          {
            field: "reference",
            value: "R-7",
            withheld: true,
            now: "waiting_on_review",
            earlierShape: true,
          },
        ],
      }),
    ],
    [
      "a value given back in a shape the step had before, with nothing it was kept as",
      keptAs({
        gaveBack: [
          { field: "reference", now: "waiting_on_review", earlierShape: true },
        ],
      }),
    ],
    [
      "a value given back in a shape the step had before, kept as other than text",
      keptAs({
        gaveBack: [
          {
            field: "reference",
            value: 7,
            now: "waiting_on_review",
            earlierShape: true,
          },
        ],
      }),
    ],
    [
      "a value given back said to be in a shape the step had before as false",
      keptAs({
        gaveBack: [
          {
            field: "reference",
            value: "R-7",
            now: "waiting_on_review",
            earlierShape: false,
          },
        ],
      }),
    ],
    [
      "an input gone in, in a shape the step had before, kept as other than text",
      keptAs({
        wentIn: [{ ...KEPT_EARLIER.wentIn[0], value: { number: 7 } }],
      }),
    ],
    [
      "a next try numbered in text",
      withStep({ next: { number: "2", beyond: false } }),
    ],
    [
      "a next try saying nothing of going beyond",
      withStep({ next: { number: 2 } }),
    ],
    [
      "a step saying nothing of what it withholds",
      withStep({ withheld: undefined }),
    ],
    [
      "an act withheld for no refusal",
      withStep({ withheld: [{ act: "review" }] }),
    ],
    [
      "what reads the refused field named without the field",
      withStep({
        withheld: [
          {
            act: "answer",
            refusal: "CODE_STEP_GIVES_OTHERWISE",
            readBy: { step: SUMMARISE },
          },
        ],
      }),
    ],
    [
      "answering refused for what code gives back without naming any field",
      withStep({
        withheld: [{ act: "answer", refusal: "CODE_STEP_GIVES_OTHERWISE" }],
      }),
    ],
    [
      "the field an answer is held back for named in no text",
      withStep({
        withheld: [
          {
            act: "answer",
            refusal: "CODE_STEP_GIVES_OTHERWISE",
            field: ["receipt"],
          },
        ],
      }),
    ],
    [
      "what reads that field named as a step and as an output both",
      withStep({
        withheld: [
          {
            act: "answer",
            refusal: "CODE_STEP_GIVES_OTHERWISE",
            field: "receipt",
            readBy: { step: SUMMARISE, output: "total" },
          },
        ],
      }),
    ],
    [
      "what reads that field named as neither a step nor an output",
      withStep({
        withheld: [
          {
            act: "answer",
            refusal: "CODE_STEP_GIVES_OTHERWISE",
            field: "receipt",
            readBy: {},
          },
        ],
      }),
    ],
    [
      "what reads that field named as a step in no text",
      withStep({
        withheld: [
          {
            act: "answer",
            refusal: "CODE_STEP_GIVES_OTHERWISE",
            field: "receipt",
            readBy: { step: 7 },
          },
        ],
      }),
    ],
    [
      "a step saying nothing of the acts it offers",
      withStep({ acts: undefined }),
    ],
    ["acts offered sent as no list", withStep({ acts: "review" })],
    [
      "the try a review waits on numbered in text",
      withStep({ where: { ...EVERY_MEMBER.where, number: "1" } }),
    ],
    [
      "an input gone in coming from nowhere",
      withStep({ wentIn: [{ input: "text", value: "Printer on fire" }] }),
    ],
    [
      "an input gone in neither given nor withheld",
      withStep({ wentIn: [{ ...EVERY_MEMBER.wentIn[0], value: undefined }] }),
    ],
    [
      "a time to read again written in text",
      { ...RUNNING, rereadAfterSeconds: "5" },
    ],
    [
      "a time to read again under a second, which would read without pause",
      { ...RUNNING, rereadAfterSeconds: 0 },
    ],
  ])("refuses %s rather than drawing part of it", async (_case, body) => {
    const failure = await refusalOf(reading(body).read);

    expect(failure.problem).toEqual({
      status: 200,
      code: NOT_A_PROBLEM_DOCUMENT,
    });
  });
});

describe("child step reading", () => {
  it("keeps the route and actual child separate, with a deepest action address", () => {
    const beneath = {
      runId: "00000008-0000-4000-8000-000000000c72",
      number: 4,
      runs: {
        kind: "workflow",
        entryId: "00000006-0000-4000-8000-000000000c72",
        name: "Demo Sample",
        version: 2,
        versionId: QUESTION_VERSION,
      },
      at: {
        runId: "00000008-0000-4000-8000-000000000c73",
        number: 5,
        stepId: SUMMARISE,
        name: "decide",
      },
    };
    const row = stepRowFrom({
      ...NOTHING_LEFT_IN,
      beneath,
      takesFrom: [
        {
          input: "choice",
          from: {
            kind: "step",
            path: "answer",
            runId: RUN,
            runNumber: 3,
            stepId: SUMMARISE,
            name: "made",
          },
        },
      ],
    });
    expect(row?.runs.kind).toBe("route");
    expect(row?.beneath).toEqual(beneath);
    expect(row?.takesFrom[0]?.from.runId).toBe(RUN);
    expect(stepRowFrom(NOTHING_LEFT_IN)).not.toHaveProperty("beneath");
    expect(
      stepRowFrom({
        ...NOTHING_LEFT_IN,
        beneath: {
          ...beneath,
          at: {
            ...beneath.at,
            stepId: null,
          },
        },
      }),
    ).toBeNull();
  });

  it("reads a route failure's named, empty, or withheld choice as separate shapes", () => {
    const failed = { ...NOTHING_LEFT_IN, state: "failed" };
    for (const failure of [
      { term: "Demo Sample" },
      { term: null },
      { withheld: true },
    ]) {
      const row = stepRowFrom({
        ...failed,
        where: {
          kind: "failed",
          reason: "unclaimed_value",
          failedUnclaimed: failure,
        },
      });

      expect(row?.where?.failedUnclaimed).toEqual(failure);
      expect(row?.where?.failedUnclaimed).not.toHaveProperty(
        "term" in failure ? "withheld" : "term",
      );
    }
  });

  it.each([
    ["the choice omitted", "unclaimed_value", undefined],
    ["a choice for another failure", "tries_spent", { term: "Demo Sample" }],
    [
      "both the choice and withholding",
      "unclaimed_value",
      { term: "Demo Sample", withheld: true },
    ],
    ["neither shape", "unclaimed_value", {}],
    ["withholding sent as false", "unclaimed_value", { withheld: false }],
    ["a choice sent as a number", "unclaimed_value", { term: 3 }],
    [
      "an extra member on a withheld choice",
      "unclaimed_value",
      { withheld: true, extra: "Demo Sample" },
    ],
    [
      "an extra member on a named choice",
      "unclaimed_value",
      { term: "Demo Sample", extra: true },
    ],
  ])("refuses a route failure with %s", (_case, reason, failure) => {
    expect(
      stepRowFrom({
        ...NOTHING_LEFT_IN,
        state: "failed",
        where: {
          kind: "failed",
          reason,
          ...(failure === undefined ? {} : { failedUnclaimed: failure }),
        },
      }),
    ).toBeNull();
  });

  it.each([
    ["1", 1],
    ["32", 32],
    ["9007199254740991", 9007199254740991],
  ])(
    "reads how deep runs may go, sent as the digits %s, only where a step failed for going deeper",
    (sent, most) => {
      const row = stepRowFrom({
        ...NOTHING_LEFT_IN,
        state: "failed",
        where: {
          kind: "failed",
          reason: "too_deep",
          failedTooDeep: { most: sent },
        },
      });

      expect(row?.where).toEqual({
        kind: "failed",
        reason: "too_deep",
        failedTooDeep: { most },
      });
      expect(row?.where).not.toHaveProperty("failedUnclaimed");
    },
  );

  it.each([
    ["going deeper, not saying how deep", "too_deep", undefined],
    ["another reason, saying how deep", "tries_spent", { most: "32" }],
    ["going deeper, at most none", "too_deep", { most: "0" }],
    ["going deeper, at most a negative", "too_deep", { most: "-1" }],
    ["going deeper, at most a fraction", "too_deep", { most: "1.5" }],
    ["going deeper, at most empty digits", "too_deep", { most: "" }],
    ["going deeper, at most a number, not digits", "too_deep", { most: 32 }],
    ["going deeper, with no most", "too_deep", {}],
    [
      "going deeper, at most past an exact number",
      "too_deep",
      { most: "9007199254740993" },
    ],
    ["going deeper, at most with a leading zero", "too_deep", { most: "032" }],
  ])("refuses a step read as failed for %s", (_case, reason, failedTooDeep) => {
    const where = {
      kind: "failed",
      reason,
      ...(failedTooDeep === undefined ? {} : { failedTooDeep }),
    };

    expect(
      stepRowFrom({ ...NOTHING_LEFT_IN, state: "failed", where }),
    ).toBeNull();
  });
});
