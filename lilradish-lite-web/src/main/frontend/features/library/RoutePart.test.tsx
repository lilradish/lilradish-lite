import { ThemeProvider } from "@mui/material/styles";
import { render, screen, within } from "@testing-library/react";
import { userEvent } from "@testing-library/user-event";
import { useState, type ReactNode } from "react";
import { describe, expect, it } from "vitest";

import type { DeclaredField, PinnedList } from "../../api/declaration";
import type { WorkflowVersion } from "../../api/groups/{groupId}/{kind}/{entryId}/versions/{versionId}";
import { isolatedInText } from "../../lib/direction/isolated";
import { theme } from "../../lib/theme/theme";
import { StepEditor } from "./StepEditor";
import {
  flowOf,
  namedVersions,
  type FlowDraft,
  type RouteDraft,
} from "./workflowDrafts";

const CATEGORY: DeclaredField = {
  fieldId: "f1",
  name: "category",
  kind: "term",
  list: {
    name: "Categories",
    versionId: "l1",
    number: 1,
    standing: "in_service",
  },
  many: false,
  mustBeGiven: true,
};

const NOTE: DeclaredField = {
  fieldId: "f2",
  name: "note",
  kind: "text",
  longest: 100,
  many: false,
  mustBeGiven: false,
};

const REASON: DeclaredField = { ...NOTE, fieldId: "f3", name: "reason" };

const ESCALATE: PinnedList = {
  name: "Escalate",
  versionId: "w1",
  number: 1,
  standing: "in_service",
  newer: { versionId: "w2", number: 2 },
};

type RouteCase = NonNullable<
  NonNullable<WorkflowVersion["steps"][number]["runs"]>["cases"]
>[number];

const URGENT: RouteCase = {
  caseId: "c1",
  term: "urgent",
  workflow: ESCALATE,
  takes: [NOTE, REASON],
  gives: [],
  bindings: [
    { bindingId: "b1", target: "note", source: { input: "note" } },
    { bindingId: "b2", target: "reason", source: { input: "note" } },
  ],
};

function routing(
  cases: readonly RouteCase[],
  chosenBy: "category" | null = "category",
): WorkflowVersion {
  return {
    revision: 1,
    takes: [CATEGORY, NOTE],
    gives: [],
    steps: [
      {
        stepId: "s1",
        name: "route",
        runs: {
          kind: "route",
          ...(chosenBy === null
            ? {}
            : {
                discriminator: {
                  bindingId: "b0",
                  source: { input: chosenBy },
                },
              }),
          gives: [],
          cases,
        },
        bindings: [],
      },
    ],
    outputs: [],
    keepsOwnCeiling: false,
    raiseNeedsApproval: false,
    mayBeHelped: false,
    problems: [],
    sendsPast: [],
    offered: {
      lists: [],
      questions: [],
      workflows: [
        {
          name: "Escalate",
          versionId: "w2",
          number: 2,
          takes: [NOTE],
          gives: [],
        },
        { name: "Close", versionId: "w3", number: 1, takes: [], gives: [] },
      ],
      codeSteps: [],
      models: [],
    },
    terms: new Map([["l1", ["urgent", "later"]]]),
  };
}

function themed({ children }: { readonly children: ReactNode }) {
  return <ThemeProvider theme={theme}>{children}</ThemeProvider>;
}

/** The route's step in its editor, over a host holding the flow, whose last route the test reads. */
function editing(workflow: WorkflowVersion) {
  const named = namedVersions(workflow);
  const held: { flow: FlowDraft } = { flow: flowOf(workflow) };
  function Host() {
    const [flow, setFlow] = useState(held.flow);
    return (
      <StepEditor
        step={flow.steps[0]!}
        position={0}
        flow={flow}
        workflow={workflow}
        named={named}
        editable
        change={(changed) => {
          const next = { ...flow, steps: [changed(flow.steps[0]!)] };
          held.flow = next;
          setFlow(next);
        }}
      />
    );
  }
  render(<Host />, { wrapper: themed });
  return {
    route: (): RouteDraft => {
      const runs = held.flow.steps[0]!.runs;
      if (runs.kind !== "route") {
        throw new Error("the step is no route");
      }
      return runs;
    },
  };
}

