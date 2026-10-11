import Box from "@mui/material/Box";
import { ThemeProvider } from "@mui/material/styles";
import { render, screen, within } from "@testing-library/react";
import type { ReactNode } from "react";
import { describe, expect, it } from "vitest";

import { BLOCK_SX, HEADING_SX } from "../../../lib/layout/parts";
import { theme } from "../../../lib/theme/theme";
import { drawnOf } from "../../../testutil/depths";
import { resolved } from "../../../testutil/grounds";
import { Message } from "./Message";

/** The room under a heading as `parts.ts` writes it, in pixels as a style reports it back. */
const UNDER_A_HEADING = theme.spacing(HEADING_SX.mb);

/** Each kind's edge and words' colour as `theme.ts` writes them, copied by hand so a path nothing resolves shows. */
const KIND_COLOURS = [
  ["question", "#8f8c62", "#5c5721"],
  ["workflow", "#8e8a87", "#403c39"],
  ["code_step", "#7a8d98", "#324b5a"],
  ["route", "#9e867a", "#614232"],
  ["search", "#ba7890", "#8a2c55"],
] as const;

function themed({ children }: { readonly children: ReactNode }) {
  return <ThemeProvider theme={theme}>{children}</ThemeProvider>;
}

function drawn(message: ReactNode) {
  render(<ol>{message}</ol>, { wrapper: themed });
  return screen.getByRole("listitem");
}

function drawnAsBlock() {
  const probe = render(
    <ThemeProvider theme={theme}>
      <Box sx={BLOCK_SX} />
    </ThemeProvider>,
  );
  const drawn = drawnOf(probe.container.firstElementChild!);
  probe.unmount();
  return drawn;
}

