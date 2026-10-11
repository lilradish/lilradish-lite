import {
  isTokenFunction,
  isTokenHash,
  isTokenIdent,
  tokenize,
} from "@csstools/css-tokenizer";
import postcss from "postcss";
import ts from "typescript";

// CSS Color 4 named and system colours. These are syntax names, never UI values.
const COLOUR_NAMES = new Set(
  (
    "aliceblue antiquewhite aqua aquamarine azure beige bisque black blanchedalmond blue blueviolet brown burlywood cadetblue chartreuse chocolate coral cornflowerblue cornsilk crimson cyan darkblue darkcyan darkgoldenrod darkgray darkgreen darkgrey darkkhaki darkmagenta darkolivegreen darkorange darkorchid darkred darksalmon darkseagreen darkslateblue darkslategray darkslategrey darkturquoise darkviolet deeppink deepskyblue dimgray dimgrey dodgerblue firebrick floralwhite forestgreen fuchsia gainsboro ghostwhite gold goldenrod gray green greenyellow grey honeydew hotpink indianred indigo ivory khaki lavender lavenderblush lawngreen lemonchiffon lightblue lightcoral lightcyan lightgoldenrodyellow lightgray lightgreen lightgrey lightpink lightsalmon lightseagreen lightskyblue lightslategray lightslategrey lightsteelblue lightyellow lime limegreen linen magenta maroon mediumaquamarine mediumblue mediumorchid mediumpurple mediumseagreen mediumslateblue mediumspringgreen mediumturquoise mediumvioletred midnightblue mintcream mistyrose moccasin navajowhite navy oldlace olive olivedrab orange orangered orchid palegoldenrod palegreen paleturquoise palevioletred papayawhip peachpuff peru pink plum powderblue purple rebeccapurple red rosybrown royalblue saddlebrown salmon sandybrown seagreen seashell sienna silver skyblue slateblue slategray slategrey snow springgreen steelblue tan teal thistle tomato turquoise violet wheat white whitesmoke yellow yellowgreen " +
    "accentcolor accentcolortext activetext buttonborder buttonface buttontext canvas canvastext field fieldtext graytext highlight highlighttext linktext mark marktext selecteditem selecteditemtext visitedtext " +
    "activeborder activecaption appworkspace background buttonhighlight buttonshadow captiontext inactiveborder inactivecaption inactivecaptiontext infobackground infotext menu menutext scrollbar threeddarkshadow threedface threedhighlight threedlightshadow threedshadow window windowframe windowtext"
  ).split(" "),
);
const COLOUR_FUNCTIONS = new Set([
  "rgb",
  "rgba",
  "hsl",
  "hsla",
  "hwb",
  "lab",
  "lch",
  "oklab",
  "oklch",
  "color",
  "color-mix",
]);
const FONT_PROPERTIES = new Set([
  "fontSize",
  "lineHeight",
  "fontWeight",
  "font-size",
  "line-height",
  "font-weight",
  "font",
]);
const STYLE_PROPERTIES =
  /^(?:color|bgcolor|background(?:Color|Image)?|.*(?:Color|Shadow)|border.*|outline.*|fill|stroke|box-shadow|text-shadow|.*-color|--.*)$/;

const DYNAMIC = "var(--dynamic)";
const COMBINATION_LIMIT = 256;

// Past the limit each choice is checked with the other varying parts dynamic,
// so a colour formed where two varying parts meet can be missed or misread.
function combine(parts: readonly string[][]): string[] {
  const choices = parts.map((part) => [...new Set(part)]);
  if (
    choices.reduce((count, part) => count * part.length, 1) <= COMBINATION_LIMIT
  )
    return choices.reduce<string[]>(
      (variants, part) =>
        variants.flatMap((first) => part.map((last) => first + last)),
      [""],
    );
  const fixed = choices.map((part) => (part.length === 1 ? part[0] : DYNAMIC));
  return choices.flatMap((part, index) =>
    part.length === 1
      ? []
      : part.map((choice) =>
          fixed.map((text, at) => (at === index ? choice : text)).join(""),
        ),
  );
}

function literalColour(value: string, named: boolean): boolean {
  return tokenize({ css: value }).some(
    (token) =>
      (isTokenHash(token) &&
        /^(?:[\da-f]{3}|[\da-f]{4}|[\da-f]{6}|[\da-f]{8})$/i.test(
          token[4].value,
        )) ||
      (isTokenFunction(token) &&
        COLOUR_FUNCTIONS.has(token[4].value.toLowerCase())) ||
      (named &&
        isTokenIdent(token) &&
        COLOUR_NAMES.has(token[4].value.toLowerCase())),
  );
}

