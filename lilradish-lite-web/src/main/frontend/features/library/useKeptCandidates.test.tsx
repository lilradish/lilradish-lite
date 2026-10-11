import { act, renderHook } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import type { KeptCandidate } from "./DeclarationBuilder";
import { keptStepsOf, useKeptCandidates } from "./useKeptCandidates";
import { freshStep, type StepDraft } from "./workflowDrafts";

const READ_AT_THIRD = "group:entry:version:3:0";

const READ_AT_FOURTH_MOVED = "group:entry:version:4:2";

function candidate(basis: string): KeptCandidate {
  return {
    basis,
    drafts: [],
    details: new Map(),
    removed: new Set(),
    picked: null,
  };
}

function routing(key: string): StepDraft {
  return {
    ...freshStep(key),
    runs: {
      kind: "route",
      discriminator: { from: "none" },
      gives: [],
      cases: [],
    },
  };
}

function asking(key: string): StepDraft {
  return { ...freshStep(key), runs: { kind: "question", version: "" } };
}

/** The scope a route's own declaration is kept from, read at `read` for the step `step`. */
function givesOf(read: string, step: string): string {
  return `${read}:${step}:route:gives`;
}

function drawerOf(
  kept: ReturnType<typeof useKeptCandidates>,
  step: string,
  read = READ_AT_THIRD,
) {
  return kept.lend(step, "route", `${read}:${step}:route`);
}

function keeping(...steps: readonly string[]) {
  const rendered = { count: 0 };
  const hook = renderHook(() => {
    rendered.count += 1;
    return useKeptCandidates();
  });
  act(() => {
    for (const step of steps)
      drawerOf(hook.result.current, step).keep(
        givesOf(READ_AT_THIRD, step),
        candidate(step),
      );
  });
  return { hook, rendered };
}

describe("useKeptCandidates", () => {
  it("lends a candidate kept under one revision and place to the same step's drawer read under another, and to no other part or step", () => {
    const { hook } = keeping("triage");

    const moved = drawerOf(hook.result.current, "triage", READ_AT_FOURTH_MOVED);

    expect(moved.kept(givesOf(READ_AT_FOURTH_MOVED, "triage"))).toEqual(
      candidate("triage"),
    );
    expect(
      moved.kept(`${READ_AT_FOURTH_MOVED}:triage:route:other`),
    ).toBeUndefined();
    expect(
      drawerOf(hook.result.current, "notify", READ_AT_FOURTH_MOVED).kept(
        givesOf(READ_AT_FOURTH_MOVED, "notify"),
      ),
    ).toBeUndefined();
  });

  it("asks only a reopened step's candidates to open their field again", () => {
    const { hook } = keeping("triage", "notify");

    act(() => hook.result.current.reopen("triage"));

    expect(
      drawerOf(hook.result.current, "triage").kept(
        givesOf(READ_AT_THIRD, "triage"),
      )?.opening,
    ).toBe(true);
    expect(
      drawerOf(hook.result.current, "notify").kept(
        givesOf(READ_AT_THIRD, "notify"),
      )?.opening,
    ).toBeUndefined();
  });

  it("forgets a step taken out, so a step added under the same key starts with nothing kept, the others' kept as they were", () => {
    const { hook } = keeping("triage", "notify");

    act(() => hook.result.current.forget("triage"));

    expect(
      drawerOf(hook.result.current, "triage").kept(
        givesOf(READ_AT_THIRD, "triage"),
      ),
    ).toBeUndefined();
    expect(
      keptStepsOf(hook.result.current.holding, [
        routing("triage"),
        routing("notify"),
      ]),
    ).toEqual(new Set(["notify"]));
  });

  it("lets go of everything when the saved draft is loaded", () => {
    const { hook } = keeping("triage", "notify");

    act(() => hook.result.current.forgetAll());

    expect(hook.result.current.holding.size).toBe(0);
    expect(
      drawerOf(hook.result.current, "notify").kept(
        givesOf(READ_AT_THIRD, "notify"),
      ),
    ).toBeUndefined();
  });

  it.each([
    ["is gone", [routing("notify")]],
    ["runs something else", [asking("triage"), routing("notify")]],
  ])(
    "lets go of what is kept for a step that %s, never to come back, and keeps the rest",
    (_case, steps) => {
      const { hook } = keeping("triage", "notify");

      act(() => hook.result.current.prune(steps));

      expect(
        keptStepsOf(hook.result.current.holding, [
          routing("triage"),
          routing("notify"),
        ]),
      ).toEqual(new Set(["notify"]));
      expect(
        drawerOf(hook.result.current, "triage").kept(
          givesOf(READ_AT_THIRD, "triage"),
        ),
      ).toBeUndefined();
    },
  );

  it("carries what is kept over to the keys a save answered, by place, letting go of one whose place was not answered", () => {
    const { hook } = keeping("triage", "step-2");

    act(() =>
      hook.result.current.rekey(["triage", "step-2"], ["triage-saved"]),
    );

    expect(
      drawerOf(hook.result.current, "triage-saved", READ_AT_FOURTH_MOVED).kept(
        givesOf(READ_AT_FOURTH_MOVED, "triage-saved"),
      ),
    ).toEqual(candidate("triage"));
    expect(
      drawerOf(hook.result.current, "triage").kept(
        givesOf(READ_AT_THIRD, "triage"),
      ),
    ).toBeUndefined();
    expect(
      keptStepsOf(hook.result.current.holding, [
        routing("triage-saved"),
        routing("step-2"),
      ]),
    ).toEqual(new Set(["triage-saved"]));
  });

  it("draws nothing anew while a step already holding a candidate is typed on, and again once it holds none", () => {
    const { hook, rendered } = keeping("triage");
    const drawn = rendered.count;
    const holding = hook.result.current.holding;

    act(() =>
      drawerOf(hook.result.current, "triage").keep(
        givesOf(READ_AT_THIRD, "triage"),
        candidate("triage, typed on"),
      ),
    );

    expect(rendered.count).toBe(drawn);
    expect(hook.result.current.holding).toBe(holding);

    act(() =>
      drawerOf(hook.result.current, "triage").keep(
        givesOf(READ_AT_THIRD, "triage"),
        null,
      ),
    );

    expect(rendered.count).toBe(drawn + 1);
    expect(hook.result.current.holding.size).toBe(0);
  });
});

describe("keptStepsOf", () => {
  it("counts only the steps still there and running what their candidate was kept under", () => {
    const { hook } = keeping("triage", "notify", "gone");

    expect(
      keptStepsOf(hook.result.current.holding, [
        routing("triage"),
        asking("notify"),
        routing("unkept"),
      ]),
    ).toEqual(new Set(["triage"]));
    expect(keptStepsOf(hook.result.current.holding, undefined)).toEqual(
      new Set(),
    );
  });
});
