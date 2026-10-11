import Box from "@mui/material/Box";
import type { SvgIconProps } from "@mui/material/SvgIcon";
import { cloneElement, type ReactElement, type ReactNode } from "react";

import { TOKENS, type StateToneName } from "../theme/tokens";

/** Callers supply the state they know and its complete words; tone adds no state facts. */
export function StatePill({
  tone,
  label,
  icon,
  id,
}: {
  readonly tone: StateToneName;
  readonly label: ReactNode;
  readonly icon?: ReactElement<SvgIconProps>;
  readonly id?: string;
}) {
  return (
    <Box
      component="span"
      id={id}
      sx={(given) => {
        const toned = given.palette.state[tone];
        return {
          display: "inline-flex",
          alignItems: "center",
          gap: "5px",
          px: "8px",
          maxWidth: "100%",
          borderRadius: TOKENS.shape.pillRadius,
          typography: "statusPill",
          color: toned.text,
          bgcolor: toned.ground,
          ...("edge" in toned
            ? { boxShadow: `inset 0 0 0 1px ${toned.edge}` }
            : null),
          "& > svg": { width: 12, height: 12, flexShrink: 0, color: "inherit" },
        };
      }}
    >
      {icon === undefined ? (
        <Box
          component="span"
          aria-hidden
          sx={{
            width: 6,
            height: 6,
            flexShrink: 0,
            borderRadius: "50%",
            bgcolor: "currentColor",
          }}
        />
      ) : (
        cloneElement(icon, {
          "aria-hidden": true,
          focusable: false,
          titleAccess: undefined,
        })
      )}
      <Box component="span" sx={{ minWidth: 0, overflowWrap: "anywhere" }}>
        {label}
      </Box>
    </Box>
  );
}
