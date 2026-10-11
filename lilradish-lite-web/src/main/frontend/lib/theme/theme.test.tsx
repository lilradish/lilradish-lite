import DeleteOutlinedIcon from "@mui/icons-material/DeleteOutlined";
import Box from "@mui/material/Box";
import Button from "@mui/material/Button";
import Checkbox from "@mui/material/Checkbox";
import CssBaseline from "@mui/material/CssBaseline";
import Container from "@mui/material/Container";
import Dialog from "@mui/material/Dialog";
import DialogActions from "@mui/material/DialogActions";
import DialogContent from "@mui/material/DialogContent";
import DialogTitle from "@mui/material/DialogTitle";
import FormControlLabel from "@mui/material/FormControlLabel";
import IconButton from "@mui/material/IconButton";
import Link from "@mui/material/Link";
import List from "@mui/material/List";
import ListItem from "@mui/material/ListItem";
import ListItemButton from "@mui/material/ListItemButton";
import MenuItem from "@mui/material/MenuItem";
import MenuList from "@mui/material/MenuList";
import Radio from "@mui/material/Radio";
import Switch from "@mui/material/Switch";
import Table from "@mui/material/Table";
import TableBody from "@mui/material/TableBody";
import TableCell from "@mui/material/TableCell";
import TableHead from "@mui/material/TableHead";
import TableRow from "@mui/material/TableRow";
import TextField from "@mui/material/TextField";
import Typography from "@mui/material/Typography";
import ToggleButton from "@mui/material/ToggleButton";
import ToggleButtonGroup from "@mui/material/ToggleButtonGroup";
import { ThemeProvider } from "@mui/material/styles";
import { render, screen, within } from "@testing-library/react";
import { userEvent } from "@testing-library/user-event";
import type { ReactNode } from "react";
import { describe, expect, it, onTestFinished } from "vitest";

import { SEVERITIES } from "../../testutil/notices";
import { Notice } from "../notice/Notice";
import { theme } from "./theme";

const IDENTIFIER_FONT = "ui-monospace, monospace";

/** `theme.palette.primary.main`, as a resolved colour is reported back. */
const PRIMARY_COLOUR = "rgb(28, 91, 129)";

/** Every ring is this wide, whichever of the two rules draws it. */
const RING_WIDTH = "2px";

/** Material's offset turns inward on a component an overflow container would clip; outward it is 2px. */
const MATERIAL_RING_OFFSET = "calc(var(--_focusVisible-offset, 1) * 2px)";

const GLOBAL_RING_OFFSET = "2px";

/** Material's medium button, as the type scale resolves it. */
const MEDIUM_BUTTON_TEXT = "14px";

const SYSTEM_FACE = 'system-ui, "Segoe UI", Helvetica, sans-serif';

const HEADING_LEVELS = ["h1", "h2", "h3", "h4", "h5", "h6"] as const;

/** `theme.palette.text.primary` and `text.secondary`, as resolved colours are reported back. */
const BODY_TEXT_COLOUR = "rgb(25, 32, 36)";

const SUPPORTING_TEXT_COLOUR = "rgb(88, 102, 108)";

