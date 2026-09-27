import { describe, expect, it } from "vitest";

import type { RunAct } from "../../api/groups/{groupId}/runs/{runId}";
import { APP_EN } from "../../i18n/en";
import { RUN_ACT_RULES, STEP_ACT_RULES } from "./actRules";

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
  /** The table is JSON, which no compiler closes: an act left out is one whose refusal is said as nothing. */
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
