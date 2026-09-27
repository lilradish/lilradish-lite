import { ThemeProvider } from "@mui/material/styles";
import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import { theme } from "../lib/theme/theme";
import { SystemPage } from "./SystemPage";

function shown(title: "destination.soundness" | "destination.measurements") {
  return render(
    <ThemeProvider theme={theme}>
      <SystemPage title={title} />
    </ThemeProvider>,
  );
}

describe("SystemPage", () => {
  /**
   * The screen's heading is the destination's own words, which is what keeps
   * the entry in the sidebar and the screen it leads to from coming to be
   * named differently — and what a reader uses to know they arrived.
   */
  it.each([
    ["destination.soundness" as const, "Soundness"],
    ["destination.measurements" as const, "Measurements"],
  ])(
    "heads the screen with the words its destination is offered under",
    (title, words) => {
      shown(title);

      expect(
        screen.getByRole("heading", { level: 1, name: words }),
      ).toBeInTheDocument();
    },
  );

  /**
   * Said rather than left blank. A screen holding nothing and a screen whose
   * content failed to arrive look the same to a reader, and only one of them is
   * worth waiting on or asking about.
   */
  it("says the screen holds nothing yet, rather than standing empty", () => {
    shown("destination.soundness");

    expect(screen.getByText("Nothing is built here yet.")).toBeInTheDocument();

    // Not a failure: nothing was refused and nothing is retryable here.
    expect(screen.queryByRole("alert")).toBeNull();
  });
});
