import { describe, expect, it } from "vitest";

import { drawingOf, withoutMarkedIn, type Piece, type Unseen } from "./hidden";
import CASES from "./legibility.cases.json";
import { concealingIn, termRefused } from "./legibility";

const of = (...codePoints: number[]) => String.fromCodePoint(...codePoints);

const plain = (text: string): Piece => ({ text });

const showsNothing = (codePoint: number, count = 1): Piece => ({
  codePoint,
  unseen: "shows_nothing",
  count,
});

const turnsOrder = (codePoint: number, count = 1): Piece => ({
  codePoint,
  unseen: "turns_order",
  count,
});

/** The most characters a value is sent or returned with. */
const MOST_CHARACTERS = 8_388_608;

const ZWNJ = 0x200c;
const ZWJ = 0x200d;
const TEXT_STYLE = 0xfe0e;
const EMOJI_STYLE = 0xfe0f;

/** What the server refuses a term for holding, which is what shows nothing or conceals. */
const HIDING_IN_A_TERM = new Set(["invisible", "direction_control", "tag"]);

/**
 * What the server counts as showing, so refuses in a term as unusable rather than as hidden, yet is marked where it
 * stands alone: every control but a tab and a line feed, and a line or a paragraph separator.
 */
const MARKED_THOUGH_SHOWING = /^(?:[^\t\n\P{Cc}]|[\p{Zl}\p{Zp}])$/u;

function piecesAlone(text: string): readonly Piece[] | undefined {
  const drawing = drawingOf([text]);
  return "pieces" in drawing ? drawing.pieces.get(text) : undefined;
}

function markedAlone(codePoint: number): boolean {
  return piecesAlone(of(codePoint))!.some((piece) => "codePoint" in piece);
}

