import { ThemeProvider } from "@mui/material/styles";
import { render, screen, within } from "@testing-library/react";
import { userEvent } from "@testing-library/user-event";
import { RouterProvider, createMemoryRouter } from "react-router";
import { afterEach, vi } from "vitest";

import { StandingProvider } from "../app/standing/StandingContext";
import { theme } from "../lib/theme/theme";
import { OpenedRun, WorkPage } from "../features/runs/WorkPage";
import { requestsTo, serving, type Reply } from "./answering";
import { answered, inGroup } from "./standingRead";

const STARTS_AND_READS_OWN = ["read_membership", "start_run", "read_own_runs"];

export function openWorkPage(
  address: string,
  {
    groupId,
    routes,
    permissions = STARTS_AND_READS_OWN,
  }: {
    readonly groupId: string;
    readonly routes: Readonly<Record<string, readonly Reply[]>>;
    readonly permissions?: readonly string[];
  },
) {
  vi.useFakeTimers({ toFake: ["Date"] });
  vi.setSystemTime(new Date("2026-09-18T13:00:00Z"));
  const sent = serving(routes);
  const router = createMemoryRouter(
    [
      { path: "/groups/:groupId/work", element: <WorkPage /> },
      { path: "/groups/:groupId/work/:runId", element: <OpenedRun /> },
      {
        path: "/groups/:groupId/work/:runId/steps/:stepId",
        element: <OpenedRun />,
      },
    ],
    { initialEntries: [address] },
  );
  const readAgain = vi.fn();
  const standing = answered(
    [],
    [inGroup(groupId, "SUPPORT", "Claims", permissions)],
  );
  const draw = (read: typeof standing) => (
    <ThemeProvider theme={theme}>
      <StandingProvider read={{ ...read, reload: readAgain }}>
        <RouterProvider router={router} />
      </StandingProvider>
    </ThemeProvider>
  );
  const mounted = render(draw(standing));
  return {
    sent,
    readAgain,
    router,
    changeReader: (userId: string) =>
      mounted.rerender(
        draw({ ...standing, value: { ...standing.value, userId } }),
      ),
    at: () =>
      `${router.state.location.pathname}${router.state.location.search}${router.state.location.hash}`,
  };
}

export const GROUP = "00000003-0000-4000-8000-000000000c61";

export const PAGE = `/groups/${GROUP}/work`;

export const NEW_RUN = `${PAGE}?tab=newRun`;

export const HISTORY = `${PAGE}?tab=history`;

export const RUNS = `/api/groups/${GROUP}/runs`;

export const OFFERED = `/api/groups/${GROUP}/offered-workflows`;

export const KEPT_AS = "lilradish.work.tab";

export const RUN_DRAWING_KEPT_AS = "lilradish.run.drawing";

export const AVON = {
  runId: "00000008-0000-4000-8000-000000000c63",
  number: 3,
  name: "Claim from Avonel",
  workflow: { name: "Handle a claim", version: 1 },
  startedBy: { userId: "000c63", displayName: "Cat Example" },
  startedAt: "2026-09-18T12:00:00Z",
  state: "stopped",
};

export const GLINN = {
  runId: "00000008-0000-4000-8000-000000000c62",
  number: 2,
  name: "Refund for Glinn",
  workflow: { name: "Pay a refund", version: 2 },
  startedBy: { userId: "000c62" },
  startedAt: "2026-09-17T09:00:00Z",
  state: "running",
  at: "summarise",
};

export const AVO = {
  runId: "00000008-0000-4000-8000-000000000c61",
  number: 1,
  name: "Claim from Avo",
  workflow: { name: "Handle a claim", version: 1 },
  startedBy: { userId: "000c63", displayName: "Cat Example" },
  startedAt: "2026-09-11T08:00:00Z",
  state: "failed",
};

export const LISTED: Reply = [
  JSON.stringify({ items: [AVON, GLINN, AVO], reading: "own" }),
  200,
];

