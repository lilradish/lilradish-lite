import { ThemeProvider } from "@mui/material/styles";
import { act, render, screen, within } from "@testing-library/react";
import { userEvent } from "@testing-library/user-event";
import { RouterProvider, createMemoryRouter } from "react-router";
import { afterEach, describe, expect, it, vi } from "vitest";

import { StandingProvider } from "../../../app/standing/StandingContext";
import { BESIDE_FROM } from "../../../lib/collection/RowPanel";
import { theme } from "../../../lib/theme/theme";
import { whenText } from "../../../lib/time/When";
import { requestsTo, serving, type Reply } from "../../../testutil/answering";
import { deferred } from "../../../testutil/deferred";
import { laidOutAt } from "../../../testutil/layout";
import { answered, inGroup } from "../../../testutil/standingRead";
import {
  laysPairsBeside,
  wideKeptBesideNarrow,
} from "../../../testutil/writtenRules";
import { WITHIN_THE_RUN } from "../runAddress";
import { StepPage } from "./StepPage";

const GROUP = "00000003-0000-4000-8000-000000000ca1";

const RUN_ID = "00000008-0000-4000-8000-000000000ca1";

const STEP_ID = "00000009-0000-4000-8000-000000000ca1";

const ENTRY = "00000006-0000-4000-8000-000000000ca1";

const VERSION = "00000007-0000-4000-8000-000000000ca1";

const QUESTION_VERSION = "00000007-0000-4000-8000-000000000ca2";

const PAGE = `/groups/${GROUP}/work/${RUN_ID}/steps/${STEP_ID}`;

const STEPS = `/api/groups/${GROUP}/runs/${RUN_ID}/steps`;

const STEP = `/api/groups/${GROUP}/runs/${RUN_ID}/steps/${STEP_ID}`;

const CAT = { userId: "000ca1", displayName: "Cat" };

const DAN = { userId: "000ca2", displayName: "Dan" };

const UNKNOWN_SAID = "Not something this page can say yet.";

const SOME_MEASURED =
  "Part of it is as this system measured it, where a model did not say what it counted.";

const ZERO_WIDTH_SPACE = String.fromCodePoint(0x200b);

const SHOWS_NOTHING = "A character that shows nothing is here: U+200B.";

const HEADER = {
  runId: RUN_ID,
  number: 7,
  versionId: VERSION,
  state: "running",
  at: { stepId: STEP_ID, name: "summarise" },
  acts: [],
  progress: { done: 1, of: 3 },
};

const ROW = {
  stepId: STEP_ID,
  order: 2,
  name: "summarise",
  runs: {
    kind: "question",
    entryId: ENTRY,
    name: "Summarise",
    version: 2,
    versionId: QUESTION_VERSION,
  },
  producer: { kind: "person" },
  reviewer: { kind: "person" },
  state: "waiting",
  where: {
    kind: "waiting_on_review",
    number: 2,
    values: [{ field: "summary", on: "review_at_gate" }],
    since: "2026-09-26T09:00:00Z",
    waitsOn: "review_at_gate",
  },
  takesFrom: [{ input: "text", from: { kind: "run_input", path: "ticket" } }],
  tries: { current: 2, declared: 2, beyond: false },
  cost: { callsAModel: false },
  acts: [],
  withheld: [],
};

const SUMMARY = {
  name: "summary",
  label: "Summary",
  kind: "text",
  longest: 100,
  mustBeGiven: true,
};

const REPLY = {
  name: "reply",
  label: "Reply",
  kind: "text",
  longest: 100,
  mustBeGiven: true,
};

const DECLARATIONS = {
  [VERSION]: {
    takes: [
      {
        name: "ticket",
        label: "Ticket",
        kind: "text",
        longest: 4000,
        mustBeGiven: true,
      },
    ],
    gives: [],
  },
  [QUESTION_VERSION]: {
    takes: [
      {
        name: "text",
        label: "Text",
        kind: "text",
        longest: 4000,
        mustBeGiven: true,
      },
    ],
    gives: [SUMMARY],
  },
};

const REFUSED_TRY = {
  number: 1,
  beyond: false,
  producedBy: { kind: "person", person: CAT },
  why: "Read it twice.",
  values: [
    {
      field: "summary",
      value: "A fire.",
      now: "refused",
      decision: { outcome: "refused", why: "Too short." },
    },
  ],
  review: { asked: true, by: { kind: "person", person: DAN } },
  ended: "refused_on_review",
  cost: { callsAModel: false },
};

const WAITING_TRY = {
  number: 2,
  beyond: false,
  askedBy: DAN,
  producedBy: { kind: "person", person: CAT },
  values: [
    {
      field: "summary",
      value: "A printer caught fire.",
      now: "waiting_on_review",
    },
  ],
  review: { asked: true },
  ended: "waiting",
  cost: { callsAModel: false },
};

const SURE_TRY = {
  number: 1,
  beyond: false,
  producedBy: { kind: "model", model: "small", mode: "careful" },
  values: [
    {
      field: "summary",
      value: "A fire.",
      now: "refused",
      confidence: 87,
      decision: { outcome: "refused", why: "Too short." },
    },
  ],
  review: { asked: true, by: { kind: "person", person: DAN } },
  ended: "refused_on_review",
  cost: {
    callsAModel: true,
    sent: "1200",
    cameBack: "300",
    spent: "1500",
    cameBackUnknown: false,
  },
};

const WAITING = {
  run: HEADER,
  rereadAfterSeconds: 5,
  declarations: DECLARATIONS,
  step: ROW,
  wentIn: [
    {
      input: "text",
      from: { kind: "run_input", path: "ticket" },
      value: "Printer on fire",
    },
  ],
  wentInFrom: "try",
  cameOut: [{ field: "summary", now: "waiting_on_review" }],
  triesMade: [REFUSED_TRY, WAITING_TRY],
};

const TWO_VALUES = {
  ...WAITING,
  declarations: {
    ...DECLARATIONS,
    [QUESTION_VERSION]: {
      ...DECLARATIONS[QUESTION_VERSION],
      gives: [SUMMARY, REPLY],
    },
  },
  cameOut: [
    { field: "summary", now: "waiting_on_review" },
    { field: "reply", now: "waiting_on_review" },
  ],
  triesMade: [
    {
      ...REFUSED_TRY,
      values: [
        ...REFUSED_TRY.values,
        { field: "reply", value: "Sorry.", now: "refused" },
      ],
    },
    WAITING_TRY,
  ],
};

const MODEL_WAITING = {
  ...WAITING,
  step: {
    ...ROW,
    producer: SURE_TRY.producedBy,
    cost: {
      callsAModel: true,
      sent: "2400",
      cameBack: "600",
      spent: "3000",
      cameBackUnknown: false,
    },
  },
  triesMade: [
    SURE_TRY,
    {
      ...WAITING_TRY,
      producedBy: SURE_TRY.producedBy,
      values: [{ ...WAITING_TRY.values[0], confidence: 92 }],
      cost: SURE_TRY.cost,
    },
  ],
};

const ERRORED_TRY = {
  number: 1,
  beyond: false,
  producedBy: { kind: "code" },
  values: [],
  review: { asked: false },
  ended: "errored",
  wentWrong: { detail: "The reference it gave back is not text.", cut: true },
  returned: '{"reference": 7}',
  cost: { callsAModel: false },
};

const RUNNING_CODE_TRY = {
  number: 2,
  beyond: false,
  producedBy: { kind: "code" },
  values: [],
  review: { asked: false },
  ended: "open",
  cost: { callsAModel: false },
};

const ERRORED = {
  run: HEADER,
  declarations: DECLARATIONS,
  step: {
    ...ROW,
    runs: { kind: "code_step", codeStep: "file_claim" },
    producer: { kind: "code" },
    state: "running",
    where: { kind: "running", on: "code" },
    tries: { current: 2, declared: 2, beyond: false },
  },
  triesMade: [ERRORED_TRY, RUNNING_CODE_TRY],
};

const FILE_CLAIM = {
  takes: [],
  gives: [
    {
      name: "reference",
      label: "Claim reference",
      kind: "text",
      longest: 20,
      mustBeGiven: true,
    },
  ],
};

const UNKEPT = {
  ...ERRORED,
  declarations: { ...DECLARATIONS, file_claim: FILE_CLAIM },
  triesMade: [
    {
      ...ERRORED_TRY,
      wentWrong: undefined,
      returned: undefined,
      erroredFor: {
        reason: "too_long_to_keep",
        field: "reference",
        returnedNotKept: true,
      },
    },
    RUNNING_CODE_TRY,
  ],
};

const LATER_STEP_ID = "0000000a-0000-4000-8000-000000000ca1";

const FAILED_CODE = {
  ...UNKEPT,
  run: { ...HEADER, state: "failed" },
  step: {
    ...UNKEPT.step,
    state: "failed",
    where: { kind: "failed", reason: "tries_spent", waitsOn: "answer_step" },
    tries: { current: 1, declared: 1, beyond: false },
    acts: [],
    withheld: [
      {
        act: "answer",
        refusal: "CODE_STEP_GIVES_OTHERWISE",
        field: "reference",
        readBy: { step: LATER_STEP_ID },
      },
    ],
  },
  triesMade: [UNKEPT.triesMade[0]],
};

const TURNED_AWAY = [
  {
    at: "2026-09-26T08:58:00Z",
    said: "Too many asks.",
    cut: false,
    sentAgain: true,
  },
  { at: "2026-09-26T08:59:00Z", sentAgain: true },
];

const MISFIT_TRY = {
  number: 1,
  beyond: false,
  producedBy: SURE_TRY.producedBy,
  values: [],
  review: { asked: false },
  ended: "did_not_fit",
  didNotFit: "cut_off",
  turnedAway: TURNED_AWAY,
  cost: { ...SURE_TRY.cost, measuredHere: "wholly" },
};

const MODEL_MISFIT = {
  ...MODEL_WAITING,
  step: {
    ...MODEL_WAITING.step,
    cost: { ...MODEL_WAITING.step.cost, measuredHere: "partly" },
  },
  triesMade: [MISFIT_TRY, MODEL_WAITING.triesMade[1]],
};

const HELD_TURNED_AWAY = {
  run: HEADER,
  rereadAfterSeconds: 5,
  declarations: DECLARATIONS,
  step: {
    ...MODEL_WAITING.step,
    state: "held_back",
    where: {
      kind: "held_back",
      reason: "turned_away",
      since: "2026-09-26T08:59:00Z",
      turnedAway: [{ ...TURNED_AWAY[1]!, sentAgain: false }],
      waitsOn: "starter",
    },
    tries: { current: 0, declared: 2, beyond: false },
    withheld: [{ act: "try_sending", refusal: "ACT_NOT_PERMITTED" }],
  },
  triesMade: [],
};

const HELD_AGAIN = {
  ...HELD_TURNED_AWAY,
  step: {
    ...HELD_TURNED_AWAY.step,
    where: {
      ...HELD_TURNED_AWAY.step.where,
      since: "2026-09-26T09:05:00Z",
      turnedAway: [{ at: "2026-09-26T09:05:00Z", sentAgain: false }],
    },
    tries: { current: 1, declared: 2, beyond: false },
  },
  triesMade: [MISFIT_TRY],
};

function reply(page: object): Reply {
  return [JSON.stringify(page), 200];
}

