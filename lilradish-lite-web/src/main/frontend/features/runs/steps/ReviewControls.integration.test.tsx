import { act, fireEvent, screen, within } from "@testing-library/react";
import { userEvent } from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { requestsTo, serving, type Reply } from "../../../testutil/answering";
import { regionsSaid } from "../../../testutil/heard";
import { KEY } from "../../../testutil/runPage";
import { openStepPage } from "../../../testutil/stepPage";
import { accountOpened } from "../../../testutil/stateAccount";
import { tryReplies } from "../../../testutil/tryReplies";
import { deferred } from "../../../testutil/deferred";

const GROUP = "00000003-0000-4000-8000-000000000cd1";

const RUN_ID = "00000008-0000-4000-8000-000000000cd1";

const VERSION = "00000007-0000-4000-8000-000000000cd1";

const QUESTION_VERSION = "00000007-0000-4000-8000-000000000cd2";

const SUMMARISE = "00000009-0000-4000-8000-000000000cd1";

const FILE_IT = "00000009-0000-4000-8000-000000000cd2";

const PAGE = `/groups/${KEY}/work/${RUN_ID}`;

const RUN = `/api/groups/${GROUP}/runs/${RUN_ID}`;

const STEPS = `${RUN}/steps`;

const STEP = `${STEPS}/${SUMMARISE}`;

const REVIEW = `${STEP}/tries/1/review`;

const CAT = { userId: "000cd1", displayName: "Cat" };

const DAN = { userId: "000cd2", displayName: "Dan" };

