import { ThemeProvider } from "@mui/material/styles";
import {
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

import type { Declared } from "../../../api/groups/{groupId}/runs/{runId}/steps";

import type {
  Try,
  TryDetails,
  TriedSearch,
  Declined,
} from "../../../api/stepTries";
import { StandingProvider } from "../../../app/standing/StandingContext";
import { answered, inGroup } from "../../../testutil/standingRead";
import { serving } from "../../../testutil/answering";
import { RunReadAccess } from "../RunReadAccess";
import { TryRead } from "./TryRead";
import { MemoryRouter } from "react-router";
import { isolatedInText } from "../../../lib/direction/isolated";
import { theme } from "../../../lib/theme/theme";
import { CurrentFileRunContext } from "../fill/CurrentFileRun";
import { TryPanel } from "./TryPanel";

const RIGHT_TO_LEFT_OVERRIDE = String.fromCodePoint(0x202e);

const ZERO_WIDTH_SPACE = String.fromCodePoint(0x200b);

const ARABIC_LETTER_MARK = String.fromCodePoint(0x061c);

const SMALL = { kind: "model", model: "small" } as const;

const REPLACED_NOTE =
  "Some of it couldn't be stored exactly as received, so it is replaced here.";

const STOPPED_NOTE =
  "This could not be read, so the rest of it was not downloaded. Only its start is kept here.";

const CUT_SHORT_NOTE =
  "It is too long to store in full, so it is cut short here.";

type PanelFixture = Omit<
  Try,
  | "review"
  | "search"
  | "wentWrongPresent"
  | "returnedPresent"
  | "declinedPresent"
  | "unreadableResponsePresent"
  | "modelReplyPresent"
> &
  Omit<TryDetails, "number" | "review" | "search"> & {
    readonly review: Omit<
      Try["review"],
      "declinedPresent" | "unreadableResponsePresent" | "modelReplyPresent"
    > &
      NonNullable<TryDetails["review"]>;
    readonly search?: TriedSearch;
  };

const RETURNED_TRY: PanelFixture = {
  number: 1,
  beyond: false,
  producedBy: { kind: "code" },
  values: [],
  review: { asked: false },
  ended: "errored",
  erroredFor: { reason: "not_its_kind", field: "reference" },
  returned: `{"reference": "${RIGHT_TO_LEFT_OVERRIDE}7"}`,
  cost: {},
};

const REFUSED_TRY: PanelFixture = {
  number: 1,
  beyond: false,
  producedBy: SMALL,
  values: [
    {
      field: "summary",
      value: "A fire.",
      now: "refused",
      decision: {
        outcome: "refused",
        whyLines: "several",
        why: `Too${ZERO_WIDTH_SPACE}short.`,
      },
    },
  ],
  review: { asked: true, by: SMALL },
  ended: "refused_on_review",
  cost: {},
};

function wentWrongTry(cut: boolean): PanelFixture {
  return {
    number: 1,
    beyond: false,
    producedBy: SMALL,
    values: [],
    review: { asked: false },
    ended: "errored",
    wentWrong: { detail: `Bad ${ARABIC_LETTER_MARK}key`, cut },
    cost: {},
  };
}

function declinedWire(declined: Declined | undefined) {
  if (declined === undefined || "withheld" in declined) return declined;
  const words = declined.words;
  return {
    ...(words === undefined
      ? {}
      : {
          words: {
            text: words.text,
            altered: words.altered,
            truncated: words.truncated,
          },
          wordsLines: words.lines,
        }),
    ...(declined.content === undefined ? {} : { content: declined.content }),
  };
}

async function drawing(
  aTry: PanelFixture,
  field?: string,
  withRun = false,
  declared?: Declared,
) {
  const {
    returned,
    declined,
    unreadableResponse,
    modelReply,
    search,
    review,
    wentWrong,
    ...held
  } = aTry;
  const summary: Try = {
    ...held,
    ...(held.producedBy.kind === "code" || wentWrong === undefined
      ? {}
      : { wentWrong }),
    wentWrongPresent: wentWrong !== undefined,
    returnedPresent: returned !== undefined,
    declinedPresent: declined !== undefined,
    unreadableResponsePresent: unreadableResponse !== undefined,
    modelReplyPresent: modelReply !== undefined,
    review: {
      asked: review.asked,
      by: review.by,
      wentWrong: review.wentWrong,
      lost: review.lost,
      didNotFit: review.didNotFit,
      declinedPresent: review.declined !== undefined,
      unreadableResponsePresent: review.unreadableResponse !== undefined,
      modelReplyPresent: review.modelReply !== undefined,
    },
    ...(search === undefined ? {} : { search: { counted: search.counted } }),
  };
  const material = {
    number: aTry.number,
    ...(held.producedBy.kind === "code" && wentWrong !== undefined
      ? { wentWrong }
      : {}),
    ...(returned === undefined ? {} : { returned }),
    ...(declined === undefined ? {} : { declined: declinedWire(declined) }),
    ...(unreadableResponse === undefined ? {} : { unreadableResponse }),
    ...(modelReply === undefined ? {} : { modelReply }),
    ...(review.declined === undefined &&
    review.unreadableResponse === undefined &&
    review.modelReply === undefined
      ? {}
      : {
          review: {
            ...(review.declined === undefined
              ? {}
              : { declined: declinedWire(review.declined) }),
            ...(review.unreadableResponse === undefined
              ? {}
              : { unreadableResponse: review.unreadableResponse }),
            ...(review.modelReply === undefined
              ? {}
              : { modelReply: review.modelReply }),
          },
        }),
    ...(search === undefined
      ? {}
      : { search: { ...search.said, ...search.counted } }),
  };
  const group = inGroup(
    "00000003-0000-4000-8000-000000000c51",
    "CLAIMS",
    "Imaginary claims",
    ["read_own_runs", "read_inference_content"],
  );
  const runId = "00000008-0000-4000-8000-000000000c51";
  const stepId = "00000009-0000-4000-8000-000000000c51";
  const sent = serving({
    [`GET /api/groups/${group.groupId}/runs/${runId}/steps/${stepId}/tries/${aTry.number}`]:
      [[JSON.stringify(material), 200]],
  });
  const drawn = render(
    <MemoryRouter>
      <ThemeProvider theme={theme}>
        <StandingProvider read={answered([], [group], "reader-a")}>
          <RunReadAccess>
            <TryRead
              group={group}
              runId={runId}
              stepId={stepId}
              shown={summary}
              waiting={undefined}
            >
              <CurrentFileRunContext.Provider
                value={withRun ? { groupId: "group-a", runId: "run-a" } : null}
              >
                <TryPanel
                  group={group}
                  aTry={summary}
                  field={field}
                  declared={declared}
                  declaring={{
                    runId: "r1",
                    declarations: new Map(),
                    runVersionId: "v1",
                    steps: undefined,
                  }}
                />
              </CurrentFileRunContext.Provider>
            </TryRead>
          </RunReadAccess>
        </StandingProvider>
      </ThemeProvider>
    </MemoryRouter>,
  ).container;
  await waitFor(() => expect(sent).toHaveBeenCalledTimes(1));
  await waitFor(() =>
    expect(drawn.querySelector('[role="progressbar"]')).toBeNull(),
  );
  expect(drawn).not.toHaveTextContent("Try loading again");
  return drawn;
}

function under(label: string): string | null | undefined {
  return screen.getByText(label).nextElementSibling?.textContent;
}

function openRaw(label: string) {
  return screen.getAllByRole("button", { name: label }).map((button) => {
    expect(button).toHaveAttribute("aria-expanded", "false");
    const contents = document.getElementById(
      button.getAttribute("aria-controls")!,
    )!;
    expect(contents).not.toBeVisible();
    fireEvent.click(button);
    expect(button).toHaveAttribute("aria-expanded", "true");
    expect(contents).toBeVisible();
    return contents;
  });
}

function rawUnder(label: string) {
  const button = screen.getByRole("button", { name: label });
  return document.getElementById(button.getAttribute("aria-controls")!)!
    .textContent;
}

describe("TryPanel", () => {
  it("draws a try's parts in the order its Details draws them, each after the summary under its own heading a level below the panel's and a rule above it reading the card's inset, none of its own", async () => {
    const drawn = await drawing({
      ...REFUSED_TRY,
      modelReply: { text: "Imaginary reply", altered: false, truncated: false },
      search: {
        said: {
          searches: [
            {
              searchedFor: "Imaginary kettle seal",
              found: "An invented seal guide.",
            },
          ],
        },
        counted: {
          uses: "1",
          usesCounted: "said",
          noFee: { reason: "no_price" },
        },
      },
    });

    const parts = [...drawn.querySelectorAll("section")].filter(
      (section) => section.parentElement?.closest("section") === null,
    );
    expect(
      parts.map((part) => part.querySelector("h4")?.textContent ?? null),
    ).toEqual([
      null,
      "What it found",
      "Review",
      "The model's reply, exactly as it came",
    ]);
    expect(parts[0]).toContainElement(
      screen.getByText("Requested by", { selector: "dt" }),
    );
    const replyHeading = within(parts[3]!).getByRole("heading", {
      level: 4,
      name: "The model's reply, exactly as it came",
    });
    expect([...replyHeading.children]).toEqual([
      screen.getByRole("button", {
        name: "The model's reply, exactly as it came",
        expanded: false,
      }),
    ]);
    expect(getComputedStyle(replyHeading).marginTop).toBe("0px");
    expect(getComputedStyle(replyHeading).fontWeight).toBe(
      getComputedStyle(replyHeading.parentElement!).fontWeight,
    );
    expect(replyHeading).not.toContainElement(
      within(parts[3]!).getByRole("button", { name: "Copy" }),
    );
    const titles = screen
      .getAllByRole("heading", { level: 4 })
      .filter((heading) => heading !== replyHeading);
    expect(titles.map((heading) => heading.textContent)).toEqual([
      "What it found",
      "Review",
    ]);
    expect(screen.queryByRole("heading", { level: 3 })).toBeNull();
    expect(screen.queryByRole("heading", { name: "Inputs" })).toBeNull();
    expect(screen.queryByRole("heading", { name: "Details" })).toBeNull();
    expect(screen.queryAllByRole("region")).toEqual([]);
    expect(getComputedStyle(parts[0]!).borderTopStyle).toBe("none");
    for (const part of parts) {
      expect(getComputedStyle(part.lastElementChild!).marginBottom).toBe("0px");
    }
    // jsdom lets a later shorthand margin beat an earlier, more specific longhand,
    // so the list's own reset is pinned by where it stands, not by its computed margin.
    expect(parts[0]!.lastElementChild!.lastElementChild).toBe(
      parts[0]!.querySelector("dl"),
    );
    for (const part of parts.slice(1)) {
      const style = getComputedStyle(part);
      expect(style.borderTopStyle).toBe("solid");
      expect(style.borderTopWidth).toBe("1px");
      expect([style.marginLeft, style.marginRight]).toEqual([
        "calc(-1 * var(--card-side-padding))",
        "calc(-1 * var(--card-side-padding))",
      ]);
      expect([style.paddingLeft, style.paddingRight]).toEqual([
        "var(--card-side-padding)",
        "var(--card-side-padding)",
      ]);
      expect(style.getPropertyValue("--card-side-padding")).toBe("");
      expect([style.marginTop, style.paddingTop]).toEqual(["18px", "18px"]);
      expect(getComputedStyle(part.firstElementChild!).marginTop).toBe("0px");
    }
    expect(
      getComputedStyle(replyHeading.closest('[role="group"] > *')!).marginTop,
    ).toBe("0px");
    for (const title of titles) {
      expect(getComputedStyle(title).fontWeight).toBe("700");
      expect([
        getComputedStyle(title).fontSize,
        getComputedStyle(title).lineHeight,
      ]).toEqual(["14px", "20px"]);
      expect(getComputedStyle(title).color).toBe("rgb(62, 74, 79)");
    }
    expect(
      within(
        screen
          .getByRole("heading", { name: "What it found" })
          .closest("section")!,
      ).getByText("Imaginary kettle seal"),
    ).toBeVisible();
    const outcome = within(
      screen.getByRole("heading", { name: "Review" }).closest("section")!,
    ).getByText("Outcome", { selector: "dt" });
    expect(
      outcome.compareDocumentPosition(
        screen.getByRole("table", { name: "Review decision" }),
      ) & Node.DOCUMENT_POSITION_FOLLOWING,
    ).toBe(Node.DOCUMENT_POSITION_FOLLOWING);
    for (const part of parts.slice(1)) {
      const first = part.firstElementChild!;
      part.prepend(document.createElement("style"));
      expect(getComputedStyle(first).marginTop).toBe("0px");
    }
  });

  it.each([
    [undefined, ["summary", "tone"]],
    ["summary", ["summary"]],
  ])(
    "says how sure the model was under who produced it, with field %s picked, one quieter line for each value it is said of, the reason given on a row of its own",
    async (field, sureOf) => {
      await drawing(
        {
          ...REFUSED_TRY,
          why: "Imaginary reason for asking again.",
          values: [
            { ...REFUSED_TRY.values[0]!, confidence: 80 },
            { field: "tone", value: "Calm.", now: "stands", confidence: 40 },
            { field: "reply", value: "Sorry.", now: "stands" },
          ],
        },
        field,
      );

      const produced = screen.getByText("Produced by", {
        selector: "dt",
      }).nextElementSibling!;
      const percents: Record<string, string> = { summary: "80%", tone: "40%" };
      expect(
        [...produced.querySelectorAll("p")].map((line) => line.textContent),
      ).toEqual(
        sureOf.map((name) => `${isolatedInText(name)}: ${percents[name]}`),
      );
      expect(produced.firstChild?.textContent).toBe("small");
      expect(produced).not.toHaveTextContent(isolatedInText("reply"));
      expect(screen.queryByText("Confidence")).toBeNull();
      const reason = screen.getByText("Reason given", { selector: "dt" });
      expect(reason).toHaveAttribute("data-across");
      expect(reason.nextElementSibling).toHaveTextContent(
        "Imaginary reason for asking again.",
      );
      expect(
        screen.getByText("Produced by", { selector: "dt" }),
      ).not.toHaveAttribute("data-across");
    },
  );

  it.each([undefined, "確認\u202e"])(
    "draws stored length quantities and the held step with label %s apart from the review decision",
    async (label) => {
      await drawing({
        ...REFUSED_TRY,
        ended: "refused_for_length",
        review: { asked: false },
        values: [
          {
            field: "summary",
            value: "The old summary",
            now: "refused_for_length",
            decision: {
              outcome: "refused",
              why: "Give it again, shorter.",
              lengthUnits: "9007199254740993",
              excessUnits: "1",
              lengthHeld: {
                run: { runId: "held-run", number: 12 },
                stepId: "held-step",
                stepName: "confirm",
                label,
              },
            },
          },
        ],
      });
      expect(screen.queryByText("Review decision")).not.toBeInTheDocument();
      expect(under("Outcome")).toContain(
        `${isolatedInText("summary")}: about 9,007,199,254,740,993 units`,
      );
      expect(under("Outcome")).toContain("Too long by: about 1 unit");
      expect(under("Outcome")).toContain("run 12");
      expect(
        screen.getByRole("link", {
          name: `Open ${isolatedInText(label ?? "confirm")}`,
        }),
      ).toHaveAttribute("href", "/groups/CLAIMS/work/held-run/steps/held-step");
    },
  );

  it.each(["", "**Imaginary original**\n<a href='/outside'>Keep raw</a>"])(
    "draws every original model reply %j under its own producer or reviewer without interpreting it",
    async (text) => {
      const drawn = await drawing({
        ...REFUSED_TRY,
        values: [],
        modelReply: { text, altered: false, truncated: false },
        review: {
          ...REFUSED_TRY.review,
          modelReply: { text, altered: true, truncated: true },
        },
      });
      expect(
        screen.getAllByText("The model's reply, exactly as it came"),
      ).toHaveLength(2);
      const originals = openRaw("The model's reply, exactly as it came").map(
        (contents) => contents.textContent,
      );
      expect(
        originals.every((original) => original?.startsWith(text) === true),
      ).toBe(true);
      expect(drawn.querySelectorAll("a,strong,em")).toHaveLength(0);
      expect(
        screen.queryByRole("button", { name: "Show as written" }),
      ).toBeNull();
    },
  );
  it("formats one-line producer and reviewer declining words while their matching original answers stay literal", async () => {
    const text = "**Imaginary evidence is missing**";
    const declined = {
      words: {
        lines: "several" as const,
        text,
        altered: false,
        truncated: false,
      },
      content: { text, altered: false, truncated: false },
    };
    const drawn = await drawing({
      ...REFUSED_TRY,
      values: [],
      didNotFit: "model_declined",
      declined,
      review: { ...REFUSED_TRY.review, didNotFit: "model_declined", declined },
    });
    expect(drawn.querySelectorAll("strong")).toHaveLength(2);
    const evidence = screen.getAllByText(
      "The model's reply, exactly as it came",
    );
    expect(evidence).toHaveLength(2);
    const originals = openRaw("The model's reply, exactly as it came");
    for (const original of originals) expect(original).toHaveTextContent(text);
    expect(
      screen.getAllByRole("button", { name: "Show as written" }),
    ).toHaveLength(1);
    fireEvent.click(screen.getByRole("button", { name: "Show as written" }));
    expect(drawn.querySelectorAll("strong")).toHaveLength(0);
    for (const original of originals) expect(original).toHaveTextContent(text);
  });
  it("formats both declined and reviewed words with one switch while raw answers remain evidence", async () => {
    const raw = '{"refusal":"# Missing evidence\\nKeep the original box"}';
    const drawn = await drawing({
      ...REFUSED_TRY,
      values: [],
      didNotFit: "model_declined",
      declined: {
        words: {
          lines: "several",
          text: "# Missing evidence\n**Keep** the box",
          altered: false,
          truncated: false,
        },
        content: { text: raw, altered: false, truncated: false },
      },
      review: {
        ...REFUSED_TRY.review,
        didNotFit: "model_declined",
        declined: {
          words: {
            lines: "several",
            text: "## Review evidence\n[Guide](https://example.com/claims)",
            altered: false,
            truncated: false,
          },
        },
      },
    });
    expect(
      screen.getAllByRole("button", { name: "Show as written" }),
    ).toHaveLength(1);
    expect(drawn.querySelectorAll("h1,h2")).toHaveLength(2);
    openRaw("The model's reply, exactly as it came");
    expect(rawUnder("The model's reply, exactly as it came")).toContain(raw);
    expect(drawn.querySelectorAll("a,img")).toHaveLength(0);
    fireEvent.click(screen.getByRole("button", { name: "Show as written" }));
    expect(drawn.querySelectorAll("h1,h2,strong")).toHaveLength(0);
    expect(drawn.textContent).toContain("# Missing evidence\n**Keep** the box");
    expect(rawUnder("The model's reply, exactly as it came")).toContain(raw);
  });
  it("keeps reviewer and returned member names distinct from the system's explanations", async () => {
    const drawn = await drawing({
      ...RETURNED_TRY,
      review: { asked: true, by: SMALL, wentWrong: true },
      erroredFor: {
        reason: "not_declared",
        member: "Demo Sample",
        memberCut: true,
      },
    });
    const hints = [...drawn.querySelectorAll("span")].filter((part) =>
      ["(the review hit an error)", "(name shortened here)"].includes(
        part.textContent ?? "",
      ),
    );

    expect(drawn).toHaveTextContent("small (the review hit an error)");
    expect(drawn).toHaveTextContent(
      `a field named ${isolatedInText("Demo Sample")} (name shortened here)`,
    );
    expect(hints).toHaveLength(2);
    expect(
      hints.every((part) => getComputedStyle(part).fontStyle === "italic"),
    ).toBe(true);
    expect(
      [...drawn.querySelectorAll("bdi")].some((part) =>
        part.textContent?.includes("Demo Sample"),
      ),
    ).toBe(true);
  });

  it("names each value's decision in details without revealing withheld words or treating length as review", async () => {
    const drawn = await drawing({
      ...REFUSED_TRY,
      values: [
        {
          field: "summary",
          value: "Demo Sample",
          now: "refused",
          decision: {
            outcome: "refused",
            why: "Not enough.",
            whyLines: "several",
          },
        },
        {
          field: "other",
          withheld: true,
          now: "refused",
          decision: { outcome: "refused", withheld: true },
        },
        {
          field: "length",
          value: "Too much",
          now: "refused_for_length",
          decision: {
            outcome: "refused",
            why: "Do not show.",
            whyLines: "several",
          },
        },
      ],
    });

    const decisions = screen.getByRole("table", { name: "Review decision" });
    const summary = within(decisions)
      .getByRole("rowheader", { name: isolatedInText("summary") })
      .closest("tr")!;
    expect(summary).toHaveTextContent("Not enough.");
    expect(
      within(decisions)
        .getByRole("rowheader", { name: isolatedInText("other") })
        .closest("tr"),
    ).toHaveTextContent(
      "Rejected, for a reason you don't have permission to see.",
    );
    expect(drawn).not.toHaveTextContent("Do not show.");
    expect(drawn).not.toHaveTextContent("length: ");
  });

  it("shows both model refusals in their own order as literal text and marks each changed block", async () => {
    const raw = '<a href="/elsewhere">Demo Sample</a>';
    await drawing({
      ...REFUSED_TRY,
      didNotFit: "model_declined",
      declined: {
        words: {
          text: "I cannot answer.",
          altered: false,
          truncated: true,
          lines: "several",
        },
        content: { text: raw, altered: true, truncated: false },
      },
      review: {
        ...REFUSED_TRY.review,
        didNotFit: "model_declined",
        declined: {
          words: {
            lines: "several",
            text: "I cannot approve.",
            altered: false,
            truncated: false,
          },
        },
      },
    });

    expect(
      screen.getAllByText("The model's reason for declining")[0]
        ?.nextElementSibling,
    ).toHaveTextContent("I cannot answer.");
    openRaw("The model's reply, exactly as it came");
    expect(rawUnder("The model's reply, exactly as it came")).toContain(raw);
    expect(screen.queryByRole("link", { name: "Demo Sample" })).toBeNull();
    expect(
      screen.getAllByText(
        "The model declined to answer, so nothing was taken from it, and this attempt is used up.",
      ),
    ).toHaveLength(2);
    expect(
      screen.getAllByText(
        "It is too long to store in full, so it is cut short here.",
      ),
    ).toHaveLength(1);
    expect(
      screen.getAllByText(
        "Some of it couldn't be stored exactly as received, so it is replaced here.",
      ),
    ).toHaveLength(1);
    expect(
      screen.getAllByText("The model's reason for declining"),
    ).toHaveLength(2);
  });

  it("does not reveal blocks or flags when either model's declined record is withheld or omitted", async () => {
    await drawing({
      ...REFUSED_TRY,
      didNotFit: "model_declined",
      declined: { withheld: true },
      review: { ...REFUSED_TRY.review, didNotFit: "model_declined" },
    });

    expect(
      screen.getAllByText(
        "The model declined to answer, so nothing was taken from it, and this attempt is used up.",
      ),
    ).toHaveLength(2);
    expect(screen.queryByText("The model's reason for declining")).toBeNull();
    expect(
      screen.queryByText("The model's reply, exactly as it came"),
    ).toBeNull();
    expect(
      screen.queryByText(
        "It is too long to store in full, so it is cut short here.",
      ),
    ).toBeNull();
  });

  it("shows independent literal unreadable responses and each source's own flags", async () => {
    const raw = '<a href="/elsewhere">Demo Sample</a>';
    const drawn = await drawing({
      ...REFUSED_TRY,
      unreadableResponse: {
        text: raw,
        altered: true,
        truncated: false,
        stopped: false,
      },
      review: {
        ...REFUSED_TRY.review,
        unreadableResponse: {
          text: "The review could not be read.",
          altered: false,
          truncated: true,
          stopped: false,
        },
      },
    });

    expect(
      screen.getAllByText("What the model service sent back"),
    ).toHaveLength(2);
    openRaw("What the model service sent back");
    expect(drawn).toHaveTextContent(raw);
    expect(drawn).toHaveTextContent("The review could not be read.");
    expect(screen.queryByRole("link", { name: "Demo Sample" })).toBeNull();
    expect(
      screen.getAllByText(
        "Some of it couldn't be stored exactly as received, so it is replaced here.",
      ),
    ).toHaveLength(1);
    expect(
      screen.getAllByText(
        "It is too long to store in full, so it is cut short here.",
      ),
    ).toHaveLength(1);
  });

  it.each(["producer", "reviewer"] as const)(
    "explains a stopped %s response without attributing it to the storage limit",
    async (source) => {
      const raw = '**Imaginary evidence**\n<a href="/outside">Keep this</a>';
      const record = {
        text: raw,
        altered: true,
        truncated: true,
        stopped: true,
      };
      const drawn = await drawing({
        ...REFUSED_TRY,
        values: [],
        ...(source === "producer" ? { unreadableResponse: record } : {}),
        review: {
          ...REFUSED_TRY.review,
          ...(source === "reviewer" ? { unreadableResponse: record } : {}),
        },
      });

      expect(
        screen.getAllByText("What the model service sent back"),
      ).toHaveLength(1);
      openRaw("What the model service sent back");
      expect(drawn.textContent).toContain(raw);
      expect(
        screen.getAllByText(
          "This could not be read, so the rest of it was not downloaded. Only its start is kept here.",
        ),
      ).toHaveLength(1);
      expect(
        screen.queryByText(
          "It is too long to store in full, so it is cut short here.",
        ),
      ).toBeNull();
      expect(
        screen.getAllByText(
          "Some of it couldn't be stored exactly as received, so it is replaced here.",
        ),
      ).toHaveLength(1);
      expect(drawn.querySelectorAll("strong,a,img")).toHaveLength(0);
      expect(
        screen.queryByRole("button", { name: "Show as written" }),
      ).toBeNull();
    },
  );

  it.each([
    {
      says: "replaced",
      altered: true,
      truncated: false,
      stopped: false,
      notes: [REPLACED_NOTE],
    },
    {
      says: "cut short",
      altered: false,
      truncated: true,
      stopped: false,
      notes: [CUT_SHORT_NOTE],
    },
    {
      says: "not all downloaded, not cut short",
      altered: false,
      truncated: true,
      stopped: true,
      notes: [STOPPED_NOTE],
    },
    {
      says: "replaced and cut short",
      altered: true,
      truncated: true,
      stopped: false,
      notes: [REPLACED_NOTE, CUT_SHORT_NOTE],
    },
    {
      says: "nothing",
      altered: false,
      truncated: false,
      stopped: false,
      notes: [],
    },
  ])(
    "says $says of a raw response under its title while its box is shut, and once when it is opened",
    async ({ altered, truncated, stopped, notes }) => {
      await drawing({
        ...REFUSED_TRY,
        values: [],
        unreadableResponse: {
          text: "Imaginary service reply",
          altered,
          truncated,
          stopped,
        },
      });
      const title = screen.getByRole("button", {
        name: "What the model service sent back",
      });
      const contents = document.getElementById(
        title.getAttribute("aria-controls")!,
      )!;
      const drawnNotes = () =>
        [REPLACED_NOTE, STOPPED_NOTE, CUT_SHORT_NOTE].flatMap((note) =>
          screen.queryAllByText(note),
        );

      expect(contents).not.toBeVisible();
      expect(drawnNotes().map((note) => note.textContent)).toEqual(notes);
      drawnNotes().forEach((note) => expect(note).toBeVisible());
      expect(drawnNotes().filter((note) => contents.contains(note))).toEqual(
        [],
      );
      fireEvent.click(title);
      expect(contents).toBeVisible();
      expect(contents).toHaveTextContent("Imaginary service reply");
      expect(drawnNotes().map((note) => note.textContent)).toEqual(notes);
      expect(drawnNotes().filter((note) => contents.contains(note))).toEqual(
        [],
      );
    },
  );

  it("draws only the producer's readable raw block when review has none", async () => {
    await drawing({
      ...REFUSED_TRY,
      unreadableResponse: {
        text: "Demo Sample service raw",
        altered: false,
        truncated: false,
        stopped: false,
      },
    });

    expect(
      screen.getAllByText("What the model service sent back"),
    ).toHaveLength(1);
    expect(
      screen.queryByText("The model's reply, exactly as it came"),
    ).toBeNull();
    expect(screen.queryByText("The model's reason for declining")).toBeNull();
  });

  it("keeps search counts, historical unit price and fee visible with withheld content, independently of model token cost", async () => {
    const drawn = await drawing({
      ...REFUSED_TRY,
      search: {
        said: { withheld: true },
        counted: {
          uses: "2",
          usesCounted: "said",
          fee: { currency: "USD", perUse: "0.000001", amount: "0.000002" },
        },
      },
    });

    expect(under("Searches")).toBe("2 searches, as the model reported");
    expect(under("Search fee")).toBe(
      "$0.000002$0.000001 per search at the time",
    );
    expect(
      screen.getByText("$0.000001 per search at the time").closest("dd")
        ?.previousElementSibling?.textContent,
    ).toBe("Search fee");
    expect(screen.queryByText("Price per search at the time")).toBeNull();
    expect(screen.getByText("Search fee", { selector: "dt" })).toHaveAttribute(
      "data-across",
    );
    expect(drawn).toHaveTextContent(
      "You don't have permission to see what it searched for and found.",
    );
    expect(screen.queryByText("Cost")).toBeNull();
    expect(screen.queryByText("Usage")).toBeNull();
    expect(screen.queryByText("Search question")).toBeNull();
  });

  it.each([
    [
      { reason: "not_in_currency", currency: "GBP" },
      "Searching had no price in GBP, so no fee is shown.",
    ],
    [
      { reason: "many_currencies" },
      "Not shown: searching was priced in more than one currency, and this group hasn't chosen one.",
    ],
    [{ reason: "no_price" }, "Searching had no price, so no fee is shown."],
  ] as const)(
    "says the server's no-fee reason %j without inventing a price or changing the search count",
    async (noFee, words) => {
      await drawing({
        ...REFUSED_TRY,
        search: {
          said: {
            searchesUnsaid: true,
          },
          counted: { uses: "1", usesCounted: "at_most", noFee },
        },
      });

      expect(under("Searches")).toBe(
        "1 search: the most one call can make, counted because the model didn't report how many",
      );
      expect(under("Search fee")).toBe(words);
      expect(screen.queryByText(/per search at the time/)).toBeNull();
      expect(screen.queryByText("Cost")).toBeNull();
    },
  );
  it("links only a trusted kept file on an errored code try, even when returned JSON names another id", async () => {
    const file = {
      path: "attachments[2]",
      id: "11111111-1111-4111-8111-111111111111",
      name: "kept.txt",
      size: 7,
    };
    const fake = "22222222-2222-4222-8222-222222222222";
    const returned = `{"attachment":{"id":"${fake}","name":"Imaginary untrusted.tar.gz","size":13}}`;
    const drawn = await drawing(
      {
        ...RETURNED_TRY,
        keptFiles: [file],
        returned,
      },
      undefined,
      true,
    );

    const fetch = vi.mocked(globalThis.fetch);

    const link = screen.getByRole("link", { name: /kept.txt/ });
    expect(link).toHaveAttribute(
      "href",
      `/api/groups/group-a/runs/run-a/files/${file.id}`,
    );
    expect(screen.getAllByRole("link")).toHaveLength(1);
    expect(link).toHaveAttribute("download");
    expect(link).toHaveStyle({ display: "block" });
    expect(
      [...link.querySelectorAll("bdi")]
        .filter((each) => each.closest("[hidden]") === null)
        .map((each) => each.textContent),
    ).toEqual(["kept.txt"]);
    expect(link).toHaveAccessibleName(
      `Download ${isolatedInText("kept.txt")}, 7 B`,
    );
    expect(screen.queryByText(returned)).toBeNull();
    expect(
      screen.queryByRole("link", { name: /Imaginary untrusted/ }),
    ).toBeNull();
    expect(drawn.querySelector("iframe, img, embed, object")).toBeNull();
    expect(fetch).toHaveBeenCalledTimes(1);
    expect(String(fetch.mock.calls[0]?.[0])).toMatch(/\/tries\/1$/);
    expect(
      fetch.mock.calls.some(([url]) => String(url).includes("/files/")),
    ).toBe(false);
    fireEvent.click(screen.getByRole("button", { name: /in full/ }));
    expect(screen.getByText(new RegExp(fake))).toBeInTheDocument();
    const raw = screen.getByText(returned);
    expect(raw).toBeVisible();
    expect(raw.textContent).toBe(returned);
    expect(screen.getAllByRole("link")).toEqual([link]);
    expect(link).toHaveAttribute(
      "href",
      `/api/groups/group-a/runs/run-a/files/${file.id}`,
    );
    expect(link.getAttribute("href")).not.toContain(fake);
    expect(
      screen.queryByRole("link", { name: /Imaginary untrusted/ }),
    ).toBeNull();
    expect(drawn.querySelector("iframe, img, embed, object")).toBeNull();
    expect(fetch).toHaveBeenCalledTimes(1);
    expect(
      fetch.mock.calls.some(([url]) => String(url).includes("/files/")),
    ).toBe(false);
  });

  it.each([
    ["identifier", { id: "not-a-uuid" }],
    ["name", { name: "Demo\nSample.txt" }],
    ["size", { size: -1 }],
  ])(
    "does not link kept code metadata with an unusable %s",
    async (_case, changed) => {
      const file = {
        path: "attachments[0]",
        id: "11111111-1111-4111-8111-111111111111",
        name: "Demo Sample.txt",
        size: 7,
        ...changed,
      };
      const drawn = await drawing(
        { ...RETURNED_TRY, keptFiles: [file] },
        undefined,
        true,
      );

      expect(drawn).toHaveTextContent("This page can't show this yet.");
      expect(screen.queryByRole("link")).toBeNull();
    },
  );

  it("uses the current nested file declaration for kept code file cards after removing list positions from their path", async () => {
    const file = {
      path: "receipts[2].attachments[1]",
      id: "11111111-1111-4111-8111-111111111111",
      name: "Imaginary receipts.TAR.GZ",
      size: 1_280,
    };
    const drawn = await drawing(
      { ...RETURNED_TRY, keptFiles: [file] },
      undefined,
      true,
      {
        takes: [],
        gives: [
          {
            name: "receipts",
            label: "Receipts",
            kind: "fields",
            mustBeGiven: true,
            most: 3,
            fields: [
              {
                name: "attachments",
                label: "Receipt files",
                kind: "file",
                mustBeGiven: true,
                most: 2,
                types: [".gz", ".tar.gz"],
                maximumBytes: 2_000,
              },
            ],
          },
        ],
      },
    );

    const link = screen.getByRole("link", {
      name: `Download ${isolatedInText(file.name)}, 1.3 KB`,
    });
    expect(link).toHaveAttribute(
      "href",
      "/api/groups/group-a/runs/run-a/files/11111111-1111-4111-8111-111111111111",
    );
    expect(link).toHaveAttribute("download");
    expect(
      [...link.querySelectorAll("bdi")]
        .filter((each) => each.closest("[hidden]") === null)
        .map((each) => each.textContent),
    ).toEqual([file.name]);
    expect(drawn).toHaveTextContent("Receipt files");
    expect(screen.getAllByRole("link")).toHaveLength(1);
    expect(drawn.querySelector("iframe, img, embed, object")).toBeNull();
  });

  /** What a try cost is said in the list the tries are picked in; never here. */
  it("says who produced and reviewed a try and how it ended, and nothing of what it cost", async () => {
    const drawn = await drawing({
      ...REFUSED_TRY,
      cost: {
        spend: {
          sent: "1200",
          cameBack: "300",
          spent: "1500",
          cameBackUnknown: false,
        },
      },
    });

    expect(screen.getAllByRole("term").map((term) => term.textContent)).toEqual(
      expect.arrayContaining(["Produced by", "Reviewed by", "Outcome"]),
    );
    expect(screen.queryByText("Cost")).toBeNull();
    expect(screen.queryByText("Usage")).toBeNull();
    expect(drawn).not.toHaveTextContent("1,500");
  });

  it("draws a space that shows nothing in the words a value was refused with as a marker named for it, the words isolated", async () => {
    const drawn = await drawing(REFUSED_TRY, "summary");
    const markers = screen.getAllByRole("img", {
      name: "An invisible character is here: U+200B.",
    });

    expect(markers).toHaveLength(2);
    expect(
      screen.getByRole("table", { name: "Review decision" }),
    ).toHaveTextContent("Rejected: TooU+200Bshort.");
    for (const marker of markers) {
      expect(marker.closest('[dir="auto"]')?.textContent).toBe(
        "TooU+200Bshort.",
      );
    }
    expect(drawn.innerHTML).not.toContain(ZERO_WIDTH_SPACE);
  });

  it.each([
    [false, "Bad U+061Ckey"],
    [true, "Bad U+061Ckey (cut short here)"],
  ])(
    "draws a mark setting a direction in what went wrong, cut short %s, as a marker named for it, never the mark itself",
    async (cut, said) => {
      const drawn = await drawing(wentWrongTry(cut));
      const marker = screen.getByRole("img", {
        name: "A text-direction character is here, shown instead of applied: U+061C.",
      });

      expect(under("Error")).toBe(said);
      expect(marker.closest("bdi")?.textContent).toBe("Bad U+061Ckey");
      expect(drawn.innerHTML).not.toContain(ARABIC_LETTER_MARK);
    },
  );

  it("draws an override in what code gave back where it stands as a marker named for it, never the override itself, once opened", async () => {
    const drawn = await drawing(RETURNED_TRY);

    fireEvent.click(screen.getByRole("button", { name: /in full/ }));

    expect(
      screen.getByRole("img", {
        name: "A text-direction character is here, shown instead of applied: U+202E.",
      }),
    ).toHaveTextContent(/^U\+202E$/);
    expect(drawn.querySelector("bdi")?.textContent).toBe(
      '{"reference": "U+202E7"}',
    );
    expect(drawn.innerHTML).not.toContain(RIGHT_TO_LEFT_OVERRIDE);
  });
});
