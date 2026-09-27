import { describe, expect, it } from "vitest";

import type { ReadField } from "../../../api/filling";
import type {
  StepRow,
  Turnaway,
  Where,
  Withheld,
} from "../../../api/groups/{groupId}/runs/{runId}/steps";
import type { DidNotFitReason } from "../../../api/groups/{groupId}/runs/{runId}/steps/{stepId}";
import { whenText } from "../../../lib/time/When";
import {
  misfitSaid,
  turnawaySaid,
  whereSaid,
  withheldSaid,
} from "./stepStates";

const UNKNOWN = "Not something this page can say yet.";

const NOBODY = "It waits on nobody while the run is stopped.";

const STARTER = "It waits on whoever started the run.";

const RUN_STOPPED =
  "A stopped run takes no answer, review or try until it is opened again.";

const ENTRY_STOPPED =
  "What this step runs, or the workflow itself, is stopped, so no try is made on it until that is let go.";

const HELD_ENTRY_STOPPED =
  "What it runs was stopped, so nothing is produced until that is undone.";

const STOPPED_AT = "2026-09-26T08:55:00Z";

const setApart = (words: string) =>
  `${String.fromCodePoint(0x2068)}${words}${String.fromCodePoint(0x2069)}`;

const STOPPED_BY_ADA = `Stopped by ${setApart("Ada")}, ${whenText(STOPPED_AT)}.`;

const NOBODY_ACTS = "Nobody can act on it while the run is stopped.";

const TRY_SENDING_BY = "Somebody who may start a run can try sending it.";

const LET_GO = "Somebody who may revoke an entry can let what was stopped go.";

const LET_GO_THEN_SEND =
  "Somebody who may revoke an entry can let what was stopped go, and then somebody who may start a run can try sending it.";

const TRIES_SPENT =
  "Every try it allows has been made, and none of them stands.";

const CODE_NOT_HELD =
  "This system, as it runs now, does not include the code this step runs, so the step waits, and goes on once the system includes that code again. Later runs can use a new version of the workflow.";

const TOO_LONG =
  "What it would send is longer than the model it names takes, so it was not sent, and nothing was spent on it.";

const TURNED_AWAY = "The model it names would not take it.";

const UNCUTTABLE =
  "What cannot be cut from what it would send is already longer than the model it names takes, so no cutting could mend it.";

const MODEL_NOT_HELD =
  "It names a model this deployment does not hold, or a mode that model does not offer.";

const GIVES: readonly ReadField[] = [
  {
    name: "summary",
    label: "Summary",
    kind: "text",
    longest: 100,
    mustBeGiven: true,
  },
];

const WAITING_ON_REVIEW: Where = {
  kind: "waiting_on_review",
  values: [{ field: "summary" }],
};

const OWED: Where = { kind: "owed_try", open: true };

/** Held back on what it runs being stopped, by Ada. */
const HELD: Where = {
  kind: "held_back",
  reason: "entry_stopped",
  stopped: {
    what: "entry",
    by: { userId: "000cb1", displayName: "Ada" },
    at: STOPPED_AT,
  },
};

const FAILED: Where = { kind: "failed", reason: "tries_spent" };

function step(
  state: string,
  where?: Where,
  withheld: readonly Withheld[] = [],
): StepRow {
  return {
    stepId: "00000009-0000-4000-8000-000000000cb1",
    order: 1,
    name: "summarise",
    runs: { kind: "question" },
    state,
    takesFrom: [],
    cost: {},
    next: { number: 2, beyond: false },
    acts: [],
    withheld,
    ...(where === undefined ? {} : { where }),
  };
}

/** A value waiting on a person's review at a gate, in a run not stopped. */
const AT_GATE: Where = { ...WAITING_ON_REVIEW, waitsOn: "review_at_gate" };

/** Every try spent, in a run not stopped: the next is owed, not asked for. */
const SPENT: Where = { ...FAILED, waitsOn: "starter" };

/** A code step whose every try is spent, holding back from the reader what `withheld` lists. */
function codeSpent(...withheld: Withheld[]): StepRow {
  return {
    ...step("failed", SPENT, withheld),
    runs: { kind: "code", codeStep: "tally" },
  };
}

/** A step offering the reader Try sending. */
function offering(state: string, where: Where): StepRow {
  return { ...step(state, where), acts: ["try_sending"] };
}

