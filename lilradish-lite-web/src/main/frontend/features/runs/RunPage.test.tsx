import { ThemeProvider } from "@mui/material/styles";
import { act, render, screen, waitFor, within } from "@testing-library/react";
import { userEvent } from "@testing-library/user-event";
import { RouterProvider, createMemoryRouter } from "react-router";
import { afterEach, describe, expect, it, vi } from "vitest";

import { StandingProvider } from "../../app/standing/StandingContext";
import { theme } from "../../lib/theme/theme";
import { whenText } from "../../lib/time/When";
import { requestsTo, serving, type Reply } from "../../testutil/answering";
import { answered, inGroup } from "../../testutil/standingRead";
import { WITHIN_THE_RUN } from "./runAddress";
import { RunPage } from "./RunPage";

const GROUP = "00000003-0000-4000-8000-000000000c51";

const RUN_ID = "00000008-0000-4000-8000-000000000c51";

const ABOVE = "00000008-0000-4000-8000-000000000c52";

const WORKFLOW = "00000006-0000-4000-8000-000000000c51";

const RAISE = "0000000b-0000-4000-8000-000000000c51";

const PAGE = `/groups/${GROUP}/work/${RUN_ID}`;

const RUN = `/api/groups/${GROUP}/runs/${RUN_ID}`;

const ADA = { userId: "000c51", displayName: "Ada Lovelace" };

const GRACE = { userId: "000c52", displayName: "Grace Hopper" };

const START_RUN_RULE =
  "A group's runs are started, renamed, stopped, opened again and given a ceiling, and a raise asked withdrawn, only by a role in it that may start one.";

const APPROVE_ENTRY_RULE =
  "A group's entries are put into service, and a run's raised ceiling approved or refused, only by a role in it that may approve an entry.";

/** Running, started by Ada, spent past what a number holds exactly, a raise of its ceiling needing approval. */
const CLAIM = {
  runId: RUN_ID,
  number: 7,
  name: "Claim from Ada",
  workflow: { entryId: WORKFLOW, name: "Handle a claim", version: 3 },
  startedBy: ADA,
  startedAt: "2026-09-24T08:00:00Z",
  state: "running",
  spend: {
    sent: "9007199254740993",
    cameBack: "20",
    spent: "9007199254741013",
    cameBackUnknown: false,
  },
  ceiling: { inForce: "9007199254740990", raiseNeedsApproval: true },
  acts: ["stop", "rename", "change_ceiling"],
};

const STOPPED = {
  ...CLAIM,
  state: "stopped",
  stopped: { at: "2026-09-24T08:15:00Z", by: GRACE },
  acts: ["open_again", "rename", "change_ceiling"],
};

/** A raise Grace asked for, which Ada, reading, may decide. */
const RAISED = {
  ...CLAIM,
  ceiling: {
    ...CLAIM.ceiling,
    waiting: {
      changeId: RAISE,
      to: "20000",
      askedBy: GRACE,
      askedAt: "2026-09-24T08:30:00Z",
    },
  },
  acts: ["stop", "change_ceiling", "approve_raise", "refuse_raise"],
};

/** The same raise, asked by the one reading. */
const ASKED = { ...RAISED, acts: ["stop", "change_ceiling", "withdraw_raise"] };

function reply(run: object, status = 200): Reply {
  return [JSON.stringify(run), status];
}

function refusal(code: string, status: number): Reply {
  return [JSON.stringify({ code }), status];
}

/** A reply the server never sends. */
const UNANSWERED: Reply = new Promise(() => {});

const STEPS = `${RUN}/steps`;

const VERSION = "00000007-0000-4000-8000-000000000c51";

const QUESTION_VERSION = "00000007-0000-4000-8000-000000000c52";

const SUMMARISE = "00000009-0000-4000-8000-000000000c51";

const FILE_IT = "00000009-0000-4000-8000-000000000c52";

const TICKET = {
  name: "ticket",
  label: "Ticket",
  kind: "text",
  longest: 4000,
  mustBeGiven: true,
};

const DUE = { name: "due", label: "Due", kind: "date", mustBeGiven: false };

const SUMMARY = {
  name: "summary",
  label: "Summary",
  kind: "text",
  longest: 100,
  mustBeGiven: true,
};

/** Ada's claim, as it was started. */
const STARTED = {
  ...CLAIM,
  startedWith: { ticket: "Kettle leaks", due: "2026-09-30" },
};

const RUN_HEADER = {
  runId: RUN_ID,
  number: 7,
  versionId: VERSION,
  state: "running",
  at: { stepId: FILE_IT, name: "file_it" },
  acts: ["stop"],
  progress: { done: 1, of: 2 },
};

const DECLARATIONS = {
  [VERSION]: { takes: [TICKET, DUE], gives: [SUMMARY] },
  [QUESTION_VERSION]: {
    takes: [{ ...TICKET, name: "text", label: "Text" }],
    gives: [SUMMARY],
  },
};

/** A question a person answered on its first try, its summary standing. */
const SUMMARISE_ROW = {
  stepId: SUMMARISE,
  order: 1,
  name: "summarise",
  runs: {
    kind: "question",
    entryId: "00000006-0000-4000-8000-000000000c52",
    name: "Summarise",
    version: 2,
    versionId: QUESTION_VERSION,
  },
  producer: { kind: "person" },
  reviewer: { kind: "person" },
  state: "done",
  takesFrom: [{ input: "text", from: { kind: "run_input", path: "ticket" } }],
  tries: { current: 1, declared: 2, beyond: false },
  cost: { callsAModel: false },
  gaveBack: [{ field: "summary", value: "A kettle leaks.", now: "stands" }],
  acts: [],
  withheld: [],
};

/** A code step, its code running its first try. */
const FILE_IT_ROW = {
  stepId: FILE_IT,
  order: 2,
  name: "file_it",
  runs: { kind: "code_step", codeStep: "file_claim" },
  producer: { kind: "code" },
  reviewer: { kind: "person" },
  state: "running",
  where: { kind: "running", on: "code" },
  tries: { current: 1, declared: 1, beyond: false },
  takesFrom: [
    {
      input: "summary",
      from: {
        kind: "step",
        path: "summary",
        stepId: SUMMARISE,
        name: "summarise",
        versionId: QUESTION_VERSION,
      },
    },
  ],
  cost: { callsAModel: false },
  acts: [],
  withheld: [],
};

/** Its first step done and its summary standing; the code step after it running. */
function stepsRead(changed: object = {}, route: object = {}): Reply {
  return reply({
    run: RUN_HEADER,
    declarations: DECLARATIONS,
    gaveBack: {
      declares: "values",
      standing: [{ field: "summary", value: "A kettle leaks." }],
    },
    steps: [SUMMARISE_ROW, { ...FILE_IT_ROW, ...route }],
    ...changed,
  });
}

function reads(sent: ReturnType<typeof serving>): string[] {
  return requestsTo(sent).filter((each) => each.startsWith("GET"));
}

/** What is in flight let land a turn at a time, until `landed` holds; failing where it never does. */
async function landing(landed: () => boolean): Promise<void> {
  for (let turn = 0; turn < 100 && !landed(); turn += 1) {
    await act(() => vi.advanceTimersByTimeAsync(0));
  }
  expect(landed()).toBe(true);
}

/**
 * The page at its address under a real router, drawn in detail unless asked otherwise, with the standing it would
 * be handed and only the server stood in for.
 */
function opening(
  routes: Readonly<Record<string, readonly Reply[]>> = {},
  address: string | { pathname: string; state?: unknown } = PAGE,
  drawing: "detail" | "conversation" = "detail",
) {
  const sent = serving({
    [`GET ${RUN}`]: [reply(CLAIM)],
    [`GET ${STEPS}`]: [stepsRead()],
    ...routes,
  });
  const onDetail = vi.fn();
  const router = createMemoryRouter(
    [
      {
        path: "/groups/:groupId/work/:runId",
        element:
          drawing === "detail" ? (
            <RunPage drawing="detail" onActed={() => {}} />
          ) : (
            <RunPage
              drawing="conversation"
              onDetail={onDetail}
              onActed={() => {}}
            />
          ),
      },
    ],
    { initialEntries: [address] },
  );
  const readAgain = vi.fn();
  const standing = answered(
    [],
    [inGroup(GROUP, "CLAIMS", "Claims", ["start_run", "read_own_runs"])],
  );
  render(
    <ThemeProvider theme={theme}>
      <StandingProvider read={{ ...standing, reload: readAgain }}>
        <RouterProvider router={router} />
      </StandingProvider>
    </ThemeProvider>,
  );
  return { sent, readAgain, onDetail, router };
}

async function opened(): Promise<HTMLElement> {
  return screen.findByRole("heading", { level: 2 });
}

function setApart(words: string): string {
  return `${String.fromCodePoint(0x2068)}${words}${String.fromCodePoint(0x2069)}`;
}

function ceiling(): HTMLElement {
  return screen.getByRole("group", { name: "Ceiling" });
}

/** Each label of the header beside what it says, in the order drawn; the header's are the page's first. */
function said(): Record<string, string> {
  const header = within(document.querySelector("dl")!);
  const terms = header.getAllByRole("term");
  const definitions = header.getAllByRole("definition");
  return Object.fromEntries(
    terms.map((term, at) => [
      term.textContent ?? "",
      definitions[at]!.textContent ?? "",
    ]),
  );
}

function drawn(): string[] {
  return screen.queryAllByRole("button").map((each) => each.textContent ?? "");
}

function runReads(sent: ReturnType<typeof serving>): number {
  return requestsTo(sent).filter((each) => each === `GET ${RUN}`).length;
}

