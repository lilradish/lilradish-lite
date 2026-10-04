import { ThemeProvider } from "@mui/material/styles";
import { render } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import { theme } from "../../../lib/theme/theme";
import { resolved } from "../../../testutil/grounds";
import { StepStateBadge } from "./StepStateBadge";

/**
 * Each state's words, its colour as `theme.ts` writes it, copied by hand so a path nothing resolves shows, and the
 * icon drawn before them, by the name Material's icons are tested by.
 */
const STATES = [
  ["not_started", "Not started", "#595959", "RadioButtonUncheckedIcon"],
  ["running", "Running", "#01579b", "AutorenewIcon"],
  ["held_back", "Held back", "#595959", "PauseCircleOutlinedIcon"],
  ["waiting", "Waiting", "#9a4f00", "HourglassEmptyIcon"],
  ["failed", "Failed", "#8c1d18", "ErrorOutlinedIcon"],
  ["done", "Done", "#1b5e20", "CheckCircleIcon"],
] as const;

const UNKNOWN = "Not something this page can say yet.";

function drawn(state: string) {
  return render(
    <ThemeProvider theme={theme}>
      <StepStateBadge state={state} />
    </ThemeProvider>,
  );
}

describe("StepStateBadge", () => {
  it("colours every state the theme colours, and no other", () => {
    expect(Object.keys(theme.palette.stepState)).toEqual(
      STATES.map(([state]) => state),
    );
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
