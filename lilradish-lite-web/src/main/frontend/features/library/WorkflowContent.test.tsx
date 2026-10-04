import { iconButtonClasses } from "@mui/material/IconButton";
import { ThemeProvider } from "@mui/material/styles";
import {
  act,
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
import { drawnAtDepth, drawnOf } from "../../testutil/depths";
import { laidOutAt } from "../../testutil/layout";
import { noticesIn } from "../../testutil/notices";
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
    searchWays: [],
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

const CLOSING = {
  ...NOTIFYING,
  stepId: "0000000c-0000-4000-8000-000000000cb4",
  name: "close",
  bindings: [],
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
  return name === "The steps"
    ? screen.getByRole("heading", { name, level: 3 }).parentElement!
        .parentElement!
    : screen.getByRole("region", { name });
}

function nameInOpenedField(partName: string): HTMLElement {
  const region = part(partName);
  const closed = region.querySelector<HTMLElement>(
    'fieldset button[aria-expanded="false"]',
  );
  if (closed !== null) {
    fireEvent.click(closed);
  }
  return within(region).getByRole("textbox", { name: "Name" });
}

async function opened(): Promise<void> {
  await screen.findByRole("heading", { name: "The steps", level: 3 });
}

function rowsOf(): HTMLElement[] {
  return within(part("The steps")).getAllByRole("row").slice(1);
}

function rowWords(): string[] {
  return rowsOf().map((row) => row.textContent ?? "");
}

function wordsOf(item: HTMLElement): string | null {
  const words = item.getAttribute("aria-label");
  expect(item.textContent).toBe(
    words?.replaceAll("\u2068", "").replaceAll("\u2069", ""),
  );
  return words;
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
  it.each(["answered", "unchanged", "refused"] as const)(
    "keeps the selected draft during a reread and reconciles it when %s",
    async (outcome) => {
      const afresh = deferred<readonly [string, number]>();
      const { sent, search } = opening(
        DRAFT,
        {
          [`GET ${ADDRESS}`]: [reply(WORKFLOW), afresh.promise],
          [`PUT ${ADDRESS}/steps`]: [
            reply({ code: "DRAFT_WRITTEN_SINCE_READ" }, 409),
          ],
        },
        `?step=${ASKING.stepId}&keep=yes`,
      );
      await opened();
      const name = screen.getByRole("textbox", { name: "Name" });
      await userEvent.type(name, "_draft");
      await userEvent.click(saveSteps());
      const readAfresh = await screen.findByRole("button", {
        name: "Read it afresh",
      });
      const nameAfterWrite = screen.getByRole("textbox", { name: "Name" });
      await userEvent.click(readAfresh);
      expect(screen.getByRole("textbox", { name: "Name" })).toBe(
        nameAfterWrite,
      );
      expect(nameAfterWrite).toHaveValue("triage_draft");
      expect(saveSteps()).toHaveAttribute("aria-disabled", "true");
      saveSteps().focus();
      await userEvent.keyboard("{Enter}");
      expect(requestsTo(sent)).toEqual([
        `GET ${ADDRESS}`,
        `PUT ${ADDRESS}/steps`,
        `GET ${ADDRESS}`,
      ]);
      await act(async () =>
        afresh.settle(
          await (outcome === "refused"
            ? reply({ code: "ENTRY_GONE" }, 404)
            : reply({
                ...WORKFLOW,
                revision: 5,
                steps:
                  outcome === "unchanged"
                    ? WORKFLOW.steps
                    : [{ ...ASKING, tries: 3 }],
              })),
        ),
      );
      if (outcome === "refused") {
        await waitFor(() =>
          expect(screen.queryByRole("textbox", { name: "Name" })).toBeNull(),
        );
        expect(screen.queryByRole("button", { name: SAVE_STEPS })).toBeNull();
        expect(
          screen.queryByRole("region", {
            name: isolatedInText("triage_draft"),
          }),
        ).toBeNull();
        expect(
          screen.getByRole("region", { name: "The steps" }),
        ).toBeInTheDocument();
      } else if (outcome === "unchanged") {
        await waitFor(() =>
          expect(saveSteps()).not.toHaveAttribute("aria-disabled"),
        );
        expect(screen.getByRole("textbox", { name: "Name" })).toHaveValue(
          "triage_draft",
        );
        expect(saveSteps()).toHaveClass("MuiButton-contained");
      } else {
        await waitFor(() =>
          expect(screen.getByRole("textbox", { name: "Name" })).toHaveValue(
            "triage",
          ),
        );
        expect(saveSteps()).not.toHaveClass("MuiButton-contained");
      }
      expect(search().get("keep")).toBe("yes");
      expect(search().get("step")).toBe(ASKING.stepId);
    },
  );

  it("shows a searching step's way, four returned fields and ordinary producer and reviewer", async () => {
    const fields = (["title", "body", "references", "other"] as const).map(
      (name, index) => ({ ...SUMMARY, fieldId: `search-${index}`, name }),
    );
    const search = {
      stepId: "0000000c-0000-4000-8000-000000000cb9",
      name: "find",
      bindings: [],
      runs: {
        kind: "search",
        searchWay: "web",
        gives: fields,
        question: [
          { text: "Find " },
          { partId: "p1", from: { input: "complaint" } },
        ],
        standings: { title: "always", body: "never" },
      },
      producer: { kind: "person" },
      tries: 2,
    };
    opening(READ_ONLY, {
      [`GET ${ADDRESS}`]: [
        reply({
          ...WORKFLOW,
          steps: [ASKING, search],
          problems: [],
          offered: {
            ...WORKFLOW.offered,
            searchWays: [{ name: "web", tool: "web_search", gives: fields }],
          },
        }),
      ],
    });
    await opened();

    const row = within(part("The steps")).getByRole("row", { name: /find/ });
    expect(row).toHaveTextContent("Search");
    expect(row).toHaveTextContent("web");
    expect(row).toHaveTextContent("4 values");
    expect(row).toHaveTextContent("A person");
    expect(row).not.toHaveTextContent("Nothing chosen yet");
  });

  it("shows run settings shut with ceiling and help in one summary while a helper problem stays visible", async () => {
    opening(DRAFT, {
      [`GET ${ADDRESS}`]: [
        reply({
          ...WORKFLOW,
          mayBeHelped: true,
          problems: [{ code: "helper_missing", part: "helper" }],
        }),
      ],
    });
    await opened();

    const settings = part("Run settings");
    const disclosure = within(settings).getByRole("button", {
      name: "Run settings",
    });
    expect(disclosure).toHaveAttribute("aria-expanded", "false");
    expect(settings).toHaveTextContent(
      "At most 100 sent and came back in all · May be helped by a model not chosen yet",
    );
    expect(settings).toHaveTextContent(
      "Runs may be helped, and no model is named to help them.",
    );
    expect(
      within(settings).queryByRole("textbox", { name: "Ceiling" }),
    ).toBeNull();

    const information = within(settings).getByRole("button", {
      name: "What is sent and what comes back, added together. Leave it empty for no ceiling.",
    });
    nameInOpenedField("What it takes").focus();
    await userEvent.keyboard("{ArrowLeft}");
    information.focus();
    expect(information).toHaveFocus();
    expect((await screen.findByRole("tooltip")).textContent).toContain(
      "Leave it empty for no ceiling.",
    );
    await userEvent.click(disclosure);
    expect(
      within(settings).getByRole("textbox", { name: "Ceiling" }),
    ).toBeInTheDocument();
    expect(
      within(settings).getByRole("switch", { name: "Runs may be helped" }),
    ).toBeInTheDocument();
  });

  it.each([
    [
      "no ceiling",
      { ceiling: undefined, mayBeHelped: false },
      "No ceiling · May not be helped",
    ],
    [
      "helper still to be chosen",
      { mayBeHelped: true },
      "At most 100 sent and came back in all · May be helped by a model not chosen yet",
    ],
    [
      "helper and mode chosen",
      { mayBeHelped: true, helper: { model: "general", mode: "research" } },
      "At most 100 sent and came back in all · May be helped by general in research",
    ],
  ])("summarizes run settings with %s", async (_case, changes, summary) => {
    opening(DRAFT, {
      [`GET ${ADDRESS}`]: [reply({ ...WORKFLOW, ...changes })],
    });
    await opened();

    expect(part("Run settings")).toHaveTextContent(summary);
    expect(
      screen.getByRole("button", { name: "Run settings" }),
    ).toHaveAttribute("aria-expanded", "false");
  });

  it("keeps a typed ceiling when run settings are shut and opened again, sending nothing", async () => {
    const { sent } = opening(DRAFT);
    await opened();
    const disclosure = screen.getByRole("button", { name: "Run settings" });
    await userEvent.click(disclosure);
    const ceiling = screen.getByRole("textbox", { name: "Ceiling" });
    await userEvent.type(ceiling, "0");
    await userEvent.click(disclosure);

    expect(disclosure).toHaveAttribute("aria-expanded", "false");
    expect(screen.queryByRole("textbox", { name: "Ceiling" })).toBeNull();
    await userEvent.click(disclosure);
    expect(screen.getByRole("textbox", { name: "Ceiling" })).toHaveValue(
      "1000",
    );
    expect(requestsTo(sent)).toEqual([`GET ${ADDRESS}`]);
  });

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

  it("sets the kind a step runs apart above which one it runs, saying the kind nowhere else", async () => {
    opening(READ_ONLY);
    await opened();

    const runs = within(rowsOf()[0]!).getAllByRole("cell")[2]!;
    const tag = within(runs).getByText("Question");

    expect(tag.nextSibling?.textContent).toBe(
      `${isolatedInText("Triage")}, version 4`,
    );
    expect(runs.textContent?.match(/question/gi)).toEqual(["Question"]);
  });

  it("sets a code step apart above its name alone, and a route by its kind alone", async () => {
    opening(READ_ONLY, {
      [`GET ${ADDRESS}`]: [
        reply({
          ...WORKFLOW,
          steps: [
            {
              ...ASKING,
              runs: { kind: "code_step", codeStep: "tidy_up" },
              producer: { kind: "code" },
              bindings: [],
            },
            {
              ...ASKING,
              stepId: NOTIFYING.stepId,
              name: "choose",
              runs: { kind: "route", gives: [], cases: [] },
              producer: undefined,
              bindings: [],
            },
          ],
        }),
      ],
    });
    await opened();

    const [code, route] = rowsOf().map(
      (row) => within(row).getAllByRole("cell")[2]!,
    );

    expect(within(code!).getByText("Code step").nextSibling?.textContent).toBe(
      "tidy_up",
    );
    expect(code!.textContent).toBe("Code steptidy_up");
    expect(route!.textContent).toBe("Route");
    expect(within(route!).getByText("Route").nextSibling).toBeNull();
  });

  it("says a step that runs nothing yet has nothing chosen, with no kind set apart", async () => {
    opening(DRAFT);
    await opened();
    await userEvent.click(screen.getByRole("button", { name: "Add a step" }));

    const runs = within(rowsOf()[1]!).getAllByRole("cell")[2]!;

    expect(runs.textContent).toBe("Nothing chosen yet");
    expect(runs.querySelector("span")).toBeNull();
  });

  /** The steps are named by the list's own box, not a region of their own, yet framed like every other block. */
  it("frames the steps as a block of the version, as it does what fills what it gives back, under a heading one below the version's", async () => {
    opening(READ_ONLY);
    await opened();

    const steps = within(part("The steps")).getByRole("heading", {
      name: "The steps",
    });
    const framed = [
      steps.parentElement!,
      part("What fills each value it gives back"),
    ];

    expect(steps.tagName).toBe("H3");
    expect(steps.parentElement!.tagName).toBe("DIV");
    expect(framed.map(drawnOf)).toEqual(framed.map(() => drawnAtDepth(2)));
  });

  it("keeps an output's earlier step selected while its field is still to be chosen", async () => {
    opening(DRAFT, {
      [`GET ${ADDRESS}`]: [reply({ ...WORKFLOW, outputs: [] })],
    });
    await opened();
    const output = part("What fills each value it gives back");
    const from = within(output).getByRole("combobox", { name: "From" });

    await userEvent.click(from);
    await userEvent.click(
      screen.getByRole("option", { name: "Step 1: triage" }),
    );
    expect(from).toHaveTextContent("Step 1: triage");
    expect(
      within(output).getByRole("combobox", { name: "Which" }),
    ).toBeInTheDocument();

    await userEvent.click(
      within(output).getByRole("combobox", { name: "Which" }),
    );
    await userEvent.click(screen.getByRole("option", { name: "summary" }));
    expect(within(output).queryByText(/Nothing fills it yet/)).toBeNull();
  });

  it("lists what submitting would refuse by its place, and nothing it would not", async () => {
    opening(READ_ONLY);
    await opened();

    const listed = within(
      screen.getByRole("list", { name: "What does not hold yet" }),
    ).getAllByRole("listitem");
    expect(listed.map(wordsOf)).toEqual([
      `The steps, ${isolatedInText("triage")}: It says nothing yet of how many tries it may make.`,
    ]);
    expect(
      screen.queryByRole("list", { name: "What could be too long to send" }),
    ).toBeNull();
  });

  it("says how many more problems the workflow read counted after its visible list", async () => {
    opening(READ_ONLY, {
      [`GET ${ADDRESS}`]: [reply({ ...WORKFLOW, problemTotal: 4 })],
    });
    await opened();

    expect(
      screen.getByText("3 more things do not hold and are not listed."),
    ).toBeVisible();
    expect(
      within(
        screen.getByRole("list", { name: "What does not hold yet" }),
      ).getAllByRole("listitem"),
    ).toHaveLength(1);
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
    expect(said.map(wordsOf)).toEqual([
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
    expect(said.map(wordsOf)).toEqual([
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

    expect(screen.queryByText("Unsaved")).toBeNull();
    expect(saveSteps()).not.toHaveClass("MuiButton-contained");
    expect(saveSteps()).toHaveAttribute("aria-disabled", "true");
    expect(saveSteps()).toHaveAccessibleDescription(
      "Change a step or what fills what it gives back first.",
    );
    expect(noticesIn(saveSteps().parentElement!)).toEqual([]);
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
    expect(noticesIn(saveSteps().parentElement!)).toEqual([
      {
        severity: "warning",
        words:
          "Step 2, not named yet: A step's name is one to 63 lowercase English letters, digits and underscores, starting with a letter.",
      },
    ]);
    const reason =
      saveSteps().parentElement!.querySelector<HTMLElement>('[role="none"]')!;
    const hint = within(reason).getByText("not named yet");
    expect(getComputedStyle(hint).fontStyle).toBe("italic");
    expect(getComputedStyle(hint).fontWeight).toBe("400");
    expect(getComputedStyle(hint).color).toBe(getComputedStyle(reason).color);
    expect(getComputedStyle(hint).color).not.toBe("rgb(89, 89, 89)");
    expect(within(reason).getByText("Step 2").tagName).toBe("BDI");
    expect(requestsTo(sent)).toEqual([`GET ${ADDRESS}`]);
  });

  it("isolates a named step in a held reason and keeps the complete text description", async () => {
    const { sent } = opening(DRAFT);
    await opened();
    await userEvent.click(screen.getByRole("button", { name: "Add a step" }));
    const name = "Demo Sample <img src=x>";

    fireEvent.change(
      within(panel("Step 2, not named yet")).getByRole("textbox", {
        name: "Name",
      }),
      { target: { value: name } },
    );
    fireEvent.click(saveSteps());

    const reason =
      saveSteps().parentElement!.querySelector<HTMLElement>('[role="none"]')!;
    expect(saveSteps()).toHaveAccessibleDescription(
      `${isolatedInText(name)}: A step's name is one to 63 lowercase English letters, digits and underscores, starting with a letter.`,
    );
    expect(saveSteps()).toHaveAttribute("aria-disabled", "true");
    const stepName = within(reason).getByText(name);
    const why = within(reason).getByText(
      "A step's name is one to 63 lowercase English letters, digits and underscores, starting with a letter.",
    );
    expect(stepName.tagName).toBe("BDI");
    expect(getComputedStyle(why).fontStyle).toBe("italic");
    expect(getComputedStyle(why).fontWeight).toBe("400");
    expect(getComputedStyle(why).color).toBe(getComputedStyle(reason).color);
    expect(getComputedStyle(stepName).fontStyle).not.toBe("italic");
    expect(reason.querySelector("img")).toBeNull();
    expect(within(reason).queryByText("not named yet")).toBeNull();
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
    expect(screen.getByText("Unsaved")).toBeVisible();
    expect(getComputedStyle(screen.getByText("Unsaved")).fontStyle).toBe(
      "italic",
    );
    const added = search().get("step");
    await userEvent.type(
      within(panel("Step 2, not named yet")).getByRole("textbox", {
        name: "Name",
      }),
      "tidy",
    );

    expect(saveSteps()).toHaveClass("MuiButton-contained");
    await userEvent.click(saveSteps());

    await waitFor(() => expect(written).toHaveBeenCalledTimes(1));
    expect(screen.queryByText("Unsaved")).toBeNull();
    expect(saveSteps()).not.toHaveClass("MuiButton-contained");
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
    expect(rowsOf()[1]!.querySelector("a bdi")?.textContent).toBe("tidy");
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

  it("opens the step picked in a panel wider than the usual one", async () => {
    opening(DRAFT, {}, `?step=${ASKING.stepId}`);
    await opened();

    expect(getComputedStyle(panel(isolatedInText("triage"))).width).toBe(
      "464px",
    );
  });

  it("sets the kind the step picked runs apart beside its panel's heading, no part of the name the panel takes from it", async () => {
    opening(READ_ONLY, {}, `?step=${ASKING.stepId}`);
    await opened();

    const picked = panel(isolatedInText("triage"));
    const heading = within(picked).getByRole("heading", { level: 4 });
    const tag = within(picked).getByText("Question");

    expect(heading.nextElementSibling).toBe(tag);
    expect(tag.firstElementChild?.getAttribute("data-testid")).toBe(
      "QuestionAnswerIcon",
    );
    expect(heading).toHaveAccessibleName(isolatedInText("triage"));
  });

  it("sets the kind chosen apart beside the heading of a step added once one is chosen, and none before", async () => {
    opening(DRAFT);
    await opened();
    await userEvent.click(screen.getByRole("button", { name: "Add a step" }));
    const added = panel("Step 2, not named yet");
    const defaultHeading = within(added).getByRole("heading", { level: 4 });
    const hint = within(defaultHeading).getByText("not named yet");
    expect(within(defaultHeading).getByText("Step 2").tagName).toBe("BDI");
    expect(getComputedStyle(hint).fontWeight).toBe("400");
    expect(getComputedStyle(hint).fontStyle).toBe("italic");
    expect(getComputedStyle(hint).fontSize).toBe(
      getComputedStyle(defaultHeading).fontSize,
    );
    expect(getComputedStyle(defaultHeading).fontStyle).not.toBe("italic");
    const headedBefore = within(added).getByRole("heading", {
      level: 4,
    }).parentElement;

    await userEvent.click(
      within(added).getByRole("combobox", { name: "Runs" }),
    );
    await userEvent.click(screen.getByRole("option", { name: "A code step" }));
    const heading = within(added).getByRole("heading", { level: 4 });

    expect(headedBefore).toBe(added);
    expect(heading.parentElement).not.toBe(added);
    expect(heading.nextElementSibling?.textContent).toBe("Code step");
    expect(heading).toHaveAccessibleName("Step 2, not named yet");
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

  /** Words on every row would outweigh the steps; each icon's name still says which step it acts on. */
  it("draws each step's moves and taking it out as an icon alone, named with its step, and only taking it out in the warning colour", async () => {
    opening(DRAFT, {
      [`GET ${ADDRESS}`]: [reply({ ...WORKFLOW, steps: [ASKING, NOTIFYING] })],
    });
    await opened();

    const icons = {
      up: "ArrowUpwardIcon",
      down: "ArrowDownwardIcon",
      out: "DeleteOutlinedIcon",
    } as const;
    const drawn = ["triage", "notify"].flatMap((name) => {
      const step = isolatedInText(name);
      return (
        [
          ["up", `Move up: ${step}`],
          ["down", `Move down: ${step}`],
          ["out", `Take out: ${step}`],
        ] as const
      ).map(([act, words]) => ({
        act,
        button: screen.getByRole("button", { name: words }),
      }));
    });

    expect(drawn.map(({ button }) => button.textContent)).toEqual(
      drawn.map(() => ""),
    );
    expect(
      drawn.map(({ button }) =>
        button.querySelector("svg")?.getAttribute("data-testid"),
      ),
    ).toEqual(drawn.map(({ act }) => icons[act]));
    expect(
      drawn.map(({ button }) =>
        button.querySelector("svg")?.getAttribute("aria-hidden"),
      ),
    ).toEqual(drawn.map(() => "true"));
    expect(
      drawn
        .filter(({ button }) =>
          button.classList.contains(iconButtonClasses.colorWarning),
        )
        .map(({ act }) => act),
    ).toEqual(["out", "out"]);
  });

  it("shows a typed step name in its row", async () => {
    opening(
      DRAFT,
      {
        [`GET ${ADDRESS}`]: [
          reply({ ...WORKFLOW, steps: [ASKING, NOTIFYING] }),
        ],
      },
      `?step=${ASKING.stepId}`,
    );
    await opened();
    within(panel(isolatedInText("triage")))
      .getByRole("textbox", { name: "Name" })
      .focus();
    await userEvent.keyboard("s");

    expect(rowsOf()[0]!.querySelector("a bdi")?.textContent).toBe("triages");
    expect(rowsOf()[1]!.querySelector("a bdi")?.textContent).toBe("notify");
  });

  it("marks only the picked step when another row is opened", async () => {
    opening(
      DRAFT,
      {
        [`GET ${ADDRESS}`]: [
          reply({ ...WORKFLOW, steps: [ASKING, NOTIFYING, CLOSING] }),
        ],
      },
      `?step=${ASKING.stepId}`,
    );
    await opened();
    await userEvent.click(
      screen.getByRole("link", { name: `Open ${isolatedInText("close")}` }),
    );

    expect(panel(isolatedInText("close"))).toBeInTheDocument();
    expect(
      screen.getByRole("link", { name: `Open ${isolatedInText("close")}` }),
    ).toHaveAttribute("aria-current", "true");
    expect(
      screen.getByRole("link", { name: `Open ${isolatedInText("triage")}` }),
    ).not.toHaveAttribute("aria-current");
  });

  it("holds moving the first step up and the last down, and no other move", async () => {
    opening(DRAFT, {
      [`GET ${ADDRESS}`]: [
        reply({ ...WORKFLOW, steps: [ASKING, NOTIFYING, CLOSING] }),
      ],
    });
    await opened();

    const held = (name: string) =>
      screen.getByRole("button", { name }).getAttribute("aria-disabled");
    expect(
      ["triage", "notify", "close"].flatMap((step) => [
        held(`Move up: ${isolatedInText(step)}`),
        held(`Move down: ${isolatedInText(step)}`),
        held(`Take out: ${isolatedInText(step)}`),
      ]),
    ).toEqual(["true", null, null, null, null, null, null, "true", null]);
  });

  it("holds every move and taking out while the steps are being saved, changing nothing, sending nothing more and keeping the step open", async () => {
    const saving = deferred<readonly [string, number]>();
    const { sent, search } = opening(
      DRAFT,
      {
        [`GET ${ADDRESS}`]: [
          reply({ ...WORKFLOW, steps: [ASKING, NOTIFYING, CLOSING] }),
        ],
        [`PUT ${ADDRESS}/steps`]: [saving.promise],
      },
      `?step=${NOTIFYING.stepId}`,
    );
    await opened();
    await userEvent.click(
      screen.getByRole("button", {
        name: `Move down: ${isolatedInText("triage")}`,
      }),
    );
    await userEvent.click(saveSteps());
    const acts = within(part("The steps"))
      .getAllByRole("button")
      .filter((button) =>
        /^(Move (?:up|down)|Take out): /.test(
          button.getAttribute("aria-label") ?? "",
        ),
      );
    const order = rowWords();

    for (const act of acts) {
      act.focus();
      await userEvent.keyboard("{Enter}");
    }

    expect(acts).toHaveLength(9);
    expect(
      acts.filter((act) => act.getAttribute("aria-disabled") !== "true"),
    ).toEqual([]);
    expect(rowWords()).toEqual(order);
    expect(requestsTo(sent)).toEqual([
      `GET ${ADDRESS}`,
      `PUT ${ADDRESS}/steps`,
    ]);
    expect(search().get("step")).toBe(NOTIFYING.stepId);
    expect(panel(isolatedInText("notify"))).toBeInTheDocument();
  });

  /** The icon's name says which step it acts on; the tip needs only say what it does, held or not. */
  it.each([
    ["held", `Move up: ${isolatedInText("triage")}`, "Move up"],
    ["available", `Move down: ${isolatedInText("triage")}`, "Move down"],
    ["warning", `Take out: ${isolatedInText("triage")}`, "Take out"],
  ])(
    "says what an act does as a tip of its own words once pointed at, %s",
    async (_state, name, tip) => {
      opening(DRAFT, {
        [`GET ${ADDRESS}`]: [
          reply({ ...WORKFLOW, steps: [ASKING, NOTIFYING] }),
        ],
      });
      await opened();

      const button = screen.getByRole("button", { name });
      await userEvent.hover(button);

      expect(button).toHaveAttribute("aria-label", name);
      expect(name.startsWith(`${tip}: `)).toBe(true);
      expect((await screen.findByRole("tooltip")).textContent).toBe(tip);
    },
  );

  it("sets each step's order, its heading with it, at the trailing end in digits of one width, and no other column so", async () => {
    opening(DRAFT, {
      [`GET ${ADDRESS}`]: [reply({ ...WORKFLOW, steps: [ASKING, NOTIFYING] })],
    });
    await opened();

    const table = within(part("The steps")).getByRole("table");
    const columnOf = (at: number) =>
      [...table.querySelectorAll("tr")].map((row) => row.cells[at]!);
    const setAs = (cell: HTMLElement) => {
      const drawn = getComputedStyle(cell);
      return `${drawn.textAlign} ${drawn.fontVariantNumeric}`;
    };

    expect(columnOf(0).map((cell) => cell.textContent)).toEqual([
      "Order",
      "1",
      "2",
    ]);
    expect(new Set(columnOf(0).map(setAs))).toEqual(
      new Set(["right tabular-nums"]),
    );
    expect(
      [1, 2, 3, 4, 5]
        .flatMap(columnOf)
        .map(setAs)
        .filter((set) => set.includes("right") || set.includes("tabular")),
    ).toEqual([]);
  });

  it("lets the steps scroll sideways in a box of their own, leaving the block around them as wide as the page gives it", async () => {
    opening(DRAFT);
    await opened();

    const table = within(part("The steps")).getByRole("table");
    const block = within(part("The steps")).getByRole("heading", {
      name: "The steps",
    }).parentElement!.parentElement!;

    expect(getComputedStyle(table.parentElement!).overflowX).toBe("auto");
    expect(table.parentElement!.parentElement).toBe(block);
    expect(getComputedStyle(block).overflowX).not.toBe("auto");
  });

  /** React hands focus back to a focused node it moves, so the press is made with focus elsewhere to see the page's own. */
  it("puts focus on the move pressed once its row has moved, wherever focus was", async () => {
    opening(DRAFT, {
      [`GET ${ADDRESS}`]: [reply({ ...WORKFLOW, steps: [ASKING, NOTIFYING] })],
    });
    await opened();
    const down = screen.getByRole("button", {
      name: `Move down: ${isolatedInText("triage")}`,
    });

    fireEvent.click(down);

    expect(rowsOf()[1]!.querySelector("a bdi")?.textContent).toBe("triage");
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
        name: `Move down: ${isolatedInText("triage")}`,
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
        name: `Take out: ${isolatedInText("triage")}`,
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
        name: `Take out: ${isolatedInText("notify")}`,
      }),
    );

    expect(
      screen.getByRole("link", { name: `Open ${isolatedInText("triage")}` }),
    ).toHaveFocus();
    expect(
      screen.queryByRole("link", { name: `Open ${isolatedInText("notify")}` }),
    ).toBeNull();
  });

  it("puts focus on the next row's step after a middle one taken out, and on no other", async () => {
    opening(DRAFT, {
      [`GET ${ADDRESS}`]: [
        reply({ ...WORKFLOW, steps: [ASKING, NOTIFYING, CLOSING] }),
      ],
    });
    await opened();

    await userEvent.click(
      screen.getByRole("button", {
        name: `Take out: ${isolatedInText("notify")}`,
      }),
    );

    expect(
      screen.getByRole("link", { name: `Open ${isolatedInText("close")}` }),
    ).toHaveFocus();
    expect(
      screen.getByRole("link", { name: `Open ${isolatedInText("triage")}` }),
    ).not.toHaveFocus();
    expect(
      screen.queryByRole("link", { name: `Open ${isolatedInText("notify")}` }),
    ).toBeNull();
  });

  it("shuts the step picked once it is taken out, drops it from the address, and puts focus on the step after it", async () => {
    const { search } = opening(
      DRAFT,
      {
        [`GET ${ADDRESS}`]: [
          reply({ ...WORKFLOW, steps: [ASKING, NOTIFYING, CLOSING] }),
        ],
      },
      `?step=${NOTIFYING.stepId}`,
    );
    await opened();
    const before = panel(isolatedInText("notify"));

    await userEvent.click(
      screen.getByRole("button", {
        name: `Take out: ${isolatedInText("notify")}`,
      }),
    );

    expect(before).not.toBeInTheDocument();
    expect(search().get("step")).toBeNull();
    expect(
      screen.queryByRole("region", { name: isolatedInText("close") }),
    ).toBeNull();
    expect(
      screen.getByRole("link", { name: `Open ${isolatedInText("close")}` }),
    ).toHaveFocus();
  });

  it("puts focus on the steps' heading once the last step is taken out, saying there is none", async () => {
    opening(DRAFT);
    await opened();
    expect(screen.queryByText(NO_STEPS_REFUSED)).toBeNull();

    await userEvent.click(
      screen.getByRole("button", {
        name: `Take out: ${isolatedInText("triage")}`,
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
    const add = await screen.findByText("Add a step", { selector: "button" });
    expect(add).toHaveAccessibleName("Add a step");
    expect(add).toHaveAttribute("aria-disabled", "true");
    expect(add).toHaveAccessibleDescription(
      "A workflow version holds at most 256 steps.",
    );
    expect(noticesIn(add.parentElement!)).toEqual([
      {
        severity: "warning",
        words: "A workflow version holds at most 256 steps.",
      },
    ]);
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
      expect(
        rowsOf().map((row) => row.querySelector("a bdi")?.textContent),
      ).toEqual(["their_triage"]),
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
    await userEvent.click(screen.getByRole("button", { name: "Run settings" }));

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
    await userEvent.click(screen.getByRole("button", { name: "Run settings" }));
    expect(
      screen.getByText(
        "A ceiling is a whole number from one to 9,007,199,254,740,991, written in digits, or none at all.",
        { selector: '[role="alert"] *' },
      ),
    ).toBeInTheDocument();
    expect(screen.queryByRole("textbox", { name: "Ceiling" })).toBeNull();
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
    await userEvent.click(screen.getByRole("button", { name: "Run settings" }));
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
    await userEvent.click(screen.getByRole("button", { name: "Run settings" }));

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
    await userEvent.click(screen.getByRole("button", { name: "Run settings" }));
    await userEvent.type(nameInOpenedField("What it takes"), "_text");
    await userEvent.type(nameInOpenedField("What it gives back"), "_text");
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
    await userEvent.click(screen.getByRole("button", { name: "Run settings" }));
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

  it("keeps an unsaved step when another part is saved and its pin description changes", async () => {
    const { sent, written } = opening(DRAFT, {
      [`GET ${ADDRESS}`]: [reply(PINNING_OLDER)],
      [`PUT ${ADDRESS}/ceiling`]: [
        reply({
          ...PINNING_OLDER,
          revision: 4,
          ceiling: "200",
          steps: [ASKING],
        }),
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
    await userEvent.click(screen.getByRole("button", { name: "Run settings" }));
    const ceiling = screen.getByRole("textbox", { name: "Ceiling" });
    await userEvent.clear(ceiling);
    await userEvent.type(ceiling, "200");
    await userEvent.click(
      screen.getByRole("button", { name: "Save what a run may spend" }),
    );

    await waitFor(() => expect(written).toHaveBeenCalledTimes(1));
    expect(
      within(panel(isolatedInText("tidy"))).getByRole("textbox", {
        name: "Name",
      }),
    ).toHaveValue("tidy");
    expect(saveSteps()).toHaveClass("MuiButton-contained");
    expect(requestsTo(sent)).toEqual([
      `GET ${ADDRESS}`,
      `PUT ${ADDRESS}/ceiling`,
    ]);
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
    await userEvent.click(screen.getByRole("button", { name: "Run settings" }));
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
