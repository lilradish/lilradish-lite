import type { Theme } from "@mui/material/styles";

/** Every table's geometry, in pixels: Material's and the native ones alike. */
export const TABLE_GEOMETRY = {
  rowHeight: 44,
  headHeight: 36,
  inset: 12,
  headPadding: 7,
  bodyPadding: 11,
  headRule: 2,
  bodyRule: 1,
} as const;

const {
  rowHeight,
  headHeight,
  inset,
  headPadding,
  bodyPadding,
  headRule,
  bodyRule,
} = TABLE_GEOMETRY;

export const TABLE_SX = {
  borderCollapse: "collapse",
  "& .MuiTableRow-hover:hover": { bgcolor: "surface.rowHover" },
};

export const CELL_SX = {
  "& .MuiTableCell-root": {
    "& a": { color: (given: Theme) => given.palette.act.main },
  },
};

/** For a table drawn with native elements, where Material's cell rules do not reach. */
export const NATIVE_TABLE_SX = {
  width: "100%",
  borderCollapse: "collapse",
  "& th": {
    typography: "tableHead",
    color: "ink.secondary",
    textAlign: "start",
    whiteSpace: "nowrap",
    boxSizing: "border-box",
    height: headHeight,
    p: `${headPadding}px ${inset}px`,
    borderBottomWidth: headRule,
    borderBottomStyle: "solid",
    borderBottomColor: "surface.cardEdge",
    "&[data-compact='true']": { width: "1%" },
  },
  "& td": {
    typography: "tableCell",
    boxSizing: "border-box",
    height: rowHeight,
    p: `${bodyPadding}px ${inset}px`,
    verticalAlign: "top",
    borderBottomWidth: bodyRule,
    borderBottomStyle: "solid",
    borderBottomColor: "surface.rule",
    "&[data-compact='true']": { width: "1%", whiteSpace: "nowrap" },
  },
};
