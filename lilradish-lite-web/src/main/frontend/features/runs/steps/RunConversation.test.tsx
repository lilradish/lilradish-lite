import { ThemeProvider } from "@mui/material/styles";
import { act, render, screen, within } from "@testing-library/react";
import { userEvent } from "@testing-library/user-event";
import { RouterProvider, createMemoryRouter } from "react-router";
import { afterEach, describe, expect, it, vi } from "vitest";

import type { Run } from "../../../api/groups/{groupId}/runs/{runId}";
import type {
  RunSteps,
  StepRow,
} from "../../../api/groups/{groupId}/runs/{runId}/steps";
import { StandingProvider } from "../../../app/standing/StandingContext";
import { isolatedInText } from "../../../lib/direction/isolated";
import { theme } from "../../../lib/theme/theme";
import { answered, inGroup } from "../../../testutil/standingRead";
import { requestsTo, serving } from "../../../testutil/answering";
import { RunConversation } from "./RunConversation";
import { RunReadAccess } from "../RunReadAccess";

const GROUP = "00000003-0000-4000-8000-000000000e41";
const CLAIMS = inGroup(GROUP, "CLAIMS", "Claims", []);
const RUN_ID = "00000008-0000-4000-8000-000000000e41";
const AVO = { userId: "reader", displayName: "Avo" };

const FIRST: StepRow = {
  producedByUserId: null,
  producedByKind: null,
  stepId: "step-1",
  order: 1,
  name: "first_step",
  runs: { versionName: null, kind: "question", name: "First question" },
  state: "done",
  takesFrom: [],
  cost: {},
  stateActs: new Set([]),
  stateRefusals: [],
};
const SECOND: StepRow = {
  ...FIRST,
  stepId: "step-2",
  order: 2,
  name: "second_step",
  runs: { versionName: null, kind: "question", name: "Second question" },
  state: "not_started",
};
const STEPS: RunSteps = {
  run: {
    stateActs: null,
    runId: RUN_ID,
    number: 7,
    versionId: "version",
    state: "running",
    progress: { done: 1, of: 2 },
  },
  steps: [FIRST, SECOND],
  declarations: new Map(),
  gaveBack: { declares: "nothing" },
};

async function drawn(
  events: Run["conversationEvents"],
  steps: RunSteps = STEPS,
  changed: Partial<Run> = {},
  permissions: readonly string[] = [],
) {
  const run: Run = {
    runId: RUN_ID,
    number: 7,
    runsAbove: [],
    workflow: {
      versionName: null,
      entryId: "workflow",
      name: "Handle a claim",
      version: 1,
    },
    startedBy: AVO,
    startedAt: "2026-09-25T08:00:00Z",
    state: "running",
    spend: { sent: "0", cameBack: "0", spent: "0", cameBackUnknown: false },
    ceiling: { raiseNeedsApproval: false },
    stateActs: new Set(),
    conversationEvents: events,
    ...changed,
  };
  const router = createMemoryRouter(
    [
      {
        path: "/",
        element: (
          <RunConversation
            group={CLAIMS}
            reading={{ run, steps }}
            onShown={vi.fn()}
          />
        ),
      },
    ],
    { initialEntries: ["/"] },
  );
  await act(async () => {
    render(
      <ThemeProvider theme={theme}>
        <StandingProvider
          read={answered(
            [],
            [inGroup(GROUP, "CLAIMS", "Claims", permissions)],
            AVO.userId,
          )}
        >
          <RunReadAccess>
            <RouterProvider router={router} />
          </RunReadAccess>
        </StandingProvider>
      </ThemeProvider>,
    );
  });
  return screen
    .getAllByRole("listitem")
    .map((item) =>
      item.getAttribute("aria-labelledby") &&
      item.getAttribute("aria-labelledby") !== null
        ? item.querySelector("h2")?.textContent
        : null,
    )
    .filter(Boolean);
}

