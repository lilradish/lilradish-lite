import { ThemeProvider } from "@mui/material/styles";
import { act, render, screen, within } from "@testing-library/react";
import { userEvent } from "@testing-library/user-event";
import { RouterProvider, createMemoryRouter } from "react-router";
import { afterEach, describe, expect, it, vi } from "vitest";

import { StandingProvider } from "../../../app/standing/StandingContext";
import { theme } from "../../../lib/theme/theme";
import { whenText } from "../../../lib/time/When";
import { requestsTo, serving, type Reply } from "../../../testutil/answering";
import { laidOutAt } from "../../../testutil/layout";
import { answered, inGroup } from "../../../testutil/standingRead";
import { WITHIN_THE_RUN } from "../runAddress";
import { StepPage } from "./StepPage";

const GROUP = "00000003-0000-4000-8000-000000000ca1";

const RUN_ID = "00000008-0000-4000-8000-000000000ca1";

const STEP_ID = "00000009-0000-4000-8000-000000000ca1";

const ENTRY = "00000006-0000-4000-8000-000000000ca1";

const VERSION = "00000007-0000-4000-8000-000000000ca1";

const QUESTION_VERSION = "00000007-0000-4000-8000-000000000ca2";

const PAGE = `/groups/${GROUP}/work/${RUN_ID}/steps/${STEP_ID}`;

const STEP = `/api/groups/${GROUP}/runs/${RUN_ID}/steps/${STEP_ID}`;

const CAT = { userId: "000ca1", displayName: "Cat" };

const DAN = { userId: "000ca2", displayName: "Dan" };

const UNKNOWN_SAID = "Not something this page can say yet.";

const SOME_MEASURED =
  "Some or all of it is as this system measured it, where a model did not say what it counted.";

const HEADER = {
  runId: RUN_ID,
  number: 7,
  versionId: VERSION,
  state: "running",
  at: { stepId: STEP_ID, name: "summarise" },
  acts: [],
  progress: { done: 1, of: 3 },
};

/** A question's second try, waiting on review at a gate. */
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

/** Cat's first answer, refused by Dan. */
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

/** Cat's second answer, which nobody has reviewed yet. */
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

/** A model's first answer, 87 sure of its summary, which Dan refused, and what the call spent. */
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

/** Read while its run runs, so it names how long after it to read the step again. */
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

/** The same step giving back a reply beside its summary, each try giving both. */
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

/** A model's step: its first try 87 sure of its summary and refused by Dan, its second asked for by Dan and waiting. */
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

/** A code step's try that went wrong, its code giving back what did not fit. */
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

/** A code step's try its code is running. */
const RUNNING_CODE_TRY = {
  number: 2,
  beyond: false,
  producedBy: { kind: "code" },
  values: [],
  review: { asked: false },
  ended: "open",
  cost: { callsAModel: false },
};

/** A code step whose first try went wrong and whose second is running; it gives nothing this page declares. */
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

/** Twice the model would not take a call for it: the first time saying why, and each time it was sent again. */
const TURNED_AWAY = [
  {
    at: "2026-09-26T08:58:00Z",
    said: "Too many asks.",
    cut: false,
    sentAgain: true,
  },
  { at: "2026-09-26T08:59:00Z", sentAgain: true },
];

/** A model's answer cut short at the most it may give back, sent once it was turned away; what it spent measured here. */
const MISFIT_TRY = {
  number: 1,
  beyond: false,
  producedBy: SURE_TRY.producedBy,
  values: [],
  review: { asked: false },
  ended: "did_not_fit",
  didNotFit: "cut_off",
  turnedAway: TURNED_AWAY,
  cost: { ...SURE_TRY.cost, measuredHere: true },
};

/** A model's step whose first answer did not fit and whose second waits on review, part of its spend measured here. */
const MODEL_MISFIT = {
  ...MODEL_WAITING,
  step: {
    ...MODEL_WAITING.step,
    cost: { ...MODEL_WAITING.step.cost, measuredHere: true },
  },
  triesMade: [MISFIT_TRY, MODEL_WAITING.triesMade[1]],
};

/** A model's step held back as the model would take none of its sends, its one try never sent. */
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
    tries: { current: 1, declared: 2, beyond: false },
    withheld: [{ act: "try_sending", refusal: "ACT_NOT_PERMITTED" }],
  },
  triesMade: [
    {
      ...MISFIT_TRY,
      ended: "open",
      didNotFit: undefined,
      turnedAway: [{ ...TURNED_AWAY[1]!, sentAgain: false }],
      cost: {
        callsAModel: true,
        sent: "0",
        cameBack: "0",
        spent: "0",
        cameBackUnknown: false,
      },
    },
  ],
};

