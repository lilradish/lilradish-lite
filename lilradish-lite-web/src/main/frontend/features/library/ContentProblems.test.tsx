import { ThemeProvider } from "@mui/material/styles";
import { render, screen, within } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import type { DeclaredField } from "../../api/declaration";
import type { ContentProblem } from "../../api/groups/{groupId}/{kind}/{entryId}/versions";
import { isolatedInText } from "../../lib/direction/isolated";
import { theme } from "../../lib/theme/theme";
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

describe("content problem totals", () => {
  it("names one omitted problem after the visible places", () => {
    render(
      <ContentProblems
        problems={[{ code: "no_steps", part: "steps" }]}
        problemTotal={2}
        content={WORKFLOW}
      />,
    );

    expect(
      screen.getByText("1 more thing does not hold and is not listed."),
    ).toBeVisible();
    expect(screen.getAllByRole("listitem")).toHaveLength(1);
  });

  it("names several omitted problems, and says nothing extra when every one is shown", () => {
    const { rerender } = render(
      <ContentProblems
        problems={[{ code: "no_steps", part: "steps" }]}
        problemTotal={203}
        content={WORKFLOW}
      />,
    );

    expect(
      screen.getByText("202 more things do not hold and are not listed."),
    ).toBeVisible();
    rerender(
      <ContentProblems
        problems={[{ code: "no_steps", part: "steps" }]}
        problemTotal={1}
        content={WORKFLOW}
      />,
    );
    expect(
      screen.queryByText(/more things? do not hold/),
    ).not.toBeInTheDocument();
  });
});

const SAID_REPEATED =
  "A term before it is already this one, whatever the case either is written in.";

function placesOf(
  problems: readonly ContentProblem[],
  content: ReadContent | null,
): (string | null)[] {
  render(
    <ThemeProvider theme={theme}>
      <ContentProblems problems={problems} content={content} />
    </ThemeProvider>,
  );
  return within(screen.getByRole("list", { name: "What does not hold yet" }))
    .getAllByRole("listitem")
    .map((each) => {
      const words = each.getAttribute("aria-label");
      expect(each.textContent).toBe(
        words?.replaceAll("\u2068", "").replaceAll("\u2069", ""),
      );
      return words;
    });
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
        searchWays: [],
        models: [],
      },
      terms: new Map(),
    };

    expect(contentOf(workflow)).toEqual(WORKFLOW);
  });
});

