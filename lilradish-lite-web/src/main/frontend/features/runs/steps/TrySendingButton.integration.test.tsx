import { act, screen, within } from "@testing-library/react";
import { userEvent } from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { whenText } from "../../../lib/time/When";
import { requestsTo, serving, type Reply } from "../../../testutil/answering";
import { deferred } from "../../../testutil/deferred";
import { KEY } from "../../../testutil/runPage";
import { openStepPage } from "../../../testutil/stepPage";
import { accountOpened } from "../../../testutil/stateAccount";
import { tryReplies } from "../../../testutil/tryReplies";

const GROUP = "00000003-0000-4000-8000-000000000d21";

const RUN_ID = "00000008-0000-4000-8000-000000000d21";

const VERSION = "00000007-0000-4000-8000-000000000d21";

const QUESTION_VERSION = "00000007-0000-4000-8000-000000000d22";

const SUMMARISE = "00000009-0000-4000-8000-000000000d21";

const PAGE = `/groups/${KEY}/work/${RUN_ID}`;

const RUN = `/api/groups/${GROUP}/runs/${RUN_ID}`;

const STEPS = `${RUN}/steps`;

const STEP = `${STEPS}/${SUMMARISE}`;

const SENDING = `${STEP}/sending`;

const HELD_SINCE = "2026-09-26T09:30:00Z";

const STOPPED_AT = "2026-09-26T09:40:00Z";

const AVO = { userId: "000d21", displayName: "Avo" };

const TRY_SENDING = "Try sending";

const SENT = "It will be sent if it can be. The result shows here once known.";

const PENDING = "Asking for it to be sent…";

const START_RULE =
  "Only someone in this group who can start runs can start, rename, stop or resume a run, set its usage limit, withdraw a limit change they asked for, or try sending a request that could not be sent.";

const NOT_OFFERED =
  "Try sending is only for a model request that could not be sent: the model service turned it away, it was too long for the model, or the model or its mode isn't available on this system.";

const ENTRY_STOPPED =
  "What this step runs, or the workflow itself, has been stopped. No attempt can be made until it is allowed to be used again.";

const MOVED_ON = "This step has changed since you opened it.";

const TOO_LONG =
  "Its request is too long for the model, so it was not sent and nothing was spent.";

const CLAIM = {
  runId: RUN_ID,
  number: 7,
  workflow: {
    versionName: null,
    entryId: "00000006-0000-4000-8000-000000000d21",
    name: "Handle a claim",
    version: 3,
  },
  startedBy: AVO,
  runsAbove: [],
  startedAt: "2026-09-26T08:00:00Z",
  conversationEvents: [
    {
      id: `step:${SUMMARISE}`,
      kind: "step_reached",
      at: "2026-09-26T08:00:01Z",
      stepId: SUMMARISE,
      run: { runId: RUN_ID, number: 7 },
    },
  ],
  state: "running",
  at: { stepId: SUMMARISE, name: "summarise" },
  spend: { sent: "0", cameBack: "0", spent: "0", cameBackUnknown: false },
  ceiling: { raiseNeedsApproval: false },
  stateActs: ["stop"],
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
  stateActs: ["stop"],
  progress: { done: 0, of: 1 },
};

const NOTHING_SPENT = {
  callsAModel: true,
  sent: "0",
  cameBack: "0",
  spent: "0",
  cameBackUnknown: false,
};