describe("misfitSaid", () => {
  it.each([
    ["not_the_shape", "The answer could not be read as what it was asked for."],
    ["field_missing", "The answer left out a value it was asked for."],
    ["field_unknown", "The answer gave a value nothing asked for."],
    [
      "nothing_given",
      "The answer gave nothing for a value that must be given.",
    ],
    [
      "not_its_kind",
      "The answer gave a value of the wrong kind for its field.",
    ],
    ["too_long", "The answer gave a value longer than its field allows."],
    ["too_many", "The answer gave more of a value than its field allows."],
    [
      "not_a_term",
      "The answer gave a value that is none of its field's terms.",
    ],
    [
      "unkeepable",
      "The answer gave text holding a character that cannot be kept.",
    ],
    [
      "too_long_to_keep",
      "The answer gave a value that fits its field but is longer than any one value may be kept.",
    ],
    [
      "confidence_missing",
      "The answer did not say how sure it was of a value it was asked to.",
    ],
    [
      "confidence_unasked",
      "The answer said how sure it was of a value nobody asked that of.",
    ],
    [
      "confidence_not_a_percent",
      "The answer said how sure it was as something other than a whole number from 0 to 100.",
    ],
    ["undecided", "The review left a value neither assured nor refused."],
    ["words_missing", "The review refused a value without saying why."],
    [
      "words_too_long",
      "The review's words for a refusal were longer than such words may be.",
    ],
    [
      "words_unkeepable",
      "The review's words for a refusal held a character such words may not hold.",
    ],
    [
      "cut_off",
      "The model stopped at the most it may give back, so its answer was cut short.",
    ],
    [
      "not_kept_as_it_came",
      "The answer held something that could not be kept exactly as it came.",
    ],
  ] satisfies [DidNotFitReason, string][])(
    "says an answer that did not fit as %s did in the reader's words, never in its spelling",
    (reason, sentence) => {
      const said = misfitSaid(reason);

      expect(said).toBe(sentence);
      expect(said).not.toContain(reason);
    },
  );

  it("says a way of not fitting spelt in a word this build does not know is something it cannot say yet, never in the server's spelling", () => {
    const said = misfitSaid("too_proud");

    expect(said).toBe(UNKNOWN);
    expect(said).not.toContain("too_proud");
  });
});

