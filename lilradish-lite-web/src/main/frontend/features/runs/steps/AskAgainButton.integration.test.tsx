import { act, fireEvent, screen, within } from "@testing-library/react";
import { userEvent } from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { requestsTo, serving, type Reply } from "../../../testutil/answering";
import { KEY } from "../../../testutil/runPage";
import { openStepPage } from "../../../testutil/stepPage";
import { accountOpened } from "../../../testutil/stateAccount";
import { tryReplies } from "../../../testutil/tryReplies";
import { deferred } from "../../../testutil/deferred";

const GROUP = "00000003-0000-4000-8000-000000000d11";

const RUN_ID = "00000008-0000-4000-8000-000000000d11";

const VERSION = "00000007-0000-4000-8000-000000000d11";

const QUESTION_VERSION = "00000007-0000-4000-8000-000000000d12";

const SUMMARISE = "00000009-0000-4000-8000-000000000d11";

const PAGE = `/groups/${KEY}/work/${RUN_ID}`;

const RUN = `/api/groups/${GROUP}/runs/${RUN_ID}`;

const STEPS = `${RUN}/steps`;

const STEP = `${STEPS}/${SUMMARISE}`;

const CAT = { userId: "000d11", displayName: "Cat" };

const DAN = { userId: "000d12", displayName: "Dan" };

/** What is said where the ask is refused for the act itself: the rule of who may answer a step and ask again. */
const ASK_RULE =
  "Only someone in this group who can answer steps can answer a step or ask again.";

const CLAIM = {
  runId: RUN_ID,
  number: 7,
  workflow: {
    versionName: null,
    entryId: "00000006-0000-4000-8000-000000000d11",
    name: "Handle a claim",
    version: 3,
  },
  startedBy: { userId: "000d13", displayName: "Avo" },
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

const OWED_ROW = {
  producedByUserId: null,
  producedByKind: null,
  stepId: SUMMARISE,
  order: 1,
  name: "summarise",
  runs: {
    versionName: null,
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
  stateActs: ["answer", "ask_again"],
  stateRefusals: [],
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
      decision: { outcome: "refused", whyLines: "several", why: "Too short." },
    },
  ],
  review: { asked: true, by: { kind: "person", person: DAN } },
  ended: "refused_on_review",
  cost: { callsAModel: false },
};

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

