import { render, screen, within } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import type { DeclaredField } from "../../api/declaration";
import type { ContentProblem } from "../../api/groups/{groupId}/{kind}/{entryId}/versions";
import { isolatedInText } from "../../lib/direction/isolated";
import {
  ContentProblems,
  contentOf,
  type ReadContent,
} from "./ContentProblems";

const COMPLAINT: DeclaredField = {
  fieldId: "f1",
  name: "complaint",
  kind: "text",
  longest: 4000,
  many: false,
  mustBeGiven: true,
};

const SUMMARY: DeclaredField = {
  fieldId: "f2",
  name: "summary",
  kind: "text",
  longest: 1000,
  many: false,
  mustBeGiven: false,
};

const WORKFLOW: ReadContent = {
  kind: "workflow",
  takes: [COMPLAINT],
  gives: [SUMMARY],
  steps: [
    {
      stepId: "s1",
      name: "triage",
      runs: { kind: "question", takes: [COMPLAINT], gives: [] },
      bindings: [
        {
          bindingId: "b1",
          target: "complaint",
          source: { input: "complaint" },
        },
      ],
    },
    {
      stepId: "s2",
      name: "route",
      runs: {
        kind: "route",
        discriminator: { bindingId: "b2", source: { step: 0, path: "x" } },
        gives: [{ ...SUMMARY, fieldId: "f9" }],
        cases: [
          {
            caseId: "c1",
            term: "urgent",
            takes: [COMPLAINT],
            bindings: [
              {
                bindingId: "b3",
                target: "complaint",
                source: { input: "complaint" },
              },
            ],
          },
          { caseId: "c2", bindings: [] },
        ],
      },
      bindings: [],
    },
  ],
  outputs: [
    {
      bindingId: "b4",
      target: "summary",
      source: { step: 0, path: "summary" },
    },
  ],
  terms: [],
};

const BILLING = "0000000c-0000-4000-8000-000000000d11";

const REPEATED = "0000000c-0000-4000-8000-000000000d12";

const LIST: ReadContent = {
  kind: "reference_list",
  takes: [],
  gives: [],
  steps: [],
  outputs: [],
  terms: [
    { termId: BILLING, term: "Billing", meaning: "Money taken wrongly." },
    { termId: REPEATED, term: "BILLING", meaning: "Charged twice." },
  ],
};

const QUESTION: ReadContent = {
  kind: "question",
  takes: [],
  gives: [],
  steps: [],
  outputs: [],
  terms: [],
};

const SAID_REPEATED =
  "A term before it is already this one, whatever the case either is written in.";

function placesOf(
  problems: readonly ContentProblem[],
  content: ReadContent | null,
): (string | null)[] {
  render(<ContentProblems problems={problems} content={content} />);
  return within(screen.getByRole("list", { name: "What does not hold yet" }))
    .getAllByRole("listitem")
    .map((each) => each.textContent);
}

const TRIAGE = isolatedInText("triage");

const ROUTE = isolatedInText("route");

const URGENT = isolatedInText("urgent");

describe("contentOf", () => {
  it("names a question's content by its halves, holding no step and nothing filled", () => {
    expect(
      contentOf({
        revision: 1,
        takes: [COMPLAINT],
        gives: [SUMMARY],
        lists: [],
      }),
    ).toEqual({
      kind: "question",
      takes: [COMPLAINT],
      gives: [SUMMARY],
      steps: [],
      outputs: [],
      terms: [],
    });
  });

  it("names a reference list's content by its terms, holding no half, step or anything filled", () => {
    expect(contentOf({ revision: 3, terms: LIST.terms })).toEqual(LIST);
  });

  it("names a workflow's content by its halves, its steps and what fills what it gives back", () => {
    const workflow = {
      revision: 1,
      takes: WORKFLOW.takes,
      gives: WORKFLOW.gives,
      steps: WORKFLOW.steps,
      outputs: WORKFLOW.outputs,
      keepsOwnCeiling: false,
      raiseNeedsApproval: false,
      mayBeHelped: false,
      problems: [],
      sendsPast: [],
      offered: {
        lists: [],
        questions: [],
        workflows: [],
        codeSteps: [],
        models: [],
      },
      terms: new Map(),
    };

    expect(contentOf(workflow)).toEqual(WORKFLOW);
  });
});

