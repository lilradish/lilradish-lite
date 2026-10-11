import { ThemeProvider } from "@mui/material/styles";
import { render } from "@testing-library/react";
import { describe, expect, expectTypeOf, it } from "vitest";

import type { StepState } from "../../../api/stepStates";

import { theme } from "../../../lib/theme/theme";
import { resolved } from "../../../testutil/grounds";
import { StepStateBadge } from "./StepStateBadge";

/**
 * Each state's words, its colour as `theme.ts` writes it, copied by hand so a path nothing resolves shows, and the
 * icon drawn before them, by the name Material's icons are tested by.
 */
const STATES = [
  ["not_started", "Not started", "#444f54", "RadioButtonUncheckedIcon"],
  ["running", "Running", "#1f4f99", "AutorenewIcon"],
  ["held_back", "Blocked", "#3a4449", "PauseCircleOutlinedIcon"],
  ["waiting", "Waiting", "#8a4700", "HourglassEmptyIcon"],
  ["failed", "Failed", "#a1261f", "ErrorOutlinedIcon"],
  ["done", "Done", "#24683a", "CheckCircleIcon"],
] as const;

const UNKNOWN = "This page can't show this yet.";

function drawn(state: string) {
  return render(
    <ThemeProvider theme={theme}>
      <StepStateBadge state={state} />
    </ThemeProvider>,
  );
}

describe("StepStateBadge", () => {
  it("covers every wire state with its canonical text and ground", () => {
    expectTypeOf<(typeof STATES)[number][0]>().toEqualTypeOf<StepState>();
    expect(theme.palette.state).toMatchObject({
      notStarted: { text: "#444f54", ground: "#ffffff", edge: "#a9b6bd" },
      running: { text: "#1f4f99", ground: "#e3ebf7" },
      blocked: { text: "#3a4449", ground: "#ffffff", edge: "#a9b6bd" },
      waiting: { text: "#8a4700", ground: "#fbeedb" },
      failed: { text: "#a1261f", ground: "#fbe7e5" },
      done: { text: "#24683a", ground: "#e1f0e4" },
    });
  });

  it.each(STATES)(
    "says a step %s as %s, its words and an icon before them in its colour, and in no other state's colour or icon",
    (state, words, colour, icon) => {
      const { container } = drawn(state);

      const badge = container.firstElementChild as HTMLElement;
      const drawnIcon = badge.firstElementChild!;
      const others = STATES.filter(([other]) => other !== state);

      expect(badge.textContent).toBe(words);
      expect(getComputedStyle(badge).color).toBe(resolved(colour));
      expect(
        others
          .filter(([, , other]) => other !== colour)
          .map(([, , other]) => resolved(other)),
      ).not.toContain(getComputedStyle(badge).color);
      expect(drawnIcon.tagName).toBe("svg");
      expect(drawnIcon.getAttribute("data-testid")).toBe(icon);
      expect(others.map(([, , , other]) => other)).not.toContain(icon);
      expect(drawnIcon.getAttribute("aria-hidden")).toBe("true");
      expect(drawnIcon.textContent).toBe("");
      expect(getComputedStyle(drawnIcon).flexShrink).toBe("0");
      expect(getComputedStyle(drawnIcon).color).toBe(resolved(colour));
      expect(getComputedStyle(drawnIcon).fontSize).toBe(
        getComputedStyle(badge).fontSize,
      );
    },
  );

  /** Colour never says a state alone: a state it has no words for is said so, with no icon and no state's colour. */
  it.each(["paused", "", "toString"])(
    "says plainly that it cannot say a state it has no words for: %j",
    (state) => {
      const { container } = drawn(state);

      expect(container.textContent).toBe(UNKNOWN);
      expect(container.firstElementChild).toBeNull();
    },
  );
});
