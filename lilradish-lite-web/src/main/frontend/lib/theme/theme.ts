import { dialogContentClasses } from "@mui/material/DialogContent";
import { dialogTitleClasses } from "@mui/material/DialogTitle";
import { createTheme } from "@mui/material/styles";
import type { CSSProperties } from "react";

/**
 * Identifiers are read character by character, so they are set in a fixed
 * pitch. `ui-monospace` alone is not enough: an engine that does not know that
 * generic falls back to the inherited proportional face.
 */
export const IDENTIFIER_FONT = "ui-monospace, monospace";

declare module "@mui/material/styles" {
  interface Palette {
    // createPalette augments the keys it knows and deep-merges the rest
    // verbatim, so PaletteColor would promise shades that nothing computes.
    generated: { main: string };
  }

  interface PaletteOptions {
    generated?: { main: string };
  }

  interface TypographyVariants {
    term: CSSProperties;
    identifier: CSSProperties;
    tag: CSSProperties;
  }

  interface TypographyVariantsOptions {
    term?: CSSProperties;
    identifier?: CSSProperties;
    tag?: CSSProperties;
  }
}

/**
 * The colours every screen reads its meaning from.
 *
 * `generated` is the one Material does not carry. What a model wrote is marked
 * wherever it appears, and a mark only reads as one thing if one value defines
 * it — so it belongs here rather than in each component that renders such text.
 *
 * Every colour is picked for a measured contrast ratio against the surface it
 * is read on rather than by eye. `theme.test.tsx` recomputes those ratios, so a
 * colour nudged below its threshold fails the build instead of an audit.
 */
export const theme = createTheme({
  // Material's own ring. Not a duplicate of the global rule below: a component
  // an overflow container would clip draws this one inset, which no outline can.
  focusVisible: true,
  palette: {
    primary: { main: "#0b57d0" },
    error: { main: "#8c1d18" },
    success: { main: "#1b5e20" },
    // Material's own orange draws a notice's icon under 3:1 on its ground,
    // and its own blue barely over, once the icon's opacity is counted.
    warning: { main: "#b35c00" },
    info: { main: "#0277bd" },
    text: {
      primary: "#1a1a1a",
      // Material's default is translucent — rgba(0, 0, 0, 0.6) — so its ratio
      // is a property of whatever it lands on rather than of the colour.
      secondary: "#595959",
    },
    generated: { main: "#5b3fa8" },
  },
  /**
   * Every face, size and weight a screen sets, by the role it plays. A
   * component names the role — a variant, or `typography` in `sx` — and never
   * the values, so the type is decided here and only here.
   */
  typography: {
    /** What a value is called, beside the value: a term, a current place. */
    term: { fontWeight: 700 },
    /** Read character by character, so in a fixed pitch, at the size around it. */
    identifier: { fontFamily: IDENTIFIER_FONT },
    /**
     * A short word set apart from what it labels. Not `overline`, which is set
     * in the regular weight at 2.66 line heights: a tag is set apart by weight.
     */
    tag: {
      fontWeight: 700,
      fontSize: "0.75rem",
      letterSpacing: "0.08em",
      textTransform: "uppercase",
    },
  },
  components: {
    MuiButton: { defaultProps: { variant: "outlined", size: "small" } },
    MuiDialogActions: {
      // A control with its reason under it stands taller than one without,
      // and Material centres them, which puts the buttons at different heights.
      styleOverrides: { root: { alignItems: "flex-start" } },
    },
    MuiDialogContent: {
      styleOverrides: {
        // The content scrolls, and under a title Material takes its top padding
        // away; a shrunk outlined label rises above its field and is clipped.
        // Content with dividers keeps Material's own, which it never took away.
        root: ({ theme: given }) => ({
          [`.${dialogTitleClasses.root} + &:not(.${dialogContentClasses.dividers})`]:
            { paddingTop: given.spacing(1.5) },
        }),
      },
    },
    MuiCssBaseline: {
      styleOverrides: (given) => ({
        // All a keyboard has to go on, so never removed without a replacement.
        // Holds off ButtonBase only — a Link rings itself and matches this too,
        // in the same colour, so whichever paints says the same thing.
        ":focus-visible:not(.MuiButtonBase-root)": {
          outlineStyle: "solid",
          outlineWidth: 3,
          outlineColor: given.palette.primary.main,
          outlineOffset: 2,
        },
        // Each screen's top-level blocks become cards here rather than in every
        // component. The child combinator spares a nested one a second frame.
        ".MuiContainer-root > section": {
          border: `1px solid ${given.palette.divider}`,
          borderRadius: 4,
          padding: "1.5rem",
          marginBottom: "1.5rem",
        },
      }),
    },
  },
});
