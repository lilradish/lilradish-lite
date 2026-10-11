import { ThemeProvider } from "@mui/material/styles";
import { render, screen, waitFor } from "@testing-library/react";
import { userEvent } from "@testing-library/user-event";
import { describe, expect, it, onTestFinished } from "vitest";

import { laidOutAt } from "../../testutil/layout";
import {
  styledClassOf,
  styleRulesOf,
  writtenRules,
} from "../../testutil/writtenRules";
import { theme } from "../theme/theme";
import { TwoColumns } from "./TwoColumns";

function drawing(frame: number, wideAtLeast = 600, stickyNarrow = false) {
  const kept = localStorage.getItem("lilradish.panel.about");
  localStorage.removeItem("lilradish.panel.about");
  onTestFinished(() => {
    if (kept === null) localStorage.removeItem("lilradish.panel.about");
    else localStorage.setItem("lilradish.panel.about", kept);
  });
  const resize = laidOutAt(frame, 1600);
  render(
    <ThemeProvider theme={theme}>
      <TwoColumns
        panelTitle="About it"
        wideAtLeast={wideAtLeast}
        stickyNarrow={stickyNarrow}
        wide={<p>What it holds</p>}
        narrow={<p>What is about it</p>}
      />
    </ThemeProvider>,
  );
  return resize;
}

function beside(): HTMLElement | null {
  return screen.queryByRole("region", { name: "About it" });
}

