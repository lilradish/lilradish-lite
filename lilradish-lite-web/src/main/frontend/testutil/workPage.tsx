import { ThemeProvider } from "@mui/material/styles";
import { act, fireEvent, render, screen, within } from "@testing-library/react";
import { userEvent } from "@testing-library/user-event";
import { RouterProvider, createMemoryRouter } from "react-router";
import { afterEach, aroundEach, vi } from "vitest";

import { WORK_ITEM } from "../app/destinations";
import { RequireGroup } from "../app/standing/RequireGroup";
import { StandingProvider } from "../app/standing/StandingContext";
import { theme } from "../lib/theme/theme";
import { OpenedRun, WorkPage } from "../features/runs/WorkPage";
import { requestsTo, serving, type Reply } from "./answering";
import { namingGroupsByKeyAlone } from "./groupAddresses";
import { pageLaidOutAt } from "./layout";
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
  pageLaidOutAt(1400, 1600);
  const sent = serving(routes);
  const router = createMemoryRouter(
    [
      {
        path: "/groups/:group/work",
        element: (
          <RequireGroup reachedBy={WORK_ITEM.reachedBy}>
            <WorkPage />
          </RequireGroup>
        ),
      },
      {
        path: "/groups/:group/work/:runId",
        element: (
          <RequireGroup reachedBy={WORK_ITEM.reachedBy}>
            <OpenedRun />
          </RequireGroup>
        ),
      },
      {
        path: "/groups/:group/work/:runId/steps/:stepId",
        element: (
          <RequireGroup reachedBy={WORK_ITEM.reachedBy}>
            <OpenedRun />
          </RequireGroup>
        ),
      },
    ],
    { initialEntries: [address] },
  );
  namingGroupsByKeyAlone(router, [groupId]);
  const readAgain = vi.fn();
  const standing = answered([], [inGroup(groupId, KEY, "Claims", permissions)]);
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
    changePermissions: (permissions: readonly string[]) =>
      mounted.rerender(
        draw(answered([], [inGroup(groupId, KEY, "Claims", permissions)])),
      ),
    changeReader: (userId: string) =>
      mounted.rerender(
        draw({ ...standing, value: { ...standing.value, userId } }),
      ),
    at: () =>
      `${router.state.location.pathname}${router.state.location.search}${router.state.location.hash}`,
  };
}

export const GROUP = "00000003-0000-4000-8000-000000000c61";

const KEY = "SUPPORT";

export const PAGE = `/groups/${KEY}/work`;

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
  workflow: { versionName: null, name: "Handle a claim", version: 1 },
  startedBy: { userId: "000c63", displayName: "Cat Example" },
  startedAt: "2026-09-18T12:00:00Z",
  state: "stopped",
};

export const GLINN = {
  runId: "00000008-0000-4000-8000-000000000c62",
  number: 2,
  name: "Refund for Glinn",
  workflow: { versionName: null, name: "Pay a refund", version: 2 },
  startedBy: { userId: "000c62" },
  startedAt: "2026-09-17T09:00:00Z",
  state: "running",
  at: "summarise",
};

export const AVO = {
  runId: "00000008-0000-4000-8000-000000000c61",
  number: 1,
  name: "Claim from Avo",
  workflow: { versionName: null, name: "Handle a claim", version: 1 },
  startedBy: { userId: "000c63", displayName: "Cat Example" },
  startedAt: "2026-09-11T08:00:00Z",
  state: "failed",
};

export const LISTED: Reply = [
  JSON.stringify({ items: [AVON, GLINN, AVO] }),
  200,
];

export const HANDLE = {
  entryId: "00000006-0000-4000-8000-000000000c61",
  name: "Handle a claim",
  purpose: "Sorts a claim out.",
  versions: [
    {
      versionName: null,
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
      versionName: null,
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
      versionName: null,
      versionId: "00000007-0000-4000-8000-000000000c63",
      number: 4,
      takes: [],
    },
  ],
};

export const BEGUN: Reply = [
  JSON.stringify({ runId: AVO.runId, number: 7 }),
  201,
];

export const BEGUN_UNREADABLE: Reply = [
  JSON.stringify({ runId: AVO.runId, number: 7 }),
  201,
];

export const STARTED_UNREADABLE =
  "SUPPORT-7 has started. Your roles in this group don't let you see runs, so you can't open it here.";

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

export const VALUES_REFUSED =
  "Some values aren't in the format their field expects.";

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
      versionName: null,
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
    versionName: null,
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
  stateActs: [],
};

export const AVOS_RUN: Reply = [JSON.stringify(AVOS_RUN_READ), 200];

