import { ThemeProvider } from "@mui/material/styles";
import { act, fireEvent, render, screen, within } from "@testing-library/react";
import type { ReactNode } from "react";
import { RouterProvider, createMemoryRouter } from "react-router";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { StandingProvider } from "../../../app/standing/StandingContext";
import { theme } from "../../../lib/theme/theme";
import { requestsTo, serving, type Reply } from "../../../testutil/answering";
import { deferred } from "../../../testutil/deferred";
import { laidOutAt } from "../../../testutil/layout";
import { answered, inGroup } from "../../../testutil/standingRead";
import { RunPage } from "../RunPage";
import { StepPage } from "./StepPage";

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

const CLAIM = {
  runId: RUN_ID,
  number: 7,
  name: "Claim from Ada",
  workflow: {
    entryId: "00000006-0000-4000-8000-000000000d01",
    name: "Handle a claim",
    version: 3,
  },
  startedBy: { userId: "000d03", displayName: "Ada" },
  startedAt: "2026-09-26T08:00:00Z",
  state: "running",
  at: { stepId: SUMMARISE, name: "summarise" },
  spend: { sent: "0", cameBack: "0", spent: "0", cameBackUnknown: false },
  ceiling: { raiseNeedsApproval: false },
  acts: ["stop"],
};

/** The run once Ada stopped it: the reader may open it again, and it is read again by nothing but that. */
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

/** Dan refused Cat's summary and assured her priority: try 2 is owed, and nobody has asked for it. */
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

/** The owed row once the run is stopped: try 2 is still owed, and nobody may answer it or ask for it. */
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

/** The step's own page while try 2 is owed, as one who may answer it reads it. */
function stepRead(row: object = {}, answering: object = {}): Reply {
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
  });
}

/** The run's steps as read, the first owing try 2. */
function stepsRead(row: object = {}): Reply {
  return reply({
    run: HEADER,
    declarations: DECLARATIONS,
    gaveBack: { declares: "nothing" },
    steps: [{ ...OWED_ROW, ...row }, FILE_IT_ROW],
    rereadAfterSeconds: 5,
  });
}

/** The run's steps once it is stopped, which says of no while to read them again. */
function stoppedStepsRead(): Reply {
  return reply({
    run: STOPPED_HEADER,
    declarations: DECLARATIONS,
    gaveBack: { declares: "nothing" },
    steps: [{ ...OWED_ROW, ...STOPPED_ROW }, FILE_IT_ROW],
  });
}

/** The step's own page once the run is stopped: nothing is put to the reader to answer. */
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

/** The owed row once the workflow the run runs is stopped: try 2 is still owed, held back until the stop is let go. */
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

/** The step's own page while the workflow is stopped: nothing is put to the reader to answer. */
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

/** The row once somebody's answer filled try 2: its summary waits on a review, and nothing is owed. */
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

/** The step once `person`'s answer filled try 2, its review withheld from the reader as `withheld` says. */
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

/** The step once the reader's own answer filled try 2, Dan reading: a review of his own he may not give. */
const ANSWERED = answeredBy(DAN, {
  act: "review",
  refusal: "REVIEW_OWN_PRODUCTION",
});

/** The step once Eve answered try 2 first, Dan reading, who may not review at all. */
const EVE_ANSWERED = answeredBy(EVE, {
  act: "review",
  refusal: "ACT_NOT_PERMITTED",
});

/** Eve's answer to try 2, refused on review in its turn: a longer summary, and a lower priority assured. */
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

/** The row once try 2 was refused: try 3 is owed, within the 3 the step allows. */
const THIRD_OWED = {
  tries: { current: 2, declared: 3, beyond: false },
  gaveBack: [
    { field: "summary", value: "A printer fire.", now: "refused" },
    { field: "priority", value: "low", now: "stands" },
  ],
  next: { number: 3, beyond: false },
};

/** The step's own page once try 2 was refused, as one who may answer try 3 reads it. */
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

/** Its code went wrong on try 1, and it may not be run again: only an answer here makes try 2. */
const CODE_ROW = {
  runs: { kind: "code_step", codeStep: "file_claim" },
  producer: { kind: "code" },
  reviewer: { kind: "person" },
  gaveBack: undefined,
  acts: ["answer"],
  withheld: [{ act: "ask_again", refusal: "ASK_AGAIN_NOT_OFFERED" }],
};

