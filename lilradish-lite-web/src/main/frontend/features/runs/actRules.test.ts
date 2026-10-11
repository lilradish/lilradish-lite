import { describe, expect, it } from "vitest";

import type { Run, RunAct } from "../../api/groups/{groupId}/runs/{runId}";
import type { Standing } from "../../api/standing";
import { APP_EN } from "../../i18n/en";
import { inGroup } from "../../testutil/standingRead";
import { RUN_ACT_RULES, STEP_ACT_RULES, runOffers } from "./actRules";

const EVERY_RUN_ACT = {
  stop: true,
  open_again: true,
  rename: true,
  change_ceiling: true,
  approve_raise: true,
  refuse_raise: true,
  withdraw_raise: true,
} satisfies Record<RunAct, true>;

describe("the rules of the acts on a run", () => {
  it("hold a rule for every act, and for no other", () => {
    expect(Object.keys(RUN_ACT_RULES).toSorted()).toEqual(
      Object.keys(EVERY_RUN_ACT).toSorted(),
    );
  });

  it("name only rules there are words for", () => {
    expect(
      Object.values(RUN_ACT_RULES).filter(
        (rule) => !Object.hasOwn(APP_EN, `inGroup.${rule}`),
      ),
    ).toEqual([]);
  });
});

const RUN: Run = {
  runId: "imaginary-root",
  number: 7,
  runsAbove: [],
  workflow: {
    versionName: null,
    entryId: "imaginary-workflow",
    name: "Handle an imaginary receipt",
    version: 1,
  },
  startedAt: "2026-10-07T08:00:00Z",
  conversationEvents: [],
  state: "running",
  spend: { sent: "0", cameBack: "0", spent: "0", cameBackUnknown: false },
  ceiling: {
    raiseNeedsApproval: true,
    waiting: {
      changeId: "imaginary-raise",
      askedBy: { userId: "requester", displayName: "Same name" },
      askedAt: "2026-10-07T08:00:01Z",
    },
  },
  stateActs: new Set([
    "rename",
    "approve_raise",
    "refuse_raise",
    "withdraw_raise",
  ]),
};

function standing(
  permissions: readonly string[],
  userId?: string,
  groupId = "group",
): Standing {
  return {
    acts: new Set(),
    groups: [inGroup(groupId, "CLAIMS", "Claims", permissions)],
    ...(userId === undefined ? {} : { userId }),
  };
}

function expectOffers(run: Run, reader: Standing, expected: readonly RunAct[]) {
  const acts = Object.keys(EVERY_RUN_ACT) as RunAct[];
  expect(acts).toHaveLength(7);
  expect(acts.map((act) => runOffers(run, act, reader, "group"))).toEqual(
    acts.map((act) => expected.includes(act)),
  );
}