describe("RunConversation", () => {
  it.each([
    [[], "Waiting for the person who started the run."],
    [
      [{ runId: "parent", number: 6 }],
      "Waiting for the person who started the top-level run.",
    ],
    [
      [
        { runId: "top", number: 5 },
        { runId: "parent", number: 6 },
      ],
      "Waiting for the person who started the top-level run.",
    ],
  ] as const)(
    "uses the full run hierarchy for a starter wait beneath %j",
    async (runsAbove, sentence) => {
      const sent = serving({});
      const step: StepRow = {
        ...FIRST,
        state: "waiting",
        reviewer: { kind: "model", model: "small" },
        where: {
          kind: "waiting_on_review",
          number: 1,
          since: "2026-09-24T08:05:00Z",
          review: "turned_away",
          values: [{ field: "summary" }],
          waitsOn: "starter",
        },
        stateActs: new Set(["try_sending"]),
        waitsOnStarter: runsAbove.length === 0 ? "this_run" : "top_level_run",
      };
      await drawn(
        [
          {
            id: "step:1",
            kind: "step_reached",
            at: "2026-09-25T08:01:00Z",
            stepId: FIRST.stepId,
            run: { runId: RUN_ID, number: 7 },
          },
        ],
        { ...STEPS, steps: [step] },
        {
          above: runsAbove.at(-1)?.runId,
          runsAbove,
          ...(runsAbove.length === 0
            ? {}
            : {
                startedBy: undefined,
                from: {
                  runId: "parent",
                  number: 6,
                  stepId: "parent-step",
                  stepName: "handle_claim",
                },
              }),
        },
        ["start_run"],
      );
      const control = screen.getByRole("button", { name: "Waiting" });
      const message = control.closest("li")!;
      expect(message).not.toHaveTextContent(sentence);

      await userEvent.click(control);

      expect(message).toHaveTextContent(sentence);
      expect(message).toHaveTextContent(
        "Waiting for the model's review, which could not be sent: the model service turned the request away.",
      );
      expect(message).toHaveTextContent(
        "Someone who can start runs can try sending it.",
      );
      expect(
        within(message).getByRole("button", { name: "Try sending" }),
      ).toBeEnabled();
      expect(message).toHaveTextContent("has not been reviewed yet.");
      expect(message).not.toHaveTextContent(
        runsAbove.length === 0
          ? "Waiting for the person who started the top-level run."
          : "Waiting for the person who started the run.",
      );
      expect(requestsTo(sent)).toEqual([]);
    },
  );

  it.each([undefined, "parent"])(
    "keeps a failed run's final account complete with parent %s",
    async (above) => {
      const sent = serving({});
      await drawn(
        [],
        {
          ...STEPS,
          run: { ...STEPS.run, state: "failed" },
          steps: [
            {
              ...FIRST,
              state: "failed",
              where: {
                kind: "failed",
                reason: "tries_spent",
                waitsOn: "starter",
              },
            },
          ],
        },
        {
          state: "failed",
          above,
          runsAbove: above === undefined ? [] : [{ runId: above, number: 6 }],
          ...(above === undefined
            ? {}
            : {
                startedBy: undefined,
                from: {
                  runId: above,
                  number: 6,
                  stepId: "parent-step",
                  stepName: "handle_claim",
                },
              }),
        },
      );

      const message = screen
        .getByRole("heading", { name: "Failed", level: 2 })
        .closest("li")!;
      expect(message).toHaveTextContent("First question");
      expect(message).toHaveTextContent(
        "All allowed attempts are used up, and none was accepted.",
      );
      expect(message).toHaveTextContent(
        "Someone who can answer steps can make another attempt.",
      );
      expect(message).not.toHaveTextContent("person who started");
      expect(requestsTo(sent)).toEqual([]);
    },
  );
  it("updates the stopped run's day at midnight without reading or moving the message", async () => {
    vi.useFakeTimers({ toFake: ["Date"] });
    vi.setSystemTime(new Date("2026-10-03T21:59:30Z"));
    const sent = serving({});
    await drawn(
      [
        {
          id: "step:1",
          kind: "step_reached",
          at: "2026-10-03T21:58:00Z",
          stepId: FIRST.stepId,
          run: { runId: RUN_ID, number: 7 },
        },
      ],
      {
        ...STEPS,
        run: { ...STEPS.run, state: "stopped" },
        steps: [{ ...FIRST, state: "done" }],
      },
      {
        state: "stopped",
        stopped: {
          at: "2026-10-03T21:59:00Z",
          by: AVO,
        },
      },
    );
    const title = screen
      .getAllByRole("heading", { level: 2 })
      .find((item) => item.textContent?.startsWith("Stopped by"))!;
    const focused = screen.getByRole("button", { name: "Details" });
    focused.focus();
    expect(document.activeElement).toBe(focused);
    expect(title).toHaveTextContent("11:59 PM today");

    vi.setSystemTime(new Date("2026-10-03T22:00:00Z"));
    act(() => document.dispatchEvent(new Event("visibilitychange")));

    expect(title).toHaveTextContent("11:59 PM yesterday");
    expect(document.activeElement).toBe(focused);
    expect(requestsTo(sent)).toEqual([]);
  });
  it("uses the server's event order as message order and draws no step before it is reached", async () => {
    const titles = await drawn([
      {
        id: "step:1",
        kind: "step_reached",
        at: "2026-09-25T08:01:00Z",
        stepId: FIRST.stepId,
        run: { runId: RUN_ID, number: 7 },
      },
      {
        id: "answer:1",
        kind: "answered_by_hand",
        at: "2026-09-25T08:02:00Z",
        stepId: FIRST.stepId,
        by: AVO,
        number: 1,
        values: [{ field: "answer", value: "Demo Sample" }],
        run: { runId: RUN_ID, number: 7 },
      },
      {
        id: "ceiling:1",
        kind: "ceiling_changed",
        at: "2026-09-25T08:03:00Z",
        by: AVO,
        run: { runId: RUN_ID, number: 7 },
        to: "100",
      },
    ]);

    expect(titles).toEqual([
      "Run inputs",
      isolatedInText("First question"),
      "Answered by hand",
      "Changed the usage limit",
    ]);
    expect(titles).not.toContain("Second question");
    expect(document.body).not.toHaveTextContent("Not reached yet:");
  });

  it("moves between machine tries around a hand answer without offering the hand answer as a machine try", async () => {
    const step = {
      ...FIRST,
      state: "done" as const,
      producer: { kind: "model" as const, model: "small" },
      tries: { current: 3, declared: 3, beyond: false },
    };
    const path = `/api/groups/${GROUP}/runs/${RUN_ID}/steps/${step.stepId}`;
    const sent = serving({
      [`GET ${path}`]: [
        [
          JSON.stringify({
            run: STEPS.run,
            declarations: {},
            step,
            triesMade: [1, 3].map((number) => ({
              wentWrongPresent: false,
              returnedPresent: false,
              declinedPresent: false,
              unreadableResponsePresent: false,
              modelReplyPresent: false,
              number,
              beyond: false,
              producedBy: { kind: "model", model: "small" },
              values: [],
              review: {
                asked: false,
                declinedPresent: false,
                unreadableResponsePresent: false,
                modelReplyPresent: false,
              },
              ended: "stands",
              cost: {
                callsAModel: true,
                sent: "1",
                cameBack: "1",
                spent: "2",
                cameBackUnknown: false,
              },
            })),
          }),
          200,
        ],
      ],
    });
    await drawn(
      [
        {
          id: "step:1",
          kind: "step_reached",
          at: "2026-09-25T08:01:00Z",
          stepId: step.stepId,
          run: { runId: RUN_ID, number: 7 },
        },
        {
          id: "answer:2",
          kind: "answered_by_hand",
          at: "2026-09-25T08:02:00Z",
          stepId: step.stepId,
          by: AVO,
          number: 2,
          values: [{ field: "answer", value: "Demo Sample" }],
          run: { runId: RUN_ID, number: 7 },
        },
      ],
      { ...STEPS, steps: [step] },
    );
    const message = screen.getByRole("listitem", {
      name: isolatedInText("First question"),
    });
    const earlier = within(message).getByRole("button", {
      name: "Previous attempt",
    });
    const later = within(message).getByRole("button", { name: "Next attempt" });
    expect(screen.getAllByText("Answered by hand")).toHaveLength(1);
    expect(later).toHaveAttribute("aria-disabled", "true");
    await userEvent.click(
      within(message).getByRole("button", { name: "Done" }),
    );
    expect(within(message).getByText("Attempt 3 of 3")).toBeVisible();

    await userEvent.click(earlier);
    expect(await within(message).findByText("Attempt 1 of 3")).toBeVisible();
    expect(earlier).toHaveAttribute("aria-disabled", "true");
    await userEvent.click(later);
    expect(within(message).getByText("Attempt 3 of 3")).toBeVisible();
    expect(later).toHaveAttribute("aria-disabled", "true");
    expect(requestsTo(sent)).toEqual([`GET ${path}`]);
    expect(requestsTo(sent)).not.toContain(`GET ${path}/tries/2`);
  });
});

afterEach(() => vi.unstubAllGlobals());
