import Box from "@mui/material/Box";
import type { ReactNode } from "react";

export function ActGroup({ children }: { readonly children: ReactNode }) {
  return (
    <Box
      sx={{
        display: "inline-grid",
        gridAutoFlow: "column",
        gridAutoColumns: "minmax(0, 1fr)",
        gap: 1,
        alignItems: "start",
      }}
    >
      {children}
    </Box>
  );
}
