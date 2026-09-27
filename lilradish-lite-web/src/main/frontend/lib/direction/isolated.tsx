import type { ReactNode } from "react";

/**
 * Words in an element of their own that takes its direction from them. A table
 * cell is isolated already but keeps the table's direction; a number has none
 * to take, and a node is the caller's to isolate.
 */
export function isolated(value: ReactNode): ReactNode {
  return typeof value === "string" ? <bdi>{value}</bdi> : value;
}

const FIRST_STRONG_ISOLATE = String.fromCodePoint(0x2068);

const POP_DIRECTIONAL_ISOLATE = String.fromCodePoint(0x2069);

const OPENS_AN_ISOLATE = new Set([0x2066, 0x2067, 0x2068]);

const CLOSES_AN_ISOLATE = 0x2069;

/** Each ends the paragraph, and every isolate open in it with the paragraph. */
const ENDS_A_PARAGRAPH = new Set([
  0x000a, 0x000d, 0x001c, 0x001d, 0x001e, 0x0085, 0x2029,
]);

// Words holding none of these are isolated without being walked.
const NEEDS_A_WALK = new RegExp(
  `[${[...ENDS_A_PARAGRAPH, 0x2066, 0x2067, 0x2068, 0x2069]
    .map((point) => `\\u{${point.toString(16)}}`)
    .join("")}]`,
  "u",
);

/**
 * Words set inside text that is handed on as text — a sentence, an attribute —
 * where no element of their own can isolate them: words written right to left,
 * or carrying a directional control, would otherwise reorder the text around
 * them.
 *
 * The words are balanced first. A paragraph separator would end the isolate
 * with its paragraph, so it is written as a space. A close with nothing open
 * inside them would end the isolate early, and an isolate they leave open
 * would take the close meant for this one; so the first is dropped and the
 * second closed here.
 */
export function isolatedInText(words: string): string {
  if (!NEEDS_A_WALK.test(words)) {
    return `${FIRST_STRONG_ISOLATE}${words}${POP_DIRECTIONAL_ISOLATE}`;
  }
  let open = 0;
  let balanced = "";
  for (const character of words) {
    const point = character.codePointAt(0)!;
    if (ENDS_A_PARAGRAPH.has(point)) {
      balanced += " ";
      continue;
    }
    if (point === CLOSES_AN_ISOLATE) {
      if (open === 0) {
        continue;
      }
      open -= 1;
    } else if (OPENS_AN_ISOLATE.has(point)) {
      open += 1;
    }
    balanced += character;
  }
  return `${FIRST_STRONG_ISOLATE}${balanced}${POP_DIRECTIONAL_ISOLATE.repeat(open + 1)}`;
}
