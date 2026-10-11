import { dialogContentClasses } from "@mui/material/DialogContent";
import { dialogTitleClasses } from "@mui/material/DialogTitle";
import { createTheme, type Shadows } from "@mui/material/styles";
import type { TableCellProps } from "@mui/material/TableCell";
import type { CSSProperties } from "react";

import { TABLE_GEOMETRY } from "../collection/table";
import { TOKENS } from "./tokens";
import { typeRolesInRem } from "./typeRoles";

const TYPE_ROLES = typeRolesInRem(TOKENS.type.roles);

const HELD = {
  color: TOKENS.held.text,
  backgroundColor: TOKENS.held.ground,
  borderColor: TOKENS.held.edge,
} as const;

// Material lowers opacity on these; a held control is said by colour alone.
const HELD_WORDS = { opacity: 1, color: TOKENS.held.text } as const;

const FOCUS_RING = {
  outlineStyle: "solid",
  outlineWidth: 2,
  outlineColor: TOKENS.act.main,
  outlineOffset: 2,
} as const;

const ON_SELECTOR = "&[aria-pressed='true'], &[aria-checked='true']";

// Inside the cell, not its border, which in the collapsed border model takes
// its width out of the cell and moves the text.
export const PICKED_EDGE = `inset 3px 0 0 ${TOKENS.act.main}`;

function cellPadding({
  ownerState,
}: {
  readonly ownerState: Pick<TableCellProps, "padding" | "variant">;
}) {
  const { inset, headPadding, bodyPadding } = TABLE_GEOMETRY;
  return ownerState.padding === "normal"
    ? {
        padding: `${ownerState.variant === "head" ? headPadding : bodyPadding}px ${inset}px`,
      }
    : {};
}

type TokenStrings<T> = {
  [K in keyof T]: T[K] extends string ? string : TokenStrings<T[K]>;
};
type TypographyRoles = Record<
  Exclude<keyof typeof TOKENS.type.roles, "button">,
  CSSProperties
>;

declare module "@mui/material/styles" {
  interface PaletteColor {
    noteGround?: string;
    noteText?: string;
  }
  interface SimplePaletteColorOptions {
    noteGround?: string;
    noteText?: string;
  }
  interface Palette {
    surface: TokenStrings<typeof TOKENS.surface>;
    ink: TokenStrings<typeof TOKENS.ink>;
    act: TokenStrings<typeof TOKENS.act>;
    quiet: TokenStrings<typeof TOKENS.quiet>;
    state: TokenStrings<typeof TOKENS.state>;
    model: TokenStrings<typeof TOKENS.model>;
    kind: TokenStrings<typeof TOKENS.kind>;
    keyTag: TokenStrings<typeof TOKENS.keyTag>;
    factTag: TokenStrings<typeof TOKENS.factTag>;
    held: TokenStrings<typeof TOKENS.held>;
    code: TokenStrings<typeof TOKENS.code>;
    elevation: TokenStrings<typeof TOKENS.elevation>;
  }

  interface PaletteOptions {
    surface?: TokenStrings<typeof TOKENS.surface>;
    ink?: TokenStrings<typeof TOKENS.ink>;
    act?: TokenStrings<typeof TOKENS.act>;
    quiet?: TokenStrings<typeof TOKENS.quiet>;
    state?: TokenStrings<typeof TOKENS.state>;
    model?: TokenStrings<typeof TOKENS.model>;
    kind?: TokenStrings<typeof TOKENS.kind>;
    keyTag?: TokenStrings<typeof TOKENS.keyTag>;
    factTag?: TokenStrings<typeof TOKENS.factTag>;
    held?: TokenStrings<typeof TOKENS.held>;
    code?: TokenStrings<typeof TOKENS.code>;
    elevation?: TokenStrings<typeof TOKENS.elevation>;
  }

