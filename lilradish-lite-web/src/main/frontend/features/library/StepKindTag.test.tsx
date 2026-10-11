import { ThemeProvider } from "@mui/material/styles";
import { render, screen } from "@testing-library/react";
import { describe, expect, expectTypeOf, it } from "vitest";

import type { RunsKind } from "../../api/groups/{groupId}/{kind}/{entryId}/versions/{versionId}";

import { theme } from "../../lib/theme/theme";
import { resolved } from "../../testutil/grounds";
import { StepKindTag } from "./StepKindTag";

/**
 * Each kind's words, its colours as `theme.ts` writes them, copied by hand so a path nothing resolves shows, and the
 * icon drawn before them, by the name Material's icons are tested by.
 */
const KINDS = [
  ["question", "Question", "#5c5721", "#f3f2db", "QuestionAnswerIcon"],
  ["workflow", "Workflow", "#403c39", "#e8e6e3", "AccountTreeIcon"],
  ["code_step", "Code step", "#324b5a", "#e4edf2", "CodeIcon"],
  ["route", "Route", "#614232", "#f5eae5", "AltRouteIcon"],
  ["search", "Search", "#8a2c55", "#f8e6ee", "SearchIcon"],
] as const;

const UNKNOWN = "This page can't show this yet.";

function drawn(kind: string) {
  return render(
    <ThemeProvider theme={theme}>
      <StepKindTag kind={kind} />
    </ThemeProvider>,
  );
}

describe("StepKindTag", () => {
  /** A kind coloured and never drawn, or drawn with no colour, would be a declaration nothing reads. */
  it("sets apart every wire kind with a canonical pair", () => {
    expectTypeOf<(typeof KINDS)[number][0]>().toEqualTypeOf<RunsKind>();
    expect(theme.palette.kind).toMatchObject({
      question: { text: "#5c5721", ground: "#f3f2db" },
      workflow: { text: "#403c39", ground: "#e8e6e3" },
      codeStep: { text: "#324b5a", ground: "#e4edf2" },
      route: { text: "#614232", ground: "#f5eae5" },
      search: { text: "#8a2c55", ground: "#f8e6ee" },
    });
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
