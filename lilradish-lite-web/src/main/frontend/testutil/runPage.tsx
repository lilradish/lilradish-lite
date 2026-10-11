import { ThemeProvider } from "@mui/material/styles";
import { act, render, screen } from "@testing-library/react";
import { RouterProvider, createMemoryRouter } from "react-router";
import { expect, vi } from "vitest";

import { WORK_ITEM } from "../app/destinations";
import { RequireGroup } from "../app/standing/RequireGroup";
import { StandingProvider } from "../app/standing/StandingContext";
import { theme } from "../lib/theme/theme";
import { requestsTo, serving, type Reply } from "./answering";
import { namingGroupsByKeyAlone } from "./groupAddresses";
import { pageLaidOutAt } from "./layout";
import { answered, inGroup } from "./standingRead";
import { RunPage } from "../features/runs/RunPage";
import { StepPage } from "../features/runs/steps/StepPage";

export const GROUP = "00000003-0000-4000-8000-000000000c51";

export const RUN_ID = "00000008-0000-4000-8000-000000000c51";

export const ABOVE = "00000008-0000-4000-8000-000000000c52";

export const WORKFLOW = "00000006-0000-4000-8000-000000000c51";

export const RAISE = "0000000b-0000-4000-8000-000000000c51";

export const KEY = "CLAIMS";

export const PAGE = `/groups/${KEY}/work/${RUN_ID}`;

export const KEPT_DRAWING_AS = "lilradish.run.drawing";

export const RUN = `/api/groups/${GROUP}/runs/${RUN_ID}`;

export const AVO = { userId: "000c51", displayName: "Avo Lumerace" };

export const GLINN = { userId: "000c52", displayName: "Glinn Havven" };

export const START_RUN_RULE =
  "Only someone in this group who can start runs can start, rename, stop or resume a run, set its usage limit, withdraw a limit change they asked for, or try sending a request that could not be sent.";

export const APPROVE_ENTRY_RULE =
  "Only someone in this group who can approve entries can put workflows, questions and reference lists into service, or approve or reject a usage limit change.";

export const SUMMARISE = "00000009-0000-4000-8000-000000000c51";

export const FILE_IT = "00000009-0000-4000-8000-000000000c52";

/** Use counts beyond safe integer precision to check that their decimal text stays exact. */
export const CLAIM = {
  runId: RUN_ID,
  number: 7,
  name: "Claim from Avo",
  workflow: {
    versionName: null,
    entryId: WORKFLOW,
    name: "Handle a claim",
    version: 3,
  },
  startedBy: AVO,
  runsAbove: [],
  startedAt: "2026-09-24T08:00:00Z",
  conversationEvents: [
    {
      id: `step:${SUMMARISE}`,
      kind: "step_reached",
      at: "2026-09-24T08:00:01Z",
      stepId: SUMMARISE,
      run: { runId: RUN_ID, number: 7 },
    },
    {
      id: `step:${FILE_IT}`,
      kind: "step_reached",
      at: "2026-09-24T08:00:02Z",
      stepId: FILE_IT,
      run: { runId: RUN_ID, number: 7 },
    },
  ],
  state: "running",
  spend: {
    sent: "9007199254740993",
    cameBack: "20",
    spent: "9007199254741013",
    cameBackUnknown: false,
  },
  ceiling: { inForce: "9007199254740990", raiseNeedsApproval: true },
  stateActs: ["stop", "rename", "change_ceiling"],
};

export const STOPPED = {
  ...CLAIM,
  state: "stopped",
  stopped: { at: "2026-09-24T08:15:00Z", by: GLINN },
  stateActs: ["open_again", "rename", "change_ceiling"],
};

export const RAISED = {
  ...CLAIM,
  ceiling: {
    ...CLAIM.ceiling,
    waiting: {
      changeId: RAISE,
      to: "20000",
      askedBy: GLINN,
      askedAt: "2026-09-24T08:30:00Z",
    },
  },
  stateActs: [
    "stop",
    "change_ceiling",
    "approve_raise",
    "refuse_raise",
    "withdraw_raise",
  ],
};

export function reply(run: object, status = 200): Reply {
  return [JSON.stringify(run), status];
}

export function refusal(code: string, status: number): Reply {
  return [JSON.stringify({ code }), status];
}

export const UNANSWERED: Reply = new Promise(() => {});

export const STEPS = `${RUN}/steps`;

export const VERSION = "00000007-0000-4000-8000-000000000c51";

export const QUESTION_VERSION = "00000007-0000-4000-8000-000000000c52";

export const FROM_ABOVE = {
  runId: ABOVE,
  number: 6,
  stepId: FILE_IT,
  stepName: "route",
};

const TICKET = {
  name: "ticket",
  label: "Ticket",
  kind: "text",
  longest: 4000,
  mustBeGiven: true,
};

const DUE = { name: "due", label: "Due", kind: "date", mustBeGiven: false };

export const SUMMARY = {
  name: "summary",
  label: "Summary",
  kind: "text",
  longest: 100,
  mustBeGiven: true,
};

export const STARTED = {
  ...CLAIM,
  startedWith: { ticket: "Kettle leaks", due: "2026-09-30" },
};

export const RUN_HEADER = {
  versionName: null,
  runId: RUN_ID,
  number: 7,
  versionId: VERSION,
  state: "running",
  at: { stepId: FILE_IT, name: "file_it" },
  stateActs: ["stop"],
  progress: { done: 1, of: 2 },
};

export const DECLARATIONS = {
  [VERSION]: { takes: [TICKET, DUE], gives: [SUMMARY] },
  [QUESTION_VERSION]: {
    takes: [{ ...TICKET, name: "text", label: "Text" }],
    gives: [SUMMARY],
  },
};

