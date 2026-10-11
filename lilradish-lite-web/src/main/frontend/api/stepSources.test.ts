import { describe, expect, it } from "vitest";

import { inputFrom } from "./stepSources";

describe("a composite input's constant source", () => {
  it("keeps the declaring run when a constant was carried from above, without making it a step's output", () => {
    const from = { kind: "constant", runId: "above", runNumber: 3 };
    const read = inputFrom({
      input: "account",
      sources: [{ targetPath: "summary", from }],
    });
    expect(read).toEqual({
      input: "account",
      sources: [{ targetPath: "summary", from }],
    });
    expect(
      inputFrom({
        input: "account",
        sources: [{ targetPath: "summary", from: { kind: "constant" } }],
      }),
    ).toEqual({
      input: "account",
      sources: [{ targetPath: "summary", from: { kind: "constant" } }],
    });
    for (const invalid of [
      { kind: "constant", runId: "above" },
      { kind: "constant", runNumber: 3 },
      { ...from, stepId: "step" },
    ]) {
      expect(
        inputFrom({
          input: "account",
          sources: [{ targetPath: "summary", from: invalid }],
        }),
      ).toBeNull();
    }
  });
});
