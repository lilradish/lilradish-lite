import react from "@vitejs/plugin-react";
import { readFileSync, readdirSync } from "node:fs";
import { join, relative } from "node:path";
import { fileURLToPath } from "node:url";
import type { Plugin } from "vite";
import { defineConfig } from "vitest/config";

import { checkStylePolicy } from "./src/main/frontend/lib/theme/stylePolicy.ts";
import { checkTokenCss } from "./src/main/frontend/lib/theme/tokenCss.ts";

/** The keys Vite puts in the resolved env itself; any other came from a `.env` file or a `VITE_*` variable. */
const VITE_OWN_ENV = new Set(["BASE_URL", "MODE", "DEV", "PROD"]);

// Vite's own switches are copied into the resolved env by loadEnv but configure Vite, not client code.
const VITE_READ_ENV = new Set([
  "VITE_DEBUG_FILTER",
  "VITE_DEPRECATION_TRACE",
  "VITE_CONFIG_NATIVE_IGNORE_WARNING",
  "VITE_USER_NODE_ENV",
]);

/**
 * Fails a build that holds a value to inline: `%VITE_*%` in the document,
 * `define`, and a dependency reading `import.meta.env` all get past the lint.
 */
function inliningNothing(): Plugin {
  return {
    name: "inlining-nothing",
    apply: "build",
    configResolved(config) {
      const held = [
        ...Object.keys(config.env).filter(
          (key) => !(VITE_OWN_ENV.has(key) || VITE_READ_ENV.has(key)),
        ),
        ...Object.keys(config.define ?? {}),
      ];
      if (held.length > 0) {
        throw new Error(
          `the build would inline ${held.join(", ")} into what it emits; ask the server for it at runtime instead`,
        );
      }
    },
  };
}

function tokenStyles(): Plugin {
  let frontend = "";
  return {
    name: "token-styles",
    apply: "build",
    configResolved(config) {
      frontend = config.root;
    },
    buildStart() {
      const files: Record<string, string> = {};
      function read(directory: string) {
        for (const entry of readdirSync(directory, { withFileTypes: true })) {
          const path = join(directory, entry.name);
          if (entry.isDirectory()) {
            read(path);
            continue;
          }
          if (!entry.isFile() || !/\.(?:ts|tsx|css)$/.test(entry.name))
            continue;
          const name = relative(frontend, path).split("\\").join("/");
          if (
            /\.test\.tsx?$|^testutil\/|^testSetup\.ts$/.test(name) ||
            name === "lib/theme/tokens.generated.css"
          )
            continue;
          files[name] = readFileSync(path, "utf8");
        }
      }
      read(frontend);
      checkTokenCss(
        files["lib/theme/tokens.ts"],
        readFileSync(join(frontend, "lib/theme/tokens.generated.css"), "utf8"),
      );
      checkStylePolicy(files);
    },
  };
}

export default defineConfig({
  // Vitest resolves both `include` and `coverage.include` against this, so it
  // has to be the root of the tree it is meant to cover.
  root: "src/main/frontend",
  // Defaults to <root>/node_modules/.vite, which would put build cache inside the
  // source tree. It must also stay out of build/frontend: Gradle registers that
  // whole directory as a resource source, so anything under it reaches the jar.
  cacheDir: "../../../build/frontend-cache",
  plugins: [react(), inliningNothing(), tokenStyles()],
  worker: {
    plugins: () => [
      {
        name: "worker-character-references",
        enforce: "pre",
        resolveId(source) {
          // This package's public default and worker exports use its character table, rather than a DOM element.
          return source === "decode-named-character-reference"
            ? fileURLToPath(import.meta.resolve(source))
            : undefined;
        },
      },
    ],
    rolldownOptions: {
      output: {
        hashCharacters: "base64",
        comments: { legal: true, annotation: false, jsdoc: false },
      },
    },
  },
  build: {
    // `static` is not decoration: the whole directory above it is a Gradle
    // resource source, so this is the path inside the archive, and Spring Boot
    // serves classpath resources from exactly that name.
    outDir: "../../../build/frontend/static",
    // Defaulted on only for an outDir inside the root; this one is outside, and
    // without it a renamed chunk accumulates beside the one that replaced it.
    emptyOutDir: true,
    rolldownOptions: {
      output: {
        // Named rather than defaulted: the specs reading the archive hold every
        // emitted name to this alphabet, and a hash may be letters alone.
        hashCharacters: "base64",
        // Third-party licences keep their notices, which minifying strips. Whole:
        // this object replaces Vite's, and rolldown reads a missing field as true.
        comments: { legal: true, annotation: false, jsdoc: false },
      },
    },
  },
  test: {
    // A fixed zone, and one that observes DST: the relative-day boundary
    // behaves differently on the two days a year the clocks change, and that
    // is not testable in a zone where they never do.
    env: { TZ: "Europe/Berlin" },
    // The components under test render into a DOM. Set for the whole suite
    // rather than per file: a pure-function spec does not care which
    // environment it runs in, and one setting is one thing to be wrong about.
    environment: "jsdom",
    setupFiles: ["./testSetup.ts"],
    // Both run before each test rather than after, so a spec that fails an
    // assertion mid-test still hands the next one a clean global. Hand-rolled
    // afterEach teardown is what does not survive that.
    //
    // Neither survives `test.concurrent`: one test finishing resets the spies
    // and globals of every test still running.
    restoreMocks: true,
    unstubGlobals: true,
    // The json reporter writes under <root>/.vitest when not told where, which
    // is the source tree; out of build/frontend for the reason cacheDir is.
    outputFile: { json: "../../../build/frontend-test-results/output.json" },
    coverage: {
      reporter: ["text", "html"],
      // Defaults under <root>, which would write the report into the source tree,
      // and it must stay out of build/frontend for the reason cacheDir does.
      reportsDirectory: "../../../build/frontend-coverage",
      // Without this only the files a test imported are counted, which reports a
      // covered fraction of the covered files and hides every untested module.
      include: ["**/*.ts", "**/*.tsx"],
      exclude: ["**/*.test.ts", "**/*.test.tsx", "testSetup.ts", "testutil/**"],
    },
  },
});
