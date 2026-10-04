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

const HEADING = { fontWeight: 600, lineHeight: 1.3 };

/** A page's own parts are headed at the second level, and the product's name is set as large. */
const PART_HEADING_SIZE = "1.375rem";

const FAILURE = "#8c1d18";

const SUCCESS = "#1b5e20";

const WARNING = "#9a4f00";

const SUPPORTING_TEXT = "#595959";

const ACTION = {
  ground: "#eaf1fb",
  border: "#356da8",
  hover: "#dbeafa",
  pressed: "#c8def5",
  warningGround: "#fff1dc",
  warningHover: "#ffe4ba",
  warningPressed: "#ffdcaa",
  navigationGround: "#f0f3f6",
  navigationBorder: "#526579",
  navigationHover: "#e2e9f0",
  navigationPressed: "#d8e3ee",
} as const;

type StepKindColours = Record<
  "question" | "workflow" | "code_step" | "route" | "search",
  { main: string; ground: string }
>;

type StepStateColours = Record<
  "not_started" | "running" | "held_back" | "waiting" | "failed" | "done",
  string
>;

declare module "@mui/material/styles" {
  interface Palette {
    // createPalette augments the keys it knows and deep-merges the rest
    // verbatim, so PaletteColor would promise shades that nothing computes.
    generated: { main: string };
    part: { mine: string };
    stepKind: StepKindColours;
    stepState: StepStateColours;
  }

  interface PaletteOptions {
    generated?: { main: string };
    part?: { mine: string };
    stepKind?: StepKindColours;
    stepState?: StepStateColours;
  }

  interface TypographyVariants {
    brand: CSSProperties;
    term: CSSProperties;
    identifier: CSSProperties;
    tag: CSSProperties;
    figure: CSSProperties;
    systemHint: CSSProperties;
  }

  interface TypographyVariantsOptions {
    brand?: CSSProperties;
    term?: CSSProperties;
    identifier?: CSSProperties;
    tag?: CSSProperties;
    figure?: CSSProperties;
    systemHint?: CSSProperties;
  }
}

declare module "@mui/material/Typography" {
  interface TypographyPropsVariantOverrides {
    brand: true;
  }
}

