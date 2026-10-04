import { ThemeProvider } from "@mui/material/styles";
import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import type { Try } from "../../../api/groups/{groupId}/runs/{runId}/steps/{stepId}";
import { isolatedInText } from "../../../lib/direction/isolated";
import { theme } from "../../../lib/theme/theme";
import { CurrentFileRunContext } from "../fill/CurrentFileRun";
import { TryPanel } from "./TryPanel";

const RIGHT_TO_LEFT_OVERRIDE = String.fromCodePoint(0x202e);

const ZERO_WIDTH_SPACE = String.fromCodePoint(0x200b);

const ARABIC_LETTER_MARK = String.fromCodePoint(0x061c);

const SMALL = { kind: "model", model: "small" } as const;

const RETURNED_TRY: Try = {
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

const REFUSED_TRY: Try = {
  number: 1,
  beyond: false,
  producedBy: SMALL,
  values: [
    {
      field: "summary",
      value: "A fire.",
      now: "refused",
      decision: { outcome: "refused", why: `Too${ZERO_WIDTH_SPACE}short.` },
    },
  ],
  review: { asked: true, by: SMALL },
  ended: "refused_on_review",
  cost: {},
};

function wentWrongTry(cut: boolean): Try {
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

function drawing(aTry: Try, field?: string, withRun = false) {
  return render(
    <ThemeProvider theme={theme}>
      <CurrentFileRunContext.Provider
        value={withRun ? { groupId: "group-a", runId: "run-a" } : null}
      >
        <TryPanel
          aTry={aTry}
          field={field}
          declared={undefined}
          declaring={{
            runId: "r1",
            declarations: new Map(),
            runVersionId: "v1",
            steps: undefined,
          }}
        />
      </CurrentFileRunContext.Provider>
    </ThemeProvider>,
  ).container;
}

function under(label: string): string | null | undefined {
  return screen.getByText(label).nextElementSibling?.textContent;
}

describe("TryPanel", () => {
  it("keeps reviewer and returned member names distinct from the system's explanations", () => {
    const drawn = drawing({
      ...RETURNED_TRY,
      review: { asked: true, by: SMALL, wentWrong: true },
      erroredFor: {
        reason: "not_declared",
        member: "Demo Sample",
        memberCut: true,
      },
    });
    const hints = [...drawn.querySelectorAll("span")].filter((part) =>
      ["whose review went wrong", "(its name cut short here)"].includes(
        part.textContent ?? "",
      ),
    );

    expect(drawn).toHaveTextContent("small, whose review went wrong");
    expect(drawn).toHaveTextContent(
      `a member named ${isolatedInText("Demo Sample")} (its name cut short here)`,
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

  it("names each value's decision in details without revealing withheld words or treating length as review", () => {
    const drawn = drawing({
      ...REFUSED_TRY,
      values: [
        {
          field: "summary",
          value: "Demo Sample",
          now: "refused",
          decision: { outcome: "refused", why: "Not enough." },
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
          decision: { outcome: "refused", why: "Do not show." },
        },
      ],
    });

    expect(under("What the review said")).toContain("summary");
    expect(under("What the review said")).toContain("Not enough.");
    expect(under("What the review said")).toContain("other");
    expect(drawn).not.toHaveTextContent("Do not show.");
    expect(drawn).not.toHaveTextContent("length: ");
  });

  it("shows both model refusals in their own order as literal text and marks each changed block", () => {
    const raw = '<a href="/elsewhere">Demo Sample</a>';
    drawing({
      ...REFUSED_TRY,
      didNotFit: "model_declined",
      declined: {
        words: { text: "I cannot answer.", altered: false, truncated: true },
        content: { text: raw, altered: true, truncated: false },
      },
      review: {
        ...REFUSED_TRY.review,
        didNotFit: "model_declined",
        declined: {
          words: {
            text: "I cannot approve.",
            altered: false,
            truncated: false,
          },
        },
      },
    });

    expect(
      screen.getAllByText("What the model said in declining")[0]
        ?.nextElementSibling,
    ).toHaveTextContent("I cannot answer.");
    expect(under("What the model gave back")).toContain(raw);
    expect(screen.queryByRole("link", { name: "Demo Sample" })).toBeNull();
    expect(
      screen.getAllByText(
        "The model declined to answer, so its answer was not taken, and this try is spent.",
      ),
    ).toHaveLength(2);
    expect(
      screen.getAllByText(
        "It is longer than may be kept, so it is cut short here.",
      ),
    ).toHaveLength(1);
    expect(
      screen.getAllByText(
        "It held something that could not be kept exactly as it came, so that is replaced here.",
      ),
    ).toHaveLength(1);
    expect(
      screen.getAllByText("What the model said in declining"),
    ).toHaveLength(2);
  });

  it("does not reveal blocks or flags when either model's declined record is withheld or omitted", () => {
    drawing({
      ...REFUSED_TRY,
      didNotFit: "model_declined",
      declined: { withheld: true },
      review: { ...REFUSED_TRY.review, didNotFit: "model_declined" },
    });

    expect(
      screen.getAllByText(
        "The model declined to answer, so its answer was not taken, and this try is spent.",
      ),
    ).toHaveLength(2);
    expect(screen.queryByText("What the model said in declining")).toBeNull();
    expect(screen.queryByText("What the model gave back")).toBeNull();
    expect(
      screen.queryByText(
        "It is longer than may be kept, so it is cut short here.",
      ),
    ).toBeNull();
  });

  it("shows independent literal unreadable responses and each source's own flags", () => {
    const raw = '<a href="/elsewhere">Demo Sample</a>';
    const drawn = drawing({
      ...REFUSED_TRY,
      unreadableResponse: { text: raw, altered: true, truncated: false },
      review: {
        ...REFUSED_TRY.review,
        unreadableResponse: {
          text: "The review could not be read.",
          altered: false,
          truncated: true,
        },
      },
    });

    expect(
      screen.getAllByText("What the model service sent back"),
    ).toHaveLength(2);
    expect(drawn).toHaveTextContent(raw);
    expect(drawn).toHaveTextContent("The review could not be read.");
    expect(screen.queryByRole("link", { name: "Demo Sample" })).toBeNull();
    expect(
      screen.getAllByText(
        "It held something that could not be kept exactly as it came, so that is replaced here.",
      ),
    ).toHaveLength(1);
    expect(
      screen.getAllByText(
        "It is longer than may be kept, so it is cut short here.",
      ),
    ).toHaveLength(1);
  });

  it("draws only the producer's readable raw block when review has none", () => {
    drawing({
      ...REFUSED_TRY,
      unreadableResponse: {
        text: "Demo Sample service raw",
        altered: false,
        truncated: false,
      },
    });

    expect(
      screen.getAllByText("What the model service sent back"),
    ).toHaveLength(1);
    expect(screen.queryByText("What the model gave back")).toBeNull();
    expect(screen.queryByText("What the model said in declining")).toBeNull();
  });

  it("keeps search counts, historical unit price and fee visible with withheld content, independently of model token cost", () => {
    const drawn = drawing({
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

    expect(under("Searches")).toBe("2 searches, as the answer said");
    expect(under("Price of one search, as it stood then")).toBe("$0.000001");
    expect(under("Search fee")).toBe("$0.000002");
    expect(drawn).toHaveTextContent("are withheld from you.");
    expect(screen.queryByText("Cost")).toBeNull();
    expect(screen.queryByText("The question it searched with")).toBeNull();
  });

  it.each([
    [
      { reason: "not_in_currency", currency: "GBP" },
      "Searching had no price in GBP, so no fee is shown.",
    ],
    [
      { reason: "many_currencies" },
      "Searching was priced in more than one currency, and this group has chosen none, so no fee is shown.",
    ],
    [{ reason: "no_price" }, "Searching had no price, so no fee is shown."],
  ] as const)(
    "says the server's no-fee reason %j without inventing a price or changing the search count",
    (noFee, words) => {
      drawing({
        ...REFUSED_TRY,
        search: {
          said: { question: "Demo Sample", searchesUnsaid: true },
          counted: { uses: "1", usesCounted: "at_most", noFee },
        },
      });

      expect(under("Searches")).toBe(
        "1 search: the most one call may make, counted as nothing said how many",
      );
      expect(under("Search fee")).toBe(words);
      expect(
        screen.queryByText("Price of one search, as it stood then"),
      ).toBeNull();
      expect(screen.queryByText("Cost")).toBeNull();
    },
  );
  it("links only a trusted kept file on an errored code try, even when returned JSON names another id", () => {
    const file = {
      path: "attachments[2]",
      id: "11111111-1111-4111-8111-111111111111",
      name: "kept.txt",
      type: "text/plain",
      size: 7,
    };
    const fake = "22222222-2222-4222-8222-222222222222";
    drawing(
      {
        ...RETURNED_TRY,
        keptFiles: [file],
        returned: `{"attachment":{"id":"${fake}"}}`,
      },
      undefined,
      true,
    );

    const link = screen.getByRole("link", { name: /kept.txt/ });
    expect(link).toHaveAttribute(
      "href",
      `/api/groups/group-a/runs/run-a/files/${file.id}`,
    );
    expect(screen.getAllByRole("link")).toHaveLength(1);
    fireEvent.click(screen.getByRole("button", { name: /in full/ }));
    expect(screen.getByText(new RegExp(fake))).toBeInTheDocument();
    expect(screen.getAllByRole("link")).toHaveLength(1);
  });

  it.each([
    ["identifier", { id: "not-a-uuid" }],
    ["name", { name: "Demo\nSample.txt" }],
    ["type", { type: "text//plain" }],
  ])(
    "does not link kept code metadata with an unusable %s",
    (_case, changed) => {
      const file = {
        path: "attachments[0]",
        id: "11111111-1111-4111-8111-111111111111",
        name: "Demo Sample.txt",
        type: "text/plain",
        size: 7,
        ...changed,
      };
      const drawn = drawing(
        { ...RETURNED_TRY, keptFiles: [file] },
        undefined,
        true,
      );

      expect(drawn).toHaveTextContent("Not something this page can say yet.");
      expect(screen.queryByRole("link")).toBeNull();
    },
  );

  /** What a try cost is said in the list the tries are picked in; never here. */
  it("says who produced and reviewed a try and how it ended, and nothing of what it cost", () => {
    const drawn = drawing({
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
      expect.arrayContaining(["Produced by", "Reviewed by", "How it ended"]),
    );
    expect(screen.queryByText("Cost")).toBeNull();
    expect(drawn).not.toHaveTextContent("1,500");
  });

  it("draws a space that shows nothing in the words a value was refused with as a marker named for it, the words isolated", () => {
    const drawn = drawing(REFUSED_TRY, "summary");
    const marker = screen.getByRole("img", {
      name: "A character that shows nothing is here: U+200B.",
    });

    expect(under("What the review said")).toBe("Refused: TooU+200Bshort.");
    expect(marker.closest("bdi")?.textContent).toBe("TooU+200Bshort.");
    expect(drawn.innerHTML).not.toContain(ZERO_WIDTH_SPACE);
  });

  it.each([
    [false, "Bad U+061Ckey"],
    [true, "Bad U+061Ckey (cut short here)"],
  ])(
    "draws a mark setting a direction in what went wrong, cut short %s, as a marker named for it, never the mark itself",
    (cut, said) => {
      const drawn = drawing(wentWrongTry(cut));
      const marker = screen.getByRole("img", {
        name: "A character that would turn the order text is shown in is here, drawn instead of obeyed: U+061C.",
      });

      expect(under("What went wrong")).toBe(said);
      expect(marker.closest("bdi")?.textContent).toBe("Bad U+061Ckey");
      expect(drawn.innerHTML).not.toContain(ARABIC_LETTER_MARK);
    },
  );

  it("draws an override in what code gave back where it stands as a marker named for it, never the override itself, once opened", () => {
    const drawn = drawing(RETURNED_TRY);

    fireEvent.click(screen.getByRole("button", { name: /in full/ }));

    expect(
      screen.getByRole("img", {
        name: "A character that would turn the order text is shown in is here, drawn instead of obeyed: U+202E.",
      }),
    ).toHaveTextContent(/^U\+202E$/);
    expect(drawn.querySelector("bdi")?.textContent).toBe(
      '{"reference": "U+202E7"}',
    );
    expect(drawn.innerHTML).not.toContain(RIGHT_TO_LEFT_OVERRIDE);
  });
});
