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
import { KEY } from "../../../testutil/runPage";
import { openStepPage } from "../../../testutil/stepPage";
import { accountOpened } from "../../../testutil/stateAccount";
import { tryReplies } from "../../../testutil/tryReplies";

const GROUP = "00000003-0000-4000-8000-000000000d01";

const RUN_ID = "00000008-0000-4000-8000-000000000d01";

const VERSION = "00000007-0000-4000-8000-000000000d01";

const QUESTION_VERSION = "00000007-0000-4000-8000-000000000d02";

const SUMMARISE = "00000009-0000-4000-8000-000000000d01";

const FILE_IT = "00000009-0000-4000-8000-000000000d02";

const PAGE = `/groups/${KEY}/work/${RUN_ID}`;

const RUN = `/api/groups/${GROUP}/runs/${RUN_ID}`;

const STEPS = `${RUN}/steps`;

const STEP = `${STEPS}/${SUMMARISE}`;

const ANSWER = `${STEP}/tries/2/answer`;

const CAT = { userId: "000d01", displayName: "Cat" };

const DAN = { userId: "000d02", displayName: "Dan" };

const EVE = { userId: "000d04", displayName: "Eve" };

const ANSWER_RULE =
  "Only someone in this group who can answer steps can answer a step or ask again.";

const CRLF_REFUSED =
  "Lines here end in a line feed alone, never in a carriage return and a line feed (CRLF).";

