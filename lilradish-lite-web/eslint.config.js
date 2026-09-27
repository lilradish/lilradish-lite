import js from "@eslint/js";
import { defineConfig, globalIgnores } from "eslint/config";
import reactHooks from "eslint-plugin-react-hooks";
import tseslint from "typescript-eslint";

export default defineConfig([
  // node_modules is already a default ignore. These are not, and ESLint lints
  // **/*.js by default, so without them it walks Gradle's caches and the Node
  // toolchain node-gradle downloads into the project — both listed in
  // .prettierignore for the same reason.
  globalIgnores(["build/**", ".gradle/**", ".node/**"]),
  {
    files: ["src/main/frontend/**/*.{ts,tsx}", "*.{js,ts}"],
    extends: [
      js.configs.recommended,
      tseslint.configs.recommended,
      // Here for the wrong dependency array, a silent bug. This set only warns
      // on it, so `--max-warnings 0` in the `lint` script is what fails a build.
      reactHooks.configs.flat.recommended,
    ],
    rules: {
      // Type-aware rules are deliberately not enabled. They need a project
      // service covering every linted file, including this config, and the rules
      // that pay for the frontend's size are the hook ones above.
      "@typescript-eslint/no-unused-vars": [
        "error",
        { argsIgnorePattern: "^_", varsIgnorePattern: "^_" },
      ],
      // Declaration merging is exactly what an empty interface with a single
      // supertype is for, and it is how a test runner's matchers are extended.
      "@typescript-eslint/no-empty-object-type": [
        "error",
        { allowInterfaces: "with-single-extends" },
      ],
    },
  },
  {
    // The served bundle's source half of keeping build-time values out of it; the
    // other half, index.html, `define` and dependencies, is `inliningNothing` in vite.config.ts.
    files: ["src/main/frontend/**"],
    ignores: [
      "src/main/frontend/**/*.test.{ts,tsx}",
      "src/main/frontend/testSetup.ts",
      "src/main/frontend/testutil/**",
    ],
    rules: {
      // `import.meta` may only be read by a plain name that is not `env`:
      // bracketed, destructured or held in a variable, it reaches `env` unseen.
      "no-restricted-syntax": [
        "error",
        {
          selector:
            'MemberExpression[object.type="MetaProperty"][object.meta.name="import"][property.name="env"]',
          message:
            "import.meta.env is inlined into the bundle the archive serves unauthenticated. Ask the server for the value at runtime instead.",
        },
        {
          selector:
            'MemberExpression[computed=true][object.type="MetaProperty"][object.meta.name="import"]',
          message:
            "import.meta read by a computed name can reach import.meta.env, which is inlined into the bundle the archive serves unauthenticated. Name the member.",
        },
        {
          selector: ':not(MemberExpression) > MetaProperty[meta.name="import"]',
          message:
            "import.meta taken whole can be read for import.meta.env, which is inlined into the bundle the archive serves unauthenticated. Name the member where it is used.",
        },
        {
          // References to the global only: a key or member named `process` is a word of this domain,
          // but `globalThis.process`, `["process"]` and a key destructured out are the global.
          selector: [
            'Identifier[name="process"]:not(MemberExpression[computed=false] > .property, ObjectExpression > Property[computed=false][shorthand=false] > .key, PropertyDefinition[computed=false] > .key, MethodDefinition[computed=false] > .key, TSPropertySignature[computed=false] > .key, TSMethodSignature[computed=false] > .key)',
            'MemberExpression[computed=false][object.name=/^(globalThis|window|self|global)$/] > Identifier.property[name="process"]',
            'MemberExpression[computed=true] > Literal.property[value="process"]',
            'MemberExpression[computed=true] > TemplateLiteral.property > TemplateElement[value.cooked="process"]',
            'ObjectPattern > Property[computed=true] > Literal.key[value="process"]',
            'ObjectPattern > Property[computed=true] > TemplateLiteral.key > TemplateElement[value.cooked="process"]',
          ].join(", "),
          message:
            "No browser has a `process`: the build rewrites process.env to {} and its NODE_ENV to the mode, and any other read throws. Ask the server for the value at runtime instead.",
        },
      ],
    },
  },
]);
