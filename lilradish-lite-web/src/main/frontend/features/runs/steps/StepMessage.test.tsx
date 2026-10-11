import Box from "@mui/material/Box";
import { buttonClasses } from "@mui/material/Button";
import { ThemeProvider } from "@mui/material/styles";
import { act, render, screen, waitFor, within } from "@testing-library/react";
import { userEvent } from "@testing-library/user-event";
import { createRef, useImperativeHandle, useState, type Ref } from "react";
import { RouterProvider, createMemoryRouter } from "react-router";
import { afterEach, describe, expect, it, vi } from "vitest";

import type {
  RunSteps,
  StepRow,
} from "../../../api/groups/{groupId}/runs/{runId}/steps";
import { StandingProvider } from "../../../app/standing/StandingContext";
import { isolatedInText } from "../../../lib/direction/isolated";
import { BLOCK_SX } from "../../../lib/layout/parts";
import { theme } from "../../../lib/theme/theme";
import { drawnOf } from "../../../testutil/depths";
import { resolved } from "../../../testutil/grounds";
import { answered, inGroup } from "../../../testutil/standingRead";
import { requestsTo, serving, type Reply } from "../../../testutil/answering";
import { deferred } from "../../../testutil/deferred";
import { tryReplies } from "../../../testutil/tryReplies";
import { RunReadAccess } from "../RunReadAccess";
import { StepMessage } from "./StepMessage";

const GROUP = "00000003-0000-4000-8000-000000000e41";

const CLAIMS = inGroup(GROUP, "CLAIMS", "Claims", []);

const RUN_ID = "00000008-0000-4000-8000-000000000e41";

const VERSION = "00000007-0000-4000-8000-000000000e41";
const SEARCH_COST = {
  callsAModel: true,
  sent: "10",
  cameBack: "1",
  spent: "11",
  cameBackUnknown: false,
};

/** Colours as `theme.ts` writes them, copied by hand so a path nothing resolves shows. */
const RUNNING = "#1f4f99";

const QUESTION_EDGE = "#8f8c62";

const WAITING_COLOUR = "#8a4700";

const SUMMARISING: StepRow = {
  producedByUserId: null,
  producedByKind: null,
  stepId: "00000009-0000-4000-8000-000000000e41",
  order: 1,
  name: "summarise",
  runs: { versionName: null, kind: "question", name: "Summarise" },
  state: "running",
  takesFrom: [],
  cost: {},
  stateActs: new Set([]),
  stateRefusals: [],
};

const READING: RunSteps = {
  run: {
    stateActs: null,
    runId: RUN_ID,
    number: 7,
    versionId: VERSION,
    state: "running",
    progress: { done: 0, of: 1 },
  },
  declarations: new Map(),
  gaveBack: { declares: VERSION },
  steps: [SUMMARISING],
};

const WAITING: StepRow = {
  ...SUMMARISING,
  state: "waiting",
  where: {
    kind: "waiting_on_review",
    number: 1,
    values: [{ field: "summary" }],
    since: "2026-09-24T08:05:00Z",
    waitsOn: "review_at_gate",
  },
  tries: { current: 1, declared: 2, beyond: false },
  producer: { kind: "person" },
  gaveBack: [
    { field: "summary", value: "A kettle leaks.", now: "waiting_on_review" },
  ],
};

const READER = "000e41";

function materialRoutes(
  path: string,
  materials: ReadonlyMap<number, Reply>,
): Record<string, readonly Reply[]> {
  return Object.fromEntries(
    [...materials].map(([number, material]) => [
      `GET ${path}/tries/${number}`,
      [material],
    ]),
  );
}

const readAgain = createRef<(step: StepRow) => void>();

function readAgainAs(step: StepRow): void {
  readAgain.current!(step);
}

function KeptUp({
  first,
  ref,
  handTries,
  subRun,
}: {
  readonly first: StepRow;
  readonly ref: Ref<(step: StepRow) => void>;
  readonly handTries?: ReadonlySet<number>;
  readonly subRun: boolean;
}) {
  const [step, setStep] = useState(first);
  useImperativeHandle(ref, () => setStep, []);
  return (
    <ol>
      <StepMessage
        group={CLAIMS}
        reading={READING}
        step={{
          ...step,
          waitsOnStarter: subRun ? "top_level_run" : "this_run",
        }}
        handTries={handTries}
        onShown={vi.fn()}
      />
    </ol>
  );
}

async function drawn(
  step: StepRow = SUMMARISING,
  reader?: string,
  handTries?: ReadonlySet<number>,
  subRun = false,
  permissions: readonly string[] = [],
  search = "",
) {
  const router = createMemoryRouter(
    [
      {
        path: "/",
        element: (
          <KeptUp
            first={step}
            ref={readAgain}
            handTries={handTries}
            subRun={subRun}
          />
        ),
      },
    ],
    { initialEntries: [`/${search}`] },
  );
  render(
    <ThemeProvider theme={theme}>
      <StandingProvider
        read={answered(
          [],
          permissions.length === 0
            ? []
            : [{ ...CLAIMS, permissions: new Set(permissions) }],
          reader,
        )}
      >
        <RunReadAccess>
          <RouterProvider router={router} />
        </RunReadAccess>
      </StandingProvider>
    </ThemeProvider>,
  );
  return screen.findByRole("heading", { level: 2 });
}

function drawnAsBlock() {
  const probe = render(
    <ThemeProvider theme={theme}>
      <Box sx={{ ...BLOCK_SX, bgcolor: "#f6f9fb" }} />
    </ThemeProvider>,
  );
  const drawn = drawnOf(probe.container.firstElementChild!);
  probe.unmount();
  return drawn;
}

afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
});

