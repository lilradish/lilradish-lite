import { describe, expect, it } from "vitest";

import CASES from "./legibility.cases.json";
import {
  concealingIn,
  fieldNameFits,
  labelFits,
  lineFits,
  meaningRefused,
  nameFits,
  noteRefused,
  oneLineWithin,
  proseFits,
  proseCharactersRefused,
  proseRefused,
  reasonRefused,
  showsInProse,
  termRefused,
  type ProseRefusal,
  type TermRefusal,
} from "./legibility";

/**
 * The page's half of each limit, run over the one table the server's half is
 * run over too (`TypedLimitsIntegrationSpec`): a case the two decide
 * differently fails on one side or the other.
 */
interface Case {
  readonly typed: string;
  readonly times?: number;
  readonly accepted: boolean;
}

/** A prose case, which names why the server refuses it as well. */
interface ProseCase extends Case {
  readonly refused: ProseRefusal | TermRefusal | null;
}

/**
 * A reason case, run over by `ReasonsLegibilityIntegrationSpec` on the server's half as well; a reason showing
 * nothing is refused as missing, apart from prose's own refusals.
 */
interface ReasonCase extends Case {
  readonly refused: ProseRefusal | "missing" | null;
}

const NAMING_REASONS = [
  ...CASES.prose,
  ...CASES.note,
  ...CASES.term,
  ...CASES.meaning,
  ...CASES.reason,
] as readonly (ProseCase | ReasonCase)[];

function typedIn(each: Case): string {
  return each.typed.repeat(each.times ?? 1);
}

describe("nameFits", () => {
  it("holds cases to judge, an empty table agreeing with anything", () => {
    expect(CASES.name.length).toBeGreaterThan(0);
  });

  it.each(CASES.name as readonly Case[])(
    "judges %j as the server's name does",
    (each) => {
      expect(nameFits(typedIn(each))).toBe(each.accepted);
    },
  );
});

describe("lineFits", () => {
  it("holds cases to judge, an empty table agreeing with anything", () => {
    expect(CASES.line.length).toBeGreaterThan(0);
  });

  it.each(CASES.line as readonly Case[])(
    "judges %j as the server's line does",
    (each) => {
      expect(lineFits(typedIn(each))).toBe(each.accepted);
    },
  );
});

describe("labelFits", () => {
  it("holds cases to judge, an empty table agreeing with anything", () => {
    expect(CASES.label.length).toBeGreaterThan(0);
  });

  it.each(CASES.label as readonly Case[])(
    "judges %j as the server's label does",
    (each) => {
      expect(labelFits(typedIn(each))).toBe(each.accepted);
    },
  );
});

describe("proseFits", () => {
  it("holds cases to judge, an empty table agreeing with anything", () => {
    expect(CASES.prose.length).toBeGreaterThan(0);
  });

  it.each(CASES.prose as readonly Case[])(
    "judges %j as the server's instruction does",
    (each) => {
      expect(proseFits(typedIn(each))).toBe(each.accepted);
    },
  );
});

describe("proseRefused", () => {
  it.each(CASES.prose as readonly ProseCase[])(
    "names %j refused for the reason the server's instruction names",
    (each) => {
      expect(proseRefused(typedIn(each))).toBe(each.refused);
    },
  );
});

describe.each([
  ["noteRefused", noteRefused, CASES.note, "note"],
  ["termRefused", termRefused, CASES.term, "term"],
  ["meaningRefused", meaningRefused, CASES.meaning, "meaning"],
] as const)("%s", (_name, refused, cases, judged) => {
  it("holds cases to judge, an empty table agreeing with anything", () => {
    expect(cases.length).toBeGreaterThan(0);
  });

  it.each(cases as readonly ProseCase[])(
    `names %j refused for the reason the server's ${judged} names, or none where it takes it`,
    (each) => {
      expect(refused(typedIn(each))).toBe(each.refused);
    },
  );
});

describe("reasonRefused", () => {
  it("holds a case of every outcome, an empty table agreeing with anything", () => {
    expect(new Set(CASES.reason.map((each) => each.refused))).toEqual(
      new Set([
        null,
        "missing",
        "crlf",
        "direction_control",
        "tag",
        "unusable",
      ]),
    );
  });

  it.each(CASES.reason as readonly ReasonCase[])(
    "names %j refused for the reason the server's reason names, or none where it takes it",
    (each) => {
      expect(reasonRefused(typedIn(each))).toBe(each.refused);
    },
  );
});