describe("run controls from published state facts", () => {
  const positions: readonly {
    name: string;
    atTop: boolean;
    stopped: boolean;
    done: boolean;
    stateActs: readonly RunAct[];
    askedBy: string | null;
    expected: readonly RunAct[];
  }[] = [
    {
      name: "a running top run without a raise",
      atTop: true,
      stopped: false,
      done: false,
      stateActs: ["stop", "rename", "change_ceiling"],
      askedBy: null,
      expected: ["stop", "rename", "change_ceiling"],
    },
    {
      name: "a stopped top run without a raise",
      atTop: true,
      stopped: true,
      done: false,
      stateActs: ["open_again", "rename", "change_ceiling"],
      askedBy: null,
      expected: ["open_again", "rename", "change_ceiling"],
    },
    {
      name: "a running top run with another person's raise",
      atTop: true,
      stopped: false,
      done: false,
      stateActs: [
        "stop",
        "rename",
        "change_ceiling",
        "approve_raise",
        "refuse_raise",
        "withdraw_raise",
      ],
      askedBy: "another",
      expected: [
        "stop",
        "rename",
        "change_ceiling",
        "approve_raise",
        "refuse_raise",
      ],
    },
    {
      name: "a stopped top run with another person's raise",
      atTop: true,
      stopped: true,
      done: false,
      stateActs: [
        "open_again",
        "rename",
        "change_ceiling",
        "approve_raise",
        "refuse_raise",
        "withdraw_raise",
      ],
      askedBy: "another",
      expected: [
        "open_again",
        "rename",
        "change_ceiling",
        "approve_raise",
        "refuse_raise",
      ],
    },
    {
      name: "a running top run with the reader's raise",
      atTop: true,
      stopped: false,
      done: false,
      stateActs: [
        "stop",
        "rename",
        "change_ceiling",
        "approve_raise",
        "refuse_raise",
        "withdraw_raise",
      ],
      askedBy: "reader",
      expected: ["stop", "rename", "change_ceiling", "withdraw_raise"],
    },
    {
      name: "a running child run without a raise",
      atTop: false,
      stopped: false,
      done: false,
      stateActs: [],
      askedBy: null,
      expected: [],
    },
    {
      name: "a stopped child run without a raise",
      atTop: false,
      stopped: true,
      done: false,
      stateActs: [],
      askedBy: null,
      expected: [],
    },
    {
      name: "a done top run without a raise",
      atTop: true,
      stopped: false,
      done: true,
      stateActs: ["rename", "change_ceiling"],
      askedBy: null,
      expected: ["rename", "change_ceiling"],
    },
    {
      name: "a stopped done top run without a raise",
      atTop: true,
      stopped: true,
      done: true,
      stateActs: ["rename", "change_ceiling"],
      askedBy: null,
      expected: ["rename", "change_ceiling"],
    },
    {
      name: "a done top run with another person's raise",
      atTop: true,
      stopped: false,
      done: true,
      stateActs: [
        "rename",
        "change_ceiling",
        "approve_raise",
        "refuse_raise",
        "withdraw_raise",
      ],
      askedBy: "another",
      expected: ["rename", "change_ceiling", "approve_raise", "refuse_raise"],
    },
    {
      name: "a stopped done top run with the reader's raise",
      atTop: true,
      stopped: true,
      done: true,
      stateActs: [
        "rename",
        "change_ceiling",
        "approve_raise",
        "refuse_raise",
        "withdraw_raise",
      ],
      askedBy: "reader",
      expected: ["rename", "change_ceiling", "withdraw_raise"],
    },
    {
      name: "a stopped done child run without a raise",
      atTop: false,
      stopped: true,
      done: true,
      stateActs: [],
      askedBy: null,
      expected: [],
    },
  ];

  it.each(positions)(
    "offers only the reader's acts on $name",
    ({ atTop, stopped, done, stateActs, askedBy, expected }) => {
      const run: Run = {
        ...RUN,
        state: done ? "done" : stopped ? "stopped" : "running",
        ...(stopped
          ? {
              stopped: {
                at: "2026-10-07T08:00:02Z",
                by: { userId: "reader", displayName: "An imaginary reader" },
              },
            }
          : {}),
        ...(atTop
          ? {}
          : {
              above: "imaginary-parent",
              runsAbove: [{ runId: "imaginary-parent", number: 6 }],
              from: {
                runId: "imaginary-parent",
                number: 6,
                stepId: "imaginary-step",
                stepName: "Follow up",
              },
            }),
        stateActs: new Set(stateActs),
        ceiling:
          askedBy === null
            ? { raiseNeedsApproval: true }
            : {
                raiseNeedsApproval: true,
                waiting: {
                  changeId: "imaginary-raise",
                  askedBy: { userId: askedBy, displayName: "Same name" },
                  askedAt: "2026-10-07T08:00:01Z",
                },
              },
      };
      expectOffers(
        run,
        standing(["start_run", "approve_entry"], "reader"),
        expected,
      );
    },
  );

  const waiting: Run = {
    ...RUN,
    stateActs: new Set([
      "stop",
      "rename",
      "change_ceiling",
      "approve_raise",
      "refuse_raise",
      "withdraw_raise",
    ]),
  };

  it.each([
    ["read_membership", []],
    ["change_membership", []],
    ["start_run", ["stop", "rename", "change_ceiling"]],
    ["read_own_runs", []],
    ["read_all_runs", []],
    ["read_inference_content", []],
    ["answer_step", []],
    ["review_at_gate", []],
    ["author_entry", []],
    ["approve_entry", ["approve_raise", "refuse_raise"]],
    ["revoke_entry", []],
  ] as const)(
    "intersects a waiting run's facts with only %s",
    (permission, expected) => {
      expectOffers(waiting, standing([permission], "reader"), expected);
    },
  );

  it.each([
    [["start_run"], "another", true, ["stop", "rename", "change_ceiling"]],
    [["approve_entry"], "another", true, ["approve_raise", "refuse_raise"]],
    [["approve_entry"], "requester", true, []],
    [["read_all_runs"], "another", true, []],
    [[], "another", false, []],
  ] as const)(
    "keeps the whole grant/requester intersection %j for %s",
    (permissions, userId, hasRaise, expected) => {
      expectOffers(
        hasRaise
          ? waiting
          : {
              ...waiting,
              stateActs: new Set(["stop", "rename", "change_ceiling"]),
              ceiling: { raiseNeedsApproval: true },
            },
        standing(permissions, userId),
        expected,
      );
    },
  );
});