export const AVOS_RUN_HEADER = {
  versionName: null,
  runId: AVO.runId,
  number: 1,
  versionId: HANDLE.versions[1].versionId,
  state: "running",
  at: { stepId: SUMMARISE, name: "summarise" },
  stateActs: [],
  progress: { done: 0, of: 1 },
};

export const AVOS_STEP_ROW = {
  producedByUserId: null,
  producedByKind: null,
  stepId: SUMMARISE,
  order: 1,
  name: "summarise",
  runs: { versionName: null, kind: "code_step", codeStep: "summarise" },
  producer: { kind: "code" },
  state: "running",
  where: { kind: "running", on: "next_try" },
  takesFrom: [],
  tries: { current: 0, declared: 1, beyond: false },
  cost: { callsAModel: false },
  stateActs: [],
  stateRefusals: [],
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
  "Use 1 to 128 characters on one line, with at least one visible character.";

export const ACT_REFUSED =
  "Only someone in this group who can start runs can start, rename, stop or resume a run, set its usage limit, withdraw a limit change they asked for, or try sending a request that could not be sent.";

export const NO_LONGER_OFFERED =
  "That version can no longer be started here, so what may be started has been read again.";

export const isolatedName = (name: string) =>
  `${String.fromCodePoint(0x2068)}${name}${String.fromCodePoint(0x2069)}`;

export interface Opening {
  readonly permissions?: readonly string[];
  readonly routes?: Readonly<Record<string, readonly Reply[]>>;
}

/** The real router and standing keep these requests on the page's production path. */
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

const { AsyncLocalStorage } = await vi.importActual<{
  AsyncLocalStorage: new () => {
    run<Result>(signal: AbortSignal, body: () => Result): Result;
    getStore(): AbortSignal | undefined;
  };
}>("node:async_hooks");
const callingTest = new AsyncLocalStorage();

// A timed-out test's body runs on into the next test, where `screen` is that test's
// page. Async context, not the current test, tells whose body is calling.
aroundEach((runTest, { signal }) => callingTest.run(signal, runTest));

function stillRunning(): void {
  callingTest.getStore()?.throwIfAborted();
}

export function tab(name: string): HTMLElement {
  stillRunning();
  return screen.getByRole("tab", { name });
}

export function panel(name: string): HTMLElement {
  stillRunning();
  return screen.getByRole("tabpanel", { name });
}

export async function inHistory() {
  await userEvent.click(tab("History"));
}

export function table(): HTMLElement {
  stillRunning();
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

export async function picking(
  workflow: string,
  inputs = true,
): Promise<HTMLElement> {
  stillRunning();
  const opened = screen.queryByRole("dialog");
  if (opened !== null)
    await userEvent.click(
      within(opened).getByRole("button", { name: "Cancel" }),
    );
  stillRunning();
  const card = await screen.findByRole("button", {
    name: new RegExp(workflow),
  });
  stillRunning();
  await userEvent.click(card);
  stillRunning();
  const dialog = screen.getByRole("dialog", {
    name: `Start a run of ${isolatedName(workflow)}`,
  });
  if (inputs) await inputting();
  return dialog;
}

export async function inputting(): Promise<void> {
  stillRunning();
  const next = screen.queryByRole("button", { name: "Next" });
  if (next !== null) await userEvent.click(next);
}

export async function naming(): Promise<HTMLElement> {
  stillRunning();
  const back = screen.queryByRole("button", { name: "Back" });
  if (back !== null) await userEvent.click(back);
  stillRunning();
  return screen.getByRole("textbox", { name: "Name of this run" });
}

export async function renaming(
  name: HTMLElement,
  words: string,
): Promise<void> {
  stillRunning();
  await userEvent.clear(name);
  stillRunning();
  await userEvent.type(name, words);
  stillRunning();
}

export async function cancelling(): Promise<void> {
  stillRunning();
  await userEvent.click(screen.getByRole("button", { name: "Cancel" }));
  stillRunning();
}

export async function loadingMore(): Promise<void> {
  stillRunning();
  await userEvent.click(
    screen.getByRole("button", { name: "Load more workflows" }),
  );
  stillRunning();
}

/** Types into the offer's filter at once and waits out its typing pause, on a fake clock. */
export async function filtering(words: string): Promise<void> {
  stillRunning();
  vi.useFakeTimers({ toFake: ["setTimeout", "clearTimeout"] });
  fireEvent.change(screen.getByRole("searchbox", { name: "Part of a name" }), {
    target: { value: words },
  });
  await act(() => vi.advanceTimersByTimeAsync(350));
  stillRunning();
}

export async function runOpened(name: RegExp): Promise<HTMLElement> {
  stillRunning();
  const heading = await screen.findByRole("heading", { level: 1, name });
  stillRunning();
  return heading;
}