describe("the tables naming reasons", () => {
  it.each(NAMING_REASONS)(
    "name a reason for %j exactly where it is not taken",
    (each) => {
      expect(each.refused === null).toBe(each.accepted);
    },
  );
});

describe("concealingIn", () => {
  it.each([
    ["nothing concealing", "Say why.", null],
    [
      "an embedding, first of the direction controls",
      "a" + String.fromCodePoint(0x202a) + "b",
      "direction_control",
    ],
    [
      "an isolate, last of them",
      "a" + String.fromCodePoint(0x2069),
      "direction_control",
    ],
    ["the first of the tag block", String.fromCodePoint(0xe0000), "tag"],
    ["the last of the tag block", "a" + String.fromCodePoint(0xe007f), "tag"],
    [
      "the space just past the direction controls",
      "a" + String.fromCodePoint(0x202f) + "b",
      null,
    ],
    [
      "the character just past the tag block",
      "a" + String.fromCodePoint(0xe0080),
      null,
    ],
    [
      "a tag before a direction control",
      "a" + String.fromCodePoint(0xe0041, 0x202e),
      "tag",
    ],
  ] as const)("names in text holding %s what it holds", (_case, text, held) => {
    expect(concealingIn(text)).toBe(held);
  });
});

describe("oneLineWithin", () => {
  it.each([
    ["one character", "a", 2, true],
    ["as many characters as it may hold", "ab", 2, true],
    ["one character past them", "abc", 2, false],
    ["two astral characters, counted as two", "\u{1F600}\u{1F600}", 2, true],
    ["nothing", "", 2, false],
    ["only spaces, which show nothing", "  ", 2, true],
    ["a format character alone", String.fromCodePoint(0x200b), 2, true],
    ["a line feed", "a\nb", 3, false],
    ["a control past the C0 block", "a\u0085b", 3, false],
    ["a line separator", "a b", 3, false],
    ["a paragraph separator", "a b", 3, false],
    ["a lone surrogate", "a\ud800b", 3, false],
  ] as const)(
    "judges %s, typed %j, as one line within %i: %s",
    (_case, typed, most, fits) => {
      expect(oneLineWithin(typed, most)).toBe(fits);
    },
  );
});

describe("fieldNameFits", () => {
  it("holds cases to judge, an empty table agreeing with anything", () => {
    expect(CASES.fieldName.length).toBeGreaterThan(0);
  });

  it.each(CASES.fieldName as readonly Case[])(
    "judges %j as the server's field name does",
    (each) => {
      expect(fieldNameFits(typedIn(each))).toBe(each.accepted);
    },
  );
});

describe("proseCharactersRefused", () => {
  it.each([
    ["a tab and a line feed between words", "Say\twhy.\n", null],
    ["nothing that shows", " \t\n", null],
    ["more than prose may run to", "a".repeat(8193), null],
    [
      "a CRLF before a direction control",
      "a\r\n" + String.fromCodePoint(0x202e),
      "crlf",
    ],
    [
      "a direction control",
      "a" + String.fromCodePoint(0x2066),
      "direction_control",
    ],
    ["the tag block", "a" + String.fromCodePoint(0xe0041), "tag"],
    ["a carriage return alone", "a\rb", "unusable"],
    ["a paragraph separator", "a" + String.fromCodePoint(0x2029), "unusable"],
  ] as const)(
    "names prose holding %s for its characters alone",
    (_case, typed, refused) => {
      expect(proseCharactersRefused(typed)).toBe(refused);
    },
  );

  it.each(CASES.prose as readonly ProseCase[])(
    "names %j for the reason the server's instruction names, or none where only its length or showing nothing may be why",
    (each) => {
      const allowed =
        each.refused === "unusable" ? ["unusable", null] : [each.refused];

      expect(allowed).toContain(proseCharactersRefused(typedIn(each)));
    },
  );
});

describe("showsInProse", () => {
  it.each([
    ["a letter", "a", true],
    ["a letter among spaces, tabs and line feeds", " \ta\n", true],
    ["spaces, tabs and line feeds", " \t\n", false],
    ["a format character", String.fromCodePoint(0x200b), false],
    ["the tag block", String.fromCodePoint(0xe0041), false],
    ["nothing", "", false],
  ] as const)("judges %s as showing: %s", (_case, typed, shows) => {
    expect(showsInProse(typed)).toBe(shows);
  });
});
