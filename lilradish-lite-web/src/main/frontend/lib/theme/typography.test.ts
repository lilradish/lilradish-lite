import { describe, expect, it } from "vitest";

import { theme } from "./theme";
import { styleViolations } from "./stylePolicy";

/** Every production module but the theme and its static typography authority. */
const SOURCES = import.meta.glob<string>(
  [
    "../../**/*.{ts,tsx}",
    "!../../testutil/**",
    "!../../testSetup.ts",
    "!**/*.test.{ts,tsx}",
    "!./theme.ts",
    "!./tokens.ts",
  ],
  { query: "?raw", import: "default", eager: true },
);

const TYPE_PROPERTIES = [
  "fontFamily",
  "fontStyle",
  "letterSpacing",
  "textTransform",
  "font-family",
  "font-style",
  "letter-spacing",
  "text-transform",
].join("|");

/**
 * A type property written as a key or a declaration: bare, quoted or computed,
 * in `sx`, `style`, a style object or template CSS.
 */
const SETS_TYPE_PROPERTY = new RegExp(
  `(?<![\\w.$-])\\[?["'\`]?(?:${TYPE_PROPERTIES})["'\`]?\\]?\\s*:`,
);

const SETS_ROLE_PROPERTY =
  /(?<![\w.$-])\[?["'`]?(?:fontSize|fontWeight|lineHeight|font-size|font-weight|line-height)["'`]?\]?\s*:/;

/** The shorthand sets all of them at once; `inherit` hands down what is around it and sets nothing. */
const SETS_FONT_SHORTHAND =
  /(?<![\w.$-])\[?["'`]?font["'`]?\]?\s*:(?!\s*["'`]?inherit\b)/;

/** A role named in `sx`, or anywhere else a style object takes one. */
const NAMES_ROLE = /(?<![\w.$-])typography:\s*["'`]([^"'`]*)["'`]/g;

/** A heading written as the element: in JSX, or named for one by `component`, spaced, quoted or in braces. */
const WRITES_HEADING =
  /<h[1-6][\s>/]|(?<![\w.$-])component\s*=\s*\{?\s*["'`]h[1-6]["'`]/;

/** An element chosen apart from the variant, which nothing here does. */
const CHOOSES_ELEMENT = /(?<![\w.$-])(?:variantMapping|as)\s*=/;

/** What each tag drawing type by a variant renders unless told otherwise; Typography renders the variant's own. */
const RENDERS = { Typography: null, DialogTitle: "h2", Link: "a" } as const;

const TAG = /<(Typography|DialogTitle|Link)\b/g;

/** An attribute's value: a plain string, written bare or in braces, or anything else as worked out. */
function valueOf(tag: string, attribute: string): string | null | undefined {
  const found = new RegExp(
    `(?<![\\w.$-])${attribute}\\s*=\\s*(?:"([^"]*)"|'([^']*)'|\\{\\s*(?:"([^"]*)"|'([^']*)')\\s*\\}|\\{)`,
  ).exec(tag);
  return found === null
    ? undefined
    : (found.slice(1).find((value) => value !== undefined) ?? null);
}

/**
 * Each opening tag of those, read to the `>` outside every expression and string in it, or null where it never
 * closes: an expression closing more than it opened, another `<`, or the next tag of those come first.
 */
function tagsIn(
  source: string,
): { name: keyof typeof RENDERS; text: string | null }[] {
  const found = [...source.matchAll(TAG)];
  return found.map((tag, index) => {
    const name = tag[1] as keyof typeof RENDERS;
    const end = found[index + 1]?.index ?? source.length;
    let depth = 0;
    for (let at = tag.index + tag[0].length; at < end; at += 1) {
      const character = source[at]!;
      if (character === '"' || character === "'" || character === "`") {
        const closing = source.indexOf(character, at + 1);
        if (closing === -1 || closing >= end) {
          break;
        }
        at = closing;
      } else if (character === "{") {
        depth += 1;
      } else if (character === "}") {
        depth -= 1;
        if (depth < 0) {
          break;
        }
      } else if (depth === 0 && character === "<") {
        break;
      } else if (depth === 0 && character === ">") {
        return { name, text: source.slice(tag.index, at + 1) };
      }
    }
    return { name, text: null };
  });
}

function isHeading(value: string | null | undefined): value is string {
  return value != null && /^h[1-6]$/.test(value);
}

/** A tag whose variant draws a heading's look on another element, or on an element worked out apart from it. */
function drawsHeadingApart(name: keyof typeof RENDERS, tag: string): boolean {
  const variant = valueOf(tag, "variant");
  const component = valueOf(tag, "component");
  const renders = RENDERS[name];
  // A dialog's title keeps its heading's look under any element it is given.
  const ownHeading = variant === undefined && isHeading(renders);
  if (component === null) {
    return variant === null || isHeading(variant) || ownHeading;
  }
  if (component !== undefined) {
    return isHeading(variant) || ownHeading;
  }
  return (
    renders !== null &&
    (isHeading(variant)
      ? variant !== renders
      : variant === null && isHeading(renders))
  );
}

/**
 * Typography draws a heading at its variant's own level, so a level is named
 * by the variant alone: one also named by the element can tell another story.
 */
function namesHeadingApartFromVariant(source: string): boolean {
  return (
    WRITES_HEADING.test(source) ||
    CHOOSES_ELEMENT.test(source) ||
    tagsIn(source).some(
      ({ name, text }) => text === null || drawsHeadingApart(name, text),
    )
  );
}

function setsTypeDirectly(source: string): boolean {
  return (
    SETS_TYPE_PROPERTY.test(source) ||
    SETS_FONT_SHORTHAND.test(source) ||
    (SETS_ROLE_PROPERTY.test(source) &&
      styleViolations("typography.tsx", source).some((failure) =>
        failure.endsWith(": font value outside a typography role"),
      ))
  );
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

  /** A level drawn in another level's variant stands out of the outline it sits in. */
  it("names every heading's level by its variant alone", () => {
    const offenders = Object.entries(SOURCES)
      .filter(([, source]) => namesHeadingApartFromVariant(source))
      .map(([path]) => path);

    expect(offenders).toEqual([]);
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
      ]),
    );
    expect(
      read.filter(
        (path) =>
          path === "./theme.ts" ||
          path === "./tokens.ts" ||
          path.includes("/testutil/") ||
          path.includes("testSetup") ||
          path.includes(".test."),
      ),
    ).toEqual([]);
  });

  it.each([
    ["an sx weight", "<Box sx={{ fontWeight: 600 }} />", true],
    ["a style size", "<Box style={{ fontSize : '1rem' }} />", true],
    ["a family in a style object", "const X = { fontFamily: FACE };", true],
    ["a slant", 'const sx = { fontStyle: "italic" };', true],
    ["a letter spacing", 'const sx = { letterSpacing: "0.1em" };', true],
    ["a line height", "const sx = { lineHeight: 1.5 };", true],
    ["a case", 'const sx = { textTransform: "uppercase" };', true],
    ["a quoted key", 'const sx = { "fontWeight": 700 };', true],
    ["a quoted kebab-case key", "const sx = { 'font-size': '12px' };", true],
    [
      "a declaration in template CSS",
      "styled.span`\n  line-height: 2;\n`",
      true,
    ],
    ["a computed key", 'const sx = { ["fontFamily"]: FACE };', true],
    ["a computed template key", "const sx = { [`letterSpacing`]: 1 };", true],
    ["the shorthand", 'const sx = { font: "700 12px serif" };', true],
    ["the shorthand, quoted", 'const sx = { "font": FACE };', true],
    ["the shorthand in template CSS", "`font: bold 1rem serif;`", true],
    ["a role", "<Box sx={{ typography: 'term' }} />", false],
    [
      "a token role's weight",
      "const sx = { fontWeight: TOKENS.type.roles.body.fontWeight };",
      false,
    ],
    [
      "a theme role's size",
      "const sx = { fontSize: theme.typography.body.fontSize };",
      false,
    ],
    [
      "a theme role's line height",
      "const sx = { lineHeight: theme.typography.body.lineHeight };",
      false,
    ],
    ["an inherited weight", 'const sx = { fontWeight: "inherit" };', false],
    [
      "a role held by a local name",
      "const weight = TOKENS.type.roles.body.fontWeight; const sx = { fontWeight: weight };",
      false,
    ],
    [
      "a role or inherited weight",
      'const sx = { fontWeight: flag ? TOKENS.type.roles.body.fontWeight : "inherit" };',
      false,
    ],
    [
      "a computed role property",
      'const sx = { ["fontWeight"]: TOKENS.type.roles.body.fontWeight };',
      false,
    ],
    [
      "a CSS role variable",
      "styled.span`font-weight: var(--lr-type-roles-body-font-weight);`",
      false,
    ],
    [
      "a token role changed by arithmetic",
      "const sx = { fontWeight: TOKENS.type.roles.body.fontWeight + 1 };",
      true,
    ],
    [
      "a theme role changed by arithmetic",
      "const sx = { fontSize: theme.typography.body.fontSize + 1 };",
      true,
    ],
    [
      "a role with a literal alternative",
      "const sx = { fontWeight: flag ? TOKENS.type.roles.body.fontWeight : 400 };",
      true,
    ],
    [
      "a local name holding a literal",
      "const weight = 400; const sx = { fontWeight: weight };",
      true,
    ],
    [
      "a role beside a literal property",
      "const sx = { fontWeight: TOKENS.type.roles.body.fontWeight, fontSize: 14 };",
      true,
    ],
    [
      "a property outside the role's type values",
      "const sx = { fontWeight: TOKENS.type.roles.body.weight };",
      true,
    ],
    [
      "a non-role theme number",
      "const sx = { fontSize: theme.typography.fontSize };",
      true,
    ],
    ["a quoted computed literal", 'const sx = { ["fontWeight"]: 400 };', true],
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
    [
      "the role the product's name is set in",
      'sx={{ typography: "brand" }}',
      ["brand"],
      true,
    ],
  ])(
    "reads %s as a role named, and knows whether it is one",
    (_case, source, named, known) => {
      expect(rolesNamedIn(source)).toEqual(named);
      expect(named.every(isRole)).toBe(known);
    },
  );

  it.each([
    [
      "a level written as the element too",
      '<Typography variant="h3" component="h3">',
      true,
    ],
    [
      "a level in another level's look",
      '<Typography component="h4" variant="subtitle2">',
      true,
    ],
    [
      "the same across lines",
      '<Typography\n  variant="h6"\n  component="h2"\n>',
      true,
    ],
    ["a level written in braces", "<Typography component={'h2'}>", true],
    ["a level on an element with no type", '<Box component="h3">', true],
    [
      "a level worked out under a fixed look",
      '<Typography variant="h5" component={level}>',
      true,
    ],
    [
      "a level and a look worked out apart",
      "<Typography variant={LOOK[tag]} component={tag} sx={X}>",
      true,
    ],
    [
      "the same past an arrow in an earlier expression",
      '<Typography ref={(node) => keep(node)} title="a > b" component={level} variant="h2">',
      true,
    ],
    ["a level named by its variant", '<Typography variant="h3">', false],
    ["a level worked out", "<Typography variant={level}>", false],
    [
      "a label's look on a paragraph",
      '<Typography variant="subtitle2" component="p">',
      false,
    ],
    [
      "a heading's look on what is no heading",
      '<Typography variant="h6" component="p">',
      true,
    ],
    [
      "a level in a template, though it names one level",
      "<Typography component={`h2`}>",
      true,
    ],
    [
      "a level worked out in a template",
      '<Typography variant="h2" component={`h${level}`}>',
      true,
    ],
    ["a level spaced from its name", '<Typography component = "h2">', true],
    ["a heading written as itself", '<h2 id="title">Steps</h2>', true],
    ["a heading closed at once", "<h3>Steps</h3>", true],
    [
      "a mapping of variants to elements",
      '<Typography variantMapping={{ h6: "p" }}>',
      true,
    ],
    ["an element named apart", '<Box as="h2">', true],
    [
      "a dialog's title in another level's look",
      '<DialogTitle variant="h3">',
      true,
    ],
    [
      "a dialog's title in a look worked out",
      "<DialogTitle variant={look}>",
      true,
    ],
    ["a link in a heading's look", '<Link variant="h2">', true],
    [
      "a link in a heading's look on an element worked out",
      '<Link variant="h2" component={tag}>',
      true,
    ],
    ["a tag that never closes", '<Typography variant="h2" sx={{ mb: 1 }', true],
    [
      "a tag closing an expression it never opened",
      '<Typography variant="body2" ref={keep}} >',
      true,
    ],
    ["a tag running into the next", '<Typography variant="body2" <Box>', true],
    [
      "a tag whose expression opens the next",
      '<Typography a={<Typography variant="h2">',
      true,
    ],
    [
      "a dialog's title on an element worked out",
      "<DialogTitle component={tag}>",
      true,
    ],
    [
      "a dialog's title on another element",
      '<DialogTitle component="div">',
      true,
    ],
    [
      "an element worked out under a body look",
      '<Typography variant="body2" component={RouterLink}>',
      false,
    ],
    [
      "a level worked out on an element that only follows the tag",
      '<Typography variant="body2">x</Typography>\n<Box component={tag}>',
      false,
    ],
    [
      "the same past a brace inside a string inside an expression",
      '<Typography aria-label={"{"} variant="body2">x</Typography>\n<Box component={tag} variant="h2">',
      false,
    ],
    [
      "the product's name in its own role",
      '<Typography variant="brand">',
      false,
    ],
    [
      "a dialog's title as Material draws it",
      "<DialogTitle>Add</DialogTitle>",
      false,
    ],
    [
      "a dialog's title in its own level's look",
      '<DialogTitle variant="h2">',
      false,
    ],
    [
      "a link drawn by the router in a body look",
      '<Link component={RouterLink} to="/" variant="body2">',
      false,
    ],
    ["an element whose name starts as a heading's", "<header>", false],
  ])(
    "takes %s for a heading named apart from its variant: %s",
    (_case, source, caught) => {
      expect(namesHeadingApartFromVariant(source)).toBe(caught);
    },
  );
});
