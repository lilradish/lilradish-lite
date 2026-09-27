import { ThemeProvider } from "@mui/material/styles";
import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import { theme } from "../lib/theme/theme";
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
    expect(screen.getByText("You are signed in.")).toBeInTheDocument();
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
