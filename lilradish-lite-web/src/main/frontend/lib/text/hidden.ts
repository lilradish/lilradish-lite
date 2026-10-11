/** How a character would go unseen where it is drawn: as nothing, or as the order of the text around it. */
export type Unseen = "shows_nothing" | "turns_order";

/** Text to draw as it is, or one character, `count` times in a row, to draw as its code point in place of them. */
export type Piece =
  | { readonly text: string }
  | {
      readonly codePoint: number;
      readonly unseen: Unseen;
      readonly count: number;
    };

// Unicode 16's Default_Ignorable_Code_Point, first and last of each range: the server's CharacterStanding table with
// U+200C and U+200D put back. The tag block its ConcealingCharacter names lies within it.
const DEFAULT_IGNORABLE = [
  0x00ad, 0x00ad, 0x034f, 0x034f, 0x061c, 0x061c, 0x115f, 0x1160, 0x17b4,
  0x17b5, 0x180b, 0x180f, 0x200b, 0x200f, 0x202a, 0x202e, 0x2060, 0x206f,
  0x3164, 0x3164, 0xfe00, 0xfe0f, 0xfeff, 0xfeff, 0xffa0, 0xffa0, 0xfff0,
  0xfff8, 0x1bca0, 0x1bca3, 0x1d173, 0x1d17a, 0xe0000, 0xe0fff,
];

// Letters and emoji are spelt with these, so they are left to do their work after a character that shows, a run
// this long at most; a longer run, or one after nothing that shows, is how hidden text is carried.
const JOINING = new Set([0x200c, 0x200d, 0xfe0e, 0xfe0f]);

const MOST_JOINING_IN_A_ROW = 2;

// As the server judges a character blank: every format character too, as U+FFF9 is, though it is not ignorable. Only
// what stays unmarked needs judging here: a joiner after a character this walk marks is marked by where it stands.
const SHOWS_NOTHING_ITSELF = /[\p{White_Space}\p{Cf}]/u;

const TAB = 0x09;
const LINE_FEED = 0x0a;
const CARRIAGE_RETURN = 0x0d;

const MOST_MARKERS = 1_000;

/**
 * How the texts of one value are drawn: each cut into its pieces, or, where they would need more than the most
 * markers drawn across them all, each without any character it would mark, and how many were left out of them all.
 */
export type Drawing =
  | { readonly pieces: ReadonlyMap<string, readonly Piece[]> }
  | {
      readonly kept: ReadonlyMap<string, string>;
      readonly leftOut: number;
    };

/** Each occurrence of a value's texts counts toward their shared marker budget. */
export function drawingOf(texts: readonly string[]): Drawing {
  const pieces = new Map<string, readonly Piece[]>();
  let markersLeft = MOST_MARKERS;
  for (const text of texts) {
    const cut = pieces.get(text) ?? piecesWithin(text, markersLeft);
    if (cut === null) {
      return withoutMarkedAcross(texts);
    }
    for (const piece of cut) {
      if ("count" in piece) {
        markersLeft -= 1;
      }
    }
    if (markersLeft < 0) {
      return withoutMarkedAcross(texts);
    }
    pieces.set(text, cut);
  }
  return { pieces };
}

/** The text with every character `piecesWithin` would mark left out, and how many it left out, cut into no pieces. */
export function withoutMarkedIn(text: string): {
  readonly kept: string;
  readonly leftOut: number;
} {
  const kept: string[] = [];
  let leftOut = 0;
  walkText(
    text,
    (from, to) => kept.push(text.slice(from, to)),
    (_codePoint, count) => {
      leftOut += count;
      return true;
    },
  );
  return { kept: kept.join(""), leftOut };
}

/**
 * Cuts unseen characters in order, each run of the same character a separate piece.
 * Returns null if more than `most` markers are needed, stopping one past them.
 * Reads code points; a lone half of a pair is drawn as it is.
 */
function piecesWithin(text: string, most: number): Piece[] | null {
  const pieces: Piece[] = [];
  let markers = 0;
  walkText(
    text,
    (from, to) => pieces.push({ text: text.slice(from, to) }),
    (codePoint, count) => {
      pieces.push({
        codePoint,
        unseen: turnsOrder(codePoint) ? "turns_order" : "shows_nothing",
        count,
      });
      markers += 1;
      return markers <= most;
    },
  );
  return markers <= most ? pieces : null;
}