const CLAIM = {
  runId: RUN_ID,
  number: 7,
  name: "Claim from Avo",
  workflow: {
    versionName: null,
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
  stateActs: ["stop"],
};

const STOPPED_CLAIM = {
  ...CLAIM,
  state: "stopped",
  at: undefined,
  stopped: { at: "2026-09-26T09:45:00Z", by: CLAIM.startedBy },
  stateActs: ["open_again"],
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
  stateActs: ["stop"],
  progress: { done: 0, of: 2 },
};

const STOPPED_HEADER = {
  ...HEADER,
  state: "stopped",
  at: undefined,
  stateActs: ["open_again"],
};

const WENT_IN = [
  {
    input: "text",
    from: { kind: "run_input", path: "ticket" },
    value: "Printer on fire",
  },
];

const OWED_ROW = {
  producedByUserId: null,
  producedByKind: null,
  stepId: SUMMARISE,
  order: 1,
  name: "summarise",
  runs: {
    versionName: null,
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
  stateActs: ["answer", "ask_again"],
  stateRefusals: [],
};

const STOPPED_ROW = {
  where: { ...OWED_ROW.where, waitsOn: undefined },
  stateActs: [],
  stateRefusals: [
    { act: "answer", refusal: "RUN_STOPPED" },
    { act: "ask_again", refusal: "RUN_STOPPED" },
  ],
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
      decision: { outcome: "refused", whyLines: "several", why: "Too short." },
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
  stateActs: [],
  stateRefusals: [
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
  stateActs: [],
};

function answeredBy(person: typeof DAN): readonly [string, number] {
  return reply({
    run: HEADER,
    rereadAfterSeconds: 5,
    declarations: DECLARATIONS,
    step: {
      ...OWED_ROW,
      ...ANSWERED_ROW,
      stateActs: ["review"],
      stateRefusals: [],
      producedByKind: "person",
      producedByUserId: person.userId,
    },
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
const ANSWERED = answeredBy(DAN);

const EVE_ANSWERED = answeredBy(EVE);

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
      decision: { outcome: "refused", whyLines: "several", why: "Say where." },
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
  runs: { versionName: null, kind: "code_step", codeStep: "file_claim" },
  producer: { kind: "code" },
  reviewer: { kind: "person" },
  gaveBack: undefined,
  stateActs: ["answer"],
  stateRefusals: [{ act: "ask_again", refusal: "ASK_AGAIN_NOT_OFFERED" }],
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
  stateActs: [],
  stateRefusals: [],
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
      decision: { outcome: "refused", whyLines: "several", why: "Which town?" },
    },
    {
      field: "notes",
      value: ["Smoke."],
      now: "refused",
      decision: { outcome: "refused", whyLines: "several", why: "Say more." },
    },
    {
      field: "priority",
      value: "high",
      now: "refused",
      decision: { outcome: "refused", whyLines: "several", why: "Not today." },
    },
    {
      field: "due",
      value: "2026-09-26T10:00:00+02:00",
      now: "refused",
      decision: { outcome: "refused", whyLines: "several", why: "Too late." },
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
      decision: { outcome: "refused", whyLines: "several", why: "Too short." },
    },
    {
      field: "priority",
      value: JSON.stringify("urgent"),
      now: "refused",
      decision: { outcome: "refused", whyLines: "several", why: "Not today." },
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

const UNANSWERED: Reply = new Promise(() => {});

function reply(body: object): readonly [string, number] {
  return tryReplies(body).summary;
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

function conversing(
  routes: Readonly<Record<string, readonly Reply[]>> = {},
  permissions: readonly string[] = ["answer_step", "start_run"],
) {
  return openStepPage({
    groupId: GROUP,
    view: "run",
    address: PAGE,
    permissions,
    userId: DAN.userId,
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
    userId: DAN.userId,
    routes,
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
  // Let the current refused value's guarded detail read land before an answer is opened.
  await act(() => vi.advanceTimersByTimeAsync(0));
  return message();
}

async function answerOpened(): Promise<HTMLElement> {
  const drawn = await messageDrawn();
  await press(
    within(drawn).getByRole("button", { name: "Answer it yourself" }),
  );
  await landing(() => yourAnswer() !== null);
  return yourAnswer()!;
}

async function detailsOpened(): Promise<void> {
  const drawn = await messageDrawn();
  await press(within(drawn).getByRole("button", { name: "Details" }));
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

let reader: ReturnType<typeof userEvent.setup>;

async function press(control: HTMLElement): Promise<void> {
  let completed = false;
  const pressed = reader.click(control);
  void pressed.then(
    () => {
      completed = true;
    },
    () => {
      completed = true;
    },
  );
  // Testing Library's async wrapper schedules one more timeout after user-event has advanced its own timers.
  if (vi.isFakeTimers()) {
    for (let turn = 0; turn < 100 && !completed; turn += 1) {
      await vi.advanceTimersByTimeAsync(0);
    }
    expect(completed).toBe(true);
  }
  await pressed;
  await act(async () => {});
}

function summary(form: HTMLElement): HTMLElement {
  return within(form).getByRole("textbox", { name: "Summary · summary" });
}

function reason(form: HTMLElement): HTMLElement {
  return within(form).getByRole("textbox", {
    name: "Your reason",
  });
}

function sendButton(form: HTMLElement): HTMLElement {
  return within(form).getByRole("button", { name: "Submit answer" });
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
  reader = userEvent.setup({
    advanceTimers: (delay) =>
      vi.isFakeTimers()
        ? act(() => vi.advanceTimersByTimeAsync(delay))
        : Promise.resolve(),
  });
});

afterEach(() => {
  vi.useRealTimers();
});

describe("AnswerHere", () => {
  it("marks the instruction's input label and name beside the input values without substituting them", async () => {
    const { sent: requests } = stepOpened({
      [`GET ${STEP}`]: [
        stepRead({}, { instruction: "Read {{text}}. \\{{literal}}" }),
      ],
    });
    await landing(
      () =>
        screen.queryByRole("button", { name: "Answer it yourself" }) !== null,
    );
    await press(screen.getByRole("button", { name: "Answer it yourself" }));
    await landing(() => yourAnswer() !== null);
    const form = yourAnswer()!;
    expect(within(form).getByText("{{text}}").parentElement?.textContent).toBe(
      "Text · {{text}}",
    );
    expect(form).toHaveTextContent("{{literal}}");
    expect(screen.getAllByText("Printer on fire").length).toBeGreaterThan(0);
    expect(form).not.toHaveTextContent("Read Printer on fire");
    expect(sent(requests)).toEqual([]);
  });
  it.each(["ACT_NOT_PERMITTED", "REASON_UNUSABLE"])(
    "discards a late answer PUT refusal %s after permission was revoked and restored",
    async (code) => {
      const later = deferred<readonly [string, number]>();
      const {
        sent: served,
        changeStanding,
        readAgain,
      } = conversing({ [`PUT ${ANSWER}`]: [later.promise] });
      const form = await answerOpened();
      fireEvent.change(summary(form), { target: { value: "A printer fire." } });
      fireEvent.change(reason(form), {
        target: { value: "Read the incident twice." },
      });
      await press(sendButton(form));
      await landing(() => sent(served).length === 1);
      act(() => changeStanding([]));
      act(() => changeStanding(["answer_step", "start_run"]));
      const restored = within(message()).getByRole("button", {
        name: "Answer it yourself",
      });
      act(() => restored.focus());
      await act(async () =>
        later.settle(refusal(code, code === "ACT_NOT_PERMITTED" ? 403 : 400)),
      );
      await act(() => vi.advanceTimersByTimeAsync(0));
      expect(screen.queryByRole("alert")).toBeNull();
      expect(document.activeElement).toBe(restored);
      expect(reason(yourAnswer()!)).not.toHaveAttribute("aria-invalid", "true");
      expect(summary(yourAnswer()!)).toHaveValue("A printer fire.");
      expect(readAgain).not.toHaveBeenCalled();
      expect(sent(served)).toHaveLength(1);
    },
  );
  it.each(["success", "failure"])(
    "discards an already issued answer reread's late %s after permission returns",
    async (outcome) => {
      const later = deferred<readonly [string, number]>();
      const { sent: served, changeStanding } = conversing({
        [`GET ${STEP}`]: [stepRead(), later.promise],
        [`PUT ${ANSWER}`]: [refusal("STEP_MOVED_ON", 409)],
      });
      const form = await answerOpened();
      fireEvent.change(summary(form), { target: { value: "A printer fire." } });
      fireEvent.change(reason(form), {
        target: { value: "Read the incident twice." },
      });
      await press(sendButton(form));
      await landing(() => asked(served, `GET ${STEP}`) === 2);
      act(() => changeStanding([]));
      act(() => changeStanding(["answer_step", "start_run"]));
      const restored = within(message()).getByRole("button", {
        name: "Answer it yourself",
      });
      act(() => restored.focus());
      await act(async () =>
        later.settle(
          outcome === "success"
            ? await stepRead()
            : refusal("READ_FAILED", 503),
        ),
      );
      await act(() => vi.advanceTimersByTimeAsync(0));
      expect(screen.queryByRole("alert")).toBeNull();
      expect(document.activeElement).toBe(restored);
      expect(summary(yourAnswer()!)).toHaveValue("A printer fire.");
      expect(asked(served, `GET ${STEP}`)).toBe(2);
      expect(sent(served)).toHaveLength(1);
    },
  );
  it.each(["permission", "identity"])(
    "does not accept a successful answer PUT after the reader's %s changes and returns",
    async (change) => {
      const later = deferred<readonly [string, number]>();
      const {
        sent: served,
        changeStanding,
        readAgain,
      } = conversing({
        [`PUT ${ANSWER}`]: [later.promise],
      });
      const form = await answerOpened();
      fireEvent.change(summary(form), { target: { value: "A printer fire." } });
      fireEvent.change(reason(form), {
        target: { value: "Read the incident twice." },
      });
      await press(sendButton(form));
      await landing(() => sent(served).length === 1);
      const permissions = ["answer_step", "start_run"];
      act(() =>
        changeStanding(
          change === "permission" ? ["start_run"] : permissions,
          change === "identity" ? EVE.userId : DAN.userId,
        ),
      );
      act(() => changeStanding(permissions, DAN.userId));
      const restored = within(message()).getByRole("button", {
        name: "Answer it yourself",
      });
      act(() => restored.focus());

      await act(async () => later.settle(ANSWERED));
      await act(() => vi.advanceTimersByTimeAsync(0));

      expect(restored).toHaveFocus();
      expect(summary(yourAnswer()!)).toHaveValue("A printer fire.");
      expect(reason(yourAnswer()!)).toHaveValue("Read the incident twice.");
      expect(screen.queryByRole("alert")).toBeNull();
      expect(readAgain).not.toHaveBeenCalled();
      expect(sent(served)).toHaveLength(1);
    },
  );
  it.each(["permission", "identity"])(
    "forgets an accepted answer-form failure after the reader's %s changes, without reviving it on return",
    async (change) => {
      const later = deferred<readonly [string, number]>();
      const {
        sent: served,
        changeStanding,
        readAgain,
      } = conversing({
        [`GET ${STEP}`]: [
          stepRead({}, {}, { answering: undefined }),
          later.promise,
          stepRead(),
        ],
      });
      const permissions = ["answer_step", "start_run"];
      const drawn = await messageDrawn();
      await press(
        within(drawn).getByRole("button", { name: "Answer it yourself" }),
      );
      await landing(() => asked(served, `GET ${STEP}`) === 2);
      await act(async () => later.settle(refusal("READ_FAILED", 503)));
      await landing(() => screen.queryByRole("alert") !== null);
      const alert = screen.getByRole("alert");
      expect(alert).toBeVisible();
      expect(alert.closest('[tabindex="-1"]')).toHaveFocus();
      expect(yourAnswer()).toBeNull();

      act(() => changeStanding(permissions));
      expect(screen.getByRole("alert")).toBe(alert);
      act(() =>
        changeStanding(
          change === "permission" ? ["start_run"] : permissions,
          change === "identity" ? EVE.userId : DAN.userId,
        ),
      );
      expect(screen.queryByRole("alert")).toBeNull();
      expect(yourAnswer()).toBeNull();
      act(() => changeStanding(permissions, DAN.userId));
      expect(screen.queryByRole("alert")).toBeNull();
      const restored = within(message()).getByRole("button", {
        name: "Answer it yourself",
      });
      act(() => restored.focus());
      await act(() => vi.advanceTimersByTimeAsync(0));
      expect(restored).toHaveFocus();
      expect(asked(served, `GET ${STEP}`)).toBe(2);
      expect(sent(served)).toEqual([]);
      expect(readAgain).not.toHaveBeenCalled();

      await press(restored);
      await landing(() => yourAnswer() !== null);
      expect(summary(yourAnswer()!)).toHaveValue("A fire.");
      expect(summary(yourAnswer()!)).toHaveFocus();
      expect(reason(yourAnswer()!)).toHaveValue("");
      expect(screen.queryByRole("alert")).toBeNull();
      expect(asked(served, `GET ${STEP}`)).toBe(3);
      expect(sent(served)).toEqual([]);
    },
  );
  it("discards a late answer-form failure after answer permission was revoked and restored", async () => {
    const later = deferred<readonly [string, number]>();
    const {
      sent: served,
      changeStanding,
      readAgain,
    } = conversing({
      [`GET ${STEP}`]: [
        stepRead({}, {}, { answering: undefined }),
        later.promise,
      ],
    });
    const drawn = await messageDrawn();
    await press(
      within(drawn).getByRole("button", { name: "Answer it yourself" }),
    );
    await landing(() => asked(served, `GET ${STEP}`) === 2);
    act(() => changeStanding([]));
    act(() => changeStanding(["answer_step", "start_run"]));
    const restored = within(message()).getByRole("button", {
      name: "Answer it yourself",
    });
    act(() => restored.focus());
    await act(async () => later.settle(refusal("READ_FAILED", 503)));
    await act(() => vi.advanceTimersByTimeAsync(0));
    expect(screen.queryByRole("alert")).toBeNull();
    expect(document.activeElement).toBe(restored);
    expect(yourAnswer()).toBeNull();
    expect(readAgain).not.toHaveBeenCalled();
    expect(sent(served)).toEqual([]);
  });
  it("discards an answer-form read that crossed a permission revocation instead of reopening its form when permission returns", async () => {
    const later = deferred<readonly [string, number]>();
    const { sent: served, changeStanding } = conversing({
      [`GET ${STEP}`]: [
        stepRead({}, {}, { answering: undefined }),
        later.promise,
      ],
    });
    const drawn = await messageDrawn();
    await press(
      within(drawn).getByRole("button", { name: "Answer it yourself" }),
    );
    await landing(() => asked(served, `GET ${STEP}`) === 2);
    act(() => changeStanding([]));
    expect(yourAnswer()).toBeNull();
    act(() => changeStanding(["answer_step", "start_run"]));
    await act(async () => later.settle(await stepRead()));
    await act(() => vi.advanceTimersByTimeAsync(0));
    expect(yourAnswer()).toBeNull();
    expect(sent(served)).toEqual([]);
    expect(asked(served, `GET ${STEP}`)).toBe(2);
  });
  it.each([true, false])(
    "draws its pinned instruction and separate ordinary inputs with sent=%s",
    async (sent) => {
      const instruction = `Demo${String.fromCodePoint(0x200b)}Sample server instruction`;
      const { sent: requests } = stepOpened({
        [`GET ${STEP}`]: [
          stepRead(
            { runs: { ...OWED_ROW.runs, kind: "search", searchWay: "web" } },
            { instruction },
            { wentInFrom: sent ? "try" : "not_yet_sent" },
          ),
        ],
      });
      await landing(
        () =>
          screen.queryByRole("button", { name: "Answer it yourself" }) !== null,
      );
      await press(screen.getByRole("button", { name: "Answer it yourself" }));
      await landing(() => yourAnswer() !== null);
      const form = yourAnswer()!;
      expect(form).toHaveTextContent("DemoU+200BSample server instruction");
      expect(form.innerHTML).not.toContain(String.fromCodePoint(0x200b));
      expect(within(form).queryByText("The instruction")).toBeNull();
      expect(within(form).queryByText("Inputs")).toBeNull();
      expect(screen.getByText("Printer on fire")).toBeInTheDocument();
      expect(
        screen.queryByText(
          "Not sent yet — these are the inputs it would use now.",
          { exact: false },
        ) !== null,
      ).toBe(!sent);
      expect(asked(requests, `GET ${STEP}`)).toBe(1);
    },
  );

  it("withholds a model-source input while keeping the author instruction and another readable input", async () => {
    stepOpened({
      [`GET ${STEP}`]: [
        stepRead(
          {
            runs: { ...OWED_ROW.runs, kind: "search", searchWay: "web" },
            takesFrom: [
              ...OWED_ROW.takesFrom,
              {
                input: "secret",
                from: { kind: "step", stepId: FILE_IT, name: "Earlier model" },
              },
            ],
          },
          { instruction: "Read the visible evidence." },
          {
            declarations: {
              ...DECLARATIONS,
              [QUESTION_VERSION]: {
                ...DECLARATIONS[QUESTION_VERSION],
                takes: [
                  ...DECLARATIONS[QUESTION_VERSION].takes,
                  { ...TICKET, name: "secret", label: "Secret evidence" },
                ],
              },
            },
            wentIn: [
              ...WENT_IN,
              {
                input: "secret",
                from: {
                  kind: "step",
                  stepId: FILE_IT,
                  name: "Earlier model",
                  runId: RUN_ID,
                  runNumber: 7,
                },
                withheld: true,
              },
            ],
          },
        ),
      ],
    });
    await landing(
      () =>
        screen.queryByRole("button", { name: "Answer it yourself" }) !== null,
    );
    await press(screen.getByRole("button", { name: "Answer it yourself" }));
    await landing(() => yourAnswer() !== null);
    expect(yourAnswer()).toHaveTextContent("Read the visible evidence.");
    expect(screen.getByText("Printer on fire")).toBeInTheDocument();
    expect(screen.getByText(/Secret evidence/)).toBeInTheDocument();
    expect(
      screen.getByText(/You don't have permission to see/),
    ).toBeInTheDocument();
    expect(
      screen.getByText(/Secret evidence/).closest("section"),
    ).toHaveTextContent("You don't have permission to see");
  });
  describe("in a run's conversation", () => {
    it("draws Answer it yourself beside Ask again without opening a form, while reading the current refusal once", async () => {
      const { sent: served } = conversing();

      const drawn = await messageDrawn();

      const answer = within(drawn).getByRole("button", {
        name: "Answer it yourself",
      });
      expect(answer).toHaveAttribute("aria-expanded", "false");
      expect(
        within(drawn).getByRole("button", { name: "Ask again" }),
      ).toBeVisible();
      expect(yourAnswer()).toBeNull();
      expect(within(drawn).queryByRole("textbox")).toBeNull();
      expect(asked(served, `GET ${STEP}`)).toBe(1);
      expect(sent(served)).toEqual([]);
    });

    it("draws neither where the step withholds both from the reader, saying so instead", async () => {
      conversing(
        {
          [`GET ${STEPS}`]: [
            stepsRead({
              stateActs: ["answer", "ask_again"],
              stateRefusals: [],
            }),
          ],
        },
        [],
      );

      const drawn = await messageDrawn();

      expect(drawn).not.toHaveTextContent(
        "You don't have permission to answer it.",
      );
      expect(
        within(accountOpened(drawn, "Waiting")).getByText(
          "You don't have permission to answer it.",
        ),
      ).toBeVisible();
      expect(
        within(drawn).queryByRole("button", { name: "Answer it yourself" }),
      ).toBeNull();
      expect(
        within(drawn).queryByRole("button", { name: "Ask again" }),
      ).toBeNull();
    });

    it("reads the step once pressed and opens the form under Answer it yourself, sending nothing", async () => {
      const { sent: served } = conversing();

      const form = await answerOpened();

      expect(asked(served, `GET ${STEP}`)).toBe(1);
      expect(
        within(message()).getByRole("button", { name: "Answer it yourself" }),
      ).toHaveAttribute("aria-expanded", "true");
      expect(form).toBeVisible();
      expect(sent(served)).toEqual([]);
    });

    it("holds Answer it yourself while its read of the step is out, reading once however often it is pressed", async () => {
      const { sent: served } = conversing({
        [`GET ${STEPS}`]: [stepsRead({ gaveBack: undefined }), UNANSWERED],
        [`GET ${STEP}`]: [UNANSWERED],
      });
      const answer = within(await messageDrawn()).getByRole("button", {
        name: "Answer it yourself",
      });
      expect(answer).not.toHaveAttribute("aria-disabled");

      await press(answer);
      await act(() => vi.advanceTimersByTimeAsync(0));
      await press(answer);
      await act(() => vi.advanceTimersByTimeAsync(0));

      expect(answer).toHaveAttribute("aria-disabled", "true");
      expect(asked(served, `GET ${STEP}`)).toBe(1);
      expect(yourAnswer()).toBeNull();
    });

    it("forgets a refused read of the step once a later press opens the form from the step read beside it", async () => {
      const { sent: served } = conversing({
        [`GET ${STEPS}`]: [stepsRead({ gaveBack: undefined }), UNANSWERED],
        [`GET ${STEP}`]: [refusal("INTERNAL", 500), stepRead(), UNANSWERED],
      });
      const drawn = await messageDrawn();
      await press(
        within(drawn).getByRole("button", { name: "Answer it yourself" }),
      );
      await landing(() => screen.queryByRole("alert") !== null);
      await detailsOpened();

      await press(
        within(message()).getByRole("button", { name: "Answer it yourself" }),
      );

      expect(yourAnswer()).not.toBeNull();
      expect(screen.queryByRole("alert")).toBeNull();
      expect(asked(served, `GET ${STEP}`)).toBe(2);
      await press(
        within(message()).getByRole("button", { name: "Answer it yourself" }),
      );
      expect(yourAnswer()).toBeNull();
      expect(screen.queryByRole("alert")).toBeNull();
      expect(asked(served, `GET ${STEP}`)).toBe(2);
    });

    it("opens with what it asks, as whoever made the question wrote it", async () => {
      conversing();

      const form = await answerOpened();

      expect(sendButton(form)).toHaveClass("MuiButton-contained");
      expect(form).toHaveTextContent("Instructions");
      expect(within(form).getByText(/^Summarise the ticket/).textContent).toBe(
        INSTRUCTION,
      );
    });

    it("draws what went in beside the form", async () => {
      conversing();

      await answerOpened();

      expect(screen.getByRole("region", { name: "Inputs" })).toHaveTextContent(
        "Printer on fire",
      );
      for (const name of ["Inputs", "Your answer"]) {
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

      const wentIn = screen.getByRole("region", { name: "Inputs" });
      const sectionLevel = Number(
        within(wentIn)
          .getByRole("heading", { name: "Inputs" })
          .tagName.slice(1),
      );
      expect(
        within(wentIn).getByRole("heading", { name: "The ticket" }).tagName,
      ).toBe(`H${sectionLevel + 1}`);
      expect(within(wentIn).queryByRole("heading", { level: 1 })).toBeNull();
      expect(wentIn.querySelector("blockquote")).toHaveTextContent(
        /^Printer on fire$/,
      );
      expect(wentIn.querySelector("dl:not(aside *)")).toBeNull();
    });

    it("starts every value from the try refused before it, saying so, and why from nothing", async () => {
      conversing();

      const form = await answerOpened();

      expect(form).toHaveTextContent(
        "Pre-filled from attempt 1. Every value is submitted again, not only the rejected ones.",
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
      const words = within(form).getByText("Too short.", { selector: "p" });

      expect(words).toBeVisible();
      expect(words.parentElement?.parentElement).toHaveTextContent(
        /^Rejected: Too short\.$/,
      );
      expect(summary(form)).toHaveAccessibleDescription(
        expect.stringContaining("Rejected: Too short."),
      );
      expect(within(form).queryByText("Accepted.")).toBeNull();
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
        expect.stringContaining("Rejected for length."),
      );
      expect(within(form).queryByText("Accepted.")).toBeNull();
    });

    it("prefills and sends the whole production when only one of its values was rejected for length", async () => {
      const values = [
        {
          ...FIRST_TRY.values[0],
          now: "refused_for_length",
          decision: {
            outcome: "refused",
            why: "Give it again, shorter.",
            whyLines: "several",
            lengthUnits: "251",
            excessUnits: "47",
            lengthHeld: {
              run: { runId: RUN_ID, number: 7 },
              stepId: FILE_IT,
              stepName: "file_it",
            },
          },
        },
        FIRST_TRY.values[1],
      ];
      const { sent: served } = conversing({
        [`GET ${STEP}`]: [
          stepRead(
            {},
            { lastRefused: { number: 1, values } },
            {
              triesMade: [
                { ...FIRST_TRY, values, ended: "refused_for_length" },
              ],
            },
          ),
        ],
        [`PUT ${ANSWER}`]: [ANSWERED],
      });
      const form = await answerOpened();
      expect(summary(form)).toHaveValue("A fire.");
      expect(within(form).getByRole("radio", { name: /high/ })).toBeChecked();
      expect(
        within(form).getByRole("radiogroup", { name: "Priority · priority" }),
      ).toBeEnabled();
      expect(summary(form)).toHaveAccessibleDescription(
        expect.stringContaining("Rejected for length."),
      );
      fireEvent.change(summary(form), { target: { value: "Fire." } });
      fireEvent.change(reason(form), {
        target: { value: "Shortened the summary." },
      });
      await press(sendButton(form));
      await landing(() => yourAnswer() === null);
      expect(sent(served).map((init) => init.body)).toEqual([
        JSON.stringify({
          values: { summary: "Fire.", priority: "high" },
          why: "Shortened the summary.",
        }),
      ]);
    });

    it.each([
      [
        "fields",
        (form: HTMLElement) =>
          within(form).getByRole("group", { name: "Address · address" }),
        "Which town?",
      ],
      [
        "many",
        (form: HTMLElement) =>
          within(form).getByRole("group", { name: "Notes · notes" }),
        "Say more.",
      ],
      [
        "choice",
        (form: HTMLElement) =>
          within(form).getByRole("radiogroup", { name: "Priority · priority" }),
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

        await press(screen.getByRole("button", { name: "Answer it yourself" }));
        await landing(() => yourAnswer() !== null);

        const form = yourAnswer()!;
        expect(control(form)).toHaveAccessibleDescription(
          expect.stringContaining(`Rejected: ${why}`),
        );
        expect(within(form).getAllByText(why, { selector: "p" })).toHaveLength(
          1,
        );
      },
    );

    it("draws a character in the words a value was refused with that shows nothing as its marker beside it, never the character", async () => {
      const values = [
        FIRST_TRY.values[0],
        {
          ...FIRST_TRY.values[1],
          now: "refused",
          decision: {
            outcome: "refused",
            whyLines: "several",
            why: `Not${ZERO_WIDTH_SPACE}today.`,
          },
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
        name: "An invisible character is here: U+200B.",
      });

      expect(marker.closest('[dir="auto"]')).toHaveTextContent(
        /^NotU\+200Btoday\.$/,
      );
      expect(
        within(form).getByRole("radiogroup", { name: "Priority · priority" }),
      ).toHaveAccessibleDescription(
        expect.stringMatching(/Rejected: Not ?U\+200B ?today\./),
      );
      expect(form.innerHTML).not.toContain(ZERO_WIDTH_SPACE);
    });

    it("draws a character that shows nothing in the words a model's review refused an earlier try's value with as its marker in the message, never the character", async () => {
      const values = [
        {
          ...FIRST_TRY.values[0],
          decision: {
            outcome: "refused",
            whyLines: "several",
            why: `Too${ZERO_WIDTH_SPACE}short.`,
          },
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

      await press(
        within(drawn).getByRole("button", { name: "Previous attempt" }),
      );
      await landing(
        () =>
          within(message()).queryByRole("img", {
            name: "An invisible character is here: U+200B.",
          }) !== null,
      );

      const marker = within(message()).getByRole("img", {
        name: "An invisible character is here: U+200B.",
      });
      expect(marker.closest('[dir="auto"]')?.textContent).toBe(
        "TooU+200Bshort.",
      );
      expect(marker.closest('[dir="auto"]')?.parentElement).toHaveTextContent(
        "Rejected:",
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
          decision: {
            outcome: "refused",
            whyLines: "several",
            why: "Not today.",
          },
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
          "The pre-filled text leaves out 3 invisible or text-direction characters from the earlier answer.",
        ),
      );
      expect(
        within(form).getByRole("radiogroup", { name: "Priority · priority" }),
      ).not.toHaveAccessibleDescription(expect.stringContaining("leaves out"));

      fireEvent.change(reason(form), { target: { value: "As it reads." } });
      await press(sendButton(form));
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
          decision: {
            outcome: "refused",
            whyLines: "several",
            why: "Too short.",
          },
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
          "The pre-filled text leaves out 6 invisible or text-direction characters from the earlier answer",
        ),
      );
      expect(
        within(form).getByRole("radiogroup", { name: "Priority · priority" }),
      ).not.toHaveAccessibleDescription(expect.stringContaining("leaves out"));

      fireEvent.change(reason(form), { target: { value: "As it reads." } });
      await press(sendButton(form));
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
            decision: {
              outcome: "refused",
              whyLines: "several",
              why: "Too short.",
            },
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
              `The pre-filled text leaves out ${leftOut} invisible or text-direction character from the earlier answer`,
            ),
          );
        }
        expect(form).not.toHaveTextContent(CRLF_REFUSED);

        fireEvent.change(reason(form), { target: { value: "As it reads." } });
        await press(sendButton(form));
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

      await press(screen.getByRole("button", { name: "Answer it yourself" }));
      await landing(() => yourAnswer() !== null);

      const form = yourAnswer()!;
      expect(
        within(form).getByRole("textbox", { name: "Street · street" }),
      ).toHaveValue("Main\nSt");
      expect(
        within(
          within(form).getByRole("group", { name: "Notes · notes" }),
        ).getByRole("textbox"),
      ).toHaveValue("Smoke\nrising.");
      for (const name of ["Address · address", "Notes · notes"]) {
        expect(
          within(form).getByRole("group", { name }),
        ).not.toHaveAccessibleDescription(
          expect.stringContaining("leaves out"),
        );
      }
      expect(form).not.toHaveTextContent(CRLF_REFUSED);

      fireEvent.change(reason(form), { target: { value: "As it reads." } });
      await press(sendButton(form));
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

      await press(screen.getByRole("button", { name: "Answer it yourself" }));
      await landing(() => yourAnswer() !== null);

      const form = yourAnswer()!;
      expect(
        within(form).getByRole("textbox", { name: "Street · street" }),
      ).toHaveValue("MainSt");
      expect(
        within(form).getByRole("group", { name: "Address · address" }),
      ).toHaveAccessibleDescription(
        expect.stringContaining(
          "The pre-filled text leaves out 1 invisible or text-direction character from the earlier answer",
        ),
      );
      expect(
        within(form).getByDisplayValue(`Smoke ${heartOnFire}`),
      ).toBeVisible();
      expect(
        within(form).getByRole("group", { name: "Notes · notes" }),
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
      await press(screen.getByRole("button", { name: "Answer it yourself" }));
      await landing(() => yourAnswer() !== null);

      const form = yourAnswer()!;
      expect(
        within(form).getByRole("textbox", { name: "Street · street" }),
      ).toHaveValue("Main St");
      expect(
        within(form).getByRole("textbox", {
          name: "Private note · privateNote",
        }),
      ).toHaveValue("");
      fireEvent.change(reason(form), {
        target: { value: "Demo Sample review." },
      });
      await press(sendButton(form));
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
      await press(screen.getByRole("button", { name: "Answer it yourself" }));
      await landing(() => yourAnswer() !== null);

      const form = yourAnswer()!;
      expect(
        within(form).getByRole("textbox", { name: "Street · street" }),
      ).toHaveValue("");
      fireEvent.change(
        within(form).getByRole("textbox", { name: "Street · street" }),
        {
          target: { value: "Demo Sample street" },
        },
      );
      fireEvent.change(reason(form), {
        target: { value: "Demo Sample review." },
      });
      await press(sendButton(form));
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
      await press(screen.getByRole("button", { name: "Answer it yourself" }));
      await landing(() => yourAnswer() !== null);

      const form = yourAnswer()!;
      expect(
        within(form).getByRole("textbox", { name: "Withheld note · withheld" }),
      ).toHaveValue("Demo Sample");
      fireEvent.change(reason(form), {
        target: { value: "Demo Sample review." },
      });
      await press(sendButton(form));
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
      await press(screen.getByRole("button", { name: "Answer it yourself" }));
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
      await press(sendButton(form));
      expect(sent(served)).toEqual([]);
      expect(time).toHaveValue("10:00:00.1234567");

      fireEvent.change(time, { target: { value: "10:00:00.123456" } });
      await press(sendButton(form));
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
      await press(screen.getByRole("button", { name: "Answer it yourself" }));
      await landing(() => yourAnswer() !== null);

      const form = yourAnswer()!;
      expect(within(form).getByRole("textbox", { name: "Time" })).toHaveValue(
        "10:00:00.1234",
      );
      fireEvent.change(reason(form), {
        target: { value: "Demo Sample review." },
      });
      await press(sendButton(form));
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
      await press(screen.getByRole("button", { name: "Answer it yourself" }));
      await landing(() => yourAnswer() !== null);
      vi.useRealTimers();

      const form = yourAnswer()!;
      const street = within(form).getByRole("textbox", {
        name: "Street · street",
      }) as HTMLInputElement;
      const user = userEvent.setup();
      expect(street).toHaveValue(`${astral}A`);
      fireEvent.change(street, { target: { value: `${astral}AB` } });
      expect(street).toHaveValue(`${astral}AB`);
      await press(sendButton(form));
      expect(street).toHaveFocus();
      expect(sent(served)).toEqual([]);
      await user.click(street);
      await user.paste("B");
      expect(street).toHaveValue(`${astral}ABB`);
      expect(street).toHaveAccessibleDescription(
        /This is longer than this field takes/,
      );
      expect(street).toHaveFocus();
      expect(sent(served)).toEqual([]);

      street.setSelectionRange(astral.length, street.value.length);
      await user.paste("C");
      expect(street).toHaveValue(`${astral}C`);
      fireEvent.change(reason(form), {
        target: { value: "Demo Sample review." },
      });
      await press(sendButton(form));
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
      await press(within(form).getByRole("radio", { name: /^low/ }));
      fireEvent.change(reason(form), {
        target: { value: "Longer, and less urgent." },
      });

      await press(sendButton(form));
      await landing(() => yourAnswer() === null);

      expect(sent(served).map((init) => init.body)).toEqual([
        JSON.stringify({
          values: { summary: "A printer fire.", priority: "low" },
          why: "Longer, and less urgent.",
        }),
      ]);
      expect(requestsTo(served)).toContain(`PUT ${ANSWER}`);
      expect(within(message()).getByText("Waiting for review")).toBeVisible();
      expect(
        within(message()).queryByRole("button", { name: "Answer it yourself" }),
      ).toBeNull();
      expect(asked(served, `GET ${RUN}`)).toBe(2);
      expect(document.activeElement).toBe(message());
      expect(screen.queryByRole("alert")).toBeNull();
    });

    it("keeps a refused file after an invalid replacement and sends its metadata nowhere in the answer document", async () => {
      const kept = {
        id: "0000000a-0000-4000-8000-000000000d01",
        name: "Demo Sample.txt",
        size: 6,
      };
      const fileField = {
        name: "attachment",
        label: "Attachment",
        kind: "file",
        types: [".txt"],
        maximumBytes: 20,
        mustBeGiven: true,
      };
      const values = [
        {
          field: "attachment",
          value: kept,
          now: "refused",
          decision: {
            outcome: "refused",
            whyLines: "several",
            why: "Read it again.",
          },
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
      expect(
        [...form.querySelectorAll("bdi")].find(
          (element) =>
            element.textContent === "Demo Sample.txt" &&
            element.closest("[hidden]") === null,
        ),
      ).toBeVisible();
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
            /Demo Sample\.png.*doesn't have an extension this field accepts/.test(
              element.textContent ?? "",
            ),
        ),
      ).toBeVisible();
      expect(
        [...form.querySelectorAll("bdi")].find(
          (element) =>
            element.textContent === "Demo Sample.txt" &&
            element.closest("[hidden]") === null,
        ),
      ).toBeVisible();
      expect(picker).toHaveAttribute("aria-invalid", "true");
      fireEvent.change(reason(form), {
        target: { value: "Kept after review." },
      });

      await press(sendButton(form));
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

      await press(send);
      await act(() => vi.advanceTimersByTimeAsync(0));

      expect(document.activeElement).toBe(summary(form));
      expect(summary(form)).toHaveAccessibleDescription(/Required\./);
      expect(form).toHaveTextContent("A required field is empty.");
      expect(reason(form)).toHaveAccessibleDescription(
        "A rejection or an answer needs a reason.",
      );

      fireEvent.change(summary(form), { target: { value: "A printer fire." } });
      act(() => send.focus());
      await press(send);
      await act(() => vi.advanceTimersByTimeAsync(0));

      expect(document.activeElement).toBe(reason(form));
      expect(summary(form)).not.toHaveAccessibleDescription(/Required\./);
      expect(sent(served)).toEqual([]);
    });

    it("takes the keyboard to why once what it lacks is said there, at the first Send with every value fitting", async () => {
      const { sent: served } = conversing({ [`PUT ${ANSWER}`]: [ANSWERED] });
      const form = await answerOpened();
      const send = sendButton(form);
      act(() => send.focus());

      await press(send);
      await act(() => vi.advanceTimersByTimeAsync(0));

      expect(document.activeElement).toBe(reason(form));
      expect(reason(form)).toHaveAccessibleDescription(
        "A rejection or an answer needs a reason.",
      );
      expect(summary(form)).not.toHaveAccessibleDescription(/Required\./);
      expect(sent(served)).toEqual([]);
    });

    it("starts empty a value the try refused before gave that is withheld from the reader, saying so at its control", async () => {
      const withheld = [
        {
          field: "summary",
          withheld: true,
          now: "refused",
          decision: {
            outcome: "refused",
            whyLines: "several",
            why: "Too short.",
          },
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
        expect.stringContaining(
          "You don't have permission to see what attempt 1 gave here.",
        ),
      );
      expect(summary(form)).toHaveAccessibleDescription(
        expect.stringContaining("Rejected: Too short."),
      );
      expect(within(form).getByRole("radio", { name: /^high/ })).toBeChecked();
      expect(
        within(form).getAllByText(/don't have permission to see/),
      ).toHaveLength(1);
    });

    it("starts empty a value the try refused before gave in a shape the step had before, saying so at its control, and starts the rest from what it gave", async () => {
      conversing({
        [`GET ${STEPS}`]: [stepsRead(CODE_ROW)],
        [`GET ${STEP}`]: [earlierShapeRead()],
      });
      await landing(
        () => screen.queryByRole("list", { name: "The conversation" }) !== null,
      );

      await press(screen.getByRole("button", { name: "Answer it yourself" }));
      await landing(() => yourAnswer() !== null);

      const form = yourAnswer()!;
      const priority = within(form).getByRole("radiogroup", {
        name: "Priority · priority",
      });
      expect(
        within(priority)
          .getAllByRole("radio")
          .filter((each) => (each as HTMLInputElement).checked),
      ).toEqual([]);
      expect(priority).toHaveAccessibleDescription(
        expect.stringContaining(
          "Attempt 1 gave this in an older format of the field, so it starts empty.",
        ),
      );
      expect(priority).toHaveAccessibleDescription(
        expect.stringContaining("Rejected: Not today."),
      );
      expect(summary(form)).toHaveValue("A fire.");
      expect(summary(form)).not.toHaveAccessibleDescription(
        expect.stringContaining("shape this step had before"),
      );
      expect(
        within(form).queryByText(/don't have permission to see/),
      ).toBeNull();
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

      await press(send);
      await landing(() => screen.queryByRole("alert") !== null);

      expect(sent(served)).toHaveLength(1);
      expect(document.activeElement).toBe(summary(form));
      expect(summary(form)).toHaveAccessibleDescription(
        /This is longer than this field takes\./,
      );
      expect(form).toHaveTextContent(
        "1 more field has a problem that isn't marked.",
      );
      expect(screen.getByRole("alert")).toHaveTextContent(
        "Some values aren't in the format their field expects.",
      );

      fireEvent.change(summary(form), { target: { value: "A fire, again." } });

      expect(summary(form)).not.toHaveAccessibleDescription(
        /This is longer than this field takes\./,
      );
      expect(form).not.toHaveTextContent("more field has a problem");
      expect(screen.queryByRole("alert")).toBeNull();
    });

    it("marks at the reason a reason the server refuses, giving it the keyboard and saying nothing else, until it is changed", async () => {
      conversing({
        [`PUT ${ANSWER}`]: [refusal("REASON_UNUSABLE", 400)],
      });
      const form = await answerOpened();
      fireEvent.change(reason(form), { target: { value: "Read it twice." } });

      await press(sendButton(form));
      await landing(() => document.activeElement === reason(form));

      expect(reason(form)).toHaveAccessibleDescription(
        "A reason is 1 to 2048 characters, with at least one visible character.",
      );
      expect(screen.queryByRole("alert")).toBeNull();
      expect(summary(form)).toHaveValue("A fire.");

      fireEvent.change(reason(form), { target: { value: "Read it again." } });

      expect(reason(form)).not.toHaveAccessibleDescription(
        /A reason is 1 to 2048 characters/,
      );
    });

    it("says nothing aloud, and the refusal nowhere, once a reason the server refused is changed", async () => {
      conversing({
        [`PUT ${ANSWER}`]: [refusal("REASON_UNUSABLE", 400)],
      });
      const form = await answerOpened();
      fireEvent.change(reason(form), { target: { value: "Read it twice." } });
      await press(sendButton(form));
      await landing(() => document.activeElement === reason(form));

      fireEvent.change(reason(form), { target: { value: "Read it again." } });

      expect(screen.queryByRole("alert")).toBeNull();
      expect(screen.queryByText(/A reason is 1 to 2048 characters/)).toBeNull();
    });

    it("says the answer is refused by the rule of who may answer a step, and reads the reader's standing again, reading nothing of the step", async () => {
      const { sent: served, readAgain } = conversing({
        [`PUT ${ANSWER}`]: [refusal("ACT_NOT_PERMITTED", 403)],
      });
      const form = await answerOpened();
      fireEvent.change(reason(form), { target: { value: "Read it twice." } });

      await press(sendButton(form));
      await landing(() => screen.queryByRole("alert") !== null);

      const said = screen.getByRole("alert");
      expect(said).toHaveTextContent(ANSWER_RULE);
      expect(said).not.toHaveTextContent(
        "You don't have permission to do this.",
      );
      expect(readAgain).toHaveBeenCalledTimes(1);
      expect(asked(served, `GET ${STEP}`)).toBe(1);
    });

    describe("once the answer is refused as the step having moved on", () => {
      async function refusedAsMovedOn() {
        const opened = conversing({
          [`GET ${STEP}`]: [
            stepRead(),
            thirdOwedRead(),
            thirdOwedRead(),
            UNANSWERED,
          ],
          [`PUT ${ANSWER}`]: [refusal("STEP_MOVED_ON", 409)],
        });
        const form = await answerOpened();
        fireEvent.change(summary(form), {
          target: { value: "A kettle fire." },
        });
        fireEvent.change(reason(form), { target: { value: "Read it twice." } });

        await press(sendButton(form));
        await landing(
          () =>
            asked(opened.sent, `GET ${STEP}`) === 3 &&
            asked(opened.sent, `GET ${STEPS}`) === 2,
        );
        return opened;
      }

      it("says who answered first, and only that, the keyboard on it", async () => {
        await refusedAsMovedOn();

        const alert = screen.getByRole("alert");
        expect(alert).toHaveTextContent(
          `Already answered by ${setApart("Eve")}.`,
        );
        expect(alert).not.toHaveTextContent("This step has changed");
        expect(screen.getAllByRole("alert")).toHaveLength(1);
        expect(document.activeElement).toBe(alert.closest("[tabindex='-1']"));
      });

      it("reads the step again and hands that on to the run, which reads its steps again, sending nothing more", async () => {
        const { sent: served } = await refusedAsMovedOn();

        // The current refusal is read once, then the moved-on answer and its new footer are read.
        expect(asked(served, `GET ${STEP}`)).toBe(3);
        expect(asked(served, `GET ${STEPS}`)).toBe(2);
        expect(sent(served)).toHaveLength(1);
      });

      it("starts the draft and why again from the try refused since", async () => {
        await refusedAsMovedOn();

        const restarted = yourAnswer()!;
        expect(restarted).toHaveTextContent(
          "Pre-filled from attempt 2. Every value is submitted again, not only the rejected ones.",
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

      await press(sendButton(form));
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
      await press(sendButton(form));
      await landing(() => screen.queryByRole("alert") !== null);
      await press(
        within(message()).getByRole("button", { name: "Answer it yourself" }),
      );

      accountOpened(message(), "Waiting");
      await act(() => vi.advanceTimersByTimeAsync(5000));
      await landing(
        () => within(message()).queryByText("Attempt 2 of 3") !== null,
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
        () => within(message()).queryByText("Attempt 2 of 3") !== null,
      );

      expect(yourAnswer()).toBeNull();
      expect(screen.getByRole("alert")).toHaveTextContent(
        "This step has changed since you opened it.",
      );
      expect(
        within(message()).getByRole("button", { name: "Answer it yourself" }),
      ).toHaveAttribute("aria-expanded", "false");
    });

    it("forgets that the step moved on under the form once it owes a later try than the one the form was open for", async () => {
      conversing({
        [`GET ${RUN}`]: [reply(CLAIM), reply(CLAIM), reply(CLAIM), UNANSWERED],
        [`GET ${STEPS}`]: [
          stepsRead(),
          stepsRead({
            ...ANSWERED_ROW,
            stateActs: ["review"],
            stateRefusals: [],
            producedByKind: "person",
            producedByUserId: EVE.userId,
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
        () => within(message()).queryByText("Attempt 2 of 3") !== null,
      );

      expect(screen.queryByRole("alert")).toBeNull();
      expect(screen.queryByText(/has changed since you opened it/)).toBeNull();
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
        await press(screen.getByRole("button", { name: "Answer it yourself" }));
        await landing(() => yourAnswer() !== null);
        fireEvent.change(
          within(yourAnswer()!).getByRole("textbox", {
            name: "Reference · reference",
          }),
          { target: { value: "REF-1" } },
        );
        fireEvent.change(reason(yourAnswer()!), {
          target: { value: "Filed by hand." },
        });

        await act(() => vi.advanceTimersByTimeAsync(5000));
        await landing(() => yourAnswer() === null);

        expect(screen.queryByRole("alert")).toBeNull();
        expect(
          screen.queryByText(/Already .* by|has changed since you opened it/),
        ).toBeNull();

        await act(() => vi.advanceTimersByTimeAsync(5000));
        await landing(() => yourAnswer() !== null);

        expect(
          within(yourAnswer()!).getByRole("textbox", {
            name: "Reference · reference",
          }),
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
        await press(
          within(message()).getByRole("button", { name: "Answer it yourself" }),
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
            screen.queryByRole("button", { name: "Resume" }) !== null,
        );
        await act(() => vi.advanceTimersByTimeAsync(0));
        return opened;
      }

      it("draws no form and says nothing of the step moving on", async () => {
        await stoppedUnderTheForm();

        expect(yourAnswer()).toBeNull();
        expect(
          within(message()).queryByRole("button", {
            name: "Answer it yourself",
          }),
        ).toBeNull();
        expect(screen.queryByRole("alert")).toBeNull();
      });

      it("draws the form again with what was typed once the run is opened again and the step read", async () => {
        const { sent: served } = await stoppedUnderTheForm();

        await press(screen.getByRole("button", { name: "Resume" }));
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

      await press(sendButton(form));
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

      await press(within(message()).getByRole("button", { name: "Ask again" }));
      await act(() => vi.advanceTimersByTimeAsync(0));

      expect(sendButton(form)).toHaveAttribute("aria-disabled", "true");
      // Dispatch even though pointer interaction is disabled, to test the handler's own guard.
      act(() => sendButton(form).click());
      expect(requestsTo(served)).not.toContain(`PUT ${ANSWER}`);
    });

    it("keeps what is typed and why once Details is closed under the form opened from it, reading nothing more", async () => {
      const { sent: served } = conversing({
        [`GET ${STEP}`]: [stepRead(), UNANSWERED],
      });
      await detailsOpened();
      await press(
        within(message()).getByRole("button", { name: "Answer it yourself" }),
      );
      fireEvent.change(summary(yourAnswer()!), {
        target: { value: "A kettle fire." },
      });
      fireEvent.change(reason(yourAnswer()!), {
        target: { value: "Read it twice." },
      });

      await press(within(message()).getByRole("button", { name: "Details" }));
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
      await press(
        within(drawn).getByRole("button", { name: "Previous attempt" }),
      );
      await landing(
        () =>
          within(message())
            .queryByText("Too short.", { selector: "p" })
            ?.closest("section")
            ?.textContent?.includes("Rejected:") === true,
      );
      await press(
        within(message()).getByRole("button", { name: "Answer it yourself" }),
      );
      fireEvent.change(summary(yourAnswer()!), {
        target: { value: "A kettle fire." },
      });
      fireEvent.change(reason(yourAnswer()!), {
        target: { value: "Read it twice." },
      });

      await press(
        within(message()).getByRole("button", { name: "Next attempt" }),
      );
      await act(() => vi.advanceTimersByTimeAsync(0));

      expect(
        within(message()).getByRole("button", { name: "Next attempt" }),
      ).toHaveAttribute("aria-disabled", "true");
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
      await press(
        within(message()).getByRole("button", { name: "Answer it yourself" }),
      );
      expect(yourAnswer()).toBeNull();
      accountOpened(message(), "Waiting");
      await act(() => vi.advanceTimersByTimeAsync(5000));
      await landing(
        () => within(message()).queryByText("Attempt 2 of 3") !== null,
      );

      await press(
        within(message()).getByRole("button", { name: "Answer it yourself" }),
      );
      await landing(() => yourAnswer() !== null);

      expect(asked(served, `GET ${STEP}`)).toBe(2);
      expect(yourAnswer()).toHaveTextContent(
        "Pre-filled from attempt 2. Every value is submitted again, not only the rejected ones.",
      );
      expect(summary(yourAnswer()!)).toHaveValue("A printer fire.");
    });

    it("says a stopped run takes no answer, reads the step again, and draws no form where it offers none", async () => {
      const stopped = reply({
        run: { ...HEADER, at: undefined, state: "stopped", stateActs: [] },
        declarations: DECLARATIONS,
        step: {
          ...OWED_ROW,
          where: { ...OWED_ROW.where, waitsOn: undefined },
          stateActs: [],
          stateRefusals: [
            { act: "answer", refusal: "RUN_STOPPED" },
            { act: "ask_again", refusal: "RUN_STOPPED" },
          ],
        },
        wentIn: WENT_IN,
        wentInFrom: "try",
        triesMade: [FIRST_TRY],
      });
      const { sent: served } = conversing({
        [`GET ${STEP}`]: [stepRead(), stopped, stopped, UNANSWERED],
        [`PUT ${ANSWER}`]: [refusal("RUN_STOPPED", 409)],
      });
      const form = await answerOpened();
      fireEvent.change(reason(form), { target: { value: "Read it twice." } });

      await press(sendButton(form));
      await landing(
        () => asked(served, `GET ${STEP}`) === 3 && yourAnswer() === null,
      );

      expect(screen.getByRole("alert")).toHaveTextContent(
        "This run is stopped. Resume it before answering, reviewing or making another attempt.",
      );
      expect(
        within(message()).queryByRole("button", { name: "Answer it yourself" }),
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
        [`GET ${STEP}`]: [
          stepRead(),
          stoppedStepRead(),
          stoppedStepRead(),
          stepRead(),
          UNANSWERED,
        ],
        [`PUT ${ANSWER}`]: [refusal("RUN_STOPPED", 409)],
        [`DELETE ${RUN}/stop`]: [reply(CLAIM)],
      });
      const form = await answerOpened();
      fireEvent.change(summary(form), { target: { value: "A kettle fire." } });
      fireEvent.change(reason(form), { target: { value: "Read it twice." } });
      await press(sendButton(form));
      await landing(
        () => screen.queryByRole("button", { name: "Resume" }) !== null,
      );
      expect(yourAnswer()).toBeNull();
      expect(screen.getByRole("alert")).toHaveTextContent(
        "This run is stopped. Resume it before answering, reviewing or making another attempt.",
      );

      await press(screen.getByRole("button", { name: "Resume" }));
      await landing(() => yourAnswer() !== null);

      expect(summary(yourAnswer()!)).toHaveValue("A kettle fire.");
      expect(reason(yourAnswer()!)).toHaveValue("Read it twice.");
      expect(screen.queryByRole("alert")).toBeNull();
      expect(asked(served, `GET ${STEP}`)).toBe(4);
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
        [`GET ${STEP}`]: [
          stepRead(),
          entryStoppedStepRead(),
          entryStoppedStepRead(),
          stepRead(),
          UNANSWERED,
        ],
        [`PUT ${ANSWER}`]: [refusal("ENTRY_STOPPED", 409)],
      });
      const form = await answerOpened();
      fireEvent.change(summary(form), { target: { value: "A kettle fire." } });
      fireEvent.change(reason(form), { target: { value: "Read it twice." } });
      await press(sendButton(form));
      await landing(
        () => asked(served, `GET ${STEP}`) === 3 && yourAnswer() === null,
      );

      expect(screen.getByRole("alert")).toHaveTextContent(
        "What this step runs, or the workflow itself, has been stopped. No attempt can be made until it is allowed to be used again.",
      );

      await act(() => vi.advanceTimersByTimeAsync(5000));
      await landing(() => yourAnswer() !== null);

      expect(summary(yourAnswer()!)).toHaveValue("A kettle fire.");
      expect(reason(yourAnswer()!)).toHaveValue("Read it twice.");
      expect(screen.queryByRole("alert")).toBeNull();
      expect(sent(served)).toHaveLength(1);
    });

    it.each([
      [2, 3, "Attempt 3 goes beyond the 2 attempts this step allows."],
      [1, 2, "Attempt 2 goes beyond the 1 attempt this step allows."],
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

      expect(within(drawn).queryByText(/goes beyond/)).toBeNull();
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
          "Attempt 3 goes beyond the 2 attempts this step allows.",
        ),
      ).toBeVisible();
      expect(
        within(message()).queryByText(/^Attempt 2 goes beyond/),
      ).toBeNull();
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
      await press(
        within(drawn).getByRole("button", { name: "Answer it yourself" }),
      );
      await landing(() => yourAnswer() !== null);

      const form = yourAnswer()!;
      const reference = within(form).getByRole("textbox", {
        name: "Reference · reference",
      });
      expect(reference).toHaveValue("");
      expect(document.activeElement).toBe(reference);
      expect(form).not.toHaveTextContent("Instructions");
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
      await press(screen.getByRole("button", { name: "Answer it yourself" }));
      await landing(() => yourAnswer() !== null);
      fireEvent.change(
        within(yourAnswer()!).getByRole("textbox", {
          name: "Reference · reference",
        }),
        { target: { value: "REF-1" } },
      );
      fireEvent.change(reason(yourAnswer()!), {
        target: { value: "Filed by hand." },
      });

      await press(sendButton(yourAnswer()!));
      await landing(() => asked(served, `GET ${STEP}`) === 2);
      await landing(
        () =>
          yourAnswer() !== null &&
          within(yourAnswer()!).queryByRole("textbox", {
            name: "Folder · folder",
          }) !== null,
      );

      const form = yourAnswer()!;
      expect(
        within(form).getByRole("textbox", { name: "Reference · reference" }),
      ).toHaveValue("");
      expect(
        within(form).getByRole("textbox", { name: "Folder · folder" }),
      ).toHaveValue("");
      expect(reason(form)).toHaveValue("");
      expect(sent(served)).toHaveLength(1);
      expect(screen.getByRole("alert")).not.toHaveTextContent(/Already .* by/);
    });
  });

  describe("on the step's own page", () => {
    async function pageDrawn(): Promise<void> {
      await landing(() => screen.queryByRole("heading", { level: 1 }) !== null);
    }

    it("opens from the page already read, reading nothing more, and draws what went in once", async () => {
      const { sent: served } = stepOpened({ [`GET ${STEP}`]: [stepRead()] });
      await pageDrawn();

      await press(screen.getByRole("button", { name: "Answer it yourself" }));

      const form = yourAnswer()!;
      expect(summary(form)).toHaveValue("A fire.");
      expect(document.activeElement).toBe(summary(form));
      expect(screen.getAllByRole("region", { name: "Inputs" })).toHaveLength(1);
      expect(asked(served, `GET ${STEP}`)).toBe(1);
    });

    it("keeps what is typed while the step is read again quietly, and sends it to the try it fills", async () => {
      const { sent: served } = stepOpened({
        [`GET ${STEP}`]: [stepRead(), stepRead(), UNANSWERED],
        [`PUT ${ANSWER}`]: [ANSWERED],
      });
      await pageDrawn();
      await press(screen.getByRole("button", { name: "Answer it yourself" }));
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

      await press(sendButton(yourAnswer()!));
      await landing(() => yourAnswer() === null);

      expect(sent(served).map((init) => init.body)).toEqual([
        JSON.stringify({
          values: { summary: "A printer fire.", priority: "high" },
          why: "Read it twice.",
        }),
      ]);
      expect(asked(served, `GET ${STEP}`)).toBe(3);
      expect(document.activeElement).toBe(
        screen.getByRole("heading", { name: "Output" }),
      );
    });

    it("says who answered first once a quiet read shows the try the form is open for answered by somebody else, the form gone and nothing sent", async () => {
      const { sent: served } = stepOpened({
        [`GET ${STEP}`]: [stepRead(), EVE_ANSWERED, UNANSWERED],
      });
      await pageDrawn();
      await press(screen.getByRole("button", { name: "Answer it yourself" }));
      fireEvent.change(reason(yourAnswer()!), {
        target: { value: "Read it twice." },
      });

      await act(() => vi.advanceTimersByTimeAsync(5000));
      await landing(
        () => asked(served, `GET ${STEP}`) === 2 && yourAnswer() === null,
      );

      expect(screen.getByRole("alert")).toHaveTextContent(
        `Already answered by ${setApart("Eve")}.`,
      );
      expect(screen.getByRole("alert")).not.toHaveTextContent(
        "This step has changed",
      );
      expect(
        screen.queryByRole("button", { name: "Answer it yourself" }),
      ).toBeNull();
      expect(sent(served)).toEqual([]);
    });

    it("gives the keyboard to what it says once a quiet read takes the form away from under it", async () => {
      const { sent: served } = stepOpened({
        [`GET ${STEP}`]: [stepRead(), EVE_ANSWERED, UNANSWERED],
      });
      await pageDrawn();
      await press(screen.getByRole("button", { name: "Answer it yourself" }));

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
      await press(screen.getByRole("button", { name: "Answer it yourself" }));
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
      await press(screen.getByRole("button", { name: "Answer it yourself" }));
      fireEvent.change(reason(yourAnswer()!), {
        target: { value: "Read it twice." },
      });
      await press(sendButton(yourAnswer()!));
      await landing(
        () => reason(yourAnswer()!).getAttribute("aria-invalid") === "true",
      );

      await act(() => vi.advanceTimersByTimeAsync(5000));
      await landing(
        () => asked(served, `GET ${STEP}`) === 2 && yourAnswer() === null,
      );

      expect(screen.getAllByRole("alert")).toHaveLength(1);
      expect(screen.getByRole("alert")).toHaveTextContent(
        `Already answered by ${setApart("Eve")}.`,
      );
      expect(screen.queryByText(/A reason is 1 to 2048 characters/)).toBeNull();
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
      await press(screen.getByRole("button", { name: "Answer it yourself" }));
      fireEvent.change(reason(yourAnswer()!), {
        target: { value: "Read it twice." },
      });
      await press(sendButton(yourAnswer()!));
      await landing(
        () => reason(yourAnswer()!).getAttribute("aria-invalid") === "true",
      );
      await act(() => vi.advanceTimersByTimeAsync(5000));

      await press(screen.getByRole("button", { name: "Answer it yourself" }));
      await press(screen.getByRole("button", { name: "Answer it yourself" }));
      await landing(() => yourAnswer() !== null);

      expect(reason(yourAnswer()!)).toHaveValue("");
      expect(reason(yourAnswer()!)).not.toHaveAttribute("aria-invalid", "true");
      expect(screen.queryByRole("alert")).toBeNull();
      expect(screen.queryByText(/A reason is 1 to 2048 characters/)).toBeNull();
    });

    it("says only who answered first, and nothing of the refusal, once an answer's refusal arrives after a quiet read showed the try answered by somebody else, the form gone", async () => {
      const answering = deferred<readonly [string, number]>();
      const { sent: served } = stepOpened({
        [`GET ${STEP}`]: [stepRead(), EVE_ANSWERED, UNANSWERED],
        [`PUT ${ANSWER}`]: [answering.promise],
      });
      await pageDrawn();
      await press(screen.getByRole("button", { name: "Answer it yourself" }));
      fireEvent.change(reason(yourAnswer()!), {
        target: { value: "Read it twice." },
      });
      await press(sendButton(yourAnswer()!));
      await act(() => vi.advanceTimersByTimeAsync(5000));
      await landing(
        () => asked(served, `GET ${STEP}`) === 2 && yourAnswer() === null,
      );
      expect(screen.queryByText(/Already .* by/)).toBeNull();

      act(() => answering.settle(refusal("INTERNAL", 500)));
      await landing(() => screen.queryByText(/Already answered/) !== null);

      expect(
        screen.getAllByRole("alert").map((each) => each.textContent),
      ).toEqual([`Already answered by ${setApart("Eve")}.`]);
      expect(
        screen.queryByRole("button", { name: "Answer it yourself" }),
      ).toBeNull();
      expect(screen.queryByText(/has changed since you opened it/)).toBeNull();
      expect(sent(served)).toHaveLength(1);
    });

    it("says nothing of anybody answering first where a quiet read shows the reader's own answer before it answers, the keyboard going where the answer takes it", async () => {
      const answering = deferred<readonly [string, number]>();
      const { sent: served } = stepOpened({
        [`GET ${STEP}`]: [stepRead(), ANSWERED, UNANSWERED],
        [`PUT ${ANSWER}`]: [answering.promise],
      });
      await pageDrawn();
      await press(screen.getByRole("button", { name: "Answer it yourself" }));
      fireEvent.change(reason(yourAnswer()!), {
        target: { value: "Read it twice." },
      });
      await press(sendButton(yourAnswer()!));
      await act(() => vi.advanceTimersByTimeAsync(5000));
      await landing(
        () => asked(served, `GET ${STEP}`) === 2 && yourAnswer() === null,
      );

      await act(async () => answering.settle(ANSWERED));
      await landing(
        () =>
          document.activeElement ===
          screen.getByRole("heading", { name: "Output" }),
      );

      expect(screen.queryByRole("alert")).toBeNull();
      expect(screen.queryByText(/Already .* by/)).toBeNull();
      expect(sent(served)).toHaveLength(1);
    });
    it.each(["another person", "another try"])(
      "does not treat a successful payload for %s as completion of the reader's sent try",
      async (mismatch) => {
        const answering = deferred<readonly [string, number]>();
        const { sent: served } = stepOpened({
          [`GET ${STEP}`]: [stepRead(), EVE_ANSWERED, UNANSWERED],
          [`PUT ${ANSWER}`]: [answering.promise],
        });
        await pageDrawn();
        await press(screen.getByRole("button", { name: "Answer it yourself" }));
        fireEvent.change(reason(yourAnswer()!), {
          target: { value: "Read it twice." },
        });
        await press(sendButton(yourAnswer()!));
        await act(() => vi.advanceTimersByTimeAsync(5000));
        await landing(() => asked(served, `GET ${STEP}`) === 2);

        await act(async () =>
          answering.settle(
            mismatch === "another person"
              ? EVE_ANSWERED
              : await stepRead(
                  ANSWERED_ROW,
                  {},
                  {
                    answering: undefined,
                    triesMade: [
                      FIRST_TRY,
                      {
                        ...SECOND_TRY,
                        number: 3,
                        producedBy: { kind: "person", person: DAN },
                      },
                    ],
                  },
                ),
          ),
        );
        await landing(() => screen.queryByRole("alert") !== null);

        expect(
          screen.getAllByRole("alert").map((each) => each.textContent),
        ).toEqual([`Already answered by ${setApart("Eve")}.`]);
        expect(yourAnswer()).toBeNull();
        expect(sent(served)).toHaveLength(1);
        expect(asked(served, `GET ${STEP}`)).toBe(2);
      },
    );
    it("keeps a quiet read's later review and next try when the earlier successful answer PUT arrives", async () => {
      const answering = deferred<readonly [string, number]>();
      const reviewed = stepRead(
        {
          ...THIRD_OWED,
          producedByKind: "person",
          producedByUserId: DAN.userId,
        },
        {
          number: 3,
          lastRefused: { number: 2, values: SECOND_TRY.values },
        },
        {
          triesMade: [
            FIRST_TRY,
            { ...SECOND_TRY, producedBy: { kind: "person", person: DAN } },
          ],
          cameOut: [
            { field: "summary", now: "refused" },
            {
              field: "priority",
              standing: { number: 2, value: "low" },
              now: "stands",
            },
          ],
        },
      );
      const { sent: served } = stepOpened({
        [`GET ${STEP}`]: [stepRead(), reviewed, UNANSWERED],
        [`PUT ${ANSWER}`]: [answering.promise],
      });
      await pageDrawn();
      await press(screen.getByRole("button", { name: "Answer it yourself" }));
      fireEvent.change(reason(yourAnswer()!), {
        target: { value: "Read it twice." },
      });
      await press(sendButton(yourAnswer()!));
      await act(() => vi.advanceTimersByTimeAsync(5000));
      await landing(() => asked(served, `GET ${STEP}`) === 2);

      await act(async () => answering.settle(ANSWERED));
      await landing(
        () =>
          document.activeElement ===
          screen.getByRole("heading", { name: "Output" }),
      );

      expect(screen.queryByRole("alert")).toBeNull();
      expect(
        screen.getByRole("button", { name: "Answer it yourself" }),
      ).toBeEnabled();
      await press(screen.getByRole("button", { name: "Answer it yourself" }));
      await landing(() => yourAnswer() !== null);
      expect(reason(yourAnswer()!)).toHaveValue("");
      expect(summary(yourAnswer()!)).toHaveValue("A printer fire.");
      expect(sent(served)).toHaveLength(1);
      expect(asked(served, `GET ${STEP}`)).toBe(2);
      expect(within(yourAnswer()!).getByText("Say where.")).toBeVisible();
    });
  });
});

it("clears rejected files when a moved-on step is read for another try", async () => {
  const pending = deferred<readonly [string, number]>();
  const fileField = {
    name: "attachments",
    label: "Attachments",
    kind: "file",
    types: [".txt"],
    maximumBytes: 4,
    most: 2,
    mustBeGiven: true,
  };
  const kept = {
    id: "0000000a-0000-4000-8000-000000000d01",
    name: "Demo Sample earlier.txt",
    size: 1,
  };
  const moved = thirdOwedRead(undefined, {
    gives: [fileField],
    lastRefused: {
      number: 2,
      values: [{ field: "attachments", value: [kept], now: "refused" }],
    },
  });
  const opened = conversing({
    [`GET ${STEP}`]: [
      stepRead({}, { gives: [fileField] }),
      moved,
      moved,
      UNANSWERED,
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
  await press(sendButton(form));
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
      asked(opened.sent, `GET ${STEP}`) === 3 &&
      yourAnswer()?.textContent?.includes("Pre-filled from attempt 2.") ===
        true,
  );

  const later = yourAnswer()!;
  const laterPicker = later.querySelector('input[type="file"]')!;
  expect(later).toHaveTextContent("Pre-filled from attempt 2.");
  expect(later).toHaveTextContent("Demo Sample earlier.txt");
  expect(
    within(later).queryByText("No files from this selection were added."),
  ).toBeNull();
  expect(within(later).queryByText(/Demo Sample too large.txt/)).toBeNull();
  expect(laterPicker).not.toHaveAttribute("aria-invalid", "true");
  expect(reason(later)).toHaveValue("");

  fireEvent.change(reason(later), { target: { value: "Read the new try." } });
  await press(sendButton(later));
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
