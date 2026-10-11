import { ThemeProvider } from "@mui/material/styles";
import {
  act,
  fireEvent,
  render,
  screen,
  within,
  waitFor,
} from "@testing-library/react";
import { userEvent } from "@testing-library/user-event";
import { RouterProvider, createMemoryRouter } from "react-router";
import { afterEach, describe, expect, it, onTestFinished, vi } from "vitest";

import { WORK_ITEM } from "../../../app/destinations";
import { RequireGroup } from "../../../app/standing/RequireGroup";
import { StandingProvider } from "../../../app/standing/StandingContext";
import { theme } from "../../../lib/theme/theme";
import { whenText } from "../../../lib/time/When";
import { requestsTo, serving, type Reply } from "../../../testutil/answering";
import { deferred } from "../../../testutil/deferred";
import { namingGroupsByKeyAlone } from "../../../testutil/groupAddresses";
import { regionsSaid } from "../../../testutil/heard";
import { pageLaidOutAt } from "../../../testutil/layout";
import { KEY, STARTED } from "../../../testutil/runPage";
import { answered, inGroup } from "../../../testutil/standingRead";
import { tryReplies } from "../../../testutil/tryReplies";
import { laysPairsBeside } from "../../../testutil/writtenRules";
import { WITHIN_THE_RUN } from "../runAddress";
import { StepPage } from "./StepPage";

const GROUP = "00000003-0000-4000-8000-000000000ca1";

const RUN_ID = "00000008-0000-4000-8000-000000000ca1";

const STEP_ID = "00000009-0000-4000-8000-000000000ca1";

const ENTRY = "00000006-0000-4000-8000-000000000ca1";

const VERSION = "00000007-0000-4000-8000-000000000ca1";

const QUESTION_VERSION = "00000007-0000-4000-8000-000000000ca2";

const PAGE = `/groups/${KEY}/work/${RUN_ID}/steps/${STEP_ID}`;

const STEPS = `/api/groups/${GROUP}/runs/${RUN_ID}/steps`;

const STEP = `/api/groups/${GROUP}/runs/${RUN_ID}/steps/${STEP_ID}`;

const CAT = { userId: "000ca1", displayName: "Cat" };

const DAN = { userId: "000ca2", displayName: "Dan" };

const UNKNOWN_SAID = "This page can't show this yet.";

const SOME_MEASURED =
  "Part of this is estimated by this system, where a model did not report its own count.";

const ZERO_WIDTH_SPACE = String.fromCodePoint(0x200b);

const SHOWS_NOTHING = "An invisible character is here: U+200B.";

const HEADER = {
  versionName: null,
  runId: RUN_ID,
  number: 7,
  versionId: VERSION,
  state: "running",
  at: { stepId: STEP_ID, name: "summarise" },
  stateActs: [],
  progress: { done: 1, of: 3 },
};

const ROW = {
  producedByUserId: CAT.userId,
  producedByKind: "person",
  stepId: STEP_ID,
  order: 2,
  name: "summarise",
  runs: {
    versionName: null,
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
  stateActs: ["review"],
  stateRefusals: [],
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
      decision: { outcome: "refused", whyLines: "several", why: "Too short." },
    },
  ],
  review: { asked: true, by: { kind: "person", person: DAN } },
  ended: "refused_on_review",
  cost: { callsAModel: false },
};

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
      decision: { outcome: "refused", whyLines: "several", why: "Too short." },
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

const TWO_VALUES = {
  ...WAITING,
  step: {
    ...ROW,
    where: {
      ...ROW.where,
      values: [
        { field: "summary", on: "review_at_gate" },
        { field: "reply", on: "review_at_gate" },
      ],
    },
  },
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
        {
          field: "reply",
          value: "Sorry.",
          now: "refused",
          decision: {
            outcome: "refused",
            whyLines: "several",
            why: "Too brief.",
          },
        },
      ],
    },
    {
      ...WAITING_TRY,
      values: [
        ...WAITING_TRY.values,
        {
          field: "reply",
          value: "We will replace it.",
          now: "waiting_on_review",
        },
      ],
    },
  ],
};

