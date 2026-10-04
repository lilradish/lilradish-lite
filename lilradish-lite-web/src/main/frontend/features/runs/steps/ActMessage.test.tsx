import { ThemeProvider } from "@mui/material/styles";
import { render, screen, within } from "@testing-library/react";
import { RouterProvider, createMemoryRouter } from "react-router";
import { describe, expect, it } from "vitest";

import type {
  ConversationEvent,
  Run,
} from "../../../api/groups/{groupId}/runs/{runId}";
import type {
  RunSteps,
  StepRow,
} from "../../../api/groups/{groupId}/runs/{runId}/steps";
import { StandingProvider } from "../../../app/standing/StandingContext";
import { theme } from "../../../lib/theme/theme";
import { answered } from "../../../testutil/standingRead";
import { ActMessage } from "./ActMessage";

const GROUP = "00000003-0000-4000-8000-000000000e41";
const RUN_ID = "00000008-0000-4000-8000-000000000e41";
const STEP_ID = "00000009-0000-4000-8000-000000000e41";
const AVO = { userId: "reader", displayName: "Avo" };
const BOB = { userId: "other", displayName: "Bob" };
const ACT_AT = new Date(2026, 8, 25, 8, 5).toISOString();

const RUN: Run = {
  runId: RUN_ID,
  number: 7,
  runsAbove: [],
  workflow: { entryId: "workflow", name: "Handle a claim", version: 1 },
  startedAt: "2026-09-25T08:00:00Z",
  conversationEvents: [],
  state: "running",
  spend: { sent: "0", cameBack: "0", spent: "0", cameBackUnknown: false },
  ceiling: { raiseNeedsApproval: false },
  acts: new Set(),
};

const STEP: StepRow = {
  stepId: STEP_ID,
  order: 1,
  name: "summarise",
  runs: { kind: "question", name: "Summarise", versionId: "version" },
  state: "done",
  takesFrom: [],
  cost: {},
  acts: [],
  withheld: [],
};

const READING: RunSteps = {
  run: {
    runId: RUN_ID,
    number: 7,
    versionId: "version",
    state: "running",
    progress: { done: 1, of: 1 },
  },
  steps: [STEP],
  declarations: new Map([
    [
      "version",
      {
        takes: [],
        gives: [
          {
            name: "answer",
            label: "Answer",
            kind: "text",
            longest: 400,
            mustBeGiven: true,
          },
        ],
      },
    ],
  ]),
  gaveBack: { declares: "version" },
};

function drawn(
  event: Exclude<ConversationEvent, { kind: "step_reached" }>,
  reader = AVO.userId,
  search = "",
  step = STEP,
) {
  const router = createMemoryRouter(
    [
      {
        path: "/",
        element: (
          <ol>
            <ActMessage
              event={event}
              run={RUN}
              reading={READING}
              step={"stepId" in event ? step : undefined}
              groupId={GROUP}
            />
          </ol>
        ),
      },
    ],
    { initialEntries: [`/${search}`] },
  );
  render(
    <ThemeProvider theme={theme}>
      <StandingProvider read={answered([], [], reader)}>
        <RouterProvider router={router} />
      </StandingProvider>
    </ThemeProvider>,
  );
  return screen.getAllByRole("listitem")[0]!;
}

