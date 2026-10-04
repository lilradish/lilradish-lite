/** What one mark stands for, and the text it was written as. */
export type Segment = { readonly written: string } & (
  { readonly name: string } | { readonly text: string }
);

/** Text with each placeholder, and each character that could pass for one, put out of a parser's reach. */
export interface Marked {
  readonly source: string;
  /** By the number its mark carries. */
  readonly segments: readonly Segment[];
}

// Plane 15's private use: one code point a parser cannot split, neither punctuation nor space, so text around one
// parses as around a letter; and as many as any text has characters to mark.
const FIRST_MARK = 0xf0000;

const LAST_MARK = 0xffffd;

const PLACEHOLDER = /\{\{([a-z][a-z0-9_]{0,62})\}\}/y;

// The numeric references a Markdown parser decodes, which would otherwise write a mark the text never held.
const NUMERIC_REFERENCE = /&#(?:[xX]([0-9a-fA-F]{1,6})|([0-9]{1,7}));/y;

// A mark percent-encoded as UTF-8, as a parser writes one into an address.
const ENCODED_MARK = /%F3%B[0-9A-F]%[89AB][0-9A-F]%[89AB][0-9A-F]/i;

/**
 * `text` with every `{{name}}` marked, scanned as the server scans it: `\{{` is two braces as they are, and anything
 * else is plain text. A character already in the marks' range, or a numeric reference to one, is marked as itself.
 */
export function markPlaceholders(text: string): Marked {
  const segments: Segment[] = [];
  let source = "";
  const mark = (segment: Segment) => {
    if (FIRST_MARK + segments.length > LAST_MARK) {
      throw new RangeError("more to mark than there are marks");
    }
    source += String.fromCodePoint(FIRST_MARK + segments.length);
    segments.push(segment);
  };
  let at = 0;
  while (at < text.length) {
    if (text.startsWith("\\{{", at)) {
      mark({ written: "\\{{", text: "{{" });
      at += 3;
      continue;
    }
    const placeholder = matchAt(PLACEHOLDER, text, at);
    if (placeholder !== null) {
      mark({ written: placeholder[0], name: placeholder[1]! });
      at += placeholder[0].length;
      continue;
    }
    const reference = matchAt(NUMERIC_REFERENCE, text, at);
    if (reference !== null && isMark(referenced(reference))) {
      mark({ written: reference[0], text: reference[0] });
      at += reference[0].length;
      continue;
    }
    const codePoint = text.codePointAt(at)!;
    if (isMark(codePoint)) {
      const character = String.fromCodePoint(codePoint);
      mark({ written: character, text: character });
      at += character.length;
      continue;
    }
    source += text[at];
    at += 1;
  }
  return { source, segments };
}

/** Text cut where each mark stands: the text between as it is, each mark as the number it carries. */
export function marksIn(text: string): readonly (string | number)[] {
  const pieces: (string | number)[] = [];
  let plainFrom = 0;
  for (const found of text.matchAll(/[\u{F0000}-\u{FFFFD}]/gu)) {
    if (found.index > plainFrom) {
      pieces.push(text.slice(plainFrom, found.index));
    }
    pieces.push(found[0].codePointAt(0)! - FIRST_MARK);
    plainFrom = found.index + found[0].length;
  }
  if (plainFrom < text.length) {
    pieces.push(text.slice(plainFrom));
  }
  return pieces;
}

/** Text cut from a marked source, each mark in it put back as what it was written as. */
export function writtenAgain(
  text: string,
  segments: readonly Segment[],
): string {
  return marksIn(text)
    .map((piece) =>
      typeof piece === "string" ? piece : segments[piece]!.written,
    )
    .join("");
}

/** Whether `text` holds a mark, as it is or percent-encoded. */
export function holdsMark(text: string): boolean {
  return /[\u{F0000}-\u{FFFFD}]/u.test(text) || ENCODED_MARK.test(text);
}

function matchAt(pattern: RegExp, text: string, at: number) {
  pattern.lastIndex = at;
  return pattern.exec(text);
}

function referenced(reference: RegExpExecArray): number {
  const [, hexadecimal, decimal] = reference;
  return hexadecimal === undefined
    ? Number.parseInt(decimal!, 10)
    : Number.parseInt(hexadecimal, 16);
}

function isMark(codePoint: number): boolean {
  return codePoint >= FIRST_MARK && codePoint <= LAST_MARK;
}