/** jsdom lays nothing out, so each frame width is handed to the panel's own measuring. */
describe("TwoColumns", () => {
  it("draws the wide part first and the narrow part after it, each a column of its own", async () => {
    drawing(1400);

    const narrow = await screen.findByRole("region", { name: "About it" });
    const wide = narrow.previousElementSibling as HTMLElement;

    expect(wide).toContainElement(screen.getByText("What it holds"));
    expect(narrow).toContainElement(screen.getByText("What is about it"));
    expect(wide).not.toContainElement(narrow);
    expect(narrow).not.toContainElement(wide);
  });

  it.each([
    [600, 1024, 1024],
    [968, 1376, 1352],
  ])(
    "folds the narrow column to a strip across where the wide one would keep less than %ipx, standing it beside at 360px once the frame is %ipx wide, and keeping it there down to %ipx",
    async (wideAtLeast, standsFrom, staysTo) => {
      const resize = drawing(standsFrom - 1, wideAtLeast);
      const strip = await screen.findByRole("button", {
        name: "About it",
        expanded: false,
      });
      expect(beside()).toBeNull();
      expect(strip.textContent).toBe("About it");

      await resize(standsFrom);
      await waitFor(() => expect(beside()).not.toBeNull());
      expect(getComputedStyle(beside()!).width).toBe("56px");
      await userEvent.click(
        screen.getByRole("button", { name: "About it", expanded: false }),
      );
      expect(getComputedStyle(beside()!).width).toBe("360px");
      await resize(staysTo);
      expect(beside()).not.toBeNull();
      await resize(staysTo - 1);
      await waitFor(() => expect(beside()).toBeNull());
    },
  );

  it.each([false, true])(
    "draws nothing of the narrow column folded beside the wide one but its strip, held in view %s, and all of it at 360px once unfolded",
    async (stickyNarrow) => {
      const resize = drawing(1023, 600, stickyNarrow);
      await screen.findByRole("button", { name: "About it", expanded: false });
      await resize(1024);
      await waitFor(() => expect(beside()).not.toBeNull());
      const fold = screen.getByRole("button", {
        name: "About it",
        expanded: false,
      });
      const held = document.getElementById(
        fold.getAttribute("aria-controls")!,
      )!;

      expect(getComputedStyle(beside()!).width).toBe("56px");
      expect(getComputedStyle(held).display).toBe("none");
      expect(held).toContainElement(screen.getByText("What is about it"));

      await userEvent.click(fold);

      expect(getComputedStyle(beside()!).width).toBe("360px");
      expect(getComputedStyle(held).display).toBe("block");
      expect(held).toContainElement(screen.getByText("What is about it"));
    },
  );

  it("keeps the beside column asked to hold an index in view stuck under the bar across the top, as tall as what it holds up to the screen less its margins, scrolling inside past that, and stretched to nothing", async () => {
    drawing(1400, 600, true);
    const held = await screen.findByRole("region", { name: "About it" });
    const style = getComputedStyle(held);

    expect([
      style.position,
      style.top,
      style.maxHeight,
      style.overflowY,
    ]).toEqual(["sticky", "64px", "calc(100dvh - 80px)", "auto"]);
    expect(style.alignSelf).not.toBe("stretch");
    expect(writtenRules()).not.toContain(`.${styledClassOf(held)}>*>[id]`);
  });

  /** jsdom answers no media query but print and screen, so the rule written for a short screen is read as written. */
  it("lets a beside column held in view go with the page, unstuck and unlimited, on a screen less tall than 400", async () => {
    drawing(1400, 600, true);
    const held = await screen.findByRole("region", { name: "About it" });

    expect(writtenRules()).toContain(
      `@media (height < 400px){.${styledClassOf(held)}{position:static;max-height:none;overflow-y:visible;overflow-x:clip;}`,
    );
  });

  it("keeps a beside column not asked to hold an index in view where the page puts it, at any height", async () => {
    drawing(1400);
    const beside = await screen.findByRole("region", { name: "About it" });
    const style = getComputedStyle(beside);

    expect([style.position, style.maxHeight, style.overflowY]).toEqual([
      "relative",
      "none",
      "visible",
    ]);
    expect(writtenRules()).not.toContain(
      `@media (height < 400px){.${styledClassOf(beside)}`,
    );
  });

  it("takes room off only the first content element, even with different sibling tags and an inserted style node", async () => {
    const kept = localStorage.getItem("lilradish.panel.about");
    localStorage.removeItem("lilradish.panel.about");
    onTestFinished(() => {
      if (kept === null) localStorage.removeItem("lilradish.panel.about");
      else localStorage.setItem("lilradish.panel.about", kept);
    });
    laidOutAt(1400, 1600);
    render(
      <ThemeProvider theme={theme}>
        <TwoColumns
          panelTitle="About it"
          wideAtLeast={600}
          wide={
            <>
              <h2>First heading</h2>
              <p>Following paragraph</p>
              <div>Following block</div>
            </>
          }
          narrow={<p>About this page</p>}
        />
      </ThemeProvider>,
    );
    const narrow = await screen.findByRole("region", { name: "About it" });
    const wide = narrow.previousElementSibling!;
    const rules = styleRulesOf(wide).filter(({ rule }) =>
      rule.selectorText.includes(":not(style)"),
    );
    expect(rules).toHaveLength(1);
    const selector = rules[0]!.rule.selectorText;
    const heading = screen.getByRole("heading", { name: "First heading" });
    expect([...document.querySelectorAll(selector)]).toEqual([heading]);
    const style = document.createElement("style");
    wide.prepend(style);
    expect([...document.querySelectorAll(selector)]).toEqual([heading]);
    expect(screen.getByText("Following paragraph").matches(selector)).toBe(
      false,
    );
    expect(screen.getByText("Following block").matches(selector)).toBe(false);
  });

  it("keeps no room above what either column opens with, so their tops are level", async () => {
    drawing(1400);
    const narrow = await screen.findByRole("region", { name: "About it" });
    const wide = narrow.previousElementSibling as HTMLElement;

    expect(
      styleRulesOf(wide).some(
        ({ rule }) =>
          rule.selectorText.includes(":not(style)") &&
          Number.parseFloat(rule.style.marginTop) === 0,
      ),
    ).toBe(true);
    expect(getComputedStyle(narrow).alignSelf).not.toBe("stretch");
  });
});
