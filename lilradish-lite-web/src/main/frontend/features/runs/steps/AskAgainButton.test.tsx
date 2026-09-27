import { ThemeProvider } from "@mui/material/styles";
import { act, render, screen, within } from "@testing-library/react";
import type { ReactNode } from "react";
import { RouterProvider, createMemoryRouter } from "react-router";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { StandingProvider } from "../../../app/standing/StandingContext";
import { theme } from "../../../lib/theme/theme";
import { requestsTo, serving, type Reply } from "../../../testutil/answering";
import { laidOutAt } from "../../../testutil/layout";
import { answered, inGroup } from "../../../testutil/standingRead";
import { RunPage } from "../RunPage";
import { StepPage } from "./StepPage";

const GROUP = "00000003-0000-4000-8000-000000000d11";

const RUN_ID = "00000008-0000-4000-8000-000000000d11";

const VERSION = "00000007-0000-4000-8000-000000000d11";

const QUESTION_VERSION = "00000007-0000-4000-8000-000000000d12";

const SUMMARISE = "00000009-0000-4000-8000-000000000d11";

const PAGE = `/groups/${GROUP}/work/${RUN_ID}`;

const RUN = `/api/groups/${GROUP}/runs/${RUN_ID}`;

const STEPS = `${RUN}/steps`;

const STEP = `${STEPS}/${SUMMARISE}`;

const CAT = { userId: "000d11", displayName: "Cat" };

const DAN = { userId: "000d12", displayName: "Dan" };

/** What is said where the ask is refused for the act itself: the rule of who may answer a step and ask again. */
const ASK_RULE =
  "A group's steps are answered, and asked for again, only by a role in it that may answer one.";

const CLAIM = {
  runId: RUN_ID,
  number: 7,
  workflow: {
    entryId: "00000006-0000-4000-8000-000000000d11",
    name: "Handle a claim",
    version: 3,
  },
  startedBy: { userId: "000d13", displayName: "Ada" },
  startedAt: "2026-09-26T08:00:00Z",
  state: "running",
  at: { stepId: SUMMARISE, name: "summarise" },
  spend: { sent: "0", cameBack: "0", spent: "0", cameBackUnknown: false },
  ceiling: { raiseNeedsApproval: false },
  acts: ["stop"],
};

const SUMMARY = {
  name: "summary",
  label: "Summary",
  kind: "text",
  longest: 1000,
  mustBeGiven: true,
};

const DECLARATIONS = {
  [VERSION]: { takes: [], gives: [] },
  [QUESTION_VERSION]: { takes: [], gives: [SUMMARY] },
};

const HEADER = {
  runId: RUN_ID,
  number: 7,
  versionId: VERSION,
  state: "running",
  at: { stepId: SUMMARISE, name: "summarise" },
  acts: ["stop"],
  progress: { done: 0, of: 1 },
};

/** Dan refused Cat's summary: try 2 is owed, and nobody has asked for it. */
const OWED_ROW = {
  stepId: SUMMARISE,
  order: 1,
  name: "summarise",
  runs: {
    kind: "question",
    entryId: "00000006-0000-4000-8000-000000000d12",
    name: "Summarise",
    version: 2,
    versionId: QUESTION_VERSION,
  },
  producer: { kind: "person" },
  reviewer: { kind: "person" },
  state: "waiting",
  where: {
    kind: "owed_try",
    open: false,
    beyond: false,
    since: "2026-09-26T09:30:00Z",
    waitsOn: "answer_step",
  },
  takesFrom: [],
  tries: { current: 1, declared: 2, beyond: false },
  cost: { callsAModel: false },
  gaveBack: [{ field: "summary", value: "A fire.", now: "refused" }],
  next: { number: 2, beyond: false },
  acts: ["answer", "ask_again"],
  withheld: [],
};

