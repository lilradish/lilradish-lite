import { buttonClasses } from "@mui/material/Button";
import IconButton from "@mui/material/IconButton";
import Tooltip from "@mui/material/Tooltip";
import type { ReactElement } from "react";

import { heldOr } from "./Press";

// Held is drawn by the class a disabled button takes, which also lets no pointer through; this one is not disabled.
const HELD_SX = { [`&.${buttonClasses.disabled}`]: { pointerEvents: "auto" } };

/**
 * An icon button, unavailable rather than disabled as `Press` is, its words a tip while pointed at or focused; for
 * an act on every row, where words on each would outweigh the row. `label` is expected to begin with those words.
 */
export function IconPress({
  icon,
  warns,
  navigation,
  label,
  unavailable,
  onPress,
  children,
}: {
  /** Given no title of its own: its name already says what it shows. */
  readonly icon: ReactElement;
  /** As `Press` takes it. */
  readonly warns?: boolean;
  readonly navigation?: boolean;
  /** Its words with the row's name, as `Press` takes it. */
  readonly label: string;
  readonly unavailable: boolean;
  readonly onPress: () => void;
  /** Its words. */
  readonly children: string;
}) {
  return (
    <Tooltip title={children}>
      <IconButton
        size="small"
        color={
          warns === true
            ? "warning"
            : navigation === true
              ? "inherit"
              : undefined
        }
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
