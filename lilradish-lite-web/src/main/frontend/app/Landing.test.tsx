import { ThemeProvider } from "@mui/material/styles";
import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import { theme } from "../lib/theme/theme";
import { drawnAtDepth, drawnOf } from "../testutil/depths";
import { Landing } from "./Landing";

describe("Landing", () => {
  it("names the system and says the reader arrived", () => {
    render(
      <ThemeProvider theme={theme}>
        <Landing />
      </ThemeProvider>,
    );

    expect(
      screen.getByRole("heading", { level: 1, name: "lilradish" }),
    ).toBeInTheDocument();
    expect(screen.getByText("You are at the start.")).toBeInTheDocument();
  });

  it("is one part of the page, named by its heading and framed as a page's part", () => {
    render(
      <ThemeProvider theme={theme}>
        <Landing />
      </ThemeProvider>,
    );

    const part = screen.getByRole("region", { name: "lilradish" });

    expect(part).toContainElement(screen.getByText("You are at the start."));
    expect(drawnOf(part)).toEqual(drawnAtDepth(1));
    expect(screen.getAllByRole("region")).toEqual([part]);
  });

  /**
   * The one screen every reader may be on, so it is the one screen that must
   * not describe the others. A link or a word about somewhere else would reach
   * readers who hold nothing and tell them what they are being kept out of.
   */
  it("offers nowhere to go and names nothing else this system has", () => {
    render(
      <ThemeProvider theme={theme}>
        <Landing />
      </ThemeProvider>,
    );

    expect(screen.queryAllByRole("link")).toEqual([]);
    expect(
      screen.queryByText(/people|groups|soundness|measurements/i),
    ).toBeNull();
  });
});
