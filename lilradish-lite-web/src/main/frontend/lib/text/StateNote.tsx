import Box from "@mui/material/Box";
import type { ReactNode } from "react";

// A line of its own, so a long name in it wraps there and is never cut short.
const NOTE_SX = {
  display: "block",
  mt: "2px",
  typography: "supportingProse",
  color: "ink.secondary",
  overflowWrap: "anywhere",
};

export function StateNote({ children }: { readonly children: ReactNode }) {
  return (
    <Box component="span" sx={NOTE_SX}>
      {children}
    </Box>
  );
}