  interface TypographyVariants extends TypographyRoles {
    brand: CSSProperties;
    messageHeading: CSSProperties;
    proseHeading: CSSProperties;
    proseCode: CSSProperties;
    term: CSSProperties;
    identifier: CSSProperties;
    tag: CSSProperties;
    figure: CSSProperties;
    systemHint: CSSProperties;
    unsaved: CSSProperties;
    keyTag: CSSProperties;
    valueLabel: CSSProperties;
    stepListName: CSSProperties;
    stepListUnstartedName: CSSProperties;
    stepListState: CSSProperties;
    controlLabel: CSSProperties;
    supportingProse: CSSProperties;
    fieldName: CSSProperties;
    versionName: CSSProperties;
    versionNameEmpty: CSSProperties;
  }

  interface TypographyVariantsOptions extends Partial<TypographyRoles> {
    brand?: CSSProperties;
    messageHeading?: CSSProperties;
    proseHeading?: CSSProperties;
    proseCode?: CSSProperties;
    term?: CSSProperties;
    identifier?: CSSProperties;
    tag?: CSSProperties;
    figure?: CSSProperties;
    systemHint?: CSSProperties;
    unsaved?: CSSProperties;
    keyTag?: CSSProperties;
    valueLabel?: CSSProperties;
    stepListName?: CSSProperties;
    stepListUnstartedName?: CSSProperties;
    stepListState?: CSSProperties;
    controlLabel?: CSSProperties;
    supportingProse?: CSSProperties;
    fieldName?: CSSProperties;
    versionName?: CSSProperties;
    versionNameEmpty?: CSSProperties;
  }
}

declare module "@mui/material/Typography" {
  interface TypographyPropsVariantOverrides {
    brand: true;
    messageHeading: true;
  }
}