describe("ContentProblems", () => {
  it("keeps data names separate from inherited warning notes through binding and case locators", () => {
    render(
      <ThemeProvider theme={theme}>
        <div style={{ color: theme.palette.warning.dark }}>
          <ContentProblems
            problems={[
              {
                code: "source_unknown",
                part: "steps",
                stepId: "s2",
                bindingId: "b3",
              },
              {
                code: "target_unknown",
                part: "steps",
                stepId: "s2",
                caseId: "c2",
              },
              { code: "name_repeated", part: "gives", fieldId: "f2" },
            ]}
            content={WORKFLOW}
          />
        </div>
      </ThemeProvider>,
    );
    const items = screen.getAllByRole("listitem");
    expect(items).toHaveLength(3);
    for (const item of items) {
      expect(item.textContent).toBe(
        item
          .getAttribute("aria-label")
          ?.replaceAll("\u2068", "")
          .replaceAll("\u2069", ""),
      );
      for (const data of item.querySelectorAll("bdi")) {
        expect(getComputedStyle(data).fontStyle).not.toBe("italic");
      }
      for (const hint of item.querySelectorAll("span")) {
        expect(getComputedStyle(hint).fontStyle).toBe("italic");
        expect(getComputedStyle(hint).color).toBe(getComputedStyle(item).color);
      }
    }
    expect(within(items[0]!).getByText("route").tagName).toBe("BDI");
    expect(within(items[0]!).getByText("urgent").tagName).toBe("BDI");
    expect(within(items[0]!).getByText("complaint").tagName).toBe("BDI");
    expect(within(items[1]!).getByText("the fallback")).toBeVisible();
    expect(within(items[2]!).getByText("summary").tagName).toBe("BDI");
  });

  it("isolates names inside drawing and search problems without rendering their markup", () => {
    const name = "Demo Sample <img src=x>";
    placesOf(
      [
        { code: "drawing_names_unknown", part: "drawing", name },
        {
          code: "search_standing_missing",
          part: "steps",
          stepId: "s1",
          fields: ["Demo_Sample", "body", name],
        },
        {
          code: "search_standing_missing",
          part: "steps",
          stepId: "s1",
          fields: [],
        },
        { code: "drawing_names_unknown", part: "drawing" },
      ],
      WORKFLOW,
    );
    const items = screen.getAllByRole("listitem");
    expect(within(items[0]!).getByText(name).tagName).toBe("BDI");
    expect(within(items[1]!).getByText("Demo Sample").tagName).toBe("BDI");
    expect(within(items[1]!).getByText("body").tagName).toBe("BDI");
    expect(within(items[1]!).getByText(name).tagName).toBe("BDI");
    expect(screen.queryByRole("img")).toBeNull();
    expect(items[3]!).toHaveAccessibleName(
      "The question, how it is drawn: Something here does not hold.",
    );
  });

  it("keeps a repeated field's position as a note beside its data name", () => {
    placesOf([{ code: "name_repeated", part: "takes", fieldId: "f2" }], {
      ...QUESTION,
      takes: [COMPLAINT, { ...COMPLAINT, fieldId: "f2" }],
    });
    const item = screen.getByRole("listitem");
    expect(item).toHaveAccessibleName(
      `What it takes, ${isolatedInText("complaint (field 2 there)")}: A field beside it already has this name.`,
    );
    const name = within(item).getByText("complaint");
    expect(name.tagName).toBe("BDI");
    expect(getComputedStyle(name).fontStyle).not.toBe("italic");
    expect(
      getComputedStyle(within(item).getByText("(field 2 there)")).fontStyle,
    ).toBe("italic");
  });
  it("places a searching read problem at its part key, not merely its step", () => {
    const content: ReadContent = {
      ...WORKFLOW,
      steps: [
        ...WORKFLOW.steps,
        {
          stepId: "s4",
          name: "find",
          bindings: [],
          runs: {
            kind: "search",
            searchWay: "web",
            question: [
              { partId: "p1", from: { input: "complaint" } },
              { partId: "p2", from: { step: 0, path: "summary" } },
            ],
            standings: {},
          },
        },
      ],
    };
    const atPart = placesOf(
      [{ code: "source_unknown", part: "steps", bindingId: "p2" }],
      content,
    )[0]!;

    expect(atPart).toContain("Part 2");
    expect(atPart).toContain("find");
    expect(atPart).not.toContain("Part 1");
    expect(atPart).not.toContain("triage");
  });

  it("uses separate counted and uncounted words for a search's review and the asking it makes", () => {
    const content: ReadContent = {
      ...WORKFLOW,
      steps: [
        {
          stepId: "s4",
          name: "find",
          bindings: [],
          runs: { kind: "search", question: [], standings: {} },
        },
      ],
    };
    const places = placesOf(
      [
        { code: "asking_past_largest", part: "steps", stepId: "s4", excess: 2 },
        {
          code: "search_review_past_largest",
          part: "steps",
          stepId: "s4",
          excess: 3,
        },
        {
          code: "search_review_past_largest",
          part: "steps",
          stepId: "s4",
          excess: Number.MAX_SAFE_INTEGER + 1,
        },
      ],
      content,
    );

    expect(places[0]).toContain("one asking");
    expect(places[0]).not.toContain("reviewing what it finds");
    expect(places[1]).toContain("reviewing what it finds");
    expect(places[1]).toContain("3 characters");
    expect(places[2]).toContain("more than");
    expect(places[2]).not.toContain("9007199254740992");
  });

  it("names an unheld drawing reference at the question drawing", () => {
    render(
      <ContentProblems
        problems={[
          { code: "drawing_names_unknown", part: "drawing", name: "missing" },
        ]}
        content={null}
      />,
    );
    expect(screen.getByRole("listitem")).toHaveAccessibleName(
      `The question, how it is drawn: It names ${isolatedInText("missing")}, which this question does not take.`,
    );
    expect(screen.getByRole("listitem").textContent).toBe(
      "The question, how it is drawn: It names missing, which this question does not take.",
    );
  });

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

  it("names a case output by its labels, preferring the target before the route, and a missing required output by the route", () => {
    const caseOutput: DeclaredField = {
      ...SUMMARY,
      fieldId: "shared",
      name: "case_details",
      label: "Case details",
      kind: "fields",
      fields: [
        { ...SUMMARY, fieldId: "inner", name: "case_note", label: "Case note" },
      ],
    };
    const routeOutput: DeclaredField = {
      ...caseOutput,
      label: "Route details",
      fields: [{ ...SUMMARY, fieldId: "inner", label: "Route note" }],
    };
    const routeStep = WORKFLOW.steps[1]!;
    const routeRuns = routeStep.runs!;
    const content: ReadContent = {
      ...WORKFLOW,
      steps: [
        WORKFLOW.steps[0]!,
        {
          ...routeStep,
          runs: {
            ...routeRuns,
            gives: [
              routeOutput,
              { ...SUMMARY, fieldId: "required", label: "Required answer" },
            ],
            cases: [
              {
                caseId: "c1",
                term: "urgent",
                gives: [caseOutput],
                bindings: [],
              },
              { caseId: "c2", gives: [], bindings: [] },
            ],
          },
        },
      ],
    };

    const places = placesOf(
      [
        {
          code: "case_gives_past_limit",
          part: "steps",
          stepId: "s2",
          caseId: "c1",
          fieldId: "inner",
        },
        {
          code: "case_may_give_nothing",
          part: "steps",
          stepId: "s2",
          caseId: "c2",
          fieldId: "required",
        },
        {
          code: "case_gives_other_kind",
          part: "steps",
          stepId: "s2",
          caseId: "c1",
          fieldId: "f1",
        },
      ],
      content,
    );

    expect(places).toEqual([
      `The steps, ${ROUTE}, the case on ${URGENT}, what it gives back as ${isolatedInText("Case details")} › ${isolatedInText("Case note")}: It could be longer, or hold more, than the route allows.`,
      `The steps, ${ROUTE}, the fallback, what it gives back as ${isolatedInText("Required answer")}: The route says it must be given, and the workflow it leads to need not give it.`,
      `The steps, ${ROUTE}, the case on ${URGENT}: The route declares it as another kind.`,
    ]);
    expect(places.join(" ")).not.toContain("input complaint");
  });

  it("words all four case output refusal reasons at the target's output field", () => {
    const routeStep = WORKFLOW.steps[1]!;
    const content: ReadContent = {
      ...WORKFLOW,
      steps: [
        WORKFLOW.steps[0]!,
        {
          ...routeStep,
          runs: {
            ...routeStep.runs!,
            cases: [
              {
                caseId: "c1",
                term: "urgent",
                gives: [{ ...SUMMARY, fieldId: "f9", label: "Case answer" }],
                bindings: [],
              },
              { caseId: "c2", bindings: [] },
            ],
          },
        },
      ],
    };
    const problems: ContentProblem[] = [
      {
        code: "case_gives_undeclared",
        part: "steps",
        stepId: "s2",
        caseId: "c1",
        fieldId: "f9",
      },
      {
        code: "case_gives_other_kind",
        part: "steps",
        stepId: "s2",
        caseId: "c1",
        fieldId: "f9",
      },
      {
        code: "case_gives_past_limit",
        part: "steps",
        stepId: "s2",
        caseId: "c1",
        fieldId: "f9",
      },
      {
        code: "case_may_give_nothing",
        part: "steps",
        stepId: "s2",
        caseId: "c1",
        fieldId: "f9",
      },
    ];

    expect(placesOf(problems, content)).toEqual([
      `The steps, ${ROUTE}, the case on ${URGENT}, what it gives back as ${isolatedInText("Case answer")}: The route declares nothing by this name.`,
      `The steps, ${ROUTE}, the case on ${URGENT}, what it gives back as ${isolatedInText("Case answer")}: The route declares it as another kind.`,
      `The steps, ${ROUTE}, the case on ${URGENT}, what it gives back as ${isolatedInText("Case answer")}: It could be longer, or hold more, than the route allows.`,
      `The steps, ${ROUTE}, the case on ${URGENT}, what it gives back as ${isolatedInText("Case answer")}: The route says it must be given, and the workflow it leads to need not give it.`,
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

  it("says a missing file size and both deployment limits at the named field", () => {
    const places = placesOf(
      [
        { code: "file_size_missing", part: "gives", fieldId: "f2" },
        {
          code: "file_limit_past_deployment",
          part: "gives",
          fieldId: "f2",
          fileLimits: {
            maximumBytes: 104857600,
            maximumPerField: 100,
            maximumTotalBytes: 1073741824,
          },
        },
      ],
      WORKFLOW,
    );

    expect(places).toEqual([
      `What it gives back, ${isolatedInText("summary")}: A field of files says how large one file may be, and this one does not yet.`,
      `What it gives back, ${isolatedInText("summary")}: It allows a larger file, or more files, than this deployment takes: files of up to 100 MB each, and at most 100 in one field.`,
    ]);
  });

  it("uses the file-type refusal for a stored field with too many types", () => {
    expect(
      placesOf(
        [{ code: "file_types_too_many", part: "gives", fieldId: "f2" }],
        WORKFLOW,
      ),
    ).toEqual([
      `What it gives back, ${isolatedInText("summary")}: A field of files takes at most 64 types.`,
    ]);
  });

  it("names the total file bound at what a workflow takes", () => {
    const limits = {
      maximumBytes: 104857600,
      maximumPerField: 100,
      maximumTotalBytes: 1073741824,
    };
    expect(
      placesOf(
        [
          {
            code: "file_total_past_deployment",
            part: "takes",
            fileLimits: limits,
          },
        ],
        WORKFLOW,
      ),
    ).toEqual([
      "What it takes: At their limits, its files together could come to more than the 1 GB this deployment takes at once.",
    ]);
  });

  it("names the total file bound at what a question gives back", () => {
    const limits = {
      maximumBytes: 104857600,
      maximumPerField: 100,
      maximumTotalBytes: 1073741824,
    };
    expect(
      placesOf(
        [
          {
            code: "file_total_past_deployment",
            part: "gives",
            fileLimits: limits,
          },
        ],
        QUESTION,
      ),
    ).toEqual([
      "The declared answer: At their limits, its files together could come to more than the 1 GB this deployment takes at once.",
    ]);
  });

  it("names a file taken by a question and file output assigned to model work", () => {
    const places = placesOf(
      [
        { code: "question_takes_file", part: "takes", fieldId: "f1" },
        { code: "step_file_made_by_model", part: "steps", stepId: "s1" },
        { code: "step_file_reviewed_by_model", part: "steps", stepId: "s1" },
      ],
      WORKFLOW,
    );

    expect(places).toEqual([
      `What it takes, ${isolatedInText("complaint")}: It is a file, and a question takes none: a model is sent text alone.`,
      `The steps, ${TRIAGE}: It gives back a file, which no model can make; a person may answer it.`,
      `The steps, ${TRIAGE}: It takes or gives back a file, which no model can review; a person may review it, by downloading it.`,
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

  it("says a searching step's problems, its fields none is chosen for named as people read them, in the server's order", () => {
    const places = placesOf(
      [
        { code: "search_tool_not_offered", part: "steps", stepId: "s1" },
        { code: "search_way_not_held", part: "steps", stepId: "s1" },
        { code: "search_question_may_be_empty", part: "steps", stepId: "s1" },
        {
          code: "search_standing_missing",
          part: "steps",
          stepId: "s1",
          fields: ["other"],
        },
        {
          code: "search_standing_missing",
          part: "steps",
          stepId: "s1",
          fields: ["references", "other"],
        },
        {
          code: "search_standing_missing",
          part: "steps",
          stepId: "s1",
          fields: ["seen_at", "references", "other"],
        },
        { code: "search_standing_missing", part: "steps", stepId: "s1" },
      ],
      WORKFLOW,
    );

    expect(places).toEqual([
      `The steps, ${TRIAGE}: It searches with a model that does not offer the tool this way to search needs.`,
      `The steps, ${TRIAGE}: It runs a way to search this release does not offer.`,
      `The steps, ${TRIAGE}: It searches with a question that could hold nothing: write something into it, or put in a value that must be given.`,
      `The steps, ${TRIAGE}: It does not yet say whether ${isolatedInText("other")} stands always or waits on a review.`,
      `The steps, ${TRIAGE}: It does not yet say whether ${isolatedInText("references")} and ${isolatedInText("other")} stand always or wait on a review.`,
      `The steps, ${TRIAGE}: It does not yet say whether ${isolatedInText("seen at")}, ${isolatedInText("references")}, and ${isolatedInText("other")} stand always or wait on a review.`,
      `The steps, ${TRIAGE}: Something here does not hold.`,
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
