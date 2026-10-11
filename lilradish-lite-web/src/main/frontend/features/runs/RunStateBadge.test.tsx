import { ThemeProvider } from "@mui/material/styles";
import { render } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import { isolatedInText } from "../../lib/direction/isolated";
import { theme } from "../../lib/theme/theme";
import { resolved } from "../../testutil/grounds";
import { RunStateBadge } from "./RunStateBadge";
import { stateSaid } from "./runStates";

const STATES = [
  ["running", "Running", "#1f4f99", "AutorenewIcon"],
  ["stopped", "Stopped", "#5a656b", "PauseCircleOutlinedIcon"],
  ["failed", "Failed", "#a1261f", "ErrorOutlinedIcon"],
  ["done", "Done", "#24683a", "CheckCircleIcon"],
] as const;

describe("RunStateBadge", () => {
  it("uses the historical step display name while leaving its machine key separate", () => {
    const { container, rerender } = render(
      <ThemeProvider theme={theme}>
        <RunStateBadge
          state="running"
          at="file_claim"
          atLabel={"請求を確認\u202e"}
        />
      </ThemeProvider>,
    );

    expect(container.textContent).toBe(
      `RunningNow at ${isolatedInText(String.fromCodePoint(0x8acb, 0x6c42, 0x3092, 0x78ba, 0x8a8d) + "\u202e")}`,
    );
    expect(container.querySelector("bdi")).toHaveTextContent(
      isolatedInText("請求を確認\u202e"),
    );
    expect(container).not.toHaveTextContent("file claim");

    rerender(
      <ThemeProvider theme={theme}>
        <RunStateBadge state="running" at="file_claim" />
      </ThemeProvider>,
    );
    expect(container.textContent).toBe(
      `RunningNow at ${isolatedInText("file claim")}`,
    );
    expect(container).not.toHaveTextContent("請求を確認");
  });
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
    const step = String.fromCodePoint(0x0645, 0x0631, 0x062d, 0x0644, 0x0629);
    const badge = container.querySelector("span")!;
    const nowAt = badge.nextElementSibling!;

    expect(badge.textContent).toBe("Running");
    expect(nowAt.textContent).toBe(`Now at ${isolatedInText(step)}`);
    expect(nowAt.querySelector("bdi")).toHaveTextContent(isolatedInText(step));
    expect(badge.querySelector("bdi")).toBeNull();
    expect([
      getComputedStyle(nowAt).display,
      getComputedStyle(nowAt).color,
      getComputedStyle(nowAt).overflowWrap,
      getComputedStyle(nowAt).marginTop,
      getComputedStyle(nowAt).fontSize,
      getComputedStyle(nowAt).lineHeight,
    ]).toEqual([
      "block",
      "rgb(88, 102, 108)",
      "anywhere",
      "2px",
      "13px",
      "20px",
    ]);
    expect(badge.querySelector("svg")).toHaveAttribute(
      "data-testid",
      "AutorenewIcon",
    );

    rerender(
      <ThemeProvider theme={theme}>
        <RunStateBadge state="done" at="مرحلة" />
      </ThemeProvider>,
    );
    expect(badge.textContent).toBe("Done");
    expect(container).not.toHaveTextContent(step);
    expect(badge.nextElementSibling).toBeNull();
    expect(badge.querySelector("bdi")).toBeNull();

    rerender(
      <ThemeProvider theme={theme}>
        <RunStateBadge state="unlisted" at="مرحلة" />
      </ThemeProvider>,
    );
    expect(container).toHaveTextContent(
      "This page can't show this status yet.",
    );
    expect(container).not.toHaveTextContent("مرحلة");
  });

  it("says an unknown state without a colour or icon of another state", () => {
    const { container } = render(
      <ThemeProvider theme={theme}>
        <RunStateBadge state="unlisted" />
      </ThemeProvider>,
    );
    expect(container).toHaveTextContent(
      "This page can't show this status yet.",
    );
    expect(container.querySelector("svg")).toBeNull();
    expect(container.querySelector("span")).toBeNull();
    expect(stateSaid("unlisted")).toBe("This page can't show this status yet.");
  });
});
