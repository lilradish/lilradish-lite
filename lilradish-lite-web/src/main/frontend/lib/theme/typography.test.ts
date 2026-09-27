import { describe, expect, it } from "vitest";

import { theme } from "./theme";

/** Every production module, read as text, but the theme that defines type. */
const SOURCES = import.meta.glob<string>(
  [
    "../../**/*.{ts,tsx}",
    "!../../testutil/**",
    "!../../testSetup.ts",
    "!**/*.test.{ts,tsx}",
    "!./theme.ts",
  ],
  { query: "?raw", import: "default", eager: true },
);

const TYPE_PROPERTIES = [
  "fontSize",
  "fontWeight",
  "fontFamily",
  "fontStyle",
  "letterSpacing",
  "lineHeight",
  "textTransform",
  "font-size",
  "font-weight",
  "font-family",
  "font-style",
  "letter-spacing",
  "line-height",
  "text-transform",
].join("|");

/**
 * A type property written as a key or a declaration: bare, quoted or computed,
 * in `sx`, `style`, a style object or template CSS.
 */
const SETS_TYPE_PROPERTY = new RegExp(
  `(?<![\\w.$-])\\[?["'\`]?(?:${TYPE_PROPERTIES})["'\`]?\\]?\\s*:`,
);

/** The shorthand sets all of them at once; `inherit` hands down what is around it and sets nothing. */
const SETS_FONT_SHORTHAND =
  /(?<![\w.$-])\[?["'`]?font["'`]?\]?\s*:(?!\s*["'`]?inherit\b)/;

/** A role named in `sx`, or anywhere else a style object takes one. */
const NAMES_ROLE = /(?<![\w.$-])typography:\s*["'`]([^"'`]*)["'`]/g;

function setsTypeDirectly(source: string): boolean {
  return SETS_TYPE_PROPERTY.test(source) || SETS_FONT_SHORTHAND.test(source);
}

function rolesNamedIn(source: string): string[] {
  return [...source.matchAll(NAMES_ROLE)].map((found) => found[1]!);
}

/** Only a variant is a style; the rest of `theme.typography` is numbers and a function. */
function isRole(name: string): boolean {
  const entry = (theme.typography as unknown as Record<string, unknown>)[name];
  return typeof entry === "object" && entry !== null;
}

describe("typography", () => {
  /** Set anywhere but the theme, a size or a weight drifts from the rest one screen at a time. */
  it("is set by role everywhere outside the theme, never property by property", () => {
    const offenders = Object.entries(SOURCES)
      .filter(([, source]) => setsTypeDirectly(source))
      .map(([path]) => path);

    expect(offenders).toEqual([]);
  });

  /** `sx` takes any string as a role and draws nothing for one the theme lacks. */
  it("names only roles the theme defines", () => {
    const named = Object.values(SOURCES).flatMap(rolesNamedIn);

    expect(named.length).toBeGreaterThan(0);
    expect(named).toContain("term");
    expect(named.filter((role) => !isRole(role))).toEqual([]);
  });

  /** Two nothings agree: the guard is only as good as the tree it was handed. */
  it("reads the production tree it guards, and neither the theme nor anything only a test runs", () => {
    const read = Object.keys(SOURCES);

    expect(read).toEqual(
      expect.arrayContaining([
        "../../main.tsx",
        "../../api/problem.ts",
        "../../app/ProblemView.tsx",
        "../../features/people/PeoplePage.tsx",
        "../../i18n/en.ts",
        "../collection/TaskCard.tsx",
      ]),
    );
    expect(
      read.filter(
        (path) =>
          path === "./theme.ts" ||
          path.includes("/testutil/") ||
          path.includes("testSetup") ||
          path.includes(".test."),
      ),
    ).toEqual([]);
  });

  it.each([
    ["an sx weight", "sx={{ fontWeight: 600 }}", true],
    ["a style size", "style={{ fontSize : '1rem' }}", true],
    ["a family in a style object", "const X = { fontFamily: FACE };", true],
    ["a slant", '{ fontStyle: "italic" }', true],
    ["a letter spacing", '{ letterSpacing: "0.1em" }', true],
    ["a line height", "{ lineHeight: 1.5 }", true],
    ["a case", '{ textTransform: "uppercase" }', true],
    ["a quoted key", '{ "fontWeight": 700 }', true],
    ["a quoted kebab-case key", "{ 'font-size': '12px' }", true],
    [
      "a declaration in template CSS",
      "styled.span`\n  line-height: 2;\n`",
      true,
    ],
    ["a computed key", '{ ["fontFamily"]: FACE }', true],
    ["a computed template key", "{ [`letterSpacing`]: 1 }", true],
    ["the shorthand", '{ font: "700 12px serif" }', true],
    ["the shorthand, quoted", '{ "font": FACE }', true],
    ["the shorthand in template CSS", "`font: bold 1rem serif;`", true],
    ["a role", "sx={{ typography: 'term' }}", false],
    ["an icon's own size prop", '<Icon fontSize="small" />', false],
    [
      "the shorthand handing down the type around it",
      '{ font: "inherit" }',
      false,
    ],
    ["the same in template CSS", "`font: inherit;`", false],
    [
      "a read of the theme's own value",
      "const size = theme.typography.fontSize;",
      false,
    ],
    ["a key that only ends in the word", "{ webfont: true }", false],
  ])("takes %s for type set directly: %s", (_case, source, caught) => {
    expect(setsTypeDirectly(source)).toBe(caught);
  });

  it.each([
    ["a role the theme adds", 'sx={{ typography: "term" }}', ["term"], true],
    ["one of Material's own", "sx={{ typography: 'body2' }}", ["body2"], true],
    ["a misspelt role", 'sx={{ typography: "trem" }}', ["trem"], false],
    [
      "a number the theme holds",
      'sx={{ typography: "fontSize" }}',
      ["fontSize"],
      false,
    ],
    ["its function", 'sx={{ typography: "pxToRem" }}', ["pxToRem"], false],
  ])(
    "reads %s as a role named, and knows whether it is one",
    (_case, source, named, known) => {
      expect(rolesNamedIn(source)).toEqual(named);
      expect(named.every(isRole)).toBe(known);
    },
  );
});
