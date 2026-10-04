import { ThemeProvider } from "@mui/material/styles";
import { render } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import { isolatedInText } from "../../lib/direction/isolated";
import { theme } from "../../lib/theme/theme";
import { resolved } from "../../testutil/grounds";
import { RunStateBadge } from "./RunStateBadge";
import { stateSaid } from "./runStates";

const STATES = [
  ["running", "Running", "#0277bd", "AutorenewIcon"],
  ["stopped", "Stopped", "#595959", "PauseCircleOutlinedIcon"],
  ["failed", "Failed", "#8c1d18", "ErrorOutlinedIcon"],
  ["done", "Done", "#1b5e20", "CheckCircleIcon"],
] as const;

describe("RunStateBadge", () => {
  it.each(STATES)(
    "draws %s with its words, colour and icon",
    (state, words, colour, icon) => {
      const { container } = render(
        <ThemeProvider theme={theme}>
          <RunStateBadge state={state} />
        </ThemeProvider>,
      );
      const badge = container.firstElementChild as HTMLElement;
      const ornament = badge.firstElementChild;

      expect(badge.textContent).toBe(words);
      expect(stateSaid(state)).toBe(words);
      expect(getComputedStyle(badge).color).toBe(resolved(colour));
      expect(ornament).toHaveAttribute("data-testid", icon);
      expect(ornament).toHaveAttribute("aria-hidden", "true");
    },
  );

  it("names the current step only for a running run", () => {
    const { container, rerender } = render(
      <ThemeProvider theme={theme}>
        <RunStateBadge state="running" at="مرحلة" />
      </ThemeProvider>,
    );
    const badge = container.querySelector("span")!;

    expect(badge).toHaveTextContent(`Running, on ${isolatedInText("مرحلة")}`);
    expect(badge.querySelector("bdi")).toHaveTextContent(
      isolatedInText("مرحلة"),
    );
    const hint = [...badge.querySelectorAll("span")].find(
      (part) => part.textContent === "Running, on",
    );
    expect(hint).toBeDefined();
    expect(getComputedStyle(hint!).fontStyle).toBe("italic");
    expect(badge.querySelector("svg")).toHaveAttribute(
      "data-testid",
      "AutorenewIcon",
    );

    rerender(
      <ThemeProvider theme={theme}>
        <RunStateBadge state="done" at="مرحلة" />
      </ThemeProvider>,
    );
    expect(badge).toHaveTextContent("Done");
    expect(badge).not.toHaveTextContent("مرحلة");
    expect(badge.querySelector("bdi")).toBeNull();

    rerender(
      <ThemeProvider theme={theme}>
        <RunStateBadge state="unlisted" at="مرحلة" />
      </ThemeProvider>,
    );
    expect(container).toHaveTextContent("Not something this page can say yet.");
    expect(container).not.toHaveTextContent("مرحلة");
  });

  it("says an unknown state without a colour or icon of another state", () => {
    const { container } = render(
      <ThemeProvider theme={theme}>
        <RunStateBadge state="unlisted" />
      </ThemeProvider>,
    );
    expect(container).toHaveTextContent("Not something this page can say yet.");
    expect(container.querySelector("svg")).toBeNull();
    expect(container.querySelector("span")).toBeNull();
    expect(stateSaid("unlisted")).toBe("Not something this page can say yet.");
  });
});
