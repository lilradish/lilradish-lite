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
import {
  PointerEventsCheckLevel,
  userEvent,
} from "@testing-library/user-event";
import { RouterProvider, createMemoryRouter } from "react-router";
import {
  cloneElement,
  isValidElement,
  type ComponentProps,
  createContext,
  Profiler,
  useContext,
  useState,
  type ProfilerOnRenderCallback,
} from "react";
import { describe, expect, it, onTestFinished, vi } from "vitest";

import type { Version } from "../../api/groups/{groupId}/{kind}";
import type { Problem } from "../../api/problem";
import { StandingProvider } from "../../app/standing/StandingContext";
import { answered, inGroup } from "../../testutil/standingRead";
import { isolatedInText } from "../../lib/direction/isolated";
import { theme } from "../../lib/theme/theme";
import { requestsTo, serving, type Reply } from "../../testutil/answering";
import { deferred } from "../../testutil/deferred";
import { drawnAtDepth, drawnOf } from "../../testutil/depths";
import { libraryLaidOutAt as laidOutAt } from "./libraryLayout.testHelper";
import { noticesIn } from "../../testutil/notices";
import type { ReadContent } from "./ContentProblems";
import { WorkflowContent } from "./WorkflowContent";
import { RowPanel } from "../../lib/collection/RowPanel";
vi.mock("../../app/standing/RequireGroup", async (importOriginal) => ({
  ...(await importOriginal<typeof import("../../app/standing/RequireGroup")>()),
  useGroup: () => ({ groupId: GROUP }),
}));

const GROUP = "00000003-0000-4000-8000-000000000cb1";

const ENTRY = "00000006-0000-4000-8000-000000000cb1";

const VERSION = "00000007-0000-4000-8000-000000000cb1";

const ADDRESS = `/api/groups/${GROUP}/workflows/${ENTRY}/versions/${VERSION}`;
const STANDING = answered(
  [],
  [inGroup(GROUP, "CLAIMS", "Claims", ["author_entry"])],
  "000cb1",
);

const GIVES_HINT =
  "What a run of this workflow gives back when it's done. It's shown as the run's result, and it's what a step gets when it runs this workflow inside another. Leave it empty for a workflow that only acts.";

/** What the steps say where there are none, which submitting refuses. */
const NO_STEPS_REFUSED =
  "It holds no step, so it could give nothing back and act on nothing.";

const DRAFT: Version = {
  versionName: null,
  versionId: VERSION,
  number: 2,
  standing: "draft",
  writers: [{ userId: "000cb1" }],
  writtenByMigration: false,
  stateActs: new Set(["write", "submit"]),
  pinnedBy: [],
};

const READ_ONLY: Version = {
  ...DRAFT,
  standing: "in_service",
  stateActs: new Set(),
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
  versionName: null,
  name: "Triage",
  versionId: "00000007-0000-4000-8000-000000000cb2",
  number: 4,
  standing: "in_service",
};

const NEWER = "00000007-0000-4000-8000-000000000cb3";
const NEWER_ADDRESS = `/api/groups/${GROUP}/versions/${NEWER}`;
const NEWER_DETAIL = {
  kind: "question",
  version: { ...TRIAGE, versionId: NEWER, number: 5 },
  takes: [COMPLAINT],
  gives: [SUMMARY],
  terms: {},
  searchMatches: false,
};

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
        version: {
          ...TRIAGE,
          newer: { versionName: null, versionId: NEWER, number: 5 },
        },
      },
    },
  ],
};

const SAVE_STEPS = "Save the steps and what fills what it gives back";

const LOAD_SAVED = "Load the saved draft";

const KEPT_HELP = "What every case gives back, not yet taken with Done.";

const DROPPED = "A field you had not confirmed was dropped: its step is gone.";

function reply(body: object, status = 200): readonly [string, number] {
  return [JSON.stringify(body), status];
}

/** The content under a real router whose address the test reads, and only the server stood in for. */
function opening(
  version: Version,
  routes: Readonly<Record<string, readonly Reply[]>> = {},
  search = "",
  width = 1400,
) {
  laidOutAt(width, width);
  const sent = serving({ [`GET ${ADDRESS}`]: [reply(WORKFLOW)], ...routes });
  const refused = vi.fn<(problem: Problem) => void>();
  const written = vi.fn<() => void>();
  const shown = vi.fn<(content: ReadContent | null) => void>();
  // Each commit in which anything beneath the step's drawer was drawn again.
  const pageDrawn = vi.fn<ProfilerOnRenderCallback>();
  const router = createMemoryRouter(
    [
      {
        path: "/content",
        element: (
          <WorkflowContent
            groupId={GROUP}
            entryId={ENTRY}
            version={version}
            editable={version.stateActs?.has("write") === true}
            readCount={0}
            onRefused={refused}
            onWritten={written}
            onShown={shown}
            wrapDocument={(content) =>
              isValidElement<ComponentProps<typeof RowPanel>>(content)
                ? cloneElement(content, {
                    list: (
                      <Profiler id="page" onRender={pageDrawn}>
                        {content.props.list}
                      </Profiler>
                    ),
                  })
                : content
            }
          />
        ),
      },
    ],
    { initialEntries: [`/content${search}`] },
  );
  render(
    <ThemeProvider theme={theme}>
      <StandingProvider read={STANDING}>
        <RouterProvider router={router} />
      </StandingProvider>
    </ThemeProvider>,
  );
  return {
    sent,
    refused,
    written,
    shown,
    pageDrawn,
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
    'button[aria-expanded="false"]',
  );
  if (closed !== null) {
    fireEvent.click(closed);
  }
  const selected = region.querySelector<HTMLElement>(
    'button[aria-expanded="true"][aria-controls]',
  )!;
  const editor = document.getElementById(
    selected.getAttribute("aria-controls")!,
  )!;
  return within(editor).getByRole("textbox", { name: "Name" });
}

async function closeFieldEditor(partName: string): Promise<void> {
  const modalClose = screen.queryByRole("button", { name: "Close" });
  if (modalClose !== null) await userEvent.click(modalClose);
  else
    await userEvent.click(
      within(part(partName)).getByRole("button", { name: /^Close / }),
    );
  await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
}

/** Shuts the step's drawer by its own control, which lets the step go and leaves the page in reach. */
async function shutStep(): Promise<void> {
  await userEvent.click(
    within(stepDrawer()).getAllByLabelText("Close", { selector: "button" })[0]!,
  );
  await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
}

/** The step's drawer, the one modal dialog while a step is picked; a field's drawer in it is not modal. */
function stepDrawer(): HTMLElement {
  const drawer = document.querySelector<HTMLElement>(
    '[role="dialog"][aria-modal="true"]',
  )!;
  expect(drawer).toBeVisible();
  return drawer;
}

/** The page is read, whether or not a step's drawer covers it. */
async function opened(): Promise<void> {
  await screen.findByRole("heading", {
    name: "The steps",
    level: 3,
    hidden: true,
  });
}

function rowsOf(): HTMLElement[] {
  return within(part("The steps")).getAllByRole("row").slice(1);
}

/** The rows as drawn beneath a step's drawer: in sight, out of reach. */
function rowsBeneath(): HTMLElement[] {
  const heading = screen.getByRole("heading", {
    name: "The steps",
    level: 3,
    hidden: true,
  });
  return within(heading.parentElement!.parentElement!)
    .getAllByRole("row", { hidden: true })
    .slice(1);
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
  return screen.getByRole("dialog", { name });
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
  await screen.findByRole("button", { name: LOAD_SAVED });
  return session;
}

