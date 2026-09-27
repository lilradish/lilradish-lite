// The page's half of limits the server holds as well; the server's half is the one that holds.

const MOST_IN_A_NAME = 128;

// Level with the server's bound on a line of text, which the shared cases table holds the two to.
const MOST_IN_A_LINE = 512;

// Level with the server's bound on a label, held to it by the same table.
const MOST_IN_A_LABEL = 128;

// Level with the server's bound on prose, held to it by the same table.
const MOST_IN_PROSE = 8192;

// Level with the server's bound on a list's note, held to it by the same table.
const MOST_IN_A_NOTE = 2048;

// Level with the server's bound on a reason, held to it by the same table; the note's figure, not its rule.
const MOST_IN_A_REASON = 2048;

// What cannot be prose: every control but a tab and a line feed, and what else breaks a line.
const NOT_PROSE = /[^\t\n\P{Cc}]|[\p{Zl}\p{Zp}\p{Cs}]/u;

// Level with the server's ConcealingCharacter: a direction control captured, the tag block not.
const CONCEALING = /([\u202A-\u202E\u2066-\u2069])|[\u{E0000}-\u{E007F}]/u;

// Level with the server's CharacterStanding: Unicode 16's Default_Ignorable_Code_Point, first and last of each
// range, less U+200C and U+200D. Numbers, not a class: a class of combining marks reads as one combined.
const INVISIBLE = [
  0x00ad, 0x00ad, 0x034f, 0x034f, 0x061c, 0x061c, 0x115f, 0x1160, 0x17b4,
  0x17b5, 0x180b, 0x180f, 0x200b, 0x200b, 0x200e, 0x200f, 0x202a, 0x202e,
  0x2060, 0x206f, 0x3164, 0x3164, 0xfe00, 0xfe0f, 0xfeff, 0xfeff, 0xffa0,
  0xffa0, 0xfff0, 0xfff8, 0x1bca0, 0x1bca3, 0x1d173, 0x1d17a, 0xe0000, 0xe0fff,
];

// One to 63 lowercase English letters, digits and underscores, starting with a letter.
const IDENTIFIER = /^[a-z][a-z0-9_]{0,62}$/;

// What cannot be one line of text.
const NOT_ONE_LINE = /[\p{Cc}\p{Zl}\p{Zp}\p{Cs}]/u;

// Any space but U+0020, which a name admits only singly between words.
const ANOTHER_SPACE = /(?! )\p{Zs}/u;

// Level with the server's CharacterStanding: anything but a space, a format character or the tag block,
// which draw as nothing; a tab and a line feed show in a line, which refuses them for what they are.
const SHOWS = /[^\p{Zs}\p{Cf}\u{E0000}-\u{E007F}]/u;

// As SHOWS, a tab and a line feed drawing nothing either, as prose holds them.
const SHOWS_IN_PROSE = /[^\t\n\p{Zs}\p{Cf}\u{E0000}-\u{E007F}]/u;

/**
 * A name as the server holds one: one line of one to 128 characters, U+0020
 * the only space and never at an end or doubled, and something in it that
 * shows. A format character is kept. Counted in code points, as the server counts.
 */
export function nameFits(typed: string): boolean {
  return (
    fitsOnOneLine(typed, MOST_IN_A_NAME) &&
    !ANOTHER_SPACE.test(typed) &&
    !typed.startsWith(" ") &&
    !typed.endsWith(" ") &&
    !typed.includes("  ")
  );
}

/**
 * A line as the server holds one, such as what an entry is for: one to 512
 * characters on one line with something in it that shows, its spacing left
 * as typed. Counted in code points, as the server counts.
 */
export function lineFits(typed: string): boolean {
  return fitsOnOneLine(typed, MOST_IN_A_LINE);
}

/** A label as the server holds one: a line as `lineFits` judges one, of at most 128 characters. */
export function labelFits(typed: string): boolean {
  return fitsOnOneLine(typed, MOST_IN_A_LABEL);
}

/**
 * Prose as the server holds it: 1 to 8192 characters, lines ending in a line feed, a tab the only other
 * control, no direction control or tag character, and something that shows.
 */
export function proseFits(typed: string): boolean {
  return proseRefused(typed) === null;
}

/**
 * Why text sent to a model is refused, spelt as the shared cases table and the
 * server's `ProseRefusal`.
 */
