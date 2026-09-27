import * as matchers from "@testing-library/jest-dom/matchers";
import type { TestingLibraryMatchers } from "@testing-library/jest-dom/matchers";
import { cleanup } from "@testing-library/react";
import { afterEach, expect } from "vitest";

// The library's own `/vitest` entry augments `Assertion`, whose type parameters
// this version of Vitest declares differently — a hard TS2428. Its matchers go
// on the documented extension point instead, which augments only from inside a
// module: in a file with no top-level import or export, the block below would
// declare a new module of that name and silently do nothing.
declare module "vitest" {
  interface Matchers<
    R extends void | Promise<void> = void | Promise<void>,
    T = unknown,
  > extends TestingLibraryMatchers<T, R> {}
}

expect.extend(matchers);

// React Testing Library wires its own cleanup, and the flag that makes React
// complain about updates outside act(), only when the runner puts `afterEach`
// and `beforeAll` in global scope. Vitest does neither unless `globals` is on,
// so both are by hand rather than absent and unnoticed.
(
  globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }
).IS_REACT_ACT_ENVIRONMENT = true;

afterEach(cleanup);

// A focus's related target is an element or null (whatwg/html source:87016-87020); jsdom stands
// the Document in for a removed focused element (Node-impl.js:494) and reports it as one.
const relatedTarget = Object.getOwnPropertyDescriptor(
  FocusEvent.prototype,
  "relatedTarget",
)!;
Object.defineProperty(FocusEvent.prototype, "relatedTarget", {
  ...relatedTarget,
  get(this: FocusEvent) {
    const target: unknown = relatedTarget.get!.call(this);
    return target instanceof Element ? target : null;
  },
});
