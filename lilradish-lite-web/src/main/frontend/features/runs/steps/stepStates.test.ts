import { render, screen } from "@testing-library/react";
import { ThemeProvider } from "@mui/material/styles";
import { createElement } from "react";
import { describe, expect, it } from "vitest";

import type { ReadField } from "../../../api/filling";
import type {
  Declared,
  StepRow,
  Turnaway,
  Where,
  Withheld,
} from "../../../api/groups/{groupId}/runs/{runId}/steps";
import type {
  DidNotFitReason,
  ErroredFor,
  ErroredForReason,
  Try,
} from "../../../api/groups/{groupId}/runs/{runId}/steps/{stepId}";
import { whenText } from "../../../lib/time/When";
import { theme } from "../../../lib/theme/theme";
import { say } from "../../../i18n/app";
import type { Declaring } from "./declared";
import {
  erroredForSaid,
  reviewVisible,
  tryBeyondVisible,
  givesOtherwiseSaid,
  misfitSaid,
  tryEndedSaid,
  tryResultSaid,
  turnawaySaid,
  whereSaid,
  withheldSaid as withheldIn,
} from "./stepStates";

const UNKNOWN = "Not something this page can say yet.";

describe("tries beyond the declared allowance", () => {
  it("keeps the full sentence while separating its number from the system explanation", () => {
    const { container } = render(
      createElement(
        ThemeProvider,
        { theme },
        createElement("p", null, tryBeyondVisible(3, 2)),
      ),
    );
    const sentence = container.querySelector("p")!;
    const hints = [...sentence.querySelectorAll("span")];

    expect(sentence.textContent).toBe(
      say("step.tryBeyond", { number: 3, allowed: 2 }),
    );
    expect(hints.map((part) => part.textContent)).toEqual([
      "past the",
      "it allows",
    ]);
    expect(
      hints.every((part) => getComputedStyle(part).fontStyle === "italic"),
    ).toBe(true);
  });

  it("shows a failed review with the reviewer separate from its explanation", () => {
    const review = {
      asked: true,
      by: { kind: "model", model: "small" },
      wentWrong: true,
    } as const;
    const { container } = render(
      createElement(
        ThemeProvider,
        { theme },
        createElement("p", null, reviewVisible(review)),
      ),
    );
    const sentence = container.querySelector("p")!;

    expect(sentence.textContent).toBe("small, whose review went wrong");
    expect(sentence.querySelector("bdi")).toHaveTextContent("small");
    const hint = sentence.querySelector("span")!;
    expect(hint).toHaveTextContent("whose review went wrong");
    expect(getComputedStyle(hint).fontStyle).toBe("italic");
  });
});

describe("a picked value's result", () => {
  const tried: Try = {
    number: 1,
    beyond: false,
    producedBy: { kind: "model", model: "small" },
    values: [
      { field: "first", value: "Demo Sample", now: "stands" },
      { field: "second", value: "No", now: "refused" },
      { field: "third", value: "No", now: "refused_for_length" },
      { field: "future", value: "No", now: "future_standing" },
    ],
    review: { asked: true },
    ended: "refused_on_review",
    cost: {},
  };

  it("says each picked value's own standing and keeps an unknown spelling unknown", () => {
    expect(tryResultSaid(tried, "first")).toBe("It stands.");
    expect(tryResultSaid(tried, "second")).toBe("Refused on review.");
    expect(tryResultSaid(tried, "third")).toBe("Refused for its length.");
    expect(tryResultSaid(tried, "future")).toBe(UNKNOWN);
  });

  it("keeps the try's own ending when no picked value came back", () => {
    expect(tryResultSaid(tried, "missing")).toBe(tryEndedSaid(tried));
    expect(tryResultSaid(tried, undefined)).toBe(tryEndedSaid(tried));
  });
});

const NOBODY = "It waits on nobody while the run is stopped.";

const STARTER = "It waits on whoever started the run.";

const RUN_STOPPED =
  "A stopped run takes no answer, review or try until it is opened again.";

const ENTRY_STOPPED =
  "What this step runs, or the workflow itself, is stopped, so no try is made on it until that is let go.";

const HELD_ENTRY_STOPPED =
  "What it runs was stopped, so nothing is produced until that is undone.";

const HELD_WORKFLOW_STOPPED =
  "The workflow this run runs was stopped, so nothing is produced until that is undone.";

const STOPPED_AT = "2026-09-26T08:55:00Z";

const setApart = (words: string) =>
  `${String.fromCodePoint(0x2068)}${words}${String.fromCodePoint(0x2069)}`;

