import { buttonClasses } from "@mui/material/Button";
import IconButton from "@mui/material/IconButton";
import Tooltip from "@mui/material/Tooltip";
import type { ReactElement } from "react";

import { heldOr } from "./Press";

// Held is drawn by the class a disabled button takes, which also lets no pointer through; this one is not disabled.
const HELD_SX = { [`&.${buttonClasses.disabled}`]: { pointerEvents: "auto" } };

/**
 * Keeps unavailable controls focusable as `Press` does, with a tip on hover or focus.
 * Icons avoid words outweighing every row; `label` must begin with the tip's words.
 */
export function IconPress({
  icon,
  label,
  unavailable,
  onPress,
  children,
}: {
  /** Given no title of its own: the button already has a name. */
  readonly icon: ReactElement;
  /** Its words with the row's name, as `Press` takes it. */
  readonly label: string;
  readonly unavailable: boolean;
  readonly onPress: () => void;
  readonly children: string;
}) {
  return (
    <Tooltip title={children} describeChild>
      <IconButton
        size="small"
        aria-label={label}
        disableRipple={unavailable}
        {...heldOr(unavailable, onPress)}
        sx={HELD_SX}
      >
        {icon}
      </IconButton>
    </Tooltip>
  );
}
