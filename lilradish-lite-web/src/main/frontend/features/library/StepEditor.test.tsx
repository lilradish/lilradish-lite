import { ThemeProvider } from "@mui/material/styles";
import {
  act,
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import {
  PointerEventsCheckLevel,
  userEvent,
} from "@testing-library/user-event";
import { useState, type ReactNode } from "react";
import { beforeEach, describe, expect, it, onTestFinished, vi } from "vitest";

import type { DeclaredField, PinnedList } from "../../api/declaration";
import type { VersionDetail } from "../../api/groups/{groupId}/versions/{versionId}";
import { serving } from "../../testutil/answering";
import type {
  OfferedCodeStep,
  Step,
  WorkflowVersion,
} from "../../api/groups/{groupId}/{kind}/{entryId}/versions/{versionId}";
import { isolatedInText } from "../../lib/direction/isolated";
import { theme } from "../../lib/theme/theme";
import { drawnAtDepth, drawnOf } from "../../testutil/depths";

import { styledClassOf, writtenRules } from "../../testutil/writtenRules";
import { StepEditor } from "./StepEditor";
import { libraryLaidOutAt } from "./libraryLayout.testHelper";

beforeEach(() => {
  libraryLaidOutAt(1600, 1600);
});
vi.mock("../../app/standing/RequireGroup", async (importOriginal) => ({
  ...(await importOriginal<typeof import("../../app/standing/RequireGroup")>()),
  useGroup: () => ({ groupId: "00000003-0000-4000-8000-000000000a71" }),
}));
import * as workflowDrafts from "./workflowDrafts";
import {
  flowOf,
  namedVersions,
  type FlowDraft,
  type RunsDraft,
} from "./workflowDrafts";
vi.mock("../../app/standing/StandingContext", () => {
  const value = { acts: new Set(), groups: [], userId: "reader" };
  return {
    useStandingRead: () => ({
      value,
      loading: false,
      problem: null,
      reload: vi.fn(),
    }),
  };
});

interface AvailableVersion {
  readonly versionId: string;
  readonly versionName: string | null;
  readonly name: string;
  readonly number: number;
  readonly takes: readonly DeclaredField[];
  readonly gives: readonly DeclaredField[];
  readonly searchWay?: string;
  readonly searchMatches?: boolean;
  readonly standing?: string;
}
interface FixtureWorkflow extends WorkflowVersion {
  readonly available: {
    readonly lists: readonly never[];
    readonly questions: readonly AvailableVersion[];
    readonly workflows: readonly AvailableVersion[];
  };
}

const COMPLAINT: DeclaredField = {
  fieldId: "f1",
  name: "complaint",
  kind: "text",
  longest: 4000,
  many: false,
  mustBeGiven: true,
};

const URGENCY: DeclaredField = {
  fieldId: "f3",
  name: "urgency",
  kind: "text",
  longest: 10,
  many: false,
  mustBeGiven: true,
};

const NOTE: DeclaredField = {
  fieldId: "f4",
  name: "note",
  kind: "text",
  longest: 100,
  many: false,
  mustBeGiven: false,
};

const COUNT: DeclaredField = {
  fieldId: "f5",
  name: "count",
  kind: "number",
  many: false,
  mustBeGiven: true,
};

const SUMMARY: DeclaredField = {
  fieldId: "f2",
  name: "summary",
  kind: "text",
  longest: 1000,
  many: false,
  mustBeGiven: true,
};

const ADDRESS: DeclaredField = {
  fieldId: "f6",
  name: "address",
  kind: "fields",
  many: false,
  mustBeGiven: true,
  fields: [
    { ...SUMMARY, fieldId: "f7", name: "street" },
    { ...SUMMARY, fieldId: "f8", name: "city" },
  ],
};

const TIDIED: DeclaredField = {
  fieldId: "k1",
  name: "text",
  kind: "text",
  longest: 100,
  many: false,
  mustBeGiven: true,
};

const TIDYING: OfferedCodeStep = {
  name: "tidy_up",
  takes: [TIDIED],
  gives: [SUMMARY],
};

const TRIAGE: PinnedList = {
  versionName: null,
  name: "Triage",
  versionId: "q1",
  number: 4,
  standing: "in_service",
  newer: { versionName: null, versionId: "q5", number: 5 },
};

const ASKING: Step = {
  stepId: "s1",
  name: "triage",
  runs: {
    kind: "question",
    version: TRIAGE,
    takes: [COMPLAINT, URGENCY],
    gives: [SUMMARY],
  },
  producer: { kind: "person" },
  tries: 2,
  reviewer: { model: "general", mode: "research" },
  bindings: [
    { bindingId: "b1", target: "complaint", source: { input: "complaint" } },
    { bindingId: "b2", target: "urgency", source: { input: "complaint" } },
  ],
};

const WORKFLOW: FixtureWorkflow = {
  revision: 1,
  takes: [COMPLAINT, COUNT, NOTE],
  gives: [],
  steps: [
    ASKING,
    {
      stepId: "s2",
      name: "route",
      runs: { kind: "route", gives: [], cases: [] },
      bindings: [],
    },
    {
      stepId: "s3",
      name: "tidy",
      runs: { kind: "code_step", codeStep: "tidy_up" },
      producer: { kind: "code" },
      bindings: [],
    },
  ],
  outputs: [],
  keepsOwnCeiling: false,
  raiseNeedsApproval: false,
  mayBeHelped: false,
  sendsPast: [],
  problems: [
    { code: "tries_missing", part: "steps", stepId: "s1" },
    { code: "input_unbound", part: "steps", stepId: "s1", fieldId: "f1" },
    { code: "source_unknown", part: "steps", stepId: "s1", bindingId: "b1" },
    { code: "discriminator_missing", part: "steps", stepId: "s2" },
  ],
  available: {
    ...{ lists: [], questions: [], workflows: [] },
    lists: [],
    questions: [
      {
        versionName: null,
        name: "Triage",
        versionId: "q5",
        number: 5,
        searchMatches: false,
        takes: [COMPLAINT],
        gives: [SUMMARY],
      },
      {
        versionName: null,
        name: "Addressing",
        versionId: "q7",
        number: 1,
        searchMatches: false,
        takes: [ADDRESS],
        gives: [],
      },
    ],
    workflows: [],
  },
  offered: {
    codeSteps: [TIDYING],
    searchWays: [],
    models: [
      { name: "general", modes: ["research", "deep_research"], tools: [] },
      { name: "small", modes: [], tools: [] },
    ],
  },
  terms: new Map(),
};

function searchingWorkflow(): FixtureWorkflow {
  const search: Step = {
    stepId: "s4",
    name: "find",
    bindings: [
      { bindingId: "p1", target: "complaint", source: { input: "complaint" } },
      {
        bindingId: "p2",
        target: "summary",
        source: { step: 0, path: "summary" },
      },
    ],
    runs: {
      kind: "search",
      version: {
        versionName: null,
        name: "Find evidence",
        versionId: "q-search",
        number: 1,
        standing: "in_service",
      },
      takes: [COMPLAINT, SUMMARY],
      gives: [{ ...SUMMARY, name: "title" }],
      searchWay: "web",
      standings: { title: "always", body: "never" },
    },
    producer: { kind: "model", model: "general", toldWhatHappened: true },
    tries: 2,
  };
  return {
    ...WORKFLOW,
    steps: [...WORKFLOW.steps, search],
    available: {
      ...WORKFLOW.available,
      questions: [
        ...WORKFLOW.available.questions,
        {
          versionName: null,
          name: "Find evidence",
          versionId: "q-search",
          number: 1,
          searchWay: "web",
          searchMatches: true,
          takes: [COMPLAINT, SUMMARY],
          gives: [{ ...SUMMARY, name: "title" }],
        },
        {
          versionName: null,
          name: "Find evidence",
          versionId: "q-search-next",
          number: 2,
          searchWay: "web",
          searchMatches: true,
          takes: [COMPLAINT, SUMMARY],
          gives: [{ ...SUMMARY, name: "title" }],
        },
        {
          versionName: null,
          name: "Changed shape",
          versionId: "q-shape",
          number: 1,
          searchWay: "web",
          searchMatches: false,
          takes: [],
          gives: [],
        },
        {
          versionName: null,
          name: "Another way",
          versionId: "q-other",
          number: 1,
          searchWay: "other",
          searchMatches: true,
          takes: [],
          gives: [],
        },
      ],
    },
    offered: {
      ...WORKFLOW.offered,
      searchWays: [
        {
          name: "web",
          tool: "web_search",
          gives: [{ ...SUMMARY, name: "title" }],
        },
      ],
      models: [
        {
          name: "general",
          modes: ["research", "deep_research"],
          tools: ["web_search"],
        },
        { name: "small", modes: [], tools: [] },
      ],
    },
  };
}

function themed({ children }: { readonly children: ReactNode }) {
  return <ThemeProvider theme={theme}>{children}</ThemeProvider>;
}

async function editing(
  position: number,
  editable: boolean,
  workflow: FixtureWorkflow = WORKFLOW,
) {
  const available = [
    ...workflow.available.questions,
    ...workflow.available.workflows,
  ];
  const chosen = workflow.steps[position]?.runs;
  const way = chosen?.kind === "search" ? chosen.searchWay : undefined;
  serving({
    "GET /api/groups/00000003-0000-4000-8000-000000000a71/questions/versions": [
      [
        JSON.stringify({
          items: workflow.available.questions
            .filter((version) => way === undefined || version.searchWay === way)
            .map(({ takes: _takes, gives: _gives, ...version }) => ({
              ...version,
              searchMatches: version.searchMatches ?? false,
            })),
          more: false,
        }),
        200,
      ],
    ],
    "GET /api/groups/00000003-0000-4000-8000-000000000a71/workflows/versions": [
      [
        JSON.stringify({
          items: workflow.available.workflows.map(
            ({ takes: _takes, gives: _gives, ...version }) => version,
          ),
          more: false,
        }),
        200,
      ],
    ],
    ...Object.fromEntries(
      available.map(({ takes, gives, ...version }) => [
        `GET /api/groups/00000003-0000-4000-8000-000000000a71/versions/${version.versionId}`,
        [
          [
            JSON.stringify({
              kind: workflow.available.questions.some(
                (q) => q.versionId === version.versionId,
              )
                ? "question"
                : "workflow",
              version: {
                ...version,
                standing: version.standing ?? "in_service",
              },
              takes,
              gives,
              terms: Object.fromEntries(workflow.terms),
              ...(workflow.available.questions.some(
                (q) => q.versionId === version.versionId,
              )
                ? {
                    searchMatches: version.searchMatches ?? false,
                    ...(version.searchWay === undefined
                      ? {}
                      : { searchWay: version.searchWay }),
                  }
                : {}),
            }),
            200,
          ],
        ],
      ]),
    ),
  });
  const held: { flow: FlowDraft } = { flow: flowOf(workflow) };
  function Host() {
    const [flow, setFlow] = useState(held.flow);
    const [details, setDetails] = useState(new Map<string, VersionDetail>());
    const named = namedVersions(workflow);
    for (const detail of details.values()) {
      if (detail.kind !== "reference_list")
        named.set(detail.version.versionId, {
          ...detail.version,
          takes: detail.takes,
          gives: detail.gives,
          pinned: detail.version,
        });
    }
    return (
      <StepEditor
        step={flow.steps[position]!}
        position={position}
        flow={flow}
        workflow={workflow}
        named={named}
        details={details}
        scope="spec:step"
        resolve={(detail, changed) => {
          const nextNames = new Map(named);
          if (detail !== null && detail.kind !== "reference_list") {
            nextNames.set(detail.version.versionId, {
              ...detail.version,
              takes: detail.takes,
              gives: detail.gives,
              pinned: detail.version,
            });
            setDetails((now) =>
              new Map(now).set(detail.version.versionId, detail),
            );
          }
          setFlow((now) => {
            const next = {
              ...now,
              steps: now.steps.map((step, index) =>
                index === position ? changed(step, nextNames) : step,
              ),
            };
            held.flow = next;
            return next;
          });
        }}
        editable={editable}
        change={(changed) => {
          const next = {
            ...flow,
            steps: flow.steps.map((each, index) =>
              index === position ? changed(each) : each,
            ),
          };
          held.flow = next;
          setFlow(next);
        }}
      />
    );
  }
  await act(async () => {
    render(<Host />, { wrapper: themed });
  });
  return held;
}

async function choose(control: string, option: string): Promise<void> {
  const input = screen.getByRole("combobox", { name: control });
  await userEvent.click(input);
  if (control === "Version")
    fireEvent.change(input, { target: { value: "a" } });
  await userEvent.click(await screen.findByRole("option", { name: option }));
}

async function chooseIn(
  scope: HTMLElement,
  control: string,
  option: string,
): Promise<void> {
  await userEvent.click(
    within(scope).getByRole("combobox", {
      name:
        control === "From" || control === "Which"
          ? new RegExp(`^${control}, `)
          : control,
    }),
  );
  await userEvent.click(screen.getByRole("option", { name: option }));
}

/** A version label as drawn, its entry and name each in an element of their own. */
function versionShownAs(words: string): HTMLElement {
  return screen.getByText(
    (_content, element) =>
      element?.tagName === "SPAN" && element.textContent === words,
  );
}

function paragraphReading(words: string): HTMLElement {
  return screen.getByText(
    (_content, element) =>
      element?.tagName === "P" && element.textContent === words,
  );
}

function placeOf(control: HTMLElement): HTMLElement {
  return control.closest<HTMLElement>(".MuiFormControl-root")!;
}

function head(): HTMLElement {
  return screen.getByText("What does not hold here yet").parentElement!;
}

function groupNames(): string[] {
  return screen
    .getAllByRole("rowgroup")
    .filter((group) => group.hasAttribute("aria-label"))
    .map(
      (group) =>
        group.getAttribute("aria-label") ??
        document.getElementById(group.getAttribute("aria-labelledby")!)!
          .textContent ??
        "",
    );
}

function unchangedFlow(): FlowDraft {
  return {
    steps: [
      {
        key: "s1",
        stepId: "s1",
        name: "triage",
        label: "",
        runs: { kind: "question", version: "q1" },
        producer: "person",
        model: "",
        mode: "",
        toldWhatHappened: false,
        tries: "2",
        reviewer: "model",
        reviewerModel: "general",
        reviewerMode: "research",
        bindings: [
          {
            bindingId: "b1",
            target: "complaint",
            source: { from: "input", path: "complaint" },
          },
          {
            bindingId: "b2",
            target: "urgency",
            source: { from: "input", path: "complaint" },
          },
        ],
      },
      {
        key: "s2",
        stepId: "s2",
        name: "route",
        label: "",
        runs: {
          kind: "route",
          discriminator: { from: "none" },
          gives: [],
          cases: [],
        },
        producer: "",
        model: "",
        mode: "",
        toldWhatHappened: false,
        tries: "",
        reviewer: "person",
        reviewerModel: "",
        reviewerMode: "",
        bindings: [],
      },
      {
        key: "s3",
        stepId: "s3",
        name: "tidy",
        label: "",
        runs: { kind: "code_step", codeStep: "tidy_up" },
        producer: "code",
        model: "",
        mode: "",
        toldWhatHappened: false,
        tries: "",
        reviewer: "person",
        reviewerModel: "",
        reviewerMode: "",
        bindings: [],
      },
    ],
    outputs: [],
  };
}

function unchangedSearchFlow(): FlowDraft {
  return {
    ...unchangedFlow(),
    steps: [
      ...unchangedFlow().steps,
      {
        key: "s4",
        stepId: "s4",
        name: "find",
        label: "",
        runs: {
          kind: "search",
          version: "q-search",
          searchWay: "web",
          standings: {
            title: "always",
            body: "never",
            references: "",
            other: "",
          },
        },
        producer: "model",
        model: "general",
        mode: "",
        toldWhatHappened: true,
        tries: "2",
        reviewer: "person",
        reviewerModel: "",
        reviewerMode: "",
        bindings: [
          {
            bindingId: "p1",
            target: "complaint",
            source: { from: "input", path: "complaint" },
          },
          {
            bindingId: "p2",
            target: "summary",
            source: { from: "step", stepKey: "s1", path: "summary" },
          },
        ],
      },
    ],
  };
}

describe("StepEditor parent changes", () => {
  it("shares source paths through the real parent when one of a hundred bindings changes", async () => {
    const fields: DeclaredField[] = Array.from({ length: 100 }, (_, index) => ({
      ...SUMMARY,
      fieldId: `field-${index}`,
      name: `field_${index}`,
    }));
    const workflow: FixtureWorkflow = {
      ...WORKFLOW,
      takes: fields,
      steps: [
        {
          stepId: "reader",
          name: "reader",
          runs: { kind: "code_step", codeStep: "read_fields" },
          producer: { kind: "code" },
          bindings: fields.map((field, index) => ({
            bindingId: `binding-${index}`,
            target: field.name,
            source: { input: "field_0" },
          })),
        },
      ],
      offered: {
        ...WORKFLOW.offered,
        codeSteps: [{ name: "read_fields", takes: fields, gives: [] }],
      },
      problems: [],
    };
    let traversed = 0;
    const batch = workflowDrafts.bindingFlagsFor;
    const measured = vi
      .spyOn(workflowDrafts, "bindingFlagsFor")
      .mockImplementation((flow, position, sources) =>
        batch(
          flow,
          position,
          sources.map((source) => ({
            ...source,
            fields: source.fields?.map((field) => ({
              ...field,
              get mustBeGiven() {
                traversed += 1;
                return field.mustBeGiven;
              },
            })),
          })),
        ),
      );
    onTestFinished(() => measured.mockRestore());
    const held = await editing(0, true, workflow);
    const before = flowOf(workflow);
    expect(held.flow).toEqual(before);
    expect(traversed).toBe(100);
    traversed = 0;
    const table = screen.getByRole("table");
    const first = within(table)
      .getAllByRole("rowgroup", { hidden: true })
      .find((group) => group.getAttribute("aria-label") === "field_0")!;
    await userEvent.click(
      within(first).getByRole("combobox", { name: "Which, field_0" }),
    );
    const options = within(screen.getByRole("listbox")).getAllByRole("option", {
      hidden: true,
    });
    expect(options.map((option) => option.textContent)).toEqual([
      "None chosen yet",
      ...fields.map((field) => field.name),
    ]);
    await userEvent.click(options[2]!);
    expect(traversed).toBe(100);
    expect(held.flow).toEqual({
      ...before,
      steps: before.steps.map((step) => ({
        ...step,
        bindings: [
          ...step.bindings.slice(1),
          { target: "field_0", source: { from: "input", path: "field_1" } },
        ],
      })),
    });
    expect(
      within(first).getByRole("combobox", { name: "Which, field_0" }),
    ).toHaveTextContent("field_1");
  });

  it.each([
    ["title", "", "Not chosen yet"],
    ["title", "never", "Waits on a review"],
    ["body", "", "Not chosen yet"],
    ["body", "always", "Always"],
    ["references", "always", "Always"],
    ["references", "never", "Waits on a review"],
    ["other", "always", "Always"],
    ["other", "never", "Waits on a review"],
  ] as const)(
    "changes only %s standing to %s in the complete parent flow",
    async (field, standing, words) => {
      const held = await editing(3, true, searchingWorkflow());
      await choose(`${field} stands`, words);
      const before = unchangedSearchFlow();
      expect(held.flow).toEqual({
        ...before,
        steps: before.steps.map((step, index) =>
          index === 3
            ? {
                ...step,
                runs: {
                  kind: "search",
                  version: "q-search",
                  searchWay: "web",
                  standings: {
                    title: "always",
                    body: "never",
                    references: "",
                    other: "",
                    [field]: standing,
                  },
                },
              }
            : step,
        ),
      });
    },
  );

  it("reads all four standing facts without controls and keeps the complete parent unchanged", async () => {
    const held = await editing(3, false, searchingWorkflow());
    for (const field of ["title", "body", "references", "other"]) {
      expect(
        screen.queryByRole("combobox", { name: `${field} stands` }),
      ).toBeNull();
      const term = screen.getByText(`${field} stands`);
      expect(term.tagName).toBe("DT");
      expect(term.nextElementSibling?.tagName).toBe("DD");
      expect(term.nextElementSibling?.textContent).not.toBe("");
      await userEvent.click(term);
      expect(screen.queryByRole("listbox")).toBeNull();
    }
    expect(held.flow).toEqual(unchangedSearchFlow());
  });

  it("lays the four standings read out by the room of their own container, a pair to a row below 560 pixels of it as in a step's panel, never by the screen's width", async () => {
    await editing(3, false, searchingWorkflow());

    const list = screen.getByText("title stands").closest("dl")!;
    const className = styledClassOf(list);

    expect(getComputedStyle(list.parentElement!).containerType).toBe(
      "inline-size",
    );
    expect(getComputedStyle(list).gridTemplateColumns).toBe(
      "112px minmax(0, 1fr)",
    );
    expect(writtenRules()).toContain(
      `@container (min-width: 560px){.${className}{grid-template-columns:112px minmax(0, 1fr) 112px minmax(0, 1fr);`,
    );
    expect(writtenRules()).not.toMatch(
      new RegExp(`@media[^{]*\\{\\.${className}\\{`),
    );
  });

  it.each([
    ["A question", { kind: "question", version: "" }],
    ["A workflow", { kind: "workflow", version: "" }],
    ["A code step", { kind: "code_step", codeStep: "" }],
    [
      "A route",
      { kind: "route", discriminator: { from: "none" }, gives: [], cases: [] },
    ],
    [
      "A way to search",
      {
        kind: "search",
        searchWay: "",
        version: "",
        standings: { title: "", body: "", references: "", other: "" },
      },
    ],
    ["Nothing yet", { kind: "none" }],
  ] satisfies readonly (readonly [string, RunsDraft])[])(
    "clears bindings for %s while preserving both roles, tries and neighboring steps",
    async (words, runs) => {
      const held = await editing(0, true);
      if (runs.kind === "question") await choose("Runs", "A workflow");
      await choose("Runs", words);
      const before = unchangedFlow();
      expect(held.flow).toEqual({
        ...before,
        steps: before.steps.map((step, index) =>
          index === 0 ? { ...step, runs, bindings: [] } : step,
        ),
      });
    },
  );

  it("clears bindings and only the producer model and mode when choosing another real search way", async () => {
    const workflow = searchingWorkflow();
    const search = workflow.steps[3]!;
    const held = await editing(3, true, {
      ...workflow,
      steps: [
        ...workflow.steps.slice(0, 3),
        {
          ...search,
          producer: {
            kind: "model",
            model: "general",
            mode: "research",
            toldWhatHappened: true,
          },
          reviewer: { model: "small", mode: "careful" },
        },
      ],
      offered: {
        ...workflow.offered,
        searchWays: [
          ...workflow.offered.searchWays,
          { name: "catalogue", tool: "catalogue_search", gives: [] },
        ],
      },
    });
    await choose(
      "Way to search",
      "catalogue Needs a model offering catalogue_search",
    );
    const before = unchangedSearchFlow();
    expect(held.flow).toEqual({
      ...before,
      steps: before.steps.map((step, index) =>
        index === 3
          ? {
              ...step,
              model: "",
              mode: "",
              reviewer: "model",
              reviewerModel: "small",
              reviewerMode: "careful",
              bindings: [],
              runs: {
                kind: "search",
                searchWay: "catalogue",
                version: "q-search",
                standings: {
                  title: "always",
                  body: "never",
                  references: "",
                  other: "",
                },
              },
            }
          : step,
      ),
    });
  });

  it("keeps producer and reviewer model and mode changes in their own complete parent fields", async () => {
    const held = await editing(0, true, {
      ...WORKFLOW,
      steps: [
        {
          ...ASKING,
          producer: {
            kind: "model",
            model: "general",
            mode: "research",
            toldWhatHappened: false,
          },
        },
        ...WORKFLOW.steps.slice(1),
      ],
      offered: {
        ...WORKFLOW.offered,
        models: [
          { name: "general", modes: ["research", "deep_research"], tools: [] },
          { name: "special", modes: ["careful", "thorough"], tools: [] },
        ],
      },
    });
    const before = unchangedFlow();
    let expected: FlowDraft = {
      ...before,
      steps: before.steps.map((step, index) =>
        index === 0
          ? { ...step, producer: "model", model: "general", mode: "research" }
          : step,
      ),
    };
    const producing = placeOf(
      screen.getByRole("combobox", { name: "Who produces its values" }),
    ).parentElement!;
    const reviewing = placeOf(
      screen.getByRole("combobox", { name: "Who may review" }),
    ).parentElement!;
    await chooseIn(producing, "Model", "special");
    expected = {
      ...expected,
      steps: expected.steps.map((step, index) =>
        index === 0 ? { ...step, model: "special", mode: "" } : step,
      ),
    };
    expect(held.flow).toEqual(expected);
    await chooseIn(producing, "Mode", "careful");
    expected = {
      ...expected,
      steps: expected.steps.map((step, index) =>
        index === 0 ? { ...step, mode: "careful" } : step,
      ),
    };
    expect(held.flow).toEqual(expected);
    await chooseIn(reviewing, "Model", "special");
    expected = {
      ...expected,
      steps: expected.steps.map((step, index) =>
        index === 0
          ? { ...step, reviewerModel: "special", reviewerMode: "" }
          : step,
      ),
    };
    expect(held.flow).toEqual(expected);
    await chooseIn(reviewing, "Mode", "careful");
    expected = {
      ...expected,
      steps: expected.steps.map((step, index) =>
        index === 0 ? { ...step, reviewerMode: "careful" } : step,
      ),
    };
    expect(held.flow).toEqual(expected);
    await chooseIn(reviewing, "Mode", "As it is");
    expected = {
      ...expected,
      steps: expected.steps.map((step, index) =>
        index === 0 ? { ...step, reviewerMode: "" } : step,
      ),
    };
    expect(held.flow).toEqual(expected);
    await userEvent.click(
      screen.getByRole("checkbox", {
        name: "A model asked again is told what happened",
      }),
    );
    expected = {
      ...expected,
      steps: expected.steps.map((step, index) =>
        index === 0 ? { ...step, toldWhatHappened: true } : step,
      ),
    };
    expect(held.flow).toEqual(expected);
    await userEvent.click(
      screen.getByRole("checkbox", {
        name: "A model asked again is told what happened",
      }),
    );
    expect(held.flow).toEqual({
      ...expected,
      steps: expected.steps.map((step, index) =>
        index === 0 ? { ...step, toldWhatHappened: false } : step,
      ),
    });
  });

  it.each([
    ["0", true],
    ["1", false],
    ["2147483647", false],
    ["2147483648", true],
  ] as const)(
    "keeps typed tries %s and its real validity without changing another parent field",
    async (tries, invalid) => {
      const held = await editing(0, true);
      const control = screen.getByRole("textbox", {
        name: "How many attempts",
      });
      fireEvent.change(control, { target: { value: tries } });
      expect(control).toHaveValue(tries);
      expect(control).toHaveAttribute("aria-invalid", String(invalid));
      const before = unchangedFlow();
      expect(held.flow).toEqual({
        ...before,
        steps: before.steps.map((step, index) =>
          index === 0 ? { ...step, tries } : step,
        ),
      });
      if (invalid)
        expect(
          screen.getByText("Attempts are a whole number from 1."),
        ).toBeVisible();
      else
        expect(
          screen.queryByText("Attempts are a whole number from 1."),
        ).toBeNull();
    },
  );
});

describe("StepEditor", () => {
  it.each([true, false])(
    "names an inline search mismatch from its draft pin without exposing the pin ID: %s",
    async (readable) => {
      const source = searchingWorkflow();
      const search = source.steps[3]!;
      const steps = source.steps.map((step) =>
        !readable && step.stepId === search.stepId
          ? { ...step, runs: { ...search.runs!, version: undefined } }
          : step,
      );
      await editing(3, true, {
        ...source,
        steps,
        problems: [
          {
            code: "search_question_no_longer_matches",
            part: "steps",
            stepId: search.stepId,
          },
        ],
      });
      const warning = screen.getByText(/Make a new version of that question/);
      expect(warning.textContent).toBe(
        readable
          ? `Its question, ${isolatedInText("Find evidence")} · Version 1, no longer matches what ${isolatedInText("web")} gives back. Make a new version of that question, then pick it for this step.`
          : `Its question no longer matches what ${isolatedInText("web")} gives back. Make a new version of that question, then pick it for this step.`,
      );
      expect(warning.textContent).not.toContain("q-search");
    },
  );
  it("edits display text independently and keeps the machine identifier", async () => {
    const held = await editing(0, true);
    const label = screen.getByRole("textbox", { name: "Display name" });
    const name = screen.getByRole("textbox", { name: "Name" });

    expect(label).toHaveValue("");
    expect(label).toHaveAccessibleDescription(
      "Shown to people in place of the step's name. Any language, up to 128 characters.",
    );
    expect(name).toHaveAccessibleDescription(
      "Used to connect steps. Lowercase English letters, digits and underscores.",
    );
    fireEvent.change(label, { target: { value: "確認する\u202e" } });
    expect(held.flow.steps[0]!.label).toBe("確認する\u202e");
    expect(held.flow.steps[0]!.name).toBe("triage");
    expect(held.flow.steps[0]!.stepId).toBe("s1");
    expect(name).toHaveValue("triage");
    expect(label).toHaveAttribute("dir", "auto");
    fireEvent.change(label, { target: { value: "" } });
    expect(held.flow.steps[0]!.label).toBe("");
    expect(name).toHaveValue("triage");
  });

  it.each([" A", "A ", "A  B", "\u2066\u2069", "😀".repeat(129)])(
    "shows the exact display name refusal for %j",
    async (label) => {
      await editing(0, true);
      const control = screen.getByRole("textbox", { name: "Display name" });

      fireEvent.change(control, { target: { value: label } });

      expect(control).toHaveAttribute("aria-invalid", "true");
      expect(control).toHaveAccessibleDescription(
        "A step's display name is up to 128 characters on one line, with single spaces between words, none at the start or end, and something in it that shows. Leave it empty for none.",
      );
    },
  );

  it("refuses a stored display name containing a line break before it can be saved", async () => {
    const held = await editing(0, true, {
      ...WORKFLOW,
      steps: [{ ...ASKING, label: "A\nB" }, ...WORKFLOW.steps.slice(1)],
    });
    const control = screen.getByRole("textbox", { name: "Display name" });

    expect(held.flow.steps[0]!.label).toBe("A\nB");
    expect(control).toHaveAttribute("aria-invalid", "true");
    expect(control).toHaveAccessibleDescription(
      "A step's display name is up to 128 characters on one line, with single spaces between words, none at the start or end, and something in it that shows. Leave it empty for none.",
    );
  });

  it("names a local collision without changing either machine identifier", async () => {
    const held = await editing(0, true);
    const label = screen.getByRole("textbox", { name: "Display name" });

    fireEvent.change(label, { target: { value: "ROUTE" } });

    expect(label).toHaveAttribute("aria-invalid", "true");
    expect(label).toHaveAccessibleDescription(
      `Another step in this version is already shown as "${isolatedInText("route")}".`,
    );
    expect(held.flow.steps.map((step) => step.name)).toEqual([
      "triage",
      "route",
      "tidy",
    ]);
  });
  it("refreshes memoized search choices when an unnamed source becomes a real name with identical words", async () => {
    const workflow = searchingWorkflow();
    const original = flowOf(workflow);
    function Host() {
      const [flow, setFlow] = useState({
        ...original,
        steps: original.steps.map((step, index) =>
          index === 0 ? { ...step, name: "" } : step,
        ),
      });
      return (
        <>
          <button
            onClick={() =>
              setFlow((now) => ({
                ...now,
                steps: now.steps.map((step, index) =>
                  index === 0
                    ? { ...step, name: "Step 1, not named yet" }
                    : step,
                ),
              }))
            }
          >
            Name the source
          </button>
          <StepEditor
            step={flow.steps[3]!}
            position={3}
            flow={flow}
            workflow={workflow}
            named={namedVersions(workflow)}
            details={new Map()}
            scope="spec:source-name"
            resolve={vi.fn()}
            editable
            change={(changed) =>
              setFlow((now) => ({
                ...now,
                steps: now.steps.map((step, index) =>
                  index === 3 ? changed(step) : step,
                ),
              }))
            }
          />
        </>
      );
    }
    render(<Host />, { wrapper: themed });
    const control = screen.getAllByRole("combobox", { name: /^From, / })[1]!;
    expect(control.textContent).toBe("Step 1: Step 1, not named yet");
    expect(
      getComputedStyle(within(control).getByText("not named yet")).fontStyle,
    ).toBe("italic");
    await userEvent.click(
      screen.getByRole("button", { name: "Name the source" }),
    );
    expect(control.textContent).toBe("Step 1: Step 1, not named yet");
    expect(within(control).queryByText("not named yet")).toBeNull();
    const name = within(control).getByText("Step 1, not named yet");
    expect(name.tagName).toBe("BDI");
    expect(getComputedStyle(name).fontStyle).not.toBe("italic");
    await userEvent.click(control);
    const option = screen.getByRole("option", {
      name: "Step 1: Step 1, not named yet",
    });
    expect(within(option).queryByText("not named yet")).toBeNull();
    await userEvent.keyboard("{Escape}");
    expect(control).toHaveFocus();
  });
  it("gives the name and runs their own full-width vertical places", async () => {
    await editing(0, true);
    const name = screen
      .getByRole("textbox", { name: "Name" })
      .closest(".MuiInputBase-root")!;
    const runs = screen
      .getByRole("combobox", { name: "Runs" })
      .closest(".MuiInputBase-root")!;
    const group = name.parentElement!.parentElement!;
    expect(runs.parentElement!.parentElement).toBe(group);
    expect(getComputedStyle(group).flexDirection).toBe("column");
    expect(getComputedStyle(group).alignItems).toBe("stretch");
    expect(name).toHaveClass("MuiInputBase-fullWidth");
    expect(runs).toHaveClass("MuiInputBase-fullWidth");
    expect(getComputedStyle(group).gap).toBe("20px");
  });
  it("stands every field of a step searching under a label above its box, the model and mode too, none floating inside one", async () => {
    await editing(3, true, searchingWorkflow());

    const labelled = [...document.querySelectorAll(".MuiInputBase-root")]
      .map(
        (field) =>
          field
            .closest(".MuiFormControl-root")
            ?.querySelector(".MuiFormLabel-root")?.textContent,
      )
      .filter((label) => label !== undefined);

    expect(labelled).toEqual([
      "Name",
      "Display name",
      "Runs",
      "Way to search",
      "Version",
      "Who produces its values",
      "Model",
      "Mode",
      "How many attempts",
      "Who may review",
      "title stands",
      "body stands",
      "references stands",
      "other stands",
    ]);
    expect(document.querySelectorAll(".MuiInputLabel-root")).toHaveLength(0);
  });

  it("spaces search headings from the labels standing above their fields and keeps complete labels across the column", async () => {
    await editing(3, true, searchingWorkflow());
    const heading = screen.getByRole("heading", { name: "Gives back stands" });
    expect(getComputedStyle(heading).marginBottom).toBe("20px");
    const field = screen
      .getByRole("combobox", { name: "references stands" })
      .closest(".MuiInputBase-root")!;
    expect(field).toHaveClass("MuiInputBase-fullWidth");
    expect(
      getComputedStyle(field.parentElement!.parentElement!).flexDirection,
    ).toBe("column");
    const control = screen.getByRole("combobox", { name: "references stands" });
    const label = document.getElementById(
      control.getAttribute("aria-labelledby")!,
    )!;
    expect(label.textContent).toBe("references stands");
    expect(label.nextElementSibling).toBe(field);
    expect(field.contains(label)).toBe(false);
    expect(field.querySelector(".MuiInputLabel-root")).toBeNull();
  });
  it("starts producer and tries at the same edge, with model controls stretched", async () => {
    await editing(3, true, searchingWorkflow());
    const producer = screen
      .getByRole("combobox", { name: "Who produces its values" })
      .closest(".MuiInputBase-root")!;
    const tries = screen
      .getByRole("textbox", { name: "How many attempts" })
      .closest(".MuiInputBase-root")!;
    const group = producer.parentElement!.parentElement!;
    expect(tries.parentElement!.parentElement).toBe(group);
    expect(getComputedStyle(group).flexDirection).toBe("column");
    expect(getComputedStyle(group).alignItems).toBe("stretch");
    expect(getComputedStyle(producer).width).toBe("100%");
    expect(getComputedStyle(tries).width).toBe("100%");
    const model = within(group)
      .getByRole("combobox", { name: "Model" })
      .closest(".MuiInputBase-root")!;
    expect(getComputedStyle(model).width).toBe("100%");
  });

  it("shows a search way's tool and offers only models that provide it", async () => {
    await editing(3, true, searchingWorkflow());

    await userEvent.click(
      screen.getByRole("combobox", { name: "Way to search" }),
    );
    expect(
      screen.getAllByText("Needs a model offering web_search").length,
    ).toBeGreaterThan(0);
    await userEvent.keyboard("{Escape}");
    await userEvent.click(screen.getByRole("combobox", { name: "Model" }));
    expect(screen.getByRole("option", { name: "general" })).toBeInTheDocument();
    expect(screen.queryByRole("option", { name: "small" })).toBeNull();
    await userEvent.keyboard("{Escape}");
    await userEvent.click(screen.getByRole("combobox", { name: "Mode" }));
    expect(
      screen.getAllByRole("option").map((option) => option.textContent),
    ).toEqual(["As it is", "research", "deep_research"]);
  });

  it("keeps the chosen question version and renamed step together with ordinary bindings", async () => {
    const held = await editing(3, true, searchingWorkflow());
    const original = structuredClone(held.flow.steps[3]!);
    fireEvent.change(screen.getByRole("textbox", { name: "Name" }), {
      target: { value: "find_more" },
    });
    await userEvent.click(screen.getByRole("combobox", { name: "Version" }));
    fireEvent.change(screen.getByRole("combobox", { name: "Version" }), {
      target: { value: "Find" },
    });
    await userEvent.click(
      await screen.findByRole("option", { name: /Find evidence.*Version 2/ }),
    );
    const step = held.flow.steps[3]!;
    expect(step.name).toBe("find_more");
    expect(step.runs).toEqual({ ...original.runs, version: "q-search-next" });
    expect(step.bindings).toEqual(
      original.bindings.map(({ target, source }) => ({ target, source })),
    );
    expect(
      step.bindings.every((binding) => binding.bindingId === undefined),
    ).toBe(true);
    expect(step.producer).toBe(original.producer);
    expect(step.tries).toBe(original.tries);
    expect(step.reviewer).toBe(original.reviewer);
    expect(step.runs).not.toHaveProperty("question");
  });

  it.each(["in_service", "retired"] as const)(
    "takes a matching newer search question from a %s pin, recalculating inputs and preserving the rest of the draft",
    async (standing) => {
      const source = searchingWorkflow();
      const original = source.steps[3]!;
      if (
        original.runs?.kind !== "search" ||
        original.runs.version === undefined
      ) {
        throw new Error("The fixture needs its search question");
      }
      const newer = source.available.questions.find(
        (question) => question.versionId === "q-search-next",
      );
      if (newer === undefined) throw new Error("The newer question is offered");
      const workflow: FixtureWorkflow = {
        ...source,
        steps: source.steps.map((step, index) =>
          index === 3
            ? {
                ...original,
                reviewer: { model: "general", mode: "research" },
                runs: {
                  ...original.runs!,
                  kind: "search",
                  version: {
                    ...original.runs!.version!,
                    standing,
                    newer: {
                      versionName: null,
                      versionId: "q-search-next",
                      number: 2,
                    },
                  },
                },
              }
            : step,
        ),
        available: {
          ...source.available,
          questions: source.available.questions.map((question) =>
            question.versionId === newer.versionId
              ? { ...question, takes: [COUNT] }
              : question,
          ),
        },
        offered: { ...source.offered },
      };
      const held = await editing(3, true, workflow);
      const before = structuredClone(held.flow);

      await userEvent.click(
        screen.getByRole("button", { name: "Take Version 2" }),
      );

      expect(held.flow).toEqual({
        ...before,
        steps: before.steps.map((step, index) =>
          index === 3
            ? {
                ...step,
                runs: { ...step.runs, version: "q-search-next" },
                bindings: [],
              }
            : step,
        ),
      });
      expect(screen.getByRole("combobox", { name: "Version" })).toHaveValue(
        `${isolatedInText("Find evidence")} \u00b7 Version 2`,
      );
      expect(
        versionShownAs(`Find evidence \u00b7 Version 2`),
      ).toBeInTheDocument();
    },
  );

  it.each([
    "shape mismatch",
    "wrong way",
    "unmarked",
    "unoffered",
    "read only",
    "retired newer",
  ])(
    "keeps the original search pin and draft when its newer version is %s",
    async (reason) => {
      const source = searchingWorkflow();
      const search = source.steps[3]!;
      if (search.runs?.kind !== "search" || search.runs.version === undefined) {
        throw new Error("The fixture needs its search question");
      }
      const offered = source.available.questions.map((question) => {
        if (question.versionId !== "q-search-next") return question;
        return {
          ...question,
          standing: reason === "retired newer" ? "retired" : "in_service",
          searchMatches:
            reason === "shape mismatch" ? false : question.searchMatches,
          searchWay:
            reason === "wrong way"
              ? "other"
              : reason === "unmarked"
                ? undefined
                : question.searchWay,
        };
      });
      const workflow: FixtureWorkflow = {
        ...source,
        steps: source.steps.map((step, index) =>
          index === 3
            ? {
                ...search,
                runs: {
                  ...search.runs!,
                  kind: "search",
                  version: {
                    ...search.runs!.version!,
                    newer: {
                      versionName: null,
                      versionId: "q-search-next",
                      number: 2,
                    },
                  },
                },
              }
            : step,
        ),
        available: {
          ...source.available,
          questions:
            reason === "unoffered"
              ? offered.filter(
                  (question) => question.versionId !== "q-search-next",
                )
              : offered,
        },
        offered: { ...source.offered },
      };
      const held = await editing(3, reason !== "read only", workflow);
      const before = structuredClone(held.flow);
      if (reason === "read only") {
        expect(
          screen.queryByRole("button", { name: "Take Version 2" }),
        ).toBeNull();
      } else {
        await userEvent.click(
          screen.getByRole("button", { name: "Take Version 2" }),
        );
        await waitFor(() =>
          expect(screen.getByRole("alert")).toBeInTheDocument(),
        );
      }
      expect(
        paragraphReading(
          "A newer version is in service: Find evidence \u00b7 Version 2",
        ),
      ).toBeInTheDocument();
      expect(held.flow).toEqual(before);
      expect(held.flow.steps[3]!.runs).toHaveProperty("version", "q-search");
    },
  );

  it("keeps an incompatible marked version visible and disabled, and omits other ways and unmarked questions", async () => {
    const held = await editing(3, true, searchingWorkflow());
    const before = structuredClone(held.flow);
    await userEvent.click(screen.getByRole("combobox", { name: "Version" }));
    fireEvent.change(screen.getByRole("combobox", { name: "Version" }), {
      target: { value: "shape" },
    });
    const mismatch = await screen.findByRole("option", {
      name: /Changed shape/,
    });
    expect(mismatch).toHaveAttribute("aria-disabled", "true");
    expect(
      within(mismatch).getByText(
        "No longer matches what web gives back; make a new version of it.",
      ),
    ).toBeInTheDocument();
    expect(screen.queryByRole("option", { name: /Another way/ })).toBeNull();
    expect(screen.queryByRole("option", { name: /Triage/ })).toBeNull();
    await userEvent.click(mismatch, {
      pointerEventsCheck: PointerEventsCheckLevel.Never,
    });
    expect(held.flow).toEqual(before);
    await userEvent.keyboard("{Escape}");
    expect(held.flow).toEqual(before);
  });

  it("shows the pinned question and ordinary inputs without offering editing on a read-only draft", async () => {
    const held = await editing(3, false, searchingWorkflow());
    expect(
      versionShownAs(`Find evidence \u00b7 Version 1`),
    ).toBeInTheDocument();
    expect(screen.queryByRole("combobox", { name: "Version" })).toBeNull();
    expect(screen.queryByRole("combobox", { name: /^From, / })).toBeNull();
    expect(held.flow.steps[3]!.bindings).toHaveLength(2);
  });

  it("updates search source choices and forward warnings when their inputs change", async () => {
    const original = searchingWorkflow();
    const later: DeclaredField = {
      ...COMPLAINT,
      fieldId: "later",
      name: "later",
    };
    function Host() {
      const [workflow, setWorkflow] = useState(original);
      const [flow, setFlow] = useState(flowOf(original));
      const position = flow.steps.findIndex((step) => step.key === "s4");
      return (
        <>
          <button
            onClick={() =>
              setWorkflow((now) => ({
                ...now,
                takes: [...now.takes, later],
              }))
            }
          >
            Offer another input
          </button>
          <button
            onClick={() =>
              setFlow((now) => ({
                ...now,
                steps: [now.steps[3]!, ...now.steps.slice(0, 3)],
              }))
            }
          >
            Move search first
          </button>
          <StepEditor
            step={flow.steps[position]!}
            position={position}
            flow={flow}
            workflow={workflow}
            named={namedVersions(workflow)}
            details={new Map()}
            scope="spec:route-name"
            resolve={vi.fn()}
            editable
            change={(changed) =>
              setFlow((now) => ({
                ...now,
                steps: now.steps.map((step, index) =>
                  index === position ? changed(step) : step,
                ),
              }))
            }
          />
        </>
      );
    }
    render(<Host />, { wrapper: themed });

    fireEvent.click(
      screen.getByRole("button", { name: "Offer another input" }),
    );
    await userEvent.click(
      screen.getAllByRole("combobox", { name: /^Which, / })[0]!,
    );
    expect(screen.getByRole("option", { name: "later" })).toBeInTheDocument();
    await userEvent.keyboard("{Escape}");
    expect(
      screen.queryByText(
        "It reads a step that does not come before this one, which submitting refuses.",
      ),
    ).toBeNull();

    fireEvent.click(screen.getByRole("button", { name: "Move search first" }));

    expect(
      screen.getByText(
        "It reads a step that does not come before this one, which submitting refuses.",
      ),
    ).toBeInTheDocument();
  });

  it("selects search input values from workflow inputs and earlier steps while excluding files for a text target", async () => {
    const file: DeclaredField = {
      fieldId: "f10",
      name: "attachment",
      kind: "file",
      many: false,
      mustBeGiven: true,
      maximumBytes: 100,
      types: [".txt"],
    };
    const workflow = {
      ...searchingWorkflow(),
      takes: [...WORKFLOW.takes, file],
    };
    const held = await editing(3, true, workflow);
    const before = structuredClone(held.flow.steps[3]!);
    const from = screen.getAllByRole("combobox", { name: /^From, / })[0]!;
    await userEvent.click(from);
    expect(
      screen.getByRole("option", { name: "What the workflow takes" }),
    ).toBeInTheDocument();
    expect(
      screen.getByRole("option", { name: /Step 1: triage/ }),
    ).toBeInTheDocument();
    expect(screen.queryByRole("option", { name: /Step 4/ })).toBeNull();
    await userEvent.click(
      screen.getByRole("option", { name: "What the workflow takes" }),
    );
    await userEvent.click(
      screen.getAllByRole("combobox", { name: /^Which, / })[0]!,
    );
    expect(
      screen.getByRole("option", { name: "complaint" }),
    ).toBeInTheDocument();
    const attachment = screen.queryByRole("option", { name: "attachment" });
    if (attachment !== null)
      expect(attachment).toHaveAttribute("aria-disabled", "true");
    await userEvent.click(screen.getByRole("option", { name: "complaint" }));
    expect(held.flow.steps[3]!.bindings[0]!.source).toEqual({
      from: "input",
      path: "complaint",
    });
    expect(held.flow.steps[3]!.bindings[1]).toEqual(before.bindings[1]);
    expect(held.flow.steps[3]!.runs).toEqual(before.runs);
  });

  it("holds what an editable step runs in the Version box with no Runs line below it", async () => {
    await editing(0, true);

    const field = screen
      .getByRole("combobox", { name: "Version" })
      .closest(".MuiInputBase-root")!;
    const chosen = field.closest(".MuiAutocomplete-root")!.nextElementSibling!;

    expect(screen.getByRole("combobox", { name: "Version" })).toHaveValue(
      `${isolatedInText("Triage")} \u00b7 Version 4`,
    );
    expect(chosen.textContent).not.toMatch(/^Runs /);
    expect(field.previousElementSibling!.textContent).toBe("Version");
    expect(field.previousElementSibling!.previousElementSibling).toBeNull();
  });

  it("reads what a step runs, who produces it, its tries and who reviews it where the draft may not be written", async () => {
    await editing(0, false);

    expect(versionShownAs(`Triage \u00b7 Version 4`)).toBeInTheDocument();
    expect(screen.getByText("Produces").nextElementSibling).toHaveTextContent(
      "A person",
    );
    expect(screen.getByText("Attempts").nextElementSibling).toHaveTextContent(
      "2",
    );
    expect(
      screen.getByText("Reviewed by").nextElementSibling,
    ).toHaveTextContent("general in research");
    expect(screen.queryByText("Not chosen yet")).toBeNull();
  });

  it.each([
    ["the code step it names", "tidy_up", "tidy_up"],
    ["that it names no code step yet", "", "Nothing chosen yet"],
  ])(
    "reads a code step by %s alone where the draft may not be written, leaving its kind to the panel's head",
    async (_case, codeStep, words) => {
      await editing(2, false, {
        ...WORKFLOW,
        steps: WORKFLOW.steps.map((step) =>
          step.stepId === "s3"
            ? { ...step, runs: { kind: "code_step", codeStep } }
            : step,
        ),
      });

      expect(screen.getByText(words)).toBeInTheDocument();
      expect(screen.queryByText(/code step/i)).toBeNull();
      expect(screen.queryByText("None chosen yet")).toBeNull();
    },
  );

  it.each(["question", "workflow"] as const)(
    "says a step running a %s that pins no version yet runs nothing chosen where the draft may not be written, as its row in the steps does",
    async (kind) => {
      await editing(0, false, {
        ...WORKFLOW,
        steps: WORKFLOW.steps.map((step) =>
          step.stepId === "s1" ? { ...step, runs: { kind } } : step,
        ),
      });

      expect(screen.getByText("Nothing chosen yet")).toBeInTheDocument();
      expect(screen.queryByText("None chosen yet")).toBeNull();
    },
  );

  it("reads a route by its parts alone where the draft may not be written, leaving its kind to the panel's head", async () => {
    await editing(1, false);

    expect(
      screen.getByRole("heading", { name: "What every case gives back" }),
    ).toBeInTheDocument();
    expect(screen.queryByText(/^(a )?route$/i)).toBeNull();
    expect(screen.queryByText("Nothing chosen yet")).toBeNull();
  });

  it("says a step that runs nothing yet runs nothing chosen where the draft may not be written, as its row in the steps does", async () => {
    await editing(1, false, {
      ...WORKFLOW,
      steps: WORKFLOW.steps.map((step) =>
        step.stepId === "s2" ? { ...step, runs: undefined } : step,
      ),
    });

    expect(screen.getByText("Nothing chosen yet")).toBeInTheDocument();
    expect(screen.queryByRole("combobox", { name: "Runs" })).toBeNull();
    expect(
      screen.queryByRole("heading", { name: "What every case gives back" }),
    ).toBeNull();
  });

  it("reads the step facts in one aligned list and the inputs under their own heading", async () => {
    await editing(0, false);

    const facts = screen.getByText("Attempts").closest("dl")!;
    expect(
      [...facts.querySelectorAll("dt")].map((label) => label.textContent),
    ).toEqual(["Runs", "Produces", "Attempts", "Reviewed by"]);
    expect(facts).toContainElement(versionShownAs(`Triage \u00b7 Version 4`));
    expect(getComputedStyle(facts).gridTemplateColumns).toContain("112px");
    expect(facts).not.toContainElement(
      screen.getByRole("heading", { name: "What fills each input" }),
    );
    expect(
      screen.getAllByRole("heading").map((heading) => heading.textContent),
    ).toEqual(["What fills each input"]);
  });

  it("reads what fills each input, and only those, under a heading of its own where the draft may not be written", async () => {
    await editing(0, false);

    expect(
      screen.getByRole("heading", { level: 5, name: "What fills each input" }),
    ).toBeInTheDocument();
    const inputs = screen.getByRole("heading", {
      level: 5,
      name: "What fills each input",
    }).parentElement!;
    expect(
      within(inputs)
        .getAllByRole("columnheader")
        .map((each) => each.textContent),
    ).toEqual(["From", "Which"]);
    expect(
      within(inputs)
        .getAllByRole("row")
        .slice(1)
        .map((row) =>
          [...row.querySelectorAll("td")].map((cell) => cell.textContent),
        ),
    ).toEqual([
      ["complaint"],
      ["What the workflow takes", "complaint"],
      ["It points at nothing what it reads from declares."],
      ["urgency"],
      ["What the workflow takes", "complaint"],
      ["What it reads does not fit what it fills."],
    ]);
  });

  it("offers no control on a step where the draft may not be written", async () => {
    await editing(0, false);

    expect(screen.queryByRole("textbox")).toBeNull();
    expect(screen.queryByRole("combobox")).toBeNull();
    expect(screen.queryByRole("button", { name: "Take Version 5" })).toBeNull();
  });

  it.each([
    ["code", "code", "Code"],
    ["a person", "person", "A person saying it was done"],
  ] as const)(
    "reads a code step whose values %s produces as such",
    async (_case, kind, words) => {
      await editing(2, false, {
        ...WORKFLOW,
        steps: WORKFLOW.steps.map((step) =>
          step.stepId === "s3" ? { ...step, producer: { kind } } : step,
        ),
      });

      expect(screen.getByText("Produces").nextElementSibling).toHaveTextContent(
        words,
      );
      expect(
        screen.getByText("Produces").nextElementSibling,
      ).not.toHaveTextContent("Not chosen yet");
    },
  );

  it("says at its head what submitting refuses of the step itself, and not what it refuses of an input or a binding", async () => {
    await editing(0, false);

    expect(head()).toHaveTextContent(
      /^What does not hold here yetIt says nothing yet of how many tries it may make\.$/,
    );
    expect(screen.getAllByText("What does not hold here yet")).toHaveLength(1);
    expect(
      screen.getByText("It points at nothing what it reads from declares."),
    ).toBeInTheDocument();
    expect(
      screen.queryByText("Nothing is bound yet to choose its case by."),
    ).toBeNull();
  });

  it("says at its head by how much the step's review could send past the most, as the review's and in words", async () => {
    await editing(0, false, {
      ...WORKFLOW,
      problems: [
        {
          code: "asking_past_largest",
          part: "steps",
          stepId: "s1",
          excess: 12,
        },
      ],
    });

    expect(head()).toHaveTextContent(
      /^What does not hold here yetA model reviewing what it produces could be sent 12 characters more than 8,388,608, the most one asking may send\.$/,
    );
    expect(screen.queryByText(/excess|It could send/)).toBeNull();
  });

  it("says a newer version of what it runs is in service, taking nothing until asked", async () => {
    const held = await editing(0, true);

    expect(
      paragraphReading(
        "A newer version is in service: Triage \u00b7 Version 5",
      ),
    ).toBeInTheDocument();
    expect(held.flow.steps[0]!.runs).toEqual({
      kind: "question",
      version: "q1",
    });
  });

  it("takes the newest version in service, keeping what fills each input it still takes and nothing else", async () => {
    const held = await editing(0, true);

    await userEvent.click(
      screen.getByRole("button", { name: "Take Version 5" }),
    );

    expect(held.flow.steps[0]!.runs).toEqual({
      kind: "question",
      version: "q5",
    });
    expect(held.flow.steps[0]!.bindings).toEqual([
      { target: "complaint", source: { from: "input", path: "complaint" } },
    ]);
    expect(held.flow.steps[1]).toEqual(flowOf(WORKFLOW).steps[1]);
  });

  it("draws controls for the inputs the version taken takes, and none for one it no longer takes", async () => {
    await editing(0, true);

    await userEvent.click(
      screen.getByRole("button", { name: "Take Version 5" }),
    );

    expect(
      screen.getByRole("rowgroup", { name: "complaint" }),
    ).toBeInTheDocument();
    expect(screen.queryByRole("rowgroup", { name: "urgency" })).toBeNull();
  });

  it("draws a control for each input of the code step it runs, as the release declares it, filled only from what fits", async () => {
    await editing(2, true);
    const input = screen.getByRole("rowgroup", { name: "text" });

    await chooseIn(input, "From", "What the workflow takes");
    await userEvent.click(
      within(input).getByRole("combobox", { name: /^Which, / }),
    );

    expect(
      screen.getAllByRole("option").map((option) => option.textContent),
    ).toEqual(["None chosen yet", "note"]);
    expect(
      screen.queryByText(/What this code step takes is not offered/),
    ).toBeNull();
  });

  it("says a code step not offered offers nothing to fill, leaving why to what the step's head says, and draws no input for it", async () => {
    await editing(2, true, {
      ...WORKFLOW,
      problems: [
        {
          code: "code_step_list_not_yet_in_service",
          part: "steps",
          stepId: "s3",
        },
      ],
      offered: { ...WORKFLOW.offered, codeSteps: [] },
    });

    expect(
      screen.getByText(
        "What this code step takes is not offered to this group, so there is nothing to fill here.",
      ),
    ).toBeInTheDocument();
    expect(head()).toHaveTextContent(
      /^What does not hold here yetThe code step it names takes a term from a version of a reference list not yet in service\.$/,
    );
    expect(screen.queryByRole("rowgroup", { name: "text" })).toBeNull();
  });

  it.each([
    [
      "offered",
      [TIDYING, { ...TIDYING, name: "stamp_reference" }],
      ["tidy_up", "stamp_reference"],
    ],
    [
      "offered no longer",
      [{ ...TIDYING, name: "stamp_reference" }],
      ["tidy_up", "stamp_reference"],
    ],
  ] as const)(
    "offers every code step the release holds for the group to run, and the one chosen, %s",
    async (_case, codeSteps, names) => {
      await editing(2, true, {
        ...WORKFLOW,
        offered: { ...WORKFLOW.offered, codeSteps },
      });

      await userEvent.click(
        screen.getByRole("combobox", { name: "Code step" }),
      );

      expect(
        screen.getAllByRole("option").map((option) => option.textContent),
      ).toEqual(["None chosen yet", ...names]);
    },
  );

  it("offers to fill an input only from what the workflow takes that fits it", async () => {
    await editing(0, true);

    await userEvent.click(
      within(screen.getByRole("rowgroup", { name: "complaint" })).getByRole(
        "combobox",
        { name: /^Which, / },
      ),
    );

    expect(
      screen.getAllByRole("option").map((option) => option.textContent),
    ).toEqual(["None chosen yet", "complaint", "note"]);
  });

  it("fills an input from what the workflow takes that is chosen, the other inputs as they were", async () => {
    const held = await editing(0, true);

    await chooseIn(
      screen.getByRole("rowgroup", { name: "complaint" }),
      "Which",
      "note",
    );

    expect(held.flow.steps[0]!.bindings).toEqual([
      {
        target: "urgency",
        source: { from: "input", path: "complaint" },
        bindingId: "b2",
      },
      { target: "complaint", source: { from: "input", path: "note" } },
    ]);
  });

  it("says against an input that must be given one filled from what need not be, which may be empty", async () => {
    await editing(0, true);
    const input = screen.getByRole("rowgroup", { name: "complaint" });
    const which = () =>
      within(input).getByRole("combobox", { name: /^Which, / });

    expect(which()).not.toHaveAccessibleDescription(/may be empty/);
    await chooseIn(input, "Which", "note");

    expect(which()).toHaveAccessibleDescription(
      "What it fills must be given, and what it reads need not be, so that may be empty.",
    );
    expect(input).not.toHaveAttribute("aria-describedby");
  });

  it("offers a step only what the workflow takes and the steps before it to read from", async () => {
    await editing(1, true);

    await userEvent.click(
      within(
        screen.getByRole("group", { name: "What it chooses by" }),
      ).getByRole("combobox", { name: /^From, / }),
    );

    expect(
      screen.getAllByRole("option").map((option) => option.textContent),
    ).toEqual(["Nothing yet", "What the workflow takes", "Step 1: triage"]);
  });

  it("offers each field within one holding fields to fill on its own, after the whole, and nothing else", async () => {
    await editing(0, true);

    await choose("Version", `Addressing \u00b7 Version 1`);

    expect(groupNames()).toEqual(["address", "street", "city"]);
    expect(
      ["address", "street", "city"].map(
        (name) =>
          getComputedStyle(
            screen
              .getByRole("rowgroup", { name })
              .querySelector("tr:first-child td > div")!,
          ).paddingLeft,
      ),
    ).toEqual(["0px", "24px", "24px"]);
  });

  it("fills a field within one holding fields on its own, filling nothing else", async () => {
    const held = await editing(0, true);
    await choose("Version", `Addressing \u00b7 Version 1`);
    const street = screen.getByRole("rowgroup", { name: "street" });

    await chooseIn(street, "From", "What the workflow takes");
    await chooseIn(street, "Which", "note");

    expect(held.flow.steps[0]!.bindings).toEqual([
      { target: "address.street", source: { from: "input", path: "note" } },
    ]);
  });

  it("says nothing fills a field within one holding fields, and not of the whole once one within it is filled", async () => {
    await editing(0, true);
    await choose("Version", `Addressing \u00b7 Version 1`);
    const street = screen.getByRole("rowgroup", { name: "street" });

    await chooseIn(street, "From", "What the workflow takes");
    await chooseIn(street, "Which", "note");

    expect(
      within(screen.getByRole("rowgroup", { name: "city" })).getByText(
        "Nothing fills it yet.",
      ),
    ).toBeInTheDocument();
    expect(
      screen.getByRole("rowgroup", { name: "address" }),
    ).not.toHaveAccessibleDescription(/Nothing fills it yet/);
  });

  it("offers a code step's values to be produced by code or by a person saying it was done, and no model for either", async () => {
    const held = await editing(2, true);

    await userEvent.click(
      screen.getByRole("combobox", { name: "Who produces its values" }),
    );
    expect(
      screen.getAllByRole("option").map((option) => option.textContent),
    ).toEqual(["Not chosen yet", "Code", "A person, saying it was done"]);
    await userEvent.click(
      screen.getByRole("option", { name: "A person, saying it was done" }),
    );

    expect(held.flow.steps[2]!.producer).toBe("person");
    expect(screen.queryByRole("combobox", { name: "Model" })).toBeNull();
    expect(
      screen.queryByRole("checkbox", {
        name: "A model asked again is told what happened",
      }),
    ).toBeNull();
  });

  it("asks how many tries on the ground of who produces where the draft may be written, apart from who reviews", async () => {
    await editing(0, true);

    const groundOf = (control: HTMLElement) => placeOf(control).parentElement!;
    const producing = groundOf(
      screen.getByRole("combobox", { name: "Who produces its values" }),
    );

    expect(
      within(producing).getByRole("textbox", { name: "How many attempts" }),
    ).toHaveValue("2");
    expect(
      groundOf(screen.getByRole("combobox", { name: "Who may review" })),
    ).not.toBe(producing);
    expect(drawnOf(producing)).toEqual(drawnAtDepth(3));
  });

  it("says a model reviewing is the one that produces where a model produces and the deployment holds one", async () => {
    await editing(0, true, {
      ...WORKFLOW,
      offered: {
        ...WORKFLOW.offered,
        models: [
          { name: "general", modes: ["research", "deep_research"], tools: [] },
        ],
      },
    });

    await choose("Who produces its values", "A model");

    expect(
      screen.getByText(
        "This deployment holds one model, so a model reviewing is the one that produces.",
      ),
    ).toBeInTheDocument();
  });

  it("says nothing of one model reviewing where the deployment holds two, offering a model to review", async () => {
    await editing(0, true, {
      ...WORKFLOW,
      offered: {
        ...WORKFLOW.offered,
        searchWays: [],
        models: [
          { name: "general", modes: ["research"], tools: [] },
          { name: "small", modes: [], tools: [] },
        ],
      },
    });

    await choose("Who produces its values", "A model");

    expect(screen.getAllByRole("combobox", { name: "Model" })).toHaveLength(2);
    expect(
      screen.queryByText(
        "This deployment holds one model, so a model reviewing is the one that produces.",
      ),
    ).toBeNull();
  });

  it.each([
    ["a person produces", 0],
    ["code produces", 2],
  ])(
    "says nothing of the one model reviewing where %s",
    async (_case, position) => {
      await editing(position, true);

      await choose("Who may review", "A model");

      expect(
        screen.getByRole("combobox", { name: "Model" }),
      ).toBeInTheDocument();
      expect(
        screen.queryByText(
          "This deployment holds one model, so a model reviewing is the one that produces.",
        ),
      ).toBeNull();
    },
  );

  it("names a step added in its panel's name box first", () => {
    const added = {
      ...WORKFLOW,
      steps: [...WORKFLOW.steps, { stepId: "s4", name: "", bindings: [] }],
    };
    const flow = flowOf(added);
    render(
      <StepEditor
        step={{ ...flow.steps[3]!, stepId: null }}
        position={3}
        flow={flow}
        workflow={added}
        named={namedVersions(added)}
        details={new Map()}
        scope="spec:added-step"
        resolve={vi.fn()}
        editable
        change={() => undefined}
      />,
      { wrapper: themed },
    );

    expect(screen.getByRole("textbox", { name: "Name" })).toHaveFocus();
  });
});
