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
// parses as around a letter.
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
  return marked(text, false);
}

/** Every complete, unescaped brace pair in an instruction is a reference, including an unknown name. */
export function markInstruction(text: string): Marked {
  return marked(text, true);
}

function marked(text: string, instruction: boolean): Marked {
  const segments: Segment[] = [];
  const pieces: string[] = [];
  let plainFrom = 0;
  scan(text, instruction, (segment, start, end) => {
    if (FIRST_MARK + segments.length > LAST_MARK) {
      throw new RangeError("more to mark than there are marks");
    }
    pieces.push(text.slice(plainFrom, start));
    pieces.push(String.fromCodePoint(FIRST_MARK + segments.length));
    segments.push(segment);
    plainFrom = end;
  });
  pieces.push(text.slice(plainFrom));
  return { source: pieces.join(""), segments };
}

function scan(
  text: string,
  instruction: boolean,
  found: (segment: Segment, start: number, end: number) => void,
): void {
  let at = 0;
  let mayClose = true;
  while (at < text.length) {
    if (text.startsWith("\\{{", at)) {
      found({ written: "\\{{", text: "{{" }, at, at + 3);
      at += 3;
      continue;
    }
    if (instruction && mayClose && text.startsWith("{{", at)) {
      const end = text.indexOf("}}", at + 2);
      if (end !== -1) {
        found(
          { written: text.slice(at, end + 2), name: text.slice(at + 2, end) },
          at,
          end + 2,
        );
        at = end + 2;
        continue;
      }
      mayClose = false;
    }
    const placeholder = instruction ? null : matchAt(PLACEHOLDER, text, at);
    if (placeholder !== null) {
      found(
        { written: placeholder[0], name: placeholder[1]! },
        at,
        at + placeholder[0].length,
      );
      at += placeholder[0].length;
      continue;
    }
    const reference = matchAt(NUMERIC_REFERENCE, text, at);
    if (reference !== null && isMark(referenced(reference))) {
      found(
        { written: reference[0], text: reference[0] },
        at,
        at + reference[0].length,
      );
      at += reference[0].length;
      continue;
    }
    const codePoint = text.codePointAt(at)!;
    if (isMark(codePoint)) {
      const character = String.fromCodePoint(codePoint);
      found({ written: character, text: character }, at, at + character.length);
      at += character.length;
      continue;
    }
    at += 1;
  }
}

/** Names of complete instruction references, as written rather than as marks. */
export function instructionNames(text: string): readonly string[] {
  const names = new Set<string>();
  scan(text, true, (segment) => {
    if ("name" in segment) names.add(segment.name);
  });
  return [...names];
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

/** Instruction references change together, preserving every other character of the author's text. */
export function renamedInstruction(
  text: string,
  names: ReadonlyMap<string, string>,
): string {
  return renamed(text, names, true);
}

/** Only a drawing's complete field references change; escapes and all other written text stay as they were. */
export function renamedPlaceholders(
  text: string,
  names: ReadonlyMap<string, string>,
): string {
  return renamed(text, names, false);
}

function renamed(
  text: string,
  names: ReadonlyMap<string, string>,
  instruction: boolean,
): string {
  const pieces: string[] = [];
  let plainFrom = 0;
  scan(text, instruction, (segment, start, end) => {
    const name = "name" in segment ? names.get(segment.name) : undefined;
    if (name === undefined) return;
    pieces.push(text.slice(plainFrom, start), `{{${name}}}`);
    plainFrom = end;
  });
  pieces.push(text.slice(plainFrom));
  return pieces.join("");
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
