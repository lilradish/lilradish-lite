import Box from "@mui/material/Box";
import { buttonClasses } from "@mui/material/Button";
import { ThemeProvider } from "@mui/material/styles";
import { act, render, screen, within } from "@testing-library/react";
import { userEvent } from "@testing-library/user-event";
import { createRef, useImperativeHandle, useState, type Ref } from "react";
import { RouterProvider, createMemoryRouter } from "react-router";
import { afterEach, describe, expect, it, vi } from "vitest";

import type {
  RunSteps,
  StepRow,
} from "../../../api/groups/{groupId}/runs/{runId}/steps";
import { StandingProvider } from "../../../app/standing/StandingContext";
import { isolatedInText } from "../../../lib/direction/isolated";
import { BLOCK_SX } from "../../../lib/layout/parts";
import { theme } from "../../../lib/theme/theme";
import { drawnOf } from "../../../testutil/depths";
import { resolved } from "../../../testutil/grounds";
import { answered } from "../../../testutil/standingRead";
import { requestsTo, serving } from "../../../testutil/answering";
import { deferred } from "../../../testutil/deferred";
import { RunReadAccess } from "../RunReadAccess";
import { StepMessage } from "./StepMessage";

const GROUP = "00000003-0000-4000-8000-000000000e41";

const RUN_ID = "00000008-0000-4000-8000-000000000e41";

const VERSION = "00000007-0000-4000-8000-000000000e41";
const SEARCH_COST = {
  callsAModel: true,
  sent: "10",
  cameBack: "1",
  spent: "11",
  cameBackUnknown: false,
};

/** Colours as `theme.ts` writes them, copied by hand so a path nothing resolves shows. */
const RUNNING = "#01579b";

const QUESTION = "#00695c";

const WAITING_COLOUR = "#9a4f00";

const SUMMARISING: StepRow = {
  stepId: "00000009-0000-4000-8000-000000000e41",
  order: 1,
  name: "summarise",
  runs: { kind: "question", name: "Summarise" },
  state: "running",
  takesFrom: [],
  cost: {},
  acts: [],
  withheld: [],
};

const READING: RunSteps = {
  run: {
    runId: RUN_ID,
    number: 7,
    versionId: VERSION,
    state: "running",
    progress: { done: 0, of: 1 },
  },
  declarations: new Map(),
  gaveBack: { declares: VERSION },
  steps: [SUMMARISING],
};

const WAITING: StepRow = {
  ...SUMMARISING,
  state: "waiting",
  where: {
    kind: "waiting_on_review",
    number: 1,
    values: [{ field: "summary" }],
    since: "2026-09-24T08:05:00Z",
    waitsOn: "review_at_gate",
  },
  tries: { current: 1, declared: 2, beyond: false },
  producer: { kind: "person" },
  gaveBack: [
    { field: "summary", value: "A kettle leaks.", now: "waiting_on_review" },
  ],
};

const READER = "000e41";

const readAgain = createRef<(step: StepRow) => void>();

function readAgainAs(step: StepRow): void {
  readAgain.current!(step);
}

function KeptUp({
  first,
  ref,
  handTries,
}: {
  readonly first: StepRow;
  readonly ref: Ref<(step: StepRow) => void>;
  readonly handTries?: ReadonlySet<number>;
}) {
  const [step, setStep] = useState(first);
  useImperativeHandle(ref, () => setStep, []);
  return (
    <ol>
      <StepMessage
        groupId={GROUP}
        reading={READING}
        step={step}
        handTries={handTries}
        onShown={vi.fn()}
      />
    </ol>
  );
}

async function drawn(
  step: StepRow = SUMMARISING,
  reader?: string,
  handTries?: ReadonlySet<number>,
) {
  const router = createMemoryRouter(
    [
      {
        path: "/",
        element: <KeptUp first={step} ref={readAgain} handTries={handTries} />,
      },
    ],
    { initialEntries: ["/"] },
  );
  render(
    <ThemeProvider theme={theme}>
      <StandingProvider read={answered([], [], reader)}>
        <RunReadAccess>
          <RouterProvider router={router} />
        </RunReadAccess>
      </StandingProvider>
    </ThemeProvider>,
  );
  return screen.findByRole("heading", { level: 2 });
}

function drawnAsBlock() {
  const probe = render(
    <ThemeProvider theme={theme}>
      <Box sx={BLOCK_SX} />
    </ThemeProvider>,
  );
  const drawn = drawnOf(probe.container.firstElementChild!);
  probe.unmount();
  return drawn;
}

afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
});

