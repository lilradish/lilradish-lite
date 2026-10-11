import Box from "@mui/material/Box";
import type { ReactNode } from "react";
import { TOKENS } from "../theme/tokens";

export function FactTag({ children }: { readonly children: ReactNode }) {
  return (
    <>
      {" "}
      <Box
        component="span"
        sx={(given) => ({
          display: "inline-flex",
          alignItems: "center",
          minHeight: 22,
          px: "8px",
          ml: "6px",
          borderRadius: TOKENS.shape.tagRadius,
          bgcolor: given.palette.factTag.ground,
          color: given.palette.factTag.text,
          boxShadow: `inset 0 0 0 1px ${given.palette.factTag.edge}`,
          typography: "factTag",
          overflowWrap: "anywhere",
        })}
      >
        {children}
      </Box>
    </>
  );
}