describe("whereSaid", () => {
  it.each([
    [
      "a value waiting on review at a gate",
      step("waiting", { ...WAITING_ON_REVIEW, waitsOn: "review_at_gate" }),
      [
        "Waiting",
        `${setApart("Summary")} is not reviewed yet.`,
        "It waits on somebody who may review it.",
      ],
    ],
    [
      "a value waiting on a model's review",
      step("waiting", { ...WAITING_ON_REVIEW, waitsOn: "model" }),
      [
        "Waiting",
        `${setApart("Summary")} is not reviewed yet.`,
        "It waits on a model's review.",
      ],
    ],
    [
      "a try asked for and owed",
      step("waiting", { ...OWED, waitsOn: "answer_step" }),
      [
        "Waiting",
        "Try 2 has been asked for.",
        "It waits on somebody who may answer it.",
      ],
    ],
    [
      "a try owed and not asked for",
      step("waiting", { ...OWED, open: false, waitsOn: "answer_step" }),
      [
        "Waiting",
        "Try 2 is not asked for yet.",
        "It waits on somebody who may answer it.",
      ],
    ],
  ])("says whom %s waits on, after where it is and why", (_case, of, said) => {
    const lines = whereSaid(of, GIVES);

    expect(lines).toEqual(said);
    expect(lines).not.toContain(NOBODY);
  });

  /** Held back and failed never wait on anybody: they say who can deal with them, or nothing where nobody can. */
  it.each([
    [
      "a step held back on a stop, who can let it go",
      step("held_back", { ...HELD, waitsOn: "starter" }),
      ["Held back", HELD_ENTRY_STOPPED, STOPPED_BY_ADA, LET_GO],
    ],
    [
      "a step whose code the run's version does not hold, which goes on by itself, nothing more",
      step("held_back", {
        kind: "held_back",
        reason: "code_step_not_held",
        waitsOn: "starter",
      }),
      ["Held back", CODE_NOT_HELD],
    ],
    [
      "a step whose every try is spent, which nothing mends, nothing more",
      step("failed", { ...FAILED, waitsOn: "starter" }),
      ["Failed", TRIES_SPENT],
    ],
  ])("says of %s", (_case, of, said) => {
    const lines = whereSaid(of, GIVES);

    expect(lines).toEqual(said);
    expect(lines.join(" ")).not.toMatch(/It waits on/);
    expect(lines).not.toContain(TRY_SENDING_BY);
  });

  it.each([
    ["held back", "too_long", TOO_LONG],
    ["held back", "turned_away", TURNED_AWAY],
    ["failed", "uncuttable_length", UNCUTTABLE],
    ["failed", "model_not_deployed", MODEL_NOT_HELD],
  ])(
    "says a step %s for %s why in the reader's words, then that somebody who may start a run can try sending it",
    (state, reason, sentence) => {
      const kind = state === "failed" ? "failed" : "held_back";
      const lines = whereSaid(
        offering(kind, { kind, reason, waitsOn: "starter" }),
        GIVES,
      );

      expect(lines).toEqual([
        state === "failed" ? "Failed" : "Held back",
        sentence,
        TRY_SENDING_BY,
      ]);
      expect(lines).not.toContain(STARTER);
      expect(lines.join(" ")).not.toContain(reason);
    },
  );

  /** Whose roles reach it is the server's to say: offered or held back for them, somebody may. */
  it.each([
    [
      "the reader's roles do not reach it",
      [{ act: "try_sending", refusal: "ACT_NOT_PERMITTED" }],
      TRY_SENDING_BY,
    ],
    ["nothing offers it", [], undefined],
  ])(
    "says of a step held back as too long, where %s, who can try sending it",
    (_case, withheld, ending) => {
      const lines = whereSaid(
        step(
          "held_back",
          { kind: "held_back", reason: "too_long", waitsOn: "starter" },
          withheld as Withheld[],
        ),
        GIVES,
      );

      expect(lines.at(-1)).toBe(ending ?? TOO_LONG);
      expect(lines).toHaveLength(ending === undefined ? 2 : 3);
    },
  );

  /** Its sentence names the value no branch takes, which the step does not carry yet. */
  it("says a route failed as no branch took its value is something it cannot say yet, never without the value", () => {
    const lines = whereSaid(
      step("failed", {
        kind: "failed",
        reason: "unclaimed_value",
        waitsOn: "starter",
      }),
      GIVES,
    );

    expect(lines).toEqual(["Failed", UNKNOWN]);
    expect(lines.join(" ")).not.toMatch(
      /branch|claimed|unclaimed_value|It waits on/,
    );
  });

  it.each([
    ["held back as too long", "held_back", "too_long", TOO_LONG],
    [
      "held back as the model would not take it",
      "held_back",
      "turned_away",
      TURNED_AWAY,
    ],
    ["failed as too long to cut", "failed", "uncuttable_length", UNCUTTABLE],
    [
      "failed as its model is not held",
      "failed",
      "model_not_deployed",
      MODEL_NOT_HELD,
    ],
  ])(
    "says what it runs was stopped, and by whom and when, before why a step %s is so, then who lets it go before it is sent",
    (_case, kind, reason, sentence) => {
      const lines = whereSaid(
        step(
          kind,
          { kind, reason, stopped: HELD.stopped!, waitsOn: "starter" },
          [{ act: "try_sending", refusal: "ENTRY_STOPPED" }],
        ),
        GIVES,
      );

      expect(lines).toEqual([
        kind === "failed" ? "Failed" : "Held back",
        HELD_ENTRY_STOPPED,
        STOPPED_BY_ADA,
        sentence,
        LET_GO_THEN_SEND,
      ]);
      expect(lines).not.toContain(TRY_SENDING_BY);
    },
  );

  it("says the stop first on a step held back as its code is not held, then who lets it go, the code still to be included", () => {
    const lines = whereSaid(
      step("held_back", {
        kind: "held_back",
        reason: "code_step_not_held",
        stopped: HELD.stopped!,
        waitsOn: "starter",
      }),
      GIVES,
    );

    expect(lines).toEqual([
      "Held back",
      HELD_ENTRY_STOPPED,
      STOPPED_BY_ADA,
      CODE_NOT_HELD,
      LET_GO,
    ]);
  });

  /** A stop stands in front of nothing sending could mend: it is no reason these failed, nor a way on. */
  it.each([
    ["every try spent", "tries_spent", TRIES_SPENT],
    ["no branch taking its value", "unclaimed_value", UNKNOWN],
  ])(
    "says only the failure of a step failed for %s while what it runs is stopped",
    (_case, reason, sentence) => {
      const lines = whereSaid(
        step("failed", {
          kind: "failed",
          reason,
          stopped: HELD.stopped!,
          waitsOn: "starter",
        }),
        GIVES,
      );

      expect(lines).toEqual(["Failed", sentence]);
      expect(lines).not.toContain(HELD_ENTRY_STOPPED);
      expect(lines.join(" ")).not.toMatch(/Stopped|let what was stopped go/);
    },
  );

  it("says the run's workflow was stopped by somebody gone before a failure sending may mend", () => {
    const { by: _gone, ...unnamed } = HELD.stopped!;

    const lines = whereSaid(
      offering("failed", {
        kind: "failed",
        reason: "uncuttable_length",
        stopped: { ...unnamed, what: "workflow" },
        waitsOn: "starter",
      }),
      GIVES,
    );

    expect(lines).toEqual([
      "Failed",
      "The workflow this run runs was stopped, so nothing is produced until that is undone.",
      `Stopped ${whenText(STOPPED_AT)}, by somebody who can no longer be found.`,
      UNCUTTABLE,
      LET_GO_THEN_SEND,
    ]);
    expect(lines).not.toContain(HELD_ENTRY_STOPPED);
  });

  it("says a step held back as the model would not take it, its spending used up, as that, then who can try sending it", () => {
    const lines = whereSaid(
      offering("held_back", {
        kind: "held_back",
        reason: "turned_away",
        spentUp: true,
        waitsOn: "starter",
      }),
      GIVES,
    );

    expect(lines).toEqual([
      "Held back",
      "The model it names would not take it, as what may be spent with that model is used up, so it is not sent again by itself.",
      TRY_SENDING_BY,
    ]);
    expect(lines).not.toContain(TURNED_AWAY);
  });

  it.each([
    [
      "to produce it, run as it is",
      { model: "small" },
      "It names small to produce it, which this deployment does not offer.",
    ],
    [
      "to review it, in a mode",
      { model: "judge", mode: "strict", reviewing: true as const },
      "It names judge in strict to review it, which this deployment does not offer.",
    ],
  ])(
    "says a step failed for a model not held %s by the model's name and mode",
    (_case, named, sentence) => {
      const lines = whereSaid(
        offering("failed", {
          kind: "failed",
          reason: "model_not_deployed",
          ...named,
          waitsOn: "starter",
        }),
        GIVES,
      );

      expect(lines).toEqual(["Failed", sentence, TRY_SENDING_BY]);
      expect(lines.join(" ")).not.toContain("It names a model");
      expect(lines.join(" ")).not.toContain("model_not_deployed");
    },
  );

  it("says what the run's own workflow being stopped holds back, and who stopped it and when", () => {
    const lines = whereSaid(
      step("held_back", {
        ...HELD,
        stopped: { ...HELD.stopped!, what: "workflow" },
        waitsOn: "starter",
      }),
      GIVES,
    );

    expect(lines).toEqual([
      "Held back",
      "The workflow this run runs was stopped, so nothing is produced until that is undone.",
      STOPPED_BY_ADA,
      LET_GO,
    ]);
    expect(lines).not.toContain(HELD_ENTRY_STOPPED);
  });

  it("says when what holds a step back was stopped, by somebody who can no longer be found, where nobody is named", () => {
    const { by: _gone, ...unnamed } = HELD.stopped!;

    const lines = whereSaid(
      step("held_back", { ...HELD, stopped: unnamed, waitsOn: "starter" }),
      GIVES,
    );

    expect(lines).toEqual([
      "Held back",
      HELD_ENTRY_STOPPED,
      `Stopped ${whenText(STOPPED_AT)}, by somebody who can no longer be found.`,
      LET_GO,
    ]);
    expect(lines.join(" ")).not.toMatch(/Stopped by/);
  });

  /** Let go, a model's step keeps its hold on the stop until this system next acts on it, which it does while the run runs. */
  it.each([
    [
      "runs",
      "starter",
      [
        "Held back",
        "What stopped it has been let go, so it goes on by itself while the run runs.",
      ],
    ],
    [
      "is stopped",
      undefined,
      [
        "Held back",
        "What stopped it has been let go, so it goes on by itself while the run runs.",
        NOBODY_ACTS,
      ],
    ],
  ])(
    "says a step held back on a stop let go goes on by itself, while its run %s",
    (_case, waitsOn, said) => {
      const lines = whereSaid(
        step("held_back", {
          kind: "held_back",
          reason: "entry_stopped",
          ...(waitsOn === undefined ? {} : { waitsOn }),
        }),
        GIVES,
      );

      expect(lines).toEqual(said);
      expect(lines).not.toContain(HELD_ENTRY_STOPPED);
      expect(lines).not.toContain(LET_GO);
    },
  );

  it.each([
    ["held back", "held_back", "Held back"],
    ["failed", "failed", "Failed"],
  ])(
    "says why a step %s is so is something it cannot say yet, where the reason is spelt in a word this build does not know",
    (_case, kind, state) => {
      const lines = whereSaid(
        step(kind, { kind, reason: "budget_gone", waitsOn: "starter" }),
        GIVES,
      );

      expect(lines).toEqual([state, UNKNOWN]);
      expect(lines.join(" ")).not.toMatch(/budget_gone|It waits on/);
    },
  );

  it.each([
    ["a value waiting on review", step("waiting", WAITING_ON_REVIEW)],
    ["a try owed", step("waiting", OWED)],
  ])(
    "says %s waits on nobody on a stopped run, whom the server names nobody for",
    (_case, of) => {
      const lines = whereSaid(of, GIVES);

      expect(lines.at(-1)).toBe(NOBODY);
      expect(lines.join(" ")).not.toMatch(
        /waits on (somebody|whoever|a model)/,
      );
    },
  );

  it.each([
    ["a step held back on a stop", step("held_back", HELD)],
    [
      "a step held back that sending may mend",
      offering("held_back", { kind: "held_back", reason: "turned_away" }),
    ],
    ["a step failed", step("failed", FAILED)],
  ])(
    "says nobody can act on %s on a stopped run, and never that it waits",
    (_case, of) => {
      const lines = whereSaid(of, GIVES);

      expect(lines.at(-1)).toBe(NOBODY_ACTS);
      expect(lines.join(" ")).not.toMatch(/waits on|can try sending|let what/);
    },
  );

  it("says whom it waits on is something it cannot say yet, where that is spelt in a word this build does not know", () => {
    const lines = whereSaid(
      step("waiting", { ...OWED, waitsOn: "treasurer" }),
      GIVES,
    );

    expect(lines).toEqual(["Waiting", "Try 2 has been asked for.", UNKNOWN]);
  });

  it("says once, and nothing of whom it waits on, that a step is somewhere this build does not know", () => {
    const lines = whereSaid(
      step("paused", { kind: "on_hold", waitsOn: "starter" }),
      GIVES,
    );

    expect(lines).toEqual([UNKNOWN]);
  });

  it("says why a step in a state it knows is there is something it cannot say yet, where that is a kind this build does not know", () => {
    const lines = whereSaid(
      step("waiting", { kind: "on_hold", waitsOn: "starter" }),
      GIVES,
    );

    expect(lines).toEqual(["Waiting", UNKNOWN]);
    expect(lines.join(" ")).not.toMatch(/waits on/);
  });

  it.each([
    ["its code", "code", "Its code is running."],
    ["a call to its model", "call", "A call to the model it names is out."],
    [
      "a try this system makes by itself",
      "next_try",
      "A try of it is made by this system, by itself, while the run runs.",
    ],
  ])(
    "says of a running step, beneath where it is, that it has %s out, and nothing of whom it waits on",
    (_case, on, sentence) => {
      const lines = whereSaid(step("running", { kind: "running", on }), GIVES);

      expect(lines).toEqual(["Running", sentence]);
      expect(lines.join(" ")).not.toMatch(/waits on/);
    },
  );

  it.each([
    [
      "running with nothing said of what it has out",
      step("running", { kind: "running" }),
    ],
    [
      "running with what it has out spelt in a word this build does not know",
      step("running", { kind: "running", on: "a_ledger" }),
    ],
    ["not started", step("not_started")],
    ["done", step("done")],
  ])("says only where a step is where it is %s", (_case, of) => {
    const lines = whereSaid(of, GIVES);

    expect(lines).toHaveLength(1);
    expect(lines).not.toContain(UNKNOWN);
  });
});