describe("Message", () => {
  it.each([
    ["an anchor finds it", "step-1", "-1"],
    ["nothing finds it", undefined, null],
  ] as const)(
    "is an item of the conversation named by its title, taking the keyboard only where %s",
    (_case, anchor, tabIndex) => {
      const item = drawn(
        <Message anchor={anchor} title="Summarise">
          <p>Running</p>
        </Message>,
      );

      expect(item.tagName).toBe("LI");
      expect(item).toHaveAccessibleName("Summarise");
      expect(item.getAttribute("id")).toBe(anchor ?? null);
      expect(item.getAttribute("tabindex")).toBe(tabIndex);
      expect(getComputedStyle(item).scrollMarginTop).toBe(
        anchor === undefined ? "0" : "64px",
      );
    },
  );

  it("says the kind a step runs beside its title on one line above the rest, no part of the title", () => {
    const item = drawn(
      <Message title="Summarise" kind="question">
        <p>Running</p>
      </Message>,
    );

    const title = within(item).getByRole("heading", { level: 2 });
    const head = title.parentElement!;

    expect(head.parentElement).toBe(item);
    expect(head.firstElementChild).toBe(title);
    expect(title.nextElementSibling?.textContent).toBe("Question");
    expect(head.children).toHaveLength(2);
    expect(title).toHaveAccessibleName("Summarise");
    expect(getComputedStyle(head).display).toBe("flex");
    expect(getComputedStyle(head).alignItems).toBe("center");
    expect(getComputedStyle(head).marginBottom).toBe(UNDER_A_HEADING);
    expect(head.nextElementSibling?.textContent).toBe("Running");
    expect(head).not.toHaveTextContent("Running");
  });

  it("keeps the step's title line to its identity while its details belong below", () => {
    const item = drawn(
      <Message title="Summarise" kind="question">
        <p>Running</p>
      </Message>,
    );

    const title = within(item).getByRole("heading", { level: 2 });
    const head = title.parentElement!;

    expect(head.lastElementChild?.textContent).toBe("Question");
    expect(head.children).toHaveLength(2);
    expect(head).not.toHaveTextContent("1,500");
    expect(title).toHaveAccessibleName("Summarise");
    expect(item).toHaveAccessibleName("Summarise");
    expect(title.parentElement!.nextElementSibling?.textContent).toBe(
      "Running",
    );
  });

  it("says nothing beside the title of a message that is no step's", () => {
    const item = drawn(
      <Message title="What it was started with">
        <p>Avo Lumerace started it</p>
      </Message>,
    );

    const title = within(item).getByRole("heading", { level: 2 });

    expect(title.parentElement!.children).toHaveLength(1);
    expect(getComputedStyle(title.parentElement!).marginBottom).toBe(
      UNDER_A_HEADING,
    );
  });

  it("heads a message at the second level, as a part of the run's page", () => {
    const item = drawn(
      <Message title="Summarise" kind="question">
        <p>Running</p>
      </Message>,
    );

    expect(within(item).getAllByRole("heading")).toEqual([
      within(item).getByRole("heading", { level: 2, name: "Summarise" }),
    ]);
    expect(within(item).queryByRole("heading", { level: 3 })).toBeNull();
  });

  it("sets a message the reader made apart on the trailing side with the shared width limit and its own ground, its frame and edge as any other's", () => {
    const item = drawn(
      <Message title="What it was started with" byTheReader={true}>
        <p>Avo Lumerace started it, running the workflow Handle a claim.</p>
      </Message>,
    );

    const drawnAs = getComputedStyle(item);

    expect(drawnAs.marginInlineStart).toBe("auto");
    expect(drawnAs.maxWidth).toBe("960px");
    expect(drawnAs.backgroundColor).toBe(
      resolved(theme.palette.background.paper),
    );
    expect(drawnOf(item)).toEqual(drawnAsBlock());
    expect([
      drawnOf(item).padding,
      drawnAs.paddingRight,
      drawnAs.getPropertyValue("--card-side-padding"),
    ]).toEqual(["16px 20px", "20px", "20px"]);
    expect(item).toHaveAccessibleName("What it was started with");
  });

  it.each([true, false, undefined])(
    "keeps the same conversation width limit in every declared viewport rule when reader identity is %s",
    (byTheReader) => {
      const item = drawn(
        <Message title="Summarise" byTheReader={byTheReader}>
          <p>One short value.</p>
        </Message>,
      );
      const limits: string[] = [];
      const widths: string[] = [];
      const responsiveWidths: string[] = [];
      const visit = (rules: CSSRuleList, condition = "") => {
        for (const rule of rules) {
          if (rule instanceof CSSStyleRule) {
            const limit = rule.style.getPropertyValue("max-width");
            if (limit !== "" && item.matches(rule.selectorText))
              limits.push(limit);
            const width = rule.style.getPropertyValue("width");
            if (width !== "" && item.matches(rule.selectorText)) {
              widths.push(width);
              responsiveWidths.push(
                `${condition.replaceAll(" ", "")}:${width}`,
              );
            }
          }
          if (rule instanceof CSSMediaRule)
            visit(rule.cssRules, rule.conditionText);
          else if ("cssRules" in rule)
            visit(rule.cssRules as CSSRuleList, condition);
        }
      };
      for (const sheet of document.styleSheets) visit(sheet.cssRules);

      expect(new Set(limits)).toEqual(new Set(["960px"]));
      expect(new Set(widths)).toEqual(new Set(["95%", "90%", "85%"]));
      expect(new Set(responsiveWidths)).toEqual(
        new Set([
          "(min-width:0px):95%",
          "(min-width:600px):90%",
          "(min-width:900px):85%",
        ]),
      );
      const drawnAs = getComputedStyle(item);
      expect(drawnAs.maxWidth).toBe("960px");
      expect(drawnAs.marginInlineStart === "auto").toBe(byTheReader === true);
      expect(drawnAs.marginInlineEnd === "auto").toBe(byTheReader !== true);
    },
  );

  it.each([
    ["no reader is said to have made it", undefined],
    ["the reader did not make it", false],
  ] as const)(
    "leaves a message on the leading side with the shared width limit and leading side's light ground, where %s",
    (_case, byTheReader) => {
      const item = drawn(
        <Message title="What it was started with" byTheReader={byTheReader}>
          <p>Glinn Havven started it, running the workflow Handle a claim.</p>
        </Message>,
      );

      const drawnAs = getComputedStyle(item);

      expect(drawnAs.marginInlineStart).not.toBe("auto");
      expect(drawnAs.maxWidth).toBe("960px");
      expect(drawnAs.backgroundColor).toBe(
        resolved(theme.palette.surface.given),
      );
      expect(drawnOf(item)).toEqual({
        ...drawnAsBlock(),
        ground: resolved(theme.palette.surface.given),
      });
    },
  );

  /** Colours as `theme.ts` writes them, copied by hand so a path nothing resolves shows. */
  it.each([
    ["done", "#24683a"],
    ["failed", "#a1261f"],
    ["stopped", "#5a656b"],
  ] as const)(
    "edges the run's last message, ended %s, all round and thicker than any other, in that ending's colour",
    (ending, colour) => {
      const item = drawn(
        <Message title="Done" ending={ending}>
          <p>What it gave back</p>
        </Message>,
      );

      const drawnAs = getComputedStyle(item);
      const other = drawnAsBlock();
      const sides = ["Top", "Right", "Bottom", "Left"] as const;

      expect(sides.map((side) => drawnAs[`border${side}Width`])).toEqual(
        Array(4).fill("2px"),
      );
      expect(sides.map((side) => drawnAs[`border${side}Color`])).toEqual(
        Array(4).fill(resolved(colour)),
      );
      expect(drawnOf(item).topEdge).not.toBe(other.topEdge);
      expect(drawnOf(item).leadingEdgeColour).not.toBe(other.leadingEdgeColour);
      expect(item).toHaveAccessibleName("Done");
    },
  );

  it("frames a message that is no step's as a block of the page, with no edge of its own", () => {
    const item = drawn(
      <Message title="What it was started with">
        <p>Avo Lumerace started it</p>
      </Message>,
    );

    expect(drawnOf(item)).toEqual({
      ...drawnAsBlock(),
      ground: resolved(theme.palette.surface.given),
    });
  });

  it.each(KIND_COLOURS)(
    "rules a message of a step that runs %s down its leading edge in that kind's edge, not its words' colour nor another kind's, framed otherwise as a block",
    (kind, colour, words) => {
      const item = drawn(
        <Message title="Summarise" kind={kind}>
          <p>Running</p>
        </Message>,
      );

      const others = KIND_COLOURS.filter(([other]) => other !== kind).map(
        ([, other]) => resolved(other),
      );

      expect(drawnOf(item)).toEqual({
        ...drawnAsBlock(),
        ground: resolved(theme.palette.surface.given),
        leadingEdge: "3px solid",
        leadingEdgeColour: resolved(colour),
      });
      expect(others).not.toContain(drawnOf(item).leadingEdgeColour);
      expect(drawnOf(item).leadingEdgeColour).not.toBe(resolved(words));
    },
  );

  /** Colour never says a kind alone: one this build has no words for is said so, and nothing is ruled in its name. */
  it("frames a message of a step running a kind it has no words for as a block, saying so beside the title", () => {
    const item = drawn(
      <Message title="Find it" kind="unmapped_kind">
        <p>Running</p>
      </Message>,
    );

    const title = within(item).getByRole("heading", { level: 2 });

    expect(drawnOf(item)).toEqual({
      ...drawnAsBlock(),
      ground: resolved(theme.palette.surface.given),
    });
    expect(title.nextSibling?.textContent).toBe(
      "This page can't show this yet.",
    );
    expect(item).not.toHaveTextContent(/unmapped_kind/i);
  });
});
