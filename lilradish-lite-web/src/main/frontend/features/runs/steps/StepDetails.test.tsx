import Box from "@mui/material/Box";
import { ThemeProvider } from "@mui/material/styles";
import {
  act,
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import { userEvent } from "@testing-library/user-event";
import { MemoryRouter } from "react-router";
import { describe, expect, it, vi } from "vitest";

import type {
  RunSteps,
  StepRow,
} from "../../../api/groups/{groupId}/runs/{runId}/steps";
import type { StepAnswer } from "../../../api/groups/{groupId}/runs/{runId}/steps/{stepId}";
import type { Cost, Try } from "../../../api/stepTries";
import { StandingProvider } from "../../../app/standing/StandingContext";
import { BLOCK_SX } from "../../../lib/layout/parts";
import { theme } from "../../../lib/theme/theme";
import { isolatedInText } from "../../../lib/direction/isolated";
import { requestsTo, serving, type Reply } from "../../../testutil/answering";
import { deferred } from "../../../testutil/deferred";
import { answered, inGroup } from "../../../testutil/standingRead";
import { RunReadAccess } from "../RunReadAccess";
import { StepDetails } from "./StepDetails";
import { TryRead } from "./TryRead";

const GROUP = inGroup(
  "00000003-0000-4000-8000-000000000d61",
  "CLAIMS",
  "Imaginary claims",
  ["read_own_runs", "read_inference_content"],
);
const RUN_ID = "00000008-0000-4000-8000-000000000d61";

const ACROSS_THE_CARD = "calc(-1 * var(--card-side-padding))";

const THE_CARDS_SIDES = "var(--card-side-padding)";
const STEP: StepRow = {
  stepId: "00000009-0000-4000-8000-000000000d61",
  order: 1,
  name: "summarise",
  runs: { versionName: null, kind: "question", name: "Summarise" },
  state: "done",
  takesFrom: [{ input: "claim.summary", from: { kind: "constant" } }],
  stateActs: new Set(),
  stateRefusals: [],
  producedByKind: "model",
  producedByUserId: null,
  cost: {
    spend: {
      sent: "1200",
      cameBack: "300",
      spent: "1500",
      cameBackUnknown: false,
    },
  },
};
const READING: RunSteps = {
  run: {
    runId: RUN_ID,
    number: 7,
    versionId: "00000007-0000-4000-8000-000000000d61",
    state: "done",
    stateActs: null,
    progress: { done: 1, of: 1 },
  },
  declarations: new Map(),
  gaveBack: { declares: "00000007-0000-4000-8000-000000000d61" },
  steps: [STEP],
};

function tried(number: number, raw = false): Try {
  return {
    number,
    beyond: false,
    producedBy: { kind: "model", model: "small" },
    values: [
      {
        field: "summary",
        value: "A fictional kettle needs a seal.",
        now: "refused",
        confidence: 75,
        decision: {
          outcome: "refused",
          why: "Check the fictional receipt.",
          whyLines: "several",
        },
      },
    ],
    review: {
      asked: true,
      by: { kind: "model", model: "judge" },
      declinedPresent: false,
      unreadableResponsePresent: false,
      modelReplyPresent: raw,
    },
    ended: "refused_on_review",
    wentWrongPresent: false,
    returnedPresent: false,
    declinedPresent: false,
    unreadableResponsePresent: false,
    modelReplyPresent: raw,
    cost: {
      spend: { sent: "8", cameBack: "1", spent: "9", cameBackUnknown: false },
    },
  };
}

function Drawing({
  selected,
  tries,
  step = STEP,
}: {
  readonly selected: number | undefined;
  readonly tries: readonly Try[];
  readonly step?: StepRow;
}) {
  const answer: StepAnswer = {
    run: READING.run,
    declarations: READING.declarations,
    step,
    triesMade: tries,
  };
  return (
    <MemoryRouter>
      <ThemeProvider theme={theme}>
        <StandingProvider read={answered([], [GROUP], "reader-a")}>
          <RunReadAccess>
            <TryRead
              group={GROUP}
              runId={RUN_ID}
              stepId={step.stepId}
              shown={tries.find((each) => each.number === selected)}
              waiting={undefined}
            >
              <StepDetails
                group={GROUP}
                answer={answer}
                reading={READING}
                step={step}
                shownNumber={selected}
                declared={undefined}
                declaring={{
                  runId: RUN_ID,
                  runVersionId: READING.run.versionId,
                  declarations: READING.declarations,
                  steps: READING.steps,
                }}
              />
            </TryRead>
          </RunReadAccess>
        </StandingProvider>
      </ThemeProvider>
    </MemoryRouter>
  );
}

function path(number: number) {
  return `/api/groups/${GROUP.groupId}/runs/${RUN_ID}/steps/${STEP.stepId}/tries/${number}`;
}
function materials(entries: ReadonlyMap<number, Reply>) {
  return serving(
    Object.fromEntries(
      [...entries].map(([number, reply]) => [`GET ${path(number)}`, [reply]]),
    ),
  );
}
function usage() {
  return screen.getByText("Usage", { selector: "dt" }).nextElementSibling!;
}
async function settled() {
  await waitFor(() => expect(screen.queryByRole("progressbar")).toBeNull());
}

describe("StepDetails", () => {
  it("keeps the step's aggregate outside both selected try costs and reuses each material read", async () => {
    const tries = [
      tried(1),
      {
        ...tried(2),
        cost: {
          spend: {
            sent: "2",
            cameBack: "3",
            spent: "5",
            cameBackUnknown: false,
          },
        },
      },
    ];
    const sent = materials(
      new Map([
        [1, [JSON.stringify({ number: 1 }), 200]],
        [2, [JSON.stringify({ number: 2 }), 200]],
      ]),
    );
    const drawn = render(<Drawing selected={1} tries={tries} />);
    await settled();
    expect(usage().textContent).toBe(
      "1,500 in total: 1,200 sent, 300 received",
    );
    expect(usage()).not.toHaveTextContent("9");
    expect(screen.queryByText("Search fee", { selector: "dt" })).toBeNull();
    expect(requestsTo(sent)).toEqual([`GET ${path(1)}`]);
    drawn.rerender(<Drawing selected={2} tries={tries} />);
    await settled();
    expect(usage().textContent).toBe(
      "1,500 in total: 1,200 sent, 300 received",
    );
    expect(usage()).not.toHaveTextContent("5 in total");
    expect(requestsTo(sent)).toEqual([`GET ${path(1)}`, `GET ${path(2)}`]);
    drawn.rerender(<Drawing selected={2} tries={tries} />);
    expect(requestsTo(sent)).toEqual([`GET ${path(1)}`, `GET ${path(2)}`]);
  });

  it.each([
    [{}, "None \u2014 no model is used.", []],
    [
      {
        spend: { sent: "0", cameBack: "0", spent: "0", cameBackUnknown: false },
      },
      "0 in total: 0 sent, 0 received",
      [],
    ],
    [
      {
        spend: {
          sent: "2",
          cameBack: "1",
          spent: "3",
          cameBackUnknown: false,
          measuredHere: "wholly",
        },
      },
      "3 in total: 2 sent, 1 received",
      [
        "All of this is estimated by this system: no model reported its own count.",
      ],
    ],
    [
      {
        spend: { sent: "2", cameBack: "0", spent: "2", cameBackUnknown: true },
      },
      "2 in total: 2 sent, 0 received",
      [
        "The amount received is not known for every call, and is counted here as zero.",
      ],
    ],
    [
      {
        spend: {
          sent: "9007199254740993",
          cameBack: "1",
          spent: "9007199254740994",
          cameBackUnknown: true,
          measuredHere: "partly",
        },
      },
      "9,007,199,254,740,994 in total: 9,007,199,254,740,993 sent, 1 received",
      [
        "Part of this is estimated by this system, where a model did not report its own count.",
        "The amount received is not known for every call, and is counted here as zero.",
      ],
    ],
  ] satisfies readonly (readonly [Cost, string, readonly string[]])[])(
    "says the actual aggregate %j even when no try is selected, in one sentence at body size, each note on a line of its own",
    async (cost: Cost, said, notes) => {
      const sent = serving({});
      render(
        <Drawing selected={undefined} tries={[]} step={{ ...STEP, cost }} />,
      );
      await act(async () => {});
      expect(requestsTo(sent)).toEqual([]);
      expect(screen.queryByText("Searches", { selector: "dt" })).toBeNull();
      expect(screen.queryByText("Search fee", { selector: "dt" })).toBeNull();
      expect(usage().textContent).toBe(`${said}${notes.join("")}`);
      expect(
        [...usage().querySelectorAll("p")].map((note) => note.textContent),
      ).toEqual(notes);
      const drawn = usage().firstElementChild!;
      expect(getComputedStyle(drawn).fontVariantNumeric).toBe("tabular-nums");
      expect(getComputedStyle(drawn).fontSize).toBe(
        getComputedStyle(usage()).fontSize,
      );
      expect(usage().querySelector("span")).toBeNull();
    },
  );

  it("orders native summary, search evidence, each review decision and input sources before independent raw replies", async () => {
    const raw = '  {"summary":"**Fictional receipt**"}\n';
    const summary = {
      ...tried(1, true),
      search: {
        counted: {
          uses: "1",
          usesCounted: "said" as const,
          fee: { currency: "USD", perUse: "0", amount: "0" },
        },
      },
    };
    const sent = materials(
      new Map([
        [
          1,
          [
            JSON.stringify({
              number: 1,
              modelReply: { text: raw, altered: false, truncated: false },
              review: {
                modelReply: {
                  text: "Fictional review raw",
                  altered: true,
                  truncated: true,
                },
              },
              search: {
                searches: [
                  {
                    searchedFor: "Fictional receipt",
                    found:
                      "**Receipt** [address](https://example.com/fictional)",
                  },
                ],
                ...summary.search.counted,
              },
            }),
            200,
          ],
        ],
      ]),
    );
    const drawn = render(<Drawing selected={1} tries={[summary]} />);
    await screen.findByRole("heading", { name: "What it found" });
    const parts = [...drawn.container.querySelectorAll("section")];
    expect(
      parts.map((part) => part.querySelector("h3")?.textContent ?? null),
    ).toEqual([
      null,
      "What it found",
      "Review",
      "Inputs",
      "The model's reply, exactly as it came",
    ]);
    expect(parts[0]).toContainElement(
      screen.getByText("Requested by", { selector: "dt" }),
    );
    const replyHeadings = within(parts[4]!).getAllByRole("heading", {
      level: 3,
      name: "The model's reply, exactly as it came",
    });
    expect(replyHeadings).toHaveLength(2);
    for (const heading of replyHeadings) {
      expect([...heading.children]).toEqual([
        within(heading).getByRole("button", {
          name: "The model's reply, exactly as it came",
          expanded: false,
        }),
      ]);
      const drawnAs = getComputedStyle(heading);
      expect(drawnAs.marginTop).toBe("0px");
      expect(drawnAs.marginBottom).toBe("0px");
      expect([
        drawnAs.fontSize,
        drawnAs.lineHeight,
        drawnAs.fontWeight,
      ]).toEqual([
        getComputedStyle(heading.parentElement!).fontSize,
        getComputedStyle(heading.parentElement!).lineHeight,
        getComputedStyle(heading.parentElement!).fontWeight,
      ]);
    }
    expect(within(parts[4]!).queryByRole("heading", { level: 4 })).toBeNull();
    expect(screen.queryAllByRole("region")).toEqual([]);
    expect(screen.queryByRole("heading", { name: "Details" })).toBeNull();
    expect(
      screen.getByText("Requested by", { selector: "dt" }).nextElementSibling,
    ).toHaveTextContent("The run itself");
    const fee = screen.getByText("Search fee", { selector: "dt" });
    expect(fee.nextElementSibling!.textContent).toBe(
      "$0.00$0.00 per search at the time",
    );
    expect(
      within(fee.nextElementSibling as HTMLElement).getByText(
        "$0.00 per search at the time",
      ).tagName,
    ).toBe("P");
    const summaryFacts = parts[0]!.querySelectorAll("dl");
    expect(summaryFacts).toHaveLength(1);
    const terms = [...summaryFacts[0]!.querySelectorAll("dt")];
    expect(terms.map((term) => term.textContent)).toEqual([
      "Requested by",
      "Produced by",
      "Usage",
      "Searches",
      "Search fee",
    ]);
    expect(
      terms
        .filter((term) => term.hasAttribute("data-across"))
        .map((term) => term.textContent),
    ).toEqual(["Search fee"]);
    expect(
      screen.queryByText("Price per search at the time", { selector: "dt" }),
    ).toBeNull();
    const decisions = screen.getByRole("table", { name: "Review decision" });
    const outcome = within(
      screen.getByRole("heading", { name: "Review" }).closest("section")!,
    ).getByText("Outcome", { selector: "dt" }).nextElementSibling!;
    expect(outcome).toBeVisible();
    expect(
      outcome.compareDocumentPosition(decisions) &
        Node.DOCUMENT_POSITION_FOLLOWING,
    ).toBe(Node.DOCUMENT_POSITION_FOLLOWING);
    expect(
      within(decisions)
        .getAllByRole("columnheader")
        .map((head) => head.textContent),
    ).toEqual(["Value", "Decision"]);
    expect(
      within(decisions).getByRole("rowheader", {
        name: isolatedInText("summary"),
      }),
    ).toBeVisible();
    expect(within(decisions).getByRole("cell")).toHaveTextContent(
      "Check the fictional receipt.",
    );
    const inputs = screen.getByRole("table", { name: "Inputs" });
    expect(
      within(inputs)
        .getAllByRole("columnheader")
        .map((head) => head.textContent),
    ).toEqual(["Input", "From"]);
    expect(
      within(inputs).getByRole("rowheader", {
        name: `${isolatedInText("claim")} \u203a ${isolatedInText("summary")}`,
      }),
    ).toBeVisible();
    expect(within(inputs).getByRole("cell")).toHaveTextContent(
      "a fixed value set in the workflow",
    );
    expect(screen.queryByRole("link", { name: "address" })).toBeNull();
    const controls = screen.getAllByRole("button", {
      name: "The model's reply, exactly as it came",
    });
    expect(controls).toHaveLength(2);
    expect(
      controls.every(
        (control) =>
          control.querySelector("svg")?.getAttribute("aria-hidden") === "true",
      ),
    ).toBe(true);
    expect(
      within(screen.getByRole("group", { name: "Produced by" })).getByRole(
        "button",
        { name: "The model's reply, exactly as it came" },
      ),
    ).toBe(controls[0]);
    expect(
      within(screen.getByRole("group", { name: "Reviewed by" })).getByRole(
        "button",
        { name: "The model's reply, exactly as it came" },
      ),
    ).toBe(controls[1]);
    expect(
      controls.every(
        (control) => control.getAttribute("aria-expanded") === "false",
      ),
    ).toBe(true);
    expect(screen.queryByText(raw, { exact: true })).toBeNull();
    fireEvent.click(controls[0]!);
    expect(controls[0]).toHaveAttribute("aria-expanded", "true");
    expect(controls[1]).toHaveAttribute("aria-expanded", "false");
    expect(
      document
        .getElementById(controls[0]!.getAttribute("aria-controls")!)!
        .querySelector("bdi")!.textContent,
    ).toBe(raw);
    fireEvent.click(controls[1]!);
    expect(screen.getByText("Fictional review raw")).toBeVisible();
    expect(requestsTo(sent)).toEqual([`GET ${path(1)}`]);
  });

  it.each([
    [1, ["Review", "Inputs"]],
    [undefined, ["Inputs"]],
  ])(
    "rules each part after the summary off across the message card, with try %s shown, each part's title in the side panel's title type",
    async (selected, titles) => {
      const sent = materials(
        new Map([[1, [JSON.stringify({ number: 1 }), 200]]]),
      );
      const drawn = render(
        <Box sx={BLOCK_SX}>
          <Drawing
            selected={selected}
            tries={selected === undefined ? [] : [tried(1)]}
          />
        </Box>,
      );
      await settled();

      const [summary, ...rest] = [
        ...drawn.container.querySelectorAll("section"),
      ];
      expect(rest.map((part) => part.querySelector("h3")?.textContent)).toEqual(
        titles,
      );
      expect(summary!.querySelector("h1, h2, h3, h4, h5, h6")).toBeNull();
      for (const part of [summary!, ...rest]) {
        expect(getComputedStyle(part.lastElementChild!).marginBottom).toBe(
          "0px",
        );
      }
      // jsdom lets a later shorthand margin beat an earlier, more specific longhand,
      // so the list's own reset is pinned by where it stands, not by its computed margin.
      const list = summary!.querySelector("dl")!;
      if (selected === undefined) {
        expect(summary!.lastElementChild!.tagName).toBe("P");
        expect(getComputedStyle(list).marginBottom).toBe("16px");
      } else {
        expect(summary!.lastElementChild!.lastElementChild).toBe(list);
      }
      expect(getComputedStyle(summary!).borderTopStyle).toBe("none");
      expect(getComputedStyle(summary!).marginLeft).not.toBe(ACROSS_THE_CARD);
      for (const part of rest) {
        const style = getComputedStyle(part);
        expect(style.borderTopStyle).toBe("solid");
        expect(style.borderTopWidth).toBe("1px");
        expect(style.borderTopColor).toBe("rgb(228, 235, 238)");
        expect([style.marginLeft, style.marginRight]).toEqual([
          ACROSS_THE_CARD,
          ACROSS_THE_CARD,
        ]);
        expect([style.paddingLeft, style.paddingRight]).toEqual([
          THE_CARDS_SIDES,
          THE_CARDS_SIDES,
        ]);
        expect(style.getPropertyValue("--card-side-padding")).toBe("20px");
        expect([style.marginTop, style.paddingTop]).toEqual(["18px", "18px"]);
        expect(getComputedStyle(part.firstElementChild!).marginTop).toBe("0px");
        const title = getComputedStyle(part.querySelector("h3")!);
        expect(title.fontWeight).toBe("700");
        expect([title.fontSize, title.lineHeight]).toEqual(["14px", "20px"]);
        expect(title.color).toBe("rgb(62, 74, 79)");
      }
      expect(screen.queryAllByRole("region")).toEqual([]);
      expect(requestsTo(sent)).toEqual(
        selected === undefined ? [] : [`GET ${path(1)}`],
      );
    },
  );

  it("copies the complete permitted raw text while closed through the HTTP copy event and removes its listener", async () => {
    const raw = "  Fictional **raw**\n<a href='/outside'>Keep literal</a>\n";
    const sent = materials(
      new Map([
        [
          1,
          [
            JSON.stringify({
              number: 1,
              modelReply: { text: raw, altered: false, truncated: false },
            }),
            200,
          ],
        ],
      ]),
    );
    const priorClipboard = Object.getOwnPropertyDescriptor(
      navigator,
      "clipboard",
    );
    const priorCommand = Object.getOwnPropertyDescriptor(
      document,
      "execCommand",
    );
    const setData = vi.fn();
    const command = vi.fn(() => {
      const event = new Event("copy", { cancelable: true });
      Object.defineProperty(event, "clipboardData", { value: { setData } });
      document.dispatchEvent(event);
      return event.defaultPrevented;
    });
    Object.defineProperty(navigator, "clipboard", {
      configurable: true,
      value: undefined,
    });
    Object.defineProperty(document, "execCommand", {
      configurable: true,
      value: command,
    });
    try {
      render(<Drawing selected={1} tries={[tried(1, true)]} />);
      await settled();
      const disclosure = screen.getByRole("button", {
        name: "The model's reply, exactly as it came",
      });
      const rawBox = disclosure.closest("h3")!.parentElement!.parentElement!;
      const copy = within(rawBox).getByRole("button", { name: "Copy" });
      await userEvent.click(copy);
      expect(disclosure).toHaveAttribute("aria-expanded", "false");
      expect(copy).toHaveFocus();
      expect(copy).toHaveAccessibleName("Copied");
      expect(command).toHaveBeenCalledExactlyOnceWith("copy");
      expect(setData).toHaveBeenCalledExactlyOnceWith("text/plain", raw);
      document.dispatchEvent(new Event("copy", { cancelable: true }));
      expect(setData).toHaveBeenCalledTimes(1);
      expect(requestsTo(sent)).toEqual([`GET ${path(1)}`]);
      expect(rawBox.querySelector('[role="status"]')).toBeEmptyDOMElement();
    } finally {
      if (priorClipboard === undefined)
        Reflect.deleteProperty(navigator, "clipboard");
      else Object.defineProperty(navigator, "clipboard", priorClipboard);
      if (priorCommand === undefined)
        Reflect.deleteProperty(document, "execCommand");
      else Object.defineProperty(document, "execCommand", priorCommand);
    }
  });

  it("does not carry an opened raw box or a late copied result to another selected try", async () => {
    const raw1 = "Fictional first raw";
    const raw2 = "Fictional second raw";
    const sent = materials(
      new Map([
        [
          1,
          [
            JSON.stringify({
              number: 1,
              modelReply: { text: raw1, altered: false, truncated: false },
            }),
            200,
          ],
        ],
        [
          2,
          [
            JSON.stringify({
              number: 2,
              modelReply: { text: raw2, altered: false, truncated: false },
            }),
            200,
          ],
        ],
      ]),
    );
    const copyDone = deferred<void>();
    const write = vi.fn(() => copyDone.promise);
    const priorClipboard = Object.getOwnPropertyDescriptor(
      navigator,
      "clipboard",
    );
    Object.defineProperty(navigator, "clipboard", {
      configurable: true,
      value: { writeText: write },
    });
    try {
      const drawn = render(
        <Drawing selected={1} tries={[tried(1, true), tried(2, true)]} />,
      );
      await settled();
      fireEvent.click(
        screen.getByRole("button", {
          name: "The model's reply, exactly as it came",
        }),
      );
      expect(screen.getByText(raw1)).toBeVisible();
      await userEvent.click(screen.getByRole("button", { name: "Copy" }));
      drawn.rerender(
        <Drawing selected={2} tries={[tried(1, true), tried(2, true)]} />,
      );
      await settled();
      expect(screen.queryByText(raw1)).toBeNull();
      const next = screen.getByRole("button", {
        name: "The model's reply, exactly as it came",
      });
      expect(next).toHaveAttribute("aria-expanded", "false");
      await act(async () => copyDone.settle());
      expect(screen.queryByRole("button", { name: "Copied" })).toBeNull();
      expect(screen.getByRole("button", { name: "Copy" })).toBeVisible();
      expect(write).toHaveBeenCalledExactlyOnceWith(raw1);
      expect(requestsTo(sent)).toEqual([`GET ${path(1)}`, `GET ${path(2)}`]);
    } finally {
      if (priorClipboard === undefined)
        Reflect.deleteProperty(navigator, "clipboard");
      else Object.defineProperty(navigator, "clipboard", priorClipboard);
    }
  });

  it.each([
    ["Produced by", "false"],
    ["Reviewed by", "false"],
    ["Produced by", "no event"],
    ["Reviewed by", "no event"],
    ["Produced by", "command throws"],
    ["Reviewed by", "command throws"],
    ["Produced by", "write throws"],
    ["Reviewed by", "write throws"],
  ])(
    "keeps %s raw copy failure local when %s, clearing only its own error on retry",
    async (owner, failure) => {
      const producerRaw = "  Fictional producer **raw**\n";
      const reviewerRaw = "  Fictional reviewer <b>raw</b>\n";
      const sent = materials(
        new Map([
          [
            1,
            [
              JSON.stringify({
                number: 1,
                modelReply: {
                  text: producerRaw,
                  altered: false,
                  truncated: false,
                },
                review: {
                  modelReply: {
                    text: reviewerRaw,
                    altered: false,
                    truncated: false,
                  },
                },
              }),
              200,
            ],
          ],
        ]),
      );
      const priorClipboard = Object.getOwnPropertyDescriptor(
        navigator,
        "clipboard",
      );
      const priorCommand = Object.getOwnPropertyDescriptor(
        document,
        "execCommand",
      );
      const setData = vi.fn(() => {
        if (failure === "write throws") throw new Error("Cannot write");
      });
      const command = vi.fn(() => {
        if (failure === "command throws") throw new Error("Cannot copy");
        if (failure === "false") return false;
        if (failure === "no event") return true;
        const event = new Event("copy", { cancelable: true });
        Object.defineProperty(event, "clipboardData", { value: { setData } });
        document.dispatchEvent(event);
        return true;
      });
      Object.defineProperty(navigator, "clipboard", {
        configurable: true,
        value: undefined,
      });
      Object.defineProperty(document, "execCommand", {
        configurable: true,
        value: command,
      });
      try {
        render(<Drawing selected={1} tries={[tried(1, true)]} />);
        const group = await screen.findByRole("group", { name: owner });
        const sibling = await screen.findByRole("group", {
          name: owner === "Produced by" ? "Reviewed by" : "Produced by",
        });
        const copy = within(group).getByRole("button", { name: "Copy" });
        const disclosure = within(group).getByRole("button", {
          name: "The model's reply, exactly as it came",
        });
        const titleLine = disclosure.closest("h3")!.parentElement!;
        const rawBox = titleLine.parentElement!;
        const status = within(group).getByRole("status");
        const siblingStatus = within(sibling).getByRole("status");
        const body = document.getElementById(
          disclosure.getAttribute("aria-controls")!,
        )!;
        await userEvent.click(copy);
        expect(copy).toHaveFocus();
        expect(copy).toHaveAccessibleName("Copy");
        expect(status).toHaveTextContent(
          "Couldn't copy here. Select the text and copy it instead.",
        );
        expect(status).toBeVisible();
        expect(getComputedStyle(status).marginTop).toBe("4px");
        expect(status.parentElement).toBe(rawBox);
        expect(titleLine.contains(copy)).toBe(true);
        expect(disclosure.closest("h3")).not.toContainElement(copy);
        expect(titleLine.nextElementSibling).toBe(status);
        expect(status.nextElementSibling).toBe(body);
        expect(body).toHaveAttribute("hidden");
        expect(body).not.toContainElement(status);
        expect(disclosure).toHaveAttribute("aria-expanded", "false");
        expect(siblingStatus).toBeEmptyDOMElement();
        expect(screen.queryByRole("alert")).toBeNull();
        expect(screen.queryByRole("button", { name: "Copied" })).toBeNull();
        expect(command).toHaveBeenCalledExactlyOnceWith("copy");
        setData.mockClear();
        const outsideCopy = new Event("copy", { cancelable: true });
        Object.defineProperty(outsideCopy, "clipboardData", {
          value: { setData },
        });
        document.dispatchEvent(outsideCopy);
        expect(outsideCopy.defaultPrevented).toBe(false);
        expect(setData).not.toHaveBeenCalled();

        await userEvent.click(
          within(sibling).getByRole("button", { name: "Copy" }),
        );
        expect(siblingStatus).toHaveTextContent("Couldn't copy here.");
        expect(status).toHaveTextContent("Couldn't copy here.");
        const retry = deferred<void>();
        const write = vi.fn(() => retry.promise);
        Object.defineProperty(navigator, "clipboard", {
          configurable: true,
          value: { writeText: write },
        });
        await userEvent.click(copy);
        expect(status).toBeEmptyDOMElement();
        expect(siblingStatus).toHaveTextContent("Couldn't copy here.");
        expect(copy).toHaveFocus();
        expect(screen.queryByRole("button", { name: "Copied" })).toBeNull();
        await act(async () => retry.settle());
        expect(copy).toHaveAccessibleName("Copied");
        expect(copy).toHaveFocus();
        expect(status).toBeEmptyDOMElement();
        expect(siblingStatus).toHaveTextContent("Couldn't copy here.");
        expect(write).toHaveBeenCalledExactlyOnceWith(
          owner === "Produced by" ? producerRaw : reviewerRaw,
        );
        expect(command).toHaveBeenCalledTimes(2);
        expect(requestsTo(sent)).toEqual([`GET ${path(1)}`]);
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
    ["Produced by", "switch"],
    ["Reviewed by", "switch"],
    ["Produced by", "unmount"],
    ["Reviewed by", "unmount"],
  ])(
    "discards a late %s raw copy rejection after %s",
    async (owner, leaving) => {
      const sent = materials(
        new Map(
          [1, 2].map((number) => [
            number,
            [
              JSON.stringify({
                number,
                modelReply: {
                  text: `Fictional producer raw ${number}`,
                  altered: false,
                  truncated: false,
                },
                review: {
                  modelReply: {
                    text: `Fictional reviewer raw ${number}`,
                    altered: false,
                    truncated: false,
                  },
                },
              }),
              200,
            ] as Reply,
          ]),
        ),
      );
      const copyDone = deferred<void>();
      const write = vi.fn(() => copyDone.promise);
      const command = vi.fn(() => false);
      const priorClipboard = Object.getOwnPropertyDescriptor(
        navigator,
        "clipboard",
      );
      const priorCommand = Object.getOwnPropertyDescriptor(
        document,
        "execCommand",
      );
      Object.defineProperty(navigator, "clipboard", {
        configurable: true,
        value: { writeText: write },
      });
      Object.defineProperty(document, "execCommand", {
        configurable: true,
        value: command,
      });
      try {
        const tries = [tried(1, true), tried(2, true)];
        const drawn = render(<Drawing selected={1} tries={tries} />);
        await screen.findByRole("group", { name: owner });
        await userEvent.click(
          within(screen.getByRole("group", { name: owner })).getByRole(
            "button",
            { name: "Copy" },
          ),
        );
        if (leaving === "switch") {
          drawn.rerender(<Drawing selected={2} tries={tries} />);
          await screen.findByRole("group", { name: owner });
        } else {
          drawn.unmount();
        }
        await act(async () => copyDone.refuse(new Error("Clipboard refused")));
        expect(command).not.toHaveBeenCalled();
        expect(write).toHaveBeenCalledExactlyOnceWith(
          `Fictional ${owner === "Produced by" ? "producer" : "reviewer"} raw 1`,
        );
        expect(screen.queryByRole("button", { name: "Copied" })).toBeNull();
        expect(
          screen.queryByText(
            "Couldn't copy here. Select the text and copy it instead.",
          ),
        ).toBeNull();
        expect(screen.queryByRole("alert")).toBeNull();
        const setData = vi.fn();
        const outsideCopy = new Event("copy", { cancelable: true });
        Object.defineProperty(outsideCopy, "clipboardData", {
          value: { setData },
        });
        document.dispatchEvent(outsideCopy);
        expect(setData).not.toHaveBeenCalled();
        expect(outsideCopy.defaultPrevented).toBe(false);
        if (leaving === "switch") {
          const current = screen.getByRole("group", { name: owner });
          expect(
            within(current).getByRole("button", { name: "Copy" }),
          ).toBeVisible();
          expect(within(current).getByRole("status")).toBeEmptyDOMElement();
          expect(
            within(current).getByRole("button", {
              name: "The model's reply, exactly as it came",
            }),
          ).toHaveAttribute("aria-expanded", "false");
          expect(requestsTo(sent)).toEqual([
            `GET ${path(1)}`,
            `GET ${path(2)}`,
          ]);
        } else {
          expect(drawn.container).toBeEmptyDOMElement();
          expect(requestsTo(sent)).toEqual([`GET ${path(1)}`]);
        }
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
});
