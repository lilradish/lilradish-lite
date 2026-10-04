import {
  act,
  fireEvent,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import { userEvent } from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { requestsTo, serving, type Reply } from "../../../testutil/answering";
import { deferred } from "../../../testutil/deferred";
import { openStepPage } from "../../../testutil/stepPage";
import { accountOpened } from "../../../testutil/stateAccount";

const GROUP = "00000003-0000-4000-8000-000000000d01";

const RUN_ID = "00000008-0000-4000-8000-000000000d01";

const VERSION = "00000007-0000-4000-8000-000000000d01";

const QUESTION_VERSION = "00000007-0000-4000-8000-000000000d02";

const SUMMARISE = "00000009-0000-4000-8000-000000000d01";

const FILE_IT = "00000009-0000-4000-8000-000000000d02";

const PAGE = `/groups/${GROUP}/work/${RUN_ID}`;

const RUN = `/api/groups/${GROUP}/runs/${RUN_ID}`;

const STEPS = `${RUN}/steps`;

const STEP = `${STEPS}/${SUMMARISE}`;

const ANSWER = `${STEP}/tries/2/answer`;

const CAT = { userId: "000d01", displayName: "Cat" };

const DAN = { userId: "000d02", displayName: "Dan" };

const EVE = { userId: "000d04", displayName: "Eve" };

/** What is said where the answer is refused for the act itself: the rule of who may answer a step. */
const ANSWER_RULE =
  "A group's steps are answered, and asked for again, only by a role in it that may answer one.";

const CRLF_REFUSED =
  "Lines here end in a line feed alone, never in a carriage return and a line feed (CRLF).";

const CLAIM = {
  runId: RUN_ID,
  number: 7,
  name: "Claim from Avo",
  workflow: {
    entryId: "00000006-0000-4000-8000-000000000d01",
    name: "Handle a claim",
    version: 3,
  },
  startedBy: { userId: "000d03", displayName: "Avo" },
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
  acts: ["stop"],
};

const STOPPED_CLAIM = {
  ...CLAIM,
  state: "stopped",
  at: undefined,
  stopped: { at: "2026-09-26T09:45:00Z", by: CLAIM.startedBy },
  acts: ["open_again"],
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
  longest: 20,
  mustBeGiven: true,
};

const PRIORITY = {
  name: "priority",
  label: "Priority",
  kind: "term",
  mustBeGiven: true,
  terms: {
    terms: [
      { term: "high", meaning: "Today." },
      { term: "low", meaning: "This week." },
    ],
  },
};

const DECLARATIONS = {
  [VERSION]: { takes: [TICKET], gives: [] },
  [QUESTION_VERSION]: {
    takes: [{ ...TICKET, name: "text", label: "Text" }],
    gives: [SUMMARY, PRIORITY],
  },
};

const HEADER = {
  runId: RUN_ID,
  number: 7,
  versionId: VERSION,
  state: "running",
  at: { stepId: SUMMARISE, name: "summarise" },
  acts: ["stop"],
  progress: { done: 0, of: 2 },
};

const STOPPED_HEADER = {
  ...HEADER,
  state: "stopped",
  at: undefined,
  acts: ["open_again"],
};

const WENT_IN = [
  {
    input: "text",
    from: { kind: "run_input", path: "ticket" },
    value: "Printer on fire",
  },
];

const OWED_ROW = {
  stepId: SUMMARISE,
  order: 1,
  name: "summarise",
  runs: {
    kind: "question",
    entryId: "00000006-0000-4000-8000-000000000d02",
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
  takesFrom: [{ input: "text", from: { kind: "run_input", path: "ticket" } }],
  tries: { current: 1, declared: 2, beyond: false },
  cost: { callsAModel: false },
  gaveBack: [
    { field: "summary", value: "A fire.", now: "refused" },
    { field: "priority", value: "high", now: "stands" },
  ],
  next: { number: 2, beyond: false },
  acts: ["answer", "ask_again"],
  withheld: [],
};

const STOPPED_ROW = {
  where: { ...OWED_ROW.where, waitsOn: undefined },
  acts: [],
  withheld: [
    { act: "answer", refusal: "RUN_STOPPED" },
    { act: "ask_again", refusal: "RUN_STOPPED" },
  ],
};

const FILE_IT_ROW = {
  stepId: FILE_IT,
  order: 2,
  name: "file_it",
  runs: { kind: "route" },
  state: "not_started",
  takesFrom: [],
  cost: { callsAModel: false },
  acts: [],
  withheld: [],
};

const FIRST_TRY = {
  number: 1,
  beyond: false,
  producedBy: { kind: "person", person: CAT },
  why: "Read it once.",
  values: [
    {
      field: "summary",
      value: "A fire.",
      now: "refused",
      decision: { outcome: "refused", why: "Too short." },
    },
    {
      field: "priority",
      value: "high",
      now: "stands",
      decision: { outcome: "assured" },
    },
  ],
  review: { asked: true, by: { kind: "person", person: DAN } },
  ended: "refused_on_review",
  cost: { callsAModel: false },
};

const SMALL = { kind: "model", model: "small" };

/** Include an invisible character to exercise how hidden review text is shown. */
const MODEL_REVIEW = { asked: true, by: SMALL };

const ZERO_WIDTH_SPACE = String.fromCodePoint(0x200b);

const RIGHT_TO_LEFT_OVERRIDE = String.fromCodePoint(0x202e);

const INSTRUCTION = "Summarise the ticket in a line.\nSay who is hurt.";

const ANSWERING = {
  number: 2,
  beyond: false,
  instruction: INSTRUCTION,
  gives: [SUMMARY, PRIORITY],
  lastRefused: { number: 1, values: FIRST_TRY.values },
};

const CAME_OUT = [
  { field: "summary", now: "refused" },
  {
    field: "priority",
    standing: { number: 1, value: "high" },
    now: "stands",
  },
];

function stepRead(
  row: object = {},
  answering: object = {},
  page: object = {},
): Reply {
  return reply({
    run: HEADER,
    rereadAfterSeconds: 5,
    declarations: DECLARATIONS,
    step: { ...OWED_ROW, ...row },
    wentIn: WENT_IN,
    wentInFrom: "try",
    cameOut: CAME_OUT,
    triesMade: [FIRST_TRY],
    answering: { ...ANSWERING, ...answering },
    ...page,
  });
}

function stepsRead(row: object = {}): Reply {
  return reply({
    run: HEADER,
    declarations: DECLARATIONS,
    gaveBack: { declares: "nothing" },
    steps: [{ ...OWED_ROW, ...row }, FILE_IT_ROW],
    rereadAfterSeconds: 5,
  });
}

function stoppedStepsRead(): Reply {
  return reply({
    run: STOPPED_HEADER,
    declarations: DECLARATIONS,
    gaveBack: { declares: "nothing" },
    steps: [{ ...OWED_ROW, ...STOPPED_ROW }, FILE_IT_ROW],
  });
}

function stoppedStepRead(): Reply {
  return reply({
    run: STOPPED_HEADER,
    declarations: DECLARATIONS,
    step: { ...OWED_ROW, ...STOPPED_ROW },
    wentIn: WENT_IN,
    wentInFrom: "try",
    cameOut: CAME_OUT,
    triesMade: [FIRST_TRY],
  });
}

const ENTRY_STOPPED_ROW = {
  state: "held_back",
  where: {
    kind: "held_back",
    reason: "entry_stopped",
    since: "2026-09-26T09:45:00Z",
    stopped: {
      what: "workflow",
      by: CLAIM.startedBy,
      at: "2026-09-26T09:45:00Z",
    },
    waitsOn: "starter",
  },
  acts: [],
  withheld: [
    { act: "answer", refusal: "ENTRY_STOPPED" },
    { act: "ask_again", refusal: "ENTRY_STOPPED" },
  ],
};

function entryStoppedStepRead(): Reply {
  return reply({
    run: HEADER,
    rereadAfterSeconds: 5,
    declarations: DECLARATIONS,
    step: { ...OWED_ROW, ...ENTRY_STOPPED_ROW },
    wentIn: WENT_IN,
    wentInFrom: "try",
    cameOut: CAME_OUT,
    triesMade: [FIRST_TRY],
  });
}

const ANSWERED_ROW = {
  where: {
    kind: "waiting_on_review",
    number: 2,
    values: [{ field: "summary", on: "review_at_gate" }],
    since: "2026-09-26T10:00:00Z",
    waitsOn: "review_at_gate",
  },
  tries: { current: 2, declared: 2, beyond: false },
  gaveBack: [
    {
      field: "summary",
      value: "A printer fire.",
      now: "waiting_on_review",
    },
    { field: "priority", value: "low", now: "stands" },
  ],
  next: undefined,
  acts: [],
};

function answeredBy(
  person: typeof DAN,
  withheld: object,
): readonly [string, number] {
  return reply({
    run: HEADER,
    rereadAfterSeconds: 5,
    declarations: DECLARATIONS,
    step: { ...OWED_ROW, ...ANSWERED_ROW, withheld: [withheld] },
    wentIn: WENT_IN,
    wentInFrom: "try",
    cameOut: [
      { field: "summary", now: "waiting_on_review" },
      {
        field: "priority",
        standing: { number: 2, value: "low" },
        now: "stands",
      },
    ],
    triesMade: [
      FIRST_TRY,
      {
        number: 2,
        beyond: false,
        producedBy: { kind: "person", person },
        why: "Longer, and less urgent.",
        values: [
          {
            field: "summary",
            value: "A printer fire.",
            now: "waiting_on_review",
          },
          { field: "priority", value: "low", now: "stands" },
        ],
        review: { asked: true },
        ended: "waiting",
        cost: { callsAModel: false },
      },
    ],
  });
}

/** A person cannot review their own answer. */
const ANSWERED = answeredBy(DAN, {
  act: "review",
  refusal: "REVIEW_OWN_PRODUCTION",
});

const EVE_ANSWERED = answeredBy(EVE, {
  act: "review",
  refusal: "ACT_NOT_PERMITTED",
});

const SECOND_TRY = {
  number: 2,
  beyond: false,
  producedBy: { kind: "person", person: EVE },
  why: "Longer, and less urgent.",
  values: [
    {
      field: "summary",
      value: "A printer fire.",
      now: "refused",
      decision: { outcome: "refused", why: "Say where." },
    },
    {
      field: "priority",
      value: "low",
      now: "stands",
      decision: { outcome: "assured" },
    },
  ],
  review: { asked: true, by: { kind: "person", person: CAT } },
  ended: "refused_on_review",
  cost: { callsAModel: false },
};

const THIRD_OWED = {
  tries: { current: 2, declared: 3, beyond: false },
  gaveBack: [
    { field: "summary", value: "A printer fire.", now: "refused" },
    { field: "priority", value: "low", now: "stands" },
  ],
  next: { number: 3, beyond: false },
};

function thirdOwedRead(
  row: object = THIRD_OWED,
  answering: object = {},
): Reply {
  return reply({
    run: HEADER,
    rereadAfterSeconds: 5,
    declarations: DECLARATIONS,
    step: { ...OWED_ROW, ...row },
    wentIn: WENT_IN,
    wentInFrom: "try",
    cameOut: [
      { field: "summary", now: "refused" },
      {
        field: "priority",
        standing: { number: 2, value: "low" },
        now: "stands",
      },
    ],
    triesMade: [FIRST_TRY, SECOND_TRY],
    answering: {
      ...ANSWERING,
      number: 3,
      lastRefused: { number: 2, values: SECOND_TRY.values },
      ...answering,
    },
  });
}

const REFERENCE = { ...SUMMARY, name: "reference", label: "Reference" };

const FOLDER = { ...SUMMARY, name: "folder", label: "Folder" };

const CODE_ROW = {
  runs: { kind: "code_step", codeStep: "file_claim" },
  producer: { kind: "code" },
  reviewer: { kind: "person" },
  gaveBack: undefined,
  acts: ["answer"],
  withheld: [{ act: "ask_again", refusal: "ASK_AGAIN_NOT_OFFERED" }],
};

const NOT_HELD_ROW = {
  state: "held_back",
  where: {
    kind: "held_back",
    reason: "code_step_not_held",
    since: "2026-09-26T09:45:00Z",
    waitsOn: "starter",
  },
  next: undefined,
  acts: [],
  withheld: [],
};

function codeStepRead(gives: readonly object[]): Reply {
  return reply({
    run: HEADER,
    rereadAfterSeconds: 5,
    declarations: DECLARATIONS,
    step: { ...OWED_ROW, ...CODE_ROW },
    wentIn: WENT_IN,
    wentInFrom: "try",
    triesMade: [
      {
        number: 1,
        beyond: false,
        producedBy: { kind: "code" },
        values: [],
        review: { asked: false },
        ended: "errored",
        wentWrong: { detail: "Timed out.", cut: false },
        cost: { callsAModel: false },
      },
    ],
    answering: { number: 2, beyond: false, gives },
  });
}

const ADDRESS = {
  name: "address",
  label: "Address",
  kind: "fields",
  mustBeGiven: true,
  fields: [
    {
      name: "street",
      label: "Street",
      kind: "text",
      longest: 40,
      mustBeGiven: true,
    },
  ],
};

const NOTES = {
  name: "notes",
  label: "Notes",
  kind: "text",
  longest: 40,
  most: 3,
  mustBeGiven: false,
};

const DUE = { name: "due", label: "Due", kind: "moment", mustBeGiven: true };

const KINDS_TRY = {
  number: 1,
  beyond: false,
  producedBy: { kind: "code" },
  values: [
    {
      field: "address",
      value: { street: "Main St" },
      now: "refused",
      decision: { outcome: "refused", why: "Which town?" },
    },
    {
      field: "notes",
      value: ["Smoke."],
      now: "refused",
      decision: { outcome: "refused", why: "Say more." },
    },
    {
      field: "priority",
      value: "high",
      now: "refused",
      decision: { outcome: "refused", why: "Not today." },
    },
    {
      field: "due",
      value: "2026-09-26T10:00:00+02:00",
      now: "refused",
      decision: { outcome: "refused", why: "Too late." },
    },
  ],
  review: { asked: true, by: { kind: "person", person: DAN } },
  ended: "refused_on_review",
  cost: { callsAModel: false },
};

function kindsRead(
  values: readonly object[] = KINDS_TRY.values,
  gives: readonly object[] = [ADDRESS, NOTES, PRIORITY, DUE],
): Reply {
  return reply({
    run: HEADER,
    rereadAfterSeconds: 5,
    declarations: DECLARATIONS,
    step: { ...OWED_ROW, ...CODE_ROW },
    wentIn: WENT_IN,
    wentInFrom: "try",
    triesMade: [{ ...KINDS_TRY, values }],
    answering: {
      number: 2,
      beyond: false,
      gives,
      lastRefused: { number: 1, values },
    },
  });
}

const EARLIER_SHAPE_TRY = {
  number: 1,
  beyond: false,
  producedBy: { kind: "code" },
  values: [
    {
      field: "summary",
      value: "A fire.",
      now: "refused",
      decision: { outcome: "refused", why: "Too short." },
    },
    {
      field: "priority",
      value: JSON.stringify("urgent"),
      now: "refused",
      decision: { outcome: "refused", why: "Not today." },
      earlierShape: true,
    },
  ],
  review: { asked: true, by: { kind: "person", person: DAN } },
  ended: "refused_on_review",
  cost: { callsAModel: false },
};

function earlierShapeRead(): Reply {
  return reply({
    run: HEADER,
    rereadAfterSeconds: 5,
    declarations: DECLARATIONS,
    step: { ...OWED_ROW, ...CODE_ROW },
    wentIn: WENT_IN,
    wentInFrom: "try",
    triesMade: [EARLIER_SHAPE_TRY],
    answering: {
      number: 2,
      beyond: false,
      gives: [SUMMARY, PRIORITY],
      lastRefused: { number: 1, values: EARLIER_SHAPE_TRY.values },
    },
  });
}

/** A reply the server never sends. */
const UNANSWERED: Reply = new Promise(() => {});

function reply(body: object): readonly [string, number] {
  return [JSON.stringify(body), 200];
}

function refusal(
  code: string,
  status: number,
  more: object = {},
): readonly [string, number] {
  return [JSON.stringify({ code, ...more }), status];
}

function setApart(words: string): string {
  return `${String.fromCodePoint(0x2068)}${words}${String.fromCodePoint(0x2069)}`;
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
      [`GET ${STEP}`]: [stepRead()],
      ...routes,
    },
  });
}

