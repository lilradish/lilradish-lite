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
import { afterEach, describe, expect, it, vi } from "vitest";

import { StandingProvider } from "../../app/standing/StandingContext";
import { theme } from "../../lib/theme/theme";
import { requestsTo, serving, type Reply } from "../../testutil/answering";
import { laidOutAt } from "../../testutil/layout";
import { answered, inGroup } from "../../testutil/standingRead";
import { WorkPage } from "./WorkPage";

const GROUP = "00000003-0000-4000-8000-000000000c61";

const PAGE = `/groups/${GROUP}/work`;

const RUNS = `/api/groups/${GROUP}/runs`;

const OFFERED = `/api/groups/${GROUP}/offered-workflows`;

const KEPT_AS = "lilradish.work.drawing";

/** What an operator holds: starting runs, and reading the ones they started. */
const STARTS_AND_READS_OWN = ["read_membership", "start_run", "read_own_runs"];

/** Started this afternoon, and stopped since. */
const ALAN = {
  runId: "00000008-0000-4000-8000-000000000c63",
  number: 3,
  name: "Claim from Alan",
  workflow: { name: "Handle a claim", version: 1 },
  startedBy: { userId: "000c63", displayName: "Cat Example" },
  startedAt: "2026-09-18T12:00:00Z",
  lastHappenedAt: "2026-09-18T12:30:00Z",
  state: "stopped",
};

/** Started yesterday, by somebody no name is held for, and nothing done to it since but its first step. */
const GRACE = {
  runId: "00000008-0000-4000-8000-000000000c62",
  number: 2,
  name: "Refund for Grace",
  workflow: { name: "Pay a refund", version: 2 },
  startedBy: { userId: "000c62" },
  startedAt: "2026-09-17T09:00:00Z",
  lastHappenedAt: "2026-09-17T09:00:00Z",
  state: "running",
  at: "summarise",
};

/** Started last week, failed on a step since, and renamed yesterday. */
const ADA = {
  runId: "00000008-0000-4000-8000-000000000c61",
  number: 1,
  name: "Claim from Ada",
  workflow: { name: "Handle a claim", version: 1 },
  startedBy: { userId: "000c63", displayName: "Cat Example" },
  startedAt: "2026-09-11T08:00:00Z",
  lastHappenedAt: "2026-09-17T08:00:00Z",
  state: "failed",
};

const LISTED: Reply = [
  JSON.stringify({ items: [ALAN, GRACE, ADA], reading: "own" }),
  200,
];

/** Its newest version takes a complaint that must be given; the one before it takes nothing. */
const HANDLE = {
  entryId: "00000006-0000-4000-8000-000000000c61",
  name: "Handle a claim",
  purpose: "Sorts a claim out.",
  versions: [
    {
      versionId: "00000007-0000-4000-8000-000000000c62",
      number: 2,
      takes: [
        {
          name: "complaint",
          label: "Complaint",
          kind: "text",
          longest: 200,
          mustBeGiven: true,
        },
      ],
    },
    {
      versionId: "00000007-0000-4000-8000-000000000c61",
      number: 1,
      takes: [],
    },
  ],
};

const REFUND = {
  entryId: "00000006-0000-4000-8000-000000000c62",
  name: "Pay a refund",
  versions: [
    {
      versionId: "00000007-0000-4000-8000-000000000c63",
      number: 4,
      takes: [],
    },
  ],
};

/** Ada's run, begun: the one the page opens on once it has started. */
const BEGUN: Reply = [
  JSON.stringify({ runId: ADA.runId, number: 7, readable: true }),
  201,
];

/** Ada's run, begun, which the server says the reader may not read. */
const BEGUN_UNREADABLE: Reply = [
  JSON.stringify({ runId: ADA.runId, number: 7, readable: false }),
  201,
];

/** What is said where a run began that the reader may not read. */
const STARTED_UNREADABLE =
  "SUPPORT-7 has begun. Reading it is a permission this group has not given you, so it cannot be opened here.";

/** A refusal of values, listing each of `problems` and saying it found `found` in all. */
const doesNotFit = (
  problems: readonly { path: (string | number)[]; reason: string }[],
  found = problems.length,
): Reply => [
  JSON.stringify({
    code: "VALUE_DOES_NOT_FIT",
    problems,
    problemsFound: found,
  }),
  400,
];

/** What is said of a refusal of values, over what marks each place listed. */
const VALUES_REFUSED = "Each value is written as its field takes it.";

/** When the page opened, where the specs keep time, as the reader's own clock says it. */
const OPENED_AT = "Sep 18, 03:00 PM";

const ON_OFFER: Reply = [JSON.stringify({ workflows: [HANDLE, REFUND] }), 200];

/** Takes the day it is due, and items each named, which must be given, and counted. */
const ORDER = {
  entryId: "00000006-0000-4000-8000-000000000c64",
  name: "Take an order",
  versions: [
    {
      versionId: "00000007-0000-4000-8000-000000000c65",
      number: 1,
      takes: [
        { name: "due", label: "Due", kind: "date", mustBeGiven: false },
        {
          name: "items",
          label: "Items",
          kind: "fields",
          most: 3,
          mustBeGiven: false,
          fields: [
            {
              name: "name",
              label: "Name",
              kind: "text",
              longest: 20,
              mustBeGiven: true,
            },
            {
              name: "count",
              label: "Count",
              kind: "number",
              mustBeGiven: false,
            },
          ],
        },
      ],
    },
  ],
};

const ORDER_ON_OFFER: Reply = [JSON.stringify({ workflows: [ORDER] }), 200];

/** Ada's run as its own page reads it. */
const ADAS_RUN_READ = {
  runId: ADA.runId,
  number: 1,
  name: "Claim from Ada",
  workflow: {
    entryId: "00000006-0000-4000-8000-000000000c61",
    name: "Handle a claim",
    version: 1,
  },
  startedBy: ADA.startedBy,
  startedAt: ADA.startedAt,
  state: "running",
  spend: { sent: "0", cameBack: "0", spent: "0", cameBackUnknown: false },
  ceiling: { raiseNeedsApproval: false },
  acts: [],
};

const ADAS_RUN: Reply = [JSON.stringify(ADAS_RUN_READ), 200];

const SUMMARISE = "00000009-0000-4000-8000-000000000c61";

/** Ada's run as a step reads it: on its one step, which runs code, its first try being made. */
const ADAS_RUN_HEADER = {
  runId: ADA.runId,
  number: 1,
  versionId: HANDLE.versions[1].versionId,
  state: "running",
  at: { stepId: SUMMARISE, name: "summarise" },
  acts: [],
  progress: { done: 0, of: 1 },
};

const ADAS_STEP_ROW = {
  stepId: SUMMARISE,
  order: 1,
  name: "summarise",
  runs: { kind: "code_step", codeStep: "summarise" },
  producer: { kind: "code" },
  state: "running",
  where: { kind: "running", on: "next_try" },
  takesFrom: [],
  tries: { current: 0, declared: 1, beyond: false },
  cost: { callsAModel: false },
  acts: [],
  withheld: [],
};

/** Ada's run's steps as it is read in detail, its workflow one that acts rather than answers. */
const ADAS_STEPS: Reply = [
  JSON.stringify({
    run: ADAS_RUN_HEADER,
    declarations: { [ADAS_RUN_HEADER.versionId]: { takes: [], gives: [] } },
    gaveBack: { declares: "nothing" },
    steps: [ADAS_STEP_ROW],
  }),
  200,
];

/** That one step's own page, nothing yet gone into it or come out of it. */
const ADAS_STEP: Reply = [
  JSON.stringify({
    run: ADAS_RUN_HEADER,
    declarations: {},
    step: ADAS_STEP_ROW,
    triesMade: [],
  }),
  200,
];

/** Ada's run as its own page reads it where the reader may stop it. */
const STOPPABLE: Reply = [
  JSON.stringify({ ...ADAS_RUN_READ, acts: ["stop"] }),
  200,
];

/** Ada's run as stopping it answers. */
const STOPPED_BY_CAT: Reply = [
  JSON.stringify({
    ...ADAS_RUN_READ,
    state: "stopped",
    stopped: { at: "2026-09-18T12:40:00Z", by: ADA.startedBy },
    acts: ["open_again"],
  }),
  200,
];

const QUESTION_VERSION = "00000007-0000-4000-8000-000000000c69";

/** What Ada's run declares where its one step asks a person, who gives back a summary. */
const QUESTION_DECLARATIONS = {
  [ADAS_RUN_HEADER.versionId]: { takes: [], gives: [] },
  [QUESTION_VERSION]: {
    takes: [],
    gives: [
      {
        name: "summary",
        label: "Summary",
        kind: "text",
        longest: 1000,
        mustBeGiven: true,
      },
    ],
  },
};

/** That one step asking a person, its summary waiting on the reader's review. */
const REVIEWABLE_ROW = {
  ...ADAS_STEP_ROW,
  runs: {
    kind: "question",
    entryId: "00000006-0000-4000-8000-000000000c69",
    name: "Summarise",
    version: 1,
    versionId: QUESTION_VERSION,
  },
  producer: { kind: "person" },
  reviewer: { kind: "person" },
  state: "waiting",
  where: {
    kind: "waiting_on_review",
    number: 1,
    values: [{ field: "summary", on: "review_at_gate" }],
    since: "2026-09-18T12:00:00Z",
    waitsOn: "review_at_gate",
  },
  tries: { current: 1, declared: 1, beyond: false },
  gaveBack: [{ field: "summary", value: "A fire.", now: "waiting_on_review" }],
  acts: ["review"],
};

/** The run once the summary stood, with nothing left to run. */
const DONE_HEADER = {
  ...ADAS_RUN_HEADER,
  state: "done",
  at: undefined,
  progress: { done: 1, of: 1 },
};

/** That one step once its summary stood. */
const STOOD_ROW = {
  ...REVIEWABLE_ROW,
  state: "done",
  where: undefined,
  gaveBack: [{ field: "summary", value: "A fire.", now: "stands" }],
  acts: [],
};

