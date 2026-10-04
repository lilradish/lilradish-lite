import Box from "@mui/material/Box";
import type { ReactNode } from "react";

const LIST_SX = {
  display: "grid",
  m: "0 0 1rem",
  "& dt": { typography: "term" },
};

// A value is often one unbroken identifier, wider than its column.
const VALUE_SX = { m: 0, overflowWrap: "anywhere" };

// Stacked, a row gap would separate a label from its own value as readily as
// from the next pair. The margin goes on the value instead, so the space falls
// between pairs and never inside one.
const STACKED_SX = {
  ...LIST_SX,
  gridTemplateColumns: "1fr",
  gap: "0 0",
  "& dd": { ...VALUE_SX, mb: 1 },
};

const BESIDE_ON_WIDER_SCREENS_SX = {
  ...LIST_SX,
  gridTemplateColumns: { xs: "1fr", sm: "max-content 1fr" },
  gap: { xs: "0 0", sm: "0.25rem 1rem" },
  "& dd": { ...VALUE_SX, mb: { xs: 1, sm: 0 } },
};

/**
 * Label-and-value pairs, as the description list they are. The grid belongs
 * here rather than to each screen that shows one: a label column that is one
 * width on this screen and another on the next reads as two layouts of one
 * kind of thing.
 *
 * One column on a narrow viewport. Two columns of `max-content 1fr` need a
 * label column the label actually fits in, and on a phone that is most of the
 * screen. A list in a column narrower than the screen asks to be stacked at
 * every width, the viewport saying nothing of the room it has.
 */
export function Fields({
  stacked,
  children,
}: {
  /** Each label over its value whatever the width of the screen. */
  readonly stacked?: true;
  readonly children: ReactNode;
}) {
  return (
    <Box
      component="dl"
      sx={stacked === true ? STACKED_SX : BESIDE_ON_WIDER_SCREENS_SX}
    >
      {children}
    </Box>
  );
}

export function Field({
  label,
  children,
}: {
  readonly label: ReactNode;
  readonly children: ReactNode;
}) {
  // A fragment, not an element: the pair belongs to the grid its parent owns,
  // and a wrapper around it would take both cells as one.
  return (
    <>
      <dt>{label}</dt>
      <dd>{children}</dd>
    </>
  );
}
