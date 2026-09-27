import { act, renderHook } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";

import { useWorkDrawing } from "./useWorkDrawing";

const KEPT_AS = "lilradish.work.drawing";

afterEach(() => {
  vi.restoreAllMocks();
  localStorage.clear();
});

describe("useWorkDrawing", () => {
  it.each([
    ["nothing kept", null, "conversation"],
    ["a conversation kept", "conversation", "conversation"],
    ["detail kept", "detail", "detail"],
    ["a word this build does not know", "sideways", "conversation"],
  ])("opens as this device kept it: %s", (_case, kept, drawn) => {
    if (kept !== null) {
      localStorage.setItem(KEPT_AS, kept);
    }

    const { result } = renderHook(() => useWorkDrawing());

    expect(result.current[0]).toBe(drawn);
  });

  it("draws the way chosen at once, and keeps it for the next time this device opens the page", () => {
    const { result } = renderHook(() => useWorkDrawing());

    act(() => result.current[1]("detail"));

    expect(result.current[0]).toBe("detail");
    expect(localStorage.getItem(KEPT_AS)).toBe("detail");
    expect(renderHook(() => useWorkDrawing()).result.current[0]).toBe("detail");
  });

  it("opens as a conversation, and still draws a choice, where the device refuses to keep anything", () => {
    vi.spyOn(Storage.prototype, "getItem").mockImplementation(() => {
      throw new DOMException("refused", "SecurityError");
    });
    vi.spyOn(Storage.prototype, "setItem").mockImplementation(() => {
      throw new DOMException("refused", "QuotaExceededError");
    });

    const { result } = renderHook(() => useWorkDrawing());
    const opened = result.current[0];
    act(() => result.current[1]("detail"));

    expect(opened).toBe("conversation");
    expect(result.current[0]).toBe("detail");
  });
});