/** Contrast tests guard these colours on every surface that reads them. */
export const theme = createTheme({
  spacing: 10,
  cssVariables: false,
  // Material's own transitions keep their motion unless told to follow the
  // reader's setting for less.
  motion: { reducedMotion: "system" },
  shadows: Array.from({ length: 25 }, (_, level) =>
    level === 0 ? "none" : TOKENS.elevation.popup,
  ) as Shadows,
  // Material's own ring. Not a duplicate of the global rule below: a component
  // an overflow container would clip draws this one inset, which no outline can.
  focusVisible: FOCUS_RING,
  palette: {
    background: { default: TOKENS.surface.ground, paper: TOKENS.surface.card },
    divider: TOKENS.surface.rule,
    primary: { main: TOKENS.act.main },
    error: { main: TOKENS.state.failed.text },
    success: { main: TOKENS.state.done.text },
    warning: TOKENS.warning,
    info: { main: TOKENS.state.running.text },
    text: {
      primary: TOKENS.ink.primary,
      secondary: TOKENS.ink.secondary,
      disabled: TOKENS.held.text,
    },
    action: {
      active: TOKENS.ink.strong,
      hover: TOKENS.surface.rowHover,
      selected: TOKENS.act.current,
      disabled: TOKENS.held.text,
      disabledBackground: TOKENS.held.ground,
      focus: TOKENS.act.hover,
    },
    surface: TOKENS.surface,
    ink: TOKENS.ink,
    act: TOKENS.act,
    quiet: TOKENS.quiet,
    state: TOKENS.state,
    model: TOKENS.model,
    kind: TOKENS.kind,
    keyTag: TOKENS.keyTag,
    factTag: TOKENS.factTag,
    held: TOKENS.held,
    code: TOKENS.code,
    elevation: TOKENS.elevation,
  },
  /**
   * A component names the role — a variant, or `typography` in `sx` — and never
   * the values, so the type is decided here and only here.
   */
  typography: {
    fontFamily: TOKENS.type.family,
    ...TYPE_ROLES,
    h1: TYPE_ROLES.pageTitle,
    h2: TYPE_ROLES.partTitle,
    h3: TYPE_ROLES.sectionTitle,
    h4: TYPE_ROLES.sectionTitle,
    h5: TYPE_ROLES.sectionTitle,
    h6: { ...TYPE_ROLES.sectionTitle, color: TOKENS.ink.secondary },
    brand: TYPE_ROLES.partTitle,
    messageHeading: TYPE_ROLES.sectionTitle,
    proseHeading: TYPE_ROLES.sectionTitle,
    proseCode: { fontFamily: TOKENS.type.identifier, fontSize: "inherit" },
    body1: TYPE_ROLES.body,
    body2: TYPE_ROLES.bodyCompact,
    caption: TYPE_ROLES.label,
    subtitle1: TYPE_ROLES.subtitle,
    subtitle2: TYPE_ROLES.subtitleCompact,
    button: { ...TYPE_ROLES.button, textTransform: "none" },
    term: TYPE_ROLES.term,
    identifier: { fontFamily: TOKENS.type.identifier },
    tag: TYPE_ROLES.kindBadge,
    figure: { fontVariantNumeric: "tabular-nums" },
    systemHint: { ...TYPE_ROLES.systemHint, fontStyle: "italic" },
    unsaved: TYPE_ROLES.label,
    keyTag: { ...TYPE_ROLES.keyTag, fontFamily: TOKENS.type.identifier },
    valueLabel: TYPE_ROLES.valueLabel,
    stepListName: TYPE_ROLES.stepListName,
    stepListUnstartedName: TYPE_ROLES.stepListUnstartedName,
    stepListState: TYPE_ROLES.stepListState,
    controlLabel: TYPE_ROLES.label,
    supportingProse: TYPE_ROLES.helper,
    fieldName: TYPE_ROLES.fieldFactKind,
    versionName: TYPE_ROLES.versionName,
    versionNameEmpty: TYPE_ROLES.versionNameEmpty,
  },
  components: {
    MuiContainer: { defaultProps: { maxWidth: "xl" } },
    MuiButton: {
      defaultProps: {
        variant: "outlined",
        size: "medium",
        disableElevation: true,
      },
      styleOverrides: {
        root: ({ theme }) => ({
          borderRadius: TOKENS.shape.controlRadius,
          minHeight: TOKENS.size.control,
          minWidth: 88,
          ...theme.typography.button,
          ...theme.typography.control,
          variants: [
            {
              props: { size: "small" },
              style: {
                minWidth: 64,
                minHeight: TOKENS.size.controlSmall,
                ...theme.typography.controlSmall,
              },
            },
            {
              props: { variant: "contained", color: "primary" },
              style: {
                "@media (hover: hover)": {
                  "&:hover": { "--variant-containedBg": TOKENS.act.pressed },
                },
                "&:active": { "--variant-containedBg": TOKENS.act.pressed },
              },
            },
            {
              props: { variant: "text", color: "inherit", size: "small" },
              style: {
                minHeight: TOKENS.size.quiet,
                padding: "0 12px",
                borderRadius: TOKENS.shape.chipRadius,
                ...theme.typography.quietControl,
                color: TOKENS.quiet.text,
                backgroundColor: TOKENS.quiet.ground,
                border: "1px solid transparent",
                "@media (hover: hover)": {
                  "&:hover": {
                    "--variant-textBg": TOKENS.quiet.hover,
                    backgroundColor: TOKENS.quiet.hover,
                  },
                },
                "&:active": {
                  "--variant-textBg": TOKENS.quiet.hover,
                  backgroundColor: TOKENS.quiet.hover,
                },
                "&[aria-pressed='true']": {
                  "--variant-textBg": TOKENS.quiet.pressed,
                  backgroundColor: TOKENS.quiet.pressed,
                  boxShadow: `inset 0 0 0 1px ${TOKENS.act.main}`,
                },
                "&.Mui-disabled": HELD,
              },
            },
            {
              props: { color: "primary", variant: "outlined" },
              style: {
                "--variant-outlinedBorder": TOKENS.act.main,
                "--variant-outlinedBg": TOKENS.surface.card,
                "@media (hover: hover)": {
                  "&:hover": { "--variant-outlinedBg": TOKENS.act.hover },
                },
                "&:active": { "--variant-outlinedBg": TOKENS.act.hover },
                [ON_SELECTOR]: {
                  "--variant-outlinedBg": TOKENS.act.current,
                  "@media (hover: hover)": {
                    "&:hover": { "--variant-outlinedBg": TOKENS.act.current },
                  },
                  "&:active": { "--variant-outlinedBg": TOKENS.act.current },
                },
              },
            },
            {
              props: { color: "warning", variant: "outlined" },
              style: {
                "--variant-outlinedBorder": TOKENS.warning.main,
                "--variant-outlinedBg": TOKENS.surface.card,
                "@media (hover: hover)": {
                  "&:hover": {
                    "--variant-outlinedBg": TOKENS.warning.noteGround,
                  },
                },
                "&:active": {
                  "--variant-outlinedBg": TOKENS.warning.noteGround,
                },
              },
            },
            {
              props: {
                color: "inherit",
                size: "medium",
                variant: "outlined",
              },
              style: {
                color: TOKENS.ink.strong,
                borderColor: TOKENS.ink.controlEdge,
                "--variant-outlinedBg": TOKENS.surface.card,
                "@media (hover: hover)": {
                  "&:hover": {
                    "--variant-outlinedBg": TOKENS.act.hover,
                    borderColor: TOKENS.ink.secondary,
                  },
                },
                "&:active": {
                  "--variant-outlinedBg": TOKENS.act.hover,
                  borderColor: TOKENS.ink.secondary,
                },
              },
            },
          ],
          [`&.Mui-disabled.MuiButton-contained, &.Mui-disabled.MuiButton-outlined`]:
            HELD,
        }),
      },
    },
    MuiIconButton: {
      styleOverrides: {
        root: {
          border: 0,
          backgroundColor: "transparent",
          color: TOKENS.ink.secondary,
          width: 32,
          height: 32,
          padding: 0,
          borderRadius: 8,
          "& .MuiSvgIcon-root": { width: 16, height: 16 },
          "&[aria-expanded] .MuiSvgIcon-root": { width: 18, height: 18 },
          "@media (hover: hover)": {
            "&:hover": {
              backgroundColor: TOKENS.quiet.ground,
              color: TOKENS.ink.primary,
            },
          },
          "&:active": { backgroundColor: TOKENS.quiet.hover },
          variants: [
            { props: { color: "inherit" }, style: { color: "inherit" } },
          ],
          "&.Mui-disabled": {
            backgroundColor: "transparent",
            color: TOKENS.held.text,
          },
        },
      },
    },
    MuiToggleButtonGroup: {
      styleOverrides: {
        root: {
          display: "inline-grid",
          gridAutoFlow: "column",
          gridAutoColumns: "minmax(0, 1fr)",
          borderRadius: TOKENS.shape.controlRadius,
        },
      },
    },
    MuiToggleButton: {
      styleOverrides: {
        root: {
          minWidth: 120,
          minHeight: TOKENS.size.controlSmall,
          padding: "0 14px",
          ...TYPE_ROLES.link,
          borderColor: TOKENS.ink.controlEdge,
          color: TOKENS.ink.secondary,
          "&.Mui-selected": {
            backgroundColor: TOKENS.act.current,
            color: TOKENS.act.main,
            "&:hover": { backgroundColor: TOKENS.act.current },
          },
          "&.Mui-disabled": HELD,
        },
      },
    },
    MuiLink: {
      defaultProps: { underline: "hover" },
      styleOverrides: {
        root: {
          textUnderlineOffset: "0.18em",
          borderRadius: 2,
          "&.MuiLink-root": TYPE_ROLES.link,
        },
      },
    },
    MuiListItemButton: {
      styleOverrides: {
        root: ({ ownerState }) => ({
          paddingTop: ownerState.dense ? 4 : 10,
          paddingBottom: ownerState.dense ? 4 : 10,
          "&.Mui-disabled": HELD_WORDS,
        }),
      },
    },
    MuiMenuItem: { styleOverrides: { root: { "&.Mui-disabled": HELD_WORDS } } },
    MuiTab: { styleOverrides: { root: { "&.Mui-disabled": HELD_WORDS } } },
    MuiChip: {
      styleOverrides: { root: { "&.Mui-disabled": { opacity: 1, ...HELD } } },
    },
    MuiSwitch: {
      styleOverrides: {
        switchBase: {
          "&.Mui-disabled, &.Mui-checked.Mui-disabled": {
            color: TOKENS.surface.card,
          },
          "&.Mui-disabled .MuiSwitch-thumb": {
            boxShadow: `0 0 0 1px ${TOKENS.held.edge}`,
          },
          "&.Mui-disabled + .MuiSwitch-track, &.Mui-checked.Mui-disabled + .MuiSwitch-track":
            {
              opacity: 1,
              backgroundColor: TOKENS.held.ground,
              boxShadow: `inset 0 0 0 1px ${TOKENS.held.edge}`,
            },
        },
      },
    },
    MuiFormHelperText: {
      styleOverrides: {
        root: { "&.Mui-disabled": { color: TOKENS.ink.secondary } },
      },
    },
    MuiTableRow: {
      styleOverrides: {
        root: {
          "&.Mui-selected, &.Mui-selected:hover": {
            backgroundColor: TOKENS.act.current,
          },
          "&.Mui-selected > :not(style):not(:not(style) ~ *)": {
            boxShadow: PICKED_EDGE,
          },
        },
      },
    },
    MuiTableCell: {
      styleOverrides: {
        root: ({ theme }) => ({
          boxSizing: "border-box",
          verticalAlign: "top",
          "&[data-compact='true']": { width: "1%", whiteSpace: "nowrap" },
          borderBottomWidth: TABLE_GEOMETRY.bodyRule,
          borderBottomColor: theme.palette.surface.rule,
          ...theme.typography.tableCell,
          height: TABLE_GEOMETRY.rowHeight,
          "& .MuiButton-root": {
            minHeight: TOKENS.size.controlSmall,
            ...theme.typography.controlSmall,
            padding: "4px 10px",
          },
          "& .MuiButton-text.MuiButton-colorInherit.MuiButton-sizeSmall": {
            minHeight: TOKENS.size.quiet,
            ...theme.typography.quietControl,
            padding: "0 12px",
          },
        }),
        head: ({ theme }) => ({
          ...theme.typography.tableHead,
          whiteSpace: "nowrap",
          color: theme.palette.ink.secondary,
          height: TABLE_GEOMETRY.headHeight,
          borderBottomWidth: TABLE_GEOMETRY.headRule,
          borderBottomColor: theme.palette.surface.cardEdge,
        }),
        // Material resolves the size after the variant, so a padding set on
        // the head alone is always overridden; the head's is set here instead.
        sizeMedium: cellPadding,
        sizeSmall: cellPadding,
      },
    },
    MuiOutlinedInput: {
      styleOverrides: {
        root: {
          borderRadius: TOKENS.shape.controlRadius,
          "&.Mui-disabled": { backgroundColor: TOKENS.held.ground },
          "&.Mui-disabled .MuiOutlinedInput-notchedOutline": {
            borderColor: TOKENS.held.edge,
          },
        },
      },
    },
    // Material draws a subtitle as an h6 and a variant of the theme's own as a span. This
    // replaces Material's whole mapping, which it still falls back on for the rest.
    MuiTypography: {
      defaultProps: {
        variantMapping: {
          brand: "p",
          messageHeading: "h2",
          subtitle1: "p",
          subtitle2: "p",
        },
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
        // Material drops top padding under a title, clipping shrunk labels in
        // scrolling content. Dividers content retains it, so is excluded.
        root: ({ theme }) => ({
          [`.${dialogTitleClasses.root} + &:not(.${dialogContentClasses.dividers})`]:
            { paddingTop: theme.spacing(1.5) },
        }),
      },
    },
    MuiCssBaseline: {
      styleOverrides: (theme) => ({
        "@keyframes mui-auto-fill": {
          from: { animationName: "mui-auto-fill" },
        },
        "@keyframes mui-auto-fill-cancel": {
          from: { animationName: "mui-auto-fill-cancel" },
        },
        // All a keyboard has to go on, so never removed without a replacement.
        // Skips ButtonBase only: a Link rings itself in the same colour,
        // so either paints the same cue.
        ":focus-visible:not(.MuiButtonBase-root)": FOCUS_RING,
        "a[href]:not(.MuiButtonBase-root):not(.MuiLink-root)": {
          color: theme.palette.primary.main,
          ...TYPE_ROLES.link,
          textDecoration: "none",
          textUnderlineOffset: "0.18em",
          borderRadius: 2,
          "&:hover": { textDecoration: "underline" },
        },
        // Otherwise in the user agent's own monospace face and size.
        code: { ...theme.typography.identifier, fontSize: "inherit" },
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