describe("drawingOf, one text alone", () => {
  it("cuts nothing out of nothing", () => {
    expect(piecesAlone("")).toEqual([]);
  });

  it.each([
    ["ordinary words", "A kettle leaks."],
    [
      "a tab, a line feed and a carriage return ending a line with one",
      "One\ttwo\nthree\r\nfour",
    ],
    ["lines each ended with a carriage return and a line feed", "\r\n\r\n"],
    [
      "a no-break space and an ideographic space",
      `a${of(0xa0)}b${of(0x3000)}c`,
    ],
    ["a combining mark", `cafe${of(0x301)}`],
    ["an astral character", `smile ${of(0x1f600)} and ${of(0x20000)}`],
    ["a visible format character", `${of(0x600)}12`],
  ])("leaves %s as one piece, drawn as it is", (_case, text) => {
    expect(piecesAlone(text)).toEqual([plain(text)]);
  });

  it.each([
    [0x0000, "shows_nothing"],
    [0x0001, "shows_nothing"],
    [0x000b, "shows_nothing"],
    [0x000c, "shows_nothing"],
    [0x000d, "shows_nothing"],
    [0x001b, "shows_nothing"],
    [0x001f, "shows_nothing"],
    [0x007f, "shows_nothing"],
    [0x0080, "shows_nothing"],
    [0x0085, "shows_nothing"],
    [0x009f, "shows_nothing"],
    [0x00ad, "shows_nothing"],
    [0x034f, "shows_nothing"],
    [0x061c, "turns_order"],
    [0x115f, "shows_nothing"],
    [0x1160, "shows_nothing"],
    [0x17b4, "shows_nothing"],
    [0x17b5, "shows_nothing"],
    [0x180b, "shows_nothing"],
    [0x180f, "shows_nothing"],
    [0x200b, "shows_nothing"],
    [0x200e, "turns_order"],
    [0x200f, "turns_order"],
    [0x2028, "shows_nothing"],
    [0x2029, "shows_nothing"],
    [0x202a, "turns_order"],
    [0x202e, "turns_order"],
    [0x2060, "shows_nothing"],
    [0x2065, "shows_nothing"],
    [0x2066, "turns_order"],
    [0x2069, "turns_order"],
    [0x206a, "shows_nothing"],
    [0x206f, "shows_nothing"],
    [0x3164, "shows_nothing"],
    [0xfe00, "shows_nothing"],
    [0xfe0d, "shows_nothing"],
    [0xfeff, "shows_nothing"],
    [0xffa0, "shows_nothing"],
    [0xfff0, "shows_nothing"],
    [0xfff8, "shows_nothing"],
    [0x1bca0, "shows_nothing"],
    [0x1bca3, "shows_nothing"],
    [0x1d173, "shows_nothing"],
    [0x1d17a, "shows_nothing"],
    [0xe0000, "shows_nothing"],
    [0xe0041, "shows_nothing"],
    [0xe007f, "shows_nothing"],
    [0xe0080, "shows_nothing"],
    [0xe0100, "shows_nothing"],
    [0xe01ef, "shows_nothing"],
    [0xe0fff, "shows_nothing"],
  ] as const satisfies readonly (readonly [number, Unseen])[])(
    "cuts code point %i out between two letters, as a character that %s",
    (codePoint, unseen) => {
      expect(piecesAlone(`a${of(codePoint)}b`)).toEqual([
        plain("a"),
        { codePoint, unseen, count: 1 },
        plain("b"),
      ]);
    },
  );

  it.each([
    0x0009, 0x000a, 0x0020, 0x007e, 0x00a0, 0x00ac, 0x00ae, 0x034e, 0x0350,
    0x061b, 0x061d, 0x115e, 0x1161, 0x17b3, 0x17b6, 0x180a, 0x1810, 0x200a,
    0x2010, 0x2027, 0x205f, 0x2070, 0x3163, 0x3165, 0xfdff, 0xfe10, 0xfefe,
    0xff00, 0xff9f, 0xffa1, 0xffef, 0xfff9, 0x1bc9f, 0x1bca4, 0x1d172, 0x1d17b,
    0xdffff, 0xe1000,
  ])(
    "leaves code point %i, just past a range or left out of one, between two letters",
    (codePoint) => {
      const text = `a${of(codePoint)}b`;

      expect(piecesAlone(text)).toEqual([plain(text)]);
    },
  );

  it("cuts out every one of several, in order, keeping what stands between them", () => {
    expect(piecesAlone(`${of(0x202e)}abc${of(0x200b, 0xe0041)}d`)).toEqual([
      turnsOrder(0x202e),
      plain("abc"),
      showsNothing(0x200b),
      showsNothing(0xe0041),
      plain("d"),
    ]);
  });

  it.each([
    [
      "a character over and over",
      `a${of(0x200b, 0x200b, 0x200b)}b`,
      [plain("a"), showsNothing(0x200b, 3), plain("b")],
    ],
    ["an override over and over", of(0x202e, 0x202e), [turnsOrder(0x202e, 2)]],
    ["a control over and over", of(0x1).repeat(5), [showsNothing(0x1, 5)]],
    ["a control alone", of(0x1), [showsNothing(0x1)]],
    [
      "a carriage return not ending a line",
      "a\rb",
      [plain("a"), showsNothing(0xd), plain("b")],
    ],
    ["a carriage return at the end", "a\r", [plain("a"), showsNothing(0xd)]],
    [
      "a carriage return after a line feed",
      "a\n\rb",
      [plain("a\n"), showsNothing(0xd), plain("b")],
    ],
    [
      "a carriage return before one ending a line, that one left",
      "a\r\r\nb",
      [plain("a"), showsNothing(0xd), plain("\r\nb")],
    ],
    [
      "carriage returns over and over before one ending a line, that one left",
      "a\r\r\r\nb",
      [plain("a"), showsNothing(0xd, 2), plain("\r\nb")],
    ],
    [
      "a carriage return after a line ended",
      "\r\n\r",
      [plain("\r\n"), showsNothing(0xd)],
    ],
    [
      "a tag over and over, read as one character",
      `a${of(0xe0041, 0xe0041)}`,
      [plain("a"), showsNothing(0xe0041, 2)],
    ],
    [
      "two characters each over and over, one after the other",
      of(0x200b, 0x200b, 0xfeff, 0x200b),
      [showsNothing(0x200b, 2), showsNothing(0xfeff), showsNothing(0x200b)],
    ],
    [
      "a tag and the low half of it alone after it",
      `${of(0xe0041)}${of(0xe0041).charAt(1)}`,
      [showsNothing(0xe0041), plain(of(0xe0041).charAt(1))],
    ],
    [
      "a run of joiners, the same ones in it each as one",
      `a${of(ZWJ, ZWJ, EMOJI_STYLE, ZWJ, ZWJ)}b`,
      [
        plain("a"),
        showsNothing(ZWJ, 2),
        showsNothing(EMOJI_STYLE),
        showsNothing(ZWJ, 2),
        plain("b"),
      ],
    ],
  ] as const)(
    "cuts out %s as one piece saying how many",
    (_case, text, pieces) => {
      expect(piecesAlone(text)).toEqual(pieces);
    },
  );

  it.each([
    ["a character", 0x200b],
    ["a joiner", ZWJ],
  ])(
    "cuts out %s as many times in a row as a value may hold as one piece",
    (_case, codePoint) => {
      expect(piecesAlone(of(codePoint).repeat(MOST_CHARACTERS))).toEqual([
        showsNothing(codePoint, MOST_CHARACTERS),
      ]);
    },
  );

  it.each([
    ["a conjunct joined in Devanagari", of(0x915, 0x94d, ZWJ, 0x937)],
    [
      "a word kept apart in Persian",
      of(0x645, 0x6cc, ZWNJ, 0x62e, 0x648, 0x627, 0x647, 0x645),
    ],
    [
      "a family of emoji, joined between each two",
      of(0x1f468, ZWJ, 0x1f469, ZWJ, 0x1f467),
    ],
    ["a heart drawn as an emoji", of(0x2764, EMOJI_STYLE)],
    ["a heart drawn as text", of(0x2764, TEXT_STYLE)],
    ["a heart on fire, two in a row", of(0x2764, EMOJI_STYLE, ZWJ, 0x1f525)],
    ["a toned emoji joined on", of(0x1f469, 0x1f3fd, ZWJ, 0x1f4bb)],
    ["a joiner between two Latin letters", `a${of(ZWJ)}b`],
    [
      "two runs of two, each after something that shows",
      `a${of(ZWJ, ZWNJ)}b${of(EMOJI_STYLE, ZWJ)}c`,
    ],
    ["a joiner after half a pair standing alone", `${of(0xd800, ZWJ)}b`],
  ])("leaves %s as it is", (_case, text) => {
    expect(piecesAlone(text)).toEqual([plain(text)]);
  });

  it.each([
    ["at the start", of(ZWJ, 0x61), [showsNothing(ZWJ), plain("a")]],
    [
      "after a space",
      `a ${of(ZWNJ)}b`,
      [plain("a "), showsNothing(ZWNJ), plain("b")],
    ],
    [
      "after a line feed",
      `a\n${of(EMOJI_STYLE)}`,
      [plain("a\n"), showsNothing(EMOJI_STYLE)],
    ],
    [
      "after a no-break space",
      `a${of(0xa0, ZWJ)}`,
      [plain(`a${of(0xa0)}`), showsNothing(ZWJ)],
    ],
    [
      "after a control, itself marked",
      `a${of(0x1, ZWJ)}`,
      [plain("a"), showsNothing(0x1), showsNothing(ZWJ)],
    ],
    [
      "after a control alone",
      of(0x1, ZWJ),
      [showsNothing(0x1), showsNothing(ZWJ)],
    ],
    [
      "after a carriage return not ending a line",
      `a\r${of(ZWNJ)}b`,
      [plain("a"), showsNothing(0xd), showsNothing(ZWNJ), plain("b")],
    ],
    [
      "after a line separator",
      `a${of(0x2028, ZWJ)}b`,
      [plain("a"), showsNothing(0x2028), showsNothing(ZWJ), plain("b")],
    ],
    [
      "after a line ended with a carriage return and a line feed",
      `a\r\n${of(ZWJ)}`,
      [plain("a\r\n"), showsNothing(ZWJ)],
    ],
    [
      "after a character marked",
      `a${of(0x200b, ZWJ)}b`,
      [plain("a"), showsNothing(0x200b), showsNothing(ZWJ), plain("b")],
    ],
    [
      "after a tag, read as one character",
      `a${of(0xe0041, ZWJ)}`,
      [plain("a"), showsNothing(0xe0041), showsNothing(ZWJ)],
    ],
    [
      "after a format character that shows nothing, though nothing marks it",
      `x${of(0xfff9, ZWJ)}`,
      [plain(`x${of(0xfff9)}`), showsNothing(ZWJ)],
    ],
    [
      "after a format character that is drawn, as the server counts every one blank",
      `${of(0x600)}${of(ZWJ)}`,
      [plain(of(0x600)), showsNothing(ZWJ)],
    ],
    [
      "three in a row of one",
      `a${of(ZWJ, ZWJ, ZWJ)}b`,
      [plain("a"), showsNothing(ZWJ, 3), plain("b")],
    ],
    [
      "three in a row of a mix, every one of the run",
      of(0x2764, EMOJI_STYLE, ZWJ, ZWNJ, 0x1f525),
      [
        plain(of(0x2764)),
        showsNothing(EMOJI_STYLE),
        showsNothing(ZWJ),
        showsNothing(ZWNJ),
        plain(of(0x1f525)),
      ],
    ],
    [
      "a selector other than the two, after a letter",
      `a${of(0xfe00)}`,
      [plain("a"), showsNothing(0xfe00)],
    ],
    [
      "a supplementary selector, after a letter",
      `a${of(0xe0100)}`,
      [plain("a"), showsNothing(0xe0100)],
    ],
  ] as const)("cuts out a joiner or a selector %s", (_case, text, pieces) => {
    expect(piecesAlone(text)).toEqual(pieces);
  });

  it.each([
    ["a high half alone", `a${of(0xd800)}b`],
    ["a low half alone", `a${of(0xdc00)}b`],
    ["a low half before a high one", `${of(0xdc00, 0xd800)}`],
  ])("draws %s as it is", (_case, text) => {
    expect(piecesAlone(text)).toEqual([plain(text)]);
  });

  it("reads a tag after a high half alone as the tag it is", () => {
    expect(piecesAlone(`${of(0xd800)}${of(0xe0041)}`)).toEqual([
      plain(of(0xd800)),
      showsNothing(0xe0041),
    ]);
  });

  it("holds cases to judge, an empty table agreeing with anything", () => {
    expect(CASES.term.length).toBeGreaterThan(0);
  });

  it.each(CASES.term)(
    "cuts a character out of %j, each judged alone, where the server refuses it in a term as hidden, a joiner, a non-joiner and what is marked though the server counts it as showing apart",
    ({ typed, refused }) => {
      const hidden = [...typed].some(
        (character) =>
          character !== of(ZWNJ) &&
          character !== of(ZWJ) &&
          !MARKED_THOUGH_SHOWING.test(character) &&
          markedAlone(character.codePointAt(0)!),
      );

      expect(hidden).toBe(HIDING_IN_A_TERM.has(String(refused)));
    },
  );

  it("cuts out alone exactly what a term is refused for holding alone as hidden, a joiner, a non-joiner, every control but a tab and a line feed, and a line or a paragraph separator, over every code point", () => {
    const disagreeing: string[] = [];
    for (let codePoint = 0; codePoint <= 0x10ffff; codePoint += 1) {
      if (codePoint >= 0xd800 && codePoint <= 0xdfff) {
        continue;
      }
      const refused = HIDING_IN_A_TERM.has(String(termRefused(of(codePoint))));
      const joining = codePoint === ZWNJ || codePoint === ZWJ;
      const showing = MARKED_THOUGH_SHOWING.test(of(codePoint));
      if (markedAlone(codePoint) !== (refused || joining || showing)) {
        disagreeing.push(codePoint.toString(16));
      }
    }

    expect(disagreeing).toEqual([]);
  });

  it("says the order turns at exactly each direction control prose refuses and the three marks, over the first plane", () => {
    const turning: number[] = [];
    const expected: number[] = [0x061c, 0x200e, 0x200f];
    for (let codePoint = 0; codePoint <= 0xffff; codePoint += 1) {
      const piece = piecesAlone(`a${of(codePoint)}`)![1];
      if (piece !== undefined && "unseen" in piece) {
        if (piece.unseen === "turns_order") {
          turning.push(codePoint);
        }
      }
      if (concealingIn(of(codePoint)) === "direction_control") {
        expected.push(codePoint);
      }
    }

    expect(turning).toEqual(expected.sort((one, other) => one - other));
  });
});