describe("StepMessage", () => {
  describe("latest refusal foot", () => {
    const refused: StepRow = {
      ...SUMMARISING,
      state: "done",
      tries: { current: 1, declared: 2, beyond: false },
      gaveBack: [
        {
          field: "summary",
          value: "The summary's invented finding.",
          now: "refused",
        },
      ],
    };
    const path = `/api/groups/${GROUP}/runs/${RUN_ID}/steps/${refused.stepId}`;
    const decision = {
      outcome: "refused",
      why: "*Check the invented inventory*",
      whyLines: "several",
    };
    const rejectedValue = {
      field: "summary",
      value: "The detail's stored spelling.",
      now: "refused",
      decision,
    };
    const reply = (
      step: StepRow,
      values: readonly object[] = [rejectedValue],
      ended = "refused_on_review",
    ) =>
      tryReplies({
        run: READING.run,
        declarations: {},
        step: { ...step, cost: { callsAModel: false } },
        triesMade: [
          {
            number: step.tries!.current,
            beyond: false,
            producedBy: { kind: "model", model: "small" },
            values,
            review: { asked: false },
            ended,
            cost: { callsAModel: false },
          },
        ],
      }).summary;

    it.each(["visible", "withheld"] as const)(
      "reads one current length refusal while Details stays shut and shows its stored quantities with a %s reason",
      async (reason) => {
        const tooLong: StepRow = {
          ...refused,
          gaveBack: [
            {
              field: "summary",
              value: "The summary's invented finding.",
              now: "refused_for_length",
            },
          ],
        };
        const why = "Private reason from the held request.";
        const lengthDecision = {
          outcome: "refused",
          lengthUnits: "9007199254740993",
          excessUnits: "1",
          lengthHeld: {
            run: { runId: RUN_ID, number: 12 },
            stepId: SUMMARISING.stepId,
            stepName: "confirm_inventory",
            label: "Confirm inventory",
          },
          ...(reason === "visible"
            ? { why, whyLines: "several" }
            : { withheld: true }),
        };
        const sent = serving({
          [`GET ${path}`]: [
            reply(
              tooLong,
              [
                {
                  field: "summary",
                  value: "The detail's stored spelling.",
                  now: "refused_for_length",
                  decision: lengthDecision,
                },
              ],
              "refused_for_length",
            ),
          ],
        });
        await drawn(
          tooLong,
          undefined,
          undefined,
          false,
          [],
          "?filter=waiting&order=oldest&value=other&try=2&tab=history",
        );
        const held = await screen.findByRole("link", {
          name: /Open.*Confirm inventory/,
        });
        const card = screen.getByRole("region", { name: /summary/ });
        expect(card).toHaveAccessibleDescription("Rejected for length");
        expect(card).toHaveTextContent("The summary's invented finding.");
        expect(card).not.toHaveTextContent("The detail's stored spelling.");
        expect(card).toHaveTextContent(/run 12/);
        expect(card).toHaveTextContent(
          /summary\u2069: about 9,007,199,254,740,993 units/,
        );
        expect(card).toHaveTextContent(/Too long by.*about 1/);
        const content = card.querySelector("[data-value-content]")!;
        expect(content).not.toContainElement(held);
        expect(content).not.toHaveTextContent(
          /9,007|Too long by|Confirm inventory/,
        );
        expect(held).toHaveAttribute(
          "href",
          `/groups/CLAIMS/work/${RUN_ID}/steps/${SUMMARISING.stepId}?filter=waiting&order=oldest`,
        );
        expect(screen.getByRole("button", { name: "Details" })).toHaveAttribute(
          "aria-expanded",
          "false",
        );
        expect(card.textContent?.includes(why)).toBe(reason === "visible");
        expect(requestsTo(sent)).toEqual([`GET ${path}`]);
      },
    );

    it.each(["missing decision", "missing quantities"] as const)(
      "keeps a length-refused value readable without inventing a length foot for %s",
      async (missing) => {
        const tooLong: StepRow = {
          ...refused,
          gaveBack: [
            {
              field: "summary",
              value: "The summary's invented finding.",
              now: "refused_for_length",
            },
          ],
        };
        const sent = serving({
          [`GET ${path}`]: [
            reply(
              tooLong,
              [
                {
                  field: "summary",
                  value: "Stored spelling",
                  now: "refused_for_length",
                  ...(missing === "missing quantities"
                    ? { decision: { outcome: "refused" } }
                    : {}),
                },
              ],
              "refused_for_length",
            ),
          ],
        });
        await drawn(tooLong);
        await act(async () => await sent.mock.results[0]!.value);
        const card = screen.getByRole("region", { name: /summary/ });
        expect(card).toHaveAccessibleDescription("Rejected for length");
        expect(card).toHaveTextContent("The summary's invented finding.");
        expect(card).not.toHaveTextContent(/Too long by|about|Stored spelling/);
        expect(within(card).queryByRole("link")).toBeNull();
        expect(requestsTo(sent)).toEqual([`GET ${path}`]);
      },
    );

    it("reads one guarded summary for the current refusal and keeps the run summary's value authoritative", async () => {
      const sent = serving({ [`GET ${path}`]: [reply(refused)] });
      await drawn(refused);
      const reason = await screen.findByText("Check the invented inventory", {
        selector: "em",
      });
      const card = reason.closest("section")!;
      expect(card).toHaveAccessibleDescription("Rejected in review");
      expect(card).toHaveTextContent("The summary's invented finding.");
      expect(card).not.toHaveTextContent("The detail's stored spelling.");
      expect(card.querySelector("[data-value-content]")).not.toContainElement(
        reason,
      );
      expect(screen.getByRole("button", { name: "Details" })).toHaveAttribute(
        "aria-expanded",
        "false",
      );
      expect(requestsTo(sent)).toEqual([`GET ${path}`]);
      await userEvent.click(screen.getByRole("button", { name: "Details" }));
      expect(requestsTo(sent)).toEqual([`GET ${path}`]);
    });

    it.each(["try", "field", "state", "withheld"])(
      "does not attach a reason from a mismatched %s",
      async (mismatch) => {
        const incoming =
          mismatch === "try"
            ? { ...refused, tries: { current: 2, declared: 2, beyond: false } }
            : refused;
        const values = [
          {
            ...rejectedValue,
            ...(mismatch === "field" ? { field: "elsewhere" } : {}),
            ...(mismatch === "state" ? { now: "waiting_on_review" } : {}),
            ...(mismatch === "withheld"
              ? { value: undefined, withheld: true }
              : {}),
          },
        ];
        const sent = serving({ [`GET ${path}`]: [reply(incoming, values)] });
        await drawn(refused);
        await act(async () => await sent.mock.results[0]!.value);
        const card = screen.getByRole("region", { name: /summary/ });
        expect(card).toHaveAccessibleDescription("Rejected in review");
        expect(card).toHaveTextContent("The summary's invented finding.");
        expect(card).not.toHaveTextContent("Check the invented inventory");
      },
    );

    it("drops the old reason as soon as the current try or state changes", async () => {
      const second = deferred<readonly [string, number]>();
      serving({ [`GET ${path}`]: [reply(refused), second.promise] });
      await drawn(refused);
      expect(
        await screen.findByText("Check the invented inventory", {
          selector: "em",
        }),
      ).toBeVisible();
      act(() =>
        readAgainAs({
          ...refused,
          tries: { current: 2, declared: 2, beyond: false },
          gaveBack: [
            {
              field: "summary",
              value: "A newer finding.",
              now: "waiting_on_review",
            },
          ],
        }),
      );
      expect(
        screen.queryByText("Check the invented inventory", { selector: "em" }),
      ).toBeNull();
      expect(
        screen.getByRole("region", { name: /summary/ }),
      ).toHaveAccessibleDescription("Waiting for review");
      second.settle(reply(refused));
      await act(async () => {});
      expect(
        screen.queryByText("Check the invented inventory", { selector: "em" }),
      ).toBeNull();
    });

    it("abandons a pending refusal read when the summary moves to a new try", async () => {
      const owed = deferred<readonly [string, number]>();
      const sent = serving({ [`GET ${path}`]: [owed.promise] });
      await drawn(refused);
      const signal = sent.mock.calls[0]![1]!.signal!;
      expect(signal.aborted).toBe(false);
      act(() =>
        readAgainAs({
          ...refused,
          tries: { current: 2, declared: 2, beyond: false },
          gaveBack: [
            { field: "summary", value: "A newer finding.", now: "stands" },
          ],
        }),
      );
      expect(signal.aborted).toBe(true);
      owed.settle(reply(refused));
      await act(async () => {});
      const card = screen.getByRole("region", { name: /summary/ });
      expect(card).toHaveTextContent("A newer finding.");
      expect(card).not.toHaveTextContent("Check the invented inventory");
      expect(card).not.toHaveAttribute("aria-describedby");
      expect(requestsTo(sent)).toEqual([`GET ${path}`]);
    });

    it("keeps a withheld refusal's words unavailable", async () => {
      const sent = serving({
        [`GET ${path}`]: [
          reply(refused, [
            {
              ...rejectedValue,
              decision: { outcome: "refused", withheld: true },
            },
          ]),
        ],
      });
      await drawn(refused);
      await act(async () => await sent.mock.results[0]!.value);
      const card = screen.getByRole("region", { name: /summary/ });
      expect(card).toHaveAccessibleDescription("Rejected in review");
      expect(card).toHaveTextContent("The summary's invented finding.");
      expect(card).not.toHaveTextContent("Check the invented inventory");
      expect(card.querySelector("[data-value-content]")).not.toHaveTextContent(
        "Rejected:",
      );
    });

    it.each(["missing", "not found", "failed"])(
      "keeps the visible value and state without inventing a reason when the decision is %s",
      async (failure) => {
        const sent = serving({
          [`GET ${path}`]: [
            failure === "not found"
              ? ['{"code":"NOT_FOUND"}', 404]
              : failure === "failed"
                ? ['{"code":"UNAVAILABLE"}', 503]
                : reply(refused, [
                    {
                      field: "summary",
                      value: "The detail's stored spelling.",
                      now: "refused",
                    },
                  ]),
          ],
        });
        await drawn(refused);
        await act(async () => {});
        const card = screen.getByRole("region", { name: /summary/ });
        expect(card).toHaveAccessibleDescription("Rejected in review");
        expect(card).toHaveTextContent("The summary's invented finding.");
        expect(card).not.toHaveTextContent(
          /Rejected:|Check the invented inventory/,
        );
        expect(requestsTo(sent)).toEqual([`GET ${path}`]);
      },
    );

    it("does not supplement a hand try's refusal", async () => {
      const sent = serving({});
      await drawn(refused, undefined, new Set([1]));
      expect(screen.getByRole("heading", { level: 2 })).toHaveTextContent(
        "Summarise",
      );
      expect(screen.queryByText("Check the invented inventory")).toBeNull();
      expect(requestsTo(sent)).toEqual([]);
    });

    it("removes the refusal words when a guarded refresh loses run access", async () => {
      const sent = serving({
        [`GET ${path}`]: [reply(refused), ['{"code":"RUN_NOT_IN_VIEW"}', 404]],
      });
      await drawn(refused);
      expect(
        await screen.findByText("Check the invented inventory", {
          selector: "em",
        }),
      ).toBeVisible();
      act(() =>
        readAgainAs({
          ...refused,
          state: "running",
        }),
      );
      await waitFor(() =>
        expect(
          screen.queryByText("Check the invented inventory", {
            selector: "em",
          }),
        ).toBeNull(),
      );
      expect(requestsTo(sent)).toEqual([`GET ${path}`, `GET ${path}`]);
      expect(
        screen.getByRole("region", { name: /summary/ }),
      ).not.toHaveTextContent("Rejected:");
    });
  });

  it.each([
    ["waiting_on_review", "Waiting for review"],
    ["refused", "Rejected in review"],
    ["refused_for_length", "Rejected for length"],
  ])(
    "puts a %s value's state in its card head and keeps the field as its name",
    async (now, words) => {
      serving({});
      await drawn({
        ...SUMMARISING,
        state: "done",
        gaveBack: [{ field: "summary", value: "An invented finding.", now }],
      });
      const card = screen.getByRole("region", { name: /summary/ });
      expect(card).toHaveAccessibleDescription(words);
      expect(
        within(card).getByRole("heading", { level: 3 }),
      ).not.toHaveTextContent(words);
      expect(card.parentElement).not.toHaveTextContent(`${words}.`);
      expect(card.querySelector("[data-value-content]")).toHaveTextContent(
        "An invented finding.",
      );
    },
  );

  it("puts the no-value explanation inside the card foot without a state chip", async () => {
    serving({});
    await drawn({
      ...SUMMARISING,
      state: "done",
      gaveBack: [{ field: "summary", value: null, now: "none" }],
    });
    const card = screen.getByRole("region", { name: /summary/ });
    const explanation = within(card).getByText(
      "The latest attempt returned nothing for this.",
    );
    expect(card).not.toHaveAttribute("aria-describedby");
    expect(card.querySelector("[data-value-content]")).not.toContainElement(
      explanation,
    );
    expect(explanation).toHaveStyle({ marginTop: "10px" });
  });

  it("copies the step title and every permitted original input and output under their labels, excluding withheld values", async () => {
    const user = userEvent.setup();
    const copy = vi.spyOn(navigator.clipboard, "writeText");
    serving({});
    await drawn({
      ...SUMMARISING,
      label: "Prepare the fictional claim",
      state: "done",
      wentIn: [
        {
          input: "ticket",
          from: { kind: "run_input", path: "ticket" },
          value: "Avo wrote: **a leak**.",
        },
      ],
      gaveBack: [
        { field: "summary", value: "**A kettle leaks.**", now: "stands" },
        { field: "private_notes", withheld: true, now: "stands" },
      ],
    });
    await user.click(screen.getByRole("button", { name: "Copy step" }));
    expect(copy).toHaveBeenCalledExactlyOnceWith(
      "Prepare the fictional claim\n\nticket\nAvo wrote: **a leak**.\n\nsummary\n**A kettle leaks.**",
    );
    expect(copy.mock.calls[0]![0]).not.toContain("private_notes");
    expect(copy.mock.calls[0]![0]).not.toContain("permission");
    expect(screen.getByRole("button", { name: "Copied" })).toBeInTheDocument();
  });
  it.each(["missing", "refused", "both failed"] as const)(
    "keeps Copy step and Details in their action row when the clipboard is %s",
    async (failure) => {
      const user = userEvent.setup();
      const priorClipboard = Object.getOwnPropertyDescriptor(
        navigator,
        "clipboard",
      );
      const priorCommand = Object.getOwnPropertyDescriptor(
        document,
        "execCommand",
      );
      const setData = vi.fn();
      const write = vi
        .fn()
        .mockRejectedValue(new DOMException("Denied", "NotAllowedError"));
      const command = vi.fn(() => {
        if (failure === "both failed") return false;
        const event = new Event("copy", { bubbles: true, cancelable: true });
        Object.defineProperty(event, "clipboardData", { value: { setData } });
        document.dispatchEvent(event);
        expect(event.defaultPrevented).toBe(true);
        return true;
      });
      Object.defineProperty(navigator, "clipboard", {
        configurable: true,
        value: failure === "missing" ? undefined : { writeText: write },
      });
      Object.defineProperty(document, "execCommand", {
        configurable: true,
        value: command,
      });
      try {
        serving({});
        await drawn({
          ...SUMMARISING,
          label: "Prepare the fictional claim",
          state: "done",
          wentIn: [
            {
              input: "ticket",
              from: { kind: "run_input", path: "ticket" },
              value: "Avo wrote: **a leak**.",
            },
          ],
          gaveBack: [
            { field: "summary", value: "**A kettle leaks.**", now: "stands" },
            { field: "private_notes", withheld: true, now: "stands" },
          ],
        });
        const copy = screen.getByRole("button", { name: "Copy step" });
        const details = screen.getByRole("button", { name: "Details" });
        const actions = copy.parentElement!;
        const status = actions.nextElementSibling!;
        expect(status).toBeEmptyDOMElement();
        expect(details.parentElement).toBe(actions);
        expect(actions).toHaveStyle({ display: "flex", flexWrap: "wrap" });
        await user.click(copy);
        expect(copy).toHaveFocus();
        expect(details.parentElement).toBe(actions);
        expect(actions.nextElementSibling).toBe(status);
        expect(actions.querySelector('[role="status"]')).toBeNull();
        expect(status).toHaveAttribute("role", "status");
        if (failure === "both failed") {
          expect(status).toHaveTextContent(
            "Couldn't copy here. Select the text and copy it instead.",
          );
          expect(status).toHaveStyle({
            marginTop: "4px",
            textAlign: "left",
            color: "rgb(154, 79, 0)",
            fontSize: "13px",
            lineHeight: "20px",
            fontWeight: "400",
            gap: "6px",
          });
          expect(status.querySelector("svg")).toHaveAttribute(
            "aria-hidden",
            "true",
          );
          expect(status.querySelector("svg")).toHaveStyle({
            width: "16px",
            height: "16px",
            marginTop: "2px",
          });
          expect(copy).toHaveAccessibleName("Copy step");
          expect(screen.queryByRole("alert")).toBeNull();
        } else {
          expect(copy).toHaveAccessibleName("Copied");
          expect(status).toBeEmptyDOMElement();
          expect(setData).toHaveBeenCalledExactlyOnceWith(
            "text/plain",
            "Prepare the fictional claim\n\nticket\nAvo wrote: **a leak**.\n\nsummary\n**A kettle leaks.**",
          );
        }
        expect(command).toHaveBeenCalledExactlyOnceWith("copy");
        expect(screen.getByRole("heading", { level: 2 })).toHaveTextContent(
          "Prepare the fictional claim",
        );
        expect(actions).not.toHaveTextContent(
          /Something went wrong|broken_here/,
        );
      } finally {
        if (priorClipboard === undefined)
          Reflect.deleteProperty(navigator, "clipboard");
        else Object.defineProperty(navigator, "clipboard", priorClipboard);
        if (priorCommand === undefined)
          Reflect.deleteProperty(document, "execCommand");
        else Object.defineProperty(document, "execCommand", priorCommand);
      }
    },
  );

  it.each([
    [
      "\u78ba\u8a8d\u3059\u308b\u202e",
      { versionName: null, kind: "question", name: "Old entry title" },
      "\u78ba\u8a8d\u3059\u308b\u202e",
    ],
    [
      undefined,
      { versionName: null, kind: "question", name: "Old entry title" },
      "Old entry title",
    ],
    [
      undefined,
      { versionName: null, kind: "code_step", codeStep: "file_claim" },
      "file claim",
    ],
  ] as const)(
    "names a message by its pinned label, then entry, then readable machine name for %j",
    async (label, runs, expected) => {
      const sent = serving({});
      const title = await drawn({
        ...SUMMARISING,
        name: "file_claim",
        runs,
        ...(label === undefined ? {} : { label }),
      });

      expect(title).toHaveTextContent(expected);
      expect(title.textContent).toContain(isolatedInText(expected));
      expect(title).not.toHaveTextContent("file_claim");
      if (label !== undefined)
        expect(title).not.toHaveTextContent("Old entry title");
      expect(requestsTo(sent)).toEqual([]);
    },
  );
  it.each([
    [false, "stands"],
    [false, "did_not_fit"],
    [true, "stands"],
    [true, "did_not_fit"],
  ] as const)(
    "keeps historical raw inside Details for review=%s and ended=%s",
    async (reviewing, ended) => {
      const step: StepRow = {
        ...SUMMARISING,
        state: "done",
        producer: { kind: "model", model: "small" },
        tries: { current: 2, declared: 2, beyond: false },
      };
      const path = `/api/groups/${GROUP}/runs/${RUN_ID}/steps/${step.stepId}`;
      const raw = `Invented ${reviewing ? "review" : "production"} original ${ended} {"answer":"kept"}`;
      const reply = { text: raw, altered: false, truncated: false };
      const material = tryReplies({
        run: READING.run,
        declarations: {},
        step: { ...step, cost: SEARCH_COST },
        triesMade: [
          {
            number: 1,
            beyond: false,
            producedBy: { kind: "model", model: "small" },
            values:
              !reviewing && ended === "did_not_fit"
                ? []
                : [
                    {
                      field: "summary",
                      value: "An invented kettle needs a seal.",
                      now:
                        reviewing && ended === "did_not_fit"
                          ? "refused"
                          : "stands",
                    },
                  ],
            review: reviewing
              ? {
                  asked: true,
                  by: { kind: "model", model: "judge" },
                  ...(ended === "did_not_fit"
                    ? { didNotFit: "not_the_shape" }
                    : {}),
                  modelReply: reply,
                }
              : { asked: false },
            ended:
              reviewing && ended === "did_not_fit"
                ? "refused_on_review"
                : ended,
            ...(!reviewing && ended === "did_not_fit"
              ? { didNotFit: "not_the_shape" }
              : {}),
            ...(!reviewing ? { modelReply: reply } : {}),
            cost: SEARCH_COST,
          },
          {
            number: 2,
            beyond: false,
            producedBy: { kind: "model", model: "small" },
            values: [],
            review: { asked: false },
            ended: "stands",
            cost: SEARCH_COST,
          },
        ],
      });
      const sent = serving({
        ...materialRoutes(path, material.materials),
        [`GET ${path}`]: [[material.summary[0], 200]],
      });
      const title = await drawn(step, READER, undefined, false, [
        "read_own_runs",
        "read_inference_content",
      ]);
      const message = title.closest("li")!;
      const details = within(message).getByRole("button", { name: "Details" });
      await userEvent.click(
        within(message).getByRole("button", { name: "Previous attempt" }),
      );
      await waitFor(() =>
        expect(
          requestsTo(sent).filter(
            (request) => request === `GET ${path}/tries/1`,
          ),
        ).toHaveLength(1),
      );
      await act(async () => {});
      expect(details).toHaveAttribute("aria-expanded", "false");
      expect(within(message).queryByText(raw)).toBeNull();
      await userEvent.click(details);
      const rawControl = await within(message).findByRole("button", {
        name: "The model's reply, exactly as it came",
      });
      expect(rawControl).toHaveAttribute("aria-expanded", "false");
      expect(within(message).queryByText(raw)).toBeNull();
      await userEvent.click(rawControl);
      expect(await within(message).findByText(raw)).toBeVisible();
      expect(within(message).getAllByText(raw)).toHaveLength(1);
      expect(
        requestsTo(sent).filter((request) => request === `GET ${path}/tries/1`),
      ).toHaveLength(1);
    },
  );

  it.each([
    [false, "Waiting for the person who started the run."],
    [true, "Waiting for the person who started the top-level run."],
  ] as const)(
    "opens the starter account for a sub-run flag of %s",
    async (subRun, sentence) => {
      const sent = serving({});
      const title = await drawn(
        {
          ...WAITING,
          reviewer: { kind: "model", model: "small" },
          cost: {
            spend: {
              sent: "0",
              cameBack: "0",
              spent: "0",
              cameBackUnknown: false,
            },
          },
          where: {
            ...WAITING.where!,
            review: "turned_away",
            waitsOn: "starter",
          },
          stateActs: new Set(["try_sending"]),
        },
        undefined,
        undefined,
        subRun,
        ["start_run"],
      );
      const message = title.closest("li")!;
      expect(message).not.toHaveTextContent(sentence);

      await userEvent.click(
        within(message).getByRole("button", { name: "Waiting" }),
      );

      expect(message).toHaveTextContent(sentence);
      expect(message).toHaveTextContent(
        "Waiting for the model's review, which could not be sent: the model service turned the request away.",
      );
      expect(message).toHaveTextContent(
        "Someone who can start runs can try sending it.",
      );
      expect(
        within(message).getByRole("button", { name: "Try sending" }),
      ).toBeEnabled();
      expect(message).toHaveTextContent(
        `${isolatedInText("summary")} has not been reviewed yet.`,
      );
      expect(message).not.toHaveTextContent(
        subRun
          ? "Waiting for the person who started the run."
          : "Waiting for the person who started the top-level run.",
      );
      expect(requestsTo(sent)).toEqual([]);
    },
  );
  it("changes the account's day at midnight without reading a step or moving its open control", async () => {
    vi.useFakeTimers({ toFake: ["Date"] });
    vi.setSystemTime(new Date("2026-10-03T21:59:30Z"));
    const sent = serving({});
    const step: StepRow = {
      ...WAITING,
      where: { ...WAITING.where!, since: "2026-10-03T21:59:00Z" },
    };
    const title = await drawn(step);
    const message = title.closest("li")!;
    const control = within(message).getByRole("button", { name: "Waiting" });
    await act(async () => control.click());
    control.focus();
    expect(message).toHaveTextContent("11:59 PM today");

    vi.setSystemTime(new Date("2026-10-03T22:00:00Z"));
    act(() => document.dispatchEvent(new Event("visibilitychange")));

    expect(message).toHaveTextContent("11:59 PM yesterday");
    expect(document.activeElement).toBe(control);
    expect(requestsTo(sent)).toEqual([]);
  });

  it("keeps a reviewer's refusal and literal words beside nonempty values on an earlier machine try", async () => {
    const step: StepRow = {
      ...SUMMARISING,
      state: "done",
      producer: { kind: "model", model: "small" },
      tries: { current: 2, declared: 2, beyond: false },
    };
    const path = `/api/groups/${GROUP}/runs/${RUN_ID}/steps/${step.stepId}`;
    const material = tryReplies({
      run: READING.run,
      declarations: {},
      step: { ...step, cost: SEARCH_COST },
      triesMade: [
        {
          number: 1,
          beyond: false,
          producedBy: { kind: "model", model: "small" },
          values: [
            {
              field: "summary",
              value: "Demo Sample answer",
              now: "refused",
            },
          ],
          unreadableResponse: {
            text: '<a href="/outside">Demo Sample service</a>',
            altered: true,
            truncated: false,
            stopped: true,
          },
          review: {
            asked: true,
            by: { kind: "model", model: "judge" },
            didNotFit: "model_declined",
            declined: {
              wordsLines: "several",
              words: {
                text: "I cannot approve Demo Sample.",
                altered: false,
                truncated: false,
              },
            },
            unreadableResponse: {
              text: "Demo Sample review service",
              altered: false,
              truncated: true,
              stopped: false,
            },
          },
          ended: "refused_on_review",
          cost: SEARCH_COST,
        },
        {
          number: 2,
          beyond: false,
          producedBy: { kind: "model", model: "small" },
          values: [],
          review: { asked: false },
          ended: "stands",
          cost: SEARCH_COST,
        },
      ],
    });
    const sent = serving({
      ...materialRoutes(path, material.materials),
      [`GET ${path}`]: [[material.summary[0], 200]],
    });
    const title = await drawn(step, READER, undefined, false, [
      "read_own_runs",
      "read_inference_content",
    ]);
    const message = title.closest("li")!;

    expect(message).not.toHaveTextContent("I cannot approve Demo Sample.");
    expect(
      requestsTo(sent).filter((request) => request === `GET ${path}`),
    ).toHaveLength(0);
    await userEvent.click(
      within(message).getByRole("button", { name: "Previous attempt" }),
    );

    expect(
      await within(message).findByText("I cannot approve Demo Sample."),
    ).toBeVisible();
    expect(message).toHaveTextContent("Demo Sample answer");
    expect(
      within(message).getAllByText("What the model service sent back"),
    ).toHaveLength(2);
    for (const control of within(message).getAllByRole("button", {
      name: "What the model service sent back",
    })) {
      expect(control).toHaveAttribute("aria-expanded", "false");
      await userEvent.click(control);
    }
    expect(message).toHaveTextContent(
      '<a href="/outside">Demo Sample service</a>',
    );
    expect(message).toHaveTextContent("Demo Sample review service");
    expect(
      within(message).getAllByText(
        "This could not be read, so the rest of it was not downloaded. Only its start is kept here.",
      ),
    ).toHaveLength(1);
    expect(
      within(message).getAllByText(
        "It is too long to store in full, so it is cut short here.",
      ),
    ).toHaveLength(1);
    expect(
      within(message).queryByRole("link", { name: "Demo Sample service" }),
    ).toBeNull();
    expect(message).toHaveTextContent(
      "The model declined to answer, so nothing was taken from it, and this attempt is used up.",
    );
    expect(
      requestsTo(sent).filter((request) => request === `GET ${path}`),
    ).toHaveLength(1);
  });

  it("names a failed review and a shortened member on an earlier code try", async () => {
    const step: StepRow = {
      ...SUMMARISING,
      runs: { versionName: null, kind: "code_step", codeStep: "file_claim" },
      producer: { kind: "code" },
      state: "done",
      tries: { current: 2, declared: 2, beyond: false },
    };
    const path = `/api/groups/${GROUP}/runs/${RUN_ID}/steps/${step.stepId}`;
    const sent = serving({
      [`GET ${path}`]: [
        [
          tryReplies({
            run: READING.run,
            declarations: {},
            step: { ...step, cost: { callsAModel: false } },
            triesMade: [
              {
                number: 1,
                beyond: false,
                producedBy: { kind: "code" },
                values: [],
                review: {
                  asked: true,
                  by: { kind: "model", model: "small" },
                  wentWrong: true,
                  lost: "errored",
                },
                ended: "errored",
                erroredFor: {
                  reason: "not_declared",
                  member: "Demo Sample",
                  memberCut: true,
                },
                cost: { callsAModel: false },
              },
              {
                number: 2,
                beyond: false,
                producedBy: { kind: "code" },
                values: [],
                review: { asked: false },
                ended: "stands",
                cost: { callsAModel: false },
              },
            ],
          }).summary[0],
          200,
        ],
      ],
    });
    const title = await drawn(step);
    const message = title.closest("li")!;

    await userEvent.click(
      within(message).getByRole("button", { name: "Previous attempt" }),
    );
    expect(
      await within(message).findByText("(the review hit an error)"),
    ).toBeVisible();
    expect(message).toHaveTextContent("small (the review hit an error)");
    expect(message.querySelector("bdi")).toHaveTextContent("small");
    expect(message).toHaveTextContent(
      `a field named ${isolatedInText("Demo Sample")} (name shortened here)`,
    );
    expect(
      [...message.querySelectorAll("span")].some(
        (part) => part.textContent === "(name shortened here)",
      ),
    ).toBe(true);
    expect(
      requestsTo(sent).filter((request) => request === `GET ${path}`),
    ).toHaveLength(1);
    expect(
      within(message).getByRole("button", { name: "Previous attempt" }),
    ).toHaveAttribute("aria-disabled", "true");
  });

  describe.each([
    [
      "source_no_longer_matches",
      "failedSourceNoLongerMatches",
      `The output ${isolatedInText("info.answer")} of Demo Sample source no longer matches what this step expects from it, now that the code step has changed, so nothing was sent.`,
    ],
    [
      "source_list_not_here",
      "failedSourceListNotHere",
      `The options for ${isolatedInText("info.answer")}, from Demo Sample source, come from a reference list version that isn't in service in this group, so nothing was sent.`,
    ],
  ] as const)("%s", (reason, member, sentence) => {
    it.each([
      [
        "question",
        { versionName: null, kind: "question", name: "Summarise" },
        RUN_ID,
      ],
      [
        "question",
        { versionName: null, kind: "question", name: "Summarise" },
        "00000008-0000-4000-8000-000000000e42",
      ],
      [
        "search",
        { versionName: null, kind: "search", searchWay: "web" },
        RUN_ID,
      ],
      [
        "search",
        { versionName: null, kind: "search", searchWay: "web" },
        "00000008-0000-4000-8000-000000000e42",
      ],
    ] as const)(
      "says which source field a failed %s no longer reads and draws only its offered sending act",
      async (_kind, runs, sourceRun) => {
        const sourceStep = "00000009-0000-4000-8000-000000000e42";
        const sourceNumber = sourceRun === RUN_ID ? 7 : 19;
        const failed: StepRow = {
          ...SUMMARISING,
          runs,
          state: "failed",
          stateActs: new Set(["try_sending"]),
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
        };
        const title = await drawn(failed, undefined, undefined, false, [
          "start_run",
        ]);
        const message = title.closest("li")!;

        expect(
          within(message).getByRole("button", { name: "Try sending" }),
        ).toBeEnabled();
        expect(
          within(message).queryByRole("button", { name: "Ask again" }),
        ).not.toBeInTheDocument();
        expect(
          within(message).queryByRole("button", { name: "Answer it yourself" }),
        ).not.toBeInTheDocument();
        await userEvent.click(
          within(message).getByRole("button", { name: "Failed" }),
        );

        const source = within(message).getByRole("link", {
          name: "Demo Sample source",
        });
        expect(source).toHaveAttribute(
          "href",
          `/groups/CLAIMS/work/${sourceRun}/steps/${sourceStep}`,
        );
        expect(source).toHaveAttribute("title", `Run ${sourceNumber}`);
        expect(source.querySelector("bdi")).toHaveTextContent(
          "Demo Sample source",
        );
        expect(message).toHaveTextContent(sentence);
        expect(message).not.toHaveTextContent("goes on by itself");
        expect(message).not.toHaveTextContent(
          "does not include the code this step runs",
        );
        expect(source).not.toHaveAttribute(
          "href",
          `/groups/CLAIMS/work/${RUN_ID}/steps/${failed.stepId}`,
        );
      },
    );
  });

  it.each([
    ["question", { versionName: null, kind: "question", name: "Summarise" }],
    ["search", { versionName: null, kind: "search", searchWay: "web" }],
  ] as const)(
    "links the actual source of a %s held on missing source code and removes that account when it is released",
    async (_kind, runs) => {
      const sourceRun = "00000008-0000-4000-8000-000000000e42";
      const sourceStep = "00000009-0000-4000-8000-000000000e42";
      const held: StepRow = {
        ...SUMMARISING,
        runs,
        state: "held_back",
        where: {
          kind: "held_back",
          reason: "code_step_not_held",
          waitsOn: "starter",
          source: {
            runId: sourceRun,
            runNumber: 19,
            stepId: sourceStep,
            name: "Demo Sample source",
            codeStep: "tally",
          },
        },
      };
      const title = await drawn(held);
      const message = title.closest("li")!;
      expect(
        within(message).queryByRole("link", { name: "Demo Sample source" }),
      ).not.toBeInTheDocument();

      await userEvent.click(
        within(message).getByRole("button", { name: "Blocked" }),
      );

      const source = within(message).getByRole("link", {
        name: "Demo Sample source",
      });
      expect(source).toHaveAttribute(
        "href",
        `/groups/CLAIMS/work/${sourceRun}/steps/${sourceStep}`,
      );
      expect(source).toHaveAttribute("title", "Run 19");
      expect(source.querySelector("bdi")).toHaveTextContent(
        "Demo Sample source",
      );
      expect(message).toHaveTextContent(
        "An input comes from Demo Sample source, whose code isn't in the installed version of this system, so it can't be sent. It continues automatically once a system update brings that code back.",
      );
      expect(message).not.toHaveTextContent(
        "does not include the code this step runs",
      );
      expect(message).not.toHaveTextContent(
        "Someone who can start runs can try sending it.",
      );

      await act(async () => readAgainAs({ ...SUMMARISING, runs }));

      expect(
        within(message).queryByRole("link", { name: "Demo Sample source" }),
      ).not.toBeInTheDocument();
      expect(message).not.toHaveTextContent("A value it reads came from");
    },
  );

  it("refreshes an automatically opened search record when its waiting try changes and drops the old record during the new read", async () => {
    const first: StepRow = {
      ...WAITING,
      runs: { versionName: null, kind: "search", searchWay: "web" },
      producer: { kind: "model", model: "small" },
      producedByKind: "model",
      producedByUserId: null,
      stateActs: new Set(["review"]),
    };
    const next: StepRow = {
      ...first,
      where: { ...first.where!, number: 2 },
      tries: { current: 2, declared: 2, beyond: false },
    };
    const path = `/api/groups/${GROUP}/runs/${RUN_ID}/steps/${first.stepId}`;
    const detail = (step: StepRow, question: string) =>
      tryReplies({
        run: READING.run,
        declarations: {},
        step: { ...step, cost: SEARCH_COST },
        triesMade: [
          {
            number: step.where!.number,
            beyond: false,
            producedBy: first.producer,
            values: [],
            review: { asked: true },
            ended: "waiting",
            cost: SEARCH_COST,
            search: { searches: [{ found: question }] },
          },
        ],
      });
    const firstReply = detail(first, "First search record");
    const nextReply = detail(next, "Next search record");
    const later = deferred<readonly [string, number]>();
    const laterMaterial = deferred<readonly [string, number]>();
    const sent = serving({
      [`GET ${path}`]: [firstReply.summary, later.promise],
      [`GET ${path}/tries/1`]: [firstReply.materials.get(1)!],
      [`GET ${path}/tries/2`]: [laterMaterial.promise],
    });

    await drawn(first, READER, undefined, false, [
      "read_own_runs",
      "read_inference_content",
      "review_at_gate",
    ]);
    expect(await screen.findByText("First search record")).toBeInTheDocument();
    act(() => readAgainAs(next));
    expect(screen.queryByText("First search record")).toBeNull();
    expect(screen.queryByText("Next search record")).toBeNull();
    await act(async () => later.settle(nextReply.summary));
    expect(screen.queryByText("First search record")).toBeNull();
    expect(screen.queryByText("Next search record")).toBeNull();
    await act(async () =>
      laterMaterial.settle(
        nextReply.materials.get(2)! as readonly [string, number],
      ),
    );
    expect(await screen.findByText("Next search record")).toBeInTheDocument();
    expect(screen.queryByText("First search record")).toBeNull();
    expect(
      requestsTo(sent).filter((each) => each === `GET ${path}`),
    ).toHaveLength(2);
    expect(
      requestsTo(sent).filter((each) => each === `GET ${path}/tries/1`),
    ).toHaveLength(1);
    expect(
      requestsTo(sent).filter((each) => each === `GET ${path}/tries/2`),
    ).toHaveLength(1);
  });
  it("reads a search record automatically for the actual waiting try and does not draw it twice when its detail opens", async () => {
    const searchStep: StepRow = {
      ...WAITING,
      runs: { versionName: null, kind: "search", searchWay: "web" },
      producer: { kind: "model", model: "small" },
      producedByKind: "model",
      producedByUserId: null,
      stateActs: new Set(["review"]),
    };
    const path = `/api/groups/${GROUP}/runs/${RUN_ID}/steps/${searchStep.stepId}`;
    const material = tryReplies({
      run: READING.run,
      declarations: {},
      step: { ...searchStep, cost: SEARCH_COST },
      triesMade: [
        {
          number: 1,
          beyond: false,
          producedBy: searchStep.producer,
          values: [],
          review: { asked: true },
          ended: "waiting",
          cost: SEARCH_COST,
          search: {
            searches: [
              {
                found:
                  "## Imaginary source record\n[Guide](https://example.com/claim)",
              },
            ],
          },
        },
      ],
    });
    const sent = serving({
      ...materialRoutes(path, material.materials),
      [`GET ${path}`]: [[material.summary[0], 200]],
    });

    await drawn(searchStep, READER, undefined, false, [
      "read_own_runs",
      "read_inference_content",
      "review_at_gate",
    ]);
    expect(
      await screen.findByRole("heading", { name: "Imaginary source record" }),
    ).toBeInTheDocument();
    expect(
      await screen.findAllByRole("button", { name: "Show as written" }),
    ).toHaveLength(1);
    await userEvent.click(
      screen.getByRole("button", { name: "Show as written" }),
    );
    expect(
      screen.queryByRole("heading", { name: "Imaginary source record" }),
    ).toBeNull();
    expect(screen.getByText(/## Imaginary source record/)).toBeInTheDocument();
    expect(
      requestsTo(sent).filter((each) => each === `GET ${path}`),
    ).toHaveLength(1);
    await userEvent.click(screen.getByRole("button", { name: "Details" }));
    expect(
      screen.queryByRole("heading", { name: "Imaginary source record" }),
    ).toBeNull();
    expect(screen.getByText(/## Imaginary source record/)).toBeInTheDocument();
    await userEvent.click(
      screen.getByRole("button", { name: "Show formatted" }),
    );
    expect(
      await screen.findAllByRole("heading", {
        name: "Imaginary source record",
      }),
    ).toHaveLength(1);
    expect(
      screen.getAllByRole("button", { name: "Show as written" }),
    ).toHaveLength(1);
    await userEvent.click(
      screen.getByRole("button", { name: "Show as written" }),
    );
    await userEvent.click(screen.getByRole("button", { name: "Details" }));
    await userEvent.click(screen.getByRole("button", { name: "Details" }));
    expect(
      screen.queryByRole("heading", { name: "Imaginary source record" }),
    ).toBeNull();
    expect(screen.getByText(/## Imaginary source record/)).toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: "Show formatted" }),
    ).toBeVisible();
    expect(
      requestsTo(sent).filter((each) => each === `GET ${path}`),
    ).toHaveLength(1);
  });

  it("does not put an earlier search snapshot beside a newer waiting try", async () => {
    const searchStep: StepRow = {
      ...WAITING,
      runs: { versionName: null, kind: "search", searchWay: "web" },
      producer: { kind: "model", model: "small" },
      where: { ...WAITING.where!, number: 2 },
      producedByKind: "model",
      producedByUserId: null,
      tries: { current: 2, declared: 2, beyond: false },
      stateActs: new Set(["review"]),
    };
    const path = `/api/groups/${GROUP}/runs/${RUN_ID}/steps/${searchStep.stepId}`;
    serving({
      [`GET ${path}`]: [
        [
          tryReplies({
            run: READING.run,
            declarations: {},
            step: {
              ...searchStep,
              where: { ...searchStep.where, number: 1 },
              cost: SEARCH_COST,
            },
            triesMade: [
              {
                number: 1,
                beyond: false,
                producedBy: searchStep.producer,
                values: [],
                review: { asked: true },
                ended: "waiting",
                cost: SEARCH_COST,
                search: { searches: [{ found: "Earlier record" }] },
              },
            ],
          }).summary[0],
          200,
        ],
      ],
    });

    await drawn(searchStep, READER, undefined, false, ["review_at_gate"]);
    await userEvent.click(screen.getByRole("button", { name: "Details" }));
    expect(
      await screen.findByRole("link", { name: "Open this step's page" }),
    ).toBeInTheDocument();
    expect(
      screen.getByRole("heading", { name: "Needs your review" }),
    ).toBeInTheDocument();
    expect(screen.queryByText("Earlier record")).toBeNull();
    expect(screen.queryByText("Search question")).toBeNull();
  });

  it("reads held detail only when Details opens and drops the hold after it is released", async () => {
    const searchStep: StepRow = {
      ...SUMMARISING,
      runs: { versionName: null, kind: "search", searchWay: "web" },
      state: "held_back",
      where: { kind: "held_back", reason: "too_long", waitsOn: "answer_step" },
    };
    const path = `/api/groups/${GROUP}/runs/${RUN_ID}/steps/${searchStep.stepId}`;
    const sent = serving({
      [`GET ${path}`]: [
        [
          tryReplies({
            run: READING.run,
            declarations: {},
            step: {
              ...searchStep,
              where: searchStep.where,
              cost: SEARCH_COST,
            },
            triesMade: [],
          }).summary[0],
          200,
        ],
      ],
    });

    const title = await drawn(searchStep);
    expect(
      requestsTo(sent).filter((each) => each === `GET ${path}`),
    ).toHaveLength(0);
    await userEvent.click(
      within(title.closest("li")!).getByRole("button", { name: /Blocked/ }),
    );
    expect(
      requestsTo(sent).filter((each) => each === `GET ${path}`),
    ).toHaveLength(0);
    await userEvent.click(screen.getByRole("button", { name: "Details" }));
    expect(
      await screen.findByRole("link", { name: "Open this step's page" }),
    ).toBeInTheDocument();
    expect(
      requestsTo(sent).filter((each) => each === `GET ${path}`),
    ).toHaveLength(1);
    await act(async () =>
      readAgainAs({
        ...searchStep,
        state: "running",
        where: { kind: "running", on: "call" },
      }),
    );
    expect(
      within(title.closest("li")!).queryByRole("button", { name: /Blocked/ }),
    ).toBeNull();
  });
  it("reads only the changed opened detail after the run rereads its steps", async () => {
    const second: StepRow = {
      ...SUMMARISING,
      stepId: "00000009-0000-4000-8000-000000000e42",
      order: 2,
      name: "archive",
      runs: { versionName: null, kind: "question", name: "Archive" },
      tries: { current: 1, declared: 1, beyond: false },
    };
    const first: StepRow = {
      ...SUMMARISING,
      state: "done",
      tries: { current: 1, declared: 1, beyond: false },
    };
    const detail = (step: StepRow, why: string) =>
      tryReplies({
        run: READING.run,
        declarations: {},
        step: { ...step, cost: { callsAModel: false } },
        triesMade: [
          {
            number: 1,
            beyond: false,
            producedBy: { kind: "person" },
            why,
            values: [],
            review: { asked: false },
            ended: "done",
            cost: { callsAModel: false },
          },
        ],
      }).summary[0];
    const firstPath = `/api/groups/${GROUP}/runs/${RUN_ID}/steps/${first.stepId}`;
    const secondPath = `/api/groups/${GROUP}/runs/${RUN_ID}/steps/${second.stepId}`;
    const sent = serving({
      [`GET ${firstPath}`]: [[detail(first, "First detail"), 200]],
      [`GET ${secondPath}`]: [
        ['{"code":"BROKEN_HERE"}', 503],
        [detail(second, "Earlier detail"), 200],
        [detail({ ...second, state: "done" }, "Current detail"), 200],
      ],
    });
    const changeRows = createRef<(rows: StepRow[]) => void>();
    function TwoMessages() {
      const [rows, setRows] = useState([first, second]);
      useImperativeHandle(changeRows, () => setRows, []);
      return (
        <ol>
          {rows.map((row) => (
            <StepMessage
              key={row.stepId}
              group={CLAIMS}
              reading={{ ...READING, steps: rows, rereadAfterSeconds: 1 }}
              step={row}
              onShown={vi.fn()}
            />
          ))}
        </ol>
      );
    }
    const router = createMemoryRouter(
      [{ path: "/", element: <TwoMessages /> }],
      { initialEntries: ["/"] },
    );
    render(
      <ThemeProvider theme={theme}>
        <StandingProvider read={answered([], [])}>
          <RunReadAccess>
            <RouterProvider router={router} />
          </RunReadAccess>
        </StandingProvider>
      </ThemeProvider>,
    );
    const details = screen.getAllByRole("button", { name: "Details" });
    await userEvent.click(details[0]!);
    await userEvent.click(details[1]!);
    expect(await screen.findByText("First detail")).toBeVisible();
    expect(await screen.findByRole("alert")).toBeVisible();
    const readsOf = (path: string) =>
      requestsTo(sent).filter((request) => request === `GET ${path}`).length;
    expect(readsOf(firstPath)).toBe(1);
    expect(readsOf(secondPath)).toBe(1);

    act(() => changeRows.current!([{ ...first }, { ...second }]));
    expect(await screen.findByText("Earlier detail")).toBeVisible();
    expect(readsOf(firstPath)).toBe(1);
    expect(readsOf(secondPath)).toBe(2);

    vi.useFakeTimers({ toFake: ["setTimeout", "clearTimeout"] });
    act(() => changeRows.current!([{ ...first }, { ...second }]));
    await act(() => vi.advanceTimersByTimeAsync(5_000));
    expect(readsOf(firstPath)).toBe(1);
    expect(readsOf(secondPath)).toBe(2);

    act(() =>
      changeRows.current!([{ ...first }, { ...second, state: "done" }]),
    );
    await act(async () => Promise.resolve());
    expect(readsOf(firstPath)).toBe(1);
    expect(readsOf(secondPath)).toBe(3);
    expect(screen.getByText("Current detail")).toBeVisible();
    expect(screen.queryByText("Earlier detail")).toBeNull();

    await act(async () => details[1]!.click());
    act(() =>
      changeRows.current!([{ ...first }, { ...second, state: "failed" }]),
    );
    await act(() => vi.advanceTimersByTimeAsync(5_000));
    expect(readsOf(firstPath)).toBe(1);
    expect(readsOf(secondPath)).toBe(3);
  });

  it("shows the newer detail when its step changes before the first read answers", async () => {
    const first: StepRow = {
      ...SUMMARISING,
      tries: { current: 1, declared: 1, beyond: false },
    };
    const changed: StepRow = { ...first, state: "done" };
    const detail = (step: StepRow, why: string) =>
      tryReplies({
        run: READING.run,
        declarations: {},
        step: { ...step, cost: { callsAModel: false } },
        triesMade: [
          {
            number: 1,
            beyond: false,
            producedBy: { kind: "person" },
            why,
            values: [],
            review: { asked: false },
            ended: "done",
            cost: { callsAModel: false },
          },
        ],
      }).summary[0];
    let settleOld!: (reply: readonly [string, number]) => void;
    const old = new Promise<readonly [string, number]>((resolve) => {
      settleOld = resolve;
    });
    const path = `/api/groups/${GROUP}/runs/${RUN_ID}/steps/${first.stepId}`;
    const sent = serving({
      [`GET ${path}`]: [old, [detail(changed, "New detail"), 200]],
    });
    const title = await drawn(first);
    await userEvent.click(
      within(title.closest("li")!).getByRole("button", { name: "Details" }),
    );
    expect(
      requestsTo(sent).filter((request) => request === `GET ${path}`),
    ).toHaveLength(1);

    act(() => readAgainAs(changed));
    expect(await screen.findByText("New detail")).toBeVisible();
    expect(
      requestsTo(sent).filter((request) => request === `GET ${path}`),
    ).toHaveLength(2);

    await act(async () => settleOld([detail(first, "Old detail"), 200]));
    expect(screen.getByText("New detail")).toBeVisible();
    expect(screen.queryByText("Old detail")).toBeNull();
  });

  it("leaves a person's newest try to its separate act message", async () => {
    const step: StepRow = {
      ...SUMMARISING,
      state: "done",
      tries: { current: 1, declared: 2, beyond: false },
      gaveBack: [{ field: "answer", value: "Demo Sample", now: "standing" }],
    };

    const title = await drawn(step, undefined, new Set([1]));
    const message = title.closest("li")!;

    expect(message).toHaveAccessibleName(isolatedInText("Summarise"));
    expect(message).not.toHaveTextContent("Demo Sample");
    expect(within(message).queryByText("Attempt 1 of 2")).toBeNull();
  });
  it("sets the kind it runs apart on the title's line, and out of the title's name", async () => {
    const title = await drawn();

    const tag = within(title.parentElement!).getByText("Question");

    expect(title).toHaveAccessibleName(isolatedInText("Summarise"));
    expect(title).not.toContainElement(tag);
    expect(title.parentElement).toContainElement(tag);
  });

  it("keeps whole-step usage in Details, exactly once and outside the title line", async () => {
    const path = `/api/groups/${GROUP}/runs/${RUN_ID}/steps/${SUMMARISING.stepId}`;
    const material = tryReplies({
      run: READING.run,
      declarations: {},
      step: { ...SUMMARISING, cost: { callsAModel: false } },
      triesMade: [],
    });
    const sent = serving({ [`GET ${path}`]: [[material.summary[0], 200]] });
    const title = await drawn(SUMMARISING, READER, undefined, false, [
      "read_own_runs",
    ]);

    const head = title.parentElement!;

    expect(head.lastElementChild?.textContent).toBe("Question");
    expect(title.closest("li")!).not.toHaveTextContent(
      "None \u2014 no model is used.",
    );
    expect(requestsTo(sent)).toEqual([]);
    await userEvent.click(
      within(title.closest("li")!).getByRole("button", { name: "Details" }),
    );
    expect(
      await screen.findByText("None \u2014 no model is used."),
    ).toBeVisible();
    expect(head).not.toHaveTextContent("None \u2014 no model is used.");
    expect(
      title.closest("li")!.textContent!.split("None \u2014 no model is used."),
    ).toHaveLength(2);
    expect(screen.getByText("Usage", { selector: "dt" })).toBeVisible();
    expect(requestsTo(sent)).toEqual([`GET ${path}`]);
    expect(title).toHaveAccessibleName(isolatedInText("Summarise"));
  });

  it("says where the step is on a line of its own under the title, and nowhere on the title's line", async () => {
    const title = await drawn();

    const where = screen.getByText("Running").closest("p")!;

    expect(where).toBe(title.parentElement!.nextElementSibling);
    expect(title.parentElement).not.toHaveTextContent("Running");
    expect(title).toHaveAccessibleName(isolatedInText("Summarise"));
  });

  it("draws where the step is as its badge: an icon and words in the state's colour, the title in none of it", async () => {
    const title = await drawn();

    const where = title.parentElement!.nextElementSibling!;
    const badge = where.firstElementChild!;
    const icon = badge.firstElementChild!;

    expect(where.textContent).toBe("Running");
    expect(getComputedStyle(badge).color).toBe(resolved(RUNNING));
    expect(icon.getAttribute("data-testid")).toBe("AutorenewIcon");
    expect(icon.getAttribute("aria-hidden")).toBe("true");
    expect(getComputedStyle(icon).color).toBe(resolved(RUNNING));
    expect(getComputedStyle(title).color).not.toBe(resolved(RUNNING));
  });

  it("rules its message down the leading edge in the colour of the kind the step runs", async () => {
    const message = (await drawn()).closest("li")!;

    expect(drawnOf(message)).toEqual({
      ...drawnAsBlock(),
      leadingEdge: "3px solid",
      leadingEdgeColour: resolved(QUESTION_EDGE),
    });
  });

  it("leaves as much room inside its frame below the last block drawn as above its title, Details shut", async () => {
    const message = (await drawn()).closest("li")!;

    const drawnBlocks = [...message.children].filter(
      (child) => !child.hasAttribute("hidden"),
    );
    const details = message.lastElementChild!;
    const inside = (side: "Top" | "Bottom", block: Element) =>
      parseFloat(getComputedStyle(message)[`padding${side}`]) +
      parseFloat(getComputedStyle(block)[`margin${side}`]);

    expect(details.hasAttribute("hidden")).toBe(true);
    expect(details).not.toBeVisible();
    expect(inside("Bottom", drawnBlocks.at(-1)!)).toBe(
      inside("Top", drawnBlocks[0]!),
    );
    expect(inside("Top", drawnBlocks[0]!)).toBeGreaterThan(0);
    expect(
      parseFloat(getComputedStyle(drawnBlocks.at(-1)!).marginBottom) +
        parseFloat(getComputedStyle(details).marginTop),
    ).toBeGreaterThan(0);
  });

  it("frames the message of a step running a kind this build has no words for as any other message, ruled in no kind's colour", async () => {
    const message = (
      await drawn({
        ...SUMMARISING,
        runs: { versionName: null, kind: "unmapped_kind", name: "Find it" },
      })
    ).closest("li")!;

    expect(drawnOf(message)).toEqual(drawnAsBlock());
    expect(message).toHaveTextContent("This page can't show this yet.");
    expect(message).not.toHaveTextContent(/unmapped_kind/i);
  });
  it("shuts the account of where the step is in its one word, drawn as a control saying whether it is open and whose it is, and opens it there", async () => {
    const title = await drawn(WAITING);
    const message = title.closest("li")!;
    const control = within(message).getByRole("button", { name: "Waiting" });

    expect(control).toHaveAttribute("aria-expanded", "false");
    expect(control).toHaveAccessibleDescription(isolatedInText("Summarise"));
    expect(
      document.getElementById(control.getAttribute("aria-controls")!),
    ).toBeEmptyDOMElement();
    expect(message).not.toHaveTextContent(/Since /);
    expect(message).not.toHaveTextContent("Attempt 1 of 2");
    expect(message).toHaveTextContent("A kettle leaks.");

    await userEvent.click(control);

    const account = document.getElementById(
      control.getAttribute("aria-controls")!,
    )!;
    expect(control).toHaveAttribute("aria-expanded", "true");
    expect(account).toBeVisible();
    expect(account).toHaveTextContent(/Since /);
    expect(account).toHaveTextContent("Attempt 1 of 2");
    expect(account).not.toHaveTextContent("A kettle leaks.");

    await userEvent.click(control);

    expect(control).toHaveAttribute("aria-expanded", "false");
    expect(account).toBeEmptyDOMElement();
  });

  it("opens and shuts the account from the keyboard", async () => {
    const title = await drawn(WAITING);
    const control = within(title.closest("li")!).getByRole("button", {
      name: "Waiting",
    });

    control.focus();
    await userEvent.keyboard("{Enter}");

    expect(control).toHaveAttribute("aria-expanded", "true");

    await userEvent.keyboard(" ");

    expect(control).toHaveAttribute("aria-expanded", "false");
    expect(document.activeElement).toBe(control);
  });

  it("draws where a step is with no control where it has no account to give", async () => {
    const title = await drawn();

    const where = title.parentElement!.nextElementSibling as HTMLElement;

    expect(where).toHaveTextContent("Running");
    expect(within(where).queryByRole("button")).toBeNull();
    expect(within(title.closest("li")!).getAllByRole("button")).toHaveLength(2);
    expect(
      within(title.closest("li")!).getByRole("button", { name: "Details" }),
    ).toBeVisible();
    expect(
      within(title.closest("li")!).getByRole("button", { name: "Copy step" }),
    ).toBeVisible();
  });

  it("sets the control the state is drawn as in the words it stands in, in the state's colour and not a button's own", async () => {
    const title = await drawn(WAITING);
    const control = within(title.closest("li")!).getByRole("button", {
      name: "Waiting",
    });

    expect(control).toHaveClass(buttonClasses.text);
    expect(control).toHaveClass(buttonClasses.colorInherit);
    expect(control).not.toHaveClass(buttonClasses.outlined);
    expect(control).not.toHaveClass(buttonClasses.colorPrimary);
    expect(getComputedStyle(control.firstElementChild!).color).toBe(
      resolved(WAITING_COLOUR),
    );
  });

  it("draws a step's message on the leading side, whoever reads it", async () => {
    const message = (await drawn(WAITING, READER)).closest("li")!;

    expect(getComputedStyle(message).marginInlineStart).not.toBe("auto");
    expect(drawnOf(message)).toEqual({
      ...drawnAsBlock(),
      leadingEdge: "3px solid",
      leadingEdgeColour: resolved(QUESTION_EDGE),
    });
  });
  it("gives the keyboard to its message where a read again leaves no account to give, taking the control it was on, and draws the next account shut", async () => {
    const title = await drawn(WAITING);
    const message = title.closest("li")!;
    const control = within(message).getByRole("button", { name: "Waiting" });
    await userEvent.click(control);
    expect(document.activeElement).toBe(control);

    act(() => readAgainAs({ ...SUMMARISING, state: "done" }));

    expect(title.closest("li")).toBe(message);
    expect(within(message).queryByRole("button", { name: "Done" })).toBeNull();
    expect(document.activeElement).toBe(message);
    expect(document.activeElement).not.toBe(document.body);

    act(() => readAgainAs(WAITING));

    expect(
      within(message).getByRole("button", { name: "Waiting" }),
    ).toHaveAttribute("aria-expanded", "false");
    expect(message).not.toHaveTextContent(/Since /);
  });

  it("leaves the keyboard where it is where a read again takes the account away while the keyboard is elsewhere", async () => {
    const title = await drawn(WAITING);
    const message = title.closest("li")!;
    const details = within(message).getByRole("button", { name: "Details" });
    details.focus();

    act(() => readAgainAs({ ...SUMMARISING, state: "done" }));

    expect(document.activeElement).toBe(details);
    expect(document.activeElement).not.toBe(message);
  });

  it("takes the keyboard nowhere where a read again takes the account away while the keyboard is on no control at all", async () => {
    const title = await drawn(WAITING);
    const message = title.closest("li")!;
    expect(document.activeElement).toBe(document.body);

    act(() => readAgainAs({ ...SUMMARISING, state: "done" }));

    expect(document.activeElement).toBe(document.body);
    expect(document.activeElement).not.toBe(message);
  });

  it.each([
    [
      "why it is where it is",
      {
        ...SUMMARISING,
        state: "held_back",
        where: { kind: "held_back", reason: "turned_away", waitsOn: "starter" },
      },
      "The model service turned the request away.",
    ],
    [
      "since when",
      {
        ...SUMMARISING,
        where: { kind: "running", since: "2026-09-24T08:05:00Z" },
      },
      "Since ",
    ],
    [
      "each time it was turned away",
      {
        ...SUMMARISING,
        where: {
          kind: "running",
          turnedAway: [{ at: "2026-09-24T08:04:00Z", sentAgain: false }],
        },
      },
      "Turned away ",
    ],
    [
      "why an act is not the reader's",
      {
        ...SUMMARISING,
        stateActs: new Set(["review"]),
        stateRefusals: [],
      },
      "You don't have permission to review it.",
    ],
  ] as const)(
    "draws its state as the control that opens its account where all it has to say is %s, and says it there",
    async (_case, step, said) => {
      const title = await drawn(step as StepRow);
      const message = title.closest("li")!;
      const control = within(message).getAllByRole("button")[0]!;

      expect(control).toHaveAttribute("aria-expanded", "false");
      expect(control).not.toHaveTextContent("Details");
      expect(message).not.toHaveTextContent(said);

      await userEvent.click(control);

      expect(
        document.getElementById(control.getAttribute("aria-controls")!),
      ).toHaveTextContent(said);
    },
  );
  it("accounts for the selected try only when its state is opened", async () => {
    const title = await drawn({
      ...SUMMARISING,
      tries: { current: 1, declared: 2, beyond: false },
    });
    const message = title.closest("li")!;

    const control = within(message).getByRole("button", { name: "Running" });
    expect(message).not.toHaveTextContent("Attempt 1 of 2");
    await userEvent.click(control);
    expect(message).toHaveTextContent("Attempt 1 of 2");
  });
});
