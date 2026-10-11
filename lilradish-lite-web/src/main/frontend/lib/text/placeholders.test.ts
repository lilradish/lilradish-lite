import { describe, expect, it } from "vitest";

import {
  holdsMark,
  instructionNames,
  markInstruction,
  markPlaceholders,
  marksIn,
  renamedInstruction,
  renamedPlaceholders,
  writtenAgain,
} from "./placeholders";

const mark = (number: number) => String.fromCodePoint(0xf0000 + number);

const of = (...codePoints: number[]) => String.fromCodePoint(...codePoints);

const LONGEST_NAME = `a${"b".repeat(62)}`;

describe("markInstruction", () => {
  it("keeps repeated unfinished openings and protected characters as raw text", () => {
    const raw = "{{".repeat(4096) + "\\{{tail " + mark(0) + " &#xF0000;\n";
    const marked = markInstruction(raw);
    expect(writtenAgain(marked.source, marked.segments)).toBe(raw);
    expect(marked.segments).toEqual([
      { written: "\\{{", text: "{{" },
      { written: mark(0), text: mark(0) },
      { written: "&#xF0000;", text: "&#xF0000;" },
    ]);
  });

  it("renames simultaneous references while keeping escapes and unfinished text", () => {
    const raw = "{{a}} / {{b}} / {{a}} / \\{{a}} / {{unfinished\n";
    expect(
      renamedInstruction(
        raw,
        new Map([
          ["a", "b"],
          ["b", "a"],
        ]),
      ),
    ).toBe("{{b}} / {{a}} / {{b}} / \\{{a}} / {{unfinished\n");
    expect(renamedPlaceholders("{{a.b}} {{a}}", new Map([["a", "b"]]))).toBe(
      "{{a.b}} {{b}}",
    );
  });

  it.each(["a.b", "items[0]", " name ", "", "\u540d\u524d", "<b>"])(
    "keeps the complete reference {{%s}} available for validation and showing in place",
    (name) => {
      const raw = `Read {{${name}}}.`;
      const marked = markInstruction(raw);
      expect(marked.segments).toEqual([{ written: `{{${name}}}`, name }]);
      expect(writtenAgain(marked.source, marked.segments)).toBe(raw);
      expect(markPlaceholders(raw).segments).toEqual([]);
    },
  );

  it("keeps escaped and unfinished braces literal without losing their written spelling", () => {
    const raw = "\\{{a.b}} {{unfinished\n";
    const marked = markInstruction(raw);
    expect(marked.segments).toEqual([{ written: "\\{{", text: "{{" }]);
    expect(writtenAgain(marked.source, marked.segments)).toBe(raw);
  });

  it("protects private-use characters even after unfinished braces, so none can become a reference mark", () => {
    const raw = "Read {{unfinished" + mark(0);
    const marked = markInstruction(raw);
    expect(marked.segments).toEqual([{ written: mark(0), text: mark(0) }]);
    expect(writtenAgain(marked.source, marked.segments)).toBe(raw);
  });
});

describe("instructionNames", () => {
  it("reads names from complete references without allocating private-use marks", () => {
    const raw =
      "{{a}}".repeat(65535) +
      "\\{{escaped}} {{a.b}} {{unfinished" +
      mark(0).repeat(65535);
    expect(instructionNames(raw)).toEqual(["a", "a.b"]);
  });
});

describe("renamedInstruction", () => {
  it.each(["instruction", "drawing"])(
    "rewrites dense references in raw %s while preserving private-use, escapes, numeric text and unknown names",
    (where) => {
      const dense = "{{a}}{{b}}".repeat(32768);
      const rest =
        mark(0).repeat(65535) + "\\{{a}} &#xF0000; {{a.b}} {{unfinished\n";
      const names = new Map([
        ["a", "b"],
        ["b", "a"],
      ]);
      const rename =
        where === "instruction" ? renamedInstruction : renamedPlaceholders;
      expect(rename(dense + rest, names)).toBe(
        "{{b}}{{a}}".repeat(32768) + rest,
      );
      expect(rename(dense + rest, new Map())).toBe(dense + rest);
    },
  );
});

