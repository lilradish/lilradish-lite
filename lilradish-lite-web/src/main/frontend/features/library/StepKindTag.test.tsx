import { ThemeProvider } from "@mui/material/styles";
import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import { theme } from "../../lib/theme/theme";
import { resolved } from "../../testutil/grounds";
import { StepKindTag } from "./StepKindTag";

/**
 * Each kind's words, its colours as `theme.ts` writes them, copied by hand so a path nothing resolves shows, and the
 * icon drawn before them, by the name Material's icons are tested by.
 */
const KINDS = [
  ["question", "Question", "#00695c", "#e0f2f1", "QuestionAnswerIcon"],
  ["workflow", "Workflow", "#283593", "#e8eaf6", "AccountTreeIcon"],
  ["code_step", "Code step", "#37474f", "#eceff1", "CodeIcon"],
  ["route", "Route", "#5d4037", "#efebe9", "AltRouteIcon"],
  ["search", "Search", "#6a1b9a", "#f3e5f5", "SearchIcon"],
] as const;

const UNKNOWN = "Not something this page can say yet.";

function drawn(kind: string) {
  return render(
    <ThemeProvider theme={theme}>
      <StepKindTag kind={kind} />
    </ThemeProvider>,
  );
}

describe("StepKindTag", () => {
  /** A kind coloured and never drawn, or drawn with no colour, would be a declaration nothing reads. */
  it("sets apart exactly the kinds the theme colours", () => {
    expect(Object.keys(theme.palette.stepKind)).toEqual(
      KINDS.map(([kind]) => kind),
    );
  });

  it.each(KINDS)(
    "sets a step that runs %s apart as %s, in its own colour on its own ground and in no other kind's",
    (kind, words, colour, ground) => {
      drawn(kind);

      const tag = screen.getByText(words);
      const style = getComputedStyle(tag);
      const others = KINDS.filter(([other]) => other !== kind);

      expect(tag.textContent).toBe(words);
      expect(style.color).toBe(resolved(colour));
      expect(style.backgroundColor).toBe(resolved(ground));
      expect(others.map(([, , other]) => resolved(other))).not.toContain(
        style.color,
      );
      expect(others.map(([, , , other]) => resolved(other))).not.toContain(
        style.backgroundColor,
      );
    },
  );

  /** The tag's type as `theme.ts` writes it, copied by hand and in pixels as a style reports it back. */
  it("sets the words naming a kind in the tag's type", () => {
    drawn("question");

    const tag = getComputedStyle(screen.getByText("Question"));

    expect([tag.fontWeight, tag.fontSize]).toEqual(["700", "12px"]);
  });

  it.each(KINDS)(
    "draws its own icon before the words naming a step that runs %s, hidden from reading and in the words' colour and size",
    (kind, words, colour, _ground, icon) => {
      drawn(kind);

      const tag = screen.getByText(words);
      const drawnIcon = tag.firstElementChild!;
      const others = KINDS.filter(([other]) => other !== kind);

      expect(drawnIcon.tagName).toBe("svg");
      expect(drawnIcon.getAttribute("data-testid")).toBe(icon);
      expect(others.map(([, , , , other]) => other)).not.toContain(icon);
      expect(drawnIcon.getAttribute("aria-hidden")).toBe("true");
      expect(drawnIcon.textContent).toBe("");
      expect(drawnIcon.nextSibling?.textContent).toBe(words);
      expect(getComputedStyle(drawnIcon).flexShrink).toBe("0");
      expect(getComputedStyle(drawnIcon).color).toBe(resolved(colour));
      expect(getComputedStyle(drawnIcon).fontSize).toBe(
        getComputedStyle(tag).fontSize,
      );
    },
  );

  /** Colour never says a kind alone: one it has no words for is said so, with no ground, no icon and no kind's colour. */
  it.each(["paused", "none", "", "toString"])(
    "says plainly that it cannot say a kind it has no words for: %j",
    (kind) => {
      const { container } = drawn(kind);

      expect(container.textContent).toBe(UNKNOWN);
      expect(container.firstElementChild).toBeNull();
    },
  );
});
