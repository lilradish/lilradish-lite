import { ThemeProvider } from "@mui/material/styles";
import { act, render, screen, within } from "@testing-library/react";
import { RouterProvider, createMemoryRouter } from "react-router";
import { afterEach, describe, expect, it, vi } from "vitest";

import type {
  RunSteps,
  StepRow,
} from "../../../api/groups/{groupId}/runs/{runId}/steps";
import { theme } from "../../../lib/theme/theme";
import { resolved } from "../../../testutil/grounds";
import { requestsTo, serving } from "../../../testutil/answering";
import { StepsTable } from "./StepsTable";

const GROUP = "00000003-0000-4000-8000-000000000e51";

const RUN_ID = "00000008-0000-4000-8000-000000000e51";

const VERSION = "00000007-0000-4000-8000-000000000e51";

/** Colours as `theme.ts` writes them, copied by hand so a path nothing resolves shows. */
const RUNNING = "#01579b";

const SUPPORTING_TEXT = "#595959";

const RUNNING_CODE: StepRow = {
  stepId: "00000009-0000-4000-8000-000000000e51",
  order: 1,
  name: "file_it",
  runs: { kind: "code_step", codeStep: "file_it" },
  state: "running",
  where: { kind: "running", on: "code" },
  takesFrom: [],
  cost: {},
  acts: [],
  withheld: [],
};

const NOT_STARTED: StepRow = {
  ...RUNNING_CODE,
  stepId: "00000009-0000-4000-8000-000000000e52",
  order: 2,
  name: "tell_them",
  state: "not_started",
  where: undefined,
};

const READING: RunSteps = {
  run: {
    runId: RUN_ID,
    number: 7,
    versionId: VERSION,
    state: "running",
    progress: { done: 0, of: 2 },
  },
  declarations: new Map(),
  gaveBack: { declares: VERSION },
  steps: [RUNNING_CODE, NOT_STARTED],
};

/** Each step's "Where it is" cell, in the order the steps run. */
function whereCells(reading: RunSteps = READING): HTMLElement[] {
  const router = createMemoryRouter(
    [
      {
        path: "/",
        element: (
          <StepsTable groupId={GROUP} reading={reading} labelledBy="steps" />
        ),
      },
    ],
    { initialEntries: ["/"] },
  );
  render(
    <ThemeProvider theme={theme}>
      <RouterProvider router={router} />
    </ThemeProvider>,
  );
  const column = screen
    .getAllByRole("columnheader")
    .findIndex((header) => header.textContent === "Where it is");
  return screen
    .getAllByRole("row")
    .slice(1)
    .map((row) => row.children[column] as HTMLElement);
}

describe("StepsTable", () => {
  it("updates a stopped step's day at midnight without reading it again", () => {
    vi.useFakeTimers({ toFake: ["Date"] });
    vi.setSystemTime(new Date("2026-10-03T21:59:30Z"));
    const sent = serving({});
    const stopped: StepRow = {
      ...RUNNING_CODE,
      state: "held_back",
      where: {
        kind: "held_back",
        reason: "entry_stopped",
        stopped: {
          what: "entry",
          by: { userId: "reader", displayName: "Avo" },
          at: "2026-10-03T21:59:00Z",
        },
      },
    };
    const [where] = whereCells({ ...READING, steps: [stopped] });
    expect(where).toHaveTextContent("11:59 PM today");

    vi.setSystemTime(new Date("2026-10-03T22:00:00Z"));
    act(() => document.dispatchEvent(new Event("visibilitychange")));

    expect(where).toHaveTextContent("11:59 PM yesterday");
    expect(requestsTo(sent)).toEqual([]);
  });
  it("draws where each step is first as its badge, an icon and words in the state's colour", () => {
    const [running, notStarted] = whereCells();

    const badges = [running!, notStarted!].map(
      (cell) => cell.firstElementChild!,
    );
    const icons = badges.map((badge) => badge.firstElementChild!);

    expect(badges.map((badge) => badge.textContent)).toEqual([
      "Running",
      "Not started",
    ]);
    expect(badges.map((badge) => getComputedStyle(badge).color)).toEqual([
      resolved(RUNNING),
      resolved(SUPPORTING_TEXT),
    ]);
    expect(icons.map((icon) => icon.getAttribute("data-testid"))).toEqual([
      "AutorenewIcon",
      "RadioButtonUncheckedIcon",
    ]);
    expect(icons.map((icon) => icon.getAttribute("aria-hidden"))).toEqual([
      "true",
      "true",
    ]);
    expect(icons.map((icon) => getComputedStyle(icon).color)).toEqual([
      resolved(RUNNING),
      resolved(SUPPORTING_TEXT),
    ]);
  });

  it("says why on quiet lines of their own under the badge, in none of the state's colour", () => {
    const [running, notStarted] = whereCells();

    const why = within(running!).getByText("Its code is running.");

    expect(why.tagName).toBe("P");
    expect(why.previousElementSibling).toBe(running!.firstElementChild);
    expect(getComputedStyle(why).color).toBe(resolved(SUPPORTING_TEXT));
    expect(running!.firstElementChild!.textContent).toBe("Running");
    expect(notStarted!.children).toHaveLength(1);
  });

  it("names each step by a link to its page with the kind it runs beside it, no part of the link", () => {
    whereCells();

    const [running, notStarted] = screen
      .getAllByRole("rowheader")
      .map((cell) => cell.firstElementChild as HTMLElement);
    const link = within(running!).getByRole("link");

    expect(running!.textContent).toBe("file itCode step");
    expect(link.nextElementSibling?.textContent).toBe("Code step");
    expect(link).toHaveAccessibleName("file it");
    expect(getComputedStyle(running!).display).toBe("flex");
    expect(getComputedStyle(running!).alignItems).toBe("center");
    expect(notStarted!.textContent).toBe("tell themCode step");
  });
});

afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
});
