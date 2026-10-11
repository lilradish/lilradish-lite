import type { ConversationEvent } from "../../../api/conversationEvents";
import { ThemeProvider } from "@mui/material/styles";
import { fireEvent, render, screen, within } from "@testing-library/react";
import { RouterProvider, createMemoryRouter } from "react-router";
import { describe, expect, it } from "vitest";

import type { Run } from "../../../api/groups/{groupId}/runs/{runId}";
import type {
  RunSteps,
  StepRow,
} from "../../../api/groups/{groupId}/runs/{runId}/steps";
import { StandingProvider } from "../../../app/standing/StandingContext";
import { theme } from "../../../lib/theme/theme";
import { answered, inGroup } from "../../../testutil/standingRead";
import { ActMessage } from "./ActMessage";

const CLAIMS = inGroup(
  "00000003-0000-4000-8000-000000000e41",
  "CLAIMS",
  "Claims",
  [],
);
const RUN_ID = "00000008-0000-4000-8000-000000000e41";
const STEP_ID = "00000009-0000-4000-8000-000000000e41";
const AVO = { userId: "reader", displayName: "Avo" };
const BOB = { userId: "other", displayName: "Bob" };
const ACT_AT = new Date(2026, 8, 25, 8, 5).toISOString();

const RUN: Run = {
  runId: RUN_ID,
  number: 7,
  runsAbove: [],
  workflow: {
    versionName: null,
    entryId: "workflow",
    name: "Handle a claim",
    version: 1,
  },
  startedAt: "2026-09-25T08:00:00Z",
  conversationEvents: [],
  state: "running",
  spend: { sent: "0", cameBack: "0", spent: "0", cameBackUnknown: false },
  ceiling: { raiseNeedsApproval: false },
  stateActs: new Set(),
};

const STEP: StepRow = {
  producedByUserId: null,
  producedByKind: null,
  stepId: STEP_ID,
  order: 1,
  name: "summarise",
  runs: {
    versionName: null,
    kind: "question",
    name: "Summarise",
    versionId: "version",
  },
  state: "done",
  takesFrom: [],
  cost: {},
  stateActs: new Set([]),
  stateRefusals: [],
};