describe("ActMessage", () => {
  it("draws one mixed review by its author on the reader's side, with each outcome", () => {
    const item = drawn({
      id: "review:1",
      kind: "reviewed",
      at: "2026-09-25T08:05:00Z",
      stepId: STEP_ID,
      by: AVO,
      number: 2,
      run: { runId: RUN_ID, number: 7 },
      decisions: [
        { field: "answer", outcome: "assured" },
        { field: "other", outcome: "refused", why: "Too short" },
      ],
    });

    expect(item).toHaveAccessibleName("Reviewed this try");
    expect(item).toHaveTextContent(/Assured.*Answer/);
    expect(item).toHaveTextContent(/Refused.*other.*Too short/);
    expect(item).toHaveTextContent(/By.*Avo/);
    expect(getComputedStyle(item).marginInlineStart).toBe("auto");
    expect(screen.getAllByRole("listitem")).toHaveLength(3);
  });

  it("keeps another person's answer on the leading side and shows its value", () => {
    const item = drawn({
      id: "answer:1",
      kind: "answered_by_hand",
      at: "2026-09-25T08:05:00Z",
      stepId: STEP_ID,
      by: BOB,
      number: 2,
      run: { runId: RUN_ID, number: 7 },
      values: [{ field: "answer", value: "Demo Sample" }],
    });

    expect(item).toHaveAccessibleName("Answered by hand");
    expect(item).toHaveTextContent("Try 2");
    expect(item).toHaveTextContent("Demo Sample");
    expect(item).toHaveTextContent(/By.*Bob/);
    expect(getComputedStyle(item).marginInlineStart).not.toBe("auto");
  });

  it("says a code step was said done, with its hand answer and reason", () => {
    const item = drawn(
      {
        id: "answer:code",
        kind: "answered_by_hand",
        at: "2026-09-25T08:05:00Z",
        stepId: STEP_ID,
        by: AVO,
        number: 3,
        run: { runId: RUN_ID, number: 7 },
        values: [{ field: "answer", value: "Demo Sample" }],
        why: "Checked the result",
      },
      AVO.userId,
      "",
      { ...STEP, runs: { kind: "code_step", codeStep: "prepare" } },
    );

    expect(item).toHaveAccessibleName("Said it was done");
    expect(item).toHaveTextContent("Try 3");
    expect(item).toHaveTextContent("Demo Sample");
    expect(item).toHaveTextContent("Checked the result");
    expect(getComputedStyle(item).marginInlineStart).toBe("auto");
  });

  it("links a length refusal to the held step in its own run", () => {
    const item = drawn({
      id: "length:1",
      kind: "refused_for_length",
      at: "2026-09-25T08:05:00Z",
      stepId: STEP_ID,
      by: BOB,
      number: 2,
      run: { runId: RUN_ID, number: 7 },
      held: {
        run: { runId: "child", number: 8 },
        stepId: "held-step",
        stepName: "file_it",
      },
      decisions: [{ field: "answer", outcome: "refused", why: "Too long" }],
    });
    const link = within(item).getByRole("link", { name: /Open.*file it/ });

    expect(item).toHaveTextContent(
      /Answer.*: what.*file it.*in run 8 would send was too long/,
    );
    expect(item).toHaveTextContent("Too long");
    expect(item).not.toHaveTextContent("Why not shown");
    expect(link.getAttribute("href")).toContain("/child/steps/held-step");
    expect(within(item).queryByText(/Reviewed this try/)).toBeNull();
  });

  it("keeps the History filter and sort when opening a held step, without a picked value or try", () => {
    const item = drawn(
      {
        id: "length:2",
        kind: "refused_for_length",
        at: "2026-09-25T08:05:00Z",
        stepId: STEP_ID,
        by: BOB,
        number: 2,
        run: { runId: RUN_ID, number: 7 },
        held: {
          run: { runId: "child", number: 8 },
          stepId: "held-step",
          stepName: "file_it",
        },
        decisions: [{ field: "answer", outcome: "refused", why: "Too long" }],
      },
      AVO.userId,
      "?filter=claim&sort=run&value=answer&try=2&tab=history",
    );
    const href = within(item)
      .getByRole("link", { name: /Open.*file it/ })
      .getAttribute("href");
    expect(href).toContain("/child/steps/held-step?filter=claim&sort=run");
    expect(href).not.toContain("value=");
    expect(href).not.toContain("try=");
    expect(href).not.toContain("tab=");
  });

  it("names the child run and both bounds when a pending raise is superseded", () => {
    const item = drawn({
      id: "ceiling:superseded",
      kind: "ceiling_superseded",
      at: "2026-09-25T08:05:00Z",
      run: { runId: "child", number: 8 },
      from: "9007199254740993",
      to: "12000000000000000",
    });

    expect(item).toHaveAccessibleName(
      "The change asked for was withdrawn by a later one.",
    );
    expect(item).toHaveTextContent("Ceiling of run 8");
    expect(item).toHaveTextContent(
      "From 9,007,199,254,740,993 to 12,000,000,000,000,000",
    );
    expect(item).not.toHaveTextContent("Ceiling of run 7");
  });

  it("names the target child run and formats a ceiling as a count", () => {
    const item = drawn({
      id: "ceiling:1",
      kind: "ceiling_changed",
      at: "2026-09-25T08:05:00Z",
      by: AVO,
      run: { runId: "child", number: 8 },
      to: "9007199254740993",
    });

    expect(item).toHaveAccessibleName("Changed the ceiling of run 8");
    expect(item).toHaveTextContent("From No ceiling to 9,007,199,254,740,993");
    expect(item).not.toHaveTextContent("$");
  });

  it.each([
    ["ceiling_asked", "Asked to change the ceiling of run 8"],
    ["ceiling_approved", "Approved the ceiling change of run 8"],
    ["ceiling_refused", "Refused the ceiling change of run 8"],
    ["ceiling_withdrawn", "Withdrew the ceiling change of run 8"],
  ] as const)(
    "says a child %s act with its author and bounds",
    (kind, title) => {
      const item = drawn({
        id: `ceiling:${kind}`,
        kind,
        at: ACT_AT,
        by: BOB,
        run: { runId: "child", number: 8 },
        from: "50",
        to: "100",
      });

      expect(item).toHaveAccessibleName(title);
      expect(item).toHaveTextContent("From 50 to 100");
      expect(item).toHaveTextContent(/By.*Bob/);
      expect(item).toHaveTextContent("08:05");
      expect(getComputedStyle(item).marginInlineStart).not.toBe("auto");
    },
  );

  it.each([
    ["ceiling_asked", "Asked to change the ceiling"],
    ["ceiling_approved", "Approved the ceiling change"],
    ["ceiling_refused", "Refused the ceiling change"],
    ["ceiling_withdrawn", "Withdrew the ceiling change"],
  ] as const)("says an own %s act without a child number", (kind, title) => {
    const item = drawn({
      id: `own:${kind}`,
      kind,
      at: ACT_AT,
      by: AVO,
      run: { runId: RUN_ID, number: 7 },
      from: "50",
      to: "100",
    });

    expect(item).toHaveAccessibleName(title);
    expect(item).toHaveTextContent("From 50 to 100");
    expect(item).toHaveTextContent(/By.*Avo/);
    expect(item).toHaveTextContent("08:05");
    expect(item).not.toHaveTextContent("run 7");
    expect(getComputedStyle(item).marginInlineStart).toBe("auto");
  });

  it.each([
    ["stopped", "Stopped this run", AVO, "auto"],
    ["stopped", "Stopped this run", BOB, "leading"],
    ["opened_again", "Opened this run again", AVO, "auto"],
    ["opened_again", "Opened this run again", BOB, "leading"],
  ] as const)(
    "says who %s the run on the actor's side",
    (kind, title, actor, side) => {
      const item = drawn({
        id: `state:${kind}`,
        kind,
        at: ACT_AT,
        by: actor,
        run: { runId: RUN_ID, number: 7 },
      });

      expect(item).toHaveAccessibleName(title);
      expect(item).toHaveTextContent(actor.displayName);
      expect(item).toHaveTextContent("08:05");
      if (side === "auto") {
        expect(getComputedStyle(item).marginInlineStart).toBe("auto");
      } else {
        expect(getComputedStyle(item).marginInlineStart).not.toBe("auto");
      }
    },
  );

  it("names its own and each child ceiling in a historical stop without naming a person", () => {
    const item = drawn({
      id: "state:ceiling",
      kind: "stopped_by_ceiling",
      at: "2026-09-25T08:05:00Z",
      run: { runId: RUN_ID, number: 7 },
      ceilings: [
        { runId: RUN_ID, number: 7 },
        { runId: "child-a", number: 8 },
        { runId: "child-b", number: 9 },
      ],
    });

    expect(item).toHaveAccessibleName(
      /Stopped by its ceiling and the ceilings of runs 8 and 9/,
    );
    expect(item).not.toHaveTextContent("By Avo");
    expect(item).not.toHaveTextContent("run 7");
  });
});
