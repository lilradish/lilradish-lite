import { ThemeProvider } from "@mui/material/styles";
import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import type { SearchSaid } from "../../../api/groups/{groupId}/runs/{runId}/steps/{stepId}";
import { theme } from "../../../lib/theme/theme";
import { SearchRecord } from "./SearchRecord";

function drawing(said: SearchSaid) {
  return render(
    <ThemeProvider theme={theme}>
      <SearchRecord said={said} />
    </ThemeProvider>,
  ).container;
}

describe("SearchRecord", () => {
  it("keeps empty halves while omitting absent halves, and draws text without interpreting markup or hidden direction", () => {
    const hidden = String.fromCodePoint(0x202e);
    const drawn = drawing({
      question: `Demo${hidden}Sample`,
      searches: [{ searchedFor: "", found: "<img src=x>" }, { found: "" }],
    });

    expect(screen.getAllByText("What it found")).toHaveLength(2);
    expect(screen.getAllByText("What it searched for")).toHaveLength(1);
    expect(screen.getByText("<img src=x>")).toBeInTheDocument();
    expect(screen.getByRole("img")).toHaveTextContent("U+202E");
    expect(screen.getByRole("img").closest("bdi")).toHaveTextContent(
      "DemoU+202ESample",
    );
    expect(drawn.innerHTML).not.toContain(hidden);
    expect(drawn.querySelector("img")).toBeNull();
  });

  it("says all recorded limits without claiming an unsaid record is complete", () => {
    const drawn = drawing({
      question: "Demo Sample",
      searches: [{ found: "A record" }],
      searchesCut: true,
      searchesNotAsItCame: true,
      searchesLeftOut: "2",
    });

    expect(drawn).not.toHaveTextContent(
      "The answer did not say what it searched for or found.",
    );
    expect(drawn).toHaveTextContent("so it is cut short here.");
    expect(drawn).toHaveTextContent("could not be kept exactly as it came.");
    expect(drawn).toHaveTextContent(
      "2 more things it searched for or found were not kept.",
    );
    expect(screen.getByText("What it found")).toBeInTheDocument();
  });

  it("withholds the question and records without drawing their labels or a guessed blank record", () => {
    const drawn = drawing({ withheld: true });

    expect(drawn).toHaveTextContent(
      "The question it searched with, and what it searched for and found, are withheld from you.",
    );
    expect(screen.queryByText("The question it searched with")).toBeNull();
    expect(drawn.querySelector("bdi, dl")).toBeNull();
  });

  it("shares the marker limit across the question and all search entries", () => {
    const drawn = drawing({
      question: `a${String.fromCodePoint(0x200b)}`.repeat(600),
      searches: [{ found: `b${String.fromCodePoint(0x200b)}`.repeat(600) }],
    });

    expect(drawn).toHaveTextContent("1,200");
    expect(screen.queryByRole("img")).toBeNull();
    expect(drawn.innerHTML).not.toContain(String.fromCodePoint(0x200b));
  });
});
