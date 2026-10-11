import { act, renderHook } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

import { RequestFailed } from "../../../api/problem";
import type { Standing } from "../../../api/standing";
import type { StepAct } from "../../../api/stepActs";
import { deferred } from "../../../testutil/deferred";

import type { StepRow } from "../../../api/groups/{groupId}/runs/{runId}/steps";
import type { GivesOtherwise, StateRefusal } from "../../../api/stepActs";
import { answered, inGroup } from "../../../testutil/standingRead";
import type { Where } from "../../../api/stepWhereabouts";
import {
  DECLARING,
  FAILED,
  GIVES,
  HELD,
  HELD_ENTRY_STOPPED,
  LATER,
  NOBODY,
  NOBODY_ACTS,
  OWED,
  RECEIPT_TOTAL,
  setApart,
  step,
  TALLY,
  UNKNOWN,
  WAITING_ON_REVIEW,
} from "../../../testutil/stepWording";
import {
  givesOtherwiseSaid,
  stepOffers,
  useStepAction,
  useStepActValidity,
  withheldSaid as withheldIn,
} from "./stepActs";
import { whereSaid } from "./stepWhereabouts";

const RUN_STOPPED =
  "This run is stopped. Resume it before answering, reviewing or making another attempt.";

const ENTRY_STOPPED =
  "What this step runs, or the workflow itself, has been stopped. No attempt can be made until it is allowed to be used again.";

const AT_GATE: Where = { ...WAITING_ON_REVIEW, waitsOn: "review_at_gate" };

const SPENT: Where = { ...FAILED, waitsOn: "starter" };

describe("stepOffers", () => {
  const permitted = answered(
    [],
    [
      inGroup("group", "Claims", "Claims", [
        "review_at_gate",
        "answer_step",
        "start_run",
      ]),
    ],
    "reader",
  ).value;
  it.each([
    ["person", "other", true],
    ["person", "reader", false],
    ["person", null, false],
    ["person", "", false],
    ["model", null, true],
    ["code", null, true],
    ["model", "other", false],
    ["code", "reader", false],
    [null, null, false],
    ["future", null, false],
  ])(
    "reviews only an identified actual production kind %s by %s",
    (producedByKind, producedByUserId, offered) => {
      const row = {
        ...step("waiting", AT_GATE),
        stateActs: new Set(["review"]),
        producedByKind,
        producedByUserId,
      };
      expect(stepOffers(row, "review", permitted, "group")).toBe(offered);
    },
  );
  it("never inherits a control from a previous standing, actual actor, grant or nullable resource fact", () => {
    const row = {
      ...step("waiting", AT_GATE),
      stateActs: new Set(["review"]),
      producedByKind: "person",
      producedByUserId: "other",
    };
    expect(stepOffers(row, "review", permitted, "group")).toBe(true);
    expect(
      stepOffers(row, "review", { ...permitted, userId: "other" }, "group"),
    ).toBe(false);
    expect(
      stepOffers(row, "review", { ...permitted, userId: undefined }, "group"),
    ).toBe(false);
    expect(
      stepOffers(row, "review", { ...permitted, groups: [] }, "group"),
    ).toBe(false);
    expect(stepOffers(row, "review", permitted, "different-group")).toBe(false);
    expect(
      stepOffers({ ...row, stateActs: null }, "review", permitted, "group"),
    ).toBe(false);
    expect(
      stepOffers(
        { ...row, stateActs: new Set() },
        "review",
        permitted,
        "group",
      ),
    ).toBe(false);
    expect(
      stepOffers(
        { ...row, stateActs: new Set(["future"]) },
        "review",
        permitted,
        "group",
      ),
    ).toBe(false);
    expect(
      stepOffers(
        { ...row, stateActs: new Set(), letGoThen: new Set(["review"]) },
        "review",
        permitted,
        "group",
      ),
    ).toBe(false);
  });
});

