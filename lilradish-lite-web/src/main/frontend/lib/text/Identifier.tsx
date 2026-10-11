import Box from "@mui/material/Box";

import { KEY_TAG_SX } from "./KeyTag";

const IDENTIFIER_SX = [
  KEY_TAG_SX,
  { "&:hover, .MuiLink-root:focus-visible &": { bgcolor: "action.selected" } },
];

export function Identifier({ value }: { readonly value: string }) {
  return (
    <Box component="span" sx={IDENTIFIER_SX}>
      {value}
    </Box>
  );
}