const HELD_ROW = {
  producedByUserId: null,
  producedByKind: null,
  stepId: SUMMARISE,
  order: 1,
  name: "summarise",
  runs: {
    versionName: null,
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
  stateActs: ["try_sending"],
  stateRefusals: [],
};

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

const OUT_ROW = {
  ...HELD_ROW,
  state: "running",
  where: { kind: "running", on: "call" },
  stateActs: [],
};

const STOPPED_ROW = {
  ...HELD_ROW,
  where: {
    ...HELD_ROW.where,
    stopped: { what: "entry", by: AVO, at: STOPPED_AT },
  },
  stateActs: [],
  stateRefusals: [{ act: "try_sending", refusal: "ENTRY_STOPPED" }],
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

const UNANSWERED: Reply = new Promise(() => {});

function reply(body: object): Reply {
  return tryReplies(body).summary;
}

function refusal(code: string, status: number): Reply {
  return [JSON.stringify({ code }), status];
}

function setApart(words: string): string {
  return `${String.fromCodePoint(0x2068)}${words}${String.fromCodePoint(0x2069)}`;
}

function stepRead(row: object = HELD_ROW): Reply {
  return reply({
    run: HEADER,
    declarations: DECLARATIONS,
    step: row,
    triesMade: [HELD_TRY],
  });
}

function stepsRead(row: object = {}): Reply {
  return reply({
    run: HEADER,
    declarations: DECLARATIONS,
    gaveBack: { declares: "nothing" },
    steps: [{ ...HELD_ROW, ...row }],
    rereadAfterSeconds: 5,
  });
}

function conversing(
  routes: Readonly<Record<string, readonly Reply[]>> = {},
  permissions: readonly string[] = ["start_run"],
) {
  return openStepPage({
    groupId: GROUP,
    view: "run",
    address: PAGE,
    permissions,
    routes: {
      [`GET ${RUN}`]: [reply(CLAIM), reply(CLAIM), UNANSWERED],
      [`GET ${STEPS}`]: [stepsRead(), UNANSWERED],
      ...routes,
    },
  });
}

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

let reader: ReturnType<typeof userEvent.setup>;

async function press(control: HTMLElement): Promise<void> {
  const pressed = reader.click(control);
  if (vi.isFakeTimers()) {
    let finished = false;
    void pressed.then(
      () => {
        finished = true;
      },
      () => {
        finished = true;
      },
    );
    // RTL drains its async wrapper with another timeout after user-event's own timer advancement.
    for (let turn = 0; turn < 100 && !finished; turn += 1) {
      await vi.advanceTimersByTimeAsync(0);
    }
    expect(finished).toBe(true);
  }
  await pressed;
  // Flush only after RTL has restored its act environment.
  await act(async () => {});
}

function dispatchClick(control: HTMLElement): void {
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

/** The box a refusal is said in, which the keyboard is given. */
function noticeBox(): HTMLElement | null {
  const [first] = screen.queryAllByRole("alert");
  return first?.closest<HTMLElement>("[tabindex='-1']") ?? null;
}

beforeEach(() => {
  vi.useFakeTimers({ toFake: ["setTimeout", "clearTimeout"] });
  reader = userEvent.setup({
    advanceTimers: (milliseconds) =>
      vi.isFakeTimers()
        ? act(() => vi.advanceTimersByTimeAsync(milliseconds))
        : Promise.resolve(),
  });
});

afterEach(() => {
  vi.useRealTimers();
});

describe("TrySendingButton", () => {
  it.each(["success", "failure"])(
    "discards an already issued sending reread's late %s after permission returns",
    async (outcome) => {
      const later = deferred<readonly [string, number]>();
      const { sent, changeStanding } = conversing({
        [`PUT ${SENDING}`]: [refusal("STEP_MOVED_ON", 409)],
        [`GET ${STEP}`]: [later.promise],
      });
      await messageDrawn();
      await press(sendButton()!);
      await landing(() => asked(sent, `GET ${STEP}`) === 1);
      act(() => changeStanding([]));
      act(() => changeStanding(["start_run"]));
      const restored = sendButton()!;
      act(() => restored.focus());
      await act(async () =>
        later.settle(
          outcome === "success"
            ? await stepRead(OUT_ROW)
            : await refusal("READ_FAILED", 503),
        ),
      );
      await act(() => vi.advanceTimersByTimeAsync(0));
      expect(screen.queryByRole("alert")).toBeNull();
      expect(document.activeElement).toBe(restored);
      expect(sendButton()).not.toBeNull();
      expect(asked(sent, `GET ${STEP}`)).toBe(1);
      expect(puts(sent)).toHaveLength(1);
    },
  );
  it("discards a late refusal after start permission was revoked and restored", async () => {
    const later = deferred<readonly [string, number]>();
    const { sent, changeStanding, readAgain } = conversing({
      [`PUT ${SENDING}`]: [later.promise],
    });
    await messageDrawn();
    await press(sendButton()!);
    await landing(() => puts(sent).length === 1);
    act(() => changeStanding([]));
    expect(sendButton()).toBeNull();
    act(() => changeStanding(["start_run"]));
    await act(async () =>
      later.settle(await refusal("ACT_NOT_PERMITTED", 403)),
    );
    await act(() => vi.advanceTimersByTimeAsync(0));
    expect(readAgain).not.toHaveBeenCalled();
    expect(document.activeElement).not.toBe(noticeBox());
    expect(screen.queryByRole("alert")).toBeNull();
    expect(screen.queryByText(START_RULE)).toBeNull();
    expect(sendButton()).toBeEnabled();
  });
  it("discards a sending reply after start permission was revoked and restored", async () => {
    const later = deferred<readonly [string, number]>();
    const { sent, changeStanding } = conversing({
      [`PUT ${SENDING}`]: [later.promise],
    });
    await messageDrawn();
    await press(sendButton()!);
    await landing(() => puts(sent).length === 1);
    act(() => changeStanding([]));
    expect(sendButton()).toBeNull();
    act(() => changeStanding(["start_run"]));
    await act(async () => later.settle(await stepRead()));
    await act(() => vi.advanceTimersByTimeAsync(0));
    expect(asked(sent, `GET ${RUN}`)).toBe(1);
    expect(within(message()).queryByText(SENT)).toBeNull();
    expect(sendButton()).toBeEnabled();
  });
  describe("in a run's conversation", () => {
    it("sends the try the step is held on with nothing sent, says in a region there before it that it will be sent, reads the run and its steps again and leaves the keyboard on the button", async () => {
      const { sent } = conversing({
        [`PUT ${SENDING}`]: [stepRead()],
      });
      const drawn = await messageDrawn();
      const regions = within(drawn).getAllByRole("status");

      await press(within(drawn).getByRole("button", { name: TRY_SENDING }));
      await landing(() => asked(sent, `GET ${STEPS}`) === 2);

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

      await press(within(drawn).getByRole("button", { name: TRY_SENDING }));
      await landing(() => asked(sent, `GET ${STEPS}`) === 2);
      dispatchClick(sendButton()!);
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

        expect(
          within(drawn).getByRole("button", { name: TRY_SENDING }),
        ).toHaveClass("MuiButton-outlined");
        await press(within(drawn).getByRole("button", { name: TRY_SENDING }));
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

      await press(within(drawn).getByRole("button", { name: TRY_SENDING }));
      await landing(
        () => asked(sent, `GET ${STEPS}`) === 2 && sendButton() === null,
      );

      expect(
        within(accountOpened(message(), "Running")).getByText(
          "A request to the model is in progress.",
        ),
      ).toBeVisible();
      expect(within(message()).queryByText(SENT)).toBeNull();
    });

    it("sends once however often it is pressed while the press is out, saying it is asked for", async () => {
      const { sent } = conversing({
        [`PUT ${SENDING}`]: [UNANSWERED],
      });
      const drawn = await messageDrawn();

      await press(within(drawn).getByRole("button", { name: TRY_SENDING }));
      dispatchClick(
        within(message()).getByRole("button", { name: TRY_SENDING }),
      );
      await act(() => vi.advanceTimersByTimeAsync(0));

      expect(puts(sent)).toHaveLength(1);
      expect(sendButton()).toHaveAttribute("aria-disabled", "true");
      expect(sendButton()).not.toHaveAccessibleDescription();
      expect(within(message()).getByText(PENDING)).toBeVisible();
      expect(within(message()).queryByText(SENT)).toBeNull();
    });

    it("says sending is not offered once the step no longer holds such a try, reading the step again once and drawing Try sending no more", async () => {
      const { sent } = conversing({
        [`PUT ${SENDING}`]: [refusal("TRY_SENDING_NOT_OFFERED", 409)],
        [`GET ${STEP}`]: [stepRead(OUT_ROW)],
        [`GET ${STEPS}`]: [stepsRead(), stepsRead(OUT_ROW), UNANSWERED],
      });
      const drawn = await messageDrawn();

      await press(within(drawn).getByRole("button", { name: TRY_SENDING }));
      await landing(() => asked(sent, `GET ${STEPS}`) === 2);
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

      await press(within(drawn).getByRole("button", { name: TRY_SENDING }));
      await landing(() => asked(sent, `GET ${STEP}`) === 1);
      dispatchClick(sendButton()!);
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

      await press(button);
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

      await press(within(drawn).getByRole("button", { name: TRY_SENDING }));
      await landing(() => screen.queryAllByRole("alert").length > 0);

      expect(noticeBox()).toHaveTextContent(
        "That step isn't here, or you can't see it.",
      );
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

      await press(within(drawn).getByRole("button", { name: TRY_SENDING }));
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
      const { sent, readAgain } = conversing({
        [`PUT ${SENDING}`]: [refusal("ACT_NOT_PERMITTED", 403)],
      });
      const drawn = await messageDrawn();

      await press(within(drawn).getByRole("button", { name: TRY_SENDING }));
      await landing(() => screen.queryByRole("alert") !== null);

      expect(screen.getByRole("alert")).toHaveTextContent(START_RULE);
      expect(screen.getByRole("alert")).not.toHaveTextContent(
        "You don't have permission to do this.",
      );
      expect(document.activeElement).toBe(noticeBox());
      expect(readAgain).toHaveBeenCalledTimes(1);
      expect(asked(sent, `GET ${STEP}`)).toBe(0);
      expect(asked(sent, `GET ${RUN}`)).toBe(1);
      expect(asked(sent, `GET ${STEPS}`)).toBe(1);
      expect(within(message()).queryByText(SENT)).toBeNull();
    });

    it.each([
      ["offered it", { stateActs: ["try_sending"], stateRefusals: [] }, true],
      [
        "whose roles do not reach it",
        {
          stateActs: ["try_sending"],
          stateRefusals: [],
        },
        false,
      ],
    ])(
      "draws Try sending for a reader %s, and otherwise says they may not",
      async (_case, row, drawnFor) => {
        conversing(
          { [`GET ${STEPS}`]: [stepsRead(row), UNANSWERED] },
          drawnFor ? ["start_run"] : [],
        );
        await messageDrawn();

        expect(sendButton() !== null).toBe(drawnFor);
        expect(
          within(accountOpened(message(), "Blocked")).queryByText(
            "You don't have permission to try sending it.",
          ) !== null,
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
              stopped: { what: "entry", by: AVO, at: STOPPED_AT },
              waitsOn: "starter",
            },
            stateActs: [],
            stateRefusals: [{ act: "try_sending", refusal: "ENTRY_STOPPED" }],
          }),
          UNANSWERED,
        ],
      });
      await messageDrawn();
      accountOpened(message(), "Blocked");

      const stop = within(message()).getByText(
        "What this step runs has been stopped, so nothing will be produced until it is allowed to be used again.",
      );
      const tooLong = within(message()).getByText(TOO_LONG);
      expect(sendButton()).toBeNull();
      expect(
        within(message()).getByText(
          `Stopped by ${setApart("Avo")}, ${whenText(STOPPED_AT)}.`,
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
      const again = deferred<readonly [string, number]>();
      const { sent } = openStepPage({
        groupId: GROUP,
        view: "step",
        address: `${PAGE}/steps/${SUMMARISE}`,
        permissions: ["start_run"],
        routes: {
          [`GET ${STEP}`]: [stepRead(), again.promise, UNANSWERED],
          [`PUT ${SENDING}`]: [stepRead()],
        },
      });
      await landing(() => screen.queryByRole("heading", { level: 1 }) !== null);
      const button = screen.getByRole("button", { name: TRY_SENDING });

      await press(button);
      await landing(() => asked(sent, `GET ${STEP}`) === 2);

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
