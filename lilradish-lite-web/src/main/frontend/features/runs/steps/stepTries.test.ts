import { render } from "@testing-library/react";
import { ThemeProvider } from "@mui/material/styles";
import { createElement } from "react";
import { describe, expect, it } from "vitest";

import type { DidNotFitReason, ErroredForReason } from "../../../api/stepTries";
import type { ErroredFor, Try, Turnaway } from "../../../api/stepTries";
import { whenText } from "../../../lib/time/When";
import { theme } from "../../../lib/theme/theme";
import { say } from "../../../i18n/app";
import {
  DECLARING,
  LATER,
  RECEIPT_TOTAL,
  setApart,
  TALLY,
  UNKNOWN,
} from "../../../testutil/stepWording";
import {
  erroredForSaid,
  reviewVisible,
  tryBeyondVisible,
  misfitSaid,
  tryEndedSaid,
  tryResultSaid,
  turnawaySaid,
} from "./stepTries";

describe("review lost", () => {
  it.each([
    [
      "did_not_fit",
      "The model's review was not in the expected format, so it was not used. This attempt is used up.",
    ],
    [
      "nothing_came_back",
      "The review request got no response, so this attempt is used up.",
    ],
    ["errored", "small (the review hit an error)"],
    ["later", UNKNOWN],
  ])(
    "says review lost as %s separately from a producer's lost answer",
    (lost, sentence) => {
      const tried: Try = {
        wentWrongPresent: false,
        returnedPresent: false,
        declinedPresent: false,
        unreadableResponsePresent: false,
        modelReplyPresent: false,
        number: 1,
        beyond: false,
        producedBy: { kind: "person" },
        values: [],
        review: {
          declinedPresent: false,
          unreadableResponsePresent: false,
          modelReplyPresent: false,
          asked: true,
          by: { kind: "model", model: "small" },
          wentWrong: true,
          lost,
        },
        ended: "nothing_came_back",
        cost: {},
      };
      expect(tryEndedSaid(tried)).toBe(sentence);
      expect(
        tryEndedSaid({
          ...tried,
          review: {
            asked: false,
            declinedPresent: false,
            unreadableResponsePresent: false,
            modelReplyPresent: false,
          },
        }),
      ).toBe("No response came back.");
    },
  );
});

describe("a picked value's result", () => {
  const tried: Try = {
    wentWrongPresent: false,
    returnedPresent: false,
    declinedPresent: false,
    unreadableResponsePresent: false,
    modelReplyPresent: false,
    number: 1,
    beyond: false,
    producedBy: { kind: "model", model: "small" },
    values: [
      { field: "first", value: "Demo Sample", now: "stands" },
      { field: "second", value: "No", now: "refused" },
      { field: "third", value: "No", now: "refused_for_length" },
      { field: "future", value: "No", now: "future_standing" },
    ],
    review: {
      asked: true,
      declinedPresent: false,
      unreadableResponsePresent: false,
      modelReplyPresent: false,
    },
    ended: "refused_on_review",
    cost: {},
  };

  it("says each picked value's own standing and keeps an unknown spelling unknown", () => {
    expect(tryResultSaid(tried, "first")).toBe("Accepted.");
    expect(tryResultSaid(tried, "second")).toBe("Rejected in review.");
    expect(tryResultSaid(tried, "third")).toBe("Rejected for length.");
    expect(tryResultSaid(tried, "future")).toBe(UNKNOWN);
  });

  it("keeps the try's own ending when no picked value came back", () => {
    expect(tryResultSaid(tried, "missing")).toBe(tryEndedSaid(tried));
    expect(tryResultSaid(tried, undefined)).toBe(tryEndedSaid(tried));
  });
});

