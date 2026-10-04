import Box from "@mui/material/Box";
import Button, { buttonClasses } from "@mui/material/Button";
import Typography from "@mui/material/Typography";
import { useId, type ReactNode } from "react";

import { Notice, type Severity } from "../notice/Notice";

/**
 * Why a control is held: never a refusal, so never an error. One of `info` is
 * said as a quiet line, one of `warning` as a notice.
 */
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

/** What a button is given to be held as `Press` holds one, or else to answer a press. */
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
  variant,
  navigation,
  warns,
  label,
  unavailable,
  reason,
  onPress,
  children,
}: {
  readonly variant?: "contained" | "neutral";
  /** A compact control for moving through a collection. */
  readonly navigation?: boolean;
  /** In the warning colour, for an act that stops something or takes it out of use; its words still say which. */
  readonly warns?: boolean;
  /**
   * Its name where its words alone are one of several alike, as on every row
   * of a table; holding those words, so what is seen is what can be said.
   */
  readonly label?: string;
  readonly unavailable?: boolean;
  /**
   * Why it cannot be pressed, which makes it unavailable whatever
   * `unavailable` says.
   */
  readonly reason?: Reason;
  readonly onPress: () => void;
  readonly children: ReactNode;
}) {
  const reasonId = useId();
  const held = unavailable === true || reason !== undefined;
  return (
    <Box sx={PRESS_SX}>
      <Button
        variant={variant === "neutral" ? "outlined" : variant}
        size={navigation === true ? "small" : undefined}
        color={
          warns === true
            ? "warning"
            : navigation === true || variant === "neutral"
              ? "inherit"
              : undefined
        }
        aria-label={label}
        aria-describedby={reason === undefined ? undefined : reasonId}
        {...heldOr(held, onPress)}
      >
        {children}
      </Button>
      {reason?.visible === undefined ? null : (
        <span id={reasonId} hidden>
          {reason.words}
        </span>
      )}
      {reason === undefined ? null : reason.severity === "info" ? (
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