describe("step action admission", () => {
  const acts: StepAct[] = ["answer", "ask_again", "review", "try_sending"];
  function admitted(): { row: StepRow; standing: Standing } {
    return {
      row: {
        ...step("waiting", AT_GATE),
        stateActs: new Set(acts),
        producedByKind: "person",
        producedByUserId: "other",
      },
      standing: answered(
        [],
        [
          inGroup("group", "Claims", "Claims", [
            "answer_step",
            "start_run",
            "review_at_gate",
          ]),
        ],
        "reader",
      ).value,
    };
  }
  function changed(original: ReturnType<typeof admitted>, change: string) {
    if (change === "permission")
      return { ...original, standing: { ...original.standing, groups: [] } };
    if (change === "identity")
      return {
        ...original,
        standing: { ...original.standing, userId: "another-reader" },
      };
    return {
      ...original,
      row: { ...original.row, where: { ...AT_GATE, number: 9 } },
    };
  }
  it.each(
    acts.flatMap((which) =>
      ["permission", "identity", "resource"].flatMap((change) =>
        ["success", "failure"].map(
          (outcome) => [which, change, outcome] as const,
        ),
      ),
    ),
  )(
    "does not revive %s after %s changed and returned before its pending %s",
    async (which, change, outcome) => {
      const original = admitted();
      const settled = vi.fn();
      const refused = vi.fn();
      const pending = deferred<string>();
      const hook = renderHook(
        ({ row, standing }) =>
          useStepAction<string>(
            useStepActValidity(row, which, standing, "group", "run"),
            settled,
            refused,
          ),
        { initialProps: original },
      );
      act(() => hook.result.current.run(() => pending.promise));
      hook.rerender(changed(original, change));
      hook.rerender(original);
      await act(async () => {
        if (outcome === "success") pending.settle("an answer");
        else
          pending.refuse(
            new RequestFailed({ status: 403, code: "ACT_NOT_PERMITTED" }),
          );
      });
      expect(settled).not.toHaveBeenCalled();
      expect(refused).not.toHaveBeenCalled();
      expect(hook.result.current.problem).toBeNull();
      expect(hook.result.current.running).toBe(false);
    },
  );
  it.each(acts)(
    "receives %s's answer while the admission remains current",
    async (which) => {
      const original = admitted();
      const settled = vi.fn();
      const hook = renderHook(
        ({ row, standing }) =>
          useStepAction<string>(
            useStepActValidity(row, which, standing, "group", "run"),
            settled,
          ),
        { initialProps: original },
      );
      await act(async () =>
        hook.result.current.run(() => Promise.resolve("an answer")),
      );
      expect(settled).toHaveBeenCalledExactlyOnceWith("an answer");
      expect(hook.result.current.problem).toBeNull();
      expect(hook.result.current.running).toBe(false);
    },
  );
  it.each([true, false])(
    "receives a resource-advanced answer only when it completes the sent act: %s",
    async (completes) => {
      const original = admitted();
      const settled = vi.fn();
      const pending = deferred<string>();
      const hook = renderHook(
        ({ row, standing }) =>
          useStepAction<string>(
            useStepActValidity(row, "answer", standing, "group", "run"),
            settled,
            undefined,
            (answer) => completes && answer === "this answer",
          ),
        { initialProps: original },
      );
      act(() => hook.result.current.run(() => pending.promise));
      hook.rerender({
        ...changed(original, "resource"),
        row: {
          ...changed(original, "resource").row,
          stateActs: new Set(),
          producedByUserId: "reader",
        },
      });
      await act(async () => pending.settle("this answer"));
      expect(settled.mock.calls).toEqual(completes ? [["this answer"]] : []);
      expect(hook.result.current.running).toBe(false);
    },
  );
  it.each(["permission", "identity", "group", "run", "step", "act"])(
    "does not revive a matching completion after its %s changes and returns",
    async (change) => {
      const original = {
        ...admitted(),
        groupId: "group",
        runId: "run",
        which: "answer" as StepAct,
      };
      const settled = vi.fn();
      const pending = deferred<string>();
      const matches = vi.fn(() => true);
      const hook = renderHook(
        ({ row, standing, groupId, runId, which }) =>
          useStepAction<string>(
            useStepActValidity(row, which, standing, groupId, runId),
            settled,
            undefined,
            matches,
          ),
        { initialProps: original },
      );
      act(() => hook.result.current.run(() => pending.promise));
      hook.rerender({
        ...original,
        ...changed(original, "resource"),
        ...(change === "permission" || change === "identity"
          ? changed(original, change)
          : change === "group"
            ? { groupId: "another-group" }
            : change === "run"
              ? { runId: "another-run" }
              : change === "step"
                ? { row: { ...original.row, stepId: "another-step" } }
                : { which: "ask_again" as StepAct }),
      });
      hook.rerender({
        ...original,
        row: { ...original.row, stateActs: new Set() },
      });
      await act(async () => pending.settle("this answer"));
      expect(settled).not.toHaveBeenCalled();
      expect(matches).not.toHaveBeenCalled();
      expect(hook.result.current.running).toBe(false);
    },
  );
  it.each(
    acts.flatMap((which) =>
      ["permission", "identity"].map((change) => [which, change] as const),
    ),
  )(
    "keeps %s's received refusal through its resource reread but forgets it when %s changes",
    async (which, change) => {
      const original = admitted();
      const refused = vi.fn();
      const problem = { status: 409, code: "STEP_MOVED_ON" };
      const hook = renderHook(
        ({ row, standing }) =>
          useStepAction<string>(
            useStepActValidity(row, which, standing, "group", "run"),
            vi.fn(),
            refused,
          ),
        { initialProps: original },
      );
      await act(async () =>
        hook.result.current.run(() =>
          Promise.reject(new RequestFailed(problem)),
        ),
      );
      expect(refused).toHaveBeenCalledExactlyOnceWith(problem);
      expect(hook.result.current.problem).toBe(problem);
      hook.rerender(changed(original, "resource"));
      expect(hook.result.current.problem).toBe(problem);
      hook.rerender(changed(original, change));
      expect(hook.result.current.problem).toBeNull();
      hook.rerender(original);
      expect(hook.result.current.problem).toBeNull();
    },
  );
  it.each(acts)(
    "forgets %s's received problem without replaying its refusal and accepts a later problem",
    async (which) => {
      const original = admitted();
      const refused = vi.fn();
      const settled = vi.fn();
      const problem = { status: 503, code: "READ_FAILED" };
      const later = { status: 409, code: "STEP_MOVED_ON" };
      const hook = renderHook(
        ({ row, standing }) =>
          useStepAction<string>(
            useStepActValidity(row, which, standing, "group", "run"),
            settled,
            refused,
          ),
        { initialProps: original },
      );
      await act(async () =>
        hook.result.current.run(() =>
          Promise.reject(new RequestFailed(problem)),
        ),
      );
      expect(hook.result.current.problem).toBe(problem);
      expect(refused).toHaveBeenCalledExactlyOnceWith(problem);
      act(() => hook.result.current.forgetProblem());
      hook.rerender(changed(original, "resource"));
      hook.rerender(original);
      expect(hook.result.current.problem).toBeNull();
      expect(hook.result.current.running).toBe(false);
      expect(refused).toHaveBeenCalledTimes(1);
      expect(settled).not.toHaveBeenCalled();
      await act(async () =>
        hook.result.current.run(() => Promise.reject(new RequestFailed(later))),
      );
      expect(hook.result.current.problem).toBe(later);
      expect(refused).toHaveBeenLastCalledWith(later);
      expect(refused).toHaveBeenCalledTimes(2);
      expect(settled).not.toHaveBeenCalled();
    },
  );
});