/** Text, and what is kept of it with every character it marks left out, and how many it left out. */
const KEPT = [
  ["nothing", "", "", 0],
  ["ordinary words", "A kettle leaks.", "A kettle leaks.", 0],
  [
    "a character over and over, and an override",
    `a${of(0x200b, 0x200b)}b${of(0x202e)}c`,
    "abc",
    3,
  ],
  ["a tag, as one character", `a${of(0xe0041)}`, "a", 1],
  [
    "a joiner left to do its work",
    of(0x2764, EMOJI_STYLE, ZWJ, 0x1f525),
    of(0x2764, EMOJI_STYLE, ZWJ, 0x1f525),
    0,
  ],
  [
    "joiners too many in a row, every one of the run",
    `a${of(ZWJ, ZWJ, ZWNJ)}b`,
    "ab",
    3,
  ],
  ["a joiner at the start", `${of(ZWJ)}a`, "a", 1],
  [
    "controls, a joiner after one, a separator and a lone carriage return, a line ended with one kept",
    `a${of(0x1, 0x1, ZWJ)}b\r\r\nc\td${of(0x2028)}e${of(0x85, 0x7f, 0x1b, 0x0)}`,
    "ab\r\nc\tde",
    9,
  ],
  ["half a pair standing alone", `a${of(0xd800)}`, `a${of(0xd800)}`, 0],
] as const;