describe("misfitSaid", () => {
  it.each([
    ["line_break", "The answer put a line break in a single-line field."],
    ["not_the_shape", "The answer was not in the expected format."],
    [
      "model_declined",
      "The model declined to answer, so nothing was taken from it, and this attempt is used up.",
    ],
    ["field_missing", "The answer left out a value it was asked for."],
    ["field_unknown", "The answer gave a value nobody asked for."],
    ["nothing_given", "The answer gave nothing for a required value."],
    [
      "not_its_kind",
      "The answer gave a value of the wrong kind for its field.",
    ],
    ["too_long", "The answer gave a value longer than its field allows."],
    [
      "too_many",
      "The answer gave more items for a value than its field allows.",
    ],
    [
      "not_a_term",
      "The answer gave a value that is not one of its field's options.",
    ],
    ["unkeepable", "The answer contained a character that can't be stored."],
    [
      "too_long_to_keep",
      "The answer gave a value that fits its field but is too long to store.",
    ],
    [
      "confidence_missing",
      "The answer did not give a confidence for a value that needed one.",
    ],
    [
      "confidence_unasked",
      "The answer gave a confidence for a value that didn't ask for one.",
    ],
    [
      "confidence_not_a_percent",
      "The answer gave a confidence that is not a whole number from 0 to 100.",
    ],
    ["undecided", "The review left a value neither accepted nor rejected."],
    ["words_missing", "The review rejected a value without saying why."],
    ["words_too_long", "The review's reason for a rejection was too long."],
    [
      "words_unkeepable",
      "The review's reason for a rejection contained a character that isn't allowed.",
    ],
    [
      "cut_off",
      "The model reached its maximum answer length, so its answer was cut off.",
    ],
    [
      "not_kept_as_it_came",
      "The answer contained something that couldn't be stored exactly as received.",
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
      `The code returned text with a line break for ${RECEIPT_TOTAL}, which is a single-line field.`,
    ],
    ["gave_nothing", {}, "The code returned nothing at all."],
    [
      "not_declared",
      { member: "colour" },
      `The code returned a field named ${setApart("colour")}, which isn't defined.`,
    ],
    [
      "nothing_given",
      { field: "receipt.total" },
      `The code returned nothing for ${RECEIPT_TOTAL}, which is required.`,
    ],
    [
      "not_its_kind",
      { field: "receipt.total" },
      `The code returned a value of the wrong kind for ${RECEIPT_TOTAL}.`,
    ],
    [
      "too_long",
      { field: "receipt.total" },
      `The code returned a value for ${RECEIPT_TOTAL} longer than that field allows.`,
    ],
    [
      "too_many",
      { field: "receipt.total" },
      `The code returned more items for ${RECEIPT_TOTAL} than that field allows.`,
    ],
    [
      "not_a_term",
      { field: "receipt.total" },
      `The code returned a value for ${RECEIPT_TOTAL} that is not one of that field's options.`,
    ],
    [
      "unkeepable",
      { field: "receipt.total" },
      `The code returned text for ${RECEIPT_TOTAL} with a character that can't be stored.`,
    ],
    [
      "too_long_to_keep",
      { field: "receipt.total" },
      `The code returned a value for ${RECEIPT_TOTAL} that fits the field but is too long to store.`,
    ],
    [
      "file_type_unusable",
      { field: "receipt.total" },
      `Its code gave a file for ${RECEIPT_TOTAL} without an extension that field accepts, so none of the files it gave back were kept.`,
    ],
    [
      "file_name_unusable",
      { field: "receipt.total" },
      `The code returned a file for ${RECEIPT_TOTAL} without a valid one-line name of 1 to 255 characters, so none of its files were kept.`,
    ],
    [
      "file_too_large",
      { field: "receipt.total" },
      `The code returned a file for ${RECEIPT_TOTAL} larger than that field allows, so none of its files were kept.`,
    ],
    [
      "file_total_too_large",
      {},
      "The code returned files that together exceed what this system accepts at once, so none of them were kept.",
    ],
    [
      "takes_a_list_not_here",
      { field: "line_items" },
      `The options for ${LINE_ITEMS}, an input of the code, came from a reference list this group didn't have.`,
    ],
    [
      "gives_a_list_not_here",
      { field: "receipt.total" },
      `The options for ${RECEIPT_TOTAL}, an output of the code, came from a reference list this group didn't have.`,
    ],
    [
      "takes_otherwise",
      { field: "line_items" },
      `The input this workflow passed to the code as ${LINE_ITEMS} no longer matched what the code expects.`,
    ],
    [
      "gives_otherwise",
      { field: "receipt.total", readBy: { step: LATER.stepId } },
      `The code's output ${RECEIPT_TOTAL} no longer matched what ${setApart("Check the receipt")} expected from it.`,
    ],
    [
      "failed_on_this_side",
      {},
      "Running the code failed on this system's side, whatever the code itself did.",
    ],
    ["said_nothing", {}, "The code hit an error without saying why."],
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
      `The code returned a field named ${setApart("colour")} inside ${setApart("Receipt")}, where it isn't defined.`,
    ],
    [
      "a field with no name that can be shown",
      { reason: "not_declared", member: "" },
      "The code returned a field with no name that can be shown, which isn't defined.",
    ],
    [
      "a member whose name was cut",
      { reason: "not_declared", member: "colour_of_the_…", memberCut: true },
      `The code returned a field named ${setApart("colour_of_the_…")} (name shortened here), which isn't defined.`,
    ],
    [
      "a member ending in an ellipsis with no cut flag",
      { reason: "not_declared", member: "colour_of_the_…" },
      `The code returned a field named ${setApart("colour_of_the_…")}, which isn't defined.`,
    ],
    [
      "a long member name ending in an ellipsis without a cut flag",
      { reason: "not_declared", member: "𝒜".repeat(64) + "…" },
      `The code returned a field named ${setApart("𝒜".repeat(64) + "…")}, which isn't defined.`,
    ],
    [
      "a field the code step no longer declares, by its name read as a person reads it",
      { reason: "too_long", field: "grand_total" },
      `The code returned a value for ${setApart("grand total")} longer than that field allows.`,
    ],
    [
      "a field read into what the workflow gives back",
      {
        reason: "gives_otherwise",
        field: "receipt.total",
        readBy: { output: "total_claimed" },
      },
      `The code's output ${RECEIPT_TOTAL} no longer matched this workflow's output ${setApart("Total claimed")}.`,
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
      `The code's output ${RECEIPT_TOTAL} no longer matched what a later step expected from it.`,
    );
  });
});

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
      "beyond the",
      "allowed",
    ]);
    expect(
      hints.every((part) => getComputedStyle(part).fontStyle === "italic"),
    ).toBe(true);
  });

  it("shows a failed review with the reviewer separate from its explanation", () => {
    const review = {
      declinedPresent: false,
      unreadableResponsePresent: false,
      modelReplyPresent: false,
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

    expect(sentence.textContent).toBe("small (the review hit an error)");
    expect(sentence.querySelector("bdi")).toHaveTextContent("small");
    const hint = sentence.querySelector("span")!;
    expect(hint).toHaveTextContent("(the review hit an error)");
    expect(getComputedStyle(hint).fontStyle).toBe("italic");
  });
});

describe("turnawaySaid", () => {
  const TURNED_AT = "2026-09-26T09:01:00Z";

  it.each([
    [
      "sent again by itself, what the model said left to be drawn where it is",
      { said: "Too many asks.", cut: false, sentAgain: true },
      [`Turned away ${whenText(TURNED_AT)}, then resent automatically.`],
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
        "You don't have permission to see what the model said.",
      ],
    ],
    [
      "sent again by itself, the model saying nothing",
      { sentAgain: true },
      [`Turned away ${whenText(TURNED_AT)}, then resent automatically.`],
    ],
  ])("says when a call was turned away, %s", (_case, turned, lines) => {
    const said = turnawaySaid({ at: TURNED_AT, ...turned } satisfies Turnaway);

    expect(said).toEqual(lines);
  });
});
