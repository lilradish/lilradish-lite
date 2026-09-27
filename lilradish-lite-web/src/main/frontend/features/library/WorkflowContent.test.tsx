import { ThemeProvider } from "@mui/material/styles";
import {
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import { userEvent } from "@testing-library/user-event";
import { RouterProvider, createMemoryRouter } from "react-router";
import { describe, expect, it, vi } from "vitest";

import type { Version } from "../../api/groups/{groupId}/{kind}";
import type { Problem } from "../../api/problem";
import { isolatedInText } from "../../lib/direction/isolated";
import { theme } from "../../lib/theme/theme";
import { requestsTo, serving, type Reply } from "../../testutil/answering";
import { deferred } from "../../testutil/deferred";
import { laidOutAt } from "../../testutil/layout";
import type { ReadContent } from "./ContentProblems";
import { WorkflowContent } from "./WorkflowContent";

const GROUP = "00000003-0000-4000-8000-000000000cb1";

const ENTRY = "00000006-0000-4000-8000-000000000cb1";

const VERSION = "00000007-0000-4000-8000-000000000cb1";

const ADDRESS = `/api/groups/${GROUP}/workflows/${ENTRY}/versions/${VERSION}`;

/** What the steps say where there are none, which submitting refuses. */
const NO_STEPS_REFUSED =
  "It holds no step, so it could give nothing back and act on nothing.";

const DRAFT: Version = {
  versionId: VERSION,
  number: 2,
  standing: "draft",
  writers: [{ userId: "000cb1" }],
  writtenByMigration: false,
  acts: new Set(["write", "submit"]),
  pinnedBy: [],
};

const READ_ONLY: Version = {
  ...DRAFT,
  standing: "in_service",
  acts: new Set(),
};

const COMPLAINT = {
  fieldId: "0000000b-0000-4000-8000-000000000cb1",
  name: "complaint",
  kind: "text",
  longest: 4000,
  many: false,
  mustBeGiven: true,
};

const SUMMARY = {
  fieldId: "0000000b-0000-4000-8000-000000000cb2",
  name: "summary",
  kind: "text",
  longest: 1000,
  many: false,
  mustBeGiven: true,
};

const TRIAGE = {
  name: "Triage",
  versionId: "00000007-0000-4000-8000-000000000cb2",
  number: 4,
  standing: "in_service",
};

const NEWER = "00000007-0000-4000-8000-000000000cb3";

const ASKING = {
  stepId: "0000000c-0000-4000-8000-000000000cb1",
  name: "triage",
  runs: {
    kind: "question",
    version: TRIAGE,
    takes: [COMPLAINT],
    gives: [SUMMARY],
  },
  producer: { kind: "person" },
  bindings: [
    {
      bindingId: "0000000e-0000-4000-8000-000000000cb1",
      target: "complaint",
      source: { input: "complaint" },
    },
  ],
};

const WORKFLOW = {
  revision: 3,
  takes: [COMPLAINT],
  gives: [SUMMARY],
  steps: [ASKING],
  outputs: [
    {
      bindingId: "0000000e-0000-4000-8000-000000000cb2",
      target: "summary",
      source: { step: 0, path: "summary" },
    },
  ],
  ceiling: "100",
  keepsOwnCeiling: false,
  raiseNeedsApproval: false,
  mayBeHelped: false,
  problems: [{ code: "tries_missing", part: "steps", stepId: ASKING.stepId }],
  sendsPast: [],
  offered: {
    lists: [],
    questions: [
      { ...TRIAGE, takes: [COMPLAINT], gives: [SUMMARY] },
      {
        ...TRIAGE,
        versionId: NEWER,
        number: 5,
        takes: [COMPLAINT],
        gives: [SUMMARY],
      },
    ],
    workflows: [],
    codeSteps: [],
    models: [],
  },
  terms: {},
};

const NOTIFYING = {
  stepId: "0000000c-0000-4000-8000-000000000cb2",
  name: "notify",
  runs: {
    kind: "question",
    version: TRIAGE,
    takes: [COMPLAINT],
    gives: [SUMMARY],
  },
  producer: { kind: "person" },
  bindings: [
    {
      bindingId: "0000000e-0000-4000-8000-000000000cb3",
      target: "complaint",
      source: { step: 0, path: "summary" },
    },
  ],
};

const PINNING_OLDER = {
  ...WORKFLOW,
  steps: [
    {
      ...ASKING,
      runs: {
        ...ASKING.runs,
        version: { ...TRIAGE, newer: { versionId: NEWER, number: 5 } },
      },
    },
  ],
};

const SAVE_STEPS = "Save the steps and what fills what it gives back";

function reply(body: object, status = 200): Reply {
  return [JSON.stringify(body), status];
}

/** The content under a real router whose address the test reads, and only the server stood in for. */
function opening(
  version: Version,
  routes: Readonly<Record<string, readonly Reply[]>> = {},
  search = "",
) {
  laidOutAt(1400, 1400);
  const sent = serving({ [`GET ${ADDRESS}`]: [reply(WORKFLOW)], ...routes });
  const refused = vi.fn<(problem: Problem) => void>();
  const written = vi.fn<() => void>();
  const shown = vi.fn<(content: ReadContent | null) => void>();
  const router = createMemoryRouter(
    [
      {
        path: "/content",
        element: (
          <WorkflowContent
            groupId={GROUP}
            entryId={ENTRY}
            version={version}
            readCount={0}
            onRefused={refused}
            onWritten={written}
            onShown={shown}
          />
        ),
      },
    ],
    { initialEntries: [`/content${search}`] },
  );
  render(
    <ThemeProvider theme={theme}>
      <RouterProvider router={router} />
    </ThemeProvider>,
  );
  return {
    sent,
    refused,
    written,
    shown,
    search: () => new URLSearchParams(router.state.location.search),
  };
}

function part(name: string): HTMLElement {
  return screen.getByRole("region", { name });
}

async function opened(): Promise<void> {
  await screen.findByRole("region", { name: "The steps" });
}

function rowsOf(): HTMLElement[] {
  return within(part("The steps")).getAllByRole("row").slice(1);
}

function rowWords(): string[] {
  return rowsOf().map((row) => row.textContent ?? "");
}

function panel(name: string): HTMLElement {
  return screen.getByRole("region", { name });
}

function bodyOf(sent: ReturnType<typeof serving>, index: number): unknown {
  return JSON.parse(String(sent.mock.calls[index]![1]?.body));
}

function saveSteps(): HTMLElement {
  return screen.getByRole("button", { name: SAVE_STEPS });
}

async function savedSince() {
  const theirs = {
    ...WORKFLOW,
    revision: 5,
    steps: [{ ...ASKING, name: "their_triage" }],
  };
  const session = opening(DRAFT, {
    [`GET ${ADDRESS}`]: [reply(WORKFLOW), reply(theirs)],
    [`PUT ${ADDRESS}/steps`]: [
      reply({ code: "DRAFT_WRITTEN_SINCE_READ" }, 409),
    ],
  });
  await opened();
  await userEvent.click(screen.getByRole("button", { name: "Add a step" }));
  await userEvent.type(
    within(panel("Step 2, not named yet")).getByRole("textbox", {
      name: "Name",
    }),
    "tidy",
  );
  await userEvent.click(saveSteps());
  await screen.findByRole("button", { name: "Read it afresh" });
  return session;
}

describe("WorkflowContent", () => {
  it("reads every step in its own row, what it runs, who produces it and what it gives", async () => {
    opening(READ_ONLY);
    await opened();

    expect(rowWords()).toEqual([
      expect.stringMatching(
        new RegExp(
          `${isolatedInText("Triage")}, version 4.*A person.*One value`,
          "u",
        ),
      ),
    ]);
  });

  it("lists what submitting would refuse by its place, and nothing it would not", async () => {
    opening(READ_ONLY);
    await opened();

    const listed = within(
      screen.getByRole("list", { name: "What does not hold yet" }),
    ).getAllByRole("listitem");
    expect(listed.map((item) => item.textContent)).toEqual([
      `The steps, ${isolatedInText("triage")}: It says nothing yet of how many tries it may make.`,
    ]);
    expect(
      screen.queryByRole("list", { name: "What could be too long to send" }),
    ).toBeNull();
  });

  it("says a step that could send its model more than it takes apart from what submitting would refuse", async () => {
    opening(READ_ONLY, {
      [`GET ${ADDRESS}`]: [
        reply({
          ...WORKFLOW,
          sendsPast: [
            {
              stepId: ASKING.stepId,
              role: "reviewing",
              model: "small",
              past: 2500,
            },
          ],
        }),
      ],
    });
    await opened();

    const said = within(
      screen.getByRole("list", { name: "What could be too long to send" }),
    ).getAllByRole("listitem");
    expect(said.map((item) => item.textContent)).toEqual([
      `${isolatedInText("triage")}: what it could send ${isolatedInText("small")} to review runs about 2,500 units past what that model takes.`,
    ]);
    expect(
      within(
        screen.getByRole("list", { name: "What does not hold yet" }),
      ).getAllByRole("listitem"),
    ).toHaveLength(1);
  });

  it("says a step that could send its model more than it takes where nothing else stands in the way", async () => {
    opening(READ_ONLY, {
      [`GET ${ADDRESS}`]: [
        reply({
          ...WORKFLOW,
          problems: [],
          sendsPast: [
            {
              stepId: ASKING.stepId,
              role: "producing",
              model: "general",
              past: 1,
            },
          ],
        }),
      ],
    });
    await opened();

    const said = within(
      screen.getByRole("list", { name: "What could be too long to send" }),
    ).getAllByRole("listitem");
    expect(said.map((item) => item.textContent)).toEqual([
      `${isolatedInText("triage")}: what it could send ${isolatedInText("general")} to produce runs about 1 unit past what that model takes.`,
    ]);
    expect(
      screen.queryByRole("list", { name: "What does not hold yet" }),
    ).toBeNull();
  });

  it("offers no control where the draft may not be written, and asks the server for nothing but the read", async () => {
    const { sent } = opening(READ_ONLY);
    await opened();

    expect(within(part("The steps")).queryAllByRole("button")).toEqual([]);
    expect(screen.queryByRole("button", { name: "Add a step" })).toBeNull();
    expect(screen.queryByRole("button", { name: SAVE_STEPS })).toBeNull();
    expect(screen.queryByRole("textbox", { name: "Ceiling" })).toBeNull();
    expect(screen.queryByRole("switch")).toBeNull();
    expect(requestsTo(sent)).toEqual([`GET ${ADDRESS}`]);
  });

  it("hands its host exactly the content it read, for naming where each problem is", async () => {
    const { shown } = opening(READ_ONLY);
    await opened();

    await waitFor(() =>
      expect(shown).toHaveBeenLastCalledWith({
        kind: "workflow",
        takes: [COMPLAINT],
        gives: [SUMMARY],
        steps: [ASKING],
        outputs: WORKFLOW.outputs,
        terms: [],
      }),
    );
  });

  it("opens a step read where the draft may not be written with nothing in it to change", async () => {
    opening(READ_ONLY, {}, `?step=${ASKING.stepId}`);
    await opened();

    const picked = panel(isolatedInText("triage"));
    expect(within(picked).queryByRole("textbox")).toBeNull();
    expect(within(picked).queryByRole("combobox")).toBeNull();
  });

  it("holds saving the steps as they were read, saying why", async () => {
    const { sent } = opening(DRAFT);
    await opened();

    expect(saveSteps()).toHaveAttribute("aria-disabled", "true");
    expect(saveSteps()).toHaveAccessibleDescription(
      "Change a step or what fills what it gives back first.",
    );
    saveSteps().focus();
    await userEvent.keyboard("{Enter}");
    expect(requestsTo(sent)).toEqual([`GET ${ADDRESS}`]);
  });

  it("picks a step added in the address and puts focus on its name, sending nothing yet", async () => {
    const { sent, search } = opening(DRAFT);
    await opened();

    await userEvent.click(screen.getByRole("button", { name: "Add a step" }));

    expect(
      within(panel("Step 2, not named yet")).getByRole("textbox", {
        name: "Name",
      }),
    ).toHaveFocus();
    expect(search().get("step")).not.toBeNull();
    expect(search().get("step")).not.toBe(ASKING.stepId);
    expect(requestsTo(sent)).toEqual([`GET ${ADDRESS}`]);
  });

  it("holds saving a step added until it is named, saying why, and sends nothing when pressed", async () => {
    const { sent } = opening(DRAFT);
    await opened();

    await userEvent.click(screen.getByRole("button", { name: "Add a step" }));
    saveSteps().focus();
    await userEvent.keyboard("{Enter}");

    expect(saveSteps()).toHaveAttribute("aria-disabled", "true");
    expect(saveSteps()).toHaveAccessibleDescription(
      "Step 2, not named yet: A step's name is one to 63 lowercase English letters, digits and underscores, starting with a letter.",
    );
    expect(requestsTo(sent)).toEqual([`GET ${ADDRESS}`]);
  });

  it("keeps a step added open once saved, picked under the key the server answered it with", async () => {
    const tidying = "0000000c-0000-4000-8000-000000000cb9";
    const { written, search } = opening(DRAFT, {
      [`PUT ${ADDRESS}/steps`]: [
        reply({
          ...WORKFLOW,
          revision: 4,
          steps: [ASKING, { stepId: tidying, name: "tidy", bindings: [] }],
        }),
      ],
    });
    await opened();
    await userEvent.click(screen.getByRole("button", { name: "Add a step" }));
    const added = search().get("step");
    await userEvent.type(
      within(panel("Step 2, not named yet")).getByRole("textbox", {
        name: "Name",
      }),
      "tidy",
    );

    await userEvent.click(saveSteps());

    await waitFor(() => expect(written).toHaveBeenCalledTimes(1));
    expect(search().getAll("step")).toEqual([tidying]);
    expect(search().get("step")).not.toBe(added);
    expect(
      await screen.findByRole("region", { name: isolatedInText("tidy") }),
    ).toBeInTheDocument();
  });

  it("sends a step added once named, after the steps read, and draws its row from the answer", async () => {
    const answered = {
      ...WORKFLOW,
      revision: 4,
      steps: [
        ASKING,
        {
          stepId: "0000000c-0000-4000-8000-000000000cb9",
          name: "tidy",
          bindings: [],
        },
      ],
    };
    const { sent, written, refused } = opening(DRAFT, {
      [`PUT ${ADDRESS}/steps`]: [reply(answered)],
    });
    await opened();
    await userEvent.click(screen.getByRole("button", { name: "Add a step" }));

    await userEvent.type(
      within(panel("Step 2, not named yet")).getByRole("textbox", {
        name: "Name",
      }),
      "tidy",
    );
    await userEvent.click(saveSteps());

    await waitFor(() => expect(written).toHaveBeenCalledTimes(1));
    expect(refused).not.toHaveBeenCalled();
    expect(requestsTo(sent)).toEqual([
      `GET ${ADDRESS}`,
      `PUT ${ADDRESS}/steps`,
    ]);
    expect(bodyOf(sent, 1)).toEqual({
      revision: 3,
      steps: [
        {
          stepId: ASKING.stepId,
          name: "triage",
          runs: { kind: "question", version: TRIAGE.versionId },
          producer: { kind: "person" },
          tries: null,
          reviewer: null,
          bindings: [{ target: "complaint", source: { input: "complaint" } }],
        },
        { stepId: null, name: "tidy", runs: null, bindings: [] },
      ],
      outputs: [{ target: "summary", source: { step: 0, path: "summary" } }],
    });
    expect(rowWords()).toHaveLength(2);
    expect(rowWords()[1]).toContain(isolatedInText("tidy"));
  });

  it("opens the step the address picks under a heading one below the steps', and at no other level", async () => {
    opening(DRAFT, {}, `?step=${ASKING.stepId}`);
    await opened();

    expect(
      screen.getByRole("heading", { level: 4, name: isolatedInText("triage") }),
    ).toBeInTheDocument();
    expect(
      screen.queryByRole("heading", {
        level: 3,
        name: isolatedInText("triage"),
      }),
    ).toBeNull();
  });

  it("marks the row of the step the address picks as the current one, and no other", async () => {
    const answered = { ...WORKFLOW, steps: [ASKING, NOTIFYING] };
    opening(
      DRAFT,
      { [`GET ${ADDRESS}`]: [reply(answered)] },
      `?step=${ASKING.stepId}`,
    );
    await opened();

    expect(
      screen.getByRole("link", { name: `Open ${isolatedInText("triage")}` }),
    ).toHaveAttribute("aria-current", "true");
    expect(
      screen.getByRole("link", { name: `Open ${isolatedInText("notify")}` }),
    ).not.toHaveAttribute("aria-current");
  });

  it("keeps the step picked in the address when its own row's link is pressed again", async () => {
    const { search } = opening(DRAFT, {}, `?step=${ASKING.stepId}`);
    await opened();

    await userEvent.click(
      screen.getByRole("link", { name: `Open ${isolatedInText("triage")}` }),
    );

    expect(search().getAll("step")).toEqual([ASKING.stepId]);
  });

  it("marks no row and opens no step where the address picks none", async () => {
    const { search } = opening(DRAFT);
    await opened();

    expect(
      screen.getByRole("link", { name: `Open ${isolatedInText("triage")}` }),
    ).not.toHaveAttribute("aria-current");
    expect(
      screen.queryByRole("heading", {
        level: 4,
        name: isolatedInText("triage"),
      }),
    ).toBeNull();
    expect(search().get("step")).toBeNull();
  });

  it("picks a step by its row's link, in the address, and opens it, asking the server nothing", async () => {
    const { sent, search } = opening(DRAFT);
    await opened();

    await userEvent.click(
      screen.getByRole("link", { name: `Open ${isolatedInText("triage")}` }),
    );

    expect(search().get("step")).toBe(ASKING.stepId);
    expect(
      await screen.findByRole("heading", {
        level: 4,
        name: isolatedInText("triage"),
      }),
    ).toBeInTheDocument();
    expect(requestsTo(sent)).toEqual([`GET ${ADDRESS}`]);
  });

  /** React hands focus back to a focused node it moves, so the press is made with focus elsewhere to see the page's own. */
  it("puts focus on the move pressed once its row has moved, wherever focus was", async () => {
    opening(DRAFT, {
      [`GET ${ADDRESS}`]: [reply({ ...WORKFLOW, steps: [ASKING, NOTIFYING] })],
    });
    await opened();
    const down = screen.getByRole("button", {
      name: `Move ${isolatedInText("triage")} down`,
    });

    fireEvent.click(down);

    expect(rowWords()[1]).toContain(isolatedInText("triage"));
    expect(down).toHaveFocus();
    expect(
      screen.getByRole("link", { name: `Open ${isolatedInText("notify")}` }),
    ).not.toHaveFocus();
  });

  it("moves a step down, and a step reading it is sent reading it at its new place", async () => {
    const { sent } = opening(DRAFT, {
      [`GET ${ADDRESS}`]: [reply({ ...WORKFLOW, steps: [ASKING, NOTIFYING] })],
      [`PUT ${ADDRESS}/steps`]: [reply(WORKFLOW)],
    });
    await opened();

    await userEvent.click(
      screen.getByRole("button", {
        name: `Move ${isolatedInText("triage")} down`,
      }),
    );
    await userEvent.click(saveSteps());
    await waitFor(() =>
      expect(requestsTo(sent)).toEqual([
        `GET ${ADDRESS}`,
        `PUT ${ADDRESS}/steps`,
      ]),
    );
    const body = bodyOf(sent, 1) as {
      steps: { stepId: string; bindings: unknown[] }[];
      outputs: unknown[];
    };
    expect(body.steps.map((step) => step.stepId)).toEqual([
      NOTIFYING.stepId,
      ASKING.stepId,
    ]);
    expect(body.steps[0]!.bindings).toEqual([
      { target: "complaint", source: { step: 1, path: "summary" } },
    ]);
    expect(body.outputs).toEqual([
      { target: "summary", source: { step: 1, path: "summary" } },
    ]);
  });

  it("puts focus on the next row's step after one taken out, the one taken out gone", async () => {
    opening(DRAFT, {
      [`GET ${ADDRESS}`]: [reply({ ...WORKFLOW, steps: [ASKING, NOTIFYING] })],
    });
    await opened();

    await userEvent.click(
      screen.getByRole("button", {
        name: `Take ${isolatedInText("triage")} out`,
      }),
    );

    expect(
      screen.getByRole("link", { name: `Open ${isolatedInText("notify")}` }),
    ).toHaveFocus();
    expect(
      screen.queryByRole("link", { name: `Open ${isolatedInText("triage")}` }),
    ).toBeNull();
  });

  it("puts focus on the row before the last one taken out, the one taken out gone", async () => {
    opening(DRAFT, {
      [`GET ${ADDRESS}`]: [reply({ ...WORKFLOW, steps: [ASKING, NOTIFYING] })],
    });
    await opened();

    await userEvent.click(
      screen.getByRole("button", {
        name: `Take ${isolatedInText("notify")} out`,
      }),
    );

    expect(
      screen.getByRole("link", { name: `Open ${isolatedInText("triage")}` }),
    ).toHaveFocus();
    expect(
      screen.queryByRole("link", { name: `Open ${isolatedInText("notify")}` }),
    ).toBeNull();
  });

  it("puts focus on the steps' heading once the last step is taken out, saying there is none", async () => {
    opening(DRAFT);
    await opened();
    expect(screen.queryByText(NO_STEPS_REFUSED)).toBeNull();

    await userEvent.click(
      screen.getByRole("button", {
        name: `Take ${isolatedInText("triage")} out`,
      }),
    );

    expect(
      screen.getByRole("heading", { level: 3, name: "The steps" }),
    ).toHaveFocus();
    expect(screen.getByText("No step yet.")).toBeInTheDocument();
    expect(screen.getByText(NO_STEPS_REFUSED)).toBeInTheDocument();
    expect(
      screen.queryByRole("link", { name: `Open ${isolatedInText("triage")}` }),
    ).toBeNull();
  });

  it("says on a step's row that a newer version of what it runs is in service, sending nothing", async () => {
    const { sent } = opening(DRAFT, {
      [`GET ${ADDRESS}`]: [reply(PINNING_OLDER)],
    });
    await opened();

    expect(rowWords()[0]).toContain(
      "Version 5 of what it runs is in service and newer.",
    );
    expect(
      within(rowsOf()[0]!).getByRole("button", {
        name: `Take version 5 for ${isolatedInText("triage")}`,
      }),
    ).toBeEnabled();
    expect(requestsTo(sent)).toEqual([`GET ${ADDRESS}`]);
  });

  it("shows the newer version taken on its row in place of the one pinned, before anything is sent", async () => {
    const { sent } = opening(DRAFT, {
      [`GET ${ADDRESS}`]: [reply(PINNING_OLDER)],
    });
    await opened();

    await userEvent.click(
      within(rowsOf()[0]!).getByRole("button", {
        name: `Take version 5 for ${isolatedInText("triage")}`,
      }),
    );

    expect(rowWords()[0]).toContain(`${isolatedInText("Triage")}, version 5`);
    expect(rowWords()[0]).not.toContain(
      `${isolatedInText("Triage")}, version 4`,
    );
    expect(requestsTo(sent)).toEqual([`GET ${ADDRESS}`]);
  });

  it("sends the newer version taken, keeping what fills its inputs", async () => {
    const { sent } = opening(DRAFT, {
      [`GET ${ADDRESS}`]: [reply(PINNING_OLDER)],
      [`PUT ${ADDRESS}/steps`]: [reply(WORKFLOW)],
    });
    await opened();
    await userEvent.click(
      within(rowsOf()[0]!).getByRole("button", {
        name: `Take version 5 for ${isolatedInText("triage")}`,
      }),
    );

    await userEvent.click(saveSteps());

    await waitFor(() =>
      expect(requestsTo(sent)).toEqual([
        `GET ${ADDRESS}`,
        `PUT ${ADDRESS}/steps`,
      ]),
    );
    expect(
      (bodyOf(sent, 1) as { steps: { runs: unknown; bindings: unknown }[] })
        .steps,
    ).toEqual([
      expect.objectContaining({
        runs: { kind: "question", version: NEWER },
        bindings: [{ target: "complaint", source: { input: "complaint" } }],
      }),
    ]);
  });

  it("holds adding a step past the most a version holds, saying why", async () => {
    opening(DRAFT, {
      [`GET ${ADDRESS}`]: [
        reply({
          ...WORKFLOW,
          outputs: [],
          problems: [],
          steps: Array.from({ length: 256 }, (_each, index) => ({
            stepId: `0000000c-0000-4000-8000-${String(index).padStart(12, "0")}`,
            name: `step_${index}`,
            bindings: [],
          })),
        }),
      ],
    });
    await opened();

    const add = screen.getByRole("button", { name: "Add a step" });
    expect(add).toHaveAttribute("aria-disabled", "true");
    expect(add).toHaveAccessibleDescription(
      "A workflow version holds at most 256 steps.",
    );
    add.focus();
    await userEvent.keyboard("{Enter}");
    expect(rowsOf()).toHaveLength(256);
  });

  it("keeps what was typed when somebody saved since, writing nothing and reading nothing afresh unasked", async () => {
    const { sent, written } = await savedSince();

    expect(rowWords()).toHaveLength(2);
    expect(written).not.toHaveBeenCalled();
    expect(requestsTo(sent)).toEqual([
      `GET ${ADDRESS}`,
      `PUT ${ADDRESS}/steps`,
    ]);
  });

  it("says aloud that somebody saved since, with focus on reading afresh, and hands its host the refusal", async () => {
    const { refused } = await savedSince();

    expect(
      screen
        .getByText(
          /Somebody saved this draft since it was read here\. What was typed/,
        )
        .closest('[role="alert"]'),
    ).not.toBeNull();
    expect(
      screen.getByRole("button", { name: "Read it afresh" }),
    ).toHaveFocus();
    expect(
      screen.getByText(
        "Somebody changed this draft since it was read; nothing was saved.",
      ),
    ).toBeInTheDocument();
    expect(refused).toHaveBeenCalledTimes(1);
    expect(refused).toHaveBeenCalledWith(
      expect.objectContaining({ code: "DRAFT_WRITTEN_SINCE_READ" }),
    );
  });

  it("reads afresh when asked, what was typed and the notice both gone", async () => {
    const { sent } = await savedSince();

    await userEvent.click(
      screen.getByRole("button", { name: "Read it afresh" }),
    );

    await waitFor(() =>
      expect(rowWords()).toEqual([
        expect.stringContaining(isolatedInText("their_triage")),
      ]),
    );
    expect(
      screen.queryByText(/Somebody saved this draft since it was read here\./),
    ).toBeNull();
    expect(requestsTo(sent)).toEqual([
      `GET ${ADDRESS}`,
      `PUT ${ADDRESS}/steps`,
      `GET ${ADDRESS}`,
    ]);
  });

  it("says nothing of somebody saving since for a write refused for anything else", async () => {
    opening(DRAFT, {
      [`PUT ${ADDRESS}/ceiling`]: [reply({ code: "CEILING_UNUSABLE" }, 400)],
    });
    await opened();

    await userEvent.clear(screen.getByRole("textbox", { name: "Ceiling" }));
    await userEvent.click(
      screen.getByRole("button", { name: "Save what a run may spend" }),
    );

    expect(
      await screen.findByText(
        "A ceiling is a whole number from one to 9,007,199,254,740,991, written in digits, or none at all.",
        { selector: '[role="alert"] *' },
      ),
    ).toBeInTheDocument();
    expect(screen.queryByText(/Somebody saved this draft/)).toBeNull();
    expect(screen.queryByRole("button", { name: "Read it afresh" })).toBeNull();
  });

  it("stops saying somebody saved since once a write is taken", async () => {
    opening(DRAFT, {
      [`PUT ${ADDRESS}/ceiling`]: [
        reply({ code: "DRAFT_WRITTEN_SINCE_READ" }, 409),
        reply({ ...WORKFLOW, ceiling: "200" }),
      ],
    });
    await opened();
    const ceiling = screen.getByRole("textbox", { name: "Ceiling" });
    const save = screen.getByRole("button", {
      name: "Save what a run may spend",
    });
    await userEvent.clear(ceiling);
    await userEvent.type(ceiling, "200");
    await userEvent.click(save);
    await screen.findByRole("button", { name: "Read it afresh" });

    await userEvent.click(save);

    await waitFor(() =>
      expect(screen.queryByText(/Somebody saved this draft/)).toBeNull(),
    );
    expect(screen.queryByRole("button", { name: "Read it afresh" })).toBeNull();
  });

  it("sends a ceiling with both its settings, naming the revision read", async () => {
    const { sent, written } = opening(DRAFT, {
      [`PUT ${ADDRESS}/ceiling`]: [reply({ ...WORKFLOW, ceiling: "5000" })],
    });
    await opened();

    await userEvent.type(
      within(part("What a run may spend")).getByRole("textbox", {
        name: "Ceiling",
      }),
      "0",
    );
    await userEvent.click(
      screen.getByRole("button", { name: "Save what a run may spend" }),
    );

    await waitFor(() => expect(written).toHaveBeenCalledTimes(1));
    expect(requestsTo(sent)).toEqual([
      `GET ${ADDRESS}`,
      `PUT ${ADDRESS}/ceiling`,
    ]);
    expect(bodyOf(sent, 1)).toEqual({
      revision: 3,
      ceiling: "1000",
      keepsOwnCeiling: false,
      raiseNeedsApproval: false,
    });
  });

  it("holds every other part's save while one write is out, which it would be refused beside", async () => {
    const held = deferred<readonly [string, number]>();
    const { sent } = opening(DRAFT, {
      [`PUT ${ADDRESS}/ceiling`]: [held.promise],
    });
    await opened();
    await userEvent.type(
      within(part("What it takes")).getByRole("textbox", { name: "Name" }),
      "_text",
    );
    await userEvent.type(
      within(part("What it gives back")).getByRole("textbox", { name: "Name" }),
      "_text",
    );
    await userEvent.click(screen.getByRole("button", { name: "Add a step" }));
    await userEvent.type(
      within(panel("Step 2, not named yet")).getByRole("textbox", {
        name: "Name",
      }),
      "tidy",
    );
    await userEvent.click(
      screen.getByRole("switch", { name: "Runs may be helped" }),
    );
    await userEvent.type(screen.getByRole("textbox", { name: "Ceiling" }), "0");
    await userEvent.click(
      screen.getByRole("button", { name: "Save what a run may spend" }),
    );
    const others = [
      "Save what it takes",
      "Save what it gives back",
      SAVE_STEPS,
      "Save whether a run may be helped",
    ].map((name) => screen.getByRole("button", { name }));

    for (const control of others) {
      control.focus();
      await userEvent.keyboard("{Enter}");
    }

    others.forEach((control) =>
      expect(control).toHaveAttribute("aria-disabled", "true"),
    );
    expect(requestsTo(sent)).toEqual([
      `GET ${ADDRESS}`,
      `PUT ${ADDRESS}/ceiling`,
    ]);
    held.settle([
      JSON.stringify({ ...WORKFLOW, revision: 4, ceiling: "1000" }),
      200,
    ]);
    await waitFor(() =>
      others.forEach((control) =>
        expect(control).not.toHaveAttribute("aria-disabled"),
      ),
    );
  });

  it("sends each write the revision the last answer carried, not the one first read", async () => {
    const { sent, written } = opening(DRAFT, {
      [`PUT ${ADDRESS}/ceiling`]: [
        reply({ ...WORKFLOW, revision: 4, ceiling: "1000" }),
      ],
      [`PUT ${ADDRESS}/help`]: [
        reply({ ...WORKFLOW, revision: 5, mayBeHelped: true }),
      ],
    });
    await opened();
    await userEvent.type(screen.getByRole("textbox", { name: "Ceiling" }), "0");
    await userEvent.click(
      screen.getByRole("button", { name: "Save what a run may spend" }),
    );
    await waitFor(() => expect(written).toHaveBeenCalledTimes(1));

    await userEvent.click(
      screen.getByRole("switch", { name: "Runs may be helped" }),
    );
    await userEvent.click(
      screen.getByRole("button", { name: "Save whether a run may be helped" }),
    );

    await waitFor(() =>
      expect(requestsTo(sent)).toContain(`PUT ${ADDRESS}/help`),
    );
    const revisions = sent.mock.calls
      .filter(([, init]) => init?.method === "PUT")
      .map(([, init]) => JSON.parse(String(init?.body)).revision);
    expect(revisions).toEqual([3, 4]);
  });

  /** What is drawn while it is read afresh is the revision being replaced, which a write would be refused over. */
  it("holds every save while the workflow is read afresh, and sends nothing until the read lands", async () => {
    const afresh = deferred<readonly [string, number]>();
    const { sent } = opening(DRAFT, {
      [`GET ${ADDRESS}`]: [reply(WORKFLOW), afresh.promise],
      [`PUT ${ADDRESS}/ceiling`]: [
        reply({ code: "DRAFT_WRITTEN_SINCE_READ" }, 409),
      ],
    });
    await opened();
    await userEvent.type(screen.getByRole("textbox", { name: "Ceiling" }), "0");
    await userEvent.click(
      screen.getByRole("switch", { name: "Runs may be helped" }),
    );
    const held = [
      screen.getByRole("button", { name: "Save what a run may spend" }),
      screen.getByRole("button", { name: "Save whether a run may be helped" }),
    ];
    await userEvent.click(held[0]!);
    await userEvent.click(
      await screen.findByRole("button", { name: "Read it afresh" }),
    );

    for (const control of held) {
      control.focus();
      await userEvent.keyboard("{Enter}");
    }

    held.forEach((control) =>
      expect(control).toHaveAttribute("aria-disabled", "true"),
    );
    expect(requestsTo(sent)).toEqual([
      `GET ${ADDRESS}`,
      `PUT ${ADDRESS}/ceiling`,
      `GET ${ADDRESS}`,
    ]);
    afresh.settle([JSON.stringify({ ...WORKFLOW, revision: 5 }), 200]);
    await waitFor(() =>
      held.forEach((control) =>
        expect(control).not.toHaveAttribute("aria-disabled"),
      ),
    );
  });
});
