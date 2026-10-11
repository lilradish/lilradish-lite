import CheckIcon from "@mui/icons-material/Check";
import Box from "@mui/material/Box";
import Button, { buttonClasses } from "@mui/material/Button";
import CircularProgress from "@mui/material/CircularProgress";
import Typography from "@mui/material/Typography";
import { useId, type ReactNode } from "react";

import { say } from "../../i18n/lib";
import { UNSEEN_SX } from "../layout/parts";
import { Notice, type Severity } from "../notice/Notice";
import { useShownAfter } from "./useShownAfter";

export type ActionTier =
  "primary" | "secondary" | "neutral" | "quiet" | "danger" | "dangerConfirm";

const TIERS = {
  primary: { variant: "contained", color: "primary", size: "medium" },
  secondary: { variant: "outlined", color: "primary", size: "medium" },
  neutral: { variant: "outlined", color: "inherit", size: "medium" },
  quiet: { variant: "text", color: "inherit", size: "small" },
  danger: { variant: "outlined", color: "warning", size: "medium" },
  dangerConfirm: { variant: "contained", color: "warning", size: "medium" },
} as const;

/** For a quiet button Press cannot draw, as one that hands its node to a ref or carries an icon. */
export const QUIET = TIERS.quiet;

/** Why a control is held: never a refusal, so never an error. */
export interface Reason {
  readonly severity: Exclude<Severity, "error">;
  /** Never empty; a name in it must be isolated by `isolatedInText`. */
  readonly words: string;
  /** The same words drawn as nodes; `words` remains the complete description. */
  readonly visible?: ReactNode;
}

// Always there, whatever the reason does: a wrapper that came and went with it
// would take the focused button with it.
const PRESS_SX = {
  display: "inline-flex",
  flexDirection: "column",
  alignItems: "flex-start",
  gap: 0.5,
};

const FILLING_PRESS_SX = {
  ...PRESS_SX,
  width: "100%",
  minWidth: 0,
  "& > :not(button)": { width: 0, minWidth: "100%" },
};

const BUTTON_SX = { position: "relative" };

const BUSY_BUTTON_SX = { ...BUTTON_SX, cursor: "default" };

const SPINNER_SX = {
  position: "absolute",
  inset: 0,
  display: "flex",
  alignItems: "center",
  justifyContent: "center",
};

const ON_WORDS_SX = {
  display: "inline-flex",
  alignItems: "center",
  gap: "6px",
};

// The words stay laid out under the spinner, so the button keeps its width.
const UNSEEN_WORDS_SX = { color: "transparent" };

const UNSEEN_ON_WORDS_SX = { ...ON_WORDS_SX, ...UNSEEN_WORDS_SX };

const ON_CHECK_SX = { width: 14, height: 14 };

/** The words of a secondary toggle, said to be on by a check before them where it is, not by colour alone. */
export function OnWords({
  on,
  unseen = false,
  children,
}: {
  readonly on: boolean;
  /** Kept in place, unseen, under something drawn over them. */
  readonly unseen?: boolean;
  readonly children: ReactNode;
}) {
  return (
    <Box
      component="span"
      sx={
        on
          ? unseen
            ? UNSEEN_ON_WORDS_SX
            : ON_WORDS_SX
          : unseen
            ? UNSEEN_WORDS_SX
            : undefined
      }
    >
      {on ? <CheckIcon aria-hidden sx={ON_CHECK_SX} /> : null}
      {children}
    </Box>
  );
}

export function heldOr(held: boolean, onPress: () => void) {
  return {
    "aria-disabled": held || undefined,
    // Material's own disabled look, which each of its buttons keys on this class alone.
    className: held ? buttonClasses.disabled : undefined,
    disableFocusRipple: held,
    onClick: held ? undefined : onPress,
  };
}

/**
 * A button said to be unavailable rather than disabled. A disabled control is
 * no longer somewhere focus can be, and the one just pressed is often the one a
 * press leaves with nowhere to go — so the keyboard would restart from the top.
 *
 * One drawn and unavailable for a reason says it, right under the button and
 * as its description.
 */
export function Press({
  tier,
  small,
  busy = false,
  fill = false,
  label,
  "aria-pressed": pressed,
  "aria-expanded": expanded,
  "aria-controls": controls,
  unavailable,
  reason,
  reasonDescribedBy,
  onPress,
  children,
}: {
  readonly tier?: ActionTier;
  /** Drawn at the small control size; a quiet act is that size already. */
  readonly small?: true;
  /** Starts holding the button immediately; only its spinner is delayed. */
  readonly busy?: boolean;
  readonly fill?: boolean;
  /**
   * Its name where its words alone are one of several alike, as on every row
   * of a table; holding those words, so what is seen is what can be said.
   */
  readonly label?: string;
  readonly "aria-pressed"?: boolean;
  readonly "aria-expanded"?: boolean;
  readonly "aria-controls"?: string;
  readonly unavailable?: boolean;
  readonly reason?: Reason;
  readonly reasonDescribedBy?: string;
  readonly onPress: () => void;
  readonly children: ReactNode;
}) {
  const reasonId = useId();
  const held = (unavailable === true && !busy) || reason !== undefined;
  const spinning = useShownAfter(busy && !held);
  const on = pressed === true && (tier ?? "secondary") === "secondary";
  const drawn = TIERS[tier ?? "secondary"];
  return (
    <Box sx={fill ? FILLING_PRESS_SX : PRESS_SX}>
      <Button
        {...drawn}
        size={small ? "small" : drawn.size}
        fullWidth={fill}
        aria-pressed={pressed}
        aria-expanded={expanded}
        aria-controls={controls}
        aria-label={
          label === undefined
            ? undefined
            : label + (spinning ? say("action.underway") : "")
        }
        aria-describedby={
          reason === undefined ? undefined : (reasonDescribedBy ?? reasonId)
        }
        {...heldOr(held, onPress)}
        aria-disabled={held || busy || undefined}
        disableRipple={held || busy}
        onClick={held || busy ? undefined : onPress}
        sx={busy ? BUSY_BUTTON_SX : BUTTON_SX}
      >
        <OnWords on={on} unseen={spinning}>
          {children}
        </OnWords>
        {spinning ? (
          <>
            {label === undefined ? (
              <Box component="span" sx={UNSEEN_SX}>
                {say("action.underway")}
              </Box>
            ) : null}
            <Box component="span" aria-hidden sx={SPINNER_SX}>
              <CircularProgress size={16} color="inherit" />
            </Box>
          </>
        ) : null}
      </Button>
      {reasonDescribedBy !== undefined ||
      reason?.visible === undefined ? null : (
        <span id={reasonId} hidden>
          {reason.words}
        </span>
      )}
      {reason === undefined ||
      reasonDescribedBy !== undefined ? null : reason.severity === "info" ? (
        <Typography
          id={reason.visible === undefined ? reasonId : undefined}
          variant="body2"
          color="textSecondary"
        >
          {reason.visible ?? reason.words}
        </Typography>
      ) : (
        <Notice
          id={reason.visible === undefined ? reasonId : undefined}
          severity={reason.severity}
        >
          {reason.visible ?? reason.words}
        </Notice>
      )}
    </Box>
  );
}
