import { ThemeProvider } from "@mui/material/styles";
import { act, fireEvent, render, screen, within } from "@testing-library/react";
import { userEvent } from "@testing-library/user-event";
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

const GROUP = "00000003-0000-4000-8000-000000000cd1";

const RUN_ID = "00000008-0000-4000-8000-000000000cd1";

const VERSION = "00000007-0000-4000-8000-000000000cd1";

const QUESTION_VERSION = "00000007-0000-4000-8000-000000000cd2";

const SUMMARISE = "00000009-0000-4000-8000-000000000cd1";

const FILE_IT = "00000009-0000-4000-8000-000000000cd2";

const PAGE = `/groups/${GROUP}/work/${RUN_ID}`;

const RUN = `/api/groups/${GROUP}/runs/${RUN_ID}`;

const STEPS = `${RUN}/steps`;

const STEP = `${STEPS}/${SUMMARISE}`;

const REVIEW = `${STEP}/tries/1/review`;

const CAT = { userId: "000cd1", displayName: "Cat" };

const DAN = { userId: "000cd2", displayName: "Dan" };

const CLAIM = {
  runId: RUN_ID,
  number: 7,
  name: "Claim from Ada",
  workflow: {
    entryId: "00000006-0000-4000-8000-000000000cd1",
    name: "Handle a claim",
    version: 3,
  },
  startedBy: { userId: "000cd3", displayName: "Ada" },
  startedAt: "2026-09-26T08:00:00Z",
  state: "running",
  at: { stepId: SUMMARISE, name: "summarise" },
  spend: { sent: "0", cameBack: "0", spent: "0", cameBackUnknown: false },
  ceiling: { raiseNeedsApproval: false },
  acts: ["stop"],
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

/** The question gives back a summary alone, or beside it a reply. */
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
  acts: ["stop"],
  progress: { done: 0, of: 2 },
};

/** The header once the review let the summary stand and the run went on to the route. */
const HEADER_ON = {
  ...HEADER,
  at: { stepId: FILE_IT, name: "file_it" },
  progress: { done: 1, of: 2 },
};

/** Long enough, and over lines, that a value is shut until opened wherever it is not reviewed. */
const WRITTEN = "A printer caught fire.\nNobody was hurt.";

const WENT_IN = [
  {
    input: "text",
    from: { kind: "run_input", path: "ticket" },
    value: "Printer on fire",
  },
];

