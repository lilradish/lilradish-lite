import { act, renderHook } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";

import { useKeptChoice } from "./useKeptChoice";

const KEPT_AS = "lilradish.test.shape";

const SHAPES = ["round", "square", "flat"] as const;

const ANOTHER_KEPT_AS = "lilradish.test.colour";

afterEach(() => {
  vi.restoreAllMocks();
  localStorage.clear();
});

describe("useKeptChoice", () => {
  it.each([
    ["nothing kept", null, "round"],
    ["the first kept", "round", "round"],
    ["another kept", "square", "square"],
    ["the last kept", "flat", "flat"],
    ["a word this build does not know", "sideways", "round"],
    ["a choice spelt in another case", "Square", "round"],
    ["an empty word", "", "round"],
  ])("opens as this device kept it: %s", (_case, kept, chosen) => {
    if (kept !== null) {
      localStorage.setItem(KEPT_AS, kept);
    }

    const { result } = renderHook(() => useKeptChoice(KEPT_AS, SHAPES));

    expect(result.current[0]).toBe(chosen);
  });

  it("shows a choice at once, and keeps it under its own name alone for the next time this device opens a page", () => {
    localStorage.setItem(ANOTHER_KEPT_AS, "green");
    const { result } = renderHook(() => useKeptChoice(KEPT_AS, SHAPES));

    act(() => result.current[1]("flat"));

    expect(result.current[0]).toBe("flat");
    expect(localStorage.getItem(KEPT_AS)).toBe("flat");
    expect(localStorage.getItem(ANOTHER_KEPT_AS)).toBe("green");
    expect(
      renderHook(() => useKeptChoice(KEPT_AS, SHAPES)).result.current[0],
    ).toBe("flat");
  });

  it("gives the first, and still shows a choice, where the device refuses to keep anything", () => {
    vi.spyOn(Storage.prototype, "getItem").mockImplementation(() => {
      throw new DOMException("refused", "SecurityError");
    });
    vi.spyOn(Storage.prototype, "setItem").mockImplementation(() => {
      throw new DOMException("refused", "QuotaExceededError");
    });

    const { result } = renderHook(() => useKeptChoice(KEPT_AS, SHAPES));
    const opened = result.current[0];
    act(() => result.current[1]("square"));

    expect(opened).toBe("round");
    expect(result.current[0]).toBe("square");
  });
});