/** The page at its address under a real router, with the standing it would be handed and only the server stood in for. */
function opening(
  pages: readonly Reply[],
  address:
    string | { pathname: string; search?: string; state?: unknown } = PAGE,
  listed: readonly Reply[] = [
    reply({
      run: HEADER,
      steps: [ROW],
      gaveBack: { declares: "nothing" },
      declarations: DECLARATIONS,
    }),
  ],
  more: Readonly<Record<string, readonly Reply[]>> = {},
) {
  laidOutAt(0, 1200);
  const sent = serving({
    [`GET ${STEP}`]: pages,
    [`GET ${STEPS}`]: listed,
    ...more,
  });
  const router = createMemoryRouter(
    [
      {
        path: "/groups/:groupId/work/:runId/steps/:stepId",
        element: <StepPage />,
      },
    ],
    { initialEntries: [address] },
  );
  const standing = answered([], [inGroup(GROUP, "CLAIMS", "Claims", [])]);
  render(
    <ThemeProvider theme={theme}>
      <StandingProvider read={standing}>
        <RouterProvider router={router} />
      </StandingProvider>
    </ThemeProvider>,
  );
  return { sent, router };
}

function setApart(words: string): string {
  return `${String.fromCodePoint(0x2068)}${words}${String.fromCodePoint(0x2069)}`;
}

async function heading(): Promise<HTMLElement> {
  return screen.findByRole("heading", { level: 1 });
}

function fieldsIn(
  list: Element = screen
    .getByRole("region", { name: "About it", hidden: true })
    .querySelector("dl")!,
): Record<string, string> {
  const terms = within(list as HTMLElement).getAllByRole("term");
  return Object.fromEntries(
    terms.map((term) => [
      term.textContent ?? "",
      term.nextElementSibling?.textContent ?? "",
    ]),
  );
}

function part(name: string | RegExp): HTMLElement {
  return screen.getByRole("region", { name });
}

function tryRows(): string[][] {
  const [, ...rows] = within(
    part("The tries"),
  ).getAllByRole<HTMLTableRowElement>("row");
  return rows.map((row) =>
    [...row.cells].map((cell) => cell.textContent ?? ""),
  );
}

/** What is in flight let land a turn at a time, until `landed` holds; failing where it never does. */
async function landing(landed: () => boolean): Promise<void> {
  for (let turn = 0; turn < 100 && !landed(); turn += 1) {
    await act(() => vi.advanceTimersByTimeAsync(0));
  }
  expect(landed()).toBe(true);
}

afterEach(() => {
  vi.useRealTimers();
});