describe("run controls from the same waiting resource", () => {
  it.each([
    ["requester", true, false, false],
    ["another", false, true, true],
    [undefined, false, false, false],
  ] as const)(
    "separates the known public requester identity %s",
    (userId, withdraw, approve, refuse) => {
      const reader = standing(["start_run", "approve_entry"], userId);
      expect(runOffers(RUN, "withdraw_raise", reader, "group")).toBe(withdraw);
      expect(runOffers(RUN, "approve_raise", reader, "group")).toBe(approve);
      expect(runOffers(RUN, "refuse_raise", reader, "group")).toBe(refuse);
      expect(runOffers(RUN, "rename", reader, "group")).toBe(true);
    },
  );

  it.each([
    [[], false, false],
    [["start_run"], true, false],
    [["approve_entry"], false, true],
    [["start_run", "approve_entry"], true, true],
  ] as const)(
    "takes the current permission intersection %j",
    (permissions, rename, approve) => {
      const reader = standing(permissions, "another");
      expect(runOffers(RUN, "rename", reader, "group")).toBe(rename);
      expect(runOffers(RUN, "approve_raise", reader, "group")).toBe(approve);
    },
  );

  it("takes no action from another group's grant or an unknown act", () => {
    expect(
      runOffers(
        RUN,
        "rename",
        standing(["start_run"], "reader", "elsewhere"),
        "group",
      ),
    ).toBe(false);
    expect(
      runOffers(
        { ...RUN, stateActs: new Set(["constructor", "invented_act"]) },
        "constructor" as RunAct,
        standing(["start_run"], "reader"),
        "group",
      ),
    ).toBe(false);
  });

  it.each([null, new Set<string>()])(
    "keeps no old controls once this read publishes %j",
    (stateActs) => {
      const reader = standing(["start_run", "approve_entry"], "another");
      expect(runOffers({ ...RUN, stateActs }, "rename", reader, "group")).toBe(
        false,
      );
      expect(
        runOffers({ ...RUN, stateActs }, "approve_raise", reader, "group"),
      ).toBe(false);
    },
  );

  it("cannot decide or withdraw a raise without its public requester fact", () => {
    const reader = standing(["start_run", "approve_entry"], "another");
    const withoutWaiting = { ...RUN, ceiling: { raiseNeedsApproval: true } };
    expect(runOffers(withoutWaiting, "approve_raise", reader, "group")).toBe(
      false,
    );
    expect(runOffers(withoutWaiting, "withdraw_raise", reader, "group")).toBe(
      false,
    );
  });
});

describe("the rules of the acts on a step", () => {
  /** Held whole: the server's spec holds the same table level with each act's own permission. */
  it("hold, for each act, the permission it takes, and hold no other act", () => {
    expect(STEP_ACT_RULES).toEqual({
      answer: "answer_step",
      ask_again: "answer_step",
      review: "review_at_gate",
      try_sending: "start_run",
    });
  });

  it("name only rules there are words for", () => {
    expect(
      Object.values(STEP_ACT_RULES).filter(
        (rule) => !Object.hasOwn(APP_EN, `inGroup.${rule}`),
      ),
    ).toEqual([]);
  });
});