describe("ContentProblems", () => {
  it("names nothing where there is nothing to name", () => {
    const { container } = render(
      <ContentProblems problems={null} content={WORKFLOW} />,
    );

    expect(container).toBeEmptyDOMElement();
  });

  it("says a workflow's step, case and input by what a reader reads them by", () => {
    const places = placesOf(
      [
        { code: "runs_missing", part: "steps", stepId: "s1" },
        { code: "case_repeated", part: "steps", stepId: "s2", caseId: "c1" },
        {
          code: "case_target_missing",
          part: "steps",
          stepId: "s2",
          caseId: "c2",
        },
        { code: "input_unbound", part: "steps", stepId: "s1", fieldId: "f1" },
        {
          code: "input_unbound",
          part: "steps",
          stepId: "s2",
          caseId: "c1",
          fieldId: "f1",
        },
      ],
      WORKFLOW,
    );

    expect(places).toEqual([
      `The steps, ${TRIAGE}: It says nothing yet of what it runs.`,
      `The steps, ${ROUTE}, the case on ${URGENT}: An earlier case is on the same term.`,
      `The steps, ${ROUTE}, the fallback: It leads to no workflow at a fixed version yet.`,
      `The steps, ${TRIAGE}, input ${isolatedInText("complaint")}: Nothing fills it yet.`,
      `The steps, ${ROUTE}, the case on ${URGENT}, input ${isolatedInText("complaint")}: Nothing fills it yet.`,
    ]);
  });

  it.each([
    [
      "read, by its input's name",
      { kind: "code_step", codeStep: "tidy_up", takes: [COMPLAINT], gives: [] },
      `The steps, ${isolatedInText("tidy")}, input ${isolatedInText("complaint")}: Nothing fills it yet.`,
    ],
    [
      "not read, by the step alone",
      { kind: "code_step", codeStep: "tidy_up" },
      `The steps, ${isolatedInText("tidy")}: Nothing fills it yet.`,
    ],
  ] as const)(
    "says an input of a code step whose declaration the page %s",
    (_case, runs, said) => {
      const places = placesOf(
        [
          {
            code: "input_unbound",
            part: "steps",
            stepId: "s3",
            fieldId: "f1",
          },
        ],
        {
          ...WORKFLOW,
          steps: [
            ...WORKFLOW.steps,
            { stepId: "s3", name: "tidy", runs, bindings: [] },
          ],
        },
      );

      expect(places).toEqual([said]);
    },
  );

  it("says a binding by what it fills, or by the route choosing by it", () => {
    const places = placesOf(
      [
        { code: "output_not_from_step", part: "gives", bindingId: "b4" },
        {
          code: "source_unknown",
          part: "steps",
          stepId: "s1",
          bindingId: "b1",
        },
        {
          code: "discriminator_not_term",
          part: "steps",
          stepId: "s2",
          bindingId: "b2",
        },
        {
          code: "source_unknown",
          part: "steps",
          stepId: "s2",
          bindingId: "b3",
        },
      ],
      WORKFLOW,
    );

    expect(places).toEqual([
      `What it gives back, what fills ${isolatedInText("summary")}: What it gives back is filled from a step, and this is filled from elsewhere.`,
      `The steps, ${TRIAGE}, what fills ${isolatedInText("complaint")}: It points at nothing what it reads from declares.`,
      `The steps, what ${ROUTE} chooses by: What it chooses by is not one term of a list.`,
      `The steps, ${ROUTE}, the case on ${URGENT}, what fills ${isolatedInText("complaint")}: It points at nothing what it reads from declares.`,
    ]);
  });

  it("names a workflow's own fields under what it gives back, and a route's by its key among the steps", () => {
    const places = placesOf(
      [
        { code: "output_unbound", part: "gives", fieldId: "f2" },
        { code: "longest_missing", part: "steps", fieldId: "f9" },
      ],
      WORKFLOW,
    );

    expect(places).toEqual([
      `What it gives back, ${isolatedInText("summary")}: Nothing fills it yet.`,
      `The steps, ${isolatedInText("summary")}: A field of text says how long its value may be, and this one does not yet.`,
    ]);
  });

  it("says how far past its bound a constant's text runs where the server says so, and its bound alone where not", () => {
    const places = placesOf(
      [
        {
          code: "constant_too_long",
          part: "gives",
          bindingId: "b4",
          excess: 3,
        },
        { code: "constant_too_long", part: "gives", bindingId: "b4" },
      ],
      WORKFLOW,
    );

    expect(places).toEqual([
      `What it gives back, what fills ${isolatedInText("summary")}: The constant holds 3 characters of text more than the 8192 it may hold.`,
      `What it gives back, what fills ${isolatedInText("summary")}: The constant holds more than 8192 characters of text.`,
    ]);
  });

  it("says a field one value of which could be written out too long by the figure the server holds it to", () => {
    const places = placesOf(
      [{ code: "limit_past_largest", part: "gives", fieldId: "f2" }],
      WORKFLOW,
    );

    expect(places).toEqual([
      `What it gives back, ${isolatedInText("summary")}: At its limits, one value of it could be written out in more than 8,388,608 characters, the names and marks holding it together counted, the longest one value may be.`,
    ]);
  });

  it("says all a workflow takes could be written out too long by the figure the server holds it to", () => {
    const places = placesOf(
      [{ code: "takes_past_largest", part: "takes" }],
      WORKFLOW,
    );

    expect(places).toEqual([
      "What it takes: All it takes, together at their limits, could be written out in more than 8,388,608 characters, the longest one value may be, and a run keeps what it was started with as one.",
    ]);
  });

  it("says a step whose review could send past the most one asking may as its review, counted or not", () => {
    const places = placesOf(
      [
        {
          code: "asking_past_largest",
          part: "steps",
          stepId: "s1",
          excess: 12,
        },
        {
          code: "asking_past_largest",
          part: "steps",
          stepId: "s1",
          excess: 2 ** 63,
        },
      ],
      WORKFLOW,
    );

    expect(places).toEqual([
      `The steps, ${TRIAGE}: A model reviewing what it produces could be sent 12 characters more than 8,388,608, the most one asking may send.`,
      `The steps, ${TRIAGE}: A model reviewing what it produces could be sent more than 9,007,199,254,740,991 characters past 8,388,608, the most one asking may send.`,
    ]);
  });

  /**
   * The figure is the server's alone: the page holds neither the envelope a review is sent in nor what a released
   * code step gives, so it says by how much as it was told, one past the most as one, and never works it out.
   */
  it("says a step whose code step's review could send past the most one asking may by as much as the server says, counted or not", () => {
    const places = placesOf(
      [
        {
          code: "code_step_review_past_largest",
          part: "steps",
          stepId: "s1",
          excess: 1,
        },
        {
          code: "code_step_review_past_largest",
          part: "steps",
          stepId: "s1",
          excess: Number.MAX_SAFE_INTEGER,
        },
        {
          code: "code_step_review_past_largest",
          part: "steps",
          stepId: "s1",
          excess: 2 ** 63,
        },
        { code: "code_step_review_past_largest", part: "steps", stepId: "s1" },
      ],
      WORKFLOW,
    );

    expect(places).toEqual([
      `The steps, ${TRIAGE}: A model reviewing what its code step gives back could be sent 1 character more than 8,388,608, the most one asking may send.`,
      `The steps, ${TRIAGE}: A model reviewing what its code step gives back could be sent 9,007,199,254,740,991 characters more than 8,388,608, the most one asking may send.`,
      `The steps, ${TRIAGE}: A model reviewing what its code step gives back could be sent more than 9,007,199,254,740,991 characters past 8,388,608, the most one asking may send.`,
      `The steps, ${TRIAGE}: Something here does not hold.`,
    ]);
    expect(places.join(" ")).not.toContain("what it produces");
  });

  it("says what fills what must be given from what need not be, where the server says so", () => {
    const places = placesOf(
      [
        {
          code: "source_may_be_empty",
          part: "steps",
          stepId: "s1",
          bindingId: "b1",
        },
      ],
      WORKFLOW,
    );

    expect(places).toEqual([
      `The steps, ${TRIAGE}, what fills ${isolatedInText("complaint")}: What it fills must be given, and what it reads need not be, so that may be empty.`,
    ]);
  });

  it("still lists a place whose step, case or binding the page did not read, said by its part", () => {
    const places = placesOf(
      [
        { code: "runs_missing", part: "steps", stepId: "gone" },
        { code: "source_unknown", part: "steps", bindingId: "gone" },
      ],
      WORKFLOW,
    );

    expect(places).toEqual([
      "The steps: It says nothing yet of what it runs.",
      "The steps: It points at nothing what it reads from declares.",
    ]);
  });

  it("names a term a problem is at by its place among the terms read and its words, set apart", () => {
    expect(
      placesOf(
        [
          { code: "term_repeated", part: "terms", termId: REPEATED },
          { code: "no_terms", part: "terms" },
        ],
        LIST,
      ),
    ).toEqual([
      `The terms, term 2, ${isolatedInText("BILLING")}: ${SAID_REPEATED}`,
      "The terms: It holds no term yet, so nothing could answer with one.",
    ]);
  });

  it.each([
    [
      "a term the page has not read",
      LIST,
      "0000000c-0000-4000-8000-000000000d19",
    ],
    ["terms the page has not read at all", null, REPEATED],
    ["content holding no terms", QUESTION, REPEATED],
  ])(
    "names only the part where the problem is at %s, still saying what is wrong",
    (_case, content, termId) => {
      expect(
        placesOf([{ code: "term_repeated", part: "terms", termId }], content),
      ).toEqual([`The terms: ${SAID_REPEATED}`]);
    },
  );

  it("looks a term's key up only where the part is the terms", () => {
    expect(
      placesOf(
        [{ code: "term_repeated", part: "takes", termId: REPEATED }],
        LIST,
      ),
    ).toEqual([`What it takes: ${SAID_REPEATED}`]);
  });
});