function theCase(name: string): HTMLElement {
  return screen.getByRole("group", { name });
}

function addCase(): Promise<void> {
  return userEvent.click(screen.getByRole("button", { name: "Add a case" }));
}

const UNSET = "A case on no term yet";

describe("RoutePart", () => {
  it("offers a case only the terms the list it chooses by offers that no other case is on", async () => {
    editing(routing([URGENT]));
    await addCase();

    await userEvent.click(
      within(theCase(UNSET)).getByRole("combobox", { name: "Term" }),
    );

    expect(
      screen.getAllByRole("option").map((option) => option.textContent),
    ).toEqual(["None chosen yet", "later"]);
  });

  it("puts the term chosen on the case added, the other case's term as it was", async () => {
    const { route } = editing(routing([URGENT]));
    await addCase();

    await userEvent.click(
      within(theCase(UNSET)).getByRole("combobox", { name: "Term" }),
    );
    await userEvent.click(screen.getByRole("option", { name: "later" }));

    expect(route().cases.map((each) => each.term)).toEqual(["urgent", "later"]);
  });

  it("takes a term typed where the list it chooses by is not known, saying nothing against one no other case is on", async () => {
    const { route } = editing(routing([URGENT], null));
    await addCase();
    const term = within(theCase(UNSET)).getByRole("textbox", { name: "Term" });

    await userEvent.type(term, "later");

    expect(route().cases.map((each) => each.term)).toEqual(["urgent", "later"]);
    expect(term).not.toHaveAccessibleDescription(/No two cases/);
  });

  it("says against a term typed that another case is on, yet keeps it as typed", async () => {
    const { route } = editing(routing([URGENT], null));
    await addCase();
    const term = within(theCase(UNSET)).getByRole("textbox", { name: "Term" });

    await userEvent.type(term, "urgent");

    expect(term).toHaveAccessibleDescription(
      "No two cases of one route are on the same term.",
    );
    expect(route().cases.map((each) => each.term)).toEqual([
      "urgent",
      "urgent",
    ]);
  });

  it("stops saying another case is on a term once the term typed differs from it", async () => {
    editing(routing([URGENT], null));
    await addCase();
    const term = within(theCase(UNSET)).getByRole("textbox", { name: "Term" });
    await userEvent.type(term, "urgent");

    await userEvent.type(term, "ly");

    expect(term).not.toHaveAccessibleDescription(/No two cases/);
  });

  it("keeps each case added apart from every other, one taken out before it or not", async () => {
    const { route } = editing(routing([], null));
    await addCase();
    await userEvent.type(
      within(theCase(UNSET)).getByRole("textbox", { name: "Term" }),
      "a",
    );
    await addCase();
    await userEvent.type(
      within(theCase(UNSET)).getByRole("textbox", { name: "Term" }),
      "b",
    );
    await userEvent.click(
      screen.getByRole("button", {
        name: `Take out On ${isolatedInText("a")}`,
      }),
    );
    await addCase();

    await userEvent.type(
      within(theCase(UNSET)).getByRole("textbox", { name: "Term" }),
      "c",
    );

    expect(route().cases.map((each) => each.term)).toEqual(["b", "c"]);
    expect(theCase(`On ${isolatedInText("b")}`)).toBeInTheDocument();
  });

  it("makes one case the fallback, and then no other case may be", async () => {
    editing(routing([URGENT]));
    await addCase();

    await userEvent.click(
      within(theCase(UNSET)).getByRole("checkbox", {
        name: "The fallback, taking what no case claims",
      }),
    );

    expect(within(theCase("The fallback")).getByRole("checkbox")).toBeEnabled();
    expect(
      within(theCase(`On ${isolatedInText("urgent")}`)).getByRole("checkbox", {
        name: "The fallback, taking what no case claims",
      }),
    ).toBeDisabled();
  });

  it("holds adding a case once there is one on every term the list offers and a fallback, saying why", async () => {
    const { route } = editing(
      routing([
        URGENT,
        { caseId: "c2", term: "later", bindings: [] },
        { caseId: "c3", bindings: [] },
      ]),
    );
    const adding = screen.getByRole("button", { name: "Add a case" });

    adding.focus();
    await userEvent.keyboard("{Enter}");

    expect(route().cases).toHaveLength(3);
    expect(adding).toHaveAttribute("aria-disabled", "true");
    expect(adding).toHaveAccessibleDescription(
      "A route holds at most one case for each term the list it chooses by offers, and a fallback.",
    );
  });

  it("clears what fills a case once it leads elsewhere", async () => {
    const { route } = editing(routing([URGENT]));

    await userEvent.click(
      within(theCase(`On ${isolatedInText("urgent")}`)).getByRole("combobox", {
        name: "Leads to",
      }),
    );
    await userEvent.click(
      screen.getByRole("option", {
        name: `${isolatedInText("Close")}, version 1`,
      }),
    );

    expect(route().cases[0]!.workflow).toBe("w3");
    expect(route().cases[0]!.bindings).toEqual([]);
    expect(route().cases[0]!.term).toBe("urgent");
  });

  it("takes the newest version in service for a case, keeping what fills each input it still takes and nothing else", async () => {
    const { route } = editing(routing([URGENT]));

    await userEvent.click(
      within(theCase(`On ${isolatedInText("urgent")}`)).getByRole("button", {
        name: "Take version 2",
      }),
    );

    expect(route().cases[0]!.workflow).toBe("w2");
    expect(route().cases[0]!.bindings).toEqual([
      { target: "note", source: { from: "input", path: "note" } },
    ]);
  });

  it("puts focus on a case added", async () => {
    editing(routing([URGENT]));

    await addCase();

    expect(theCase(UNSET)).toHaveFocus();
  });

  it("puts focus on the case after one taken out, the one taken out gone", async () => {
    editing(routing([URGENT, { caseId: "c2", term: "later", bindings: [] }]));

    await userEvent.click(
      screen.getByRole("button", {
        name: `Take out On ${isolatedInText("urgent")}`,
      }),
    );

    expect(theCase(`On ${isolatedInText("later")}`)).toHaveFocus();
    expect(
      screen.queryByRole("group", { name: `On ${isolatedInText("urgent")}` }),
    ).toBeNull();
  });

  it("puts focus on the case before the last one taken out, the one taken out gone", async () => {
    editing(routing([URGENT, { caseId: "c2", term: "later", bindings: [] }]));

    await userEvent.click(
      screen.getByRole("button", {
        name: `Take out On ${isolatedInText("later")}`,
      }),
    );

    expect(theCase(`On ${isolatedInText("urgent")}`)).toHaveFocus();
    expect(
      screen.queryByRole("group", { name: `On ${isolatedInText("later")}` }),
    ).toBeNull();
  });

  it("heads what fills a case's inputs one level below the cases, and at no other level", () => {
    editing(routing([URGENT]));

    const inCase = within(theCase(`On ${isolatedInText("urgent")}`));
    expect(
      inCase.getByRole("heading", {
        level: 6,
        name: "What fills each input of what it leads to",
      }),
    ).toBeInTheDocument();
    expect(
      inCase.queryByRole("heading", {
        level: 5,
        name: "What fills each input of what it leads to",
      }),
    ).toBeNull();
  });

  it("puts focus on the cases' heading once the last case is taken out, no case left", async () => {
    const { route } = editing(routing([URGENT]));

    await userEvent.click(
      screen.getByRole("button", {
        name: `Take out On ${isolatedInText("urgent")}`,
      }),
    );

    expect(
      screen.getByRole("heading", { level: 5, name: "Cases" }),
    ).toHaveFocus();
    expect(route().cases).toEqual([]);
  });
});
