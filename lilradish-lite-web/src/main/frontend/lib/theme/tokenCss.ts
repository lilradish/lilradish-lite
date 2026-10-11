import ts from "typescript";
import { typeRoleValueInRem } from "./typeRoles.ts";

/** Build tooling reads the static token AST; the application never imports this module. */
export function generateTokenCss(source: string): string {
  const parsed = ts.createSourceFile(
    "tokens.ts",
    source,
    ts.ScriptTarget.Latest,
    true,
    ts.ScriptKind.TS,
  );
  const declaration = parsed.statements
    .filter(ts.isVariableStatement)
    .flatMap((statement) => [...statement.declarationList.declarations])
    .find((each) => ts.isIdentifier(each.name) && each.name.text === "TOKENS");
  const initializer = declaration?.initializer;
  if (
    !initializer ||
    !ts.isAsExpression(initializer) ||
    initializer.type.getText(parsed) !== "const" ||
    !ts.isObjectLiteralExpression(initializer.expression)
  ) {
    throw new Error("TOKENS must be a static object declared as const");
  }
  const declarations: string[] = [];
  const names = new Set<string>();
  function visit(object: ts.ObjectLiteralExpression, path: string[]) {
    for (const member of object.properties) {
      if (
        !ts.isPropertyAssignment(member) ||
        !(ts.isIdentifier(member.name) || ts.isStringLiteral(member.name))
      ) {
        throw new Error("Tokens may contain only named property assignments");
      }
      const parts = [...path, member.name.text];
      const value = member.initializer;
      if (ts.isObjectLiteralExpression(value)) {
        visit(value, parts);
      } else if (ts.isStringLiteral(value) || ts.isNumericLiteral(value)) {
        const name = `--lr-${parts
          .map((part) =>
            part.replace(/[A-Z]/g, (letter) => `-${letter.toLowerCase()}`),
          )
          .join("-")}`;
        if (names.has(name) || /[{};\r\n]/.test(value.text)) {
          throw new Error(`Invalid or duplicate CSS token: ${name}`);
        }
        names.add(name);
        const text =
          parts.length === 4 && parts[0] === "type" && parts[1] === "roles"
            ? typeRoleValueInRem(parts[3], value.text)
            : value.text;
        declarations.push(`  ${name}: ${text};`);
      } else {
        throw new Error(`Token ${parts.join(".")} must be a string or number`);
      }
    }
  }
  visit(initializer.expression, []);
  if (declarations.length === 0) throw new Error("TOKENS must not be empty");
  return `/* Generated from tokens.ts. Checked byte for byte at build time. */\n/* prettier-ignore */\n:root {\n${declarations.join("\n")}\n}\n`;
}

export function checkTokenCss(source: string, css: string): void {
  const expected = generateTokenCss(source);
  if (expected !== css) {
    let offset = 0;
    while (offset < expected.length && expected[offset] === css[offset])
      offset += 1;
    throw new Error(
      `tokens.generated.css differs from the static token source at character ${offset}: expected ${JSON.stringify(expected.slice(offset, offset + 40))}, received ${JSON.stringify(css.slice(offset, offset + 40))}`,
    );
  }
}