function stepOpened(routes: Readonly<Record<string, readonly Reply[]>>) {
  return openStepPage({
    groupId: GROUP,
    view: "step",
    address: `${PAGE}/steps/${SUMMARISE}`,
    permissions: ["answer_step"],
    routes,
  });
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

async function answerOpened(): Promise<HTMLElement> {
  const drawn = await messageDrawn();
  press(within(drawn).getByRole("button", { name: "Answer it here" }));
  await landing(() => yourAnswer() !== null);
  return yourAnswer()!;
}

async function detailsOpened(): Promise<void> {
  const drawn = await messageDrawn();
  press(within(drawn).getByRole("button", { name: "Details" }));
  await landing(
    () =>
      within(message()).queryByRole("link", {
        name: "Open this step's page",
      }) !== null,
  );
}

function yourAnswer(): HTMLElement | null {
  return screen.queryByRole("region", { name: "Your answer" });
}

function press(control: HTMLElement): void {
  act(() => control.click());
}

function summary(form: HTMLElement): HTMLElement {
  return within(form).getByRole("textbox", { name: "Summary" });
}

function reason(form: HTMLElement): HTMLElement {
  return within(form).getByRole("textbox", {
    name: "Why you are answering this way",
  });
}

function sendButton(form: HTMLElement): HTMLElement {
  return within(form).getByRole("button", { name: "Send this answer" });
}

function sent(served: ReturnType<typeof serving>): RequestInit[] {
  return served.mock.calls
    .filter(([, init]) => init?.method === "PUT")
    .map(([, init]) => init!);
}

function asked(served: ReturnType<typeof serving>, request: string): number {
  return requestsTo(served).filter((each) => each === request).length;
}

beforeEach(() => {
  vi.useFakeTimers({ toFake: ["setTimeout", "clearTimeout"] });
});

afterEach(() => {
  vi.useRealTimers();
});

describe("AnswerHere", () => {
  it.each([true, false])(
    "draws the server's search question with sent=%s rather than assembling it from the inputs",
    async (sent) => {
      const held = `Demo${String.fromCodePoint(0x200b)}Sample server question`;
      const { sent: requests } = stepOpened({
        [`GET ${STEP}`]: [
          stepRead(
            { runs: { kind: "search", searchWay: "web" } },
            {
              instruction: undefined,
              searchQuestion: held,
              searchQuestionSent: sent,
            },
          ),
        ],
      });
      await landing(
        () => screen.queryByRole("button", { name: "Answer it here" }) !== null,
      );
      press(screen.getByRole("button", { name: "Answer it here" }));
      await landing(() => yourAnswer() !== null);
      const form = yourAnswer()!;

      expect(
        within(form).getByText("The question it searches with"),
      ).toBeInTheDocument();
      expect(form).toHaveTextContent("DemoU+200BSample server question");
      expect(form.innerHTML).not.toContain(String.fromCodePoint(0x200b));
      expect(within(form).queryByText("The instruction")).toBeNull();
      expect(within(form).queryByText("What went in")).toBeNull();
      expect(within(form).queryByText("Not yet sent")).toBeNull();
      expect(
        form.textContent?.includes(
          "Nothing is sent yet: this is what it would take now.",
        ),
      ).toBe(!sent);
      expect(asked(requests, `GET ${STEP}`)).toBe(1);
    },
  );

  it("draws the withheld search question without leaking the stored instruction or reconstructing it from inputs", async () => {
    stepOpened({
      [`GET ${STEP}`]: [
        stepRead(
          { runs: { kind: "search", searchWay: "web" } },
          { instruction: undefined, searchQuestionWithheld: true },
        ),
      ],
    });
    await landing(
      () => screen.queryByRole("button", { name: "Answer it here" }) !== null,
    );
    press(screen.getByRole("button", { name: "Answer it here" }));
    await landing(() => yourAnswer() !== null);
    expect(yourAnswer()).toHaveTextContent("are withheld from you.");
    expect(
      within(yourAnswer()!).queryByText("The question it searches with"),
    ).toBeNull();
    expect(within(yourAnswer()!).queryByText("The instruction")).toBeNull();
  });
  describe("in a run's conversation", () => {
    it("draws Answer it here as a button beside Ask again, neither pressed, with no form and no read of the step", async () => {
      const { sent: served } = conversing();

      const drawn = await messageDrawn();

      const answer = within(drawn).getByRole("button", {
        name: "Answer it here",
      });
      expect(answer).toHaveAttribute("aria-expanded", "false");
      expect(
        within(drawn).getByRole("button", { name: "Ask again" }),
      ).toBeVisible();
      expect(yourAnswer()).toBeNull();
      expect(within(drawn).queryByRole("textbox")).toBeNull();
      expect(asked(served, `GET ${STEP}`)).toBe(0);
      expect(sent(served)).toEqual([]);
    });

    it("draws neither where the step withholds both from the reader, saying so instead", async () => {
      conversing({
        [`GET ${STEPS}`]: [
          stepsRead({
            acts: [],
            withheld: [
              { act: "answer", refusal: "ACT_NOT_PERMITTED" },
              { act: "ask_again", refusal: "ACT_NOT_PERMITTED" },
            ],
          }),
        ],
      });

      const drawn = await messageDrawn();

      expect(drawn).not.toHaveTextContent("You may not answer it here.");
      expect(
        within(accountOpened(drawn, "Waiting")).getByText(
          "You may not answer it here.",
        ),
      ).toBeVisible();
      expect(
        within(drawn).queryByRole("button", { name: "Answer it here" }),
      ).toBeNull();
      expect(
        within(drawn).queryByRole("button", { name: "Ask again" }),
      ).toBeNull();
    });

    it("reads the step once pressed and opens the form under Answer it here, sending nothing", async () => {
      const { sent: served } = conversing();

      const form = await answerOpened();

      expect(asked(served, `GET ${STEP}`)).toBe(1);
      expect(
        within(message()).getByRole("button", { name: "Answer it here" }),
      ).toHaveAttribute("aria-expanded", "true");
      expect(form).toBeVisible();
      expect(sent(served)).toEqual([]);
    });

    it("holds Answer it here while its read of the step is out, reading once however often it is pressed", async () => {
      const { sent: served } = conversing({ [`GET ${STEP}`]: [UNANSWERED] });
      const answer = within(await messageDrawn()).getByRole("button", {
        name: "Answer it here",
      });
      expect(answer).not.toHaveAttribute("aria-disabled");

      press(answer);
      await act(() => vi.advanceTimersByTimeAsync(0));
      press(answer);
      await act(() => vi.advanceTimersByTimeAsync(0));

      expect(answer).toHaveAttribute("aria-disabled", "true");
      expect(asked(served, `GET ${STEP}`)).toBe(1);
      expect(yourAnswer()).toBeNull();
    });

    it("forgets a refused read of the step once a later press opens the form from the step read beside it", async () => {
      const { sent: served } = conversing({
        [`GET ${STEP}`]: [refusal("INTERNAL", 500), stepRead(), UNANSWERED],
      });
      const drawn = await messageDrawn();
      press(within(drawn).getByRole("button", { name: "Answer it here" }));
      await landing(() => screen.queryByRole("alert") !== null);
      await detailsOpened();

      press(within(message()).getByRole("button", { name: "Answer it here" }));

      expect(yourAnswer()).not.toBeNull();
      expect(screen.queryByRole("alert")).toBeNull();
      expect(asked(served, `GET ${STEP}`)).toBe(2);
    });

    it("opens with what it asks, as whoever made the question wrote it", async () => {
      conversing();

      const form = await answerOpened();

      expect(sendButton(form)).toHaveClass("MuiButton-contained");
      expect(form).toHaveTextContent("What it asks");
      expect(within(form).getByText(/^Summarise the ticket/).textContent).toBe(
        INSTRUCTION,
      );
    });

    it("draws what went in beside the form", async () => {
      conversing();

      await answerOpened();

      expect(
        screen.getByRole("region", { name: "What went in" }),
      ).toHaveTextContent("Printer on fire");
      for (const name of ["What went in", "Your answer"]) {
        expect(
          screen.getByRole("heading", { level: 3, name }),
        ).toBeInTheDocument();
        expect(screen.queryByRole("heading", { level: 2, name })).toBeNull();
      }
    });

    it("draws what went in as the question's drawing lays it out, where it says how it is drawn", async () => {
      conversing({
        [`GET ${STEP}`]: [
          stepRead({}, {}, { drawing: "# The ticket\n\n> {{text}}" }),
        ],
      });

      await answerOpened();

      const wentIn = screen.getByRole("region", { name: "What went in" });
      const sectionLevel = Number(
        within(wentIn)
          .getByRole("heading", { name: "What went in" })
          .tagName.slice(1),
      );
      expect(
        within(wentIn).getByRole("heading", { name: "The ticket" }).tagName,
      ).toBe(`H${sectionLevel + 1}`);
      expect(within(wentIn).queryByRole("heading", { level: 1 })).toBeNull();
      expect(wentIn.querySelector("blockquote")).toHaveTextContent(
        /^Printer on fire$/,
      );
      expect(wentIn.querySelector("dl")).toBeNull();
    });

    it("starts every value from the try refused before it, saying so, and why from nothing", async () => {
      conversing();

      const form = await answerOpened();

      expect(form).toHaveTextContent(
        "It starts from what try 1 gave, and every value is given again.",
      );
      expect(summary(form)).toHaveValue("A fire.");
      expect(within(form).getByRole("radio", { name: /^high/ })).toBeChecked();
      expect(
        within(form).getByRole("radio", { name: /^low/ }),
      ).not.toBeChecked();
      expect(reason(form)).toHaveValue("");
    });

    it("gives the keyboard to the first value's control once it opens", async () => {
      conversing();

      const form = await answerOpened();

      expect(document.activeElement).toBe(summary(form));
    });

    it("says the words a value was refused with beside it, and nothing beside a value assured, aloud or otherwise", async () => {
      conversing();

      const form = await answerOpened();
      const words = within(form).getByText("Too short.", { selector: "bdi" });

      expect(words).toBeVisible();
      expect(words.parentElement).toHaveTextContent(/^Refused: Too short\.$/);
      expect(summary(form)).toHaveAccessibleDescription(
        expect.stringContaining("Refused: Too short."),
      );
      expect(within(form).queryByText("Assured.")).toBeNull();
      expect(screen.queryByRole("alert")).toBeNull();
    });

    it("says a value refused for its length is refused for its length, whatever its review said of it", async () => {
      const values = [
        {
          field: "summary",
          value: "A fire.",
          now: "refused_for_length",
          decision: { outcome: "assured" },
        },
        FIRST_TRY.values[1],
      ];
      conversing({
        [`GET ${STEPS}`]: [
          stepsRead({
            gaveBack: [
              { field: "summary", value: "A fire.", now: "refused_for_length" },
              OWED_ROW.gaveBack[1],
            ],
          }),
          UNANSWERED,
        ],
        [`GET ${STEP}`]: [
          reply({
            run: HEADER,
            declarations: DECLARATIONS,
            step: OWED_ROW,
            wentIn: WENT_IN,
            wentInFrom: "try",
            triesMade: [{ ...FIRST_TRY, values, ended: "refused_for_length" }],
            answering: { ...ANSWERING, lastRefused: { number: 1, values } },
          }),
        ],
      });

      const form = await answerOpened();

      expect(summary(form)).toHaveAccessibleDescription(
        expect.stringContaining("Refused for its length."),
      );
      expect(within(form).queryByText("Assured.")).toBeNull();
    });

    it.each([
      [
        "fields",
        (form: HTMLElement) =>
          within(form).getByRole("group", { name: "Address" }),
        "Which town?",
      ],
      [
        "many",
        (form: HTMLElement) =>
          within(form).getByRole("group", { name: "Notes" }),
        "Say more.",
      ],
      [
        "choice",
        (form: HTMLElement) =>
          within(form).getByRole("radiogroup", { name: "Priority" }),
        "Not today.",
      ],
      [
        "moment",
        (form: HTMLElement) => within(form).getByLabelText("Date"),
        "Too late.",
      ],
    ] as const)(
      "says beside a %s control the words its value was refused with, as its description, and there alone",
      async (_kind, control, why) => {
        conversing({
          [`GET ${STEPS}`]: [stepsRead(CODE_ROW)],
          [`GET ${STEP}`]: [kindsRead()],
        });
        await landing(
          () =>
            screen.queryByRole("list", { name: "The conversation" }) !== null,
        );

        press(screen.getByRole("button", { name: "Answer it here" }));
        await landing(() => yourAnswer() !== null);

        const form = yourAnswer()!;
        expect(control(form)).toHaveAccessibleDescription(
          expect.stringContaining(`Refused: ${why}`),
        );
        expect(
          within(form).getAllByText(why, { selector: "bdi" }),
        ).toHaveLength(1);
      },
    );

    it("draws a character in the words a value was refused with that shows nothing as its marker beside it, never the character", async () => {
      const values = [
        FIRST_TRY.values[0],
        {
          ...FIRST_TRY.values[1],
          now: "refused",
          decision: { outcome: "refused", why: `Not${ZERO_WIDTH_SPACE}today.` },
        },
      ];
      conversing({
        [`GET ${STEP}`]: [
          stepRead(
            {},
            { lastRefused: { number: 1, values } },
            { triesMade: [{ ...FIRST_TRY, values, review: MODEL_REVIEW }] },
          ),
        ],
      });

      const form = await answerOpened();
      const marker = within(form).getByRole("img", {
        name: "A character that shows nothing is here: U+200B.",
      });

      expect(marker.closest("bdi")).toHaveTextContent(/^NotU\+200Btoday\.$/);
      expect(
        within(form).getByRole("radiogroup", { name: "Priority" }),
      ).toHaveAccessibleDescription(
        expect.stringMatching(/Refused: Not ?U\+200B ?today\./),
      );
      expect(form.innerHTML).not.toContain(ZERO_WIDTH_SPACE);
    });

    it("draws a character that shows nothing in the words a model's review refused an earlier try's value with as its marker in the message, never the character", async () => {
      const values = [
        {
          ...FIRST_TRY.values[0],
          decision: { outcome: "refused", why: `Too${ZERO_WIDTH_SPACE}short.` },
        },
        FIRST_TRY.values[1],
      ];
      conversing({
        [`GET ${STEPS}`]: [stepsRead(THIRD_OWED), UNANSWERED],
        [`GET ${STEP}`]: [
          stepRead(
            THIRD_OWED,
            {},
            {
              triesMade: [
                { ...FIRST_TRY, values, review: MODEL_REVIEW },
                SECOND_TRY,
              ],
            },
          ),
          UNANSWERED,
        ],
      });
      const drawn = await messageDrawn();

      press(within(drawn).getByRole("button", { name: "Earlier try" }));
      await landing(
        () =>
          within(message()).queryByRole("img", {
            name: "A character that shows nothing is here: U+200B.",
          }) !== null,
      );

      const marker = within(message()).getByRole("img", {
        name: "A character that shows nothing is here: U+200B.",
      });
      expect(marker.closest("bdi")?.textContent).toBe("TooU+200Bshort.");
      expect(marker.closest("bdi")?.parentElement?.textContent).toBe(
        "Refused: TooU+200Bshort.",
      );
      expect(message().innerHTML).not.toContain(ZERO_WIDTH_SPACE);
    });

    it("starts a value, an assured one too, less every character drawing it would mark, saying under it how many it left out, and sends only what is shown", async () => {
      const values = [
        {
          field: "summary",
          value: `A${ZERO_WIDTH_SPACE.repeat(2)} fire.${RIGHT_TO_LEFT_OVERRIDE}`,
          now: "stands",
          decision: { outcome: "assured" },
        },
        {
          field: "priority",
          value: "high",
          now: "refused",
          decision: { outcome: "refused", why: "Not today." },
        },
      ];
      const { sent: served } = conversing({
        [`GET ${STEP}`]: [
          stepRead(
            {},
            { lastRefused: { number: 1, values } },
            { triesMade: [{ ...FIRST_TRY, producedBy: SMALL, values }] },
          ),
        ],
        [`PUT ${ANSWER}`]: [ANSWERED],
      });

      const form = await answerOpened();

      expect(summary(form)).toHaveValue("A fire.");
      expect(summary(form)).toHaveAccessibleDescription(
        expect.stringContaining(
          "What is filled in here leaves out 3 characters of the earlier answer that showed nothing or would have turned the order text is shown in.",
        ),
      );
      expect(
        within(form).getByRole("radiogroup", { name: "Priority" }),
      ).not.toHaveAccessibleDescription(expect.stringContaining("leaves out"));

      fireEvent.change(reason(form), { target: { value: "As it reads." } });
      press(sendButton(form));
      await landing(() => yourAnswer() === null);

      expect(sent(served).map((init) => init.body)).toEqual([
        JSON.stringify({
          values: { summary: "A fire.", priority: "high" },
          why: "As it reads.",
        }),
      ]);
    });

    it("starts a value less every control, a joiner after one, a separator and a lone carriage return, counting them under it, and sends only what is shown", async () => {
      const values = [
        {
          field: "summary",
          value: `A${String.fromCodePoint(0x1, 0x1, 0x200d)} fire.${String.fromCodePoint(0x2028, 0x1b)}\r`,
          now: "refused",
          decision: { outcome: "refused", why: "Too short." },
        },
        {
          field: "priority",
          value: "high",
          now: "stands",
          decision: { outcome: "assured" },
        },
      ];
      const { sent: served } = conversing({
        [`GET ${STEP}`]: [
          stepRead(
            {},
            { lastRefused: { number: 1, values } },
            { triesMade: [{ ...FIRST_TRY, producedBy: SMALL, values }] },
          ),
        ],
        [`PUT ${ANSWER}`]: [ANSWERED],
      });

      const form = await answerOpened();

      expect(summary(form)).toHaveValue("A fire.");
      expect(summary(form)).toHaveAccessibleDescription(
        expect.stringContaining(
          "What is filled in here leaves out 6 characters of the earlier answer",
        ),
      );
      expect(
        within(form).getByRole("radiogroup", { name: "Priority" }),
      ).not.toHaveAccessibleDescription(expect.stringContaining("leaves out"));

      fireEvent.change(reason(form), { target: { value: "As it reads." } });
      press(sendButton(form));
      await landing(() => yourAnswer() === null);

      expect(sent(served).map((init) => init.body)).toEqual([
        JSON.stringify({
          values: { summary: "A fire.", priority: "high" },
          why: "As it reads.",
        }),
      ]);
    });

    it.each([
      [
        "a line ending in a carriage return and a line feed",
        "A\r\nfire.",
        "A\nfire.",
        null,
      ],
      ["a lone carriage return", "A\rfire.", "Afire.", 1],
      ["a carriage return before one ending a line", "A\r\r\nb", "A\nb", 1],
    ] as const)(
      "starts a value holding %s with each line ending in a line feed alone, counting only what drawing would mark, and sends it as shown",
      async (_held, value, shown, leftOut) => {
        const values = [
          {
            field: "summary",
            value,
            now: "refused",
            decision: { outcome: "refused", why: "Too short." },
          },
          {
            field: "priority",
            value: "high",
            now: "stands",
            decision: { outcome: "assured" },
          },
        ];
        const { sent: served } = conversing({
          [`GET ${STEP}`]: [
            stepRead(
              {},
              { lastRefused: { number: 1, values } },
              { triesMade: [{ ...FIRST_TRY, producedBy: SMALL, values }] },
            ),
          ],
          [`PUT ${ANSWER}`]: [ANSWERED],
        });

        const form = await answerOpened();

        expect(summary(form)).toHaveValue(shown);
        if (leftOut === null) {
          expect(summary(form)).not.toHaveAccessibleDescription(
            expect.stringContaining("leaves out"),
          );
        } else {
          expect(summary(form)).toHaveAccessibleDescription(
            expect.stringContaining(
              `What is filled in here leaves out ${leftOut} character of the earlier answer`,
            ),
          );
        }
        expect(form).not.toHaveTextContent(CRLF_REFUSED);

        fireEvent.change(reason(form), { target: { value: "As it reads." } });
        press(sendButton(form));
        await landing(() => yourAnswer() === null);

        expect(sent(served).map((init) => init.body)).toEqual([
          JSON.stringify({
            values: { summary: shown, priority: "high" },
            why: "As it reads.",
          }),
        ]);
      },
    );

    it("starts a field within a value and an item of a many with each line ending in a line feed alone, counting nothing, and sends them as shown", async () => {
      const values = [
        { ...KINDS_TRY.values[0], value: { street: "Main\r\nSt" } },
        { ...KINDS_TRY.values[1], value: ["Smoke\r\nrising."] },
        ...KINDS_TRY.values.slice(2),
      ];
      const { sent: served } = conversing({
        [`GET ${STEPS}`]: [stepsRead(CODE_ROW)],
        [`GET ${STEP}`]: [kindsRead(values)],
        [`PUT ${ANSWER}`]: [ANSWERED],
      });
      await landing(
        () => screen.queryByRole("list", { name: "The conversation" }) !== null,
      );

      press(screen.getByRole("button", { name: "Answer it here" }));
      await landing(() => yourAnswer() !== null);

      const form = yourAnswer()!;
      expect(within(form).getByRole("textbox", { name: "Street" })).toHaveValue(
        "Main\nSt",
      );
      expect(
        within(within(form).getByRole("group", { name: "Notes" })).getByRole(
          "textbox",
        ),
      ).toHaveValue("Smoke\nrising.");
      for (const name of ["Address", "Notes"]) {
        expect(
          within(form).getByRole("group", { name }),
        ).not.toHaveAccessibleDescription(
          expect.stringContaining("leaves out"),
        );
      }
      expect(form).not.toHaveTextContent(CRLF_REFUSED);

      fireEvent.change(reason(form), { target: { value: "As it reads." } });
      press(sendButton(form));
      await landing(() => yourAnswer() === null);

      expect(
        sent(served).map((init) => JSON.parse(init.body as string)),
      ).toEqual([
        {
          values: {
            address: { street: "Main\nSt" },
            notes: ["Smoke\nrising."],
            priority: "high",
            due: "2026-09-26T10:00:00+02:00",
          },
          why: "As it reads.",
        },
      ]);
    });

    it("leaves out what drawing would mark from within a value holding others, saying one character under it, and keeps a joiner doing its work", async () => {
      const heartOnFire = String.fromCodePoint(0x2764, 0xfe0f, 0x200d, 0x1f525);
      const values = [
        {
          ...KINDS_TRY.values[0],
          value: { street: `Main${ZERO_WIDTH_SPACE}St` },
        },
        { ...KINDS_TRY.values[1], value: [`Smoke ${heartOnFire}`] },
        ...KINDS_TRY.values.slice(2),
      ];
      conversing({
        [`GET ${STEPS}`]: [stepsRead(CODE_ROW)],
        [`GET ${STEP}`]: [kindsRead(values)],
      });
      await landing(
        () => screen.queryByRole("list", { name: "The conversation" }) !== null,
      );

      press(screen.getByRole("button", { name: "Answer it here" }));
      await landing(() => yourAnswer() !== null);

      const form = yourAnswer()!;
      expect(within(form).getByRole("textbox", { name: "Street" })).toHaveValue(
        "MainSt",
      );
      expect(
        within(form).getByRole("group", { name: "Address" }),
      ).toHaveAccessibleDescription(
        expect.stringContaining(
          "What is filled in here leaves out 1 character of the earlier answer",
        ),
      );
      expect(
        within(form).getByDisplayValue(`Smoke ${heartOnFire}`),
      ).toBeVisible();
      expect(
        within(form).getByRole("group", { name: "Notes" }),
      ).not.toHaveAccessibleDescription(expect.stringContaining("leaves out"));
    });

    it("starts a hidden nested text empty and sends no hidden marker with the visible sibling", async () => {
      const hiddenAddress = {
        ...ADDRESS,
        fields: [
          ...ADDRESS.fields,
          {
            name: "privateNote",
            label: "Private note",
            kind: "text",
            longest: 40,
            mustBeGiven: false,
          },
        ],
      };
      const values = [
        {
          ...KINDS_TRY.values[0],
          value: { street: "Main St", privateNote: { withheld: true } },
        },
        ...KINDS_TRY.values.slice(1),
      ];
      const { sent: served } = conversing({
        [`GET ${STEPS}`]: [stepsRead(CODE_ROW)],
        [`GET ${STEP}`]: [
          kindsRead(values, [hiddenAddress, NOTES, PRIORITY, DUE]),
        ],
        [`PUT ${ANSWER}`]: [ANSWERED],
      });
      await landing(
        () => screen.queryByRole("list", { name: "The conversation" }) !== null,
      );
      press(screen.getByRole("button", { name: "Answer it here" }));
      await landing(() => yourAnswer() !== null);

      const form = yourAnswer()!;
      expect(within(form).getByRole("textbox", { name: "Street" })).toHaveValue(
        "Main St",
      );
      expect(
        within(form).getByRole("textbox", { name: "Private note" }),
      ).toHaveValue("");
      fireEvent.change(reason(form), {
        target: { value: "Demo Sample review." },
      });
      press(sendButton(form));
      await landing(() => yourAnswer() === null);

      const posted = sent(served).map((init) =>
        JSON.parse(init.body as string),
      );
      expect(posted).toEqual([
        {
          values: {
            address: { street: "Main St", privateNote: null },
            notes: ["Smoke."],
            priority: "high",
            due: "2026-09-26T10:00:00+02:00",
          },
          why: "Demo Sample review.",
        },
      ]);
      expect(JSON.stringify(posted)).not.toContain("withheld");
    });

    it("starts a wholly withheld fields value empty and submits only what the person newly typed", async () => {
      const values = [
        { ...KINDS_TRY.values[0], value: { withheld: true } },
        ...KINDS_TRY.values.slice(1),
      ];
      const { sent: served } = conversing({
        [`GET ${STEPS}`]: [stepsRead(CODE_ROW)],
        [`GET ${STEP}`]: [kindsRead(values)],
        [`PUT ${ANSWER}`]: [ANSWERED],
      });
      await landing(
        () => screen.queryByRole("list", { name: "The conversation" }) !== null,
      );
      press(screen.getByRole("button", { name: "Answer it here" }));
      await landing(() => yourAnswer() !== null);

      const form = yourAnswer()!;
      expect(within(form).getByRole("textbox", { name: "Street" })).toHaveValue(
        "",
      );
      fireEvent.change(within(form).getByRole("textbox", { name: "Street" }), {
        target: { value: "Demo Sample street" },
      });
      fireEvent.change(reason(form), {
        target: { value: "Demo Sample review." },
      });
      press(sendButton(form));
      await landing(() => yourAnswer() === null);

      const posted = sent(served).map((init) =>
        JSON.parse(init.body as string),
      );
      expect(posted).toEqual([
        {
          values: {
            address: { street: "Demo Sample street" },
            notes: ["Smoke."],
            priority: "high",
            due: "2026-09-26T10:00:00+02:00",
          },
          why: "Demo Sample review.",
        },
      ]);
      expect(JSON.stringify(posted)).not.toContain("withheld");
    });

    it("prefills and submits a real fields child named withheld as ordinary text", async () => {
      const addressWithNamedChild = {
        ...ADDRESS,
        fields: [
          ...ADDRESS.fields,
          {
            name: "withheld",
            label: "Withheld note",
            kind: "text",
            longest: 40,
            mustBeGiven: false,
          },
        ],
      };
      const values = [
        {
          ...KINDS_TRY.values[0],
          value: { street: "Main St", withheld: "Demo Sample" },
        },
        ...KINDS_TRY.values.slice(1),
      ];
      const { sent: served } = conversing({
        [`GET ${STEPS}`]: [stepsRead(CODE_ROW)],
        [`GET ${STEP}`]: [
          kindsRead(values, [addressWithNamedChild, NOTES, PRIORITY, DUE]),
        ],
        [`PUT ${ANSWER}`]: [ANSWERED],
      });
      await landing(
        () => screen.queryByRole("list", { name: "The conversation" }) !== null,
      );
      press(screen.getByRole("button", { name: "Answer it here" }));
      await landing(() => yourAnswer() !== null);

      const form = yourAnswer()!;
      expect(
        within(form).getByRole("textbox", { name: "Withheld note" }),
      ).toHaveValue("Demo Sample");
      fireEvent.change(reason(form), {
        target: { value: "Demo Sample review." },
      });
      press(sendButton(form));
      await landing(() => yourAnswer() === null);

      expect(
        sent(served).map((init) => JSON.parse(init.body as string)),
      ).toEqual([
        {
          values: {
            address: { street: "Main St", withheld: "Demo Sample" },
            notes: ["Smoke."],
            priority: "high",
            due: "2026-09-26T10:00:00+02:00",
          },
          why: "Demo Sample review.",
        },
      ]);
    });

    it("keeps a refused moment's four digits, sends six edited digits exactly, and does not send seven", async () => {
      const values = KINDS_TRY.values.map((value) =>
        value.field === "due"
          ? { ...value, value: "2026-09-26T10:00:00.1234+02:00" }
          : value,
      );
      const { sent: served } = conversing({
        [`GET ${STEPS}`]: [stepsRead(CODE_ROW)],
        [`GET ${STEP}`]: [kindsRead(values)],
        [`PUT ${ANSWER}`]: [ANSWERED],
      });
      await landing(
        () => screen.queryByRole("list", { name: "The conversation" }) !== null,
      );
      press(screen.getByRole("button", { name: "Answer it here" }));
      await landing(() => yourAnswer() !== null);

      const form = yourAnswer()!;
      const time = within(form).getByRole("textbox", { name: "Time" });
      expect(time).toHaveValue("10:00:00.1234");
      expect(
        within(form).getByText(
          "Give the time to the second, with up to six digits after the point.",
        ),
      ).toBeVisible();
      fireEvent.change(time, { target: { value: "10:00:00.1234567" } });
      fireEvent.change(reason(form), {
        target: { value: "Demo Sample review." },
      });
      press(sendButton(form));
      expect(sent(served)).toEqual([]);
      expect(time).toHaveValue("10:00:00.1234567");

      fireEvent.change(time, { target: { value: "10:00:00.123456" } });
      press(sendButton(form));
      await landing(() => yourAnswer() === null);

      expect(
        sent(served).map((init) => JSON.parse(init.body as string)),
      ).toEqual([
        {
          values: {
            address: { street: "Main St" },
            notes: ["Smoke."],
            priority: "high",
            due: "2026-09-26T10:00:00.123456+02:00",
          },
          why: "Demo Sample review.",
        },
      ]);
    });

    it("sends four refused fractional digits exactly when the moment is kept", async () => {
      const values = KINDS_TRY.values.map((value) =>
        value.field === "due"
          ? { ...value, value: "2026-09-26T10:00:00.1234+02:00" }
          : value,
      );
      const { sent: served } = conversing({
        [`GET ${STEPS}`]: [stepsRead(CODE_ROW)],
        [`GET ${STEP}`]: [kindsRead(values)],
        [`PUT ${ANSWER}`]: [ANSWERED],
      });
      await landing(
        () => screen.queryByRole("list", { name: "The conversation" }) !== null,
      );
      press(screen.getByRole("button", { name: "Answer it here" }));
      await landing(() => yourAnswer() !== null);

      const form = yourAnswer()!;
      expect(within(form).getByRole("textbox", { name: "Time" })).toHaveValue(
        "10:00:00.1234",
      );
      fireEvent.change(reason(form), {
        target: { value: "Demo Sample review." },
      });
      press(sendButton(form));
      await landing(() => yourAnswer() === null);

      expect(
        sent(served).map((init) => JSON.parse(init.body as string)),
      ).toEqual([
        {
          values: {
            address: { street: "Main St" },
            notes: ["Smoke."],
            priority: "high",
            due: "2026-09-26T10:00:00.1234+02:00",
          },
          why: "Demo Sample review.",
        },
      ]);
    });

    it("rejects an astral text answer pasted beyond its character limit and sends a selected replacement", async () => {
      const astral = String.fromCodePoint(0x1f642);
      const address = {
        ...ADDRESS,
        fields: [{ ...ADDRESS.fields[0], longest: 2 }],
      };
      const values = [
        { ...KINDS_TRY.values[0], value: { street: `${astral}A` } },
        ...KINDS_TRY.values.slice(1),
      ];
      const { sent: served } = conversing({
        [`GET ${STEPS}`]: [stepsRead(CODE_ROW)],
        [`GET ${STEP}`]: [kindsRead(values, [address, NOTES, PRIORITY, DUE])],
        [`PUT ${ANSWER}`]: [ANSWERED],
      });
      await landing(
        () => screen.queryByRole("list", { name: "The conversation" }) !== null,
      );
      press(screen.getByRole("button", { name: "Answer it here" }));
      await landing(() => yourAnswer() !== null);
      vi.useRealTimers();

      const form = yourAnswer()!;
      const street = within(form).getByRole("textbox", {
        name: "Street",
      }) as HTMLInputElement;
      const user = userEvent.setup();
      expect(street).toHaveValue(`${astral}A`);
      fireEvent.change(street, { target: { value: `${astral}AB` } });
      expect(street).toHaveValue(`${astral}A`);
      expect(sent(served)).toEqual([]);
      await user.click(street);
      await user.paste("B");
      expect(street).toHaveValue(`${astral}A`);
      expect(street).toHaveAccessibleDescription(
        /This is longer than this field takes/,
      );
      expect(street).toHaveFocus();
      expect(sent(served)).toEqual([]);

      street.setSelectionRange(astral.length, astral.length + 1);
      await user.paste("C");
      expect(street).toHaveValue(`${astral}C`);
      fireEvent.change(reason(form), {
        target: { value: "Demo Sample review." },
      });
      press(sendButton(form));
      await waitFor(() => expect(yourAnswer()).toBeNull());

      expect(
        sent(served).map((init) => JSON.parse(init.body as string)),
      ).toEqual([
        {
          values: {
            address: { street: `${astral}C` },
            notes: ["Smoke."],
            priority: "high",
            due: "2026-09-26T10:00:00+02:00",
          },
          why: "Demo Sample review.",
        },
      ]);
    });

    it("sends every value, assured ones too, and why, to the try it fills, then shows the step as answered, reads the run again and gives the keyboard to its message", async () => {
      const { sent: served } = conversing({
        [`PUT ${ANSWER}`]: [ANSWERED],
      });
      const form = await answerOpened();
      fireEvent.change(summary(form), { target: { value: "A printer fire." } });
      press(within(form).getByRole("radio", { name: /^low/ }));
      fireEvent.change(reason(form), {
        target: { value: "Longer, and less urgent." },
      });

      press(sendButton(form));
      await landing(() => yourAnswer() === null);

      expect(sent(served).map((init) => init.body)).toEqual([
        JSON.stringify({
          values: { summary: "A printer fire.", priority: "low" },
          why: "Longer, and less urgent.",
        }),
      ]);
      expect(requestsTo(served)).toContain(`PUT ${ANSWER}`);
      expect(within(message()).getByText("It waits on review.")).toBeVisible();
      expect(
        within(message()).queryByRole("button", { name: "Answer it here" }),
      ).toBeNull();
      expect(asked(served, `GET ${RUN}`)).toBe(2);
      expect(document.activeElement).toBe(message());
      expect(screen.queryByRole("alert")).toBeNull();
    });

    it("keeps a refused file after an invalid replacement and sends its metadata nowhere in the answer document", async () => {
      const kept = {
        id: "0000000a-0000-4000-8000-000000000d01",
        name: "Demo Sample.txt",
        type: "text/plain",
        size: 6,
      };
      const fileField = {
        name: "attachment",
        label: "Attachment",
        kind: "file",
        types: ["text/plain"],
        maximumBytes: 20,
        mustBeGiven: true,
      };
      const values = [
        {
          field: "attachment",
          value: kept,
          now: "refused",
          decision: { outcome: "refused", why: "Read it again." },
        },
      ];
      const { sent: served } = conversing({
        [`GET ${STEPS}`]: [stepsRead(), UNANSWERED],
        [`GET ${STEP}`]: [
          reply({
            run: HEADER,
            declarations: DECLARATIONS,
            step: OWED_ROW,
            wentIn: WENT_IN,
            wentInFrom: "try",
            triesMade: [{ ...KINDS_TRY, values }],
            answering: {
              number: 2,
              beyond: false,
              gives: [fileField],
              lastRefused: { number: 1, values },
            },
          }),
        ],
        [`PUT ${ANSWER}`]: [ANSWERED],
      });
      const form = await answerOpened();
      expect(within(form).getByText(/Demo Sample\.txt/)).toBeVisible();
      const picker = form.querySelector('input[type="file"]')!;
      fireEvent.change(picker, {
        target: {
          files: [
            new File(["wrong"], "Demo Sample.png", { type: "image/png" }),
          ],
        },
      });
      expect(
        within(form).getByText(
          (_, element) =>
            element?.firstElementChild?.tagName === "BDI" &&
            /Demo Sample\.png.*not a type this field takes/.test(
              element.textContent ?? "",
            ),
        ),
      ).toBeVisible();
      expect(
        within(form).getByText(
          (_, element) =>
            element?.firstElementChild?.tagName === "BDI" &&
            /Demo Sample\.txt.*previously given/.test(
              element.textContent ?? "",
            ),
        ),
      ).toBeVisible();
      expect(picker).toHaveAttribute("aria-invalid", "true");
      fireEvent.change(reason(form), {
        target: { value: "Kept after review." },
      });

      press(sendButton(form));
      await landing(() => sent(served).length === 1);

      const body = sent(served)[0]?.body;
      expect(body).toBeInstanceOf(FormData);
      expect([...(body as FormData).entries()]).toEqual([
        [
          "document",
          JSON.stringify({
            values: { attachment: { keep: 0 } },
            why: "Kept after review.",
          }),
        ],
      ]);
      expect(JSON.stringify([...(body as FormData).entries()])).not.toContain(
        kept.id,
      );
    });

    it("sends nothing while a value is missing or no reason is given, taking the keyboard to the first of them in turn", async () => {
      const { sent: served } = conversing({ [`PUT ${ANSWER}`]: [ANSWERED] });
      const form = await answerOpened();
      const send = sendButton(form);
      fireEvent.change(summary(form), { target: { value: "" } });
      act(() => send.focus());

      press(send);
      await act(() => vi.advanceTimersByTimeAsync(0));

      expect(document.activeElement).toBe(summary(form));
      expect(summary(form)).toHaveAccessibleDescription(/This must be given\./);
      expect(form).toHaveTextContent(
        "A field that must be given is still empty.",
      );
      expect(reason(form)).toHaveAccessibleDescription(
        "A refusal, and an answer, say why.",
      );

      fireEvent.change(summary(form), { target: { value: "A printer fire." } });
      act(() => send.focus());
      press(send);
      await act(() => vi.advanceTimersByTimeAsync(0));

      expect(document.activeElement).toBe(reason(form));
      expect(summary(form)).not.toHaveAccessibleDescription(
        /This must be given\./,
      );
      expect(sent(served)).toEqual([]);
    });

    it("takes the keyboard to why once what it lacks is said there, at the first Send with every value fitting", async () => {
      const { sent: served } = conversing({ [`PUT ${ANSWER}`]: [ANSWERED] });
      const form = await answerOpened();
      const send = sendButton(form);
      act(() => send.focus());

      press(send);
      await act(() => vi.advanceTimersByTimeAsync(0));

      expect(document.activeElement).toBe(reason(form));
      expect(reason(form)).toHaveAccessibleDescription(
        "A refusal, and an answer, say why.",
      );
      expect(summary(form)).not.toHaveAccessibleDescription(
        /This must be given\./,
      );
      expect(sent(served)).toEqual([]);
    });

    it("starts empty a value the try refused before gave that is withheld from the reader, saying so at its control", async () => {
      const withheld = [
        {
          field: "summary",
          withheld: true,
          now: "refused",
          decision: { outcome: "refused", why: "Too short." },
        },
        FIRST_TRY.values[1],
      ];
      const gaveBack = [
        { field: "summary", withheld: true, now: "refused" },
        OWED_ROW.gaveBack[1],
      ];
      conversing({
        [`GET ${STEPS}`]: [stepsRead({ gaveBack }), UNANSWERED],
        [`GET ${STEP}`]: [
          reply({
            run: HEADER,
            declarations: DECLARATIONS,
            step: { ...OWED_ROW, gaveBack },
            wentIn: WENT_IN,
            wentInFrom: "try",
            triesMade: [{ ...FIRST_TRY, values: withheld }],
            answering: {
              ...ANSWERING,
              lastRefused: { number: 1, values: withheld },
            },
          }),
        ],
      });

      const form = await answerOpened();

      expect(summary(form)).toHaveValue("");
      expect(summary(form)).toHaveAccessibleDescription(
        expect.stringContaining("What try 1 gave here is withheld from you."),
      );
      expect(summary(form)).toHaveAccessibleDescription(
        expect.stringContaining("Refused: Too short."),
      );
      expect(within(form).getByRole("radio", { name: /^high/ })).toBeChecked();
      expect(within(form).getAllByText(/withheld from you/)).toHaveLength(1);
    });

    it("starts empty a value the try refused before gave in a shape the step had before, saying so at its control, and starts the rest from what it gave", async () => {
      conversing({
        [`GET ${STEPS}`]: [stepsRead(CODE_ROW)],
        [`GET ${STEP}`]: [earlierShapeRead()],
      });
      await landing(
        () => screen.queryByRole("list", { name: "The conversation" }) !== null,
      );

      press(screen.getByRole("button", { name: "Answer it here" }));
      await landing(() => yourAnswer() !== null);

      const form = yourAnswer()!;
      const priority = within(form).getByRole("radiogroup", {
        name: "Priority",
      });
      expect(
        within(priority)
          .getAllByRole("radio")
          .filter((each) => (each as HTMLInputElement).checked),
      ).toEqual([]);
      expect(priority).toHaveAccessibleDescription(
        expect.stringContaining(
          "What try 1 gave here was in the shape this step had before, so it starts empty.",
        ),
      );
      expect(priority).toHaveAccessibleDescription(
        expect.stringContaining("Refused: Not today."),
      );
      expect(summary(form)).toHaveValue("A fire.");
      expect(summary(form)).not.toHaveAccessibleDescription(
        expect.stringContaining("shape this step had before"),
      );
      expect(within(form).queryByText(/withheld from you/)).toBeNull();
    });

    it("marks each value the server finds does not fit where it stands, says how many more it found, and forgets both once the value changes", async () => {
      const { sent: served } = conversing({
        [`PUT ${ANSWER}`]: [
          refusal("VALUE_DOES_NOT_FIT", 400, {
            problems: [{ path: ["summary"], reason: "too_long" }],
            problemsFound: 2,
          }),
        ],
      });
      const form = await answerOpened();
      const send = sendButton(form);
      fireEvent.change(reason(form), { target: { value: "Read it twice." } });
      act(() => send.focus());

      press(send);
      await landing(() => screen.queryByRole("alert") !== null);

      expect(sent(served)).toHaveLength(1);
      expect(document.activeElement).toBe(summary(form));
      expect(summary(form)).toHaveAccessibleDescription(
        /This is longer than this field takes\./,
      );
      expect(form).toHaveTextContent(
        "1 more place does not fit and is not marked.",
      );
      expect(screen.getByRole("alert")).toHaveTextContent(
        "Each value is written as its field takes it.",
      );

      fireEvent.change(summary(form), { target: { value: "A fire, again." } });

      expect(summary(form)).not.toHaveAccessibleDescription(
        /This is longer than this field takes\./,
      );
      expect(form).not.toHaveTextContent("more place does not fit");
      expect(screen.queryByRole("alert")).toBeNull();
    });

    it("marks at the reason a reason the server refuses, giving it the keyboard and saying nothing else, until it is changed", async () => {
      conversing({
        [`PUT ${ANSWER}`]: [refusal("REASON_UNUSABLE", 400)],
      });
      const form = await answerOpened();
      fireEvent.change(reason(form), { target: { value: "Read it twice." } });

      press(sendButton(form));
      await landing(() => document.activeElement === reason(form));

      expect(reason(form)).toHaveAccessibleDescription(
        "A reason is prose of one to 2048 characters, with something in it that shows.",
      );
      expect(screen.queryByRole("alert")).toBeNull();
      expect(summary(form)).toHaveValue("A fire.");

      fireEvent.change(reason(form), { target: { value: "Read it again." } });

      expect(reason(form)).not.toHaveAccessibleDescription(/A reason is prose/);
    });

    it("says nothing aloud, and the refusal nowhere, once a reason the server refused is changed", async () => {
      conversing({
        [`PUT ${ANSWER}`]: [refusal("REASON_UNUSABLE", 400)],
      });
      const form = await answerOpened();
      fireEvent.change(reason(form), { target: { value: "Read it twice." } });
      press(sendButton(form));
      await landing(() => document.activeElement === reason(form));

      fireEvent.change(reason(form), { target: { value: "Read it again." } });

      expect(screen.queryByRole("alert")).toBeNull();
      expect(screen.queryByText(/A reason is prose/)).toBeNull();
    });

    it("says the answer is refused by the rule of who may answer a step, and reads the reader's standing again, reading nothing of the step", async () => {
      const { sent: served, readAgain } = conversing({
        [`PUT ${ANSWER}`]: [refusal("ACT_NOT_PERMITTED", 403)],
      });
      const form = await answerOpened();
      fireEvent.change(reason(form), { target: { value: "Read it twice." } });

      press(sendButton(form));
      await landing(() => screen.queryByRole("alert") !== null);

      const said = screen.getByRole("alert");
      expect(said).toHaveTextContent(ANSWER_RULE);
      expect(said).not.toHaveTextContent(
        "This is done only by a role that reaches it.",
      );
      expect(readAgain).toHaveBeenCalledTimes(1);
      expect(asked(served, `GET ${STEP}`)).toBe(1);
    });

    describe("once the answer is refused as the step having moved on", () => {
      async function refusedAsMovedOn() {
        const opened = conversing({
          [`GET ${STEP}`]: [stepRead(), thirdOwedRead()],
          [`PUT ${ANSWER}`]: [refusal("STEP_MOVED_ON", 409)],
        });
        const form = await answerOpened();
        fireEvent.change(summary(form), {
          target: { value: "A kettle fire." },
        });
        fireEvent.change(reason(form), { target: { value: "Read it twice." } });

        press(sendButton(form));
        await landing(
          () =>
            asked(opened.sent, `GET ${STEP}`) === 2 &&
            asked(opened.sent, `GET ${STEPS}`) === 2,
        );
        return opened;
      }

      it("says who answered first, and only that, the keyboard on it", async () => {
        await refusedAsMovedOn();

        const alert = screen.getByRole("alert");
        expect(alert).toHaveTextContent(
          `Answered already, by ${setApart("Eve")}.`,
        );
        expect(alert).not.toHaveTextContent("That step has moved on");
        expect(screen.getAllByRole("alert")).toHaveLength(1);
        expect(document.activeElement).toBe(alert.closest("[tabindex='-1']"));
      });

      it("reads the step again and hands that on to the run, which reads its steps again, sending nothing more", async () => {
        const { sent: served } = await refusedAsMovedOn();

        expect(asked(served, `GET ${STEP}`)).toBe(2);
        expect(asked(served, `GET ${STEPS}`)).toBe(2);
        expect(sent(served)).toHaveLength(1);
      });

      it("starts the draft and why again from the try refused since", async () => {
        await refusedAsMovedOn();

        const restarted = yourAnswer()!;
        expect(restarted).toHaveTextContent(
          "It starts from what try 2 gave, and every value is given again.",
        );
        expect(summary(restarted)).toHaveValue("A printer fire.");
        expect(
          within(restarted).getByRole("radio", { name: /^low/ }),
        ).toBeChecked();
        expect(reason(restarted)).toHaveValue("");
      });
    });

    it("says nothing of the step having moved on while it is read again, holding Send and what was typed", async () => {
      const { sent: served } = conversing({
        [`GET ${STEP}`]: [stepRead(), UNANSWERED],
        [`PUT ${ANSWER}`]: [refusal("STEP_MOVED_ON", 409)],
      });
      const form = await answerOpened();
      fireEvent.change(reason(form), { target: { value: "Read it twice." } });

      press(sendButton(form));
      await landing(() => asked(served, `GET ${STEP}`) === 2);

      expect(screen.queryByRole("alert")).toBeNull();
      expect(sendButton(form)).toHaveAttribute("aria-disabled", "true");
      expect(summary(form)).toHaveValue("A fire.");
      expect(reason(form)).toHaveValue("Read it twice.");
    });

    it("forgets a refusal of the answer once the step owes a later try than the one it was about", async () => {
      conversing({
        [`GET ${RUN}`]: [reply(CLAIM), reply(CLAIM), UNANSWERED],
        [`GET ${STEPS}`]: [stepsRead(), stepsRead(THIRD_OWED), UNANSWERED],
        [`PUT ${ANSWER}`]: [refusal("ACT_NOT_PERMITTED", 403)],
      });
      const form = await answerOpened();
      fireEvent.change(reason(form), { target: { value: "Read it twice." } });
      press(sendButton(form));
      await landing(() => screen.queryByRole("alert") !== null);
      press(within(message()).getByRole("button", { name: "Answer it here" }));

      accountOpened(message(), "Waiting");
      await act(() => vi.advanceTimersByTimeAsync(5000));
      await landing(
        () =>
          within(message()).queryByText("Try 2 of the 3 it allows") !== null,
      );

      expect(screen.queryByRole("alert")).toBeNull();
      expect(screen.queryByText(ANSWER_RULE)).toBeNull();
    });

    it("closes the form, saying the step moved on, once the run's steps owe a later try than the one it is open for", async () => {
      conversing({
        [`GET ${RUN}`]: [reply(CLAIM), reply(CLAIM), UNANSWERED],
        [`GET ${STEPS}`]: [stepsRead(), stepsRead(THIRD_OWED), UNANSWERED],
      });
      await answerOpened();

      accountOpened(message(), "Waiting");
      await act(() => vi.advanceTimersByTimeAsync(5000));
      await landing(
        () =>
          within(message()).queryByText("Try 2 of the 3 it allows") !== null,
      );

      expect(yourAnswer()).toBeNull();
      expect(screen.getByRole("alert")).toHaveTextContent(
        "That step has moved on since it was read.",
      );
      expect(
        within(message()).getByRole("button", { name: "Answer it here" }),
      ).toHaveAttribute("aria-expanded", "false");
    });

    it("forgets that the step moved on under the form once it owes a later try than the one the form was open for", async () => {
      conversing({
        [`GET ${RUN}`]: [reply(CLAIM), reply(CLAIM), reply(CLAIM), UNANSWERED],
        [`GET ${STEPS}`]: [
          stepsRead(),
          stepsRead({
            ...ANSWERED_ROW,
            withheld: [{ act: "review", refusal: "ACT_NOT_PERMITTED" }],
          }),
          stepsRead(THIRD_OWED),
          UNANSWERED,
        ],
      });
      await answerOpened();
      await act(() => vi.advanceTimersByTimeAsync(5000));
      await landing(() => screen.queryByRole("alert") !== null);

      accountOpened(message(), "Waiting");
      await act(() => vi.advanceTimersByTimeAsync(5000));
      await landing(
        () =>
          within(message()).queryByText("Try 2 of the 3 it allows") !== null,
      );

      expect(screen.queryByRole("alert")).toBeNull();
      expect(screen.queryByText(/has moved on/)).toBeNull();
    });

    it.each([
      ["the code its step runs is not held", NOT_HELD_ROW],
      [
        "the workflow it runs is stopped",
        { ...ENTRY_STOPPED_ROW, next: undefined },
      ],
    ])(
      "keeps what was typed without a word while %s, its step held back naming no next try, and draws it again once the step offers the answer again",
      async (_case, held) => {
        conversing({
          [`GET ${RUN}`]: [
            reply(CLAIM),
            reply(CLAIM),
            reply(CLAIM),
            UNANSWERED,
          ],
          [`GET ${STEPS}`]: [
            stepsRead(CODE_ROW),
            stepsRead({ ...CODE_ROW, ...held }),
            stepsRead(CODE_ROW),
            UNANSWERED,
          ],
          [`GET ${STEP}`]: [codeStepRead([REFERENCE])],
        });
        await landing(
          () =>
            screen.queryByRole("list", { name: "The conversation" }) !== null,
        );
        press(screen.getByRole("button", { name: "Answer it here" }));
        await landing(() => yourAnswer() !== null);
        fireEvent.change(
          within(yourAnswer()!).getByRole("textbox", { name: "Reference" }),
          { target: { value: "REF-1" } },
        );
        fireEvent.change(reason(yourAnswer()!), {
          target: { value: "Filed by hand." },
        });

        await act(() => vi.advanceTimersByTimeAsync(5000));
        await landing(() => yourAnswer() === null);

        expect(screen.queryByRole("alert")).toBeNull();
        expect(screen.queryByText(/already, by|has moved on/)).toBeNull();

        await act(() => vi.advanceTimersByTimeAsync(5000));
        await landing(() => yourAnswer() !== null);

        expect(
          within(yourAnswer()!).getByRole("textbox", { name: "Reference" }),
        ).toHaveValue("REF-1");
        expect(reason(yourAnswer()!)).toHaveValue("Filed by hand.");
        expect(screen.queryByRole("alert")).toBeNull();
      },
    );

    it("gives the keyboard to the step's message once a quiet read takes the form away without a word, the run stopped under it", async () => {
      conversing({
        [`GET ${RUN}`]: [reply(CLAIM), reply(STOPPED_CLAIM), UNANSWERED],
        [`GET ${STEPS}`]: [stepsRead(), stoppedStepsRead(), UNANSWERED],
      });
      const form = await answerOpened();
      expect(document.activeElement).toBe(summary(form));

      await act(() => vi.advanceTimersByTimeAsync(5000));
      await landing(() => yourAnswer() === null);

      expect(document.activeElement).toBe(message());
      expect(screen.queryByRole("alert")).toBeNull();
    });

    it("leaves the keyboard where it is once a quiet read takes the form away without a word while the keyboard is elsewhere", async () => {
      conversing({
        [`GET ${RUN}`]: [reply(CLAIM), reply(STOPPED_CLAIM), UNANSWERED],
        [`GET ${STEPS}`]: [stepsRead(), stoppedStepsRead(), UNANSWERED],
      });
      await answerOpened();
      const details = within(message()).getByRole("button", {
        name: "Details",
      });
      act(() => details.focus());

      await act(() => vi.advanceTimersByTimeAsync(5000));
      await landing(() => yourAnswer() === null);

      expect(document.activeElement).toBe(details);
      expect(document.activeElement).not.toBe(message());
    });

    describe("once a quiet read shows the run stopped under the form", () => {
      async function stoppedUnderTheForm() {
        const opened = conversing({
          [`GET ${RUN}`]: [
            reply(CLAIM),
            reply(STOPPED_CLAIM),
            reply(CLAIM),
            UNANSWERED,
          ],
          [`GET ${STEPS}`]: [
            stepsRead(),
            stoppedStepsRead(),
            stepsRead(),
            UNANSWERED,
          ],
          [`GET ${STEP}`]: [
            stepRead(),
            stoppedStepRead(),
            stepRead(),
            UNANSWERED,
          ],
          [`DELETE ${RUN}/stop`]: [reply(CLAIM)],
        });
        await detailsOpened();
        press(
          within(message()).getByRole("button", { name: "Answer it here" }),
        );
        fireEvent.change(summary(yourAnswer()!), {
          target: { value: "A kettle fire." },
        });
        fireEvent.change(reason(yourAnswer()!), {
          target: { value: "Read it twice." },
        });

        await act(() => vi.advanceTimersByTimeAsync(5000));
        await landing(
          () =>
            asked(opened.sent, `GET ${STEP}`) === 2 &&
            screen.queryByRole("button", { name: "Open again" }) !== null,
        );
        await act(() => vi.advanceTimersByTimeAsync(0));
        return opened;
      }

      it("draws no form and says nothing of the step moving on", async () => {
        await stoppedUnderTheForm();

        expect(yourAnswer()).toBeNull();
        expect(
          within(message()).queryByRole("button", { name: "Answer it here" }),
        ).toBeNull();
        expect(screen.queryByRole("alert")).toBeNull();
      });

      it("draws the form again with what was typed once the run is opened again and the step read", async () => {
        const { sent: served } = await stoppedUnderTheForm();

        press(screen.getByRole("button", { name: "Open again" }));
        await landing(() => yourAnswer() !== null);
        await act(() => vi.advanceTimersByTimeAsync(5000));
        await landing(() => asked(served, `GET ${STEP}`) === 3);
        await act(() => vi.advanceTimersByTimeAsync(0));

        expect(summary(yourAnswer()!)).toHaveValue("A kettle fire.");
        expect(reason(yourAnswer()!)).toHaveValue("Read it twice.");
        expect(screen.queryByRole("alert")).toBeNull();
      });
    });

    it("holds Ask again while an answer is out, and not before", async () => {
      conversing({ [`PUT ${ANSWER}`]: [UNANSWERED] });
      const form = await answerOpened();
      fireEvent.change(reason(form), { target: { value: "Read it twice." } });
      expect(
        within(message()).getByRole("button", { name: "Ask again" }),
      ).not.toHaveAttribute("aria-disabled");

      press(sendButton(form));
      await act(() => vi.advanceTimersByTimeAsync(0));

      expect(
        within(message()).getByRole("button", { name: "Ask again" }),
      ).toHaveAttribute("aria-disabled", "true");
      expect(sendButton(form)).toHaveAttribute("aria-disabled", "true");
    });

    it("holds Send while an ask for the try is out, and not before", async () => {
      const { sent: served } = conversing({
        [`PUT ${STEP}/tries/2`]: [UNANSWERED],
      });
      const form = await answerOpened();
      fireEvent.change(reason(form), { target: { value: "Read it twice." } });
      expect(sendButton(form)).not.toHaveAttribute("aria-disabled");

      press(within(message()).getByRole("button", { name: "Ask again" }));
      await act(() => vi.advanceTimersByTimeAsync(0));

      expect(sendButton(form)).toHaveAttribute("aria-disabled", "true");
      press(sendButton(form));
      expect(requestsTo(served)).not.toContain(`PUT ${ANSWER}`);
    });

    it("keeps what is typed and why once Details is closed under the form opened from it, reading nothing more", async () => {
      const { sent: served } = conversing({
        [`GET ${STEP}`]: [stepRead(), UNANSWERED],
      });
      await detailsOpened();
      press(within(message()).getByRole("button", { name: "Answer it here" }));
      fireEvent.change(summary(yourAnswer()!), {
        target: { value: "A kettle fire." },
      });
      fireEvent.change(reason(yourAnswer()!), {
        target: { value: "Read it twice." },
      });

      press(within(message()).getByRole("button", { name: "Details" }));
      await act(() => vi.advanceTimersByTimeAsync(0));

      expect(
        within(message()).getByRole("button", { name: "Details" }),
      ).toHaveAttribute("aria-expanded", "false");
      expect(summary(yourAnswer()!)).toHaveValue("A kettle fire.");
      expect(reason(yourAnswer()!)).toHaveValue("Read it twice.");
      expect(asked(served, `GET ${STEP}`)).toBe(1);
    });

    it("keeps what is typed and why once turned back to the newest try from an earlier one the form opened under", async () => {
      const { sent: served } = conversing({
        [`GET ${STEPS}`]: [stepsRead(THIRD_OWED), UNANSWERED],
        [`GET ${STEP}`]: [thirdOwedRead(), UNANSWERED],
      });
      const drawn = await messageDrawn();
      press(within(drawn).getByRole("button", { name: "Earlier try" }));
      await landing(
        () =>
          within(message()).queryByText("Too short.", { selector: "bdi" })
            ?.parentElement?.textContent === "Refused: Too short.",
      );
      press(within(message()).getByRole("button", { name: "Answer it here" }));
      fireEvent.change(summary(yourAnswer()!), {
        target: { value: "A kettle fire." },
      });
      fireEvent.change(reason(yourAnswer()!), {
        target: { value: "Read it twice." },
      });

      press(within(message()).getByRole("button", { name: "Later try" }));
      await act(() => vi.advanceTimersByTimeAsync(0));

      expect(
        within(message()).getByRole("button", { name: "Later try" }),
      ).toBeDisabled();
      expect(summary(yourAnswer()!)).toHaveValue("A kettle fire.");
      expect(reason(yourAnswer()!)).toHaveValue("Read it twice.");
      expect(asked(served, `GET ${STEP}`)).toBe(1);
    });

    it("reads the step again on opening once it owes a later try than the form was last opened for, drafting that try", async () => {
      const { sent: served } = conversing({
        [`GET ${RUN}`]: [reply(CLAIM), reply(CLAIM), UNANSWERED],
        [`GET ${STEPS}`]: [stepsRead(), stepsRead(THIRD_OWED), UNANSWERED],
        [`GET ${STEP}`]: [stepRead(), thirdOwedRead()],
      });
      await answerOpened();
      press(within(message()).getByRole("button", { name: "Answer it here" }));
      expect(yourAnswer()).toBeNull();
      accountOpened(message(), "Waiting");
      await act(() => vi.advanceTimersByTimeAsync(5000));
      await landing(
        () =>
          within(message()).queryByText("Try 2 of the 3 it allows") !== null,
      );

      press(within(message()).getByRole("button", { name: "Answer it here" }));
      await landing(() => yourAnswer() !== null);

      expect(asked(served, `GET ${STEP}`)).toBe(2);
      expect(yourAnswer()).toHaveTextContent(
        "It starts from what try 2 gave, and every value is given again.",
      );
      expect(summary(yourAnswer()!)).toHaveValue("A printer fire.");
    });

    it("says a stopped run takes no answer, reads the step again, and draws no form where it offers none", async () => {
      const stopped = reply({
        run: { ...HEADER, at: undefined, state: "stopped", acts: [] },
        declarations: DECLARATIONS,
        step: {
          ...OWED_ROW,
          where: { ...OWED_ROW.where, waitsOn: undefined },
          acts: [],
          withheld: [
            { act: "answer", refusal: "RUN_STOPPED" },
            { act: "ask_again", refusal: "RUN_STOPPED" },
          ],
        },
        wentIn: WENT_IN,
        wentInFrom: "try",
        triesMade: [FIRST_TRY],
      });
      const { sent: served } = conversing({
        [`GET ${STEP}`]: [stepRead(), stopped],
        [`PUT ${ANSWER}`]: [refusal("RUN_STOPPED", 409)],
      });
      const form = await answerOpened();
      fireEvent.change(reason(form), { target: { value: "Read it twice." } });

      press(sendButton(form));
      await landing(
        () => asked(served, `GET ${STEP}`) === 2 && yourAnswer() === null,
      );

      expect(screen.getByRole("alert")).toHaveTextContent(
        "A stopped run takes no answer, review or try until it is opened again.",
      );
      expect(
        within(message()).queryByRole("button", { name: "Answer it here" }),
      ).toBeNull();
    });

    it("keeps what was typed and why once an answer is refused as the run stopped, and draws them again once the run is opened again", async () => {
      const { sent: served } = conversing({
        [`GET ${RUN}`]: [
          reply(CLAIM),
          reply(STOPPED_CLAIM),
          reply(CLAIM),
          UNANSWERED,
        ],
        [`GET ${STEPS}`]: [
          stepsRead(),
          stoppedStepsRead(),
          stepsRead(),
          UNANSWERED,
        ],
        [`GET ${STEP}`]: [stepRead(), stoppedStepRead(), UNANSWERED],
        [`PUT ${ANSWER}`]: [refusal("RUN_STOPPED", 409)],
        [`DELETE ${RUN}/stop`]: [reply(CLAIM)],
      });
      const form = await answerOpened();
      fireEvent.change(summary(form), { target: { value: "A kettle fire." } });
      fireEvent.change(reason(form), { target: { value: "Read it twice." } });
      press(sendButton(form));
      await landing(
        () => screen.queryByRole("button", { name: "Open again" }) !== null,
      );
      expect(yourAnswer()).toBeNull();
      expect(screen.getByRole("alert")).toHaveTextContent(
        "A stopped run takes no answer, review or try until it is opened again.",
      );

      press(screen.getByRole("button", { name: "Open again" }));
      await landing(() => yourAnswer() !== null);

      expect(summary(yourAnswer()!)).toHaveValue("A kettle fire.");
      expect(reason(yourAnswer()!)).toHaveValue("Read it twice.");
      expect(screen.queryByRole("alert")).toBeNull();
      expect(asked(served, `GET ${STEP}`)).toBe(2);
      expect(sent(served)).toHaveLength(1);
    });

    it("keeps what was typed and why once an answer is refused as the workflow stopped, and draws them again once the stop is let go", async () => {
      const { sent: served } = conversing({
        [`GET ${RUN}`]: [reply(CLAIM), reply(CLAIM), reply(CLAIM), UNANSWERED],
        [`GET ${STEPS}`]: [
          stepsRead(),
          stepsRead(ENTRY_STOPPED_ROW),
          stepsRead(),
          UNANSWERED,
        ],
        [`GET ${STEP}`]: [stepRead(), entryStoppedStepRead(), UNANSWERED],
        [`PUT ${ANSWER}`]: [refusal("ENTRY_STOPPED", 409)],
      });
      const form = await answerOpened();
      fireEvent.change(summary(form), { target: { value: "A kettle fire." } });
      fireEvent.change(reason(form), { target: { value: "Read it twice." } });
      press(sendButton(form));
      await landing(
        () => asked(served, `GET ${STEP}`) === 2 && yourAnswer() === null,
      );

      expect(screen.getByRole("alert")).toHaveTextContent(
        "What this step runs, or the workflow itself, is stopped, so no try is made on it until that is let go.",
      );

      await act(() => vi.advanceTimersByTimeAsync(5000));
      await landing(() => yourAnswer() !== null);

      expect(summary(yourAnswer()!)).toHaveValue("A kettle fire.");
      expect(reason(yourAnswer()!)).toHaveValue("Read it twice.");
      expect(screen.queryByRole("alert")).toBeNull();
      expect(sent(served)).toHaveLength(1);
    });

    it.each([
      [2, 3, "Try 3 goes past the 2 tries its step allows."],
      [1, 2, "Try 2 goes past the 1 try its step allows."],
    ])(
      "says once the try goes past the %i its step allows, where it does",
      async (declared, number, words) => {
        conversing({
          [`GET ${STEPS}`]: [
            stepsRead({
              tries: { current: number - 1, declared, beyond: false },
              next: { number, beyond: true },
            }),
          ],
        });

        const drawn = await messageDrawn();

        expect(within(drawn).getAllByText(words)).toHaveLength(1);
      },
    );

    it("says nothing of going past what its step allows for a try within it", async () => {
      conversing();

      const drawn = await messageDrawn();

      expect(within(drawn).queryByText(/goes past/)).toBeNull();
    });

    it("says the try the form is open for goes past what its step allows, where the run's steps were read before it was owed", async () => {
      conversing({
        [`GET ${STEP}`]: [
          thirdOwedRead(
            {
              ...THIRD_OWED,
              tries: { current: 2, declared: 2, beyond: false },
              next: { number: 3, beyond: true },
            },
            { beyond: true },
          ),
        ],
      });

      await answerOpened();

      expect(
        within(message()).getByText(
          "Try 3 goes past the 2 tries its step allows.",
        ),
      ).toBeVisible();
      expect(within(message()).queryByText(/^Try 2 goes past/)).toBeNull();
    });

    it("answers a code step that may not run again as its release declares it, offering no Ask again, with no instruction and nothing refused before it", async () => {
      conversing({
        [`GET ${STEPS}`]: [stepsRead(CODE_ROW)],
        [`GET ${STEP}`]: [codeStepRead([REFERENCE])],
      });
      await landing(
        () => screen.queryByRole("list", { name: "The conversation" }) !== null,
      );
      const drawn = screen.getByRole("listitem", {
        name: setApart("summarise"),
      });

      expect(
        within(drawn).queryByRole("button", { name: "Ask again" }),
      ).toBeNull();
      press(within(drawn).getByRole("button", { name: "Answer it here" }));
      await landing(() => yourAnswer() !== null);

      const form = yourAnswer()!;
      const reference = within(form).getByRole("textbox", {
        name: "Reference",
      });
      expect(reference).toHaveValue("");
      expect(document.activeElement).toBe(reference);
      expect(form).not.toHaveTextContent("What it asks");
      expect(form).not.toHaveTextContent("It starts from what try");
      expect(within(form).queryByRole("radio")).toBeNull();
    });

    it("starts the draft and why again where the step read again gives other fields for the same try", async () => {
      const { sent: served } = conversing({
        [`GET ${STEPS}`]: [stepsRead(CODE_ROW), UNANSWERED],
        [`GET ${STEP}`]: [
          codeStepRead([REFERENCE]),
          codeStepRead([REFERENCE, FOLDER]),
        ],
        [`PUT ${ANSWER}`]: [refusal("CODE_STEP_GIVES_OTHERWISE", 409)],
      });
      await landing(
        () => screen.queryByRole("list", { name: "The conversation" }) !== null,
      );
      press(screen.getByRole("button", { name: "Answer it here" }));
      await landing(() => yourAnswer() !== null);
      fireEvent.change(
        within(yourAnswer()!).getByRole("textbox", { name: "Reference" }),
        { target: { value: "REF-1" } },
      );
      fireEvent.change(reason(yourAnswer()!), {
        target: { value: "Filed by hand." },
      });

      press(sendButton(yourAnswer()!));
      await landing(() => asked(served, `GET ${STEP}`) === 2);
      await landing(
        () =>
          yourAnswer() !== null &&
          within(yourAnswer()!).queryByRole("textbox", { name: "Folder" }) !==
            null,
      );

      const form = yourAnswer()!;
      expect(
        within(form).getByRole("textbox", { name: "Reference" }),
      ).toHaveValue("");
      expect(within(form).getByRole("textbox", { name: "Folder" })).toHaveValue(
        "",
      );
      expect(reason(form)).toHaveValue("");
      expect(sent(served)).toHaveLength(1);
      expect(screen.getByRole("alert")).not.toHaveTextContent(/already, by/);
    });
  });

  describe("on the step's own page", () => {
    async function pageDrawn(): Promise<void> {
      await landing(() => screen.queryByRole("heading", { level: 1 }) !== null);
    }

    it("opens from the page already read, reading nothing more, and draws what went in once", async () => {
      const { sent: served } = stepOpened({ [`GET ${STEP}`]: [stepRead()] });
      await pageDrawn();

      press(screen.getByRole("button", { name: "Answer it here" }));

      const form = yourAnswer()!;
      expect(summary(form)).toHaveValue("A fire.");
      expect(document.activeElement).toBe(summary(form));
      expect(
        screen.getAllByRole("region", { name: "What went in" }),
      ).toHaveLength(1);
      expect(asked(served, `GET ${STEP}`)).toBe(1);
    });

    it("keeps what is typed while the step is read again quietly, and sends it to the try it fills", async () => {
      const { sent: served } = stepOpened({
        [`GET ${STEP}`]: [stepRead(), stepRead(), UNANSWERED],
        [`PUT ${ANSWER}`]: [ANSWERED],
      });
      await pageDrawn();
      press(screen.getByRole("button", { name: "Answer it here" }));
      fireEvent.change(summary(yourAnswer()!), {
        target: { value: "A printer fire." },
      });
      fireEvent.change(reason(yourAnswer()!), {
        target: { value: "Read it twice." },
      });

      await act(() => vi.advanceTimersByTimeAsync(5000));
      await landing(() => asked(served, `GET ${STEP}`) === 2);

      expect(summary(yourAnswer()!)).toHaveValue("A printer fire.");
      expect(reason(yourAnswer()!)).toHaveValue("Read it twice.");
      expect(document.activeElement).toBe(summary(yourAnswer()!));

      press(sendButton(yourAnswer()!));
      await landing(() => yourAnswer() === null);

      expect(sent(served).map((init) => init.body)).toEqual([
        JSON.stringify({
          values: { summary: "A printer fire.", priority: "high" },
          why: "Read it twice.",
        }),
      ]);
      expect(asked(served, `GET ${STEP}`)).toBe(3);
      expect(document.activeElement).toBe(
        screen.getByRole("heading", { name: "What came out" }),
      );
    });

    it("says who answered first once a quiet read shows the try the form is open for answered by somebody else, the form gone and nothing sent", async () => {
      const { sent: served } = stepOpened({
        [`GET ${STEP}`]: [stepRead(), EVE_ANSWERED, UNANSWERED],
      });
      await pageDrawn();
      press(screen.getByRole("button", { name: "Answer it here" }));
      fireEvent.change(reason(yourAnswer()!), {
        target: { value: "Read it twice." },
      });

      await act(() => vi.advanceTimersByTimeAsync(5000));
      await landing(
        () => asked(served, `GET ${STEP}`) === 2 && yourAnswer() === null,
      );

      expect(screen.getByRole("alert")).toHaveTextContent(
        `Answered already, by ${setApart("Eve")}.`,
      );
      expect(screen.getByRole("alert")).not.toHaveTextContent(
        "That step has moved on",
      );
      expect(
        screen.queryByRole("button", { name: "Answer it here" }),
      ).toBeNull();
      expect(sent(served)).toEqual([]);
    });

    it("gives the keyboard to what it says once a quiet read takes the form away from under it", async () => {
      const { sent: served } = stepOpened({
        [`GET ${STEP}`]: [stepRead(), EVE_ANSWERED, UNANSWERED],
      });
      await pageDrawn();
      press(screen.getByRole("button", { name: "Answer it here" }));

      await act(() => vi.advanceTimersByTimeAsync(5000));
      await landing(
        () => asked(served, `GET ${STEP}`) === 2 && yourAnswer() === null,
      );

      expect(document.activeElement).toBe(
        screen.getByRole("alert").closest("[tabindex='-1']"),
      );
    });

    it("leaves the keyboard where it is once a quiet read takes the form away while the keyboard is elsewhere", async () => {
      const { sent: served } = stepOpened({
        [`GET ${STEP}`]: [stepRead(), EVE_ANSWERED, UNANSWERED],
      });
      await pageDrawn();
      press(screen.getByRole("button", { name: "Answer it here" }));
      const back = screen.getByRole("link", { name: "History" });
      act(() => back.focus());

      await act(() => vi.advanceTimersByTimeAsync(5000));
      await landing(
        () => asked(served, `GET ${STEP}`) === 2 && yourAnswer() === null,
      );

      expect(document.activeElement).toBe(back);
      expect(screen.getByRole("alert")).toBeVisible();
    });

    it("says nothing of a reason refused once a quiet read shows the try answered by somebody else, saying only who answered first", async () => {
      const { sent: served } = stepOpened({
        [`GET ${STEP}`]: [stepRead(), EVE_ANSWERED, UNANSWERED],
        [`PUT ${ANSWER}`]: [refusal("REASON_UNUSABLE", 400)],
      });
      await pageDrawn();
      press(screen.getByRole("button", { name: "Answer it here" }));
      fireEvent.change(reason(yourAnswer()!), {
        target: { value: "Read it twice." },
      });
      press(sendButton(yourAnswer()!));
      await landing(
        () => reason(yourAnswer()!).getAttribute("aria-invalid") === "true",
      );

      await act(() => vi.advanceTimersByTimeAsync(5000));
      await landing(
        () => asked(served, `GET ${STEP}`) === 2 && yourAnswer() === null,
      );

      expect(screen.getAllByRole("alert")).toHaveLength(1);
      expect(screen.getByRole("alert")).toHaveTextContent(
        `Answered already, by ${setApart("Eve")}.`,
      );
      expect(screen.queryByText(/A reason is prose/)).toBeNull();
    });

    /** The reason's own refusal starts again with the draft, and is said nowhere else once it has. */
    it("says nothing of a reason refused once the form is opened again on the same try giving otherwise, its reason empty", async () => {
      stepOpened({
        [`GET ${STEP}`]: [
          stepRead(),
          stepRead({}, { gives: [SUMMARY] }),
          UNANSWERED,
        ],
        [`PUT ${ANSWER}`]: [refusal("REASON_UNUSABLE", 400)],
      });
      await pageDrawn();
      press(screen.getByRole("button", { name: "Answer it here" }));
      fireEvent.change(reason(yourAnswer()!), {
        target: { value: "Read it twice." },
      });
      press(sendButton(yourAnswer()!));
      await landing(
        () => reason(yourAnswer()!).getAttribute("aria-invalid") === "true",
      );
      await act(() => vi.advanceTimersByTimeAsync(5000));

      press(screen.getByRole("button", { name: "Answer it here" }));
      press(screen.getByRole("button", { name: "Answer it here" }));
      await landing(() => yourAnswer() !== null);

      expect(reason(yourAnswer()!)).toHaveValue("");
      expect(reason(yourAnswer()!)).not.toHaveAttribute("aria-invalid", "true");
      expect(screen.queryByRole("alert")).toBeNull();
      expect(screen.queryByText(/A reason is prose/)).toBeNull();
    });

    it("says only who answered first, and nothing of the refusal, once an answer's refusal arrives after a quiet read showed the try answered by somebody else, the form gone", async () => {
      const answering = deferred<readonly [string, number]>();
      const { sent: served } = stepOpened({
        [`GET ${STEP}`]: [stepRead(), EVE_ANSWERED, UNANSWERED],
        [`PUT ${ANSWER}`]: [answering.promise],
      });
      await pageDrawn();
      press(screen.getByRole("button", { name: "Answer it here" }));
      fireEvent.change(reason(yourAnswer()!), {
        target: { value: "Read it twice." },
      });
      press(sendButton(yourAnswer()!));
      await act(() => vi.advanceTimersByTimeAsync(5000));
      await landing(
        () => asked(served, `GET ${STEP}`) === 2 && yourAnswer() === null,
      );
      expect(screen.queryByText(/already, by/)).toBeNull();

      act(() => answering.settle(refusal("INTERNAL", 500)));
      await landing(() => screen.queryByText(/Answered already/) !== null);

      expect(
        screen.getAllByRole("alert").map((each) => each.textContent),
      ).toEqual([`Answered already, by ${setApart("Eve")}.`]);
      expect(
        screen.queryByRole("button", { name: "Answer it here" }),
      ).toBeNull();
      expect(screen.queryByText(/has moved on/)).toBeNull();
      expect(sent(served)).toHaveLength(1);
    });

    it("says nothing of anybody answering first where a quiet read shows the reader's own answer before it answers, the keyboard going where the answer takes it", async () => {
      const answering = deferred<readonly [string, number]>();
      const { sent: served } = stepOpened({
        [`GET ${STEP}`]: [stepRead(), ANSWERED, UNANSWERED],
        [`PUT ${ANSWER}`]: [answering.promise],
      });
      await pageDrawn();
      press(screen.getByRole("button", { name: "Answer it here" }));
      fireEvent.change(reason(yourAnswer()!), {
        target: { value: "Read it twice." },
      });
      press(sendButton(yourAnswer()!));
      await act(() => vi.advanceTimersByTimeAsync(5000));
      await landing(
        () => asked(served, `GET ${STEP}`) === 2 && yourAnswer() === null,
      );

      act(() => answering.settle(ANSWERED));
      await landing(
        () =>
          document.activeElement ===
          screen.getByRole("heading", { name: "What came out" }),
      );

      expect(screen.queryByRole("alert")).toBeNull();
      expect(screen.queryByText(/already, by/)).toBeNull();
      expect(sent(served)).toHaveLength(1);
    });
  });
});

it("clears rejected files when a moved-on step is read for another try", async () => {
  const pending = deferred<readonly [string, number]>();
  const fileField = {
    name: "attachments",
    label: "Attachments",
    kind: "file",
    types: ["text/plain"],
    maximumBytes: 4,
    most: 2,
    mustBeGiven: true,
  };
  const kept = {
    id: "0000000a-0000-4000-8000-000000000d01",
    name: "Demo Sample earlier.txt",
    type: "text/plain",
    size: 1,
  };
  const opened = conversing({
    [`GET ${STEP}`]: [
      stepRead({}, { gives: [fileField] }),
      thirdOwedRead(undefined, {
        gives: [fileField],
        lastRefused: {
          number: 2,
          values: [{ field: "attachments", value: [kept], now: "refused" }],
        },
      }),
    ],
    [`PUT ${ANSWER}`]: [pending.promise],
    [`PUT ${STEP}/tries/3/answer`]: [ANSWERED],
  });
  const form = await answerOpened();
  const picker = form.querySelector('input[type="file"]')!;
  fireEvent.change(picker, {
    target: {
      files: [
        new File(["x"], "Demo Sample current.txt", { type: "text/plain" }),
      ],
    },
  });
  fireEvent.change(reason(form), { target: { value: "Read it twice." } });
  press(sendButton(form));
  await landing(() => sent(opened.sent).length === 1);

  fireEvent.change(picker, {
    target: {
      files: [
        new File(["xxxxx"], "Demo Sample too large.txt", {
          type: "text/plain",
        }),
      ],
    },
  });
  expect(
    within(form).getByText("No files from this selection were added."),
  ).toBeVisible();
  expect(picker).toHaveAttribute("aria-invalid", "true");

  act(() => pending.settle(refusal("STEP_MOVED_ON", 409)));
  await landing(
    () =>
      asked(opened.sent, `GET ${STEP}`) === 2 &&
      yourAnswer()?.textContent?.includes("try 2 gave") === true,
  );

  const later = yourAnswer()!;
  const laterPicker = later.querySelector('input[type="file"]')!;
  expect(later).toHaveTextContent("try 2 gave");
  expect(later).toHaveTextContent("Demo Sample earlier.txt");
  expect(
    within(later).queryByText("No files from this selection were added."),
  ).toBeNull();
  expect(within(later).queryByText(/Demo Sample too large.txt/)).toBeNull();
  expect(laterPicker).not.toHaveAttribute("aria-invalid", "true");
  expect(reason(later)).toHaveValue("");

  fireEvent.change(reason(later), { target: { value: "Read the new try." } });
  press(sendButton(later));
  await landing(() => asked(opened.sent, `PUT ${STEP}/tries/3/answer`) === 1);
  const body = sent(opened.sent)[1]?.body;
  expect(body).toBeInstanceOf(FormData);
  expect([...(body as FormData).entries()]).toEqual([
    [
      "document",
      JSON.stringify({
        values: { attachments: [{ keep: 0 }] },
        why: "Read the new try.",
      }),
    ],
  ]);
  expect(JSON.stringify([...(body as FormData).entries()])).not.toContain(
    kept.id,
  );
});
