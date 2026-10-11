import Box from "@mui/material/Box";

import { say } from "../../i18n/lib";
import { UNSEEN_SX } from "./parts";

export function UnsavedMark({
  compact = false,
}: {
  readonly compact?: boolean;
}) {
  return (
    <Box
      component="span"
      sx={{
        display: "inline-flex",
        alignItems: "center",
        gap: "5px",
        typography: "unsaved",
        color: "text.secondary",
      }}
    >
      <Box
        component="span"
        aria-hidden
        sx={{
          width: "7px",
          height: "7px",
          borderRadius: "50%",
          bgcolor: "warning.main",
          flexShrink: 0,
        }}
      />
      <Box component="span" sx={compact ? UNSEEN_SX : undefined}>
        {say("edit.unsaved")}
      </Box>
    </Box>
  );
}