/** Build and tests supply source bytes; no application modules are imported. */
export function styleViolations(path: string, source: string): string[] {
  const failures = new Set<string>();
  const report = (line: number, reason: string) => {
    failures.add(`${path}:${line}: ${reason}`);
  };
  // The sole manually authored token authority; its derived CSS has a separate drift check.
  if (path === "lib/theme/tokens.ts") return [];
  function checkCss(cssSource: string, startingLine = 1) {
    const css = postcss.parse(cssSource, { from: path });
    css.walkDecls((declaration) => {
      const line = startingLine + (declaration.source?.start?.line ?? 1) - 1;
      if (literalColour(declaration.value, true))
        report(line, "literal colour; name a token role");
      if (
        FONT_PROPERTIES.has(declaration.prop) &&
        declaration.value !== "inherit" &&
        !/^var\(--lr-type-roles-[a-z][a-z0-9-]*-(?:font-size|line-height|font-weight)\)$/.test(
          declaration.value,
        )
      )
        report(line, "font value outside a typography role");
    });
  }
  if (path.endsWith(".css")) {
    checkCss(source);
    return [...failures];
  }
  const parsed = ts.createSourceFile(
    path,
    source,
    ts.ScriptTarget.Latest,
    true,
    path.endsWith(".tsx") ? ts.ScriptKind.TSX : ts.ScriptKind.TS,
  );
  const constants = new Map<ts.Node, Map<string, ts.Expression>>();
  function scopeOf(node: ts.Node): ts.Node {
    if (ts.isBlock(node) || ts.isSourceFile(node)) return node;
    return scopeOf(node.parent);
  }
  function collect(node: ts.Node) {
    if (
      ts.isVariableDeclaration(node) &&
      ts.isIdentifier(node.name) &&
      node.initializer
    ) {
      const scope = scopeOf(node);
      const declarations =
        constants.get(scope) ?? new Map<string, ts.Expression>();
      declarations.set(node.name.text, node.initializer);
      constants.set(scope, declarations);
    }
    ts.forEachChild(node, collect);
  }
  collect(parsed);
  function resolve(
    expression: ts.Expression,
    seen = new Set<string>(),
  ): ts.Expression {
    if (
      ts.isParenthesizedExpression(expression) ||
      ts.isAsExpression(expression) ||
      ts.isSatisfiesExpression(expression) ||
      ts.isNonNullExpression(expression) ||
      ts.isTypeAssertionExpression(expression)
    )
      return resolve(expression.expression, seen);
    if (ts.isIdentifier(expression) && !seen.has(expression.text)) {
      for (
        let parent: ts.Node | undefined = expression;
        parent;
        parent = parent.parent
      ) {
        const value = constants.get(parent)?.get(expression.text);
        if (value) return resolve(value, new Set([...seen, expression.text]));
      }
    }
    if (ts.isPropertyAccessExpression(expression)) {
      const object = resolve(expression.expression, seen);
      if (ts.isObjectLiteralExpression(object)) {
        const member = object.properties.find(
          (property) =>
            ts.isPropertyAssignment(property) &&
            (ts.isIdentifier(property.name) ||
              ts.isStringLiteral(property.name)) &&
            property.name.text === expression.name.text,
        );
        if (member && ts.isPropertyAssignment(member))
          return resolve(member.initializer, seen);
      }
    }
    return expression;
  }
  function outcomes(
    expression: ts.Expression,
    ancestors: ReadonlySet<ts.Node>,
  ): ts.Expression[] {
    const value = resolve(expression);
    if (ancestors.has(value)) return [];
    const within = new Set([...ancestors, value]);
    if (ts.isConditionalExpression(value))
      return [value.whenTrue, value.whenFalse].flatMap((branch) =>
        outcomes(branch, within),
      );
    if (
      ts.isBinaryExpression(value) &&
      (value.operatorToken.kind === ts.SyntaxKind.AmpersandAmpersandToken ||
        value.operatorToken.kind === ts.SyntaxKind.BarBarToken ||
        value.operatorToken.kind === ts.SyntaxKind.QuestionQuestionToken)
    )
      return [value.left, value.right].flatMap((side) =>
        outcomes(side, within),
      );
    if (ts.isArrowFunction(value) || ts.isFunctionExpression(value)) {
      if (!ts.isBlock(value.body)) return outcomes(value.body, within);
      const returned: ts.Expression[] = [];
      function returns(node: ts.Node) {
        if (ts.isReturnStatement(node) && node.expression)
          returned.push(...outcomes(node.expression, within));
        else if (!ts.isFunctionLike(node)) ts.forEachChild(node, returns);
      }
      ts.forEachChild(value.body, returns);
      return returned;
    }
    return [value];
  }
  function textsOf(
    expression: ts.Expression,
    ancestors: ReadonlySet<ts.Node> = new Set(),
  ): string[] {
    return outcomes(expression, ancestors).flatMap((value) =>
      valueTexts(value, ancestors),
    );
  }
  function valueTexts(
    value: ts.Expression,
    ancestors: ReadonlySet<ts.Node>,
  ): string[] {
    const within = new Set([...ancestors, value]);
    if (ts.isStringLiteralLike(value)) return [value.text];
    if (ts.isArrayLiteralExpression(value))
      return value.elements.flatMap((element) => textsOf(element, within));
    if (ts.isSpreadElement(value)) return textsOf(value.expression, within);
    if (ts.isObjectLiteralExpression(value)) {
      return value.properties.flatMap((member) =>
        ts.isPropertyAssignment(member)
          ? textsOf(member.initializer, within)
          : ts.isSpreadAssignment(member)
            ? textsOf(member.expression, within)
            : [],
      );
    }
    if (
      ts.isBinaryExpression(value) &&
      value.operatorToken.kind === ts.SyntaxKind.PlusToken
    ) {
      return combine([
        textsOf(value.left, within),
        textsOf(value.right, within),
      ]);
    }
    if (ts.isTemplateExpression(value)) {
      const dynamic = (texts: string[]) =>
        texts.length === 0 ? [DYNAMIC] : texts;
      return combine([
        [value.head.text],
        ...value.templateSpans.flatMap((span) => [
          dynamic(
            outcomes(span.expression, within).flatMap((part) =>
              dynamic(valueTexts(part, within)),
            ),
          ),
          [span.literal.text],
        ]),
      ]);
    }
    return [];
  }
  function propertyNames(name: ts.PropertyName): string[] {
    if (ts.isIdentifier(name) || ts.isStringLiteralLike(name))
      return [name.text];
    return ts.isComputedPropertyName(name) ? textsOf(name.expression) : [];
  }
  function paletteMember(node: ts.Node): boolean {
    for (let parent = node.parent; parent; parent = parent.parent) {
      if (
        ts.isPropertyAssignment(parent) &&
        propertyNames(parent.name).includes("palette")
      )
        return true;
    }
    return false;
  }
  function roleReference(expression: ts.Expression): boolean {
    const values = outcomes(expression, new Set());
    return (
      values.length > 0 &&
      values.every(
        (value) =>
          (ts.isStringLiteralLike(value) && value.text === "inherit") ||
          (ts.isPropertyAccessExpression(value) &&
            /^(?:[\w.]+\.typography\.\w+|TOKENS\.type\.roles\.\w+)\.(?:fontSize|fontWeight|lineHeight)$/.test(
              value.getText(parsed),
            )),
      )
    );
  }
  function checkProperty(
    name: string,
    expression: ts.Expression,
    node: ts.Node,
  ) {
    const line =
      parsed.getLineAndCharacterOfPosition(node.getStart(parsed)).line + 1;
    if (
      (STYLE_PROPERTIES.test(name) || paletteMember(node)) &&
      textsOf(expression).some(
        (text) =>
          !/^[a-z][\w]*(?:\.[a-z][\w]*)+$/i.test(text) &&
          literalColour(text, true),
      )
    ) {
      report(line, "literal colour; name a token role");
    }
    if (FONT_PROPERTIES.has(name) && !roleReference(expression))
      report(line, "font value outside a typography role");
  }
  function visit(node: ts.Node) {
    const line =
      parsed.getLineAndCharacterOfPosition(node.getStart(parsed)).line + 1;
    if (ts.isStringLiteralLike(node) && literalColour(node.text, false))
      report(line, "literal colour; name a token role");
    if (ts.isPropertyAssignment(node)) {
      for (const name of propertyNames(node.name))
        checkProperty(name, node.initializer, node);
    }
    if (
      ts.isJsxAttribute(node) &&
      ts.isIdentifier(node.name) &&
      node.initializer
    ) {
      if (ts.isStringLiteral(node.initializer))
        checkProperty(node.name.text, node.initializer, node);
      else if (
        ts.isJsxExpression(node.initializer) &&
        node.initializer.expression
      )
        checkProperty(node.name.text, node.initializer.expression, node);
    }
    if (
      ts.isTaggedTemplateExpression(node) &&
      /(?:\bstyled\b|\bcss\b)/.test(node.tag.getText(parsed))
    ) {
      for (const css of textsOf(node.template)) checkCss(css, line);
    }
    ts.forEachChild(node, visit);
  }
  visit(parsed);
  return [...failures];
}

export function checkStylePolicy(
  files: Readonly<Record<string, string>>,
): void {
  const failures = Object.entries(files).flatMap(([path, source]) =>
    styleViolations(path, source),
  );
  if (failures.length > 0) throw new Error(failures.join("\n"));
}