describe("WorkflowContent parent refresh and selection", () => {
  const route = {
    ...ASKING,
    name: "choose",
    producer: undefined,
    bindings: [],
    runs: {
      kind: "route",
      discriminator: {
        bindingId: "0000000e-0000-4000-8000-000000000cb4",
        source: { input: "category" },
      },
      gives: [SUMMARY],
      cases: [
        {
          caseId: "0000000d-0000-4000-8000-000000000cb1",
          term: "urgent",
          workflow: TRIAGE,
          takes: [COMPLAINT],
          gives: [SUMMARY],
          bindings: ASKING.bindings,
        },
      ],
    },
  };
  const routed = {
    ...WORKFLOW,
    takes: [
      COMPLAINT,
      {
        fieldId: "0000000b-0000-4000-8000-000000000cb4",
        name: "category",
        kind: "term",
        list: TRIAGE,
        many: false,
        mustBeGiven: true,
      },
    ],
    steps: [route, NOTIFYING],
    problems: [],
    terms: { [TRIAGE.versionId]: ["urgent", "later"] },
  };
  const canonicalField = "0000000b-0000-4000-8000-000000000cb9";
  const canonicalStep = "0000000c-0000-4000-8000-000000000cb5";
  const detail = {
    ...SUMMARY,
    fieldId: "0000000b-0000-4000-8000-000000000cb6",
    name: "detail",
  };
  const audit = {
    ...SUMMARY,
    fieldId: "0000000b-0000-4000-8000-000000000cb7",
    name: "audit",
  };

  function routeMoveReading(
    fields: readonly (typeof SUMMARY)[],
    otherWrites: Readonly<Record<string, readonly Reply[]>> = {},
    search = `?step=${ASKING.stepId}&keep=yes`,
  ) {
    const user = userEvent.setup();
    const moving = {
      ...route,
      runs: {
        ...route.runs,
        gives: fields,
        cases: route.runs.cases.map((each) => ({ ...each, gives: fields })),
      },
    };
    const before = { ...routed, steps: [moving, NOTIFYING] };
    const answer = deferred<readonly [string, number]>();
    const session = opening(
      DRAFT,
      {
        [`GET ${ADDRESS}`]: [reply(before)],
        [`PUT ${ADDRESS}/steps`]: [answer.promise],
        ...otherWrites,
      },
      search,
    );
    return { ...session, before, moving, answer, user };
  }

  function routeFieldsRegion(): HTMLElement {
    const heading = within(stepDrawer()).getByText(
      "What every case gives back",
      { selector: "h5" },
    );
    const region = heading.closest("section")!;
    expect(region).toHaveAccessibleName("What every case gives back");
    return region;
  }

  async function keepMoveCandidate(user: ReturnType<typeof userEvent.setup>) {
    const fieldsRegion = routeFieldsRegion();
    const summaryButton = within(fieldsRegion).getByLabelText(
      `Open ${isolatedInText("summary")}`,
      {
        selector: "button",
      },
    );
    await user.click(summaryButton);
    const editor = document.getElementById(
      summaryButton.getAttribute("aria-controls")!,
    )!;
    fireEvent.change(
      within(editor).getByLabelText("Help", { selector: "input,textarea" }),
      {
        target: { value: KEPT_HELP },
      },
    );
    expect(summaryButton).toHaveAccessibleName(
      `Close ${isolatedInText("summary")}`,
    );
    expect(summaryButton).toBeInTheDocument();
    await user.click(summaryButton);
    return fieldsRegion;
  }

  async function routeMoveOpened(
    fields: readonly (typeof SUMMARY)[],
    otherWrites: Readonly<Record<string, readonly Reply[]>> = {},
  ) {
    const session = routeMoveReading(fields, otherWrites);
    await opened();
    const fieldsRegion = await keepMoveCandidate(session.user);
    return { ...session, fieldsRegion };
  }

  async function shutMoveStep(user: ReturnType<typeof userEvent.setup>) {
    const drawer = stepDrawer();
    await user.click(
      within(drawer).getAllByLabelText("Close", { selector: "button" })[0]!,
    );
    await waitFor(() => expect(drawer).not.toBeInTheDocument());
    expect(screen.queryByRole("dialog")).toBeNull();
  }

  async function chooseMoveBinding(
    user: ReturnType<typeof userEvent.setup>,
    region: HTMLElement,
    label: RegExp,
    name: string,
  ) {
    const control = within(region).getByLabelText(label, {
      selector: '[role="combobox"]',
    });
    await user.click(control);
    const options = document.getElementById(
      control.getAttribute("aria-controls")!,
    )!;
    await user.click(within(options).getByRole("option", { name }));
  }

  it.each([
    ["summary", "down", [SUMMARY, detail], [detail, SUMMARY], "up"],
    ["detail", "up", [SUMMARY, detail], [detail, SUMMARY], "down"],
    [
      "summary",
      "down",
      [SUMMARY, detail, audit],
      [detail, SUMMARY, audit],
      "down",
    ],
  ] as const)(
    "immediately saves moving %s %s with only confirmed fields, preserves its candidate through canonical identities and restores its row button",
    async (name, direction, fields, after, focusDirection) => {
      const session = await routeMoveOpened(fields);
      let fieldsRegion = session.fieldsRegion;
      const names = () => {
        expect(fieldsRegion).toBeInTheDocument();
        return [
          ...fieldsRegion.querySelectorAll("tbody tr td:first-child bdi"),
        ].map((name) => name.textContent);
      };
      const beforeNames = names();
      const button = within(fieldsRegion).getByLabelText(
        `Move ${isolatedInText(name)} ${direction}`,
        { selector: "button" },
      );
      await session.user.click(button);
      await waitFor(() =>
        expect(requestsTo(session.sent)).toEqual([
          `GET ${ADDRESS}`,
          `PUT ${ADDRESS}/steps`,
        ]),
      );
      expect(names()).toEqual(beforeNames);
      expect(
        within(fieldsRegion).queryByLabelText(
          `Move ${isolatedInText(name)} ${direction}`,
          { selector: "button" },
        ),
      ).toBeNull();
      expect(bodyOf(session.sent, 1)).toMatchObject({
        revision: 3,
        steps: [
          {
            stepId: ASKING.stepId,
            runs: {
              gives: after.map((field) => ({
                fieldId: field.fieldId,
                name: field.name,
                help: null,
              })),
            },
          },
          { stepId: NOTIFYING.stepId },
        ],
      });
      expect(JSON.stringify(bodyOf(session.sent, 1))).not.toContain(KEPT_HELP);
      await act(async () =>
        session.answer.settle(
          reply({
            ...session.before,
            revision: 4,
            steps: [
              {
                ...session.moving,
                stepId: canonicalStep,
                runs: {
                  ...session.moving.runs,
                  gives: after.map((field) => ({
                    ...field,
                    fieldId:
                      field.name === "summary" ? canonicalField : field.fieldId,
                  })),
                },
              },
              NOTIFYING,
            ],
          }),
        ),
      );
      await waitFor(() => expect(session.written).toHaveBeenCalledTimes(1));
      fieldsRegion = routeFieldsRegion();
      expect(session.search().get("step")).toBe(canonicalStep);
      expect(session.search().get("keep")).toBe("yes");
      expect(names()).toEqual(after.map((field) => field.name));
      await waitFor(() =>
        expect(
          within(fieldsRegion).getByLabelText(
            `Move ${isolatedInText(name)} ${focusDirection}`,
            { selector: "button" },
          ),
        ).toHaveFocus(),
      );
      const said = `${isolatedInText(name)} moved to position ${after.findIndex((field) => field.name === name) + 1} of ${after.length}.`;
      expect(await within(stepDrawer()).findByText(said)).toBeVisible();
      expect(within(stepDrawer()).getAllByText(said)).toHaveLength(1);
      const summaryButton = within(fieldsRegion).getByLabelText(
        `Open ${isolatedInText("summary")}`,
        { selector: "button" },
      );
      await session.user.click(summaryButton);
      const editor = document.getElementById(
        summaryButton.getAttribute("aria-controls")!,
      )!;
      expect(
        within(editor).getByLabelText("Help", { selector: "input,textarea" }),
      ).toHaveValue(KEPT_HELP);
      const fieldDrawer = editor.closest<HTMLElement>(
        '[role="dialog"][aria-modal="false"]',
      )!;
      expect(fieldDrawer).toBeVisible();
      expect(
        within(fieldDrawer).getByRole("button", { name: "Done" }),
      ).toBeVisible();
      expect(screen.queryByText(DROPPED)).toBeNull();
      expect(requestsTo(session.sent)).toEqual([
        `GET ${ADDRESS}`,
        `PUT ${ADDRESS}/steps`,
      ]);
    },
  );

  it.each(["another control", "the closed step"] as const)(
    "does not restore a moved route field's button after the reader chooses %s while saving",
    async (where) => {
      const session = await routeMoveOpened([SUMMARY, detail]);
      await session.user.click(
        within(session.fieldsRegion).getByLabelText(
          `Move ${isolatedInText("summary")} down`,
          { selector: "button" },
        ),
      );
      await waitFor(() =>
        expect(requestsTo(session.sent)).toContain(`PUT ${ADDRESS}/steps`),
      );
      if (where === "another control")
        act(() =>
          within(stepDrawer()).getByRole("button", { name: "Close" }).focus(),
        );
      else await shutStep();
      await act(async () =>
        session.answer.settle(
          reply({
            ...session.before,
            revision: 4,
            steps: [
              {
                ...session.moving,
                stepId: canonicalStep,
                runs: {
                  ...session.moving.runs,
                  gives: [detail, { ...SUMMARY, fieldId: canonicalField }],
                },
              },
              NOTIFYING,
            ],
          }),
        ),
      );
      await waitFor(() => expect(session.written).toHaveBeenCalledTimes(1));
      if (where === "another control") {
        expect(session.search().get("step")).toBe(canonicalStep);
        expect(
          within(
            screen.getByRole("region", { name: "What every case gives back" }),
          ).getByRole("button", {
            name: `Move ${isolatedInText("summary")} up`,
          }),
        ).not.toHaveFocus();
      } else {
        expect(session.search().get("step")).toBeNull();
        expect(screen.queryByRole("dialog")).toBeNull();
        await session.user.click(within(rowsOf()[1]!).getByRole("link"));
        expect(
          within(stepDrawer()).queryByText(/moved to position/),
        ).toBeNull();
      }
      expect(requestsTo(session.sent)).toEqual([
        `GET ${ADDRESS}`,
        `PUT ${ADDRESS}/steps`,
      ]);
    },
  );

  it("keeps the route fields in their original order and the unconfirmed candidate when an immediate move is refused", async () => {
    const session = await routeMoveOpened([SUMMARY, detail]);
    const fieldsRegion = session.fieldsRegion;
    const names = () => {
      expect(fieldsRegion).toBeInTheDocument();
      return [
        ...fieldsRegion.querySelectorAll("tbody tr td:first-child bdi"),
      ].map((name) => name.textContent);
    };
    const before = names();
    await session.user.click(
      within(fieldsRegion).getByLabelText(
        `Move ${isolatedInText("summary")} down`,
        { selector: "button" },
      ),
    );
    await waitFor(() =>
      expect(requestsTo(session.sent)).toContain(`PUT ${ADDRESS}/steps`),
    );
    expect(names()).toEqual(before);
    await act(async () =>
      session.answer.settle(reply({ code: "ACT_NOT_PERMITTED" }, 403)),
    );
    await waitFor(() => expect(session.refused).toHaveBeenCalledTimes(1));
    expect(session.written).not.toHaveBeenCalled();
    expect(names()).toEqual(before);
    expect(session.search().get("step")).toBe(ASKING.stepId);
    expect(
      within(fieldsRegion).getByLabelText(
        `Move ${isolatedInText("summary")} down`,
        { selector: "button" },
      ),
    ).not.toHaveAttribute("aria-disabled");
    const summaryButton = within(fieldsRegion).getByLabelText(
      `Open ${isolatedInText("summary")}`,
      { selector: "button" },
    );
    await session.user.click(summaryButton);
    const editor = document.getElementById(
      summaryButton.getAttribute("aria-controls")!,
    )!;
    expect(
      within(editor).getByLabelText("Help", { selector: "input,textarea" }),
    ).toHaveValue(KEPT_HELP);
    await session.user.unhover(summaryButton);
    expect(JSON.stringify(bodyOf(session.sent, 1))).not.toContain(KEPT_HELP);
    expect(requestsTo(session.sent)).toEqual([
      `GET ${ADDRESS}`,
      `PUT ${ADDRESS}/steps`,
    ]);
  });

  it("immediately moves a route field with the current unsaved confirmed step and output edits, keeping its unconfirmed candidate out of the complete PUT", async () => {
    const session = routeMoveReading([SUMMARY, detail], {}, "?keep=yes");
    await opened();
    const steps = part("The steps");
    const output = part("What fills each value it gives back");
    await chooseMoveBinding(session.user, output, /^From, /, "Step 2: notify");
    await chooseMoveBinding(session.user, output, /^Which, /, "summary");
    await session.user.click(
      within(steps).getByLabelText(`Open ${isolatedInText("notify")}`, {
        selector: "a",
      }),
    );
    const notification = stepDrawer();
    fireEvent.change(
      within(notification).getByLabelText("Display name", {
        selector: "input,textarea",
      }),
      {
        target: { value: "Send claim notice" },
      },
    );
    fireEvent.change(
      within(notification).getByLabelText("How many attempts", {
        selector: "input,textarea",
      }),
      {
        target: { value: "3" },
      },
    );
    await chooseMoveBinding(
      session.user,
      notification,
      /^From, /,
      "What the workflow takes",
    );
    await chooseMoveBinding(
      session.user,
      notification,
      /^Which, /,
      "complaint",
    );
    await shutMoveStep(session.user);
    await session.user.click(
      within(steps).getByLabelText(`Open ${isolatedInText("choose")}`, {
        selector: "a",
      }),
    );
    expect(requestsTo(session.sent)).toEqual([`GET ${ADDRESS}`]);
    const fields = await keepMoveCandidate(session.user);
    await session.user.click(
      within(fields).getByLabelText(`Move ${isolatedInText("summary")} down`, {
        selector: "button",
      }),
    );
    await waitFor(() =>
      expect(requestsTo(session.sent)).toEqual([
        `GET ${ADDRESS}`,
        `PUT ${ADDRESS}/steps`,
      ]),
    );
    expect(bodyOf(session.sent, 1)).toEqual({
      revision: 3,
      steps: [
        {
          stepId: ASKING.stepId,
          name: "choose",
          label: null,
          runs: {
            kind: "route",
            discriminator: { input: "category" },
            gives: [detail, SUMMARY].map((field) => ({
              fieldId: field.fieldId,
              name: field.name,
              label: null,
              help: null,
              kind: "text",
              longest: 1000,
              lines: "several",
              many: false,
              most: null,
              mustBeGiven: true,
            })),
            cases: [
              {
                caseId: route.runs.cases[0]!.caseId,
                term: "urgent",
                workflow: TRIAGE.versionId,
                bindings: [
                  { target: "complaint", source: { input: "complaint" } },
                ],
              },
            ],
          },
          bindings: [],
        },
        {
          stepId: NOTIFYING.stepId,
          name: "notify",
          label: "Send claim notice",
          runs: { kind: "question", version: TRIAGE.versionId },
          producer: { kind: "person" },
          tries: 3,
          reviewer: null,
          bindings: [{ target: "complaint", source: { input: "complaint" } }],
        },
      ],
      outputs: [{ target: "summary", source: { step: 1, path: "summary" } }],
    });
    expect(JSON.stringify(bodyOf(session.sent, 1))).not.toContain(KEPT_HELP);
    const savedNotification = {
      ...NOTIFYING,
      label: "Send claim notice",
      tries: 3,
      bindings: [{ ...NOTIFYING.bindings[0]!, source: { input: "complaint" } }],
    };
    await act(async () =>
      session.answer.settle(
        reply({
          ...session.before,
          revision: 4,
          steps: [
            {
              ...session.moving,
              runs: { ...session.moving.runs, gives: [detail, SUMMARY] },
            },
            savedNotification,
          ],
          outputs: [
            { ...WORKFLOW.outputs[0]!, source: { step: 1, path: "summary" } },
          ],
        }),
      ),
    );
    await waitFor(() => expect(session.written).toHaveBeenCalledTimes(1));
    expect(session.refused).not.toHaveBeenCalled();
    const savedFields = routeFieldsRegion();
    const summaryButton = within(savedFields).getByLabelText(
      `Open ${isolatedInText("summary")}`,
      { selector: "button" },
    );
    await session.user.click(summaryButton);
    const editor = document.getElementById(
      summaryButton.getAttribute("aria-controls")!,
    )!;
    expect(
      within(editor).getByLabelText("Help", { selector: "input,textarea" }),
    ).toHaveValue(KEPT_HELP);
    await session.user.click(summaryButton);
    await shutMoveStep(session.user);
    const savedOutput = part("What fills each value it gives back");
    expect(
      within(savedOutput).getByLabelText(/^From, /, {
        selector: '[role="combobox"]',
      }),
    ).toHaveTextContent("Step 2: Send claim notice");
    expect(
      within(savedOutput).getByLabelText(/^Which, /, {
        selector: '[role="combobox"]',
      }),
    ).toHaveTextContent("summary");
    await session.user.click(
      within(steps).getByLabelText(
        `Open ${isolatedInText("Send claim notice")}`,
        {
          selector: "a",
        },
      ),
    );
    const savedNotificationDrawer = stepDrawer();
    expect(
      within(savedNotificationDrawer).getByLabelText("Display name", {
        selector: "input,textarea",
      }),
    ).toHaveValue("Send claim notice");
    expect(
      within(savedNotificationDrawer).getByLabelText("How many attempts", {
        selector: "input,textarea",
      }),
    ).toHaveValue("3");
    expect(
      within(savedNotificationDrawer).getByLabelText(/^From, /, {
        selector: '[role="combobox"]',
      }),
    ).toHaveTextContent("What the workflow takes");
    expect(
      within(savedNotificationDrawer).getByLabelText(/^Which, /, {
        selector: '[role="combobox"]',
      }),
    ).toHaveTextContent("complaint");
    expect(requestsTo(session.sent)).toEqual([
      `GET ${ADDRESS}`,
      `PUT ${ADDRESS}/steps`,
    ]);
  });

  it("holds an immediate route field move for another step's invalid confirmed name, preserving order and candidate until that reason is corrected", async () => {
    const session = routeMoveReading([SUMMARY, detail], {}, "?keep=yes");
    await opened();
    const steps = part("The steps");
    await session.user.click(
      within(steps).getByLabelText(`Open ${isolatedInText("notify")}`, {
        selector: "a",
      }),
    );
    fireEvent.change(
      within(stepDrawer()).getByLabelText("Name", {
        selector: "input,textarea",
      }),
      { target: { value: "notify invalid" } },
    );
    await shutMoveStep(session.user);
    await session.user.click(
      within(steps).getByLabelText(`Open ${isolatedInText("choose")}`, {
        selector: "a",
      }),
    );
    const fields = await keepMoveCandidate(session.user);
    const move = within(fields).getByLabelText(
      `Move ${isolatedInText("summary")} down`,
      { selector: "button" },
    );
    expect(move).toHaveAttribute("aria-disabled", "true");
    expect(move).toHaveAccessibleDescription(
      "notify invalid: A step's name is one to 63 lowercase English letters, digits and underscores, starting with a letter.",
    );
    await expect(session.user.click(move)).rejects.toThrow(
      "pointer-events: none",
    );
    await act(async () => move.focus());
    await session.user.keyboard("{Enter}");
    expect(
      [...fields.querySelectorAll("tbody tr td:first-child bdi")].map(
        (field) => field.textContent,
      ),
    ).toEqual(["summary", "detail"]);
    const summaryButton = within(fields).getByLabelText(
      `Open ${isolatedInText("summary")}`,
      { selector: "button" },
    );
    await session.user.click(summaryButton);
    const editor = document.getElementById(
      summaryButton.getAttribute("aria-controls")!,
    )!;
    expect(
      within(editor).getByLabelText("Help", { selector: "input,textarea" }),
    ).toHaveValue(KEPT_HELP);
    await session.user.click(summaryButton);
    expect(session.search().get("step")).toBe(ASKING.stepId);
    expect(session.written).not.toHaveBeenCalled();
    expect(session.refused).not.toHaveBeenCalled();
    expect(requestsTo(session.sent)).toEqual([`GET ${ADDRESS}`]);
    await shutMoveStep(session.user);
    await session.user.click(
      within(steps).getByLabelText(`Open ${isolatedInText("notify invalid")}`, {
        selector: "a",
      }),
    );
    fireEvent.change(
      within(stepDrawer()).getByLabelText("Name", {
        selector: "input,textarea",
      }),
      { target: { value: "notify" } },
    );
    await shutMoveStep(session.user);
    await session.user.click(
      within(steps).getByLabelText(`Open ${isolatedInText("choose")}`, {
        selector: "a",
      }),
    );
    const restored = routeFieldsRegion();
    const available = within(restored).getByLabelText(
      `Move ${isolatedInText("summary")} down`,
      { selector: "button" },
    );
    expect(available).not.toHaveAttribute("aria-disabled", "true");
    expect(available).toHaveAccessibleDescription("Move down");
    await session.user.click(available);
    await waitFor(() =>
      expect(requestsTo(session.sent)).toEqual([
        `GET ${ADDRESS}`,
        `PUT ${ADDRESS}/steps`,
      ]),
    );
    expect(bodyOf(session.sent, 1)).toMatchObject({ revision: 3 });
    expect(JSON.stringify(bodyOf(session.sent, 1))).not.toContain(KEPT_HELP);
  });

  it("holds an immediate route field move while run settings are being saved, retaining order and candidate and then using the saved revision", async () => {
    const settings = deferred<readonly [string, number]>();
    const session = await routeMoveOpened([SUMMARY, detail], {
      [`PUT ${ADDRESS}/ceiling`]: [settings.promise],
    });
    await shutStep();
    const settingsToggle = screen.getByRole("button", { name: "Run settings" });
    await session.user.click(settingsToggle);
    const settingsBody = document.getElementById(
      settingsToggle.getAttribute("aria-controls")!,
    )!;
    fireEvent.change(
      within(settingsBody).getByRole("textbox", { name: "Usage limit" }),
      {
        target: { value: "1000" },
      },
    );
    await session.user.click(
      within(settingsBody).getByRole("button", {
        name: "Save what a run may spend",
      }),
    );
    await waitFor(() =>
      expect(requestsTo(session.sent)).toEqual([
        `GET ${ADDRESS}`,
        `PUT ${ADDRESS}/ceiling`,
      ]),
    );
    await session.user.click(
      screen.getByRole("link", { name: `Open ${isolatedInText("choose")}` }),
    );
    const fields = routeFieldsRegion();
    const move = within(fields).getByLabelText(
      `Move ${isolatedInText("summary")} down`,
      { selector: "button" },
    );
    expect(move).toHaveAttribute("aria-disabled", "true");
    expect(
      screen.getByText(
        "It is being saved, and that can no longer be called back.",
      ),
    ).toBeVisible();
    await expect(session.user.click(move)).rejects.toThrow(
      "pointer-events: none",
    );
    await act(async () => move.focus());
    await session.user.keyboard("{Enter}");
    expect(
      [...fields.querySelectorAll("tbody tr td:first-child bdi")].map(
        (field) => field.textContent,
      ),
    ).toEqual(["summary", "detail"]);
    const summaryButton = within(fields).getByLabelText(
      `Open ${isolatedInText("summary")}`,
      { selector: "button" },
    );
    await session.user.click(summaryButton);
    const editor = document.getElementById(
      summaryButton.getAttribute("aria-controls")!,
    )!;
    expect(
      within(editor).getByLabelText("Help", { selector: "input,textarea" }),
    ).toHaveValue(KEPT_HELP);
    await session.user.click(summaryButton);
    expect(session.refused).not.toHaveBeenCalled();
    expect(session.written).not.toHaveBeenCalled();
    expect(requestsTo(session.sent)).toEqual([
      `GET ${ADDRESS}`,
      `PUT ${ADDRESS}/ceiling`,
    ]);
    await act(async () =>
      settings.settle(
        reply({ ...session.before, revision: 4, ceiling: "1000" }),
      ),
    );
    await waitFor(() => expect(session.written).toHaveBeenCalledTimes(1));
    const available = within(stepDrawer()).getByRole("button", {
      name: `Move ${isolatedInText("summary")} down`,
    });
    expect(available).not.toHaveAttribute("aria-disabled", "true");
    await session.user.click(available);
    await waitFor(() =>
      expect(requestsTo(session.sent)).toEqual([
        `GET ${ADDRESS}`,
        `PUT ${ADDRESS}/ceiling`,
        `PUT ${ADDRESS}/steps`,
      ]),
    );
    expect(bodyOf(session.sent, 2)).toMatchObject({
      revision: 4,
      steps: [
        { runs: { gives: [{ name: "detail" }, { name: "summary" }] } },
        { stepId: NOTIFYING.stepId },
      ],
    });
    expect(JSON.stringify(bodyOf(session.sent, 2))).not.toContain(KEPT_HELP);
  });

  /** The route's help typed and taken with Done, the save of the steps answering canonical identities. */
  async function routeHelpDone() {
    const session = opening(
      DRAFT,
      {
        [`GET ${ADDRESS}`]: [reply(routed)],
        [`PUT ${ADDRESS}/steps`]: [
          reply({
            ...routed,
            revision: 4,
            steps: [
              {
                ...route,
                stepId: canonicalStep,
                runs: {
                  ...route.runs,
                  gives: [
                    {
                      ...SUMMARY,
                      fieldId: canonicalField,
                      help: "Route raw help.",
                    },
                  ],
                },
              },
              NOTIFYING,
            ],
          }),
        ],
      },
      `?step=${ASKING.stepId}&keep=yes`,
    );
    await opened();
    const routeGives = routeFieldsRegion();
    const open = within(routeGives).getByLabelText(
      `Open ${isolatedInText("summary")}`,
      { selector: "button" },
    );
    await userEvent.click(open);
    const editor = document.getElementById(
      open.getAttribute("aria-controls")!,
    )!;
    const help = within(editor).getByRole("textbox", { name: "Help" });
    await userEvent.clear(help);
    await userEvent.paste("Route raw help.");
    expect(requestsTo(session.sent)).toEqual([`GET ${ADDRESS}`]);
    await userEvent.click(
      within(editor.closest('[role="dialog"]')!).getByRole("button", {
        name: "Done",
      }),
    );
    return session;
  }

  /** The same, the steps saved and the answer drawn. */
  async function routeHelpSaved() {
    const session = await routeHelpDone();
    await userEvent.click(
      within(stepDrawer()).getByRole("button", { name: SAVE_STEPS }),
    );
    await waitFor(() => expect(session.written).toHaveBeenCalledTimes(1));
    return session;
  }

  it("keeps route Done local, sending nothing until the steps are saved", async () => {
    const { sent, written } = await routeHelpDone();

    expect(requestsTo(sent)).toEqual([`GET ${ADDRESS}`]);
    expect(written).not.toHaveBeenCalled();
    expect(saveSteps()).not.toHaveAttribute("aria-disabled");
  });

  it("saves the complete flow with its revision once route Done is followed by saving the steps, picking the step the answer names", async () => {
    const { sent, search } = await routeHelpSaved();

    expect(requestsTo(sent)).toEqual([
      `GET ${ADDRESS}`,
      `PUT ${ADDRESS}/steps`,
    ]);
    expect(bodyOf(sent, 1)).toEqual({
      revision: 3,
      steps: [
        {
          stepId: ASKING.stepId,
          name: "choose",
          label: null,
          runs: {
            kind: "route",
            discriminator: { input: "category" },
            gives: [
              {
                fieldId: SUMMARY.fieldId,
                name: "summary",
                label: null,
                help: "Route raw help.",
                kind: "text",
                longest: 1000,
                lines: "several",
                many: false,
                most: null,
                mustBeGiven: true,
              },
            ],
            cases: [
              {
                caseId: "0000000d-0000-4000-8000-000000000cb1",
                term: "urgent",
                workflow: TRIAGE.versionId,
                bindings: [
                  { target: "complaint", source: { input: "complaint" } },
                ],
              },
            ],
          },
          bindings: [],
        },
        {
          stepId: NOTIFYING.stepId,
          name: "notify",
          label: null,
          runs: { kind: "question", version: TRIAGE.versionId },
          producer: { kind: "person" },
          tries: null,
          reviewer: null,
          bindings: [
            { target: "complaint", source: { step: 0, path: "summary" } },
          ],
        },
      ],
      outputs: [{ target: "summary", source: { step: 0, path: "summary" } }],
    });
    expect(search().get("step")).toBe(canonicalStep);
    expect(search().get("keep")).toBe("yes");
  });

  it("reopens the route's field as the save answered it, with nothing left to take and nothing more sent", async () => {
    const { sent } = await routeHelpSaved();

    await shutStep();
    await userEvent.click(
      screen.getByRole("link", { name: `Open ${isolatedInText("choose")}` }),
    );
    const reopened = await waitFor(routeFieldsRegion);
    const edit = within(reopened).getByLabelText(
      `Open ${isolatedInText("summary")}`,
      { selector: "button" },
    );
    await userEvent.click(edit);
    const editor = document.getElementById(
      edit.getAttribute("aria-controls")!,
    )!;
    expect(within(editor).getByRole("textbox", { name: "Help" })).toHaveValue(
      "Route raw help.",
    );
    expect(within(editor).getByRole("textbox", { name: "Name" })).toHaveValue(
      "summary",
    );
    expect(screen.queryByRole("button", { name: "Done" })).toBeNull();
    expect(requestsTo(sent)).toEqual([
      `GET ${ADDRESS}`,
      `PUT ${ADDRESS}/steps`,
    ]);
  });

  it("saves a later local route candidate using its canonical field and step identities and the revision read", async () => {
    const prior = {
      ...routed,
      revision: 4,
      steps: [
        {
          ...route,
          stepId: canonicalStep,
          runs: {
            ...route.runs,
            gives: [
              { ...SUMMARY, fieldId: canonicalField, help: "Route raw help." },
            ],
          },
        },
        NOTIFYING,
      ],
    };
    const { sent, written, search } = opening(
      DRAFT,
      {
        [`GET ${ADDRESS}`]: [reply(prior)],
        [`PUT ${ADDRESS}/steps`]: [
          reply({
            ...prior,
            revision: 5,
            steps: [
              {
                ...prior.steps[0]!,
                runs: {
                  ...route.runs,
                  gives: [
                    {
                      ...SUMMARY,
                      fieldId: canonicalField,
                      help: "Route raw help. Next.",
                    },
                  ],
                },
              },
              NOTIFYING,
            ],
          }),
        ],
      },
      `?step=${canonicalStep}&keep=yes`,
    );
    await opened();
    const routeGives = screen.getByRole("region", {
      name: "What every case gives back",
    });
    await userEvent.click(
      within(routeGives).getByRole("button", { name: /^Open/ }),
    );
    const help = screen.getByRole("textbox", { name: "Help" });
    expect(help).toHaveValue("Route raw help.");
    await userEvent.clear(help);
    await userEvent.paste("Route raw help. Next.");
    await userEvent.click(screen.getByRole("button", { name: "Done" }));
    expect(requestsTo(sent)).toEqual([`GET ${ADDRESS}`]);
    expect(written).not.toHaveBeenCalled();
    await userEvent.click(saveSteps());
    await waitFor(() => expect(written).toHaveBeenCalledTimes(1));
    expect(requestsTo(sent)).toEqual([
      `GET ${ADDRESS}`,
      `PUT ${ADDRESS}/steps`,
    ]);
    expect(bodyOf(sent, 1)).toMatchObject({
      revision: 4,
      steps: [
        {
          stepId: canonicalStep,
          runs: {
            gives: [{ fieldId: canonicalField, help: "Route raw help. Next." }],
          },
        },
        { stepId: NOTIFYING.stepId },
      ],
      outputs: [{ target: "summary", source: { step: 0, path: "summary" } }],
    });
    expect(search().get("step")).toBe(canonicalStep);
    expect(search().get("keep")).toBe("yes");
  });

  it("sends the next save of the flow under the field and step identities and revision the last save answered", async () => {
    const answered = (help: string, revision: number) => ({
      ...routed,
      revision,
      steps: [
        {
          ...route,
          stepId: canonicalStep,
          runs: {
            ...route.runs,
            gives: [{ ...SUMMARY, fieldId: canonicalField, help }],
          },
        },
        NOTIFYING,
      ],
    });
    const { sent, written } = opening(
      DRAFT,
      {
        [`GET ${ADDRESS}`]: [reply(routed)],
        [`PUT ${ADDRESS}/steps`]: [
          reply(answered("Route raw help.", 4)),
          reply(answered("Route raw help. Again.", 5)),
        ],
      },
      `?step=${ASKING.stepId}`,
    );
    await opened();
    const editHelp = async (help: string) => {
      const open = within(await waitFor(routeFieldsRegion)).getByLabelText(
        `Open ${isolatedInText("summary")}`,
        { selector: "button" },
      );
      await userEvent.click(open);
      const editor = document.getElementById(
        open.getAttribute("aria-controls")!,
      )!;
      const box = within(editor).getByRole("textbox", { name: "Help" });
      await userEvent.clear(box);
      await userEvent.paste(help);
      await userEvent.click(
        within(editor.closest('[role="dialog"]')!).getByRole("button", {
          name: "Done",
        }),
      );
    };
    await editHelp("Route raw help.");
    await userEvent.click(saveSteps());
    await waitFor(() => expect(written).toHaveBeenCalledTimes(1));

    await editHelp("Route raw help. Again.");
    await userEvent.click(saveSteps());

    await waitFor(() => expect(written).toHaveBeenCalledTimes(2));
    expect(requestsTo(sent)).toEqual([
      `GET ${ADDRESS}`,
      `PUT ${ADDRESS}/steps`,
      `PUT ${ADDRESS}/steps`,
    ]);
    expect(bodyOf(sent, 2)).toMatchObject({
      revision: 4,
      steps: [
        {
          stepId: canonicalStep,
          runs: {
            gives: [
              { fieldId: canonicalField, help: "Route raw help. Again." },
            ],
          },
        },
        { stepId: NOTIFYING.stepId },
      ],
    });
    const second = JSON.stringify(bodyOf(sent, 2));
    expect(second).not.toContain(ASKING.stepId);
    expect(second).not.toContain(SUMMARY.fieldId);
  });

  /** The route's own field opened in its step's drawer, nothing typed yet. */
  async function routeFieldOpened(
    routes: Readonly<Record<string, readonly Reply[]>> = {},
  ) {
    const session = opening(
      DRAFT,
      { [`GET ${ADDRESS}`]: [reply(routed)], ...routes },
      `?step=${ASKING.stepId}`,
    );
    await opened();
    await userEvent.click(
      within(routeFieldsRegion()).getByLabelText(/^Open/, {
        selector: "button",
      }),
    );
    return session;
  }

  /** The route's help typed and not taken with Done, its step's drawer shut on it. */
  async function routeHelpKept(
    routes: Readonly<Record<string, readonly Reply[]>> = {},
    renamed = false,
  ) {
    const session = await routeFieldOpened(routes);
    if (renamed)
      fireEvent.change(
        within(stepDrawer()).getByRole("textbox", { name: "Display name" }),
        { target: { value: "Choose the way" } },
      );
    fireEvent.change(
      within(stepDrawer()).getByLabelText("Help", {
        selector: "input,textarea",
      }),
      {
        target: { value: KEPT_HELP },
      },
    );
    await shutStep();
    return session;
  }

  async function routeReopened(): Promise<HTMLElement> {
    await userEvent.click(within(rowsOf()[0]!).getByRole("link"));
    return screen.findByRole("textbox", { name: "Help" });
  }

  it("draws nothing beneath the step's drawer again as a route field's change goes on being typed, once its step holds one, and draws it as the step itself changes", async () => {
    const { pageDrawn } = await routeFieldOpened();
    const help = screen.getByRole("textbox", { name: "Help" });
    fireEvent.change(help, { target: { value: "R" } });

    pageDrawn.mockClear();
    for (const typed of ["Ro", "Rou", "Rout"])
      fireEvent.change(help, { target: { value: typed } });

    expect(pageDrawn).not.toHaveBeenCalled();
    expect(help).toHaveValue("Rout");

    fireEvent.change(
      within(stepDrawer()).getByRole("textbox", { name: "Display name" }),
      { target: { value: "Choose" } },
    );

    expect(pageDrawn).toHaveBeenCalled();
  });

  it.each([
    ["moved down", "moving"],
    [
      "read again at a new revision after its run settings are saved",
      "settings",
    ],
  ] as const)(
    "keeps a route field's change not taken in while its step is %s, there again as the step is reopened and never sent",
    async (_case, way) => {
      const { sent, written } = await routeHelpKept({
        [`PUT ${ADDRESS}/steps`]: [
          reply({ ...routed, revision: 4, steps: [NOTIFYING, route] }),
        ],
        [`PUT ${ADDRESS}/ceiling`]: [
          reply({ ...routed, revision: 4, ceiling: "5" }),
        ],
      });
      if (way === "moving")
        await userEvent.click(
          rowsOf()[0]!.querySelector<HTMLElement>('[data-part="down"] button')!,
        );
      else {
        await userEvent.click(
          screen.getByRole("button", { name: "Run settings" }),
        );
        await userEvent.type(
          screen.getByRole("textbox", { name: "Usage limit" }),
          "5",
        );
        await userEvent.click(
          screen.getByRole("button", { name: "Save what a run may spend" }),
        );
        await waitFor(() =>
          expect(requestsTo(sent)).toContain(`PUT ${ADDRESS}/ceiling`),
        );
      }
      if (way === "moving") {
        await waitFor(() => expect(written).toHaveBeenCalledTimes(1));
        expect(rowWords()[1]).toContain("choose");
      }

      const help =
        way === "moving"
          ? (await userEvent.click(within(rowsOf()[1]!).getByRole("link")),
            await screen.findByRole("textbox", { name: "Help" }))
          : await routeReopened();

      expect(help).toHaveValue(KEPT_HELP);
      expect(
        requestsTo(sent).filter((each) => each.endsWith("/steps")),
      ).toEqual(way === "moving" ? [`PUT ${ADDRESS}/steps`] : []);
      if (way === "moving")
        expect(JSON.stringify(bodyOf(sent, 1))).not.toContain(KEPT_HELP);
    },
  );

  it.each(["saved", "refused"] as const)(
    "moves a confirmed step at once with the complete flow, retaining its unconfirmed field when %s",
    async (outcome) => {
      const user = userEvent.setup();
      const answer = deferred<readonly [string, number]>();
      const { sent, written, refused } = await routeHelpKept(
        { [`PUT ${ADDRESS}/steps`]: [answer.promise] },
        true,
      );
      await user.click(
        screen.getByLabelText(`Open ${isolatedInText("notify")}`, {
          selector: "a",
        }),
      );
      fireEvent.change(
        within(stepDrawer()).getByLabelText("Display name", {
          selector: "input",
        }),
        { target: { value: "Send claim notice" } },
      );
      await shutStep();
      const before = rowWords();
      const down = screen.getByLabelText(
        `Move down: ${isolatedInText("Choose the way")}`,
        { selector: "button" },
      );
      await user.click(down);
      await waitFor(() =>
        expect(requestsTo(sent)).toEqual([
          `GET ${ADDRESS}`,
          `PUT ${ADDRESS}/steps`,
        ]),
      );
      expect(rowWords()).toEqual(before);
      expect(down).toHaveAttribute("aria-disabled", "true");
      await user.click(down);
      await act(async () => down.focus());
      await user.keyboard("{Enter}");
      expect(requestsTo(sent)).toEqual([
        `GET ${ADDRESS}`,
        `PUT ${ADDRESS}/steps`,
      ]);
      expect(bodyOf(sent, 1)).toMatchObject({
        revision: 3,
        steps: [
          { stepId: NOTIFYING.stepId, label: "Send claim notice" },
          {
            stepId: route.stepId,
            label: "Choose the way",
            runs: { gives: [{ name: "summary", help: null }] },
          },
        ],
        outputs: [{ target: "summary", source: { step: 1, path: "summary" } }],
      });
      expect(JSON.stringify(bodyOf(sent, 1))).not.toContain(KEPT_HELP);
      await act(async () =>
        answer.settle(
          outcome === "saved"
            ? reply({
                ...routed,
                revision: 4,
                steps: [
                  {
                    ...NOTIFYING,
                    label: "Send claim notice",
                    bindings: [
                      {
                        ...NOTIFYING.bindings[0]!,
                        source: { step: 1, path: "summary" },
                      },
                    ],
                  },
                  {
                    ...route,
                    label: "Choose the way",
                    stepId: canonicalStep,
                    runs: {
                      ...route.runs,
                      gives: [{ ...SUMMARY, fieldId: canonicalField }],
                    },
                  },
                ],
                outputs: [
                  {
                    ...WORKFLOW.outputs[0]!,
                    source: { step: 1, path: "summary" },
                  },
                ],
              })
            : reply({ code: "DRAFT_WRITTEN_SINCE_READ" }, 409),
        ),
      );
      if (outcome === "saved") {
        await waitFor(() => expect(written).toHaveBeenCalledTimes(1));
        expect(rowWords()[1]).toContain("Choose the way");
        await waitFor(() =>
          expect(
            screen.getByLabelText(
              `Move up: ${isolatedInText("Choose the way")}`,
              { selector: "button" },
            ),
          ).toHaveFocus(),
        );
        expect(within(part("The steps")).getByRole("status")).toHaveTextContent(
          `${isolatedInText("Choose the way")} moved to position 2 of 2.`,
        );
        expect(refused).not.toHaveBeenCalled();
      } else {
        await waitFor(() => expect(refused).toHaveBeenCalledTimes(1));
        expect(rowWords()).toEqual(before);
        expect(written).not.toHaveBeenCalled();
        expect(screen.getByRole("button", { name: LOAD_SAVED })).toBeVisible();
        expect(saveSteps()).not.toHaveAttribute("aria-disabled", "true");
      }
      await user.click(
        within(rowsOf()[outcome === "saved" ? 1 : 0]!).getByRole("link"),
      );
      expect(await screen.findByRole("textbox", { name: "Help" })).toHaveValue(
        KEPT_HELP,
      );
      expect(screen.getByRole("textbox", { name: "Display name" })).toHaveValue(
        "Choose the way",
      );
      expect(screen.getByRole("button", { name: "Done" })).toBeVisible();
      expect(requestsTo(sent)).toEqual([
        `GET ${ADDRESS}`,
        `PUT ${ADDRESS}/steps`,
      ]);
    },
  );

  it("lets a route field's change not taken in go with its step, saying so once where the steps are until the next change", async () => {
    await routeHelpKept();
    expect(screen.queryByText(DROPPED)).toBeNull();

    await userEvent.click(
      screen.getByRole("link", { name: `Open ${isolatedInText("choose")}` }),
    );
    await userEvent.click(
      screen.getByRole("button", { name: "Remove this step" }),
    );
    await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());

    expect(screen.getByText(DROPPED)).toBeVisible();
    expect(rowWords().some((words) => words.includes("choose"))).toBe(false);

    await userEvent.click(screen.getByRole("button", { name: "Add a step" }));

    expect(screen.queryByText(DROPPED)).toBeNull();
  });

  it("carries a route field's change not taken in over a save of the steps, sending only what was confirmed and laying it on what the save answered", async () => {
    const { sent } = await routeHelpKept(
      {
        [`PUT ${ADDRESS}/steps`]: [
          reply({
            ...routed,
            revision: 4,
            steps: [
              { ...route, stepId: canonicalStep, label: "Choose the way" },
              NOTIFYING,
            ],
          }),
        ],
      },
      true,
    );

    await userEvent.click(saveSteps());
    await waitFor(() =>
      expect(requestsTo(sent)).toContain(`PUT ${ADDRESS}/steps`),
    );

    expect(bodyOf(sent, 1)).toMatchObject({
      steps: [
        {
          label: "Choose the way",
          runs: { gives: [{ help: null }] },
        },
        {},
      ],
    });
    expect(await routeReopened()).toHaveValue(KEPT_HELP);
    expect(screen.queryByText(DROPPED)).toBeNull();
  });

  it("lets a route field's change not taken in go once the reader loads the saved draft", async () => {
    const { sent } = await routeHelpKept(
      {
        [`GET ${ADDRESS}`]: [reply(routed), reply({ ...routed, revision: 5 })],
        [`PUT ${ADDRESS}/steps`]: [
          reply({ code: "DRAFT_WRITTEN_SINCE_READ" }, 409),
        ],
      },
      true,
    );
    await userEvent.click(saveSteps());
    await userEvent.click(
      await screen.findByRole("button", { name: LOAD_SAVED }),
    );
    await waitFor(() =>
      expect(
        requestsTo(sent).filter((each) => each.startsWith("GET")),
      ).toHaveLength(2),
    );

    await userEvent.click(within(rowsOf()[0]!).getByRole("link"));
    const routeGives = await screen.findByRole("region", {
      name: "What every case gives back",
    });
    expect(screen.queryByRole("textbox", { name: "Help" })).toBeNull();
    await userEvent.click(
      within(routeGives).getByRole("button", { name: /^Open/ }),
    );
    expect(screen.getByRole("textbox", { name: "Help" })).toHaveValue("");
  });

  it("reloads the same version when its parent read count changes and holds writes until the new revision arrives", async () => {
    const afresh = deferred<readonly [string, number]>();
    const sent = serving({
      [`GET ${ADDRESS}`]: [reply(WORKFLOW), afresh.promise],
    });
    const written = vi.fn<() => void>();
    const refused = vi.fn<(problem: Problem) => void>();
    const shown = vi.fn<(content: ReadContent | null) => void>();
    // The host's own counts come from outside its router, so it is drawn
    // anew by rerendering rather than by controls the step's drawer hides.
    const HostCounts = createContext({ readCount: 0 });
    function Host() {
      const { readCount } = useContext(HostCounts);
      return (
        <WorkflowContent
          groupId={GROUP}
          entryId={ENTRY}
          version={DRAFT}
          editable
          readCount={readCount}
          onRefused={refused}
          onWritten={written}
          onShown={shown}
        />
      );
    }
    const router = createMemoryRouter(
      [{ path: "/content", element: <Host /> }],
      {
        initialEntries: [`/content?step=${ASKING.stepId}&keep=yes`],
      },
    );
    // A new value each time, so the host is drawn anew even at the same count.
    const hosting = (readCount: number) => (
      <ThemeProvider theme={theme}>
        <StandingProvider read={STANDING}>
          <HostCounts.Provider value={{ readCount }}>
            <RouterProvider router={router} />
          </HostCounts.Provider>
        </StandingProvider>
      </ThemeProvider>
    );
    laidOutAt(1400, 1400);
    const { rerender } = render(hosting(0));
    const answered = {
      ...WORKFLOW,
      revision: 8,
      steps: [{ ...ASKING, name: "fresh_triage", tries: 4 }],
      problems: [],
    };
    try {
      await opened();
      fireEvent.change(
        within(panel(isolatedInText("triage"))).getByRole("textbox", {
          name: "Name",
        }),
        { target: { value: "unsaved_triage" } },
      );
      rerender(hosting(1));
      await waitFor(() =>
        expect(requestsTo(sent)).toEqual([`GET ${ADDRESS}`, `GET ${ADDRESS}`]),
      );
      expect(
        within(panel(isolatedInText("unsaved_triage"))).getByRole("textbox", {
          name: "Name",
        }),
      ).toHaveValue("unsaved_triage");
      expect(saveSteps()).toHaveAttribute("aria-disabled", "true");
      await act(async () => saveSteps().focus());
      await userEvent.keyboard("{Enter}");
      expect(written).not.toHaveBeenCalled();
      expect(refused).not.toHaveBeenCalled();
      expect(requestsTo(sent)).toEqual([`GET ${ADDRESS}`, `GET ${ADDRESS}`]);
      expect(router.state.location.search).toBe(
        `?step=${ASKING.stepId}&keep=yes`,
      );
      afresh.settle(reply(answered) as readonly [string, number]);
      await screen.findByRole("dialog", {
        name: isolatedInText("fresh_triage"),
      });
      expect(
        within(panel(isolatedInText("fresh_triage"))).getByRole("textbox", {
          name: "How many attempts",
        }),
      ).toHaveValue("4");
      expect(screen.queryByRole("button", { name: SAVE_STEPS })).toBeNull();
      expect(
        screen.queryByRole("dialog", {
          name: isolatedInText("unsaved_triage"),
        }),
      ).toBeNull();
      expect(screen.queryByText("Unsaved")).toBeNull();
      expect(rowsBeneath()).toHaveLength(1);
      expect(rowsBeneath()[0]!.querySelector("a bdi")?.textContent).toBe(
        "fresh_triage",
      );
      fireEvent.change(
        within(panel(isolatedInText("fresh_triage"))).getByRole("textbox", {
          name: "Name",
        }),
        { target: { value: "fresh_triage_typed" } },
      );
      expect(saveSteps()).not.toHaveAttribute("aria-disabled");
      rerender(hosting(1));
      expect(
        within(panel(isolatedInText("fresh_triage_typed"))).getByRole(
          "textbox",
          { name: "Name" },
        ),
      ).toHaveValue("fresh_triage_typed");
      expect(requestsTo(sent)).toEqual([`GET ${ADDRESS}`, `GET ${ADDRESS}`]);
      expect(written).not.toHaveBeenCalled();
      expect(refused).not.toHaveBeenCalled();
    } finally {
      await act(async () =>
        afresh.settle(reply(answered) as readonly [string, number]),
      );
    }
  });

  it("removes only the selected step query when a shorter save reply has no step at its old position", async () => {
    const initial = {
      ...WORKFLOW,
      steps: [ASKING, NOTIFYING, CLOSING],
      problems: [],
    };
    const answered = {
      ...WORKFLOW,
      revision: 4,
      steps: [ASKING],
      problems: [],
    };
    const { sent, written, refused, search } = opening(
      DRAFT,
      {
        [`GET ${ADDRESS}`]: [reply(initial)],
        [`PUT ${ADDRESS}/steps`]: [reply(answered)],
      },
      `?step=${CLOSING.stepId}&keep=yes`,
    );
    await opened();
    fireEvent.change(
      within(panel(isolatedInText("close"))).getByRole("textbox", {
        name: "Name",
      }),
      { target: { value: "closed" } },
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
          label: null,
          runs: { kind: "question", version: TRIAGE.versionId },
          producer: { kind: "person" },
          tries: null,
          reviewer: null,
          bindings: [{ target: "complaint", source: { input: "complaint" } }],
        },
        {
          stepId: NOTIFYING.stepId,
          name: "notify",
          label: null,
          runs: { kind: "question", version: TRIAGE.versionId },
          producer: { kind: "person" },
          tries: null,
          reviewer: null,
          bindings: [
            { target: "complaint", source: { step: 0, path: "summary" } },
          ],
        },
        {
          stepId: CLOSING.stepId,
          name: "closed",
          label: null,
          runs: { kind: "question", version: TRIAGE.versionId },
          producer: { kind: "person" },
          tries: null,
          reviewer: null,
          bindings: [],
        },
      ],
      outputs: [{ target: "summary", source: { step: 0, path: "summary" } }],
    });
    await waitFor(() => expect(search().get("step")).toBeNull());
    expect([...search().entries()]).toEqual([["keep", "yes"]]);
    await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
    expect(rowsOf()).toHaveLength(1);
    expect(rowsOf()[0]!.querySelector("a bdi")?.textContent).toBe("triage");
    expect(screen.queryByText("Unsaved")).toBeNull();
    expect(written).toHaveBeenCalledTimes(1);
    expect(requestsTo(sent)).toEqual([
      `GET ${ADDRESS}`,
      `PUT ${ADDRESS}/steps`,
    ]);
  });
});