const FIRST_TRY = {
  number: 1,
  beyond: false,
  producedBy: { kind: "person", person: CAT },
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

/** The step's own page while try 2 is owed. */
const OWED_READ = reply({
  run: HEADER,
  declarations: DECLARATIONS,
  step: OWED_ROW,
  cameOut: [{ field: "summary", now: "refused" }],
  triesMade: [FIRST_TRY],
  answering: {
    number: 2,
    beyond: false,
    instruction: "Summarise the ticket.",
    gives: [SUMMARY],
    lastRefused: { number: 1, values: FIRST_TRY.values },
  },
});

/** The step once try 2 was asked of the person it names by `askedBy`, or by the run where none: open, and only answered now. */
function askedRead(askedBy: object | undefined): Reply {
  return reply({
    run: HEADER,
    declarations: DECLARATIONS,
    step: {
      ...OWED_ROW,
      where: { ...OWED_ROW.where, open: true, since: "2026-09-26T10:00:00Z" },
      tries: { current: 2, declared: 2, beyond: false },
      acts: ["answer"],
    },
    cameOut: [{ field: "summary", now: "refused" }],
    triesMade: [
      FIRST_TRY,
      {
        number: 2,
        beyond: false,
        askedBy,
        producedBy: { kind: "person" },
        values: [],
        review: { asked: false },
        ended: "open",
        cost: { callsAModel: false },
      },
    ],
    answering: {
      number: 2,
      beyond: false,
      instruction: "Summarise the ticket.",
      gives: [SUMMARY],
      lastRefused: { number: 1, values: FIRST_TRY.values },
    },
  });
}

const ASKED = askedRead(DAN);

/** A reply the server never sends. */
const UNANSWERED: Reply = new Promise(() => {});

function reply(body: object): Reply {
  return [JSON.stringify(body), 200];
}

function refusal(code: string, status: number): Reply {
  return [JSON.stringify({ code }), status];
}

function setApart(words: string): string {
  return `${String.fromCodePoint(0x2068)}${words}${String.fromCodePoint(0x2069)}`;
}

/** The run's steps as read, the step as `row` changes it. */
function stepsRead(row: object = {}): Reply {
  return reply({
    run: HEADER,
    declarations: DECLARATIONS,
    gaveBack: { declares: "nothing" },
    steps: [{ ...OWED_ROW, ...row }],
    rereadAfterSeconds: 5,
  });
}

/** The page at its address under a real router, with the standing it would be handed and only the server stood in for. */
function opening(
  routes: Readonly<Record<string, readonly Reply[]>>,
  path: string,
  element: ReactNode,
  address: string,
) {
  laidOutAt(0, 1200);
  const sent = serving(routes);
  const router = createMemoryRouter([{ path, element }], {
    initialEntries: [address],
  });
  const readAgain = vi.fn();
  const standing = answered(
    [],
    [inGroup(GROUP, "CLAIMS", "Claims", ["answer_step"])],
  );
  render(
    <ThemeProvider theme={theme}>
      <StandingProvider read={{ ...standing, reload: readAgain }}>
        <RouterProvider router={router} />
      </StandingProvider>
    </ThemeProvider>,
  );
  return { sent, readAgain };
}

function conversing(routes: Readonly<Record<string, readonly Reply[]>> = {}) {
  const onActed = vi.fn();
  const opened = opening(
    {
      [`GET ${RUN}`]: [reply(CLAIM), UNANSWERED],
      [`GET ${STEPS}`]: [stepsRead(), UNANSWERED],
      ...routes,
    },
    "/groups/:groupId/work/:runId",
    <RunPage drawing="conversation" onDetail={() => {}} onActed={onActed} />,
    PAGE,
  );
  return { ...opened, onActed };
}

/** What is in flight let land a turn at a time, until `landed` holds; failing where it never does. */
async function landing(landed: () => boolean): Promise<void> {
  for (let turn = 0; turn < 100 && !landed(); turn += 1) {
    await act(() => vi.advanceTimersByTimeAsync(0));
  }
  expect(landed()).toBe(true);
}

function message(): HTMLElement {
  return within(
    screen.getByRole("list", { name: "The conversation" }),
  ).getByRole("listitem", { name: setApart("Summarise") });
}

async function messageDrawn(): Promise<HTMLElement> {
  await landing(
    () => screen.queryByRole("list", { name: "The conversation" }) !== null,
  );
  return message();
}

function press(control: HTMLElement): void {
  act(() => control.click());
}

function puts(sent: ReturnType<typeof serving>): [string, RequestInit][] {
  return sent.mock.calls
    .filter(([, init]) => init?.method === "PUT")
    .map(([address, init]) => [String(address), init!]);
}

function asked(sent: ReturnType<typeof serving>, request: string): number {
  return requestsTo(sent).filter((each) => each === request).length;
}

beforeEach(() => {
  vi.useFakeTimers({ toFake: ["setTimeout", "clearTimeout"] });
});

afterEach(() => {
  vi.useRealTimers();
});

describe("AskAgainButton", () => {
  describe("in a run's conversation", () => {
    it("asks for the try the step owes with nothing sent, then shows the step as asked, reads the run again and gives the keyboard to its message", async () => {
      const { sent, onActed } = conversing({
        [`PUT ${STEP}/tries/2`]: [ASKED],
      });
      const drawn = await messageDrawn();

      press(within(drawn).getByRole("button", { name: "Ask again" }));
      await landing(() => onActed.mock.calls.length === 1);

      const [[address, init]] = puts(sent) as [[string, RequestInit]];
      expect(address).toBe(`${STEP}/tries/2`);
      expect(init.body).toBeUndefined();
      expect(puts(sent)).toHaveLength(1);
      expect(
        within(message()).getByText("Try 2 has been asked for."),
      ).toBeVisible();
      expect(
        within(message()).queryByRole("button", { name: "Ask again" }),
      ).toBeNull();
      expect(
        within(message()).getByRole("button", { name: "Answer it here" }),
      ).toBeVisible();
      expect(asked(sent, `GET ${RUN}`)).toBe(2);
      expect(document.activeElement).toBe(message());
      expect(screen.queryByRole("alert")).toBeNull();
    });

    it("says who asked for the try first once the step moved on, reading it again and handing that on, giving the keyboard to what it says", async () => {
      const { sent, onActed } = conversing({
        [`PUT ${STEP}/tries/2`]: [refusal("STEP_MOVED_ON", 409)],
        [`GET ${STEP}`]: [ASKED],
      });
      const drawn = await messageDrawn();

      press(within(drawn).getByRole("button", { name: "Ask again" }));
      await landing(() => onActed.mock.calls.length === 1);

      const alert = screen.getByRole("alert");
      expect(alert).toHaveTextContent(
        `Asked for already, by ${setApart("Dan")}.`,
      );
      expect(alert).not.toHaveTextContent("That step has moved on");
      expect(screen.getAllByRole("alert")).toHaveLength(1);
      expect(document.activeElement).toBe(alert.closest("[tabindex='-1']"));
      expect(asked(sent, `GET ${STEP}`)).toBe(1);
      expect(
        within(message()).getByText("Try 2 has been asked for."),
      ).toBeVisible();
      expect(puts(sent)).toHaveLength(1);
    });

    it("says nothing of the step having moved on while it is read again, holding Ask again", async () => {
      const { sent } = conversing({
        [`PUT ${STEP}/tries/2`]: [refusal("STEP_MOVED_ON", 409)],
        [`GET ${STEP}`]: [UNANSWERED],
      });
      const drawn = await messageDrawn();

      press(within(drawn).getByRole("button", { name: "Ask again" }));
      await landing(() => asked(sent, `GET ${STEP}`) === 1);

      expect(screen.queryByRole("alert")).toBeNull();
      expect(
        within(message()).getByRole("button", { name: "Ask again" }),
      ).toHaveAttribute("aria-disabled", "true");
      expect(puts(sent)).toHaveLength(1);
    });

    it("says the refusal's own sentence once the step moved on where the step read again names nobody who asked", async () => {
      const { onActed } = conversing({
        [`PUT ${STEP}/tries/2`]: [refusal("STEP_MOVED_ON", 409)],
        [`GET ${STEP}`]: [askedRead(undefined)],
      });
      const drawn = await messageDrawn();

      press(within(drawn).getByRole("button", { name: "Ask again" }));
      await landing(() => onActed.mock.calls.length === 1);

      expect(screen.getByRole("alert")).toHaveTextContent(
        "That step has moved on since it was read.",
      );
      expect(screen.queryByText(/already, by/)).toBeNull();
    });

    it("forgets a refusal once the step owes a later try than the one it was about", async () => {
      conversing({
        [`GET ${RUN}`]: [reply(CLAIM), reply(CLAIM), UNANSWERED],
        [`GET ${STEPS}`]: [
          stepsRead(),
          stepsRead({
            tries: { current: 2, declared: 3, beyond: false },
            next: { number: 3, beyond: false },
          }),
          UNANSWERED,
        ],
        [`PUT ${STEP}/tries/2`]: [refusal("ACT_NOT_PERMITTED", 403)],
      });
      const drawn = await messageDrawn();
      press(within(drawn).getByRole("button", { name: "Ask again" }));
      await landing(() => screen.queryByRole("alert") !== null);

      await act(() => vi.advanceTimersByTimeAsync(5000));
      await landing(() => screen.queryByRole("alert") === null);

      expect(
        within(message()).getByText("Try 2 of the 3 it allows"),
      ).toBeVisible();
      expect(
        within(message()).getByRole("button", { name: "Ask again" }),
      ).not.toHaveAttribute("aria-disabled");
      expect(screen.queryByText(ASK_RULE)).toBeNull();
    });

    it("says the ask is refused by the rule of who may answer a step and reads the reader's standing again, reading nothing of the step", async () => {
      const { sent, readAgain, onActed } = conversing({
        [`PUT ${STEP}/tries/2`]: [refusal("ACT_NOT_PERMITTED", 403)],
      });
      const drawn = await messageDrawn();

      press(within(drawn).getByRole("button", { name: "Ask again" }));
      await landing(() => screen.queryByRole("alert") !== null);

      expect(screen.getByRole("alert")).toHaveTextContent(ASK_RULE);
      expect(screen.getByRole("alert")).not.toHaveTextContent(
        "This is done only by a role that reaches it.",
      );
      expect(readAgain).toHaveBeenCalledTimes(1);
      expect(asked(sent, `GET ${STEP}`)).toBe(0);
      expect(onActed).not.toHaveBeenCalled();
    });

    it("draws Ask again alone where a code step's release gives otherwise than the workflow reads, saying the try goes past what the step allows", async () => {
      conversing({
        [`GET ${STEPS}`]: [
          stepsRead({
            runs: { kind: "code_step", codeStep: "file_claim" },
            producer: { kind: "code" },
            reviewer: { kind: "person" },
            gaveBack: undefined,
            state: "failed",
            where: {
              kind: "failed",
              reason: "tries_spent",
              since: "2026-09-26T09:30:00Z",
            },
            tries: { current: 2, declared: 2, beyond: false },
            next: { number: 3, beyond: true },
            acts: ["ask_again"],
            withheld: [{ act: "answer", refusal: "CODE_STEP_GIVES_OTHERWISE" }],
          }),
        ],
      });
      await landing(
        () => screen.queryByRole("list", { name: "The conversation" }) !== null,
      );

      const drawn = screen.getByRole("listitem", {
        name: setApart("summarise"),
      });

      expect(
        within(drawn).getByRole("button", { name: "Ask again" }),
      ).toBeVisible();
      expect(
        within(drawn).getByText("Try 3 goes past the 2 tries its step allows."),
      ).toBeVisible();
      expect(
        within(drawn).queryByRole("button", { name: "Answer it here" }),
      ).toBeNull();
    });
  });

  describe("on the step's own page", () => {
    it("asks for the try the step owes, then shows the step as asked and gives the keyboard to what came out", async () => {
      const onActed = vi.fn();
      const { sent } = opening(
        {
          [`GET ${STEP}`]: [OWED_READ, UNANSWERED],
          [`PUT ${STEP}/tries/2`]: [ASKED],
        },
        "/groups/:groupId/work/:runId/steps/:stepId",
        <StepPage onActed={onActed} />,
        `${PAGE}/steps/${SUMMARISE}`,
      );
      await landing(() => screen.queryByRole("heading", { level: 2 }) !== null);

      press(screen.getByRole("button", { name: "Ask again" }));
      await landing(() => onActed.mock.calls.length === 1);

      expect(puts(sent).map(([address]) => address)).toEqual([
        `${STEP}/tries/2`,
      ]);
      expect(screen.queryByRole("button", { name: "Ask again" })).toBeNull();
      expect(screen.getByText("Try 2 has been asked for.")).toBeVisible();
      expect(document.activeElement).toBe(
        screen.getByRole("heading", { name: "What came out" }),
      );
    });
  });
});
