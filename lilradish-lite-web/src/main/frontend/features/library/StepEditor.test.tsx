import { ThemeProvider } from "@mui/material/styles";
import { render, screen, within } from "@testing-library/react";
import { userEvent } from "@testing-library/user-event";
import { useState, type ReactNode } from "react";
import { describe, expect, it } from "vitest";

import type { DeclaredField, PinnedList } from "../../api/declaration";
import type {
  OfferedCodeStep,
  Step,
  WorkflowVersion,
} from "../../api/groups/{groupId}/{kind}/{entryId}/versions/{versionId}";
import { isolatedInText } from "../../lib/direction/isolated";
import { theme } from "../../lib/theme/theme";
import { StepEditor } from "./StepEditor";
import { flowOf, namedVersions, type FlowDraft } from "./workflowDrafts";

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
  name: "Triage",
  versionId: "q1",
  number: 4,
  standing: "in_service",
  newer: { versionId: "q5", number: 5 },
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

const WORKFLOW: WorkflowVersion = {
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
  offered: {
    lists: [],
    questions: [
      {
        name: "Triage",
        versionId: "q5",
        number: 5,
        takes: [COMPLAINT],
        gives: [SUMMARY],
      },
      {
        name: "Addressing",
        versionId: "q7",
        number: 1,
        takes: [ADDRESS],
        gives: [],
      },
    ],
    workflows: [],
    codeSteps: [TIDYING],
    models: [{ name: "general", modes: ["research"] }],
  },
  terms: new Map(),
};

function themed({ children }: { readonly children: ReactNode }) {
  return <ThemeProvider theme={theme}>{children}</ThemeProvider>;
}

