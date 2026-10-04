import DeleteOutlinedIcon from "@mui/icons-material/DeleteOutlined";
import Box from "@mui/material/Box";
import Button from "@mui/material/Button";
import CssBaseline from "@mui/material/CssBaseline";
import Container from "@mui/material/Container";
import Dialog from "@mui/material/Dialog";
import DialogActions from "@mui/material/DialogActions";
import DialogContent from "@mui/material/DialogContent";
import DialogTitle from "@mui/material/DialogTitle";
import IconButton from "@mui/material/IconButton";
import Link from "@mui/material/Link";
import List from "@mui/material/List";
import ListItem from "@mui/material/ListItem";
import ListItemButton from "@mui/material/ListItemButton";
import Table from "@mui/material/Table";
import TableBody from "@mui/material/TableBody";
import TableCell from "@mui/material/TableCell";
import TableRow from "@mui/material/TableRow";
import TextField from "@mui/material/TextField";
import Typography from "@mui/material/Typography";
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

/** Material's medium button, as the type scale resolves it. */
const MEDIUM_BUTTON_TEXT = "14px";

const SYSTEM_FACE =
  'system-ui, "Segoe UI", Roboto, Helvetica, Arial, sans-serif';

const HEADING_LEVELS = ["h1", "h2", "h3", "h4", "h5", "h6"] as const;

/** `theme.palette.text.primary` and `text.secondary`, as resolved colours are reported back. */
const BODY_TEXT_COLOUR = "rgb(26, 26, 26)";

const SUPPORTING_TEXT_COLOUR = "rgb(89, 89, 89)";

/** Every colour text is drawn in, by what it marks. */
const TEXT_COLOURS = [
  ["the mark on generated output", theme.palette.generated.main],
  ["body text", theme.palette.text.primary],
  ["supporting text", theme.palette.text.secondary],
  ["a primary action", theme.palette.primary.main],
  ["a failure", theme.palette.error.main],
  ["a success", theme.palette.success.main],
] as const;

/** Every surface text is read on, including page, card and message grounds. */
const READ_ON = [
  ["the page", theme.palette.background.default],
  ["a white card", theme.palette.background.paper],
  ["the reader's own messages' ground", theme.palette.part.mine],
] as const;

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

describe("page container", () => {
  it("uses the same wide measure when a page leaves its width to the theme", () => {
    styled(
      <Container>
        <p>Demo Sample</p>
      </Container>,
    );

    const container = screen.getByText("Demo Sample").parentElement!;
    expect(container).toHaveClass(
      "MuiContainer-root",
      "MuiContainer-maxWidthXl",
    );
    expect(container).not.toHaveClass("MuiContainer-maxWidthLg");
  });
});

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

function stateRuleFor(
  element: HTMLElement,
  state: "hover" | "active",
  property: string,
): string | undefined {
  const generated = Array.from(element.classList).find((name) =>
    name.startsWith("css-"),
  );
  let applied: string | undefined;
  for (const sheet of document.styleSheets) {
    for (const outer of sheet.cssRules) {
      const rules = outer instanceof CSSMediaRule ? outer.cssRules : [outer];
      for (const rule of rules) {
        if (
          rule instanceof CSSStyleRule &&
          rule.selectorText.includes(`.${generated}:${state}`)
        ) {
          const value = rule.style.getPropertyValue(property);
          if (value !== "") applied = value;
        }
      }
    }
  }
  return applied;
}

