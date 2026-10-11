import postcss from "postcss";
import { describe, expect, it, vi } from "vitest";
import { checkStylePolicy, styleViolations } from "./stylePolicy";
import { checkTokenCss } from "./tokenCss";

const { readFileSync, readdirSync } = await vi.importActual<{
  readFileSync(path: string, encoding: "utf8"): string;
  readdirSync(
    path: string,
    options: { withFileTypes: true },
  ): { name: string; isDirectory(): boolean; isFile(): boolean }[];
}>("node:fs");
const { dirname, join, relative } = await vi.importActual<{
  dirname(path: string): string;
  join(...paths: string[]): string;
  relative(from: string, to: string): string;
}>("node:path");
const { fileURLToPath } = await vi.importActual<{
  fileURLToPath(url: string): string;
}>("node:url");

describe("source style policy", () => {
  it.each([
    ["card.tsx", 'const sx = {color: "red"};'],
    ["card.tsx", 'const sx = {bgcolor: "red"};'],
    ["card.tsx", 'const sx = {backgroundImage: "linear-gradient(red, blue)"};'],
    ["card.tsx", 'const sx = {color: flag ? "red" : "inherit"};'],
    ["card.tsx", 'const sx = {color: flag && "red"};'],
    ["card.tsx", 'const sx = {color: chosen || "red"};'],
    ["card.tsx", 'const sx = {color: chosen ?? "red"};'],
    ["card.tsx", 'const sx = {color: () => { return "red"; }};'],
    [
      "card.tsx",
      'const sx = {color: () => { if (flag) return "inherit"; return "red"; }};',
    ],
    ["card.tsx", 'const sx = {["co" + "lor"]: flag && "red"};'],
    ["card.tsx", 'const sx = {color: true && "red"};'],
    ["card.tsx", 'const sx = {color: false && "red"};'],
    ["card.tsx", 'const sx = {color: false || "red"};'],
    ["card.tsx", 'const sx = {color: true || "red"};'],
    ["card.tsx", 'const sx = {color: null ?? "red"};'],
    ["card.tsx", 'const sx = {color: "inherit" || "red"};'],
    ["card.tsx", 'const sx = {color: "inherit" ?? "red"};'],
    ["card.tsx", 'const sx = {color: "red" && chosen};'],
    ["card.tsx", 'const base = "inherit"; const sx = {color: base || "red"};'],
    ["card.tsx", 'const sx = {color: function () { return "red"; }};'],
    ["card.tsx", 'const sx = {color: "red" satisfies string};'],
    ["card.tsx", 'const sx = {color: "red"!};'],
    ["card.ts", 'const sx = {color: <string>"red"};'],
    ["card.tsx", 'const sx = {[flag ? "color" : "margin"]: "red"};'],
    ["card.tsx", 'const sx = {color: ["red", "inherit"]};'],
    ["card.tsx", 'const sx = {["co" + "lor"]: flag ? "red" : "inherit"};'],
    ["card.tsx", '<Box color={flag ? "red" : "inherit"} />;'],
    ["card.tsx", '<Box sx={{bgcolor: {xs: "red", md: "inherit"}}} />;'],
    [
      "card.tsx",
      '<Box sx={[{backgroundImage: flag ? "linear-gradient(red, blue)" : "none"}]} />;',
    ],
    [
      "card.tsx",
      'const shade = flag ? "r" : "blue"; const sx = {color: shade + "ed"};',
    ],
    [
      "card.tsx",
      'const shade = flag ? "red" : "inherit"; const sx = {border: `1px solid ${shade}`};',
    ],
    ["card.tsx", 'const sx = {border: "1px solid #fff"};'],
    ["card.tsx", 'const colour = "r" + "ed"; const sx = {color: colour};'],
    [
      "card.tsx",
      'const colour = "red"; function card() { return {color: colour}; } function other() { const colour = "inherit"; return colour; }',
    ],
    [
      "card.tsx",
      'const weight = weight + ""; const sx = {fontWeight: weight};',
    ],
    [
      "card.tsx",
      'const shades = {edge: "red"}; const sx = {borderColor: shades.edge};',
    ],
    ["card.tsx", "const sx = {backgroundColor: `rgb(${amount}, 0, 0)`};"],
    ["card.tsx", 'const sx = {color: "\\x72ed"};'],
    [
      "card.tsx",
      'const theme = createTheme({palette: {primary: {main: "red"}}});',
    ],
    ["card.tsx", "const sx = {fontSize: 14};"],
    ["card.tsx", 'const sx = {["lineHeight"]: "20px"};'],
    ["card.tsx", "const sx = {fontSize: theme.typography.body1.fontSize + 1};"],
    [
      "card.tsx",
      "const sx = {fontSize: flag ? given.typography.body.fontSize : 14};",
    ],
    ["card.tsx", "const sx = {fontSize: () => {}};"],
    ["card.tsx", '<Icon fontSize="small" />;'],
    ["card.tsx", "styled.span`color: red; font-size: 12px;`"],
    ["card.tsx", 'styled.span`color: ${(props) => props.on && "red"};`'],
    ["card.tsx", 'css`color: ${flag ? "red" : "inherit"};`'],
    ["card.css", ".card { color: r\\65 d; }"],
    ["card.css", ".card { box-shadow: 0 0 4px rgba(0, 0, 0, .2); }"],
    ["card.css", ".card { font: 14px/20px sans-serif; }"],
    ["card.css", ".card { --old-colour: hsl(0 0% 20%); }"],
    [
      "features/library/tokens.ts",
      'export const TOKENS = {ground: "#fff", fontSize: 14};',
    ],
    ["features/library/tokens.generated.css", ":root { --old-ground: #fff; }"],
  ])("fails an actual forbidden source, %s: %s", (path, source) => {
    expect(() => checkStylePolicy({ [path]: source })).toThrow(path);
  });

  it("allows exact role references, inheritance and ordinary business words", () => {
    expect(
      styleViolations(
        "card.tsx",
        'const field = "field"; const mark = "mark"; const sx = {color: given.palette.ink.primary, fontSize: given.typography.body.fontSize, lineHeight: "inherit", fontWeight: TOKENS.type.roles.body.fontWeight, border: "1px solid currentColor", background: "transparent"};',
      ),
    ).toEqual([]);
    expect(
      styleViolations(
        "card.css",
        '.red { color: var(--lr-ink-primary); font-size: var(--lr-type-roles-body-font-size); line-height: inherit; background: transparent; outline: 1px solid currentColor; content: "red"; mask: url(#fff); } /* red #fff */',
      ),
    ).toEqual([]);
  });

  it.each([
    'const sx = {color: flag ? given.palette.ink.primary : "inherit"};',
    'const sx = {color: flag && "inherit"};',
    'const sx = {color: chosen || "ink.primary"};',
    'const sx = {color: chosen ?? "currentColor"};',
    'const sx = {color: () => { return "inherit"; }};',
    'const sx = {color: () => { const business = () => "red"; return "ink.primary"; }};',
    'const sx = {color: "ink.primary" && given.palette.ink.primary};',
    'const sx = {color: "transparent" || "currentColor"};',
    'const sx = {color: function () { return "ink.primary"; }};',
    'const sx = {color: "inherit" satisfies string};',
    "const sx = {color: given.palette.ink.primary!};",
    "const sx = {fontSize: (theme) => { return theme.typography.body1.fontSize; }};",
    'const sx = {fontWeight: flag ? TOKENS.type.roles.body.fontWeight : "inherit"};',
    "const sx = {lineHeight: function () { return given.typography.body.lineHeight; }};",
    "const sx = {fontSize: given.typography.body.fontSize satisfies number};",
    'styled.span`color: ${(props) => props.on && "var(--lr-ink-primary)"};`',
    'const sx = {color: [given.palette.ink.primary, "inherit"]};',
    'const sx = {["co" + "lor"]: flag ? "surface.card" : "currentColor"};',
    '<Box color={flag ? "ink.primary" : "inherit"} />;',
    '<Box sx={{bgcolor: {xs: "surface.card", md: "transparent"}}} />;',
    '<Box sx={{bgcolor: {xs: "background.paper", md: "background.default"}, color: "text.primary"}} />;',
    'const words = flag ? "red" : "blue"; const business = ["red", "field", "mark"];',
  ])("keeps legal conditional and responsive input: %s", (source) => {
    expect(() => checkStylePolicy({ "card.tsx": source })).not.toThrow();
  });

  const borderWords = (parts: number, last: string) =>
    Array.from(
      { length: parts },
      (_, at) =>
        `\${f${at} ? "${at === parts - 1 ? last : "solid"}" : "dashed"}`,
    ).join(" ");

  it.each([
    [8, 256],
    [9, 18],
    [18, 36],
  ])(
    "reads a %i-part template in %i stylesheets, once per choice past the combination limit",
    (parts, stylesheets) => {
      const parse = vi.spyOn(postcss, "parse");

      const failures = styleViolations(
        "card.tsx",
        `css\`border: ${borderWords(parts, "double")};\``,
      );

      expect(parse).toHaveBeenCalledTimes(stylesheets);
      expect(failures).toEqual([]);
    },
  );

  it.each([
    [8, "template", (words: string) => `css\`border: ${words};\``],
    [18, "template", (words: string) => `css\`border: ${words};\``],
    [8, "style value", (words: string) => `const sx = {border: \`${words}\`};`],
    [
      18,
      "style value",
      (words: string) => `const sx = {border: \`${words}\`};`,
    ],
  ])(
    "refuses a colour named in one of %i varying parts of a %s",
    (parts, _form, source) => {
      expect(
        styleViolations("card.tsx", source(borderWords(parts, "red"))),
      ).toEqual(["card.tsx:1: literal colour; name a token role"]);
    },
  );

  it("checks every production source and the one derived stylesheet", () => {
    // Read source bytes, as the build hook does. Vitest's CSS module handling
    // can return an empty raw import and would silently exempt a stylesheet.
    const frontend = join(dirname(fileURLToPath(import.meta.url)), "../..");
    const all: Record<string, string> = {};
    function read(directory: string) {
      for (const entry of readdirSync(directory, { withFileTypes: true })) {
        const path = join(directory, entry.name);
        if (entry.isDirectory()) read(path);
        else if (entry.isFile() && /\.(?:ts|tsx|css)$/.test(entry.name)) {
          all[relative(frontend, path).split("\\").join("/")] = readFileSync(
            path,
            "utf8",
          );
        }
      }
    }
    read(frontend);
    expect(Object.keys(all)).toEqual(
      expect.arrayContaining([
        "main.tsx",
        "lib/theme/theme.ts",
        "features/runs/fill/ValueCard.tsx",
        "lib/text/modelProseMarkdown.css",
        "lib/theme/tokens.ts",
        "lib/theme/tokens.generated.css",
      ]),
    );
    const files = Object.fromEntries(
      Object.entries(all).filter(
        ([path]) =>
          !/\.test\.tsx?$|^testutil\/|^testSetup\.ts$/.test(path) &&
          path !== "lib/theme/tokens.generated.css",
      ),
    );
    checkStylePolicy(files);
    checkTokenCss(
      all["lib/theme/tokens.ts"],
      all["lib/theme/tokens.generated.css"],
    );
  });
});