describe("StepPage", () => {
  it("updates the step's stopped day at midnight without rereading or moving the keyboard", async () => {
    vi.useFakeTimers({ toFake: ["Date"] });
    vi.setSystemTime(new Date("2026-10-03T21:59:30Z"));
    const { sent } = opening([
      reply({
        ...HELD_TURNED_AWAY,
        rereadAfterSeconds: undefined,
        step: {
          ...HELD_TURNED_AWAY.step,
          where: {
            kind: "held_back",
            reason: "entry_stopped",
            stopped: {
              what: "entry",
              by: { userId: "000ca1", displayName: "Cat" },
              at: "2026-10-03T21:59:00Z",
            },
          },
        },
      }),
    ]);
    await heading();
    const focusTarget = screen.getByRole("link", { name: /version 2/ });
    focusTarget.focus();
    expect(document.activeElement).toBe(focusTarget);
    expect(document.body).toHaveTextContent("11:59 PM today");
    const reads = requestsTo(sent).filter(
      (request) => request === `GET ${STEP}`,
    ).length;

    vi.setSystemTime(new Date("2026-10-03T22:00:00Z"));
    act(() => document.dispatchEvent(new Event("visibilitychange")));

    expect(document.body).toHaveTextContent("11:59 PM yesterday");
    expect(
      requestsTo(sent).filter((request) => request === `GET ${STEP}`),
    ).toHaveLength(reads);
    expect(document.activeElement).toBe(focusTarget);
  });

  it("keeps a reviewer's declined words inside the picked try without turning literal markup into a link", async () => {
    const words = '<a href="/elsewhere">Demo Sample</a>';
    opening(
      [
        reply({
          ...MODEL_WAITING,
          triesMade: [
            {
              ...SURE_TRY,
              review: {
                asked: true,
                by: { kind: "model", model: "judge" },
                didNotFit: "model_declined",
                declined: {
                  words: { text: words, altered: false, truncated: false },
                },
              },
            },
            MODEL_WAITING.triesMade[1],
          ],
        }),
      ],
      `${PAGE}?try=1`,
    );

    const detail = await screen.findByRole("dialog", {
      name: "Try 1 of the 2 it allows",
    });
    expect(detail).toHaveTextContent(words);
    expect(
      within(detail).getByText("What the model said in declining"),
    ).toBeVisible();
    expect(
      within(detail).queryByRole("link", { name: "Demo Sample" }),
    ).toBeNull();
    await userEvent.click(
      within(detail).getByRole("button", { name: "Close" }),
    );
    await userEvent.click(
      await screen.findByRole("button", { name: "Try 2 of the 2 it allows" }),
    );
    expect(screen.queryByText(words)).toBeNull();
  });

  it("removes a previously visible model refusal when the next read withholds it", async () => {
    vi.useFakeTimers({ toFake: ["setTimeout", "clearTimeout"] });
    const tried = {
      ...SURE_TRY,
      didNotFit: "model_declined",
      declined: {
        content: {
          text: "Demo Sample private answer",
          altered: true,
          truncated: false,
        },
      },
    };
    const response = {
      text: '<a href="/outside">Demo Sample service raw</a>',
      altered: false,
      truncated: true,
    };
    const page = (declined: unknown, unreadableResponse?: typeof response) => ({
      ...MODEL_WAITING,
      triesMade: [
        { ...tried, declined, unreadableResponse },
        MODEL_WAITING.triesMade[1],
      ],
    });
    const { sent } = opening(
      [reply(page(tried.declined, response)), reply(page({ withheld: true }))],
      `${PAGE}?try=1`,
    );
    await landing(
      () => screen.queryByText("Demo Sample private answer") !== null,
    );
    expect(
      screen.getByText("What the model service sent back"),
    ).toBeInTheDocument();
    expect(document.body).toHaveTextContent(response.text);
    expect(
      screen.queryByRole("link", { name: "Demo Sample service raw" }),
    ).toBeNull();
    expect(
      screen.getByText(
        "It held something that could not be kept exactly as it came, so that is replaced here.",
      ),
    ).toBeInTheDocument();

    await act(() => vi.advanceTimersByTimeAsync(5_000));
    await landing(
      () => screen.queryByText("Demo Sample private answer") === null,
    );
    expect(screen.queryByText("What the model gave back")).toBeNull();
    expect(screen.queryByText("What the model service sent back")).toBeNull();
    expect(document.body).not.toHaveTextContent(response.text);
    expect(
      screen.queryByText(
        "It held something that could not be kept exactly as it came, so that is replaced here.",
      ),
    ).toBeNull();
    expect(
      requestsTo(sent).filter((request) => request === `GET ${STEP}`),
    ).toHaveLength(2);
  });

  it("links a child's output to its originating numbered run with History filters but no picked try", async () => {
    const source = {
      runId: "00000008-0000-4000-8000-000000000ca2",
      number: 19,
    };
    opening(
      [
        reply({
          ...WAITING,
          cameOut: [{ field: "summary", now: "stands", fromRun: source }],
        }),
      ],
      `${PAGE}?filter=Demo+Sample&sort=started&value=summary&try=1`,
    );

    await screen.findByRole("dialog", { name: "Try 1 of the 2 it allows" });
    const link = screen.getByRole("link", { name: "Run 19", hidden: true });
    expect(link).toHaveAttribute(
      "href",
      `/groups/${GROUP}/work/${source.runId}?filter=Demo+Sample&sort=started`,
    );
    expect(link).not.toHaveAttribute("href", expect.stringContaining(RUN_ID));
  });

  it("names the picked value's own standing even when its sibling was refused", async () => {
    opening(
      [
        reply({
          ...TWO_VALUES,
          triesMade: [
            {
              ...TWO_VALUES.triesMade[0],
              values: [
                { field: "summary", value: "Demo Sample", now: "stands" },
                { field: "reply", value: "No", now: "refused" },
              ],
            },
            TWO_VALUES.triesMade[1],
          ],
        }),
      ],
      `${PAGE}?value=summary&try=1`,
    );

    const dialog = await screen.findByRole("dialog", {
      name: "Try 1 of the 2 it allows",
    });
    const table = screen.getByRole("table", {
      name: /The tries/,
      hidden: true,
    });
    expect(
      table.querySelector("tbody tr")?.querySelectorAll("td")[2],
    ).toHaveTextContent("It stands.");
    expect(fieldsIn(dialog.querySelector("dl")!)["How it ended"]).toContain(
      "It stands.",
    );
    expect(fieldsIn(dialog.querySelector("dl")!)["How it ended"]).toContain(
      "Refused on review.",
    );
  });

  describe.each([
    [
      "source_no_longer_matches",
      "failedSourceNoLongerMatches",
      `What Demo Sample source gave back as ${setApart("info.answer")} no longer matches what this step reads from it, as that code step is declared now, so nothing was sent.`,
    ],
    [
      "source_list_not_here",
      "failedSourceListNotHere",
      `The terms of ${setApart("info.answer")}, which Demo Sample source gave back, come from a reference list version this group does not hold in service, so nothing was sent.`,
    ],
  ])("%s", (reason, member, sentence) => {
    it.each([
      ["question", { kind: "question", name: "Summarise" }, RUN_ID],
      [
        "question",
        { kind: "question", name: "Summarise" },
        "00000008-0000-4000-8000-000000000ca2",
      ],
      ["search", { kind: "search", searchWay: "web" }, RUN_ID],
      [
        "search",
        { kind: "search", searchWay: "web" },
        "00000008-0000-4000-8000-000000000ca2",
      ],
    ])(
      "says the actual mismatched source field of a failed %s and offers only sending",
      async (_kind, runs, sourceRun) => {
        const sourceStep = "00000009-0000-4000-8000-000000000ca2";
        const sourceNumber = sourceRun === RUN_ID ? 7 : 19;
        opening(
          [
            reply({
              ...HELD_TURNED_AWAY,
              step: {
                ...HELD_TURNED_AWAY.step,
                runs,
                state: "failed",
                acts: ["try_sending"],
                withheld: [],
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
              },
            }),
          ],
          `${PAGE}?filter=Demo+Sample&sort=started&value=summary&try=1`,
        );
        await heading();

        const source = screen.getByRole("link", { name: "Demo Sample source" });
        expect(source).toHaveAttribute(
          "href",
          `/groups/${GROUP}/work/${sourceRun}/steps/${sourceStep}?filter=Demo+Sample&sort=started`,
        );
        expect(source).toHaveAttribute("title", `Run ${sourceNumber}`);
        expect(source.querySelector("bdi")).toHaveTextContent(
          "Demo Sample source",
        );
        expect(
          screen.getByRole("button", { name: "Try sending" }),
        ).toBeEnabled();
        expect(
          screen.queryByRole("button", { name: "Ask again" }),
        ).not.toBeInTheDocument();
        expect(
          screen.queryByRole("button", { name: "Answer it here" }),
        ).not.toBeInTheDocument();
        expect(document.body).toHaveTextContent(sentence);
        expect(document.body).not.toHaveTextContent("goes on by itself");
        expect(document.body).not.toHaveTextContent("gave back as text");
        expect(source).not.toHaveAttribute("href", PAGE);
      },
    );
  });

  it.each([
    ["question", { kind: "question", name: "Summarise" }, RUN_ID],
    [
      "question",
      { kind: "question", name: "Summarise" },
      "00000008-0000-4000-8000-000000000ca2",
    ],
    ["search", { kind: "search", searchWay: "web" }, RUN_ID],
    [
      "search",
      { kind: "search", searchWay: "web" },
      "00000008-0000-4000-8000-000000000ca2",
    ],
  ])(
    "links a %s's held source in its actual run",
    async (_kind, runs, sourceRun) => {
      const sourceStep = "00000009-0000-4000-8000-000000000ca2";
      const sourceNumber = sourceRun === RUN_ID ? 7 : 19;
      const page = {
        ...HELD_TURNED_AWAY,
        step: {
          ...HELD_TURNED_AWAY.step,
          runs,
          where: {
            kind: "held_back",
            reason: "code_step_not_held",
            waitsOn: "starter",
            source: {
              runId: sourceRun,
              runNumber: sourceNumber,
              stepId: sourceStep,
              name: "Demo Sample source",
              codeStep: "tally",
            },
          },
        },
      };
      opening([reply(page)]);
      await heading();

      const source = screen.getByRole("link", { name: "Demo Sample source" });
      expect(source).toHaveAttribute(
        "href",
        `/groups/${GROUP}/work/${sourceRun}/steps/${sourceStep}`,
      );
      expect(source).toHaveAttribute("title", `Run ${sourceNumber}`);
      expect(source.querySelector("bdi")).toHaveTextContent(
        "Demo Sample source",
      );
      expect(source.closest('[role="none"]')).toHaveTextContent(
        "A value it reads came from Demo Sample source, whose code this system, as it runs now, does not include, so it cannot be sent; it goes on by itself once a release holds that code step again.",
      );
      expect(
        screen.queryByText(/does not include the code this step runs/),
      ).not.toBeInTheDocument();
      expect(source).not.toHaveAttribute("href", PAGE);
    },
  );

  it.each([
    ["question", { kind: "question", name: "Summarise" }],
    ["search", { kind: "search", searchWay: "web" }],
  ])(
    "keeps the existing missing-code sentence for a %s with no held source",
    async (_kind, runs) => {
      opening([
        reply({
          ...HELD_TURNED_AWAY,
          step: {
            ...HELD_TURNED_AWAY.step,
            runs,
            where: {
              kind: "held_back",
              reason: "code_step_not_held",
              waitsOn: "starter",
            },
          },
        }),
      ]);
      await heading();

      expect(
        screen.getByText(
          "This system, as it runs now, does not include the code this step runs; it goes on by itself once a release holds that code step again.",
        ),
      ).toBeInTheDocument();
      expect(
        screen.queryByText(/A value it reads came from/),
      ).not.toBeInTheDocument();
      expect(
        screen.queryByRole("link", { name: "Demo Sample source" }),
      ).not.toBeInTheDocument();
    },
  );

  it("names the search way and keeps the waiting try's record beside review once, even when that try is picked", async () => {
    const step = {
      ...ROW,
      runs: { kind: "search", searchWay: "web", tool: "web_search" },
      acts: ["review"],
    };
    const page = {
      ...WAITING,
      step,
      triesMade: [
        {
          ...WAITING_TRY,
          producedBy: { kind: "model", model: "small" },
          search: {
            question: "Demo Sample current search",
            searches: [{ found: "The current record" }],
          },
        },
      ],
    };
    opening([reply(page)]);
    await heading();

    expect(
      screen.getByText(setApart("web"), { exact: false }),
    ).toHaveTextContent("Search:");
    expect(screen.getAllByText("Demo Sample current search")).toHaveLength(1);
    expect(screen.getAllByText("The current record")).toHaveLength(1);
    expect(
      screen.getByRole("heading", { name: "Your review" }),
    ).toBeInTheDocument();
    expect(screen.queryByText("web_search")).toBeNull();
    await userEvent.click(
      screen.getByRole("button", { name: "Try 2 of the 2 it allows" }),
    );
    const detail = await screen.findByRole("dialog");
    expect(
      within(detail).getByText("Demo Sample current search"),
    ).toBeInTheDocument();
    expect(screen.getAllByText("Demo Sample current search")).toHaveLength(1);
  });

  it.each(["too_long", "turned_away"])(
    "draws a held search question from detail only for %s",
    async (reason) => {
      opening([
        reply({
          ...WAITING,
          step: {
            ...ROW,
            runs: { kind: "search", searchWay: "web" },
            state: "held_back",
            where: {
              kind: "held_back",
              reason,
              waitsOn: "answer_step",
              searchQuestion: "Demo Sample held question",
            },
          },
          triesMade: [],
        }),
      ]);
      await heading();

      expect(screen.getByText("Demo Sample held question")).toBeInTheDocument();
      expect(
        screen.getByText(
          reason === "too_long"
            ? "The question it would have searched with"
            : "The question it searched with, which the model would not take",
        ),
      ).toBeInTheDocument();
      expect(
        screen.queryByText(
          reason === "too_long"
            ? "The question it searched with, which the model would not take"
            : "The question it would have searched with",
        ),
      ).toBeNull();
    },
  );
  it("gives the way back through its run first, keeping the list's address and leaving what this page picked", async () => {
    opening(
      [reply(WAITING)],
      `${PAGE}?sort=run&value=summary&try=1`,
      undefined,
      {
        [`GET /api/groups/${GROUP}/runs/${RUN_ID}`]: [
          reply({
            runId: RUN_ID,
            number: 7,
            name: "Claim from Avo",
            runsAbove: [],
            workflow: { entryId: ENTRY, name: "Handle a claim", version: 3 },
            startedAt: "2026-09-24T08:00:00Z",
            conversationEvents: [],

            state: "running",
            spend: {
              sent: "0",
              cameBack: "0",
              spent: "0",
              cameBackUnknown: false,
            },
            ceiling: { raiseNeedsApproval: false },
            acts: [],
          }),
        ],
      },
    );

    await screen.findByRole("dialog");
    const title = screen.getByRole("heading", { level: 1, hidden: true });

    const path = screen.getByRole("navigation", {
      name: "Breadcrumb",
      hidden: true,
    });
    const run = await within(path).findByRole("link", {
      name: setApart("Claim from Avo"),
      hidden: true,
    });

    expect(run).toHaveAttribute(
      "href",
      `/groups/${GROUP}/work/${RUN_ID}?sort=run`,
    );
    expect(
      path.compareDocumentPosition(title) & Node.DOCUMENT_POSITION_FOLLOWING,
    ).toBeTruthy();
    const toTheRun = screen
      .getAllByRole("link", { hidden: true })
      .filter(
        (link) =>
          link.getAttribute("href") ===
          `/groups/${GROUP}/work/${RUN_ID}?sort=run`,
      );
    expect(toTheRun).toEqual([run]);
  });

  it("starts the keyboard at the step's heading where it was opened from its run, and nowhere else", async () => {
    opening([reply(WAITING)], { pathname: PAGE, state: WITHIN_THE_RUN });

    const title = await heading();

    expect(document.activeElement).toBe(title);
  });

  it("leaves the keyboard where it was where the page was opened by its address", async () => {
    opening([reply(WAITING)]);

    const title = await heading();

    expect(document.activeElement).not.toBe(title);
    expect(document.activeElement).toBe(document.body);
  });

  it("heads the page with what the step runs, and says which run, where it runs, what it runs, who produces and reviews it, where it is, its try and its cost", async () => {
    opening([reply(WAITING)]);

    expect(await heading()).toHaveTextContent(setApart("Summarise"));
    expect(fieldsIn()).toEqual({
      Run: "Run 7",
      Order: "2 of 3",
      "What it runs": `Question ${setApart("Summarise")}, version 2`,
      "Produced by": "A person",
      "Reviewed by": "Somebody who may review it",
      "Where it is": `Waiting${setApart("Summary")} is not reviewed yet.It waits on somebody who may review it.`,
      Tries: "Try 2 of the 2 it allows",
      Cost: "Nothing: it calls no model.",
    });
    expect(screen.getByRole("link", { name: /Summarise/ })).toHaveAttribute(
      "href",
      `/groups/${GROUP}/questions/${ENTRY}?version=${QUESTION_VERSION}`,
    );
    const fields = screen
      .getByRole("region", { name: "About it" })
      .querySelector("dl")!;
    const kind = within(fields).getByText("Question");
    const state = within(fields).getByText("Waiting");
    expect(kind.querySelector("svg")).toHaveAttribute(
      "data-testid",
      "QuestionAnswerIcon",
    );
    expect(state.querySelector("svg")).toHaveAttribute(
      "data-testid",
      "HourglassEmptyIcon",
    );
    expect(kind).not.toContainElement(state);
  });

  it("draws what went in, what came out and its tries first, and About it after them in a column of its own, the step's name the page's one first-level heading", async () => {
    opening([reply(WAITING)]);
    const title = await heading();

    const about = screen.getByRole("region", { name: "About it" });
    const wide = [
      part("What went in"),
      part("What came out"),
      screen.getByRole("heading", { level: 2, name: /^The tries/ }),
    ];
    for (const each of wide) {
      expect(
        each.compareDocumentPosition(about) & Node.DOCUMENT_POSITION_FOLLOWING,
      ).toBeTruthy();
      expect(about).not.toContainElement(each);
      expect(about.parentElement!.previousElementSibling).toContainElement(
        each,
      );
    }
    expect(screen.getAllByRole("heading", { level: 1 })).toEqual([title]);
    expect(
      within(about).getByRole("heading", { level: 2, name: "About it" }),
    ).toBeInTheDocument();
    expect(laysPairsBeside(about.querySelector("dl")!)).toBe(false);
    expect(laysPairsBeside(part("What came out").querySelector("dl")!)).toBe(
      true,
    );
    expect(wideKeptBesideNarrow()).toBeGreaterThanOrEqual(BESIDE_FROM);
  });

  it.each([
    [
      "unsent",
      "It waits on a model's review, which this system sends by itself.",
    ],
    ["out", "It waits on a model's review; the call asking for it is out."],
    [
      "turned_away",
      "It waits on a model's review, which could not be sent: the model would not take it.",
    ],
    [
      "too_long",
      "What its review would send is longer than the model that reviews it takes, so it was not sent, and somebody who may review it does so instead.",
    ],
    [
      "list_not_here",
      "This could not be put to the model that reviews it: a reference list it names is not here, so a person reviews it instead.",
    ],
    [
      "takes_no_longer_declared",
      "This could not be put to the model that reviews it: the release no longer declares what this step takes, so a person reviews it instead.",
    ],
    [
      "no_longer_declared",
      "This could not be put to the model that reviews it: the release no longer declares what this step gave back, so a person reviews it instead.",
    ],
  ])(
    "draws a model review that is %s as waiting on the step page",
    async (review, sentence) => {
      const fallback = [
        "too_long",
        "list_not_here",
        "takes_no_longer_declared",
        "no_longer_declared",
      ].includes(review);
      opening([
        reply({
          ...WAITING,
          step: {
            ...ROW,
            reviewer: { kind: "model", model: "small" },
            where: {
              ...ROW.where,
              review,
              waitsOn: fallback ? "review_at_gate" : "model",
            },
          },
        }),
      ]);
      await heading();
      expect(fieldsIn()["Where it is"]).toContain(sentence);
      expect(fieldsIn()["Where it is"]).toMatch(/^Waiting/);
      expect(fieldsIn()["Where it is"]).not.toContain("Held back");
      expect(
        screen.queryByText("The model it names would not take it."),
      ).toBeNull();
    },
  );

  it("says before what went in why an act the step holds back from the reader is held back, as the step's message in the run does, and not in About it", async () => {
    opening([
      reply({
        ...WAITING,
        step: {
          ...ROW,
          withheld: [{ act: "review", refusal: "ACT_NOT_PERMITTED" }],
        },
      }),
    ]);
    await heading();

    const said = screen.getByText("You may not review it.");
    expect(
      screen.getByRole("region", { name: "About it" }),
    ).not.toContainElement(said);
    expect(
      said.compareDocumentPosition(part("What went in")) &
        Node.DOCUMENT_POSITION_FOLLOWING,
    ).toBeTruthy();
    expect(screen.queryByText(/ACT_NOT_PERMITTED/)).toBeNull();
  });

  it("says nothing of an act held back where the step holds none back from the reader", async () => {
    opening([reply(WAITING)]);
    await heading();

    expect(screen.queryByText(/^You may not/)).toBeNull();
    expect(screen.queryByText(/^This step's next try/)).toBeNull();
  });

  it.each([
    [
      "what it runs has a readable name",
      { kind: "question", name: "Check the claim" },
      "Check the claim",
    ],
    [
      "what it runs has no readable name",
      { kind: "code_step", codeStep: "check_claim" },
      "check the claim",
    ],
  ])(
    "names the later step reading a code step's refused field by its message title when %s",
    async (_case, runs, title) => {
      const later = {
        ...ROW,
        stepId: "0000000a-0000-4000-8000-000000000ca1",
        order: 3,
        name: "check_the_claim",
        runs,
        state: "not_started",
        where: undefined,
        tries: undefined,
        acts: [],
        withheld: [],
      };
      const failed = FAILED_CODE;
      const { sent } = opening([reply(failed)], PAGE, [
        reply({
          run: failed.run,
          steps: [failed.step, later],
          gaveBack: { declares: "nothing" },
          declarations: failed.declarations,
        }),
      ]);
      await screen.findByText(
        `What its code gives back as ${setApart("Claim reference")} no longer matches what ${setApart(title)} reads from it.`,
      );
      expect(
        screen.getByText(/^What this code step gives back, as this release/),
      ).toBeVisible();
      expect(
        requestsTo(sent).filter((each) => each === `GET ${STEPS}`),
      ).toHaveLength(1);
      expect(
        screen.queryByRole("button", { name: "Answer it here" }),
      ).toBeNull();
      expect(screen.queryByText(new RegExp(later.stepId))).toBeNull();
    },
  );

  it("says only what is known when the step-list read succeeds without the later reader", async () => {
    const { sent } = opening([reply(FAILED_CODE)]);
    await screen.findByText(UNKNOWN_SAID);

    expect(screen.queryByText(/what a later step reads from it/)).toBeNull();
    expect(screen.queryByRole("alert")).toBeNull();
    expect(fieldsIn()["Where it is"]).toMatch(/^Failed/);
    expect(
      requestsTo(sent).filter((each) => each === `GET ${STEPS}`),
    ).toHaveLength(1);
  });

  it("keeps the step page readable when its step-list read fails, naming the reader as a later step", async () => {
    const failed = FAILED_CODE;
    opening([reply(failed)], PAGE, [
      [JSON.stringify({ code: "READ_FAILED" }), 503],
    ]);
    await heading();
    expect(
      screen.getByText(
        `What its code gives back as ${setApart("Claim reference")} no longer matches what a later step reads from it.`,
      ),
    ).toBeVisible();
    expect(screen.queryByRole("alert")).toBeNull();
    expect(fieldsIn()["Where it is"]).toMatch(/^Failed/);
  });

  it("removes the step, run path and its values when the step-list read says the run is no longer in view", async () => {
    const { sent } = opening([reply(FAILED_CODE)], PAGE, [
      [JSON.stringify({ code: "RUN_NOT_IN_VIEW" }), 404],
    ]);
    expect(await screen.findByText("That run is not in view.")).toBeVisible();
    expect(screen.queryByRole("heading", { level: 1 })).toBeNull();
    expect(screen.queryByText("Claim reference")).toBeNull();
    expect(screen.queryByRole("region", { name: "About it" })).toBeNull();
    expect(requestsTo(sent)).toContain(`GET ${STEPS}`);
  });

  it("stops background step reads after the step-list read closes the run", async () => {
    vi.useFakeTimers({ toFake: ["setTimeout", "clearTimeout"] });
    const listed = deferred<readonly [string, number]>();
    const { sent } = opening(
      [reply({ ...FAILED_CODE, rereadAfterSeconds: 1 })],
      PAGE,
      [listed.promise],
    );
    await landing(() => screen.queryByRole("heading", { level: 1 }) !== null);

    await act(async () =>
      listed.settle([JSON.stringify({ code: "RUN_NOT_IN_VIEW" }), 404]),
    );
    await landing(
      () => screen.queryByText("That run is not in view.") !== null,
    );
    const stepCount = requestsTo(sent).filter(
      (each) => each === `GET ${STEP}`,
    ).length;

    await act(() => vi.advanceTimersByTimeAsync(3000));

    expect(
      requestsTo(sent).filter((each) => each === `GET ${STEP}`),
    ).toHaveLength(stepCount);
    expect(screen.queryByRole("heading", { level: 1 })).toBeNull();
  });

  it("waits for the step-list read to settle before naming the reader and uses its title once it succeeds", async () => {
    let finishList: (answer: readonly [string, number]) => void = () => {
      throw new Error("No pending list read");
    };
    const pendingList: Reply = new Promise((resolve) => {
      finishList = resolve;
    });
    opening([reply(FAILED_CODE)], PAGE, [pendingList]);
    await heading();
    expect(screen.getByText(UNKNOWN_SAID)).toBeVisible();
    expect(screen.queryByText(/what a later step reads from it/)).toBeNull();
    const later = {
      ...ROW,
      stepId: LATER_STEP_ID,
      order: 3,
      name: "check_claim",
      runs: { ...ROW.runs, name: "Check the claim" },
      state: "not_started",
      where: undefined,
      tries: undefined,
      acts: [],
      withheld: [],
    };
    await act(async () => {
      finishList([
        JSON.stringify({
          run: FAILED_CODE.run,
          gaveBack: { declares: "nothing" },
          declarations: FAILED_CODE.declarations,
          steps: [FAILED_CODE.step, later],
        }),
        200,
      ]);
      await Promise.resolve();
    });
    expect(
      await screen.findByText(
        `What its code gives back as ${setApart("Claim reference")} no longer matches what ${setApart("Check the claim")} reads from it.`,
      ),
    ).toBeVisible();
    expect(screen.queryByText(UNKNOWN_SAID)).toBeNull();
  });

  it("abandons the old run's step-list read on navigation and keeps the new reader's title after its old reply arrives", async () => {
    const laterId = "0000000a-0000-4000-8000-000000000ca1";
    const otherRun = "00000008-0000-4000-8000-000000000ca2";
    const otherSteps = `/api/groups/${GROUP}/runs/${otherRun}/steps`;
    let finishList: (answer: readonly [string, number]) => void = () => {
      throw new Error("No pending list read");
    };
    const pendingList: Reply = new Promise((resolve) => {
      finishList = resolve;
    });
    const failed = FAILED_CODE;
    const later = {
      ...ROW,
      stepId: laterId,
      order: 3,
      name: "check_claim",
      state: "not_started",
      where: undefined,
      tries: undefined,
      acts: [],
      withheld: [],
    };
    const next = {
      ...failed,
      run: { ...failed.run, runId: otherRun, number: 8 },
    };
    const listed = (name: string, run = failed.run) => ({
      run,
      gaveBack: { declares: "nothing" },
      declarations: failed.declarations,
      steps: [failed.step, { ...later, runs: { ...later.runs, name } }],
    });
    const { sent, router } = opening([reply(failed)], PAGE, [pendingList], {
      [`GET ${otherSteps}/${STEP_ID}`]: [reply(next)],
      [`GET ${otherSteps}`]: [reply(listed("Check the new claim", next.run))],
    });
    await heading();
    expect(screen.getByText(UNKNOWN_SAID)).toBeVisible();
    expect(screen.queryByText(/what a later step reads from it/)).toBeNull();
    const oldSignal = sent.mock.calls.find(([input]) =>
      String(input).endsWith(STEPS),
    )![1]!.signal!;
    expect(oldSignal.aborted).toBe(false);
    await act(() =>
      router.navigate(`/groups/${GROUP}/work/${otherRun}/steps/${STEP_ID}`),
    );
    const newLine = `What its code gives back as ${setApart("Claim reference")} no longer matches what ${setApart("Check the new claim")} reads from it.`;
    await screen.findByText(newLine);
    expect(oldSignal.aborted).toBe(true);
    await act(async () => {
      finishList([JSON.stringify(listed("Check the old claim")), 200]);
      await Promise.resolve();
    });
    expect(screen.getByText(newLine)).toBeVisible();
    expect(screen.queryByText(/Check the old claim/)).toBeNull();
    expect(fieldsIn()["Run"]).toBe("Run 8");
  });

  it("titles a route step by its own name without claiming a producer or reviewer", async () => {
    const { producer: _producer, reviewer: _reviewer, ...unreviewed } = ROW;
    opening([
      reply({
        ...WAITING,
        step: { ...unreviewed, name: "file_it", runs: { kind: "route" } },
      }),
    ]);

    expect(await heading()).toHaveTextContent(setApart("file it"));
    expect(fieldsIn()).toMatchObject({
      "What it runs": "Route",
    });
    expect(fieldsIn()).not.toHaveProperty("Produced by");
    expect(fieldsIn()).not.toHaveProperty("Reviewed by");
    expect(screen.queryByRole("link", { name: /Summarise/ })).toBeNull();
    expect(screen.getByText("Route").querySelector("svg")).toHaveAttribute(
      "data-testid",
      "AltRouteIcon",
    );
  });

  it("says where a route step failed with the term no branch took isolated, a character in it that shows nothing drawn as a marker and never itself", async () => {
    const { producer: _producer, reviewer: _reviewer, ...unreviewed } = ROW;
    opening([
      reply({
        ...WAITING,
        step: {
          ...unreviewed,
          runs: { kind: "route" },
          state: "failed",
          where: {
            kind: "failed",
            reason: "unclaimed_value",
            failedUnclaimed: { term: `Demo${ZERO_WIDTH_SPACE}Sample` },
          },
        },
      }),
    ]);
    await heading();

    const marker = screen.getByRole("img", { name: SHOWS_NOTHING });
    expect(fieldsIn()["Where it is"]).toBe(
      "FailedNo branch of this route takes “DemoU+200BSample”, and it has no fallback. The way on is a new version of this workflow, and a new run.",
    );
    expect(marker.closest("bdi")?.textContent).toBe("DemoU+200BSample");
    expect(document.body.innerHTML).not.toContain(ZERO_WIDTH_SPACE);
  });

  it("says why a step held back is held with the term no branch took drawn in place, never as text made of the drawing", async () => {
    const { producer: _producer, reviewer: _reviewer, ...unreviewed } = ROW;
    opening([
      reply({
        ...WAITING,
        step: {
          ...unreviewed,
          runs: { kind: "route" },
          state: "held_back",
          where: {
            kind: "failed",
            reason: "unclaimed_value",
            failedUnclaimed: { term: `Demo${ZERO_WIDTH_SPACE}Sample` },
          },
        },
      }),
    ]);
    await heading();

    const marker = screen.getByRole("img", { name: SHOWS_NOTHING });
    const notice = marker.closest('[role="none"]')!;
    expect(notice).toHaveTextContent(
      "No branch of this route takes “DemoU+200BSample”, and it has no fallback.",
    );
    expect(marker.closest("bdi")?.textContent).toBe("DemoU+200BSample");
    expect(
      notice.compareDocumentPosition(document.querySelector("dl")!) &
        Node.DOCUMENT_POSITION_FOLLOWING,
    ).toBeTruthy();
    expect(fieldsIn()["Where it is"]).toBe("Held back");
    expect(document.body.textContent).not.toContain("[object Object]");
    expect(document.body.innerHTML).not.toContain(ZERO_WIDTH_SPACE);
  });

  it("opens the child run and the deepest step from a route without offering parent try actions", async () => {
    const child = "00000008-0000-4000-8000-000000000c82";
    const deepest = "00000009-0000-4000-8000-000000000c82";
    const { producer: _producer, reviewer: _reviewer, ...route } = ROW;
    opening([
      reply({
        ...WAITING,
        step: {
          ...route,
          runs: { kind: "route" },
          acts: [],
          beneath: {
            runId: child,
            number: 8,
            runs: {
              kind: "workflow",
              entryId: "00000006-0000-4000-8000-000000000c82",
              name: "Demo Sample",
              version: 2,
              versionId: VERSION,
            },
            at: { runId: child, number: 8, stepId: deepest, name: "make_it" },
          },
        },
      }),
    ]);

    await heading();
    expect(
      screen.getByRole("link", { name: "Open run 8" }).getAttribute("href"),
    ).toBe(`/groups/${GROUP}/work/${child}`);
    expect(
      screen
        .getByRole("link", { name: setApart("make_it") })
        .getAttribute("href"),
    ).toBe(`/groups/${GROUP}/work/${child}/steps/${deepest}`);
    expect(screen.queryByRole("button", { name: "Ask again" })).toBeNull();
    expect(screen.queryByRole("button", { name: "Answer it here" })).toBeNull();
  });

  it("draws a whole withheld input without treating it as absent or historical", async () => {
    opening([
      reply({
        ...WAITING,
        wentIn: [
          {
            input: "text",
            from: { kind: "run_input", path: "ticket" },
            withheld: true,
          },
        ],
      }),
    ]);
    await heading();

    expect(part("What went in")).toHaveTextContent("Withheld from you.");
    expect(part("What came out")).toHaveTextContent("Summary");
    expect(document.body).not.toHaveTextContent(
      "Kept as it was given; it no longer matches what is declared now.",
    );
    expect(
      screen.getByRole("heading", { name: "About it" }),
    ).toBeInTheDocument();
  });

  it("says nothing has gone in and nothing come out of a step not started, and no try is made", async () => {
    const { where: _where, ...unplaced } = ROW;
    opening([
      reply({
        run: HEADER,
        declarations: DECLARATIONS,
        step: { ...unplaced, state: "not_started" },
        triesMade: [],
      }),
    ]);
    await heading();

    expect(part("What went in")).toHaveTextContent("Nothing yet.");
    expect(part("What came out")).toHaveTextContent("Nothing yet.");
    expect(part(/^The tries/)).toHaveTextContent("No try has been made yet.");
    expect(within(part("What went in")).queryByRole("term")).toBeNull();
    expect(within(part(/^The tries/)).queryByRole("table")).toBeNull();
    for (const name of ["What went in", "What came out", /^The tries/]) {
      expect(
        within(part(name)).getByRole("heading", { level: 2, name }),
      ).toBeInTheDocument();
      expect(
        within(part(name)).queryByRole("heading", { level: 3, name }),
      ).toBeNull();
    }
  });

  it("names each input by its label, where it came from and what it was given", async () => {
    opening([reply(WAITING)]);
    await heading();

    const wentIn = part("What went in");
    expect(within(wentIn).getByRole("term")).toHaveTextContent("Text");
    expect(within(wentIn).getByRole("definition")).toHaveTextContent(
      `${setApart("Ticket")}, which the run was started with` +
        "Printer on fire",
    );
    expect(wentIn).not.toHaveTextContent("Nothing is sent yet");
  });

  it("names a received value's source run from the published answer", async () => {
    const [text] = WAITING.wentIn;
    opening([
      reply({
        ...WAITING,
        wentIn: [
          {
            ...text,
            from: {
              kind: "run_input",
              path: "ticket",
              runId: "00000008-0000-4000-8000-000000000ca2",
              runNumber: 6,
            },
          },
        ],
      }),
    ]);
    await heading();

    const wentIn = part("What went in");
    expect(wentIn).toHaveTextContent(
      `${setApart("ticket")}, which run 6 was started with`,
    );
    expect(wentIn).not.toHaveTextContent(
      `${setApart("Ticket")}, which the run was started with`,
    );
  });

  it("downloads a file from the readable step's run even when its input came from another run", async () => {
    const sourceRunId = "00000008-0000-4000-8000-000000000ca2";
    const file = {
      id: "0000000f-0000-4000-8000-000000000ca1",
      name: "Demo Sample.pdf",
      type: "application/pdf",
      size: 5,
    };
    const attachment = {
      name: "text",
      label: "Text",
      kind: "file",
      types: ["application/pdf"],
      maximumBytes: 12,
      mustBeGiven: true,
    };
    opening([
      reply({
        ...WAITING,
        declarations: {
          ...DECLARATIONS,
          [QUESTION_VERSION]: {
            ...DECLARATIONS[QUESTION_VERSION],
            takes: [attachment],
          },
        },
        wentIn: [
          {
            input: "text",
            from: {
              kind: "run_input",
              path: "ticket",
              runId: sourceRunId,
              runNumber: 6,
            },
            value: file,
          },
        ],
      }),
    ]);
    await heading();

    const wentIn = part("What went in");
    const link = within(wentIn).getByRole("link", {
      name: /Download.*Demo Sample.pdf/,
    });
    expect(link).toHaveAttribute(
      "href",
      `/api/groups/${GROUP}/runs/${RUN_ID}/files/${file.id}`,
    );
    expect(link.getAttribute("href")).not.toContain(sourceRunId);
    expect(wentIn).toHaveTextContent("Type:");
    expect(wentIn).toHaveTextContent("Size: 5 bytes.");
  });

  it("draws a long text in full, in what went in and in a try opened, with nothing to open it by", async () => {
    const [text] = WAITING.wentIn;
    opening([
      reply({
        ...WAITING,
        wentIn: [{ ...text, value: "Printer on fire.\nIt caught again." }],
        triesMade: [
          {
            ...REFUSED_TRY,
            values: [
              { ...REFUSED_TRY.values[0], value: "A fire.\nIn the printer." },
            ],
          },
          WAITING_TRY,
        ],
      }),
    ]);
    await heading();

    const wentIn = part("What went in");
    expect(within(wentIn).getByRole("definition")).toHaveTextContent(
      "Printer on fire. It caught again.",
    );
    expect(within(wentIn).queryByRole("button")).toBeNull();
    await userEvent.click(
      screen.getByRole("button", { name: "Try 1 of the 2 it allows" }),
    );
    const opened = await screen.findByRole("dialog", {
      name: "Try 1 of the 2 it allows",
    });
    expect(opened).toHaveTextContent("A fire. In the printer.");
    expect(
      within(opened).queryByRole("button", { name: /in full/ }),
    ).toBeNull();
  });

  it("says what went in is what it would take now, where nothing is sent yet", async () => {
    opening([reply({ ...WAITING, wentInFrom: "not_yet_sent" })]);
    await heading();

    expect(part("What went in")).toHaveTextContent(
      "Nothing is sent yet: this is what it would take now.",
    );
  });

  it("lays out what went in as the question's drawing says, a heading it writes one level below the section's own", async () => {
    opening([reply({ ...WAITING, drawing: "# {{text}}" })]);
    await heading();

    const wentIn = part("What went in");
    expect(
      within(wentIn)
        .getAllByRole("heading")
        .map((each) => [each.tagName, each.textContent]),
    ).toEqual([
      ["H2", "What went in"],
      ["H3", "Printer on fire"],
    ]);
    expect(within(wentIn).queryByRole("term")).toBeNull();
  });

  it("says a step that has produced nothing so, rather than listing nothing", async () => {
    const { cameOut: _cameOut, ...nothingOut } = WAITING;
    opening([reply({ ...nothingOut, triesMade: [] })]);
    await heading();

    expect(part("What came out")).toHaveTextContent(
      "It has produced nothing yet.",
    );
    expect(within(part("What came out")).queryByRole("term")).toBeNull();
  });

  it("lists every try of the value, oldest first and the refused one with them, and opens one with what it produced and the words it was refused with", async () => {
    const { router } = opening([reply(WAITING)], `${PAGE}?sort=run`);
    await heading();

    expect(part("What came out")).toHaveTextContent(
      `${setApart("Summary")}It waits on review.`,
    );
    expect(tryRows()).toEqual([
      [
        "Try 1 of the 2 it allows",
        setApart("Cat"),
        setApart("Dan"),
        "Refused on review.",
        "Nothing: it calls no model.",
      ],
      [
        "Try 2 of the 2 it allows",
        setApart("Cat"),
        "Nobody has reviewed it yet.",
        "It waits on review.",
        "Nothing: it calls no model.",
      ],
    ]);
    expect(
      within(part("The tries"))
        .getAllByRole("rowheader")
        .map((cell) => cell.textContent),
    ).toEqual(["Try 1 of the 2 it allows", "Try 2 of the 2 it allows"]);

    await userEvent.click(
      screen.getByRole("button", { name: "Try 1 of the 2 it allows" }),
    );

    const opened = await screen.findByRole("dialog", {
      name: "Try 1 of the 2 it allows",
    });
    expect(opened).toHaveTextContent("A fire.");
    expect(opened).toHaveTextContent("Read it twice.");
    expect(opened).toHaveTextContent("Refused: Too short.");
    expect(within(opened).getByText("Too short.").tagName).toBe("BDI");
    expect(opened).toHaveTextContent("The run itself");
    expect(opened).not.toHaveTextContent("A printer caught fire.");
    expect(router.state.location.search).toBe("?sort=run&value=summary&try=1");
  });

  it("shows an extra try's number and allowance with the explanation marked in its row and open panel", async () => {
    opening([
      reply({
        ...WAITING,
        step: {
          ...ROW,
          tries: { current: 3, declared: 2, beyond: true },
          where: { ...ROW.where, number: 3 },
        },
        triesMade: [
          REFUSED_TRY,
          WAITING_TRY,
          { ...WAITING_TRY, number: 3, beyond: true },
        ],
      }),
    ]);
    await heading();
    const rowLink = within(part("The tries")).getByRole("button", {
      name: "Try 3, past the 2 it allows",
    });
    expect(rowLink.textContent).toBe("Try 3, past the 2 it allows");
    const hints = [...rowLink.querySelectorAll("span")];
    expect(hints.map((part) => part.textContent)).toEqual([
      "past the",
      "it allows",
    ]);
    expect(
      hints.every((part) => getComputedStyle(part).fontStyle === "italic"),
    ).toBe(true);

    await userEvent.click(rowLink);
    const opened = await screen.findByRole("dialog", {
      name: "Try 3, past the 2 it allows",
    });
    expect(
      within(opened).getByRole("heading", {
        name: "Try 3, past the 2 it allows",
      }),
    ).toBeVisible();
    expect(opened).toHaveTextContent("A printer caught fire.");
  });

  it("draws the words a model's review refused a value in, in the try opened, a character in them that shows nothing as a marker and never itself", async () => {
    opening(
      [
        reply({
          ...MODEL_WAITING,
          triesMade: [
            {
              ...SURE_TRY,
              values: [
                {
                  ...SURE_TRY.values[0],
                  decision: {
                    outcome: "refused",
                    why: `Too${ZERO_WIDTH_SPACE}short.`,
                  },
                },
              ],
              review: { asked: true, by: SURE_TRY.producedBy },
            },
            MODEL_WAITING.triesMade[1],
          ],
        }),
      ],
      `${PAGE}?try=1`,
    );

    const opened = await screen.findByRole("dialog", {
      name: "Try 1 of the 2 it allows",
    });
    const marker = within(opened).getByRole("img", { name: SHOWS_NOTHING });
    expect(opened).toHaveTextContent("Refused: TooU+200Bshort.");
    expect(marker.closest("bdi")?.textContent).toBe("TooU+200Bshort.");
    expect(opened.innerHTML).not.toContain(ZERO_WIDTH_SPACE);
  });

  it("says in a model's try opened how sure it was of the value picked, beneath where that value stands", async () => {
    opening([reply(MODEL_WAITING)], `${PAGE}?try=1`);

    const opened = await screen.findByRole("dialog", {
      name: "Try 1 of the 2 it allows",
    });

    const said = fieldsIn(opened.querySelector("dl")!);
    expect(said["How sure"]).toBe("87%");
    expect(Object.keys(said).indexOf("How sure")).toBe(
      Object.keys(said).indexOf(setApart("Summary")) + 1,
    );
  });

  it.each([
    ["a person's step, whose tries carry why and never how sure", WAITING],
    [
      "a model's step whose words are withheld from the reader",
      {
        ...MODEL_WAITING,
        triesMade: [
          {
            ...SURE_TRY,
            values: [
              {
                field: "summary",
                withheld: true,
                now: "refused",
                decision: { outcome: "refused", why: "Too short." },
              },
            ],
          },
          {
            ...MODEL_WAITING.triesMade[1],
            values: [
              { field: "summary", withheld: true, now: "waiting_on_review" },
            ],
          },
        ],
      },
    ],
  ])(
    "says nothing of how sure in the try opened of %s",
    async (_case, page) => {
      opening([reply(page)], `${PAGE}?try=1`);

      const opened = await screen.findByRole("dialog", {
        name: "Try 1 of the 2 it allows",
      });

      expect(within(opened).queryByText("How sure")).toBeNull();
      expect(opened).not.toHaveTextContent(/\d+%/);
    },
  );

  it("offers what a code step's code gave back as it came, shut until it is opened, beneath what went wrong", async () => {
    opening([reply(ERRORED)], `${PAGE}?try=1`);
    const opened = await screen.findByRole("dialog", {
      name: "Try 1 of the 2 it allows",
    });
    const control = within(opened).getByRole("button", {
      name: "What its code gave back, in full",
    });
    expect(opened).toHaveTextContent(
      "The reference it gave back is not text. (cut short here)",
    );
    expect(
      within(opened).getByText("The reference it gave back is not text.")
        .tagName,
    ).toBe("BDI");
    expect(control).toHaveAttribute("aria-expanded", "false");
    expect(opened).not.toHaveTextContent('{"reference": 7}');

    await userEvent.click(control);

    expect(control).toHaveAttribute("aria-expanded", "true");
    expect(
      document.getElementById(control.getAttribute("aria-controls")!),
    ).toHaveTextContent('{"reference": 7}');
  });

  it("shuts what code gave back as another try is turned to, whatever was left open in the one before", async () => {
    const { router } = opening(
      [
        reply({
          ...ERRORED,
          step: {
            ...ERRORED.step,
            tries: { current: 3, declared: 3, beyond: false },
          },
          triesMade: [
            ERRORED_TRY,
            { ...ERRORED_TRY, number: 2, returned: '{"reference": 8}' },
            { ...RUNNING_CODE_TRY, number: 3 },
          ],
        }),
      ],
      `${PAGE}?try=1`,
    );
    const first = await screen.findByRole("dialog", {
      name: "Try 1 of the 3 it allows",
    });
    await userEvent.click(
      within(first).getByRole("button", {
        name: "What its code gave back, in full",
      }),
    );

    await act(() => router.navigate(`${PAGE}?try=2`));

    const second = await screen.findByRole("dialog", {
      name: "Try 2 of the 3 it allows",
    });
    expect(
      within(second).getByRole("button", {
        name: "What its code gave back, in full",
      }),
    ).toHaveAttribute("aria-expanded", "false");
    expect(second).not.toHaveTextContent('{"reference": 8}');
    expect(second).not.toHaveTextContent('{"reference": 7}');
  });

  it("says why this system found a code step's try went wrong in the reader's words, its field by its label, and that what came back was not kept rather than offering it", async () => {
    opening([reply(UNKEPT)], `${PAGE}?try=1`);

    const opened = await screen.findByRole("dialog", {
      name: "Try 1 of the 2 it allows",
    });

    expect(fieldsIn(opened.querySelector("dl")!)).toMatchObject({
      "How it ended": "It went wrong.",
      "What went wrong": `Its code gave a value for ${setApart("Claim reference")} that fits the field but is longer than any one value may be kept.`,
      "What its code gave back": "It could not be kept, so it cannot be shown.",
    });
    expect(
      within(opened).queryByRole("button", {
        name: "What its code gave back, in full",
      }),
    ).toBeNull();
    expect(opened).not.toHaveTextContent(/too_long_to_keep|\(cut short here\)/);
  });

  it.each([
    {
      returnState: "kept",
      returned: '{"reference":"A returned claim reference"}',
      erroredFor: { reason: "too_long", field: "reference" },
      output: undefined,
      expected: `Its code gave a value for ${setApart("Claim reference")} longer than that field allows.`,
    },
    {
      returnState: "not kept",
      returned: undefined,
      erroredFor: {
        reason: "too_long",
        field: "reference",
        returnedNotKept: true,
      },
      output: undefined,
      expected: `Its code gave a value for ${setApart("Claim reference")} longer than that field allows.`,
    },
    {
      returnState: "not returned",
      returned: undefined,
      erroredFor: {
        reason: "gives_otherwise",
        field: "reference",
        readBy: { output: "summary" },
      },
      output: SUMMARY,
      expected: `What its code gave back as ${setApart("Claim reference")} no longer matched what this workflow was to give back as ${setApart("Summary")}.`,
    },
  ])(
    "shows the reason with returned evidence only when it is present, where the return is $returnState",
    async ({ returned, erroredFor, output, expected }) => {
      const page = {
        ...UNKEPT,
        declarations: {
          ...UNKEPT.declarations,
          [VERSION]: {
            ...DECLARATIONS[VERSION],
            gives: output ? [output] : [],
          },
        },
        triesMade: [
          {
            ...UNKEPT.triesMade[0],
            returned,
            erroredFor,
          },
        ],
      };
      opening([reply(page)], `${PAGE}?try=1`, [
        reply({
          run: page.run,
          steps: [page.step],
          gaveBack: output
            ? { declares: "values", standing: [] }
            : { declares: "nothing" },
          declarations: page.declarations,
        }),
      ]);
      const opened = await screen.findByRole("dialog", {
        name: "Try 1 of the 2 it allows",
      });
      const shown = fieldsIn(opened.querySelector("dl")!);
      expect(shown["What went wrong"]).toBe(expected);
      const full = within(opened).queryByRole("button", {
        name: "What its code gave back, in full",
      });
      if (returned !== undefined) {
        expect(full).toBeVisible();
        expect(shown["What its code gave back"]).toBe(
          "What its code gave back, in full",
        );
        expect(opened).not.toHaveTextContent(returned);
        await userEvent.click(full!);
        expect(opened).toHaveTextContent(returned);
      } else if (erroredFor.returnedNotKept === true) {
        expect(full).toBeNull();
        expect(shown["What its code gave back"]).toBe(
          "It could not be kept, so it cannot be shown.",
        );
      } else {
        expect(full).toBeNull();
        expect(shown).not.toHaveProperty("What its code gave back");
      }
    },
  );

  it.each([
    [
      "one that did not fit says which way, beneath how it ended",
      1,
      "What came back did not fit what was declared.The model stopped at the most it may give back, so its answer was cut short.",
    ],
    ["one still waiting says only how it ended", 2, "It waits on review."],
  ])("says of a model's try opened, %s", async (_case, number, ended) => {
    opening([reply(MODEL_MISFIT)], `${PAGE}?try=${number}`);

    const opened = await screen.findByRole("dialog", {
      name: `Try ${number} of the 2 it allows`,
    });

    expect(fieldsIn(opened.querySelector("dl")!)["How it ended"]).toBe(ended);
    expect(opened).not.toHaveTextContent("cut_off");
  });

  it.each([
    [
      "did_not_fit",
      "The model's review did not fit what it was asked for, so it was not taken, and this try is spent.",
    ],
    [
      "nothing_came_back",
      "The model's review was sent and nothing came back, so this try is spent.",
    ],
    ["errored", "small, whose review went wrong"],
  ])(
    "draws a lost model review %s separately from a production's outcome",
    async (lost, sentence) => {
      const reviewed = {
        ...SURE_TRY,
        review: {
          asked: true,
          by: { kind: "model", model: "small" },
          wentWrong: true,
          lost,
          ...(lost === "did_not_fit" ? { didNotFit: "words_missing" } : {}),
        },
        ended: "nothing_came_back",
      };
      opening(
        [reply({ ...MODEL_WAITING, triesMade: [reviewed] })],
        `${PAGE}?try=1`,
      );
      const opened = await screen.findByRole("dialog", {
        name: "Try 1 of the 2 it allows",
      });
      expect(fieldsIn(opened.querySelector("dl")!)["How it ended"]).toContain(
        sentence,
      );
      const result = screen
        .getByRole("table", { name: /The tries/, hidden: true })
        .querySelector("tbody tr")!
        .querySelectorAll("td")[2];
      const ended =
        within(opened).getByText("How it ended").nextElementSibling!;
      if (lost === "errored") {
        expect(ended.querySelector("bdi")).toHaveTextContent("small");
        expect(ended.querySelector("span")).toHaveTextContent(
          "whose review went wrong",
        );
      } else {
        expect(ended).not.toHaveTextContent("whose review went wrong");
      }
      expect(result).not.toHaveTextContent("whose review went wrong");
      expect(
        fieldsIn(opened.querySelector("dl")!)["How it ended"],
      ).not.toContain("Nothing came back.");
      if (lost === "did_not_fit") {
        expect(opened).toHaveTextContent(
          "The review refused a value without saying why.",
        );
      }
      expect(opened).not.toHaveTextContent(lost);
    },
  );

  it("separates a failed reviewer's name from a try result with no value", async () => {
    const reviewed = {
      ...SURE_TRY,
      values: [],
      review: {
        asked: true,
        by: { kind: "model", model: "small" },
        wentWrong: true,
        lost: "errored",
      },
      ended: "nothing_came_back",
    };
    opening(
      [reply({ ...MODEL_WAITING, triesMade: [reviewed] })],
      `${PAGE}?try=1`,
    );
    const opened = await screen.findByRole("dialog", {
      name: "Try 1 of the 2 it allows",
    });
    const result = screen
      .getByRole("table", { name: /The tries/, hidden: true })
      .querySelector("tbody tr")!
      .querySelectorAll("td")[2];
    expect(result.querySelector("bdi")).toHaveTextContent("small");
    expect(result.querySelector("span")).toHaveTextContent(
      "whose review went wrong",
    );
    expect(fieldsIn(opened.querySelector("dl")!)["How it ended"]).toBe(
      "small, whose review went wrong",
    );
  });

  it("keeps a picked value first and names a review error separately", async () => {
    const reviewed = {
      ...SURE_TRY,
      review: {
        asked: true,
        by: { kind: "model", model: "small" },
        wentWrong: true,
        lost: "errored",
      },
      ended: "nothing_came_back",
    };
    opening(
      [reply({ ...MODEL_WAITING, triesMade: [reviewed] })],
      `${PAGE}?value=summary&try=1`,
    );
    const opened = await screen.findByRole("dialog", {
      name: "Try 1 of the 2 it allows",
    });
    const ended = within(opened).getByText("How it ended").nextElementSibling!;
    expect(ended).toHaveTextContent("Refused on review.");
    expect(ended).toHaveTextContent("small, whose review went wrong");
    expect(ended.querySelector("bdi")).toHaveTextContent("small");
    expect(ended.querySelector("span")).toHaveTextContent(
      "whose review went wrong",
    );
  });

  it("lists each time a try's call was turned away before it was sent, right after how it ended, none of them a try", async () => {
    opening([reply(MODEL_MISFIT)], `${PAGE}?try=1`);

    const opened = await screen.findByRole("dialog", {
      name: "Try 1 of the 2 it allows",
    });

    const said = fieldsIn(opened.querySelector("dl")!);
    const labels = Object.keys(said);
    expect(labels.indexOf("Times the model would not take it")).toBe(
      labels.indexOf("How it ended") + 1,
    );
    expect(said["Times the model would not take it"]).toBe(
      [
        `Turned away ${whenText("2026-09-26T08:58:00Z")}, and sent again by itself.`,
        "The model said: Too many asks.",
        `Turned away ${whenText("2026-09-26T08:59:00Z")}, and sent again by itself.`,
        "None of these is a try, and none of them cost anything.",
      ].join(""),
    );
    expect(within(opened).getByText("Too many asks.").tagName).toBe("BDI");
  });

  it("draws what a model said turning a call away, in the try opened, a character in it that shows nothing as a marker and never itself", async () => {
    opening(
      [
        reply({
          ...MODEL_MISFIT,
          triesMade: [
            {
              ...MISFIT_TRY,
              turnedAway: [
                { ...TURNED_AWAY[0], said: `Too${ZERO_WIDTH_SPACE}many.` },
              ],
            },
            MODEL_WAITING.triesMade[1],
          ],
        }),
      ],
      `${PAGE}?try=1`,
    );

    const opened = await screen.findByRole("dialog", {
      name: "Try 1 of the 2 it allows",
    });
    const marker = within(opened).getByRole("img", { name: SHOWS_NOTHING });
    expect(opened).toHaveTextContent("The model said: TooU+200Bmany.");
    expect(marker.closest("bdi")?.textContent).toBe("TooU+200Bmany.");
    expect(opened.innerHTML).not.toContain(ZERO_WIDTH_SPACE);
  });

  it("shows an unsent hold's turnaways beneath the hold without inventing a try", async () => {
    opening([reply(HELD_TURNED_AWAY)], `${PAGE}?try=1`);

    const held = await screen.findByText(
      "The model it names would not take it. Somebody who may start a run can try sending it.",
    );
    expect(held).toHaveTextContent(
      `Turned away ${whenText("2026-09-26T08:59:00Z")}.`,
    );
    expect(held).not.toHaveTextContent("It waits on");
    expect(screen.getByText("No try has been made yet.")).toBeInTheDocument();
    expect(screen.queryByText("Try 0 of the 2 it allows")).toBeNull();
    expect(fieldsIn()["Tries"]).toBeUndefined();
    expect(screen.queryByRole("dialog")).toBeNull();
    expect(
      screen.queryByRole("button", { name: "Try 1 of the 2 it allows" }),
    ).toBeNull();
  });

  it("keeps an earlier sent try's turnaways in that try while an unnumbered production is held", async () => {
    opening([reply(HELD_AGAIN)], `${PAGE}?try=1`);

    const opened = await screen.findByRole("dialog", {
      name: "Try 1 of the 2 it allows",
    });
    const held = screen.getByText(/^The model it names would not take it\./);
    expect(document.querySelector("dl")).toHaveTextContent(
      "Try 1 of the 2 it allows",
    );
    expect(document.querySelector("dl")).not.toHaveTextContent("Try 0");
    expect(held).toHaveTextContent(
      `Turned away ${whenText("2026-09-26T09:05:00Z")}.`,
    );
    expect(held).not.toHaveTextContent(whenText("2026-09-26T08:58:00Z"));
    expect(
      fieldsIn(opened.querySelector("dl")!)[
        "Times the model would not take it"
      ],
    ).toBe(
      [
        `Turned away ${whenText("2026-09-26T08:58:00Z")}, and sent again by itself.`,
        "The model said: Too many asks.",
        `Turned away ${whenText("2026-09-26T08:59:00Z")}, and sent again by itself.`,
        "None of these is a try, and none of them cost anything.",
      ].join(""),
    );
    expect(within(opened).getByText("Too many asks.").tagName).toBe("BDI");
    expect(opened).not.toHaveTextContent(whenText("2026-09-26T09:05:00Z"));
    expect(
      screen.queryByRole("button", { name: "Try 2 of the 2 it allows" }),
    ).toBeNull();
  });

  it("says what a model's call said went wrong is withheld from the reader, never as nothing said", async () => {
    opening(
      [
        reply({
          ...MODEL_WAITING,
          triesMade: [
            {
              ...SURE_TRY,
              ended: "nothing_came_back",
              wentWrong: { withheld: true },
            },
            MODEL_WAITING.triesMade[1],
          ],
        }),
      ],
      `${PAGE}?try=1`,
    );

    const opened = await screen.findByRole("dialog", {
      name: "Try 1 of the 2 it allows",
    });

    expect(fieldsIn(opened.querySelector("dl")!)["What went wrong"]).toBe(
      "Withheld from you.",
    );
    expect(opened).not.toHaveTextContent("(cut short here)");
  });

  it.each([
    [
      "a try any call of which the model did not count",
      MODEL_MISFIT,
      1,
      "All of it is as this system measured it, as no model said what it counted.",
      ["Part of", "not known"],
    ],
    [
      "a try nothing came back for",
      {
        ...MODEL_MISFIT,
        triesMade: [
          {
            ...MISFIT_TRY,
            cost: { ...MISFIT_TRY.cost, cameBackUnknown: true },
          },
          MODEL_WAITING.triesMade[1],
        ],
      },
      1,
      "All of it is as this system measured it, as no model said what it counted.What came back is not known for every call yet, and is counted here as nothing.",
      ["Both as", "What was sent is as"],
    ],
    [
      "a try the model counted all of",
      MODEL_MISFIT,
      2,
      "",
      ["as this system measured", "not known"],
    ],
  ])(
    "says who counted what %s cost, beneath the count in the list the tries are picked in",
    async (_case, page, number, counted, never) => {
      opening([reply(page)]);
      await heading();

      const cost = tryRows()[number - 1]!.at(-1)!;
      expect(cost).toBe(`1,500 in all: 1,200 sent, 300 came back${counted}`);
      for (const words of never) {
        expect(cost).not.toContain(words);
      }
    },
  );

  it("says a step's cost is some or all of it as this system measured it where any call of it was", async () => {
    opening([reply(MODEL_MISFIT)]);
    await heading();

    expect(fieldsIn()["Cost"]).toBe(
      `3,000 in all: 2,400 sent, 600 came back${SOME_MEASURED}`,
    );
    expect(fieldsIn()["Cost"]).not.toContain("not known");
  });

  it("says who counted a try's cost in the list the tries are picked in only of a try any call of which was measured here", async () => {
    opening([reply(MODEL_MISFIT)]);
    await heading();

    const [measured, counted] = tryRows().map((cells) => cells.at(-1));
    expect(measured).toBe(
      "1,500 in all: 1,200 sent, 300 came backAll of it is as this system measured it, as no model said what it counted.",
    );
    expect(counted).toBe("1,500 in all: 1,200 sent, 300 came back");
  });

  it("opens again the value and the try its address names", async () => {
    opening([reply(TWO_VALUES)], `${PAGE}?sort=run&value=reply&try=1`);

    const opened = await screen.findByRole("dialog", {
      name: "Try 1 of the 2 it allows",
    });
    expect(
      within(opened)
        .getAllByRole("term")
        .map((term) => term.textContent),
    ).toContain(setApart("Reply"));
    expect(opened).toHaveTextContent("Sorry.");
    expect(opened).not.toHaveTextContent("A fire.");
    expect(
      screen.getByRole("heading", {
        level: 2,
        name: /^The tries/,
        hidden: true,
      }),
    ).toHaveTextContent(`The tries of ${setApart("Reply")}`);
  });

  it("lists the tries of the first value where its address names one the step does not give", async () => {
    opening([reply(TWO_VALUES)], `${PAGE}?value=gone`);
    await heading();

    expect(
      screen.getByRole("heading", { level: 2, name: /^The tries/ }),
    ).toHaveTextContent(`The tries of ${setApart("Summary")}`);
    expect(
      screen.getByRole("button", {
        name: `See the tries of ${setApart("Summary")}`,
      }),
    ).toHaveAttribute("aria-pressed", "true");
    expect(
      screen.getByRole("button", {
        name: `See the tries of ${setApart("Reply")}`,
      }),
    ).toHaveAttribute("aria-pressed", "false");
    expect(screen.queryByText(/gone/)).toBeNull();
  });

  it("lists the tries of another value once it is picked, keeping the list's address and closing no try that is not open", async () => {
    const { router } = opening(
      [reply(TWO_VALUES)],
      `${PAGE}?sort=run&value=summary`,
    );
    await heading();
    const summary = screen.getByRole("button", {
      name: `See the tries of ${setApart("Summary")}`,
    });
    const replyButton = screen.getByRole("button", {
      name: `See the tries of ${setApart("Reply")}`,
    });
    expect(summary).toHaveAttribute("aria-pressed", "true");

    await userEvent.click(replyButton);

    expect(
      screen.getByRole("heading", { level: 2, name: /^The tries/ }),
    ).toHaveTextContent(`The tries of ${setApart("Reply")}`);
    expect(replyButton).toHaveAttribute("aria-pressed", "true");
    expect(summary).toHaveAttribute("aria-pressed", "false");
    expect(router.state.location.search).toBe("?sort=run&value=reply");
    expect(screen.queryByRole("dialog")).toBeNull();
  });

  it("draws no value in a try of a step that gives nothing back, and names none in its tries", async () => {
    const { cameOut: _cameOut, ...nothingOut } = WAITING;
    opening([
      reply({
        ...nothingOut,
        declarations: {
          ...DECLARATIONS,
          [QUESTION_VERSION]: { ...DECLARATIONS[QUESTION_VERSION], gives: [] },
        },
        triesMade: [{ ...WAITING_TRY, values: [] }],
      }),
    ]);
    await heading();

    await userEvent.click(
      screen.getByRole("button", { name: "Try 2 of the 2 it allows" }),
    );

    const opened = await screen.findByRole("dialog");
    expect(
      within(opened)
        .getAllByRole("term")
        .map((term) => term.textContent),
    ).toEqual(["Asked for by", "Produced by", "Reviewed by", "How it ended"]);
    expect(opened).not.toHaveTextContent("It gave nothing back for this.");
    expect(opened).not.toHaveTextContent("Nothing: it calls no model.");
    expect(
      screen.getByRole("heading", {
        level: 2,
        name: /^The tries/,
        hidden: true,
      }),
    ).toHaveTextContent(/^The tries$/);
  });

  it("says what a model said, and the words its refusal was given in, are withheld from the reader, naming the model", async () => {
    opening([
      reply({
        ...WAITING,
        triesMade: [
          {
            ...REFUSED_TRY,
            producedBy: { kind: "model", model: "small", mode: "careful" },
            why: undefined,
            values: [
              {
                field: "summary",
                withheld: true,
                now: "refused",
                decision: { outcome: "refused", withheld: true },
              },
            ],
          },
        ],
      }),
    ]);
    await heading();
    expect(tryRows()[0]![1]).toBe("small in careful");

    await userEvent.click(
      screen.getByRole("button", { name: "Try 1 of the 2 it allows" }),
    );

    const opened = await screen.findByRole("dialog");
    expect(opened).toHaveTextContent("Withheld from you.");
    expect(opened).toHaveTextContent("Refused, in words withheld from you.");
    expect(opened).not.toHaveTextContent("A fire.");
    expect(opened).not.toHaveTextContent("permission");
  });

  it("says what held a step back, who stopped it and when, and who can let it go, before anything else it says of the step, and not again", async () => {
    opening([
      reply({
        ...WAITING,
        step: {
          ...ROW,
          state: "held_back",
          where: {
            kind: "held_back",
            reason: "entry_stopped",
            since: "2026-09-26T09:00:00Z",
            stopped: { what: "entry", by: CAT, at: "2026-09-26T09:00:00Z" },
            waitsOn: "starter",
          },
        },
      }),
    ]);
    const title = await heading();

    const held = screen.getByText(
      `What it runs was stopped, so nothing is produced until that is undone. Stopped by ${setApart("Cat")}, ${whenText("2026-09-26T09:00:00Z")}. Somebody who may revoke an entry can let what was stopped go.`,
    );
    expect(held).not.toHaveTextContent("It waits on");
    const firstList = document.querySelector("dl")!;
    expect(
      held.compareDocumentPosition(firstList) &
        Node.DOCUMENT_POSITION_FOLLOWING,
    ).toBeTruthy();
    expect(
      title.compareDocumentPosition(held) & Node.DOCUMENT_POSITION_FOLLOWING,
    ).toBeTruthy();
    expect(fieldsIn()["Where it is"]).toBe("Held back");
  });

  it("says a step held back on its length why, in the reader's words, before anything else it says of the step, and not again", async () => {
    opening([
      reply({
        ...MODEL_WAITING,
        step: {
          ...MODEL_WAITING.step,
          state: "held_back",
          where: {
            kind: "held_back",
            reason: "too_long",
            since: "2026-09-26T09:00:00Z",
            waitsOn: "starter",
          },
          withheld: [{ act: "try_sending", refusal: "ACT_NOT_PERMITTED" }],
        },
      }),
    ]);
    await heading();

    const held = screen.getByText(
      "What it would send is longer than the model it names takes, so it was not sent, and nothing was spent on it. Somebody who may start a run can try sending it.",
    );
    expect(screen.getByText("You may not try sending it.")).toBeVisible();
    expect(
      held.compareDocumentPosition(document.querySelector("dl")!) &
        Node.DOCUMENT_POSITION_FOLLOWING,
    ).toBeTruthy();
    expect(fieldsIn()["Where it is"]).toBe("Held back");
    expect(screen.queryByText(UNKNOWN_SAID)).toBeNull();
    expect(screen.queryByText(/too_long/)).toBeNull();
  });

  /**
   * Timeouts are faked, so nothing here waits the way Testing Library does: its waiting drains on a timeout,
   * which would never come. What is in flight is let land a turn at a time instead.
   */
  it("keeps a waiting step's page up as often as each read of it says, saying nothing of reading, and stops once a read names no wait", async () => {
    vi.useFakeTimers({ toFake: ["setTimeout", "clearTimeout"] });
    const reviewed = {
      ...WAITING,
      rereadAfterSeconds: 7,
      triesMade: [
        REFUSED_TRY,
        {
          ...WAITING_TRY,
          review: { asked: true, by: { kind: "person", person: DAN } },
        },
      ],
    };
    const { rereadAfterSeconds: _wait, ...unkept } = reviewed;
    const { sent } = opening([
      reply({ ...WAITING, rereadAfterSeconds: 7 }),
      reply(reviewed),
      reply({ ...unkept, run: { ...HEADER, state: "done" } }),
    ]);
    const reads = () =>
      requestsTo(sent).filter((each) => each === `GET ${STEP}`).length;
    await landing(() => screen.queryByRole("heading", { level: 1 }) !== null);
    expect(tryRows()[1]![2]).toBe("Nobody has reviewed it yet.");

    await act(() => vi.advanceTimersByTimeAsync(6999));
    expect(reads()).toBe(1);
    await act(() => vi.advanceTimersByTimeAsync(1));
    await landing(() => tryRows()[1]![2] === setApart("Dan"));
    await act(() => vi.advanceTimersByTimeAsync(7000));
    await landing(() => reads() === 3);
    await act(() => vi.advanceTimersByTimeAsync(60_000));

    expect(reads()).toBe(3);
    expect(
      requestsTo(sent).filter((each) => each === `GET ${STEPS}`),
    ).toHaveLength(1);
    expect(vi.getTimerCount()).toBe(1);
    expect(screen.queryByText("Still reading…")).toBeNull();
  });

  it("reads a step's page only once where the read names no wait, though the run it is of runs", async () => {
    vi.useFakeTimers({ toFake: ["setTimeout", "clearTimeout"] });
    const { rereadAfterSeconds: _wait, ...unkept } = WAITING;
    const { sent } = opening([reply(unkept), reply(WAITING)]);
    await landing(() => screen.queryByRole("heading", { level: 1 }) !== null);

    await act(() => vi.advanceTimersByTimeAsync(60_000));

    expect(
      requestsTo(sent).filter((each) => each === `GET ${STEP}`),
    ).toHaveLength(1);
    expect(vi.getTimerCount()).toBe(1);
    expect(fieldsIn()["Where it is"]).toMatch(/^Waiting/);
  });

  it("says once, and only there, where a read again finds the step or its run in another state", async () => {
    vi.useFakeTimers({ toFake: ["setTimeout", "clearTimeout"] });
    const { where: _where, ...placeless } = ROW;
    const done = { ...WAITING, step: { ...placeless, state: "done" } };
    opening([reply(WAITING), reply(done), reply(done)]);
    await landing(() => screen.queryByRole("heading", { level: 1 }) !== null);
    // The page's own region comes first; the read's own follows it.
    const news = screen.getAllByRole("status")[0]!;
    expect(news).toHaveTextContent(/^$/);

    await act(() => vi.advanceTimersByTimeAsync(5000));
    await landing(() => fieldsIn()["Where it is"] === "Done");

    expect(news).toHaveTextContent(`${setApart("Summarise")}: now Done.`);
    expect(news).not.toHaveTextContent("Run 7");

    await act(() => vi.advanceTimersByTimeAsync(5000));
    await landing(() => news.textContent === "");

    expect(fieldsIn()["Where it is"]).toBe("Done");
  });

  it("says a step is now somewhere it cannot say yet in words of their own, never as a state", async () => {
    vi.useFakeTimers({ toFake: ["setTimeout", "clearTimeout"] });
    const { where: _where, ...placeless } = ROW;
    opening([
      reply(WAITING),
      reply({ ...WAITING, step: { ...placeless, state: "paused" } }),
    ]);
    await landing(() => screen.queryByRole("heading", { level: 1 }) !== null);
    const news = screen.getAllByRole("status")[0]!;

    await act(() => vi.advanceTimersByTimeAsync(5000));
    await landing(() => news.textContent !== "");

    expect(news).toHaveTextContent(
      `${setApart("Summarise")}: now somewhere this page cannot say yet.`,
    );
    expect(news).not.toHaveTextContent("Not something");
  });

  it("stops saying where things now are a while after reads stop, leaving nothing stale to come on", async () => {
    vi.useFakeTimers({ toFake: ["setTimeout", "clearTimeout"] });
    const { where: _where, ...placeless } = ROW;
    const { rereadAfterSeconds: _wait, ...unkept } = WAITING;
    const { sent } = opening([
      reply(WAITING),
      reply({
        ...unkept,
        run: { ...HEADER, state: "done" },
        step: { ...placeless, state: "done" },
      }),
    ]);
    await landing(() => screen.queryByRole("heading", { level: 1 }) !== null);
    const news = screen.getAllByRole("status")[0]!;
    await act(() => vi.advanceTimersByTimeAsync(5000));
    await landing(() => fieldsIn()["Where it is"] === "Done");
    expect(news).toHaveTextContent("Run 7: now Done.");

    await act(() => vi.advanceTimersByTimeAsync(5000));

    expect(news).toHaveTextContent(/^$/);
    expect(
      requestsTo(sent).filter((each) => each === `GET ${STEP}`),
    ).toHaveLength(2);
  });

  it("says a step refused in place, the way back through Work and History still there, naming neither run nor step", async () => {
    opening([[JSON.stringify({ code: "STEP_NOT_IN_VIEW" }), 404]]);

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "That step is not in view.",
    );
    expect(screen.queryByRole("heading", { level: 1 })).toBeNull();

    const path = screen.getByRole("navigation", { name: "Breadcrumb" });
    expect(
      within(path)
        .getAllByRole("link")
        .map((link) => link.textContent),
    ).toEqual(["Work", "History"]);
    expect(within(path).getAllByRole("listitem")).toHaveLength(2);
  });
});
