import { ThemeProvider } from "@mui/material/styles";
import { act, render, screen, within } from "@testing-library/react";
import { RouterProvider, createMemoryRouter } from "react-router";
import { afterEach, describe, expect, it, vi } from "vitest";

import type { ReadField } from "../../../api/filling";
import type {
  RunSteps,
  StepRow,
} from "../../../api/groups/{groupId}/runs/{runId}/steps";
import { theme } from "../../../lib/theme/theme";
import { resolved } from "../../../testutil/grounds";
import { requestsTo, serving } from "../../../testutil/answering";
import { inGroup } from "../../../testutil/standingRead";
import { StepsTable } from "./StepsTable";

const CLAIMS = inGroup(
  "00000003-0000-4000-8000-000000000e51",
  "CLAIMS",
  "Claims",
  [],
);

const RUN_ID = "00000008-0000-4000-8000-000000000e51";

const VERSION = "00000007-0000-4000-8000-000000000e51";

/** Colours as `theme.ts` writes them, copied by hand so a path nothing resolves shows. */
const RUNNING = "#1f4f99";

const SUPPORTING_TEXT = "#58666c";
const NOT_STARTED_COLOUR = "#444f54";

const RUNNING_CODE: StepRow = {
  producedByUserId: null,
  producedByKind: null,
  stepId: "00000009-0000-4000-8000-000000000e51",
  order: 1,
  name: "file_it",
  runs: { versionName: null, kind: "code_step", codeStep: "file_it" },
  state: "running",
  where: { kind: "running", on: "code" },
  takesFrom: [],
  cost: {},
  stateActs: new Set([]),
  stateRefusals: [],
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
    stateActs: null,
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

function whereCells(
  reading: RunSteps = READING,
  subRun?: boolean,
): HTMLElement[] {
  const router = createMemoryRouter(
    [
      {
        path: "/",
        element: (
          <StepsTable
            group={CLAIMS}
            reading={{
              ...reading,
              steps: reading.steps.map((step) => ({
                ...step,
                waitsOnStarter:
                  subRun === undefined
                    ? undefined
                    : subRun
                      ? "top_level_run"
                      : "this_run",
              })),
            }}
            labelledBy="steps"
          />
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
    .findIndex((header) => header.textContent === "Status");
  return screen
    .getAllByRole("row")
    .slice(1)
    .map((row) => row.children[column] as HTMLElement);
}

describe("StepsTable", () => {
  it("reads the step's input label and the run input's source label from their own declarations", () => {
    const questionVersion = "00000007-0000-4000-8000-000000000e52";
    const ticket: ReadField = {
      name: "ticket",
      label: "Ticket",
      kind: "text",
      longest: 4000,
      mustBeGiven: true,
    };
    const taking: StepRow = {
      ...NOT_STARTED,
      name: "summarise_it",
      runs: {
        versionName: null,
        kind: "question",
        name: "Summarise",
        versionId: questionVersion,
      },
      takesFrom: [
        { input: "ticket", from: { kind: "run_input", path: "ticket" } },
      ],
    };
    whereCells({
      ...READING,
      declarations: new Map([
        [VERSION, { takes: [ticket], gives: [] }],
        [questionVersion, { takes: [{ ...ticket, label: "Text" }], gives: [] }],
      ]),
      steps: [RUNNING_CODE, taking],
    });

    const row = screen
      .getByRole("link", { name: "summarise it" })
      .closest("tr")!;
    const terms = within(row).getAllByRole("term");
    const sources = within(row).getAllByRole("definition");
    expect(terms).toHaveLength(1);
    expect(sources).toHaveLength(terms.length);
    expect(terms[0]!.textContent).toBe("\u2068Text\u2069");
    expect(sources[0]!.textContent).toBe("\u2068Ticket\u2069 (run input)");
  });

  it("shows display names while keeping step links and source machine paths", () => {
    const labelled: StepRow = {
      ...RUNNING_CODE,
      label: "申請を記録\u202e",
      takesFrom: [
        {
          input: "claim",
          from: {
            kind: "step",
            path: "summary",
            stepId: "source-step",
            name: "summarise_it",
            label: "要約を読む",
            runId: "other-run",
            runNumber: 6,
          },
        },
      ],
    };
    whereCells({ ...READING, steps: [labelled] });

    const link = screen.getByRole("link", { name: /申請を記録/ });
    expect(link).toHaveAttribute(
      "href",
      `/groups/CLAIMS/work/${RUN_ID}/steps/${RUNNING_CODE.stepId}`,
    );
    expect(link.querySelector("bdi")).toHaveTextContent("申請を記録\u202e");
    expect(link).not.toHaveTextContent("file it");
    expect(link).not.toHaveTextContent("file_it");
    expect(within(link.closest("tr")!).getByRole("term")).toHaveTextContent(
      `${String.fromCodePoint(0x2068)}claim${String.fromCodePoint(0x2069)}`,
    );
    expect(
      within(link.closest("tr")!)
        .getAllByRole("definition")
        .map((each) => each.textContent)
        .join(""),
    ).toBe(
      `${String.fromCodePoint(0x2068)}summary${String.fromCodePoint(0x2069)} (output of ${String.fromCodePoint(0x2068)}要約を読む${String.fromCodePoint(0x2069)} in run 6)`,
    );
    expect(link.closest("tr")).not.toHaveTextContent("summarise it");
    expect(labelled.name).toBe("file_it");
    expect(labelled.takesFrom[0]!.from.path).toBe("summary");
  });

  it.each([
    [false, "Waiting for the person who started the run."],
    [true, "Waiting for the person who started the top-level run."],
    [undefined, ""],
  ] as const)(
    "draws the starter wait only for a known run hierarchy of %s",
    (subRun, sentence) => {
      const sent = serving({});
      const [where] = whereCells(
        {
          ...READING,
          steps: [
            {
              ...RUNNING_CODE,
              state: "waiting",
              reviewer: { kind: "model", model: "small" },
              cost: {
                spend: {
                  sent: "0",
                  cameBack: "0",
                  spent: "0",
                  cameBackUnknown: false,
                },
              },
              where: {
                kind: "waiting_on_review",
                number: 1,
                since: "2026-09-24T08:05:00Z",
                review: "turned_away",
                values: [{ field: "summary" }],
                waitsOn: "starter",
              },
            },
          ],
        },
        subRun,
      );

      expect(where!.textContent).toBe(
        `Waiting${String.fromCodePoint(0x2068)}summary${String.fromCodePoint(0x2069)} has not been reviewed yet.Waiting for the model's review, which could not be sent: the model service turned the request away.Someone who can start runs can try sending it.${sentence}`,
      );
      for (const other of [
        "Waiting for the person who started the run.",
        "Waiting for the person who started the top-level run.",
      ].filter((text) => text !== sentence)) {
        expect(where).not.toHaveTextContent(other);
      }
      expect(requestsTo(sent)).toEqual([]);
    },
  );
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
      resolved(NOT_STARTED_COLOUR),
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
      resolved(NOT_STARTED_COLOUR),
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