const REVIEW = `${RUNS}/${ADA.runId}/steps/${SUMMARISE}/tries/1/review`;

/** Ada's run's steps, as a list of steps is read, where the one step is as `row` says under `run`. */
const stepsAs = (run: object, row: object): Reply => [
  JSON.stringify({
    run,
    declarations: QUESTION_DECLARATIONS,
    gaveBack: { declares: "nothing" },
    steps: [row],
  }),
  200,
];

/** The step's own page while its summary waits on the reader's review. */
const REVIEWABLE_STEP: Reply = [
  JSON.stringify({
    run: ADAS_RUN_HEADER,
    declarations: QUESTION_DECLARATIONS,
    step: REVIEWABLE_ROW,
    cameOut: [{ field: "summary", now: "waiting_on_review" }],
    triesMade: [
      {
        number: 1,
        beyond: false,
        producedBy: { kind: "person" },
        values: [
          { field: "summary", value: "A fire.", now: "waiting_on_review" },
        ],
        review: { asked: true },
        ended: "waiting",
        cost: { callsAModel: false },
      },
    ],
  }),
  200,
];

/** The step's own page once somebody assured the summary. */
const ASSURED: Reply = [
  JSON.stringify({
    run: DONE_HEADER,
    declarations: QUESTION_DECLARATIONS,
    step: STOOD_ROW,
    cameOut: [
      {
        field: "summary",
        standing: { number: 1, value: "A fire." },
        now: "stands",
      },
    ],
    triesMade: [
      {
        number: 1,
        beyond: false,
        producedBy: { kind: "person" },
        values: [
          {
            field: "summary",
            value: "A fire.",
            now: "stands",
            decision: { outcome: "assured" },
          },
        ],
        review: { asked: true, by: { kind: "person" } },
        ended: "stands",
        cost: { callsAModel: false },
      },
    ],
  }),
  200,
];

/** The runs listed as none but Ada's, as `row` says of it. */
const listedAs = (row: object): Reply => [
  JSON.stringify({ items: [row], reading: "own" }),
  200,
];

/** A reply the server never sends. */
const UNANSWERED: Reply = new Promise(() => {});

/** What is said on a name the server refused. */
const NAME_REFUSED =
  "A run's name is one to 128 characters on one line, with something in it that shows.";

/** What is said where starting is refused for the act itself: the rule of who may start one. */
const ACT_REFUSED =
  "A group's runs are started, renamed, stopped, opened again and given a ceiling, and a raise asked withdrawn, only by a role in it that may start one.";

/** What is said once what may be started is read again for a version no longer offered. */
const NO_LONGER_OFFERED =
  "That version can no longer be started here, so what may be started has been read again.";

/** Set apart from the words around it, as every name in a heading is. */
const isolatedName = (name: string) =>
  `${String.fromCodePoint(0x2068)}${name}${String.fromCodePoint(0x2069)}`;

interface Opening {
  readonly permissions?: readonly string[];
  readonly routes?: Readonly<Record<string, readonly Reply[]>>;
}

/**
 * The page under a real router with the standing it is handed, whose reading again is counted; only the
 * server and the day are stood in for, it being 15:00 on the 18th where the specs keep time.
 */
function opening(
  address: string,
  { permissions = STARTS_AND_READS_OWN, routes = {} }: Opening = {},
) {
  vi.useFakeTimers({ toFake: ["Date"] });
  vi.setSystemTime(new Date("2026-09-18T13:00:00Z"));
  const sent = serving({
    [`GET ${RUNS}`]: [LISTED],
    [`GET ${OFFERED}`]: [ON_OFFER],
    [`GET ${RUNS}/${ADA.runId}`]: [ADAS_RUN],
    [`GET ${RUNS}/${ADA.runId}/steps`]: [ADAS_STEPS],
    [`GET ${RUNS}/${ADA.runId}/steps/${SUMMARISE}`]: [ADAS_STEP],
    ...routes,
  });
  const router = createMemoryRouter(
    [
      { path: "/groups/:groupId/work/:runId?", element: <WorkPage /> },
      {
        path: "/groups/:groupId/work/:runId/steps/:stepId",
        element: <WorkPage />,
      },
    ],
    { initialEntries: [address] },
  );
  const readAgain = vi.fn();
  const standing = answered(
    [],
    [inGroup(GROUP, "SUPPORT", "Claims", permissions)],
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
    router,
    at: () =>
      `${router.state.location.pathname}${router.state.location.search}${router.state.location.hash}`,
  };
}

afterEach(() => {
  vi.useRealTimers();
  localStorage.clear();
});

function theRuns(): HTMLElement {
  return screen.getByRole("region", { name: "The runs" });
}

/** Each day listed, with the name and the line under it of each run it lists. */
async function days(): Promise<[string, string[]][]> {
  await within(theRuns()).findByRole("link", { name: /Claim from Ada/ });
  return within(theRuns())
    .getAllByRole("list")
    .map((list) => [
      list.getAttribute("aria-labelledby") === null
        ? ""
        : (document.getElementById(list.getAttribute("aria-labelledby")!)
            ?.textContent ?? ""),
      within(list)
        .getAllByRole("link")
        .map((link) => link.textContent ?? ""),
    ]);
}

async function inDetail() {
  await userEvent.click(screen.getByRole("button", { name: "In detail" }));
}

function table(): HTMLElement {
  return screen.getByRole("table", { name: "What is running" });
}

/** What the table says of its rows, in the region that follows it. */
function tableStatus(): HTMLElement {
  const region = table().parentElement?.nextElementSibling;
  if (
    !(region instanceof HTMLElement) ||
    region.getAttribute("role") !== "status"
  ) {
    throw new Error("No status follows the table of what is running.");
  }
  return region;
}

/** Every row's cells, once the first page has landed. */
async function rows(): Promise<string[][]> {
  await within(table()).findByRole("link", { name: "SUPPORT-1" });
  const [, ...listed] =
    within(table()).getAllByRole<HTMLTableRowElement>("row");
  return listed.map((row) =>
    [...row.cells].map((cell) => cell.textContent ?? ""),
  );
}

function runsAsked(sent: Parameters<typeof requestsTo>[0]): string[] {
  return requestsTo(sent).filter((each) => each.startsWith(`GET ${RUNS}?`));
}

/** Every run asked to start, as its document was sent. */
function startsAsked(sent: Parameters<typeof requestsTo>[0]): unknown[] {
  return sent.mock.calls
    .filter(([, init]) => init?.method === "POST")
    .map(([, init]) => JSON.parse(String(init?.body)) as unknown);
}

/** The card of a workflow pressed, once what is on offer has landed; the heading of what it draws. */
async function picking(workflow: string): Promise<HTMLElement> {
  await userEvent.click(
    await screen.findByRole("button", { name: new RegExp(workflow) }),
  );
  return screen.getByRole("heading", {
    level: 3,
    name: isolatedName(workflow),
  });
}

/** The dialog Start a run opens, drawn in detail once the runs have landed. */
async function openingTheDialog(): Promise<HTMLElement> {
  await rows();
  await userEvent.click(screen.getByRole("button", { name: "Start a run" }));
  return screen.findByRole("dialog", { name: "Start a run" });
}

async function choosingInTheDialog(
  dialog: HTMLElement,
  workflow: string,
): Promise<void> {
  await userEvent.click(
    await within(dialog).findByRole("combobox", { name: "Workflow" }),
  );
  await userEvent.click(
    screen.getByRole("option", { name: isolatedName(workflow) }),
  );
}