const MODEL_WAITING = {
  ...WAITING,
  step: {
    ...ROW,
    producedByUserId: null,
    producedByKind: "model",
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

const SEARCH_FIELDS = ["title", "body", "references", "other"];

const SEARCH_OUTPUTS = {
  takes: [],
  gives: SEARCH_FIELDS.map((name) => ({
    name,
    kind: "text",
    longest: 4000,
    mustBeGiven: name === "title" || name === "body",
  })),
};

const SEARCH_DECLARATIONS = {
  ...DECLARATIONS,
  [QUESTION_VERSION]: {
    ...DECLARATIONS[QUESTION_VERSION],
    gives: SEARCH_OUTPUTS.gives,
  },
  "search:web": SEARCH_OUTPUTS,
};

const SEARCH_WAITING = {
  ...MODEL_WAITING,
  declarations: SEARCH_DECLARATIONS,
  step: {
    ...MODEL_WAITING.step,
    runs: { ...ROW.runs, kind: "search", searchWay: "web", tool: "web_search" },
    where: {
      ...ROW.where,
      values: SEARCH_FIELDS.map((field) => ({ field, on: "review_at_gate" })),
    },
  },
  cameOut: SEARCH_FIELDS.map((field) => ({ field, now: "waiting_on_review" })),
  triesMade: [
    {
      ...SURE_TRY,
      values: SEARCH_FIELDS.map((field) => ({
        field,
        value: `Demo Sample earlier ${field}`,
        now: "refused",
        decision: {
          outcome: "refused",
          whyLines: "several",
          why: "Look further.",
        },
      })),
    },
    {
      ...MODEL_WAITING.triesMade[1],
      values: SEARCH_FIELDS.map((field) => ({
        field,
        value: `Demo Sample current ${field}`,
        now: "waiting_on_review",
      })),
    },
  ],
};

const ROUTE_PAGE = {
  run: { ...HEADER, at: { stepId: STEP_ID, name: "file_it" } },
  rereadAfterSeconds: 5,
  declarations: {
    ...DECLARATIONS,
    [`route:${STEP_ID}`]: { takes: [], gives: [] },
  },
  step: {
    ...ROW,
    name: "file_it",
    runs: { versionName: null, kind: "route" },
    producer: undefined,
    reviewer: undefined,
    producedByUserId: null,
    producedByKind: null,
    state: "running",
    where: { kind: "running", on: "next_try" },
    takesFrom: [],
    tries: undefined,
    stateActs: [],
    stateRefusals: [],
  },
  triesMade: [],
};

const MODEL_REVIEW_WAITING = {
  ...MODEL_WAITING,
  step: {
    ...MODEL_WAITING.step,
    reviewer: { kind: "model", model: "small" },
    where: {
      ...ROW.where,
      values: [{ field: "summary", on: "model" }],
      review: "unsent",
      waitsOn: "model",
    },
    stateActs: [],
    stateRefusals: [{ act: "review", refusal: "REVIEW_NOT_A_PERSONS" }],
  },
  triesMade: [
    MODEL_WAITING.triesMade[0],
    { ...MODEL_WAITING.triesMade[1], askedBy: undefined },
  ],
};

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

const RUNNING_CODE_TRY = {
  number: 2,
  beyond: false,
  producedBy: { kind: "code" },
  values: [],
  review: { asked: false },
  ended: "open",
  cost: { callsAModel: false },
};

const ERRORED = {
  run: HEADER,
  declarations: DECLARATIONS,
  step: {
    ...ROW,
    producedByUserId: null,
    producedByKind: null,
    stateActs: [],
    stateRefusals: [],
    runs: { versionName: null, kind: "code_step", codeStep: "file_claim" },
    producer: { kind: "code" },
    state: "running",
    where: { kind: "running", on: "code" },
    tries: { current: 2, declared: 2, beyond: false },
  },
  triesMade: [ERRORED_TRY, RUNNING_CODE_TRY],
};

const FILE_CLAIM = {
  takes: [],
  gives: [
    {
      name: "reference",
      label: "Claim reference",
      kind: "text",
      longest: 20,
      mustBeGiven: true,
    },
  ],
};

const UNKEPT = {
  ...ERRORED,
  declarations: { ...DECLARATIONS, file_claim: FILE_CLAIM },
  triesMade: [
    {
      ...ERRORED_TRY,
      wentWrong: undefined,
      returned: undefined,
      erroredFor: {
        reason: "too_long_to_keep",
        field: "reference",
        returnedNotKept: true,
      },
    },
    RUNNING_CODE_TRY,
  ],
};

const LATER_STEP_ID = "0000000a-0000-4000-8000-000000000ca1";

const FAILED_CODE = {
  ...UNKEPT,
  run: { ...HEADER, state: "failed" },
  step: {
    ...UNKEPT.step,
    state: "failed",
    where: { kind: "failed", reason: "tries_spent", waitsOn: "answer_step" },
    tries: { current: 1, declared: 1, beyond: false },
    stateActs: [],
    stateRefusals: [
      {
        act: "answer",
        refusal: "CODE_STEP_GIVES_OTHERWISE",
      },
    ],
    givesOtherwise: { field: "reference", readBy: { step: LATER_STEP_ID } },
  },
  triesMade: [UNKEPT.triesMade[0]],
};

const TURNED_AWAY = [
  {
    at: "2026-09-26T08:58:00Z",
    said: "Too many asks.",
    cut: false,
    sentAgain: true,
  },
  { at: "2026-09-26T08:59:00Z", sentAgain: true },
];

const MISFIT_TRY = {
  number: 1,
  beyond: false,
  producedBy: SURE_TRY.producedBy,
  values: [],
  review: { asked: false },
  ended: "did_not_fit",
  didNotFit: "cut_off",
  turnedAway: TURNED_AWAY,
  cost: { ...SURE_TRY.cost, measuredHere: "wholly" },
};

const MODEL_MISFIT = {
  ...MODEL_WAITING,
  step: {
    ...MODEL_WAITING.step,
    cost: { ...MODEL_WAITING.step.cost, measuredHere: "partly" },
  },
  triesMade: [MISFIT_TRY, MODEL_WAITING.triesMade[1]],
};

const HELD_TURNED_AWAY = {
  run: HEADER,
  rereadAfterSeconds: 5,
  declarations: DECLARATIONS,
  step: {
    ...MODEL_WAITING.step,
    producedByUserId: null,
    producedByKind: null,
    state: "held_back",
    where: {
      kind: "held_back",
      reason: "turned_away",
      since: "2026-09-26T08:59:00Z",
      turnedAway: [{ ...TURNED_AWAY[1]!, sentAgain: false }],
      waitsOn: "starter",
    },
    tries: { current: 0, declared: 2, beyond: false },
    stateActs: ["try_sending"],
    stateRefusals: [],
  },
  triesMade: [],
};

const HELD_AGAIN = {
  ...HELD_TURNED_AWAY,
  step: {
    ...HELD_TURNED_AWAY.step,
    where: {
      ...HELD_TURNED_AWAY.step.where,
      since: "2026-09-26T09:05:00Z",
      turnedAway: [{ at: "2026-09-26T09:05:00Z", sentAgain: false }],
    },
    tries: { current: 1, declared: 2, beyond: false },
  },
  triesMade: [MISFIT_TRY],
};

function reply(page: object): Reply {
  return tryReplies(page).summary;
}

function listedStep(page: {
  readonly run: object;
  readonly step: object;
  readonly declarations: object;
}): readonly Reply[] {
  return [
    reply({
      run: page.run,
      steps: [page.step],
      gaveBack: { declares: "nothing" },
      declarations: page.declarations,
    }),
  ];
}

function materialRoutes(
  pages: readonly object[],
): Readonly<Record<string, readonly Reply[]>> {
  const routes: Record<string, Reply[]> = {};
  for (const page of pages) {
    for (const [number, material] of tryReplies(page).materials) {
      const path = `GET ${STEP}/tries/${number}`;
      (routes[path] ??= []).push(material);
    }
  }
  return routes;
}

const CONTENT_READER = {
  userId: CAT.userId,
  permissions: ["read_own_runs", "read_inference_content"],
};

function keptExpanded() {
  const kept = localStorage.getItem("lilradish.panel.row");
  localStorage.setItem("lilradish.panel.row", "expanded");
  onTestFinished(() => {
    if (kept === null) localStorage.removeItem("lilradish.panel.row");
    else localStorage.setItem("lilradish.panel.row", kept);
  });
}

function opening(
  pages: readonly Reply[],
  address:
    string | { pathname: string; search?: string; state?: unknown } = PAGE,
  listed: readonly Reply[] = [
    reply({
      run: HEADER,
      steps: [ROW],
      gaveBack: { declares: "nothing" },
      declarations: DECLARATIONS,
    }),
  ],
  more: Readonly<Record<string, readonly Reply[]>> = {},
  reader?: { readonly userId: string; readonly permissions: readonly string[] },
  page = 1400,
) {
  pageLaidOutAt(page, 1600);
  const sent = serving({
    [`GET ${STEP}`]: pages,
    [`GET ${STEPS}`]: listed,
    ...more,
  });
  const router = createMemoryRouter(
    [
      {
        path: "/groups/:group/work/:runId/steps/:stepId",
        element: (
          <RequireGroup reachedBy={WORK_ITEM.reachedBy}>
            <StepPage />
          </RequireGroup>
        ),
      },
    ],
    { initialEntries: [address] },
  );
  namingGroupsByKeyAlone(router, [GROUP]);
  const standing = answered(
    [],
    [inGroup(GROUP, KEY, "Claims", reader?.permissions ?? [])],
    reader?.userId,
  );
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
  return screen.findByRole("heading", { level: 1 });
}

function fieldsIn(
  list: Element = screen
    .getByRole("region", { name: "Overview", hidden: true })
    .querySelector("dl")!,
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

function tryRows(): string[][] {
  const [, ...rows] = within(
    part("Attempts"),
  ).getAllByRole<HTMLTableRowElement>("row");
  return rows.map((row) =>
    [...row.cells].map((cell) => cell.textContent ?? ""),
  );
}

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
  it("reads a display name into the page heading while preserving the run and pinned question links", async () => {
    const row = { ...ROW, label: "\u7533\u8acb\u3092\u8981\u7d04\u202e" };
    const { sent } = opening(
      [
        reply({
          ...WAITING,
          step: {
            ...WAITING.step,
            label: "\u7533\u8acb\u3092\u8981\u7d04\u202e",
          },
        }),
      ],
      PAGE,
      [
        reply({
          run: HEADER,
          steps: [row],
          gaveBack: { declares: "nothing" },
          declarations: DECLARATIONS,
        }),
      ],
      {
        [`GET /api/groups/${GROUP}/runs/${RUN_ID}`]: [
          reply({ ...STARTED, runId: RUN_ID, number: 7 }),
        ],
      },
    );

    expect((await heading()).textContent).toBe(
      setApart("\u7533\u8acb\u3092\u8981\u7d04\u202e"),
    );
    expect(screen.getAllByRole("heading", { level: 1 })).toHaveLength(1);
    expect(
      await screen.findByRole("link", { name: /Summarise/ }),
    ).toHaveAttribute(
      "href",
      `/groups/${KEY}/questions/${ENTRY}?version=${QUESTION_VERSION}`,
    );
    expect(
      await screen.findByRole("link", { name: setApart("Claim from Avo") }),
    ).toHaveAttribute("href", `/groups/${KEY}/work/${RUN_ID}`);
    expect(screen.getByRole("heading", { level: 1 })).not.toHaveTextContent(
      "Summarise",
    );
    expect(screen.getByRole("heading", { level: 1 })).not.toHaveTextContent(
      "summarise",
    );
    expect(requestsTo(sent)).toContain(`GET ${STEP}`);
    expect(requestsTo(sent)).toContain(`GET ${STEPS}`);
  });

  it.each([
    [
      "LENGTH_VALUE_USED",
      "It can't be rejected now: something has already been made from it.",
    ],
    [
      "LENGTH_VALUE_NOT_STANDING",
      "It can't be rejected now: it is no longer the accepted value.",
    ],
    [
      "STEP_NOT_HELD_FOR_LENGTH",
      "It can't be rejected now: the step is no longer blocked on length.",
    ],
  ])(
    "keeps the %s refusal while replacing the obsolete length panel",
    async (code, sentence) => {
      const user = userEvent.setup();
      const refusal = deferred<readonly [string, number]>();
      const hold = {
        ...HELD_TURNED_AWAY,
        step: {
          ...HELD_TURNED_AWAY.step,
          where: {
            kind: "held_back",
            reason: "too_long",
            since: "2026-09-26T08:59:00Z",
          },
          length: {
            holdId: "old-hold",
            model: "small",
            limitUnits: "600",
            wholeUnits: "800",
            excessUnits: "200",
            fixedUnits: "10",
            tellsWhatHappened: false,
            values: [
              {
                valueId: "accepted-value",
                inputs: ["text"],
                units: "790",
                from: {
                  kind: "step",
                  runId: RUN_ID,
                  runNumber: 7,
                  stepId: "maker",
                  name: "summarise",
                  path: "summary",
                },
                canBeMadeAgain: true,
                fromSubRun: false,
                triesSpent: false,
                mayRefuse: true,
                used: [],
              },
            ],
          },
        },
      };
      const { sent } = opening([reply(hold), reply(WAITING)], PAGE, undefined, {
        [`PUT ${STEP}/length/refusal`]: [refusal.promise],
      });
      await heading();
      await user.click(
        screen.getByRole("button", { name: "Reject for length" }),
      );
      const confirmation = screen.getByRole("dialog");
      expect(
        within(confirmation).queryByRole("textbox"),
      ).not.toBeInTheDocument();
      await user.click(
        within(confirmation).getByRole("button", { name: "Reject for length" }),
      );
      expect(
        within(confirmation).getByRole("button", { name: "Reject for length" }),
      ).toHaveAttribute("aria-disabled", "true");
      await act(async () => refusal.settle([JSON.stringify({ code }), 409]));
      expect(await screen.findByText(sentence)).toBeVisible();
      await waitFor(() =>
        expect(
          screen.queryByRole("heading", { name: "Why it's too long" }),
        ).not.toBeInTheDocument(),
      );
      expect(fieldsIn().Status).toContain("has not been reviewed yet");
      expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
      expect(
        screen.queryByRole("button", { name: "Reject for length" }),
      ).not.toBeInTheDocument();
      const sentRefusal = sent.mock.calls.find(
        ([, init]) => init?.method === "PUT",
      );
      expect(JSON.parse(String(sentRefusal?.[1]?.body))).toEqual({
        holdId: "old-hold",
        valueId: "accepted-value",
      });
      expect(
        requestsTo(sent).filter(
          (request) => request === `PUT ${STEP}/length/refusal`,
        ),
      ).toHaveLength(1);
      expect(
        requestsTo(sent).filter((request) => request === `GET ${STEP}`).length,
      ).toBeGreaterThan(1);
    },
  );

  it.each([
    [[], "Waiting for the person who started the run."],
    [
      [{ runId: "parent", number: 6 }],
      "Waiting for the person who started the top-level run.",
    ],
    [
      [
        { runId: "top", number: 5 },
        { runId: "parent", number: 6 },
      ],
      "Waiting for the person who started the top-level run.",
    ],
  ] as const)(
    "keeps the step visible while reading the run hierarchy beneath %j",
    async (runsAbove, sentence) => {
      const run = deferred<readonly [string, number]>();
      const { sent } = opening(
        [
          reply({
            ...WAITING,
            step: {
              ...WAITING.step,
              reviewer: { kind: "model", model: "small" },
              cost: {
                callsAModel: true,
                sent: "0",
                cameBack: "0",
                spent: "0",
                cameBackUnknown: false,
              },
              where: {
                ...WAITING.step.where,
                values: [{ field: "summary", on: "model" }],
                review: "turned_away",
                waitsOn: "starter",
              },
              stateActs: ["try_sending"],
              stateRefusals: [
                { act: "review", refusal: "REVIEW_NOT_A_PERSONS" },
              ],
              waitsOnStarter:
                runsAbove.length === 0 ? "this_run" : "top_level_run",
            },
            triesMade: [
              REFUSED_TRY,
              {
                ...WAITING_TRY,
                review: { asked: true, by: { kind: "model", model: "small" } },
                cost: {
                  callsAModel: true,
                  sent: "0",
                  cameBack: "0",
                  spent: "0",
                  cameBackUnknown: false,
                },
              },
            ],
          }),
        ],
        PAGE,
        undefined,
        { [`GET /api/groups/${GROUP}/runs/${RUN_ID}`]: [run.promise] },
        { userId: CAT.userId, permissions: ["start_run"] },
      );
      const title = await heading();
      const before = fieldsIn().Status;
      expect(before).toBe(
        `Waiting${setApart("Summary")} has not been reviewed yet.Waiting for the model's review, which could not be sent: the model service turned the request away.Someone who can start runs can try sending it.${sentence}`,
      );
      expect(document.body).toHaveTextContent(sentence);

      await act(async () =>
        run.settle([
          JSON.stringify({
            ...STARTED,
            runId: RUN_ID,
            number: 7,
            runsAbove,
            ...(runsAbove.length === 0
              ? {}
              : {
                  name: undefined,
                  startedBy: undefined,
                  above: runsAbove.at(-1)!.runId,
                  from: {
                    runId: "parent",
                    number: 6,
                    stepId: "parent-step",
                    stepName: "handle_claim",
                  },
                }),
          }),
          200,
        ]),
      );

      expect(fieldsIn().Status).toBe(before);
      expect(screen.getByRole("button", { name: "Try sending" })).toBeEnabled();
      expect(await heading()).toBe(title);
      expect(document.body).not.toHaveTextContent(
        runsAbove.length === 0
          ? "Waiting for the person who started the top-level run."
          : "Waiting for the person who started the run.",
      );
      expect(
        requestsTo(sent).filter(
          (request) => request === `GET /api/groups/${GROUP}/runs/${RUN_ID}`,
        ),
      ).toHaveLength(1);
    },
  );

  it("keeps a waiting step and its reason visible when its run cannot be read", async () => {
    const run = deferred<readonly [string, number]>();
    const { sent } = opening(
      [
        reply({
          ...WAITING,
          step: {
            ...WAITING.step,
            reviewer: { kind: "model", model: "small" },
            where: {
              ...WAITING.step.where,
              values: [{ field: "summary", on: "model" }],
              review: "turned_away",
              waitsOn: "starter",
            },
            stateActs: ["try_sending"],
            stateRefusals: [{ act: "review", refusal: "REVIEW_NOT_A_PERSONS" }],
          },
          triesMade: [
            REFUSED_TRY,
            {
              ...WAITING_TRY,
              review: { asked: true, by: { kind: "model", model: "small" } },
            },
          ],
        }),
      ],
      PAGE,
      undefined,
      { [`GET /api/groups/${GROUP}/runs/${RUN_ID}`]: [run.promise] },
      { userId: CAT.userId, permissions: ["start_run"] },
    );
    const title = await heading();
    const before = fieldsIn().Status;

    await act(async () =>
      run.settle([JSON.stringify({ code: "SERVICE_UNAVAILABLE" }), 503]),
    );

    expect(fieldsIn().Status).toBe(before);
    expect(before).toBe(
      `Waiting${setApart("Summary")} has not been reviewed yet.Waiting for the model's review, which could not be sent: the model service turned the request away.Someone who can start runs can try sending it.`,
    );
    expect(await heading()).toBe(title);
    expect(document.body).not.toHaveTextContent("person who started");
    expect(screen.getByRole("button", { name: "Try sending" })).toBeEnabled();
    expect(
      requestsTo(sent).filter(
        (request) => request === `GET /api/groups/${GROUP}/runs/${RUN_ID}`,
      ),
    ).toHaveLength(1);
  });
  it.each(["one", "several"] as const)(
    "uses the same %s field declaration when moving between tries",
    async (lines) => {
      keptExpanded();
      const first = "**Imaginary first**";
      const second =
        lines === "several"
          ? "**Imaginary second**\nRetain the label."
          : "**Imaginary second**";
      const page = {
        ...WAITING,
        declarations: {
          ...DECLARATIONS,
          [QUESTION_VERSION]: {
            ...DECLARATIONS[QUESTION_VERSION],
            gives: [{ ...SUMMARY, lines }],
          },
        },
        triesMade: [
          {
            ...REFUSED_TRY,
            values: [{ field: "summary", value: first, now: "refused" }],
            review: { asked: false },
          },
          {
            ...WAITING_TRY,
            values: [
              { field: "summary", value: second, now: "waiting_on_review" },
            ],
          },
        ],
      };
      const { router } = opening([reply(page)], `${PAGE}?try=1`);
      const check = async (number: number, text: string) => {
        const detail = await screen.findByRole("dialog", {
          name: `Attempt ${number} of 2`,
        });
        if (lines === "one") {
          expect(detail.textContent).toContain(text);
          expect(detail.querySelectorAll("strong")).toHaveLength(0);
          expect(
            within(detail).queryByRole("button", { name: "Show as written" }),
          ).toBeNull();
        } else {
          expect(
            within(detail).queryByRole("button", { name: "Show all" }),
          ).toBeNull();
          expect(detail.querySelector("strong")?.textContent).toBe(
            `Imaginary ${number === 1 ? "first" : "second"}`,
          );
          expect(
            within(detail).getAllByRole("button", { name: "Show as written" }),
          ).toHaveLength(1);
          await userEvent.click(
            within(detail).getByRole("button", { name: "Show as written" }),
          );
          expect(detail.querySelectorAll("strong")).toHaveLength(0);
          expect(
            [...detail.querySelectorAll("bdi")].some(
              (node) => node.textContent === text,
            ),
          ).toBe(true);
          await userEvent.click(
            within(detail).getByRole("button", { name: "Show formatted" }),
          );
          expect(detail.querySelectorAll("strong")).toHaveLength(1);
        }
      };
      await check(1, first);
      await act(() => router.navigate(`${PAGE}?try=2`));
      await check(2, second);
      await act(() => router.navigate(`${PAGE}?try=1`));
      await check(1, first);
    },
  );
  it("updates the step's stopped day at midnight without rereading or moving the keyboard", async () => {
    vi.useFakeTimers({ toFake: ["Date"] });
    vi.setSystemTime(new Date("2026-10-03T21:59:30Z"));
    const { sent } = opening([
      reply({
        ...HELD_TURNED_AWAY,
        rereadAfterSeconds: undefined,
        step: {
          ...HELD_TURNED_AWAY.step,
          stateActs: [],
          where: {
            kind: "held_back",
            reason: "entry_stopped",
            stopped: {
              what: "entry",
              by: { userId: "000ca1", displayName: "Cat" },
              at: "2026-10-03T21:59:00Z",
            },
          },
        },
      }),
    ]);
    await heading();
    const focusTarget = screen.getByRole("link", { name: /Version 2/ });
    focusTarget.focus();
    expect(document.activeElement).toBe(focusTarget);
    expect(document.body).toHaveTextContent("11:59 PM today");
    const reads = requestsTo(sent).filter(
      (request) => request === `GET ${STEP}`,
    ).length;

    vi.setSystemTime(new Date("2026-10-03T22:00:00Z"));
    act(() => document.dispatchEvent(new Event("visibilitychange")));

    expect(document.body).toHaveTextContent("11:59 PM yesterday");
    expect(
      requestsTo(sent).filter((request) => request === `GET ${STEP}`),
    ).toHaveLength(reads);
    expect(document.activeElement).toBe(focusTarget);
  });

  it("keeps a reviewer's declined words inside the picked try without turning literal markup into a link", async () => {
    keptExpanded();
    const words = '<a href="/elsewhere">Demo Sample</a>';
    const materialPages = [
      {
        ...MODEL_WAITING,
        triesMade: [
          {
            ...SURE_TRY,
            review: {
              asked: true,
              by: { kind: "model", model: "judge" },
              didNotFit: "model_declined",
              declined: {
                wordsLines: "several",
                words: { text: words, altered: false, truncated: false },
              },
            },
          },
          MODEL_WAITING.triesMade[1],
        ],
      },
    ];
    opening(
      materialPages.map(reply),
      `${PAGE}?try=1`,
      undefined,
      materialRoutes(materialPages),
      CONTENT_READER,
    );

    const detail = await screen.findByRole("dialog", {
      name: "Attempt 1 of 2",
    });
    await within(detail).findByText("The model's reason for declining");
    expect(detail).toHaveTextContent(words);
    expect(
      within(detail).getByText("The model's reason for declining"),
    ).toBeVisible();
    expect(
      within(detail).queryByRole("link", { name: "Demo Sample" }),
    ).toBeNull();
    await userEvent.click(
      within(detail).getByRole("button", { name: "Close" }),
    );
    await userEvent.click(
      await screen.findByRole("button", { name: "Attempt 2 of 2" }),
    );
    expect(screen.queryByText(words)).toBeNull();
  });

  it("removes a previously visible model refusal when the next read withholds it", async () => {
    keptExpanded();
    vi.useFakeTimers({ toFake: ["setTimeout", "clearTimeout"] });
    const tried = {
      ...SURE_TRY,
      didNotFit: "model_declined",
      declined: {
        content: {
          text: "Demo Sample private answer",
          altered: true,
          truncated: false,
        },
      },
    };
    const response = {
      text: '<a href="/outside">Demo Sample service raw</a>',
      altered: false,
      truncated: true,
      stopped: true,
    };
    const page = (declined: unknown, unreadableResponse?: typeof response) => ({
      ...MODEL_WAITING,
      triesMade: [
        { ...tried, declined, unreadableResponse },
        MODEL_WAITING.triesMade[1],
      ],
    });
    const materialPages = [
      page(tried.declined, response),
      page({ withheld: true }),
    ];
    const { sent } = opening(
      materialPages.map(reply),
      `${PAGE}?try=1`,
      undefined,
      materialRoutes(materialPages),
      CONTENT_READER,
    );
    await landing(
      () =>
        screen.queryByRole("button", {
          name: "The model's reply, exactly as it came",
        }) !== null,
    );
    const rawReply = screen.getByRole("button", {
      name: "The model's reply, exactly as it came",
    });
    expect(rawReply).toHaveAttribute("aria-expanded", "false");
    fireEvent.click(rawReply);
    await landing(
      () => screen.queryByText("Demo Sample private answer") !== null,
    );
    expect(
      screen.getByText("What the model service sent back"),
    ).toBeInTheDocument();
    const unreadable = screen.getByRole("button", {
      name: "What the model service sent back",
    });
    expect(unreadable).toHaveAttribute("aria-expanded", "false");
    fireEvent.click(unreadable);
    expect(document.body).toHaveTextContent(response.text);
    expect(
      screen.getByText(
        "This could not be read, so the rest of it was not downloaded. Only its start is kept here.",
      ),
    ).toBeInTheDocument();
    expect(
      screen.queryByRole("link", { name: "Demo Sample service raw" }),
    ).toBeNull();
    expect(
      screen.getByText(
        "Some of it couldn't be stored exactly as received, so it is replaced here.",
      ),
    ).toBeInTheDocument();

    await act(() => vi.advanceTimersByTimeAsync(5_000));
    await landing(
      () => screen.queryByText("Demo Sample private answer") === null,
    );
    expect(
      screen.queryByText("The model's reply, exactly as it came"),
    ).toBeNull();
    expect(screen.queryByText("What the model service sent back")).toBeNull();
    expect(document.body).not.toHaveTextContent(response.text);
    expect(
      screen.queryByText(
        "This could not be read, so the rest of it was not downloaded. Only its start is kept here.",
      ),
    ).toBeNull();
    expect(
      screen.queryByText(
        "Some of it couldn't be stored exactly as received, so it is replaced here.",
      ),
    ).toBeNull();
    expect(
      requestsTo(sent).filter((request) => request === `GET ${STEP}`),
    ).toHaveLength(2);
  });

  it("links a child's output to its originating numbered run with History filters but no picked try", async () => {
    keptExpanded();
    const source = {
      runId: "00000008-0000-4000-8000-000000000ca2",
      number: 19,
    };
    const page = {
      run: {
        ...HEADER,
        at: { stepId: LATER_STEP_ID, name: "check_claim" },
        progress: { done: 2, of: 3 },
      },
      rereadAfterSeconds: 5,
      declarations: DECLARATIONS,
      step: {
        ...ROW,
        runs: { ...ROW.runs, kind: "workflow", name: "Demo Sample child" },
        producer: undefined,
        reviewer: undefined,
        producedByUserId: null,
        producedByKind: null,
        state: "done",
        where: undefined,
        tries: undefined,
        stateActs: [],
        stateRefusals: [],
        beneath: {
          ...source,
          runs: { ...ROW.runs, kind: "workflow", name: "Demo Sample child" },
        },
      },
      cameOut: [
        {
          field: "summary",
          now: "stands",
          standing: { number: 1, value: "Demo Sample child answer." },
          fromRun: source,
        },
      ],
      triesMade: [],
    };
    opening(
      [reply(page)],
      `${PAGE}?filter=Demo+Sample&sort=started&value=summary&try=1`,
      listedStep(page),
    );

    await heading();
    const link = screen.getByRole("link", { name: "Run 19", hidden: true });
    expect(link).toHaveAttribute(
      "href",
      `/groups/${KEY}/work/${source.runId}?filter=Demo+Sample&sort=started`,
    );
    expect(link).not.toHaveAttribute("href", expect.stringContaining(RUN_ID));
    expect(link).not.toHaveAttribute("href", expect.stringContaining("value="));
    expect(link).not.toHaveAttribute("href", expect.stringContaining("try="));
    expect(screen.queryByRole("dialog")).toBeNull();
  });

  it("opens a question's picked parent attempt with the History filters intact", async () => {
    keptExpanded();
    const { router } = opening(
      [reply(WAITING)],
      `${PAGE}?filter=Demo+Sample&sort=started&value=summary&try=1`,
    );
    expect(
      await screen.findByRole("dialog", { name: "Attempt 1 of 2" }),
    ).toBeVisible();
    expect(router.state.location.search).toBe(
      "?filter=Demo+Sample&sort=started&value=summary&try=1",
    );
  });

  it("names the picked value's own standing even when its sibling was refused", async () => {
    keptExpanded();
    const page = {
      ...TWO_VALUES,
      triesMade: [
        {
          ...TWO_VALUES.triesMade[0],
          values: [
            {
              field: "summary",
              value: "Demo Sample",
              now: "stands",
              decision: { outcome: "assured" },
            },
            {
              field: "reply",
              value: "No",
              now: "refused",
              decision: {
                outcome: "refused",
                whyLines: "several",
                why: "Try again.",
              },
            },
          ],
        },
        TWO_VALUES.triesMade[1],
      ],
    };
    opening([reply(page)], `${PAGE}?value=summary&try=1`, listedStep(page));

    const dialog = await screen.findByRole("dialog", {
      name: "Attempt 1 of 2",
    });
    const table = screen.getByRole("table", {
      name: /Attempts/,
      hidden: true,
    });
    expect(
      table.querySelector("tbody tr")?.querySelectorAll("td")[2],
    ).toHaveTextContent("Accepted.");
    expect(fieldsIn(dialog)["Outcome"]).toContain("Accepted.");
    expect(fieldsIn(dialog)["Outcome"]).toContain("Rejected in review.");
  });

  describe.each([
    [
      "source_no_longer_matches",
      "failedSourceNoLongerMatches",
      `The output ${setApart("info.answer")} of Demo Sample source no longer matches what this step expects from it, now that the code step has changed, so nothing was sent.`,
    ],
    [
      "source_list_not_here",
      "failedSourceListNotHere",
      `The options for ${setApart("info.answer")}, from Demo Sample source, come from a reference list version that isn't in service in this group, so nothing was sent.`,
    ],
  ])("%s", (reason, member, sentence) => {
    it.each([
      [
        "question",
        { versionName: null, kind: "question", name: "Summarise" },
        RUN_ID,
      ],
      [
        "question",
        { versionName: null, kind: "question", name: "Summarise" },
        "00000008-0000-4000-8000-000000000ca2",
      ],
      [
        "search",
        { versionName: null, kind: "search", searchWay: "web" },
        RUN_ID,
      ],
      [
        "search",
        { versionName: null, kind: "search", searchWay: "web" },
        "00000008-0000-4000-8000-000000000ca2",
      ],
    ])(
      "says the actual mismatched source field of a failed %s and offers only sending",
      async (_kind, runs, sourceRun) => {
        const sourceStep = "00000009-0000-4000-8000-000000000ca2";
        const sourceNumber = sourceRun === RUN_ID ? 7 : 19;
        opening(
          [
            reply({
              ...HELD_TURNED_AWAY,
              step: {
                ...HELD_TURNED_AWAY.step,
                runs,
                state: "failed",
                stateActs: ["try_sending"],
                stateRefusals: [],
                where: {
                  kind: "failed",
                  reason,
                  waitsOn: "starter",
                  [member]: {
                    source: {
                      runId: sourceRun,
                      runNumber: sourceNumber,
                      stepId: sourceStep,
                      name: "Demo Sample source",
                      codeStep: "tally",
                    },
                    field: "info.answer",
                  },
                },
              },
            }),
          ],
          `${PAGE}?filter=Demo+Sample&sort=started&value=summary&try=1`,
          undefined,
          undefined,
          { userId: CAT.userId, permissions: ["start_run"] },
        );
        await heading();

        const source = screen.getByRole("link", { name: "Demo Sample source" });
        expect(source).toHaveAttribute(
          "href",
          `/groups/${KEY}/work/${sourceRun}/steps/${sourceStep}?filter=Demo+Sample&sort=started`,
        );
        expect(source).toHaveAttribute("title", `Run ${sourceNumber}`);
        expect(source.querySelector("bdi")).toHaveTextContent(
          "Demo Sample source",
        );
        expect(
          screen.getByRole("button", { name: "Try sending" }),
        ).toBeEnabled();
        expect(
          screen.queryByRole("button", { name: "Ask again" }),
        ).not.toBeInTheDocument();
        expect(
          screen.queryByRole("button", { name: "Answer it yourself" }),
        ).not.toBeInTheDocument();
        expect(document.body).toHaveTextContent(sentence);
        expect(document.body).not.toHaveTextContent("goes on by itself");
        expect(document.body).not.toHaveTextContent("gave back as text");
        expect(source).not.toHaveAttribute("href", PAGE);
      },
    );
  });

  it.each([
    [
      "question",
      { versionName: null, kind: "question", name: "Summarise" },
      RUN_ID,
    ],
    [
      "question",
      { versionName: null, kind: "question", name: "Summarise" },
      "00000008-0000-4000-8000-000000000ca2",
    ],
    ["search", { versionName: null, kind: "search", searchWay: "web" }, RUN_ID],
    [
      "search",
      { versionName: null, kind: "search", searchWay: "web" },
      "00000008-0000-4000-8000-000000000ca2",
    ],
  ])(
    "links a %s's held source in its actual run",
    async (_kind, runs, sourceRun) => {
      const sourceStep = "00000009-0000-4000-8000-000000000ca2";
      const sourceNumber = sourceRun === RUN_ID ? 7 : 19;
      const page = {
        ...HELD_TURNED_AWAY,
        step: {
          ...HELD_TURNED_AWAY.step,
          runs,
          stateActs: [],
          where: {
            kind: "held_back",
            reason: "code_step_not_held",
            waitsOn: "starter",
            source: {
              runId: sourceRun,
              runNumber: sourceNumber,
              stepId: sourceStep,
              name: "Demo Sample source",
              codeStep: "tally",
            },
          },
        },
      };
      opening([reply(page)]);
      await heading();

      const source = screen.getByRole("link", { name: "Demo Sample source" });
      expect(source).toHaveAttribute(
        "href",
        `/groups/${KEY}/work/${sourceRun}/steps/${sourceStep}`,
      );
      expect(source).toHaveAttribute("title", `Run ${sourceNumber}`);
      expect(source.querySelector("bdi")).toHaveTextContent(
        "Demo Sample source",
      );
      expect(source.closest('[role="none"]')).toHaveTextContent(
        "An input comes from Demo Sample source, whose code isn't in the installed version of this system, so it can't be sent. It continues automatically once a system update brings that code back.",
      );
      expect(
        screen.queryByText(
          /The code this step runs isn't in the installed version/,
        ),
      ).not.toBeInTheDocument();
      expect(source).not.toHaveAttribute("href", PAGE);
    },
  );

  it.each([
    ["question", { versionName: null, kind: "question", name: "Summarise" }],
    ["search", { versionName: null, kind: "search", searchWay: "web" }],
  ])(
    "keeps the existing missing-code sentence for a %s with no held source",
    async (_kind, runs) => {
      opening([
        reply({
          ...HELD_TURNED_AWAY,
          step: {
            ...HELD_TURNED_AWAY.step,
            runs,
            stateActs: [],
            where: {
              kind: "held_back",
              reason: "code_step_not_held",
              waitsOn: "starter",
            },
          },
        }),
      ]);
      await heading();

      expect(
        screen.getByText(
          "The code this step runs isn't in the installed version of this system. The step continues automatically once a system update brings it back.",
        ),
      ).toBeInTheDocument();
      expect(screen.queryByText(/An input comes from/)).not.toBeInTheDocument();
      expect(
        screen.queryByRole("link", { name: "Demo Sample source" }),
      ).not.toBeInTheDocument();
    },
  );

  it("names the search way and keeps the waiting try's record beside review once, even when that try is picked", async () => {
    keptExpanded();
    const page = {
      ...SEARCH_WAITING,
      triesMade: [
        SEARCH_WAITING.triesMade[0],
        {
          ...SEARCH_WAITING.triesMade[1],
          search: {
            searches: [{ found: "The current record" }],
          },
        },
      ],
    };
    const materialPages = [page];
    opening(
      materialPages.map(reply),
      PAGE,
      listedStep(page),
      materialRoutes(materialPages),
      {
        ...CONTENT_READER,
        permissions: [...CONTENT_READER.permissions, "review_at_gate"],
      },
    );
    await heading();

    expect(
      screen.getByText(setApart("web"), { exact: false }),
    ).toHaveTextContent("Search:");
    await screen.findByText("The current record");
    expect(screen.getAllByText("The current record")).toHaveLength(1);
    expect(
      screen.getByRole("heading", { name: "Needs your review" }),
    ).toBeInTheDocument();
    expect(screen.queryByText("web_search")).toBeNull();
    await userEvent.click(
      screen.getByRole("button", { name: "Attempt 2 of 2" }),
    );
    const detail = await screen.findByRole("dialog");
    expect(within(detail).getByText("The current record")).toBeInTheDocument();
    expect(screen.getAllByText("The current record")).toHaveLength(1);
  });

  it.each(["too_long", "turned_away"])(
    "keeps a %s hold beside its ordinary input and actual sending state",
    async (reason) => {
      const page = {
        ...HELD_TURNED_AWAY,
        declarations: SEARCH_DECLARATIONS,
        step: {
          ...HELD_TURNED_AWAY.step,
          runs: SEARCH_WAITING.step.runs,
          producer: SURE_TRY.producedBy,
          producedByUserId: null,
          producedByKind: null,
          cost: {
            callsAModel: true,
            sent: "0",
            cameBack: "0",
            spent: "0",
            cameBackUnknown: false,
          },
          stateActs: ["try_sending"],
          stateRefusals: [],
          waitsOnStarter: "this_run",
          state: "held_back",
          where: {
            kind: "held_back",
            reason,
            since: "2026-09-26T08:59:00Z",
            waitsOn: "starter",
            ...(reason === "turned_away"
              ? { turnedAway: [{ ...TURNED_AWAY[1]!, sentAgain: false }] }
              : {}),
          },
        },
        wentIn: WAITING.wentIn,
        wentInFrom: reason === "too_long" ? "not_yet_sent" : "try",
        triesMade: [],
      };
      opening([reply(page)], PAGE, listedStep(page));
      await heading();
      expect(screen.getByText("Printer on fire")).toBeInTheDocument();
      expect(
        screen.getByText(
          reason === "too_long"
            ? "Its request is too long for the model, so it was not sent and nothing was spent."
            : "The model service turned the request away.",
          { exact: false },
        ),
      ).toBeInTheDocument();
      expect(
        screen.queryByText(
          "Not sent yet — these are the inputs it would use now.",
          { exact: false },
        ) !== null,
      ).toBe(reason === "too_long");
    },
  );
  it("gives the way back through its run first, keeping the list's address and leaving what this page picked", async () => {
    keptExpanded();
    opening(
      [reply(WAITING)],
      `${PAGE}?sort=run&value=summary&try=1`,
      undefined,
      {
        [`GET /api/groups/${GROUP}/runs/${RUN_ID}`]: [
          reply({
            runId: RUN_ID,
            number: 7,
            name: "Claim from Avo",
            runsAbove: [],
            workflow: {
              versionName: null,
              entryId: ENTRY,
              name: "Handle a claim",
              version: 3,
            },
            startedAt: "2026-09-24T08:00:00Z",
            conversationEvents: [],

            state: "running",
            spend: {
              sent: "0",
              cameBack: "0",
              spent: "0",
              cameBackUnknown: false,
            },
            ceiling: { raiseNeedsApproval: false },
            stateActs: [],
          }),
        ],
      },
    );

    await screen.findByRole("dialog");
    const title = screen.getByRole("heading", { level: 1, hidden: true });

    const path = screen.getByRole("navigation", {
      name: "Breadcrumb",
      hidden: true,
    });
    const run = await within(path).findByRole("link", {
      name: setApart("Claim from Avo"),
      hidden: true,
    });

    expect(run).toHaveAttribute(
      "href",
      `/groups/${KEY}/work/${RUN_ID}?sort=run`,
    );
    expect(
      path.compareDocumentPosition(title) & Node.DOCUMENT_POSITION_FOLLOWING,
    ).toBeTruthy();
    const toTheRun = screen
      .getAllByRole("link", { hidden: true })
      .filter(
        (link) =>
          link.getAttribute("href") ===
          `/groups/${KEY}/work/${RUN_ID}?sort=run`,
      );
    expect(toTheRun).toEqual([run]);
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
    expect(fieldsIn()).toEqual({
      Run: "Run 7",
      Order: "2 of 3",
      "What it runs": `Question Summarise \u00b7 Version 2`,
      "Produced by": "A person",
      "Reviewed by": "Anyone who can review it",
      Status: `Waiting${setApart("Summary")} has not been reviewed yet.Waiting for a reviewer.`,
      Attempts: "Attempt 2 of 2",
      Usage: "None — no model is used.",
    });
    expect(screen.getByRole("link", { name: /Summarise/ })).toHaveAttribute(
      "href",
      `/groups/${KEY}/questions/${ENTRY}?version=${QUESTION_VERSION}`,
    );
    const fields = screen
      .getByRole("region", { name: "Overview" })
      .querySelector("dl")!;
    const kind = within(fields).getByText("Question");
    const state = within(fields).getByText("Waiting");
    expect(kind.querySelector("svg")).toHaveAttribute(
      "data-testid",
      "QuestionAnswerIcon",
    );
    const stateIcon = state.parentElement!.querySelector(
      'svg[data-testid="HourglassEmptyIcon"]',
    );
    expect(stateIcon).toHaveAttribute("data-testid", "HourglassEmptyIcon");
    expect(stateIcon).toHaveAttribute("aria-hidden", "true");
    expect(kind).not.toContainElement(state);
  });

  it("says the name given the version a step runs after its number, the entry and the name each set apart", async () => {
    opening([
      reply({
        ...WAITING,
        step: { ...ROW, runs: { ...ROW.runs, versionName: "Spring intake" } },
      }),
    ]);
    await heading();

    expect(fieldsIn()["What it runs"]).toBe(
      `Question Summarise \u00b7 Version 2 \u00b7 Spring intake`,
    );
    const link = screen.getByRole("link", { name: /Summarise/ });
    expect(
      [...link.querySelectorAll("bdi")].map((each) => each.textContent),
    ).toEqual(["Summarise", "Spring intake"]);
    expect(link).toHaveAttribute(
      "href",
      `/groups/${KEY}/questions/${ENTRY}?version=${QUESTION_VERSION}`,
    );
  });

  it("leads About it with where the step is, its label in the facts' own label column beside its badge and why under the badge, and puts no badge on the panel's heading line", async () => {
    opening([reply(WAITING)]);
    await heading();

    const panel = screen.getByRole("region", { name: "Overview" });
    const fields = panel.querySelector("dl")!;
    const label = within(fields).getAllByRole("term")[0]!;
    const value = label.nextElementSibling!;
    expect(label.textContent).toBe("Status");
    expect(Object.keys(fieldsIn())[0]).toBe("Status");
    expect(label.parentElement).toBe(fields);
    expect(fields.firstElementChild).toBe(label);
    expect(value.tagName).toBe("DD");
    const title = within(panel).getByRole("heading", { name: "Overview" });
    expect(getComputedStyle(title).marginBottom).toBe("16px");
    const header = title.closest("header")!;
    const fold = within(header).getByRole("button", {
      name: "Overview",
      expanded: true,
    });
    const body = document.getElementById(fold.getAttribute("aria-controls")!)!;
    expect(panel).toHaveAttribute("aria-labelledby", title.id);
    expect(header.nextElementSibling).toBe(body);
    expect(body.firstElementChild!.firstElementChild!.firstElementChild).toBe(
      fields,
    );
    expect(header).not.toContainElement(fields);
    expect(header.textContent).toBe("Overview");
    expect(getComputedStyle(fields).marginTop).toBe("0px");
    expect(getComputedStyle(fields).display).toBe("grid");
    expect([
      getComputedStyle(fields).gridTemplateColumns,
      getComputedStyle(fields).alignItems,
    ]).toEqual(["112px minmax(0, 1fr)", "baseline"]);
    expect(value.firstElementChild).toHaveTextContent(/^Waiting$/);
    expect(
      value.firstElementChild!.querySelector(
        'svg[data-testid="HourglassEmptyIcon"]',
      ),
    ).not.toBeNull();
    expect(value.lastElementChild).toHaveTextContent("Waiting for a reviewer.");
    const why = [...value.children].slice(1);
    expect(why.length).toBeGreaterThan(0);
    expect(
      why.map((line) => {
        const drawn = getComputedStyle(line);
        return [drawn.display, drawn.color, drawn.fontSize, drawn.lineHeight];
      }),
    ).toEqual(why.map(() => ["block", "rgb(88, 102, 108)", "13px", "20px"]));
    expect(
      [...title.parentElement!.children].filter(
        (beside) => beside !== title && !beside.contains(fields),
      ),
    ).toEqual([]);
  });

  it("folds About it on a step page first measured narrow to a strip across before the work, opening it over the page", async () => {
    localStorage.removeItem("lilradish.panel.about");
    opening([reply(WAITING)], PAGE, undefined, undefined, undefined, 900);
    await heading();
    const strip = screen.getByRole("button", {
      name: "Overview",
      expanded: false,
    });
    expect(strip.textContent).toBe("Overview");
    expect(
      strip.compareDocumentPosition(part("Inputs")) &
        Node.DOCUMENT_POSITION_FOLLOWING,
    ).toBe(Node.DOCUMENT_POSITION_FOLLOWING);
    expect(screen.queryByRole("region", { name: "Overview" })).toBeNull();

    await userEvent.click(strip);

    const panel = await screen.findByRole("dialog", { name: "Overview" });
    expect(document.getElementById(strip.getAttribute("aria-controls")!)).toBe(
      panel,
    );
    expect(within(panel).getByText("Order")).toBeVisible();
    expect(screen.queryByRole("heading", { level: 1 })).toBeNull();
    const header = within(panel).getByRole("heading", {
      name: "Overview",
    }).parentElement!;
    expect([
      getComputedStyle(header).padding,
      getComputedStyle(header).borderBottomStyle,
    ]).toEqual(["18px 24px 14px", "solid"]);
    expect(header.nextElementSibling!).toContainElement(
      panel.querySelector("dl"),
    );
    expect(getComputedStyle(panel.querySelector("dl")!).marginTop).toBe("0px");
  });

  it("draws what went in, what came out and its tries first, and About it after them in a column of its own, the step's name the page's one first-level heading", async () => {
    opening([reply(WAITING)]);
    const title = await heading();

    const about = screen.getByRole("region", { name: "Overview" });
    const wide = [
      part("Inputs"),
      part("Output"),
      screen.getByRole("heading", { level: 2, name: /^Attempts/ }),
    ];
    for (const each of wide) {
      expect(
        each.compareDocumentPosition(about) & Node.DOCUMENT_POSITION_FOLLOWING,
      ).toBeTruthy();
      expect(about).not.toContainElement(each);
      expect(about.previousElementSibling).toContainElement(each);
    }
    expect(screen.getAllByRole("heading", { level: 1 })).toEqual([title]);
    expect(
      within(about).getByRole("heading", { level: 2, name: "Overview" }),
    ).toBeInTheDocument();
    expect(
      getComputedStyle(about.querySelector("dl")!).gridTemplateColumns,
    ).toBe("112px minmax(0, 1fr)");
    expect(laysPairsBeside(part("Output").querySelector("dl")!)).toBe(true);
    const frame = about.parentElement!;
    expect(getComputedStyle(frame).display).toBe("flex");
    expect(getComputedStyle(frame).gap).toBe("24px");
    expect(getComputedStyle(about).width).toBe("360px");
  });

  it.each([
    [
      "unsent",
      "Waiting for the model's review, which the system will request automatically.",
    ],
    ["out", "Waiting for the model's review — request in progress."],
    [
      "turned_away",
      "Waiting for the model's review, which could not be sent: the model service turned the request away.",
    ],
    [
      "too_long",
      "The review request would be too long for the reviewing model, so it was not sent. Someone who can review does it instead.",
    ],
    [
      "list_not_here",
      "This couldn't go to the reviewing model: a reference list it uses isn't available here, so a person reviews it instead.",
    ],
    [
      "takes_no_longer_declared",
      "This couldn't go to the reviewing model: the installed version of this code step no longer defines its inputs, so a person reviews it instead.",
    ],
    [
      "no_longer_declared",
      "This couldn't go to the reviewing model: the installed version of this code step no longer defines its outputs, so a person reviews it instead.",
    ],
  ])(
    "draws a model review that is %s as waiting on the step page",
    async (review, sentence) => {
      const fallback = [
        "too_long",
        "list_not_here",
        "takes_no_longer_declared",
        "no_longer_declared",
      ].includes(review);
      opening([
        reply({
          ...WAITING,
          step: {
            ...ROW,
            reviewer: { kind: "model", model: "small" },
            where: {
              ...ROW.where,
              review,
              values: [
                { field: "summary", on: fallback ? "review_at_gate" : "model" },
              ],
              waitsOn: fallback ? "review_at_gate" : "model",
            },
            stateActs: fallback
              ? ["review"]
              : review === "turned_away"
                ? ["try_sending"]
                : [],
            stateRefusals: fallback
              ? []
              : [{ act: "review", refusal: "REVIEW_NOT_A_PERSONS" }],
          },
        }),
      ]);
      await heading();
      expect(fieldsIn()["Status"]).toContain(sentence);
      expect(fieldsIn()["Status"]).toMatch(/^Waiting/);
      expect(fieldsIn()["Status"]).not.toContain("Blocked");
      expect(
        screen.queryByText("The model service turned the request away."),
      ).toBeNull();
    },
  );

  it("says before what went in why an act the step holds back from the reader is held back, as the step's message in the run does, and not in About it", async () => {
    opening([
      reply({
        ...WAITING,
        step: {
          ...ROW,
          stateActs: ["review"],
          stateRefusals: [],
        },
      }),
    ]);
    await heading();

    const said = screen.getByText("You don't have permission to review it.");
    expect(
      screen.getByRole("region", { name: "Overview" }),
    ).not.toContainElement(said);
    expect(
      said.compareDocumentPosition(part("Inputs")) &
        Node.DOCUMENT_POSITION_FOLLOWING,
    ).toBeTruthy();
    expect(screen.queryByText(/ACT_NOT_PERMITTED/)).toBeNull();
  });

  it("says nothing of an act held back where the step holds none back from the reader", async () => {
    opening([reply(WAITING)], undefined, undefined, undefined, {
      userId: DAN.userId,
      permissions: ["review_at_gate"],
    });
    await heading();

    expect(screen.queryByText(/^You don't have permission to/)).toBeNull();
    expect(screen.queryByText(/^This step can't be asked again/)).toBeNull();
  });

  it.each([
    [
      "what it runs has a readable name",
      { versionName: null, kind: "question", name: "Check the claim" },
      "Check the claim",
    ],
    [
      "what it runs has no readable name",
      { versionName: null, kind: "code_step", codeStep: "check_claim" },
      "check the claim",
    ],
  ])(
    "names the later step reading a code step's refused field by its message title when %s",
    async (_case, runs, title) => {
      const later = {
        ...ROW,
        producedByUserId: null,
        producedByKind: null,
        stepId: "0000000a-0000-4000-8000-000000000ca1",
        order: 3,
        name: "check_the_claim",
        runs,
        state: "not_started",
        where: undefined,
        tries: undefined,
        stateActs: [],
        stateRefusals: [],
      };
      const failed = FAILED_CODE;
      const { sent } = opening(
        [reply(failed)],
        PAGE,
        [
          reply({
            run: failed.run,
            steps: [failed.step, later],
            gaveBack: { declares: "nothing" },
            declarations: failed.declarations,
          }),
        ],
        undefined,
        { userId: CAT.userId, permissions: ["answer_step"] },
      );
      await screen.findByText(
        `This code step's output ${setApart("Claim reference")} no longer matches what ${setApart(title)} expects from it.`,
      );
      expect(
        screen.getByText(/^This code step's outputs, as installed now/),
      ).toBeVisible();
      expect(
        requestsTo(sent).filter((each) => each === `GET ${STEPS}`),
      ).toHaveLength(1);
      expect(
        screen.queryByRole("button", { name: "Answer it yourself" }),
      ).toBeNull();
      expect(screen.queryByText(new RegExp(later.stepId))).toBeNull();
    },
  );

  it("says only what is known when the step-list read succeeds without the later reader", async () => {
    const { sent } = opening(
      [reply(FAILED_CODE)],
      undefined,
      undefined,
      undefined,
      { userId: CAT.userId, permissions: ["answer_step"] },
    );
    await screen.findByText(UNKNOWN_SAID);

    expect(screen.queryByText(/what a later step reads from it/)).toBeNull();
    expect(screen.queryByRole("alert")).toBeNull();
    expect(fieldsIn()["Status"]).toMatch(/^Failed/);
    expect(
      requestsTo(sent).filter((each) => each === `GET ${STEPS}`),
    ).toHaveLength(1);
  });

  it("keeps the step page readable when its step-list read fails, naming the reader as a later step", async () => {
    const failed = FAILED_CODE;
    opening(
      [reply(failed)],
      PAGE,
      [[JSON.stringify({ code: "READ_FAILED" }), 503]],
      undefined,
      { userId: CAT.userId, permissions: ["answer_step"] },
    );
    await heading();
    expect(
      screen.getByText(
        `This code step's output ${setApart("Claim reference")} no longer matches what a later step expects from it.`,
      ),
    ).toBeVisible();
    expect(screen.queryByRole("alert")).toBeNull();
    expect(fieldsIn()["Status"]).toMatch(/^Failed/);
  });

  it("removes the step, run path and its values when the step-list read says the run is no longer in view", async () => {
    const { sent } = opening([reply(FAILED_CODE)], PAGE, [
      [JSON.stringify({ code: "RUN_NOT_IN_VIEW" }), 404],
    ]);
    expect(
      await screen.findByText("That run isn't here, or you can't see it."),
    ).toBeVisible();
    expect(screen.queryByRole("heading", { level: 1 })).toBeNull();
    expect(screen.queryByText("Claim reference")).toBeNull();
    expect(screen.queryByRole("region", { name: "Overview" })).toBeNull();
    expect(requestsTo(sent)).toContain(`GET ${STEPS}`);
  });

  it("stops background step reads after the step-list read closes the run", async () => {
    vi.useFakeTimers({ toFake: ["setTimeout", "clearTimeout"] });
    const listed = deferred<readonly [string, number]>();
    const { sent } = opening(
      [reply({ ...FAILED_CODE, rereadAfterSeconds: 1 })],
      PAGE,
      [listed.promise],
    );
    await landing(() => screen.queryByRole("heading", { level: 1 }) !== null);

    await act(async () =>
      listed.settle([JSON.stringify({ code: "RUN_NOT_IN_VIEW" }), 404]),
    );
    await landing(
      () =>
        screen.queryByText("That run isn't here, or you can't see it.") !==
        null,
    );
    const stepCount = requestsTo(sent).filter(
      (each) => each === `GET ${STEP}`,
    ).length;

    await act(() => vi.advanceTimersByTimeAsync(3000));

    expect(
      requestsTo(sent).filter((each) => each === `GET ${STEP}`),
    ).toHaveLength(stepCount);
    expect(screen.queryByRole("heading", { level: 1 })).toBeNull();
  });

  it("waits for the step-list read to settle before naming the reader and uses its title once it succeeds", async () => {
    let finishList: (answer: readonly [string, number]) => void = () => {
      throw new Error("No pending list read");
    };
    const pendingList: Reply = new Promise((resolve) => {
      finishList = resolve;
    });
    opening([reply(FAILED_CODE)], PAGE, [pendingList], undefined, {
      userId: CAT.userId,
      permissions: ["answer_step"],
    });
    await heading();
    expect(screen.getByText(UNKNOWN_SAID)).toBeVisible();
    expect(screen.queryByText(/what a later step reads from it/)).toBeNull();
    const later = {
      ...ROW,
      producedByUserId: null,
      producedByKind: null,
      stepId: LATER_STEP_ID,
      order: 3,
      name: "check_claim",
      runs: { ...ROW.runs, name: "Check the claim" },
      state: "not_started",
      where: undefined,
      tries: undefined,
      stateActs: [],
      stateRefusals: [],
    };
    await act(async () => {
      finishList([
        JSON.stringify({
          run: FAILED_CODE.run,
          gaveBack: { declares: "nothing" },
          declarations: FAILED_CODE.declarations,
          steps: [FAILED_CODE.step, later],
        }),
        200,
      ]);
      await Promise.resolve();
    });
    expect(
      await screen.findByText(
        `This code step's output ${setApart("Claim reference")} no longer matches what ${setApart("Check the claim")} expects from it.`,
      ),
    ).toBeVisible();
    expect(screen.queryByText(UNKNOWN_SAID)).toBeNull();
  });

  it("abandons the old run's step-list read on navigation and keeps the new reader's title after its old reply arrives", async () => {
    const laterId = "0000000a-0000-4000-8000-000000000ca1";
    const otherRun = "00000008-0000-4000-8000-000000000ca2";
    const otherSteps = `/api/groups/${GROUP}/runs/${otherRun}/steps`;
    let finishList: (answer: readonly [string, number]) => void = () => {
      throw new Error("No pending list read");
    };
    const pendingList: Reply = new Promise((resolve) => {
      finishList = resolve;
    });
    const failed = FAILED_CODE;
    const later = {
      ...ROW,
      producedByUserId: null,
      producedByKind: null,
      stepId: laterId,
      order: 3,
      name: "check_claim",
      state: "not_started",
      where: undefined,
      tries: undefined,
      stateActs: [],
      stateRefusals: [],
    };
    const next = {
      ...failed,
      run: { ...failed.run, runId: otherRun, number: 8 },
    };
    const listed = (name: string, run = failed.run) => ({
      run,
      gaveBack: { declares: "nothing" },
      declarations: failed.declarations,
      steps: [failed.step, { ...later, runs: { ...later.runs, name } }],
    });
    const { sent, router } = opening(
      [reply(failed)],
      PAGE,
      [pendingList],
      {
        [`GET ${otherSteps}/${STEP_ID}`]: [reply(next)],
        [`GET ${otherSteps}`]: [reply(listed("Check the new claim", next.run))],
      },
      { userId: CAT.userId, permissions: ["answer_step"] },
    );
    await heading();
    expect(screen.getByText(UNKNOWN_SAID)).toBeVisible();
    expect(screen.queryByText(/what a later step reads from it/)).toBeNull();
    const oldSignal = sent.mock.calls.find(([input]) =>
      String(input).endsWith(STEPS),
    )![1]!.signal!;
    expect(oldSignal.aborted).toBe(false);
    await act(() =>
      router.navigate(`/groups/${KEY}/work/${otherRun}/steps/${STEP_ID}`),
    );
    const newLine = `This code step's output ${setApart("Claim reference")} no longer matches what ${setApart("Check the new claim")} expects from it.`;
    await screen.findByText(newLine);
    expect(oldSignal.aborted).toBe(true);
    await act(async () => {
      finishList([JSON.stringify(listed("Check the old claim")), 200]);
      await Promise.resolve();
    });
    expect(screen.getByText(newLine)).toBeVisible();
    expect(screen.queryByText(/Check the old claim/)).toBeNull();
    expect(fieldsIn()["Run"]).toBe("Run 8");
  });

  it("titles a route step by its own name without claiming a producer or reviewer", async () => {
    opening([reply(ROUTE_PAGE)], PAGE, listedStep(ROUTE_PAGE));

    expect(await heading()).toHaveTextContent(setApart("file it"));
    expect(fieldsIn()).toMatchObject({
      "What it runs": "Route",
    });
    expect(fieldsIn()).not.toHaveProperty("Produced by");
    expect(fieldsIn()).not.toHaveProperty("Reviewed by");
    expect(screen.queryByRole("link", { name: /Summarise/ })).toBeNull();
    expect(screen.getByText("Route").querySelector("svg")).toHaveAttribute(
      "data-testid",
      "AltRouteIcon",
    );
  });

  it("says where a route step failed with the term no branch took isolated, a character in it that shows nothing drawn as a marker and never itself", async () => {
    const page = {
      ...ROUTE_PAGE,
      run: { ...ROUTE_PAGE.run, state: "failed" },
      rereadAfterSeconds: undefined,
      step: {
        ...ROUTE_PAGE.step,
        state: "failed",
        where: {
          kind: "failed",
          reason: "unclaimed_value",
          since: "2026-09-26T09:00:00Z",
          failedUnclaimed: { term: `Demo${ZERO_WIDTH_SPACE}Sample` },
        },
      },
    };
    opening([reply(page)], PAGE, listedStep(page));
    await heading();

    const marker = screen.getByRole("img", { name: SHOWS_NOTHING });
    expect(fieldsIn()["Status"]).toBe(
      "FailedNo branch of this route handles “DemoU+200BSample”, and it has no fallback. To go on, create a new version of this workflow and start a new run.",
    );
    expect(marker.closest("bdi")?.textContent).toBe("DemoU+200BSample");
    expect(document.body.textContent).not.toContain("[object Object]");
    expect(document.body.innerHTML).not.toContain(ZERO_WIDTH_SPACE);
  });

  it("says why a stopped entry holds its next production back before the step's facts", async () => {
    const page = {
      ...HELD_TURNED_AWAY,
      step: {
        ...HELD_TURNED_AWAY.step,
        cost: {
          callsAModel: true,
          sent: "0",
          cameBack: "0",
          spent: "0",
          cameBackUnknown: false,
        },
        stateActs: [],
        where: {
          kind: "held_back",
          reason: "entry_stopped",
          since: "2026-09-26T09:00:00Z",
          stopped: { what: "entry", by: CAT, at: "2026-09-26T09:00:00Z" },
        },
      },
      wentIn: WAITING.wentIn,
      wentInFrom: "not_yet_sent",
    };
    opening([reply(page)], PAGE, listedStep(page));
    await heading();

    const notice = screen.getByText(/^What this step runs has been stopped,/);
    expect(notice).toHaveTextContent(
      "What this step runs has been stopped, so nothing will be produced until it is allowed to be used again.",
    );
    expect(
      notice.compareDocumentPosition(document.querySelector("dl")!) &
        Node.DOCUMENT_POSITION_FOLLOWING,
    ).toBeTruthy();
    expect(fieldsIn()["Status"]).toBe("Blocked");
  });

  it("opens the child run and the deepest step from a route without offering parent try actions", async () => {
    const child = "00000008-0000-4000-8000-000000000c82";
    const deepest = "00000009-0000-4000-8000-000000000c82";
    const page = {
      ...ROUTE_PAGE,
      run: { ...HEADER, at: { stepId: STEP_ID, name: "file_it" } },
      declarations: {
        ...ROUTE_PAGE.declarations,
        "00000007-0000-4000-8000-000000000c82": { takes: [], gives: [] },
      },
      step: {
        ...ROUTE_PAGE.step,
        where: { kind: "running", on: "person", waitsOn: "answer_step" },
        beneath: {
          runId: child,
          number: 8,
          runs: {
            versionName: null,
            kind: "workflow",
            entryId: "00000006-0000-4000-8000-000000000c82",
            name: "Demo Sample",
            version: 2,
            versionId: "00000007-0000-4000-8000-000000000c82",
          },
          at: { runId: child, number: 8, stepId: deepest, name: "make_it" },
        },
      },
      cameOut: [],
    };
    opening([reply(page)], PAGE, listedStep(page));

    await heading();
    expect(screen.getByText("Sub-run", { selector: "dt" })).toBeVisible();
    expect(fieldsIn()["Sub-run"]).toContain("Open run 8");
    expect(fieldsIn()["Part of"]).toBeUndefined();
    expect(
      screen.getByRole("link", { name: "Open run 8" }).getAttribute("href"),
    ).toBe(`/groups/${KEY}/work/${child}`);
    expect(
      screen
        .getByRole("link", { name: setApart("make_it") })
        .getAttribute("href"),
    ).toBe(`/groups/${KEY}/work/${child}/steps/${deepest}`);
    expect(screen.queryByRole("button", { name: "Ask again" })).toBeNull();
    expect(
      screen.queryByRole("button", { name: "Answer it yourself" }),
    ).toBeNull();
  });

  it("draws a whole withheld input without treating it as absent or historical", async () => {
    opening([
      reply({
        ...WAITING,
        wentIn: [
          {
            input: "text",
            from: { kind: "run_input", path: "ticket" },
            withheld: true,
          },
        ],
      }),
    ]);
    await heading();

    expect(part("Inputs")).toHaveTextContent(
      "You don't have permission to see this.",
    );
    expect(part("Output")).toHaveTextContent("Summary");
    expect(document.body).not.toHaveTextContent(
      "Shown as originally given; it no longer matches the field's current format.",
    );
    expect(
      screen.getByRole("heading", { name: "Overview" }),
    ).toBeInTheDocument();
  });

  it("says nothing has gone in and nothing come out of a step not started, and no try is made", async () => {
    const { where: _where, ...unplaced } = ROW;
    opening([
      reply({
        run: HEADER,
        declarations: DECLARATIONS,
        step: {
          ...unplaced,
          producedByUserId: null,
          producedByKind: null,
          stateActs: [],
          stateRefusals: [],
          state: "not_started",
        },
        triesMade: [],
      }),
    ]);
    await heading();

    expect(part("Inputs")).toHaveTextContent("Nothing yet.");
    expect(part("Output")).toHaveTextContent("Nothing yet.");
    expect(part(/^Attempts/)).toHaveTextContent("No attempts yet.");
    expect(within(part("Inputs")).queryByRole("term")).toBeNull();
    expect(within(part(/^Attempts/)).queryByRole("table")).toBeNull();
    for (const name of ["Inputs", "Output", /^Attempts/]) {
      expect(
        within(part(name)).getByRole("heading", { level: 2, name }),
      ).toBeInTheDocument();
      expect(
        within(part(name)).queryByRole("heading", { level: 3, name }),
      ).toBeNull();
    }
  });

  it("names each input by its label, where it came from and what it was given", async () => {
    opening([reply(WAITING)]);
    await heading();

    const wentIn = part("Inputs");
    expect(within(wentIn).getByRole("term")).toHaveTextContent("Text");
    expect(within(wentIn).getByRole("definition")).toHaveTextContent(
      "Printer on fire",
    );
    expect(
      within(wentIn).getByText(`${setApart("Ticket")} (run input)`),
    ).toBeVisible();
    expect(wentIn).not.toHaveTextContent("Nothing is sent yet");
  });

  it("names a received value's source run from the published answer", async () => {
    const [text] = WAITING.wentIn;
    opening([
      reply({
        ...WAITING,
        wentIn: [
          {
            ...text,
            from: {
              kind: "run_input",
              path: "ticket",
              runId: "00000008-0000-4000-8000-000000000ca2",
              runNumber: 6,
            },
          },
        ],
      }),
    ]);
    await heading();

    const wentIn = part("Inputs");
    expect(wentIn).toHaveTextContent(`${setApart("ticket")} (input of run 6)`);
    expect(wentIn).not.toHaveTextContent(`${setApart("Ticket")} (run input)`);
  });

  it("downloads a file from the readable step's run even when its input came from another run", async () => {
    const sourceRunId = "00000008-0000-4000-8000-000000000ca2";
    const file = {
      id: "0000000f-0000-4000-8000-000000000ca1",
      name: "Imaginary receipt.TAR.GZ",
      size: 5,
    };
    const attachment = {
      name: "text",
      label: "Text",
      kind: "file",
      types: [".gz", ".tar.gz"],
      maximumBytes: 12,
      mustBeGiven: true,
    };
    const page = {
      ...UNKEPT,
      declarations: {
        ...UNKEPT.declarations,
        [VERSION]: {
          ...UNKEPT.declarations[VERSION],
          takes: UNKEPT.declarations[VERSION].takes.map((field) =>
            field.name === "ticket"
              ? { ...attachment, name: field.name, label: field.label }
              : field,
          ),
        },
        file_claim: { ...FILE_CLAIM, takes: [attachment] },
      },
      wentIn: [
        {
          input: "text",
          from: {
            kind: "run_input",
            path: "ticket",
            runId: sourceRunId,
            runNumber: 6,
          },
          value: file,
        },
      ],
    };
    const { sent } = opening([reply(page)], PAGE, listedStep(page));
    await heading();

    const wentIn = part("Inputs");
    const link = within(wentIn).getByRole("link", {
      name: `Download ${setApart("Imaginary receipt.TAR.GZ")}, 5 B`,
    });
    expect(link).toHaveAttribute(
      "href",
      `/api/groups/${GROUP}/runs/${RUN_ID}/files/${file.id}`,
    );
    expect(link).toHaveAttribute("download");
    expect(link).toHaveStyle({ display: "block" });
    expect(
      [...link.querySelectorAll("bdi")]
        .filter((each) => each.closest("[hidden]") === null)
        .map((each) => each.textContent),
    ).toEqual(["Imaginary receipt.TAR.GZ"]);
    expect(link.getAttribute("href")).not.toContain(sourceRunId);
    expect(wentIn).not.toHaveTextContent("Type:");
    expect(wentIn).toHaveTextContent("5 B");
    expect(
      within(wentIn)
        .getAllByRole("link")
        .filter((each) => each.hasAttribute("download")),
    ).toEqual([link]);
    expect(wentIn.querySelector("iframe, img, embed, object")).toBeNull();
    expect(requestsTo(sent).filter((each) => each.includes("/files/"))).toEqual(
      [],
    );
  });

  it("keeps a long text intact in its expandable input and attempt value cards", async () => {
    keptExpanded();
    vi.spyOn(HTMLElement.prototype, "clientHeight", "get").mockImplementation(
      function (this: HTMLElement) {
        return this.hasAttribute("data-value-content") ? 192 : 0;
      },
    );
    vi.spyOn(HTMLElement.prototype, "scrollHeight", "get").mockImplementation(
      function (this: HTMLElement) {
        return this.hasAttribute("data-value-content") ? 384 : 0;
      },
    );
    vi.spyOn(HTMLElement.prototype, "getBoundingClientRect").mockImplementation(
      function (this: HTMLElement) {
        return DOMRect.fromRect({
          width: Element.prototype.getBoundingClientRect.call(this).width,
          height: this.parentElement?.hasAttribute("data-value-content")
            ? 384
            : 0,
        });
      },
    );
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

    const wentIn = part("Inputs");
    expect(within(wentIn).getByRole("definition")).toHaveTextContent(
      "Printer on fire. It caught again.",
    );
    expect(within(wentIn).getByRole("definition")).toHaveAttribute("inert");
    const expandInput = within(wentIn).getByRole("button", {
      name: "Show all",
    });
    expect(expandInput).toHaveAttribute("aria-expanded", "false");
    await userEvent.click(expandInput);
    expect(
      within(wentIn).getByRole("button", { name: "Show less" }),
    ).toHaveAttribute("aria-expanded", "true");
    expect(within(wentIn).getByRole("definition")).toHaveTextContent(
      "Printer on fire. It caught again.",
    );
    await userEvent.click(
      screen.getByRole("button", { name: "Attempt 1 of 2" }),
    );
    const opened = await screen.findByRole("dialog", {
      name: "Attempt 1 of 2",
    });
    expect(opened).toHaveTextContent("A fire. In the printer.");
    await userEvent.click(
      within(opened).getByRole("button", { name: "Show all" }),
    );
    expect(
      within(opened).getByRole("button", { name: "Show less" }),
    ).toHaveAttribute("aria-expanded", "true");
    expect(opened).toHaveTextContent("A fire. In the printer.");
  });

  it.each(
    ([1400, 2400] as const).flatMap((page) =>
      (["Close", "Escape", "backdrop"] as const).map(
        (shutting) => [page, shutting] as const,
      ),
    ),
  )(
    "lets an attempt go when its drawer over a page %s pixels wide is shut by %s, its row marked beneath it until then: no panel or strip is left, the address loses it, and the keyboard is back on its row",
    async (page, shutting) => {
      localStorage.setItem("lilradish.panel.row", "collapsed");
      onTestFinished(() => localStorage.removeItem("lilradish.panel.row"));
      const { router } = opening(
        [reply({ ...WAITING, triesMade: [REFUSED_TRY, WAITING_TRY] })],
        PAGE,
        undefined,
        {},
        undefined,
        page,
      );
      await heading();
      const attempt = screen.getByRole("button", { name: "Attempt 1 of 2" });
      await userEvent.click(attempt);
      const opened = await screen.findByRole("dialog", {
        name: "Attempt 1 of 2",
      });
      expect(router.state.location.search).toContain("try=1");
      expect(
        screen.queryByRole("region", { name: "Attempt 1 of 2" }),
      ).toBeNull();
      const row = attempt.closest("tr")!;
      expect(row).toHaveClass("Mui-selected");
      expect(attempt).toHaveAttribute("aria-current", "true");
      expect(getComputedStyle(row.cells[0]!).boxShadow).toBe(
        "inset 3px 0 0 #1c5b81",
      );
      expect(getComputedStyle(row.cells[1]!).boxShadow).not.toBe(
        "inset 3px 0 0 #1c5b81",
      );
      expect(
        attempt.closest("tbody")!.querySelectorAll("tr.Mui-selected"),
      ).toHaveLength(1);

      if (shutting === "Close")
        await userEvent.click(
          within(opened).getByRole("button", { name: "Close" }),
        );
      else if (shutting === "Escape") await userEvent.keyboard("{Escape}");
      else
        await userEvent.click(
          document.querySelector<HTMLElement>(".MuiBackdrop-root")!,
        );
      await waitFor(() =>
        expect(screen.queryByRole("dialog", { hidden: true })).toBeNull(),
      );

      expect(router.state.location.search).not.toContain("try=");
      expect(row).not.toHaveClass("Mui-selected");
      expect(attempt).not.toHaveAttribute("aria-current");
      expect(
        screen
          .queryAllByRole("button", { name: "Attempt 1 of 2", hidden: true })
          .filter((each) => each.hasAttribute("aria-controls")),
      ).toEqual([]);
      await waitFor(() =>
        expect(
          screen.getByRole("button", { name: "Attempt 1 of 2" }),
        ).toHaveFocus(),
      );
    },
  );

  it("says what went in is what it would take now, where nothing is sent yet", async () => {
    opening([reply({ ...WAITING, wentInFrom: "not_yet_sent" })]);
    await heading();

    expect(part("Inputs")).toHaveTextContent(
      "Not sent yet — these are the inputs it would use now.",
    );
  });

  it("lays out what went in as the question's drawing says, a heading it writes one level below the section's own", async () => {
    opening([reply({ ...WAITING, drawing: "# {{text}}" })]);
    await heading();

    const wentIn = part("Inputs");
    expect(
      within(wentIn)
        .getAllByRole("heading")
        .map((each) => [each.tagName, each.textContent]),
    ).toEqual([
      ["H2", "Inputs"],
      ["H3", "Printer on fire"],
    ]);
    expect(wentIn.querySelector("dt:not(aside *)")).toBeNull();
    expect(wentIn.querySelector("aside dt")).toHaveTextContent("Text");
  });

  it("says a step that has produced nothing so, rather than listing nothing", async () => {
    const { cameOut: _cameOut, ...nothingOut } = WAITING;
    opening([reply({ ...nothingOut, triesMade: [] })]);
    await heading();

    expect(part("Output")).toHaveTextContent("No output yet.");
    expect(within(part("Output")).queryByRole("term")).toBeNull();
  });

  it("lists every try of the value, oldest first and the refused one with them, and opens one with what it produced and the words it was refused with", async () => {
    keptExpanded();
    const { router } = opening([reply(WAITING)], `${PAGE}?sort=run`);
    await heading();

    expect(part("Output")).toHaveTextContent(
      `${setApart("Summary")}Waiting for review`,
    );
    expect(tryRows()).toEqual([
      [
        "Attempt 1 of 2",
        setApart("Cat"),
        setApart("Dan"),
        "Rejected in review.",
        "None — no model is used.",
      ],
      [
        "Attempt 2 of 2",
        setApart("Cat"),
        "Not reviewed yet.",
        "Waiting for review.",
        "None — no model is used.",
      ],
    ]);
    expect(
      within(part("Attempts"))
        .getAllByRole("rowheader")
        .map((cell) => cell.textContent),
    ).toEqual(["Attempt 1 of 2", "Attempt 2 of 2"]);

    await userEvent.click(
      screen.getByRole("button", { name: "Attempt 1 of 2" }),
    );

    const opened = await screen.findByRole("dialog", {
      name: "Attempt 1 of 2",
    });
    expect(opened).toHaveTextContent("A fire.");
    expect(opened).toHaveTextContent("Read it twice.");
    expect(opened).toHaveTextContent("Rejected: Too short.");
    const reasons = within(opened).getAllByText("Too short.");
    expect(reasons).toHaveLength(2);
    for (const reason of reasons) {
      expect(reason.closest('[dir="auto"]')).toBeInTheDocument();
    }
    expect(opened).toHaveTextContent("The run itself");
    expect(opened).not.toHaveTextContent("A printer caught fire.");
    expect(router.state.location.search).toBe("?sort=run&value=summary&try=1");
  });

  it("shows an extra try's number and allowance with the explanation marked in its row and open panel", async () => {
    keptExpanded();
    opening([
      reply({
        ...WAITING,
        step: {
          ...ROW,
          tries: { current: 3, declared: 2, beyond: true },
          where: { ...ROW.where, number: 3 },
        },
        triesMade: [
          REFUSED_TRY,
          WAITING_TRY,
          { ...WAITING_TRY, number: 3, beyond: true },
        ],
      }),
    ]);
    await heading();
    const rowLink = within(part("Attempts")).getByRole("button", {
      name: "Attempt 3, beyond the 2 allowed",
    });
    expect(rowLink.textContent).toBe("Attempt 3, beyond the 2 allowed");
    const hints = [...rowLink.querySelectorAll("span")];
    expect(hints.map((part) => part.textContent)).toEqual([
      "beyond the",
      "allowed",
    ]);
    expect(
      hints.every((part) => getComputedStyle(part).fontStyle === "italic"),
    ).toBe(true);

    await userEvent.click(rowLink);
    const opened = await screen.findByRole("dialog", {
      name: "Attempt 3, beyond the 2 allowed",
    });
    expect(
      within(opened).getByRole("heading", {
        name: "Attempt 3, beyond the 2 allowed",
      }),
    ).toBeVisible();
    expect(opened).toHaveTextContent("A printer caught fire.");
  });

  it("draws the words a model's review refused a value in, in the try opened, a character in them that shows nothing as a marker and never itself", async () => {
    keptExpanded();
    opening(
      [
        reply({
          ...MODEL_WAITING,
          triesMade: [
            {
              ...SURE_TRY,
              values: [
                {
                  ...SURE_TRY.values[0],
                  decision: {
                    outcome: "refused",
                    whyLines: "several",
                    why: `Too${ZERO_WIDTH_SPACE}short.`,
                  },
                },
              ],
              review: { asked: true, by: SURE_TRY.producedBy },
            },
            MODEL_WAITING.triesMade[1],
          ],
        }),
      ],
      `${PAGE}?try=1`,
    );

    const opened = await screen.findByRole("dialog", {
      name: "Attempt 1 of 2",
    });
    const markers = within(opened).getAllByRole("img", { name: SHOWS_NOTHING });
    expect(markers).toHaveLength(2);
    expect(opened).toHaveTextContent("Rejected:");
    for (const marker of markers) {
      expect(marker.closest('[dir="auto"]')?.textContent).toBe(
        "TooU+200Bshort.",
      );
    }
    expect(opened.innerHTML).not.toContain(ZERO_WIDTH_SPACE);
  });

  it("says in a model's try opened how sure it was of the value picked, in a quieter line under the model that produced it, and under no label of its own", async () => {
    keptExpanded();
    opening([reply(MODEL_WAITING)], `${PAGE}?try=1`);

    const opened = await screen.findByRole("dialog", {
      name: "Attempt 1 of 2",
    });

    const said = fieldsIn(opened);
    const sure = within(opened).getByText(`${setApart("Summary")}: 87%`);
    expect(sure.tagName).toBe("P");
    expect(sure.closest("dd")?.previousElementSibling?.textContent).toBe(
      "Produced by",
    );
    expect(said["Produced by"]).toBe(
      `small in careful${setApart("Summary")}: 87%`,
    );
    expect(Object.keys(said)).not.toContain("Confidence");
    expect(within(opened).queryByText("Confidence")).toBeNull();
  });

  it("rules each part of a try opened over the page off across the drawer's whole card, under a heading below the panel's and never a named region", async () => {
    keptExpanded();
    opening([reply(MODEL_WAITING)], `${PAGE}?try=1`);

    const opened = await screen.findByRole("dialog", {
      name: "Attempt 1 of 2",
    });

    expect(
      within(opened).getByRole("heading", { level: 3, name: "Attempt 1 of 2" }),
    ).toBeVisible();
    const review = within(opened)
      .getByRole("heading", { level: 4, name: "Review" })
      .closest("section")!;
    const style = getComputedStyle(review);
    expect(style.borderTopStyle).toBe("solid");
    expect([style.marginLeft, style.marginRight]).toEqual([
      "calc(-1 * var(--card-side-padding))",
      "calc(-1 * var(--card-side-padding))",
    ]);
    expect([
      style.paddingLeft,
      style.paddingRight,
      style.getPropertyValue("--card-side-padding"),
    ]).toEqual([
      "var(--card-side-padding)",
      "var(--card-side-padding)",
      "24px",
    ]);
    expect(
      within(opened).queryByRole("region", {
        name: /^(Details|What it found|Review)$/,
      }),
    ).toBeNull();
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
                decision: {
                  outcome: "refused",
                  whyLines: "several",
                  why: "Too short.",
                },
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
      keptExpanded();
      opening([reply(page)], `${PAGE}?try=1`);

      const opened = await screen.findByRole("dialog", {
        name: "Attempt 1 of 2",
      });

      expect(within(opened).queryByText("Confidence")).toBeNull();
      expect(opened).not.toHaveTextContent(/\d+%/);
    },
  );

  it("offers what a code step's code gave back as it came, shut until it is opened, beneath what went wrong", async () => {
    keptExpanded();
    const materialPages = [ERRORED];
    opening(
      materialPages.map(reply),
      `${PAGE}?try=1`,
      undefined,
      materialRoutes(materialPages),
      CONTENT_READER,
    );
    const opened = await screen.findByRole("dialog", {
      name: "Attempt 1 of 2",
    });
    const control = await within(opened).findByRole("button", {
      name: "What the code returned, in full",
    });
    expect(opened).toHaveTextContent(
      "The reference it gave back is not text. (cut short here)",
    );
    expect(
      within(opened).getByText("The reference it gave back is not text.")
        .tagName,
    ).toBe("BDI");
    expect(control).toHaveAttribute("aria-expanded", "false");
    expect(opened).not.toHaveTextContent('{"reference": 7}');

    await userEvent.click(control);

    expect(control).toHaveAttribute("aria-expanded", "true");
    expect(
      document.getElementById(control.getAttribute("aria-controls")!),
    ).toHaveTextContent('{"reference": 7}');
  });

  it("shuts what code gave back as another try is turned to, whatever was left open in the one before", async () => {
    keptExpanded();
    const materialPages = [
      {
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
      },
    ];
    const { router } = opening(
      materialPages.map(reply),
      `${PAGE}?try=1`,
      undefined,
      materialRoutes(materialPages),
      CONTENT_READER,
    );
    const first = await screen.findByRole("dialog", {
      name: "Attempt 1 of 3",
    });
    await userEvent.click(
      await within(first).findByRole("button", {
        name: "What the code returned, in full",
      }),
    );

    await act(() => router.navigate(`${PAGE}?try=2`));

    const second = await screen.findByRole("dialog", {
      name: "Attempt 2 of 3",
    });
    expect(
      await within(second).findByRole("button", {
        name: "What the code returned, in full",
      }),
    ).toHaveAttribute("aria-expanded", "false");
    expect(second).not.toHaveTextContent('{"reference": 8}');
    expect(second).not.toHaveTextContent('{"reference": 7}');
  });

  it("says why this system found a code step's try went wrong in the reader's words, its field by its label, and that what came back was not kept rather than offering it", async () => {
    keptExpanded();
    opening([reply(UNKEPT)], `${PAGE}?try=1`);

    const opened = await screen.findByRole("dialog", {
      name: "Attempt 1 of 2",
    });

    expect(fieldsIn(opened)).toMatchObject({
      Outcome: "An error occurred.",
      Error: `The code returned a value for ${setApart("Claim reference")} that fits the field but is too long to store.`,
      "What the code returned": "It couldn't be stored, so it can't be shown.",
    });
    expect(
      within(opened).queryByRole("button", {
        name: "What the code returned, in full",
      }),
    ).toBeNull();
    expect(opened).not.toHaveTextContent(/too_long_to_keep|\(cut short here\)/);
  });

  it.each([
    {
      returnState: "kept",
      returned: '{"reference":"A returned claim reference"}',
      erroredFor: { reason: "too_long", field: "reference" },
      output: undefined,
      expected: `The code returned a value for ${setApart("Claim reference")} longer than that field allows.`,
    },
    {
      returnState: "not kept",
      returned: undefined,
      erroredFor: {
        reason: "too_long",
        field: "reference",
        returnedNotKept: true,
      },
      output: undefined,
      expected: `The code returned a value for ${setApart("Claim reference")} longer than that field allows.`,
    },
    {
      returnState: "not returned",
      returned: undefined,
      erroredFor: {
        reason: "gives_otherwise",
        field: "reference",
        readBy: { output: "summary" },
      },
      output: SUMMARY,
      expected: `The code's output ${setApart("Claim reference")} no longer matched this workflow's output ${setApart("Summary")}.`,
    },
  ])(
    "shows the reason with returned evidence only when it is present, where the return is $returnState",
    async ({ returned, erroredFor, output, expected }) => {
      keptExpanded();
      const page = {
        ...UNKEPT,
        declarations: {
          ...UNKEPT.declarations,
          [VERSION]: {
            ...DECLARATIONS[VERSION],
            gives: output ? [output] : [],
          },
        },
        triesMade: [
          {
            ...UNKEPT.triesMade[0],
            returned,
            erroredFor,
          },
        ],
      };
      const materialPages = [page];
      opening(
        materialPages.map(reply),
        `${PAGE}?try=1`,
        [
          reply({
            run: page.run,
            steps: [page.step],
            gaveBack: output
              ? { declares: "values", standing: [] }
              : { declares: "nothing" },
            declarations: page.declarations,
          }),
        ],
        materialRoutes(materialPages),
        CONTENT_READER,
      );
      const opened = await screen.findByRole("dialog", {
        name: "Attempt 1 of 2",
      });
      if (returned !== undefined) {
        await within(opened).findByRole("button", {
          name: "What the code returned, in full",
        });
      }
      const shown = fieldsIn(opened);
      expect(shown["Error"]).toBe(expected);
      const full = within(opened).queryByRole("button", {
        name: "What the code returned, in full",
      });
      if (returned !== undefined) {
        expect(full).toBeVisible();
        expect(shown["What the code returned"]).toBe(
          "What the code returned, in full",
        );
        expect(opened).not.toHaveTextContent(returned);
        await userEvent.click(full!);
        expect(opened).toHaveTextContent(returned);
      } else if (erroredFor.returnedNotKept === true) {
        expect(full).toBeNull();
        expect(shown["What the code returned"]).toBe(
          "It couldn't be stored, so it can't be shown.",
        );
      } else {
        expect(full).toBeNull();
        expect(shown).not.toHaveProperty("What the code returned");
      }
    },
  );

  it.each([
    [
      "one that did not fit says which way, beneath how it ended",
      1,
      "The answer did not match the expected format.The model reached its maximum answer length, so its answer was cut off.",
    ],
    ["one still waiting says only how it ended", 2, "Waiting for review."],
  ])("says of a model's try opened, %s", async (_case, number, ended) => {
    keptExpanded();
    opening([reply(MODEL_MISFIT)], `${PAGE}?try=${number}`);

    const opened = await screen.findByRole("dialog", {
      name: `Attempt ${number} of 2`,
    });

    expect(fieldsIn(opened)["Outcome"]).toBe(ended);
    expect(opened).not.toHaveTextContent("cut_off");
  });

  it.each([
    [
      "did_not_fit",
      "The model's review was not in the expected format, so it was not used. This attempt is used up.",
    ],
    [
      "nothing_came_back",
      "The review request got no response, so this attempt is used up.",
    ],
    ["errored", "small (the review hit an error)"],
  ])(
    "draws a lost model review %s separately from a production's outcome",
    async (lost, sentence) => {
      keptExpanded();
      const reviewed = {
        ...SURE_TRY,
        values: [
          {
            field: "summary",
            value: "A fire.",
            now: "refused",
            confidence: 87,
          },
        ],
        review: {
          asked: true,
          by: { kind: "model", model: "small" },
          wentWrong: true,
          lost,
          ...(lost === "did_not_fit" ? { didNotFit: "words_missing" } : {}),
        },
        ended: "refused_on_review",
      };
      const page = {
        ...MODEL_REVIEW_WAITING,
        triesMade: [reviewed, MODEL_REVIEW_WAITING.triesMade[1]],
      };
      opening([reply(page)], `${PAGE}?try=1`, listedStep(page));
      const opened = await screen.findByRole("dialog", {
        name: "Attempt 1 of 2",
      });
      expect(fieldsIn(opened)["Outcome"]).toContain(sentence);
      const result = screen
        .getByRole("table", { name: /Attempts/, hidden: true })
        .querySelector("tbody tr")!
        .querySelectorAll("td")[2];
      const ended = within(opened).getByText("Outcome").nextElementSibling!;
      if (lost === "errored") {
        expect(ended.querySelector("bdi")).toHaveTextContent("small");
        expect(ended.querySelector("span")).toHaveTextContent(
          "(the review hit an error)",
        );
      } else {
        expect(ended).not.toHaveTextContent("(the review hit an error)");
      }
      expect(result).not.toHaveTextContent("(the review hit an error)");
      expect(fieldsIn(opened)["Outcome"]).not.toContain(
        "No response came back.",
      );
      if (lost === "did_not_fit") {
        expect(opened).toHaveTextContent(
          "The review rejected a value without saying why.",
        );
      }
      expect(opened).not.toHaveTextContent(lost);
    },
  );

  it("keeps a picked value first and names a review error separately", async () => {
    keptExpanded();
    const reviewed = {
      ...SURE_TRY,
      values: [
        { field: "summary", value: "A fire.", now: "refused", confidence: 87 },
      ],
      review: {
        asked: true,
        by: { kind: "model", model: "small" },
        wentWrong: true,
        lost: "errored",
      },
      ended: "refused_on_review",
    };
    const page = {
      ...MODEL_REVIEW_WAITING,
      triesMade: [reviewed, MODEL_REVIEW_WAITING.triesMade[1]],
    };
    opening([reply(page)], `${PAGE}?value=summary&try=1`, listedStep(page));
    const opened = await screen.findByRole("dialog", {
      name: "Attempt 1 of 2",
    });
    const ended = within(opened).getByText("Outcome").nextElementSibling!;
    expect(ended).toHaveTextContent("Rejected in review.");
    expect(ended).toHaveTextContent("small (the review hit an error)");
    expect(ended.querySelector("bdi")).toHaveTextContent("small");
    expect(ended.querySelector("span")).toHaveTextContent(
      "(the review hit an error)",
    );
  });

  it("lists each time a try's call was turned away before it was sent, right after how it ended, none of them a try", async () => {
    keptExpanded();
    opening([reply(MODEL_MISFIT)], `${PAGE}?try=1`);

    const opened = await screen.findByRole("dialog", {
      name: "Attempt 1 of 2",
    });

    const said = fieldsIn(opened);
    const labels = Object.keys(said);
    expect(labels.indexOf("Times the model service turned it away")).toBe(
      labels.indexOf("Outcome") + 1,
    );
    expect(said["Times the model service turned it away"]).toBe(
      [
        `Turned away ${whenText("2026-09-26T08:58:00Z")}, then resent automatically.`,
        "The model said: Too many asks.",
        `Turned away ${whenText("2026-09-26T08:59:00Z")}, then resent automatically.`,
        "These don't count as attempts and cost nothing.",
      ].join(""),
    );
    expect(within(opened).getByText("Too many asks.").tagName).toBe("BDI");
  });

  it("draws what a model said turning a call away, in the try opened, a character in it that shows nothing as a marker and never itself", async () => {
    keptExpanded();
    opening(
      [
        reply({
          ...MODEL_MISFIT,
          triesMade: [
            {
              ...MISFIT_TRY,
              turnedAway: [
                { ...TURNED_AWAY[0], said: `Too${ZERO_WIDTH_SPACE}many.` },
              ],
            },
            MODEL_WAITING.triesMade[1],
          ],
        }),
      ],
      `${PAGE}?try=1`,
    );

    const opened = await screen.findByRole("dialog", {
      name: "Attempt 1 of 2",
    });
    const marker = within(opened).getByRole("img", { name: SHOWS_NOTHING });
    expect(opened).toHaveTextContent("The model said: TooU+200Bmany.");
    expect(marker.closest("bdi")?.textContent).toBe("TooU+200Bmany.");
    expect(opened.innerHTML).not.toContain(ZERO_WIDTH_SPACE);
  });

  it("shows an unsent hold's turnaways beneath the hold without inventing a try", async () => {
    opening([reply(HELD_TURNED_AWAY)], `${PAGE}?try=1`);

    const held = await screen.findByText(
      "The model service turned the request away. Someone who can start runs can try sending it.",
    );
    expect(held).toHaveTextContent(
      `Turned away ${whenText("2026-09-26T08:59:00Z")}.`,
    );
    expect(held).not.toHaveTextContent("It waits on");
    expect(screen.getByText("No attempts yet.")).toBeInTheDocument();
    expect(screen.queryByText("Attempt 0 of 2")).toBeNull();
    expect(fieldsIn()["Attempts"]).toBeUndefined();
    expect(screen.queryByRole("dialog")).toBeNull();
    expect(screen.queryByRole("button", { name: "Attempt 1 of 2" })).toBeNull();
  });

  it("keeps an earlier sent try's turnaways in that try while an unnumbered production is held", async () => {
    keptExpanded();
    opening([reply(HELD_AGAIN)], `${PAGE}?try=1`);

    const opened = await screen.findByRole("dialog", {
      name: "Attempt 1 of 2",
    });
    const held = screen.getByText(
      /^The model service turned the request away\./,
    );
    expect(document.querySelector("dl")).toHaveTextContent("Attempt 1 of 2");
    expect(document.querySelector("dl")).not.toHaveTextContent("Try 0");
    expect(held).toHaveTextContent(
      `Turned away ${whenText("2026-09-26T09:05:00Z")}.`,
    );
    expect(held).not.toHaveTextContent(whenText("2026-09-26T08:58:00Z"));
    expect(fieldsIn(opened)["Times the model service turned it away"]).toBe(
      [
        `Turned away ${whenText("2026-09-26T08:58:00Z")}, then resent automatically.`,
        "The model said: Too many asks.",
        `Turned away ${whenText("2026-09-26T08:59:00Z")}, then resent automatically.`,
        "These don't count as attempts and cost nothing.",
      ].join(""),
    );
    expect(within(opened).getByText("Too many asks.").tagName).toBe("BDI");
    expect(opened).not.toHaveTextContent(whenText("2026-09-26T09:05:00Z"));
    expect(screen.queryByRole("button", { name: "Attempt 2 of 2" })).toBeNull();
  });

  it("says what a model's call said went wrong is withheld from the reader, never as nothing said", async () => {
    keptExpanded();
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
      name: "Attempt 1 of 2",
    });

    expect(fieldsIn(opened)["Error"]).toBe(
      "You don't have permission to see this.",
    );
    expect(opened).not.toHaveTextContent("(cut short here)");
  });

  it.each([
    [
      "a try any call of which the model did not count",
      MODEL_MISFIT,
      1,
      "All of this is estimated by this system: no model reported its own count.",
      ["Part of", "not known"],
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
      "All of this is estimated by this system: no model reported its own count.The amount received is not known for every call, and is counted here as zero.",
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
    "says who counted what %s cost, beneath the count in the list the tries are picked in",
    async (_case, page, number, counted, never) => {
      opening([reply(page)]);
      await heading();

      const heads = within(part("Attempts"))
        .getAllByRole("columnheader")
        .map((head) => head.textContent);
      expect(heads.at(-1)).toBe("Usage");
      expect(heads).not.toContain("Cost");
      const cost = tryRows()[number - 1]!.at(-1)!;
      expect(cost).toBe(`1,500 in total: 1,200 sent, 300 received${counted}`);
      for (const words of never) {
        expect(cost).not.toContain(words);
      }
    },
  );

  it("says a step's cost is some or all of it as this system measured it where any call of it was", async () => {
    opening([reply(MODEL_MISFIT)]);
    await heading();

    expect(fieldsIn()["Usage"]).toBe(
      `3,000 in total: 2,400 sent, 600 received${SOME_MEASURED}`,
    );
    expect(fieldsIn()["Usage"]).not.toContain("not known");
  });

  it("says who counted a try's cost in the list the tries are picked in only of a try any call of which was measured here", async () => {
    opening([reply(MODEL_MISFIT)]);
    await heading();

    const [measured, counted] = tryRows().map((cells) => cells.at(-1));
    expect(measured).toBe(
      "1,500 in total: 1,200 sent, 300 receivedAll of this is estimated by this system: no model reported its own count.",
    );
    expect(counted).toBe("1,500 in total: 1,200 sent, 300 received");
  });

  it("opens again the value and the try its address names", async () => {
    keptExpanded();
    opening(
      [reply(TWO_VALUES)],
      `${PAGE}?sort=run&value=reply&try=1`,
      listedStep(TWO_VALUES),
    );

    const opened = await screen.findByRole("dialog", {
      name: "Attempt 1 of 2",
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
        level: 2,
        name: /^Attempts/,
        hidden: true,
      }),
    ).toHaveTextContent(`Attempts for ${setApart("Reply")}`);
  });

  it("lists the tries of the first value where its address names one the step does not give", async () => {
    opening([reply(TWO_VALUES)], `${PAGE}?value=gone`, listedStep(TWO_VALUES));
    await heading();

    expect(
      screen.getByRole("heading", { level: 2, name: /^Attempts/ }),
    ).toHaveTextContent(`Attempts for ${setApart("Summary")}`);
    expect(
      screen.getByRole("button", {
        name: `See attempts for ${setApart("Summary")}`,
      }),
    ).toHaveAttribute("aria-pressed", "true");
    expect(
      screen.getByRole("button", {
        name: `See attempts for ${setApart("Reply")}`,
      }),
    ).toHaveAttribute("aria-pressed", "false");
    expect(screen.queryByText(/gone/)).toBeNull();
  });

  it("lists the tries of another value once it is picked, keeping the list's address and closing no try that is not open", async () => {
    const { router } = opening(
      [reply(TWO_VALUES)],
      `${PAGE}?sort=run&value=summary`,
      listedStep(TWO_VALUES),
    );
    await heading();
    const summary = screen.getByRole("button", {
      name: `See attempts for ${setApart("Summary")}`,
    });
    const replyButton = screen.getByRole("button", {
      name: `See attempts for ${setApart("Reply")}`,
    });
    expect(summary).toHaveAttribute("aria-pressed", "true");

    await userEvent.click(replyButton);

    expect(
      screen.getByRole("heading", { level: 2, name: /^Attempts/ }),
    ).toHaveTextContent(`Attempts for ${setApart("Reply")}`);
    expect(replyButton).toHaveAttribute("aria-pressed", "true");
    expect(summary).toHaveAttribute("aria-pressed", "false");
    expect(router.state.location.search).toBe("?sort=run&value=reply");
    expect(screen.queryByRole("dialog")).toBeNull();
  });

  it("draws no value in a try of a step that gives nothing back, and names none in its tries", async () => {
    keptExpanded();
    const page = {
      ...WAITING,
      run: {
        ...HEADER,
        at: { stepId: LATER_STEP_ID, name: "check_claim" },
        progress: { done: 2, of: 3 },
      },
      step: {
        ...ROW,
        runs: {
          versionName: null,
          kind: "code_step",
          codeStep: "demo_sample_file_notice",
        },
        producer: { kind: "code" },
        state: "done",
        where: undefined,
        producedByUserId: null,
        producedByKind: null,
        stateActs: [],
        stateRefusals: [],
      },
      declarations: {
        ...DECLARATIONS,
        demo_sample_file_notice: {
          takes: DECLARATIONS[QUESTION_VERSION].takes,
          gives: [],
        },
      },
      cameOut: [],
      triesMade: [
        {
          ...ERRORED_TRY,
          returned: undefined,
          wentWrong: {
            detail: "Demo Sample filing was interrupted.",
            cut: false,
          },
        },
        {
          ...WAITING_TRY,
          producedBy: { kind: "code" },
          values: [],
          review: { asked: false },
          ended: "stands",
        },
      ],
    };
    opening([reply(page)], PAGE, listedStep(page));
    await heading();

    await userEvent.click(
      screen.getByRole("button", { name: "Attempt 2 of 2" }),
    );

    const opened = await screen.findByRole("dialog");
    expect(
      within(opened)
        .getAllByRole("term")
        .map((term) => term.textContent),
    ).toEqual(["Requested by", "Produced by", "Reviewed by", "Outcome"]);
    expect(opened).not.toHaveTextContent("No value was returned for this.");
    expect(opened).not.toHaveTextContent("None — no model is used.");
    expect(
      screen.getByRole("heading", {
        level: 2,
        name: /^Attempts/,
        hidden: true,
      }),
    ).toHaveTextContent(/^Attempts$/);
  });

  it("says what a model said, and the words its refusal was given in, are withheld from the reader, naming the model", async () => {
    keptExpanded();
    const page = {
      ...MODEL_REVIEW_WAITING,
      step: { ...MODEL_REVIEW_WAITING.step, reviewer: SURE_TRY.producedBy },
      triesMade: [
        {
          ...SURE_TRY,
          review: { asked: true, by: SURE_TRY.producedBy },
          values: [
            {
              field: "summary",
              withheld: true,
              now: "refused",
              decision: { outcome: "refused", withheld: true },
            },
          ],
        },
        MODEL_REVIEW_WAITING.triesMade[1],
      ],
    };
    opening([reply(page)], PAGE, listedStep(page));
    await heading();
    expect(tryRows()[0]![1]).toBe("small in careful");

    await userEvent.click(
      screen.getByRole("button", { name: "Attempt 1 of 2" }),
    );

    const opened = await screen.findByRole("dialog");
    expect(opened).toHaveTextContent("You don't have permission to see this.");
    expect(opened).toHaveTextContent(
      "Rejected, for a reason you don't have permission to see.",
    );
    expect(opened).not.toHaveTextContent("A fire.");
    expect(opened).not.toHaveTextContent(/\bwithheld\b/i);
  });

  it("says what held a step back, who stopped it and when, and who can let it go, before anything else it says of the step, and not again", async () => {
    opening([
      reply({
        ...WAITING,
        step: {
          ...ROW,
          state: "held_back",
          producedByUserId: null,
          producedByKind: null,
          stateActs: [],
          stateRefusals: [],
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
      `What this step runs has been stopped, so nothing will be produced until it is allowed to be used again. Stopped by ${setApart("Cat")}, ${whenText("2026-09-26T09:00:00Z")}. Someone who can stop entries can allow it to be used again.`,
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
    expect(fieldsIn()["Status"]).toBe("Blocked");
  });

  it("says a step held back on its length why, in the reader's words, before anything else it says of the step, and not again", async () => {
    opening([
      reply({
        ...MODEL_WAITING,
        step: {
          ...MODEL_WAITING.step,
          state: "held_back",
          producedByUserId: null,
          producedByKind: null,
          where: {
            kind: "held_back",
            reason: "too_long",
            since: "2026-09-26T09:00:00Z",
            waitsOn: "starter",
          },
          stateActs: ["try_sending"],
          stateRefusals: [],
        },
      }),
    ]);
    await heading();

    const held = screen.getByText(
      "Its request is too long for the model, so it was not sent and nothing was spent. Someone who can start runs can try sending it.",
    );
    expect(
      screen.getByText("You don't have permission to try sending it."),
    ).toBeVisible();
    expect(
      held.compareDocumentPosition(document.querySelector("dl")!) &
        Node.DOCUMENT_POSITION_FOLLOWING,
    ).toBeTruthy();
    expect(fieldsIn()["Status"]).toBe("Blocked");
    expect(screen.queryByText(UNKNOWN_SAID)).toBeNull();
    expect(screen.queryByText(/too_long/)).toBeNull();
  });

  // Fake timeouts prevent Testing Library's waits from draining; settle reads one turn at a time.
  it("keeps a waiting step's page up as often as each read of it says, saying nothing of reading, and stops once a read names no wait", async () => {
    vi.useFakeTimers({ toFake: ["setTimeout", "clearTimeout"] });
    const reviewed = {
      ...WAITING,
      rereadAfterSeconds: 7,
      run: {
        ...HEADER,
        at: { stepId: LATER_STEP_ID, name: "check_claim" },
        progress: { done: 2, of: 3 },
      },
      step: {
        ...ROW,
        state: "done",
        where: undefined,
        producedByUserId: null,
        producedByKind: null,
        stateActs: [],
        stateRefusals: [],
      },
      cameOut: [
        {
          field: "summary",
          now: "stands",
          standing: { number: 2, value: "A printer caught fire." },
        },
      ],
      triesMade: [
        REFUSED_TRY,
        {
          ...WAITING_TRY,
          values: [
            {
              ...WAITING_TRY.values[0],
              now: "stands",
              decision: { outcome: "assured" },
            },
          ],
          review: { asked: true, by: { kind: "person", person: DAN } },
          ended: "stands",
        },
      ],
    };
    const { rereadAfterSeconds: _wait, ...unkept } = reviewed;
    const { sent } = opening([
      reply({ ...WAITING, rereadAfterSeconds: 7 }),
      reply(reviewed),
      reply({
        ...unkept,
        run: {
          ...HEADER,
          state: "done",
          at: undefined,
          progress: { done: 3, of: 3 },
        },
      }),
    ]);
    const reads = () =>
      requestsTo(sent).filter((each) => each === `GET ${STEP}`).length;
    await landing(() => screen.queryByRole("heading", { level: 1 }) !== null);
    expect(tryRows()[1]![2]).toBe("Not reviewed yet.");

    await act(() => vi.advanceTimersByTimeAsync(6999));
    expect(reads()).toBe(1);
    await act(() => vi.advanceTimersByTimeAsync(1));
    await landing(() => tryRows()[1]![2] === setApart("Dan"));
    await act(() => vi.advanceTimersByTimeAsync(7000));
    await landing(() => reads() === 3);
    await act(() => vi.advanceTimersByTimeAsync(60_000));

    expect(reads()).toBe(3);
    expect(
      requestsTo(sent).filter((each) => each === `GET ${STEPS}`),
    ).toHaveLength(1);
    expect(vi.getTimerCount()).toBe(1);
    expect(screen.queryByText("Loading…")).toBeNull();
  });

  it("reads a step's page only once where the read names no wait, though the run it is of runs", async () => {
    vi.useFakeTimers({ toFake: ["setTimeout", "clearTimeout"] });
    const { rereadAfterSeconds: _wait, ...unkept } = WAITING;
    const { sent } = opening([reply(unkept), reply(WAITING)]);
    await landing(() => screen.queryByRole("heading", { level: 1 }) !== null);

    await act(() => vi.advanceTimersByTimeAsync(60_000));

    expect(
      requestsTo(sent).filter((each) => each === `GET ${STEP}`),
    ).toHaveLength(1);
    expect(vi.getTimerCount()).toBe(1);
    expect(fieldsIn()["Status"]).toMatch(/^Waiting/);
  });

  it("says once, and only there, where a read again finds the step or its run in another state", async () => {
    vi.useFakeTimers({ toFake: ["setTimeout", "clearTimeout"] });
    const { where: _where, ...placeless } = ROW;
    const done = {
      ...WAITING,
      step: {
        ...placeless,
        producedByUserId: null,
        producedByKind: null,
        stateActs: [],
        stateRefusals: [],
        state: "done",
      },
    };
    opening([reply(WAITING), reply(done), reply(done)]);
    await landing(() => screen.queryByRole("heading", { level: 1 }) !== null);
    expect(regionsSaid()).toEqual(["", "", "", "", ""]);

    await act(() => vi.advanceTimersByTimeAsync(5000));
    await landing(() => fieldsIn()["Status"] === "Done");

    expect(regionsSaid()).toEqual([
      "",
      `${setApart("Summarise")}: now Done.`,
      "",
      "",
      "",
    ]);

    await act(() => vi.advanceTimersByTimeAsync(5000));
    await landing(() => regionsSaid().every((each) => each === ""));

    expect(fieldsIn()["Status"]).toBe("Done");
  });

  it("says a step is now somewhere it cannot say yet in words of their own, never as a state", async () => {
    vi.useFakeTimers({ toFake: ["setTimeout", "clearTimeout"] });
    const { where: _where, ...placeless } = ROW;
    opening([
      reply(WAITING),
      reply({ ...WAITING, step: { ...placeless, state: "paused" } }),
    ]);
    await landing(() => screen.queryByRole("heading", { level: 1 }) !== null);

    await act(() => vi.advanceTimersByTimeAsync(5000));
    await landing(() => regionsSaid().some((each) => each !== ""));

    expect(regionsSaid()).toEqual([
      "",
      `${setApart("Summarise")}: status changed, and this page can't show the new status yet.`,
      "",
      "",
      "",
    ]);
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
        step: {
          ...placeless,
          producedByUserId: null,
          producedByKind: null,
          stateActs: [],
          stateRefusals: [],
          state: "done",
        },
      }),
    ]);
    await landing(() => screen.queryByRole("heading", { level: 1 }) !== null);
    await act(() => vi.advanceTimersByTimeAsync(5000));
    await landing(() => fieldsIn()["Status"] === "Done");
    expect(regionsSaid()).toEqual([
      "",
      `Run 7: now Done. ${setApart("Summarise")}: now Done.`,
      "",
      "",
      "",
    ]);

    await act(() => vi.advanceTimersByTimeAsync(5000));

    expect(regionsSaid()).toEqual(["", "", "", "", ""]);
    expect(
      requestsTo(sent).filter((each) => each === `GET ${STEP}`),
    ).toHaveLength(2);
  });

  it("says a step refused in place, the way back through Work and History still there, naming neither run nor step", async () => {
    opening([[JSON.stringify({ code: "STEP_NOT_IN_VIEW" }), 404]]);

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "That step isn't here, or you can't see it.",
    );
    expect(screen.queryByRole("heading", { level: 1 })).toBeNull();

    const path = screen.getByRole("navigation", { name: "Breadcrumb" });
    expect(
      within(path)
        .getAllByRole("link")
        .map((link) => link.textContent),
    ).toEqual(["Work", "History"]);
    expect(within(path).getAllByRole("listitem")).toHaveLength(2);
  });
});