const STOPPED_BY_AVO = `Stopped by ${setApart("Avo")}, ${whenText(STOPPED_AT)}.`;

const ZERO_WIDTH_SPACE = String.fromCodePoint(0x200b);

const NOBODY_ACTS =
  "Nothing can be done about it until the run is opened again.";

const TRY_SENDING_BY = "Somebody who may start a run can try sending it.";

const LET_GO = "Somebody who may revoke an entry can let what was stopped go.";

const LET_GO_THEN_SEND =
  "Somebody who may revoke an entry can let what was stopped go, and then somebody who may start a run can try sending it.";

const TRY_AGAIN_BY = "Somebody who may answer a step can give it another try.";

const LET_GO_THEN_TRY_AGAIN =
  "Somebody who may revoke an entry can let what was stopped go, and then somebody who may answer a step can give it another try.";

const TRIES_SPENT =
  "Every try it allows has been made, and none of them stands.";

const CODE_NOT_HELD =
  "This system, as it runs now, does not include the code this step runs; it goes on by itself once a release holds that code step again.";

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

const HELD: Where = {
  kind: "held_back",
  reason: "entry_stopped",
  stopped: {
    what: "entry",
    by: { userId: "000cb1", displayName: "Avo" },
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

const AT_GATE: Where = { ...WAITING_ON_REVIEW, waitsOn: "review_at_gate" };

const SPENT: Where = { ...FAILED, waitsOn: "starter" };

function codeSpent(...withheld: Withheld[]): StepRow {
  return {
    ...step("failed", SPENT, withheld),
    runs: { kind: "code_step", codeStep: "tally" },
  };
}

function offering(state: string, where: Where): StepRow {
  return { ...step(state, where), acts: ["try_sending"] };
}

const RUN_VERSION = "00000007-0000-4000-8000-000000000cb1";

const TALLY: Declared = {
  takes: [
    {
      name: "line_items",
      label: "Line items",
      kind: "text",
      longest: 100,
      mustBeGiven: true,
    },
  ],
  gives: [
    {
      name: "receipt",
      label: "Receipt",
      kind: "fields",
      mustBeGiven: true,
      fields: [
        {
          name: "total",
          label: "Total",
          kind: "text",
          longest: 20,
          mustBeGiven: true,
        },
      ],
    },
  ],
};

const LATER: StepRow = {
  ...step("not_started"),
  stepId: "0000000a-0000-4000-8000-000000000cb1",
  order: 2,
  name: "check_it",
  runs: { kind: "question", name: "Check the receipt" },
};

const DECLARING: Declaring = {
  declarations: new Map([
    [
      RUN_VERSION,
      {
        takes: [],
        gives: [
          {
            name: "total_claimed",
            label: "Total claimed",
            kind: "text",
            longest: 20,
            mustBeGiven: true,
          },
        ],
      },
    ],
    ["tally", TALLY],
  ]),
  runId: "00000008-0000-4000-8000-000000000c91",
  runVersionId: RUN_VERSION,
  steps: [LATER],
};

const RECEIPT_TOTAL = `${setApart("Receipt")} › ${setApart("Total")}`;

const GIVES_OTHERWISE =
  "What this code step gives back, as this release declares it, no longer matches what the workflow reads from it or the lists this group holds, so it cannot be answered here.";

const ASKED_OF_SOMEBODY =
  "This step's next try is only ever somebody's answer to it.";

describe("misfitSaid", () => {
  it.each([
    [
      "line_break",
      "The answer gave text holding a line break for a field of one line.",
    ],
    ["not_the_shape", "The answer could not be read as what it was asked for."],
    [
      "model_declined",
      "The model declined to answer, so its answer was not taken, and this try is spent.",
    ],
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

describe("erroredForSaid", () => {
  const LINE_ITEMS = setApart("Line items");

  it.each([
    [
      "line_break",
      { field: "receipt.total" },
      `Its code gave text for ${RECEIPT_TOTAL} holding a line break, which that field, of one line, does not take.`,
    ],
    ["gave_nothing", {}, "Its code gave nothing back at all."],
    [
      "not_declared",
      { member: "colour" },
      `Its code gave back a member named ${setApart("colour")}, which nothing declares.`,
    ],
    [
      "nothing_given",
      { field: "receipt.total" },
      `Its code gave nothing for ${RECEIPT_TOTAL}, which must be given.`,
    ],
    [
      "not_its_kind",
      { field: "receipt.total" },
      `Its code gave a value of the wrong kind for ${RECEIPT_TOTAL}.`,
    ],
    [
      "too_long",
      { field: "receipt.total" },
      `Its code gave a value for ${RECEIPT_TOTAL} longer than that field allows.`,
    ],
    [
      "too_many",
      { field: "receipt.total" },
      `Its code gave more for ${RECEIPT_TOTAL} than that field allows.`,
    ],
    [
      "not_a_term",
      { field: "receipt.total" },
      `Its code gave a value for ${RECEIPT_TOTAL} that is none of that field's terms.`,
    ],
    [
      "unkeepable",
      { field: "receipt.total" },
      `Its code gave text for ${RECEIPT_TOTAL} holding a character that cannot be kept.`,
    ],
    [
      "too_long_to_keep",
      { field: "receipt.total" },
      `Its code gave a value for ${RECEIPT_TOTAL} that fits the field but is longer than any one value may be kept.`,
    ],
    [
      "file_type_unusable",
      { field: "receipt.total" },
      `Its code gave a file for ${RECEIPT_TOTAL} of a type that field does not take, so no file it gave back was kept.`,
    ],
    [
      "file_name_unusable",
      { field: "receipt.total" },
      `Its code gave a file for ${RECEIPT_TOTAL} whose name is not one to 255 characters on one line, with something in it that shows, so no file it gave back was kept.`,
    ],
    [
      "file_too_large",
      { field: "receipt.total" },
      `Its code gave a file for ${RECEIPT_TOTAL} larger than that field allows, so no file it gave back was kept.`,
    ],
    [
      "file_total_too_large",
      {},
      "Its code gave back files that together came to more than this deployment takes at once, so no file it gave back was kept.",
    ],
    [
      "takes_a_list_not_here",
      { field: "line_items" },
      `The terms of ${LINE_ITEMS}, which its code takes, came from a reference list this group did not hold.`,
    ],
    [
      "gives_a_list_not_here",
      { field: "receipt.total" },
      `The terms of ${RECEIPT_TOTAL}, which its code gives back, came from a reference list this group did not hold.`,
    ],
    [
      "takes_otherwise",
      { field: "line_items" },
      `What this workflow gives its code as ${LINE_ITEMS} no longer matched what the code takes.`,
    ],
    [
      "gives_otherwise",
      { field: "receipt.total", readBy: { step: LATER.stepId } },
      `What its code gave back as ${RECEIPT_TOTAL} no longer matched what ${setApart("Check the receipt")} was to read from it.`,
    ],
    [
      "failed_on_this_side",
      {},
      "Running its code went wrong on this system's side, whatever the code itself did.",
    ],
    ["said_nothing", {}, "Its code went wrong without saying why."],
  ] satisfies [ErroredForReason, Omit<ErroredFor, "reason">, string][])(
    "says code gone wrong for %s in the reader's words, its field by its label, never in its spelling",
    (reason, named, sentence) => {
      const said = erroredForSaid({ reason, ...named }, TALLY, DECLARING);

      expect(said).toBe(sentence);
    },
  );

  it.each([
    [
      "a member within a field",
      { reason: "not_declared", field: "receipt", member: "colour" },
      `Its code gave back a member named ${setApart("colour")} within ${setApart("Receipt")}, which nothing declares there.`,
    ],
    [
      "a member with no name that can be shown",
      { reason: "not_declared", member: "" },
      "Its code gave back a member with no name that can be shown, which nothing declares.",
    ],
    [
      "a member whose name was cut",
      { reason: "not_declared", member: "colour_of_the_…", memberCut: true },
      `Its code gave back a member named ${setApart("colour_of_the_…")} (its name cut short here), which nothing declares.`,
    ],
    [
      "a member ending in an ellipsis with no cut flag",
      { reason: "not_declared", member: "colour_of_the_…" },
      `Its code gave back a member named ${setApart("colour_of_the_…")}, which nothing declares.`,
    ],
    [
      "a long member name ending in an ellipsis without a cut flag",
      { reason: "not_declared", member: "𝒜".repeat(64) + "…" },
      `Its code gave back a member named ${setApart("𝒜".repeat(64) + "…")}, which nothing declares.`,
    ],
    [
      "a field the code step no longer declares, by its name read as a person reads it",
      { reason: "too_long", field: "grand_total" },
      `Its code gave a value for ${setApart("grand total")} longer than that field allows.`,
    ],
    [
      "a field read into what the workflow gives back",
      {
        reason: "gives_otherwise",
        field: "receipt.total",
        readBy: { output: "total_claimed" },
      },
      `What its code gave back as ${RECEIPT_TOTAL} no longer matched what this workflow was to give back as ${setApart("Total claimed")}.`,
    ],
    [
      "a field read by a step the read does not hold",
      {
        reason: "gives_otherwise",
        field: "receipt.total",
        readBy: { step: "0000000b-0000-4000-8000-000000000cb1" },
      },
      UNKNOWN,
    ],
  ] satisfies [string, ErroredFor, string][])(
    "says %s",
    (_case, erroredFor, sentence) => {
      expect(erroredForSaid(erroredFor, TALLY, DECLARING)).toBe(sentence);
    },
  );

  it.each([
    ["a reason this build does not know", { reason: "ran_too_hot" }],
    ["a field that did not come", { reason: "too_long" }],
    ["a member that did not come", { reason: "not_declared" }],
    [
      "what reads a field, which did not come",
      { reason: "gives_otherwise", field: "receipt.total" },
    ],
  ] satisfies [string, ErroredFor][])(
    "says code gone wrong for %s as something it cannot say yet, never in the server's spelling",
    (_case, erroredFor) => {
      const said = erroredForSaid(erroredFor, TALLY, DECLARING);

      expect(said).toBe(UNKNOWN);
    },
  );

  it("uses a later step only where the step-list read failed", () => {
    expect(
      erroredForSaid(
        {
          reason: "gives_otherwise",
          field: "receipt.total",
          readBy: { step: LATER.stepId },
        },
        TALLY,
        { ...DECLARING, steps: undefined },
      ),
    ).toBe(
      `What its code gave back as ${RECEIPT_TOTAL} no longer matched what a later step was to read from it.`,
    );
  });
});

describe("givesOtherwiseSaid", () => {
  it("names the reader as a later step when the step-list read failed", () => {
    const said = givesOtherwiseSaid(
      {
        field: "receipt.total",
        readBy: { step: "0000000b-0000-4000-8000-000000000cb1" },
      },
      TALLY,
      { ...DECLARING, steps: undefined },
    );

    expect(said).toBe(
      `What its code gives back as ${RECEIPT_TOTAL} no longer matches what a later step reads from it.`,
    );
  });
});

describe("whereSaid", () => {
  it("says a missing search way goes on after a release, without offering a person a retry", () => {
    const of = step("held_back", {
      kind: "held_back",
      reason: "search_way_not_held",
      waitsOn: "answer_step",
    });
    expect(whereSaid(of, GIVES)).toEqual([
      "Held back",
      "This system, as it runs now, does not include the way to search this step runs; it goes on by itself once a release holds it again.",
    ]);
  });

  it("names the failed search's saved tool and producer mode, and offers sending rather than another try", () => {
    const of = {
      ...step("failed", {
        kind: "failed",
        reason: "tool_not_offered",
        model: "small",
        mode: "careful",
        tool: "web_search",
        waitsOn: "answer_step",
      }),
      acts: ["try_sending" as const],
      runs: { kind: "search", searchWay: "web", tool: "a_new_tool" },
    };
    const lines = whereSaid(of, GIVES);
    expect(lines).toContain(
      `It names small in careful to search, which does not offer ${setApart("web_search")}, the tool this way to search needs.`,
    );
    expect(lines.join(" ")).not.toContain("a_new_tool");
    expect(lines.join(" ")).not.toContain("another try");
    expect(lines).toContain("Somebody who may start a run can try sending it.");
  });
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

  it("names the actual source when a later step cannot send its code's value, without saying the later step runs missing code", () => {
    const of: StepRow = {
      ...step("held_back", {
        kind: "held_back",
        reason: "code_step_not_held",
        waitsOn: "starter",
        source: {
          runId: "00000008-0000-4000-8000-000000000c92",
          runNumber: 19,
          stepId: "00000009-0000-4000-8000-000000000c92",
          name: "Demo Sample source",
          codeStep: "tally",
        },
      }),
      runs: { kind: "question", name: "Demo Sample destination" },
    };

    const lines = whereSaid(of, GIVES);

    expect(lines).toEqual([
      "Held back",
      `A value it reads came from ${setApart("Demo Sample source")}, whose code this system, as it runs now, does not include, so it cannot be sent; it goes on by itself once a release holds that code step again.`,
    ]);
    expect(lines).not.toContain(CODE_NOT_HELD);
    expect(lines.join(" ")).not.toContain("Demo Sample destination");
    expect(lines).not.toContain(TRY_SENDING_BY);
    expect(lines.join(" ")).not.toMatch(/It waits on/);
  });

  it.each([
    [
      "source_no_longer_matches",
      "failedSourceNoLongerMatches",
      `What ${setApart("Demo Sample source")} gave back as ${setApart("info.answer")} no longer matches what this step reads from it, as that code step is declared now, so nothing was sent.`,
    ],
    [
      "source_list_not_here",
      "failedSourceListNotHere",
      `The terms of ${setApart("info.answer")}, which ${setApart("Demo Sample source")} gave back, come from a reference list version this group does not hold in service, so nothing was sent.`,
    ],
  ] as const)(
    "names the source field for %s and offers sending instead of another try or automatic release",
    (reason, member, sentence) => {
      const of: StepRow = {
        ...step("failed", {
          kind: "failed",
          reason,
          waitsOn: "starter",
          [member]: {
            source: {
              runId: "00000008-0000-4000-8000-000000000c92",
              runNumber: 19,
              stepId: "00000009-0000-4000-8000-000000000c92",
              name: "Demo Sample source",
              codeStep: "tally",
            },
            field: "info.answer",
          },
        }),
        acts: ["try_sending"],
        takesFrom: [
          { input: "text", from: { kind: "step", path: "info.answer" } },
        ],
      };

      const lines = whereSaid(of, GIVES);

      expect(lines).toEqual(["Failed", sentence, TRY_SENDING_BY]);
      expect(lines).not.toContain(TRY_AGAIN_BY);
      expect(lines).not.toContain(CODE_NOT_HELD);
      expect(lines.join(" ")).not.toContain("gave back as text");
      expect(lines.join(" ")).not.toContain("goes on by itself");
    },
  );

  /** Held back and failed never wait on anybody: they say who can deal with them, or nothing where nobody can. */
  it.each([
    [
      "a step held back on a stop, who can let it go",
      step("held_back", { ...HELD, waitsOn: "starter" }),
      ["Held back", HELD_ENTRY_STOPPED, STOPPED_BY_AVO, LET_GO],
    ],
    [
      "a step whose code the running release does not hold, which goes on by itself, nothing more",
      {
        ...step("held_back", {
          kind: "held_back",
          reason: "code_step_not_held",
          waitsOn: "starter",
        }),
        runs: { kind: "code_step", codeStep: "tally" },
      },
      ["Held back", CODE_NOT_HELD],
    ],
    [
      "a step whose every try is spent, which somebody may try again",
      step("failed", { ...FAILED, waitsOn: "starter" }),
      ["Failed", TRIES_SPENT, TRY_AGAIN_BY],
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

  /** Do not invent a route value absent from the read. */
  it("says a route failed as no branch took its value is something it cannot say yet, never without the value", () => {
    for (const waitsOn of ["starter", undefined]) {
      for (const stopped of [undefined, HELD.stopped]) {
        const lines = whereSaid(
          {
            ...step("failed", {
              kind: "failed",
              reason: "unclaimed_value",
              ...(waitsOn === undefined ? {} : { waitsOn }),
              ...(stopped === undefined
                ? {}
                : { stopped: { ...stopped, what: "workflow" } }),
            }),
            runs: { kind: "route" },
          },
          GIVES,
        );

        expect(lines).toEqual(["Failed", UNKNOWN]);
        expect(lines.join(" ")).not.toMatch(
          /branch|claimed|unclaimed_value|It waits on/,
        );
      }
    }
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
        STOPPED_BY_AVO,
        sentence,
        LET_GO_THEN_SEND,
      ]);
      expect(lines).not.toContain(TRY_SENDING_BY);
    },
  );

  it("says the stop first on a step held back as its code is not held, then that it goes on when a release holds it again", () => {
    const lines = whereSaid(
      {
        ...step("held_back", {
          kind: "held_back",
          reason: "code_step_not_held",
          stopped: { ...HELD.stopped!, what: "workflow" },
          waitsOn: "starter",
        }),
        runs: { kind: "code_step", codeStep: "tally" },
      },
      GIVES,
    );

    expect(lines).toEqual([
      "Held back",
      HELD_WORKFLOW_STOPPED,
      STOPPED_BY_AVO,
      CODE_NOT_HELD,
    ]);
  });

  it("draws the term no branch of a route took isolated in its sentence, a character in it that shows nothing drawn as a marker and never itself", () => {
    const failed = step("failed", {
      kind: "failed",
      reason: "unclaimed_value",
    });
    const [state, ...why] = whereSaid(
      {
        ...failed,
        where: {
          ...failed.where!,
          failedUnclaimed: { term: `Demo${ZERO_WIDTH_SPACE}Sample` },
        },
      },
      GIVES,
    );
    const drawn = render(createElement("p", null, ...why)).container;

    expect(state).toBe("Failed");
    expect(why).toHaveLength(1);
    expect(drawn.textContent).toBe(
      "No branch of this route takes “DemoU+200BSample”, and it has no fallback. The way on is a new version of this workflow, and a new run.",
    );
    expect(drawn.querySelector("bdi")?.textContent).toBe("DemoU+200BSample");
    expect(
      screen.getByRole("img", {
        name: "A character that shows nothing is here: U+200B.",
      }),
    ).toBeInTheDocument();
    expect(drawn.innerHTML).not.toContain(ZERO_WIDTH_SPACE);
    expect(drawn.textContent).not.toContain("unclaimed_value");
  });

  it("says a route had no value to choose by in words alone, naming no term and drawing no marker", () => {
    const failed = step("failed", {
      kind: "failed",
      reason: "unclaimed_value",
    });

    expect(
      whereSaid(
        {
          ...failed,
          where: { ...failed.where!, failedUnclaimed: { term: null } },
        },
        GIVES,
      ),
    ).toEqual([
      "Failed",
      "This route had no value to choose by, and it has no fallback. The way on is a new version of this workflow, and a new run.",
    ]);
  });

  it("says a model-produced route choice is withheld without naming the value or an action", () => {
    const failed = step("failed", {
      kind: "failed",
      reason: "unclaimed_value",
    });
    const lines = whereSaid(
      {
        ...failed,
        where: { ...failed.where!, failedUnclaimed: { withheld: true } },
      },
      GIVES,
    );

    expect(lines).toEqual([
      "Failed",
      "No branch of this route takes the value it chose by, which a model produced and which is withheld from you, and it has no fallback. The way on is a new version of this workflow, and a new run.",
    ]);
    expect(lines.join(" ")).not.toMatch(
      /Demo Sample|unclaimed_value|Refuse|Try sending/,
    );
  });

  /** An entry stop cannot mend a route whose value no branch took. */
  it.each([["no branch taking its value", "unclaimed_value", UNKNOWN]])(
    "says only the failure of a step failed for %s while what it runs is stopped",
    (_case, reason, sentence) => {
      const lines = whereSaid(
        {
          ...step("failed", {
            kind: "failed",
            reason,
            stopped: { ...HELD.stopped!, what: "workflow" },
            waitsOn: "starter",
          }),
          runs: { kind: "route" },
        },
        GIVES,
      );

      expect(lines).toEqual(["Failed", sentence]);
      expect(lines).not.toContain(HELD_ENTRY_STOPPED);
      expect(lines.join(" ")).not.toMatch(/Stopped|let what was stopped go/);
    },
  );

  it.each([
    ["entry", HELD_ENTRY_STOPPED],
    ["workflow", HELD_WORKFLOW_STOPPED],
  ])(
    "says a stopped %s must be let go before a spent step is tried again, with or without its stopper named",
    (what, stop) => {
      for (const by of [HELD.stopped!.by, undefined]) {
        const lines = whereSaid(
          step("failed", {
            ...FAILED,
            stopped: {
              what,
              at: STOPPED_AT,
              ...(by === undefined ? {} : { by }),
            },
            waitsOn: "starter",
          }),
          GIVES,
        );

        expect(lines).toEqual([
          "Failed",
          stop,
          by === undefined
            ? `Stopped ${whenText(STOPPED_AT)}, by somebody who can no longer be found.`
            : STOPPED_BY_AVO,
          TRIES_SPENT,
          LET_GO_THEN_TRY_AGAIN,
        ]);
      }
    },
  );

  it.each([
    ["tries spent", "failed", "tries_spent", TRIES_SPENT],
    ["too long to send", "held_back", "too_long", TOO_LONG],
    ["a model not held", "failed", "model_not_deployed", MODEL_NOT_HELD],
    ["code not held", "held_back", "code_step_not_held", CODE_NOT_HELD],
  ])(
    "says the run must be opened again before %s can go on, even when what it runs is also stopped",
    (_case, kind, reason, why) => {
      for (const stopped of [undefined, HELD.stopped]) {
        const lines = whereSaid(
          {
            ...step(kind, {
              kind,
              reason,
              ...(stopped === undefined
                ? {}
                : {
                    stopped: {
                      ...stopped,
                      what:
                        reason === "code_step_not_held" ? "workflow" : "entry",
                    },
                  }),
            }),
            ...(reason === "code_step_not_held"
              ? { runs: { kind: "code_step", codeStep: "tally" } }
              : {}),
          },
          GIVES,
        );

        expect(lines).toEqual([
          kind === "failed" ? "Failed" : "Held back",
          ...(stopped === undefined
            ? []
            : [
                reason === "code_step_not_held"
                  ? HELD_WORKFLOW_STOPPED
                  : HELD_ENTRY_STOPPED,
                STOPPED_BY_AVO,
              ]),
          why,
          NOBODY_ACTS,
        ]);
      }
    },
  );

  it("says an unknown kind of stop as unknown, keeping who stopped it and when", () => {
    expect(
      whereSaid(
        step("held_back", {
          ...HELD,
          stopped: { ...HELD.stopped!, what: "a_ledger" },
          waitsOn: "starter",
        }),
        GIVES,
      ),
    ).toEqual(["Held back", UNKNOWN, STOPPED_BY_AVO, LET_GO]);
  });

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
      HELD_WORKFLOW_STOPPED,
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

  it.each([
    [
      1,
      "Runs may go at most 1 run deep, counting the run at the top, and this step would have started one deeper than that. The way on is a new version of this workflow, and a new run.",
    ],
    [
      32,
      "Runs may go at most 32 runs deep, counting the run at the top, and this step would have started one deeper than that. The way on is a new version of this workflow, and a new run.",
    ],
  ])(
    "says a step failed for starting a run deeper than runs may go, at most %i deep, and offers no way on",
    (most, sentence) => {
      const lines = whereSaid(
        {
          ...step("failed", {
            kind: "failed",
            reason: "too_deep",
            failedTooDeep: { most },
            waitsOn: "starter",
          }),
          runs: { kind: "workflow", name: "Demo Sample" },
        },
        GIVES,
      );

      expect(lines).toEqual(["Failed", sentence]);
      expect(lines.join(" ")).not.toMatch(
        /too_deep|\{|try|It waits on|let what was stopped go/,
      );
    },
  );

  it.each([
    ["while the run is stopped", {}],
    [
      "while what it runs is stopped",
      { stopped: HELD.stopped!, waitsOn: "starter" },
    ],
    ["though sending is offered", { waitsOn: "starter" }],
  ])(
    "says only why a step that would have started a run too deep failed, %s",
    (_case, around) => {
      const lines = whereSaid(
        offering("failed", {
          kind: "failed",
          reason: "too_deep",
          failedTooDeep: { most: 32 },
          ...around,
        }),
        GIVES,
      );

      expect(lines).toEqual([
        "Failed",
        expect.stringMatching(/^Runs may go at most 32 runs deep/),
      ]);
      expect(lines).not.toContain(NOBODY_ACTS);
      expect(lines).not.toContain(TRY_SENDING_BY);
      expect(lines).not.toContain(HELD_ENTRY_STOPPED);
      expect(lines).not.toContain(STOPPED_BY_AVO);
    },
  );

  it("says a step failed for going deeper without saying how deep is something it cannot say yet", () => {
    const lines = whereSaid(
      step("failed", {
        kind: "failed",
        reason: "too_deep",
        waitsOn: "starter",
      }),
      GIVES,
    );

    expect(lines).toEqual(["Failed", UNKNOWN]);
    expect(lines.join(" ")).not.toMatch(/Runs may go|\{|too_deep/);
  });

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
      HELD_WORKFLOW_STOPPED,
      STOPPED_BY_AVO,
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
      for (const reason of ["budget_gone", undefined]) {
        for (const waitsOn of ["starter", undefined]) {
          const lines = whereSaid(
            step(kind, {
              kind,
              ...(reason === undefined ? {} : { reason }),
              ...(waitsOn === undefined ? {} : { waitsOn }),
              stopped: HELD.stopped!,
            }),
            GIVES,
          );

          expect(lines).toEqual([state, UNKNOWN]);
          expect(lines.join(" ")).not.toMatch(/budget_gone|It waits on/);
        }
      }
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
    for (const waitsOn of ["starter", undefined]) {
      const lines = whereSaid(
        step("waiting", {
          kind: "on_hold",
          ...(waitsOn === undefined ? {} : { waitsOn }),
        }),
        GIVES,
      );

      expect(lines).toEqual(["Waiting", UNKNOWN]);
      expect(lines.join(" ")).not.toMatch(/waits on/);
    }
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

function withheldSaid(step: StepRow): string[] {
  return withheldIn(step, DECLARING);
}

describe("withheldSaid", () => {
  it.each([
    [
      "the later step reading it",
      { field: "receipt.total", readBy: { step: LATER.stepId } },
      `What its code gives back as ${RECEIPT_TOTAL} no longer matches what ${setApart("Check the receipt")} reads from it.`,
    ],
    [
      "what the workflow gives back of it",
      { field: "receipt.total", readBy: { output: "total_claimed" } },
      `What its code gives back as ${RECEIPT_TOTAL} no longer matches what this workflow gives back as ${setApart("Total claimed")}.`,
    ],
    [
      "nothing, as the list its terms come from is not this group's",
      { field: "receipt.total" },
      `The terms of ${RECEIPT_TOTAL}, which its code gives back, come from a reference list this group does not hold.`,
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
      "What this code step gives back, as this release declares it, no longer matches what the workflow reads from it or the lists this group holds, so it cannot be answered here.",
      `The terms of ${RECEIPT_TOTAL}, which its code gives back, come from a reference list this group does not hold.`,
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
      "sent again by itself, what the model said left to be drawn where it is",
      { said: "Too many asks.", cut: false, sentAgain: true },
      [`Turned away ${whenText(TURNED_AT)}, and sent again by itself.`],
    ],
    [
      "not sent again, what the model said cut left to be drawn where it is",
      { said: "Too many", cut: true, sentAgain: false },
      [`Turned away ${whenText(TURNED_AT)}.`],
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

describe("model review words", () => {
  it.each([
    [
      "unsent",
      "It waits on a model's review, which this system sends by itself.",
    ],
    ["out", "It waits on a model's review; the call asking for it is out."],
    [
      "turned_away",
      "It waits on a model's review, which could not be sent: the model would not take it.",
    ],
    [
      "too_long",
      "What its review would send is longer than the model that reviews it takes, so it was not sent, and somebody who may review it does so instead.",
    ],
    [
      "list_not_here",
      "This could not be put to the model that reviews it: a reference list it names is not here, so a person reviews it instead.",
    ],
    [
      "takes_no_longer_declared",
      "This could not be put to the model that reviews it: the release no longer declares what this step takes, so a person reviews it instead.",
    ],
    [
      "no_longer_declared",
      "This could not be put to the model that reviews it: the release no longer declares what this step gave back, so a person reviews it instead.",
    ],
    [
      "holds_a_file",
      "This could not be put to the model that reviews it: a file went into it or came back from it, and a model is never sent one, so a person reviews it instead.",
    ],
  ])(
    "says a waiting review that is %s in its own words",
    (review, sentence) => {
      const said = whereSaid(
        step("waiting", { ...WAITING_ON_REVIEW, review, waitsOn: "model" }),
        GIVES,
      );
      expect(said).toEqual([
        "Waiting",
        `${setApart("Summary")} is not reviewed yet.`,
        sentence,
        ...(review === "turned_away" ? [TRY_SENDING_BY] : []),
      ]);
      expect(said).not.toContain("Held back");
      expect(said).not.toContain("It waits on the model it names.");
    },
  );

  it("says a spent-up review can be tried by a person and a stopped run permits nobody", () => {
    const where = {
      ...WAITING_ON_REVIEW,
      review: "turned_away",
      spentUp: true as const,
    };
    expect(
      whereSaid(step("waiting", { ...where, waitsOn: "starter" }), GIVES),
    ).toEqual([
      "Waiting",
      `${setApart("Summary")} is not reviewed yet.`,
      "It waits on a model's review, which could not be sent: what may be spent with that model is used up, so it is not sent again by itself.",
      TRY_SENDING_BY,
    ]);
    expect(whereSaid(step("waiting", where), GIVES)).toEqual([
      "Waiting",
      `${setApart("Summary")} is not reviewed yet.`,
      "It waits on a model's review, which could not be sent: what may be spent with that model is used up, so it is not sent again by itself.",
      NOBODY,
    ]);
  });

  it("says a review spelling unknown here as unknown without printing its wire text", () => {
    expect(
      whereSaid(
        step("waiting", {
          ...WAITING_ON_REVIEW,
          review: "later",
          waitsOn: "model",
        }),
        GIVES,
      ),
    ).toEqual([
      "Waiting",
      `${setApart("Summary")} is not reviewed yet.`,
      UNKNOWN,
    ]);
  });
});

describe("review lost", () => {
  it.each([
    [
      "did_not_fit",
      "The model's review did not fit what it was asked for, so it was not taken, and this try is spent.",
    ],
    [
      "nothing_came_back",
      "The model's review was sent and nothing came back, so this try is spent.",
    ],
    ["errored", "small, whose review went wrong"],
    ["later", UNKNOWN],
  ])(
    "says review lost as %s separately from a producer's lost answer",
    (lost, sentence) => {
      const tried: Try = {
        number: 1,
        beyond: false,
        producedBy: { kind: "person" },
        values: [],
        review: {
          asked: true,
          by: { kind: "model", model: "small" },
          wentWrong: true,
          lost,
        },
        ended: "nothing_came_back",
        cost: {},
      };
      expect(tryEndedSaid(tried)).toBe(sentence);
      expect(tryEndedSaid({ ...tried, review: { asked: false } })).toBe(
        "Nothing came back.",
      );
    },
  );
});
