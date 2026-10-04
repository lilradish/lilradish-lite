// The page's half of how deep the server lets a drawing nest; the server's half is the one that holds.

// Level with the server's bound; the cases table beside this file is shaped for the server to read too.
const DEEPEST_DRAWING = 32;

const TAB_COLUMNS = 4;

const MOST_ORDERED_DIGITS = 9;

const TAB = 0x09;
const LINE_FEED = 0x0a;
const CARRIAGE_RETURN = 0x0d;
const SPACE = 0x20;
const PLUS = 0x2b;
const ASTERISK = 0x2a;
const HYPHEN = 0x2d;
const FULL_STOP = 0x2e;
const RIGHT_PARENTHESIS = 0x29;
const GREATER_THAN = 0x3e;
const DIGIT_ZERO = 0x30;
const DIGIT_NINE = 0x39;
const CAPITAL_A = 0x41;
const CAPITAL_Z = 0x5a;
const LOW_LINE = 0x5f;
const SMALL_A = 0x61;
const SMALL_Z = 0x7a;
const BYTE_ORDER_MARK = 0xfeff;

/**
 * How deep a drawing's lists and quotes could nest, as the server's
 * `DrawingDepth` estimates it: line by line, never shallower than Markdown
 * nests them. Each line counts the quote and list markers it opens with, and
 * half of every run of spaces and tabs before and between them, a tab as four
 * and a run straight after a marker first giving one column back to it.
 */
export function drawingDepth(drawing: string): number {
  const length = drawing.length;
  // Markdown passes over one byte order mark opening the text, so a line behind it opens containers all the same.
  let at = drawing.charCodeAt(0) === BYTE_ORDER_MARK ? 1 : 0;
  let deepest = 0;
  for (;;) {
    let depth = 0;
    let afterMarker = false;
    for (;;) {
      let columns = 0;
      while (at < length && isSpaceOrTab(drawing.charCodeAt(at))) {
        columns += drawing.charCodeAt(at) === TAB ? TAB_COLUMNS : 1;
        at += 1;
      }
      depth += Math.floor(
        (afterMarker && columns > 0 ? columns - 1 : columns) / 2,
      );
      const after = markerEnd(drawing, at);
      if (after < 0) {
        break;
      }
      depth += 1;
      at = after;
      afterMarker = true;
    }
    deepest = Math.max(deepest, depth);
    while (at < length && !isLineEnd(drawing.charCodeAt(at))) {
      at += 1;
    }
    if (at === length) {
      return deepest;
    }
    at += 1;
  }
}

/**
 * How deep a drawing's emphasis could nest, as the server's `DrawingDepth`
 * estimates it: never shallower than Markdown nests it. Between lines empty or
 * of only spaces and tabs, half of every `*` and of every `_` not standing
 * between two ASCII letters or digits.
 */
export function emphasisDepth(drawing: string): number {
  // A byte order mark opening the text needs no passing over: it is no letter or digit, and a first line it keeps
  // from being blank has nothing above it to join.
  const length = drawing.length;
  let deepest = 0;
  let delimiters = 0;
  let blank = true;
  for (let at = 0; at < length; at += 1) {
    const here = drawing.charCodeAt(at);
    if (isLineEnd(here)) {
      if (blank) {
        deepest = Math.max(deepest, Math.floor(delimiters / 2));
        delimiters = 0;
      }
      blank = true;
      if (
        here === CARRIAGE_RETURN &&
        drawing.charCodeAt(at + 1) === LINE_FEED
      ) {
        at += 1;
      }
      continue;
    }
    blank &&= isSpaceOrTab(here);
    if (
      here === ASTERISK ||
      (here === LOW_LINE &&
        !(
          isAsciiAlphanumeric(drawing.charCodeAt(at - 1)) &&
          isAsciiAlphanumeric(drawing.charCodeAt(at + 1))
        ))
    ) {
      delimiters += 1;
    }
  }
  return Math.max(deepest, Math.floor(delimiters / 2));
}

/** A drawing the server refuses as nesting too deep. */
export function drawingTooDeep(drawing: string): boolean {
  return (
    drawingDepth(drawing) > DEEPEST_DRAWING ||
    emphasisDepth(drawing) > DEEPEST_DRAWING
  );
}

/** Where a container marker starting at `at` ends, or -1 where none starts there. */
function markerEnd(drawing: string, at: number): number {
  const here = drawing.charCodeAt(at);
  if (here === GREATER_THAN) {
    return at + 1;
  }
  let after: number;
  if (here === HYPHEN || here === ASTERISK || here === PLUS) {
    after = at + 1;
  } else {
    let digit = at;
    while (
      digit - at < MOST_ORDERED_DIGITS &&
      isDigit(drawing.charCodeAt(digit))
    ) {
      digit += 1;
    }
    const closing = drawing.charCodeAt(digit);
    if (
      digit === at ||
      (closing !== FULL_STOP && closing !== RIGHT_PARENTHESIS)
    ) {
      return -1;
    }
    after = digit + 1;
  }
  // A list marker with nothing after it on its line still opens an item.
  if (after === drawing.length) {
    return after;
  }
  const next = drawing.charCodeAt(after);
  return isSpaceOrTab(next) || isLineEnd(next) ? after : -1;
}

function isSpaceOrTab(code: number): boolean {
  return code === SPACE || code === TAB;
}

function isLineEnd(code: number): boolean {
  return code === LINE_FEED || code === CARRIAGE_RETURN;
}

function isDigit(code: number): boolean {
  return code >= DIGIT_ZERO && code <= DIGIT_NINE;
}

function isAsciiAlphanumeric(code: number): boolean {
  return (
    isDigit(code) ||
    (code >= CAPITAL_A && code <= CAPITAL_Z) ||
    (code >= SMALL_A && code <= SMALL_Z)
  );
}