/** Every colour text is drawn in, by what it marks. */
const TEXT_COLOURS = [
  ["the mark on generated output", theme.palette.model.text],
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
  ["the ground of messages given to the reader", theme.palette.surface.given],
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

describe("typography roles", () => {
  it.each([
    [16, "14px", "22px"],
    [32, "28px", "44px"],
  ])(
    "keeps body and button type relative to a %s-pixel root",
    (root, size, leading) => {
      const previous = document.documentElement.style.fontSize;
      document.documentElement.style.fontSize = `${root}px`;
      onTestFinished(() => {
        document.documentElement.style.fontSize = previous;
      });
      styled(
        <>
          <Typography>Reading the claim.</Typography>
          <Button>Save claim</Button>
        </>,
      );
      const prose = screen.getByText("Reading the claim.");
      const button = screen.getByRole("button", { name: "Save claim" });
      expect(theme.typography.body1.fontSize).toBe("0.875rem");
      expect(theme.typography.body1.lineHeight).toBe("1.375rem");
      expect(theme.typography.button.fontSize).toBe("0.875rem");
      expect(getComputedStyle(prose).fontSize).toBe(size);
      expect(getComputedStyle(prose).lineHeight).toBe(leading);
      expect(getComputedStyle(button).fontSize).toBe(size);
    },
  );
  it.each([
    ["valueLabel", "13px", "20px", "600"],
    ["factLabel", "13px", "24px", "600"],
    ["factValue", "14px", "24px", "400"],
    ["statusPill", "12px", "22px", "600"],
    ["stepListName", "14px", "20px", "600"],
    ["stepListUnstartedName", "14px", "20px", "500"],
    ["stepListState", "12px", "18px", "600"],
    ["controlLabel", "12px", "18px", "600"],
    ["quietControl", "12px", "18px", "600"],
    ["controlSmall", "13px", "20px", "600"],
    ["supportingProse", "13px", "20px", "400"],
    ["fieldName", "14px", "22px", "600"],
    ["versionName", "20px", "28px", "400"],
    ["versionNameEmpty", "14px", "20px", "400"],
  ])(
    "draws %s through its own type role without changing the element's heading level",
    (role, size, lineHeight, weight) => {
      styled(
        <Typography variant="h3" sx={{ typography: role }}>
          Field
        </Typography>,
      );
      const heading = screen.getByRole("heading", { name: "Field", level: 3 });
      const drawn = getComputedStyle(heading);
      expect([drawn.fontSize, drawn.lineHeight, drawn.fontWeight]).toEqual([
        size,
        lineHeight,
        weight,
      ]);
      expect(screen.queryByRole("heading", { level: 2 })).toBeNull();
    },
  );
  it("keeps page, part, message and supporting text in their reading roles without moving heading levels", () => {
    styled(
      <>
        <Typography variant="h1">Claim page</Typography>
        <Typography variant="h2">Claim details</Typography>
        <Typography variant="messageHeading">Claim message</Typography>
        <Typography variant="body1">Claim body</Typography>
        <Typography variant="body2">Claim control</Typography>
        <Typography variant="caption">Claim helper</Typography>
      </>,
    );
    const expected = [
      ["Claim page", "H1", "24px", "32px"],
      ["Claim details", "H2", "20px", "28px"],
      ["Claim message", "H2", "16px", "24px"],
      ["Claim body", "P", "14px", "22px"],
      ["Claim control", "P", "14px", "20px"],
      ["Claim helper", "SPAN", "12px", "18px"],
    ];
    expect(expected.map(([words]) => screen.getByText(words!).tagName)).toEqual(
      expected.map(([, tag]) => tag),
    );
    expect(
      expected.map(([words]) => {
        const style = getComputedStyle(screen.getByText(words!));
        return [style.fontSize, style.lineHeight];
      }),
    ).toEqual(expected.map(([, , size, height]) => [size, height]));
    expect(screen.getAllByRole("heading", { level: 1 })).toEqual([
      screen.getByText("Claim page"),
    ]);
  });
});

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
  within = "",
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
          rule.selectorText.includes(`.${generated}${within}:${state}`)
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

describe("theme", () => {
  it("lets Material's own transitions follow the reader's setting for less motion", () => {
    expect(theme.motion.reducedMotion).toBe("system");
  });

  it("keeps the complete surface, ink, action, code and elevation palette in its approved roles", () => {
    expect(theme.palette.surface).toEqual({
      ground: "#ffffff",
      card: "#ffffff",
      cardEdge: "#d6e0e4",
      rule: "#e4ebee",
      rowHover: "#f3f8fa",
      subtle: "#f3f7f9",
      given: "#f6f9fb",
    });
    expect(theme.palette.ink).toEqual({
      primary: "#192024",
      strong: "#3e4a4f",
      secondary: "#58666c",
      controlEdge: "#808f96",
    });
    expect(theme.palette.act).toEqual({
      main: "#1c5b81",
      pressed: "#144b6c",
      hover: "#ebf3f9",
      current: "#daebf7",
    });
    expect(theme.palette.quiet).toEqual({
      ground: "#d3e6f4",
      hover: "#c7dded",
      pressed: "#aecce2",
      text: "#144b6c",
    });
    expect(theme.palette.warning).toMatchObject({
      main: "#9a4f00",
      noteGround: "#fbf1e1",
      noteText: "#5c3b00",
    });
    expect(theme.palette.keyTag).toEqual({
      text: "#4b575d",
      ground: "#ffffff",
      edge: "#c2cdd2",
    });
    expect(theme.palette.factTag).toEqual({
      text: "#3e4a4f",
      ground: "#ffffff",
      edge: "#c2cdd2",
    });
    expect(theme.palette.held).toEqual({
      ground: "#eef2f4",
      text: "#808f96",
      edge: "#d6e0e4",
    });
    expect(theme.palette.action).toMatchObject({
      disabled: "#808f96",
      disabledBackground: "#eef2f4",
      focus: "#ebf3f9",
    });
    expect(theme.palette.code).toEqual({
      ground: "#f3f7f9",
      text: "#192024",
      comment: "#58666c",
      keyword: "#a1261f",
      string: "#1f4f99",
      constant: "#055d7a",
      entity: "#8a4700",
      variable: "#0b6b4f",
      deleted: { text: "#a1261f", ground: "#fbe7e5" },
      inserted: { text: "#24683a", ground: "#e1f0e4" },
    });
    expect(theme.palette.elevation).toEqual({
      panel: "-8px 0 24px rgba(25, 32, 36, 0.10)",
      popup: "0 4px 12px rgba(25, 32, 36, 0.12)",
      scrim: "rgba(25, 32, 36, 0.24)",
    });
    expect(theme.shadows).toEqual([
      "none",
      ...Array<string>(24).fill("0 4px 12px rgba(25, 32, 36, 0.12)"),
    ]);
    expect(theme.vars).toBeUndefined();
  });

  it.each([
    [
      "primary",
      "contained",
      "primary",
      "medium",
      "--variant-containedBg",
      "#1c5b81",
      "#144b6c",
      "#144b6c",
      "#fff",
      null,
      null,
    ],
    [
      "secondary",
      "outlined",
      "primary",
      "medium",
      "--variant-outlinedBg",
      "#ffffff",
      "#ebf3f9",
      "#ebf3f9",
      "#1c5b81",
      "#1c5b81",
      "#1c5b81",
    ],
    [
      "neutral",
      "outlined",
      "inherit",
      "medium",
      "--variant-outlinedBg",
      "#ffffff",
      "#ebf3f9",
      "#ebf3f9",
      "#3e4a4f",
      "#808f96",
      "#58666c",
    ],
    [
      "danger",
      "outlined",
      "warning",
      "medium",
      "--variant-outlinedBg",
      "#ffffff",
      "#fbf1e1",
      "#fbf1e1",
      "#9a4f00",
      "#9a4f00",
      "#9a4f00",
    ],
    [
      "quiet",
      "text",
      "inherit",
      "small",
      "--variant-textBg",
      "#d3e6f4",
      "#c7dded",
      "#c7dded",
      "#144b6c",
      "rgba(0, 0, 0, 0)",
      "rgba(0, 0, 0, 0)",
    ],
  ] as const)(
    "draws the complete %s rest, hover and press fill, word and edge roles",
    (
      _role,
      variant,
      color,
      size,
      property,
      rest,
      hover,
      press,
      text,
      restingEdge,
      hoveredEdge,
    ) => {
      styled(
        <Button variant={variant} color={color} size={size}>
          Act
        </Button>,
      );
      const button = screen.getByRole("button", { name: "Act" });
      const style = getComputedStyle(button);
      const resting =
        variant === "text"
          ? hexOf(style.backgroundColor)
          : style.getPropertyValue(property);
      const words =
        color === "inherit"
          ? hexOf(style.color)
          : style.getPropertyValue(
              variant === "contained"
                ? "--variant-containedColor"
                : "--variant-outlinedColor",
            );
      expect([
        resting,
        stateRuleFor(button, "hover", property),
        stateRuleFor(button, "active", property),
        words,
      ]).toEqual([rest, hover, press, text]);
      for (const ground of [rest, hover, press])
        expect(contrastRatio(words, ground)).toBeGreaterThanOrEqual(4.5);
      const edgeProperty =
        color === "inherit" ? "border-color" : "--variant-outlinedBorder";
      const asWritten = (value: string) =>
        value.startsWith("rgb(") ? hexOf(value) : value;
      const edgeAtRest =
        variant === "contained"
          ? null
          : asWritten(style.getPropertyValue(edgeProperty));
      const edgeUnderPointer =
        variant === "contained"
          ? null
          : asWritten(
              stateRuleFor(button, "hover", edgeProperty) ?? edgeAtRest!,
            );
      expect([edgeAtRest, edgeUnderPointer]).toEqual([
        restingEdge,
        hoveredEdge,
      ]);
      if (variant === "contained") expect(style.borderWidth).toBe("0px");
    },
  );

  it.each([
    ["toggle", "aria-pressed", "button"],
    ["choice", "aria-checked", "radio"],
  ] as const)(
    "draws a secondary %s that is on in the picked ground under the working edge and words, the same under the pointer and pressed, and one that is off white",
    (_kind, attribute, role) => {
      styled(
        <>
          <Button role={role} {...{ [attribute]: true }}>
            On
          </Button>
          <Button role={role} {...{ [attribute]: false }}>
            Off
          </Button>
        </>,
      );
      const on = screen.getByRole(role, { name: "On" });
      const off = screen.getByRole(role, { name: "Off" });
      const property = "--variant-outlinedBg";
      const drawn = getComputedStyle(on);
      expect([
        drawn.getPropertyValue(property),
        stateRuleFor(on, "hover", property, `[${attribute}='true']`),
        stateRuleFor(on, "active", property, `[${attribute}='true']`),
        drawn.getPropertyValue("--variant-outlinedBorder"),
        drawn.getPropertyValue("--variant-outlinedColor"),
        drawn.boxShadow,
      ]).toEqual([
        "#daebf7",
        "#daebf7",
        "#daebf7",
        "#1c5b81",
        "#1c5b81",
        "none",
      ]);
      expect(contrastRatio("#1c5b81", "#daebf7")).toBeGreaterThanOrEqual(4.5);
      expect([
        getComputedStyle(off).getPropertyValue(property),
        getComputedStyle(off).getPropertyValue("--variant-outlinedBorder"),
      ]).toEqual(["#ffffff", "#1c5b81"]);
    },
  );

  it("marks a picked table row with the picked ground and a 3px working edge inside its first cell alone", () => {
    styled(
      <Table>
        <TableBody>
          <TableRow selected>
            <TableCell>Picked</TableCell>
            <TableCell>Its kind</TableCell>
          </TableRow>
          <TableRow>
            <TableCell>Not picked</TableCell>
          </TableRow>
        </TableBody>
      </Table>,
    );
    const picked = screen.getByText("Picked");
    expect(hexOf(getComputedStyle(picked.parentElement!).backgroundColor)).toBe(
      "#daebf7",
    );
    expect(getComputedStyle(picked).boxShadow).toBe("inset 3px 0 0 #1c5b81");
    expect(getComputedStyle(screen.getByText("Its kind")).boxShadow).not.toBe(
      "inset 3px 0 0 #1c5b81",
    );
    const other = screen.getByText("Not picked");
    expect(getComputedStyle(other).boxShadow).not.toBe("inset 3px 0 0 #1c5b81");
    expect(getComputedStyle(other.parentElement!).backgroundColor).not.toBe(
      "rgb(218, 235, 247)",
    );
  });

  it("marks a picked row headed by a header cell on that cell alone, never on the first data cell after it", () => {
    styled(
      <Table>
        <TableBody>
          <TableRow selected>
            <TableCell component="th" scope="row">
              Attempt 1
            </TableCell>
            <TableCell>Cat</TableCell>
            <TableCell>Waiting</TableCell>
          </TableRow>
        </TableBody>
      </Table>,
    );
    const [head, first, last] = [
      ...screen.getByText("Attempt 1").parentElement!.children,
    ];
    expect(head!.tagName).toBe("TH");
    expect(getComputedStyle(head!).boxShadow).toBe("inset 3px 0 0 #1c5b81");
    expect(first!.tagName).toBe("TD");
    expect(getComputedStyle(first!).boxShadow).not.toBe(
      "inset 3px 0 0 #1c5b81",
    );
    expect(getComputedStyle(last!).boxShadow).not.toBe("inset 3px 0 0 #1c5b81");
    const row = head!.parentElement!;
    row.prepend(document.createElement("style"));
    head!.after(document.createElement("style"));
    expect(getComputedStyle(head!).boxShadow).toBe("inset 3px 0 0 #1c5b81");
    for (const following of [first!, last!]) {
      expect(getComputedStyle(following).boxShadow).not.toBe(
        getComputedStyle(head!).boxShadow,
      );
    }
  });

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
    expect(ring.outlineStyle).toBe("solid");
    expect(ring.outlineColor).toBe(PRIMARY_COLOUR);
    expect(ring.outlineWidth).toBe(RING_WIDTH);
    expect(ring.outlineOffset).toBe(MATERIAL_RING_OFFSET);
    expect(
      getComputedStyle(screen.getByRole("button")).getPropertyValue(
        "--_focusVisible-offset",
      ),
    ).toBe("1");
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
      contrastRatio(theme.palette.model.text, theme.palette.background.default),
    ).toBeGreaterThanOrEqual(3);
  });

  /**
   * Material grows the keys it knows into a full colour and merges the rest as written, so a colour of the
   * theme's own is typed as written: typed as a full colour, it would promise shades that nothing computes.
   */
  it("grows Material colours while keeping every canonical role exact and no compatibility palettes", () => {
    expect(theme.palette.model).toEqual({ text: "#5b3fa8", ground: "#efeaf8" });
    expect(theme.palette.kind).toEqual({
      workflow: { text: "#403c39", ground: "#e8e6e3", edge: "#8e8a87" },
      question: { text: "#5c5721", ground: "#f3f2db", edge: "#8f8c62" },
      route: { text: "#614232", ground: "#f5eae5", edge: "#9e867a" },
      search: { text: "#8a2c55", ground: "#f8e6ee", edge: "#ba7890" },
      codeStep: { text: "#324b5a", ground: "#e4edf2", edge: "#7a8d98" },
      referenceList: { text: "#215e54", ground: "#e1f3ef" },
    });
    expect(theme.palette.kind.referenceList).not.toHaveProperty("edge");
    expect(theme.palette.state).toEqual({
      draft: { text: "#444f54", ground: "#ffffff", edge: "#a9b6bd" },
      submitted: { text: "#1f4f99", ground: "#e3ebf7" },
      inService: { text: "#24683a", ground: "#e1f0e4" },
      retired: { text: "#5a656b", ground: "#ffffff", edge: "#c2cdd2" },
      notStarted: { text: "#444f54", ground: "#ffffff", edge: "#a9b6bd" },
      running: { text: "#1f4f99", ground: "#e3ebf7" },
      waiting: { text: "#8a4700", ground: "#fbeedb" },
      blocked: { text: "#3a4449", ground: "#ffffff", edge: "#a9b6bd" },
      failed: { text: "#a1261f", ground: "#fbe7e5" },
      done: { text: "#24683a", ground: "#e1f0e4" },
      stopped: { text: "#5a656b", ground: "#ffffff", edge: "#c2cdd2" },
      rejectedForLength: {
        text: "#3a4449",
        ground: "#ffffff",
        edge: "#a9b6bd",
      },
    });
    for (const removed of [
      "generated",
      "part",
      "stepKind",
      "stepState",
      "stepStateGround",
      "valueState",
      "kindMore",
      "stateMore",
    ])
      expect(theme.palette).not.toHaveProperty(removed);
    expect(theme.palette.primary.dark).toBeDefined();
    expect(theme.palette.primary.contrastText).toBeDefined();
  });

  /** Not held to a bar: whose a message is, its words say; the ground only differs from what it stands among. */
  it.each([
    ["a card", theme.palette.background.paper],
    ["the page", theme.palette.background.default],
  ])(
    "lays messages given to the reader on a ground that differs from %s",
    (_neighbour, neighbour) => {
      expect(
        contrastRatio(theme.palette.surface.given, neighbour),
      ).toBeGreaterThan(1);
    },
  );

  /**
   * A kind's words and icon are drawn only on its own ground, which carries no meaning the words do not. A kind's or
   * a state's icon is in its words' colour, so the 4.5:1 its words are held to holds it over SC 1.4.11's 3:1.
   */
  it.each(Object.entries(theme.palette.kind))(
    "keeps the words naming a step that runs %s, and their icon, legible on their own ground",
    (_kind, { text, ground }) => {
      expect(contrastRatio(text, ground)).toBeGreaterThanOrEqual(4.5);
    },
  );

  /** The rule down a step's message is a mark beside the words that say its kind, so SC 1.4.11 asks 3:1 of it. */
  it.each([
    ["workflow", "#8e8a87"],
    ["question", "#8f8c62"],
    ["route", "#9e867a"],
    ["search", "#ba7890"],
    ["codeStep", "#7a8d98"],
  ] as const)(
    "keeps the rule down the message of a step that runs %s visible against the page it is drawn on",
    (_kind, edge) => {
      expect(
        contrastRatio(edge, theme.palette.background.paper),
      ).toBeGreaterThanOrEqual(3);
    },
  );

  /** A state's words, and its icon with them, on every ground they are drawn on. */
  it.each(
    Object.entries(theme.palette.state).flatMap(([state, { text: colour }]) =>
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
    expect(theme.palette.state.blocked.text).not.toBe(
      theme.palette.state.waiting.text,
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

  it("gives the page and its cards one white ground with a roomier spacing step", () => {
    styled(<Typography>Read</Typography>);

    expect(theme.spacing(1)).toBe("10px");
    expect(theme.palette.background.default).toBe("#ffffff");
    expect(theme.palette.background.paper).toBe("#ffffff");
    expect(getComputedStyle(document.body).backgroundColor).toBe(
      "rgb(255, 255, 255)",
    );
  });

  it("gives body and supporting text room to read", () => {
    styled(
      <>
        <Typography variant="body1">Body</Typography>
        <Typography variant="body2">Supporting</Typography>
      </>,
    );

    expect(getComputedStyle(screen.getByText("Body")).lineHeight).toBe("22px");
    expect(getComputedStyle(screen.getByText("Supporting")).lineHeight).toBe(
      "20px",
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
    const buttonLink = getComputedStyle(
      screen.getByRole("link", { name: "Next" }),
    );
    expect(buttonLink.borderRadius).toBe("8px");
    expect(buttonLink.textUnderlineOffset).not.toBe("0.18em");
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
    expect(cell.paddingTop).toBe("11px");
    expect(smallCell.paddingTop).toBe("11px");
    expect(cell.paddingLeft).toBe("12px");
    expect(noPadding.paddingTop).toBe("0px");
    expect(noPadding.paddingLeft).toBe("0px");
    expect(checkboxPadding.paddingTop).toBe("0px");
    expect(inputRoot.borderRadius).toBe("8px");
    expect(button.minHeight).toBe("36px");
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
    expect(sizes).toEqual([24, 20, 16, 16, 16, 16]);
    expect(sizes.filter((size) => size < body)).toEqual([]);
    expect(
      new Set(drawn.map((heading) => getComputedStyle(heading).fontWeight)),
    ).toEqual(new Set(["650"]));
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
    expect(drawn.fontSize).toBe("20px");
    expect(drawn.fontWeight).toBe("650");
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

  it("gives primary, secondary, neutral, quiet and danger acts distinct fills, edges and sizes", () => {
    styled(
      <>
        <Button>Rename</Button>
        <Button color="warning">Remove</Button>
        <Button color="inherit">Cancel</Button>
        <Button variant="text" color="inherit" size="small">
          Next
        </Button>
        <Button variant="contained">Create</Button>
        <Button variant="text">Expand</Button>
        <Button variant="text" color="inherit" size="small">
          Details
        </Button>
        <Button variant="contained" color="warning">
          Retire
        </Button>
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
    const outlined = [ordinary!, warning!, neutral!];
    const fills = outlined.map((style) =>
      style.getPropertyValue("--variant-outlinedBg"),
    );
    const borders = [
      ordinary!.getPropertyValue("--variant-outlinedBorder"),
      warning!.getPropertyValue("--variant-outlinedBorder"),
      hexOf(neutral!.borderColor),
    ];
    const colours = [
      ordinary!.getPropertyValue("--variant-outlinedColor"),
      warning!.getPropertyValue("--variant-outlinedColor"),
      hexOf(neutral!.color),
    ];

    const leadingFill = leading!.getPropertyValue("--variant-containedBg");
    const quietFill = hexOf(navigation!.backgroundColor);
    expect(fills).toEqual(["#ffffff", "#ffffff", "#ffffff"]);
    expect(leadingFill).not.toBe(fills[0]);
    expect([leadingFill, ...fills]).not.toContain(quietFill);
    expect(new Set(borders).size).toBe(3);
    expect(outlined.map((style) => style.borderWidth)).toEqual([
      "1px",
      "1px",
      "1px",
    ]);
    expect(ordinary!.minHeight).toBe("36px");
    expect(warning!.minHeight).toBe("36px");
    expect(neutral!.minHeight).toBe("36px");
    expect(leading!.minHeight).toBe("36px");
    expect(navigation!.minHeight).toBe("26px");
    expect(neutral!.color).toBe("rgb(62, 74, 79)");
    expect(neutral!.borderColor).toBe("rgb(128, 143, 150)");
    expect(navigation!.color).toBe("rgb(20, 75, 108)");
    expect(quietFill).toBe("#d3e6f4");
    expect(navigation!.borderRadius).toBe("6px");
    expect(ordinary!.borderRadius).toBe("8px");
    expect(screen.getByRole("button", { name: "Next" })).toHaveClass(
      "MuiButton-text",
    );
    expect(screen.getByRole("button", { name: "Next" })).not.toHaveClass(
      "MuiButton-outlined",
    );
    expect(navigation!.borderColor).toBe("rgba(0, 0, 0, 0)");
    expect(leading!.getPropertyValue("--variant-containedBg")).toBe(
      theme.palette.primary.main,
    );
    expect(unframed!.borderWidth).toBe("0px");
    const quiet = getComputedStyle(
      screen.getByRole("button", { name: "Details" }),
    );
    const confirm = getComputedStyle(
      screen.getByRole("button", { name: "Retire" }),
    );
    expect(ordinary!.minWidth).toBe("88px");
    expect(quiet.minWidth).toBe("64px");
    expect(quiet.minHeight).toBe("26px");
    expect(quiet.color).toBe("rgb(20, 75, 108)");
    expect(quiet.borderColor).toBe("rgba(0, 0, 0, 0)");
    expect(confirm.getPropertyValue("--variant-containedBg")).toBe("#9a4f00");
    expect(leading!.boxShadow).toBe("none");
    expect(confirm.boxShadow).toBe("none");
    expect(contrastRatio(hexOf(quiet.color), "#ffffff")).toBeGreaterThanOrEqual(
      4.5,
    );
    expect(
      contrastRatio(
        confirm.getPropertyValue("--variant-containedColor"),
        "#9a4f00",
      ),
    ).toBeGreaterThanOrEqual(4.5);
    for (let at = 0; at < outlined.length; at += 1) {
      expect(contrastRatio(borders[at]!, fills[at]!)).toBeGreaterThanOrEqual(3);
      expect(contrastRatio(colours[at]!, fills[at]!)).toBeGreaterThanOrEqual(
        4.5,
      );
    }
  });

  it("changes every outlined act's fill on hover and press and a quiet navigation act's fill on hover", () => {
    styled(
      <>
        <Button>Rename</Button>
        <Button color="warning">Remove</Button>
        <Button color="inherit">Cancel</Button>
        <Button variant="text" color="inherit" size="small">
          Next
        </Button>
      </>,
    );

    for (const name of ["Rename", "Remove", "Cancel"]) {
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
        name === "Cancel"
          ? hexOf(drawn.color)
          : drawn.getPropertyValue("--variant-outlinedColor");
      const restingEdge =
        name === "Cancel"
          ? hexOf(drawn.borderColor)
          : drawn.getPropertyValue("--variant-outlinedBorder");
      for (const ground of [resting, hovered!, pressed!]) {
        expect(contrastRatio(words, ground)).toBeGreaterThanOrEqual(4.5);
      }
      const edgeProperty =
        name === "Cancel" ? "border-color" : "--variant-outlinedBorder";
      const stateEdge = (state: "hover" | "active") => {
        const changed = stateRuleFor(button, state, edgeProperty);
        return changed === undefined
          ? restingEdge
          : changed.startsWith("rgb")
            ? hexOf(changed)
            : changed;
      };
      expect(contrastRatio(restingEdge, resting)).toBeGreaterThanOrEqual(3);
      expect(
        contrastRatio(stateEdge("hover"), hovered!),
      ).toBeGreaterThanOrEqual(3);
      expect(
        contrastRatio(stateEdge("active"), pressed!),
      ).toBeGreaterThanOrEqual(3);
    }
    const next = screen.getByRole("button", { name: "Next" });
    const hovered = stateRuleFor(next, "hover", "--variant-textBg");
    expect(hovered).toBe("#c7dded");
    expect(
      contrastRatio(hexOf(getComputedStyle(next).color), hovered!),
    ).toBeGreaterThanOrEqual(4.5);
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
  it.each(READ_ON)(
    "keeps a borderless icon legible on %s",
    (_surface, ground) => {
      styled(
        <IconButton aria-label="Remove">
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
      expect(style.borderWidth).toBe("0px");
      expect(style.backgroundColor).toBe("rgba(0, 0, 0, 0)");
      expect(style.color).toBe("rgb(88, 102, 108)");
      expect([
        style.width,
        style.height,
        style.padding,
        style.borderRadius,
      ]).toEqual(["32px", "32px", "0px", "8px"]);
      expect(getComputedStyle(button.querySelector("svg")!).width).toBe("16px");
      expect(stateRuleFor(button, "hover", "background-color")).toBe(
        "rgb(211, 230, 244)",
      );
      expect(stateRuleFor(button, "active", "background-color")).toBe(
        "rgb(199, 221, 237)",
      );
      expect(stateRuleFor(button, "hover", "color")).toBe("rgb(25, 32, 36)");
    },
  );

  it("keeps AppBar icons in their inherited colour and fold arrows slightly larger", () => {
    styled(
      <div style={{ color: "#ffffff" }}>
        <IconButton color="inherit" aria-label="Sidebar">
          <DeleteOutlinedIcon />
        </IconButton>
        <IconButton aria-label="Fold" aria-expanded="true">
          <DeleteOutlinedIcon />
        </IconButton>
      </div>,
    );
    expect(
      getComputedStyle(screen.getByRole("button", { name: "Sidebar" })).color,
    ).toBe("rgb(255, 255, 255)");
    expect(
      getComputedStyle(
        screen.getByRole("button", { name: "Fold" }).querySelector("svg")!,
      ).width,
    ).toBe("18px");
  });

  it("draws toggles in equal columns and marks the chosen segment with colour and pressed state", () => {
    styled(
      <ToggleButtonGroup value="conversation" exclusive aria-label="View">
        <ToggleButton value="conversation">Conversation</ToggleButton>
        <ToggleButton value="detail">Full detail</ToggleButton>
      </ToggleButtonGroup>,
    );
    const group = screen.getByRole("group", { name: "View" });
    const chosen = screen.getByRole("button", { name: "Conversation" });
    const other = screen.getByRole("button", { name: "Full detail" });
    expect(getComputedStyle(group).display).toBe("inline-grid");
    expect(getComputedStyle(group).gridAutoColumns).toBe("minmax(0, 1fr)");
    expect(chosen).toHaveAttribute("aria-pressed", "true");
    expect(other).toHaveAttribute("aria-pressed", "false");
    expect(chosen).toHaveStyle({
      backgroundColor: "#daebf7",
      color: "#1c5b81",
    });
    expect(other).toHaveStyle({
      color: "#58666c",
      borderColor: "#808f96",
      minHeight: "32px",
    });
  });

  it("draws a held quiet button in the colours of a control that cannot be pressed, a quiet toggle that is on on its pressed ground inside an act-coloured edge, and one that is off with no edge", () => {
    styled(
      <>
        <Button variant="text" color="inherit" size="small" disabled>
          Previous
        </Button>
        <Button variant="text" color="inherit" size="small" aria-pressed="true">
          Attempts
        </Button>
        <Button
          variant="text"
          color="inherit"
          size="small"
          aria-pressed="false"
        >
          Sources
        </Button>
      </>,
    );
    expect(screen.getByRole("button", { name: "Previous" })).toHaveStyle({
      color: "#808f96",
    });
    expect(
      getComputedStyle(screen.getByRole("button", { name: "Previous" }))
        .backgroundColor,
    ).toBe("rgb(238, 242, 244)");
    expect(
      getComputedStyle(screen.getByRole("button", { name: "Previous" }))
        .borderColor,
    ).toBe("rgb(214, 224, 228)");
    const on = getComputedStyle(
      screen.getByRole("button", { name: "Attempts" }),
    );
    const off = getComputedStyle(
      screen.getByRole("button", { name: "Sources" }),
    );
    expect([on.color, on.backgroundColor, on.boxShadow]).toEqual([
      "rgb(20, 75, 108)",
      "rgb(174, 204, 226)",
      "inset 0 0 0 1px #1c5b81",
    ]);
    expect([off.color, off.backgroundColor]).toEqual([
      "rgb(20, 75, 108)",
      "rgb(211, 230, 244)",
    ]);
    expect(off.boxShadow).not.toContain("inset");
  });

  it("draws a held outlined or filled button in the colours of a control that cannot be pressed", () => {
    styled(
      <>
        <Button disabled>Rename</Button>
        <Button color="inherit" disabled>
          Cancel
        </Button>
        <Button variant="contained" disabled>
          Create
        </Button>
      </>,
    );

    for (const name of ["Rename", "Cancel", "Create"]) {
      const drawn = getComputedStyle(screen.getByRole("button", { name }));
      expect([
        drawn.color,
        drawn.backgroundColor,
        drawn.borderColor,
        drawn.opacity,
      ]).toEqual([
        "rgb(128, 143, 150)",
        "rgb(238, 242, 244)",
        "rgb(214, 224, 228)",
        "1",
      ]);
    }
  });

  it("draws a held segment in the held colours and a held icon in the held words on no ground", () => {
    styled(
      <>
        <ToggleButtonGroup value="conversation" exclusive aria-label="View">
          <ToggleButton value="conversation">Conversation</ToggleButton>
          <ToggleButton value="detail" disabled>
            Full detail
          </ToggleButton>
        </ToggleButtonGroup>
        <IconButton aria-label="Remove" disabled>
          <DeleteOutlinedIcon />
        </IconButton>
      </>,
    );

    const segment = getComputedStyle(
      screen.getByRole("button", { name: "Full detail" }),
    );
    const icon = getComputedStyle(
      screen.getByRole("button", { name: "Remove" }),
    );
    expect([
      segment.color,
      segment.backgroundColor,
      segment.borderColor,
      segment.opacity,
    ]).toEqual([
      "rgb(128, 143, 150)",
      "rgb(238, 242, 244)",
      "rgb(214, 224, 228)",
      "1",
    ]);
    expect([icon.color, icon.backgroundColor, icon.opacity]).toEqual([
      "rgb(128, 143, 150)",
      "rgba(0, 0, 0, 0)",
      "1",
    ]);
  });

  it("draws a held check box and radio, their boxes included, in the held words at full opacity, with their labels", () => {
    styled(
      <>
        <FormControlLabel
          control={<Checkbox disabled />}
          label="Copy the notes"
        />
        <FormControlLabel
          control={<Checkbox disabled defaultChecked />}
          label="Keep the files"
        />
        <FormControlLabel control={<Radio disabled />} label="Every step" />
      </>,
    );

    for (const name of ["Copy the notes", "Keep the files", "Every step"]) {
      const input = screen.getByRole(
        name === "Every step" ? "radio" : "checkbox",
        { name },
      );
      const control = getComputedStyle(input.parentElement!);
      const box = getComputedStyle(input.parentElement!.querySelector("svg")!);
      const label = getComputedStyle(screen.getByText(name));
      expect([
        control.color,
        control.backgroundColor,
        control.opacity,
        box.color,
        label.color,
        label.opacity,
      ]).toEqual([
        "rgb(128, 143, 150)",
        "rgba(0, 0, 0, 0)",
        "1",
        "rgb(128, 143, 150)",
        "rgb(128, 143, 150)",
        "1",
      ]);
    }
  });

  it.each([false, true])(
    "draws a held switch, on %s, as a held track and a white thumb each inside the held edge, the thumb where it stands",
    (on) => {
      styled(
        <FormControlLabel
          control={<Switch disabled checked={on} />}
          label="Notify"
        />,
      );

      const input = screen.getByRole("switch", { name: "Notify" });
      const base = input.parentElement!;
      const thumb = getComputedStyle(base.querySelector(".MuiSwitch-thumb")!);
      const track = getComputedStyle(base.nextElementSibling!);
      const label = getComputedStyle(screen.getByText("Notify"));
      expect(base.classList.contains("Mui-checked")).toBe(on);
      expect(getComputedStyle(base).transform).toBe(
        on ? "translateX(20px)" : "none",
      );
      expect([getComputedStyle(base).color, thumb.boxShadow]).toEqual([
        "rgb(255, 255, 255)",
        "0 0 0 1px #d6e0e4",
      ]);
      expect([track.backgroundColor, track.boxShadow, track.opacity]).toEqual([
        "rgb(238, 242, 244)",
        "inset 0 0 0 1px #d6e0e4",
        "1",
      ]);
      expect([label.color, label.opacity]).toEqual(["rgb(128, 143, 150)", "1"]);
    },
  );

  it("draws a held menu item in the held words on no ground, at full opacity", () => {
    styled(
      <MenuList>
        <MenuItem disabled>Archive</MenuItem>
        <MenuItem>Keep</MenuItem>
      </MenuList>,
    );

    const held = getComputedStyle(
      screen.getByRole("menuitem", { name: "Archive" }),
    );
    const open = getComputedStyle(
      screen.getByRole("menuitem", { name: "Keep" }),
    );
    expect([held.color, held.backgroundColor, held.opacity]).toEqual([
      "rgb(128, 143, 150)",
      "rgba(0, 0, 0, 0)",
      "1",
    ]);
    expect(open.color).not.toBe(held.color);
  });

  it("draws a held field on the held ground inside the held edge, its words and label held, its hint still in the supporting ink", () => {
    styled(
      <TextField
        label="Name"
        defaultValue="Oak crate"
        helperText="Only an owner can rename it."
        disabled
      />,
    );

    const input = screen.getByRole("textbox", { name: "Name" });
    const root = getComputedStyle(input.parentElement!);
    const edge = getComputedStyle(
      input.parentElement!.querySelector("fieldset")!,
    );
    const words = getComputedStyle(input);
    const label = getComputedStyle(
      screen.getByText("Name", { selector: "label" }),
    );
    const hint = getComputedStyle(
      screen.getByText("Only an owner can rename it."),
    );
    expect([root.backgroundColor, root.opacity, edge.borderColor]).toEqual([
      "rgb(238, 242, 244)",
      "1",
      "rgb(214, 224, 228)",
    ]);
    expect([
      words.color,
      words.getPropertyValue("-webkit-text-fill-color"),
      words.opacity,
    ]).toEqual(["rgb(128, 143, 150)", "rgb(128, 143, 150)", "1"]);
    expect([label.color, label.opacity]).toEqual(["rgb(128, 143, 150)", "1"]);
    expect([hint.color, hint.opacity]).toEqual(["rgb(88, 102, 108)", "1"]);
  });

  it.each([
    [
      "primary",
      "contained",
      "primary",
      "medium",
      "--variant-containedBg",
      "#144b6c",
    ],
    ["quiet", "text", "inherit", "small", "--variant-textBg", "#c7dded"],
  ] as const)(
    "keeps the actual %s hover fill and text above 4.5:1",
    (_tier, variant, color, size, property, expectedGround) => {
      styled(
        <Button variant={variant} color={color} size={size}>
          Act
        </Button>,
      );
      const button = screen.getByRole("button", { name: "Act" });
      const ground = stateRuleFor(button, "hover", property);
      expect(ground).toBe(expectedGround);
      const drawn = getComputedStyle(button);
      const text =
        variant === "contained"
          ? drawn.getPropertyValue("--variant-containedColor")
          : hexOf(drawn.color);
      expect(contrastRatio(text, ground!)).toBeGreaterThanOrEqual(4.5);
    },
  );

  it.each([
    ["primary hover", "#ffffff", "#144b6c"],
    ["quiet resting", "#144b6c", "#d3e6f4"],
    ["quiet hover", "#144b6c", "#c7dded"],
    ["icon resting", "#58666c", "#ffffff"],
    ["icon hover", "#192024", "#d3e6f4"],
    ["icon pressed", "#192024", "#c7dded"],
    ["quiet toggle on", "#144b6c", "#aecce2"],
    ["toggle resting", "#58666c", "#ffffff"],
    ["toggle chosen", "#1c5b81", "#daebf7"],
    [
      "rejected value",
      theme.palette.error.main,
      theme.palette.state.failed.ground,
    ],
    [
      "running step",
      theme.palette.state.running.text,
      theme.palette.state.running.ground,
    ],
    [
      "waiting step",
      theme.palette.state.waiting.text,
      theme.palette.state.waiting.ground,
    ],
    [
      "blocked step",
      theme.palette.state.blocked.text,
      theme.palette.state.blocked.ground,
    ],
    ["running step hover", theme.palette.state.running.text, "#f3f8fa"],
    ["waiting step hover", theme.palette.state.waiting.text, "#f3f8fa"],
    ["blocked step hover", theme.palette.state.blocked.text, "#f3f8fa"],
    ["key tag", "#4b575d", "#ffffff"],
    ["fact tag", "#3e4a4f", "#ffffff"],
  ])("keeps %s text above 4.5:1", (_place, text, ground) => {
    expect(contrastRatio(text, ground)).toBeGreaterThanOrEqual(4.5);
  });

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
    expect(cell.lineHeight).toBe("22px");
    expect(cell.verticalAlign).toBe("top");
    expect(cell.whiteSpace).not.toBe("nowrap");
    expect(inside.minHeight).toBe("32px");
    expect(outside.minHeight).toBe("36px");
    expect([inside.fontSize, inside.lineHeight, inside.fontWeight]).toEqual([
      "13px",
      "20px",
      "600",
    ]);
    expect(outside.fontSize).toBe(MEDIUM_BUTTON_TEXT);
  });

  it("sets a small secondary or neutral button in the small control's type and every medium tier in the control's", () => {
    styled(
      <>
        <Button size="small">Rename</Button>
        <Button color="inherit" size="small">
          Cancel
        </Button>
        <Button variant="contained">Create</Button>
        <Button>Outside</Button>
        <Button color="inherit">Close</Button>
        <Button color="warning">Remove</Button>
      </>,
    );

    for (const name of ["Rename", "Cancel"]) {
      const small = getComputedStyle(screen.getByRole("button", { name }));
      expect([
        small.minHeight,
        small.fontSize,
        small.lineHeight,
        small.fontWeight,
      ]).toEqual(["32px", "13px", "20px", "600"]);
    }
    for (const name of ["Create", "Outside", "Close", "Remove"]) {
      const medium = getComputedStyle(screen.getByRole("button", { name }));
      expect([
        medium.minHeight,
        medium.fontSize,
        medium.lineHeight,
        medium.fontWeight,
      ]).toEqual(["36px", MEDIUM_BUTTON_TEXT, "20px", "600"]);
    }
  });

  it("keeps a quiet button in a table cell at the quiet height", () => {
    styled(
      <Table>
        <TableBody>
          <TableRow>
            <TableCell>
              <Button variant="text" color="inherit" size="small">
                Try 2
              </Button>
              <Button>Rename</Button>
            </TableCell>
          </TableRow>
        </TableBody>
      </Table>,
    );

    const quiet = getComputedStyle(
      screen.getByRole("button", { name: "Try 2" }),
    );
    const other = getComputedStyle(
      screen.getByRole("button", { name: "Rename" }),
    );
    expect([quiet.minHeight, quiet.paddingTop, quiet.borderRadius]).toEqual([
      "26px",
      "0px",
      "6px",
    ]);
    expect([quiet.fontSize, quiet.lineHeight]).toEqual(["12px", "18px"]);
    expect([other.minHeight, other.paddingTop]).toEqual(["32px", "4px"]);
    expect([other.fontSize, other.lineHeight]).toEqual(["13px", "20px"]);
  });

  it.each(["medium", "small"] as const)(
    "draws a %s table's heading row 36 pixels high over a 2-pixel card-edge rule and its rows 44 over a 1-pixel rule, the words inset 12",
    (size) => {
      styled(
        <Table size={size}>
          <TableHead>
            <TableRow>
              <TableCell>Crate</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            <TableRow>
              <TableCell>Oak crate</TableCell>
            </TableRow>
          </TableBody>
        </Table>,
      );

      const drawn = (cell: HTMLElement) => {
        const style = getComputedStyle(cell);
        return [
          style.boxSizing,
          style.height,
          style.paddingTop,
          style.paddingBottom,
          style.paddingLeft,
          style.paddingRight,
          style.lineHeight,
          style.borderBottomWidth,
          style.borderBottomStyle,
          style.borderBottomColor,
        ];
      };
      expect(
        drawn(screen.getByRole("columnheader", { name: "Crate" })),
      ).toEqual([
        "border-box",
        "36px",
        "7px",
        "7px",
        "12px",
        "12px",
        "22px",
        "2px",
        "solid",
        "rgb(214, 224, 228)",
      ]);
      expect(drawn(screen.getByRole("cell", { name: "Oak crate" }))).toEqual([
        "border-box",
        "44px",
        "11px",
        "11px",
        "12px",
        "12px",
        "22px",
        "1px",
        "solid",
        "rgb(228, 235, 238)",
      ]);
    },
  );

  it("draws a dialog's title, a second-level heading, at the second level's size", () => {
    styled(
      <Dialog open>
        <DialogTitle>Add somebody</DialogTitle>
      </Dialog>,
    );

    const title = screen.getByRole("heading", { name: "Add somebody" });

    expect(title.tagName).toBe("H2");
    expect(getComputedStyle(title).fontSize).toBe("20px");
    expect(getComputedStyle(title).fontWeight).toBe("650");
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

    expect(ring?.outlineStyle).toBe("solid");
    expect(ring?.outlineColor).toBe(PRIMARY_COLOUR);
    expect(ring?.outlineWidth).toBe(RING_WIDTH);
    expect(ring?.outlineOffset).toBe(GLOBAL_RING_OFFSET);
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