function keyframesFor(name: string): CSSKeyframesRule | undefined {
  for (const sheet of document.styleSheets) {
    for (const rule of sheet.cssRules) {
      if (rule instanceof CSSKeyframesRule && rule.name === name) {
        return rule;
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
  it("provides nonempty autofill keyframes once through the page baseline", () => {
    styled(<TextField label="Name" />);

    for (const name of ["mui-auto-fill", "mui-auto-fill-cancel"]) {
      const keyframes = keyframesFor(name);
      expect(keyframes).toBeDefined();
      expect(keyframes?.cssRules).toHaveLength(1);
      expect(
        (keyframes?.cssRules[0] as CSSKeyframeRule).style.animationName,
      ).toBe(name);
    }
    expect(
      theme.components?.MuiInputBase?.defaultProps
        ?.disableInjectingGlobalStyles,
    ).toBe(true);
  });

  it("keeps both input animations that signal autofill and its cancellation", () => {
    styled(<TextField label="Name" />);

    const input = screen.getByRole("textbox", { name: "Name" });
    const cancel = getComputedStyle(input);
    expect(cancel.animationName).toBe("mui-auto-fill-cancel");
    expect(cancel.animationDuration).toBe("10ms");

    const autofill = Array.from(document.styleSheets)
      .flatMap((sheet) => Array.from(sheet.cssRules))
      .find(
        (rule): rule is CSSStyleRule =>
          rule instanceof CSSStyleRule &&
          rule.selectorText.endsWith(":-webkit-autofill") &&
          rule.selectorText.includes(input.classList[0]!),
      );
    expect(autofill?.style.animationName).toBe("mui-auto-fill");
    expect(autofill?.style.animationDuration).toBe("5000s");
  });

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
   * Measured against the palette's own surfaces, so a palette that darkens one
   * has to be re-measured rather than re-read.
   */
  it.each(
    TEXT_COLOURS.flatMap(([use, colour]) =>
      READ_ON.map(
        ([surface, ground]) => [use, surface, colour, ground] as const,
      ),
    ),
  )("keeps %s legible on %s", (_use, _surface, colour, ground) => {
    expect(contrastRatio(colour, ground)).toBeGreaterThanOrEqual(4.5);
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
   * Material grows the keys it knows into a full colour and merges the rest as written, so a colour of the
   * theme's own is typed as written: typed as a full colour, it would promise shades that nothing computes.
   */
  it("grows the colours Material knows and leaves the mark's, a part's, a step kind's and a step state's colours as written", () => {
    const written = expect.stringMatching(/^#[0-9a-f]{6}$/);
    expect(theme.palette.generated).toEqual({ main: "#5b3fa8" });
    expect(theme.palette.part).toEqual({ mine: written });
    expect(Object.keys(theme.palette.stepKind).toSorted()).toEqual([
      "code_step",
      "question",
      "route",
      "search",
      "workflow",
    ]);
    expect(Object.keys(theme.palette.stepState).toSorted()).toEqual([
      "done",
      "failed",
      "held_back",
      "not_started",
      "running",
      "waiting",
    ]);
    expect(Object.values(theme.palette.stepKind)).toEqual(
      Object.keys(theme.palette.stepKind).map(() => ({
        main: written,
        ground: written,
      })),
    );
    expect(Object.values(theme.palette.stepState)).toEqual(
      Object.keys(theme.palette.stepState).map(() => written),
    );
    expect(theme.palette.primary.dark).toBeDefined();
    expect(theme.palette.primary.contrastText).toBeDefined();
  });

  /** Not held to a bar: whose a message is, its words say; the ground only differs from what it stands among. */
  it.each([
    ["a card", theme.palette.background.paper],
    ["the page", theme.palette.background.default],
  ])(
    "lays the reader's own messages on a ground that differs from %s",
    (_neighbour, neighbour) => {
      expect(contrastRatio(theme.palette.part.mine, neighbour)).toBeGreaterThan(
        1,
      );
    },
  );

  /**
   * A kind's words and icon are drawn only on its own ground, which carries no meaning the words do not. A kind's or
   * a state's icon is in its words' colour, so the 4.5:1 its words are held to holds it over SC 1.4.11's 3:1.
   */
  it.each(Object.entries(theme.palette.stepKind))(
    "keeps the words naming a step that runs %s, and their icon, legible on their own ground",
    (_kind, { main, ground }) => {
      expect(contrastRatio(main, ground)).toBeGreaterThanOrEqual(4.5);
    },
  );

  /** The rule down a step's message is a mark beside the words that say its kind, so SC 1.4.11 asks 3:1 of it. */
  it.each(Object.entries(theme.palette.stepKind))(
    "keeps the rule down the message of a step that runs %s visible against the page it is drawn on",
    (_kind, { main }) => {
      expect(
        contrastRatio(main, theme.palette.background.paper),
      ).toBeGreaterThanOrEqual(3);
    },
  );

  /** A state's words, and its icon with them, on every ground they are drawn on. */
  it.each(
    Object.entries(theme.palette.stepState).flatMap(([state, colour]) =>
      READ_ON.map(([surface, ground]) => [state, surface, colour, ground]),
    ),
  )(
    "keeps a step %s, its words and its icon, legible on %s",
    (_state, _surface, colour, ground) => {
      expect(contrastRatio(colour, ground)).toBeGreaterThanOrEqual(4.5);
    },
  );

  /** Held back wants nothing of the reader and waiting wants an act, so the two are never drawn alike. */
  it("draws a step held back and a step waiting in colours of their own", () => {
    expect(theme.palette.stepState.held_back).not.toBe(
      theme.palette.stepState.waiting,
    );
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

  it("gives the page a pale ground and a roomier spacing step", () => {
    styled(<Typography>Read</Typography>);

    expect(theme.spacing(1)).toBe("10px");
    expect(theme.palette.background.default).toBe("#f5f7fa");
    expect(theme.palette.background.paper).toBe("#ffffff");
    expect(getComputedStyle(document.body).backgroundColor).toBe(
      "rgb(245, 247, 250)",
    );
  });

  it("gives body and supporting text room to read", () => {
    styled(
      <>
        <Typography variant="body1">Body</Typography>
        <Typography variant="body2">Supporting</Typography>
      </>,
    );

    expect(getComputedStyle(screen.getByText("Body")).lineHeight).toBe("1.65");
    expect(getComputedStyle(screen.getByText("Supporting")).lineHeight).toBe(
      "1.6",
    );
  });

  it("marks a Material link within a sentence without a resting underline", () => {
    styled(
      <Typography>
        Read <Link href="/demo">Demo Sample</Link> now.
      </Typography>,
    );

    const link = screen.getByRole("link", { name: "Demo Sample" });
    const sentence = link.parentElement!;
    expect(getComputedStyle(link).textDecorationLine).toBe("none");
    expect(getComputedStyle(link).fontWeight).toBe("600");
    expect(getComputedStyle(sentence).fontWeight).toBe("400");
    expect(getComputedStyle(link).color).toBe(PRIMARY_COLOUR);
    expect(theme.components?.MuiLink?.defaultProps?.underline).toBe("hover");
    link.focus();
    expect(link).toHaveFocus();
    expect(
      ruleFor(":focus-visible:not(.MuiButtonBase-root)")?.outlineColor,
    ).toBe(PRIMARY_COLOUR);
  });

  it("marks an ordinary content link in a sentence while leaving button links alone", () => {
    styled(
      <>
        <Typography>
          Open <a href="/demo">Demo Sample</a> now.
        </Typography>
        <Button href="/next">Next</Button>
      </>,
    );

    const link = screen.getByRole("link", { name: "Demo Sample" });
    const sentence = link.parentElement!;
    const selector = "a[href]:not(.MuiButtonBase-root):not(.MuiLink-root)";
    expect(getComputedStyle(link).textDecorationLine).toBe("none");
    expect(getComputedStyle(link).fontWeight).toBe("600");
    expect(getComputedStyle(sentence).fontWeight).toBe("400");
    expect(getComputedStyle(link).color).toBe(PRIMARY_COLOUR);
    expect(ruleFor(`${selector}:hover`)?.textDecoration).toBe("underline");
    expect(
      getComputedStyle(screen.getByRole("link", { name: "Next" })).fontWeight,
    ).not.toBe("600");
    link.focus();
    expect(link).toHaveFocus();
    expect(
      ruleFor(":focus-visible:not(.MuiButtonBase-root)")?.outlineColor,
    ).toBe(PRIMARY_COLOUR);
  });

  it("gives lists, table rows and form controls a comfortable default size", () => {
    styled(
      <>
        <List>
          <ListItem disablePadding>
            <ListItemButton>Unpadded outer</ListItemButton>
          </ListItem>
          <ListItemButton>Another</ListItemButton>
        </List>
        <List dense>
          <ListItemButton>Dense inner</ListItemButton>
        </List>
        <Table>
          <TableBody>
            <TableRow>
              <TableCell>Value</TableCell>
            </TableRow>
          </TableBody>
        </Table>
        <Table size="small">
          <TableBody>
            <TableRow>
              <TableCell>Compact</TableCell>
              <TableCell padding="none">No padding</TableCell>
              <TableCell padding="checkbox">Checkbox padding</TableCell>
            </TableRow>
          </TableBody>
        </Table>
        <TextField label="Name" />
        <Button>Save</Button>
      </>,
    );

    const cell = getComputedStyle(screen.getByRole("cell", { name: "Value" }));
    const smallCell = getComputedStyle(
      screen.getByRole("cell", { name: "Compact" }),
    );
    const another = getComputedStyle(screen.getByText("Another"));
    const unpadded = getComputedStyle(
      screen.getByText("Unpadded outer").closest("li")!,
    );
    const denseInner = getComputedStyle(screen.getByText("Dense inner"));
    const noPadding = getComputedStyle(
      screen.getByRole("cell", { name: "No padding" }),
    );
    const checkboxPadding = getComputedStyle(
      screen.getByRole("cell", { name: "Checkbox padding" }),
    );
    const inputRoot = getComputedStyle(
      screen.getByRole("textbox", { name: "Name" }).parentElement!,
    );
    const button = getComputedStyle(
      screen.getByRole("button", { name: "Save" }),
    );
    expect(another.paddingTop).toBe("10px");
    expect(unpadded.paddingTop).toBe("0");
    expect(unpadded.paddingBottom).toBe("0");
    expect(denseInner.paddingTop).toBe("4px");
    expect(cell.paddingTop).toBe("16px");
    expect(smallCell.paddingTop).toBe("12px");
    expect(cell.paddingLeft).toBe("18px");
    expect(noPadding.paddingTop).toBe("0px");
    expect(noPadding.paddingLeft).toBe("0px");
    expect(checkboxPadding.paddingTop).toBe("0px");
    expect(inputRoot.borderRadius).toBe("10px");
    expect(button.minHeight).toBe("40px");
  });

  it("sets the page in the reader's system face, never in a face nothing loads", () => {
    styled(<Typography>Read</Typography>);

    const face = getComputedStyle(screen.getByText("Read")).fontFamily;

    expect(face).toBe(SYSTEM_FACE);
    expect(face).not.toMatch(/^"?Roboto/);
  });

  /** Measured on the elements drawn, so a variant that resolves to another size shows here. */
  it("draws each heading level in one weight, no larger than the level above it and never smaller than body text", () => {
    styled(
      <>
        {HEADING_LEVELS.map((level) => (
          <Typography key={level} variant={level}>
            {level}
          </Typography>
        ))}
        <Typography>Body</Typography>
      </>,
    );

    const drawn = HEADING_LEVELS.map((level) =>
      screen.getByRole("heading", { level: Number(level.slice(1)) }),
    );
    const sizes = drawn.map((heading) =>
      parseFloat(getComputedStyle(heading).fontSize),
    );
    const body = parseFloat(
      getComputedStyle(screen.getByText("Body")).fontSize,
    );

    expect(drawn.map((heading) => heading.textContent)).toEqual(HEADING_LEVELS);
    expect(sizes).toEqual([28, 22, 20, 18, 17, 16]);
    expect(sizes.filter((size) => size < body)).toEqual([]);
    expect(
      new Set(drawn.map((heading) => getComputedStyle(heading).fontWeight)),
    ).toEqual(new Set(["600"]));
  });

  it("draws the last heading level in the supporting colour, and every other in the colour of the text around it", () => {
    styled(
      HEADING_LEVELS.map((level) => (
        <Typography key={level} variant={level}>
          {level}
        </Typography>
      )),
    );

    const colours = HEADING_LEVELS.map(
      (level) =>
        getComputedStyle(
          screen.getByRole("heading", { level: Number(level.slice(1)) }),
        ).color,
    );

    expect(colours.at(-1)).toBe(SUPPORTING_TEXT_COLOUR);
    expect(new Set(colours.slice(0, -1))).toEqual(new Set([BODY_TEXT_COLOUR]));
  });

  /** The supporting colour stays legible on every surface used for a page's parts. */
  it.each([
    ...READ_ON,
    ["the page behind the cards", theme.palette.background.default] as const,
  ])("keeps the last heading level legible on %s", (_surface, ground) => {
    styled(<Typography variant="h6">Inputs</Typography>);

    const drawn = hexOf(
      getComputedStyle(screen.getByRole("heading", { level: 6 })).color,
    );

    expect(contrastRatio(drawn, ground)).toBeGreaterThanOrEqual(4.5);
  });

  it("sets the product's name as large as a page's part, as a paragraph and no heading", () => {
    styled(
      <>
        <Typography variant="brand">lilradish</Typography>
        <Typography variant="h2">Versions</Typography>
      </>,
    );

    const name = screen.getByText("lilradish");
    const drawn = getComputedStyle(name);

    expect(name.tagName).toBe("P");
    expect(drawn.fontSize).toBe("22px");
    expect(drawn.fontWeight).toBe("600");
    expect(screen.getAllByRole("heading")).toEqual([
      screen.getByText("Versions"),
    ]);
  });

  it("writes a button's words as they are given, in the face around them", () => {
    styled(<Button>Save the steps</Button>);

    const button = getComputedStyle(
      screen.getByRole("button", { name: "Save the steps" }),
    );

    expect(button.textTransform).toBe("none");
    expect(button.fontFamily).toBe(SYSTEM_FACE);
  });

  /** Material would draw a subtitle as an h6: a label that no outline holds would stand in it as a heading. */
  it.each(["subtitle1", "subtitle2"] as const)(
    "draws %s as a paragraph and no heading, while a heading's variant stays its heading",
    (variant) => {
      styled(
        <>
          <Typography variant={variant}>Label</Typography>
          <Typography variant="h6">Heading</Typography>
        </>,
      );

      expect(screen.getByText("Label").tagName).toBe("P");
      expect(screen.getAllByRole("heading")).toEqual([
        screen.getByText("Heading"),
      ]);
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
      "a tag small and in its weight, written and spaced as the words around it",
      "tag",
      {
        fontWeight: "700",
        fontSize: "12px",
        fontFamily: "serif",
        letterSpacing: "1px",
        textTransform: "lowercase",
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

  it("sets a figure in digits of one width, taking the rest from around it", () => {
    styled(
      <Box sx={{ fontWeight: 300, fontSize: "20px", fontFamily: "serif" }}>
        <Box sx={{ typography: "figure" }}>1,024</Box>
        <Box>2,048</Box>
      </Box>,
    );

    const [figure, words] = ["1,024", "2,048"].map((text) =>
      getComputedStyle(screen.getByText(text)),
    );

    expect(figure!.fontVariantNumeric).toBe("tabular-nums");
    expect(words!.fontVariantNumeric).not.toBe("tabular-nums");
    expect([figure!.fontWeight, figure!.fontSize, figure!.fontFamily]).toEqual([
      "300",
      "20px",
      "serif",
    ]);
  });

  it("sets a system hint at the surrounding size, in regular italic type, without choosing a colour", () => {
    styled(
      <Typography variant="h2" sx={{ color: "text.secondary" }}>
        Demo Sample{" "}
        <Box component="span" sx={{ typography: "systemHint" }}>
          not named yet
        </Box>
      </Typography>,
    );

    const heading = screen.getByRole("heading", {
      name: "Demo Sample not named yet",
    });
    const hint = getComputedStyle(screen.getByText("not named yet"));
    const surrounding = getComputedStyle(heading);

    expect(hint.fontSize).toBe(surrounding.fontSize);
    expect(hint.fontWeight).toBe("400");
    expect(hint.fontStyle).toBe("italic");
    expect(hint.fontWeight).not.toBe(surrounding.fontWeight);
    expect(hint.color).toBe(SUPPORTING_TEXT_COLOUR);
    expect(
      contrastRatio(hexOf(hint.color), theme.palette.background.paper),
    ).toBeGreaterThanOrEqual(4.5);
  });

  it.each(["warning", "error"] as const)(
    "keeps a hint inside a %s notice in the notice's semantic colour at AA contrast",
    (severity) => {
      styled(
        <Notice severity={severity}>
          Demo Sample,{" "}
          <Box component="span" sx={{ typography: "systemHint" }}>
            not named yet
          </Box>
        </Notice>,
      );

      const hint = getComputedStyle(screen.getByText("not named yet"));
      const notice = screen
        .getByText("not named yet")
        .closest<HTMLElement>('[role="none"]')!;
      const noticeStyle = getComputedStyle(notice);

      expect(hint.color).toBe(noticeStyle.color);
      expect(hint.color).not.toBe(SUPPORTING_TEXT_COLOUR);
      expect(hint.fontStyle).toBe("italic");
      expect(
        contrastRatio(hexOf(hint.color), hexOf(noticeStyle.backgroundColor)),
      ).toBeGreaterThanOrEqual(4.5);
    },
  );

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
    expect(unasked.fontSize).toBe(MEDIUM_BUTTON_TEXT);
    expect(overridden.borderWidth).toBe("0px");
  });

  it("gives ordinary, warning and navigation acts distinct fills, edges and sizes", () => {
    styled(
      <>
        <Button>Rename</Button>
        <Button color="warning">Remove</Button>
        <Button color="inherit">Cancel</Button>
        <Button color="inherit" size="small">
          Next
        </Button>
        <Button variant="contained">Create</Button>
        <Button variant="text">Expand</Button>
      </>,
    );

    const [ordinary, warning, neutral, navigation, leading, unframed] = [
      "Rename",
      "Remove",
      "Cancel",
      "Next",
      "Create",
      "Expand",
    ].map((name) => getComputedStyle(screen.getByRole("button", { name })));
    const outlined = [ordinary!, warning!, neutral!, navigation!];
    const fills = outlined.map((style) =>
      style.getPropertyValue("--variant-outlinedBg"),
    );
    const borders = [
      ordinary!.getPropertyValue("--variant-outlinedBorder"),
      warning!.getPropertyValue("--variant-outlinedBorder"),
      hexOf(neutral!.borderColor),
      hexOf(navigation!.borderColor),
    ];
    const colours = [
      ordinary!.getPropertyValue("--variant-outlinedColor"),
      warning!.getPropertyValue("--variant-outlinedColor"),
      hexOf(neutral!.color),
      hexOf(navigation!.color),
    ];

    expect(new Set(fills).size).toBe(3);
    expect(new Set(borders).size).toBe(3);
    expect(outlined.map((style) => style.borderWidth)).toEqual([
      "1px",
      "1px",
      "1px",
      "1px",
    ]);
    expect(ordinary!.minHeight).toBe("40px");
    expect(warning!.minHeight).toBe("40px");
    expect(neutral!.minHeight).toBe("40px");
    expect(navigation!.minHeight).toBe("32px");
    expect(neutral!.color).toBe("rgb(82, 101, 121)");
    expect(neutral!.borderColor).toBe("rgb(82, 101, 121)");
    expect(neutral!.getPropertyValue("--variant-outlinedBg")).toBe(
      navigation!.getPropertyValue("--variant-outlinedBg"),
    );
    expect(navigation!.color).toBe("rgb(82, 101, 121)");
    expect(navigation!.borderColor).toBe("rgb(82, 101, 121)");
    expect(leading!.getPropertyValue("--variant-containedBg")).toBe(
      theme.palette.primary.main,
    );
    expect(unframed!.borderWidth).toBe("0px");
    for (let at = 0; at < outlined.length; at += 1) {
      expect(contrastRatio(borders[at]!, fills[at]!)).toBeGreaterThanOrEqual(3);
      expect(contrastRatio(colours[at]!, fills[at]!)).toBeGreaterThanOrEqual(
        4.5,
      );
    }
  });

  it("changes every outlined act's fill on hover and press", () => {
    styled(
      <>
        <Button>Rename</Button>
        <Button color="warning">Remove</Button>
        <Button color="inherit">Cancel</Button>
        <Button color="inherit" size="small">
          Next
        </Button>
      </>,
    );

    for (const name of ["Rename", "Remove", "Cancel", "Next"]) {
      const button = screen.getByRole("button", { name });
      const drawn = getComputedStyle(button);
      const resting = drawn.getPropertyValue("--variant-outlinedBg");
      const hovered = stateRuleFor(button, "hover", "--variant-outlinedBg");
      const pressed = stateRuleFor(button, "active", "--variant-outlinedBg");
      expect(hovered).toMatch(/^#[0-9a-f]{6}$/);
      expect(pressed).toMatch(/^#[0-9a-f]{6}$/);
      expect(hovered).not.toBe(resting);
      expect(pressed).not.toBe(resting);
      const words =
        name === "Next" || name === "Cancel"
          ? hexOf(drawn.color)
          : drawn.getPropertyValue("--variant-outlinedColor");
      const edge =
        name === "Next" || name === "Cancel"
          ? hexOf(drawn.borderColor)
          : drawn.getPropertyValue("--variant-outlinedBorder");
      for (const ground of [resting, hovered!, pressed!]) {
        expect(contrastRatio(words, ground)).toBeGreaterThanOrEqual(4.5);
        expect(contrastRatio(edge, ground)).toBeGreaterThanOrEqual(3);
      }
    }
  });

  /**
   * An act that stops something or takes it out of use has warning words, fill
   * and edge; both the words and edge stay distinct on every surface used here.
   */
  it.each(READ_ON)(
    "keeps a warning act's words and edge legible on %s, beside an ordinary act",
    (_surface, ground) => {
      styled(
        <>
          <Button color="warning">Stop it being used</Button>
          <Button>Let it be used again</Button>
        </>,
      );

      const [warning, other] = screen
        .getAllByRole("button")
        .map((button) => getComputedStyle(button));

      expect(
        contrastRatio(
          warning.getPropertyValue("--variant-outlinedColor"),
          ground,
        ),
      ).toBeGreaterThanOrEqual(4.5);
      expect(
        contrastRatio(
          warning.getPropertyValue("--variant-outlinedBorder"),
          ground,
        ),
      ).toBeGreaterThanOrEqual(3);
      expect(other.getPropertyValue("--variant-outlinedBorder")).toMatch(
        /^#[0-9a-f]{6}$/,
      );
      expect(
        contrastRatio(
          other.getPropertyValue("--variant-outlinedBorder"),
          ground,
        ),
      ).toBeGreaterThanOrEqual(3);
    },
  );

  /** The act a page or a dialog leads with is filled, its words on the primary colour at SC 1.4.3's 4.5:1. */
  it("keeps a leading act's words legible on its fill", () => {
    styled(<Button variant="contained">Approve</Button>);

    const drawn = getComputedStyle(screen.getByRole("button"));

    expect(
      contrastRatio(
        drawn.getPropertyValue("--variant-containedColor"),
        drawn.getPropertyValue("--variant-containedBg"),
      ),
    ).toBeGreaterThanOrEqual(4.5);
  });

  /** An icon alone is a non-text mark held to SC 1.4.11's 3:1 contrast. */
  it.each(
    READ_ON.flatMap(([surface, ground]) =>
      (["plain", "warning", "neutral"] as const).map(
        (colour) => [colour, surface, ground] as const,
      ),
    ),
  )(
    "keeps a %s act drawn as an icon alone visible on %s",
    (colour, _surface, ground) => {
      styled(
        <IconButton
          aria-label="Remove"
          color={
            colour === "warning"
              ? "warning"
              : colour === "neutral"
                ? "inherit"
                : undefined
          }
        >
          <DeleteOutlinedIcon />
        </IconButton>,
      );

      const drawn = /^rgba?\((\d+), (\d+), (\d+)(?:, ([\d.]+))?\)$/.exec(
        getComputedStyle(screen.getByRole("button", { name: "Remove" })).color,
      )!;
      const opaque = hexOf(`rgb(${drawn[1]}, ${drawn[2]}, ${drawn[3]})`);

      expect(
        contrastRatio(
          composited(opaque, Number(drawn[4] ?? 1), ground),
          ground,
        ),
      ).toBeGreaterThanOrEqual(3);
      const button = screen.getByRole("button", { name: "Remove" });
      const style = getComputedStyle(button);
      expect(style.borderWidth).toBe("1px");
      expect(
        contrastRatio(hexOf(style.color), hexOf(style.backgroundColor)),
      ).toBeGreaterThanOrEqual(4.5);
      expect(
        contrastRatio(hexOf(style.borderColor), ground),
      ).toBeGreaterThanOrEqual(3);
      for (const state of ["hover", "active"] as const) {
        const stateGround = stateRuleFor(button, state, "background-color");
        expect(stateGround).toMatch(/^rgb\(\d+, \d+, \d+\)$/);
        expect(
          contrastRatio(hexOf(style.color), hexOf(stateGround!)),
        ).toBeGreaterThanOrEqual(4.5);
        expect(
          contrastRatio(hexOf(style.borderColor), hexOf(stateGround!)),
        ).toBeGreaterThanOrEqual(3);
      }
    },
  );

  it("keeps table text readable while making only a table's buttons compact", () => {
    styled(
      <>
        <Table>
          <TableBody>
            <TableRow>
              <TableCell>
                A longer name that can wrap <Button>Rename</Button>
              </TableCell>
            </TableRow>
          </TableBody>
        </Table>
        <Button>Outside</Button>
      </>,
    );

    const cell = getComputedStyle(screen.getByRole("cell"));
    const inside = getComputedStyle(
      screen.getByRole("button", { name: "Rename" }),
    );
    const outside = getComputedStyle(
      screen.getByRole("button", { name: "Outside" }),
    );
    expect(cell.fontFamily).toBe(SYSTEM_FACE);
    expect(cell.lineHeight).toBe("1.6");
    expect(cell.verticalAlign).toBe("top");
    expect(cell.whiteSpace).not.toBe("nowrap");
    expect(inside.minHeight).toBe("32px");
    expect(outside.minHeight).toBe("40px");
  });

  it("draws a dialog's title, a second-level heading, at the second level's size", () => {
    styled(
      <Dialog open>
        <DialogTitle>Add somebody</DialogTitle>
      </Dialog>,
    );

    const title = screen.getByRole("heading", { name: "Add somebody" });

    expect(title.tagName).toBe("H2");
    expect(getComputedStyle(title).fontSize).toBe("22px");
    expect(getComputedStyle(title).fontWeight).toBe("600");
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
   * Read off the rule rather than off an element: this environment matches `:focus-visible` on focus from script
   * and from a click as well, so a computed style would say nothing about the keyboard alone.
   */
  it("emits a ring in the primary colour for everything outside ButtonBase", () => {
    styled(null);

    const ring = ruleFor(":focus-visible:not(.MuiButtonBase-root)");

    expect(ring?.outlineColor).toBe(PRIMARY_COLOUR);
    expect(ring?.outlineWidth).toBe(GLOBAL_RING_WIDTH);
  });

  /** Read off the rule as well: this environment has no monospace size of its own for the rule to override. */
  it("sets code in the identifier's face, at the size of the text around it, and nothing else in that face", () => {
    styled(
      <Box sx={{ fontSize: "20px" }}>
        <code>reviewer_model</code> beside <span>words</span>
      </Box>,
    );

    const rule = ruleFor("code");

    expect(rule?.fontFamily).toBe(IDENTIFIER_FONT);
    expect(rule?.fontSize).toBe("inherit");
    expect(
      getComputedStyle(screen.getByText("reviewer_model")).fontFamily,
    ).toBe(IDENTIFIER_FONT);
    expect(getComputedStyle(screen.getByText("words")).fontFamily).toBe(
      SYSTEM_FACE,
    );
  });
});