/** Contrast tests guard these colours on every surface that reads them. */
export const theme = createTheme({
  spacing: 10,
  // Material's own ring. Not a duplicate of the global rule below: a component
  // an overflow container would clip draws this one inset, which no outline can.
  focusVisible: true,
  palette: {
    background: { default: "#f5f7fa", paper: "#ffffff" },
    primary: { main: "#0b57d0" },
    error: { main: FAILURE },
    success: { main: SUCCESS },
    // Material's own orange draws a notice's icon under 3:1 on its ground,
    // and its own blue barely over, once the icon's opacity is counted.
    warning: { main: WARNING },
    info: { main: "#0277bd" },
    text: {
      primary: "#1a1a1a",
      // Material's default is translucent — rgba(0, 0, 0, 0.6) — so its ratio
      // is a property of whatever it lands on rather than of the colour.
      secondary: SUPPORTING_TEXT,
    },
    generated: { main: "#5b3fa8" },
    part: { mine: "#eaf1fb" },
    stepKind: {
      question: { main: "#00695c", ground: "#e0f2f1" },
      workflow: { main: "#283593", ground: "#e8eaf6" },
      code_step: { main: "#37474f", ground: "#eceff1" },
      route: { main: "#5d4037", ground: "#efebe9" },
      search: { main: "#6a1b9a", ground: "#f3e5f5" },
    },
    stepState: {
      not_started: SUPPORTING_TEXT,
      running: "#01579b",
      held_back: SUPPORTING_TEXT,
      waiting: WARNING,
      failed: FAILURE,
      done: SUCCESS,
    },
  },
  /**
   * Every face, size and weight a screen sets, by the role it plays. A
   * component names the role — a variant, or `typography` in `sx` — and never
   * the values, so the type is decided here and only here.
   */
  typography: {
    /**
     * The reader's own system face. Material names Roboto first, which nothing
     * here loads, so the face would be whichever one each machine falls back on.
     */
    fontFamily: 'system-ui, "Segoe UI", Roboto, Helvetica, Arial, sans-serif',
    /** One weight, and no heading smaller than body text. */
    h1: { ...HEADING, fontSize: "1.75rem" },
    h2: { ...HEADING, fontSize: PART_HEADING_SIZE },
    h3: { ...HEADING, fontSize: "1.25rem" },
    h4: { ...HEADING, fontSize: "1.125rem" },
    h5: { ...HEADING, fontSize: "1.0625rem" },
    h6: { ...HEADING, fontSize: "1rem", color: SUPPORTING_TEXT },
    /** The product's name where it stands for the product, set as large as a page's part and no heading. */
    brand: { fontSize: PART_HEADING_SIZE, fontWeight: HEADING.fontWeight },
    body1: { lineHeight: 1.65 },
    body2: { lineHeight: 1.6 },
    caption: { lineHeight: 1.5 },
    subtitle1: { fontSize: "1.0625rem", fontWeight: 600, lineHeight: 1.5 },
    subtitle2: { fontWeight: 600, lineHeight: 1.5 },
    /** Material sets a button in capitals, which no other words here are. */
    button: { textTransform: "none" },
    /** What a value is called, beside the value: a term, a current place. */
    term: { fontWeight: 700 },
    /** Read character by character, so in a fixed pitch, at the size around it. */
    identifier: { fontFamily: IDENTIFIER_FONT },
    /**
     * A short word set apart from what it labels. Not `overline`, which is set
     * in the regular weight at 2.66 line heights: a tag is set apart by weight.
     */
    tag: { fontWeight: 700, fontSize: "0.75rem" },
    /** A figure read against those above and below it, in digits of one width, at the size around it. */
    figure: { fontVariantNumeric: "tabular-nums" },
    systemHint: { fontSize: "inherit", fontWeight: 400, fontStyle: "italic" },
  },
  components: {
    MuiContainer: { defaultProps: { maxWidth: "xl" } },
    MuiButton: {
      defaultProps: { variant: "outlined", size: "medium" },
      styleOverrides: {
        root: ({ theme: given }) => ({
          borderRadius: 10,
          minHeight: 40,
          fontWeight: 500,
          variants: [
            {
              props: { color: "primary", variant: "outlined" },
              style: {
                "--variant-outlinedBorder": ACTION.border,
                "--variant-outlinedBg": ACTION.ground,
                "@media (hover: hover)": {
                  "&:hover": { "--variant-outlinedBg": ACTION.hover },
                },
                "&:active": { "--variant-outlinedBg": ACTION.pressed },
              },
            },
            {
              props: { color: "warning", variant: "outlined" },
              style: {
                "--variant-outlinedBorder": WARNING,
                "--variant-outlinedBg": ACTION.warningGround,
                "@media (hover: hover)": {
                  "&:hover": { "--variant-outlinedBg": ACTION.warningHover },
                },
                "&:active": { "--variant-outlinedBg": ACTION.warningPressed },
              },
            },
            {
              props: {
                color: "inherit",
                size: "medium",
                variant: "outlined",
              },
              style: {
                color: ACTION.navigationBorder,
                borderColor: ACTION.navigationBorder,
                "--variant-outlinedBg": ACTION.navigationGround,
                "@media (hover: hover)": {
                  "&:hover": {
                    "--variant-outlinedBg": ACTION.navigationHover,
                  },
                },
                "&:active": {
                  "--variant-outlinedBg": ACTION.navigationPressed,
                },
              },
            },
            {
              props: {
                color: "inherit",
                size: "small",
                variant: "outlined",
              },
              style: {
                minHeight: 32,
                fontWeight: 600,
                color: ACTION.navigationBorder,
                borderColor: ACTION.navigationBorder,
                "--variant-outlinedBg": ACTION.navigationGround,
                "@media (hover: hover)": {
                  "&:hover": {
                    "--variant-outlinedBg": ACTION.navigationHover,
                  },
                },
                "&:active": {
                  "--variant-outlinedBg": ACTION.navigationPressed,
                },
              },
            },
          ],
          [`&.Mui-disabled`]: {
            backgroundColor: given.palette.action.disabledBackground,
          },
        }),
      },
    },
    MuiIconButton: {
      styleOverrides: {
        root: ({ theme: given }) => ({
          border: `1px solid ${ACTION.border}`,
          backgroundColor: ACTION.ground,
          color: given.palette.primary.main,
          "@media (hover: hover)": {
            "&:hover": { backgroundColor: ACTION.hover },
          },
          "&:active": { backgroundColor: ACTION.pressed },
          variants: [
            {
              props: { color: "warning" },
              style: {
                borderColor: WARNING,
                backgroundColor: ACTION.warningGround,
                color: WARNING,
                "@media (hover: hover)": {
                  "&:hover": { backgroundColor: ACTION.warningHover },
                },
                "&:active": { backgroundColor: ACTION.warningPressed },
              },
            },
            {
              props: { color: "inherit" },
              style: {
                borderColor: ACTION.navigationBorder,
                backgroundColor: ACTION.navigationGround,
                color: ACTION.navigationBorder,
                "@media (hover: hover)": {
                  "&:hover": { backgroundColor: ACTION.navigationHover },
                },
                "&:active": {
                  backgroundColor: ACTION.navigationPressed,
                },
              },
            },
          ],
          "&.Mui-disabled": {
            backgroundColor: given.palette.action.disabledBackground,
          },
        }),
      },
    },
    MuiLink: {
      defaultProps: { underline: "hover" },
      styleOverrides: {
        root: {
          textUnderlineOffset: "0.18em",
          borderRadius: 2,
          "&.MuiLink-root": { fontWeight: 600 },
        },
      },
    },
    MuiListItemButton: {
      styleOverrides: {
        root: ({ ownerState }) => ({
          paddingTop: ownerState.dense ? 4 : 10,
          paddingBottom: ownerState.dense ? 4 : 10,
        }),
      },
    },
    MuiTableCell: {
      styleOverrides: {
        root: ({ theme: given }) => ({
          verticalAlign: "top",
          fontFamily: given.typography.fontFamily,
          fontSize: given.typography.body2.fontSize,
          lineHeight: given.typography.body2.lineHeight,
          "& .MuiButton-root": {
            minHeight: 32,
            fontSize: given.typography.pxToRem(13),
            padding: "4px 10px",
          },
        }),
        sizeMedium: ({ ownerState }) =>
          ownerState.padding === "normal" ? { padding: "16px 18px" } : {},
        sizeSmall: ({ ownerState }) =>
          ownerState.padding === "normal" ? { padding: "12px 16px" } : {},
      },
    },
    MuiOutlinedInput: {
      styleOverrides: { root: { borderRadius: 10 } },
    },
    // Material draws a subtitle as an h6 and a variant of the theme's own as a span. This
    // replaces Material's whole mapping, which it still falls back on for the rest.
    MuiTypography: {
      defaultProps: {
        variantMapping: { brand: "p", subtitle1: "p", subtitle2: "p" },
      },
    },
    // Material draws the title, an h2, in the h6 variant.
    MuiDialogTitle: { defaultProps: { variant: "h2" } },
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
        "@keyframes mui-auto-fill": {
          from: { animationName: "mui-auto-fill" },
        },
        "@keyframes mui-auto-fill-cancel": {
          from: { animationName: "mui-auto-fill-cancel" },
        },
        // All a keyboard has to go on, so never removed without a replacement.
        // Holds off ButtonBase only — a Link rings itself and matches this too,
        // in the same colour, so whichever paints says the same thing.
        ":focus-visible:not(.MuiButtonBase-root)": {
          outlineStyle: "solid",
          outlineWidth: 3,
          outlineColor: given.palette.primary.main,
          outlineOffset: 2,
        },
        "a[href]:not(.MuiButtonBase-root):not(.MuiLink-root)": {
          color: given.palette.primary.main,
          fontWeight: 600,
          textDecoration: "none",
          textUnderlineOffset: "0.18em",
          borderRadius: 2,
          "&:hover": { textDecoration: "underline" },
        },
        // Otherwise in the user agent's own monospace face and size.
        code: { ...given.typography.identifier, fontSize: "inherit" },
      }),
    },
    MuiInputBase: {
      defaultProps: { disableInjectingGlobalStyles: true },
      styleOverrides: {
        input: {
          animationName: "mui-auto-fill-cancel",
          animationDuration: "10ms",
          "&:-webkit-autofill": {
            animationDuration: "5000s",
            animationName: "mui-auto-fill",
          },
        },
      },
    },
  },
});