/** The code step's row while the system does not hold the code it runs: try 2 is owed all the same, and named nowhere. */
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

/** The code step's own page, try 2 answered here with the fields its release now gives. */
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

/** Try 1 of the code step gave a value of every kind of control, and Dan refused each. */
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

/** The code step's own page, try 2 answered here starting from every value of try 1. */
function kindsRead(): Reply {
  return reply({
    run: HEADER,
    rereadAfterSeconds: 5,
    declarations: DECLARATIONS,
    step: { ...OWED_ROW, ...CODE_ROW },
    wentIn: WENT_IN,
    wentInFrom: "try",
    triesMade: [KINDS_TRY],
    answering: {
      number: 2,
      beyond: false,
      gives: [ADDRESS, NOTES, PRIORITY, DUE],
      lastRefused: { number: 1, values: KINDS_TRY.values },
    },
  });
}

/**
 * Try 1 of the code step, made before its release changed: its summary as the release gives one now, and its
 * priority as it was kept, a term none of the list's; Dan refused both.
 */
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

/** The code step's own page, try 2 answered here starting from what of try 1 its release still gives. */
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

/** The run drawn as a conversation, its run and steps read as `routes` answers beyond what they first say. */
function conversing(routes: Readonly<Record<string, readonly Reply[]>> = {}) {
  const onActed = vi.fn();
  const opened = opening(
    {
      [`GET ${RUN}`]: [reply(CLAIM), UNANSWERED],
      [`GET ${STEPS}`]: [stepsRead(), UNANSWERED],
      [`GET ${STEP}`]: [stepRead()],
      ...routes,
    },
    "/groups/:groupId/work/:runId",
    <RunPage drawing="conversation" onDetail={() => {}} onActed={onActed} />,
    PAGE,
  );
  return { ...opened, onActed };
}

function stepOpened(routes: Readonly<Record<string, readonly Reply[]>>) {
  const onActed = vi.fn();
  const opened = opening(
    routes,
    "/groups/:groupId/work/:runId/steps/:stepId",
    <StepPage onActed={onActed} />,
    `${PAGE}/steps/${SUMMARISE}`,
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

/** The message with Answer it here pressed and its form open. */
async function answerOpened(): Promise<HTMLElement> {
  const drawn = await messageDrawn();
  press(within(drawn).getByRole("button", { name: "Answer it here" }));
  await landing(() => yourAnswer() !== null);
  return yourAnswer()!;
}

/** The message with Details open, so the step is read beside it. */
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

      expect(
        within(drawn).getByText("You may not answer it here."),
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

      expect(
        within(form).getByText(`Refused: ${setApart("Too short.")}`),
      ).toBeVisible();
      expect(summary(form)).toHaveAccessibleDescription(
        expect.stringContaining(`Refused: ${setApart("Too short.")}`),
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
          expect.stringContaining(`Refused: ${setApart(why)}`),
        );
        expect(
          within(form).getAllByText(`Refused: ${setApart(why)}`),
        ).toHaveLength(1);
      },
    );

    it("sends every value, assured ones too, and why, to the try it fills, then shows the step as answered, reads the run again and gives the keyboard to its message", async () => {
      const { sent: served, onActed } = conversing({
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
      expect(onActed).toHaveBeenCalledTimes(1);
      expect(document.activeElement).toBe(message());
      expect(screen.queryByRole("alert")).toBeNull();
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
        expect.stringContaining(`Refused: ${setApart("Too short.")}`),
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
        expect.stringContaining(`Refused: ${setApart("Not today.")}`),
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
      /** Try 2 answered first by Eve and refused on review since, the answer to it refused and the step read again. */
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
            opened.onActed.mock.calls.length === 1,
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

      it("reads the step again and hands that on, sending nothing more", async () => {
        const { sent: served, onActed } = await refusedAsMovedOn();

        expect(asked(served, `GET ${STEP}`)).toBe(2);
        expect(onActed).toHaveBeenCalledTimes(1);
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
      /** Details open, the form opened from what it read and typed in, then the run and the step read as stopped. */
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
          within(message()).queryByText(
            `Refused: ${setApart("Too short.")}`,
          ) !== null,
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
      await landing(() => screen.queryByRole("heading", { level: 2 }) !== null);
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
      const { sent: served, onActed } = stepOpened({
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
      expect(onActed).toHaveBeenCalledTimes(1);
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
      const back = screen.getByRole("link", { name: "Back to the run" });
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
