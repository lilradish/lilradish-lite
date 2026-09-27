import { ThemeProvider } from "@mui/material/styles";
import { act, render, screen, within } from "@testing-library/react";
import type { ReactNode } from "react";
import { RouterProvider, createMemoryRouter } from "react-router";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { StandingProvider } from "../../../app/standing/StandingContext";
import { theme } from "../../../lib/theme/theme";
import { whenText } from "../../../lib/time/When";
import { requestsTo, serving, type Reply } from "../../../testutil/answering";
import { deferred } from "../../../testutil/deferred";
import { laidOutAt } from "../../../testutil/layout";
import { answered, inGroup } from "../../../testutil/standingRead";
import { RunPage } from "../RunPage";
import { StepPage } from "./StepPage";

const GROUP = "00000003-0000-4000-8000-000000000d21";

const RUN_ID = "00000008-0000-4000-8000-000000000d21";

const VERSION = "00000007-0000-4000-8000-000000000d21";

const QUESTION_VERSION = "00000007-0000-4000-8000-000000000d22";

const SUMMARISE = "00000009-0000-4000-8000-000000000d21";

const PAGE = `/groups/${GROUP}/work/${RUN_ID}`;

const RUN = `/api/groups/${GROUP}/runs/${RUN_ID}`;

const STEPS = `${RUN}/steps`;

const STEP = `${STEPS}/${SUMMARISE}`;

const SENDING = `${STEP}/sending`;

const HELD_SINCE = "2026-09-26T09:30:00Z";

const STOPPED_AT = "2026-09-26T09:40:00Z";

const ADA = { userId: "000d21", displayName: "Ada" };

const TRY_SENDING = "Try sending";

const SENT =
  "It will be sent if it can be. What comes of it shows here once it is known.";

const PENDING = "Asking for it to be sent.";

/** What is said where the press is refused for the act itself: the rule of who may start a run. */
const START_RULE =
  "A group's runs are started, renamed, stopped, opened again and given a ceiling, and a raise asked withdrawn, only by a role in it that may start one.";

const NOT_OFFERED =
  "Try sending is only for a call to a model that could not be sent: the model turned it away, it was too long for the model, or it is to a model, or a mode of one, that this deployment does not offer.";

const ENTRY_STOPPED =
  "What this step runs, or the workflow itself, is stopped, so no try is made on it until that is let go.";

const MOVED_ON = "That step has moved on since it was read.";

const TOO_LONG =
  "What it would send is longer than the model it names takes, so it was not sent, and nothing was spent on it.";