describe("WorkflowContent", () => {
  it.each([
    [0xa7ce, 0xa7cf],
    [0xa7d2, 0xa7d3],
    [0xa7d4, 0xa7d5],
    [0x16ea0, 0x16ebb],
    [0x16ea1, 0x16ebc],
    [0x16ea2, 0x16ebd],
    [0x16ea3, 0x16ebe],
    [0x16ea4, 0x16ebf],
    [0x16ea5, 0x16ec0],
    [0x16ea6, 0x16ec1],
    [0x16ea7, 0x16ec2],
    [0x16ea8, 0x16ec3],
    [0x16ea9, 0x16ec4],
    [0x16eaa, 0x16ec5],
    [0x16eab, 0x16ec6],
    [0x16eac, 0x16ec7],
    [0x16ead, 0x16ec8],
    [0x16eae, 0x16ec9],
    [0x16eaf, 0x16eca],
    [0x16eb0, 0x16ecb],
    [0x16eb1, 0x16ecc],
    [0x16eb2, 0x16ecd],
    [0x16eb3, 0x16ece],
    [0x16eb4, 0x16ecf],
    [0x16eb5, 0x16ed0],
    [0x16eb6, 0x16ed1],
    [0x16eb7, 0x16ed2],
    [0x16eb8, 0x16ed3],
  ] as const)(
    "saves distinct Unicode code points %i and %i through the editor",
    async (left, right) => {
      const label = String.fromCodePoint(left);
      const otherLabel = String.fromCodePoint(right);
      const other = {
        ...ASKING,
        stepId: "0000000c-0000-4000-8000-000000000cb8",
        name: "other",
        label: otherLabel,
        tries: 3,
        bindings: [
          {
            ...ASKING.bindings[0]!,
            bindingId: "0000000e-0000-4000-8000-000000000cb8",
          },
        ],
      };
      const workflow = {
        ...WORKFLOW,
        steps: [{ ...ASKING, tries: 3 }, other],
        problems: [],
      };
      const { sent, written, refused } = opening(
        DRAFT,
        {
          [`GET ${ADDRESS}`]: [reply(workflow)],
          [`PUT ${ADDRESS}/steps`]: [
            reply({
              ...workflow,
              revision: 4,
              steps: [{ ...ASKING, tries: 3, label }, other],
            }),
          ],
        },
        `?step=${ASKING.stepId}`,
      );
      await opened();
      const control = screen.getByRole("textbox", { name: "Display name" });
      fireEvent.change(control, { target: { value: label } });
      expect(control).not.toHaveAttribute("aria-invalid", "true");
      expect(saveSteps()).not.toHaveAttribute("aria-disabled", "true");

      await userEvent.click(saveSteps());

      await waitFor(() => expect(written).toHaveBeenCalledTimes(1));
      expect(control).toHaveValue(label);
      expect(refused).not.toHaveBeenCalled();
      expect(requestsTo(sent)).toEqual([
        `GET ${ADDRESS}`,
        `PUT ${ADDRESS}/steps`,
      ]);
      expect(bodyOf(sent, 1)).toEqual({
        revision: 3,
        steps: [
          {
            stepId: "0000000c-0000-4000-8000-000000000cb1",
            name: "triage",
            label,
            runs: {
              kind: "question",
              version: "00000007-0000-4000-8000-000000000cb2",
            },
            producer: { kind: "person" },
            tries: 3,
            reviewer: null,
            bindings: [{ target: "complaint", source: { input: "complaint" } }],
          },
          {
            stepId: "0000000c-0000-4000-8000-000000000cb8",
            name: "other",
            label: otherLabel,
            runs: {
              kind: "question",
              version: "00000007-0000-4000-8000-000000000cb2",
            },
            producer: { kind: "person" },
            tries: 3,
            reviewer: null,
            bindings: [{ target: "complaint", source: { input: "complaint" } }],
          },
        ],
        outputs: [{ target: "summary", source: { step: 0, path: "summary" } }],
      });
    },
  );

  it.each(["new", "stored"] as const)(
    "names a server-only display collision for a %s step and retains the draft",
    async (kind) => {
      const other = {
        ...ASKING,
        stepId: "0000000c-0000-4000-8000-000000000cb8",
        name: "other",
        label: "STRASSE",
      };
      const workflow = {
        ...WORKFLOW,
        steps: kind === "new" ? [other] : [ASKING, other],
      };
      const problem = {
        code: "step_label_repeated",
        part: "steps",
        name: "STRASSE",
        ...(kind === "new" ? {} : { stepId: ASKING.stepId }),
      };
      const { sent, written, refused } = opening(
        DRAFT,
        {
          [`GET ${ADDRESS}`]: [reply(workflow)],
          [`PUT ${ADDRESS}/steps`]: [
            reply(
              {
                code: "VERSION_CONTENT_DOES_NOT_HOLD",
                problems: [problem],
                pins: [],
              },
              409,
            ),
          ],
        },
        kind === "stored" ? `?step=${ASKING.stepId}` : "",
      );
      await opened();
      if (kind === "new") {
        await userEvent.click(
          screen.getByRole("button", { name: "Add a step" }),
        );
        fireEvent.change(screen.getByRole("textbox", { name: "Name" }), {
          target: { value: "first" },
        });
      }
      const control = screen.getByRole("textbox", { name: "Display name" });
      fireEvent.change(control, { target: { value: "Straße" } });
      expect(control).not.toHaveAttribute("aria-invalid", "true");
      await userEvent.click(saveSteps());

      const collision = await screen.findByRole("listitem", {
        name: /Another step in this version is already shown as/,
      });
      expect(collision).toHaveAccessibleName(
        `${kind === "new" ? "The steps" : `The steps, ${isolatedInText("triage")}`}: Another step in this version is already shown as "${isolatedInText("STRASSE")}".`,
      );
      expect(collision).toHaveTextContent(
        'Another step in this version is already shown as "STRASSE".',
      );
      expect(within(collision).getByText("STRASSE").tagName).toBe("BDI");
      expect(control).toHaveValue("Straße");
      expect(written).not.toHaveBeenCalled();
      expect(refused).toHaveBeenCalledTimes(1);
      expect(refused).toHaveBeenCalledWith({
        code: "VERSION_CONTENT_DOES_NOT_HOLD",
        status: 409,
        detail: undefined,
        extensions: { problems: [problem], pins: [] },
      });
      expect(requestsTo(sent)).toEqual([
        `GET ${ADDRESS}`,
        `PUT ${ADDRESS}/steps`,
      ]);
      expect(bodyOf(sent, 1)).toMatchObject({
        revision: 3,
        steps:
          kind === "new"
            ? [
                { stepId: other.stepId, name: "other", label: "STRASSE" },
                { stepId: null, name: "first", label: "Straße" },
              ]
            : [
                { stepId: ASKING.stepId, name: "triage", label: "Straße" },
                { stepId: other.stepId, name: "other", label: "STRASSE" },
              ],
      });
    },
  );
  it.each([
    ["draft with outputs", DRAFT, WORKFLOW],
    ["read-only with outputs", READ_ONLY, WORKFLOW],
    ["draft with no outputs", DRAFT, { ...WORKFLOW, gives: [], outputs: [] }],
    [
      "read-only with no outputs",
      READ_ONLY,
      { ...WORKFLOW, gives: [], outputs: [] },
    ],
  ])(
    "keeps the information only beside what it gives back in a %s",
    async (_, version, workflow) => {
      const { sent, written, refused } = opening(version, {
        [`GET ${ADDRESS}`]: [reply(workflow)],
      });
      await opened();
      const gives = part("What it gives back");
      const title = within(gives).getByRole("heading", {
        name: "What it gives back",
        level: 3,
      });
      const information = within(gives).getByRole("button", {
        name: "About what it gives back",
      });

      expect(information).toBeVisible();
      expect(information).toHaveAttribute("type", "button");
      expect(information).toHaveAttribute(
        "aria-label",
        "About what it gives back",
      );
      expect(
        screen.getAllByRole("button", { name: "About what it gives back" }),
      ).toEqual([information]);
      expect(title.textContent).toBe("What it gives back");
      expect(gives).toHaveAttribute("aria-labelledby", title.id);
      expect(title).not.toContainElement(information);
      expect(
        within(part("What it takes")).queryByRole("button", {
          name: "About what it gives back",
        }),
      ).toBeNull();
      expect(
        within(part("What fills each value it gives back")).queryByRole(
          "button",
          { name: "About what it gives back" },
        ),
      ).toBeNull();
      expect(screen.queryByRole("tooltip")).toBeNull();
      expect(information).toHaveAccessibleDescription(GIVES_HINT);
      expect(screen.getByText(GIVES_HINT, { exact: true })).not.toBeVisible();
      expect(sent).toHaveBeenCalledTimes(1);
      expect(written).not.toHaveBeenCalled();
      expect(refused).not.toHaveBeenCalled();
    },
  );

  it("shows what a workflow gives back while its information is pointed at, without writing", async () => {
    const { sent, written, refused } = opening(DRAFT);
    await opened();
    const gives = part("What it gives back");
    const information = within(gives).getByRole("button", {
      name: "About what it gives back",
    });
    expect(screen.queryByRole("tooltip")).toBeNull();

    await userEvent.hover(information);

    const tooltip = await screen.findByRole("tooltip");
    expect(tooltip.textContent).toBe(GIVES_HINT);
    expect(gives).not.toContainElement(tooltip);
    expect(within(gives).getByRole("heading", { level: 3 }).textContent).toBe(
      "What it gives back",
    );
    expect(sent).toHaveBeenCalledTimes(1);
    expect(written).not.toHaveBeenCalled();
    expect(refused).not.toHaveBeenCalled();
    await userEvent.unhover(information);
    await waitFor(() => expect(screen.queryByRole("tooltip")).toBeNull());
  });

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
        name: LOAD_SAVED,
      });
      const nameAfterWrite = screen.getByRole("textbox", { name: "Name" });
      await userEvent.click(readAfresh);
      expect(screen.getByRole("textbox", { name: "Name" })).toBe(
        nameAfterWrite,
      );
      expect(nameAfterWrite).toHaveValue("triage_draft");
      expect(saveSteps()).toHaveAttribute("aria-disabled", "true");
      await act(async () => saveSteps().focus());
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
          screen.queryByRole("dialog", {
            name: isolatedInText("triage_draft"),
          }),
        ).toBeNull();
        expect(
          await screen.findByRole("region", { name: "The steps" }),
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
        expect(screen.queryByRole("button", { name: SAVE_STEPS })).toBeNull();
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
      bindings: [
        {
          bindingId: "p1",
          target: "complaint",
          source: { input: "complaint" },
        },
      ],
      runs: {
        kind: "search",
        searchWay: "web",
        version: TRIAGE,
        takes: [COMPLAINT],
        gives: fields,
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
      "Usage limit: 100 · May be helped by a model not chosen yet",
    );
    expect(settings).toHaveTextContent(
      "Runs may be helped, and no model is named to help them.",
    );
    expect(
      within(settings).queryByRole("textbox", { name: "Usage limit" }),
    ).toBeNull();

    const information = within(settings).getByRole("button", {
      name: "About run settings",
    });
    const input = nameInOpenedField("What it takes");
    await act(async () => input.focus());
    await userEvent.keyboard("{ArrowLeft}");
    await act(async () => information.focus());
    expect(information).toHaveFocus();
    expect((await screen.findByRole("tooltip")).textContent).toContain(
      "The usage limit for each run of this version, and whether people following a run can ask a model for help.",
    );
    await userEvent.click(disclosure);
    expect(
      within(settings).getByRole("textbox", { name: "Usage limit" }),
    ).toBeInTheDocument();
    expect(
      within(settings).getByRole("switch", { name: "Runs may be helped" }),
    ).toBeInTheDocument();
  });

  it.each([
    [
      "no ceiling",
      { ceiling: undefined, mayBeHelped: false },
      "No usage limit · May not be helped",
    ],
    [
      "helper still to be chosen",
      { mayBeHelped: true },
      "Usage limit: 100 · May be helped by a model not chosen yet",
    ],
    [
      "helper and mode chosen",
      { mayBeHelped: true, helper: { model: "general", mode: "research" } },
      "Usage limit: 100 · May be helped by general in research",
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
    const ceiling = screen.getByRole("textbox", { name: "Usage limit" });
    await userEvent.type(ceiling, "0");
    await userEvent.click(disclosure);

    expect(disclosure).toHaveAttribute("aria-expanded", "false");
    expect(screen.queryByRole("textbox", { name: "Usage limit" })).toBeNull();
    await userEvent.click(disclosure);
    expect(screen.getByRole("textbox", { name: "Usage limit" })).toHaveValue(
      "1000",
    );
    expect(requestsTo(sent)).toEqual([`GET ${ADDRESS}`]);
  });

  it("reads every step in its own row, what it runs, who produces it and what it gives", async () => {
    opening(READ_ONLY);
    await opened();

    expect(rowWords()).toEqual([
      expect.stringMatching(
        new RegExp(`Triage \u00b7 Version 4.*A person.*One value`, "u"),
      ),
    ]);
  });

  it("sets the kind a step runs apart above which one it runs, saying the kind nowhere else", async () => {
    opening(READ_ONLY);
    await opened();

    const runs = within(rowsOf()[0]!).getAllByRole("cell")[2]!;
    const tag = within(runs).getByText("Question");

    expect(tag.nextSibling?.textContent).toBe(`Triage \u00b7 Version 4`);
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
    await shutStep();

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
    expect(
      within(output).getByText(
        "Nothing fills it yet, which submitting refuses.",
        { exact: true },
      ),
    ).toBeVisible();
    expect(screen.queryByRole("tooltip")).toBeNull();
    const from = within(output).getByRole("combobox", { name: /^From, / });

    await userEvent.click(from);
    await userEvent.click(
      screen.getByRole("option", { name: "Step 1: triage" }),
    );
    expect(from).toHaveTextContent("Step 1: triage");
    expect(
      within(output).getByRole("combobox", { name: /^Which, / }),
    ).toBeInTheDocument();

    await userEvent.click(
      within(output).getByRole("combobox", { name: /^Which, / }),
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
    expect(screen.queryByRole("textbox", { name: "Usage limit" })).toBeNull();
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

  it("draws no save of the steps as they were read, on the page or in a step's drawer, and sends nothing", async () => {
    const { sent } = opening(DRAFT);
    await opened();

    expect(screen.queryByText("Unsaved")).toBeNull();
    expect(screen.queryByRole("button", { name: SAVE_STEPS })).toBeNull();
    expect(
      screen.queryAllByRole("button", { name: SAVE_STEPS, hidden: true }),
    ).toEqual([]);
    expect(requestsTo(sent)).toEqual([`GET ${ADDRESS}`]);

    await userEvent.click(
      screen.getByRole("link", { name: `Open ${isolatedInText("triage")}` }),
    );

    const drawer = panel(isolatedInText("triage"));
    expect(
      within(drawer).queryByRole("button", { name: SAVE_STEPS }),
    ).toBeNull();
    expect(within(drawer).queryByText("Unsaved")).toBeNull();
    expect(
      within(drawer).getByRole("button", { name: "Remove this step" }),
    ).toBeVisible();
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
    await act(async () => saveSteps().focus());
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
    expect(within(stepDrawer()).getByText("Unsaved")).toBeVisible();
    expect(
      getComputedStyle(within(stepDrawer()).getByText("Unsaved")).fontStyle,
    ).toBe("normal");
    const added = search().get("step");
    await userEvent.type(
      within(panel("Step 2, not named yet")).getByRole("textbox", {
        name: "Name",
      }),
      "tidy",
    );

    expect(stepDrawer()).toContainElement(saveSteps());
    expect(saveSteps()).toHaveClass("MuiButton-contained");
    await userEvent.click(saveSteps());

    await waitFor(() => expect(written).toHaveBeenCalledTimes(1));
    expect(within(stepDrawer()).queryByText("Unsaved")).toBeNull();
    expect(screen.queryByRole("button", { name: SAVE_STEPS })).toBeNull();
    expect(search().getAll("step")).toEqual([tidying]);
    expect(search().get("step")).not.toBe(added);
    expect(
      await screen.findByRole("dialog", { name: isolatedInText("tidy") }),
    ).toBeInTheDocument();
    await shutStep();
    expect(screen.queryByText("Unsaved")).toBeNull();
    expect(screen.queryByRole("button", { name: SAVE_STEPS })).toBeNull();
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
          label: null,
          runs: { kind: "question", version: TRIAGE.versionId },
          producer: { kind: "person" },
          tries: null,
          reviewer: null,
          bindings: [{ target: "complaint", source: { input: "complaint" } }],
        },
        { stepId: null, name: "tidy", label: null, runs: null, bindings: [] },
      ],
      outputs: [{ target: "summary", source: { step: 0, path: "summary" } }],
    });
    await shutStep();
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

  it("opens the step picked in a row's drawer over the page, wider than a panel beside a list", async () => {
    opening(DRAFT, {}, `?step=${ASKING.stepId}`);
    await opened();

    expect(getComputedStyle(panel(isolatedInText("triage"))).width).toBe(
      "560px",
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

    expect(added).toContainElement(headedBefore);
    expect(headedBefore).toContainElement(heading);
    expect(heading.parentElement).not.toBe(added);
    expect(heading.nextElementSibling?.textContent).toBe("Code step");
    expect(heading).toHaveAccessibleName("Step 2, not named yet");
  });

  it("marks the row of the step the address picks as the current one beneath its drawer, and no other", async () => {
    const answered = { ...WORKFLOW, steps: [ASKING, NOTIFYING] };
    opening(
      DRAFT,
      { [`GET ${ADDRESS}`]: [reply(answered)] },
      `?step=${ASKING.stepId}`,
    );
    await opened();

    expect(
      screen.getByRole("link", {
        name: `Open ${isolatedInText("triage")}`,
        hidden: true,
      }),
    ).toHaveAttribute("aria-current", "true");
    expect(
      screen.getByRole("link", {
        name: `Open ${isolatedInText("notify")}`,
        hidden: true,
      }),
    ).not.toHaveAttribute("aria-current");
  });

  it("keeps the step picked in the address, its own row's link out of reach beneath its drawer", async () => {
    const { search } = opening(DRAFT, {}, `?step=${ASKING.stepId}`);
    await opened();

    expect(
      screen.queryByRole("link", { name: `Open ${isolatedInText("triage")}` }),
    ).toBeNull();
    expect(
      screen
        .getByRole("link", {
          name: `Open ${isolatedInText("triage")}`,
          hidden: true,
        })
        .closest('[aria-hidden="true"]'),
    ).not.toBeNull();
    expect(search().getAll("step")).toEqual([ASKING.stepId]);
  });

  it.each(["Close", "Escape", "backdrop"] as const)(
    "lets a step go when its panel over a narrow page is shut by %s: no panel or strip is left, the address loses it, and the keyboard is back on its row",
    async (shutting) => {
      localStorage.setItem("lilradish.panel.row", "collapsed");
      onTestFinished(() => localStorage.removeItem("lilradish.panel.row"));
      const { search } = opening(DRAFT, {}, "", 700);
      await opened();
      const link = screen.getByRole("link", {
        name: `Open ${isolatedInText("triage")}`,
      });
      await userEvent.click(link);
      const panel = await screen.findByRole("dialog", {
        name: isolatedInText("triage"),
      });
      expect(search().get("step")).toBe(ASKING.stepId);

      if (shutting === "Close")
        await userEvent.click(
          within(panel).getByRole("button", { name: "Close" }),
        );
      else if (shutting === "Escape") await userEvent.keyboard("{Escape}");
      else
        await userEvent.click(
          document.querySelector<HTMLElement>(".MuiBackdrop-root")!,
        );
      await waitFor(() =>
        expect(screen.queryByRole("dialog", { hidden: true })).toBeNull(),
      );

      expect(search().get("step")).toBeNull();
      expect(
        screen.queryAllByRole("button", {
          name: isolatedInText("triage"),
          hidden: true,
        }),
      ).toEqual([]);
      await waitFor(() =>
        expect(
          screen.getByRole("link", {
            name: `Open ${isolatedInText("triage")}`,
          }),
        ).toHaveFocus(),
      );
    },
  );

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
  it("draws each available step move as an ordinary icon in its last cell, named with its step, and removes only in its drawer", async () => {
    opening(DRAFT, {
      [`GET ${ADDRESS}`]: [reply({ ...WORKFLOW, steps: [ASKING, NOTIFYING] })],
    });
    await opened();

    const icons = { up: "ArrowUpwardIcon", down: "ArrowDownwardIcon" } as const;
    const drawn = (
      [
        ["down", "triage"],
        ["up", "notify"],
      ] as const
    ).map(([act, name]) => ({
      act,
      button: screen.getByRole("button", {
        name: `Move ${act}: ${isolatedInText(name)}`,
      }),
    }));
    expect(rowsOf()).toHaveLength(2);
    expect(
      rowsOf().map((row) => within(row).getAllByRole("cell").length),
    ).toEqual([7, 7]);
    expect(
      drawn.every(
        ({ button }) =>
          button.closest("td") === button.closest("tr")!.lastElementChild,
      ),
    ).toBe(true);
    expect(screen.queryByRole("button", { name: /^Remove / })).toBeNull();
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
    ).toEqual([]);
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
    await act(async () =>
      within(panel(isolatedInText("triage")))
        .getByRole("textbox", { name: "Name" })
        .focus(),
    );
    await userEvent.keyboard("s");

    expect(rowsBeneath()[0]!.querySelector("a bdi")?.textContent).toBe(
      "triages",
    );
    expect(rowsBeneath()[1]!.querySelector("a bdi")?.textContent).toBe(
      "notify",
    );
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
    await shutStep();
    await userEvent.click(
      screen.getByRole("link", { name: `Open ${isolatedInText("close")}` }),
    );

    expect(panel(isolatedInText("close"))).toBeInTheDocument();
    expect(
      screen.getByRole("link", {
        name: `Open ${isolatedInText("close")}`,
        hidden: true,
      }),
    ).toHaveAttribute("aria-current", "true");
    expect(
      screen.getByRole("link", {
        name: `Open ${isolatedInText("triage")}`,
        hidden: true,
      }),
    ).not.toHaveAttribute("aria-current");
  });

  it("holds moving the first step up and the last down, and no other move", async () => {
    opening(DRAFT, {
      [`GET ${ADDRESS}`]: [
        reply({ ...WORKFLOW, steps: [ASKING, NOTIFYING, CLOSING] }),
      ],
    });
    await opened();

    const directions = rowsOf().map((row) =>
      ["up", "down"].map((direction) =>
        (() => {
          const button = row.querySelector(`[data-part="${direction}"] button`);
          return button === null
            ? "absent"
            : button.getAttribute("aria-disabled");
        })(),
      ),
    );
    expect(directions).toEqual(
      [
        ["absent", "absent"],
        ["absent", "absent"],
        ["absent", "absent"],
      ].map((row, at) =>
        row.map((_, by) =>
          (at === 0 && by === 0) || (at === 2 && by === 1) ? "absent" : null,
        ),
      ),
    );
    expect(
      rowsOf().map((row) => row.querySelectorAll("[data-part]").length),
    ).toEqual([2, 2, 2]);
  });

  it("holds every move, taking out and adding while the steps are being saved from the page, changing nothing and sending nothing more", async () => {
    const saving = deferred<readonly [string, number]>();
    const { sent, search } = opening(DRAFT, {
      [`GET ${ADDRESS}`]: [
        reply({ ...WORKFLOW, steps: [ASKING, NOTIFYING, CLOSING] }),
      ],
      [`PUT ${ADDRESS}/steps`]: [saving.promise],
    });
    await opened();
    await userEvent.click(
      screen.getByRole("button", {
        name: `Move down: ${isolatedInText("triage")}`,
      }),
    );
    const acts = within(part("The steps"))
      .getAllByRole("button")
      .filter((button) =>
        /^(?:Move (?:up|down): |Remove )/.test(
          button.getAttribute("aria-label") ?? "",
        ),
      );
    const adding = screen.getByRole("button", { name: "Add a step" });
    const order = rowWords();

    for (const control of [...acts, adding]) {
      await act(async () => control.focus());
      await userEvent.keyboard("{Enter}");
    }

    expect(acts).toHaveLength(4);
    expect(
      [...acts, adding].filter(
        (control) => control.getAttribute("aria-disabled") !== "true",
      ),
    ).toEqual([]);
    expect(rowWords()).toEqual(order);
    expect(requestsTo(sent)).toEqual([
      `GET ${ADDRESS}`,
      `PUT ${ADDRESS}/steps`,
    ]);
    expect(search().get("step")).toBeNull();
    expect(screen.queryByRole("dialog")).toBeNull();
    await act(async () =>
      saving.settle(
        await reply({
          ...WORKFLOW,
          revision: 4,
          steps: [NOTIFYING, ASKING, CLOSING],
        }),
      ),
    );
  });

  /** The icon's name says which step it acts on; the tip needs only say what it does, held or not. */
  it.each([
    ["held", `Move up: ${isolatedInText("notify")}`, "Move up"],
    ["available", `Move down: ${isolatedInText("triage")}`, "Move down"],
    ["keyboard", `Move up: ${isolatedInText("notify")}`, "Move up"],
  ])(
    "says what an act does as a tip of its own words once pointed at, %s",
    async (_state, name, tip) => {
      opening(DRAFT, {
        [`GET ${ADDRESS}`]: [
          reply({
            ...WORKFLOW,
            steps: [
              _state === "held"
                ? { ...ASKING, label: "triage", name: "not a name" }
                : ASKING,
              NOTIFYING,
            ],
          }),
        ],
      });
      await opened();

      const button = screen.getByRole("button", { name });
      if (_state === "keyboard") {
        const matches = button.matches.bind(button);
        // jsdom leaves focus-visible false between button and anchor focus; supply only that browser predicate.
        vi.spyOn(button, "matches").mockImplementation((selector) =>
          selector === ":focus-visible"
            ? document.activeElement === button
            : matches(selector),
        );
        await act(async () =>
          screen
            .getByRole("link", { name: `Open ${isolatedInText("notify")}` })
            .focus(),
        );
        await userEvent.tab();
        expect(button).toHaveFocus();
        expect(button.matches(":focus-visible")).toBe(true);
      } else await userEvent.hover(button);

      expect(button).toHaveAttribute("aria-label", name);
      expect(name.startsWith(`${tip} `) || name.startsWith(`${tip}: `)).toBe(
        true,
      );
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

  it("keeps a locally held step move inert by pointer and keyboard until the confirmed name is corrected", async () => {
    const { sent } = opening(DRAFT, {
      [`GET ${ADDRESS}`]: [
        reply({
          ...WORKFLOW,
          steps: [{ ...ASKING, name: "bad name", label: "triage" }, NOTIFYING],
        }),
      ],
      [`PUT ${ADDRESS}/steps`]: [
        reply({
          ...WORKFLOW,
          revision: 4,
          steps: [{ ...NOTIFYING, bindings: [] }, ASKING],
        }),
      ],
    });
    await opened();
    const down = screen.getByLabelText(
      `Move down: ${isolatedInText("triage")}`,
      { selector: "button" },
    );
    const before = rowWords();
    expect(down).toHaveAttribute("aria-disabled", "true");
    expect(down).toHaveAccessibleDescription(/A step's name is one to 63/);
    for (const id of down.getAttribute("aria-describedby")!.split(" "))
      expect(document.getElementById(id)).toBeVisible();
    await userEvent.click(down);
    await act(async () => down.focus());
    await userEvent.keyboard("{Enter}");
    expect(rowWords()).toEqual(before);
    expect(requestsTo(sent)).toEqual([`GET ${ADDRESS}`]);
    await userEvent.click(
      screen.getByLabelText(`Open ${isolatedInText("triage")}`, {
        selector: "a",
      }),
    );
    fireEvent.change(
      within(stepDrawer()).getByLabelText("Name", { selector: "input" }),
      { target: { value: "triage" } },
    );
    await shutStep();
    const corrected = screen.getByLabelText(
      `Move down: ${isolatedInText("triage")}`,
      { selector: "button" },
    );
    expect(corrected).not.toHaveAttribute("aria-disabled", "true");
    expect(corrected).not.toHaveAttribute("aria-describedby");
    await userEvent.click(corrected);
    await waitFor(() => expect(rowWords()[1]).toContain("triage"));
    expect(requestsTo(sent)).toEqual([
      `GET ${ADDRESS}`,
      `PUT ${ADDRESS}/steps`,
    ]);
    expect(bodyOf(sent, 1)).toMatchObject({
      revision: 3,
      steps: [{ name: "notify" }, { name: "triage" }],
    });
  });

  it("leaves focus on the control the reader chose while a step move was being kept", async () => {
    const answer = deferred<readonly [string, number]>();
    const { sent, written } = opening(DRAFT, {
      [`GET ${ADDRESS}`]: [reply({ ...WORKFLOW, steps: [ASKING, NOTIFYING] })],
      [`PUT ${ADDRESS}/steps`]: [answer.promise],
    });
    await opened();
    await userEvent.click(
      screen.getByLabelText(`Move down: ${isolatedInText("triage")}`, {
        selector: "button",
      }),
    );
    const other = screen.getByRole("button", { name: "Run settings" });
    await userEvent.click(other);
    expect(other).toHaveFocus();
    expect(screen.queryByRole("dialog")).toBeNull();
    await act(async () =>
      answer.settle(
        reply({
          ...WORKFLOW,
          revision: 4,
          steps: [{ ...NOTIFYING, bindings: [] }, ASKING],
        }),
      ),
    );
    await waitFor(() => expect(written).toHaveBeenCalledTimes(1));
    expect(other).toHaveFocus();
    expect(
      screen.getByLabelText(`Move up: ${isolatedInText("triage")}`, {
        selector: "button",
      }),
    ).not.toHaveFocus();
    expect(rowWords()[1]).toContain("triage");
    expect(requestsTo(sent)).toEqual([
      `GET ${ADDRESS}`,
      `PUT ${ADDRESS}/steps`,
    ]);
  });

  it("puts focus on the remaining direction after the move is kept, without focusing a row link", async () => {
    opening(DRAFT, {
      [`GET ${ADDRESS}`]: [reply({ ...WORKFLOW, steps: [ASKING, NOTIFYING] })],
      [`PUT ${ADDRESS}/steps`]: [
        reply({ ...WORKFLOW, revision: 4, steps: [NOTIFYING, ASKING] }),
      ],
    });
    await opened();
    const down = screen.getByRole("button", {
      name: `Move down: ${isolatedInText("triage")}`,
    });
    await userEvent.click(down);
    await waitFor(() =>
      expect(rowsOf()[1]!.querySelector("a bdi")?.textContent).toBe("triage"),
    );
    expect(
      screen.getByRole("button", {
        name: `Move up: ${isolatedInText("triage")}`,
      }),
    ).toHaveFocus();
    expect(
      screen.getByRole("link", { name: `Open ${isolatedInText("notify")}` }),
    ).not.toHaveFocus();
    expect(
      await screen.findByText(
        `${isolatedInText("triage")} moved to position 2 of 2.`,
      ),
    ).toBeVisible();
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
      screen.getByRole("link", { name: `Open ${isolatedInText("triage")}` }),
    );
    await userEvent.click(
      screen.getByRole("button", { name: "Remove this step" }),
    );
    await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());

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
      screen.getByRole("link", { name: `Open ${isolatedInText("notify")}` }),
    );
    await userEvent.click(
      screen.getByRole("button", { name: "Remove this step" }),
    );
    await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());

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
      screen.getByRole("link", { name: `Open ${isolatedInText("notify")}` }),
    );
    await userEvent.click(
      screen.getByRole("button", { name: "Remove this step" }),
    );
    await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());

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

  it("shuts the step picked once Remove this step is pressed in its drawer, drops it from the steps and the address, and puts focus on the step after it", async () => {
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
      within(before).getByRole("button", { name: "Remove this step" }),
    );

    await waitFor(() => expect(before).not.toBeInTheDocument());
    expect(search().get("step")).toBeNull();
    expect(rowWords().some((words) => words.includes("notify"))).toBe(false);
    expect(rowWords()).toHaveLength(2);
    expect(screen.queryByRole("dialog")).toBeNull();
    expect(
      screen.getByRole("link", { name: `Open ${isolatedInText("close")}` }),
    ).toHaveFocus();
  });

  it("keeps the step, its drawer and the keyboard where they are while Remove this step is not pressed", async () => {
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
    const drawer = panel(isolatedInText("notify"));
    const name = within(drawer).getByRole("textbox", { name: "Name" });
    await act(async () => name.focus());
    await userEvent.keyboard("s");

    expect(search().get("step")).toBe(NOTIFYING.stepId);
    expect(rowsBeneath()).toHaveLength(3);
    expect(rowsBeneath()[1]!.textContent).toContain("notifys");
    expect(drawer).toBeInTheDocument();
    expect(name).toHaveFocus();
    expect(
      within(drawer).getByRole("button", { name: "Remove this step" }),
    ).not.toHaveAttribute("aria-disabled");
  });

  it("draws the step's drawer foot with Save first, the unsaved mark beside it and Remove this step, quiet, at the trailing end, 8 apart", async () => {
    opening(
      DRAFT,
      {
        [`GET ${ADDRESS}`]: [
          reply({ ...WORKFLOW, steps: [ASKING, NOTIFYING] }),
        ],
      },
      `?step=${NOTIFYING.stepId}`,
    );
    await opened();
    const drawer = panel(isolatedInText("notify"));
    await act(async () =>
      within(drawer).getByRole("textbox", { name: "Name" }).focus(),
    );
    await userEvent.keyboard("s");

    const save = within(drawer).getByRole("button", { name: SAVE_STEPS });
    const removing = within(drawer).getByRole("button", {
      name: "Remove this step",
    });
    let foot = save.parentElement!;
    while (!foot.contains(removing)) foot = foot.parentElement!;
    const drawn = getComputedStyle(foot);
    expect([
      drawn.display,
      drawn.flexWrap,
      drawn.alignItems,
      drawn.gap,
      drawn.padding,
      drawn.borderTopStyle,
      drawn.borderTopColor,
    ]).toEqual([
      "flex",
      "wrap",
      "center",
      "8px",
      "14px 24px",
      "solid",
      "rgb(228, 235, 238)",
    ]);
    const [first, mark, last] = [...foot.children];
    expect(first).toContainElement(save);
    expect(save).toHaveClass("MuiButton-contained");
    expect(mark).toHaveTextContent("Unsaved");
    expect(last).toContainElement(removing);
    expect(getComputedStyle(last!).marginInlineStart).toBe("auto");
    expect(removing).toHaveClass("MuiButton-text", "MuiButton-colorInherit");
    expect(removing.querySelector("svg")).toBeNull();
    expect(stepDrawer().lastElementChild).not.toBe(foot);
    expect(stepDrawer().firstElementChild!.lastElementChild).toBe(foot);
  });

  it("holds Remove this step in the step's drawer while the steps are being saved, as the rows' own taking out is", async () => {
    const saving = deferred<readonly [string, number]>();
    const { sent } = opening(
      DRAFT,
      {
        [`GET ${ADDRESS}`]: [
          reply({ ...WORKFLOW, steps: [ASKING, NOTIFYING] }),
        ],
        [`PUT ${ADDRESS}/steps`]: [saving.promise],
      },
      `?step=${NOTIFYING.stepId}`,
    );
    await opened();
    const drawer = panel(isolatedInText("notify"));
    await act(async () =>
      within(drawer).getByRole("textbox", { name: "Name" }).focus(),
    );
    await userEvent.keyboard("s");
    await userEvent.click(
      within(drawer).getByRole("button", { name: SAVE_STEPS }),
    );

    const removing = within(drawer).getByRole("button", {
      name: "Remove this step",
    });
    expect(removing).toHaveAttribute("aria-disabled", "true");
    expect(removing).toHaveClass("Mui-disabled");
    await act(async () => removing.focus());
    await userEvent.keyboard("{Enter}");
    expect(panel(isolatedInText("notifys"))).toBe(drawer);
    expect(requestsTo(sent)).toEqual([
      `GET ${ADDRESS}`,
      `PUT ${ADDRESS}/steps`,
    ]);
  });

  it("keeps the page's save held and says it is underway there once the step's drawer is shut while its save is out, sending it once", async () => {
    const saving = deferred<readonly [string, number]>();
    const { sent } = opening(
      DRAFT,
      {
        [`GET ${ADDRESS}`]: [
          reply({ ...WORKFLOW, steps: [ASKING, NOTIFYING] }),
        ],
        [`PUT ${ADDRESS}/steps`]: [saving.promise],
      },
      `?step=${NOTIFYING.stepId}`,
    );
    await opened();
    const drawer = panel(isolatedInText("notify"));
    await act(async () =>
      within(drawer).getByRole("textbox", { name: "Name" }).focus(),
    );
    await userEvent.keyboard("s");
    await userEvent.click(
      within(drawer).getByRole("button", { name: SAVE_STEPS }),
    );

    await shutStep();

    const save = saveSteps();
    expect(save).toHaveAttribute("aria-disabled", "true");
    const underway = screen.getByText(
      "It is being saved, and that can no longer be called back.",
    );
    expect(underway).toBeVisible();
    expect(underway.closest('[role="dialog"]')).toBeNull();
    expect(screen.getAllByText(/It is being saved/)).toHaveLength(1);
    await act(async () => save.focus());
    await userEvent.keyboard("{Enter}");
    expect(
      requestsTo(sent).filter((request) => request.startsWith("PUT ")),
    ).toEqual([`PUT ${ADDRESS}/steps`]);
    await act(async () =>
      saving.settle(
        await reply({ ...WORKFLOW, revision: 4, steps: [ASKING, NOTIFYING] }),
      ),
    );
  });

  it.each([
    ["on the page", false],
    ["in the step's drawer", true],
  ])(
    "puts the keyboard on the name of what was saved once a save pressed %s leaves no Save to stay on",
    async (_case, inDrawer) => {
      const { written } = opening(
        DRAFT,
        {
          [`PUT ${ADDRESS}/steps`]: [
            reply({
              ...WORKFLOW,
              revision: 4,
              steps: [{ ...ASKING, label: "Sort it" }],
            }),
          ],
        },
        `?step=${ASKING.stepId}`,
      );
      await opened();
      fireEvent.change(
        within(stepDrawer()).getByRole("textbox", { name: "Display name" }),
        { target: { value: "Sort it" } },
      );
      if (!inDrawer) await shutStep();
      await act(async () => saveSteps().focus());

      await userEvent.keyboard("{Enter}");

      await waitFor(() => expect(written).toHaveBeenCalledTimes(1));
      await waitFor(() =>
        expect(screen.queryByRole("button", { name: SAVE_STEPS })).toBeNull(),
      );
      const named = inDrawer
        ? within(stepDrawer()).getByRole("heading", { level: 4 })
        : screen.getByRole("heading", { name: "The steps", level: 3 });
      await waitFor(() => expect(named).toHaveFocus());
      expect(document.activeElement).not.toBe(document.body);
      expect(screen.queryAllByRole("dialog")).toHaveLength(inDrawer ? 1 : 0);
    },
  );

  it("says a refused save of the steps at the top of the step's drawer, scrolled to and given the keyboard, its foot holding the buttons alone", async () => {
    const problem = {
      code: "step_label_repeated",
      part: "steps",
      name: "STRASSE",
      stepId: ASKING.stepId,
    };
    opening(
      DRAFT,
      {
        [`PUT ${ADDRESS}/steps`]: [
          reply(
            {
              code: "VERSION_CONTENT_DOES_NOT_HOLD",
              problems: [problem],
              pins: [],
            },
            409,
          ),
        ],
      },
      `?step=${ASKING.stepId}`,
    );
    await opened();
    fireEvent.change(screen.getByRole("textbox", { name: "Display name" }), {
      target: { value: "Strasse" },
    });
    const scrolled = vi.fn();
    Element.prototype.scrollIntoView = scrolled;
    onTestFinished(() => {
      Reflect.deleteProperty(Element.prototype, "scrollIntoView");
    });
    await userEvent.click(saveSteps());

    const collision = await screen.findByRole("listitem", {
      name: /Another step in this version is already shown as/,
    });
    const drawer = stepDrawer();
    const [, body, foot] = [...drawer.firstElementChild!.children];
    expect(body).toContainElement(collision);
    expect(foot).not.toContainElement(collision);
    const said = collision.closest<HTMLElement>('[tabindex="-1"]')!;
    expect(body!.contains(said)).toBe(true);
    expect(said).toHaveFocus();
    expect(scrolled.mock.contexts).toContain(said);
    expect(
      said.compareDocumentPosition(
        within(drawer).getByRole("textbox", { name: "Display name" }),
      ) & Node.DOCUMENT_POSITION_FOLLOWING,
    ).toBeTruthy();
    expect(
      [...foot!.children].map(
        (child) =>
          within(child as HTMLElement).queryByRole("button")?.textContent ??
          child.textContent,
      ),
    ).toEqual(["Save", "Unsaved", "Remove this step"]);
  });

  it("lets a refused save of the steps go as the step's drawer is shut, saying it neither on the page nor in the drawer going, the save still there", async () => {
    const problem = {
      code: "step_label_repeated",
      part: "steps",
      name: "STRASSE",
      stepId: ASKING.stepId,
    };
    opening(
      DRAFT,
      {
        [`PUT ${ADDRESS}/steps`]: [
          reply(
            {
              code: "VERSION_CONTENT_DOES_NOT_HOLD",
              problems: [problem],
              pins: [],
            },
            409,
          ),
        ],
      },
      `?step=${ASKING.stepId}`,
    );
    await opened();
    fireEvent.change(screen.getByRole("textbox", { name: "Display name" }), {
      target: { value: "Strasse" },
    });
    await userEvent.click(saveSteps());
    await screen.findByRole("listitem", {
      name: /Another step in this version is already shown as/,
    });
    const drawer = stepDrawer();
    const inDrawer = within(drawer).getAllByRole("alert");
    expect(inDrawer.length).toBeGreaterThan(0);

    await userEvent.click(
      within(drawer).getAllByRole("button", { name: "Close" })[0]!,
    );

    expect(drawer).toBeInTheDocument();
    expect(screen.queryAllByRole("alert", { hidden: true })).toEqual([]);
    expect(
      screen.queryByRole("listitem", {
        name: /Another step in this version is already shown as/,
        hidden: true,
      }),
    ).toBeNull();
    await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
    expect(screen.queryAllByRole("alert")).toEqual([]);
    expect(saveSteps()).toBeVisible();
  });

  it("puts focus on the steps' heading once the last step is taken out, saying there is none", async () => {
    opening(DRAFT);
    await opened();
    expect(screen.queryByText(NO_STEPS_REFUSED)).toBeNull();

    await userEvent.click(
      screen.getByRole("link", { name: `Open ${isolatedInText("triage")}` }),
    );
    await userEvent.click(
      screen.getByRole("button", { name: "Remove this step" }),
    );
    await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());

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
      "A newer version is in service: Triage \u00b7 Version 5",
    );
    expect(
      within(rowsOf()[0]!).getByRole("button", {
        name: `Take Version 5 for ${isolatedInText("triage")}`,
      }),
    ).toBeEnabled();
    expect(requestsTo(sent)).toEqual([`GET ${ADDRESS}`]);
  });

  it("shows the newer version taken on its row in place of the one pinned, before anything is sent", async () => {
    const { sent } = opening(DRAFT, {
      [`GET ${ADDRESS}`]: [reply(PINNING_OLDER)],
      [`GET ${NEWER_ADDRESS}`]: [reply(NEWER_DETAIL)],
    });
    await opened();

    await userEvent.click(
      within(rowsOf()[0]!).getByRole("button", {
        name: `Take Version 5 for ${isolatedInText("triage")}`,
      }),
    );

    await waitFor(() =>
      expect(rowWords()[0]).toContain(`Triage \u00b7 Version 5`),
    );
    expect(rowWords()[0]).not.toContain(`Triage \u00b7 Version 4`);
    expect(requestsTo(sent)).toEqual([
      `GET ${ADDRESS}`,
      `GET ${NEWER_ADDRESS}`,
    ]);
  });

  it.each([200, 404])(
    "keeps a table Take after an earlier ordinary detail returns %s",
    async (status) => {
      const ordinary = "00000007-0000-4000-8000-000000000cb5";
      const held = deferred<readonly [string, number]>();
      const { sent } = opening(
        DRAFT,
        {
          [`GET ${ADDRESS}`]: [reply(PINNING_OLDER)],
          [`GET /api/groups/${GROUP}/questions/versions`]: [
            reply({
              items: [
                {
                  ...TRIAGE,
                  versionId: ordinary,
                  number: 6,
                  searchMatches: false,
                },
              ],
              more: false,
            }),
          ],
          [`GET /api/groups/${GROUP}/versions/${ordinary}`]: [held.promise],
          [`GET ${NEWER_ADDRESS}`]: [reply(NEWER_DETAIL)],
          [`PUT ${ADDRESS}/steps`]: [reply(WORKFLOW)],
        },
        `?step=${ASKING.stepId}`,
      );
      await opened();
      await userEvent.type(
        within(panel(isolatedInText("triage"))).getByRole("combobox", {
          name: "Version",
        }),
        "Triage",
      );
      await userEvent.click(
        await screen.findByRole("option", { name: /Triage.*Version 6/ }),
      );
      await waitFor(() =>
        expect(requestsTo(sent)).toContain(
          `GET /api/groups/${GROUP}/versions/${ordinary}`,
        ),
      );
      await shutStep();
      await userEvent.click(
        within(rowsOf()[0]!).getByRole("button", {
          name: `Take Version 5 for ${isolatedInText("triage")}`,
        }),
      );
      await waitFor(() =>
        expect(rowWords()[0]).toContain(`Triage \u00b7 Version 5`),
      );
      await act(async () =>
        held.settle(
          status === 200
            ? reply({
                ...NEWER_DETAIL,
                version: { ...TRIAGE, versionId: ordinary, number: 6 },
              })
            : reply({ code: "VERSION_NOT_IN_VIEW" }, 404),
        ),
      );
      expect(rowWords()[0]).toContain(`Triage \u00b7 Version 5`);
      expect(rowWords()[0]).not.toContain(`Triage \u00b7 Version 6`);
      expect(screen.queryByRole("alert")).toBeNull();
      expect(
        requestsTo(sent).filter((request) => request.startsWith("PUT ")),
      ).toEqual([]);
      await userEvent.click(saveSteps());
      await waitFor(() =>
        expect(requestsTo(sent)).toContain(`PUT ${ADDRESS}/steps`),
      );
      const write = sent.mock.calls.find(([, init]) => init?.method === "PUT")!;
      expect(JSON.parse(String(write[1]?.body))).toMatchObject({
        steps: [
          {
            runs: { version: NEWER },
            bindings: [{ target: "complaint", source: { input: "complaint" } }],
          },
        ],
      });
    },
  );

  it.each([200, 404])(
    "keeps an ordinary choice after an earlier table Take returns %s",
    async (status) => {
      const ordinary = "00000007-0000-4000-8000-000000000cb5";
      const held = deferred<readonly [string, number]>();
      const { sent } = opening(DRAFT, {
        [`GET ${ADDRESS}`]: [reply(PINNING_OLDER)],
        [`GET ${NEWER_ADDRESS}`]: [held.promise],
        [`GET /api/groups/${GROUP}/questions/versions`]: [
          reply({
            items: [
              {
                ...TRIAGE,
                versionId: ordinary,
                number: 6,
                searchMatches: false,
              },
            ],
            more: false,
          }),
        ],
        [`GET /api/groups/${GROUP}/versions/${ordinary}`]: [
          reply({
            ...NEWER_DETAIL,
            version: { ...TRIAGE, versionId: ordinary, number: 6 },
          }),
        ],
        [`PUT ${ADDRESS}/steps`]: [reply(WORKFLOW)],
      });
      await opened();
      await userEvent.click(
        within(rowsOf()[0]!).getByRole("button", {
          name: `Take Version 5 for ${isolatedInText("triage")}`,
        }),
      );
      await waitFor(() =>
        expect(requestsTo(sent)).toContain(`GET ${NEWER_ADDRESS}`),
      );
      await userEvent.click(
        screen.getByRole("link", { name: `Open ${isolatedInText("triage")}` }),
      );
      await userEvent.type(
        within(panel(isolatedInText("triage"))).getByRole("combobox", {
          name: "Version",
        }),
        "Triage",
      );
      await userEvent.click(
        await screen.findByRole("option", { name: /Triage.*Version 6/ }),
      );
      await waitFor(() =>
        expect(rowsBeneath()[0]!.textContent).toContain(
          `Triage \u00b7 Version 6`,
        ),
      );
      await act(async () =>
        held.settle(
          status === 200
            ? reply(NEWER_DETAIL)
            : reply({ code: "VERSION_NOT_IN_VIEW" }, 404),
        ),
      );
      expect(rowsBeneath()[0]!.textContent).toContain(
        `Triage \u00b7 Version 6`,
      );
      expect(rowsBeneath()[0]!.textContent).not.toContain(
        `Triage \u00b7 Version 5`,
      );
      expect(screen.queryByRole("alert")).toBeNull();
      expect(
        requestsTo(sent).filter((request) => request.startsWith("PUT ")),
      ).toEqual([]);
      await userEvent.click(saveSteps());
      await waitFor(() =>
        expect(requestsTo(sent)).toContain(`PUT ${ADDRESS}/steps`),
      );
      const write = sent.mock.calls.find(([, init]) => init?.method === "PUT")!;
      expect(JSON.parse(String(write[1]?.body))).toMatchObject({
        steps: [{ runs: { version: ordinary }, bindings: [] }],
      });
    },
  );

  it.each([200, 404])(
    "invalidates a table Take across a move away and back before its %s response",
    async (status) => {
      const held = deferred<readonly [string, number]>();
      const { sent } = opening(DRAFT, {
        [`GET ${ADDRESS}`]: [
          reply({
            ...PINNING_OLDER,
            steps: [
              PINNING_OLDER.steps[0],
              {
                ...NOTIFYING,
                runs: {
                  ...NOTIFYING.runs,
                  version: {
                    ...TRIAGE,
                    versionId: "00000007-0000-4000-8000-000000000cb6",
                    name: "Notifications",
                    number: 1,
                  },
                },
              },
            ],
          }),
        ],
        [`GET ${NEWER_ADDRESS}`]: [held.promise],
        [`PUT ${ADDRESS}/steps`]: [
          reply({
            ...PINNING_OLDER,
            revision: 4,
            steps: [
              {
                ...NOTIFYING,
                bindings: [
                  {
                    ...NOTIFYING.bindings[0]!,
                    source: { step: 1, path: "summary" },
                  },
                ],
              },
              PINNING_OLDER.steps[0]!,
            ],
            outputs: [
              { ...WORKFLOW.outputs[0]!, source: { step: 1, path: "summary" } },
            ],
          }),
          reply({
            ...PINNING_OLDER,
            revision: 5,
            steps: [PINNING_OLDER.steps[0]!, NOTIFYING],
          }),
        ],
      });
      await opened();
      await userEvent.click(
        within(rowsOf()[0]!).getByRole("button", {
          name: `Take Version 5 for ${isolatedInText("triage")}`,
        }),
      );
      await waitFor(() =>
        expect(requestsTo(sent)).toContain(`GET ${NEWER_ADDRESS}`),
      );
      await userEvent.click(
        screen.getByRole("button", {
          name: `Move down: ${isolatedInText("triage")}`,
        }),
      );
      await waitFor(() =>
        expect(
          screen.getByRole("button", {
            name: `Move up: ${isolatedInText("triage")}`,
          }),
        ).not.toHaveAttribute("aria-disabled", "true"),
      );
      await userEvent.click(
        screen.getByRole("button", {
          name: `Move up: ${isolatedInText("triage")}`,
        }),
      );
      await waitFor(() => expect(rowWords()[0]).toContain("triage"));
      await act(async () =>
        held.settle(
          status === 200
            ? reply(NEWER_DETAIL)
            : reply({ code: "VERSION_NOT_IN_VIEW" }, 404),
        ),
      );
      expect(rowWords()[0]).toContain(`QuestionTriage \u00b7 Version 4`);
      expect(rowWords()[0]).not.toContain(`QuestionTriage \u00b7 Version 5`);
      expect(screen.queryByRole("alert")).toBeNull();
      expect(
        requestsTo(sent).filter((request) => request.startsWith("PUT ")),
      ).toEqual([`PUT ${ADDRESS}/steps`, `PUT ${ADDRESS}/steps`]);
    },
  );

  it.each([200, 404])(
    "does not admit an ordinary detail after another part saves and the parent rereads, status %s",
    async (status) => {
      const ordinary = "00000007-0000-4000-8000-000000000cb5";
      const held = deferred<readonly [string, number]>();
      const updated = { ...WORKFLOW, revision: 4, ceiling: "200" };
      const sent = serving({
        [`GET ${ADDRESS}`]: [reply(WORKFLOW), reply(updated)],
        [`GET /api/groups/${GROUP}/questions/versions`]: [
          reply({
            items: [
              {
                ...TRIAGE,
                versionId: ordinary,
                number: 6,
                searchMatches: false,
              },
            ],
            more: false,
          }),
        ],
        [`GET /api/groups/${GROUP}/versions/${ordinary}`]: [held.promise],
        [`PUT ${ADDRESS}/ceiling`]: [reply(updated)],
      });
      function Host() {
        const [readCount, reread] = useState(0);
        return (
          <WorkflowContent
            groupId={GROUP}
            entryId={ENTRY}
            version={DRAFT}
            editable
            readCount={readCount}
            onRefused={() => {}}
            onShown={() => {}}
            onWritten={() => reread((now) => now + 1)}
          />
        );
      }
      laidOutAt(1400, 1400);
      const router = createMemoryRouter(
        [{ path: "/content", element: <Host /> }],
        { initialEntries: [`/content?step=${ASKING.stepId}`] },
      );
      render(
        <ThemeProvider theme={theme}>
          <StandingProvider read={STANDING}>
            <RouterProvider router={router} />
          </StandingProvider>
        </ThemeProvider>,
      );
      await opened();
      await userEvent.type(
        within(panel(isolatedInText("triage"))).getByRole("combobox", {
          name: "Version",
        }),
        "Triage",
      );
      await userEvent.click(
        await screen.findByRole("option", { name: /Triage.*Version 6/ }),
      );
      await waitFor(() =>
        expect(requestsTo(sent)).toContain(
          `GET /api/groups/${GROUP}/versions/${ordinary}`,
        ),
      );
      await shutStep();
      await userEvent.click(
        screen.getByRole("button", { name: "Run settings" }),
      );
      await userEvent.clear(
        screen.getByRole("textbox", { name: "Usage limit" }),
      );
      await userEvent.type(
        screen.getByRole("textbox", { name: "Usage limit" }),
        "200",
      );
      await userEvent.click(
        screen.getByRole("button", { name: "Save what a run may spend" }),
      );
      await waitFor(() =>
        expect(
          requestsTo(sent).filter((request) => request === `GET ${ADDRESS}`),
        ).toHaveLength(2),
      );
      await waitFor(() =>
        expect(
          screen.getByRole("textbox", { name: "Usage limit" }),
        ).toHaveValue("200"),
      );
      await act(async () =>
        held.settle(
          status === 200
            ? reply({
                ...NEWER_DETAIL,
                version: { ...TRIAGE, versionId: ordinary, number: 6 },
              })
            : reply({ code: "VERSION_NOT_IN_VIEW" }, 404),
        ),
      );
      expect(rowWords()[0]).toContain(`Triage \u00b7 Version 4`);
      expect(rowWords()[0]).not.toContain(`Triage \u00b7 Version 6`);
      expect(screen.queryByRole("alert")).toBeNull();
      expect(
        requestsTo(sent).filter((request) => request.startsWith("PUT ")),
      ).toEqual([`PUT ${ADDRESS}/ceiling`]);
    },
  );

  it("sends the newer version taken, keeping what fills its inputs", async () => {
    const { sent } = opening(DRAFT, {
      [`GET ${ADDRESS}`]: [reply(PINNING_OLDER)],
      [`GET ${NEWER_ADDRESS}`]: [reply(NEWER_DETAIL)],
      [`PUT ${ADDRESS}/steps`]: [reply(WORKFLOW)],
    });
    await opened();
    await userEvent.click(
      within(rowsOf()[0]!).getByRole("button", {
        name: `Take Version 5 for ${isolatedInText("triage")}`,
      }),
    );

    await waitFor(() =>
      expect(rowWords()[0]).toContain(`Triage \u00b7 Version 5`),
    );
    await userEvent.click(saveSteps());

    await waitFor(() =>
      expect(requestsTo(sent)).toEqual([
        `GET ${ADDRESS}`,
        `GET ${NEWER_ADDRESS}`,
        `PUT ${ADDRESS}/steps`,
      ]),
    );
    expect(
      (bodyOf(sent, 2) as { steps: { runs: unknown; bindings: unknown }[] })
        .steps,
    ).toEqual([
      expect.objectContaining({
        runs: { kind: "question", version: NEWER },
        bindings: [{ target: "complaint", source: { input: "complaint" } }],
      }),
    ]);
  });

  it("adds the last step a version holds, picks its name and refuses another without writing", async () => {
    const steps = Array.from({ length: 255 }, (_each, index) => ({
      stepId: `0000000c-0000-4000-8000-${String(index).padStart(12, "0")}`,
      name: `step_${index}`,
      bindings: [],
    }));
    const { sent, search } = opening(DRAFT, {
      [`GET ${ADDRESS}`]: [
        reply({ ...WORKFLOW, outputs: [], problems: [], steps }),
      ],
    });
    const add = await screen.findByRole("button", { name: "Add a step" });
    expect(add).not.toHaveAttribute("aria-disabled", "true");
    expect(rowsOf()).toHaveLength(255);

    await userEvent.click(add);

    expect(rowsBeneath()).toHaveLength(256);
    expect(
      rowsBeneath()
        .slice(0, 255)
        .map((row) => row.querySelector("a bdi")?.textContent),
    ).toEqual(steps.map((step) => step.name));
    const named = panel("Step 256, not named yet");
    expect(within(named).getByRole("textbox", { name: "Name" })).toHaveFocus();
    expect(search().get("step")).not.toBeNull();
    expect(steps.map((step) => step.stepId)).not.toContain(
      search().get("step"),
    );
    await shutStep();
    expect(add).toHaveAttribute("aria-disabled", "true");
    expect(add).toHaveAccessibleDescription(
      "A workflow version holds at most 256 steps.",
    );
    const order = rowsOf().map((row) => row.textContent);
    const address = search().toString();
    await act(async () => add.focus());

    await userEvent.keyboard("{Enter}");

    expect(rowsOf().map((row) => row.textContent)).toEqual(order);
    expect(rowsOf()).toHaveLength(256);
    expect(search().toString()).toBe(address);
    expect(screen.queryByRole("dialog")).toBeNull();
    expect(requestsTo(sent)).toEqual([`GET ${ADDRESS}`]);
  }, 15_000);

  it("keeps adding locked while steps are saved from the page, leaving their rows and address unchanged and opening no step", async () => {
    const saving = deferred<readonly [string, number]>();
    const { sent, search, written } = opening(
      DRAFT,
      {
        [`GET ${ADDRESS}`]: [
          reply({ ...WORKFLOW, steps: [ASKING, NOTIFYING, CLOSING] }),
        ],
        [`PUT ${ADDRESS}/steps`]: [saving.promise],
      },
      "?keep=yes",
    );
    await opened();
    await userEvent.click(
      screen.getByRole("button", {
        name: `Move down: ${isolatedInText("triage")}`,
      }),
    );
    const add = screen.getByRole("button", { name: "Add a step" });
    const order = rowWords();
    const picked = search().toString();
    try {
      await waitFor(() =>
        expect(requestsTo(sent)).toEqual([
          `GET ${ADDRESS}`,
          `PUT ${ADDRESS}/steps`,
        ]),
      );
      expect(add).toHaveAttribute("aria-disabled", "true");
      expect(written).not.toHaveBeenCalled();

      await userEvent.click(add, {
        pointerEventsCheck: PointerEventsCheckLevel.Never,
      });
      await act(async () => add.focus());
      await userEvent.keyboard("{Enter}");
      expect(saveSteps()).toHaveAttribute("aria-disabled", "true");

      expect(rowWords()).toEqual(order);
      expect(rowsOf()).toHaveLength(3);
      expect(search().toString()).toBe(picked);
      expect(screen.queryByRole("dialog")).toBeNull();
      expect(requestsTo(sent)).toEqual([
        `GET ${ADDRESS}`,
        `PUT ${ADDRESS}/steps`,
      ]);
      expect(written).not.toHaveBeenCalled();
    } finally {
      await act(async () =>
        saving.settle(
          await reply({
            ...WORKFLOW,
            revision: 4,
            steps: [NOTIFYING, ASKING, CLOSING],
          }),
        ),
      );
    }
    await waitFor(() =>
      expect(add).not.toHaveAttribute("aria-disabled", "true"),
    );
    expect(written).toHaveBeenCalledTimes(1);
    expect(rowWords().map((words) => words.slice(1))).toEqual([
      order[1]!.slice(1),
      order[0]!.slice(1),
      order[2]!.slice(1),
    ]);
    expect(search().toString()).toBe(picked);
    expect(requestsTo(sent)).toEqual([
      `GET ${ADDRESS}`,
      `PUT ${ADDRESS}/steps`,
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
    const add = await screen.findByRole("button", { name: "Add a step" });
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
    await act(async () => add.focus());
    await userEvent.keyboard("{Enter}");
    expect(rowsOf()).toHaveLength(256);
  }, 10_000);

  it("keeps what was typed when somebody saved since, writing nothing and reading nothing afresh unasked", async () => {
    const { sent, written } = await savedSince();

    expect(stepDrawer()).toContainElement(
      screen.getByRole("button", { name: LOAD_SAVED }),
    );
    expect(rowsBeneath()).toHaveLength(2);
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
          "Somebody saved this draft since you opened it. What you typed is kept here until you load the saved draft, which shows theirs in its place.",
        )
        .closest('[role="alert"]'),
    ).not.toBeNull();
    expect(screen.getByRole("button", { name: LOAD_SAVED })).toHaveFocus();
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

    await userEvent.click(screen.getByRole("button", { name: LOAD_SAVED }));

    await waitFor(() =>
      expect(
        rowsOf().map((row) => row.querySelector("a bdi")?.textContent),
      ).toEqual(["their_triage"]),
    );
    expect(
      screen.queryByText(/Somebody saved this draft since you opened it\./),
    ).toBeNull();
    expect(requestsTo(sent)).toEqual([
      `GET ${ADDRESS}`,
      `PUT ${ADDRESS}/steps`,
      `GET ${ADDRESS}`,
    ]);
  });

  it.each([
    ["the next change", false],
    ["shutting the step's drawer", true],
  ])(
    "keeps saying the steps' refused save once reading afresh leaves nothing to save, with no Save and no way on to saving, until %s",
    async (_case, shutting) => {
      const { sent } = opening(DRAFT, {
        [`GET ${ADDRESS}`]: [
          reply(WORKFLOW),
          reply({
            ...WORKFLOW,
            revision: 5,
            steps: [{ ...ASKING, name: "their_triage" }],
          }),
        ],
        [`PUT ${ADDRESS}/steps`]: [
          reply({ code: "DRAFT_WRITTEN_SINCE_READ" }, 409),
        ],
      });
      await opened();
      await userEvent.click(
        screen.getByRole("link", { name: `Open ${isolatedInText("triage")}` }),
      );
      await userEvent.type(
        within(stepDrawer()).getAllByRole("textbox", { name: "Name" })[0]!,
        "_mine",
      );
      await userEvent.click(saveSteps());
      await userEvent.click(
        await screen.findByRole("button", { name: LOAD_SAVED }),
      );
      await waitFor(() =>
        expect(
          rowsBeneath().map((row) => row.querySelector("a bdi")?.textContent),
        ).toEqual(["their_triage"]),
      );

      const drawer = stepDrawer();
      expect(within(drawer).getByRole("alert")).toHaveTextContent(
        "Somebody changed this draft since it was read; nothing was saved.",
      );
      expect(drawer.textContent).not.toMatch(/again|load the saved draft/i);
      expect(screen.queryByRole("button", { name: SAVE_STEPS })).toBeNull();

      if (shutting) await shutStep();
      else
        await userEvent.type(
          within(drawer).getAllByRole("textbox", { name: "Name" })[0]!,
          "x",
        );

      expect(
        screen.queryByText(
          "Somebody changed this draft since it was read; nothing was saved.",
        ),
      ).toBeNull();
      expect(
        screen.queryAllByRole("button", { name: SAVE_STEPS }),
      ).toHaveLength(shutting ? 0 : 1);
      expect(requestsTo(sent)).toEqual([
        `GET ${ADDRESS}`,
        `PUT ${ADDRESS}/steps`,
        `GET ${ADDRESS}`,
      ]);
    },
  );

  it.each([
    ["What it takes", "takes", 900],
    ["What it takes", "takes", 1400],
    ["What it gives back", "gives", 900],
    ["What it gives back", "gives", 1400],
  ] as const)(
    "says %s was refused for somebody saving since once, inside the drawer that saved it, and loads the saved draft only when asked (%s, width %s)",
    async (title, half, width) => {
      const stored = half === "takes" ? COMPLAINT : SUMMARY;
      const theirs = {
        ...WORKFLOW,
        revision: 5,
        [half]: [{ ...stored, name: `their_${stored.name}` }],
      };
      const { sent, refused } = opening(
        DRAFT,
        {
          [`GET ${ADDRESS}`]: [reply(WORKFLOW), reply(theirs)],
          [`PUT ${ADDRESS}/${half}`]: [
            reply({ code: "DRAFT_WRITTEN_SINCE_READ" }, 409),
          ],
        },
        "",
        width,
      );
      await opened();
      const list = within(part(title)).getByRole("group", { name: title });
      const name = nameInOpenedField(title);
      await userEvent.type(name, "_raw");
      await userEvent.click(screen.getByRole("button", { name: "Save" }));

      const alert = await screen.findByRole("alert");
      expect(screen.getAllByRole("alert")).toHaveLength(1);
      expect(alert).toHaveTextContent(
        "Somebody changed this draft since it was read; nothing was saved.",
      );
      const load = screen.getByRole("button", { name: LOAD_SAVED });
      const drawer =
        width === 900 ? screen.getByRole("dialog") : name.closest("section")!;
      expect(drawer).toContainElement(name);
      expect(drawer).toContainElement(alert);
      expect(drawer).toContainElement(load);
      expect(list).not.toContainElement(alert);
      expect(document.querySelectorAll('[role="alert"]')).toHaveLength(1);
      expect(screen.queryByText(/Somebody saved this draft/)).toBeNull();
      expect(name).toHaveValue(`${stored.name}_raw`);
      expect(refused).toHaveBeenCalledTimes(1);
      expect(requestsTo(sent)).toEqual([
        `GET ${ADDRESS}`,
        `PUT ${ADDRESS}/${half}`,
      ]);

      await userEvent.click(load);

      await waitFor(() =>
        expect(
          within(part(title)).getByRole("rowgroup", {
            name: `their_${stored.name}`,
          }),
        ).toBeInTheDocument(),
      );
      expect(requestsTo(sent)).toEqual([
        `GET ${ADDRESS}`,
        `PUT ${ADDRESS}/${half}`,
        `GET ${ADDRESS}`,
      ]);
      expect(document.querySelectorAll('[role="alert"]')).toHaveLength(0);
      expect(screen.queryByRole("dialog")).toBeNull();
      expect(
        screen.getByRole("heading", { level: 3, name: title }),
      ).toHaveFocus();
    },
  );

  it.each([true, false])(
    "keeps a complete unsaved search draft when saving refuses its shape, with readable question facts %s",
    async (named) => {
      const fields = (["title", "body", "references", "other"] as const).map(
        (name, index) => ({ ...SUMMARY, fieldId: `search-${index}`, name }),
      );
      const workflow = {
        ...WORKFLOW,
        problems: [],
        steps: [
          {
            ...ASKING,
            name: "find",
            runs: {
              kind: "search",
              version: TRIAGE,
              searchWay: "web",
              takes: [COMPLAINT],
              gives: fields,
              standings: {
                title: "always",
                body: "never",
                references: "never",
                other: "never",
              },
            },
            tries: 2,
          },
        ],
        outputs: [
          {
            ...WORKFLOW.outputs[0]!,
            source: { step: 0, path: "title" },
          },
        ],
        offered: {
          ...WORKFLOW.offered,
          searchWays: [{ name: "web", tool: "web_search", gives: fields }],
        },
      };
      const problem = {
        code: "SEARCH_QUESTION_DOES_NOT_MATCH",
        searchWay: "web",
        ...(named ? { questionName: "Triage", questionNumber: 4 } : {}),
      };
      const { sent, written, refused, search } = opening(
        DRAFT,
        {
          [`GET ${ADDRESS}`]: [reply(workflow)],
          [`PUT ${ADDRESS}/steps`]: [reply(problem, 400)],
        },
        `?step=${ASKING.stepId}&keep=yes`,
      );
      await opened();
      await userEvent.type(
        screen.getByRole("textbox", { name: "Name" }),
        "_draft",
      );
      await userEvent.click(saveSteps());
      const question = named
        ? `${isolatedInText("Triage")} · Version 4`
        : "That question";
      const way = isolatedInText("web");

      expect(
        await screen.findByText(
          `${question} can't be used to search with ${way}: its answer isn't exactly what ${way} gives back. Pick a version set for searching with ${way}.`,
          { selector: '[role="alert"] *' },
        ),
      ).toBeInTheDocument();
      expect(screen.getByRole("textbox", { name: "Name" })).toHaveValue(
        "find_draft",
      );
      expect(rowsBeneath()).toHaveLength(1);
      expect(
        screen
          .getAllByRole("alert")
          .every((alert) => stepDrawer().contains(alert)),
      ).toBe(true);
      expect(saveSteps()).toHaveClass("MuiButton-contained");
      expect(search().get("step")).toBe(ASKING.stepId);
      expect(search().get("keep")).toBe("yes");
      expect(requestsTo(sent)).toEqual([
        `GET ${ADDRESS}`,
        `PUT ${ADDRESS}/steps`,
      ]);
      expect(bodyOf(sent, 1)).toMatchObject({
        revision: 3,
        steps: [
          {
            stepId: ASKING.stepId,
            name: "find_draft",
            runs: {
              kind: "search",
              version: TRIAGE.versionId,
              searchWay: "web",
              standings: {
                title: "always",
                body: "never",
                references: "never",
                other: "never",
              },
            },
            producer: { kind: "person" },
            tries: 2,
            bindings: [{ target: "complaint", source: { input: "complaint" } }],
          },
        ],
        outputs: [{ target: "summary", source: { step: 0, path: "title" } }],
      });
      expect(written).not.toHaveBeenCalled();
      expect(refused).toHaveBeenCalledTimes(1);
      expect(refused).toHaveBeenCalledWith({
        status: 400,
        code: "SEARCH_QUESTION_DOES_NOT_MATCH",
        detail: undefined,
        extensions: {
          searchWay: "web",
          ...(named ? { questionName: "Triage", questionNumber: 4 } : {}),
        },
      });
      expect(screen.queryByText(/Its question.*no longer matches/)).toBeNull();
      expect(screen.getByRole("alert").textContent).not.toContain(
        TRIAGE.versionId,
      );
    },
  );

  it("says nothing of somebody saving since for a write refused for anything else", async () => {
    opening(DRAFT, {
      [`PUT ${ADDRESS}/ceiling`]: [reply({ code: "CEILING_UNUSABLE" }, 400)],
    });
    await opened();
    await userEvent.click(screen.getByRole("button", { name: "Run settings" }));

    await userEvent.clear(screen.getByRole("textbox", { name: "Usage limit" }));
    await userEvent.click(
      screen.getByRole("button", { name: "Save what a run may spend" }),
    );

    expect(
      await screen.findByText(
        "A usage limit is a whole number from 1 to 9,007,199,254,740,991, or empty for no limit.",
        { selector: '[role="alert"] *' },
      ),
    ).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "Run settings" }));
    expect(
      screen.getByText(
        "A usage limit is a whole number from 1 to 9,007,199,254,740,991, or empty for no limit.",
        { selector: '[role="alert"] *' },
      ),
    ).toBeInTheDocument();
    expect(screen.queryByRole("textbox", { name: "Usage limit" })).toBeNull();
    expect(screen.queryByText(/Somebody saved this draft/)).toBeNull();
    expect(screen.queryByRole("button", { name: LOAD_SAVED })).toBeNull();
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
    const ceiling = screen.getByRole("textbox", { name: "Usage limit" });
    await userEvent.clear(ceiling);
    await userEvent.type(ceiling, "200");
    const save = screen.getByRole("button", {
      name: "Save what a run may spend",
    });
    await userEvent.click(save);
    await screen.findByRole("button", { name: LOAD_SAVED });

    await userEvent.click(save);

    await waitFor(() =>
      expect(screen.queryByText(/Somebody saved this draft/)).toBeNull(),
    );
    expect(screen.queryByRole("button", { name: LOAD_SAVED })).toBeNull();
  });

  it("sends a ceiling with both its settings, naming the revision read", async () => {
    const { sent, written } = opening(DRAFT, {
      [`PUT ${ADDRESS}/ceiling`]: [reply({ ...WORKFLOW, ceiling: "5000" })],
    });
    await opened();
    await userEvent.click(screen.getByRole("button", { name: "Run settings" }));

    await userEvent.type(
      within(part("What a run may spend")).getByRole("textbox", {
        name: "Usage limit",
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
    await closeFieldEditor("What it takes");
    await userEvent.type(nameInOpenedField("What it gives back"), "_text");
    await closeFieldEditor("What it gives back");
    await userEvent.click(screen.getByRole("button", { name: "Add a step" }));
    await userEvent.type(
      within(panel("Step 2, not named yet")).getByRole("textbox", {
        name: "Name",
      }),
      "tidy",
    );
    await shutStep();
    await userEvent.click(
      screen.getByRole("switch", { name: "Runs may be helped" }),
    );
    await userEvent.type(
      screen.getByRole("textbox", { name: "Usage limit" }),
      "0",
    );
    await userEvent.click(
      screen.getByRole("button", { name: "Save what a run may spend" }),
    );
    const others = [SAVE_STEPS, "Save whether a run may be helped"].map(
      (name) => screen.getByRole("button", { name }),
    );

    for (const control of others) {
      await act(async () => control.focus());
      await userEvent.keyboard("{Enter}");
    }

    others.forEach((control) =>
      expect(control).toHaveAttribute("aria-disabled", "true"),
    );
    for (const title of ["What it takes", "What it gives back"]) {
      const input = nameInOpenedField(title);
      expect(input).toHaveValue(
        title === "What it takes" ? "complaint_text" : "summary_text",
      );
      const save = within(
        input.closest<HTMLElement>('[role="dialog"], section')!,
      ).getByRole("button", { name: "Save" });
      expect(save).toHaveAttribute("aria-disabled", "true");
      await act(async () => save.focus());
      await userEvent.keyboard("{Enter}");
      await closeFieldEditor(title);
    }
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
    await userEvent.type(
      screen.getByRole("textbox", { name: "Usage limit" }),
      "0",
    );
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
    await shutStep();
    await userEvent.click(screen.getByRole("button", { name: "Run settings" }));
    const ceiling = screen.getByRole("textbox", { name: "Usage limit" });
    await userEvent.clear(ceiling);
    await userEvent.type(ceiling, "200");
    await userEvent.click(
      screen.getByRole("button", { name: "Save what a run may spend" }),
    );

    await waitFor(() => expect(written).toHaveBeenCalledTimes(1));
    expect(saveSteps()).toHaveClass("MuiButton-contained");
    await userEvent.click(
      screen.getByRole("link", { name: `Open ${isolatedInText("tidy")}` }),
    );
    expect(
      within(panel(isolatedInText("tidy"))).getByRole("textbox", {
        name: "Name",
      }),
    ).toHaveValue("tidy");
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
    await userEvent.type(
      screen.getByRole("textbox", { name: "Usage limit" }),
      "0",
    );
    await userEvent.click(
      screen.getByRole("switch", { name: "Runs may be helped" }),
    );
    const held = [
      screen.getByRole("button", { name: "Save what a run may spend" }),
      screen.getByRole("button", { name: "Save whether a run may be helped" }),
    ];
    await userEvent.click(held[0]!);
    await userEvent.click(
      await screen.findByRole("button", { name: LOAD_SAVED }),
    );

    for (const control of held) {
      await act(async () => control.focus());
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
