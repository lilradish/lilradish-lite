import Box from "@mui/material/Box";
import type { ReactNode } from "react";

/** All labels keep their width; only the chosen one is read or seen. */
export function Widest({
  labels,
  shown,
}: {
  readonly labels: readonly ReactNode[];
  readonly shown: number;
}) {
  return (
    <Box component="span" sx={{ display: "inline-grid" }}>
      {labels.map((label, index) => (
        <Box
          component="span"
          key={index}
          aria-hidden={index === shown ? undefined : true}
          sx={{
            gridArea: "1 / 1",
            visibility: index === shown ? "visible" : "hidden",
          }}
        >
          {label}
        </Box>
      ))}
    </Box>
  );
}
