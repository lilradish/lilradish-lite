import Box from "@mui/material/Box";
import type { Theme } from "@mui/material/styles";

import { TOKENS } from "../theme/tokens";

export const KEY_TAG_SX = (given: Theme) => ({
  typography: "keyTag",
  px: "5px",
  border: `1px solid ${given.palette.keyTag.edge}`,
  borderRadius: TOKENS.shape.tagRadius,
  color: given.palette.keyTag.text,
  bgcolor: given.palette.keyTag.ground,
  minWidth: 0,
  maxWidth: "100%",
  overflowWrap: "anywhere",
});

export function KeyTag({
  children,
  id,
}: {
  readonly children: string;
  readonly id?: string;
}) {
  return (
    <Box component="code" id={id} sx={KEY_TAG_SX}>
      <bdi>{children}</bdi>
    </Box>
  );
}