describe("RunPage", () => {
  /** Every count grouped as the reader groups one, and none of its digits lost on the way. */
  it("heads the page with the run's name, and says what ran, who started it, where it is, what it has cost and its ceiling", async () => {
    opening();

    expect(await opened()).toHaveTextContent(setApart("Claim from Ada"));
    const header = said();
    expect(Object.keys(header)).toEqual([
      "Number",
      "Workflow",
      "Started",
      "Where it is",
      "What it has cost",
      "Ceiling",
    ]);
    expect(header).toMatchObject({
      Number: "7",
      Workflow: `${setApart("Handle a claim")}, version 3`,
      "Where it is": "Running",
      "What it has cost":
        "9,007,199,254,741,013 in all: 9,007,199,254,740,993 sent, 20 came back",
      Ceiling: "9,007,199,254,740,990Change the ceiling",
    });
    expect(header.Started).toMatch(
      new RegExp(`^By ${setApart("Ada Lovelace")}, .+$`),
    );
    expect(
      screen.getByRole("link", { name: /Handle a claim/ }),
    ).toHaveAttribute("href", `/groups/${GROUP}/workflows/${WORKFLOW}`);
  });

  it("heads a run beneath another with its number, links the run above, and says it is held to that one's ceiling", async () => {
    const { name: _unnamed, startedBy: _nobody, ...beneath } = CLAIM;
    opening({
      [`GET ${RUN}`]: [
        reply({
          ...beneath,
          above: ABOVE,
          state: "stopped",
          stopped: {
            at: "2026-09-24T08:15:00Z",
            ceilingOf: { runId: ABOVE, number: 6 },
          },
          ceiling: { heldBy: { runId: ABOVE, number: 6 } },
          acts: [],
        }),
      ],
    });

    expect(await opened()).toHaveTextContent("Run 7");
    const header = said();
    expect(header.Started).toMatch(/^By the run above it, .+$/);
    expect(header["Where it is"]).toMatch(
      /^Stopped by the ceiling of run 6, .+$/,
    );
    expect(header.Ceiling).toBe("Held to the ceiling of run 6, at the top");
    expect(
      [
        screen.getByRole("link", { name: "The run above it" }),
        screen.getByRole("link", { name: /Held to the ceiling of run 6/ }),
      ].map((link) => link.getAttribute("href")),
    ).toEqual([
      `/groups/${GROUP}/work/${ABOVE}`,
      `/groups/${GROUP}/work/${ABOVE}`,
    ]);
    expect(drawn()).toEqual([]);
  });

  /** Opened beside a list, a run's links keep the list's filter and order, as the list's own links do. */
  it("keeps the address's query on each link to another run", async () => {
    const { name: _unnamed, startedBy: _nobody, ...beneath } = CLAIM;
    opening(
      {
        [`GET ${RUN}`]: [
          reply({
            ...beneath,
            above: ABOVE,
            ceiling: { heldBy: { runId: ABOVE, number: 6 } },
            acts: [],
          }),
        ],
      },
      `${PAGE}?filter=claim&sort=run`,
    );

    await opened();

    expect(
      [
        screen.getByRole("link", { name: "The run above it" }),
        screen.getByRole("link", { name: /Held to the ceiling of run 6/ }),
      ].map((link) => link.getAttribute("href")),
    ).toEqual([
      `/groups/${GROUP}/work/${ABOVE}?filter=claim&sort=run`,
      `/groups/${GROUP}/work/${ABOVE}?filter=claim&sort=run`,
    ]);
  });

  it.each([
    ["somebody", { by: GRACE }, `^Stopped by ${setApart("Grace Hopper")}, .+$`],
    [
      "its own ceiling, naming nobody",
      { ceilingOf: { runId: RUN_ID, number: 7 } },
      "^Stopped by its ceiling, .+$",
    ],
  ])("says a run is stopped, and by %s", async (_case, by, where) => {
    opening({
      [`GET ${RUN}`]: [
        reply({ ...STOPPED, stopped: { at: "2026-09-24T08:15:00Z", ...by } }),
      ],
    });
    await opened();

    expect(said()["Where it is"]).toMatch(new RegExp(where));
  });

  /** A state named after this build is said plainly as one it cannot say, never in the server's spelling. */
  it("says where a run is in words only, even where the server works out a state this build has none for", async () => {
    opening({ [`GET ${RUN}`]: [reply({ ...CLAIM, state: "resting" })] });
    await opened();

    expect(said()["Where it is"]).toBe("Not something this page can say yet.");
    expect(screen.queryByText(/resting/)).toBeNull();
  });

  it.each([
    [
      "with some of what came back unknown, says so",
      { cameBackUnknown: true },
      "What came back is not known for every call yet, and is counted here as nothing.",
    ],
    [
      "with any of it as this system measured it, says some or all of it is, never as one try's",
      { measuredHere: true },
      "Some or all of it is as this system measured it, where a model did not say what it counted.",
    ],
    [
      "with both, says who counted it before what is not known",
      { cameBackUnknown: true, measuredHere: true },
      "Some or all of it is as this system measured it, where a model did not say what it counted.What came back is not known for every call yet, and is counted here as nothing.",
    ],
    ["with all of it known as the model counted it, says nothing more", {}, ""],
  ])("says what a run has cost, and %s", async (_case, marked, more) => {
    opening({
      [`GET ${RUN}`]: [
        reply({
          ...CLAIM,
          spend: { ...CLAIM.spend, cameBackUnknown: false, ...marked },
        }),
      ],
    });
    await opened();

    expect(said()["What it has cost"]).toBe(
      `9,007,199,254,741,013 in all: 9,007,199,254,740,993 sent, 20 came back${more}`,
    );
  });

  it.each([
    [
      "every act on a running run, a raise waiting",
      [
        "stop",
        "open_again",
        "rename",
        "change_ceiling",
        "approve_raise",
        "refuse_raise",
        "withdraw_raise",
      ],
      [
        "Stop",
        "Rename",
        "Change the ceiling",
        "Approve the raise",
        "Refuse the raise",
        "Withdraw the raise",
      ],
    ],
    ["opening again alone", ["open_again"], ["Open again"]],
    ["none of them", [], []],
  ])(
    "draws only the controls the server says the reader may use: %s",
    async (_case, acts, controls) => {
      opening({ [`GET ${RUN}`]: [reply({ ...RAISED, acts })] });
      await opened();

      expect(drawn()).toEqual(controls);
    },
  );

  /** The run read again after the act is held unanswered, so all that is drawn is what the act answered. */
  it("stops the run, showing it as the act answered before the run read again after it comes back", async () => {
    const { sent } = opening({
      [`GET ${RUN}`]: [reply(CLAIM), UNANSWERED],
      [`PUT ${RUN}/stop`]: [reply(STOPPED)],
    });
    await opened();
    const control = screen.getByRole("button", { name: "Stop" });

    await userEvent.click(control);

    expect(await screen.findByRole("button", { name: "Open again" })).toBe(
      control,
    );
    expect(said()["Where it is"]).toMatch(/^Stopped by /);
    await waitFor(() =>
      expect(requestsTo(sent)).toEqual([
        `GET ${RUN}`,
        `GET ${STEPS}`,
        `PUT ${RUN}/stop`,
        `GET ${RUN}`,
        `GET ${STEPS}`,
      ]),
    );
    expect(screen.queryByRole("alert")).toBeNull();
  });

  it("opens a stopped run again", async () => {
    const { sent } = opening({
      [`GET ${RUN}`]: [reply(STOPPED), reply(CLAIM)],
      [`DELETE ${RUN}/stop`]: [reply(CLAIM)],
    });
    await opened();

    await userEvent.click(screen.getByRole("button", { name: "Open again" }));

    expect(
      await screen.findByRole("button", { name: "Stop" }),
    ).toBeInTheDocument();
    expect(requestsTo(sent).filter((each) => !each.startsWith("GET"))).toEqual([
      `DELETE ${RUN}/stop`,
    ]);
  });

  /** A refusal of the act says the reader's standing moved, which is read again with the run. */
  it("says a refused stop as the rule of starting a run, and reads the standing and the run again", async () => {
    const { sent, readAgain } = opening({
      [`PUT ${RUN}/stop`]: [refusal("ACT_NOT_PERMITTED", 403)],
    });
    await opened();

    await userEvent.click(screen.getByRole("button", { name: "Stop" }));

    expect(await screen.findByRole("alert")).toHaveTextContent(START_RUN_RULE);
    expect(readAgain).toHaveBeenCalledOnce();
    await waitFor(() => expect(runReads(sent)).toBe(2));
  });

  it("renames the run through its dialog, heading the page with the name it answers with", async () => {
    const { sent } = opening({
      [`GET ${RUN}`]: [reply(CLAIM), UNANSWERED],
      [`PATCH ${RUN}`]: [reply({ ...CLAIM, name: "Claim from Ada, again" })],
    });
    await opened();

    await userEvent.click(screen.getByRole("button", { name: "Rename" }));
    const dialog = screen.getByRole("dialog", { name: "Rename this run" });
    await userEvent.type(
      within(dialog).getByRole("textbox", { name: "Name" }),
      ", again",
    );
    await userEvent.click(
      within(dialog).getByRole("button", { name: "Rename" }),
    );

    await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
    expect(await opened()).toHaveTextContent(setApart("Claim from Ada, again"));
    expect(requestsTo(sent).filter((each) => !each.startsWith("GET"))).toEqual([
      `PATCH ${RUN}`,
    ]);
  });

  it.each([
    [
      "raised, says to what",
      RAISED.ceiling.waiting,
      "for it to be raised to 20,000\\. That waits on approval\\.$",
    ],
    [
      "taken away, says so",
      { changeId: RAISE, askedBy: GRACE, askedAt: "2026-09-24T08:30:00Z" },
      "for it to be taken away\\. That waits on approval\\.$",
    ],
  ])(
    "says a raise waiting beside the ceiling in force: asked for it to be %s",
    async (_case, waiting, words) => {
      opening({
        [`GET ${RUN}`]: [
          reply({ ...RAISED, ceiling: { ...RAISED.ceiling, waiting } }),
        ],
      });
      await opened();

      expect(within(ceiling()).getByText(/asked/)).toHaveTextContent(
        new RegExp(`^${setApart("Grace Hopper")} asked, .+, ${words}`),
      );
      expect(
        within(ceiling()).getByText("9,007,199,254,740,990"),
      ).toBeInTheDocument();
    },
  );

  /** The raise is named by its identifier, so nobody decides one they never saw. */
  it.each([
    ["Approve the raise", "approval", RAISED],
    ["Refuse the raise", "refusal", RAISED],
    ["Withdraw the raise", "withdrawal", ASKED],
  ])(
    "decides the raise shown when %s is pressed, keeping the keyboard at the ceiling",
    async (control, decision, run) => {
      const { sent } = opening({
        [`GET ${RUN}`]: [reply(run), UNANSWERED],
        [`PUT ${RUN}/ceiling-changes/${RAISE}/${decision}`]: [reply(CLAIM)],
      });
      await opened();

      await userEvent.click(screen.getByRole("button", { name: control }));

      await waitFor(() =>
        expect(within(ceiling()).queryByText(/asked/)).toBeNull(),
      );
      expect(
        requestsTo(sent).filter((each) => !each.startsWith("GET")),
      ).toEqual([`PUT ${RUN}/ceiling-changes/${RAISE}/${decision}`]);
      expect(document.activeElement).toBe(ceiling());
      expect(screen.queryByRole("alert")).toBeNull();
    },
  );

  /** Decided by somebody else meanwhile: the refusal is said, and the run read again shows how it stands now. */
  it.each([
    ["Approve the raise", "approval", RAISED],
    ["Withdraw the raise", "withdrawal", ASKED],
  ])(
    "says %s refused as the raise no longer waiting, and reads the run again and not the standing",
    async (control, decision, run) => {
      const { sent, readAgain } = opening({
        [`GET ${RUN}`]: [reply(run), reply(CLAIM)],
        [`PUT ${RUN}/ceiling-changes/${RAISE}/${decision}`]: [
          refusal("CEILING_RAISE_NOT_WAITING", 409),
        ],
      });
      await opened();

      await userEvent.click(screen.getByRole("button", { name: control }));

      expect(await within(ceiling()).findByRole("alert")).toHaveTextContent(
        "That raise is not waiting on anybody.",
      );
      await waitFor(() => expect(runReads(sent)).toBe(2));
      expect(readAgain).not.toHaveBeenCalled();
    },
  );

  it.each([
    ["Approve the raise", "approval", RAISED, APPROVE_ENTRY_RULE],
    ["Refuse the raise", "refusal", RAISED, APPROVE_ENTRY_RULE],
    ["Withdraw the raise", "withdrawal", ASKED, START_RUN_RULE],
  ])(
    "says %s refused as the act itself as the rule of the permission it takes, and reads the standing again",
    async (control, decision, run, rule) => {
      const { readAgain } = opening({
        [`GET ${RUN}`]: [reply(run)],
        [`PUT ${RUN}/ceiling-changes/${RAISE}/${decision}`]: [
          refusal("ACT_NOT_PERMITTED", 403),
        ],
      });
      await opened();

      await userEvent.click(screen.getByRole("button", { name: control }));

      expect(await within(ceiling()).findByRole("alert")).toHaveTextContent(
        rule,
      );
      expect(readAgain).toHaveBeenCalledOnce();
    },
  );

  it("says a withdrawal refused as the raise being another's, and reads the run again", async () => {
    const { sent, readAgain } = opening({
      [`GET ${RUN}`]: [reply(ASKED), reply(RAISED)],
      [`PUT ${RUN}/ceiling-changes/${RAISE}/withdrawal`]: [
        refusal("CEILING_RAISE_ASKED_BY_ANOTHER", 403),
      ],
    });
    await opened();

    await userEvent.click(
      screen.getByRole("button", { name: "Withdraw the raise" }),
    );

    expect(await within(ceiling()).findByRole("alert")).toHaveTextContent(
      "A raise is withdrawn only by whoever asked for it.",
    );
    await waitFor(() => expect(runReads(sent)).toBe(2));
    expect(readAgain).not.toHaveBeenCalled();
  });

  it("changes the ceiling through its dialog, showing the run as the change answered", async () => {
    const { sent } = opening({
      [`GET ${RUN}`]: [reply(CLAIM), UNANSWERED],
      [`PATCH ${RUN}/ceiling`]: [
        reply({ ...CLAIM, ceiling: { raiseNeedsApproval: true } }),
      ],
    });
    await opened();

    await userEvent.click(
      screen.getByRole("button", { name: "Change the ceiling" }),
    );
    const dialog = screen.getByRole("dialog", { name: "Change the ceiling" });
    await userEvent.clear(
      within(dialog).getByRole("textbox", { name: "Ceiling" }),
    );
    await userEvent.click(
      within(dialog).getByRole("button", { name: "Change" }),
    );

    await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
    expect(
      await within(ceiling()).findByText("None. It may spend without limit."),
    ).toBeInTheDocument();
    const changed = sent.mock.calls.find(
      ([, init]) => init?.method === "PATCH",
    );
    expect(JSON.parse(String(changed![1]?.body))).toEqual({ ceiling: null });
  });

  it.each([
    [
      "the run is not in view",
      "RUN_NOT_IN_VIEW",
      "That run is not in view.",
      0,
    ],
    [
      "the group is not the reader's",
      "GROUP_NOT_IN_VIEW",
      "That group is not in view.",
      1,
    ],
  ])(
    "says a read refused as %s in place, drawing nothing of the run, and reads the standing again only where it moved",
    async (_case, code, sentence, standingReads) => {
      const { readAgain } = opening({ [`GET ${RUN}`]: [refusal(code, 404)] });

      expect(await screen.findByRole("alert")).toHaveTextContent(sentence);
      expect(screen.queryByRole("heading", { level: 2 })).toBeNull();
      expect(readAgain).toHaveBeenCalledTimes(standingReads);
    },
  );

  describe("in detail", () => {
    function part(name: string): HTMLElement {
      return screen.getByRole("region", { name });
    }

    function stepRows(): string[][] {
      const [, ...rows] = within(
        screen.getByRole("table", { name: "The steps" }),
      ).getAllByRole<HTMLTableRowElement>("row");
      return rows.map((row) =>
        [...row.cells].map((cell) => cell.textContent ?? ""),
      );
    }

    afterEach(() => {
      vi.useRealTimers();
    });

    it("draws under the header what it was started with, what it gave back that stands, and every step in order with where each input comes from", async () => {
      const { sent } = opening(
        { [`GET ${RUN}`]: [reply(STARTED)], [`GET ${STEPS}`]: [stepsRead()] },
        PAGE,
        "detail",
      );

      await screen.findByRole("table", { name: "The steps" });

      expect(
        within(part("What it was started with"))
          .getAllByRole("definition")
          .map((each) => each.textContent),
      ).toEqual(["Kettle leaks", "Sep 30, 2026"]);
      expect(
        within(part("What it gave back")).getByRole("term"),
      ).toHaveTextContent("Summary");
      expect(
        within(part("What it gave back")).getByRole("definition"),
      ).toHaveTextContent("A kettle leaks.");
      expect(stepRows()).toEqual([
        [
          "1",
          "summarise",
          `${setApart("Text")}: ${setApart("Ticket")}, which the run was started with`,
          "Done",
          "Nothing: it calls no model.",
        ],
        [
          "2",
          "file it",
          `${setApart("summary")}: ${setApart("Summary")}, which ${setApart("summarise")} gave back`,
          "RunningIts code is running.",
          "Nothing: it calls no model.",
        ],
      ]);
      expect(
        screen.queryByText("Nothing it gives back stands yet."),
      ).toBeNull();
      expect(reads(sent)).toEqual([`GET ${RUN}`, `GET ${STEPS}`]);
    });

    it("says in the steps a step's cost is some or all of it as this system measured it, never as one try's", async () => {
      const measured = {
        ...SUMMARISE_ROW,
        cost: {
          callsAModel: true,
          sent: "1200",
          cameBack: "300",
          spent: "1500",
          cameBackUnknown: false,
          measuredHere: true,
        },
      };
      opening(
        { [`GET ${STEPS}`]: [stepsRead({ steps: [measured, FILE_IT_ROW] })] },
        PAGE,
        "detail",
      );

      await screen.findByRole("table", { name: "The steps" });

      const [cost, uncalled] = stepRows().map((cells) => cells.at(-1));
      expect(cost).toBe(
        "1,500 in all: 1,200 sent, 300 came backSome or all of it is as this system measured it, where a model did not say what it counted.",
      );
      expect(cost).not.toContain("Both as");
      expect(uncalled).toBe("Nothing: it calls no model.");
    });

    it("opens each step's own page from its row, keeping the list's address", async () => {
      opening(
        { [`GET ${STEPS}`]: [stepsRead()] },
        `${PAGE}?sort=run`,
        "detail",
      );

      const table = await screen.findByRole("table", { name: "The steps" });

      expect(
        within(table)
          .getAllByRole("link")
          .map((link) => link.getAttribute("href")),
      ).toEqual([
        `${PAGE}/steps/${SUMMARISE}?sort=run`,
        `${PAGE}/steps/${FILE_IT}?sort=run`,
      ]);
    });

    it.each([
      [
        "nothing standing yet",
        { declares: "values", standing: [] },
        "Nothing it gives back stands yet.",
      ],
      [
        "a workflow that acts rather than answers",
        { declares: "nothing" },
        "Its workflow gives nothing back: it acts rather than answers.",
      ],
      [
        "what it declares in a word this build does not know",
        { declares: "later" },
        "Not something this page can say yet.",
      ],
    ])(
      "says so of what it gave back where it has %s, and lists nothing",
      async (_case, gaveBack, sentence) => {
        opening(
          { [`GET ${STEPS}`]: [stepsRead({ gaveBack })] },
          PAGE,
          "detail",
        );

        await screen.findByRole("table", { name: "The steps" });

        expect(part("What it gave back")).toHaveTextContent(sentence);
        expect(
          within(part("What it gave back")).queryByRole("term"),
        ).toBeNull();
      },
    );

    it.each([
      [
        "the run above started it",
        { ...CLAIM, name: undefined, startedBy: undefined, above: ABOVE },
        `The run above it started it, with ${setApart("Handle a claim")}.`,
      ],
      [
        "it takes nothing",
        { ...CLAIM, startedWith: {} },
        "Its workflow takes nothing, so it was started with nothing.",
      ],
    ])(
      "says what it was started with in words where %s and no value is there to list",
      async (_case, run, sentence) => {
        opening(
          {
            [`GET ${RUN}`]: [reply(run)],
            [`GET ${STEPS}`]: [
              stepsRead({
                declarations: { [VERSION]: { takes: [], gives: [SUMMARY] } },
              }),
            ],
          },
          PAGE,
          "detail",
        );

        await screen.findByRole("table", { name: "The steps" });

        expect(part("What it was started with")).toHaveTextContent(sentence);
        expect(
          within(part("What it was started with")).queryByRole("term"),
        ).toBeNull();
      },
    );

    /** Timeouts are faked, so what is in flight is let land a turn at a time rather than waited on. */
    it("keeps where each step is up for as long as the steps say to, never saying it is reading, and stops once they no longer say it", async () => {
      vi.useFakeTimers({ toFake: ["setTimeout", "clearTimeout"] });
      const { sent } = opening(
        {
          [`GET ${STEPS}`]: [
            stepsRead({ rereadAfterSeconds: 2 }),
            stepsRead({}, { state: "done", where: undefined }),
          ],
        },
        PAGE,
        "detail",
      );
      await landing(() => screen.queryByRole("table") !== null);

      await act(() => vi.advanceTimersByTimeAsync(1999));
      expect(reads(sent)).toHaveLength(2);
      await act(() => vi.advanceTimersByTimeAsync(1));
      await landing(() => stepRows()[1]![3] === "Done");
      await act(() => vi.advanceTimersByTimeAsync(60_000));

      expect(reads(sent)).toEqual([
        `GET ${RUN}`,
        `GET ${STEPS}`,
        `GET ${RUN}`,
        `GET ${STEPS}`,
      ]);
      expect(vi.getTimerCount()).toBe(0);
      expect(screen.queryByText("Still reading…")).toBeNull();
      expect(screen.queryByRole("alert")).toBeNull();
    });

    it("reads the steps again once an act on the run answers, as well as showing the run it answered with", async () => {
      const { sent } = opening(
        {
          [`GET ${STEPS}`]: [
            stepsRead(),
            stepsRead({}, { state: "failed", where: undefined }),
          ],
          [`PUT ${RUN}/stop`]: [reply(STOPPED)],
        },
        PAGE,
        "detail",
      );
      await screen.findByRole("table", { name: "The steps" });

      await userEvent.click(screen.getByRole("button", { name: "Stop" }));

      await waitFor(() => expect(stepRows()[1]![3]).toBe("Failed"));
      const asked = requestsTo(sent);
      expect(asked.slice(asked.indexOf(`PUT ${RUN}/stop`))).toEqual([
        `PUT ${RUN}/stop`,
        `GET ${RUN}`,
        `GET ${STEPS}`,
      ]);
    });

    it("starts the keyboard at the ceiling once an act's answer is drawn, and leaves it where the reader put it when the run is read again after", async () => {
      let settle!: (answer: readonly [string, number]) => void;
      const readAgain = new Promise<readonly [string, number]>((done) => {
        settle = done;
      });
      opening(
        {
          [`GET ${RUN}`]: [reply(RAISED), readAgain],
          [`GET ${STEPS}`]: [stepsRead()],
          [`PUT ${RUN}/ceiling-changes/${RAISE}/approval`]: [reply(CLAIM)],
        },
        PAGE,
        "detail",
      );
      await screen.findByRole("table", { name: "The steps" });

      await userEvent.click(
        screen.getByRole("button", { name: "Approve the raise" }),
      );
      await waitFor(() => expect(document.activeElement).toBe(ceiling()));
      ceiling().blur();
      settle([JSON.stringify({ ...CLAIM, name: "Claim, read again" }), 200]);

      await waitFor(() =>
        expect(screen.getByRole("heading", { level: 2 })).toHaveTextContent(
          setApart("Claim, read again"),
        ),
      );
      expect(document.activeElement).toBe(document.body);
    });

    /** Timeouts are faked, as where each step is kept up is. */
    it("says once, in a region of its own, where a read again finds a step in another state, and nothing where none is", async () => {
      vi.useFakeTimers({ toFake: ["setTimeout", "clearTimeout"] });
      const done = { state: "done", where: undefined };
      opening(
        {
          [`GET ${STEPS}`]: [
            stepsRead({ rereadAfterSeconds: 1 }),
            stepsRead({ rereadAfterSeconds: 1 }, done),
            stepsRead({ rereadAfterSeconds: 1 }, done),
          ],
        },
        PAGE,
        "detail",
      );
      await landing(() => screen.queryByRole("table") !== null);
      // The page's own region comes first; the read's own follows it.
      const news = screen.getAllByRole("status")[0]!;
      expect(news).toHaveTextContent(/^$/);

      await act(() => vi.advanceTimersByTimeAsync(1000));
      await landing(() => stepRows()[1]![3] === "Done");

      expect(news).toHaveTextContent(`${setApart("file it")}: now Done.`);
      expect(news).not.toHaveTextContent("summarise");
      expect(news).not.toHaveTextContent("Claim from Ada");

      await act(() => vi.advanceTimersByTimeAsync(1000));
      await landing(() => news.textContent === "");
      expect(stepRows()[1]![3]).toBe("Done");
    });

    it("starts the keyboard at the run's heading where the reader came back to it from one of its steps", async () => {
      opening(
        { [`GET ${STEPS}`]: [stepsRead()] },
        { pathname: PAGE, state: WITHIN_THE_RUN },
        "detail",
      );

      const heading = await opened();

      await waitFor(() => expect(document.activeElement).toBe(heading));
    });

    it("leaves the keyboard where it was where the run was opened by its address", async () => {
      opening({ [`GET ${STEPS}`]: [stepsRead()] }, PAGE, "detail");

      const heading = await opened();

      expect(document.activeElement).not.toBe(heading);
      expect(document.activeElement).toBe(document.body);
    });

    it("says the run refused in place where its steps are refused, drawing nothing of it", async () => {
      opening(
        { [`GET ${STEPS}`]: [refusal("RUN_NOT_IN_VIEW", 404)] },
        PAGE,
        "detail",
      );

      expect(await screen.findByRole("alert")).toHaveTextContent(
        "That run is not in view.",
      );
      expect(screen.queryByRole("heading", { level: 2 })).toBeNull();
      expect(screen.queryByRole("table")).toBeNull();
    });

    it("draws no conversation, and names no step's message", async () => {
      opening();

      await screen.findByRole("table", { name: "The steps" });

      expect(
        screen.queryByRole("list", { name: "The conversation" }),
      ).toBeNull();
      expect(document.getElementById(`step-${SUMMARISE}`)).toBeNull();
      expect(screen.queryByRole("button", { name: "More" })).toBeNull();
    });
  });

  describe("as a conversation", () => {
    const NOTIFY = "00000009-0000-4000-8000-000000000c53";

    const CLOSE_IT = "00000009-0000-4000-8000-000000000c54";

    /** A step not reached yet. */
    function notReached(stepId: string, order: number, name: string) {
      return {
        stepId,
        order,
        name,
        runs: { kind: "route" },
        state: "not_started",
        takesFrom: [],
        cost: { callsAModel: false },
        acts: [],
        withheld: [],
      };
    }

    /** Ada's answer to the summary, which Grace assured. */
    const ANSWERED_TRY = {
      number: 1,
      beyond: false,
      producedBy: { kind: "person", person: ADA },
      values: [
        {
          field: "summary",
          value: "A kettle leaks.",
          now: "stands",
          decision: { outcome: "assured" },
        },
      ],
      review: { asked: true, by: { kind: "person", person: GRACE } },
      ended: "stands",
      cost: { callsAModel: false },
    };

    /** Ada's first answer, which Grace refused. */
    const REFUSED_TRY = {
      ...ANSWERED_TRY,
      values: [
        {
          field: "summary",
          value: "Kettle.",
          now: "refused",
          decision: { outcome: "refused", why: "Too short." },
        },
      ],
      ended: "refused_on_review",
    };

    /** The summary asked twice: refused, then answered again and assured. */
    const ASKED_TWICE = {
      ...SUMMARISE_ROW,
      tries: { current: 2, declared: 2, beyond: false },
    };

    /** The summary answered by the reader, waiting on a review the reader may not give for `refusal`. */
    function waitingOnReview(refusal: string) {
      return {
        ...SUMMARISE_ROW,
        state: "waiting",
        where: {
          kind: "waiting_on_review",
          number: 1,
          values: [{ field: "summary", on: "review_at_gate" }],
          since: "2026-09-24T08:05:00Z",
          waitsOn: "review_at_gate",
        },
        gaveBack: [
          {
            field: "summary",
            value: "A kettle leaks.",
            now: "waiting_on_review",
          },
        ],
        withheld: [{ act: "review", refusal }],
      };
    }

    function stepPage(step: object, triesMade: readonly object[]): Reply {
      return reply({
        run: RUN_HEADER,
        declarations: DECLARATIONS,
        step,
        triesMade,
      });
    }

    function conversation(): HTMLElement {
      return screen.getByRole("list", { name: "The conversation" });
    }

    async function conversing(): Promise<HTMLElement> {
      return screen.findByRole("list", { name: "The conversation" });
    }

    function message(name: string): HTMLElement {
      return within(conversation()).getByRole("listitem", { name });
    }

    /** Each message by its title, in the order drawn. */
    function messages(): string[] {
      return [...conversation().children].map(
        (each) =>
          within(each as HTMLElement).getAllByRole("heading", {
            level: 3,
          })[0]!.textContent ?? "",
      );
    }

    /** What a disclosure opens and shuts. */
    function controlled(control: HTMLElement): HTMLElement {
      return document.getElementById(control.getAttribute("aria-controls")!)!;
    }

    function labelled(part: HTMLElement): Record<string, string> {
      const terms = within(part).getAllByRole("term");
      const definitions = within(part).getAllByRole("definition");
      return Object.fromEntries(
        terms.map((term, at) => [
          term.textContent ?? "",
          definitions[at]!.textContent ?? "",
        ]),
      );
    }

    afterEach(() => {
      vi.useRealTimers();
    });

    it("heads it with its name, where it is by the title of the step it is on, and how far it has got, keeping its cost and ceiling out of sight", async () => {
      opening(
        { [`GET ${RUN}`]: [reply({ ...CLAIM, at: RUN_HEADER.at })] },
        PAGE,
        "conversation",
      );

      expect(await opened()).toHaveTextContent(setApart("Claim from Ada"));
      await conversing();

      expect(
        screen.getByText(`Running, on ${setApart("file it")}`),
      ).toBeInTheDocument();
      expect(screen.getByText("1 of 2 steps done")).toBeInTheDocument();
      expect(screen.getByRole("button", { name: "More" })).toHaveAttribute(
        "aria-expanded",
        "false",
      );
      expect(screen.queryByRole("group", { name: "Ceiling" })).toBeNull();
      expect(screen.queryByText(/in all:/)).toBeNull();
      expect(screen.queryByText("Number")).toBeNull();
    });

    it("opens behind More what it has cost, its ceiling, a way to rename it and the way to the detail, and shuts them again", async () => {
      opening({}, PAGE, "conversation");
      await conversing();
      const more = screen.getByRole("button", { name: "More" });

      await userEvent.click(more);

      expect(more).toHaveAttribute("aria-expanded", "true");
      expect(labelled(controlled(more))).toEqual({
        "What it has cost":
          "9,007,199,254,741,013 in all: 9,007,199,254,740,993 sent, 20 came back",
        Ceiling: "9,007,199,254,740,990Change the ceiling",
      });
      expect(
        within(controlled(more))
          .getAllByRole("button")
          .map((each) => each.textContent),
      ).toEqual(["Change the ceiling", "Rename", "Show in detail"]);

      await userEvent.click(more);

      expect(more).toHaveAttribute("aria-expanded", "false");
      expect(screen.queryByRole("group", { name: "Ceiling" })).toBeNull();
    });

    it("says behind More that some or all of what it has cost is as this system measured it, never as one try's", async () => {
      opening(
        {
          [`GET ${RUN}`]: [
            reply({ ...CLAIM, spend: { ...CLAIM.spend, measuredHere: true } }),
          ],
        },
        PAGE,
        "conversation",
      );
      await conversing();
      const more = screen.getByRole("button", { name: "More" });

      await userEvent.click(more);

      const cost = labelled(controlled(more))["What it has cost"];
      expect(cost).toBe(
        "9,007,199,254,741,013 in all: 9,007,199,254,740,993 sent, 20 came backSome or all of it is as this system measured it, where a model did not say what it counted.",
      );
      expect(cost).not.toContain("Both as");
    });

    it("asks for the detail from More, reading nothing more of the run", async () => {
      const { sent, onDetail } = opening({}, PAGE, "conversation");
      await conversing();
      await userEvent.click(screen.getByRole("button", { name: "More" }));

      await userEvent.click(
        screen.getByRole("button", { name: "Show in detail" }),
      );

      expect(onDetail).toHaveBeenCalledOnce();
      expect(reads(sent)).toEqual([`GET ${RUN}`, `GET ${STEPS}`]);
    });

    it("reads the steps again once an act on the run answers, heading it with the run the act answered with", async () => {
      const { sent } = opening(
        {
          [`GET ${RUN}`]: [reply(CLAIM), reply(STOPPED)],
          [`PUT ${RUN}/stop`]: [reply(STOPPED)],
        },
        PAGE,
        "conversation",
      );
      await conversing();

      await userEvent.click(screen.getByRole("button", { name: "Stop" }));

      expect(
        await screen.findByRole("button", { name: "Open again" }),
      ).toBeInTheDocument();
      await waitFor(() => expect(messages().at(-1)).toMatch(/^Stopped by /));
      const asked = requestsTo(sent);
      expect(asked.slice(asked.indexOf(`PUT ${RUN}/stop`))).toEqual([
        `PUT ${RUN}/stop`,
        `GET ${RUN}`,
        `GET ${STEPS}`,
      ]);
    });

    /** The run read again after the act is held unanswered, so all that is drawn is what the act answered. */
    it("starts the keyboard at More once a stop takes its control away from one who may not open the run again, More still shut", async () => {
      opening(
        {
          [`GET ${RUN}`]: [reply(CLAIM), UNANSWERED],
          [`PUT ${RUN}/stop`]: [
            reply({ ...STOPPED, acts: ["rename", "change_ceiling"] }),
          ],
        },
        PAGE,
        "conversation",
      );
      await conversing();
      const more = screen.getByRole("button", { name: "More" });

      await userEvent.click(screen.getByRole("button", { name: "Stop" }));

      await waitFor(() => expect(document.activeElement).toBe(more));
      expect(more).toHaveAttribute("aria-expanded", "false");
      expect(screen.queryByRole("button", { name: "Stop" })).toBeNull();
      expect(screen.queryByRole("button", { name: "Open again" })).toBeNull();
      expect(screen.queryByRole("group", { name: "Ceiling" })).toBeNull();
    });

    it("draws what it was started with first, then one message per step reached in the order they run, each named by its title, and names the steps not reached on one line after them", async () => {
      const { sent } = opening(
        {
          [`GET ${STEPS}`]: [
            stepsRead({
              steps: [
                SUMMARISE_ROW,
                FILE_IT_ROW,
                notReached(NOTIFY, 3, "notify_them"),
                notReached(CLOSE_IT, 4, "close_it"),
              ],
            }),
          ],
        },
        PAGE,
        "conversation",
      );
      await conversing();

      expect(messages()).toEqual([
        "What it was started with",
        setApart("Summarise"),
        setApart("file it"),
      ]);
      expect(
        screen.getByText(
          `Not reached yet: ${setApart("notify them")} and ${setApart("close it")}`,
        ),
      ).toBeInTheDocument();
      expect(document.getElementById(`step-${NOTIFY}`)).toBeNull();
      expect(screen.queryByRole("table")).toBeNull();
      expect(reads(sent)).toEqual([`GET ${RUN}`, `GET ${STEPS}`]);
    });

    it("says in its first message who started it, with which workflow, and each value it was started with under its label", async () => {
      opening({ [`GET ${RUN}`]: [reply(STARTED)] }, PAGE, "conversation");
      await conversing();

      const first = message("What it was started with");

      expect(first).toHaveTextContent(
        new RegExp(
          `${setApart("Ada Lovelace")} started it, .+, with ${setApart("Handle a claim")}, version 3\\.`,
        ),
      );
      expect(labelled(first)).toEqual({
        Ticket: "Kettle leaks",
        Due: "Sep 30, 2026",
      });
      expect(within(first).queryByRole("button")).toBeNull();
    });

    it("shuts a long text it was started with until it is opened, saying whether it is open", async () => {
      opening(
        {
          [`GET ${RUN}`]: [
            reply({
              ...STARTED,
              startedWith: {
                ticket: "Kettle leaks.\nIt leaked again.",
                due: null,
              },
            }),
          ],
        },
        PAGE,
        "conversation",
      );
      await conversing();
      const first = message("What it was started with");
      const control = within(first).getByRole("button", {
        name: `${setApart("Ticket")}, in full`,
      });
      expect(control).toHaveAttribute("aria-expanded", "false");
      expect(within(first).queryByText(/It leaked again/)).toBeNull();
      expect(labelled(first).Due).toBe("None");

      await userEvent.click(control);

      expect(control).toHaveAttribute("aria-expanded", "true");
      expect(controlled(control)).toHaveTextContent(
        "Kettle leaks. It leaked again.",
      );
      expect(within(first).getAllByRole("button")).toHaveLength(1);
    });

    it("says in its first message that the run above started it, opening that run, and lists nothing it was started with", async () => {
      const { name: _unnamed, startedBy: _nobody, ...beneath } = CLAIM;
      opening(
        {
          [`GET ${RUN}`]: [reply({ ...beneath, above: ABOVE, acts: [] })],
        },
        `${PAGE}?sort=run`,
        "conversation",
      );
      await conversing();

      const first = message("What it was started with");

      expect(first).toHaveTextContent(
        new RegExp(
          `The run above it started it, .+, with ${setApart("Handle a claim")}, version 3\\.`,
        ),
      );
      expect(
        within(first).getByRole("link", { name: "The run above it" }),
      ).toHaveAttribute("href", `/groups/${GROUP}/work/${ABOVE}?sort=run`);
      expect(within(first).queryByRole("term")).toBeNull();
    });

    it("says in each step's message where it is, which try, and what that try gave back under its label with where each value stands, and nothing more until Details is opened", async () => {
      const { sent } = opening({}, PAGE, "conversation");
      await conversing();

      const summarise = message(setApart("Summarise"));
      const fileIt = message(setApart("file it"));

      expect(summarise).toHaveTextContent("Done");
      expect(summarise).toHaveTextContent("Try 1 of the 2 it allows");
      expect(labelled(summarise)).toEqual({
        [setApart("Summary")]: "A kettle leaks.It stands.",
      });
      expect(
        within(summarise).getByRole("button", { name: "Details" }),
      ).toHaveAttribute("aria-expanded", "false");
      expect(within(summarise).queryByText("Produced by")).toBeNull();
      expect(within(summarise).queryByRole("link")).toBeNull();
      expect(
        within(summarise).queryByRole("button", { name: "Earlier try" }),
      ).toBeNull();
      expect(fileIt).toHaveTextContent("RunningIts code is running.");
      expect(fileIt).toHaveTextContent("Try 1 of the 1 it allows");
      expect(within(fileIt).queryByRole("term")).toBeNull();
      expect(reads(sent)).toEqual([`GET ${RUN}`, `GET ${STEPS}`]);
    });

    it("describes each message's controls by that message's title, so one message's Details is told from another's", async () => {
      opening(
        {
          [`GET ${STEPS}`]: [stepsRead({ steps: [ASKED_TWICE, FILE_IT_ROW] })],
        },
        PAGE,
        "conversation",
      );
      await conversing();
      const summarise = message(setApart("Summarise"));
      const fileIt = message(setApart("file it"));

      expect(
        within(summarise).getByRole("button", { name: "Details" }),
      ).toHaveAccessibleDescription(setApart("Summarise"));
      expect(
        within(fileIt).getByRole("button", { name: "Details" }),
      ).toHaveAccessibleDescription(setApart("file it"));
      expect(
        within(summarise)
          .getAllByRole("button")
          .map((each) => each.getAttribute("aria-describedby")),
      ).toEqual(Array(3).fill(within(summarise).getByRole("heading").id));
    });

    it("reads the step's own page as Details opens, drawing who produced its newest try, who reviewed it, where each input comes from, and the way to its page", async () => {
      const { sent } = opening(
        {
          [`GET ${STEPS}/${SUMMARISE}`]: [
            stepPage(SUMMARISE_ROW, [ANSWERED_TRY]),
          ],
        },
        `${PAGE}?sort=run`,
        "conversation",
      );
      await conversing();
      const details = within(message(setApart("Summarise"))).getByRole(
        "button",
        { name: "Details" },
      );

      await userEvent.click(details);

      expect(details).toHaveAttribute("aria-expanded", "true");
      const opened = controlled(details);
      await within(opened).findByText("Produced by");
      expect(labelled(opened)).toEqual({
        "Asked for by": "The run itself",
        "Produced by": setApart("Ada Lovelace"),
        "Reviewed by": setApart("Grace Hopper"),
        "How it ended": "It stands.",
        Cost: "Nothing: it calls no model.",
        "Takes from": `${setApart("Text")}: ${setApart("Ticket")}, which the run was started with`,
      });
      expect(
        within(opened).getByRole("link", { name: "Open this step's page" }),
      ).toHaveAttribute("href", `${PAGE}/steps/${SUMMARISE}?sort=run`);
      expect(reads(sent)).toEqual([
        `GET ${RUN}`,
        `GET ${STEPS}`,
        `GET ${STEPS}/${SUMMARISE}`,
      ]);

      await userEvent.click(details);

      expect(details).toHaveAttribute("aria-expanded", "false");
      expect(within(opened).queryByText("Produced by")).toBeNull();
    });

    it("says in Details how sure a model was of each value it said so of, after who produced it, and nothing of a value it did not", async () => {
      const model = { kind: "model", model: "small", mode: "careful" };
      const spend = {
        callsAModel: true,
        sent: "12",
        cameBack: "4",
        spent: "16",
        cameBackUnknown: false,
      };
      const sorry = { field: "reply", value: "Sorry.", now: "stands" };
      const modelled = {
        ...SUMMARISE_ROW,
        producer: model,
        cost: spend,
        gaveBack: [...SUMMARISE_ROW.gaveBack, sorry],
      };
      const declarations = {
        ...DECLARATIONS,
        [QUESTION_VERSION]: {
          ...DECLARATIONS[QUESTION_VERSION],
          gives: [
            SUMMARY,
            { ...SUMMARY, name: "reply", label: "Reply" },
            { ...SUMMARY, name: "tone", label: "Tone" },
          ],
        },
      };
      opening(
        {
          [`GET ${STEPS}`]: [
            stepsRead({ declarations, steps: [modelled, FILE_IT_ROW] }),
          ],
          [`GET ${STEPS}/${SUMMARISE}`]: [
            reply({
              run: RUN_HEADER,
              declarations,
              step: modelled,
              triesMade: [
                {
                  ...ANSWERED_TRY,
                  producedBy: model,
                  values: [
                    {
                      field: "summary",
                      value: "A kettle leaks.",
                      now: "stands",
                      confidence: 87,
                      decision: { outcome: "assured" },
                    },
                    { ...sorry, decision: { outcome: "assured" } },
                    {
                      field: "tone",
                      value: "Calm.",
                      now: "stands",
                      confidence: 40,
                      decision: { outcome: "assured" },
                    },
                  ],
                  cost: spend,
                },
              ],
            }),
          ],
        },
        PAGE,
        "conversation",
      );
      await conversing();
      const details = within(message(setApart("Summarise"))).getByRole(
        "button",
        { name: "Details" },
      );

      await userEvent.click(details);

      const opened = controlled(details);
      await within(opened).findByText("How sure");
      const fields = labelled(opened);
      expect(fields["How sure"]).toBe(
        `${setApart("Summary")}: 87%${setApart("Tone")}: 40%`,
      );
      expect(Object.keys(fields).slice(0, 3)).toEqual([
        "Asked for by",
        "Produced by",
        "How sure",
      ]);
    });

    it("says nothing in Details of how sure where no value carries it, as none of a person's try does", async () => {
      opening(
        {
          [`GET ${STEPS}/${SUMMARISE}`]: [
            stepPage(SUMMARISE_ROW, [ANSWERED_TRY]),
          ],
        },
        PAGE,
        "conversation",
      );
      await conversing();
      const details = within(message(setApart("Summarise"))).getByRole(
        "button",
        { name: "Details" },
      );

      await userEvent.click(details);

      const opened = controlled(details);
      await within(opened).findByText("Produced by");
      expect(within(opened).queryByText("How sure")).toBeNull();
      expect(opened).not.toHaveTextContent(/\d+%/);
    });

    it("turns to an earlier try in the same message, saying which of how many it is and what it gave back and was told, and back to the newest, the keyboard kept on the control left to press", async () => {
      const { sent } = opening(
        {
          [`GET ${STEPS}`]: [stepsRead({ steps: [ASKED_TWICE, FILE_IT_ROW] })],
          [`GET ${STEPS}/${SUMMARISE}`]: [
            stepPage(ASKED_TWICE, [
              REFUSED_TRY,
              { ...ANSWERED_TRY, number: 2 },
            ]),
          ],
        },
        PAGE,
        "conversation",
      );
      await conversing();
      const summarise = message(setApart("Summarise"));
      const earlier = within(summarise).getByRole("button", {
        name: "Earlier try",
      });
      const later = within(summarise).getByRole("button", {
        name: "Later try",
      });
      expect(summarise).toHaveTextContent("Try 2 of the 2 it allows");
      expect(later).toBeDisabled();

      await userEvent.click(earlier);

      await waitFor(() =>
        expect(labelled(summarise)).toEqual({
          [setApart("Summary")]:
            `Kettle.Refused on review.Refused: ${setApart("Too short.")}`,
        }),
      );
      expect(summarise).toHaveTextContent("Try 1 of the 2 it allows");
      expect(earlier).toBeDisabled();
      expect(later).toBeEnabled();
      expect(document.activeElement).toBe(later);

      await userEvent.click(later);

      expect(labelled(summarise)).toEqual({
        [setApart("Summary")]: "A kettle leaks.It stands.",
      });
      expect(summarise).toHaveTextContent("Try 2 of the 2 it allows");
      expect(later).toBeDisabled();
      expect(document.activeElement).toBe(earlier);
      expect(reads(sent)).toEqual([
        `GET ${RUN}`,
        `GET ${STEPS}`,
        `GET ${STEPS}/${SUMMARISE}`,
      ]);
    });

    it("says a value of an earlier try turned to that was refused for its length is refused for its length, and not what its review said of it", async () => {
      const refusedForLength = {
        ...REFUSED_TRY,
        values: [
          {
            field: "summary",
            value: "Kettle.",
            now: "refused_for_length",
            decision: { outcome: "assured" },
          },
        ],
        ended: "refused_for_length",
      };
      opening(
        {
          [`GET ${STEPS}`]: [stepsRead({ steps: [ASKED_TWICE, FILE_IT_ROW] })],
          [`GET ${STEPS}/${SUMMARISE}`]: [
            stepPage(ASKED_TWICE, [
              refusedForLength,
              { ...ANSWERED_TRY, number: 2 },
            ]),
          ],
        },
        PAGE,
        "conversation",
      );
      await conversing();
      const summarise = message(setApart("Summarise"));

      await userEvent.click(
        within(summarise).getByRole("button", { name: "Earlier try" }),
      );

      await waitFor(() =>
        expect(labelled(summarise)).toEqual({
          [setApart("Summary")]: "Kettle.Refused for its length.",
        }),
      );
      expect(summarise).not.toHaveTextContent("Assured.");
    });

    /** A model's try: a person's is never lost, and one of theirs giving nothing back is never followed by another. */
    it("says how a try turned to ended where it gave nothing back", async () => {
      const model = { kind: "model", model: "small", mode: "careful" };
      const spent = (sent: number, cameBack: number) => ({
        callsAModel: true,
        sent: String(sent),
        cameBack: String(cameBack),
        spent: String(sent + cameBack),
        cameBackUnknown: false,
      });
      const asked = { ...ASKED_TWICE, producer: model, cost: spent(20, 5) };
      opening(
        {
          [`GET ${STEPS}`]: [stepsRead({ steps: [asked, FILE_IT_ROW] })],
          [`GET ${STEPS}/${SUMMARISE}`]: [
            stepPage(asked, [
              {
                ...ANSWERED_TRY,
                producedBy: model,
                values: [],
                review: { asked: false },
                ended: "nothing_came_back",
                cost: spent(10, 0),
              },
              {
                ...ANSWERED_TRY,
                number: 2,
                producedBy: model,
                cost: spent(10, 5),
              },
            ]),
          ],
        },
        PAGE,
        "conversation",
      );
      await conversing();
      const summarise = message(setApart("Summarise"));

      await userEvent.click(
        within(summarise).getByRole("button", { name: "Earlier try" }),
      );

      expect(
        await within(summarise).findByText("Nothing came back."),
      ).toBeInTheDocument();
      expect(summarise).toHaveTextContent("Try 1 of the 2 it allows");
      expect(within(summarise).queryByRole("term")).toBeNull();
      expect(summarise).not.toHaveTextContent("A kettle leaks.");
    });

    it("says which way a try turned to did not fit, beneath how it ended", async () => {
      const model = { kind: "model", model: "small", mode: "careful" };
      const asked = { ...ASKED_TWICE, producer: model };
      opening(
        {
          [`GET ${STEPS}`]: [stepsRead({ steps: [asked, FILE_IT_ROW] })],
          [`GET ${STEPS}/${SUMMARISE}`]: [
            stepPage(asked, [
              {
                ...ANSWERED_TRY,
                producedBy: model,
                values: [],
                review: { asked: false },
                ended: "did_not_fit",
                didNotFit: "field_missing",
              },
              { ...ANSWERED_TRY, number: 2, producedBy: model },
            ]),
          ],
        },
        PAGE,
        "conversation",
      );
      await conversing();
      const summarise = message(setApart("Summarise"));

      await userEvent.click(
        within(summarise).getByRole("button", { name: "Earlier try" }),
      );

      const ended = await within(summarise).findByText(
        "What came back did not fit what was declared.",
      );
      expect(ended.nextElementSibling).toHaveTextContent(
        "The answer left out a value it was asked for.",
      );
      expect(summarise).not.toHaveTextContent("field_missing");
    });

    it("says each time a step held back was turned away beneath what holds it, and never again in the try its Details open", async () => {
      const model = { kind: "model", model: "small" };
      const turnedAway = [
        {
          at: "2026-09-24T08:04:00Z",
          said: "Too many asks.",
          cut: true,
          sentAgain: false,
        },
      ];
      const held = {
        ...SUMMARISE_ROW,
        producer: model,
        state: "held_back",
        where: {
          kind: "held_back",
          reason: "turned_away",
          since: "2026-09-24T08:04:00Z",
          turnedAway,
          waitsOn: "starter",
        },
        tries: { current: 1, declared: 2, beyond: false },
        gaveBack: undefined,
      };
      opening(
        {
          [`GET ${STEPS}`]: [stepsRead({ steps: [held, FILE_IT_ROW] })],
          [`GET ${STEPS}/${SUMMARISE}`]: [
            stepPage(held, [
              {
                ...ANSWERED_TRY,
                producedBy: model,
                values: [],
                review: { asked: false },
                ended: "open",
                turnedAway,
              },
            ]),
          ],
        },
        PAGE,
        "conversation",
      );
      await conversing();
      const summarise = message(setApart("Summarise"));
      const holding = within(summarise).getByText(
        "The model it names would not take it.",
      );
      const turned = within(summarise).getByText(
        `Turned away ${whenText("2026-09-24T08:04:00Z")}.`,
      );
      expect(
        holding.compareDocumentPosition(turned) &
          Node.DOCUMENT_POSITION_FOLLOWING,
      ).toBeTruthy();
      expect(summarise).toHaveTextContent(
        `The model said: ${setApart("Too many asks.")} (cut short here)`,
      );

      await userEvent.click(
        within(summarise).getByRole("button", { name: "Details" }),
      );

      await within(summarise).findByText("Produced by");
      expect(within(summarise).getAllByText(/^Turned away/)).toEqual([turned]);
      expect(
        within(summarise).queryByText("Times the model would not take it"),
      ).toBeNull();
    });

    it("lets the step's own page go once Details is shut in the middle of reading it, drawing nothing it answers", async () => {
      let settle!: (answer: readonly [string, number]) => void;
      const held = new Promise<readonly [string, number]>((done) => {
        settle = done;
      });
      const { sent } = opening(
        { [`GET ${STEPS}/${SUMMARISE}`]: [held] },
        PAGE,
        "conversation",
      );
      await conversing();
      const summarise = message(setApart("Summarise"));
      const details = within(summarise).getByRole("button", {
        name: "Details",
      });
      await userEvent.click(details);
      await waitFor(() =>
        expect(reads(sent)).toContain(`GET ${STEPS}/${SUMMARISE}`),
      );

      await userEvent.click(details);
      await act(async () => {
        settle([
          JSON.stringify({
            run: RUN_HEADER,
            declarations: DECLARATIONS,
            step: SUMMARISE_ROW,
            triesMade: [ANSWERED_TRY],
          }),
          200,
        ]);
        await held;
      });

      const [, asked] = sent.mock.calls.find(([address]) =>
        String(address).endsWith(`/steps/${SUMMARISE}`),
      )!;
      expect(asked?.signal?.aborted).toBe(true);
      expect(details).toHaveAttribute("aria-expanded", "false");
      expect(within(summarise).queryByText("Produced by")).toBeNull();
      expect(screen.queryByRole("alert")).toBeNull();
    });

    it("says once that the step's own page is being read, and once that it was refused, where a try turned to and Details both wait on it", async () => {
      let settle!: (answer: readonly [string, number]) => void;
      const held = new Promise<readonly [string, number]>((done) => {
        settle = done;
      });
      opening(
        {
          [`GET ${STEPS}`]: [stepsRead({ steps: [ASKED_TWICE, FILE_IT_ROW] })],
          [`GET ${STEPS}/${SUMMARISE}`]: [held],
        },
        PAGE,
        "conversation",
      );
      await conversing();
      const summarise = message(setApart("Summarise"));
      await userEvent.click(
        within(summarise).getByRole("button", { name: "Earlier try" }),
      );
      await userEvent.click(
        within(summarise).getByRole("button", { name: "Details" }),
      );

      expect(within(summarise).getAllByText("Still reading…")).toHaveLength(1);

      await act(async () => {
        settle([JSON.stringify({ code: "STEP_NOT_IN_VIEW" }), 404]);
        await held;
      });

      const refused = within(summarise).getAllByRole("alert");
      expect(refused).toHaveLength(1);
      expect(refused[0]).toHaveTextContent("That step is not in view.");
      expect(within(summarise).queryByText("Still reading…")).toBeNull();
      expect(within(summarise).queryByText("Produced by")).toBeNull();
    });

    /** Timeouts are faked, as where each step is kept up is; a click is dispatched, as nothing here may wait. */
    it("reads a try turned to once, and keeps the step's own page up only while Details is open", async () => {
      vi.useFakeTimers({ toFake: ["setTimeout", "clearTimeout"] });
      const { sent } = opening(
        {
          [`GET ${STEPS}`]: [
            stepsRead({
              rereadAfterSeconds: 1,
              steps: [ASKED_TWICE, FILE_IT_ROW],
            }),
          ],
          [`GET ${STEPS}/${SUMMARISE}`]: [
            stepPage(ASKED_TWICE, [
              REFUSED_TRY,
              { ...ANSWERED_TRY, number: 2 },
            ]),
          ],
        },
        PAGE,
        "conversation",
      );
      const stepReads = () =>
        reads(sent).filter((each) => each === `GET ${STEPS}/${SUMMARISE}`)
          .length;
      await landing(
        () => screen.queryByRole("list", { name: "The conversation" }) !== null,
      );
      const summarise = message(setApart("Summarise"));
      act(() =>
        within(summarise).getByRole("button", { name: "Earlier try" }).click(),
      );
      await landing(() => (summarise.textContent ?? "").includes("Kettle."));

      await act(() => vi.advanceTimersByTimeAsync(5000));
      expect(stepReads()).toBe(1);

      const details = within(summarise).getByRole("button", {
        name: "Details",
      });
      act(() => details.click());
      await act(() => vi.advanceTimersByTimeAsync(1000));
      await landing(() => stepReads() === 2);

      act(() => details.click());
      await act(() => vi.advanceTimersByTimeAsync(5000));
      expect(stepReads()).toBe(2);
      expect(vi.getTimerCount()).toBe(1);
    });

    /** Timeouts are faked, as where each step is kept up is; a click is dispatched, as nothing here may wait. */
    it("keeps the step's own page up while Details is open and the run runs, and stops once the run no longer does", async () => {
      vi.useFakeTimers({ toFake: ["setTimeout", "clearTimeout"] });
      const done = { ...CLAIM, state: "done", acts: [] };
      const { sent } = opening(
        {
          [`GET ${RUN}`]: [reply(CLAIM), reply(CLAIM), reply(done)],
          [`GET ${STEPS}`]: [
            stepsRead({ rereadAfterSeconds: 2 }),
            stepsRead({ rereadAfterSeconds: 2 }),
            stepsRead(
              { run: { ...RUN_HEADER, state: "done", acts: [] } },
              { state: "done", where: undefined },
            ),
          ],
          [`GET ${STEPS}/${SUMMARISE}`]: [
            stepPage(SUMMARISE_ROW, [ANSWERED_TRY]),
          ],
        },
        PAGE,
        "conversation",
      );
      const stepReads = () =>
        reads(sent).filter((each) => each === `GET ${STEPS}/${SUMMARISE}`)
          .length;
      await landing(
        () => screen.queryByRole("list", { name: "The conversation" }) !== null,
      );
      await act(() => vi.advanceTimersByTimeAsync(1000));
      const details = within(message(setApart("Summarise"))).getByRole(
        "button",
        { name: "Details" },
      );
      act(() => details.click());
      await landing(() => screen.queryByText("Produced by") !== null);

      await act(() => vi.advanceTimersByTimeAsync(2000));
      await landing(() => stepReads() === 2);
      await act(() => vi.advanceTimersByTimeAsync(2000));
      await landing(() =>
        (message(setApart("file it")).textContent ?? "").includes("Done"),
      );
      const whileItRan = stepReads();
      await act(() => vi.advanceTimersByTimeAsync(60_000));

      expect(stepReads()).toBe(whileItRan);
      expect(
        reads(sent).filter((each) => each === `GET ${STEPS}`),
      ).toHaveLength(3);
      expect(vi.getTimerCount()).toBe(0);
      expect(details).toHaveAttribute("aria-expanded", "true");
    });

    it.each([
      [
        "the reader gave it",
        "REVIEW_OWN_PRODUCTION",
        "You gave this, so somebody else reviews it.",
      ],
      [
        "the reader's roles reach no review",
        "ACT_NOT_PERMITTED",
        "You may not review it.",
      ],
    ])(
      "says a value waiting on review is not the reader's to review where %s, whom it waits on and since when, and offers nothing to review it with",
      async (_case, refusal, sentence) => {
        opening(
          {
            [`GET ${STEPS}`]: [
              stepsRead({ steps: [waitingOnReview(refusal), FILE_IT_ROW] }),
            ],
          },
          PAGE,
          "conversation",
        );
        await conversing();

        const summarise = message(setApart("Summarise"));

        expect(summarise).toHaveTextContent(
          `Waiting${setApart("Summary")} is not reviewed yet.It waits on somebody who may review it.`,
        );
        expect(summarise).toHaveTextContent(/Since .+Try 1/);
        expect(summarise).toHaveTextContent(sentence);
        expect(labelled(summarise)).toEqual({
          [setApart("Summary")]: "A kettle leaks.It waits on review.",
        });
        expect(
          within(summarise)
            .getAllByRole("button")
            .map((each) => each.textContent),
        ).toEqual(["Details"]);
        expect(summarise).not.toHaveTextContent(refusal);
      },
    );

    it("says who stopped what holds a step back and when, and says that moment no second time as since when it is held", async () => {
      const stoppedAt = "2026-09-24T08:05:00Z";
      opening(
        {
          [`GET ${STEPS}`]: [
            stepsRead({
              steps: [
                {
                  ...SUMMARISE_ROW,
                  state: "held_back",
                  where: {
                    kind: "held_back",
                    reason: "entry_stopped",
                    since: stoppedAt,
                    stopped: { what: "entry", by: GRACE, at: stoppedAt },
                    waitsOn: "starter",
                  },
                  gaveBack: [
                    { field: "summary", value: "Kettle.", now: "refused" },
                  ],
                  withheld: [{ act: "answer", refusal: "ENTRY_STOPPED" }],
                },
                notReached(FILE_IT, 2, "file_it"),
              ],
            }),
          ],
        },
        PAGE,
        "conversation",
      );
      await conversing();

      const summarise = message(setApart("Summarise"));

      expect(summarise).toHaveTextContent(
        `Stopped by ${setApart("Grace Hopper")}, `,
      );
      expect(summarise).not.toHaveTextContent(/Since /);
    });

    it("says since when a step failed that sending may mend, beside who stopped what it runs and when, each its own moment", async () => {
      const failedAt = "2026-09-24T08:02:00Z";
      const stoppedAt = "2026-09-24T08:05:00Z";
      opening(
        {
          [`GET ${STEPS}`]: [
            stepsRead({
              steps: [
                {
                  ...SUMMARISE_ROW,
                  state: "failed",
                  where: {
                    kind: "failed",
                    reason: "uncuttable_length",
                    since: failedAt,
                    stopped: { what: "entry", by: GRACE, at: stoppedAt },
                    waitsOn: "starter",
                  },
                  withheld: [{ act: "try_sending", refusal: "ENTRY_STOPPED" }],
                },
                notReached(FILE_IT, 2, "file_it"),
              ],
            }),
          ],
        },
        PAGE,
        "conversation",
      );
      await conversing();

      const summarise = message(setApart("Summarise"));

      expect(summarise).toHaveTextContent(
        `Stopped by ${setApart("Grace Hopper")}, ${whenText(stoppedAt)}.`,
      );
      expect(summarise).toHaveTextContent(`Since ${whenText(failedAt)}`);
      expect(summarise).not.toHaveTextContent(`Since ${whenText(stoppedAt)}`);
      expect(summarise).not.toHaveTextContent("It waits on");
    });

    it.each([
      ["done, with what it gave back", CLAIM, {}, "Done", /^What it gave back/],
      [
        "done, as one that acts rather than answers",
        CLAIM,
        { gaveBack: { declares: "nothing" } },
        "Done",
        /^Its workflow gives nothing back: it acts rather than answers\.$/,
      ],
      [
        "stopped, by whom, with what stood by then",
        STOPPED,
        {},
        new RegExp(`^Stopped by ${setApart("Grace Hopper")}, `),
        /^What stood by then/,
      ],
    ])(
      "ends with its last message where it is %s",
      async (_case, run, changed, title, body) => {
        const state = run === STOPPED ? "stopped" : "done";
        opening(
          {
            [`GET ${RUN}`]: [reply({ ...run, state, acts: [] })],
            [`GET ${STEPS}`]: [
              stepsRead(
                { run: { ...RUN_HEADER, state, acts: [] }, ...changed },
                // A run whose every step is done is done, stopped or not: a stopped one is left a step short.
                state === "stopped" ? {} : { state: "done", where: undefined },
              ),
            ],
          },
          PAGE,
          "conversation",
        );
        await conversing();

        const last = conversation().lastElementChild as HTMLElement;
        const heading = within(last).getAllByRole("heading", { level: 3 })[0]!;

        expect(heading.textContent).toMatch(title);
        expect(last.textContent!.slice(heading.textContent!.length)).toMatch(
          body,
        );
        expect(
          within(last)
            .queryAllByRole("term")
            .map((term) => term.textContent),
        ).toEqual("gaveBack" in changed ? [] : [setApart("Summary")]);
      },
    );

    it("ends with its last message saying at which step it failed and why, with what stood by then", async () => {
      opening(
        {
          [`GET ${RUN}`]: [reply({ ...CLAIM, state: "failed" })],
          [`GET ${STEPS}`]: [
            stepsRead(
              { run: { ...RUN_HEADER, state: "failed" } },
              {
                state: "failed",
                where: {
                  kind: "failed",
                  reason: "tries_spent",
                  waitsOn: "starter",
                },
              },
            ),
          ],
        },
        PAGE,
        "conversation",
      );
      await conversing();

      const last = message("Failed");

      expect(last).toHaveTextContent(
        `It failed at ${setApart("file it")}.Every try it allows has been made, and none of them stands.`,
      );
      expect(last).toHaveTextContent("What stood by then");
      expect(labelled(last)).toEqual({
        [setApart("Summary")]: "A kettle leaks.",
      });
      expect(messages().at(-1)).toBe("Failed");
    });

    /** Timeouts are faked, so what is in flight is let land a turn at a time rather than waited on. */
    it("keeps each message up for as long as the steps say to, saying what changed, and leaves the keyboard where the reader put it", async () => {
      vi.useFakeTimers({ toFake: ["setTimeout", "clearTimeout"] });
      opening(
        {
          [`GET ${STEPS}`]: [
            stepsRead({ rereadAfterSeconds: 1 }),
            stepsRead({}, { state: "done", where: undefined }),
          ],
        },
        PAGE,
        "conversation",
      );
      await landing(
        () => screen.queryByRole("list", { name: "The conversation" }) !== null,
      );
      const details = within(message(setApart("Summarise"))).getByRole(
        "button",
        { name: "Details" },
      );
      details.focus();

      await act(() => vi.advanceTimersByTimeAsync(1000));
      await landing(() =>
        (message(setApart("file it")).textContent ?? "").includes("Done"),
      );

      expect(document.activeElement).toBe(details);
      expect(screen.getAllByRole("status")[0]).toHaveTextContent(
        `${setApart("file it")}: now Done.`,
      );
      expect(message(setApart("file it"))).not.toHaveTextContent("Running");
    });

    /** Timeouts are faked, so what is in flight is let land a turn at a time rather than waited on. */
    it("says what changed of a step by its message's title, where what it runs is named otherwise than the step", async () => {
      vi.useFakeTimers({ toFake: ["setTimeout", "clearTimeout"] });
      opening(
        {
          [`GET ${STEPS}`]: [
            stepsRead({
              rereadAfterSeconds: 1,
              steps: [waitingOnReview("ACT_NOT_PERMITTED"), FILE_IT_ROW],
            }),
            stepsRead(),
          ],
        },
        PAGE,
        "conversation",
      );
      await landing(
        () => screen.queryByRole("list", { name: "The conversation" }) !== null,
      );

      await act(() => vi.advanceTimersByTimeAsync(1000));
      await landing(() =>
        (message(setApart("Summarise")).textContent ?? "").includes("Done"),
      );

      expect(screen.getAllByRole("status")[0]!.textContent).toBe(
        `${setApart("Summarise")}: now Done.`,
      );
    });

    /** Timeouts are faked, as where each step is kept up is. */
    it("starts the keyboard at the message the address names as the page arrives, and never again as it is read again", async () => {
      vi.useFakeTimers({ toFake: ["setTimeout", "clearTimeout"] });
      const { sent, router } = opening(
        { [`GET ${STEPS}`]: [stepsRead({ rereadAfterSeconds: 1 })] },
        `${PAGE}?filter=claim#step-${FILE_IT}`,
        "conversation",
      );
      await landing(
        () => screen.queryByRole("list", { name: "The conversation" }) !== null,
      );
      const aimed = message(setApart("file it"));
      await landing(() => document.activeElement === aimed);
      expect(aimed.id).toBe(`step-${FILE_IT}`);
      const { pathname, search, hash } = router.state.location;
      expect(`${pathname}${search}${hash}`).toBe(`${PAGE}?filter=claim`);
      expect(router.state.historyAction).toBe("REPLACE");

      await act(() => router.navigate(-1));
      const before = router.state.location;
      expect(`${before.pathname}${before.search}${before.hash}`).toBe(
        `${PAGE}?filter=claim`,
      );

      aimed.blur();
      await act(() => vi.advanceTimersByTimeAsync(1000));
      await landing(() => reads(sent).length === 4);

      expect(document.activeElement).toBe(document.body);
    });

    it("forgets an address naming a step not reached yet, starting the keyboard nowhere", async () => {
      const { router } = opening(
        {
          [`GET ${STEPS}`]: [
            stepsRead({
              steps: [
                SUMMARISE_ROW,
                FILE_IT_ROW,
                notReached(NOTIFY, 3, "notify_them"),
              ],
            }),
          ],
        },
        `${PAGE}?filter=claim#step-${NOTIFY}`,
        "conversation",
      );
      await conversing();

      await waitFor(() => expect(router.state.location.hash).toBe(""));
      expect(router.state.location.search).toBe("?filter=claim");
      expect(document.activeElement).toBe(document.body);
    });
  });
});
