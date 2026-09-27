import Box from "@mui/material/Box";
import Button, { buttonClasses } from "@mui/material/Button";
import { useId, type ReactNode } from "react";

import { Notice, type Severity } from "../notice/Notice";

/** Why a control is held: never a refusal, so never an error. */
export interface Reason {
  readonly severity: Exclude<Severity, "error">;
  /** Never empty. Shown as given, so a name in it arrives isolated by `isolatedInText`. */
  readonly words: string;
}

// Always there, whatever the reason does: a wrapper that came and went with it
// would take the focused button with it.
const PRESS_SX = {
  display: "inline-flex",
  flexDirection: "column",
  alignItems: "flex-start",
  gap: 0.5,
};

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
  label,
  unavailable,
  reason,
  onPress,
  children,
}: {
  /** Contained for the one control a page leads with. */
  readonly variant?: "contained";
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
        variant={variant}
        aria-label={label}
        aria-disabled={held || undefined}
        aria-describedby={reason === undefined ? undefined : reasonId}
        // Material's own disabled look, which it keys on this class alone.
        className={held ? buttonClasses.disabled : undefined}
        disableFocusRipple={held}
        onClick={held ? undefined : onPress}
      >
        {children}
      </Button>
      {reason === undefined ? null : (
        <Notice id={reasonId} severity={reason.severity}>
          {reason.words}
        </Notice>
      )}
    </Box>
  );
}