/** Cat's first answer, its summary waiting on the reader's review. */
const WAITING_ROW = {
  stepId: SUMMARISE,
  order: 1,
  name: "summarise",
  runs: {
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
  acts: ["review"],
  withheld: [],
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

/** The run's steps as read, the first waiting on the reader, as a list of steps sends one it may review. */
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

/**
 * The step once a review decided its summary as `decided` says, its try's review as `review` says, with the run gone
 * on as `header` says. Who reviewed is not named to the reader unless `review` names them.
 */
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
      acts: [],
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

/** Assured by the reader: the summary stands, and the run goes on to the route. */
const ASSURED = reviewed({ outcome: "assured" }, "stands", HEADER_ON, DONE);

/** Refused on review, by one who may not answer: the next try is owed, and nobody has asked for it. */
const OWED = {
  where: {
    kind: "owed_try",
    open: false,
    beyond: false,
    since: "2026-09-26T09:30:00Z",
    waitsOn: "answer_step",
  },
  next: { number: 2, beyond: false },
  withheld: [
    { act: "answer", refusal: "ACT_NOT_PERMITTED" },
    { act: "ask_again", refusal: "ACT_NOT_PERMITTED" },
  ],
};

const REFUSED = reviewed(
  { outcome: "refused", why: "Too short." },
  "refused",
  HEADER,
  OWED,
);

/** Assured by Dan before the reader's review arrived. */
const ASSURED_BY_DAN = reviewed(
  { outcome: "assured" },
  "stands",
  HEADER_ON,
  DONE,
  { asked: true, by: { kind: "person", person: DAN } },
);

/** Refused by Dan before the reader's review arrived. */
const REFUSED_BY_DAN = reviewed(
  { outcome: "refused", why: "Too short." },
  "refused",
  HEADER,
  OWED,
  { asked: true, by: { kind: "person", person: DAN } },
);

/** The steps once Cat's second answer waits on the reader's review, the first refused. */
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

/** Still waiting, on a run stopped since it was read, which withholds the review from everybody. */
const STOPPED = reply({
  run: { ...HEADER, at: undefined, state: "stopped", acts: [] },
  declarations: declarations(SUMMARY),
  step: {
    ...WAITING_ROW,
    where: { ...WAITING_ROW.where, waitsOn: undefined },
    acts: [],
    withheld: [{ act: "review", refusal: "RUN_STOPPED" }],
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

/** Cat's first try, its summary waiting on review, as the step's own page lists it. */
const FIRST_TRY_WAITING = {
  number: 1,
  beyond: false,
  producedBy: { kind: "person", person: CAT },
  values: [{ field: "summary", value: WRITTEN, now: "waiting_on_review" }],
  review: { asked: true },
  ended: "waiting",
  cost: { callsAModel: false },
};

/** The step's own page while its summary waits on the reader's review. */
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

/** The step's own page once Dan refused the first try and Cat's second waits on the reader's review. */
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
          decision: { outcome: "refused", why: "Too short." },
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

/** Still waiting, now on the model the step names to review, which withholds the review from every person. */
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
    acts: [],
    withheld: [{ act: "review", refusal: "REVIEW_NOT_A_PERSONS" }],
  },
  wentIn: WENT_IN,
  wentInFrom: "try",
  cameOut: [{ field: "summary", now: "waiting_on_review" }],
  triesMade: [FIRST_TRY_WAITING],
});

/** A reply the server never sends. */
const UNANSWERED: Reply = new Promise(() => {});

const REASON_UNUSABLE =
  "A reason is prose of one to 2048 characters, with something in it that shows.";

/** What is said where the review is refused for the act itself: the rule of who may review a step. */
const REVIEW_RULE =
  "A group's steps are reviewed, what they give back assured or refused, only by a role in it that may review them.";

function reply(body: object): Reply {
  return [JSON.stringify(body), 200];
}

function refusal(code: string, status: number): Reply {
  return [JSON.stringify({ code }), status];
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
    [inGroup(GROUP, "CLAIMS", "Claims", ["review_at_gate"])],
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
  return opening(
    {
      [`GET ${RUN}`]: [reply(CLAIM)],
      [`GET ${STEPS}`]: [stepsRead()],
      ...routes,
    },
    "/groups/:groupId/work/:runId",
    <RunPage drawing="conversation" onDetail={() => {}} onActed={() => {}} />,
    PAGE,
  );
}

function stepOpened(routes: Readonly<Record<string, readonly Reply[]>>) {
  return opening(
    routes,
    "/groups/:groupId/work/:runId/steps/:stepId",
    <StepPage onActed={() => {}} />,
    `${PAGE}/steps/${SUMMARISE}`,
  );
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

function reviews(sent: ReturnType<typeof serving>): RequestInit[] {
  return sent.mock.calls
    .filter(([, init]) => init?.method === "PUT")
    .map(([, init]) => init!);
}

function asked(sent: ReturnType<typeof serving>, request: string): number {
  return requestsTo(sent).filter((each) => each === request).length;
}

function reason(drawn: HTMLElement): HTMLElement {
  return within(drawn).getByRole("textbox", { name: /^Why you refuse/ });
}

beforeEach(() => {
  vi.useFakeTimers({ toFake: ["setTimeout", "clearTimeout"] });
});

afterEach(() => {
  vi.useRealTimers();
});

describe("ReviewControls", () => {
  describe("in a run's conversation", () => {
    it("lays out what went in and the value waiting in full the moment its message is drawn, beside Assure and Refuse named after the value", async () => {
      conversing();

      const drawn = await messageDrawn();

      const wentIn = within(drawn).getByRole("region", {
        name: "What went in",
      });
      expect(within(wentIn).getByRole("term")).toHaveTextContent(
        setApart("Text"),
      );
      expect(within(wentIn).getByRole("definition")).toHaveTextContent(
        `${setApart("Ticket")}, which the run was started with` +
          "Printer on fire",
      );
      const review = within(drawn).getByRole("region", { name: "Your review" });
      expect(review).toHaveTextContent(
        "A printer caught fire. Nobody was hurt.",
      );
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
      ).toEqual(["Assure", "Refuse"]);
      expect(within(review).queryByRole("textbox")).toBeNull();
      expect(
        within(review).queryByRole("button", { name: "Submit this review" }),
      ).toBeNull();
    });

    it("draws in full, and once, what came out that stands without review beside the value waiting, offering a choice for the value waiting alone", async () => {
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

      const review = within(drawn).getByRole("region", { name: "Your review" });
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
      expect(within(drawn).getAllByText("A fire.")).toHaveLength(1);
    });

    it("draws neither, nor what went in, where the step withholds the review from the reader, and keeps a long value shut", async () => {
      conversing({
        [`GET ${STEPS}`]: [
          stepsRead({
            acts: [],
            withheld: [{ act: "review", refusal: "ACT_NOT_PERMITTED" }],
            wentIn: undefined,
            wentInFrom: undefined,
          }),
        ],
      });

      const drawn = await messageDrawn();

      expect(within(drawn).getByText("You may not review it.")).toBeVisible();
      expect(
        within(drawn).getByRole("button", {
          name: `${setApart("Summary")}, in full`,
        }),
      ).toHaveAttribute("aria-expanded", "false");
      expect(
        within(drawn).queryByRole("region", { name: "What went in" }),
      ).toBeNull();
      expect(
        within(drawn).queryByRole("region", { name: "Your review" }),
      ).toBeNull();
      expect(
        within(drawn).queryByRole("button", { name: "Assure" }),
      ).toBeNull();
      expect(
        within(drawn).queryByRole("button", { name: "Refuse" }),
      ).toBeNull();
    });

    it("assures a lone value at a press, sending that decision alone, then shows the step as it answers, reads the run again and gives the keyboard to its message", async () => {
      const { sent } = conversing({
        [`GET ${RUN}`]: [reply(CLAIM), UNANSWERED],
        [`GET ${STEPS}`]: [stepsRead(), UNANSWERED],
        [`PUT ${REVIEW}`]: [ASSURED],
      });
      const drawn = await messageDrawn();

      press(within(drawn).getByRole("button", { name: "Assure" }));
      await landing(() => within(message()).queryByText("Done") !== null);

      expect(reviews(sent).map((init) => init.body)).toEqual([
        JSON.stringify({ decisions: { summary: { outcome: "assured" } } }),
      ]);
      expect(within(message()).getByText("It stands.")).toBeVisible();
      expect(screen.getByText("1 of 2 steps done")).toBeVisible();
      expect(asked(sent, `GET ${RUN}`)).toBe(2);
      expect(document.activeElement).toBe(message());
      expect(
        within(message()).queryByRole("region", { name: "Your review" }),
      ).toBeNull();
      expect(screen.queryByRole("alert")).toBeNull();
    });

    it("asks why before refusing a lone value, sending nothing until a usable reason is given, and then sends that reason", async () => {
      const { sent } = conversing({
        [`GET ${STEPS}`]: [stepsRead(), UNANSWERED],
        [`PUT ${REVIEW}`]: [REFUSED],
      });
      const drawn = await messageDrawn();
      const refuse = within(drawn).getByRole("button", { name: "Refuse" });

      press(refuse);

      const why = reason(drawn);
      expect(refuse).toHaveAttribute("aria-pressed", "true");
      expect(document.activeElement).toBe(why);
      const submit = within(drawn).getByRole("button", {
        name: "Submit this review",
      });
      expect(submit).toHaveAttribute("aria-disabled", "true");
      press(submit);
      await act(() => vi.advanceTimersByTimeAsync(0));
      expect(reviews(sent)).toEqual([]);

      fireEvent.change(why, { target: { value: "Too short." } });
      expect(submit).not.toHaveAttribute("aria-disabled");
      press(submit);
      await landing(() => reviews(sent).length === 1);

      expect(reviews(sent)[0]!.body).toBe(
        JSON.stringify({
          decisions: { summary: { outcome: "refused", why: "Too short." } },
        }),
      );
    });

    it("says Submit is held on why the lone value is refused, and not on a choice for each value", async () => {
      conversing();
      const drawn = await messageDrawn();

      press(within(drawn).getByRole("button", { name: "Refuse" }));

      const submit = within(drawn).getByRole("button", {
        name: "Submit this review",
      });
      expect(submit).toHaveAccessibleDescription("Say why you refuse it.");
      expect(submit).toHaveAttribute("aria-disabled", "true");
      expect(within(drawn).queryByText(/for each value/)).toBeNull();
    });

    it.each([
      ["showing nothing", " \t\n ", "A refusal, and an answer, say why."],
      [
        "holding a tag character",
        `No${String.fromCodePoint(0xe0041)}`,
        "What was written holds a tag character, which shows nothing and which text sent to a model may not hold.",
      ],
      [
        "holding a direction control",
        `No${String.fromCodePoint(0x202e)}`,
        "What was written holds a direction control, which text sent to a model may not hold.",
      ],
      [
        "holding a direction control and nothing that shows, which is refused for the control before being taken as none",
        String.fromCodePoint(0x202e),
        "What was written holds a direction control, which text sent to a model may not hold.",
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
        press(within(drawn).getByRole("button", { name: "Refuse" }));
        const why = reason(drawn);

        fireEvent.change(why, { target: { value: typed } });

        expect(why).toHaveAccessibleDescription(words);
        expect(why).toHaveAttribute("aria-invalid", "true");
        const submit = within(drawn).getByRole("button", {
          name: "Submit this review",
        });
        expect(submit).toHaveAttribute("aria-disabled", "true");
        press(submit);
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
        press(within(drawn).getByRole("button", { name: "Refuse" }));
        const why = reason(drawn);

        fireEvent.change(why, { target: { value: typed } });

        expect(why).toHaveAttribute("aria-invalid", "false");
        expect(why).not.toHaveAccessibleDescription();
        expect(
          within(drawn).getByRole("button", { name: "Submit this review" }),
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
      const drawn = await messageDrawn();

      press(within(drawn).getByRole("button", { name: "Assure" }));
      await landing(() => screen.queryByRole("alert") !== null);

      const said = screen.getByRole("alert");
      expect(said).toHaveTextContent(
        `This was reviewed already, by ${setApart("Dan")}.`,
      );
      expect(document.activeElement).toContainElement(said);
      expect(asked(sent, `GET ${STEP}`)).toBe(1);
      expect(asked(sent, `GET ${RUN}`)).toBe(2);
      expect(within(message()).getByText("Done")).toBeVisible();
      expect(
        within(message()).queryByRole("button", { name: "Assure" }),
      ).toBeNull();
      expect(
        screen.queryByText("That step has moved on since it was read."),
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
        const drawn = await messageDrawn();

        press(within(drawn).getByRole("button", { name: "Assure" }));
        await landing(() => screen.queryByRole("alert") !== null);

        const said = screen.getByRole("alert");
        expect(said).toHaveTextContent(
          "That step has moved on since it was read.",
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
      const drawn = await messageDrawn();

      press(within(drawn).getByRole("button", { name: "Assure" }));
      await landing(
        () => within(message()).queryByText("A longer summary.") !== null,
      );

      const said = screen.getByRole("alert");
      expect(said).toHaveTextContent(
        `This was reviewed already, by ${setApart("Dan")}.`,
      );
      expect(document.activeElement).toContainElement(said);
      expect(requestsTo(sent).filter((each) => each.startsWith("PUT"))).toEqual(
        [`PUT ${REVIEW}`],
      );
      expect(
        screen.queryByText("That step has moved on since it was read."),
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
      const drawn = await messageDrawn();
      press(within(drawn).getByRole("button", { name: "Assure" }));
      await landing(() => screen.queryByRole("alert") !== null);
      expect(screen.getByRole("alert")).toHaveTextContent(
        `This was reviewed already, by ${setApart("Dan")}.`,
      );

      settle(SECOND_WAITING as readonly [string, number]);
      await landing(
        () => within(message()).queryByText("A longer summary.") !== null,
      );

      expect(screen.queryByRole("alert")).toBeNull();
      expect(screen.queryByText(/reviewed already/)).toBeNull();
      expect(
        within(message()).getByRole("button", { name: "Assure" }),
      ).not.toHaveAttribute("aria-disabled");
    });

    it("marks nothing at the reason for another try that waits, where the server refused the reason given for the one before", async () => {
      const { sent } = conversing({
        [`GET ${STEPS}`]: [stepsRead(), SECOND_WAITING],
        [`PUT ${REVIEW}`]: [refusal("REASON_UNUSABLE", 400)],
      });
      const drawn = await messageDrawn();
      press(within(drawn).getByRole("button", { name: "Refuse" }));
      fireEvent.change(reason(drawn), { target: { value: "Too short." } });
      press(within(drawn).getByRole("button", { name: "Submit this review" }));
      await landing(
        () => reason(message()).getAttribute("aria-invalid") === "true",
      );

      await act(() => vi.advanceTimersByTimeAsync(5000));
      await landing(
        () => within(message()).queryByText("A longer summary.") !== null,
      );
      press(within(message()).getByRole("button", { name: "Refuse" }));

      const why = reason(message());
      expect(why).toHaveValue("");
      expect(why).toHaveAttribute("aria-invalid", "false");
      expect(why).not.toHaveAccessibleDescription();
      expect(screen.queryByRole("alert")).toBeNull();
      expect(asked(sent, `GET ${STEPS}`)).toBe(2);
    });

    it("sends Assure once while the first is out, holding it until that answers", async () => {
      const { sent } = conversing({ [`PUT ${REVIEW}`]: [UNANSWERED] });
      const drawn = await messageDrawn();
      const assure = within(drawn).getByRole("button", { name: "Assure" });

      press(assure);
      await landing(() => reviews(sent).length === 1);
      press(assure);
      await act(() => vi.advanceTimersByTimeAsync(0));

      expect(reviews(sent)).toHaveLength(1);
      expect(assure).toHaveAttribute("aria-disabled", "true");
      expect(
        within(message()).getByRole("region", { name: "Your review" }),
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
      const drawn = await messageDrawn();
      await act(() => vi.advanceTimersByTimeAsync(5000));
      await landing(() => asked(sent, `GET ${STEPS}`) === 2);

      press(within(drawn).getByRole("button", { name: "Assure" }));
      await landing(() => within(message()).queryByText("Done") !== null);
      settle(stepsRead() as readonly [string, number]);
      await act(() => vi.advanceTimersByTimeAsync(0));

      expect(within(message()).getByText("Done")).toBeVisible();
      expect(
        within(message()).queryByRole("region", { name: "Your review" }),
      ).toBeNull();
      expect(
        within(message()).queryByRole("button", { name: "Assure" }),
      ).toBeNull();
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
      const drawn = await messageDrawn();
      // The page's own region comes first.
      const news = screen.getAllByRole("status")[0]!;

      press(within(drawn).getByRole("button", { name: "Assure" }));
      await landing(() => asked(sent, `GET ${RUN}`) === 2);
      await act(() => vi.advanceTimersByTimeAsync(0));
      await act(() => vi.advanceTimersByTimeAsync(0));

      expect(news).toHaveTextContent(`${setApart("Summarise")}: now Done.`);
      expect(news).not.toHaveTextContent("Claim from Ada");
    });

    it("says a stopped run takes no review, and reads the step again, which offers none", async () => {
      const { sent } = conversing({
        [`GET ${RUN}`]: [reply(CLAIM), UNANSWERED],
        [`GET ${STEPS}`]: [stepsRead(), UNANSWERED],
        [`PUT ${REVIEW}`]: [refusal("RUN_STOPPED", 409)],
        [`GET ${STEP}`]: [STOPPED],
      });
      const drawn = await messageDrawn();

      press(within(drawn).getByRole("button", { name: "Assure" }));
      await landing(() => asked(sent, `GET ${STEP}`) === 1);
      await landing(
        () =>
          within(message()).queryByRole("button", { name: "Assure" }) === null,
      );

      const said = screen.getByRole("alert");
      expect(said).toHaveTextContent(
        "A stopped run takes no answer, review or try until it is opened again.",
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
      const drawn = await messageDrawn();

      press(within(drawn).getByRole("button", { name: "Assure" }));
      await landing(
        () =>
          within(message()).queryByRole("button", { name: "Assure" }) === null,
      );

      const said = screen.getByRole("alert");
      expect(said).toHaveTextContent(
        "Those values wait on the model the step names to review.",
      );
      expect(document.activeElement).toContainElement(said);
      expect(asked(sent, `GET ${STEP}`)).toBe(1);
      expect(reviews(sent)).toHaveLength(1);
      expect(readAgain).not.toHaveBeenCalled();
      expect(
        within(message()).queryByRole("region", { name: "Your review" }),
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
      press(control);
      const details = () =>
        document.getElementById(control.getAttribute("aria-controls")!)!;
      await landing(
        () =>
          within(details()).queryByText("Nobody has reviewed it yet.") !== null,
      );

      press(within(drawn).getByRole("button", { name: "Assure" }));
      await landing(() => asked(sent, `GET ${STEP}`) === 2);
      await landing(() => within(details()).queryByText("It stands.") !== null);

      expect(within(details()).getByText("A person")).toBeVisible();
      expect(
        within(details()).queryByText("Nobody has reviewed it yet."),
      ).toBeNull();
      expect(within(details()).queryByText("It waits on review.")).toBeNull();
    });

    it("marks at its field a reason the server refuses, giving the field the keyboard and saying nothing else, until it is changed", async () => {
      const { sent } = conversing({
        [`PUT ${REVIEW}`]: [refusal("REASON_UNUSABLE", 400)],
      });
      const drawn = await messageDrawn();
      press(within(drawn).getByRole("button", { name: "Refuse" }));
      const why = reason(drawn);
      fireEvent.change(why, { target: { value: "Too short." } });

      press(within(drawn).getByRole("button", { name: "Submit this review" }));
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
      const drawn = await messageDrawn();

      press(within(drawn).getByRole("button", { name: "Assure" }));
      await landing(() => screen.queryByRole("alert") !== null);

      const said = screen.getByRole("alert");
      expect(said).toHaveTextContent(REVIEW_RULE);
      expect(said).not.toHaveTextContent(
        "This is done only by a role that reaches it.",
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
            acts: [],
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
      const drawn = await messageDrawn();
      press(within(drawn).getByRole("button", { name: "Assure" }));
      await landing(() => document.activeElement === message());
      const more = screen.getByRole("button", { name: "More" });
      act(() => more.focus());

      await act(() => vi.advanceTimersByTimeAsync(5000));
      await landing(() => asked(sent, `GET ${STEPS}`) >= 3);
      await act(() => vi.advanceTimersByTimeAsync(0));

      expect(document.activeElement).toBe(more);
    });
  });

  describe("on the step's own page", () => {
    /** Cat's first answer, its summary and its reply both waiting on the reader's review. */
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

    /** The summary assured and the reply refused, in one review: the next try is owed. */
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
        acts: [],
        withheld: [
          { act: "answer", refusal: "ACT_NOT_PERMITTED" },
          { act: "ask_again", refusal: "ACT_NOT_PERMITTED" },
        ],
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
              decision: { outcome: "refused", why: "Too curt." },
            },
          ],
          review: { asked: true, by: { kind: "person" } },
          ended: "refused_on_review",
          cost: { callsAModel: false },
        },
      ],
    });

    async function pageDrawn(): Promise<HTMLElement> {
      await landing(() => screen.queryByRole("heading", { level: 2 }) !== null);
      return screen.getByRole("region", { name: "Your review" });
    }

    function choice(review: HTMLElement, label: string): HTMLElement {
      return within(review).getByRole("radiogroup", { name: setApart(label) });
    }

    it("draws each value waiting in full beside its own choice, and sends nothing until every value is chosen and every refusal says why", async () => {
      const { sent } = stepOpened({
        [`GET ${STEP}`]: [twoWaiting()],
        [`PUT ${REVIEW}`]: [DECIDED],
      });
      const review = await pageDrawn();
      const submit = within(review).getByRole("button", {
        name: "Submit this review",
      });

      expect(review).toHaveTextContent("A fire.");
      expect(review).toHaveTextContent(
        "A printer caught fire. Nobody was hurt.",
      );
      expect(
        within(review).queryByRole("button", { name: /in full/ }),
      ).toBeNull();
      for (const label of ["Summary", "Reply"]) {
        const radios = within(choice(review, label)).getAllByRole("radio");
        expect(radios.map((each) => each.getAttribute("value"))).toEqual([
          "assured",
          "refused",
        ]);
        expect(
          radios.filter((each) => (each as HTMLInputElement).checked),
        ).toEqual([]);
      }
      expect(submit).toHaveAttribute("aria-disabled", "true");

      press(
        within(choice(review, "Summary")).getByRole("radio", {
          name: "Assure",
        }),
      );
      expect(submit).toHaveAttribute("aria-disabled", "true");
      press(
        within(choice(review, "Reply")).getByRole("radio", { name: "Refuse" }),
      );

      const why = within(review).getByRole("textbox", {
        name: `Why you refuse ${setApart("Reply")}`,
      });
      expect(within(review).getAllByRole("textbox")).toEqual([why]);
      expect(submit).toHaveAttribute("aria-disabled", "true");
      press(submit);
      await act(() => vi.advanceTimersByTimeAsync(0));
      expect(reviews(sent)).toEqual([]);
    });

    it("sends one review deciding every value its own way, then shows the step as it answers, reads it again and gives the keyboard to what came out", async () => {
      const { sent } = stepOpened({
        [`GET ${STEP}`]: [twoWaiting(), UNANSWERED],
        [`PUT ${REVIEW}`]: [DECIDED],
      });
      const review = await pageDrawn();
      press(
        within(choice(review, "Summary")).getByRole("radio", {
          name: "Assure",
        }),
      );
      press(
        within(choice(review, "Reply")).getByRole("radio", { name: "Refuse" }),
      );
      fireEvent.change(within(review).getByRole("textbox"), {
        target: { value: "Too curt." },
      });

      press(within(review).getByRole("button", { name: "Submit this review" }));
      await landing(
        () => screen.queryByRole("region", { name: "Your review" }) === null,
      );

      expect(reviews(sent).map((init) => init.body)).toEqual([
        JSON.stringify({
          decisions: {
            summary: { outcome: "assured" },
            reply: { outcome: "refused", why: "Too curt." },
          },
        }),
      ]);
      const cameOut = screen.getByRole("region", { name: "What came out" });
      expect(cameOut).toHaveTextContent("A fire.It stands.");
      expect(cameOut).toHaveTextContent("Refused on review.");
      expect(document.activeElement).toBe(
        within(cameOut).getByRole("heading", { name: "What came out" }),
      );
      expect(screen.queryByRole("radiogroup")).toBeNull();
      expect(screen.queryByRole("alert")).toBeNull();
      expect(asked(sent, `GET ${STEP}`)).toBe(2);
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
      press(
        within(choice(review, "Summary")).getByRole("radio", {
          name: "Assure",
        }),
      );
      press(
        within(choice(review, "Reply")).getByRole("radio", { name: "Refuse" }),
      );
      fireEvent.change(within(review).getByRole("textbox"), {
        target: { value: "Too curt." },
      });

      press(within(review).getByRole("button", { name: "Submit this review" }));
      await landing(
        () => screen.queryByRole("region", { name: "Your review" }) === null,
      );
      settle(twoWaiting() as readonly [string, number]);
      await act(() => vi.advanceTimersByTimeAsync(0));

      expect(
        screen.getByRole("region", { name: "What came out" }),
      ).toHaveTextContent("Refused on review.");
      expect(screen.queryByRole("region", { name: "Your review" })).toBeNull();
      expect(screen.queryByRole("radiogroup")).toBeNull();
      expect(asked(sent, `GET ${STEP}`)).toBe(3);
    });

    it("keeps what was chosen and the reason half typed where the step is read again meanwhile", async () => {
      const { sent } = stepOpened({ [`GET ${STEP}`]: [twoWaiting()] });
      const review = await pageDrawn();
      press(
        within(choice(review, "Summary")).getByRole("radio", {
          name: "Assure",
        }),
      );
      press(
        within(choice(review, "Reply")).getByRole("radio", { name: "Refuse" }),
      );
      fireEvent.change(within(review).getByRole("textbox"), {
        target: { value: "Too cu" },
      });

      await act(() => vi.advanceTimersByTimeAsync(5000));
      await landing(() => asked(sent, `GET ${STEP}`) === 2);
      await act(() => vi.advanceTimersByTimeAsync(0));

      const again = screen.getByRole("region", { name: "Your review" });
      const checked = (label: string) =>
        within(choice(again, label))
          .getAllByRole("radio")
          .filter((each) => (each as HTMLInputElement).checked)
          .map((each) => each.getAttribute("value"));
      expect(checked("Summary")).toEqual(["assured"]);
      expect(checked("Reply")).toEqual(["refused"]);
      expect(within(again).getByRole("textbox")).toHaveValue("Too cu");
      expect(screen.queryByRole("alert")).toBeNull();
    });

    it("keeps the keyboard on Refuse where arrow keys move onto it, drawing the reason after it", async () => {
      stepOpened({ [`GET ${STEP}`]: [twoWaiting()] });
      const review = await pageDrawn();
      // Testing Library wraps every user-event call in a wait on a timeout of 0 that it advances only where `jest`
      // is defined, so faked timeouts hang it whatever `advanceTimers` says.
      vi.useRealTimers();
      const user = userEvent.setup();
      await user.click(
        within(choice(review, "Reply")).getByRole("radio", { name: "Assure" }),
      );

      await user.keyboard("{ArrowRight}");

      const refuse = within(choice(review, "Reply")).getByRole("radio", {
        name: "Refuse",
      });
      expect(refuse).toBeChecked();
      expect(document.activeElement).toBe(refuse);
      const why = within(review).getByRole("textbox", {
        name: `Why you refuse ${setApart("Reply")}`,
      });
      expect(
        refuse.compareDocumentPosition(why) & Node.DOCUMENT_POSITION_FOLLOWING,
      ).toBeTruthy();
    });

    it("says why Submit is held until every value is chosen and every refusal says why, and nothing once it is not", async () => {
      const held =
        "Choose Assure or Refuse for each value, and say why for each one refused.";
      stepOpened({ [`GET ${STEP}`]: [twoWaiting()] });
      const review = await pageDrawn();
      const submit = within(review).getByRole("button", {
        name: "Submit this review",
      });
      expect(submit).toHaveAccessibleDescription(held);

      press(
        within(choice(review, "Summary")).getByRole("radio", {
          name: "Assure",
        }),
      );
      press(
        within(choice(review, "Reply")).getByRole("radio", { name: "Refuse" }),
      );
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
            acts: [],
            withheld: [{ act: "review", refusal: "REVIEW_OWN_PRODUCTION" }],
          }),
        ],
      });
      await landing(() => screen.queryByRole("heading", { level: 2 }) !== null);

      expect(
        screen.getByRole("region", { name: "What went in" }),
      ).toBeVisible();
      expect(screen.queryByRole("region", { name: "Your review" })).toBeNull();
      expect(screen.queryByRole("radiogroup")).toBeNull();
      expect(
        screen.queryByRole("button", { name: "Submit this review" }),
      ).toBeNull();
    });
  });
});
