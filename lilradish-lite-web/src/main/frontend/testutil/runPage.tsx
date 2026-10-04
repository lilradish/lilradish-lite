import { ThemeProvider } from "@mui/material/styles";
import { act, render, screen, within } from "@testing-library/react";
import { RouterProvider, createMemoryRouter } from "react-router";
import { expect, vi } from "vitest";

import { StandingProvider } from "../app/standing/StandingContext";
import { theme } from "../lib/theme/theme";
import { requestsTo, serving, type Reply } from "./answering";
import { answered, inGroup } from "./standingRead";
import { RunPage } from "../features/runs/RunPage";
import { StepPage } from "../features/runs/steps/StepPage";

export const GROUP = "00000003-0000-4000-8000-000000000c51";

export const RUN_ID = "00000008-0000-4000-8000-000000000c51";

export const ABOVE = "00000008-0000-4000-8000-000000000c52";

export const WORKFLOW = "00000006-0000-4000-8000-000000000c51";

export const RAISE = "0000000b-0000-4000-8000-000000000c51";

export const PAGE = `/groups/${GROUP}/work/${RUN_ID}`;

export const KEPT_DRAWING_AS = "lilradish.run.drawing";

export const RUN = `/api/groups/${GROUP}/runs/${RUN_ID}`;

export const AVO = { userId: "000c51", displayName: "Avo Lumerace" };

export const GLINN = { userId: "000c52", displayName: "Glinn Havven" };

export const START_RUN_RULE =
  "A group's runs are started, renamed, stopped, opened again and given a ceiling, and a raise asked withdrawn, and what was held back sent again, only by a role in it that may start one.";

export const APPROVE_ENTRY_RULE =
  "A group's entries are put into service, and a run's raised ceiling approved or refused, only by a role in it that may approve an entry.";

export const SUMMARISE = "00000009-0000-4000-8000-000000000c51";

export const FILE_IT = "00000009-0000-4000-8000-000000000c52";

/** Use counts beyond safe integer precision to check that their decimal text stays exact. */
export const CLAIM = {
  runId: RUN_ID,
  number: 7,
  name: "Claim from Avo",
  workflow: { entryId: WORKFLOW, name: "Handle a claim", version: 3 },
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
  acts: ["stop", "rename", "change_ceiling"],
};

export const STOPPED = {
  ...CLAIM,
  state: "stopped",
  stopped: { at: "2026-09-24T08:15:00Z", by: GLINN },
  acts: ["open_again", "rename", "change_ceiling"],
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
  acts: ["stop", "change_ceiling", "approve_raise", "refuse_raise"],
};

export const ASKED = {
  ...RAISED,
  acts: ["stop", "change_ceiling", "withdraw_raise"],
};

export function reply(run: object, status = 200): Reply {
  return [JSON.stringify(run), status];
}

export function refusal(code: string, status: number): Reply {
  return [JSON.stringify({ code }), status];
}

/** A reply the server never sends. */
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
  runId: RUN_ID,
  number: 7,
  versionId: VERSION,
  state: "running",
  at: { stepId: FILE_IT, name: "file_it" },
  acts: ["stop"],
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
  stepId: SUMMARISE,
  order: 1,
  name: "summarise",
  runs: {
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
  acts: [],
  withheld: [],
};

export const FILE_IT_ROW = {
  stepId: FILE_IT,
  order: 2,
  name: "file_it",
  runs: { kind: "code_step", codeStep: "file_claim" },
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
  acts: [],
  withheld: [],
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

/** What is in flight let land a turn at a time, until `landed` holds; failing where it never does. */
export async function landing(landed: () => boolean): Promise<void> {
  for (let turn = 0; turn < 100 && !landed(); turn += 1) {
    await act(() => vi.advanceTimersByTimeAsync(0));
  }
  expect(landed()).toBe(true);
}

/** Draw a run at its real route with group standing and HTTP replies. */
export function opening(
  routes: Readonly<Record<string, readonly Reply[]>> = {},
  address: string | { pathname: string; state?: unknown } = PAGE,
  drawing: "fullDetail" | "conversation" | null = "fullDetail",
  reader?: string,
) {
  if (drawing !== null) {
    localStorage.setItem(KEPT_DRAWING_AS, drawing);
  }
  const sent = serving({
    [`GET ${RUN}`]: [reply(CLAIM)],
    [`GET ${STEPS}`]: [stepsRead()],
    ...routes,
  });
  const router = createMemoryRouter(
    [
      { path: "/groups/:groupId/work/:runId", element: <RunPage /> },
      {
        path: "/groups/:groupId/work/:runId/steps/:stepId",
        element: <StepPage />,
      },
    ],
    { initialEntries: [address] },
  );
  const readAgain = vi.fn();
  const standing = answered(
    [],
    [inGroup(GROUP, "CLAIMS", "Claims", ["start_run", "read_own_runs"])],
    reader,
  );
  render(
    <ThemeProvider theme={theme}>
      <StandingProvider read={{ ...standing, reload: readAgain }}>
        <RouterProvider router={router} />
      </StandingProvider>
    </ThemeProvider>,
  );
  return { sent, readAgain, router };
}

export async function opened(): Promise<HTMLElement> {
  return screen.findByRole("heading", { level: 1 });
}

export function setApart(words: string): string {
  return `${String.fromCodePoint(0x2068)}${words}${String.fromCodePoint(0x2069)}`;
}

export function ceiling(): HTMLElement {
  return screen.getByRole("group", { name: "Ceiling" });
}

export function said(): Record<string, string> {
  const facts = within(about().querySelector("dl")!);
  const terms = facts.getAllByRole("term");
  const definitions = facts.getAllByRole("definition");
  return Object.fromEntries(
    terms.map((term, at) => [
      term.textContent ?? "",
      definitions[at]!.textContent ?? "",
    ]),
  );
}

export function about(): HTMLElement {
  return screen.getByRole("region", { name: "About it" });
}

export function drawn(): string[] {
  return screen.queryAllByRole("button").map((each) => each.textContent ?? "");
}

export function runReads(sent: ReturnType<typeof serving>): number {
  return requestsTo(sent).filter((each) => each === `GET ${RUN}`).length;
}
