import { expect } from "vitest";

type ConsoleError = typeof console.error;
type ErrorCalls = unknown[][];

type ErrorGuard = {
  calls: ErrorCalls;
  original: ConsoleError;
  forwarding: ConsoleError;
  reporting: boolean;
};

const GUARD = Symbol.for("lilradish.test.consoleErrors");
type GuardGetter = (() => ConsoleError) & { [GUARD]?: ErrorGuard };

const previous = Object.getOwnPropertyDescriptor(console, "error");
const getter = previous?.get as GuardGetter | undefined;
const guard: ErrorGuard = getter?.[GUARD] ?? {
  calls: [],
  original: console.error.bind(console),
  forwarding: console.error.bind(console),
  reporting: false,
};

if (getter?.[GUARD] === undefined) {
  const report: ConsoleError = (...arguments_) => {
    guard.calls.push(arguments_);
    // Delegate through the original target to avoid recursive replacement calls.
    if (guard.reporting) {
      guard.original(...arguments_);
      return;
    }
    guard.reporting = true;
    try {
      guard.forwarding(...arguments_);
    } finally {
      guard.reporting = false;
    }
  };
  const read: GuardGetter = () => report;
  read[GUARD] = guard;
  // Mock call histories and property replacement must not erase this ledger.
  Object.defineProperty(console, "error", {
    configurable: false,
    enumerable: previous?.enumerable ?? true,
    get: read,
    set(replacement: ConsoleError) {
      if (typeof replacement !== "function") {
        throw new TypeError("console.error must remain a function");
      }
      guard.forwarding = replacement === report ? guard.original : replacement;
    },
  });
}

export function expectConsoleErrors(...expected: ErrorCalls): void {
  expect(guard.calls, "unasserted console.error calls").toEqual(expected);
  guard.calls = [];
}

export async function guardConsoleErrors(
  runTest: () => Promise<void>,
): Promise<void> {
  guard.forwarding = guard.original;
  try {
    await runTest();
  } finally {
    try {
      expectConsoleErrors();
    } finally {
      guard.calls = [];
      guard.forwarding = guard.original;
    }
  }
}