describe("markPlaceholders", () => {
  it("marks each placeholder where it stands by the order it comes in, leaving the text between as it is", () => {
    const marked = markPlaceholders("Dear {{name_1}}, of {{b}}.");

    expect(marked.source).toBe(`Dear ${mark(0)}, of ${mark(1)}.`);
    expect(marked.segments).toEqual([
      { written: "{{name_1}}", name: "name_1" },
      { written: "{{b}}", name: "b" },
    ]);
  });

  it.each([
    ["one letter", "a"],
    ["a letter, digits and underscores", "a0_9"],
    ["sixty-three characters, the longest a name is", LONGEST_NAME],
  ])("marks a name of %s", (_case, name) => {
    const marked = markPlaceholders(`{{${name}}}`);

    expect(marked.source).toBe(mark(0));
    expect(marked.segments).toEqual([{ written: `{{${name}}}`, name }]);
  });

  it.each([
    ["a name past sixty-three characters", `{{${LONGEST_NAME}c}}`],
    ["a capital letter", "{{Title}}"],
    ["spaces within the braces", "{{ title }}"],
    ["a name starting with a digit", "{{1title}}"],
    ["a name starting with an underscore", "{{_title}}"],
    ["a hyphen", "{{a-b}}"],
    ["a name broken over lines", "{{ti\ntle}}"],
    ["no name", "{{}}"],
    ["one closing brace", "{{title}"],
    ["one opening brace", "{title}}"],
    ["single braces", "{title}"],
    ["a letter outside the alphabet", "{{tïtle}}"],
  ])("reads %s as plain text, marking nothing", (_case, text) => {
    expect(markPlaceholders(text)).toEqual({ source: text, segments: [] });
  });

  it("reads a backslash before two braces as the two braces as they are, the name after it as plain text", () => {
    const marked = markPlaceholders("\\{{title}}");

    expect(marked.source).toBe(`${mark(0)}title}}`);
    expect(marked.segments).toEqual([{ written: "\\{{", text: "{{" }]);
  });

  it.each([
    [
      "a backslash before the escaping one, which stays as it is",
      "\\\\{{a}}",
      `\\${mark(0)}a}}`,
      [{ written: "\\{{", text: "{{" }],
    ],
    [
      "a third brace before a placeholder, which starts at the second",
      "{{{a}}}",
      `{${mark(0)}}`,
      [{ written: "{{a}}", name: "a" }],
    ],
    [
      "an escape followed by a placeholder's last brace and name",
      "\\{{{a}}",
      `${mark(0)}{a}}`,
      [{ written: "\\{{", text: "{{" }],
    ],
    [
      "a backslash alone at the end",
      "{{a}}\\",
      `${mark(0)}\\`,
      [{ written: "{{a}}", name: "a" }],
    ],
    [
      "two placeholders written together",
      "{{a}}{{b}}",
      `${mark(0)}${mark(1)}`,
      [
        { written: "{{a}}", name: "a" },
        { written: "{{b}}", name: "b" },
      ],
    ],
  ])("scans %s as the server does", (_case, text, source, segments) => {
    expect(markPlaceholders(text)).toEqual({ source, segments });
  });

  it.each([
    ["the first of the marks", 0xf0000],
    ["the last of the marks", 0xffffd],
  ])(
    "marks a character written as %s as itself, so it is never read as a placeholder",
    (_case, codePoint) => {
      const marked = markPlaceholders(`{{a}}${of(codePoint)}`);

      expect(marked.source).toBe(`${mark(0)}${mark(1)}`);
      expect(marked.segments).toEqual([
        { written: "{{a}}", name: "a" },
        { written: of(codePoint), text: of(codePoint) },
      ]);
    },
  );

  it.each([
    ["the private use before the marks", 0xeffff],
    ["a noncharacter after the last mark", 0xffffe],
    ["private use in the first plane", 0xe000],
    ["private use in the last plane", 0x100000],
  ])("leaves a character of %s as it is", (_case, codePoint) => {
    expect(markPlaceholders(of(codePoint))).toEqual({
      source: of(codePoint),
      segments: [],
    });
  });

  it.each([
    ["in hexadecimal", "&#xF0000;"],
    ["in hexadecimal with a capital X", "&#XFFFFD;"],
    ["in decimal", "&#983040;"],
    ["in seven decimal digits", "&#0983041;"],
    ["in six hexadecimal digits, a nought leading", "&#x0F0002;"],
  ])(
    "marks a numeric reference to a mark written %s as the reference as written",
    (_case, reference) => {
      const marked = markPlaceholders(`a${reference}b`);

      expect(marked.source).toBe(`a${mark(0)}b`);
      expect(marked.segments).toEqual([
        { written: reference, text: reference },
      ]);
    },
  );

  it.each([
    ["to the private use before the marks", "&#xEFFFF;"],
    ["to a noncharacter after the last mark", "&#xFFFFE;"],
    ["in seven hexadecimal digits, which no parser reads", "&#x00F0000;"],
    ["in eight decimal digits, which no parser reads", "&#00983040;"],
    ["with no semicolon", "&#xF0000"],
    ["by name", "&amp;"],
  ])("leaves a reference %s as it is", (_case, reference) => {
    expect(markPlaceholders(reference)).toEqual({
      source: reference,
      segments: [],
    });
  });

  it("leaves half a pair as it is, the mark after it whole", () => {
    const marked = markPlaceholders(`${String.fromCharCode(0xdb80)}{{a}}`);

    expect(marked.source).toBe(`${String.fromCharCode(0xdb80)}${mark(0)}`);
    expect(marksIn(marked.source)).toEqual([String.fromCharCode(0xdb80), 0]);
  });

  it("marks as many characters as there are marks, the last of them the last mark", () => {
    const marked = markPlaceholders(of(0xf0000).repeat(65534));

    expect(marked.segments).toHaveLength(65534);
    expect(marked.source.slice(-2)).toBe(of(0xffffd));
  });

  it("refuses text holding more to mark than there are marks", () => {
    expect(() => markPlaceholders(of(0xf0000).repeat(65535))).toThrow(
      RangeError,
    );
  });
});