function askedRead(askedBy: object | undefined): Reply {
  return reply({
    run: HEADER,
    declarations: DECLARATIONS,
    step: {
      ...OWED_ROW,
      where: { ...OWED_ROW.where, open: true, since: "2026-09-26T10:00:00Z" },
      tries: { current: 2, declared: 2, beyond: false },
      stateActs: ["answer"],
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

function stepsRead(row: object = {}): Reply {
  return reply({
    run: HEADER,
    declarations: DECLARATIONS,
    gaveBack: { declares: "nothing" },
    steps: [{ ...OWED_ROW, ...row }],
    rereadAfterSeconds: 5,
  });
}

function conversing(routes: Readonly<Record<string, readonly Reply[]>> = {}) {
  return openStepPage({
    groupId: GROUP,
    view: "run",
    address: PAGE,
    permissions: ["answer_step"],
    routes: {
      [`GET ${RUN}`]: [reply(CLAIM), UNANSWERED],
      [`GET ${STEPS}`]: [stepsRead(), UNANSWERED],
      ...routes,
      // The current refusal is read before the original action response sequence.
      [`GET ${STEP}`]: [OWED_READ, ...(routes[`GET ${STEP}`] ?? [UNANSWERED])],
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
  await act(() => vi.advanceTimersByTimeAsync(0));
  return message();
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

describe("AskAgainButton", () => {
  it("discards a late permission refusal after permission is revoked and restored", async () => {
    const later = deferred<readonly [string, number]>();
    const { sent, changeStanding, readAgain } = conversing({
      [`PUT ${STEP}/tries/2`]: [later.promise],
    });
    const drawn = await messageDrawn();
    await press(within(drawn).getByRole("button", { name: "Ask again" }));
    await landing(() => puts(sent).length === 1);
    act(() => changeStanding([]));
    expect(
      within(message()).queryByRole("button", { name: "Ask again" }),
    ).toBeNull();
    act(() => changeStanding(["answer_step"]));
    await act(async () =>
      later.settle(await refusal("ACT_NOT_PERMITTED", 403)),
    );
    await act(() => vi.advanceTimersByTimeAsync(0));
    expect(screen.queryByText(ASK_RULE)).toBeNull();
    expect(readAgain).not.toHaveBeenCalled();
  });

  it.each(["success", "failure"])(
    "discards a late reread %s and focus after permission is revoked and restored",
    async (outcome) => {
      const later = deferred<readonly [string, number]>();
      const consumed = vi.spyOn(later.promise, "then");
      const { sent, changeStanding } = conversing({
        [`PUT ${STEP}/tries/2`]: [refusal("STEP_MOVED_ON", 409)],
        [`GET ${STEP}`]: [later.promise],
      });
      const drawn = await messageDrawn();
      await press(
        within(drawn).getByRole("button", { name: "Answer it yourself" }),
      );
      const form = screen.getByRole("region", { name: "Your answer" });
      fireEvent.change(
        within(form).getByRole("textbox", { name: "Summary \u00b7 summary" }),
        {
          target: { value: "A fuller account of the invented ticket." },
        },
      );
      fireEvent.change(
        within(form).getByRole("textbox", { name: "Your reason" }),
        {
          target: { value: "The account needs more detail." },
        },
      );
      expect(asked(sent, `GET ${STEP}`)).toBe(1);
      expect(consumed).not.toHaveBeenCalled();
      await press(within(drawn).getByRole("button", { name: "Ask again" }));
      await landing(() => asked(sent, `GET ${STEP}`) === 2);
      expect(consumed).toHaveBeenCalledTimes(1);
      expect(puts(sent)).toHaveLength(1);
      expect(screen.queryByRole("alert")).toBeNull();
      act(() => changeStanding([]));
      expect(
        within(message()).queryByRole("button", { name: "Ask again" }),
      ).toBeNull();
      act(() => changeStanding(["answer_step"]));
      const restored = within(message()).getByRole("button", {
        name: "Ask again",
      });
      act(() => restored.focus());
      await act(async () =>
        later.settle(
          outcome === "success"
            ? await ASKED
            : await refusal("READ_FAILED", 503),
        ),
      );
      await act(() => vi.advanceTimersByTimeAsync(0));
      expect(screen.queryByText("The server refused this request.")).toBeNull();
      expect(screen.queryByRole("alert")).toBeNull();
      expect(
        screen.queryByText(`Already asked again by ${setApart("Dan")}.`),
      ).toBeNull();
      expect(screen.queryByText("Attempt 2 has been requested.")).toBeNull();
      expect(document.activeElement).toBe(restored);
      expect(restored).toBeVisible();
      expect(restored).not.toHaveAttribute("aria-disabled");
      expect(
        within(message()).getByRole("button", { name: "Answer it yourself" }),
      ).toBeVisible();
      const kept = screen.getByRole("region", { name: "Your answer" });
      expect(
        within(kept).getByRole("textbox", { name: "Summary \u00b7 summary" }),
      ).toHaveValue("A fuller account of the invented ticket.");
      expect(
        within(kept).getByRole("textbox", { name: "Your reason" }),
      ).toHaveValue("The account needs more detail.");
      expect(asked(sent, `GET ${STEP}`)).toBe(2);
      expect(asked(sent, `GET ${RUN}`)).toBe(1);
      expect(asked(sent, `GET ${STEPS}`)).toBe(1);
      expect(puts(sent)).toHaveLength(1);
    },
  );

  it("discards an ask reply after its permission was revoked, even if that permission returns before the reply", async () => {
    const later = deferred<readonly [string, number]>();
    const { sent, changeStanding } = conversing({
      [`PUT ${STEP}/tries/2`]: [later.promise],
    });
    const drawn = await messageDrawn();
    await press(within(drawn).getByRole("button", { name: "Ask again" }));
    await landing(() => puts(sent).length === 1);
    act(() => changeStanding([]));
    expect(
      within(message()).queryByRole("button", { name: "Ask again" }),
    ).toBeNull();
    act(() => changeStanding(["answer_step"]));
    await act(async () => later.settle(await ASKED));
    await act(() => vi.advanceTimersByTimeAsync(0));
    expect(asked(sent, `GET ${RUN}`)).toBe(1);
    expect(
      within(message()).getByRole("button", { name: "Ask again" }),
    ).toBeVisible();
    expect(puts(sent)).toHaveLength(1);
  });
  describe("in a run's conversation", () => {
    it("asks for the try the step owes with nothing sent, then shows the step as asked, reads the run again and gives the keyboard to its message", async () => {
      const { sent } = conversing({
        [`PUT ${STEP}/tries/2`]: [ASKED],
      });
      const drawn = await messageDrawn();

      expect(
        within(drawn).getByRole("button", { name: "Ask again" }),
      ).toHaveClass("MuiButton-outlined");
      await press(within(drawn).getByRole("button", { name: "Ask again" }));
      await landing(() => asked(sent, `GET ${RUN}`) === 2);

      const [[address, init]] = puts(sent) as [[string, RequestInit]];
      expect(address).toBe(`${STEP}/tries/2`);
      expect(init.body).toBeUndefined();
      expect(puts(sent)).toHaveLength(1);
      expect(
        within(message()).queryByRole("button", { name: "Ask again" }),
      ).toBeNull();
      expect(
        within(message()).getByRole("button", { name: "Answer it yourself" }),
      ).toBeVisible();
      expect(asked(sent, `GET ${RUN}`)).toBe(2);
      expect(document.activeElement).toBe(message());
      expect(screen.queryByRole("alert")).toBeNull();
      expect(
        within(accountOpened(message(), "Waiting")).getByText(
          "Attempt 2 has been requested.",
        ),
      ).toBeVisible();
    });

    it("says who asked for the try first once the step moved on, reading it again and handing that on, giving the keyboard to what it says", async () => {
      const { sent } = conversing({
        [`PUT ${STEP}/tries/2`]: [refusal("STEP_MOVED_ON", 409)],
        [`GET ${STEP}`]: [ASKED],
      });
      const drawn = await messageDrawn();

      await press(within(drawn).getByRole("button", { name: "Ask again" }));
      await landing(() => asked(sent, `GET ${STEPS}`) === 2);

      const alert = screen.getByRole("alert");
      expect(alert).toHaveTextContent(
        `Already asked again by ${setApart("Dan")}.`,
      );
      expect(alert).not.toHaveTextContent("This step has changed");
      expect(screen.getAllByRole("alert")).toHaveLength(1);
      expect(document.activeElement).toBe(alert.closest("[tabindex='-1']"));
      expect(asked(sent, `GET ${STEP}`)).toBe(3);
      expect(puts(sent)).toHaveLength(1);
      expect(
        within(accountOpened(message(), "Waiting")).getByText(
          "Attempt 2 has been requested.",
        ),
      ).toBeVisible();
    });

    it("removes the conversation when the refusal's step read says the run is no longer in view", async () => {
      const { sent } = conversing({
        [`PUT ${STEP}/tries/2`]: [refusal("STEP_MOVED_ON", 409)],
        [`GET ${STEP}`]: [refusal("RUN_NOT_IN_VIEW", 404)],
      });
      const drawn = await messageDrawn();

      await press(within(drawn).getByRole("button", { name: "Ask again" }));

      await landing(
        () =>
          screen.queryByText("That run isn't here, or you can't see it.") !==
          null,
      );
      expect(
        screen.getByText("That run isn't here, or you can't see it."),
      ).toBeVisible();
      expect(
        screen.queryByRole("list", { name: "The conversation" }),
      ).toBeNull();
      expect(screen.queryByRole("heading", { level: 1 })).toBeNull();
      expect(asked(sent, `GET ${STEP}`)).toBe(2);
    });

    it("keeps the moved-on answer visible when reading the step again is refused", async () => {
      const { sent } = conversing({
        [`PUT ${STEP}/tries/2`]: [refusal("STEP_MOVED_ON", 409)],
        [`GET ${STEP}`]: [refusal("READ_FAILED", 503)],
      });
      const drawn = await messageDrawn();

      await press(within(drawn).getByRole("button", { name: "Ask again" }));
      await landing(
        () => screen.queryByText("The server refused this request.") !== null,
      );

      const alert = screen
        .getByText("The server refused this request.")
        .closest("[role='alert']")!;
      expect(alert).toHaveTextContent("The server refused this request.");
      expect(
        screen.getByText("This step has changed since you opened it."),
      ).toBeVisible();
      expect(document.activeElement).toBe(alert.closest("[tabindex='-1']"));
      expect(asked(sent, `GET ${STEP}`)).toBe(2);
      expect(puts(sent)).toHaveLength(1);
      expect(
        within(message()).getByRole("button", { name: "Ask again" }),
      ).toBeVisible();
    });

    it("says nothing of the step having moved on while it is read again, holding Ask again", async () => {
      const { sent } = conversing({
        [`PUT ${STEP}/tries/2`]: [refusal("STEP_MOVED_ON", 409)],
        [`GET ${STEP}`]: [UNANSWERED],
      });
      const drawn = await messageDrawn();

      await press(within(drawn).getByRole("button", { name: "Ask again" }));
      await landing(() => asked(sent, `GET ${STEP}`) === 2);

      expect(screen.queryByRole("alert")).toBeNull();
      expect(
        within(message()).getByRole("button", { name: "Ask again" }),
      ).toHaveAttribute("aria-disabled", "true");
      expect(puts(sent)).toHaveLength(1);
    });

    it("says the refusal's own sentence once the step moved on where the step read again names nobody who asked", async () => {
      const { sent } = conversing({
        [`PUT ${STEP}/tries/2`]: [refusal("STEP_MOVED_ON", 409)],
        [`GET ${STEP}`]: [askedRead(undefined)],
      });
      const drawn = await messageDrawn();

      await press(within(drawn).getByRole("button", { name: "Ask again" }));
      await landing(() => asked(sent, `GET ${STEPS}`) === 2);

      expect(screen.getByRole("alert")).toHaveTextContent(
        "This step has changed since you opened it.",
      );
      expect(screen.queryByText(/Already .* by/)).toBeNull();
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
      accountOpened(drawn, "Waiting");
      await press(within(drawn).getByRole("button", { name: "Ask again" }));
      await landing(() => screen.queryByRole("alert") !== null);

      await act(() => vi.advanceTimersByTimeAsync(5000));
      await landing(
        () =>
          screen.queryByRole("alert") === null &&
          within(message()).queryByText("Attempt 2 of 3") !== null,
      );

      expect(within(message()).getByText("Attempt 2 of 3")).toBeVisible();
      expect(
        within(message()).getByRole("button", { name: "Ask again" }),
      ).not.toHaveAttribute("aria-disabled");
      expect(screen.queryByText(ASK_RULE)).toBeNull();
    });

    it("says the ask is refused by the rule of who may answer a step and reads the reader's standing again without another step read", async () => {
      const { sent, readAgain } = conversing({
        [`PUT ${STEP}/tries/2`]: [refusal("ACT_NOT_PERMITTED", 403)],
      });
      const drawn = await messageDrawn();

      await press(within(drawn).getByRole("button", { name: "Ask again" }));
      await landing(() => screen.queryByRole("alert") !== null);

      expect(screen.getByRole("alert")).toHaveTextContent(ASK_RULE);
      expect(screen.getByRole("alert")).not.toHaveTextContent(
        "You don't have permission to do this.",
      );
      expect(readAgain).toHaveBeenCalledTimes(1);
      expect(asked(sent, `GET ${STEP}`)).toBe(1);
      expect(asked(sent, `GET ${RUN}`)).toBe(1);
      expect(asked(sent, `GET ${STEPS}`)).toBe(1);
    });

    it("draws Ask again alone where a code step's release gives otherwise than the workflow reads, saying the try goes past what the step allows", async () => {
      conversing({
        [`GET ${STEPS}`]: [
          stepsRead({
            runs: {
              versionName: null,
              kind: "code_step",
              codeStep: "file_claim",
            },
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
            stateActs: ["ask_again"],
            stateRefusals: [
              {
                act: "answer",
                refusal: "CODE_STEP_GIVES_OTHERWISE",
                field: "summary",
              },
            ],
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
        within(drawn).getByText(
          "Attempt 3 goes beyond the 2 attempts this step allows.",
        ),
      ).toBeVisible();
      expect(
        within(drawn).queryByRole("button", { name: "Answer it yourself" }),
      ).toBeNull();
    });
  });

  describe("on the step's own page", () => {
    it("asks for the try the step owes, then shows the step as asked and gives the keyboard to what came out", async () => {
      const { sent } = openStepPage({
        groupId: GROUP,
        view: "step",
        address: `${PAGE}/steps/${SUMMARISE}`,
        permissions: ["answer_step"],
        routes: {
          [`GET ${STEP}`]: [OWED_READ, UNANSWERED],
          [`PUT ${STEP}/tries/2`]: [ASKED],
        },
      });
      await landing(() => screen.queryByRole("heading", { level: 1 }) !== null);

      await press(screen.getByRole("button", { name: "Ask again" }));
      await landing(() => asked(sent, `GET ${STEP}`) === 2);

      expect(puts(sent).map(([address]) => address)).toEqual([
        `${STEP}/tries/2`,
      ]);
      expect(screen.queryByRole("button", { name: "Ask again" })).toBeNull();
      expect(screen.getByText("Attempt 2 has been requested.")).toBeVisible();
      expect(document.activeElement).toBe(
        screen.getByRole("heading", { name: "Output" }),
      );
    });
  });
});