describe("WorkPage", () => {
  describe("as a conversation", () => {
    it("opens so on a first visit, asking for the run last acted on first and listing each under the day of that", async () => {
      const { sent } = opening(PAGE);

      expect(await days()).toEqual([
        [
          "Today",
          [
            `${isolatedName("Claim from Alan")}${isolatedName("Handle a claim")} · Stopped`,
          ],
        ],
        [
          "Yesterday",
          [
            `${isolatedName("Refund for Grace")}${isolatedName("Pay a refund")} · Running, on ${isolatedName("summarise")}`,
            `${isolatedName("Claim from Ada")}${isolatedName("Handle a claim")} · Failed`,
          ],
        ],
      ]);
      expect(
        screen.getByRole("button", { name: "As a conversation" }),
      ).toHaveAttribute("aria-pressed", "true");
      expect(screen.queryByRole("table")).toBeNull();
      expect(runsAsked(sent)).toEqual([`GET ${RUNS}?sort=-lastHappened`]);
    });

    it("names the page first, then each day as a heading under it, then what may start", async () => {
      opening(PAGE);
      await days();
      await screen.findByRole("heading", { name: "A new run" });

      expect(
        screen
          .getAllByRole("heading")
          .map((heading) => [heading.tagName, heading.textContent]),
      ).toEqual([
        ["H1", `Work of ${isolatedName("Claims")}`],
        ["H2", "Today"],
        ["H2", "Yesterday"],
        ["H2", "A new run"],
      ]);
    });

    it("names the page first and the run open under it, as the page's one first-level heading", async () => {
      opening(`${PAGE}/${ADA.runId}`);
      await days();
      await screen.findByRole("heading", { level: 2, name: /Claim from Ada/ });

      expect(
        screen
          .getAllByRole("heading")
          .map((heading) => [heading.tagName, heading.textContent]),
      ).toEqual([
        ["H1", `Work of ${isolatedName("Claims")}`],
        ["H2", "Today"],
        ["H2", "Yesterday"],
        ["H2", isolatedName("Claim from Ada")],
        ["H3", "What it was started with"],
        ["H3", isolatedName("summarise")],
      ]);
    });

    it("narrows the runs to what the filter typed holds, as the server matches it, and keeps the filter in the address", async () => {
      const { sent, at } = opening(PAGE, {
        routes: {
          [`GET ${RUNS}`]: [
            LISTED,
            [JSON.stringify({ items: [ADA], reading: "own" }), 200],
          ],
        },
      });
      await days();

      await userEvent.type(
        screen.getByRole("searchbox", {
          name: "Part of a run's name, or its workflow's",
        }),
        "ada",
      );

      await waitFor(() =>
        expect(requestsTo(sent)).toContain(
          `GET ${RUNS}?sort=-lastHappened&filter=ada`,
        ),
      );
      await waitFor(() =>
        expect(
          within(theRuns()).queryByRole("link", { name: /Claim from Alan/ }),
        ).toBeNull(),
      );
      expect(
        within(theRuns()).getByRole("link", { name: /Claim from Ada/ }),
      ).toBeInTheDocument();
      expect(at()).toBe(`${PAGE}?filter=ada`);
    });

    it("opens a run in the rest of the page, the runs still beside it and its line marked, the filter kept", async () => {
      const { at } = opening(`${PAGE}?filter=claim`);
      await days();

      await userEvent.click(
        within(theRuns()).getByRole("link", { name: /Claim from Ada/ }),
      );

      expect(
        await screen.findByRole("heading", {
          level: 2,
          name: /Claim from Ada/,
        }),
      ).toBeInTheDocument();
      expect(at()).toBe(`${PAGE}/${ADA.runId}?filter=claim`);
      expect(
        within(theRuns()).getByRole("link", { name: /Claim from Ada/ }),
      ).toHaveAttribute("aria-current", "true");
      expect(
        within(theRuns()).getByRole("link", { name: /Claim from Alan/ }),
      ).not.toHaveAttribute("aria-current");
      expect(screen.queryByRole("heading", { name: "A new run" })).toBeNull();
    });

    it("draws the page in detail once a run's More asks for it, as the switch at its top would, keeping that choice and taking the keyboard to that switch", async () => {
      opening(`${PAGE}/${ADA.runId}`);
      await screen.findByRole("list", { name: "The conversation" });
      await userEvent.click(screen.getByRole("button", { name: "More" }));

      await userEvent.click(
        screen.getByRole("button", { name: "Show in detail" }),
      );

      expect(
        await screen.findByRole("table", { name: "The steps" }),
      ).toBeInTheDocument();
      const inDetail = screen.getByRole("button", { name: "In detail" });
      expect(inDetail).toHaveAttribute("aria-pressed", "true");
      expect(document.activeElement).toBe(inDetail);
      expect(localStorage.getItem(KEPT_AS)).toBe("detail");
      expect(
        screen.queryByRole("list", { name: "The conversation" }),
      ).toBeNull();
    });

    it("starts the keyboard at the step's message the address names as the page arrives, forgetting it in place, and not again once the switch draws it anew", async () => {
      const { at, router } = opening(
        `${PAGE}/${ADA.runId}?filter=claim#step-${SUMMARISE}`,
      );
      const arrivedAt = await screen.findByRole("list", {
        name: "The conversation",
      });
      await waitFor(() =>
        expect(document.activeElement).toBe(
          document.getElementById(`step-${SUMMARISE}`),
        ),
      );
      expect(at()).toBe(`${PAGE}/${ADA.runId}?filter=claim`);
      expect(router.state.historyAction).toBe("REPLACE");
      await act(() => router.navigate(-1));
      expect(at()).toBe(`${PAGE}/${ADA.runId}?filter=claim`);

      await userEvent.click(screen.getByRole("button", { name: "In detail" }));
      await screen.findByRole("table", { name: "The steps" });
      const asConversation = screen.getByRole("button", {
        name: "As a conversation",
      });
      await userEvent.click(asConversation);

      const drawnAgain = await screen.findByRole("list", {
        name: "The conversation",
      });
      expect(drawnAgain).not.toBe(arrivedAt);
      expect(document.activeElement).toBe(asConversation);
    });

    it("opens a step in the run's place, the runs still beside it and the run's line marked, with a way back to the run", async () => {
      const { sent } = opening(
        `${PAGE}/${ADA.runId}/steps/${SUMMARISE}?filter=claim`,
      );

      expect(
        await screen.findByRole("heading", { level: 2, name: /summarise/ }),
      ).toBeInTheDocument();
      expect(
        screen.getByRole("link", { name: "Back to the run" }),
      ).toHaveAttribute("href", `${PAGE}/${ADA.runId}?filter=claim`);
      expect(
        await within(theRuns()).findByRole("link", { name: /Claim from Ada/ }),
      ).toHaveAttribute("aria-current", "true");
      expect(
        screen.queryByRole("heading", { name: /Claim from Ada/ }),
      ).toBeNull();
      expect(requestsTo(sent)).not.toContain(`GET ${RUNS}/${ADA.runId}`);
    });

    it("reads the runs again once a review on the run open answers, so the run it finished is listed Done, the keyboard left where the review put it", async () => {
      const { sent } = opening(`${PAGE}/${ADA.runId}`, {
        routes: {
          [`GET ${RUNS}`]: [
            listedAs({ ...ADA, state: "running", at: "summarise" }),
            listedAs({ ...ADA, state: "done" }),
          ],
          [`GET ${RUNS}/${ADA.runId}/steps`]: [
            stepsAs(ADAS_RUN_HEADER, REVIEWABLE_ROW),
            stepsAs(DONE_HEADER, STOOD_ROW),
          ],
          [`PUT ${REVIEW}`]: [ASSURED],
        },
      });
      const line = () =>
        within(theRuns()).getByRole("link", { name: /Claim from Ada/ });
      await within(theRuns()).findByRole("link", { name: /Claim from Ada/ });
      expect(line()).toHaveTextContent("Running");

      await userEvent.click(
        await screen.findByRole("button", { name: "Assure" }),
      );
      await waitFor(() => expect(line()).toHaveTextContent("Done"));

      expect(line()).not.toHaveTextContent("Running");
      expect(line()).toHaveAttribute("aria-current", "true");
      expect(runsAsked(sent)).toHaveLength(2);
      expect(theRuns().contains(document.activeElement)).toBe(false);
      expect(screen.queryByRole("button", { name: "Assure" })).toBeNull();
    });

    it("reads the runs again once a review on the step open answers, the keyboard left on what came out", async () => {
      laidOutAt(0, 1200);
      const { sent } = opening(`${PAGE}/${ADA.runId}/steps/${SUMMARISE}`, {
        routes: {
          [`GET ${RUNS}`]: [
            listedAs({ ...ADA, state: "running", at: "summarise" }),
            listedAs({ ...ADA, state: "done" }),
          ],
          [`GET ${RUNS}/${ADA.runId}/steps/${SUMMARISE}`]: [
            REVIEWABLE_STEP,
            ASSURED,
          ],
          [`PUT ${REVIEW}`]: [ASSURED],
        },
      });
      const line = () =>
        within(theRuns()).getByRole("link", { name: /Claim from Ada/ });
      await within(theRuns()).findByRole("link", { name: /Claim from Ada/ });

      await userEvent.click(
        await screen.findByRole("button", { name: "Assure" }),
      );
      await waitFor(() => expect(line()).toHaveTextContent("Done"));

      expect(runsAsked(sent)).toHaveLength(2);
      expect(document.activeElement).toBe(
        screen.getByRole("heading", { name: "What came out" }),
      );
      expect(theRuns().contains(document.activeElement)).toBe(false);
      expect(screen.queryByRole("button", { name: "Assure" })).toBeNull();
    });

    it("reads the runs again once Stop on the run open answers, the keyboard left on the control it became", async () => {
      const { sent } = opening(`${PAGE}/${ADA.runId}`, {
        routes: {
          [`GET ${RUNS}`]: [
            listedAs({ ...ADA, state: "running", at: "summarise" }),
            listedAs({ ...ADA, state: "stopped" }),
          ],
          [`GET ${RUNS}/${ADA.runId}`]: [STOPPABLE, STOPPED_BY_CAT],
          [`PUT ${RUNS}/${ADA.runId}/stop`]: [STOPPED_BY_CAT],
        },
      });
      const line = () =>
        within(theRuns()).getByRole("link", { name: /Claim from Ada/ });
      await within(theRuns()).findByRole("link", { name: /Claim from Ada/ });
      const control = await screen.findByRole("button", { name: "Stop" });

      await userEvent.click(control);
      await waitFor(() => expect(line()).toHaveTextContent("Stopped"));

      expect(line()).not.toHaveTextContent("Running");
      expect(runsAsked(sent)).toHaveLength(2);
      expect(control).toHaveTextContent("Open again");
      expect(document.activeElement).toBe(control);
      expect(theRuns().contains(document.activeElement)).toBe(false);
    });

    it("reads the runs again once the step is read again after a review refused as moved on, the keyboard left on what that says", async () => {
      const { sent } = opening(`${PAGE}/${ADA.runId}`, {
        routes: {
          [`GET ${RUNS}`]: [
            listedAs({ ...ADA, state: "running", at: "summarise" }),
            listedAs({ ...ADA, state: "done" }),
          ],
          [`GET ${RUNS}/${ADA.runId}/steps`]: [
            stepsAs(ADAS_RUN_HEADER, REVIEWABLE_ROW),
            stepsAs(DONE_HEADER, STOOD_ROW),
          ],
          [`PUT ${REVIEW}`]: [[JSON.stringify({ code: "STEP_MOVED_ON" }), 409]],
          [`GET ${RUNS}/${ADA.runId}/steps/${SUMMARISE}`]: [ASSURED],
        },
      });
      const line = () =>
        within(theRuns()).getByRole("link", { name: /Claim from Ada/ });
      await within(theRuns()).findByRole("link", { name: /Claim from Ada/ });

      await userEvent.click(
        await screen.findByRole("button", { name: "Assure" }),
      );
      await waitFor(() => expect(line()).toHaveTextContent("Done"));

      const said = await screen.findByRole("alert");
      expect(said).toHaveTextContent(
        "That step has moved on since it was read.",
      );
      expect(runsAsked(sent)).toHaveLength(2);
      expect(document.activeElement).toContainElement(said);
      expect(theRuns().contains(document.activeElement)).toBe(false);
    });

    it("says it is still reading the runs only while none has answered, and not while they are read again after an act", async () => {
      let settle!: (answer: readonly [string, number]) => void;
      const first: Reply = new Promise((done) => {
        settle = done;
      });
      const { sent } = opening(`${PAGE}/${ADA.runId}`, {
        routes: {
          [`GET ${RUNS}`]: [first, UNANSWERED],
          [`GET ${RUNS}/${ADA.runId}`]: [STOPPABLE, STOPPED_BY_CAT],
          [`PUT ${RUNS}/${ADA.runId}/stop`]: [STOPPED_BY_CAT],
        },
      });
      const status = within(theRuns()).getByRole("status");
      expect(status).toHaveTextContent("Still reading…");

      settle(LISTED as readonly [string, number]);
      await within(theRuns()).findByRole("link", { name: /Claim from Ada/ });
      expect(status).not.toHaveTextContent("Still reading…");
      await userEvent.click(
        await screen.findByRole("button", { name: "Stop" }),
      );
      await waitFor(() => expect(runsAsked(sent)).toHaveLength(2));

      expect(status).not.toHaveTextContent("Still reading…");
      expect(
        within(theRuns()).getByRole("link", { name: /Claim from Ada/ }),
      ).toBeVisible();
    });

    it("leaves what a step's page picked behind when another run is opened from beside it, keeping the list's address", async () => {
      const { at } = opening(
        `${PAGE}/${ADA.runId}/steps/${SUMMARISE}?filter=claim&value=summary&try=1`,
      );
      await screen.findByRole("heading", { level: 2, name: /summarise/ });

      await userEvent.click(
        await within(theRuns()).findByRole("link", { name: /Claim from Alan/ }),
      );

      expect(at()).toBe(`${PAGE}/${ALAN.runId}?filter=claim`);
      expect(
        screen.getByRole("link", { name: "New run" }).getAttribute("href"),
      ).toBe(`${PAGE}?filter=claim`);
    });

    it("draws the workflows on offer as cards where no run is open, each saying what it is for, pressable and none picked yet", async () => {
      const { sent } = opening(PAGE);

      const offered = await screen.findByRole("group", { name: "A new run" });

      expect(
        await within(offered).findByText(isolatedName("Handle a claim")),
      ).toBeInTheDocument();
      expect(
        within(offered).getByText(isolatedName("Sorts a claim out.")),
      ).toBeInTheDocument();
      expect(
        within(offered)
          .getAllByRole("button")
          .map((card) => [card.textContent, card.getAttribute("aria-pressed")]),
      ).toEqual([
        [
          `${isolatedName("Handle a claim")}${isolatedName("Sorts a claim out.")}`,
          "false",
        ],
        [isolatedName("Pay a refund"), "false"],
      ]);
      expect(within(offered).queryByRole("textbox")).toBeNull();
      expect(requestsTo(sent)).toContain(`GET ${OFFERED}`);
    });

    it("draws under the cards, once one is picked, what its newest version takes with a name already suggested, the version not shown until asked for", async () => {
      opening(PAGE);

      const form = await picking("Handle a claim");

      expect(form).toHaveFocus();
      expect(
        screen.getByRole("button", { name: /Handle a claim/ }),
      ).toHaveAttribute("aria-pressed", "true");
      expect(
        screen.getByRole("textbox", { name: "Name of this run" }),
      ).toHaveValue(`Handle a claim ${OPENED_AT}`);
      expect(
        screen.getByRole("textbox", { name: "Complaint" }),
      ).toBeInTheDocument();
      expect(screen.queryByRole("combobox", { name: "Version" })).toBeNull();
      expect(
        screen.getByRole("button", { name: "Choose another version" }),
      ).toBeInTheDocument();
    });

    it("offers no other version where only one is in service, and nothing to fill where it takes nothing", async () => {
      opening(PAGE);

      await picking("Pay a refund");

      expect(
        screen.queryByRole("button", { name: "Choose another version" }),
      ).toBeNull();
      expect(
        screen.getByText("It takes nothing, so it may be started as it is."),
      ).toBeInTheDocument();
      expect(screen.getAllByRole("textbox")).toEqual([
        screen.getByRole("textbox", { name: "Name of this run" }),
      ]);
    });

    it("suggests the first line of the first text as the name, following it until the name is changed, and not again once it is cleared", async () => {
      opening(PAGE);
      await picking("Handle a claim");
      const name = screen.getByRole("textbox", { name: "Name of this run" });
      const complaint = screen.getByRole("textbox", { name: "Complaint" });

      await userEvent.type(complaint, "Kettle leaks{Enter}Since Monday");
      expect(name).toHaveValue("Kettle leaks");

      await userEvent.clear(name);
      await userEvent.type(complaint, ", badly");

      expect(name).toHaveValue("");
    });

    it("starts the run of the newest version with its name and every value, and the rest of the page becomes that run, taking the keyboard to its name", async () => {
      const { sent, at } = opening(`${PAGE}?filter=claim`, {
        routes: { [`POST ${RUNS}`]: [BEGUN] },
      });
      await picking("Handle a claim");
      await userEvent.type(
        screen.getByRole("textbox", { name: "Complaint" }),
        "Kettle leaks",
      );

      await userEvent.click(screen.getByRole("button", { name: "Start" }));

      const opened = await screen.findByRole("heading", {
        level: 2,
        name: /Claim from Ada/,
      });
      await waitFor(() => expect(opened).toHaveFocus());
      expect(at()).toBe(`${PAGE}/${ADA.runId}?filter=claim`);
      expect(startsAsked(sent)).toEqual([
        {
          name: "Kettle leaks",
          versionId: HANDLE.versions[0]!.versionId,
          values: { complaint: "Kettle leaks" },
        },
      ]);
      expect(runsAsked(sent)).toHaveLength(2);
      expect(screen.queryByRole("group", { name: "A new run" })).toBeNull();
    });

    it("takes the keyboard to the run begun only as it arrives, and not once it is opened again after another", async () => {
      const gracesRun: Reply = [
        JSON.stringify({
          ...(JSON.parse(ADAS_RUN[0] as string) as object),
          runId: GRACE.runId,
          name: GRACE.name,
        }),
        200,
      ];
      opening(PAGE, {
        routes: {
          [`POST ${RUNS}`]: [BEGUN],
          [`GET ${RUNS}/${GRACE.runId}`]: [gracesRun],
          [`GET ${RUNS}/${GRACE.runId}/steps`]: [ADAS_STEPS],
        },
      });
      await picking("Pay a refund");
      await userEvent.click(screen.getByRole("button", { name: "Start" }));
      const begun = await screen.findByRole("heading", {
        level: 2,
        name: /Claim from Ada/,
      });
      await waitFor(() => expect(begun).toHaveFocus());

      await userEvent.click(
        within(theRuns()).getByRole("link", { name: /Refund for Grace/ }),
      );
      await screen.findByRole("heading", {
        level: 2,
        name: /Refund for Grace/,
      });
      const ada = within(theRuns()).getByRole("link", {
        name: /Claim from Ada/,
      });
      await userEvent.click(ada);

      const reopened = await screen.findByRole("heading", {
        level: 2,
        name: /Claim from Ada/,
      });
      expect(reopened).not.toHaveFocus();
      expect(ada).toHaveFocus();
    });

    it("says at once that what must be given is empty, and once Start is pressed marks it so, takes the keyboard there and asks nothing", async () => {
      const { sent } = opening(PAGE, {
        routes: { [`POST ${RUNS}`]: [BEGUN] },
      });
      await picking("Handle a claim");
      const complaint = screen.getByRole("textbox", { name: "Complaint" });
      const start = screen.getByRole("button", { name: "Start" });
      expect(
        screen.getByText("A field that must be given is still empty."),
      ).toBeInTheDocument();
      expect(complaint).toHaveAttribute("aria-invalid", "false");

      start.focus();
      await userEvent.keyboard("{Enter}");

      expect(start).not.toHaveAttribute("aria-disabled");
      expect(complaint).toHaveAttribute("aria-invalid", "true");
      expect(complaint).toHaveAccessibleDescription(
        expect.stringContaining("This must be given."),
      );
      expect(complaint).toHaveFocus();
      expect(startsAsked(sent)).toEqual([]);
    });

    it("takes the keyboard to the name once Start is pressed where it is none a run may be called, and asks nothing", async () => {
      const { sent } = opening(PAGE, {
        routes: { [`POST ${RUNS}`]: [BEGUN] },
      });
      await picking("Pay a refund");
      const name = screen.getByRole("textbox", { name: "Name of this run" });
      await userEvent.clear(name);

      await userEvent.click(screen.getByRole("button", { name: "Start" }));

      expect(name).toHaveFocus();
      expect(name).toHaveAttribute("aria-invalid", "true");
      expect(startsAsked(sent)).toEqual([]);
    });

    it("takes the keyboard to the first place that does not fit, in declared order, marking every place and asking nothing", async () => {
      const { sent } = opening(PAGE, {
        routes: {
          [`GET ${OFFERED}`]: [ORDER_ON_OFFER],
          [`POST ${RUNS}`]: [BEGUN],
        },
      });
      await picking("Take an order");
      const items = screen.getByRole("group", { name: "Items" });
      await userEvent.click(
        within(items).getByRole("button", {
          name: `Add one to ${isolatedName("Items")}`,
        }),
      );
      await userEvent.type(
        within(items).getByRole("textbox", { name: "Count" }),
        "2",
      );

      await userEvent.click(screen.getByRole("button", { name: "Start" }));

      const name = within(items).getByRole("textbox", { name: "Name" });
      expect(name).toHaveFocus();
      expect(name).toHaveAttribute("aria-invalid", "true");
      expect(
        within(items).getByRole("textbox", { name: "Count" }),
      ).toHaveAttribute("aria-invalid", "false");
      expect(startsAsked(sent)).toEqual([]);
    });

    it("says why a value does not fit only once the keyboard has left it, while saying at once that a value does not fit", async () => {
      opening(PAGE);
      await picking("Handle a claim");
      const complaint = screen.getByRole("textbox", { name: "Complaint" });

      await userEvent.type(complaint, `Kettle${String.fromCodePoint(0x2066)}`);
      expect(complaint).toHaveAttribute("aria-invalid", "false");
      expect(
        screen.getByText(
          "A value does not fit its field yet; each one says why.",
        ),
      ).toBeInTheDocument();

      await userEvent.tab();

      expect(complaint).toHaveAttribute("aria-invalid", "true");
    });

    it("says fields that must be given hold nothing once what they hold has been changed and emptied", async () => {
      const address = {
        entryId: "00000006-0000-4000-8000-000000000c63",
        name: "Send a letter",
        versions: [
          {
            versionId: "00000007-0000-4000-8000-000000000c64",
            number: 1,
            takes: [
              {
                name: "address",
                label: "Address",
                kind: "fields",
                mustBeGiven: true,
                fields: [
                  {
                    name: "street",
                    label: "Street",
                    kind: "text",
                    longest: 20,
                    mustBeGiven: false,
                  },
                ],
              },
            ],
          },
        ],
      };
      opening(PAGE, {
        routes: {
          [`GET ${OFFERED}`]: [[JSON.stringify({ workflows: [address] }), 200]],
        },
      });
      await picking("Send a letter");
      const group = screen.getByRole("group", { name: "Address" });
      expect(group).toHaveAccessibleDescription("");

      const street = within(group).getByRole("textbox", { name: "Street" });
      await userEvent.type(street, "1");
      await userEvent.clear(street);

      expect(group).toHaveAccessibleDescription("This must be given.");
      expect(street).toHaveAttribute("aria-invalid", "false");
    });

    it("moves what was changed of each one of many down past one taken away, so one added in its place is not yet said to be empty", async () => {
      const labelled = {
        entryId: "00000006-0000-4000-8000-000000000c65",
        name: "Label a note",
        versions: [
          {
            versionId: "00000007-0000-4000-8000-000000000c66",
            number: 1,
            takes: [
              {
                name: "tags",
                label: "Tags",
                kind: "text",
                longest: 20,
                most: 2,
                mustBeGiven: false,
              },
            ],
          },
        ],
      };
      opening(PAGE, {
        routes: {
          [`GET ${OFFERED}`]: [
            [JSON.stringify({ workflows: [labelled] }), 200],
          ],
        },
      });
      await picking("Label a note");
      const tags = screen.getByRole("group", { name: "Tags" });
      const tag = (position: number) =>
        within(tags).getByRole("textbox", {
          name: `${isolatedName("Tags")} ${position}`,
        });
      const addOne = () =>
        userEvent.click(
          within(tags).getByRole("button", {
            name: `Add one to ${isolatedName("Tags")}`,
          }),
        );
      await addOne();
      await userEvent.type(tag(1), "a");
      await addOne();
      await userEvent.type(tag(2), "b");

      await userEvent.click(
        within(tags).getByRole("button", {
          name: `Take away ${isolatedName("Tags")} 1`,
        }),
      );
      await addOne();

      expect(tag(1)).toHaveValue("b");
      expect(tag(1)).toHaveAttribute("aria-invalid", "false");
      expect(tag(2)).toHaveValue("");
      expect(tag(2)).toHaveAttribute("aria-invalid", "false");
      expect(tag(2)).not.toHaveAccessibleDescription(/must be given/);
      expect(tags).toHaveAccessibleDescription("2 / at most 2");
    });

    it("starts another version once asked for, filling what that one takes", async () => {
      const { sent } = opening(PAGE, {
        routes: { [`POST ${RUNS}`]: [BEGUN] },
      });
      await picking("Handle a claim");

      await userEvent.click(
        screen.getByRole("button", { name: "Choose another version" }),
      );
      expect(screen.getByRole("combobox", { name: "Version" })).toHaveFocus();
      await userEvent.click(screen.getByRole("combobox", { name: "Version" }));
      await userEvent.click(screen.getByRole("option", { name: "Version 1" }));
      await userEvent.click(screen.getByRole("button", { name: "Start" }));

      await waitFor(() =>
        expect(startsAsked(sent)).toEqual([
          {
            name: `Handle a claim ${OPENED_AT}`,
            versionId: HANDLE.versions[1]!.versionId,
            values: {},
          },
        ]),
      );
      expect(screen.queryByRole("textbox", { name: "Complaint" })).toBeNull();
    });

    it("stays where it was for one who may start a run and not read it, saying it began, its key and number, and that it cannot be read", async () => {
      const { at } = opening(PAGE, {
        permissions: ["read_membership", "start_run"],
        routes: {
          [`GET ${RUNS}`]: [['{"items":[],"reading":"none"}', 200]],
          [`POST ${RUNS}`]: [BEGUN_UNREADABLE],
        },
      });
      await picking("Pay a refund");

      await userEvent.click(screen.getByRole("button", { name: "Start" }));

      expect(await screen.findByText(STARTED_UNREADABLE)).toBeInTheDocument();
      expect(at()).toBe(PAGE);
      expect(
        screen.queryByRole("textbox", { name: "Name of this run" }),
      ).toBeNull();
      expect(
        screen.getByRole("button", { name: /Pay a refund/ }),
      ).toHaveAttribute("aria-pressed", "false");
    });

    it("stays where it was where the server says the run begun cannot be read, whatever the standing says of reading runs", async () => {
      const { at } = opening(PAGE, {
        routes: { [`POST ${RUNS}`]: [BEGUN_UNREADABLE] },
      });
      await picking("Pay a refund");

      await userEvent.click(screen.getByRole("button", { name: "Start" }));

      expect(await screen.findByText(STARTED_UNREADABLE)).toBeInTheDocument();
      expect(at()).toBe(PAGE);
      expect(
        screen.queryByRole("heading", { level: 2, name: /Claim from Ada/ }),
      ).toBeNull();
    });

    it("opens the run begun where the server says it can be read, whatever the standing says of reading runs", async () => {
      const { at } = opening(PAGE, {
        permissions: ["read_membership", "start_run"],
        routes: { [`POST ${RUNS}`]: [BEGUN] },
      });
      await picking("Pay a refund");

      await userEvent.click(screen.getByRole("button", { name: "Start" }));

      expect(
        await screen.findByRole("heading", {
          level: 2,
          name: /Claim from Ada/,
        }),
      ).toBeInTheDocument();
      expect(at()).toBe(`${PAGE}/${ADA.runId}`);
      expect(screen.queryByText(STARTED_UNREADABLE)).toBeNull();
    });

    it("marks each value the server refused where it stands and nothing else, takes the keyboard to the first, and goes nowhere", async () => {
      const { at } = opening(PAGE, {
        routes: {
          [`POST ${RUNS}`]: [
            doesNotFit([{ path: ["complaint"], reason: "too_long" }]),
          ],
        },
      });
      await picking("Handle a claim");
      const complaint = screen.getByRole("textbox", { name: "Complaint" });
      await userEvent.type(complaint, "Kettle leaks");

      await userEvent.click(screen.getByRole("button", { name: "Start" }));

      await waitFor(() =>
        expect(complaint).toHaveAttribute("aria-invalid", "true"),
      );
      expect(complaint).toHaveAccessibleDescription(
        expect.stringContaining("This is longer than this field takes."),
      );
      expect(complaint).toHaveFocus();
      expect(
        screen.getByRole("textbox", { name: "Name of this run" }),
      ).toHaveAttribute("aria-invalid", "false");
      expect(screen.getByText(VALUES_REFUSED)).toBeInTheDocument();
      expect(screen.queryByText(/more place/)).toBeNull();
      expect(at()).toBe(PAGE);
    });

    it("marks a place the server refused within one of many fields and takes the keyboard there, marking nothing beside it", async () => {
      opening(PAGE, {
        routes: {
          [`GET ${OFFERED}`]: [ORDER_ON_OFFER],
          [`POST ${RUNS}`]: [
            doesNotFit([{ path: ["items", 0, "name"], reason: "unusable" }]),
          ],
        },
      });
      await picking("Take an order");
      const items = screen.getByRole("group", { name: "Items" });
      await userEvent.click(
        within(items).getByRole("button", {
          name: `Add one to ${isolatedName("Items")}`,
        }),
      );
      const name = within(items).getByRole("textbox", { name: "Name" });
      await userEvent.type(name, "Kettle");

      await userEvent.click(screen.getByRole("button", { name: "Start" }));

      await waitFor(() => expect(name).toHaveFocus());
      expect(name).toHaveAttribute("aria-invalid", "true");
      expect(
        within(items).getByRole("textbox", { name: "Count" }),
      ).toHaveAttribute("aria-invalid", "false");
      expect(
        screen.getByRole("group", { name: `${isolatedName("Items")} 1` }),
      ).toHaveAccessibleDescription("");
      expect(items).toHaveAccessibleDescription("1 / at most 3");
    });

    it("says how many more places the server found than it listed, marked nowhere, and says it no longer once anything is changed", async () => {
      opening(PAGE, {
        routes: {
          [`POST ${RUNS}`]: [
            doesNotFit([{ path: ["complaint"], reason: "too_long" }], 3),
          ],
        },
      });
      await picking("Handle a claim");
      const complaint = screen.getByRole("textbox", { name: "Complaint" });
      await userEvent.type(complaint, "Kettle leaks");

      await userEvent.click(screen.getByRole("button", { name: "Start" }));

      expect(
        await screen.findByText("2 more places do not fit and are not marked."),
      ).toBeInTheDocument();
      expect(screen.getByText(VALUES_REFUSED)).toBeInTheDocument();

      await userEvent.type(complaint, "!");

      expect(screen.queryByText(/more places/)).toBeNull();
      expect(screen.queryByText(VALUES_REFUSED)).toBeNull();
      expect(complaint).toHaveAttribute("aria-invalid", "false");
    });

    it("marks nothing the server refused where what was sent has since been changed, saying only that values were refused", async () => {
      let refuse: (reply: readonly [string, number]) => void = () => {};
      opening(PAGE, {
        routes: {
          [`POST ${RUNS}`]: [
            new Promise((settle) => {
              refuse = settle;
            }),
          ],
        },
      });
      await picking("Handle a claim");
      const complaint = screen.getByRole("textbox", { name: "Complaint" });
      await userEvent.type(complaint, "Kettle leaks");
      await userEvent.click(screen.getByRole("button", { name: "Start" }));
      await userEvent.type(complaint, " badly");
      const name = screen.getByRole("textbox", { name: "Name of this run" });
      await userEvent.click(name);

      refuse(
        doesNotFit([{ path: ["complaint"], reason: "too_long" }]) as readonly [
          string,
          number,
        ],
      );

      expect(await screen.findByText(VALUES_REFUSED)).toBeInTheDocument();
      expect(complaint).toHaveAttribute("aria-invalid", "false");
      expect(name).toHaveFocus();
    });

    it.each([
      [
        "busy",
        '{"code":"SERVICE_BUSY"}',
        503,
        "This is busy with another large request; send it again shortly.",
      ],
      [
        "sent too slowly",
        '{"code":"BODY_SENT_TOO_SLOWLY"}',
        400,
        "What was sent arrived too slowly to be read; send it again.",
      ],
    ])(
      "says a start refused as %s where it is refused, keeping the form as it was",
      async (_case, body, status, said) => {
        const { at } = opening(PAGE, {
          routes: { [`POST ${RUNS}`]: [[body, status]] },
        });
        await picking("Pay a refund");

        await userEvent.click(screen.getByRole("button", { name: "Start" }));

        expect(await screen.findByRole("alert")).toHaveTextContent(said);
        expect(at()).toBe(PAGE);
        expect(
          screen.getByRole("textbox", { name: "Name of this run" }),
        ).toHaveValue(`Pay a refund ${OPENED_AT}`);
      },
    );

    it("marks a day the browser holds and cannot read as not written as it is taken once the keyboard leaves it, and asks nothing", async () => {
      const { sent } = opening(PAGE, {
        routes: {
          [`GET ${OFFERED}`]: [ORDER_ON_OFFER],
          [`POST ${RUNS}`]: [BEGUN],
        },
      });
      await picking("Take an order");
      const due = screen.getByLabelText<HTMLInputElement>("Due");
      vi.spyOn(due.validity, "badInput", "get").mockReturnValue(true);

      fireEvent.blur(due);
      await userEvent.click(screen.getByRole("button", { name: "Start" }));

      expect(due).toHaveAttribute("aria-invalid", "true");
      expect(due).toHaveAccessibleDescription(
        "This is not written as this field takes it.",
      );
      expect(due).toHaveFocus();
      expect(startsAsked(sent)).toEqual([]);
    });

    it("says a name the server refused on the name, and nowhere else, taking the keyboard there", async () => {
      opening(PAGE, {
        routes: {
          [`POST ${RUNS}`]: [['{"code":"RUN_NAME_UNUSABLE"}', 400]],
        },
      });
      await picking("Pay a refund");

      await userEvent.click(screen.getByRole("button", { name: "Start" }));

      const name = screen.getByRole("textbox", { name: "Name of this run" });
      await waitFor(() =>
        expect(name).toHaveAccessibleDescription(NAME_REFUSED),
      );
      expect(screen.getAllByText(NAME_REFUSED)).toHaveLength(1);
      expect(name).toHaveFocus();
    });

    it("says a name refused no longer once the name suggested has changed", async () => {
      opening(PAGE, {
        routes: {
          [`POST ${RUNS}`]: [['{"code":"RUN_NAME_UNUSABLE"}', 400]],
        },
      });
      await picking("Handle a claim");
      const complaint = screen.getByRole("textbox", { name: "Complaint" });
      const name = screen.getByRole("textbox", { name: "Name of this run" });
      await userEvent.type(complaint, "Kettle");
      await userEvent.click(screen.getByRole("button", { name: "Start" }));
      await waitFor(() =>
        expect(name).toHaveAccessibleDescription(NAME_REFUSED),
      );

      await userEvent.type(complaint, " leaks");

      expect(name).toHaveValue("Kettle leaks");
      expect(name).toHaveAccessibleDescription("");
      expect(name).toHaveAttribute("aria-invalid", "false");
    });

    it("reads what may be started again where the version is no longer offered, says so, and takes the keyboard back to what may start once the workflow is gone", async () => {
      const { sent } = opening(PAGE, {
        routes: {
          [`POST ${RUNS}`]: [['{"code":"WORKFLOW_NOT_OFFERED"}', 409]],
          [`GET ${OFFERED}`]: [
            ON_OFFER,
            [JSON.stringify({ workflows: [REFUND] }), 200],
          ],
        },
      });
      await picking("Handle a claim");
      await userEvent.type(
        screen.getByRole("textbox", { name: "Complaint" }),
        "Kettle leaks",
      );

      await userEvent.click(screen.getByRole("button", { name: "Start" }));

      expect(
        await screen.findByText(
          "That version can no longer be started here, so what may be started has been read again.",
        ),
      ).toBeInTheDocument();
      await waitFor(() =>
        expect(
          screen.queryByRole("button", { name: /Handle a claim/ }),
        ).toBeNull(),
      );
      expect(
        requestsTo(sent).filter((each) => each === `GET ${OFFERED}`),
      ).toHaveLength(2);
      expect(screen.queryByRole("textbox", { name: "Complaint" })).toBeNull();
      expect(screen.getByRole("heading", { name: "A new run" })).toHaveFocus();
    });

    it("reads the standing again where starting is refused for the act itself, saying so and keeping the form where it is", async () => {
      const { readAgain, at } = opening(PAGE, {
        routes: {
          [`POST ${RUNS}`]: [['{"code":"ACT_NOT_PERMITTED"}', 403]],
        },
      });
      await picking("Pay a refund");
      const name = screen.getByRole("textbox", { name: "Name of this run" });

      await userEvent.click(screen.getByRole("button", { name: "Start" }));

      await waitFor(() => expect(readAgain).toHaveBeenCalledTimes(1));
      expect(await screen.findByRole("alert")).toHaveTextContent(ACT_REFUSED);
      expect(at()).toBe(PAGE);
      expect(name).toBeInTheDocument();
      expect(name).toHaveValue(`Pay a refund ${OPENED_AT}`);
    });

    it("puts the form away when it is abandoned, starting nothing and taking the keyboard back to what may start", async () => {
      const { sent } = opening(PAGE, {
        routes: { [`POST ${RUNS}`]: [BEGUN] },
      });
      await picking("Pay a refund");

      await userEvent.click(screen.getByRole("button", { name: "Cancel" }));

      expect(
        screen.queryByRole("textbox", { name: "Name of this run" }),
      ).toBeNull();
      expect(screen.getByRole("heading", { name: "A new run" })).toHaveFocus();
      expect(startsAsked(sent)).toEqual([]);
    });

    it("takes the keyboard to what may start when New run is pressed with no run open, going nowhere", async () => {
      const { at } = opening(`${PAGE}?filter=claim`);
      const heading = await screen.findByRole("heading", { name: "A new run" });

      await userEvent.click(screen.getByRole("link", { name: "New run" }));

      expect(heading).toHaveFocus();
      expect(at()).toBe(`${PAGE}?filter=claim`);
    });

    it.each([
      ["a ctrl-click", { ctrlKey: true }, true],
      ["a click of another button", { button: 1 }, true],
      ["a plain click", {}, false],
    ])(
      "leaves %s on New run to the browser only where it asks for more than this page",
      async (_case, modified, letThrough) => {
        const { at } = opening(`${PAGE}?filter=claim`);
        const heading = await screen.findByRole("heading", {
          name: "A new run",
        });

        const dispatched = fireEvent.click(
          screen.getByRole("link", { name: "New run" }),
          modified,
        );

        expect(dispatched).toBe(letThrough);
        expect(document.activeElement === heading).toBe(!letThrough);
        expect(at()).toBe(`${PAGE}?filter=claim`);
      },
    );

    it("offers New run to one who may start one, opening the workflows on offer in place of the run open and taking the keyboard there", async () => {
      const { at } = opening(`${PAGE}/${ADA.runId}?filter=claim`);
      await screen.findByRole("heading", { level: 2, name: /Claim from Ada/ });

      await userEvent.click(screen.getByRole("link", { name: "New run" }));

      await waitFor(() =>
        expect(
          screen.getByRole("heading", { name: "A new run" }),
        ).toHaveFocus(),
      );
      expect(at()).toBe(`${PAGE}?filter=claim`);
      expect(
        screen.queryByRole("heading", { level: 2, name: /Claim from Ada/ }),
      ).toBeNull();
    });

    it("draws nothing that starts a run for one who may not start one, and never asks what may be started", async () => {
      const { sent } = opening(PAGE, {
        permissions: ["read_membership", "read_own_runs"],
      });
      await days();

      expect(screen.queryByRole("link", { name: "New run" })).toBeNull();
      expect(screen.queryByRole("group", { name: "A new run" })).toBeNull();
      expect(
        screen.getByText("Open a run from the list to read it here."),
      ).toBeInTheDocument();
      expect(requestsTo(sent)).not.toContain(`GET ${OFFERED}`);
    });

    it("asks nobody who may neither start a run nor read one to open one from the list", async () => {
      opening(PAGE, {
        permissions: ["read_membership"],
        routes: { [`GET ${RUNS}`]: [['{"items":[],"reading":"none"}', 200]] },
      });

      await within(theRuns()).findByText(
        "Reading a run is a permission this group has not given you, so no run is listed, including the ones you started.",
      );

      expect(
        screen.queryByText("Open a run from the list to read it here."),
      ).toBeNull();
      expect(screen.queryByRole("group", { name: "A new run" })).toBeNull();
    });

    it("says so where nothing can be started", async () => {
      opening(PAGE, {
        routes: { [`GET ${OFFERED}`]: [['{"workflows":[]}', 200]] },
      });

      expect(
        await screen.findByText(
          "No workflow of this group can be started: none has a version in service that is not stopped.",
        ),
      ).toBeInTheDocument();
      expect(
        within(screen.getByRole("group", { name: "A new run" })).queryAllByText(
          isolatedName("Handle a claim"),
        ),
      ).toHaveLength(0);
    });

    /** Every row opened as an operator, so what is said follows the server's reading and not the standing. */
    it.each([
      [
        "read none",
        "none",
        PAGE,
        "Reading a run is a permission this group has not given you, so no run is listed, including the ones you started.",
      ],
      [
        "read their own, and they started none",
        "own",
        PAGE,
        "You have started no run here yet.",
      ],
      [
        "read their own, and the filter matches none",
        "own",
        `${PAGE}?filter=zz`,
        "No run you started matches that.",
      ],
      [
        "read all, and nothing has been run",
        "all",
        PAGE,
        "Nothing has been run here yet.",
      ],
      [
        "read all, and the filter matches none",
        "all",
        `${PAGE}?filter=zz`,
        "No run here, or its workflow, is called anything holding that.",
      ],
    ])(
      "says why no run is listed where the server %s",
      async (_case, reading, address, said) => {
        opening(address, {
          routes: {
            [`GET ${RUNS}`]: [[`{"items":[],"reading":"${reading}"}`, 200]],
          },
        });

        expect(await within(theRuns()).findByText(said)).toBeInTheDocument();
        expect(within(theRuns()).queryAllByRole("list")).toHaveLength(0);
      },
    );

    it("reads the standing again where the server says the reader is in the group no longer", async () => {
      const { readAgain } = opening(PAGE, {
        routes: {
          [`GET ${RUNS}`]: [['{"code":"GROUP_NOT_IN_VIEW"}', 404]],
        },
      });

      await waitFor(() => expect(readAgain).toHaveBeenCalledTimes(1));
      expect(within(theRuns()).queryAllByRole("list")).toHaveLength(0);
    });
  });

  describe("in detail", () => {
    it("draws what is running as a table sorted newest first by when each started, once switched to", async () => {
      opening(PAGE);
      await days();

      await inDetail();

      expect(await rows()).toEqual([
        [
          "SUPPORT-3",
          "Claim from Alan",
          `${isolatedName("Handle a claim")}, version 1`,
          "02:00 PM today",
          "Cat Example",
          "Stopped",
        ],
        [
          "SUPPORT-2",
          "Refund for Grace",
          `${isolatedName("Pay a refund")}, version 2`,
          "11:00 AM yesterday",
          "000c62",
          `Running, on ${isolatedName("summarise")}`,
        ],
        [
          "SUPPORT-1",
          "Claim from Ada",
          `${isolatedName("Handle a claim")}, version 1`,
          "Sep 11 10:00 AM",
          "Cat Example",
          "Failed",
        ],
      ]);
      expect(
        within(table()).getByRole("columnheader", { name: /Started$/ }),
      ).toHaveAttribute("aria-sort", "descending");
      expect(screen.queryByRole("region", { name: "The runs" })).toBeNull();
    });

    it.each([
      ["running where no step arrived", { ...GRACE, at: undefined }, "Running"],
      [
        "in a state this build has not heard of, whatever step arrived",
        { ...GRACE, state: "paused" },
        "Not something this page can say yet.",
      ],
    ])(
      "says where a run is %s without naming a step, and every other run as it was",
      async (_case, run, said) => {
        localStorage.setItem(KEPT_AS, "detail");
        opening(PAGE, {
          routes: {
            [`GET ${RUNS}`]: [
              [JSON.stringify({ items: [run, ADA], reading: "own" }), 200],
            ],
          },
        });

        expect((await rows()).map((cells) => cells[5])).toEqual([
          said,
          "Failed",
        ]);
      },
    );

    /** The same runs the server lets the reader read, asked in another order and never under the conversation's filter. */
    it("asks the server for the very runs the conversation asks for, in the table's own order", async () => {
      const { sent } = opening(`${PAGE}?filter=claim`);
      await days();

      await inDetail();
      await rows();

      expect(runsAsked(sent)).toEqual([
        `GET ${RUNS}?sort=-lastHappened&filter=claim`,
        `GET ${RUNS}?sort=-started`,
      ]);
    });

    it("asks the server for another order when a heading is pressed, and keeps it in the address", async () => {
      localStorage.setItem(KEPT_AS, "detail");
      const { sent, at } = opening(PAGE);
      await rows();

      await userEvent.click(
        within(table()).getByRole("button", { name: "Started by" }),
      );

      await waitFor(() =>
        expect(requestsTo(sent)).toContain(`GET ${RUNS}?sort=startedBy`),
      );
      expect(at()).toBe(`${PAGE}?sort=startedBy`);
    });

    it("offers no order by where a run is, and asks for the runs newest first where the address asks for that order", async () => {
      localStorage.setItem(KEPT_AS, "detail");
      const { sent } = opening(`${PAGE}?sort=where`);
      await rows();

      const where = within(table()).getByRole("columnheader", {
        name: "Where it is",
      });
      expect(within(where).queryByRole("button")).toBeNull();
      expect(where).not.toHaveAttribute("aria-sort");
      expect(
        within(table()).getByRole("columnheader", { name: /Started$/ }),
      ).toHaveAttribute("aria-sort", "descending");
      expect(runsAsked(sent)).toEqual([`GET ${RUNS}?sort=-started`]);
    });

    it("opens as this device left it, and a row opens its run under the table, which keeps its order and marks the row", async () => {
      localStorage.setItem(KEPT_AS, "detail");
      const { sent, at } = opening(`${PAGE}?sort=startedBy`);
      await rows();

      await userEvent.click(
        within(table()).getByRole("link", { name: "SUPPORT-1" }),
      );

      expect(
        await screen.findByRole("heading", {
          level: 2,
          name: /Claim from Ada/,
        }),
      ).toBeInTheDocument();
      expect(at()).toBe(`${PAGE}/${ADA.runId}?sort=startedBy`);
      expect(
        within(table()).getByRole("link", { name: "SUPPORT-1" }),
      ).toHaveAttribute("aria-current", "true");
      expect(
        within(table()).getByRole("link", { name: "SUPPORT-3" }),
      ).not.toHaveAttribute("aria-current");
      expect(
        within(table()).getByRole("columnheader", { name: /Started by$/ }),
      ).toHaveAttribute("aria-sort", "ascending");
      expect(runsAsked(sent)).toEqual([`GET ${RUNS}?sort=startedBy`]);
      expect(screen.queryByRole("region", { name: "The runs" })).toBeNull();
    });

    it("opens a step from the run's steps in the run's place under the table, which stays as it was, and goes back to the run by its link", async () => {
      localStorage.setItem(KEPT_AS, "detail");
      const { sent, at } = opening(`${PAGE}/${ADA.runId}?sort=startedBy`);
      const steps = await screen.findByRole("table", { name: "The steps" });

      await userEvent.click(
        within(steps).getByRole("link", { name: "summarise" }),
      );

      expect(
        await screen.findByRole("heading", { level: 2, name: /summarise/ }),
      ).toBeInTheDocument();
      expect(at()).toBe(
        `${PAGE}/${ADA.runId}/steps/${SUMMARISE}?sort=startedBy`,
      );
      expect(screen.queryByRole("table", { name: "The steps" })).toBeNull();
      expect(
        within(table()).getByRole("link", { name: "SUPPORT-1" }),
      ).toHaveAttribute("aria-current", "true");

      await userEvent.click(
        screen.getByRole("link", { name: "Back to the run" }),
      );

      expect(
        await screen.findByRole("heading", {
          level: 2,
          name: /Claim from Ada/,
        }),
      ).toBeInTheDocument();
      expect(at()).toBe(`${PAGE}/${ADA.runId}?sort=startedBy`);
      expect(runsAsked(sent)).toEqual([`GET ${RUNS}?sort=startedBy`]);
    });

    it("says it is still reading the runs only while none has answered, and not while they are read again after an act on the run open under them", async () => {
      localStorage.setItem(KEPT_AS, "detail");
      let settle!: (answer: readonly [string, number]) => void;
      const first: Reply = new Promise((done) => {
        settle = done;
      });
      const { sent } = opening(`${PAGE}/${ADA.runId}`, {
        routes: {
          [`GET ${RUNS}`]: [first, UNANSWERED],
          [`GET ${RUNS}/${ADA.runId}`]: [STOPPABLE, STOPPED_BY_CAT],
          [`PUT ${RUNS}/${ADA.runId}/stop`]: [STOPPED_BY_CAT],
        },
      });
      await screen.findByRole("table", { name: "What is running" });
      const status = tableStatus();
      expect(status).toHaveTextContent("Still reading…");

      settle(LISTED as readonly [string, number]);
      await within(table()).findByRole("link", { name: "SUPPORT-1" });
      expect(status).not.toHaveTextContent("Still reading…");
      await userEvent.click(
        await screen.findByRole("button", { name: "Stop" }),
      );
      await waitFor(() => expect(runsAsked(sent)).toHaveLength(2));

      expect(tableStatus()).toBe(status);
      expect(status).not.toHaveTextContent("Still reading…");
      expect(
        within(table()).getByRole("link", { name: "SUPPORT-1" }),
      ).toBeVisible();
    });

    it("draws the runs beside a run open in detail once switched back to a conversation, and keeps that choice", async () => {
      localStorage.setItem(KEPT_AS, "detail");
      opening(`${PAGE}/${ADA.runId}`);
      await screen.findByRole("heading", { level: 2, name: /Claim from Ada/ });

      await userEvent.click(
        screen.getByRole("button", { name: "As a conversation" }),
      );

      expect(
        await within(theRuns()).findByRole("link", { name: /Claim from Ada/ }),
      ).toHaveAttribute("aria-current", "true");
      expect(localStorage.getItem(KEPT_AS)).toBe("conversation");
    });

    it("leaves the keyboard on the drawing pressed where the run was come back to from its step, starting it at the run's name only as it arrived", async () => {
      localStorage.setItem(KEPT_AS, "detail");
      opening(`${PAGE}/${ADA.runId}/steps/${SUMMARISE}`);
      await screen.findByRole("heading", { level: 2, name: /summarise/ });
      await userEvent.click(
        screen.getByRole("link", { name: "Back to the run" }),
      );
      const arrivedAt = await screen.findByRole("heading", {
        level: 2,
        name: /Claim from Ada/,
      });
      expect(document.activeElement).toBe(arrivedAt);
      const conversation = screen.getByRole("button", {
        name: "As a conversation",
      });

      await userEvent.click(conversation);

      const drawnAgain = await screen.findByRole("heading", {
        level: 2,
        name: /Claim from Ada/,
      });
      expect(drawnAgain).not.toBe(arrivedAt);
      expect(document.activeElement).toBe(conversation);
      expect(document.activeElement).not.toBe(drawnAgain);
    });

    it("keeps the choice on this device for the next time the page opens", async () => {
      opening(PAGE);
      await days();

      await inDetail();

      expect(localStorage.getItem(KEPT_AS)).toBe("detail");
    });

    it("offers Start a run level with the title to one who may start one, and only drawn in detail", async () => {
      opening(PAGE);
      await days();
      expect(screen.queryByRole("button", { name: "Start a run" })).toBeNull();

      await inDetail();

      expect(
        screen.getByRole("button", { name: "Start a run" }),
      ).toBeInTheDocument();
    });

    it("draws no Start a run for one who may not start a run", async () => {
      localStorage.setItem(KEPT_AS, "detail");
      opening(PAGE, { permissions: ["read_membership", "read_own_runs"] });
      await rows();

      expect(screen.queryByRole("button", { name: "Start a run" })).toBeNull();
    });

    it("starts a run from the dialog, a workflow chosen with its newest version already picked, and opens it under the table", async () => {
      localStorage.setItem(KEPT_AS, "detail");
      const { sent, at } = opening(`${PAGE}?sort=startedBy`, {
        routes: { [`POST ${RUNS}`]: [BEGUN] },
      });
      await rows();

      await userEvent.click(
        screen.getByRole("button", { name: "Start a run" }),
      );
      const dialog = await screen.findByRole("dialog", { name: "Start a run" });
      await userEvent.click(
        await within(dialog).findByRole("combobox", { name: "Workflow" }),
      );
      await userEvent.click(
        screen.getByRole("option", { name: isolatedName("Handle a claim") }),
      );
      expect(
        within(dialog).getByRole("combobox", { name: "Version" }),
      ).toHaveTextContent("Version 2");
      await userEvent.type(
        within(dialog).getByRole("textbox", { name: "Complaint" }),
        "Kettle leaks",
      );
      await userEvent.click(
        within(dialog).getByRole("button", { name: "Start" }),
      );

      expect(
        await screen.findByRole("heading", {
          level: 2,
          name: /Claim from Ada/,
        }),
      ).toBeInTheDocument();
      expect(at()).toBe(`${PAGE}/${ADA.runId}?sort=startedBy`);
      await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
      expect(startsAsked(sent)).toEqual([
        {
          name: "Kettle leaks",
          versionId: HANDLE.versions[0]!.versionId,
          values: { complaint: "Kettle leaks" },
        },
      ]);
      expect(runsAsked(sent)).toEqual([
        `GET ${RUNS}?sort=startedBy`,
        `GET ${RUNS}?sort=startedBy`,
      ]);
    });

    it("holds the dialog's start until a workflow is chosen", async () => {
      localStorage.setItem(KEPT_AS, "detail");
      opening(PAGE);
      await rows();

      await userEvent.click(
        screen.getByRole("button", { name: "Start a run" }),
      );
      const dialog = await screen.findByRole("dialog", { name: "Start a run" });

      expect(
        within(dialog).getByRole("button", { name: "Start" }),
      ).toHaveAttribute("aria-disabled", "true");
      expect(
        within(dialog).getByText("Choose a workflow first."),
      ).toBeInTheDocument();
    });

    it("names the run afresh once another workflow is chosen in the dialog, forgetting a name typed or refused for the one before", async () => {
      localStorage.setItem(KEPT_AS, "detail");
      opening(PAGE, {
        routes: {
          [`POST ${RUNS}`]: [['{"code":"RUN_NAME_UNUSABLE"}', 400]],
        },
      });
      const dialog = await openingTheDialog();
      await choosingInTheDialog(dialog, "Pay a refund");
      const name = within(dialog).getByRole("textbox", {
        name: "Name of this run",
      });
      await userEvent.clear(name);
      await userEvent.type(name, "Mine");
      await userEvent.click(
        within(dialog).getByRole("button", { name: "Start" }),
      );
      await waitFor(() =>
        expect(name).toHaveAccessibleDescription(NAME_REFUSED),
      );

      await choosingInTheDialog(dialog, "Handle a claim");
      expect(name).toHaveValue(`Handle a claim ${OPENED_AT}`);
      expect(name).toHaveAccessibleDescription("");

      await choosingInTheDialog(dialog, "Pay a refund");
      expect(name).toHaveValue(`Pay a refund ${OPENED_AT}`);
      expect(name).toHaveAccessibleDescription("");
    });

    it("says no longer that a version is not offered once another workflow is chosen in the dialog", async () => {
      localStorage.setItem(KEPT_AS, "detail");
      opening(PAGE, {
        routes: {
          [`POST ${RUNS}`]: [['{"code":"WORKFLOW_NOT_OFFERED"}', 409]],
          [`GET ${OFFERED}`]: [ON_OFFER, ON_OFFER],
        },
      });
      const dialog = await openingTheDialog();
      await choosingInTheDialog(dialog, "Pay a refund");
      await userEvent.click(
        within(dialog).getByRole("button", { name: "Start" }),
      );
      expect(await within(dialog).findByText(NO_LONGER_OFFERED)).toBeVisible();

      await choosingInTheDialog(dialog, "Handle a claim");

      expect(within(dialog).queryByText(NO_LONGER_OFFERED)).toBeNull();
    });

    it.each([
      ["a name refused", '{"code":"RUN_NAME_UNUSABLE"}', 400],
      ["a version no longer offered", '{"code":"WORKFLOW_NOT_OFFERED"}', 409],
    ])(
      "says nothing of %s on another workflow chosen in the dialog while the start was out",
      async (_case, body, status) => {
        let refuse: (reply: readonly [string, number]) => void = () => {};
        localStorage.setItem(KEPT_AS, "detail");
        const { sent } = opening(PAGE, {
          routes: {
            [`POST ${RUNS}`]: [
              new Promise((settle) => {
                refuse = settle;
              }),
            ],
          },
        });
        const dialog = await openingTheDialog();
        await choosingInTheDialog(dialog, "Pay a refund");
        const name = within(dialog).getByRole("textbox", {
          name: "Name of this run",
        });
        await userEvent.clear(name);
        await userEvent.type(name, "Mine");
        const start = within(dialog).getByRole("button", { name: "Start" });
        await userEvent.click(start);
        await choosingInTheDialog(dialog, "Handle a claim");
        await userEvent.clear(name);
        await userEvent.type(name, "Mine");
        const complaint = within(dialog).getByRole("textbox", {
          name: "Complaint",
        });
        await userEvent.click(complaint);

        refuse([body, status]);

        await waitFor(() => expect(start).not.toHaveAttribute("aria-disabled"));
        expect(complaint).toHaveFocus();
        expect(name).toHaveAttribute("aria-invalid", "false");
        expect(name).toHaveAccessibleDescription("");
        expect(within(dialog).queryByText(NO_LONGER_OFFERED)).toBeNull();
        expect(within(dialog).queryByRole("alert")).toBeNull();
        expect(
          requestsTo(sent).filter((each) => each === `GET ${OFFERED}`),
        ).toEqual([`GET ${OFFERED}`]);
      },
    );

    it("says a refusal no longer once another workflow is chosen in the dialog, nor again once the first is chosen back", async () => {
      localStorage.setItem(KEPT_AS, "detail");
      opening(PAGE, {
        routes: { [`POST ${RUNS}`]: [['{"code":"SERVICE_BUSY"}', 503]] },
      });
      const dialog = await openingTheDialog();
      await choosingInTheDialog(dialog, "Pay a refund");
      await userEvent.click(
        within(dialog).getByRole("button", { name: "Start" }),
      );
      expect(await within(dialog).findByRole("alert")).toBeInTheDocument();

      await choosingInTheDialog(dialog, "Handle a claim");
      expect(within(dialog).queryByRole("alert")).toBeNull();

      await choosingInTheDialog(dialog, "Pay a refund");
      expect(within(dialog).queryByRole("alert")).toBeNull();
    });

    it("shuts the dialog and stays for one who may not read the run begun, saying it began and its number", async () => {
      localStorage.setItem(KEPT_AS, "detail");
      const { at } = opening(PAGE, {
        permissions: ["read_membership", "start_run"],
        routes: {
          [`GET ${RUNS}`]: [['{"items":[],"reading":"none"}', 200]],
          [`POST ${RUNS}`]: [BEGUN_UNREADABLE],
        },
      });
      await screen.findByText(
        "Reading a run is a permission this group has not given you, so no run is listed, including the ones you started.",
      );

      await userEvent.click(
        screen.getByRole("button", { name: "Start a run" }),
      );
      const dialog = await screen.findByRole("dialog", { name: "Start a run" });
      await userEvent.click(
        await within(dialog).findByRole("combobox", { name: "Workflow" }),
      );
      await userEvent.click(
        screen.getByRole("option", { name: isolatedName("Pay a refund") }),
      );
      await userEvent.click(
        within(dialog).getByRole("button", { name: "Start" }),
      );

      expect(
        await screen.findByText(
          "SUPPORT-7 has begun. Reading it is a permission this group has not given you, so it cannot be opened here.",
        ),
      ).toBeInTheDocument();
      await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
      expect(at()).toBe(PAGE);
    });
  });
});