function codeSpent(
  ...refused: (StateRefusal & { readonly givesOtherwise?: GivesOtherwise })[]
): StepRow {
  return {
    ...step(
      "failed",
      SPENT,
      refused.map(({ act, refusal }) => ({ act, refusal })),
    ),
    givesOtherwise: refused.find((one) => one.givesOtherwise !== undefined)
      ?.givesOtherwise,
    runs: { versionName: null, kind: "code_step", codeStep: "tally" },
  };
}

const GIVES_OTHERWISE =
  "This code step's outputs, as installed now, no longer match what the workflow reads from it or the reference lists this group has, so it can't be answered here.";

const ASKED_OF_SOMEBODY =
  "This step can't be asked again; its next attempt can only be answered by a person.";

describe("givesOtherwiseSaid", () => {
  it("names the consuming step as a later step when the step-list read failed", () => {
    const said = givesOtherwiseSaid(
      {
        field: "receipt.total",
        readBy: { step: "0000000b-0000-4000-8000-000000000cb1" },
      },
      TALLY,
      { ...DECLARING, steps: undefined },
    );

    expect(said).toBe(
      `This code step's output ${RECEIPT_TOTAL} no longer matches what a later step expects from it.`,
    );
  });
});

function withheldSaid(
  step: StepRow,
  permissions = ["answer_step", "review_at_gate", "start_run"],
): string[] {
  return withheldIn(
    step,
    DECLARING,
    answered([], [inGroup("group", "Claims", "Claims", permissions)], "reader")
      .value,
    "group",
  );
}