export const SUMMARISE_ROW = {
  producedByUserId: null,
  producedByKind: null,
  stepId: SUMMARISE,
  order: 1,
  name: "summarise",
  runs: {
    versionName: null,
    kind: "question",
    entryId: "00000006-0000-4000-8000-000000000c52",
    name: "Summarise",
    version: 2,
    versionId: QUESTION_VERSION,
  },
  producer: { kind: "person" },
  reviewer: { kind: "person" },
  state: "done",
  takesFrom: [{ input: "text", from: { kind: "run_input", path: "ticket" } }],
  tries: { current: 1, declared: 2, beyond: false },
  cost: { callsAModel: false },
  gaveBack: [{ field: "summary", value: "A kettle leaks.", now: "stands" }],
  stateActs: [],
  stateRefusals: [],
};

export const FILE_IT_ROW = {
  producedByUserId: null,
  producedByKind: null,
  stepId: FILE_IT,
  order: 2,
  name: "file_it",
  runs: { versionName: null, kind: "code_step", codeStep: "file_claim" },
  producer: { kind: "code" },
  reviewer: { kind: "person" },
  state: "running",
  where: { kind: "running", on: "code" },
  tries: { current: 1, declared: 1, beyond: false },
  takesFrom: [
    {
      input: "summary",
      from: {
        kind: "step",
        path: "summary",
        stepId: SUMMARISE,
        name: "summarise",
        versionId: QUESTION_VERSION,
      },
    },
  ],
  cost: { callsAModel: false },
  stateActs: [],
  stateRefusals: [],
};

export function stepsRead(changed: object = {}, route: object = {}): Reply {
  return reply({
    run: RUN_HEADER,
    declarations: DECLARATIONS,
    gaveBack: {
      declares: "values",
      standing: [{ field: "summary", value: "A kettle leaks." }],
    },
    steps: [SUMMARISE_ROW, { ...FILE_IT_ROW, ...route }],
    ...changed,
  });
}

export function reads(sent: ReturnType<typeof serving>): string[] {
  return requestsTo(sent).filter((each) => each.startsWith("GET"));
}

export async function landing(landed: () => boolean): Promise<void> {
  for (let turn = 0; turn < 100 && !landed(); turn += 1) {
    await act(() => vi.advanceTimersByTimeAsync(0));
  }
  expect(landed()).toBe(true);
}

export function opening(
  routes: Readonly<Record<string, readonly Reply[]>> = {},
  address: string | { pathname: string; state?: unknown } = PAGE,
  drawing: "fullDetail" | "conversation" | null = "fullDetail",
  reader?: string,
  permissions: readonly string[] = [
    "start_run",
    "read_own_runs",
    "approve_entry",
  ],
  page = 1400,
) {
  if (drawing !== null) {
    localStorage.setItem(KEPT_DRAWING_AS, drawing);
  }
  const resize = pageLaidOutAt(page, 1600);
  const sent = serving({
    [`GET ${RUN}`]: [reply(CLAIM)],
    [`GET ${STEPS}`]: [stepsRead()],
    ...routes,
  });
  const router = createMemoryRouter(
    [
      {
        path: "/groups/:group/work/:runId",
        element: (
          <RequireGroup reachedBy={WORK_ITEM.reachedBy}>
            <RunPage />
          </RequireGroup>
        ),
      },
      {
        path: "/groups/:group/work/:runId/steps/:stepId",
        element: (
          <RequireGroup reachedBy={WORK_ITEM.reachedBy}>
            <StepPage />
          </RequireGroup>
        ),
      },
    ],
    { initialEntries: [address] },
  );
  namingGroupsByKeyAlone(router, [GROUP]);
  const readAgain = vi.fn();
  const standing = answered(
    [],
    [inGroup(GROUP, KEY, "Claims", permissions)],
    reader,
  );
  const draw = (current = standing) => (
    <ThemeProvider theme={theme}>
      <StandingProvider read={{ ...current, reload: readAgain }}>
        <RouterProvider router={router} />
      </StandingProvider>
    </ThemeProvider>
  );
  const mounted = render(draw());
  return {
    sent,
    readAgain,
    router,
    resize,
    changeStanding(
      currentPermissions: readonly string[],
      currentReader?: string,
    ) {
      mounted.rerender(
        draw(
          answered(
            [],
            [inGroup(GROUP, KEY, "Claims", currentPermissions)],
            currentReader,
          ),
        ),
      );
    },
  };
}

export async function opened(): Promise<HTMLElement> {
  return screen.findByRole("heading", { level: 1 });
}

export function setApart(words: string): string {
  return `${String.fromCodePoint(0x2068)}${words}${String.fromCodePoint(0x2069)}`;
}

export function ceiling(): HTMLElement {
  return screen.getByRole("group", { name: "Usage limit" });
}

export function said(): Record<string, string> {
  const lists = about().querySelectorAll("dl");
  expect(lists).toHaveLength(1);
  const facts: [string, string][] = [];
  for (const child of lists[0]!.children) {
    if (child.tagName === "DT") facts.push([child.textContent ?? "", ""]);
    else facts.at(-1)![1] += child.textContent ?? "";
  }
  return Object.fromEntries(facts);
}

export function about(): HTMLElement {
  return screen.getByRole("region", { name: "Overview" });
}

export function drawn(): string[] {
  return screen.queryAllByRole("button").map((each) => each.textContent ?? "");
}

export function runReads(sent: ReturnType<typeof serving>): number {
  return requestsTo(sent).filter((each) => each === `GET ${RUN}`).length;
}