export const HANDLE = {
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

export const REFUND = {
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

export const BEGUN: Reply = [
  JSON.stringify({ runId: AVO.runId, number: 7, readable: true }),
  201,
];

export const BEGUN_UNREADABLE: Reply = [
  JSON.stringify({ runId: AVO.runId, number: 7, readable: false }),
  201,
];

export const STARTED_UNREADABLE =
  "SUPPORT-7 has begun. Reading it is a permission this group has not given you, so it cannot be opened here.";

export const doesNotFit = (
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

export const VALUES_REFUSED = "Each value is written as its field takes it.";

export const OPENED_AT = "Sep 18, 03:00 PM";

export const ON_OFFER: Reply = [
  JSON.stringify({ items: [HANDLE, REFUND] }),
  200,
];

export const ORDER = {
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

export const ORDER_ON_OFFER: Reply = [JSON.stringify({ items: [ORDER] }), 200];

export const SUMMARISE = "00000009-0000-4000-8000-000000000c61";

export const AVOS_RUN_READ = {
  runId: AVO.runId,
  number: 1,
  name: "Claim from Avo",
  workflow: {
    entryId: "00000006-0000-4000-8000-000000000c61",
    name: "Handle a claim",
    version: 1,
  },
  startedBy: AVO.startedBy,
  runsAbove: [],
  startedAt: AVO.startedAt,
  conversationEvents: [
    {
      id: `step:${SUMMARISE}`,
      kind: "step_reached",
      at: "2026-09-18T12:00:01Z",
      stepId: SUMMARISE,
      run: { runId: AVO.runId, number: 1 },
    },
  ],
  state: "running",
  spend: { sent: "0", cameBack: "0", spent: "0", cameBackUnknown: false },
  ceiling: { raiseNeedsApproval: false },
  acts: [],
};

export const AVOS_RUN: Reply = [JSON.stringify(AVOS_RUN_READ), 200];

export const AVOS_RUN_HEADER = {
  runId: AVO.runId,
  number: 1,
  versionId: HANDLE.versions[1].versionId,
  state: "running",
  at: { stepId: SUMMARISE, name: "summarise" },
  acts: [],
  progress: { done: 0, of: 1 },
};

export const AVOS_STEP_ROW = {
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

export const AVOS_STEPS: Reply = [
  JSON.stringify({
    run: AVOS_RUN_HEADER,
    declarations: { [AVOS_RUN_HEADER.versionId]: { takes: [], gives: [] } },
    gaveBack: { declares: "nothing" },
    steps: [AVOS_STEP_ROW],
  }),
  200,
];

export const AVOS_STEP: Reply = [
  JSON.stringify({
    run: AVOS_RUN_HEADER,
    declarations: {},
    step: AVOS_STEP_ROW,
    triesMade: [],
  }),
  200,
];

export const NAME_REFUSED =
  "A run's name is one to 128 characters on one line, with something in it that shows.";

/** What is said where starting is refused for the act itself: the rule of who may start one. */
export const ACT_REFUSED =
  "A group's runs are started, renamed, stopped, opened again and given a ceiling, and a raise asked withdrawn, and what was held back sent again, only by a role in it that may start one.";

export const NO_LONGER_OFFERED =
  "That version can no longer be started here, so what may be started has been read again.";

export const isolatedName = (name: string) =>
  `${String.fromCodePoint(0x2068)}${name}${String.fromCodePoint(0x2069)}`;

export interface Opening {
  readonly permissions?: readonly string[];
  readonly routes?: Readonly<Record<string, readonly Reply[]>>;
}

/** Keep the real router and standing; replace only HTTP replies and the clock. */
export function opening(
  address: string,
  { permissions, routes = {} }: Opening = {},
) {
  return openWorkPage(address, {
    groupId: GROUP,
    permissions,
    routes: {
      [`GET ${RUNS}`]: [LISTED, LISTED],
      [`GET ${OFFERED}`]: [ON_OFFER],
      [`GET ${RUNS}/${AVO.runId}`]: [AVOS_RUN, AVOS_RUN],
      [`GET ${RUNS}/${AVO.runId}/steps`]: [AVOS_STEPS, AVOS_STEPS],
      [`GET ${RUNS}/${AVO.runId}/steps/${SUMMARISE}`]: [AVOS_STEP],
      ...routes,
    },
  });
}

afterEach(() => {
  vi.useRealTimers();
  localStorage.clear();
});

export function tab(name: string): HTMLElement {
  return screen.getByRole("tab", { name });
}

export function panel(name: string): HTMLElement {
  return screen.getByRole("tabpanel", { name });
}

export async function inHistory() {
  await userEvent.click(tab("History"));
}

export function table(): HTMLElement {
  return screen.getByRole("table", { name: "What is running" });
}

export function precedes(earlier: Element, later: Element): boolean {
  const relation = earlier.compareDocumentPosition(later);
  return (relation & Node.DOCUMENT_POSITION_FOLLOWING) !== 0;
}

export async function rows(): Promise<string[][]> {
  await within(table()).findByRole("link", { name: "SUPPORT-1" });
  const [, ...listed] =
    within(table()).getAllByRole<HTMLTableRowElement>("row");
  return listed.map((row) =>
    [...row.cells].map((cell) => cell.textContent ?? ""),
  );
}

export function runsAsked(sent: Parameters<typeof requestsTo>[0]): string[] {
  return requestsTo(sent).filter((each) => each.startsWith(`GET ${RUNS}?`));
}

export function startsAsked(sent: Parameters<typeof requestsTo>[0]): unknown[] {
  return sent.mock.calls
    .filter(([, init]) => init?.method === "POST")
    .map(([, init]) => JSON.parse(String(init?.body)) as unknown);
}

export async function picking(workflow: string): Promise<HTMLElement> {
  await userEvent.click(
    await screen.findByRole("button", { name: new RegExp(workflow) }),
  );
  return screen.getByRole("heading", {
    level: 2,
    name: isolatedName(workflow),
  });
}

export function runOpened(name: RegExp): Promise<HTMLElement> {
  return screen.findByRole("heading", { level: 1, name });
}