describe("withheldSaid", () => {
  /** The roles are the reader's own: whom the step waits on instead is said where it is. */
  it.each([
    [
      "a value waiting on review",
      step("waiting", AT_GATE, [
        { act: "review", refusal: "ACT_NOT_PERMITTED" },
      ]),
      ["You may not review it."],
    ],
    [
      "a try asked for",
      step("waiting", { ...OWED, waitsOn: "answer_step" }, [
        { act: "answer", refusal: "ACT_NOT_PERMITTED" },
      ]),
      ["You may not answer it here."],
    ],
    [
      "a try not asked for, which answering and asking again both take the same role to do",
      step("waiting", { ...OWED, open: false, waitsOn: "answer_step" }, [
        { act: "answer", refusal: "ACT_NOT_PERMITTED" },
        { act: "ask_again", refusal: "ACT_NOT_PERMITTED" },
      ]),
      ["You may not answer it here.", "You may not ask for it again."],
    ],
  ])(
    "says each act on %s the reader's roles do not reach as theirs",
    (_case, of, sentences) => {
      const said = withheldSaid(of);

      expect(said).toEqual(sentences);
      expect(said.join(" ")).not.toMatch(/ACT_NOT_PERMITTED|role/);
    },
  );

  /** Too long to send, it was never sent, so trying to send it is never said to be trying again. */
  it("says trying to send a step held back as too long, which the reader's roles do not reach, as theirs and never as again", () => {
    const said = withheldSaid(
      step(
        "held_back",
        { kind: "held_back", reason: "too_long", waitsOn: "starter" },
        [{ act: "try_sending", refusal: "ACT_NOT_PERMITTED" }],
      ),
    );

    expect(said).toEqual(["You may not try sending it."]);
    expect(said.join(" ")).not.toMatch(/again|ACT_NOT_PERMITTED/);
  });

  it.each([
    [
      "their own production",
      step("waiting", AT_GATE, [
        { act: "review", refusal: "REVIEW_OWN_PRODUCTION" },
      ]),
      "You gave this, so somebody else reviews it.",
    ],
    [
      "a production waiting on a model that turned its review away, where whom it waits on is its starter",
      step("waiting", { ...WAITING_ON_REVIEW, waitsOn: "starter" }, [
        { act: "review", refusal: "REVIEW_NOT_A_PERSONS" },
      ]),
      "Those values wait on the model the step names to review.",
    ],
    [
      "a step whose next try is only ever somebody's answer",
      codeSpent({ act: "ask_again", refusal: "ASK_AGAIN_NOT_OFFERED" }),
      "This step's next try is only ever somebody's answer to it.",
    ],
  ])(
    "says an act held back for %s as the step's reason, never as the reader's roles",
    (_case, of, sentence) => {
      const said = withheldSaid(of);

      expect(said).toEqual([sentence]);
      expect(said.join(" ")).not.toMatch(/^You may not/);
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
      "It waits on a model's review.",
    ],
  ])("says nothing again of %s", (_case, of, whereSentence) => {
    expect(withheldSaid(of)).toEqual([]);
    expect(whereSaid(of, GIVES)).toContain(whereSentence);
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
        { act: "answer", refusal: "CODE_STEP_GIVES_OTHERWISE" },
        { act: "ask_again", refusal: "ASK_AGAIN_NOT_OFFERED" },
      ),
    );

    expect(said).toEqual([
      "What this code step gives back, as this release declares it, no longer matches what the workflow reads from it or the lists this group holds, so it cannot be answered here.",
      "This step's next try is only ever somebody's answer to it.",
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

describe("turnawaySaid", () => {
  const TURNED_AT = "2026-09-26T09:01:00Z";

  it.each([
    [
      "sent again by itself, the model saying why",
      { said: "Too many asks.", cut: false, sentAgain: true },
      [
        `Turned away ${whenText(TURNED_AT)}, and sent again by itself.`,
        `The model said: ${setApart("Too many asks.")}`,
      ],
    ],
    [
      "not sent again, what the model said cut",
      { said: "Too many", cut: true, sentAgain: false },
      [
        `Turned away ${whenText(TURNED_AT)}.`,
        `The model said: ${setApart("Too many")} (cut short here)`,
      ],
    ],
    [
      "not sent again, what the model said withheld from the reader",
      { withheld: true as const, sentAgain: false },
      [
        `Turned away ${whenText(TURNED_AT)}.`,
        "What the model said is withheld from you.",
      ],
    ],
    [
      "sent again by itself, the model saying nothing",
      { sentAgain: true },
      [`Turned away ${whenText(TURNED_AT)}, and sent again by itself.`],
    ],
  ])("says when a call was turned away, %s", (_case, turned, lines) => {
    const said = turnawaySaid({ at: TURNED_AT, ...turned } satisfies Turnaway);

    expect(said).toEqual(lines);
  });
});
