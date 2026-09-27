import { ThemeProvider } from "@mui/material/styles";
import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import type { Standing } from "../../api/standing";
import type { Resource } from "../../lib/request/useResource";
import { theme } from "../../lib/theme/theme";
import { noticesIn } from "../../testutil/notices";
import { answered, stillReading } from "../../testutil/standingRead";
import { RequireStanding } from "./RequireStanding";
import { StandingProvider } from "./StandingContext";

/** The rule of the act this gate asks for, and nothing about who met it. */
const REFUSED = "The pool is seen and changed only by a role that may keep it.";

const SCREEN = "Everybody this system knows";

function gated(read: Resource<Standing>) {
  return render(
    <ThemeProvider theme={theme}>
      <StandingProvider read={read}>
        <RequireStanding act="keep_pool">
          <p>{SCREEN}</p>
        </RequireStanding>
      </StandingProvider>
    </ThemeProvider>,
  );
}

function gate(standing: Iterable<string>) {
  return gated(answered(standing));
}

describe("RequireStanding", () => {
  it("renders the screen to a reader the server named its act for", () => {
    gate(["keep_pool"]);

    expect(screen.getByText(SCREEN)).toBeInTheDocument();
    expect(screen.queryByRole("alert")).toBeNull();
  });

  /**
   * Said where the reader is standing. Not a redirect, which takes the address
   * they followed away without telling them what happened to it, and not an
   * answer of "no such screen", which is a lie about a screen that is there.
   */
  it("says so in place to a reader it did not, and renders none of the screen", () => {
    gate(["check_soundness", "read_measurements"]);

    expect(screen.getByRole("alert")).toHaveTextContent(REFUSED);
    expect(noticesIn(document.body)).toEqual([
      { severity: "error", words: REFUSED },
    ]);
    expect(screen.queryByText(SCREEN)).toBeNull();
  });

  it("names the rule of the act it asks for, not of another", () => {
    render(
      <ThemeProvider theme={theme}>
        <StandingProvider read={answered(["keep_pool"])}>
          <RequireStanding act="read_measurements">
            <p>{SCREEN}</p>
          </RequireStanding>
        </StandingProvider>
      </ThemeProvider>,
    );

    expect(screen.getByRole("alert")).toHaveTextContent(
      "Measurements is read only by a role that may read what the estate measures.",
    );
    expect(screen.queryByText(REFUSED)).toBeNull();
  });

  it("refuses where the server named nothing at all", () => {
    gate([]);

    expect(screen.getByRole("alert")).toHaveTextContent(REFUSED);
    expect(screen.queryByText(SCREEN)).toBeNull();
  });

  /** An act published after this build cannot be what any gate here asks for. */
  it("refuses on an act this build does not know, however much is held", () => {
    gate(["commission_a_satellite", "decide_everything"]);

    expect(screen.getByRole("alert")).toHaveTextContent(REFUSED);
    expect(screen.queryByText(SCREEN)).toBeNull();
  });

  /**
   * The one distinction this gate has to draw, and the reason it takes the read
   * rather than the standing. A reader nothing has answered for holds nothing,
   * and refusing them would say a wall is there and then take it away a moment
   * later — which reads as a permission that arrived late, and is the shape a
   * real refusal gets mistaken for ever afterwards.
   */
  it("waits on a read still out rather than refusing on the nothing it holds so far", () => {
    gated(stillReading());

    expect(screen.getByText("Still reading…")).toBeInTheDocument();
    expect(screen.queryByRole("alert")).toBeNull();
    expect(screen.queryByText(SCREEN)).toBeNull();
  });

  /**
   * Read again once it has answered, the standing is decided on the last
   * answer until the next lands: a wait drawn over the screen would push it
   * down and take the control that asked away with it.
   */
  it("keeps the screen, and says nothing of a wait, while a standing that has answered is read again", () => {
    const { rerender } = gated(answered(["keep_pool"]));
    const drawn = screen.getByText(SCREEN);
    const status = screen.getByRole("status");

    const again = answered(["keep_pool"]);
    rerender(
      <ThemeProvider theme={theme}>
        <StandingProvider
          read={{ ...again, value: again.value, loading: true }}
        >
          <RequireStanding act="keep_pool">
            <p>{SCREEN}</p>
          </RequireStanding>
        </StandingProvider>
      </ThemeProvider>,
    );

    expect(screen.getByText(SCREEN)).toBe(drawn);
    expect(screen.getByRole("status")).toBe(status);
    expect(status.textContent).toBe("");
    expect(screen.queryByText("Still reading…")).toBeNull();
    expect(screen.queryByRole("alert")).toBeNull();
  });

  it("decides on the next answer once it lands, refusing where it no longer names the act", () => {
    const { rerender } = gated(answered(["keep_pool"]));

    rerender(
      <ThemeProvider theme={theme}>
        <StandingProvider read={answered([])}>
          <RequireStanding act="keep_pool">
            <p>{SCREEN}</p>
          </RequireStanding>
        </StandingProvider>
      </ThemeProvider>,
    );

    expect(screen.getByRole("alert")).toHaveTextContent(REFUSED);
    expect(screen.queryByText(SCREEN)).toBeNull();
  });
});
