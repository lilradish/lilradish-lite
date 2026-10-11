import { ThemeProvider } from "@mui/material/styles";
import { fireEvent, render, screen } from "@testing-library/react";
import { createIntl } from "react-intl";
import { describe, expect, it, vi } from "vitest";

import type { SearchSaid } from "../../../api/stepTries";
import { readersIntl } from "../../../i18n/intl";
import { Fields } from "../../../lib/form/Fields";
import { theme } from "../../../lib/theme/theme";
import { SearchRecord, SearchesCountedRows } from "./SearchRecord";

function drawing(said: SearchSaid) {
  return render(
    <ThemeProvider theme={theme}>
      <SearchRecord said={said} />
    </ThemeProvider>,
  ).container;
}

describe("SearchRecord", () => {
  it.each(["en", "de", "fr"])(
    "keeps exact long counts in the reader's %s format and English plural",
    (locale) => {
      const formatter = createIntl({ locale, defaultLocale: "en" });
      const formatting = vi
        .spyOn(readersIntl, "formatNumber")
        .mockImplementation(formatter.formatNumber);
      const digits = "9223372036854775807";
      try {
        const drawn = drawing({
          searchesLeftOut: digits,
        });
        expect(drawn.textContent).toContain(
          `${formatter.formatNumber(BigInt(digits))} more search entries were not stored.`,
        );
        const counted = render(
          <Fields>
            <SearchesCountedRows
              counted={{
                uses: digits,
                usesCounted: "said",
                noFee: { reason: "no_price" },
              }}
            />
          </Fields>,
        ).container;
        expect(counted.textContent).toContain(
          `${formatter.formatNumber(BigInt(digits))} searches, as the model reported`,
        );
        const atMost = render(
          <Fields>
            <SearchesCountedRows
              counted={{
                uses: "1",
                usesCounted: "at_most",
                noFee: { reason: "no_price" },
              }}
            />
          </Fields>,
        ).container;
        expect(atMost).toHaveTextContent(
          "1 search: the most one call can make",
        );
        expect(
          formatting.mock.calls
            .filter(([value]) => typeof value === "bigint")
            .map(([value]) => value),
        ).toContain(BigInt(digits));
      } finally {
        formatting.mockRestore();
      }
    },
  );
  it("formats the recorded query and found words together", () => {
    const drawn = drawing({
      searches: [
        {
          searchedFor:
            "**Imaginary billing question**\n*Imaginary invoice evidence*",
          found: "[Imaginary source](https://example.com/invented)",
        },
      ],
    });
    expect(drawn.querySelector("strong")).toHaveTextContent(
      "Imaginary billing question",
    );
    expect(drawn.querySelector("em")).toHaveTextContent(
      "Imaginary invoice evidence",
    );
    expect(drawn).toHaveTextContent("https://example.com/invented");
    expect(drawn.querySelectorAll("a,img")).toHaveLength(0);
    expect(
      screen.getAllByRole("button", { name: "Show as written" }),
    ).toHaveLength(1);
    fireEvent.click(screen.getByRole("button", { name: "Show as written" }));
    expect(drawn.querySelectorAll("strong,em")).toHaveLength(0);
    expect(drawn).toHaveTextContent("**Imaginary billing question**");
    expect(drawn).toHaveTextContent(
      "[Imaginary source](https://example.com/invented)",
    );
  });
  it("switches every multiline search record together with one control", () => {
    const drawn = drawing({
      searches: [
        {
          searchedFor:
            "# Imaginary parcel\nFind the box label\n**Evidence**\nBox label",
          found: "## Imaginary notes\n[Source](https://example.com/practice)",
        },
      ],
    });
    expect(
      screen.getAllByRole("button", { name: "Show as written" }),
    ).toHaveLength(1);
    expect(drawn.querySelectorAll("h1,h2,strong")).toHaveLength(3);
    expect(drawn.querySelectorAll("a,img")).toHaveLength(0);
    fireEvent.click(screen.getByRole("button", { name: "Show as written" }));
    expect(drawn.querySelectorAll("h1,h2,strong")).toHaveLength(0);
    expect(drawn.textContent).toContain(
      "[Source](https://example.com/practice)",
    );
    expect(
      screen.getAllByRole("button", { name: "Show formatted" }),
    ).toHaveLength(1);
  });

  it("counts decoded hidden entities across query and found records under one bound", () => {
    const drawn = drawing({
      searches: [
        {
          searchedFor: "Question\n" + "a&#8203;".repeat(600),
          found: "Evidence\n" + "b&#8203;".repeat(600),
        },
      ],
    });
    expect(drawn.textContent).toContain(
      "1,200 invisible or text-direction characters",
    );
    expect(drawn.querySelectorAll('[role="img"]')).toHaveLength(0);
    expect(drawn.textContent).not.toContain("U+200B");
    expect(drawn.textContent).not.toContain(String.fromCodePoint(0x200b));
  });
  it("keeps empty halves while omitting absent halves, and draws text without interpreting markup or hidden direction", () => {
    const hidden = String.fromCodePoint(0x202e);
    const drawn = drawing({
      searches: [
        { searchedFor: "", found: `<img src=x>\nDemo${hidden}Sample` },
        { found: "" },
      ],
    });

    expect(screen.getAllByText("Found")).toHaveLength(2);
    expect(screen.getAllByText("Searched for")).toHaveLength(1);
    expect(
      screen.getAllByText("Found")[0]!.nextElementSibling,
    ).toHaveTextContent("<img src=x>");
    expect(screen.getByRole("img")).toHaveTextContent("U+202E");
    expect(screen.getByRole("img").closest('[dir="auto"]')).toHaveTextContent(
      "DemoU+202ESample",
    );
    expect(drawn.innerHTML).not.toContain(hidden);
    expect(drawn.querySelector("img")).toBeNull();
  });

  it("says all recorded limits without claiming an unsaid record is complete", () => {
    const drawn = drawing({
      searches: [{ found: "A record" }],
      searchesCut: true,
      searchesNotAsItCame: true,
      searchesLeftOut: "2",
    });

    expect(drawn).not.toHaveTextContent(
      "The model did not say what it searched for or found.",
    );
    expect(drawn).toHaveTextContent("so it is cut short here.");
    expect(drawn).toHaveTextContent(
      "Part of the search record couldn't be stored exactly as received.",
    );
    expect(drawn).toHaveTextContent("2 more search entries were not stored.");
    expect(screen.getByText("Found")).toBeInTheDocument();
  });

  it("withholds records without drawing their labels or a guessed blank record", () => {
    const drawn = drawing({ withheld: true });

    expect(drawn).toHaveTextContent(
      "You don't have permission to see what it searched for and found.",
    );
    expect(screen.queryByText("Search question")).toBeNull();
    expect(drawn.querySelector("bdi, dl")).toBeNull();
  });

  it("says what one search was priced at in a quieter line under the fee, never as a pair of its own", () => {
    const counted = render(
      <ThemeProvider theme={theme}>
        <Fields>
          <SearchesCountedRows
            counted={{
              uses: "3",
              usesCounted: "said",
              fee: { currency: "USD", perUse: "0.25", amount: "0.75" },
            }}
          />
        </Fields>
      </ThemeProvider>,
    ).container;

    expect(
      [...counted.querySelectorAll("dt")].map((term) => term.textContent),
    ).toEqual(["Searches", "Search fee"]);
    const fee = screen.getByText("Search fee", {
      selector: "dt",
    }).nextElementSibling!;
    expect(fee.textContent).toBe("$0.75$0.25 per search at the time");
    expect(fee.lastElementChild?.tagName).toBe("P");
    expect(fee.lastElementChild).toHaveTextContent(
      "$0.25 per search at the time",
    );
    expect(screen.queryByText("Price per search at the time")).toBeNull();
  });

  it("shares the marker limit across the query and all found entries", () => {
    const drawn = drawing({
      searches: [
        {
          searchedFor: `a${String.fromCodePoint(0x200b)}`.repeat(600),
          found: `b${String.fromCodePoint(0x200b)}`.repeat(600),
        },
      ],
    });

    expect(drawn).toHaveTextContent("1,200");
    expect(screen.queryByRole("img")).toBeNull();
    expect(drawn.innerHTML).not.toContain(String.fromCodePoint(0x200b));
  });
});
