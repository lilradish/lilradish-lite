import Box from "@mui/material/Box";
import Button from "@mui/material/Button";
import Container from "@mui/material/Container";
import CssBaseline from "@mui/material/CssBaseline";
import Dialog from "@mui/material/Dialog";
import DialogActions from "@mui/material/DialogActions";
import DialogContent from "@mui/material/DialogContent";
import DialogTitle from "@mui/material/DialogTitle";
import TextField from "@mui/material/TextField";
import { ThemeProvider } from "@mui/material/styles";
import { render, screen, within } from "@testing-library/react";
import { userEvent } from "@testing-library/user-event";
import type { ReactNode } from "react";
import { describe, expect, it } from "vitest";

import { SEVERITIES } from "../../testutil/notices";
import { Notice } from "../notice/Notice";
import { IDENTIFIER_FONT, theme } from "./theme";

/** `theme.palette.primary.main`, as a resolved colour is reported back. */
const PRIMARY_COLOUR = "rgb(11, 87, 208)";

/** The widths are what say which of the two rules drew a given ring. */
const MATERIAL_RING_WIDTH = "2px";

const GLOBAL_RING_WIDTH = "3px";

/** Material's small button, as the type scale resolves it. */
const SMALL_BUTTON_TEXT = "13px";

/**
 * sRGB channels as fractions of full scale, from a `#rgb` or `#rrggbb` value.
 *
 * Anything else is refused rather than guessed at. A translucent colour has no
 * luminance until it is composited onto something, so measuring one as though
 * it did would report a ratio for a colour nobody ever sees.
 */
function channels(colour: string): number[] {
  const short = /^#([0-9a-f])([0-9a-f])([0-9a-f])$/i.exec(colour);
  const expanded =
    short === null
      ? colour
      : `#${short
          .slice(1)
          .map((digit) => digit + digit)
          .join("")}`;
  const full = /^#([0-9a-f]{2})([0-9a-f]{2})([0-9a-f]{2})$/i.exec(expanded);
  if (full === null) {
    throw new Error(`Not an opaque sRGB hex colour: ${colour}`);
  }
  return full.slice(1).map((pair) => parseInt(pair, 16) / 255);
}

/** A resolved `rgb(r, g, b)` as the hex the instrument reads; anything translucent is refused. */
function hexOf(resolved: string): string {
  const rgb = /^rgb\((\d+), (\d+), (\d+)\)$/.exec(resolved);
  if (rgb === null) {
    throw new Error(`Not an opaque resolved colour: ${resolved}`);
  }
  return `#${rgb
    .slice(1)
    .map((channel) => Number(channel).toString(16).padStart(2, "0"))
    .join("")}`;
}

/**
 * What a colour drawn at `opacity` over `ground` shows as: each sRGB channel
 * mixed in proportion, which is how a browser composites.
 */
function composited(colour: string, opacity: number, ground: string): string {
  const under = channels(ground);
  return `#${channels(colour)
    .map((channel, at) =>
      Math.round((opacity * channel + (1 - opacity) * under[at]!) * 255)
        .toString(16)
        .padStart(2, "0"),
    )
    .join("")}`;
}

/** WCAG 2.2 relative luminance for the sRGB colourspace. */
function relativeLuminance(colour: string): number {
  const [red, green, blue] = channels(colour).map((channel) =>
    channel <= 0.04045 ? channel / 12.92 : ((channel + 0.055) / 1.055) ** 2.4,
  );
  return 0.2126 * red + 0.7152 * green + 0.0722 * blue;
}

/** WCAG 2.2 contrast ratio: (L1 + 0.05) / (L2 + 0.05), lighter over darker. */
function contrastRatio(one: string, other: string): number {
  const luminances = [relativeLuminance(one), relativeLuminance(other)];
  return (Math.max(...luminances) + 0.05) / (Math.min(...luminances) + 0.05);
}

function styled(children: ReactNode) {
  return render(
    <ThemeProvider theme={theme}>
      {/* The component that puts the theme's global rules in the document. */}
      <CssBaseline />
      {children}
    </ThemeProvider>,
  );
}