describe("withheldSaid", () => {
  it.each([
    [
      "the later step reading it",
      { field: "receipt.total", readBy: { step: LATER.stepId } },
      `This code step's output ${RECEIPT_TOTAL} no longer matches what ${setApart("Check the receipt")} expects from it.`,
    ],
    [
      "what the workflow gives back of it",
      { field: "receipt.total", readBy: { output: "total_claimed" } },
      `This code step's output ${RECEIPT_TOTAL} no longer matches this workflow's output ${setApart("Total claimed")}.`,
    ],
    [
      "nothing, as the list its terms come from is not this group's",
      { field: "receipt.total" },
      `The options for ${RECEIPT_TOTAL}, an output of this code step, come from a reference list this group doesn't have.`,
    ],
  ])(
    "says the field of what a code step gives back that holds answering back beside the refusal, and %s",
    (_case, named, sentence) => {
      const said = withheldSaid(
        codeSpent(
          {
            act: "answer",
            refusal: "CODE_STEP_GIVES_OTHERWISE",
            givesOtherwise: named,
          },
          { act: "ask_again", refusal: "ASK_AGAIN_NOT_OFFERED" },
        ),
      );

      expect(said).toEqual([GIVES_OTHERWISE, sentence, ASKED_OF_SOMEBODY]);
    },
  );

  /** The roles are the reader's own: whom the step waits on instead is said where it is. */
  it.each([
    [
      "a value waiting on review",
      { ...step("waiting", AT_GATE), stateActs: new Set(["review"]) },
      ["You don't have permission to review it."],
    ],
    [
      "a try asked for",
      {
        ...step("waiting", { ...OWED, waitsOn: "answer_step" }),
        stateActs: new Set(["answer"]),
      },
      ["You don't have permission to answer it."],
    ],
    [
      "a try not asked for, which answering and asking again both take the same role to do",
      {
        ...step("waiting", { ...OWED, open: false, waitsOn: "answer_step" }),
        stateActs: new Set(["answer", "ask_again"]),
      },
      [
        "You don't have permission to answer it.",
        "You don't have permission to ask again.",
      ],
    ],
  ])(
    "says each act on %s the reader's roles do not reach as theirs",
    (_case, of, sentences) => {
      const said = withheldSaid(of, []);

      expect(said).toEqual(sentences);
      expect(said.join(" ")).not.toMatch(/ACT_NOT_PERMITTED|role/);
    },
  );

  /** Too long to send, it was never sent, so trying to send it is never said to be trying again. */
  it("says trying to send a step held back as too long, which the reader's roles do not reach, as theirs and never as again", () => {
    const said = withheldSaid(
      {
        ...step("held_back", {
          kind: "held_back",
          reason: "too_long",
          waitsOn: "starter",
        }),
        stateActs: new Set(["try_sending"]),
      },
      [],
    );

    expect(said).toEqual(["You don't have permission to try sending it."]);
    expect(said.join(" ")).not.toMatch(/again|ACT_NOT_PERMITTED/);
  });

  it.each([
    [
      "their own production",
      {
        ...step("waiting", AT_GATE),
        stateActs: new Set(["review"]),
        producedByKind: "person",
        producedByUserId: "reader",
      },
      "You produced this, so someone else must review it.",
    ],
    [
      "a production waiting on a model that turned its review away, where whom it waits on is its starter",
      step("waiting", { ...WAITING_ON_REVIEW, waitsOn: "starter" }, [
        { act: "review", refusal: "REVIEW_NOT_A_PERSONS" },
      ]),
      "These values are for the model this step names to review, not a person.",
    ],
    [
      "a step whose next try is only ever somebody's answer",
      codeSpent({ act: "ask_again", refusal: "ASK_AGAIN_NOT_OFFERED" }),
      "This step can't be asked again; its next attempt can only be answered by a person.",
    ],
  ])(
    "says an act held back for %s as the step's reason, never as the reader's roles",
    (_case, of, sentence) => {
      const said = withheldSaid(of);

      expect(said).toEqual([sentence]);
      expect(said.join(" ")).not.toMatch(/^You don't have permission to/);
    },
  );

  it.each([
    [
      "the run's stop on a try owed, where whom it waits on says it",
      step("waiting", OWED, [{ act: "answer", refusal: "RUN_STOPPED" }]),
      NOBODY,
    ],
    [
      "the run's stop on a value waiting on review, where whom it waits on says it",
      step("waiting", WAITING_ON_REVIEW, [
        { act: "review", refusal: "RUN_STOPPED" },
      ]),
      NOBODY,
    ],
    [
      "the run's stop on a step held back, where who can act on it says it",
      step("held_back", { kind: "held_back", reason: "turned_away" }, [
        { act: "try_sending", refusal: "RUN_STOPPED" },
      ]),
      NOBODY_ACTS,
    ],
    [
      "what it runs being stopped, where what holds it back says it",
      step("held_back", { ...HELD, waitsOn: "starter" }, [
        { act: "answer", refusal: "ENTRY_STOPPED" },
      ]),
      HELD_ENTRY_STOPPED,
    ],
    [
      "what it runs being stopped on a try too long to send, where the stop said first says it",
      step(
        "held_back",
        {
          kind: "held_back",
          reason: "too_long",
          stopped: HELD.stopped!,
          waitsOn: "starter",
        },
        [{ act: "try_sending", refusal: "ENTRY_STOPPED" }],
      ),
      HELD_ENTRY_STOPPED,
    ],
    [
      "a model's review, where whom it waits on says it",
      step("waiting", { ...WAITING_ON_REVIEW, waitsOn: "model" }, [
        { act: "review", refusal: "REVIEW_NOT_A_PERSONS" },
      ]),
      "Waiting for the model's review.",
    ],
  ])("says nothing again of %s", (_case, of, whereSentence) => {
    expect(withheldSaid(of)).toEqual([]);
    for (const subRun of [false, true, undefined]) {
      expect(
        whereSaid(
          {
            ...of,
            waitsOnStarter:
              subRun === undefined
                ? undefined
                : subRun
                  ? "top_level_run"
                  : "this_run",
          },
          GIVES,
          undefined,
          undefined,
          "",
        ),
      ).toContain(whereSentence);
    }
  });

  it.each([
    [
      "the run's stop, where the step is somewhere this build does not know",
      step("waiting", { kind: "on_hold" }, [
        { act: "answer", refusal: "RUN_STOPPED" },
        { act: "ask_again", refusal: "RUN_STOPPED" },
      ]),
      RUN_STOPPED,
    ],
    [
      "the run's stop, where the held reason is unknown",
      step("held_back", { kind: "held_back", reason: "budget_gone" }, [
        { act: "try_sending", refusal: "RUN_STOPPED" },
      ]),
      RUN_STOPPED,
    ],
    [
      "the run's stop, where the failure reason is absent",
      step("failed", { kind: "failed" }, [
        { act: "answer", refusal: "RUN_STOPPED" },
      ]),
      RUN_STOPPED,
    ],
    [
      "what it runs being stopped, where the step failed",
      step("failed", SPENT, [
        { act: "answer", refusal: "ENTRY_STOPPED" },
        { act: "ask_again", refusal: "ENTRY_STOPPED" },
      ]),
      ENTRY_STOPPED,
    ],
  ])(
    "says %s once, where nothing said of where it is says it, however many acts it holds back",
    (_case, of, sentence) => {
      expect(withheldSaid(of)).toEqual([sentence]);
    },
  );

  it("says each reason in the order its act came, where acts are held back for different reasons", () => {
    const said = withheldSaid(
      codeSpent(
        {
          act: "answer",
          refusal: "CODE_STEP_GIVES_OTHERWISE",
          givesOtherwise: { field: "receipt.total" },
        },
        { act: "ask_again", refusal: "ASK_AGAIN_NOT_OFFERED" },
      ),
    );

    expect(said).toEqual([
      "This code step's outputs, as installed now, no longer match what the workflow reads from it or the reference lists this group has, so it can't be answered here.",
      `The options for ${RECEIPT_TOTAL}, an output of this code step, come from a reference list this group doesn't have.`,
      "This step can't be asked again; its next attempt can only be answered by a person.",
    ]);
  });

  it.each([
    [
      "a refusal",
      step("waiting", AT_GATE, [{ act: "review", refusal: "REVIEW_LATER" }]),
      /REVIEW_LATER/,
    ],
    [
      "an act the reader's roles do not reach",
      step("waiting", { ...OWED, waitsOn: "answer_step" }, [
        { act: "delegate", refusal: "ACT_NOT_PERMITTED" },
      ]),
      /delegate|ACT_NOT_PERMITTED/,
    ],
  ])(
    "says %s this build has no words for as something it cannot say yet, never in the server's spelling",
    (_case, of, spelling) => {
      const said = withheldSaid(of);

      expect(said).toEqual([UNKNOWN]);
      expect(said.join(" ")).not.toMatch(spelling);
    },
  );

  it("says nothing where nothing is held back", () => {
    expect(
      withheldSaid(step("running", { kind: "running", on: "call" })),
    ).toEqual([]);
  });
});
