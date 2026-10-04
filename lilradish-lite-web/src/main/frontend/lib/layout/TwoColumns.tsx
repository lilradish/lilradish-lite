import Box from "@mui/material/Box";
import type { ReactNode } from "react";

const NARROW_COLUMN = 320;

const GAP = 24;

export function columnsBesideAt(wideAtLeast: number): number {
  return wideAtLeast + GAP + NARROW_COLUMN;
}

// Judged by a container query on this frame's own width, never the viewport's: the sidebar folding changes the
// room here without changing the screen. A container cannot query itself, so the grid is a child of the frame.
const FRAME_SX = { containerType: "inline-size" };

/** The two tops level: what a column opens with keeps no room of its own above it. */
const COLUMNS_SX = {
  display: "grid",
  gridTemplateColumns: "minmax(0, 1fr)",
  gap: `${GAP}px`,
  "& > * > :first-child": { mt: 0 },
};

/**
 * What a page is about, wide on the leading side, and what is about it, narrow on the trailing side; where there is
 * not room for both, the narrow one goes under the wide one. The order is the same either way, so the keyboard and a
 * screen reader meet the wide one first at any width.
 */
export function TwoColumns({
  wideAtLeast,
  wide,
  narrow,
  stickyNarrow = false,
}: {
  /** The width, in pixels, the wide column keeps before the narrow one stands beside it. */
  readonly wideAtLeast: number;
  readonly wide: ReactNode;
  readonly narrow: ReactNode;
  readonly stickyNarrow?: boolean;
}) {
  return (
    <Box sx={FRAME_SX}>
      <Box
        sx={{
          ...COLUMNS_SX,
          [`@container (min-width: ${columnsBesideAt(wideAtLeast)}px)`]: {
            gridTemplateColumns: `minmax(0, 1fr) ${NARROW_COLUMN}px`,
            alignItems: "start",
            ...(stickyNarrow
              ? { "& > :last-child": { alignSelf: "stretch" } }
              : {}),
          },
        }}
      >
        <div>{wide}</div>
        <div>{narrow}</div>
      </Box>
    </Box>
  );
}