/** The rule the theme emitted for one selector, as the document holds it. */
function ruleFor(selector: string): CSSStyleDeclaration | undefined {
  for (const sheet of document.styleSheets) {
    for (const rule of sheet.cssRules) {
      if (rule instanceof CSSStyleRule && rule.selectorText === selector) {
        return rule.style;
      }
    }
  }
  return undefined;
}

describe("contrastRatio", () => {
  /**
   * The suite below is only worth as much as the instrument measuring it, so
   * the instrument is pinned to the two ends of the scale the definition
   * states: 21:1 at the extremes, 1:1 for a colour against itself. Both
   * orderings, because dividing in argument order also gives 1/21.
   */
  it.each([
    ["black on white", 21, "#000000", "#ffffff"],
    ["white on black", 21, "#ffffff", "#000000"],
    ["a colour on itself", 1, "#5b3fa8", "#5b3fa8"],
  ])("puts %s at %d to 1", (_pair, expected, one, other) => {
    expect(contrastRatio(one, other)).toBeCloseTo(expected, 5);
  });

  it("reads the upper-case hex every design tool exports", () => {
    expect(contrastRatio("#5B3FA8", "#FFF")).toBeCloseTo(7.717, 3);
  });

  it("refuses a colour that has no luminance until something is behind it", () => {
    expect(() => contrastRatio("rgba(0, 0, 0, 0.6)", "#ffffff")).toThrow(
      /opaque/,
    );
  });

  /**
   * Why `theme.ts` writes `text.secondary` out rather than leaving Material's
   * default: the ratio below is the composite's, not the colour's. The default
   * clears 4.5:1 here and would measure something else on a darker card, and
   * nothing in this suite could tell the difference.
   */
  it("puts Material's default secondary text, flattened onto white, at 5.74 to 1", () => {
    // rgba(0, 0, 0, 0.6) over white is 0.4 × 255 = 102 = 0x66 in each channel.
    expect(contrastRatio("#666666", "#ffffff")).toBeCloseTo(5.742, 3);
  });
});

describe("composited", () => {
  it.each([
    ["wholly opaque, as itself", "#8c1d18", 1, "#8c1d18"],
    ["wholly transparent, as the ground", "#8c1d18", 0, "#ffffff"],
    [
      "black at 0.6 on white, as Material's secondary text shows",
      "#000000",
      0.6,
      "#666666",
    ],
  ])("shows a colour drawn %s", (_case, colour, opacity, shown) => {
    expect(composited(colour, opacity, "#ffffff")).toBe(shown);
  });
});

