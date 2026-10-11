import { TOKENS } from "../theme/tokens";

export const ACTS_SX = { display: "flex", flexWrap: "wrap", gap: 1, mb: 2 };

export const HEADER_ACTS_SX = { display: "flex", flexWrap: "wrap", gap: 1 };

export const TITLE_LINE_SX = { ...HEADER_ACTS_SX, alignItems: "center" };

export const MARKED_WORDS_SX = {
  display: "inline-flex",
  alignItems: "center",
  gap: 0.5,
};

export const QUIET_SX = { color: "text.secondary", mb: 1 };

export const HEADING_SX = { mb: 2 };

export const SPACED_SX = { mb: 3 };

export const SPACED_ABOVE_SX = { mt: 3 };

export const SPACED_FAR_ABOVE_SX = { mt: 4 };

// Its markers taken away, so it is named a list outright.
export const LIST_SX = { listStyleType: "none", m: 0, p: 0 };

const LAST_CHILD_SX = { "& > :last-child": { mb: 0 } };

const SIDE_PADDING = "--card-side-padding";

/**
 * A card's padding, top and bottom then the sides, with the sides kept where a rule across the card reads them, so a
 * rule reaches the edges of whichever card it is drawn in.
 */
export function cardPadding(padding: string) {
  return {
    p: padding,
    [SIDE_PADDING]: padding.slice(padding.indexOf(" ") + 1),
  };
}

/** A part after the first in a card: a rule across the whole card above it, the card's own side padding carried through. */
export const RULED_PART_SX = {
  mx: `calc(-1 * var(${SIDE_PADDING}))`,
  px: `var(${SIDE_PADDING})`,
  mt: TOKENS.space.part,
  pt: TOKENS.space.part,
  borderTopWidth: 1,
  borderTopStyle: "solid",
  borderTopColor: "surface.rule",
};

export const BLOCK_SX = {
  ...LAST_CHILD_SX,
  bgcolor: "background.paper",
  border: 1,
  borderColor: TOKENS.surface.cardEdge,
  borderRadius: TOKENS.shape.cardRadius,
  ...cardPadding(TOKENS.space.message),
  mb: 2,
};

export const VALUE_CARD_SX = {
  ...BLOCK_SX,
  ...cardPadding(TOKENS.space.valueCard),
};

export const INNER_PART_SX = { ...LAST_CHILD_SX, mt: 3, mb: 3 };

const PAGE_SX = {
  ...LAST_CHILD_SX,
  bgcolor: "background.paper",
  borderRadius: TOKENS.shape.cardRadius,
  border: 1,
  borderColor: TOKENS.surface.cardEdge,
  ...cardPadding(TOKENS.space.card),
  mb: 3,
};

export const LEADING_RULE_WIDTH = 3;

export const GROUP_SX = { ...LAST_CHILD_SX, mt: 2, mb: 2 };

export const INSET_SX = { pl: 2 };

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