describe("marksIn", () => {
  it("cuts text where each mark stands, giving the number it carries", () => {
    expect(marksIn(`a${mark(0)}${mark(1)}bc${mark(65533)}`)).toEqual([
      "a",
      0,
      1,
      "bc",
      65533,
    ]);
  });

  it.each([
    ["text holding no mark", "a{{b}}c", ["a{{b}}c"]],
    ["no text", "", []],
    [
      "characters either side of the marks",
      of(0xeffff, 0xffffe),
      [of(0xeffff, 0xffffe)],
    ],
  ])("gives %s as it is", (_case, text, pieces) => {
    expect(marksIn(text)).toEqual(pieces);
  });
});

describe("writtenAgain", () => {
  it.each([
    '![{{alt}}](https://e.example/{{src}} "\\{{t}}")',
    `<a href="{{url}}">&#xF0000; ${of(0xf0001)}</a>`,
    "plain",
  ])("puts every mark back as it was written: %s", (text) => {
    const { source, segments } = markPlaceholders(text);

    expect(writtenAgain(source, segments)).toBe(text);
  });

  it("puts back the marks in a part of the source alone", () => {
    const { source, segments } = markPlaceholders("{{a}} and {{b}}");

    expect(writtenAgain(source.slice(2), segments)).toBe(" and {{b}}");
  });
});

describe("holdsMark", () => {
  it.each([
    ["a mark as it is", `https://e.example/${mark(3)}`],
    ["the first mark percent-encoded", "https://e.example/%F3%B0%80%80"],
    ["the last mark percent-encoded in lower case", "x%f3%bf%bf%bd"],
  ])("finds %s", (_case, text) => {
    expect(holdsMark(text)).toBe(true);
  });

  it.each([
    ["plain text", "https://e.example/a?b=%20"],
    ["a tag percent-encoded", "%F3%A0%80%81"],
    ["private use in the first plane percent-encoded", "%EE%80%80"],
    ["private use in the first plane as it is", of(0xe000)],
    ["a character just before the marks", of(0xeffff)],
  ])("finds none in %s", (_case, text) => {
    expect(holdsMark(text)).toBe(false);
  });
});
