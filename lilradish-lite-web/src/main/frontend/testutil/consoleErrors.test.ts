import { describe, expect, it, vi } from "vitest";

import { expectConsoleErrors } from "./consoleErrors";

describe("expected console errors", () => {
  it("keeps a mismatched call pending until its complete arguments are asserted", () => {
    const failure = new Error("The receipt could not be read.");
    console.error("Reading failed", failure);

    expect(() => expectConsoleErrors(["Reading failed"])).toThrow(
      "unasserted console.error calls",
    );
    expectConsoleErrors(["Reading failed", failure]);
    expectConsoleErrors();
  });

  it("keeps errors independently of a replacement's cleared and restored history", () => {
    const forwarding = vi.fn();
    const original = console.error;
    console.error = forwarding;

    console.error("First failure");
    forwarding.mockClear();
    forwarding.mockRestore();
    console.error = original;
    console.error("Second failure");

    expect(forwarding).not.toHaveBeenCalled();
    expectConsoleErrors(["First failure"], ["Second failure"]);
  });

  it("records nested errors without recursively forwarding through the replacement", () => {
    const original = console.error;
    const forwarding = vi.fn((message: string) => {
      original(message);
      console.error("Nested failure");
    });
    console.error = forwarding;

    console.error("Read failed");

    expect(forwarding.mock.calls).toEqual([["Read failed"]]);
    expectConsoleErrors(["Read failed"], ["Read failed"], ["Nested failure"]);
  });

  it("shares the pending ledger when the helper module is loaded again", async () => {
    console.error("Still pending");
    vi.resetModules();

    const reloaded = await import("./consoleErrors");

    reloaded.expectConsoleErrors(["Still pending"]);
    expectConsoleErrors();
  });

  it("refuses a spy that would replace the collector's property", () => {
    expect(() => vi.spyOn(console, "error")).toThrow(TypeError);
    expectConsoleErrors();
  });
});