function withoutMarkedAcross(texts: readonly string[]): Drawing {
  const each = new Map<string, ReturnType<typeof withoutMarkedIn>>();
  let leftOut = 0;
  for (const text of texts) {
    const without = each.get(text) ?? withoutMarkedIn(text);
    each.set(text, without);
    leftOut += without.leftOut;
  }
  return {
    kept: new Map([...each].map(([text, without]) => [text, without.kept])),
    leftOut,
  };
}

/**
 * Walks the text as `piecesWithin` cuts it, handing `plain` where each stretch drawn as it is starts and ends and
 * `marked` each run of one character to mark, in order; it stops where `marked` returns false.
 */
export function walkText(
  text: string,
  plain: (from: number, to: number) => void,
  marked: (codePoint: number, count: number, unseen: Unseen) => boolean,
): void {
  let plainFrom = 0;
  let at = 0;
  while (at < text.length) {
    const codePoint = text.codePointAt(at)!;
    if (JOINING.has(codePoint)) {
      let end = at + 1;
      while (end < text.length && JOINING.has(text.charCodeAt(end))) {
        end += 1;
      }
      if (
        end - at > MOST_JOINING_IN_A_ROW ||
        plainFrom === at ||
        SHOWS_NOTHING_ITSELF.test(characterBefore(text, at))
      ) {
        if (plainFrom < at) {
          plain(plainFrom, at);
        }
        for (let joining = at; joining < end;) {
          const same = sameRunEnd(text, joining, 1, end);
          if (
            !marked(text.charCodeAt(joining), same - joining, "shows_nothing")
          ) {
            return;
          }
          joining = same;
        }
        plainFrom = end;
      }
      at = end;
      continue;
    }
    const width = codePoint > 0xffff ? 2 : 1;
    if (!shownAsCode(text, at, codePoint)) {
      at += width;
      continue;
    }
    if (plainFrom < at) {
      plain(plainFrom, at);
    }
    let end = sameRunEnd(text, at, width, text.length);
    // The last of a run of carriage returns ends a line with the line feed after it, so it is no part of the run. Never
    // back to `at`: a carriage return before a line feed is never marked, so a run cut here holds two at least.
    if (codePoint === CARRIAGE_RETURN && text.charCodeAt(end) === LINE_FEED) {
      end -= 1;
    }
    if (
      !marked(
        codePoint,
        (end - at) / width,
        turnsOrder(codePoint) ? "turns_order" : "shows_nothing",
      )
    ) {
      return;
    }
    plainFrom = end;
    at = end;
  }
  if (plainFrom < text.length) {
    plain(plainFrom, text.length);
  }
}

/** Where the character of `width` code units at `from` stops repeating, before `limit`. */
function sameRunEnd(
  text: string,
  from: number,
  width: number,
  limit: number,
): number {
  const first = text.charCodeAt(from);
  const second = text.charCodeAt(from + 1);
  let end = from + width;
  while (
    end + width <= limit &&
    text.charCodeAt(end) === first &&
    (width === 1 || text.charCodeAt(end + 1) === second)
  ) {
    end += width;
  }
  return end;
}

/**
 * The character at `at` must not be a joiner.
 */
function shownAsCode(text: string, at: number, codePoint: number): boolean {
  if (codePoint === TAB || codePoint === LINE_FEED) {
    return false;
  }
  if (codePoint === CARRIAGE_RETURN) {
    return text.charCodeAt(at + 1) !== LINE_FEED;
  }
  return (
    codePoint < 0x20 ||
    (codePoint >= 0x7f && codePoint <= 0x9f) ||
    codePoint === 0x2028 ||
    codePoint === 0x2029 ||
    ignorable(codePoint)
  );
}

function ignorable(codePoint: number): boolean {
  for (let range = 0; range < DEFAULT_IGNORABLE.length; range += 2) {
    if (codePoint < DEFAULT_IGNORABLE[range]!) {
      return false;
    }
    if (codePoint <= DEFAULT_IGNORABLE[range + 1]!) {
      return true;
    }
  }
  return false;
}

/** An embedding, an override or an isolate, and the three marks that set a direction without one. */
function turnsOrder(codePoint: number): boolean {
  return (
    (codePoint >= 0x202a && codePoint <= 0x202e) ||
    (codePoint >= 0x2066 && codePoint <= 0x2069) ||
    codePoint === 0x200e ||
    codePoint === 0x200f ||
    codePoint === 0x061c
  );
}

function characterBefore(text: string, at: number): string {
  return at >= 2 && text.codePointAt(at - 2)! > 0xffff
    ? text.slice(at - 2, at)
    : text.charAt(at - 1);
}