/** The cases below run in the order `theme.ts` declares what they are about. */
describe("theme", () => {
  it("rings a Material control the keyboard has reached, out of its own palette", async () => {
    styled(<Button>Retry</Button>);

    await userEvent.tab();

    const ring = getComputedStyle(screen.getByRole("button"));
    expect(ring.outlineColor).toBe(PRIMARY_COLOUR);
    expect(ring.outlineWidth).toBe(MATERIAL_RING_WIDTH);
  });

  /**
   * Every colour here is borne by text on a card — the mark on generated
   * output, a pass or fail verdict, a link, a breadcrumb — so SC 1.4.3's
   * 4.5:1 is the bar for all of them.
   *
   * Measured against `background.paper` rather than a written-down white: the
   * surface is what the ratio is taken against, so a palette that darkens its
   * cards has to be re-measured rather than re-read.
   */
  it.each([
    ["the mark on generated output", theme.palette.generated.main],
    ["body text", theme.palette.text.primary],
    ["supporting text", theme.palette.text.secondary],
    ["a primary action", theme.palette.primary.main],
    ["a failure", theme.palette.error.main],
    ["a success", theme.palette.success.main],
  ])("keeps %s legible on the surface it is read on", (_use, colour) => {
    expect(
      contrastRatio(colour, theme.palette.background.paper),
    ).toBeGreaterThanOrEqual(4.5);
  });

  /**
   * The rule down the edge of a generated region is a non-text mark, so
   * SC 1.4.11 asks 3:1 against adjacent colours — plural. It is drawn on the
   * card's boundary, card on one side and page on the other; the card side is
   * already held above at the stricter 4.5:1, and this is the other neighbour,
   * a separate value even while Material's defaults make the two look like one.
   */
  it("keeps the rule marking generated output visible against the page behind the card", () => {
    expect(
      contrastRatio(
        theme.palette.generated.main,
        theme.palette.background.default,
      ),
    ).toBeGreaterThanOrEqual(3);
  });

  /**
   * The shape `generated` is declared with rests on this: keys Material knows
   * are grown into a full colour, and keys it does not are merged as written.
   * Typing it as a full colour would promise shades that nothing computes.
   */
  it("grows the colours Material knows and leaves the mark colour as written", () => {
    expect(theme.palette.generated).toEqual({ main: "#5b3fa8" });
    expect(theme.palette.primary.dark).toBeDefined();
    expect(theme.palette.primary.contrastText).toBeDefined();
  });

  /**
   * A notice's words and icon are drawn in colours Material derives from the
   * severity's palette entry, on a ground derived the same way: new colours
   * this theme puts in front of readers, held to the same bars — 4.5:1 for the
   * words, 3:1 for the icon, a non-text mark. The icon is drawn translucent,
   * so it is measured as it shows on the ground rather than as its colour.
   */
  it.each(SEVERITIES)(
    "keeps a %s notice's words and icon legible on its own ground",
    (severity) => {
      styled(<Notice severity={severity}>Read this</Notice>);

      const notice = screen
        .getByText("Read this")
        .closest<HTMLElement>('[role="none"]')!;
      const icon = getComputedStyle(
        notice.querySelector("svg")!.parentElement!,
      );
      const ground = hexOf(getComputedStyle(notice).backgroundColor);

      expect(
        contrastRatio(hexOf(getComputedStyle(notice).color), ground),
      ).toBeGreaterThanOrEqual(4.5);
      expect(
        contrastRatio(
          composited(hexOf(icon.color), Number(icon.opacity), ground),
          ground,
        ),
      ).toBeGreaterThanOrEqual(3);
    },
  );

  /**
   * Set inside text whose every type property is its own, so what a role
   * leaves alone shows as that text's rather than as a default that happens to
   * match.
   */
  it.each([
    [
      "a term in its weight alone",
      "term",
      {
        fontWeight: "700",
        fontSize: "20px",
        fontFamily: "serif",
        letterSpacing: "1px",
        textTransform: "lowercase",
      },
    ],
    [
      "an identifier in its face alone",
      "identifier",
      {
        fontWeight: "300",
        fontSize: "20px",
        fontFamily: IDENTIFIER_FONT,
        letterSpacing: "1px",
        textTransform: "lowercase",
      },
    ],
    [
      "a tag small, spaced and in capitals, in the face around it",
      "tag",
      {
        fontWeight: "700",
        fontSize: "12px",
        fontFamily: "serif",
        letterSpacing: "0.96px",
        textTransform: "uppercase",
      },
    ],
  ])("sets %s, taking the rest from around it", (_role, role, expected) => {
    styled(
      <Box
        sx={{
          fontWeight: 300,
          fontSize: "20px",
          fontFamily: "serif",
          letterSpacing: "1px",
          textTransform: "lowercase",
        }}
      >
        <Box sx={{ typography: role }}>Set</Box>
      </Box>,
    );

    const set = getComputedStyle(screen.getByText("Set"));

    expect({
      fontWeight: set.fontWeight,
      fontSize: set.fontSize,
      fontFamily: set.fontFamily,
      letterSpacing: set.letterSpacing,
      textTransform: set.textTransform,
    }).toEqual(expected);
  });

  it("gives a button the product's edge and size unasked, and yields to a caller", () => {
    styled(
      <>
        <Button>Retry</Button>
        <Button variant="text" size="medium">
          Cancel
        </Button>
      </>,
    );

    const [unasked, overridden] = screen
      .getAllByRole("button")
      .map((button) => getComputedStyle(button));

    expect(unasked.borderWidth).toBe("1px");
    expect(unasked.fontSize).toBe(SMALL_BUTTON_TEXT);
    expect(overridden.borderWidth).toBe("0px");
  });

  /** A control with its reason under it stands taller than one without. */
  it("lines a dialog's actions up along their tops, still at the trailing end", () => {
    styled(
      <Dialog open>
        <DialogActions>
          <Button>Cancel</Button>
        </DialogActions>
      </Dialog>,
    );

    const actions = getComputedStyle(
      screen.getByRole("button", { name: "Cancel" }).parentElement!,
    );

    expect(actions.alignItems).toBe("flex-start");
    expect(actions.justifyContent).toBe("flex-end");
  });

  /**
   * A shrunk outlined label rises above its field, and dialog content both
   * scrolls — clipping whatever stands outside its padding — and, under a
   * title, loses its top padding in Material's own styles. This asserts the
   * rule that prevents the clipping: the scrolling box keeps at least the
   * label's rise, as the label's own transform states it, above its content.
   *
   * It cannot prove the picture. There is no layout here: no box heights, no
   * font metrics, no other ancestor that might clip, and nothing about a field
   * that is not the first thing in the content.
   */
  it("keeps room under a dialog's title for the label an outlined field raises above itself", () => {
    styled(
      <Dialog open>
        <DialogTitle>Add somebody</DialogTitle>
        <DialogContent>
          <TextField
            label="User number or name"
            slotProps={{ inputLabel: { shrink: true } }}
          />
        </DialogContent>
      </Dialog>,
    );

    const content = screen.getByRole("heading", {
      name: "Add somebody",
    }).nextElementSibling!;
    const label = within(content as HTMLElement).getByText(
      "User number or name",
      { selector: "label" },
    );
    const rise = -Number(
      /translate\([^,]+,\s*(-?[\d.]+)px\)/.exec(
        getComputedStyle(label).transform,
      )![1],
    );

    expect(rise).toBeGreaterThan(0);
    expect(getComputedStyle(content).overflowY).toBe("auto");
    expect(
      parseFloat(getComputedStyle(content).paddingTop),
    ).toBeGreaterThanOrEqual(rise);
  });

  it("leaves dialog content that follows no title padded as Material pads it", () => {
    styled(
      <Dialog open>
        <DialogContent>Nothing above it</DialogContent>
      </Dialog>,
    );

    expect(
      getComputedStyle(screen.getByText("Nothing above it")).paddingTop,
    ).toBe("20px");
  });

  it("leaves dialog content drawn between dividers under a title padded as Material pads it", () => {
    styled(
      <Dialog open>
        <DialogTitle>Add somebody</DialogTitle>
        <DialogContent dividers>Between the rules</DialogContent>
      </Dialog>,
    );

    expect(
      getComputedStyle(screen.getByText("Between the rules")).paddingTop,
    ).toBe("16px");
  });

  /**
   * What the exclusion holds off is ButtonBase, which is narrower than
   * "Material's own": a `Link` is a styled `Typography` and matches this rule
   * as well as the one it draws for itself. Both are the primary colour, so
   * whichever the cascade picks says the same thing to a reader.
   *
   * Read off the rule rather than off an element, because this environment
   * decides `:focus-visible` from the event that moved focus and never matches
   * it for an ordinary one — a computed style would report nothing at all.
   */
  it("emits a ring in the primary colour for everything outside ButtonBase", () => {
    styled(null);

    const ring = ruleFor(":focus-visible:not(.MuiButtonBase-root)");

    expect(ring?.outlineColor).toBe(PRIMARY_COLOUR);
    expect(ring?.outlineWidth).toBe(GLOBAL_RING_WIDTH);
  });

  it("frames a screen's own blocks in the divider every other edge is drawn in", () => {
    styled(
      <Container>
        <section aria-label="screen">
          <section aria-label="evidence">held</section>
        </section>
      </Container>,
    );

    expect(getComputedStyle(screen.getByLabelText("screen")).borderColor).toBe(
      theme.palette.divider,
    );
    expect(
      getComputedStyle(screen.getByLabelText("evidence")).borderColor,
    ).not.toBe(theme.palette.divider);
  });
});
