import { ThemeProvider } from "@mui/material/styles";
import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import { theme } from "../theme/theme";
import { styledClassOf, writtenRules } from "../../testutil/writtenRules";
import { TwoColumns } from "./TwoColumns";

function drawing(wideAtLeast = 600, stickyNarrow = false) {
  render(
    <ThemeProvider theme={theme}>
      <TwoColumns
        wideAtLeast={wideAtLeast}
        stickyNarrow={stickyNarrow}
        wide={<p>What it holds</p>}
        narrow={<p>What is about it</p>}
      />
    </ThemeProvider>,
  );
  const wide = screen.getByText("What it holds").parentElement!;
  const narrow = screen.getByText("What is about it").parentElement!;
  const grid = wide.parentElement!;
  return { wide, narrow, grid, frame: grid.parentElement! };
}

/** jsdom lays nothing out, so where each column lands is read from the order drawn and the rules written for it. */
describe("TwoColumns", () => {
  it("draws the wide part first and the narrow part after it, each a column of its own", () => {
    const { wide, narrow, grid } = drawing();

    expect([...grid.children]).toEqual([wide, narrow]);
    expect(wide).not.toContainElement(narrow);
    expect(narrow).not.toContainElement(wide);
  });

  it.each([
    [600, 944],
    [968, 1312],
  ])(
    "stacks the narrow column under a wide one that keeps %ipx, standing it beside at 320px only once the frame itself is %ipx wide",
    (wideAtLeast, beside) => {
      const { grid, frame } = drawing(wideAtLeast);
      const written = writtenRules();

      expect(written).toContain(
        `.${styledClassOf(frame)}{container-type:inline-size;}`,
      );
      expect(written).toContain(
        `.${styledClassOf(grid)}{display:grid;grid-template-columns:minmax(0, 1fr);gap:24px;`,
      );
      expect(written).toContain(
        `@container (min-width: ${beside}px){.${styledClassOf(grid)}{grid-template-columns:minmax(0, 1fr) 320px;`,
      );
      expect(written).not.toMatch(
        new RegExp(`@media[^{]*\\{\\.${styledClassOf(grid)}\\{`),
      );
    },
  );

  it("stretches only the wide-screen narrow column when asked to hold an index in view", () => {
    const { grid } = drawing(600, true);
    const written = writtenRules();

    expect(written).toContain(
      `@container (min-width: 944px){.${styledClassOf(grid)}{grid-template-columns:minmax(0, 1fr) 320px;`,
    );
    expect(written).toContain(
      `.${styledClassOf(grid)}>:last-child{-webkit-align-self:stretch;-ms-flex-item-align:stretch;align-self:stretch;`,
    );
  });

  it("keeps no room above what either column opens with, so their tops are level", () => {
    const { grid } = drawing();

    expect(writtenRules()).toMatch(
      new RegExp(`\\.${styledClassOf(grid)}>\\*>:first-child\\{margin-top:0`),
    );
  });
});