const CLAIM = {
  runId: RUN_ID,
  number: 7,
  name: "Claim from Avo",
  workflow: {
    versionName: null,
    entryId: "00000006-0000-4000-8000-000000000cd1",
    name: "Handle a claim",
    version: 3,
  },
  startedBy: { userId: "000cd3", displayName: "Avo" },
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

const TICKET = {
  name: "ticket",
  label: "Ticket",
  kind: "text",
  longest: 4000,
  mustBeGiven: true,
};

const SUMMARY = {
  name: "summary",
  label: "Summary",
  kind: "text",
  longest: 1000,
  mustBeGiven: true,
};

const REPLY = {
  name: "reply",
  label: "Reply",
  kind: "text",
  longest: 1000,
  mustBeGiven: true,
};

function declarations(...gives: readonly object[]) {
  return {
    [VERSION]: { takes: [TICKET], gives: [] },
    [QUESTION_VERSION]: {
      takes: [{ ...TICKET, name: "text", label: "Text" }],
      gives,
    },
  };
}

const HEADER = {
  runId: RUN_ID,
  number: 7,
  versionId: VERSION,
  state: "running",
  at: { stepId: SUMMARISE, name: "summarise" },
  stateActs: ["stop"],
  progress: { done: 0, of: 2 },
};

const HEADER_ON = {
  ...HEADER,
  at: { stepId: FILE_IT, name: "file_it" },
  progress: { done: 1, of: 2 },
};

const WRITTEN = "A printer caught fire.\nNobody was hurt.";

const WENT_IN = [
  {
    input: "text",
    from: { kind: "run_input", path: "ticket" },
    value: "Printer on fire",
  },
];

const WAITING_ROW = {
  producedByUserId: CAT.userId,
  producedByKind: "person",
  stepId: SUMMARISE,
  order: 1,
  name: "summarise",
  runs: {
    versionName: null,
    kind: "question",
    entryId: "00000006-0000-4000-8000-000000000cd2",
    name: "Summarise",
    version: 2,
    versionId: QUESTION_VERSION,
  },
  producer: { kind: "person" },
  reviewer: { kind: "person" },
  state: "waiting",
  where: {
    kind: "waiting_on_review",
    number: 1,
    values: [{ field: "summary", on: "review_at_gate" }],
    since: "2026-09-26T09:00:00Z",
    waitsOn: "review_at_gate",
  },
  takesFrom: [{ input: "text", from: { kind: "run_input", path: "ticket" } }],
  tries: { current: 1, declared: 2, beyond: false },
  cost: { callsAModel: false },
  gaveBack: [{ field: "summary", value: WRITTEN, now: "waiting_on_review" }],
  stateActs: ["review"],
  stateRefusals: [],
};

const FILE_IT_ROW = {
  producedByUserId: null,
  producedByKind: null,
  stepId: FILE_IT,
  order: 2,
  name: "file_it",
  runs: { versionName: null, kind: "route" },
  state: "not_started",
  takesFrom: [],
  cost: { callsAModel: false },
  stateActs: [],
  stateRefusals: [],
};

function stepsRead(first: object = {}): Reply {
  return reply({
    run: HEADER,
    declarations: declarations(SUMMARY),
    gaveBack: { declares: "nothing" },
    steps: [
      { ...WAITING_ROW, wentIn: WENT_IN, wentInFrom: "try", ...first },
      FILE_IT_ROW,
    ],
    rereadAfterSeconds: 5,
  });
}

/** Leave the reviewer unnamed unless the scenario needs a known identity. */
function reviewed(
  decided: object,
  now: string,
  header: object,
  changed: object,
  review: object = { asked: true, by: { kind: "person" } },
): Reply {
  return reply({
    run: header,
    declarations: declarations(SUMMARY),
    step: {
      ...WAITING_ROW,
      gaveBack: [{ field: "summary", value: WRITTEN, now }],
      stateActs: [],
      ...changed,
    },
    wentIn: WENT_IN,
    wentInFrom: "try",
    cameOut: [
      now === "stands"
        ? {
            field: "summary",
            standing: { number: 1, value: WRITTEN },
            now,
          }
        : { field: "summary", now },
    ],
    triesMade: [
      {
        number: 1,
        beyond: false,
        producedBy: { kind: "person", person: CAT },
        values: [{ field: "summary", value: WRITTEN, now, decision: decided }],
        review,
        ended: now === "stands" ? "stands" : "refused_on_review",
        cost: { callsAModel: false },
      },
    ],
  });
}

const DONE = { state: "done", where: undefined };

const ASSURED = reviewed({ outcome: "assured" }, "stands", HEADER_ON, DONE);

const OWED = {
  where: {
    kind: "owed_try",
    open: false,
    beyond: false,
    since: "2026-09-26T09:30:00Z",
    waitsOn: "answer_step",
  },
  next: { number: 2, beyond: false },
  stateActs: ["answer", "ask_again"],
  stateRefusals: [],
};

const REFUSED = reviewed(
  { outcome: "refused", why: "Too short.", whyLines: "several" },
  "refused",
  HEADER,
  OWED,
);

const ASSURED_BY_DAN = reviewed(
  { outcome: "assured" },
  "stands",
  HEADER_ON,
  DONE,
  { asked: true, by: { kind: "person", person: DAN } },
);

const REFUSED_BY_DAN = reviewed(
  { outcome: "refused", why: "Too short.", whyLines: "several" },
  "refused",
  HEADER,
  OWED,
  { asked: true, by: { kind: "person", person: DAN } },
);

const SECOND_WAITING = stepsRead({
  where: {
    ...WAITING_ROW.where,
    number: 2,
    since: "2026-09-26T10:00:00Z",
  },
  tries: { current: 2, declared: 2, beyond: false },
  gaveBack: [
    { field: "summary", value: "A longer summary.", now: "waiting_on_review" },
  ],
});

const STOPPED = reply({
  run: { ...HEADER, at: undefined, state: "stopped", stateActs: [] },
  declarations: declarations(SUMMARY),
  step: {
    ...WAITING_ROW,
    where: { ...WAITING_ROW.where, waitsOn: undefined },
    stateActs: [],
    stateRefusals: [{ act: "review", refusal: "RUN_STOPPED" }],
  },
  wentIn: WENT_IN,
  wentInFrom: "try",
  cameOut: [{ field: "summary", now: "waiting_on_review" }],
  triesMade: [
    {
      number: 1,
      beyond: false,
      producedBy: { kind: "person", person: CAT },
      values: [{ field: "summary", value: WRITTEN, now: "waiting_on_review" }],
      review: { asked: true },
      ended: "waiting",
      cost: { callsAModel: false },
    },
  ],
});

const FIRST_TRY_WAITING = {
  number: 1,
  beyond: false,
  producedBy: { kind: "person", person: CAT },
  values: [{ field: "summary", value: WRITTEN, now: "waiting_on_review" }],
  review: { asked: true },
  ended: "waiting",
  cost: { callsAModel: false },
};

const WAITING_READ = reply({
  run: HEADER,
  rereadAfterSeconds: 5,
  declarations: declarations(SUMMARY),
  step: WAITING_ROW,
  wentIn: WENT_IN,
  wentInFrom: "try",
  cameOut: [{ field: "summary", now: "waiting_on_review" }],
  triesMade: [FIRST_TRY_WAITING],
});

const SECOND_WAITING_READ = reply({
  run: HEADER,
  rereadAfterSeconds: 5,
  declarations: declarations(SUMMARY),
  step: {
    ...WAITING_ROW,
    where: { ...WAITING_ROW.where, number: 2, since: "2026-09-26T10:00:00Z" },
    tries: { current: 2, declared: 2, beyond: false },
    gaveBack: [
      {
        field: "summary",
        value: "A longer summary.",
        now: "waiting_on_review",
      },
    ],
  },
  wentIn: WENT_IN,
  wentInFrom: "try",
  cameOut: [{ field: "summary", now: "waiting_on_review" }],
  triesMade: [
    {
      ...FIRST_TRY_WAITING,
      values: [
        {
          field: "summary",
          value: WRITTEN,
          now: "refused",
          decision: {
            outcome: "refused",
            whyLines: "several",
            why: "Too short.",
          },
        },
      ],
      review: { asked: true, by: { kind: "person", person: DAN } },
      ended: "refused_on_review",
    },
    {
      ...FIRST_TRY_WAITING,
      number: 2,
      values: [
        {
          field: "summary",
          value: "A longer summary.",
          now: "waiting_on_review",
        },
      ],
    },
  ],
});

const MODEL_REVIEWS = reply({
  run: HEADER,
  rereadAfterSeconds: 5,
  declarations: declarations(SUMMARY),
  step: {
    ...WAITING_ROW,
    reviewer: { kind: "model", model: "judge" },
    where: {
      ...WAITING_ROW.where,
      values: [{ field: "summary", on: "model" }],
      waitsOn: "model",
    },
    stateActs: [],
    stateRefusals: [{ act: "review", refusal: "REVIEW_NOT_A_PERSONS" }],
  },
  wentIn: WENT_IN,
  wentInFrom: "try",
  cameOut: [{ field: "summary", now: "waiting_on_review" }],
  triesMade: [FIRST_TRY_WAITING],
});

const UNANSWERED: Reply = new Promise(() => {});

const REASON_UNUSABLE =
  "A reason is 1 to 2048 characters, with at least one visible character.";

const REVIEW_RULE =
  "Only someone in this group who can review can review steps, accepting or rejecting their values.";

function reply(body: object): Reply {
  return tryReplies(body).summary;
}

function refusal(code: string, status: number): Reply {
  return [JSON.stringify({ code }), status];
}

function setApart(words: string): string {
  return `${String.fromCodePoint(0x2068)}${words}${String.fromCodePoint(0x2069)}`;
}

function conversing(
  routes: Readonly<Record<string, readonly Reply[]>> = {},
  permissions: readonly string[] = ["review_at_gate"],
) {
  return openStepPage({
    groupId: GROUP,
    view: "run",
    address: PAGE,
    permissions,
    userId: DAN.userId,
    routes: {
      [`GET ${RUN}`]: [reply(CLAIM)],
      [`GET ${STEPS}`]: [stepsRead()],
      ...routes,
    },
  });
}

function stepOpened(routes: Readonly<Record<string, readonly Reply[]>>) {
  return openStepPage({
    groupId: GROUP,
    view: "step",
    address: `${PAGE}/steps/${SUMMARISE}`,
    permissions: ["review_at_gate"],
    userId: DAN.userId,
    routes,
  });
}

/** Timeouts are faked, and testing-library's waits advance faked time only under jest: each turn is advanced by hand. */
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

function reviews(sent: ReturnType<typeof serving>): RequestInit[] {
  return sent.mock.calls
    .filter(([, init]) => init?.method === "PUT")
    .map(([, init]) => init!);
}

function asked(sent: ReturnType<typeof serving>, request: string): number {
  return requestsTo(sent).filter((each) => each === request).length;
}

function reviewFrame(): HTMLElement {
  const review = screen.getByRole("region", { name: "Needs your review" });
  return review.closest("li") ?? review.parentElement!;
}

function reason(_drawn: HTMLElement): HTMLElement {
  return within(reviewFrame()).getByRole("textbox", {
    name: /^Reason for rejecting/,
  });
}

beforeEach(() => {
  vi.useFakeTimers({ toFake: ["setTimeout", "clearTimeout"] });
  reader = userEvent.setup({
    advanceTimers: (milliseconds) =>
      vi.isFakeTimers()
        ? act(() => vi.advanceTimersByTimeAsync(milliseconds))
        : Promise.resolve(),
  });
  // Give the long fixture a measured preview viewport; jsdom has no layout.
  vi.spyOn(HTMLElement.prototype, "clientHeight", "get").mockImplementation(
    function (this: HTMLElement) {
      return this.hasAttribute("data-value-content") ? 192 : 0;
    },
  );
  vi.spyOn(HTMLElement.prototype, "scrollHeight", "get").mockImplementation(
    function (this: HTMLElement) {
      return this.hasAttribute("data-value-content") &&
        this.textContent?.includes("Nobody was hurt.")
        ? 384
        : 0;
    },
  );
  vi.spyOn(HTMLElement.prototype, "getBoundingClientRect").mockImplementation(
    function (this: HTMLElement) {
      return DOMRect.fromRect({
        width: Element.prototype.getBoundingClientRect.call(this).width,
        height:
          this.parentElement?.hasAttribute("data-value-content") &&
          this.textContent?.includes("Nobody was hurt.")
            ? 384
            : 0,
      });
    },
  );
});

afterEach(() => {
  vi.useRealTimers();
});

describe("ReviewControls", () => {
  it.each(["success", "failure"])(
    "discards an already issued review reread's late %s after reviewer identity returns",
    async (outcome) => {
      const later = deferred<readonly [string, number]>();
      const { sent, changeStanding } = conversing({
        [`PUT ${REVIEW}`]: [refusal("STEP_MOVED_ON", 409)],
        [`GET ${STEP}`]: [later.promise],
      });
      await messageDrawn();
      await press(
        within(reviewFrame()).getByRole("button", { name: "Accept" }),
      );
      await landing(() => asked(sent, `GET ${STEP}`) === 1);
      act(() => changeStanding(["review_at_gate"], CAT.userId));
      act(() => changeStanding(["review_at_gate"], DAN.userId));
      const restored = within(reviewFrame()).getByRole("button", {
        name: "Accept",
      });
      act(() => restored.focus());
      await act(async () =>
        later.settle(
          outcome === "success"
            ? await SECOND_WAITING_READ
            : await refusal("READ_FAILED", 503),
        ),
      );
      await act(() => vi.advanceTimersByTimeAsync(0));
      expect(screen.queryByRole("alert")).toBeNull();
      expect(document.activeElement).toBe(restored);
      expect(within(reviewFrame()).queryByText("A longer summary.")).toBeNull();
      expect(asked(sent, `GET ${STEP}`)).toBe(1);
      expect(reviews(sent)).toHaveLength(1);
    },
  );
  it("discards a late review refusal after reviewer identity changed and returned", async () => {
    const later = deferred<readonly [string, number]>();
    const { sent, changeStanding, readAgain } = conversing({
      [`PUT ${REVIEW}`]: [later.promise],
    });
    await messageDrawn();
    await press(within(reviewFrame()).getByRole("button", { name: "Accept" }));
    await landing(() => reviews(sent).length === 1);
    act(() => changeStanding(["review_at_gate"], CAT.userId));
    act(() => changeStanding(["review_at_gate"], DAN.userId));
    const restored = within(reviewFrame()).getByRole("button", {
      name: "Accept",
    });
    act(() => restored.focus());
    await act(async () =>
      later.settle(await refusal("ACT_NOT_PERMITTED", 403)),
    );
    await act(() => vi.advanceTimersByTimeAsync(0));
    expect(screen.queryByText(REVIEW_RULE)).toBeNull();
    expect(document.activeElement).toBe(restored);
    expect(readAgain).not.toHaveBeenCalled();
    expect(reviews(sent)).toHaveLength(1);
  });
  it("discards a review reply after the actual reviewer identity changed, even when the original identity returns", async () => {
    const later = deferred<readonly [string, number]>();
    const { sent, changeStanding } = conversing({
      [`PUT ${REVIEW}`]: [later.promise],
    });
    await messageDrawn();
    await press(within(reviewFrame()).getByRole("button", { name: "Accept" }));
    await landing(() => reviews(sent).length === 1);
    act(() => changeStanding(["review_at_gate"], CAT.userId));
    expect(
      screen.queryByRole("region", { name: "Needs your review" }),
    ).toBeNull();
    act(() => changeStanding(["review_at_gate"], DAN.userId));
    await act(async () => later.settle(await ASSURED));
    await act(() => vi.advanceTimersByTimeAsync(0));
    expect(asked(sent, `GET ${RUN}`)).toBe(1);
    expect(
      screen.getByRole("region", { name: "Needs your review" }),
    ).toBeVisible();
    expect(reviews(sent)).toHaveLength(1);
  });
  describe("in a run's conversation", () => {
    it("lays out what went in and opens the long value waiting beside Accept and Reject named after the value", async () => {
      conversing();

      const drawn = await messageDrawn();

      const wentIn = within(reviewFrame()).getByRole("region", {
        name: "Inputs",
      });
      expect(
        within(wentIn).getByRole("region", {
          name: `${setApart("Text")} · text`,
        }),
      ).toHaveTextContent("Printer on fire");
      expect(wentIn).toHaveTextContent(`${setApart("Ticket")} (run input)`);
      expect(drawn).not.toContainElement(reviewFrame());
      expect(drawn.nextElementSibling).toBe(reviewFrame());
      const review = screen.getByRole("region", { name: "Needs your review" });
      expect(review).toHaveTextContent(
        "A printer caught fire. Nobody was hurt.",
      );
      const expand = within(review).getByRole("button", { name: "Show all" });
      expect(expand).toHaveAttribute("aria-expanded", "false");
      await press(expand);
      expect(expand).toHaveAttribute("aria-expanded", "true");
      expect(review).toHaveTextContent(
        "A printer caught fire. Nobody was hurt.",
      );
      for (const name of ["Inputs", "Needs your review"]) {
        expect(
          within(reviewFrame()).getByRole("heading", { level: 3, name }),
        ).toBeInTheDocument();
        expect(
          within(reviewFrame()).queryByRole("heading", { level: 2, name }),
        ).toBeNull();
      }
      expect(
        within(drawn).queryByRole("button", { name: /in full/ }),
      ).toBeNull();
      const choice = within(review).getByRole("group", {
        name: setApart("Summary"),
      });
      expect(
        within(choice)
          .getAllByRole("button")
          .map((each) => each.textContent),
      ).toEqual(["Accept", "Reject"]);
      expect(
        within(choice).getByRole("button", { name: "Accept" }),
      ).toHaveClass("MuiButton-outlined");
      expect(within(review).queryByRole("textbox")).toBeNull();
      expect(
        within(review).queryByRole("button", { name: "Submit review" }),
      ).toBeNull();
    });

    it("opens once what came out that stands without review beside the value waiting, offering a choice for the value waiting alone", async () => {
      conversing({
        [`GET ${STEPS}`]: [
          reply({
            run: HEADER,
            declarations: declarations(SUMMARY, REPLY),
            gaveBack: { declares: "nothing" },
            steps: [
              {
                ...WAITING_ROW,
                gaveBack: [
                  {
                    field: "summary",
                    value: "A fire.",
                    now: "waiting_on_review",
                  },
                  { field: "reply", value: WRITTEN, now: "stands" },
                ],
                wentIn: WENT_IN,
                wentInFrom: "try",
              },
              FILE_IT_ROW,
            ],
            rereadAfterSeconds: 5,
          }),
        ],
      });

      const drawn = await messageDrawn();

      const review = screen.getByRole("region", { name: "Needs your review" });
      expect(drawn).toHaveTextContent(
        "A printer caught fire. Nobody was hurt.",
      );
      const expand = within(drawn).getByRole("button", { name: "Show all" });
      expect(expand).toHaveAttribute("aria-expanded", "false");
      await press(expand);
      expect(expand).toHaveAttribute("aria-expanded", "true");
      expect(drawn).toHaveTextContent(
        "A printer caught fire. Nobody was hurt.",
      );
      expect(review).not.toHaveTextContent("A printer caught fire.");
      expect(review).toHaveTextContent("A fire.");
      expect(
        within(drawn).queryByRole("button", { name: /in full/ }),
      ).toBeNull();
      expect(
        within(review)
          .getAllByRole("group")
          .map((each) => each.getAttribute("aria-labelledby"))
          .map((id) => document.getElementById(id!)?.textContent),
      ).toEqual([setApart("Summary")]);
      expect(within(review).getAllByText("A fire.")).toHaveLength(1);
      expect(within(drawn).queryByText("A fire.")).toBeNull();
    });

    it("draws neither, nor what went in, where the step withholds the review from the reader, and keeps a long value shut", async () => {
      conversing(
        {
          [`GET ${STEPS}`]: [
            stepsRead({
              stateActs: ["review"],
              stateRefusals: [],
              wentIn: undefined,
              wentInFrom: undefined,
            }),
          ],
        },
        [],
      );

      const drawn = await messageDrawn();

      expect(drawn).not.toHaveTextContent(
        "You don't have permission to review it.",
      );
      expect(
        within(accountOpened(drawn, "Waiting")).getByText(
          "You don't have permission to review it.",
        ),
      ).toBeVisible();
      expect(
        within(drawn).getByRole("button", {
          name: "Show all",
        }),
      ).toHaveAttribute("aria-expanded", "false");
      expect(
        within(drawn).queryByRole("region", { name: "Inputs" }),
      ).toBeNull();
      expect(
        screen.queryByRole("region", { name: "Needs your review" }),
      ).toBeNull();
      expect(screen.queryByRole("button", { name: "Accept" })).toBeNull();
      expect(screen.queryByRole("button", { name: "Reject" })).toBeNull();
    });

    it("assures a lone value at a press, sending that decision alone, then shows the step as it answers, reads the run again and gives the keyboard to its message", async () => {
      const { sent } = conversing({
        [`GET ${RUN}`]: [reply(CLAIM), UNANSWERED],
        [`GET ${STEPS}`]: [stepsRead(), UNANSWERED],
        [`PUT ${REVIEW}`]: [ASSURED],
      });
      await messageDrawn();

      await press(
        within(reviewFrame()).getByRole("button", { name: "Accept" }),
      );
      await landing(() => within(message()).queryByText("Done") !== null);

      expect(reviews(sent).map((init) => init.body)).toEqual([
        JSON.stringify({ decisions: { summary: { outcome: "assured" } } }),
      ]);
      expect(within(message()).queryByText("Accepted.")).toBeNull();
      expect(screen.getByText("1 of 2 steps done")).toBeVisible();
      expect(asked(sent, `GET ${RUN}`)).toBe(2);
      expect(document.activeElement).toBe(message());
      expect(
        screen.queryByRole("region", { name: "Needs your review" }),
      ).toBeNull();
      expect(screen.queryByRole("alert")).toBeNull();
    });

    it("asks why before refusing a lone value, sending nothing until a usable reason is given, and then sends that reason", async () => {
      const { sent } = conversing({
        [`GET ${STEPS}`]: [stepsRead(), UNANSWERED],
        [`PUT ${REVIEW}`]: [REFUSED],
      });
      const drawn = await messageDrawn();
      const refuse = within(reviewFrame()).getByRole("button", {
        name: "Reject",
      });

      expect(refuse.querySelector("svg")).toBeNull();
      expect(
        getComputedStyle(refuse).getPropertyValue("--variant-outlinedBg"),
      ).toBe("#ffffff");

      await press(refuse);

      const why = reason(drawn);
      expect(refuse).toHaveAttribute("aria-pressed", "true");
      expect(refuse.querySelector("svg")).toHaveAttribute(
        "aria-hidden",
        "true",
      );
      expect(
        getComputedStyle(refuse).getPropertyValue("--variant-outlinedBg"),
      ).toBe("#daebf7");
      expect(
        within(reviewFrame())
          .getByRole("button", { name: "Accept" })
          .querySelector("svg"),
      ).toBeNull();
      expect(document.activeElement).toBe(why);
      const submit = within(reviewFrame()).getByRole("button", {
        name: "Submit review",
      });
      expect(submit).toHaveClass("MuiButton-contained");
      expect(submit).toHaveAttribute("aria-disabled", "true");
      dispatchClick(submit);
      await act(() => vi.advanceTimersByTimeAsync(0));
      expect(reviews(sent)).toEqual([]);

      fireEvent.change(why, { target: { value: "Too short." } });
      expect(submit).not.toHaveAttribute("aria-disabled");
      await press(submit);
      await landing(
        () => within(message()).queryByText("Rejected in review") !== null,
      );

      expect(reviews(sent).map((init) => init.body)).toEqual([
        JSON.stringify({
          decisions: { summary: { outcome: "refused", why: "Too short." } },
        }),
      ]);
    });

    it("says Submit is held on why the lone value is refused, and not on a choice for each value", async () => {
      conversing();
      const drawn = await messageDrawn();

      await press(
        within(reviewFrame()).getByRole("button", { name: "Reject" }),
      );

      const submit = within(reviewFrame()).getByRole("button", {
        name: "Submit review",
      });
      expect(submit).toHaveAccessibleDescription(
        "Give a reason for rejecting it.",
      );
      expect(submit).toHaveAttribute("aria-disabled", "true");
      expect(within(drawn).queryByText(/for each value/)).toBeNull();
    });

    it.each([
      ["showing nothing", " \t\n ", "A rejection or an answer needs a reason."],
      [
        "holding a tag character",
        `No${String.fromCodePoint(0xe0041)}`,
        "What you wrote contains an invisible tag character, which can't be sent to a model.",
      ],
      [
        "holding a direction control",
        `No${String.fromCodePoint(0x202e)}`,
        "What you wrote contains a text-direction character, which can't be sent to a model.",
      ],
      [
        "holding a direction control and nothing that shows, which is refused for the control before being taken as none",
        String.fromCodePoint(0x202e),
        "What you wrote contains a text-direction character, which can't be sent to a model.",
      ],
      ["of 2049 characters", "x".repeat(2049), REASON_UNUSABLE],
      [
        "of 2049 characters each beyond the basic plane",
        String.fromCodePoint(0x20000).repeat(2049),
        REASON_UNUSABLE,
      ],
    ])(
      "marks a reason %s where it is typed, as the server would refuse it, and sends nothing",
      async (_case, typed, words) => {
        const { sent } = conversing({ [`PUT ${REVIEW}`]: [REFUSED] });
        const drawn = await messageDrawn();
        await press(
          within(reviewFrame()).getByRole("button", { name: "Reject" }),
        );
        const why = reason(drawn);

        fireEvent.change(why, { target: { value: typed } });

        expect(why).toHaveAccessibleDescription(words);
        expect(why).toHaveAttribute("aria-invalid", "true");
        const submit = within(reviewFrame()).getByRole("button", {
          name: "Submit review",
        });
        expect(submit).toHaveAttribute("aria-disabled", "true");
        dispatchClick(submit);
        await act(() => vi.advanceTimersByTimeAsync(0));
        expect(reviews(sent)).toEqual([]);
      },
    );

    it.each([
      ["over lines", `${"x".repeat(2045)}\n\ty`],
      [
        "each beyond the basic plane, 4096 units long",
        String.fromCodePoint(0x20000).repeat(2048),
      ],
    ])(
      "accepts a reason of 2048 characters %s, marking nothing",
      async (_case, typed) => {
        conversing();
        const drawn = await messageDrawn();
        await press(
          within(reviewFrame()).getByRole("button", { name: "Reject" }),
        );
        const why = reason(drawn);

        fireEvent.change(why, { target: { value: typed } });

        expect(why).toHaveAttribute("aria-invalid", "false");
        expect(why).not.toHaveAccessibleDescription();
        expect(
          within(reviewFrame()).getByRole("button", { name: "Submit review" }),
        ).not.toHaveAttribute("aria-disabled");
      },
    );

    it("says it was reviewed already, and by whom, where the step read again names the person, giving the keyboard to what it says", async () => {
      const { sent } = conversing({
        [`GET ${RUN}`]: [reply(CLAIM), UNANSWERED],
        [`GET ${STEPS}`]: [stepsRead(), UNANSWERED],
        [`PUT ${REVIEW}`]: [refusal("STEP_MOVED_ON", 409)],
        [`GET ${STEP}`]: [ASSURED_BY_DAN],
      });
      await messageDrawn();

      await press(
        within(reviewFrame()).getByRole("button", { name: "Accept" }),
      );
      await landing(() => screen.queryByRole("alert") !== null);

      const said = screen.getByRole("alert");
      expect(said).toHaveTextContent(`Already reviewed by ${setApart("Dan")}.`);
      expect(document.activeElement).toContainElement(said);
      expect(asked(sent, `GET ${STEP}`)).toBe(1);
      expect(asked(sent, `GET ${RUN}`)).toBe(2);
      expect(within(message()).getByText("Done")).toBeVisible();
      expect(screen.queryByRole("button", { name: "Accept" })).toBeNull();
      expect(
        screen.queryByText("This step has changed since you opened it."),
      ).toBeNull();
    });

    it.each([
      ["names nobody in particular as its reviewer", ASSURED],
      [
        "names no reviewer",
        reviewed({ outcome: "assured" }, "stands", HEADER_ON, DONE, {
          asked: true,
        }),
      ],
    ])(
      "says the step moved on in the refusal's own words where the step read again %s",
      async (_case, readAgain) => {
        const { sent } = conversing({
          [`GET ${RUN}`]: [reply(CLAIM), UNANSWERED],
          [`GET ${STEPS}`]: [stepsRead(), UNANSWERED],
          [`PUT ${REVIEW}`]: [refusal("STEP_MOVED_ON", 409)],
          [`GET ${STEP}`]: [readAgain],
        });
        await messageDrawn();

        await press(
          within(reviewFrame()).getByRole("button", { name: "Accept" }),
        );
        await landing(() => screen.queryByRole("alert") !== null);

        const said = screen.getByRole("alert");
        expect(said).toHaveTextContent(
          "This step has changed since you opened it.",
        );
        expect(said).not.toHaveTextContent("reviewed already");
        expect(document.activeElement).toContainElement(said);
        expect(asked(sent, `GET ${STEP}`)).toBe(1);
        expect(within(message()).getByText("Done")).toBeVisible();
      },
    );

    it("keeps saying it was reviewed already, and by whom, where the step read again shows another try waiting, sending nothing for that try", async () => {
      const { sent } = conversing({
        [`GET ${RUN}`]: [reply(CLAIM), UNANSWERED],
        [`GET ${STEPS}`]: [stepsRead(), UNANSWERED],
        [`PUT ${REVIEW}`]: [refusal("STEP_MOVED_ON", 409)],
        [`GET ${STEP}`]: [SECOND_WAITING_READ],
      });
      await messageDrawn();

      await press(
        within(reviewFrame()).getByRole("button", { name: "Accept" }),
      );
      await landing(() => screen.queryByText("A longer summary.") !== null);

      const said = screen.getByRole("alert");
      expect(said).toHaveTextContent(`Already reviewed by ${setApart("Dan")}.`);
      expect(document.activeElement).toContainElement(said);
      expect(requestsTo(sent).filter((each) => each.startsWith("PUT"))).toEqual(
        [`PUT ${REVIEW}`],
      );
      expect(
        screen.queryByText("This step has changed since you opened it."),
      ).toBeNull();
    });

    it("says nothing more of a review refused as moved on once another try waits on the reader", async () => {
      let settle!: (answer: readonly [string, number]) => void;
      const later: Reply = new Promise((done) => {
        settle = done;
      });
      conversing({
        [`GET ${STEPS}`]: [stepsRead(), later],
        [`PUT ${REVIEW}`]: [refusal("STEP_MOVED_ON", 409)],
        [`GET ${STEP}`]: [REFUSED_BY_DAN],
      });
      await messageDrawn();
      await press(
        within(reviewFrame()).getByRole("button", { name: "Accept" }),
      );
      await landing(() => screen.queryByRole("alert") !== null);
      expect(screen.getByRole("alert")).toHaveTextContent(
        `Already reviewed by ${setApart("Dan")}.`,
      );

      settle(SECOND_WAITING as readonly [string, number]);
      await landing(() => screen.queryByText("A longer summary.") !== null);

      expect(screen.queryByRole("alert")).toBeNull();
      expect(screen.queryByText(/Already reviewed/)).toBeNull();
      expect(
        within(reviewFrame()).getByRole("button", { name: "Accept" }),
      ).not.toHaveAttribute("aria-disabled");
    });

    it("marks nothing at the reason for another try that waits, where the server refused the reason given for the one before", async () => {
      const { sent } = conversing({
        [`GET ${STEPS}`]: [stepsRead(), SECOND_WAITING],
        [`PUT ${REVIEW}`]: [refusal("REASON_UNUSABLE", 400)],
      });
      const drawn = await messageDrawn();
      await press(
        within(reviewFrame()).getByRole("button", { name: "Reject" }),
      );
      fireEvent.change(reason(drawn), { target: { value: "Too short." } });
      await press(
        within(reviewFrame()).getByRole("button", { name: "Submit review" }),
      );
      await landing(
        () => reason(message()).getAttribute("aria-invalid") === "true",
      );

      await act(() => vi.advanceTimersByTimeAsync(5000));
      await landing(() => screen.queryByText("A longer summary.") !== null);
      await press(
        within(reviewFrame()).getByRole("button", { name: "Reject" }),
      );

      const why = reason(message());
      expect(why).toHaveValue("");
      expect(why).toHaveAttribute("aria-invalid", "false");
      expect(why).not.toHaveAccessibleDescription();
      expect(screen.queryByRole("alert")).toBeNull();
      expect(asked(sent, `GET ${STEPS}`)).toBe(2);
    });

    it("sends Accept once while the first is out, holding it until that answers", async () => {
      const { sent } = conversing({ [`PUT ${REVIEW}`]: [UNANSWERED] });
      await messageDrawn();
      const assure = within(reviewFrame()).getByRole("button", {
        name: "Accept",
      });

      await press(assure);
      await landing(() => reviews(sent).length === 1);
      dispatchClick(assure);
      await act(() => vi.advanceTimersByTimeAsync(0));

      expect(reviews(sent)).toHaveLength(1);
      expect(assure).toHaveAttribute("aria-disabled", "true");
      expect(
        screen.getByRole("region", { name: "Needs your review" }),
      ).toBeVisible();
    });

    it("lets go of a read of the run already out when the review answers, so what that read says never replaces the answer", async () => {
      let settle!: (answer: readonly [string, number]) => void;
      const stale: Reply = new Promise((done) => {
        settle = done;
      });
      const { sent } = conversing({
        [`GET ${STEPS}`]: [stepsRead(), stale, UNANSWERED],
        [`PUT ${REVIEW}`]: [ASSURED],
      });
      await messageDrawn();
      await act(() => vi.advanceTimersByTimeAsync(5000));
      await landing(() => asked(sent, `GET ${STEPS}`) === 2);

      await press(
        within(reviewFrame()).getByRole("button", { name: "Accept" }),
      );
      await landing(() => within(message()).queryByText("Done") !== null);
      settle(stepsRead() as readonly [string, number]);
      await act(() => vi.advanceTimersByTimeAsync(0));

      expect(within(message()).getByText("Done")).toBeVisible();
      expect(
        screen.queryByRole("region", { name: "Needs your review" }),
      ).toBeNull();
      expect(screen.queryByRole("button", { name: "Accept" })).toBeNull();
      expect(asked(sent, `GET ${STEPS}`)).toBe(3);
    });

    it("keeps saying what the review's answer changed through a read again that reaches nothing", async () => {
      const offline = Promise.reject(new TypeError("Failed to fetch"));
      offline.catch(() => {});
      const { sent } = conversing({
        [`GET ${RUN}`]: [reply(CLAIM), offline],
        [`GET ${STEPS}`]: [stepsRead(), UNANSWERED],
        [`PUT ${REVIEW}`]: [ASSURED],
      });
      await messageDrawn();

      await press(
        within(reviewFrame()).getByRole("button", { name: "Accept" }),
      );
      await landing(() => asked(sent, `GET ${RUN}`) === 2);
      await act(() => vi.advanceTimersByTimeAsync(0));
      await act(() => vi.advanceTimersByTimeAsync(0));

      expect(regionsSaid()).toEqual([
        "",
        `${setApart("Summarise")}: now Done.`,
        "",
        "",
        "",
        "",
      ]);
    });

    it("says a stopped run takes no review, and reads the step again, which offers none", async () => {
      const { sent } = conversing({
        [`GET ${RUN}`]: [reply(CLAIM), UNANSWERED],
        [`GET ${STEPS}`]: [stepsRead(), UNANSWERED],
        [`PUT ${REVIEW}`]: [refusal("RUN_STOPPED", 409)],
        [`GET ${STEP}`]: [STOPPED],
      });
      await messageDrawn();

      await press(
        within(reviewFrame()).getByRole("button", { name: "Accept" }),
      );
      await landing(() => asked(sent, `GET ${STEP}`) === 1);
      await landing(
        () => screen.queryByRole("button", { name: "Accept" }) === null,
      );

      const said = screen.getByRole("alert");
      expect(said).toHaveTextContent(
        "This run is stopped. Resume it before answering, reviewing or making another attempt.",
      );
      expect(document.activeElement).toContainElement(said);
      expect(reviews(sent)).toHaveLength(1);
    });

    it("says the values wait on a model's review now, and reads the step again, which offers the reader none", async () => {
      const { sent, readAgain } = conversing({
        [`GET ${RUN}`]: [reply(CLAIM), UNANSWERED],
        [`GET ${STEPS}`]: [stepsRead(), UNANSWERED],
        [`PUT ${REVIEW}`]: [refusal("REVIEW_NOT_A_PERSONS", 409)],
        [`GET ${STEP}`]: [MODEL_REVIEWS],
      });
      await messageDrawn();

      await press(
        within(reviewFrame()).getByRole("button", { name: "Accept" }),
      );
      await landing(
        () => screen.queryByRole("button", { name: "Accept" }) === null,
      );

      const said = screen.getByRole("alert");
      expect(said).toHaveTextContent(
        "These values are for the model this step names to review, not a person.",
      );
      expect(document.activeElement).toContainElement(said);
      expect(asked(sent, `GET ${STEP}`)).toBe(1);
      expect(reviews(sent)).toHaveLength(1);
      expect(readAgain).not.toHaveBeenCalled();
      expect(
        screen.queryByRole("region", { name: "Needs your review" }),
      ).toBeNull();
    });

    it("reads an open Details again once a review from the message answers, so its try shows reviewed rather than waiting", async () => {
      const { sent } = conversing({
        [`GET ${RUN}`]: [reply(CLAIM), UNANSWERED],
        [`GET ${STEPS}`]: [stepsRead(), UNANSWERED],
        [`GET ${STEP}`]: [WAITING_READ, ASSURED],
        [`PUT ${REVIEW}`]: [ASSURED],
      });
      const drawn = await messageDrawn();
      const control = within(drawn).getByRole("button", { name: "Details" });
      await press(control);
      const details = () =>
        document.getElementById(control.getAttribute("aria-controls")!)!;
      await landing(
        () => within(details()).queryByText("Not reviewed yet.") !== null,
      );

      await press(
        within(reviewFrame()).getByRole("button", { name: "Accept" }),
      );
      await landing(() => asked(sent, `GET ${STEP}`) === 2);
      await landing(
        () => within(details()).queryAllByText("Accepted.").length === 2,
      );
      expect(within(details()).getAllByText("Accepted.")).toHaveLength(2);
      expect(
        within(details()).getByRole("rowheader", {
          name: "\u2068Summary\u2069",
        }),
      ).toBeVisible();

      expect(within(details()).getByText("A person")).toBeVisible();
      expect(within(details()).queryByText("Not reviewed yet.")).toBeNull();
      expect(within(details()).queryByText("Waiting for review.")).toBeNull();
    });

    it("marks at its field a reason the server refuses, giving the field the keyboard and saying nothing else, until it is changed", async () => {
      const { sent } = conversing({
        [`PUT ${REVIEW}`]: [refusal("REASON_UNUSABLE", 400)],
      });
      const drawn = await messageDrawn();
      await press(
        within(reviewFrame()).getByRole("button", { name: "Reject" }),
      );
      const why = reason(drawn);
      fireEvent.change(why, { target: { value: "Too short." } });

      await press(
        within(reviewFrame()).getByRole("button", { name: "Submit review" }),
      );
      await landing(() => why.getAttribute("aria-invalid") === "true");

      expect(why).toHaveAccessibleDescription(REASON_UNUSABLE);
      expect(document.activeElement).toBe(why);
      expect(screen.queryByRole("alert")).toBeNull();
      expect(asked(sent, `GET ${STEP}`)).toBe(0);

      fireEvent.change(why, { target: { value: "Too short, and wrong." } });

      expect(why).toHaveAttribute("aria-invalid", "false");
      expect(screen.queryByRole("alert")).toBeNull();
    });

    it("says the review is refused by the rule of who may review a step and reads the reader's standing again, where the server refuses the act so, reading nothing of the step", async () => {
      const { sent, readAgain } = conversing({
        [`PUT ${REVIEW}`]: [refusal("ACT_NOT_PERMITTED", 403)],
      });
      await messageDrawn();

      await press(
        within(reviewFrame()).getByRole("button", { name: "Accept" }),
      );
      await landing(() => screen.queryByRole("alert") !== null);

      const said = screen.getByRole("alert");
      expect(said).toHaveTextContent(REVIEW_RULE);
      expect(said).not.toHaveTextContent(
        "You don't have permission to do this.",
      );
      expect(document.activeElement).toContainElement(said);
      expect(readAgain).toHaveBeenCalledTimes(1);
      expect(asked(sent, `GET ${STEP}`)).toBe(0);
    });

    it("leaves the keyboard where the reader put it when the run is read again later", async () => {
      const after = reply({
        run: HEADER_ON,
        declarations: declarations(SUMMARY),
        gaveBack: { declares: "nothing" },
        steps: [
          {
            ...WAITING_ROW,
            state: "done",
            where: undefined,
            gaveBack: [{ field: "summary", value: WRITTEN, now: "stands" }],
            stateActs: [],
          },
          {
            ...FILE_IT_ROW,
            state: "running",
            where: { kind: "running", on: "code" },
          },
        ],
        rereadAfterSeconds: 5,
      });
      const { sent } = conversing({
        [`GET ${STEPS}`]: [stepsRead(), after],
        [`PUT ${REVIEW}`]: [ASSURED],
      });
      await messageDrawn();
      await press(
        within(reviewFrame()).getByRole("button", { name: "Accept" }),
      );
      await landing(() => document.activeElement === message());
      const elsewhere = screen.getByRole("button", {
        name: "Full detail",
      });
      act(() => elsewhere.focus());

      await act(() => vi.advanceTimersByTimeAsync(5000));
      await landing(() => asked(sent, `GET ${STEPS}`) >= 3);
      await act(() => vi.advanceTimersByTimeAsync(0));

      expect(document.activeElement).toBe(elsewhere);
    });
  });

  describe("on the step's own page", () => {
    const TWO_ROW = {
      ...WAITING_ROW,
      where: {
        ...WAITING_ROW.where,
        values: [
          { field: "summary", on: "review_at_gate" },
          { field: "reply", on: "review_at_gate" },
        ],
      },
      gaveBack: [
        { field: "summary", value: "A fire.", now: "waiting_on_review" },
        { field: "reply", value: WRITTEN, now: "waiting_on_review" },
      ],
    };

    function twoWaiting(step: object = TWO_ROW): Reply {
      return reply({
        run: HEADER,
        rereadAfterSeconds: 5,
        declarations: declarations(SUMMARY, REPLY),
        step,
        wentIn: WENT_IN,
        wentInFrom: "try",
        cameOut: [
          { field: "summary", now: "waiting_on_review" },
          { field: "reply", now: "waiting_on_review" },
        ],
        triesMade: [
          {
            number: 1,
            beyond: false,
            producedBy: { kind: "person", person: CAT },
            values: [
              { field: "summary", value: "A fire.", now: "waiting_on_review" },
              { field: "reply", value: WRITTEN, now: "waiting_on_review" },
            ],
            review: { asked: true },
            ended: "waiting",
            cost: { callsAModel: false },
          },
        ],
      });
    }

    const DECIDED = reply({
      run: HEADER,
      declarations: declarations(SUMMARY, REPLY),
      step: {
        ...TWO_ROW,
        where: {
          kind: "owed_try",
          open: false,
          beyond: false,
          since: "2026-09-26T09:30:00Z",
          waitsOn: "answer_step",
        },
        gaveBack: [
          { field: "summary", value: "A fire.", now: "stands" },
          { field: "reply", value: WRITTEN, now: "refused" },
        ],
        next: { number: 2, beyond: false },
        stateActs: ["answer", "ask_again"],
        stateRefusals: [],
      },
      wentIn: WENT_IN,
      wentInFrom: "try",
      cameOut: [
        {
          field: "summary",
          standing: { number: 1, value: "A fire." },
          now: "stands",
        },
        { field: "reply", now: "refused" },
      ],
      triesMade: [
        {
          number: 1,
          beyond: false,
          producedBy: { kind: "person", person: CAT },
          values: [
            {
              field: "summary",
              value: "A fire.",
              now: "stands",
              decision: { outcome: "assured" },
            },
            {
              field: "reply",
              value: WRITTEN,
              now: "refused",
              decision: {
                outcome: "refused",
                whyLines: "several",
                why: "Too curt.",
              },
            },
          ],
          review: { asked: true, by: { kind: "person" } },
          ended: "refused_on_review",
          cost: { callsAModel: false },
        },
      ],
    });

    async function pageDrawn(): Promise<HTMLElement> {
      await landing(() => screen.queryByRole("heading", { level: 1 }) !== null);
      return screen.getByRole("region", { name: "Needs your review" });
    }

    function choice(review: HTMLElement, label: string): HTMLElement {
      return within(review).getByRole("radiogroup", {
        name: `Decision for ${setApart(label)}`,
      });
    }

    function option(
      review: HTMLElement,
      label: string,
      name: "Accept" | "Reject",
    ): HTMLElement {
      return within(choice(review, label)).getByRole("radio", { name });
    }

    it("opens each long value waiting beside its own choice, and sends nothing until every value is chosen and every refusal says why", async () => {
      const { sent } = stepOpened({
        [`GET ${STEP}`]: [twoWaiting()],
        [`PUT ${REVIEW}`]: [DECIDED],
      });
      const review = await pageDrawn();
      const submit = within(review).getByRole("button", {
        name: "Submit review",
      });

      expect(review).toHaveTextContent("A fire.");
      expect(review).toHaveTextContent(
        "A printer caught fire. Nobody was hurt.",
      );
      const expand = within(review).getByRole("button", { name: "Show all" });
      expect(expand).toHaveAttribute("aria-expanded", "false");
      await press(expand);
      expect(expand).toHaveAttribute("aria-expanded", "true");
      expect(review).toHaveTextContent(
        "A printer caught fire. Nobody was hurt.",
      );
      expect(
        within(review).queryByRole("button", { name: /in full/ }),
      ).toBeNull();
      for (const label of ["Summary", "Reply"]) {
        const radios = within(choice(review, label)).getAllByRole("radio");
        expect(
          radios.map((each) =>
            each.textContent === "Accept" ? "assured" : "refused",
          ),
        ).toEqual(["assured", "refused"]);
        expect(
          radios.filter((each) => each.getAttribute("aria-checked") === "true"),
        ).toEqual([]);
      }
      expect(submit).toHaveAttribute("aria-disabled", "true");

      await press(option(review, "Summary", "Accept"));
      expect(submit).toHaveAttribute("aria-disabled", "true");
      await press(option(review, "Reply", "Reject"));

      const why = within(review).getByRole("textbox", {
        name: `Reason for rejecting ${setApart("Reply")}`,
      });
      const accept = option(review, "Summary", "Accept");
      const reject = option(review, "Reply", "Reject");
      await press(accept);
      await press(reject);
      expect(accept).toHaveAttribute("aria-checked", "true");
      expect(reject).toHaveAttribute("aria-checked", "true");
      expect(
        within(review).getByRole("textbox", {
          name: `Reason for rejecting ${setApart("Reply")}`,
        }),
      ).toBe(why);
      expect(within(review).getAllByRole("textbox")).toEqual([why]);
      expect(submit).toHaveAttribute("aria-disabled", "true");
      dispatchClick(submit);
      await act(() => vi.advanceTimersByTimeAsync(0));
      expect(reviews(sent)).toEqual([]);
    });

    it("takes the keyboard into each decision at one stop, moves and chooses with the arrows, and keeps a choice pressed again", async () => {
      stepOpened({ [`GET ${STEP}`]: [twoWaiting()] });
      const review = await pageDrawn();
      vi.useRealTimers();
      const user = userEvent.setup();
      const group = choice(review, "Summary");
      const accept = within(group).getByRole("radio", { name: "Accept" });
      const reject = within(group).getByRole("radio", { name: "Reject" });
      const checked = () =>
        [accept, reject].map((radio) => radio.getAttribute("aria-checked"));
      const stops = () =>
        within(group)
          .getAllByRole("radio")
          .filter((radio) => radio.tabIndex === 0);
      expect(checked()).toEqual(["false", "false"]);
      expect(stops()).toEqual([accept]);
      await act(async () => {
        accept.focus();
      });
      await user.tab();
      expect(group).not.toContainElement(document.activeElement as HTMLElement);
      await user.tab({ shift: true });
      expect(accept).toHaveFocus();
      expect(checked()).toEqual(["false", "false"]);

      await user.keyboard("{ArrowRight}");
      expect(reject).toHaveFocus();
      expect(checked()).toEqual(["false", "true"]);
      expect(stops()).toEqual([reject]);
      await user.keyboard("{ArrowRight}");
      expect(accept).toHaveFocus();
      expect(checked()).toEqual(["true", "false"]);
      await user.keyboard("{ArrowLeft}");
      expect(reject).toHaveFocus();
      expect(checked()).toEqual(["false", "true"]);
      await user.keyboard("{ArrowUp}");
      expect(accept).toHaveFocus();
      expect(stops()).toEqual([accept]);

      await user.keyboard("{Enter}{ }");
      await user.click(accept);
      expect(checked()).toEqual(["true", "false"]);
      expect(accept).toHaveFocus();
      expect(
        within(choice(review, "Reply"))
          .getAllByRole("radio")
          .map((radio) => radio.getAttribute("aria-checked")),
      ).toEqual(["false", "false"]);
    });

    it("leaves the keyboard and both choices where they are at an arrow while the review is being sent", async () => {
      const sending = deferred<readonly [string, number]>();
      stepOpened({
        [`GET ${STEP}`]: [twoWaiting()],
        [`PUT ${REVIEW}`]: [sending.promise],
      });
      const review = await pageDrawn();
      await press(option(review, "Summary", "Accept"));
      await press(option(review, "Reply", "Accept"));
      await press(
        within(review).getByRole("button", { name: "Submit review" }),
      );
      vi.useRealTimers();
      const user = userEvent.setup();
      const accept = option(review, "Summary", "Accept");
      const reject = option(review, "Summary", "Reject");
      expect(accept).toHaveAttribute("aria-disabled", "true");
      await act(async () => {
        accept.focus();
      });

      await user.keyboard("{ArrowRight}");

      expect(accept).toHaveFocus();
      expect(reject).not.toHaveFocus();
      expect([accept, reject].map((radio) => radio.ariaChecked)).toEqual([
        "true",
        "false",
      ]);
      await act(async () => sending.settle(await DECIDED));
    });

    it("draws the choice made as on, with a check before its words kept from its name, and the other as off with none", async () => {
      stepOpened({ [`GET ${STEP}`]: [twoWaiting()] });
      const review = await pageDrawn();
      const accept = option(review, "Summary", "Accept");
      const reject = option(review, "Summary", "Reject");
      for (const radio of [accept, reject]) {
        expect(radio.querySelector("svg")).toBeNull();
        expect(
          getComputedStyle(radio).getPropertyValue("--variant-outlinedBg"),
        ).toBe("#ffffff");
      }

      await press(accept);

      const check = accept.querySelector("svg");
      expect(check).toHaveAttribute("aria-hidden", "true");
      expect(check!.nextSibling?.textContent).toBe("Accept");
      expect(accept).toHaveAccessibleName("Accept");
      const on = getComputedStyle(accept);
      expect([
        on.getPropertyValue("--variant-outlinedBg"),
        on.getPropertyValue("--variant-outlinedBorder"),
        on.getPropertyValue("--variant-outlinedColor"),
      ]).toEqual(["#daebf7", "#1c5b81", "#1c5b81"]);
      expect(reject.querySelector("svg")).toBeNull();
      expect(
        getComputedStyle(reject).getPropertyValue("--variant-outlinedBg"),
      ).toBe("#ffffff");
      expect(option(review, "Reply", "Accept").querySelector("svg")).toBeNull();
    });

    it("sends one review deciding every value its own way, then shows the step as it answers, reads it again and gives the keyboard to what came out", async () => {
      const { sent } = stepOpened({
        [`GET ${STEP}`]: [twoWaiting(), UNANSWERED],
        [`PUT ${REVIEW}`]: [DECIDED],
      });
      const review = await pageDrawn();
      await press(option(review, "Summary", "Accept"));
      await press(option(review, "Reply", "Reject"));
      fireEvent.change(within(review).getByRole("textbox"), {
        target: { value: "Too curt." },
      });

      await press(
        within(review).getByRole("button", { name: "Submit review" }),
      );
      await landing(
        () =>
          screen.queryByRole("region", { name: "Needs your review" }) === null,
      );

      expect(reviews(sent).map((init) => init.body)).toEqual([
        JSON.stringify({
          decisions: {
            summary: { outcome: "assured" },
            reply: { outcome: "refused", why: "Too curt." },
          },
        }),
      ]);
      const cameOut = screen.getByRole("region", { name: "Output" });
      expect(cameOut).toHaveTextContent("A fire.");
      expect(cameOut).not.toHaveTextContent("Accepted.");
      expect(cameOut).toHaveTextContent("Rejected in review");
      expect(document.activeElement).toBe(
        within(cameOut).getByRole("heading", { name: "Output" }),
      );
      expect(screen.queryByRole("radiogroup")).toBeNull();
      expect(screen.queryByRole("alert")).toBeNull();
      expect(asked(sent, `GET ${STEP}`)).toBe(2);
    });

    it("keeps two distinct refusal reasons and choices after the server rejects their review once", async () => {
      const { sent } = stepOpened({
        [`GET ${STEP}`]: [twoWaiting()],
        [`PUT ${REVIEW}`]: [refusal("REASON_UNUSABLE", 400)],
      });
      const review = await pageDrawn();
      for (const [label, reason] of [
        ["Summary", "The summary misses the fire."],
        ["Reply", "The reply misses the printer."],
      ] as const) {
        await press(option(review, label, "Reject"));
        fireEvent.change(
          within(review).getByRole("textbox", {
            name: `Reason for rejecting ${setApart(label)}`,
          }),
          { target: { value: reason } },
        );
      }

      await press(
        within(review).getByRole("button", { name: "Submit review" }),
      );
      await landing(() => screen.queryByRole("alert") !== null);

      const alert = screen.getByRole("alert");
      expect(screen.getAllByRole("alert")).toEqual([alert]);
      expect(alert).toHaveTextContent(REASON_UNUSABLE);
      expect(document.activeElement).toBe(alert.closest("[tabindex='-1']"));
      expect(reviews(sent)).toHaveLength(1);
      expect(JSON.parse(String(reviews(sent)[0]!.body))).toEqual({
        decisions: {
          summary: { outcome: "refused", why: "The summary misses the fire." },
          reply: { outcome: "refused", why: "The reply misses the printer." },
        },
      });
      for (const [label, reason] of [
        ["Summary", "The summary misses the fire."],
        ["Reply", "The reply misses the printer."],
      ] as const) {
        expect(option(review, label, "Reject")).toHaveAttribute(
          "aria-checked",
          "true",
        );
        expect(
          within(review).getByRole("textbox", {
            name: `Reason for rejecting ${setApart(label)}`,
          }),
        ).toHaveValue(reason);
      }
    });

    it("lets go of a read of the step already out when the review answers, so what that read says never replaces the answer", async () => {
      let settle!: (answer: readonly [string, number]) => void;
      const stale: Reply = new Promise((done) => {
        settle = done;
      });
      const { sent } = stepOpened({
        [`GET ${STEP}`]: [twoWaiting(), stale, UNANSWERED],
        [`PUT ${REVIEW}`]: [DECIDED],
      });
      const review = await pageDrawn();
      await act(() => vi.advanceTimersByTimeAsync(5000));
      await landing(() => asked(sent, `GET ${STEP}`) === 2);
      await press(option(review, "Summary", "Accept"));
      await press(option(review, "Reply", "Reject"));
      fireEvent.change(within(review).getByRole("textbox"), {
        target: { value: "Too curt." },
      });

      await press(
        within(review).getByRole("button", { name: "Submit review" }),
      );
      await landing(
        () =>
          screen.queryByRole("region", { name: "Needs your review" }) === null,
      );
      settle(twoWaiting() as readonly [string, number]);
      await act(() => vi.advanceTimersByTimeAsync(0));

      expect(screen.getByRole("region", { name: "Output" })).toHaveTextContent(
        "Rejected in review",
      );
      expect(
        screen.queryByRole("region", { name: "Needs your review" }),
      ).toBeNull();
      expect(screen.queryByRole("radiogroup")).toBeNull();
      expect(asked(sent, `GET ${STEP}`)).toBe(3);
    });

    it("keeps what was chosen and the reason half typed where the step is read again meanwhile", async () => {
      const { sent } = stepOpened({ [`GET ${STEP}`]: [twoWaiting()] });
      const review = await pageDrawn();
      await press(option(review, "Summary", "Accept"));
      await press(option(review, "Reply", "Reject"));
      fireEvent.change(within(review).getByRole("textbox"), {
        target: { value: "Too cu" },
      });

      await act(() => vi.advanceTimersByTimeAsync(5000));
      await landing(() => asked(sent, `GET ${STEP}`) === 2);
      await act(() => vi.advanceTimersByTimeAsync(0));

      const again = screen.getByRole("region", { name: "Needs your review" });
      const checked = (label: string) =>
        within(choice(again, label))
          .getAllByRole("radio")
          .filter((each) => each.getAttribute("aria-checked") === "true")
          .map((each) =>
            each.textContent === "Accept" ? "assured" : "refused",
          );
      expect(checked("Summary")).toEqual(["assured"]);
      expect(checked("Reply")).toEqual(["refused"]);
      expect(within(again).getByRole("textbox")).toHaveValue("Too cu");
      expect(screen.queryByRole("alert")).toBeNull();
    });

    it("takes the keyboard to the reason once Reject is pressed beside a value", async () => {
      stepOpened({ [`GET ${STEP}`]: [twoWaiting()] });
      const review = await pageDrawn();
      // Testing Library wraps every user-event call in a wait on a timeout of 0 that it advances only where `jest`
      // is defined, so faked timeouts hang it whatever `advanceTimers` says.
      vi.useRealTimers();
      const user = userEvent.setup();
      await user.click(option(review, "Reply", "Accept"));

      await user.click(option(review, "Reply", "Reject"));

      const refuse = option(review, "Reply", "Reject");
      expect(refuse).toHaveAttribute("aria-checked", "true");
      expect(
        within(review).getByRole("textbox", {
          name: `Reason for rejecting ${setApart("Reply")}`,
        }),
      ).toHaveFocus();
      const why = within(review).getByRole("textbox", {
        name: `Reason for rejecting ${setApart("Reply")}`,
      });
      expect(
        refuse.compareDocumentPosition(why) & Node.DOCUMENT_POSITION_FOLLOWING,
      ).toBeTruthy();
    });

    it("says why Submit is held until every value is chosen and every refusal says why, and nothing once it is not", async () => {
      const held =
        "Choose Accept or Reject for each value, and give a reason for each one you reject.";
      stepOpened({ [`GET ${STEP}`]: [twoWaiting()] });
      const review = await pageDrawn();
      const submit = within(review).getByRole("button", {
        name: "Submit review",
      });
      expect(submit).toHaveAccessibleDescription(held);

      await press(option(review, "Summary", "Accept"));
      await press(option(review, "Reply", "Reject"));
      expect(submit).toHaveAccessibleDescription(held);
      fireEvent.change(within(review).getByRole("textbox"), {
        target: { value: "Too curt." },
      });

      expect(submit).not.toHaveAccessibleDescription();
      expect(submit).not.toHaveAttribute("aria-disabled");
      expect(within(review).queryByText(held)).toBeNull();
    });

    it("draws no review where the step withholds it from the reader, what went in drawn all the same", async () => {
      stepOpened({
        [`GET ${STEP}`]: [
          twoWaiting({
            ...TWO_ROW,
            stateActs: ["review"],
            stateRefusals: [],
            producedByUserId: DAN.userId,
          }),
        ],
      });
      await landing(() => screen.queryByRole("heading", { level: 1 }) !== null);

      expect(screen.getByRole("region", { name: "Inputs" })).toBeVisible();
      expect(
        screen.queryByRole("region", { name: "Needs your review" }),
      ).toBeNull();
      expect(screen.queryByRole("radiogroup")).toBeNull();
      expect(
        screen.queryByRole("button", { name: "Submit review" }),
      ).toBeNull();
    });
  });
});
