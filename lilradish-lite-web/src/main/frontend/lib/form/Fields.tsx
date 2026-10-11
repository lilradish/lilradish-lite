import Box from "@mui/material/Box";
import type { ReactNode } from "react";

const LIST_SX = {
  display: "grid",
  m: "0 0 1rem",
  "& dt": { typography: "term" },
};

// A value is often one unbroken identifier, wider than its column.
const VALUE_SX = { m: 0, overflowWrap: "anywhere" };

// A stacked row gap would also split each pair. Margin on the value instead
// puts the space only between pairs.
const STACKED_SX = {
  ...LIST_SX,
  gridTemplateColumns: "1fr",
  gap: "0 0",
  "& dd": { ...VALUE_SX, mb: 1 },
};

const IN_PANEL_SX = {
  ...LIST_SX,
  m: 0,
  gridTemplateColumns: "112px minmax(0, 1fr)",
  gap: "14px 12px",
  alignItems: "baseline",
  "& dt": {
    typography: "factLabel",
    color: "ink.secondary",
    overflowWrap: "anywhere",
  },
  "& dd": { ...VALUE_SX, typography: "factValue" },
};

// The column gap is the room between pairs; each value reaches back over half
// of it, so a label sits nearer its own value than the value before it.
const PROPERTIES_SX = {
  ...LIST_SX,
  gridTemplateColumns: "112px minmax(0, 1fr)",
  "@container (min-width: 560px)": {
    gridTemplateColumns: "112px minmax(0, 1fr) 112px minmax(0, 1fr)",
    "& dt[data-across]": { gridColumn: "1" },
    "& dt[data-across] + dd": { gridColumn: "2 / -1" },
  },
  gap: "14px 24px",
  "& dd": { ...VALUE_SX, ml: "-12px" },
  "& dt": { typography: "term", overflowWrap: "anywhere" },
};

const BESIDE_ON_WIDER_SCREENS_SX = {
  ...LIST_SX,
  gridTemplateColumns: { xs: "1fr", sm: "max-content 1fr" },
  gap: { xs: "0 0", sm: "0.25rem 1rem" },
  "& dd": { ...VALUE_SX, mb: { xs: 1, sm: 0 } },
};

/**
 * The grid belongs here rather than to each screen: a label column that is one
 * width on this screen and another on the next reads as two layouts of one
 * kind of thing.
 *
 * A list in a column narrower than the screen asks to be stacked at
 * every width, the viewport saying nothing of the room it has.
 */
export function Fields({
  stacked,
  properties,
  inPanel,
  children,
}: {
  readonly stacked?: true;
  readonly properties?: true;
  /** A side panel's facts: each label in a narrow column of its own beside its value, at every width. */
  readonly inPanel?: true;
  readonly children: ReactNode;
}) {
  const list = (
    <Box
      component="dl"
      sx={
        properties === true
          ? PROPERTIES_SX
          : inPanel === true
            ? IN_PANEL_SX
            : stacked === true
              ? STACKED_SX
              : BESIDE_ON_WIDER_SCREENS_SX
      }
    >
      {children}
    </Box>
  );
  return properties === true ? (
    <Box sx={{ containerType: "inline-size", width: "100%", minWidth: 0 }}>
      {list}
    </Box>
  ) : (
    list
  );
}

export function Field({
  label,
  across,
  children,
}: {
  readonly label: ReactNode;
  /** Among properties two to a row, the pair takes a row of its own; elsewhere it changes nothing. */
  readonly across?: true;
  readonly children: ReactNode;
}) {
  // A fragment, not an element: the pair belongs to the grid its parent owns,
  // and a wrapper around it would take both cells as one.
  return (
    <>
      <dt data-across={across}>{label}</dt>
      <dd>{children}</dd>
    </>
  );
}
