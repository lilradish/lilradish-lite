import ExpandLessIcon from "@mui/icons-material/ExpandLess";
import ExpandMoreIcon from "@mui/icons-material/ExpandMore";
import Button from "@mui/material/Button";
import type { FocusEventHandler, ReactNode } from "react";

// Set as the words it stands in rather than as a button, so they read where they would without it.
const DISCLOSURE_SX = { font: "inherit", p: 0, minWidth: 0 };

/**
 * The caller holds whether it is open, and draws what it controls under `controls`.
 */
export function Disclosure({
  open,
  controls,
  onToggle,
  describedBy,
  onFocus,
  onBlur,
  children,
}: {
  readonly open: boolean;
  readonly controls: string;
  readonly onToggle: () => void;
  readonly describedBy?: string;
  readonly onFocus?: FocusEventHandler<HTMLButtonElement>;
  readonly onBlur?: FocusEventHandler<HTMLButtonElement>;
  readonly children: ReactNode;
}) {
  return (
    <Button
      variant="text"
      color="inherit"
      size="medium"
      aria-expanded={open}
      aria-controls={controls}
      aria-describedby={describedBy}
      onFocus={onFocus}
      onBlur={onBlur}
      endIcon={open ? <ExpandLessIcon /> : <ExpandMoreIcon />}
      onClick={onToggle}
      sx={DISCLOSURE_SX}
    >
      {children}
    </Button>
  );
}