const READING: RunSteps = {
  run: {
    stateActs: null,
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
              group={CLAIMS}
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
  it.each(["reviewed", "refused_for_length"] as const)(
    "formats a one-line human %s reason from its server fact",
    (kind) => {
      const event = {
        id: "imaginary-refusal",
        run: { runId: RUN_ID, number: 7 },
        at: ACT_AT,
        by: AVO,
        stepId: STEP_ID,
        number: 1,
        decisions: [
          {
            field: "answer",
            outcome: "refused" as const,
            why: "**Keep the imaginary box label**",
            whyLines: "several" as const,
          },
        ],
      };
      const item = drawn(
        kind === "reviewed"
          ? { ...event, kind }
          : {
              ...event,
              kind,
              held: {
                run: { runId: RUN_ID, number: 7 },
                stepId: STEP_ID,
                stepName: "summarise",
              },
            },
      );
      expect(item.querySelector("strong")).toHaveTextContent(
        "Keep the imaginary box label",
      );
      fireEvent.click(screen.getByRole("button", { name: "Show as written" }));
      expect(item.querySelector("strong")).toBeNull();
      expect(item).toHaveTextContent("**Keep the imaginary box label**");
    },
  );
  it("formats every multiline refusal in one message with one shared switch", () => {
    drawn({
      kind: "reviewed",
      id: "imaginary-review",
      run: { runId: RUN_ID, number: 7 },
      at: ACT_AT,
      by: AVO,
      stepId: STEP_ID,
      number: 1,
      decisions: [
        {
          field: "answer",
          outcome: "refused",
          why: "# Missing evidence\nKeep the box",
          whyLines: "several",
        },
        {
          field: "other",
          outcome: "refused",
          why: "## Missing label\n[Source](https://example.com/claim)",
          whyLines: "several",
        },
      ],
    });
    expect(
      screen.getAllByRole("button", { name: "Show as written" }),
    ).toHaveLength(1);
    expect(
      screen.getByRole("heading", { name: "Missing evidence" }),
    ).toBeInTheDocument();
    expect(
      screen.getByRole("heading", { name: "Missing label" }),
    ).toBeInTheDocument();
    expect(screen.queryByRole("link")).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: "Show as written" }));
    expect(
      screen.queryByRole("heading", { name: "Missing evidence" }),
    ).toBeNull();
    expect(screen.getByText(/# Missing evidence/)).toBeInTheDocument();
  });
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
        {
          field: "other",
          outcome: "refused",
          why: "Too short",
          whyLines: "several",
        },
      ],
    });

    expect(item).toHaveAccessibleName("Reviewed");
    expect(item).toHaveTextContent(/Accepted.*Answer/);
    expect(item).toHaveTextContent(/Rejected.*other.*Too short/);
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
    expect(item).toHaveTextContent("Attempt 2");
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
      {
        ...STEP,
        runs: { versionName: null, kind: "code_step", codeStep: "prepare" },
      },
    );

    expect(item).toHaveAccessibleName("Said it was done");
    expect(item).toHaveTextContent("Attempt 3");
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
      decisions: [
        {
          field: "answer",
          outcome: "refused",
          why: "Too long",
          whyLines: "several",
          lengthUnits: "9007199254740993",
          excessUnits: "1",
        },
      ],
    });
    const link = within(item).getByRole("link", { name: /Open.*file it/ });

    expect(item).toHaveTextContent(
      /Answer.*: made the request from.*file it.*in run 8 too long to send/,
    );
    expect(item).toHaveTextContent("Too long");
    expect(item).toHaveTextContent("about 9,007,199,254,740,993 units");
    expect(item).toHaveTextContent("Too long by: about 1 unit");
    expect(item).not.toHaveTextContent("Why not shown");
    expect(link.getAttribute("href")).toBe(
      "/groups/CLAIMS/work/child/steps/held-step",
    );
    expect(within(item).queryByText(/Reviewed/)).toBeNull();
  });

  it("uses the historical held step's display name while linking its original run and key", () => {
    const item = drawn({
      id: "length:labelled",
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
        label: "申請を記録\u202e",
      },
      decisions: [
        {
          field: "answer",
          outcome: "refused",
          why: "Too long",
          whyLines: "several",
        },
      ],
    });

    const link = within(item).getByRole("link", { name: /Open.*申請を記録/ });
    expect(link).toHaveAttribute(
      "href",
      "/groups/CLAIMS/work/child/steps/held-step",
    );
    expect(item).toHaveTextContent(
      /Answer.*: made the request from.*申請を記録.*in run 8 too long to send/,
    );
    expect(item).toHaveTextContent("Too long");
    expect(item).not.toHaveTextContent("file it");
    expect(item).not.toHaveTextContent("file_it");
    expect(within(item).queryByText(/Reviewed/)).toBeNull();
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
        decisions: [
          {
            field: "answer",
            outcome: "refused",
            why: "Too long",
            whyLines: "several",
          },
        ],
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
      "Request withdrawn: a later change replaced it.",
    );
    expect(item).toHaveTextContent("Usage limit of run 8");
    expect(item).toHaveTextContent(
      "From 9,007,199,254,740,993 to 12,000,000,000,000,000",
    );
    expect(item).not.toHaveTextContent("Usage limit of run 7");
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

    expect(item).toHaveAccessibleName("Changed the usage limit of run 8");
    expect(item).toHaveTextContent("From No limit to 9,007,199,254,740,993");
    expect(item).not.toHaveTextContent("$");
  });

  it.each([
    ["ceiling_asked", "Asked to change the usage limit of run 8"],
    ["ceiling_approved", "Approved the usage limit change of run 8"],
    ["ceiling_refused", "Rejected the usage limit change of run 8"],
    ["ceiling_withdrawn", "Withdrew the usage limit request of run 8"],
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
    ["ceiling_asked", "Asked to change the usage limit"],
    ["ceiling_approved", "Approved the usage limit change"],
    ["ceiling_refused", "Rejected the usage limit change"],
    ["ceiling_withdrawn", "Withdrew the usage limit request"],
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
    ["opened_again", "Resumed this run", AVO, "auto"],
    ["opened_again", "Resumed this run", BOB, "leading"],
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
      /Stopped: its usage limit and the limits of runs 8 and 9 reached/,
    );
    expect(item).not.toHaveTextContent("By Avo");
    expect(item).not.toHaveTextContent("run 7");
  });
});