/** Each a marker, so that many of them in a row need as many markers. */
const markedApart = (markers: number) => `a${of(0x200b)}`.repeat(markers);

describe("drawingOf", () => {
  it("cuts nothing of no text", () => {
    expect(drawingOf([])).toEqual({ pieces: new Map() });
  });

  it("cuts every text into its pieces where they need as many markers across them as are drawn, a text standing twice counted twice", () => {
    const half = markedApart(500);

    const drawing = drawingOf([half, half]);

    expect(drawing).toEqual({
      pieces: new Map([
        [
          half,
          Array.from({ length: 500 }, () => [
            plain("a"),
            showsNothing(0x200b),
          ]).flat(),
        ],
      ]),
    });
  });

  it("leaves out of every text each character it would mark where they need one more marker across them than are drawn, counting them all", () => {
    const half = markedApart(500);
    const last = `b${of(0x202e, 0x202e, 0x202e)}`;

    const drawing = drawingOf([half, last, half]);

    expect(drawing).toEqual({
      kept: new Map([
        [half, "a".repeat(500)],
        [last, "b"],
      ]),
      leftOut: 1_003,
    });
  });

  it("counts a marker for each control and lone carriage return toward the most drawn, and none for a line ended with one", () => {
    const controls = `a${of(0x1)}`.repeat(999);
    const lines = "b\r\n".repeat(1_001);

    const drawing = drawingOf([controls, lines, "c\r"]);

    expect(drawing).toEqual({
      pieces: new Map([
        [
          controls,
          Array.from({ length: 999 }, () => [
            plain("a"),
            showsNothing(0x1),
          ]).flat(),
        ],
        [lines, [plain(lines)]],
        ["c\r", [plain("c"), showsNothing(0xd)]],
      ]),
    });
  });

  it("leaves out every control and lone carriage return where they need one more marker than are drawn, counting them, and keeps a line ended with one", () => {
    const controls = `a${of(0x1)}`.repeat(999);

    const drawing = drawingOf([controls, "b\r\n", `c\r${of(0x2029)}`]);

    expect(drawing).toEqual({
      kept: new Map([
        [controls, "a".repeat(999)],
        ["b\r\n", "b\r\n"],
        [`c\r${of(0x2029)}`, "c"],
      ]),
      leftOut: 1_001,
    });
  });

  it("leaves out each character of a text as long as a value may hold that needs a marker for every other one, counting them", () => {
    const text = markedApart(MOST_CHARACTERS / 2);

    expect(drawingOf([text])).toEqual({
      kept: new Map([[text, "a".repeat(MOST_CHARACTERS / 2)]]),
      leftOut: MOST_CHARACTERS / 2,
    });
  });
});

describe("withoutMarkedIn", () => {
  it.each(KEPT)(
    "keeps of %s only what is drawn as it is, counting what it leaves out",
    (_case, text, kept, leftOut) => {
      expect(withoutMarkedIn(text)).toEqual({ kept, leftOut });
    },
  );

  it("leaves out every one of a character as many times in a row as a value may hold, and counts them", () => {
    expect(withoutMarkedIn(`a${of(0x200b).repeat(MOST_CHARACTERS)}b`)).toEqual({
      kept: "ab",
      leftOut: MOST_CHARACTERS,
    });
  });
});