const CLAIM = {
  runId: RUN_ID,
  number: 7,
  workflow: {
    entryId: "00000006-0000-4000-8000-000000000d21",
    name: "Handle a claim",
    version: 3,
  },
  startedBy: ADA,
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

const NOTHING_SPENT = {
  callsAModel: true,
  sent: "0",
  cameBack: "0",
  spent: "0",
  cameBackUnknown: false,
};

/** A model's step held back as the model would not take its one try, offered to be sent. */
const HELD_ROW = {
  stepId: SUMMARISE,
  order: 1,
  name: "summarise",
  runs: {
    kind: "question",
    entryId: "00000006-0000-4000-8000-000000000d22",
    name: "Summarise",
    version: 2,
    versionId: QUESTION_VERSION,
  },
  producer: { kind: "model", model: "small" },
  state: "held_back",
  where: {
    kind: "held_back",
    reason: "turned_away",
    since: HELD_SINCE,
    waitsOn: "starter",
  },
  takesFrom: [],
  tries: { current: 1, declared: 2, beyond: false },
  cost: NOTHING_SPENT,
  acts: ["try_sending"],
  withheld: [],
};

/** The same step's value waiting on a model whose review of it the model turned away, offered to be sent again. */
const REVIEW_TURNED_AWAY_ROW = {
  ...HELD_ROW,
  state: "waiting",
  where: {
    kind: "waiting_on_review",
    values: [{ field: "summary", on: "model" }],
    since: HELD_SINCE,
    waitsOn: "starter",
  },
};

/** The same step once its try is out to the model. */
const OUT_ROW = {
  ...HELD_ROW,
  state: "running",
  where: { kind: "running", on: "call" },
  acts: [],
};

/** The same step held back where it was, once what it runs is stopped, sending held back from a reader who may. */
const STOPPED_ROW = {
  ...HELD_ROW,
  where: {
    ...HELD_ROW.where,
    stopped: { what: "entry", by: ADA, at: STOPPED_AT },
  },
  acts: [],
  withheld: [{ act: "try_sending", refusal: "ENTRY_STOPPED" }],
};

const HELD_TRY = {
  number: 1,
  beyond: false,
  producedBy: { kind: "model", model: "small" },
  values: [],
  review: { asked: false },
  ended: "open",
  cost: NOTHING_SPENT,
};

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

/** The step's own page, the step as `row` has it. */
function stepRead(row: object = HELD_ROW): Reply {
  return reply({
    run: HEADER,
    declarations: DECLARATIONS,
    step: row,
    triesMade: [HELD_TRY],
  });
}

/** The run's steps as read, the step as `row` changes it. */
function stepsRead(row: object = {}): Reply {
  return reply({
    run: HEADER,
    declarations: DECLARATIONS,
    gaveBack: { declares: "nothing" },
    steps: [{ ...HELD_ROW, ...row }],
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
    [inGroup(GROUP, "CLAIMS", "Claims", ["start_run"])],
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
      [`GET ${RUN}`]: [reply(CLAIM), reply(CLAIM), UNANSWERED],
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

function sendButton(): HTMLElement | null {
  return within(message()).queryByRole("button", { name: TRY_SENDING });
}

/** Pressed from the keyboard's place on it, as a reader pressing it would be. */
function press(control: HTMLElement): void {
  act(() => {
    control.focus();
    control.click();
  });
}

function puts(sent: ReturnType<typeof serving>): [string, RequestInit][] {
  return sent.mock.calls
    .filter(([, init]) => init?.method === "PUT")
    .map(([address, init]) => [String(address), init!]);
}

function asked(sent: ReturnType<typeof serving>, request: string): number {
  return requestsTo(sent).filter((each) => each === request).length;
}

/** The box a refusal is said in, which the keyboard is given. */
function noticeBox(): HTMLElement | null {
  const [first] = screen.queryAllByRole("alert");
  return first?.closest<HTMLElement>("[tabindex='-1']") ?? null;
}

beforeEach(() => {
  vi.useFakeTimers({ toFake: ["setTimeout", "clearTimeout"] });
});

afterEach(() => {
  vi.useRealTimers();
});

describe("TrySendingButton", () => {
  describe("in a run's conversation", () => {
    it("sends the try the step is held on with nothing sent, says in a region there before it that it will be sent, reads the run and its steps again and leaves the keyboard on the button", async () => {
      const { sent, onActed } = conversing({
        [`PUT ${SENDING}`]: [stepRead()],
      });
      const drawn = await messageDrawn();
      const regions = within(drawn).getAllByRole("status");

      press(within(drawn).getByRole("button", { name: TRY_SENDING }));
      await landing(
        () =>
          onActed.mock.calls.length === 1 && asked(sent, `GET ${STEPS}`) === 2,
      );

      const [[address, init]] = puts(sent) as [[string, RequestInit]];
      expect(address).toBe(SENDING);
      expect(init.body).toBeUndefined();
      expect(asked(sent, `GET ${RUN}`)).toBe(2);
      const said = within(message()).getByText(SENT);
      expect(regions).toContain(said.closest("[role='status']"));
      expect(document.activeElement).toBe(sendButton());
      expect(within(message()).queryByText(PENDING)).toBeNull();
      expect(screen.queryByRole("alert")).toBeNull();
    });

    it("sends nothing more while the step is not read since the press was answered", async () => {
      const { sent } = conversing({
        [`PUT ${SENDING}`]: [stepRead()],
      });
      const drawn = await messageDrawn();

      press(within(drawn).getByRole("button", { name: TRY_SENDING }));
      await landing(() => asked(sent, `GET ${STEPS}`) === 2);
      press(sendButton()!);
      await act(() => vi.advanceTimersByTimeAsync(0));

      expect(sendButton()).toHaveAttribute("aria-disabled", "true");
      expect(puts(sent)).toHaveLength(1);
    });

    /** Nothing on the wire moves when the engine drops a press or a model turns a review away again. */
    it.each([
      ["held back", HELD_ROW],
      [
        "waiting on a model that turned its review away",
        REVIEW_TURNED_AWAY_ROW,
      ],
    ])(
      "offers Try sending afresh on a step %s once it is next read, though it reads just as it did",
      async (_case, row) => {
        const again = deferred<readonly [string, number]>();
        const { sent } = conversing({
          [`GET ${STEPS}`]: [stepsRead(row), again.promise, UNANSWERED],
          [`PUT ${SENDING}`]: [stepRead(row)],
        });
        const drawn = await messageDrawn();

        press(within(drawn).getByRole("button", { name: TRY_SENDING }));
        await landing(() => within(message()).queryByText(SENT) !== null);
        await act(async () => {
          again.settle(stepsRead(row) as readonly [string, number]);
          await vi.advanceTimersByTimeAsync(0);
        });
        await landing(() => within(message()).queryByText(SENT) === null);

        expect(sendButton()).not.toHaveAttribute("aria-disabled");
        expect(sendButton()).not.toHaveAccessibleDescription();
        expect(asked(sent, `GET ${STEPS}`)).toBe(2);
      },
    );

    it("draws Try sending no more once the steps read again show the try out to the model", async () => {
      const { sent } = conversing({
        [`GET ${STEPS}`]: [stepsRead(), stepsRead(OUT_ROW), UNANSWERED],
        [`PUT ${SENDING}`]: [stepRead()],
      });
      const drawn = await messageDrawn();

      press(within(drawn).getByRole("button", { name: TRY_SENDING }));
      await landing(
        () => asked(sent, `GET ${STEPS}`) === 2 && sendButton() === null,
      );

      expect(
        within(message()).getByText("A call to the model it names is out."),
      ).toBeVisible();
      expect(within(message()).queryByText(SENT)).toBeNull();
    });

    it("sends once however often it is pressed while the press is out, saying it is asked for", async () => {
      const { sent } = conversing({
        [`PUT ${SENDING}`]: [UNANSWERED],
      });
      const drawn = await messageDrawn();

      press(within(drawn).getByRole("button", { name: TRY_SENDING }));
      press(within(message()).getByRole("button", { name: TRY_SENDING }));
      await act(() => vi.advanceTimersByTimeAsync(0));

      expect(puts(sent)).toHaveLength(1);
      expect(sendButton()).toHaveAttribute("aria-disabled", "true");
      expect(sendButton()).toHaveAccessibleDescription(PENDING);
      expect(within(message()).queryByText(SENT)).toBeNull();
    });

    it("says sending is not offered once the step no longer holds such a try, reading the step again once and drawing Try sending no more", async () => {
      const { sent, onActed } = conversing({
        [`PUT ${SENDING}`]: [refusal("TRY_SENDING_NOT_OFFERED", 409)],
        [`GET ${STEP}`]: [stepRead(OUT_ROW)],
        [`GET ${STEPS}`]: [stepsRead(), stepsRead(OUT_ROW), UNANSWERED],
      });
      const drawn = await messageDrawn();

      press(within(drawn).getByRole("button", { name: TRY_SENDING }));
      await landing(() => onActed.mock.calls.length === 1);
      await act(() => vi.advanceTimersByTimeAsync(0));

      const alert = screen.getByRole("alert");
      expect(alert).toHaveTextContent(NOT_OFFERED);
      expect(screen.getAllByRole("alert")).toHaveLength(1);
      expect(document.activeElement).toBe(noticeBox());
      expect(sendButton()).toBeNull();
      expect(asked(sent, `GET ${STEP}`)).toBe(1);
      expect(puts(sent)).toHaveLength(1);
      expect(within(message()).queryByText(SENT)).toBeNull();
    });

    it("sends nothing while the step is read again after a refusal", async () => {
      const { sent } = conversing({
        [`PUT ${SENDING}`]: [refusal("ENTRY_STOPPED", 409)],
        [`GET ${STEP}`]: [UNANSWERED],
      });
      const drawn = await messageDrawn();

      press(within(drawn).getByRole("button", { name: TRY_SENDING }));
      await landing(() => asked(sent, `GET ${STEP}`) === 1);
      press(sendButton()!);
      await act(() => vi.advanceTimersByTimeAsync(0));

      expect(puts(sent)).toHaveLength(1);
      expect(sendButton()).toHaveAttribute("aria-disabled", "true");
    });

    it("says nothing and keeps the keyboard where it is while a step moved on is read again, then says it moved on and gives the keyboard to that", async () => {
      const read = deferred<readonly [string, number]>();
      const { sent } = conversing({
        [`PUT ${SENDING}`]: [refusal("STEP_MOVED_ON", 409)],
        [`GET ${STEP}`]: [read.promise],
      });
      const drawn = await messageDrawn();
      const button = within(drawn).getByRole("button", { name: TRY_SENDING });

      press(button);
      await landing(() => asked(sent, `GET ${STEP}`) === 1);

      expect(screen.queryByRole("alert")).toBeNull();
      expect(document.activeElement).toBe(button);

      await act(async () => {
        read.settle(stepRead(OUT_ROW) as readonly [string, number]);
        await vi.advanceTimersByTimeAsync(0);
      });
      await landing(() => screen.queryByRole("alert") !== null);

      expect(screen.getByRole("alert")).toHaveTextContent(MOVED_ON);
      expect(document.activeElement).toBe(noticeBox());
      expect(asked(sent, `GET ${STEP}`)).toBe(1);
    });

    it("gives the keyboard to what went wrong where reading the step again after a refusal fails", async () => {
      const { sent } = conversing({
        [`PUT ${SENDING}`]: [refusal("STEP_MOVED_ON", 409)],
        [`GET ${STEP}`]: [refusal("STEP_NOT_IN_VIEW", 404)],
      });
      const drawn = await messageDrawn();

      press(within(drawn).getByRole("button", { name: TRY_SENDING }));
      await landing(() => screen.queryAllByRole("alert").length > 0);

      expect(noticeBox()).toHaveTextContent("That step is not in view.");
      expect(document.activeElement).toBe(noticeBox());
      expect(asked(sent, `GET ${STEP}`)).toBe(1);
      expect(within(message()).queryByText(SENT)).toBeNull();
    });

    it("says what it runs is stopped where the press is refused so, and says it no more once a later read offers sending again", async () => {
      const { sent } = conversing({
        [`GET ${RUN}`]: [reply(CLAIM), reply(CLAIM), reply(CLAIM), UNANSWERED],
        [`GET ${STEPS}`]: [
          stepsRead(),
          stepsRead(STOPPED_ROW),
          stepsRead(),
          UNANSWERED,
        ],
        [`PUT ${SENDING}`]: [refusal("ENTRY_STOPPED", 409)],
        [`GET ${STEP}`]: [stepRead(STOPPED_ROW)],
      });
      const drawn = await messageDrawn();

      press(within(drawn).getByRole("button", { name: TRY_SENDING }));
      await landing(
        () =>
          asked(sent, `GET ${STEPS}`) === 2 &&
          screen.queryByRole("alert") !== null,
      );

      expect(screen.getByRole("alert")).toHaveTextContent(ENTRY_STOPPED);
      expect(document.activeElement).toBe(noticeBox());
      expect(sendButton()).toBeNull();

      await act(() => vi.advanceTimersByTimeAsync(5000));
      await landing(
        () => asked(sent, `GET ${STEPS}`) === 3 && sendButton() !== null,
      );

      expect(screen.queryByRole("alert")).toBeNull();
      expect(screen.queryByText(ENTRY_STOPPED)).toBeNull();
      expect(sendButton()).not.toHaveAttribute("aria-disabled");
    });

    it("says the press is refused by the rule of who may start a run and reads the reader's standing again, reading nothing of the step", async () => {
      const { sent, readAgain, onActed } = conversing({
        [`PUT ${SENDING}`]: [refusal("ACT_NOT_PERMITTED", 403)],
      });
      const drawn = await messageDrawn();

      press(within(drawn).getByRole("button", { name: TRY_SENDING }));
      await landing(() => screen.queryByRole("alert") !== null);

      expect(screen.getByRole("alert")).toHaveTextContent(START_RULE);
      expect(screen.getByRole("alert")).not.toHaveTextContent(
        "This is done only by a role that reaches it.",
      );
      expect(document.activeElement).toBe(noticeBox());
      expect(readAgain).toHaveBeenCalledTimes(1);
      expect(asked(sent, `GET ${STEP}`)).toBe(0);
      expect(onActed).not.toHaveBeenCalled();
      expect(within(message()).queryByText(SENT)).toBeNull();
    });

    it.each([
      ["offered it", { acts: ["try_sending"], withheld: [] }, true],
      [
        "whose roles do not reach it",
        {
          acts: [],
          withheld: [{ act: "try_sending", refusal: "ACT_NOT_PERMITTED" }],
        },
        false,
      ],
    ])(
      "draws Try sending for a reader %s, and otherwise says they may not",
      async (_case, row, drawnFor) => {
        conversing({ [`GET ${STEPS}`]: [stepsRead(row), UNANSWERED] });
        await messageDrawn();

        expect(sendButton() !== null).toBe(drawnFor);
        expect(
          within(message()).queryByText("You may not try sending it.") !== null,
        ).toBe(!drawnFor);
      },
    );

    it("draws no Try sending while what the step runs is stopped, saying the stop, by whom and when, before why it is held, and not again", async () => {
      conversing({
        [`GET ${STEPS}`]: [
          stepsRead({
            where: {
              kind: "held_back",
              reason: "too_long",
              since: HELD_SINCE,
              stopped: { what: "entry", by: ADA, at: STOPPED_AT },
              waitsOn: "starter",
            },
            acts: [],
            withheld: [{ act: "try_sending", refusal: "ENTRY_STOPPED" }],
          }),
          UNANSWERED,
        ],
      });
      await messageDrawn();

      const stop = within(message()).getByText(
        "What it runs was stopped, so nothing is produced until that is undone.",
      );
      const tooLong = within(message()).getByText(TOO_LONG);
      expect(sendButton()).toBeNull();
      expect(
        within(message()).getByText(
          `Stopped by ${setApart("Ada")}, ${whenText(STOPPED_AT)}.`,
        ),
      ).toBeVisible();
      expect(
        stop.compareDocumentPosition(tooLong) &
          Node.DOCUMENT_POSITION_FOLLOWING,
      ).toBeTruthy();
      expect(within(message()).queryByText(ENTRY_STOPPED)).toBeNull();
    });
  });

  describe("on the step's own page", () => {
    it("sends the try the step is held on, says it will be sent until the step is read again, and leaves the keyboard on the button", async () => {
      const onActed = vi.fn();
      const again = deferred<readonly [string, number]>();
      const { sent } = opening(
        {
          [`GET ${STEP}`]: [stepRead(), again.promise, UNANSWERED],
          [`PUT ${SENDING}`]: [stepRead()],
        },
        "/groups/:groupId/work/:runId/steps/:stepId",
        <StepPage onActed={onActed} />,
        `${PAGE}/steps/${SUMMARISE}`,
      );
      await landing(() => screen.queryByRole("heading", { level: 2 }) !== null);
      const button = screen.getByRole("button", { name: TRY_SENDING });

      press(button);
      await landing(
        () =>
          onActed.mock.calls.length === 1 && asked(sent, `GET ${STEP}`) === 2,
      );

      expect(puts(sent).map(([address]) => address)).toEqual([SENDING]);
      expect(screen.getByText(SENT).closest("[role='status']")).not.toBeNull();
      expect(button).toHaveAttribute("aria-disabled", "true");
      expect(document.activeElement).toBe(button);
      expect(screen.queryByRole("alert")).toBeNull();

      await act(async () => {
        again.settle(stepRead() as readonly [string, number]);
        await vi.advanceTimersByTimeAsync(0);
      });
      await landing(() => screen.queryByText(SENT) === null);

      expect(
        screen.getByRole("button", { name: TRY_SENDING }),
      ).not.toHaveAttribute("aria-disabled");
    });
  });
});