describe("StepMessage", () => {
  it("changes the account's day at midnight without reading a step or moving its open control", async () => {
    vi.useFakeTimers({ toFake: ["Date"] });
    vi.setSystemTime(new Date("2026-10-03T21:59:30Z"));
    const sent = serving({});
    const step: StepRow = {
      ...WAITING,
      where: { ...WAITING.where!, since: "2026-10-03T21:59:00Z" },
    };
    const title = await drawn(step);
    const message = title.closest("li")!;
    const control = within(message).getByRole("button", { name: "Waiting" });
    await act(async () => control.click());
    control.focus();
    expect(message).toHaveTextContent("11:59 PM today");

    vi.setSystemTime(new Date("2026-10-03T22:00:00Z"));
    act(() => document.dispatchEvent(new Event("visibilitychange")));

    expect(message).toHaveTextContent("11:59 PM yesterday");
    expect(document.activeElement).toBe(control);
    expect(requestsTo(sent)).toEqual([]);
  });

  it("keeps a reviewer's refusal and literal words beside nonempty values on an earlier machine try", async () => {
    const step: StepRow = {
      ...SUMMARISING,
      state: "done",
      producer: { kind: "model", model: "small" },
      tries: { current: 2, declared: 2, beyond: false },
    };
    const path = `/api/groups/${GROUP}/runs/${RUN_ID}/steps/${step.stepId}`;
    const sent = serving({
      [`GET ${path}`]: [
        [
          JSON.stringify({
            run: READING.run,
            declarations: {},
            step: { ...step, cost: SEARCH_COST },
            triesMade: [
              {
                number: 1,
                beyond: false,
                producedBy: { kind: "model", model: "small" },
                values: [
                  {
                    field: "summary",
                    value: "Demo Sample answer",
                    now: "refused",
                  },
                ],
                unreadableResponse: {
                  text: '<a href="/outside">Demo Sample service</a>',
                  altered: true,
                  truncated: false,
                },
                review: {
                  asked: true,
                  by: { kind: "model", model: "judge" },
                  didNotFit: "model_declined",
                  declined: {
                    words: {
                      text: "I cannot approve Demo Sample.",
                      altered: false,
                      truncated: false,
                    },
                  },
                  unreadableResponse: {
                    text: "Demo Sample review service",
                    altered: false,
                    truncated: true,
                  },
                },
                ended: "refused_on_review",
                cost: SEARCH_COST,
              },
              {
                number: 2,
                beyond: false,
                producedBy: { kind: "model", model: "small" },
                values: [],
                review: { asked: false },
                ended: "stands",
                cost: SEARCH_COST,
              },
            ],
          }),
          200,
        ],
      ],
    });
    const title = await drawn(step);
    const message = title.closest("li")!;

    expect(message).not.toHaveTextContent("I cannot approve Demo Sample.");
    expect(
      requestsTo(sent).filter((request) => request === `GET ${path}`),
    ).toHaveLength(0);
    await userEvent.click(
      within(message).getByRole("button", { name: "Earlier try" }),
    );

    expect(
      await within(message).findByText("I cannot approve Demo Sample."),
    ).toBeVisible();
    expect(message).toHaveTextContent("Demo Sample answer");
    expect(
      within(message).getAllByText("What the model service sent back"),
    ).toHaveLength(2);
    expect(message).toHaveTextContent(
      '<a href="/outside">Demo Sample service</a>',
    );
    expect(message).toHaveTextContent("Demo Sample review service");
    expect(
      within(message).queryByRole("link", { name: "Demo Sample service" }),
    ).toBeNull();
    expect(message).toHaveTextContent(
      "The model declined to answer, so its answer was not taken, and this try is spent.",
    );
    expect(
      requestsTo(sent).filter((request) => request === `GET ${path}`),
    ).toHaveLength(1);
  });

  it("names a failed review and a shortened member on an earlier code try", async () => {
    const step: StepRow = {
      ...SUMMARISING,
      runs: { kind: "code_step", codeStep: "file_claim" },
      producer: { kind: "code" },
      state: "done",
      tries: { current: 2, declared: 2, beyond: false },
    };
    const path = `/api/groups/${GROUP}/runs/${RUN_ID}/steps/${step.stepId}`;
    const sent = serving({
      [`GET ${path}`]: [
        [
          JSON.stringify({
            run: READING.run,
            declarations: {},
            step: { ...step, cost: { callsAModel: false } },
            triesMade: [
              {
                number: 1,
                beyond: false,
                producedBy: { kind: "code" },
                values: [],
                review: {
                  asked: true,
                  by: { kind: "model", model: "small" },
                  wentWrong: true,
                  lost: "errored",
                },
                ended: "errored",
                erroredFor: {
                  reason: "not_declared",
                  member: "Demo Sample",
                  memberCut: true,
                },
                cost: { callsAModel: false },
              },
              {
                number: 2,
                beyond: false,
                producedBy: { kind: "code" },
                values: [],
                review: { asked: false },
                ended: "stands",
                cost: { callsAModel: false },
              },
            ],
          }),
          200,
        ],
      ],
    });
    const title = await drawn(step);
    const message = title.closest("li")!;

    await userEvent.click(
      within(message).getByRole("button", { name: "Earlier try" }),
    );
    expect(
      await within(message).findByText("whose review went wrong"),
    ).toBeVisible();
    expect(message).toHaveTextContent("small, whose review went wrong");
    expect(message.querySelector("bdi")).toHaveTextContent("small");
    expect(message).toHaveTextContent(
      `a member named ${isolatedInText("Demo Sample")} (its name cut short here)`,
    );
    expect(
      [...message.querySelectorAll("span")].some(
        (part) => part.textContent === "(its name cut short here)",
      ),
    ).toBe(true);
    expect(
      requestsTo(sent).filter((request) => request === `GET ${path}`),
    ).toHaveLength(1);
    expect(
      within(message).getByRole("button", { name: "Earlier try" }),
    ).toBeDisabled();
  });

  describe.each([
    [
      "source_no_longer_matches",
      "failedSourceNoLongerMatches",
      `What Demo Sample source gave back as ${isolatedInText("info.answer")} no longer matches what this step reads from it, as that code step is declared now, so nothing was sent.`,
    ],
    [
      "source_list_not_here",
      "failedSourceListNotHere",
      `The terms of ${isolatedInText("info.answer")}, which Demo Sample source gave back, come from a reference list version this group does not hold in service, so nothing was sent.`,
    ],
  ] as const)("%s", (reason, member, sentence) => {
    it.each([
      ["question", { kind: "question", name: "Summarise" }, RUN_ID],
      [
        "question",
        { kind: "question", name: "Summarise" },
        "00000008-0000-4000-8000-000000000e42",
      ],
      ["search", { kind: "search", searchWay: "web" }, RUN_ID],
      [
        "search",
        { kind: "search", searchWay: "web" },
        "00000008-0000-4000-8000-000000000e42",
      ],
    ] as const)(
      "says which source field a failed %s no longer reads and draws only its offered sending act",
      async (_kind, runs, sourceRun) => {
        const sourceStep = "00000009-0000-4000-8000-000000000e42";
        const sourceNumber = sourceRun === RUN_ID ? 7 : 19;
        const failed: StepRow = {
          ...SUMMARISING,
          runs,
          state: "failed",
          acts: ["try_sending"],
          where: {
            kind: "failed",
            reason,
            waitsOn: "starter",
            [member]: {
              source: {
                runId: sourceRun,
                runNumber: sourceNumber,
                stepId: sourceStep,
                name: "Demo Sample source",
                codeStep: "tally",
              },
              field: "info.answer",
            },
          },
        };
        const title = await drawn(failed);
        const message = title.closest("li")!;

        expect(
          within(message).getByRole("button", { name: "Try sending" }),
        ).toBeEnabled();
        expect(
          within(message).queryByRole("button", { name: "Ask again" }),
        ).not.toBeInTheDocument();
        expect(
          within(message).queryByRole("button", { name: "Answer it here" }),
        ).not.toBeInTheDocument();
        await userEvent.click(
          within(message).getByRole("button", { name: "Failed" }),
        );

        const source = within(message).getByRole("link", {
          name: "Demo Sample source",
        });
        expect(source).toHaveAttribute(
          "href",
          `/groups/${GROUP}/work/${sourceRun}/steps/${sourceStep}`,
        );
        expect(source).toHaveAttribute("title", `Run ${sourceNumber}`);
        expect(source.querySelector("bdi")).toHaveTextContent(
          "Demo Sample source",
        );
        expect(message).toHaveTextContent(sentence);
        expect(message).not.toHaveTextContent("goes on by itself");
        expect(message).not.toHaveTextContent(
          "does not include the code this step runs",
        );
        expect(source).not.toHaveAttribute(
          "href",
          `/groups/${GROUP}/work/${RUN_ID}/steps/${failed.stepId}`,
        );
      },
    );
  });

  it.each([
    ["question", { kind: "question", name: "Summarise" }],
    ["search", { kind: "search", searchWay: "web" }],
  ] as const)(
    "links the actual source of a %s held on missing source code and removes that account when it is released",
    async (_kind, runs) => {
      const sourceRun = "00000008-0000-4000-8000-000000000e42";
      const sourceStep = "00000009-0000-4000-8000-000000000e42";
      const held: StepRow = {
        ...SUMMARISING,
        runs,
        state: "held_back",
        where: {
          kind: "held_back",
          reason: "code_step_not_held",
          waitsOn: "starter",
          source: {
            runId: sourceRun,
            runNumber: 19,
            stepId: sourceStep,
            name: "Demo Sample source",
            codeStep: "tally",
          },
        },
      };
      const title = await drawn(held);
      const message = title.closest("li")!;
      expect(
        within(message).queryByRole("link", { name: "Demo Sample source" }),
      ).not.toBeInTheDocument();

      await userEvent.click(
        within(message).getByRole("button", { name: "Held back" }),
      );

      const source = within(message).getByRole("link", {
        name: "Demo Sample source",
      });
      expect(source).toHaveAttribute(
        "href",
        `/groups/${GROUP}/work/${sourceRun}/steps/${sourceStep}`,
      );
      expect(source).toHaveAttribute("title", "Run 19");
      expect(source.querySelector("bdi")).toHaveTextContent(
        "Demo Sample source",
      );
      expect(message).toHaveTextContent(
        "A value it reads came from Demo Sample source, whose code this system, as it runs now, does not include, so it cannot be sent; it goes on by itself once a release holds that code step again.",
      );
      expect(message).not.toHaveTextContent(
        "does not include the code this step runs",
      );
      expect(message).not.toHaveTextContent(
        "Somebody who may start a run can try sending it.",
      );

      await act(async () => readAgainAs({ ...SUMMARISING, runs }));

      expect(
        within(message).queryByRole("link", { name: "Demo Sample source" }),
      ).not.toBeInTheDocument();
      expect(message).not.toHaveTextContent("A value it reads came from");
    },
  );

  it("refreshes an automatically opened search record when its waiting try changes and drops the old record during the new read", async () => {
    const first: StepRow = {
      ...WAITING,
      runs: { kind: "search", searchWay: "web" },
      producer: { kind: "model", model: "small" },
      acts: ["review"],
    };
    const next: StepRow = {
      ...first,
      where: { ...first.where!, number: 2 },
      tries: { current: 2, declared: 2, beyond: false },
    };
    const path = `/api/groups/${GROUP}/runs/${RUN_ID}/steps/${first.stepId}`;
    const detail = (step: StepRow, question: string) =>
      JSON.stringify({
        run: READING.run,
        declarations: {},
        step: { ...step, cost: SEARCH_COST },
        triesMade: [
          {
            number: step.where!.number,
            beyond: false,
            producedBy: first.producer,
            values: [],
            review: { asked: true },
            ended: "waiting",
            cost: SEARCH_COST,
            search: { question },
          },
        ],
      });
    const later = deferred<readonly [string, number]>();
    const sent = serving({
      [`GET ${path}`]: [
        [detail(first, "First search record"), 200],
        later.promise,
      ],
    });

    await drawn(first);
    expect(await screen.findByText("First search record")).toBeInTheDocument();
    act(() => readAgainAs(next));
    expect(screen.queryByText("First search record")).toBeNull();
    expect(screen.queryByText("Next search record")).toBeNull();
    await act(async () =>
      later.settle([detail(next, "Next search record"), 200]),
    );
    expect(await screen.findByText("Next search record")).toBeInTheDocument();
    expect(screen.queryByText("First search record")).toBeNull();
    expect(
      requestsTo(sent).filter((each) => each === `GET ${path}`),
    ).toHaveLength(2);
  });
  it("reads a search record automatically for the actual waiting try and does not draw it twice when its detail opens", async () => {
    const searchStep: StepRow = {
      ...WAITING,
      runs: { kind: "search", searchWay: "web" },
      producer: { kind: "model", model: "small" },
      acts: ["review"],
    };
    const path = `/api/groups/${GROUP}/runs/${RUN_ID}/steps/${searchStep.stepId}`;
    const sent = serving({
      [`GET ${path}`]: [
        [
          JSON.stringify({
            run: READING.run,
            declarations: {},
            step: { ...searchStep, cost: SEARCH_COST },
            triesMade: [
              {
                number: 1,
                beyond: false,
                producedBy: searchStep.producer,
                values: [],
                review: { asked: true },
                ended: "waiting",
                cost: SEARCH_COST,
                search: {
                  question: "Demo Sample search",
                  searches: [{ found: "A useful record" }],
                },
              },
            ],
          }),
          200,
        ],
      ],
    });

    await drawn(searchStep);
    expect(await screen.findByText("Demo Sample search")).toBeInTheDocument();
    expect(screen.getByText("A useful record")).toBeInTheDocument();
    expect(
      requestsTo(sent).filter((each) => each === `GET ${path}`),
    ).toHaveLength(1);
    await userEvent.click(screen.getByRole("button", { name: "Details" }));
    expect(screen.getAllByText("Demo Sample search")).toHaveLength(1);
    expect(screen.getAllByText("A useful record")).toHaveLength(1);
  });

  it("does not put an earlier search snapshot beside a newer waiting try", async () => {
    const searchStep: StepRow = {
      ...WAITING,
      runs: { kind: "search", searchWay: "web" },
      producer: { kind: "model", model: "small" },
      where: { ...WAITING.where!, number: 2 },
      tries: { current: 2, declared: 2, beyond: false },
      acts: ["review"],
    };
    const path = `/api/groups/${GROUP}/runs/${RUN_ID}/steps/${searchStep.stepId}`;
    serving({
      [`GET ${path}`]: [
        [
          JSON.stringify({
            run: READING.run,
            declarations: {},
            step: {
              ...searchStep,
              where: { ...searchStep.where, number: 1 },
              cost: SEARCH_COST,
            },
            triesMade: [
              {
                number: 1,
                beyond: false,
                producedBy: searchStep.producer,
                values: [],
                review: { asked: true },
                ended: "waiting",
                cost: SEARCH_COST,
                search: { question: "Earlier question" },
              },
            ],
          }),
          200,
        ],
      ],
    });

    await drawn(searchStep);
    await userEvent.click(screen.getByRole("button", { name: "Details" }));
    expect(
      await screen.findByRole("link", { name: "Open this step's page" }),
    ).toBeInTheDocument();
    expect(
      screen.getByRole("heading", { name: "Your review" }),
    ).toBeInTheDocument();
    expect(screen.queryByText("Earlier question")).toBeNull();
    expect(screen.queryByText("The question it searched with")).toBeNull();
  });

  it("reads a held search question only when its account opens and removes it once the hold is released", async () => {
    const searchStep: StepRow = {
      ...SUMMARISING,
      runs: { kind: "search", searchWay: "web" },
      state: "held_back",
      where: { kind: "held_back", reason: "too_long", waitsOn: "answer_step" },
    };
    const path = `/api/groups/${GROUP}/runs/${RUN_ID}/steps/${searchStep.stepId}`;
    const sent = serving({
      [`GET ${path}`]: [
        [
          JSON.stringify({
            run: READING.run,
            declarations: {},
            step: {
              ...searchStep,
              where: {
                ...searchStep.where,
                searchQuestion: "Held Demo Sample",
              },
              cost: SEARCH_COST,
            },
            triesMade: [],
          }),
          200,
        ],
      ],
    });

    const title = await drawn(searchStep);
    expect(
      requestsTo(sent).filter((each) => each === `GET ${path}`),
    ).toHaveLength(0);
    expect(screen.queryByText("Held Demo Sample")).toBeNull();
    await userEvent.click(
      within(title.closest("li")!).getByRole("button", { name: /Held back/ }),
    );
    expect(await screen.findByText("Held Demo Sample")).toBeInTheDocument();
    expect(
      screen.getByText("The question it would have searched with"),
    ).toBeInTheDocument();
    expect(
      requestsTo(sent).filter((each) => each === `GET ${path}`),
    ).toHaveLength(1);
    act(() =>
      readAgainAs({
        ...searchStep,
        state: "running",
        where: { kind: "running", on: "call" },
      }),
    );
    expect(screen.queryByText("Held Demo Sample")).toBeNull();
    expect(
      screen.queryByText("The question it would have searched with"),
    ).toBeNull();
  });
  it("reads only the changed opened detail after the run rereads its steps", async () => {
    const second: StepRow = {
      ...SUMMARISING,
      stepId: "00000009-0000-4000-8000-000000000e42",
      order: 2,
      name: "archive",
      runs: { kind: "question", name: "Archive" },
      tries: { current: 1, declared: 1, beyond: false },
    };
    const first: StepRow = {
      ...SUMMARISING,
      state: "done",
      tries: { current: 1, declared: 1, beyond: false },
    };
    const detail = (step: StepRow, why: string) =>
      JSON.stringify({
        run: READING.run,
        declarations: {},
        step: { ...step, cost: { callsAModel: false } },
        triesMade: [
          {
            number: 1,
            beyond: false,
            producedBy: { kind: "person" },
            why,
            values: [],
            review: { asked: false },
            ended: "done",
            cost: { callsAModel: false },
          },
        ],
      });
    const firstPath = `/api/groups/${GROUP}/runs/${RUN_ID}/steps/${first.stepId}`;
    const secondPath = `/api/groups/${GROUP}/runs/${RUN_ID}/steps/${second.stepId}`;
    const sent = serving({
      [`GET ${firstPath}`]: [[detail(first, "First detail"), 200]],
      [`GET ${secondPath}`]: [
        ['{"code":"BROKEN_HERE"}', 503],
        [detail(second, "Earlier detail"), 200],
        [detail({ ...second, state: "done" }, "Current detail"), 200],
      ],
    });
    const changeRows = createRef<(rows: StepRow[]) => void>();
    function TwoMessages() {
      const [rows, setRows] = useState([first, second]);
      useImperativeHandle(changeRows, () => setRows, []);
      return (
        <ol>
          {rows.map((row) => (
            <StepMessage
              key={row.stepId}
              groupId={GROUP}
              reading={{ ...READING, steps: rows, rereadAfterSeconds: 1 }}
              step={row}
              onShown={vi.fn()}
            />
          ))}
        </ol>
      );
    }
    const router = createMemoryRouter(
      [{ path: "/", element: <TwoMessages /> }],
      { initialEntries: ["/"] },
    );
    render(
      <ThemeProvider theme={theme}>
        <StandingProvider read={answered([], [])}>
          <RunReadAccess>
            <RouterProvider router={router} />
          </RunReadAccess>
        </StandingProvider>
      </ThemeProvider>,
    );
    const details = screen.getAllByRole("button", { name: "Details" });
    await userEvent.click(details[0]!);
    await userEvent.click(details[1]!);
    expect(await screen.findByText("First detail")).toBeVisible();
    expect(await screen.findByRole("alert")).toBeVisible();
    const readsOf = (path: string) =>
      requestsTo(sent).filter((request) => request === `GET ${path}`).length;
    expect(readsOf(firstPath)).toBe(1);
    expect(readsOf(secondPath)).toBe(1);

    act(() => changeRows.current!([{ ...first }, { ...second }]));
    expect(await screen.findByText("Earlier detail")).toBeVisible();
    expect(readsOf(firstPath)).toBe(1);
    expect(readsOf(secondPath)).toBe(2);

    vi.useFakeTimers({ toFake: ["setTimeout", "clearTimeout"] });
    act(() => changeRows.current!([{ ...first }, { ...second }]));
    await act(() => vi.advanceTimersByTimeAsync(5_000));
    expect(readsOf(firstPath)).toBe(1);
    expect(readsOf(secondPath)).toBe(2);

    act(() =>
      changeRows.current!([{ ...first }, { ...second, state: "done" }]),
    );
    await act(async () => Promise.resolve());
    expect(readsOf(firstPath)).toBe(1);
    expect(readsOf(secondPath)).toBe(3);
    expect(screen.getByText("Current detail")).toBeVisible();
    expect(screen.queryByText("Earlier detail")).toBeNull();

    await act(async () => details[1]!.click());
    act(() =>
      changeRows.current!([{ ...first }, { ...second, state: "failed" }]),
    );
    await act(() => vi.advanceTimersByTimeAsync(5_000));
    expect(readsOf(firstPath)).toBe(1);
    expect(readsOf(secondPath)).toBe(3);
  });

  it("shows the newer detail when its step changes before the first read answers", async () => {
    const first: StepRow = {
      ...SUMMARISING,
      tries: { current: 1, declared: 1, beyond: false },
    };
    const changed: StepRow = { ...first, state: "done" };
    const detail = (step: StepRow, why: string) =>
      JSON.stringify({
        run: READING.run,
        declarations: {},
        step: { ...step, cost: { callsAModel: false } },
        triesMade: [
          {
            number: 1,
            beyond: false,
            producedBy: { kind: "person" },
            why,
            values: [],
            review: { asked: false },
            ended: "done",
            cost: { callsAModel: false },
          },
        ],
      });
    let settleOld!: (reply: readonly [string, number]) => void;
    const old = new Promise<readonly [string, number]>((resolve) => {
      settleOld = resolve;
    });
    const path = `/api/groups/${GROUP}/runs/${RUN_ID}/steps/${first.stepId}`;
    const sent = serving({
      [`GET ${path}`]: [old, [detail(changed, "New detail"), 200]],
    });
    const title = await drawn(first);
    await userEvent.click(
      within(title.closest("li")!).getByRole("button", { name: "Details" }),
    );
    expect(
      requestsTo(sent).filter((request) => request === `GET ${path}`),
    ).toHaveLength(1);

    act(() => readAgainAs(changed));
    expect(await screen.findByText("New detail")).toBeVisible();
    expect(
      requestsTo(sent).filter((request) => request === `GET ${path}`),
    ).toHaveLength(2);

    await act(async () => settleOld([detail(first, "Old detail"), 200]));
    expect(screen.getByText("New detail")).toBeVisible();
    expect(screen.queryByText("Old detail")).toBeNull();
  });

  it("leaves a person's newest try to its separate act message", async () => {
    const step: StepRow = {
      ...SUMMARISING,
      state: "done",
      tries: { current: 1, declared: 2, beyond: false },
      gaveBack: [{ field: "answer", value: "Demo Sample", now: "standing" }],
    };

    const title = await drawn(step, undefined, new Set([1]));
    const message = title.closest("li")!;

    expect(message).toHaveAccessibleName(isolatedInText("Summarise"));
    expect(message).not.toHaveTextContent("Demo Sample");
    expect(within(message).queryByText("Try 1 of the 2 it allows")).toBeNull();
  });
  it("sets the kind it runs apart on the title's line, and out of the title's name", async () => {
    const title = await drawn();

    const tag = within(title.parentElement!).getByText("Question");

    expect(title).toHaveAccessibleName(isolatedInText("Summarise"));
    expect(title).not.toContainElement(tag);
    expect(title.parentElement).toContainElement(tag);
  });

  it("says what the step cost on its head's trailing side, and nowhere under it", async () => {
    const title = await drawn();

    const head = title.parentElement!;

    expect(head.lastElementChild?.textContent).toBe(
      "Nothing: it calls no model.",
    );
    expect(
      title.closest("li")!.textContent!.split("Nothing: it calls no model."),
    ).toHaveLength(2);
    expect(title).toHaveAccessibleName(isolatedInText("Summarise"));
  });

  it("says where the step is on a line of its own under the title, and nowhere on the title's line", async () => {
    const title = await drawn();

    const where = screen.getByText("Running").closest("p")!;

    expect(where).toBe(title.parentElement!.nextElementSibling);
    expect(title.parentElement).not.toHaveTextContent("Running");
    expect(title).toHaveAccessibleName(isolatedInText("Summarise"));
  });

  it("draws where the step is as its badge: an icon and words in the state's colour, the title in none of it", async () => {
    const title = await drawn();

    const where = title.parentElement!.nextElementSibling!;
    const badge = where.firstElementChild!;
    const icon = badge.firstElementChild!;

    expect(where.textContent).toBe("Running");
    expect(getComputedStyle(badge).color).toBe(resolved(RUNNING));
    expect(icon.getAttribute("data-testid")).toBe("AutorenewIcon");
    expect(icon.getAttribute("aria-hidden")).toBe("true");
    expect(getComputedStyle(icon).color).toBe(resolved(RUNNING));
    expect(getComputedStyle(title).color).not.toBe(resolved(RUNNING));
  });

  it("rules its message down the leading edge in the colour of the kind the step runs", async () => {
    const message = (await drawn()).closest("li")!;

    expect(drawnOf(message)).toEqual({
      ...drawnAsBlock(),
      leadingEdge: "3px solid",
      leadingEdgeColour: resolved(QUESTION),
    });
  });

  it("leaves as much room inside its frame below the last block drawn as above its title, Details shut", async () => {
    const message = (await drawn()).closest("li")!;

    const drawnBlocks = [...message.children].filter(
      (child) => !child.hasAttribute("hidden"),
    );
    const details = message.lastElementChild!;
    const inside = (side: "Top" | "Bottom", block: Element) =>
      parseFloat(getComputedStyle(message)[`padding${side}`]) +
      parseFloat(getComputedStyle(block)[`margin${side}`]);

    expect(details.hasAttribute("hidden")).toBe(true);
    expect(details).not.toBeVisible();
    expect(inside("Bottom", drawnBlocks.at(-1)!)).toBe(
      inside("Top", drawnBlocks[0]!),
    );
    expect(inside("Top", drawnBlocks[0]!)).toBeGreaterThan(0);
    expect(
      parseFloat(getComputedStyle(drawnBlocks.at(-1)!).marginBottom) +
        parseFloat(getComputedStyle(details).marginTop),
    ).toBeGreaterThan(0);
  });

  it("frames the message of a step running a kind this build has no words for as any other message, ruled in no kind's colour", async () => {
    const message = (
      await drawn({
        ...SUMMARISING,
        runs: { kind: "unmapped_kind", name: "Find it" },
      })
    ).closest("li")!;

    expect(drawnOf(message)).toEqual(drawnAsBlock());
    expect(message).toHaveTextContent("Not something this page can say yet.");
    expect(message).not.toHaveTextContent(/unmapped_kind/i);
  });
  it("shuts the account of where the step is in its one word, drawn as a control saying whether it is open and whose it is, and opens it there", async () => {
    const title = await drawn(WAITING);
    const message = title.closest("li")!;
    const control = within(message).getByRole("button", { name: "Waiting" });

    expect(control).toHaveAttribute("aria-expanded", "false");
    expect(control).toHaveAccessibleDescription(isolatedInText("Summarise"));
    expect(
      document.getElementById(control.getAttribute("aria-controls")!),
    ).toBeEmptyDOMElement();
    expect(message).not.toHaveTextContent(/Since /);
    expect(message).not.toHaveTextContent("Try 1 of the 2 it allows");
    expect(message).toHaveTextContent("A kettle leaks.");

    await userEvent.click(control);

    const account = document.getElementById(
      control.getAttribute("aria-controls")!,
    )!;
    expect(control).toHaveAttribute("aria-expanded", "true");
    expect(account).toBeVisible();
    expect(account).toHaveTextContent(/Since /);
    expect(account).toHaveTextContent("Try 1 of the 2 it allows");
    expect(account).not.toHaveTextContent("A kettle leaks.");

    await userEvent.click(control);

    expect(control).toHaveAttribute("aria-expanded", "false");
    expect(account).toBeEmptyDOMElement();
  });

  it("opens and shuts the account from the keyboard", async () => {
    const title = await drawn(WAITING);
    const control = within(title.closest("li")!).getByRole("button", {
      name: "Waiting",
    });

    control.focus();
    await userEvent.keyboard("{Enter}");

    expect(control).toHaveAttribute("aria-expanded", "true");

    await userEvent.keyboard(" ");

    expect(control).toHaveAttribute("aria-expanded", "false");
    expect(document.activeElement).toBe(control);
  });

  it("draws where a step is with no control where it has no account to give", async () => {
    const title = await drawn();

    const where = title.parentElement!.nextElementSibling as HTMLElement;

    expect(where).toHaveTextContent("Running");
    expect(within(where).queryByRole("button")).toBeNull();
    expect(within(title.closest("li")!).getAllByRole("button")).toEqual([
      within(title.closest("li")!).getByRole("button", { name: "Details" }),
    ]);
  });

  it("sets the control the state is drawn as in the words it stands in, in the state's colour and not a button's own", async () => {
    const title = await drawn(WAITING);
    const control = within(title.closest("li")!).getByRole("button", {
      name: "Waiting",
    });

    expect(control).toHaveClass(buttonClasses.text);
    expect(control).toHaveClass(buttonClasses.colorInherit);
    expect(control).not.toHaveClass(buttonClasses.outlined);
    expect(control).not.toHaveClass(buttonClasses.colorPrimary);
    expect(getComputedStyle(control.firstElementChild!).color).toBe(
      resolved(WAITING_COLOUR),
    );
  });

  it("draws a step's message on the leading side, whoever reads it", async () => {
    const message = (await drawn(WAITING, READER)).closest("li")!;

    expect(getComputedStyle(message).marginInlineStart).not.toBe("auto");
    expect(drawnOf(message)).toEqual({
      ...drawnAsBlock(),
      leadingEdge: "3px solid",
      leadingEdgeColour: resolved(QUESTION),
    });
  });
  it("gives the keyboard to its message where a read again leaves no account to give, taking the control it was on, and draws the next account shut", async () => {
    const title = await drawn(WAITING);
    const message = title.closest("li")!;
    const control = within(message).getByRole("button", { name: "Waiting" });
    await userEvent.click(control);
    expect(document.activeElement).toBe(control);

    act(() => readAgainAs({ ...SUMMARISING, state: "done" }));

    expect(title.closest("li")).toBe(message);
    expect(within(message).queryByRole("button", { name: "Done" })).toBeNull();
    expect(document.activeElement).toBe(message);
    expect(document.activeElement).not.toBe(document.body);

    act(() => readAgainAs(WAITING));

    expect(
      within(message).getByRole("button", { name: "Waiting" }),
    ).toHaveAttribute("aria-expanded", "false");
    expect(message).not.toHaveTextContent(/Since /);
  });

  it("leaves the keyboard where it is where a read again takes the account away while the keyboard is elsewhere", async () => {
    const title = await drawn(WAITING);
    const message = title.closest("li")!;
    const details = within(message).getByRole("button", { name: "Details" });
    details.focus();

    act(() => readAgainAs({ ...SUMMARISING, state: "done" }));

    expect(document.activeElement).toBe(details);
    expect(document.activeElement).not.toBe(message);
  });

  it("takes the keyboard nowhere where a read again takes the account away while the keyboard is on no control at all", async () => {
    const title = await drawn(WAITING);
    const message = title.closest("li")!;
    expect(document.activeElement).toBe(document.body);

    act(() => readAgainAs({ ...SUMMARISING, state: "done" }));

    expect(document.activeElement).toBe(document.body);
    expect(document.activeElement).not.toBe(message);
  });

  it.each([
    [
      "why it is where it is",
      {
        ...SUMMARISING,
        state: "held_back",
        where: { kind: "held_back", reason: "turned_away", waitsOn: "starter" },
      },
      "The model it names would not take it.",
    ],
    [
      "since when",
      {
        ...SUMMARISING,
        where: { kind: "running", since: "2026-09-24T08:05:00Z" },
      },
      "Since ",
    ],
    [
      "each time it was turned away",
      {
        ...SUMMARISING,
        where: {
          kind: "running",
          turnedAway: [{ at: "2026-09-24T08:04:00Z", sentAgain: false }],
        },
      },
      "Turned away ",
    ],
    [
      "why an act is not the reader's",
      {
        ...SUMMARISING,
        withheld: [{ act: "review", refusal: "ACT_NOT_PERMITTED" }],
      },
      "You may not review it.",
    ],
  ] as const)(
    "draws its state as the control that opens its account where all it has to say is %s, and says it there",
    async (_case, step, said) => {
      const title = await drawn(step as StepRow);
      const message = title.closest("li")!;
      const control = within(message).getAllByRole("button")[0]!;

      expect(control).toHaveAttribute("aria-expanded", "false");
      expect(control).not.toHaveTextContent("Details");
      expect(message).not.toHaveTextContent(said);

      await userEvent.click(control);

      expect(
        document.getElementById(control.getAttribute("aria-controls")!),
      ).toHaveTextContent(said);
    },
  );
  it("accounts for the selected try only when its state is opened", async () => {
    const title = await drawn({
      ...SUMMARISING,
      tries: { current: 1, declared: 2, beyond: false },
    });
    const message = title.closest("li")!;

    const control = within(message).getByRole("button", { name: "Running" });
    expect(message).not.toHaveTextContent("Try 1 of the 2 it allows");
    await userEvent.click(control);
    expect(message).toHaveTextContent("Try 1 of the 2 it allows");
  });
});