/** The editor over a host holding the flow, whose last state the test reads. */
function editing(
  position: number,
  editable: boolean,
  workflow: WorkflowVersion = WORKFLOW,
) {
  const named = namedVersions(workflow);
  const held: { flow: FlowDraft } = { flow: flowOf(workflow) };
  function Host() {
    const [flow, setFlow] = useState(held.flow);
    return (
      <StepEditor
        step={flow.steps[position]!}
        position={position}
        flow={flow}
        workflow={workflow}
        named={named}
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
  render(<Host />, { wrapper: themed });
  return held;
}

async function choose(control: string, option: string): Promise<void> {
  await userEvent.click(screen.getByRole("combobox", { name: control }));
  await userEvent.click(screen.getByRole("option", { name: option }));
}

async function chooseIn(
  scope: HTMLElement,
  control: string,
  option: string,
): Promise<void> {
  await userEvent.click(within(scope).getByRole("combobox", { name: control }));
  await userEvent.click(screen.getByRole("option", { name: option }));
}

function head(): HTMLElement {
  return screen.getByText("What does not hold here yet").parentElement!;
}

function groupNames(): string[] {
  return screen
    .getAllByRole("group")
    .map(
      (group) =>
        document.getElementById(group.getAttribute("aria-labelledby")!)!
          .textContent ?? "",
    );
}

describe("StepEditor", () => {
  it("reads what a step runs, who produces it, its tries and who reviews it where the draft may not be written", () => {
    editing(0, false);

    expect(
      screen.getByText(`${isolatedInText("Triage")}, version 4`),
    ).toBeInTheDocument();
    expect(
      screen.getByText("Who produces its values: A person"),
    ).toBeInTheDocument();
    expect(screen.getByText("How many tries: 2")).toBeInTheDocument();
    expect(
      screen.getByText("Who may review: general in research"),
    ).toBeInTheDocument();
    expect(
      screen.queryByText("Who produces its values: Not chosen yet"),
    ).toBeNull();
  });

  it("reads what fills each input, and only those, under a heading of its own where the draft may not be written", () => {
    editing(0, false);

    expect(
      screen.getByRole("heading", { level: 5, name: "What fills each input" }),
    ).toBeInTheDocument();
    expect(
      screen
        .getAllByText(/: What the workflow takes, /)
        .map((each) => each.textContent),
    ).toEqual([
      "complaint: What the workflow takes, complaint",
      "urgency: What the workflow takes, complaint",
    ]);
  });

  it("offers no control on a step where the draft may not be written", () => {
    editing(0, false);

    expect(screen.queryByRole("textbox")).toBeNull();
    expect(screen.queryByRole("combobox")).toBeNull();
    expect(screen.queryByRole("button", { name: "Take version 5" })).toBeNull();
  });

  it.each([
    ["code", "code", "Code"],
    ["a person", "person", "A person saying it was done"],
  ] as const)(
    "reads a code step whose values %s produces as such",
    (_case, kind, words) => {
      editing(2, false, {
        ...WORKFLOW,
        steps: WORKFLOW.steps.map((step) =>
          step.stepId === "s3" ? { ...step, producer: { kind } } : step,
        ),
      });

      expect(
        screen.getByText(`Who produces its values: ${words}`),
      ).toBeInTheDocument();
      expect(
        screen.queryByText("Who produces its values: Not chosen yet"),
      ).toBeNull();
    },
  );

  it("says at its head what submitting refuses of the step itself, and not what it refuses of an input or a binding", () => {
    editing(0, false);

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

  it("says at its head by how much the step's review could send past the most, as the review's and in words", () => {
    editing(0, false, {
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

  it("says a newer version of what it runs is in service, taking nothing until asked", () => {
    const held = editing(0, true);

    expect(
      screen.getByText("Version 5 of what it runs is in service and newer."),
    ).toBeInTheDocument();
    expect(held.flow.steps[0]!.runs).toEqual({
      kind: "question",
      version: "q1",
    });
  });

  it("takes the newest version in service, keeping what fills each input it still takes and nothing else", async () => {
    const held = editing(0, true);

    await userEvent.click(
      screen.getByRole("button", { name: "Take version 5" }),
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
    editing(0, true);

    await userEvent.click(
      screen.getByRole("button", { name: "Take version 5" }),
    );

    expect(
      screen.getByRole("group", { name: "complaint" }),
    ).toBeInTheDocument();
    expect(screen.queryByRole("group", { name: "urgency" })).toBeNull();
  });

  it("draws a control for each input of the code step it runs, as the release declares it, filled only from what fits", async () => {
    editing(2, true);
    const input = screen.getByRole("group", { name: "text" });

    await chooseIn(input, "From", "What the workflow takes");
    await userEvent.click(
      within(input).getByRole("combobox", { name: "Which" }),
    );

    expect(
      screen.getAllByRole("option").map((option) => option.textContent),
    ).toEqual(["None chosen yet", "note"]);
    expect(
      screen.queryByText(/What this code step takes is not offered/),
    ).toBeNull();
  });

  it("says a code step not offered offers nothing to fill, leaving why to what the step's head says, and draws no input for it", () => {
    editing(2, true, {
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
    expect(screen.queryByRole("group", { name: "text" })).toBeNull();
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
      editing(2, true, {
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
    editing(0, true);

    await userEvent.click(
      within(screen.getByRole("group", { name: "complaint" })).getByRole(
        "combobox",
        { name: "Which" },
      ),
    );

    expect(
      screen.getAllByRole("option").map((option) => option.textContent),
    ).toEqual(["None chosen yet", "complaint", "note"]);
  });

  it("fills an input from what the workflow takes that is chosen, the other inputs as they were", async () => {
    const held = editing(0, true);

    await chooseIn(
      screen.getByRole("group", { name: "complaint" }),
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
    editing(0, true);
    const input = screen.getByRole("group", { name: "complaint" });

    expect(input).not.toHaveAccessibleDescription(/may be empty/);
    await chooseIn(input, "Which", "note");

    expect(input).toHaveAccessibleDescription(
      "What it fills must be given, and what it reads need not be, so that may be empty.",
    );
  });

  it("offers a step only what the workflow takes and the steps before it to read from", async () => {
    editing(1, true);

    await userEvent.click(
      within(
        screen.getByRole("group", { name: "What it chooses by" }),
      ).getByRole("combobox", { name: "From" }),
    );

    expect(
      screen.getAllByRole("option").map((option) => option.textContent),
    ).toEqual(["Nothing yet", "What the workflow takes", "Step 1: triage"]);
  });

  it("offers each field within one holding fields to fill on its own, after the whole, and nothing else", async () => {
    editing(0, true);

    await choose("Version", `${isolatedInText("Addressing")}, version 1`);

    expect(groupNames()).toEqual(["address", "address.street", "address.city"]);
  });

  it("fills a field within one holding fields on its own, filling nothing else", async () => {
    const held = editing(0, true);
    await choose("Version", `${isolatedInText("Addressing")}, version 1`);
    const street = screen.getByRole("group", { name: "address.street" });

    await chooseIn(street, "From", "What the workflow takes");
    await chooseIn(street, "Which", "note");

    expect(held.flow.steps[0]!.bindings).toEqual([
      { target: "address.street", source: { from: "input", path: "note" } },
    ]);
  });

  it("says nothing fills a field within one holding fields, and not of the whole once one within it is filled", async () => {
    editing(0, true);
    await choose("Version", `${isolatedInText("Addressing")}, version 1`);
    const street = screen.getByRole("group", { name: "address.street" });

    await chooseIn(street, "From", "What the workflow takes");
    await chooseIn(street, "Which", "note");

    expect(
      within(screen.getByRole("group", { name: "address.city" })).getByText(
        "Nothing fills it yet.",
      ),
    ).toBeInTheDocument();
    expect(
      screen.getByRole("group", { name: "address" }),
    ).not.toHaveAccessibleDescription(/Nothing fills it yet/);
  });

  it("offers a code step's values to be produced by code or by a person saying it was done, and no model for either", async () => {
    const held = editing(2, true);

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

  it("says a model reviewing is the one that produces where a model produces and the deployment holds one", async () => {
    editing(0, true);

    await choose("Who produces its values", "A model");

    expect(
      screen.getByText(
        "This deployment holds one model, so a model reviewing is the one that produces.",
      ),
    ).toBeInTheDocument();
  });

  it("says nothing of one model reviewing where the deployment holds two, offering a model to review", async () => {
    editing(0, true, {
      ...WORKFLOW,
      offered: {
        ...WORKFLOW.offered,
        models: [
          { name: "general", modes: ["research"] },
          { name: "small", modes: [] },
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
      editing(position, true);

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
        editable
        change={() => undefined}
      />,
      { wrapper: themed },
    );

    expect(screen.getByRole("textbox", { name: "Name" })).toHaveFocus();
  });
});
