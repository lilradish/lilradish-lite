/** Controls side by side, each at its own width, wrapping onto the next line where the row runs out. */
export const ACTS_SX = { display: "flex", flexWrap: "wrap", gap: 1, mb: 2 };

/** The same with no room of its own below: beside a page's heading, or where what follows keeps the room. */
export const HEADER_ACTS_SX = { display: "flex", flexWrap: "wrap", gap: 1 };

/** A title and what is said beside it, centred on one line. */
export const TITLE_LINE_SX = { ...HEADER_ACTS_SX, alignItems: "center" };

/** Words with an icon before them, centred on one line. */
export const MARKED_WORDS_SX = {
  display: "inline-flex",
  alignItems: "center",
  gap: 0.5,
};

/** A line said beside what it is about, drawn quieter than it. */
export const QUIET_SX = { color: "text.secondary", mb: 1 };

export const HEADING_SX = { mb: 2 };

export const SPACED_SX = { mb: 3 };

export const SPACED_ABOVE_SX = { mt: 3 };

export const SPACED_FAR_ABOVE_SX = { mt: 4 };

// Its markers taken away, so it is named a list outright.
export const LIST_SX = { listStyleType: "none", m: 0, p: 0 };

/** The last child brings no extra space beneath its section. */
const LAST_CHILD_SX = { "& > :last-child": { mb: 0 } };

export const BLOCK_SX = {
  ...LAST_CHILD_SX,
  border: 1,
  borderColor: "divider",
  borderRadius: 1,
  p: 2,
  mb: 2,
};

export const INNER_PART_SX = { ...LAST_CHILD_SX, mt: 3, mb: 3 };

const PAGE_SX = {
  ...LAST_CHILD_SX,
  bgcolor: "background.paper",
  borderRadius: 2,
  boxShadow: 1,
  p: 3,
  mb: 3,
};

/** The width of a rule down a step message's leading edge. */
export const LEADING_RULE_WIDTH = 3;

export const GROUP_SX = { ...LAST_CHILD_SX, mt: 2, mb: 2 };

export const INSET_SX = { pl: 2 };

export const HELD_LIST_SX = { ...LIST_SX, ...INSET_SX };

/** A page card and its inner parts, which only their headings and space set apart. */
export const DEPTH_SX = {
  1: PAGE_SX,
  2: INNER_PART_SX,
  3: GROUP_SX,
  4: INSET_SX,
};

/** Out of sight, still read out. In pixels: `sx` reads a bare 1 on a size as a fraction and emits 100%. */
export const UNSEEN_SX = {
  position: "absolute",
  height: "1px",
  width: "1px",
  clipPath: "inset(50%)",
  overflow: "hidden",
  whiteSpace: "nowrap",
};