export type ProseRefusal = "crlf" | "direction_control" | "tag" | "unusable";

/** Why one line sent to a model is refused: on one line, a carriage return is simply unusable. */
export type LineRefusal = Exclude<ProseRefusal, "crlf">;

/** Why a term is refused: a term alone refuses what shows nothing, and names it before anything else unusable. */
export type TermRefusal = LineRefusal | "invisible";

/** What the server would refuse the prose as, in the order it looks, or null where it takes it. */
export function proseRefused(typed: string): ProseRefusal | null {
  return refusedAsProse(typed, MOST_IN_PROSE);
}

/** What the server would refuse a list's note as: prose, of at most 2048 characters. */
export function noteRefused(typed: string): ProseRefusal | null {
  return refusedAsProse(typed, MOST_IN_A_NOTE);
}

/** What the server would refuse a list's term as: a name as `nameFits` judges one, concealing nothing and showing all. */
export function termRefused(typed: string): TermRefusal | null {
  if (holdsInvisible(typed)) {
    return concealingIn(typed) ?? "invisible";
  }
  return concealingIn(typed) ?? (nameFits(typed) ? null : "unusable");
}

/** What the server would refuse what a term means as: a line as `lineFits` judges one, concealing nothing. */
export function meaningRefused(typed: string): LineRefusal | null {
  return concealingIn(typed) ?? (lineFits(typed) ? null : "unusable");
}

/**
 * What the server would refuse a person's reason as, in the order it looks: a CRLF or what conceals first, then
 * none given where nothing in it shows, then prose past 2048 characters or holding what prose cannot.
 */
export function reasonRefused(typed: string): "missing" | ProseRefusal | null {
  const refused = refusedAsProse(typed, MOST_IN_A_REASON);
  if (refused !== null && refused !== "unusable") {
    return refused;
  }
  return showsInProse(typed) ? refused : "missing";
}

/** The first character in the text that shows nothing yet changes how it reads, as the server finds it. */
export function concealingIn(text: string): "direction_control" | "tag" | null {
  const concealing = CONCEALING.exec(text);
  return concealing === null
    ? null
    : concealing[1] === undefined
      ? "tag"
      : "direction_control";
}

/** One well-formed line of one to `most` characters, whether or not anything in it shows. */
export function oneLineWithin(typed: string, most: number): boolean {
  return (
    typed !== "" && countedWithin(typed, most) && !NOT_ONE_LINE.test(typed)
  );
}

/** A name anything binding to a field calls it by: nothing is folded, so a capital is refused. */
export function fieldNameFits(typed: string): boolean {
  return IDENTIFIER.test(typed);
}

/**
 * What the server would refuse prose as for the characters in it alone, in the order it looks: however long it
 * runs, and whether or not anything in it shows.
 */
export function proseCharactersRefused(typed: string): ProseRefusal | null {
  if (typed.includes("\r\n")) {
    return "crlf";
  }
  return concealingIn(typed) ?? (NOT_PROSE.test(typed) ? "unusable" : null);
}

/** Whether anything in prose shows, where a tab and a line feed draw nothing, as the server judges it. */
export function showsInProse(typed: string): boolean {
  return SHOWS_IN_PROSE.test(typed);
}

function refusedAsProse(typed: string, most: number): ProseRefusal | null {
  return (
    proseCharactersRefused(typed) ??
    (countedWithin(typed, most) && showsInProse(typed) ? null : "unusable")
  );
}

function holdsInvisible(typed: string): boolean {
  for (const character of typed) {
    const codePoint = character.codePointAt(0)!;
    for (let range = 0; range < INVISIBLE.length; range += 2) {
      if (
        codePoint >= INVISIBLE[range]! &&
        codePoint <= INVISIBLE[range + 1]!
      ) {
        return true;
      }
    }
  }
  return false;
}

// Counted in code points as the server counts, without spreading what is typed into an array.
function countedWithin(typed: string, most: number): boolean {
  if (typed.length <= most) {
    return true;
  }
  let counted = 0;
  for (
    let at = 0;
    at < typed.length;
    at += (typed.codePointAt(at) ?? 0) > 0xffff ? 2 : 1
  ) {
    counted += 1;
    if (counted > most) {
      return false;
    }
  }
  return true;
}

function fitsOnOneLine(typed: string, most: number): boolean {
  return oneLineWithin(typed, most) && SHOWS.test(typed);
}
