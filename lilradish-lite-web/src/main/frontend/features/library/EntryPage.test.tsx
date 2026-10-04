import { buttonClasses } from "@mui/material/Button";
import { ThemeProvider } from "@mui/material/styles";
import {
  act,
  cleanup,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import { userEvent } from "@testing-library/user-event";
import { RouterProvider, createMemoryRouter } from "react-router";
import { describe, expect, it, vi } from "vitest";

import { StandingProvider } from "../../app/standing/StandingContext";
import { theme } from "../../lib/theme/theme";
import { requestsTo, serving, type Reply } from "../../testutil/answering";
import { drawnAtDepth, drawnOf } from "../../testutil/depths";
import { laidOutAt } from "../../testutil/layout";
import { answered, inGroup } from "../../testutil/standingRead";
import { deferred } from "../../testutil/deferred";
import { JUST_MADE } from "./entryAddress";
import { EntryPage } from "./EntryPage";
import { LIBRARY_KINDS } from "./libraryKinds";

// Editing's rule moved to one it never takes, which the editing case below reads back.
vi.mock("./actRules", async (importOriginal) => {
  const actual = await importOriginal<typeof import("./actRules")>();
  return {
    ...actual,
    ENTRY_ACT_RULES: { ...actual.ENTRY_ACT_RULES, rename: "approve_entry" },
  };
});

const GROUP = "00000003-0000-4000-8000-000000000b51";

const ENTRY_ID = "00000006-0000-4000-8000-000000000b51";

const PAGE = `/groups/${GROUP}/questions/${ENTRY_ID}`;

const ENTRY = `/api/groups/${GROUP}/questions/${ENTRY_ID}`;

const AVO = { userId: "000b51", displayName: "Avo Lumerace" };

const GLINN = { userId: "000b52", displayName: "Glinn Havven" };

const HANDLE = "00000006-0000-4000-8000-000000000b59";

const HANDLING = "00000007-0000-4000-8000-000000000b59";

const IN_SERVICE = {
  versionId: "00000007-0000-4000-8000-000000000b52",
  number: 2,
  standing: "in_service",
  writers: [AVO],
  writtenByMigration: false,
  approval: { approver: GLINN },
  acts: ["retire"],
  pinnedBy: [
    {
      entryId: HANDLE,
      kind: "workflow",
      name: "Handle",
      versionId: HANDLING,
      number: 4,
    },
  ],
};

const SEEDED = {
  versionId: "00000007-0000-4000-8000-000000000b51",
  number: 1,
  standing: "in_service",
  writers: [],
  writtenByMigration: true,
  approval: {},
  acts: [],
  pinnedBy: [],
};

const DRAFT = {
  versionId: "00000007-0000-4000-8000-000000000b53",
  number: 3,
  standing: "draft",
  writers: [GLINN],
  writtenByMigration: false,
  acts: ["write", "submit"],
  pinnedBy: [],
};

const SUBMITTED = {
  ...DRAFT,
  standing: "submitted",
  acts: ["withdraw", "approve"],
};

const TRIAGE = {
  entryId: ENTRY_ID,
  kind: "question",
  name: "Triage",
  purpose: "Sorts claims.",
  acts: ["start_draft", "rename", "stop"],
  versions: [IN_SERVICE, SEEDED],
};

const DRAFTED = {
  ...TRIAGE,
  acts: ["rename", "stop"],
  versions: [DRAFT, IN_SERVICE, SEEDED],
};

function reply(entry: object, status = 200): Reply {
  return [JSON.stringify(entry), status];
}

const QUESTION = {
  revision: 1,
  instruction: "Say which category the claim falls under.",
  takes: [],
  gives: [],
  lists: [],
};

const TERM = {
  termId: "0000000a-0000-4000-8000-000000000b81",
  term: "Billing",
  meaning: "Money taken wrongly.",
};

const COMPLAINT = {
  fieldId: "0000000b-0000-4000-8000-000000000b81",
  name: "complaint",
  kind: "text",
  longest: 4000,
  many: false,
  mustBeGiven: true,
};

const SUMMARY = {
  ...COMPLAINT,
  fieldId: "0000000b-0000-4000-8000-000000000b82",
  name: "summary",
};

const READY_SUMMARY = { ...SUMMARY, stands: "always" };

const READY_QUESTION = { ...QUESTION, gives: [READY_SUMMARY] };

const ASKING = {
  stepId: "0000000c-0000-4000-8000-000000000b81",
  name: "triage",
  runs: {
    kind: "question",
    version: {
      name: "Triage",
      versionId: IN_SERVICE.versionId,
      number: 2,
      standing: "in_service",
    },
    takes: [COMPLAINT],
    gives: [SUMMARY],
  },
  producer: { kind: "person" },
  tries: 2,
  bindings: [
    {
      bindingId: "0000000e-0000-4000-8000-000000000b81",
      target: "complaint",
      source: { input: "complaint" },
    },
  ],
};

/** A draft holding two fields of one name inside another, and a field of fields holding none. */
const HELD_QUESTION = {
  ...QUESTION,
  takes: [
    {
      fieldId: "0000000b-0000-4000-8000-000000000b7f",
      name: "sender",
      kind: "fields",
      many: false,
      mustBeGiven: true,
      fields: [],
    },
  ],
  gives: [
    {
      fieldId: "0000000b-0000-4000-8000-000000000b70",
      name: "details",
      kind: "fields",
      many: false,
      mustBeGiven: true,
      stands: "always",
      fields: [
        {
          fieldId: "0000000b-0000-4000-8000-000000000b71",
          name: "product",
          kind: "text",
          many: false,
          mustBeGiven: true,
        },
        {
          fieldId: "0000000b-0000-4000-8000-000000000b72",
          name: "product",
          kind: "text",
          longest: 64,
          many: false,
          mustBeGiven: false,
        },
      ],
    },
  ],
};

const WORKFLOW = {
  revision: 1,
  takes: [COMPLAINT],
  gives: [READY_SUMMARY],
  steps: [
    {
      ...ASKING,
      name: "Demo Sample",
      runs: { ...ASKING.runs, gives: [READY_SUMMARY] },
    },
  ],
  outputs: [
    {
      bindingId: "0000000e-0000-4000-8000-000000000b82",
      target: "summary",
      source: { step: 0, path: "summary" },
    },
  ],
  keepsOwnCeiling: false,
  raiseNeedsApproval: false,
  mayBeHelped: false,
  problems: [],
  sendsPast: [],
  offered: {
    lists: [],
    questions: [
      { ...ASKING.runs.version, takes: [COMPLAINT], gives: [READY_SUMMARY] },
    ],
    workflows: [],
    codeSteps: [],
    searchWays: [],
    models: [],
  },
  terms: {},
};

const CONTENT_READS = Object.fromEntries(
  [IN_SERVICE, SEEDED, DRAFT].map((version) => [
    `GET ${ENTRY}/versions/${version.versionId}`,
    [reply(QUESTION)],
  ]),
);

/** What the server says when it refuses. */
function refusal(code: string, status: number, more: object = {}): Reply {
  return [JSON.stringify({ code, ...more }), status];
}

/**
 * The page at an address, under a real router whose location the test reads,
 * with the standing it would be handed — whose reading again the test counts —
 * and only the server stood in for.
 */
function opening(
  address: string,
  routes: Readonly<Record<string, readonly Reply[]>> = {},
  state?: unknown,
  of: keyof typeof LIBRARY_KINDS = "question",
) {
  const sent = serving({
    [`GET ${ENTRY}`]: [reply(TRIAGE)],
    ...CONTENT_READS,
    ...routes,
  });
  const { pathname, search } = new URL(address, "http://reader.test");
  const router = createMemoryRouter(
    [
      {
        path: "/groups/:groupId/:segment/:entryId",
        element: <EntryPage of={LIBRARY_KINDS[of]} />,
      },
    ],
    { initialEntries: [{ pathname, search, state }] },
  );
  const readAgain = vi.fn();
  const standing = answered(
    [],
    [inGroup(GROUP, "CLAIMS", "Claims", ["read_membership"])],
  );
  render(
    <ThemeProvider theme={theme}>
      <StandingProvider read={{ ...standing, reload: readAgain }}>
        <RouterProvider router={router} />
      </StandingProvider>
    </ThemeProvider>,
  );
  return {
    sent,
    readAgain,
    at: () =>
      `${router.state.location.pathname}${router.state.location.search}`,
    async go(path: string) {
      await act(async () => router.navigate(path));
    },
  };
}

async function opened(): Promise<HTMLElement> {
  return screen.findByRole("heading", { level: 1, name: /Triage/ });
}

function versions(): HTMLElement {
  return screen.getByRole("region", { name: "Versions" });
}

function openVersionRegion(): HTMLElement {
  return screen.getByRole("region", { name: /^Version \d+$/ });
}

async function confirmRetire() {
  await userEvent.click(
    within(openVersionRegion()).getByRole("button", { name: "Retire" }),
  );
  const dialog = screen.getByRole("dialog", { name: "Retire version 2?" });
  expect(dialog).toHaveTextContent(
    "Nothing new will be offered it, and whatever already names it goes on as it is. It cannot be put back in service.",
  );
  await userEvent.click(within(dialog).getByRole("button", { name: "Retire" }));
}

function versionActionButtons(): HTMLElement[] {
  const heading = within(openVersionRegion()).getByRole("heading", {
    name: /^Version \d+$/,
  });
  const line = heading.parentElement!;
  return line.tagName === "DIV" ? within(line).queryAllByRole("button") : [];
}

function theVersion(number: number): HTMLElement {
  return screen.getByRole("region", { name: `Version ${number}` });
}

function actsDrawn(): string[] {
  return versionActionButtons().map((each) => each.textContent ?? "");
}

/** Every version row's cells, in the order drawn. */
function versionRows(): string[][] {
  const [, ...rows] = within(
    within(versions()).getByRole("table", { name: "Versions" }),
  ).getAllByRole<HTMLTableRowElement>("row");
  return rows.map((row) =>
    [...row.cells].map((cell) => cell.textContent ?? ""),
  );
}

function picked(): string {
  return (
    within(versions())
      .getAllByRole("link")
      .find((each) => each.getAttribute("aria-current") === "true")
      ?.textContent ?? ""
  );
}

function entryReads(sent: ReturnType<typeof serving>): number {
  return requestsTo(sent).filter((each) => each === `GET ${ENTRY}`).length;
}

function setApart(words: string): string {
  return `${String.fromCodePoint(0x2068)}${words}${String.fromCodePoint(0x2069)}`;
}

describe("EntryPage", () => {
  it.each(["empty", "refused"] as const)(
    "keeps workflow version metadata and controls through loading and %s content",
    async (outcome) => {
      laidOutAt(1400, 1400);
      const address = `/api/groups/${GROUP}/workflows/${ENTRY_ID}`;
      const content = deferred<readonly [string, number]>();
      const { sent } = opening(
        `/groups/${GROUP}/workflows/${ENTRY_ID}`,
        {
          [`GET ${address}`]: [reply({ ...DRAFTED, kind: "workflow" })],
          [`GET ${address}/versions/${DRAFT.versionId}`]: [content.promise],
        },
        undefined,
        "workflow",
      );
      await opened();
      const document = openVersionRegion();
      const title = within(document).getByRole("heading", {
        name: "Version 3",
      });
      const submit = within(document).getByRole("button", { name: "Submit" });
      expect(document).toHaveTextContent("Still reading…");
      expect(document).toHaveTextContent("Nothing in service pins it.");
      expect(submit).toHaveAttribute("aria-disabled", "true");
      await act(async () =>
        content.settle(
          await (outcome === "refused"
            ? refusal("ENTRY_GONE", 404)
            : reply({
                ...WORKFLOW,
                takes: [],
                gives: [],
                steps: [],
                outputs: [],
              })),
        ),
      );
      if (outcome === "empty")
        await within(document).findByText("No step yet.");
      else
        await waitFor(() =>
          expect(document).not.toHaveTextContent("Still reading…"),
        );
      expect(openVersionRegion()).toBe(document);
      expect(within(document).getByRole("heading", { name: "Version 3" })).toBe(
        title,
      );
      expect(within(document).getByRole("button", { name: "Submit" })).toBe(
        submit,
      );
      expect(document).toHaveTextContent("Nothing in service pins it.");
      if (outcome === "refused") {
        expect(submit).toHaveAttribute("aria-disabled", "true");
        submit.focus();
        await userEvent.keyboard("{Enter}");
        expect(
          requestsTo(sent).some((request) => request.endsWith("/submission")),
        ).toBe(false);
      } else {
        await waitFor(() =>
          expect(submit).not.toHaveAttribute("aria-disabled", "true"),
        );
      }
      expect(screen.queryByRole("textbox", { name: "Name" })).toBeNull();
    },
  );

  it("keeps one step editor outside the version card through measured changes and reports its unsaved draft", async () => {
    let width = 1132;
    const observers = new Set<{ deliver: () => void }>();
    const measured = (target: Element) =>
      target.querySelector(
        ':scope > [role="region"][aria-label="The steps"]',
      ) === null
        ? 0
        : width;
    vi.spyOn(Element.prototype, "getBoundingClientRect").mockImplementation(
      function (this: Element) {
        return new DOMRect(0, 0, measured(this), 0);
      },
    );
    class Observing implements ResizeObserver {
      private target: Element | null = null;
      constructor(private readonly notify: ResizeObserverCallback) {}
      observe(target: Element) {
        this.target = target;
        observers.add(this);
        queueMicrotask(() => this.deliver());
      }
      unobserve() {
        this.target = null;
      }
      disconnect() {
        observers.delete(this);
        this.target = null;
      }
      deliver() {
        if (this.target !== null)
          this.notify(
            [
              {
                target: this.target,
                contentRect: new DOMRect(0, 0, measured(this.target), 0),
                borderBoxSize: [
                  { inlineSize: measured(this.target), blockSize: 0 },
                ],
                contentBoxSize: [
                  { inlineSize: measured(this.target), blockSize: 0 },
                ],
                devicePixelContentBoxSize: [
                  { inlineSize: measured(this.target), blockSize: 0 },
                ],
              },
            ],
            this,
          );
      }
    }
    vi.stubGlobal("ResizeObserver", Observing);
    const address = `/api/groups/${GROUP}/workflows/${ENTRY_ID}`;
    const { at } = opening(
      `/groups/${GROUP}/workflows/${ENTRY_ID}?step=${ASKING.stepId}&keep=yes`,
      {
        [`GET ${address}`]: [reply({ ...DRAFTED, kind: "workflow" })],
        [`GET ${address}/versions/${DRAFT.versionId}`]: [reply(WORKFLOW)],
      },
      undefined,
      "workflow",
    );
    const input = await screen.findByRole("textbox", { name: "Name" });
    const document = openVersionRegion();
    const panel = screen.getByRole("region", { name: setApart("Demo Sample") });
    expect(document).not.toContainElement(panel);
    expect(document.parentElement!.parentElement).toContainElement(panel);
    expect(getComputedStyle(document).backgroundColor).toBe(
      getComputedStyle(panel).backgroundColor,
    );
    expect(screen.getAllByRole("textbox", { name: "Name" })).toHaveLength(1);
    const submit = within(document).getByRole("button", { name: "Submit" });
    await waitFor(() =>
      expect(submit).not.toHaveAttribute("aria-disabled", "true"),
    );
    await userEvent.type(input, " changed");
    expect(submit).toHaveAttribute("aria-disabled", "true");
    expect(at()).toContain("keep=yes");
    await act(async () => {
      width = 1108;
      observers.forEach((observer) => observer.deliver());
    });
    expect(
      screen.getByRole("region", { name: setApart("Demo Sample changed") }),
    ).toContainElement(input);
    await act(async () => {
      width = 1107;
      observers.forEach((observer) => observer.deliver());
    });
    const dialog = await screen.findByRole("dialog", {
      name: setApart("Demo Sample changed"),
    });
    expect(dialog).toContainElement(input);
    expect(input).toHaveFocus();
    expect(screen.getAllByRole("textbox", { name: "Name" })).toHaveLength(1);
    await act(async () => {
      width = 1131;
      observers.forEach((observer) => observer.deliver());
    });
    expect(
      screen.getByRole("dialog", { name: setApart("Demo Sample changed") }),
    ).toContainElement(input);
    await act(async () => {
      width = 1132;
      observers.forEach((observer) => observer.deliver());
    });
    await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
    expect(openVersionRegion()).toBe(document);
    expect(screen.getByRole("textbox", { name: "Name" })).toBe(input);
    expect(input).toHaveFocus();
    expect(submit).toHaveAttribute("aria-disabled", "true");
  });

  it.each([
    ["question", "questions", READY_QUESTION],
    [
      "workflow",
      "workflows",
      {
        revision: 1,
        takes: [COMPLAINT],
        gives: [SUMMARY],
        steps: [
          { ...ASKING, runs: { ...ASKING.runs, gives: [READY_SUMMARY] } },
        ],
        outputs: [
          {
            bindingId: "0000000e-0000-4000-8000-000000000b82",
            target: "summary",
            source: { step: 0, path: "summary" },
          },
        ],
        keepsOwnCeiling: false,
        raiseNeedsApproval: false,
        mayBeHelped: false,
        problems: [],
        sendsPast: [],
        offered: {
          lists: [],
          questions: [
            {
              ...ASKING.runs.version,
              takes: [COMPLAINT],
              gives: [READY_SUMMARY],
            },
          ],
          workflows: [],
          codeSteps: [],
          searchWays: [],
          models: [],
        },
        terms: {},
      },
    ],
    ["reference_list", "reference-lists", { revision: 1, terms: [TERM] }],
  ] as const)(
    "allows a fully read %s draft to be submitted once",
    async (kind, segment, content) => {
      if (kind === "workflow") {
        laidOutAt(1400, 1400);
      }
      const address = `/api/groups/${GROUP}/${segment}/${ENTRY_ID}`;
      const { sent } = opening(
        `/groups/${GROUP}/${segment}/${ENTRY_ID}`,
        {
          [`GET ${address}`]: [reply({ ...DRAFTED, kind })],
          [`GET ${address}/versions/${DRAFT.versionId}`]: [reply(content)],
          [`PUT ${address}/versions/${DRAFT.versionId}/submission`]: [
            reply({
              ...DRAFTED,
              kind,
              versions: [SUBMITTED, IN_SERVICE, SEEDED],
            }),
          ],
        },
        undefined,
        kind,
      );
      await opened();
      const submitControl = within(openVersionRegion()).getByRole("button", {
        name: "Submit",
      });
      await waitFor(() =>
        expect(submitControl).not.toHaveAttribute("aria-disabled", "true"),
      );
      await userEvent.click(submitControl);

      await waitFor(() =>
        expect(
          requestsTo(sent).filter(
            (request) =>
              request ===
              `PUT ${address}/versions/${DRAFT.versionId}/submission`,
          ),
        ).toHaveLength(1),
      );
      expect(
        within(openVersionRegion()).queryByRole("button", { name: "Submit" }),
      ).toBeNull();
    },
  );

  it("holds submission while a part is unsaved or saving, then submits the saved version once", async () => {
    const saving = deferred<readonly [string, number]>();
    const { sent } = opening(PAGE, {
      [`GET ${ENTRY}`]: [reply(DRAFTED)],
      [`GET ${ENTRY}/versions/${DRAFT.versionId}`]: [reply(READY_QUESTION)],
      [`PUT ${ENTRY}/versions/${DRAFT.versionId}/instruction`]: [
        saving.promise,
      ],
      [`PUT ${ENTRY}/versions/${DRAFT.versionId}/submission`]: [
        reply({ ...DRAFTED, versions: [SUBMITTED, IN_SERVICE, SEEDED] }),
      ],
    });
    await opened();
    const instruction = await within(theVersion(3)).findByRole("textbox", {
      name: "The instruction",
    });
    const submitControl = within(openVersionRegion()).getByRole("button", {
      name: "Submit",
    });
    expect(submitControl).not.toHaveAttribute("aria-disabled", "true");

    await userEvent.type(instruction, " Further detail.");
    expect(submitControl).toHaveAttribute("aria-disabled", "true");
    submitControl.focus();
    await userEvent.keyboard("{Enter}");
    expect(requestsTo(sent)).not.toContain(
      `PUT ${ENTRY}/versions/${DRAFT.versionId}/submission`,
    );

    await userEvent.click(
      within(theVersion(3)).getByRole("button", {
        name: "Save the instruction",
      }),
    );
    expect(submitControl).toHaveAttribute("aria-disabled", "true");
    saving.settle([
      JSON.stringify({
        ...READY_QUESTION,
        revision: 2,
        instruction: " Further detail.",
      }),
      200,
    ]);
    await waitFor(() =>
      expect(submitControl).not.toHaveAttribute("aria-disabled", "true"),
    );
    await userEvent.click(submitControl);
    await waitFor(() =>
      expect(
        requestsTo(sent).filter((request) => request.endsWith("/submission")),
      ).toHaveLength(1),
    );
    expect(
      within(openVersionRegion()).queryByRole("button", { name: "Submit" }),
    ).toBeNull();
  });

  it("holds a reference list's submission through a note edit and its write", async () => {
    const address = `/api/groups/${GROUP}/reference-lists/${ENTRY_ID}`;
    const note = { revision: 1, note: "Pick the narrowest.", terms: [TERM] };
    const saving = deferred<readonly [string, number]>();
    const { sent } = opening(
      `/groups/${GROUP}/reference-lists/${ENTRY_ID}`,
      {
        [`GET ${address}`]: [reply({ ...DRAFTED, kind: "reference_list" })],
        [`GET ${address}/versions/${DRAFT.versionId}`]: [reply(note)],
        [`PUT ${address}/versions/${DRAFT.versionId}/note`]: [saving.promise],
        [`PUT ${address}/versions/${DRAFT.versionId}/submission`]: [
          reply({
            ...DRAFTED,
            kind: "reference_list",
            versions: [SUBMITTED, IN_SERVICE, SEEDED],
          }),
        ],
      },
      undefined,
      "reference_list",
    );
    await opened();
    const submit = within(openVersionRegion()).getByRole("button", {
      name: "Submit",
    });
    await waitFor(() =>
      expect(submit).not.toHaveAttribute("aria-disabled", "true"),
    );
    const box = await screen.findByRole("textbox", { name: "The note" });
    await userEvent.type(box, " Again.");
    expect(submit).toHaveAttribute("aria-disabled", "true");
    await userEvent.click(
      screen.getByRole("button", { name: "Save the note" }),
    );
    expect(submit).toHaveAttribute("aria-disabled", "true");
    expect(requestsTo(sent)).not.toContain(
      `PUT ${address}/versions/${DRAFT.versionId}/submission`,
    );
    saving.settle([
      JSON.stringify({
        ...note,
        revision: 2,
        note: "Pick the narrowest. Again.",
      }),
      200,
    ]);
    await waitFor(() =>
      expect(submit).not.toHaveAttribute("aria-disabled", "true"),
    );
    await userEvent.click(submit);
    await waitFor(() =>
      expect(
        requestsTo(sent).filter((request) => request.endsWith("/submission")),
      ).toHaveLength(1),
    );
  });

  it("holds a workflow's submission through a setting edit and its write", async () => {
    laidOutAt(1400, 1400);
    const address = `/api/groups/${GROUP}/workflows/${ENTRY_ID}`;
    const content = {
      revision: 1,
      takes: [COMPLAINT],
      gives: [SUMMARY],
      steps: [{ ...ASKING, runs: { ...ASKING.runs, gives: [READY_SUMMARY] } }],
      outputs: [
        {
          bindingId: "0000000e-0000-4000-8000-000000000b82",
          target: "summary",
          source: { step: 0, path: "summary" },
        },
      ],
      keepsOwnCeiling: false,
      raiseNeedsApproval: false,
      mayBeHelped: false,
      problems: [],
      sendsPast: [],
      offered: {
        lists: [],
        questions: [
          {
            ...ASKING.runs.version,
            takes: [COMPLAINT],
            gives: [READY_SUMMARY],
          },
        ],
        workflows: [],
        codeSteps: [],
        searchWays: [],
        models: [],
      },
      terms: {},
    };
    const saving = deferred<readonly [string, number]>();
    const { sent } = opening(
      `/groups/${GROUP}/workflows/${ENTRY_ID}`,
      {
        [`GET ${address}`]: [reply({ ...DRAFTED, kind: "workflow" })],
        [`GET ${address}/versions/${DRAFT.versionId}`]: [reply(content)],
        [`PUT ${address}/versions/${DRAFT.versionId}/help`]: [saving.promise],
        [`PUT ${address}/versions/${DRAFT.versionId}/submission`]: [
          reply({
            ...DRAFTED,
            kind: "workflow",
            versions: [SUBMITTED, IN_SERVICE, SEEDED],
          }),
        ],
      },
      undefined,
      "workflow",
    );
    await opened();
    const submit = within(openVersionRegion()).getByRole("button", {
      name: "Submit",
    });
    await waitFor(() =>
      expect(submit).not.toHaveAttribute("aria-disabled", "true"),
    );
    await userEvent.click(screen.getByRole("button", { name: "Run settings" }));
    await userEvent.click(
      screen.getByRole("switch", { name: "Runs may be helped" }),
    );
    expect(submit).toHaveAttribute("aria-disabled", "true");
    await userEvent.click(
      screen.getByRole("button", { name: "Save whether a run may be helped" }),
    );
    expect(submit).toHaveAttribute("aria-disabled", "true");
    expect(requestsTo(sent)).not.toContain(
      `PUT ${address}/versions/${DRAFT.versionId}/submission`,
    );
    saving.settle([
      JSON.stringify({ ...content, revision: 2, mayBeHelped: true }),
      200,
    ]);
    await waitFor(() =>
      expect(submit).not.toHaveAttribute("aria-disabled", "true"),
    );
    await userEvent.click(submit);
    await waitFor(() =>
      expect(
        requestsTo(sent).filter((request) => request.endsWith("/submission")),
      ).toHaveLength(1),
    );
  });

  it("heads the page with its name, a kind tag and quiet purpose, without a stop line while running", async () => {
    opening(PAGE);

    const heading = await opened();
    expect(heading).toHaveTextContent("Triage");
    expect(heading.parentElement).toHaveTextContent("Question");
    expect(screen.getByText("Sorts claims.")).toHaveStyle({
      color: theme.palette.text.secondary,
    });
    expect(screen.queryByText(/^Stopped by /)).toBeNull();
  });

  it("shows who stopped a stopped entry and when in warning colour, with its empty purpose", async () => {
    const { purpose: _said, ...unsaid } = TRIAGE;
    opening(PAGE, {
      [`GET ${ENTRY}`]: [
        reply({
          ...unsaid,
          stopped: { by: AVO, at: "2026-09-24T08:15:00Z" },
          acts: ["let_go"],
        }),
      ],
    });
    await opened();

    expect(
      screen.getByText("It says nothing of what it is for."),
    ).toBeVisible();
    const stopped = screen.getByText(/^Stopped by /);
    expect(stopped).toHaveTextContent(
      new RegExp(`^Stopped by ${setApart("Avo Lumerace")} · .+$`),
    );
    expect(stopped).toHaveStyle({ color: theme.palette.warning.main });
    expect(
      screen.getByRole("button", { name: "Let it be used again" }),
    ).toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: "Stop it being used" }),
    ).toBeNull();
  });

  it("describes a stopper whose name can no longer be found", async () => {
    opening(PAGE, {
      [`GET ${ENTRY}`]: [
        reply({
          ...TRIAGE,
          stopped: { by: { userId: AVO.userId }, at: "2026-09-24T08:15:00Z" },
          acts: ["let_go"],
        }),
      ],
    });
    await opened();

    const stopped = screen.getByText(/^Stopped by /);
    expect(stopped).toHaveTextContent(
      /^Stopped by somebody who can no longer be found · .+$/,
    );
    expect(stopped).not.toHaveTextContent(AVO.userId);
    expect(stopped).toHaveStyle({ color: theme.palette.warning.main });
  });

  it.each([
    [
      "every act on the entry",
      ["start_draft", "rename", "stop"],
      ["Stop it being used", "Edit", "New draft"],
    ],
    ["none of them", [], []],
  ])(
    "draws the controls on the entry the server says the reader may use: %s",
    async (_case, entryActs, drawn) => {
      opening(PAGE, {
        [`GET ${ENTRY}`]: [reply({ ...TRIAGE, acts: entryActs })],
      });
      await opened();

      expect(
        [
          "Stop it being used",
          "Let it be used again",
          "Edit",
          "New draft",
        ].filter((name) => screen.queryByRole("button", { name }) !== null),
      ).toEqual(drawn);
    },
  );

  /** Stopping the entry and retiring a version take something out of use; every other act, undoing one included, does not. */
  it.each([
    [
      "an entry that may be stopped, its version in service",
      TRIAGE,
      ["Stop it being used", "Retire"],
      ["Edit", "New draft"],
    ],
    [
      "a stopped entry, its version submitted",
      {
        ...DRAFTED,
        stopped: { by: AVO, at: "2026-09-24T08:15:00Z" },
        acts: ["let_go", "rename"],
        versions: [SUBMITTED, SEEDED],
      },
      [],
      ["Let it be used again", "Edit", "Withdraw", "Approve"],
    ],
    ["a draft", DRAFTED, ["Stop it being used"], ["Edit", "Submit"]],
  ])(
    "draws only what stops or takes out of use in the warning colour, on %s",
    async (_case, entry, warning, plain) => {
      opening(PAGE, { [`GET ${ENTRY}`]: [reply(entry)] });
      await opened();

      const entryButtons = screen
        .getAllByRole("button")
        .filter((each) =>
          [
            "Stop it being used",
            "Let it be used again",
            "Edit",
            "New draft",
          ].includes(each.textContent ?? ""),
        );
      const drawn = [...entryButtons, ...versionActionButtons()];

      expect(
        drawn
          .filter((each) => each.classList.contains(buttonClasses.colorWarning))
          .map((each) => each.textContent),
      ).toEqual(warning);
      expect(
        drawn
          .filter((each) => each.classList.contains(buttonClasses.colorPrimary))
          .map((each) => each.textContent),
      ).toEqual(plain);
    },
  );

  /** A version a migration put in service says nobody approved it, never as though somebody had. */
  it("tables the versions newest first, each with its standing, who wrote it and who approved it, the newest open", async () => {
    opening(PAGE);
    await opened();

    expect(versionRows()).toEqual([
      ["Version 2", "In service", setApart("Avo Lumerace"), "Glinn Havven"],
      [
        "Version 1",
        "In service",
        "Made when this system was set up; nobody wrote it.",
        "Put into service when this system was set up; nobody approved it.",
      ],
    ]);
    expect(picked()).toBe("Version 2");
  });

  it("starts with one version's table shut while leaving that version open below", async () => {
    opening(PAGE, {
      [`GET ${ENTRY}`]: [reply({ ...TRIAGE, versions: [IN_SERVICE] })],
    });
    await opened();

    expect(
      within(versions()).getByRole("button", { name: "Versions" }),
    ).toHaveAttribute("aria-expanded", "false");
    expect(within(versions()).queryByRole("table")).toBeNull();
    expect(theVersion(2)).toBeInTheDocument();
  });

  it("opens the version the address names, including its own content", async () => {
    const { sent } = opening(`${PAGE}?version=${SEEDED.versionId}`, {
      [`GET ${ENTRY}/versions/${SEEDED.versionId}`]: [
        reply({ ...QUESTION, instruction: "Read the older category." }),
      ],
    });
    await opened();

    expect(picked()).toMatch(/^Version 1/);
    expect(theVersion(1)).toBeInTheDocument();
    await within(theVersion(1)).findByText("Read the older category.");
    expect(theVersion(1)).toHaveTextContent("Read the older category.");
    expect(requestsTo(sent)).toContain(
      `GET ${ENTRY}/versions/${SEEDED.versionId}`,
    );
  });

  it("opens the newest version and its content when the address names no version here", async () => {
    const missing = "00000007-0000-4000-8000-000000000b99";
    const content = deferred<readonly [string, number]>();
    const { sent } = opening(`${PAGE}?version=${missing}`, {
      [`GET ${ENTRY}/versions/${IN_SERVICE.versionId}`]: [content.promise],
    });
    await opened();

    expect(picked()).toMatch(/^Version 2/);
    expect(theVersion(2)).toHaveTextContent("Still reading…");
    await waitFor(() => {
      expect(requestsTo(sent)).toContain(
        `GET ${ENTRY}/versions/${IN_SERVICE.versionId}`,
      );
    });
    expect(requestsTo(sent)).not.toContain(`GET ${ENTRY}/versions/${missing}`);

    await act(async () => {
      content.settle([
        JSON.stringify({
          ...QUESTION,
          instruction: "Read the current category.",
        }),
        200,
      ]);
    });

    await within(theVersion(2)).findByText("Read the current category.");
    expect(theVersion(2)).toHaveTextContent("Read the current category.");
    expect(theVersion(2)).not.toHaveTextContent("Still reading…");
    expect(
      requestsTo(sent).filter(
        (request) =>
          request === `GET ${ENTRY}/versions/${IN_SERVICE.versionId}`,
      ),
    ).toHaveLength(1);
  });

  it.each([
    ["no act on it", SEEDED, []],
    ["an act on it", IN_SERVICE, ["Retire"]],
  ])(
    "draws only admitted actions at the end of version %s's title line",
    async (_case, version, drawn) => {
      opening(`${PAGE}?version=${version.versionId}`);
      await opened();

      const heading = within(theVersion(version.number)).getByRole("heading", {
        name: `Version ${version.number}`,
      });
      expect(actsDrawn()).toEqual(drawn);
      expect(
        screen.queryByRole("region", { name: "What can be done" }),
      ).toBeNull();
      if (drawn.length > 0) {
        expect(heading.parentElement).toContainElement(
          versionActionButtons()[0]!,
        );
      }
    },
  );

  /** Moving the version on is what the reader came to do; withdrawing and retiring are drawn as any other act. */
  it.each([
    ["its writer, the version a draft", DRAFTED, ["Submit"], [], []],
    [
      "an approver, the version submitted",
      { ...DRAFTED, versions: [SUBMITTED, SEEDED] },
      ["Approve"],
      ["Withdraw"],
      [],
    ],
    [
      "one who may retire it, the version in service",
      TRIAGE,
      [],
      [],
      ["Retire"],
    ],
  ])(
    "fills only the act that moves the version forward, for %s",
    async (_case, entry, filled, plain, warning) => {
      opening(PAGE, { [`GET ${ENTRY}`]: [reply(entry)] });
      await opened();

      const drawn = versionActionButtons();
      const drawnAs = (has: (each: HTMLElement) => boolean) =>
        drawn.filter(has).map((each) => each.textContent);

      expect(
        drawnAs((each) => each.classList.contains(buttonClasses.contained)),
      ).toEqual(filled);
      expect(
        drawnAs(
          (each) =>
            each.classList.contains(buttonClasses.outlined) &&
            each.classList.contains(buttonClasses.colorPrimary),
        ),
      ).toEqual(plain);
      expect(
        drawnAs(
          (each) =>
            each.classList.contains(buttonClasses.outlined) &&
            each.classList.contains(buttonClasses.colorWarning),
        ),
      ).toEqual(warning);
      expect(drawn).toHaveLength(filled.length + plain.length + warning.length);
    },
  );

  it("frames the versions and the version open each as a part of the page, none within another", async () => {
    opening(PAGE);
    await opened();

    const parts = [versions(), theVersion(2)];

    expect(parts.map(drawnOf)).toEqual(parts.map(() => drawnAtDepth(1)));
    expect(
      parts.flatMap((outer) =>
        parts.filter((inner) => inner !== outer && outer.contains(inner)),
      ),
    ).toEqual([]);
  });

  /** None laid beside another: a version's content is read across the page, never in a column beside the table. */
  it("lays the versions and the version open one under another, in that order", async () => {
    opening(PAGE);
    await opened();

    const parts = [versions(), theVersion(2)];
    const holder = parts[0]!.parentElement!;

    expect(parts.map((part) => part.parentElement)).toEqual(
      parts.map(() => holder),
    );
    expect(getComputedStyle(holder).display).toBe("block");
    expect(
      parts
        .slice(1)
        .map(
          (part, index) =>
            parts[index]!.compareDocumentPosition(part) &
            Node.DOCUMENT_POSITION_FOLLOWING,
        ),
    ).toEqual([Node.DOCUMENT_POSITION_FOLLOWING]);
  });

  it("links each version to the address that opens it", async () => {
    const { at } = opening(PAGE);
    await opened();

    await userEvent.click(
      within(versions()).getByRole("link", { name: /^Version 1/ }),
    );

    expect(at()).toBe(`${PAGE}?version=${SEEDED.versionId}`);
    expect(picked()).toMatch(/^Version 1/);
  });

  /** Another entry is another page opened, whatever draws it: nothing of how the last one's versions were left is kept. */
  it("opens another entry's versions afresh, open and newest first, however this one's were left", async () => {
    const other = `/api/groups/${GROUP}/questions/${HANDLE}`;
    const handling = { ...IN_SERVICE, versionId: HANDLING, number: 4 };
    opening(PAGE, {
      [`GET ${other}`]: [
        reply({
          ...TRIAGE,
          entryId: HANDLE,
          name: "Handle",
          versions: [handling, SEEDED],
        }),
      ],
      [`GET ${other}/versions/${HANDLING}`]: [reply(QUESTION)],
    });
    await opened();
    await userEvent.click(
      within(versions()).getByRole("button", { name: "Version" }),
    );
    await userEvent.click(
      within(versions()).getByRole("button", { name: "Versions" }),
    );

    await userEvent.click(within(theVersion(2)).getByRole("link"));

    await screen.findByRole("heading", { level: 1, name: /Handle/ });
    expect(
      within(versions()).getByRole("button", { name: "Versions" }),
    ).toHaveAttribute("aria-expanded", "true");
    expect(versionRows().map(([number]) => number)).toEqual([
      "Version 4",
      "Version 1",
    ]);
  });

  it("opens a different entry with one version shut by default", async () => {
    const other = `/api/groups/${GROUP}/questions/${HANDLE}`;
    const handling = { ...IN_SERVICE, versionId: HANDLING, number: 4 };
    opening(PAGE, {
      [`GET ${other}`]: [
        reply({
          ...TRIAGE,
          entryId: HANDLE,
          name: "Handle",
          versions: [handling],
        }),
      ],
      [`GET ${other}/versions/${HANDLING}`]: [reply(QUESTION)],
    });
    await opened();

    await userEvent.click(within(theVersion(2)).getByRole("link"));

    await screen.findByRole("heading", { level: 1, name: /Handle/ });
    expect(
      within(versions()).getByRole("button", { name: "Versions" }),
    ).toHaveAttribute("aria-expanded", "false");
    expect(within(versions()).queryByRole("table")).toBeNull();
    expect(theVersion(4)).toBeInTheDocument();
  });

  /** Read again, it is the same page: how the reader left its versions stays as they left it. */
  it("keeps the versions shut and in the order picked while the same entry is read again", async () => {
    const { sent } = opening(PAGE, {
      [`GET ${ENTRY}`]: [
        reply(TRIAGE),
        reply({ ...TRIAGE, versions: [{ ...IN_SERVICE, acts: [] }, SEEDED] }),
      ],
      [`PUT ${ENTRY}/versions/${IN_SERVICE.versionId}/retirement`]: [
        refusal("VERSION_STANDING_REFUSES", 409),
      ],
    });
    await opened();
    await userEvent.click(
      within(versions()).getByRole("button", { name: "Version" }),
    );
    await userEvent.click(
      within(versions()).getByRole("button", { name: "Versions" }),
    );

    await confirmRetire();
    await waitFor(() => expect(entryReads(sent)).toBe(2));
    await waitFor(() => expect(actsDrawn()).toEqual([]));

    const toggle = within(versions()).getByRole("button", { name: "Versions" });
    const expandedAfterReading = toggle.getAttribute("aria-expanded");
    await userEvent.click(toggle);

    expect(expandedAfterReading).toBe("false");
    expect(versionRows().map(([number]) => number)).toEqual([
      "Version 1",
      "Version 2",
    ]);
  });

  it("says of the open version how many in service pin it, links each, and draws what it holds", async () => {
    const { sent } = opening(PAGE);
    await opened();

    expect(actsDrawn()).toEqual(["Retire"]);
    expect(theVersion(2)).toHaveTextContent("One version in service pins it.");
    const pinners = within(theVersion(2)).getByRole("list", {
      name: "One version in service pins it.",
    });
    expect(
      within(pinners)
        .getAllByRole("link")
        .map((link) => [link.textContent, link.getAttribute("href")]),
    ).toEqual([
      [
        "Handle (Workflow), version 4",
        `/groups/${GROUP}/workflows/${HANDLE}?version=${HANDLING}`,
      ],
    ]);
    expect(
      await within(theVersion(2)).findByText(
        "Say which category the claim falls under.",
      ),
    ).toBeInTheDocument();
    expect(requestsTo(sent)).toContain(
      `GET ${ENTRY}/versions/${IN_SERVICE.versionId}`,
    );
    const pin = within(pinners).getByRole("link");
    expect(pin).toHaveAccessibleName(
      `${setApart("Handle")} (Workflow), version 4`,
    );
    expect(pin.querySelector("bdi")?.textContent).toBe("Handle");
    expect(pin.querySelector("bdi")?.parentElement).toBe(pin);
    expect(getComputedStyle(pin.querySelector("bdi")!).fontStyle).not.toBe(
      "italic",
    );
    expect(
      Array.from(pin.querySelectorAll("span"), (part) => [
        part.textContent,
        getComputedStyle(part).fontStyle === "italic",
      ]),
    ).toEqual([
      [" (", true],
      ["Workflow", false],
      ["), version ", true],
      ["4", false],
    ]);
    expect(
      getComputedStyle(
        document.getElementById(pinners.getAttribute("aria-labelledby")!)!,
      ).fontStyle,
    ).toBe("italic");
    expect(theVersion(2)).not.toHaveTextContent("Waiting on");
  });

  it("says of a submitted version that it waits on anybody who may approve an entry here", async () => {
    opening(PAGE, {
      [`GET ${ENTRY}`]: [reply({ ...DRAFTED, versions: [SUBMITTED, SEEDED] })],
    });
    await opened();

    expect(theVersion(3)).toHaveTextContent(
      "Waiting on anybody who may approve an entry here.",
    );
    expect(theVersion(3)).toHaveTextContent("Nothing in service pins it.");
    expect(within(theVersion(3)).queryByRole("list")).toBeNull();
  });

  /** Each act's request and the rule its refusal is said as, the whole table pressed. */
  it.each([
    [
      "Submit",
      DRAFTED,
      "PUT",
      "submission",
      DRAFT,
      "made, written and submitted",
    ],
    [
      "Withdraw",
      { ...DRAFTED, versions: [SUBMITTED, SEEDED] },
      "DELETE",
      "submission",
      SUBMITTED,
      "made, written and submitted",
    ],
    [
      "Approve",
      { ...DRAFTED, versions: [SUBMITTED, SEEDED] },
      "PUT",
      "approval",
      SUBMITTED,
      "put into service, and a run's raised ceiling approved or refused,",
    ],
    [
      "Retire",
      TRIAGE,
      "PUT",
      "retirement",
      IN_SERVICE,
      "retired, stopped and let go",
    ],
  ])(
    "asks %s of the open version, and says a refusal of the act as the rule it asks",
    async (control, entry, method, part, version, rule) => {
      const address = `${ENTRY}/versions/${version.versionId}/${part}`;
      const { sent, readAgain } = opening(PAGE, {
        [`GET ${ENTRY}`]: [reply(entry)],
        [`${method} ${address}`]: [refusal("ACT_NOT_PERMITTED", 403)],
      });
      await opened();

      if (control === "Retire") {
        await confirmRetire();
      } else {
        await userEvent.click(
          within(openVersionRegion()).getByRole("button", { name: control }),
        );
      }

      if (control === "Retire") {
        await waitFor(() =>
          expect(
            screen.queryByRole("dialog", { name: "Retire version 2?" }),
          ).toBeNull(),
        );
      }
      expect(
        await within(openVersionRegion()).findByRole("alert"),
      ).toHaveTextContent(
        `A group's entries are ${rule} only by a role in it that may`,
      );
      expect(requestsTo(sent)).toContain(`${method} ${address}`);
      expect(readAgain).toHaveBeenCalledTimes(1);
      await waitFor(() => expect(entryReads(sent)).toBe(2));
    },
  );

  it.each([
    [
      "Stop it being used",
      "PUT",
      "/stop",
      "retired, stopped and let go",
      TRIAGE,
    ],
    [
      "Let it be used again",
      "DELETE",
      "/stop",
      "retired, stopped and let go",
      {
        ...TRIAGE,
        stopped: { by: AVO, at: "2026-09-24T08:15:00Z" },
        acts: ["let_go"],
      },
    ],
    ["New draft", "POST", "/versions", "made, written and submitted", TRIAGE],
  ])(
    "asks %s of the entry, and says a refusal of the act as the rule it asks",
    async (control, method, path, rule, entry) => {
      const { sent } = opening(PAGE, {
        [`GET ${ENTRY}`]: [reply(entry)],
        [`${method} ${ENTRY}${path}`]: [refusal("ACT_NOT_PERMITTED", 403)],
      });
      await opened();

      await userEvent.click(screen.getByRole("button", { name: control }));

      expect(await screen.findByRole("alert")).toHaveTextContent(
        `A group's entries are ${rule} only by a role in it that may`,
      );
      expect(requestsTo(sent)).toContain(`${method} ${ENTRY}${path}`);
    },
  );

  /** The table is stood in for with a rule editing takes nowhere, so only the table's word can reach the page. */
  it("edits the entry's name and what it is for as one change, and says a refusal as the table's rule for editing", async () => {
    const { sent } = opening(PAGE, {
      [`PATCH ${ENTRY}`]: [refusal("ACT_NOT_PERMITTED", 403)],
    });
    await opened();

    await userEvent.click(screen.getByRole("button", { name: "Edit" }));
    const dialog = screen.getByRole("dialog", { name: "Edit this entry" });
    await userEvent.clear(
      within(dialog).getByRole("textbox", { name: "Name" }),
    );
    await userEvent.type(
      within(dialog).getByRole("textbox", { name: "Name" }),
      "Intake",
    );
    await userEvent.click(within(dialog).getByRole("button", { name: "Save" }));

    expect(await within(dialog).findByRole("alert")).toHaveTextContent(
      "A group's entries are put into service, and a run's raised ceiling approved or refused, only by a role in it that may approve an entry.",
    );
    const edited = sent.mock.calls.find(([, init]) => init?.method === "PATCH");
    expect(JSON.parse(String(edited![1]?.body))).toEqual({
      name: "Intake",
      purpose: "Sorts claims.",
    });
  });

  /** No line is written after an act: the page is drawn again as the change answered. */
  it("stops the entry, showing it as the change answered without reading it again or saying anything more", async () => {
    const { sent } = opening(PAGE, {
      [`PUT ${ENTRY}/stop`]: [
        reply({
          ...TRIAGE,
          stopped: { by: AVO, at: "2026-09-24T08:15:00Z" },
          acts: ["rename", "let_go"],
        }),
      ],
    });
    await opened();
    const control = screen.getByRole("button", { name: "Stop it being used" });

    await userEvent.click(control);

    expect(
      await screen.findByRole("button", { name: "Let it be used again" }),
    ).toBe(control);
    expect(requestsTo(sent)).toEqual([
      `GET ${ENTRY}`,
      `GET ${ENTRY}/versions/${IN_SERVICE.versionId}`,
      `PUT ${ENTRY}/stop`,
    ]);
    expect(screen.queryByRole("alert")).toBeNull();
    expect(
      screen
        .queryAllByRole("status")
        .filter((each) => !theVersion(2).contains(each))
        .map((each) => each.textContent),
    ).toEqual([""]);
    expect(
      within(theVersion(2))
        .queryAllByRole("status")
        .map((each) => each.textContent),
    ).toEqual([""]);
  });

  it("keeps a stop answer visible while replacing an ordinary read in flight", async () => {
    const unanswered: Reply = new Promise(() => {});
    const { sent, readAgain } = opening(PAGE, {
      [`GET ${ENTRY}`]: [reply(TRIAGE), unanswered, unanswered],
      [`PUT ${ENTRY}/stop`]: [
        refusal("ACT_NOT_PERMITTED", 403),
        reply({
          ...TRIAGE,
          stopped: { by: AVO, at: "2026-09-24T08:15:00Z" },
          acts: ["rename", "let_go"],
        }),
      ],
    });
    await opened();

    await userEvent.click(
      screen.getByRole("button", { name: "Stop it being used" }),
    );
    await screen.findByRole("alert");
    await waitFor(() => expect(entryReads(sent)).toBe(2));
    await userEvent.click(
      screen.getByRole("button", { name: "Stop it being used" }),
    );

    expect(
      await screen.findByRole("button", { name: "Let it be used again" }),
    ).toBeInTheDocument();
    expect(entryReads(sent)).toBe(3);
    expect(readAgain).toHaveBeenCalledOnce();
    expect(screen.queryByRole("alert")).toBeNull();
  });

  it.each(["answer", "refusal"])(
    "keeps another entry under its own address when the first one's stopped %s arrives late",
    async (ending) => {
      const otherAddress = `/groups/${GROUP}/questions/${HANDLE}`;
      const otherEntry = `/api/groups/${GROUP}/questions/${HANDLE}`;
      const { go, at } = opening(PAGE, {
        [`GET ${otherEntry}`]: [
          reply({
            ...TRIAGE,
            entryId: HANDLE,
            name: "Demo Sample",
            acts: [],
            versions: [],
          }),
        ],
      });
      await opened();
      const held = deferred<Response>();
      const ordinaryFetch = globalThis.fetch;
      const sent = vi.fn<typeof fetch>((input, init) =>
        `${init?.method ?? "GET"} ${new URL(String(input), "http://reader.test").pathname}` ===
        `PUT ${ENTRY}/stop`
          ? held.promise
          : ordinaryFetch(input, init),
      );
      vi.stubGlobal("fetch", sent);

      await userEvent.click(
        screen.getByRole("button", { name: "Stop it being used" }),
      );
      expect(
        sent.mock.calls.some(
          ([input, init]) =>
            init?.method === "PUT" && String(input).includes(`${ENTRY}/stop`),
        ),
      ).toBe(true);
      await go(otherAddress);
      expect(
        await screen.findByRole("heading", { level: 1, name: /Demo Sample/ }),
      ).toBeInTheDocument();

      await act(async () => {
        held.settle(
          new Response(
            JSON.stringify(
              ending === "answer"
                ? {
                    ...TRIAGE,
                    stopped: { by: AVO, at: "2026-09-24T08:15:00Z" },
                  }
                : { code: "ACT_NOT_PERMITTED" },
            ),
            {
              status: ending === "answer" ? 200 : 403,
              headers: { "Content-Type": "application/json" },
            },
          ),
        );
      });

      expect(at()).toBe(otherAddress);
      expect(
        screen.getByRole("heading", { level: 1, name: /Demo Sample/ }),
      ).toBeInTheDocument();
      expect(
        screen.queryByRole("heading", { level: 1, name: /Triage/ }),
      ).toBeNull();
      expect(screen.queryByRole("alert")).toBeNull();
    },
  );

  /** The control pressed goes with the act it offered, so the keyboard is kept in the part that asked. */
  it("opens a draft just made, the newest, keeping the keyboard in the versions", async () => {
    const { at } = opening(`${PAGE}?version=${SEEDED.versionId}`, {
      [`POST ${ENTRY}/versions`]: [reply(DRAFTED, 201)],
    });
    await opened();

    await userEvent.click(screen.getByRole("button", { name: "New draft" }));

    await waitFor(() => expect(picked()).toMatch(/^Version 3/));
    expect(at()).toBe(PAGE);
    expect(actsDrawn()).toEqual(["Submit"]);
    expect(screen.queryByRole("button", { name: "New draft" })).toBeNull();
    expect(document.activeElement).toBe(
      screen.getByRole("heading", { name: "Versions" }),
    );
  });

  it("keeps the keyboard in the version where the act pressed is no longer offered", async () => {
    opening(PAGE, {
      [`GET ${ENTRY}`]: [reply(DRAFTED)],
      [`PUT ${ENTRY}/versions/${DRAFT.versionId}/submission`]: [
        reply({ ...DRAFTED, versions: [SUBMITTED, IN_SERVICE, SEEDED] }),
      ],
    });
    await opened();

    await userEvent.click(
      within(openVersionRegion()).getByRole("button", { name: "Submit" }),
    );

    await waitFor(() => expect(actsDrawn()).toEqual(["Withdraw", "Approve"]));
    expect(document.activeElement).toBe(
      screen.getByRole("heading", { name: "Version 3" }),
    );
  });

  it("cancels retiring a version without sending a request", async () => {
    const { sent } = opening(PAGE);
    await opened();
    await userEvent.click(
      within(openVersionRegion()).getByRole("button", { name: "Retire" }),
    );
    expect(
      screen.getByRole("dialog", { name: "Retire version 2?" }),
    ).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "Cancel" }));
    await waitFor(() =>
      expect(
        screen.queryByRole("dialog", { name: "Retire version 2?" }),
      ).toBeNull(),
    );
    expect(
      requestsTo(sent).filter((each) => each.includes("/retirement")),
    ).toEqual([]);
  });

  /** What moved under the page is read again, so no control is left offering what was just refused. */
  it("says a refusal for what moved beside the control that asked, and reads the entry again", async () => {
    const { sent, readAgain } = opening(PAGE, {
      [`GET ${ENTRY}`]: [
        reply(TRIAGE),
        reply({ ...TRIAGE, versions: [{ ...IN_SERVICE, acts: [] }, SEEDED] }),
      ],
      [`PUT ${ENTRY}/versions/${IN_SERVICE.versionId}/retirement`]: [
        refusal("VERSION_STANDING_REFUSES", 409),
      ],
    });
    await opened();

    await confirmRetire();
    await waitFor(() =>
      expect(
        screen.queryByRole("dialog", { name: "Retire version 2?" }),
      ).toBeNull(),
    );

    expect(
      await within(openVersionRegion()).findByRole("alert"),
    ).toHaveTextContent("That version's standing does not admit this.");
    await waitFor(() => expect(entryReads(sent)).toBe(2));
    await waitFor(() => expect(actsDrawn()).toEqual([]));
    expect(readAgain).not.toHaveBeenCalled();
  });

  /** Read again, the entry no longer offers the act refused, so the control pressed is gone with it. */
  it("keeps the keyboard in the version where a refused act is read again out of being offered", async () => {
    opening(PAGE, {
      [`GET ${ENTRY}`]: [
        reply(TRIAGE),
        reply({ ...TRIAGE, versions: [{ ...IN_SERVICE, acts: [] }, SEEDED] }),
      ],
      [`PUT ${ENTRY}/versions/${IN_SERVICE.versionId}/retirement`]: [
        refusal("VERSION_STANDING_REFUSES", 409),
      ],
    });
    await opened();

    await confirmRetire();

    await waitFor(() => expect(actsDrawn()).toEqual([]));
    expect(document.activeElement).toBe(
      screen.getByRole("heading", { name: "Version 2" }),
    );
  });

  /** The last act goes away, and the keyboard stays with the version that offered it. */
  it("puts the keyboard on the version's heading where the act pressed leaves nothing to be done", async () => {
    opening(PAGE, {
      [`PUT ${ENTRY}/versions/${IN_SERVICE.versionId}/retirement`]: [
        reply({
          ...TRIAGE,
          versions: [{ ...IN_SERVICE, standing: "retired", acts: [] }, SEEDED],
        }),
      ],
    });
    await opened();

    await confirmRetire();

    await waitFor(() => expect(actsDrawn()).toEqual([]));
    expect(document.activeElement).toBe(
      screen.getByRole("heading", { name: "Version 2" }),
    );
  });

  it.each([
    [
      "a draft, which taking the version in service changes",
      DRAFTED,
      "submission",
      "Submit",
      "Taking the version in service in place of each retired one is a change to this draft.",
    ],
    [
      "a submitted version, which has to be withdrawn first",
      { ...DRAFTED, versions: [SUBMITTED, IN_SERVICE, SEEDED] },
      "approval",
      "Approve",
      "A submitted version cannot be changed: withdraw it first, then take the version in service in place of each retired one.",
    ],
  ])(
    "names each pin retired since within the version, with what replaces it, on %s",
    async (_case, entry, part, control, way) => {
      const { sent } = opening(PAGE, {
        [`GET ${ENTRY}`]: [reply(entry)],
        [`PUT ${ENTRY}/versions/${DRAFT.versionId}/${part}`]: [
          refusal("VERSION_PINS_RETIRED", 409, {
            pins: [
              {
                entryId: "00000006-0000-4000-8000-000000000b61",
                kind: "reference_list",
                name: "Regions",
                pinned: {
                  versionId: "00000007-0000-4000-8000-000000000b61",
                  number: 1,
                },
                newestInService: {
                  versionId: "00000007-0000-4000-8000-000000000b62",
                  number: 3,
                },
              },
              {
                entryId: "00000006-0000-4000-8000-000000000b63",
                kind: "question",
                name: "Sort",
                pinned: {
                  versionId: "00000007-0000-4000-8000-000000000b63",
                  number: 2,
                },
              },
            ],
          }),
        ],
      });
      await opened();

      await userEvent.click(
        within(openVersionRegion()).getByRole("button", { name: control }),
      );

      const pins = await within(openVersionRegion()).findByRole("list", {
        name: "Pinned, and retired since",
      });
      expect(
        within(pins)
          .getAllByRole("listitem")
          .map((each) => each.textContent),
      ).toEqual([
        "Regions (Reference list), version 1, is retired. Version 3 is in service. Open Regions, version 3",
        "Sort (Question), version 2, is retired. None of it is in service.",
      ]);
      const [replaceable, unreplaceable] =
        within(pins).getAllByRole("listitem");
      for (const [item, name, segments] of [
        [
          replaceable,
          "Regions",
          [
            [" (", true],
            ["Reference list", false],
            ["), version ", true],
            ["1", false],
            [", is retired. Version ", true],
            ["3", false],
            [" is in service.", true],
          ],
        ],
        [
          unreplaceable,
          "Sort",
          [
            [" (", true],
            ["Question", false],
            ["), version ", true],
            ["2", false],
            [", is retired. None of it is in service.", true],
          ],
        ],
      ] as const) {
        const isolatedName = item.querySelector("bdi")!;
        expect(isolatedName.textContent).toBe(name);
        expect(isolatedName.parentElement).toBe(item);
        expect(getComputedStyle(isolatedName).fontStyle).not.toBe("italic");
        expect(
          Array.from(item.children)
            .filter((child) => child.tagName === "SPAN")
            .map((child) => [
              child.textContent,
              getComputedStyle(child).fontStyle === "italic",
            ]),
        ).toEqual(segments);
      }
      expect(within(pins).getByRole("link")).toHaveAccessibleName(
        `Open ${setApart("Regions")}, version 3`,
      );
      expect(within(pins).getByRole("link").getAttribute("href")).toBe(
        `/groups/${GROUP}/reference-lists/00000006-0000-4000-8000-000000000b61?version=00000007-0000-4000-8000-000000000b62`,
      );
      expect(openVersionRegion()).toHaveTextContent(way);
      expect(within(openVersionRegion()).getByRole("alert")).toHaveTextContent(
        "This version pins a version retired since.",
      );
      expect(entryReads(sent)).toBe(1);
    },
  );

  /**
   * Each place in the order the server named it: the part, and a field found by its key in the version as the
   * page read it, named down to it, with its place where a fellow shares its name.
   */
  it("names every place a submission is refused for within the version, each where it is", async () => {
    const { sent } = opening(PAGE, {
      [`GET ${ENTRY}`]: [reply(DRAFTED)],
      [`GET ${ENTRY}/versions/${DRAFT.versionId}`]: [reply(HELD_QUESTION)],
      [`PUT ${ENTRY}/versions/${DRAFT.versionId}/submission`]: [
        refusal("VERSION_CONTENT_DOES_NOT_HOLD", 409, {
          problems: [
            { code: "instruction_missing", part: "instruction" },
            {
              code: "longest_missing",
              part: "gives",
              fieldId: "0000000b-0000-4000-8000-000000000b71",
            },
            {
              code: "name_repeated",
              part: "gives",
              fieldId: "0000000b-0000-4000-8000-000000000b72",
            },
            {
              code: "no_fields_held",
              part: "takes",
              fieldId: "0000000b-0000-4000-8000-000000000b7f",
            },
            {
              code: "list_missing",
              part: "gives",
              fieldId: "0000000b-0000-4000-8000-000000000b7e",
            },
            { code: "a_problem_not_worded", part: "a_part_not_worded" },
            { code: "asking_past_largest", part: "asking", excess: 1234567 },
            { code: "asking_past_largest", part: "asking", excess: 1 },
            {
              code: "asking_past_largest",
              part: "asking",
              excess: Number.MAX_SAFE_INTEGER,
            },
            {
              code: "asking_past_largest",
              part: "asking",
              excess: Number.MAX_SAFE_INTEGER + 1,
            },
            { code: "asking_past_largest", part: "asking" },
          ],
        }),
      ],
    });
    await opened();
    await within(theVersion(3)).findByRole("region", {
      name: "The declared answer",
    });

    await userEvent.click(
      within(openVersionRegion()).getByRole("button", { name: "Submit" }),
    );

    const places = await within(openVersionRegion()).findByRole("list", {
      name: "What does not hold yet",
    });
    const items = within(places).getAllByRole("listitem");
    const expected = [
      "The instruction: It tells whoever answers it nothing yet.",
      `The declared answer, ${setApart("details")}.${setApart("product (field 1 there)")}: A field of text says how long its value may be, and this one does not yet.`,
      `The declared answer, ${setApart("details")}.${setApart("product (field 2 there)")}: A field beside it already has this name.`,
      `What it takes, ${setApart("sender")}: A field holding fields declares at least one, and this one declares none yet.`,
      "The declared answer: A field of terms names the list they come from, and this one does not yet.",
      "a_part_not_worded: Something here does not hold.",
      "One asking of it: It could send 1,234,567 characters more than 8,388,608, the most one asking may send.",
      "One asking of it: It could send 1 character more than 8,388,608, the most one asking may send.",
      "One asking of it: It could send 9,007,199,254,740,991 characters more than 8,388,608, the most one asking may send.",
      "One asking of it: It could send more than 9,007,199,254,740,991 characters past 8,388,608, the most one asking may send.",
      "One asking of it: Something here does not hold.",
    ];
    expect(items.map((each) => each.getAttribute("aria-label"))).toEqual(
      expected,
    );
    expect(items.map((each) => each.textContent)).toEqual(
      expected.map((each) =>
        each
          .replaceAll(String.fromCodePoint(0x2068), "")
          .replaceAll(String.fromCodePoint(0x2069), ""),
      ),
    );
    expect(
      items.map((each) =>
        [...each.querySelectorAll("bdi")].map((data) => data.textContent),
      ),
    ).toEqual([
      [],
      ["details", "product"],
      ["details", "product"],
      ["sender"],
      [],
      ["a_part_not_worded"],
      [],
      [],
      [],
      [],
      [],
    ]);
    expect(within(openVersionRegion()).getByRole("alert")).toHaveTextContent(
      "Some of what this version holds does not hold yet. Each place is named.",
    );
    expect(
      within(openVersionRegion()).queryByRole("list", {
        name: "Pinned, and retired since",
      }),
    ).toBeNull();
    expect(entryReads(sent)).toBe(1);
  });

  it("shows a workflow submission refusal in place of an older read report, once", async () => {
    const workflows = `/api/groups/${GROUP}/workflows/${ENTRY_ID}`;
    const problems = [{ code: "runs_missing", part: "steps", stepId: "s9" }];
    laidOutAt(1400, 1400);
    opening(
      `/groups/${GROUP}/workflows/${ENTRY_ID}`,
      {
        [`GET ${workflows}`]: [reply({ ...DRAFTED, kind: "workflow" })],
        [`GET ${workflows}/versions/${DRAFT.versionId}`]: [
          reply({
            revision: 1,
            takes: [],
            gives: [],
            steps: [],
            outputs: [],
            keepsOwnCeiling: false,
            raiseNeedsApproval: false,
            mayBeHelped: false,
            problems,
            sendsPast: [],
            offered: {
              lists: [],
              questions: [],
              workflows: [],
              codeSteps: [],
              searchWays: [],
              models: [],
            },
            terms: {},
          }),
        ],
        [`PUT ${workflows}/versions/${DRAFT.versionId}/submission`]: [
          refusal("VERSION_CONTENT_DOES_NOT_HOLD", 409, {
            problems: [{ code: "no_steps", part: "steps" }],
            problemTotal: 4,
          }),
        ],
      },
      undefined,
      "workflow",
    );
    await opened();
    await within(theVersion(3)).findByRole("list", {
      name: "What does not hold yet",
    });

    await userEvent.click(
      within(openVersionRegion()).getByRole("button", { name: "Submit" }),
    );

    expect(
      await within(openVersionRegion()).findByRole("alert"),
    ).toHaveTextContent(
      "Some of what this version holds does not hold yet. Each place is named.",
    );
    expect(
      screen.getAllByRole("list", { name: "What does not hold yet" }),
    ).toHaveLength(1);
    expect(
      within(theVersion(3)).getByRole("list", {
        name: "What does not hold yet",
      }),
    ).toHaveTextContent(
      "The steps: It holds no step, so it could give nothing back and act on nothing.",
    );
    expect(
      screen.getByText("3 more things do not hold and are not listed."),
    ).toBeVisible();
    expect(
      screen.queryByText(/It says nothing yet of what it runs/),
    ).not.toBeInTheDocument();
  });

  /** Neither refusal hides the other: the places and the pins retired since are both named. */
  it("names the pins retired since beside the places a submission is refused for", async () => {
    opening(PAGE, {
      [`GET ${ENTRY}`]: [reply(DRAFTED)],
      [`PUT ${ENTRY}/versions/${DRAFT.versionId}/submission`]: [
        refusal("VERSION_CONTENT_DOES_NOT_HOLD", 409, {
          problems: [{ code: "instruction_missing", part: "instruction" }],
          pins: [
            {
              entryId: "00000006-0000-4000-8000-000000000b61",
              kind: "reference_list",
              name: "Regions",
              pinned: {
                versionId: "00000007-0000-4000-8000-000000000b61",
                number: 1,
              },
            },
          ],
        }),
      ],
    });
    await opened();

    await userEvent.click(
      within(openVersionRegion()).getByRole("button", { name: "Submit" }),
    );

    expect(
      await within(openVersionRegion()).findByRole("list", {
        name: "What does not hold yet",
      }),
    ).toHaveTextContent(
      "The instruction: It tells whoever answers it nothing yet.",
    );
    expect(
      within(
        within(openVersionRegion()).getByRole("list", {
          name: "Pinned, and retired since",
        }),
      )
        .getAllByRole("listitem")
        .map((each) => each.textContent),
    ).toEqual([
      "Regions (Reference list), version 1, is retired. None of it is in service.",
    ]);
  });

  it("says how many more problems a refused submission counted after its visible places", async () => {
    opening(PAGE, {
      [`GET ${ENTRY}`]: [reply(DRAFTED)],
      [`PUT ${ENTRY}/versions/${DRAFT.versionId}/submission`]: [
        refusal("VERSION_CONTENT_DOES_NOT_HOLD", 409, {
          problems: [{ code: "instruction_missing", part: "instruction" }],
          problemTotal: 2,
        }),
      ],
    });
    await opened();
    await userEvent.click(
      within(openVersionRegion()).getByRole("button", { name: "Submit" }),
    );

    expect(
      await screen.findByText("1 more thing does not hold and is not listed."),
    ).toBeVisible();
    expect(
      within(openVersionRegion()).getByRole("list", {
        name: "What does not hold yet",
      }),
    ).toHaveTextContent(
      "The instruction: It tells whoever answers it nothing yet.",
    );
  });

  /** A write changes what a refusal named and who wrote the version, so neither is left as it was. */
  it("drops the places a submission was refused for once a part is written, and reads the entry again", async () => {
    const { sent } = opening(PAGE, {
      [`GET ${ENTRY}`]: [reply(DRAFTED)],
      [`PUT ${ENTRY}/versions/${DRAFT.versionId}/submission`]: [
        refusal("VERSION_CONTENT_DOES_NOT_HOLD", 409, {
          problems: [{ code: "instruction_missing", part: "instruction" }],
        }),
      ],
      [`PUT ${ENTRY}/versions/${DRAFT.versionId}/instruction`]: [
        reply({ ...QUESTION, instruction: "Say which." }),
      ],
    });
    await opened();
    await userEvent.click(
      within(openVersionRegion()).getByRole("button", { name: "Submit" }),
    );
    await within(openVersionRegion()).findByRole("list", {
      name: "What does not hold yet",
    });

    const box = within(theVersion(3)).getByRole("textbox", {
      name: "The instruction",
    });
    await userEvent.clear(box);
    await userEvent.type(box, "Say which.");
    await userEvent.click(
      within(theVersion(3)).getByRole("button", {
        name: "Save the instruction",
      }),
    );

    await waitFor(() =>
      expect(
        within(openVersionRegion()).queryByRole("list", {
          name: "What does not hold yet",
        }),
      ).toBeNull(),
    );
    expect(within(openVersionRegion()).queryByRole("alert")).toBeNull();
    await waitFor(() => expect(entryReads(sent)).toBe(2));
  });

  it("reads the reader's standing again where the entry is refused for a group they are no longer in", async () => {
    const { readAgain } = opening(PAGE, {
      [`GET ${ENTRY}`]: [refusal("GROUP_NOT_IN_VIEW", 404)],
    });

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "That group is not in view.",
    );
    // Reread from a passive effect, which React may run in a task after the commit that drew the alert.
    await waitFor(() => expect(readAgain).toHaveBeenCalledTimes(1));
    // Unmounting flushes the passive effects of commits already made, so a further reread is counted too.
    cleanup();
    expect(readAgain).toHaveBeenCalledTimes(1);
  });

  it("answers an entry not in view with that refusal and nothing of an entry", async () => {
    const { readAgain } = opening(PAGE, {
      [`GET ${ENTRY}`]: [refusal("ENTRY_NOT_IN_VIEW", 404)],
    });

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "That entry is not in this group's library.",
    );
    expect(screen.queryByRole("heading", { level: 1 })).toBeNull();
    // Unmounting flushes any passive effect still pending; before it, a reread may just not have run yet.
    cleanup();
    expect(readAgain).not.toHaveBeenCalled();
  });

  /** Made from a page now left, the control that made it is gone with that page. */
  it("puts the keyboard on the entry's heading where it was just made", async () => {
    opening(PAGE, {}, JUST_MADE);
    const heading = await opened();

    await waitFor(() => expect(document.activeElement).toBe(heading));
  });

  it("leaves the keyboard alone on an entry opened plainly", async () => {
    opening(PAGE);

    await opened();

    expect(document.activeElement).toBe(document.body);
  });
});