/** The same step held back on its second try, its first sent after being turned away twice and not fitting. */
const HELD_AGAIN = {
  ...HELD_TURNED_AWAY,
  step: {
    ...HELD_TURNED_AWAY.step,
    where: {
      ...HELD_TURNED_AWAY.step.where,
      since: "2026-09-26T09:05:00Z",
      turnedAway: [{ at: "2026-09-26T09:05:00Z", sentAgain: false }],
    },
    tries: { current: 2, declared: 2, beyond: false },
  },
  triesMade: [
    MISFIT_TRY,
    {
      ...HELD_TURNED_AWAY.triesMade[0]!,
      number: 2,
      turnedAway: [{ at: "2026-09-26T09:05:00Z", sentAgain: false }],
    },
  ],
};

function reply(page: object): Reply {
  return [JSON.stringify(page), 200];
}

/** The page at its address under a real router, with the standing it would be handed and only the server stood in for. */
function opening(
  pages: readonly Reply[],
  address:
    string | { pathname: string; search?: string; state?: unknown } = PAGE,
) {
  laidOutAt(0, 1200);
  const sent = serving({ [`GET ${STEP}`]: pages });
  const router = createMemoryRouter(
    [
      {
        path: "/groups/:groupId/work/:runId/steps/:stepId",
        element: <StepPage onActed={() => {}} />,
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
  return screen.findByRole("heading", { level: 2 });
}

/** The labels of a list of fields beside what each says, in the order drawn; the header's where none is named. */
function header(
  list: Element = document.querySelector("dl")!,
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

/** Each try's cells, from the list the tries are picked in. */
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
  it("gives the way back to the run first, keeping the list's address and leaving what this page picked", async () => {
    opening([reply(WAITING)], `${PAGE}?sort=run&value=summary&try=1`);

    await screen.findByRole("dialog");
    const title = screen.getByRole("heading", { level: 2, hidden: true });

    const back = screen.getByRole("link", {
      name: "Back to the run",
      hidden: true,
    });
    expect(back).toHaveAttribute(
      "href",
      `/groups/${GROUP}/work/${RUN_ID}?sort=run`,
    );
    expect(
      back.compareDocumentPosition(title) & Node.DOCUMENT_POSITION_FOLLOWING,
    ).toBeTruthy();
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
    expect(header()).toEqual({
      Run: "Run 7",
      Order: "2 of 3",
      "What it runs": `${setApart("Summarise")}, version 2`,
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
  });

  it("says under the header why an act the step holds back from the reader is held back, as the step's message in the run does", async () => {
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
      document.querySelector("dl")!.compareDocumentPosition(said) &
        Node.DOCUMENT_POSITION_FOLLOWING,
    ).toBeTruthy();
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

  it("titles a step running a route by its own name, underscores read as spaces, and says it produces nothing and nobody reviews it", async () => {
    const { producer: _producer, reviewer: _reviewer, ...unreviewed } = ROW;
    opening([
      reply({
        ...WAITING,
        step: { ...unreviewed, name: "file_it", runs: { kind: "route" } },
      }),
    ]);

    expect(await heading()).toHaveTextContent(setApart("file it"));
    expect(header()).toMatchObject({
      "What it runs": "A route to the steps after it",
      "Produced by": "Nothing: it produces nothing of its own.",
      "Reviewed by": "Nobody reviews it.",
    });
    expect(screen.queryByRole("link", { name: /Summarise/ })).toBeNull();
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
    expect(opened).toHaveTextContent(`Refused: ${setApart("Too short.")}`);
    expect(opened).toHaveTextContent("The run itself");
    expect(opened).not.toHaveTextContent("A printer caught fire.");
    expect(router.state.location.search).toBe("?sort=run&value=summary&try=1");
  });

  it("says in a model's try opened how sure it was of the value picked, beneath where that value stands", async () => {
    opening([reply(MODEL_WAITING)], `${PAGE}?try=1`);

    const opened = await screen.findByRole("dialog", {
      name: "Try 1 of the 2 it allows",
    });

    const said = header(opened.querySelector("dl")!);
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
      `${setApart("The reference it gave back is not text.")} (cut short here)`,
    );
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

    expect(header(opened.querySelector("dl")!)["How it ended"]).toBe(ended);
    expect(opened).not.toHaveTextContent("cut_off");
  });

  it("lists each time a try's call was turned away before it was sent, right after how it ended, none of them a try", async () => {
    opening([reply(MODEL_MISFIT)], `${PAGE}?try=1`);

    const opened = await screen.findByRole("dialog", {
      name: "Try 1 of the 2 it allows",
    });

    const said = header(opened.querySelector("dl")!);
    const labels = Object.keys(said);
    expect(labels.indexOf("Times the model would not take it")).toBe(
      labels.indexOf("How it ended") + 1,
    );
    expect(said["Times the model would not take it"]).toBe(
      [
        `Turned away ${whenText("2026-09-26T08:58:00Z")}, and sent again by itself.`,
        `The model said: ${setApart("Too many asks.")}`,
        `Turned away ${whenText("2026-09-26T08:59:00Z")}, and sent again by itself.`,
        "None of these is a try, and none of them cost anything.",
      ].join(""),
    );
  });

  it("says each time a step held back was turned away beneath what holds it, and never again in its try, which was never sent", async () => {
    opening([reply(HELD_TURNED_AWAY)], `${PAGE}?try=1`);
    const opened = await screen.findByRole("dialog", {
      name: "Try 1 of the 2 it allows",
    });

    const held = screen.getByText(
      "The model it names would not take it. Somebody who may start a run can try sending it.",
    );
    expect(held).toHaveTextContent(
      `Turned away ${whenText("2026-09-26T08:59:00Z")}.`,
    );
    expect(held).not.toHaveTextContent("It waits on");
    expect(
      held.compareDocumentPosition(document.querySelector("dl")!) &
        Node.DOCUMENT_POSITION_FOLLOWING,
    ).toBeTruthy();
    expect(
      within(opened).queryByText("Times the model would not take it"),
    ).toBeNull();
    expect(opened).not.toHaveTextContent("Turned away");
  });

  it.each([
    [
      "the try sent before it, in its own try",
      1,
      [
        `Turned away ${whenText("2026-09-26T08:58:00Z")}, and sent again by itself.`,
        `The model said: ${setApart("Too many asks.")}`,
        `Turned away ${whenText("2026-09-26T08:59:00Z")}, and sent again by itself.`,
        "None of these is a try, and none of them cost anything.",
      ].join(""),
    ],
    ["the try the hold holds, nowhere but with the hold", 2, undefined],
  ])(
    "says of a step held back on its newest try each time %s was turned away",
    async (_case, number, inTheTry) => {
      opening([reply(HELD_AGAIN)], `${PAGE}?try=${number}`);
      const opened = await screen.findByRole("dialog", {
        name: `Try ${number} of the 2 it allows`,
      });

      const held = screen.getByText(/^The model it names would not take it\./);
      expect(held).toHaveTextContent(
        `Turned away ${whenText("2026-09-26T09:05:00Z")}.`,
      );
      expect(held).not.toHaveTextContent(whenText("2026-09-26T08:58:00Z"));
      expect(
        header(opened.querySelector("dl")!)[
          "Times the model would not take it"
        ],
      ).toBe(inTheTry);
      expect(opened).not.toHaveTextContent(whenText("2026-09-26T09:05:00Z"));
    },
  );

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

    expect(header(opened.querySelector("dl")!)["What went wrong"]).toBe(
      "Withheld from you.",
    );
    expect(opened).not.toHaveTextContent("(cut short here)");
  });

  /** A try's figure may add up a call to produce it and one to review it, and the wire never says whether all were measured here. */
  it.each([
    [
      "a try any call of which the model did not count",
      MODEL_MISFIT,
      1,
      SOME_MEASURED,
      ["not known"],
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
      `${SOME_MEASURED}What came back is not known for every call yet, and is counted here as nothing.`,
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
    "says who counted what %s cost, beneath the count",
    async (_case, page, number, counted, never) => {
      opening([reply(page)], `${PAGE}?try=${number}`);

      const opened = await screen.findByRole("dialog", {
        name: `Try ${number} of the 2 it allows`,
      });

      const cost = header(opened.querySelector("dl")!)["Cost"]!;
      expect(cost).toBe(`1,500 in all: 1,200 sent, 300 came back${counted}`);
      for (const words of never) {
        expect(cost).not.toContain(words);
      }
    },
  );

  it("says a step's cost is some or all of it as this system measured it where any call of it was", async () => {
    opening([reply(MODEL_MISFIT)]);
    await heading();

    expect(header()["Cost"]).toBe(
      `3,000 in all: 2,400 sent, 600 came back${SOME_MEASURED}`,
    );
    expect(header()["Cost"]).not.toContain("not known");
  });

  it("says who counted a try's cost in the list the tries are picked in only of a try any call of which was measured here", async () => {
    opening([reply(MODEL_MISFIT)]);
    await heading();

    const [measured, counted] = tryRows().map((cells) => cells.at(-1));
    expect(measured).toBe(
      `1,500 in all: 1,200 sent, 300 came back${SOME_MEASURED}`,
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
        level: 3,
        name: /^The tries/,
        hidden: true,
      }),
    ).toHaveTextContent(`The tries of ${setApart("Reply")}`);
  });

  it("lists the tries of the first value where its address names one the step does not give", async () => {
    opening([reply(TWO_VALUES)], `${PAGE}?value=gone`);
    await heading();

    expect(
      screen.getByRole("heading", { level: 3, name: /^The tries/ }),
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
      screen.getByRole("heading", { level: 3, name: /^The tries/ }),
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
    ).toEqual([
      "Asked for by",
      "Produced by",
      "Reviewed by",
      "How it ended",
      "Cost",
    ]);
    expect(opened).not.toHaveTextContent("It gave nothing back for this.");
    expect(
      screen.getByRole("heading", {
        level: 3,
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
    expect(header()["Where it is"]).toBe("Held back");
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
    expect(header()["Where it is"]).toBe("Held back");
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
    await landing(() => screen.queryByRole("heading", { level: 2 }) !== null);
    expect(tryRows()[1]![2]).toBe("Nobody has reviewed it yet.");

    await act(() => vi.advanceTimersByTimeAsync(6999));
    expect(reads()).toBe(1);
    await act(() => vi.advanceTimersByTimeAsync(1));
    await landing(() => tryRows()[1]![2] === setApart("Dan"));
    await act(() => vi.advanceTimersByTimeAsync(7000));
    await landing(() => reads() === 3);
    await act(() => vi.advanceTimersByTimeAsync(60_000));

    expect(reads()).toBe(3);
    expect(vi.getTimerCount()).toBe(0);
    expect(screen.queryByText("Still reading…")).toBeNull();
  });

  it("reads a step's page only once where the read names no wait, though the run it is of runs", async () => {
    vi.useFakeTimers({ toFake: ["setTimeout", "clearTimeout"] });
    const { rereadAfterSeconds: _wait, ...unkept } = WAITING;
    const { sent } = opening([reply(unkept), reply(WAITING)]);
    await landing(() => screen.queryByRole("heading", { level: 2 }) !== null);

    await act(() => vi.advanceTimersByTimeAsync(60_000));

    expect(
      requestsTo(sent).filter((each) => each === `GET ${STEP}`),
    ).toHaveLength(1);
    expect(vi.getTimerCount()).toBe(0);
    expect(header()["Where it is"]).toMatch(/^Waiting/);
  });

  it("says once, and only there, where a read again finds the step or its run in another state", async () => {
    vi.useFakeTimers({ toFake: ["setTimeout", "clearTimeout"] });
    const { where: _where, ...placeless } = ROW;
    const done = { ...WAITING, step: { ...placeless, state: "done" } };
    opening([reply(WAITING), reply(done), reply(done)]);
    await landing(() => screen.queryByRole("heading", { level: 2 }) !== null);
    // The page's own region comes first; the read's own follows it.
    const news = screen.getAllByRole("status")[0]!;
    expect(news).toHaveTextContent(/^$/);

    await act(() => vi.advanceTimersByTimeAsync(5000));
    await landing(() => header()["Where it is"] === "Done");

    expect(news).toHaveTextContent(`${setApart("Summarise")}: now Done.`);
    expect(news).not.toHaveTextContent("Run 7");

    await act(() => vi.advanceTimersByTimeAsync(5000));
    await landing(() => news.textContent === "");

    expect(header()["Where it is"]).toBe("Done");
  });

  it("says a step is now somewhere it cannot say yet in words of their own, never as a state", async () => {
    vi.useFakeTimers({ toFake: ["setTimeout", "clearTimeout"] });
    const { where: _where, ...placeless } = ROW;
    opening([
      reply(WAITING),
      reply({ ...WAITING, step: { ...placeless, state: "paused" } }),
    ]);
    await landing(() => screen.queryByRole("heading", { level: 2 }) !== null);
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
    await landing(() => screen.queryByRole("heading", { level: 2 }) !== null);
    const news = screen.getAllByRole("status")[0]!;
    await act(() => vi.advanceTimersByTimeAsync(5000));
    await landing(() => header()["Where it is"] === "Done");
    expect(news).toHaveTextContent("Run 7: now Done.");

    await act(() => vi.advanceTimersByTimeAsync(5000));

    expect(news).toHaveTextContent(/^$/);
    expect(
      requestsTo(sent).filter((each) => each === `GET ${STEP}`),
    ).toHaveLength(2);
  });

  it("says a step refused in place, the way back to the run still there", async () => {
    opening([[JSON.stringify({ code: "STEP_NOT_IN_VIEW" }), 404]]);

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "That step is not in view.",
    );
    expect(screen.queryByRole("heading", { level: 2 })).toBeNull();
    expect(
      screen.getByRole("link", { name: "Back to the run" }),
    ).toBeInTheDocument();
  });
});
