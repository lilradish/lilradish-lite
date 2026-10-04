import { act, renderHook } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";

import { useSidebarExpanded } from "./useSidebarExpanded";

const KEPT_AS = "lilradish.navigation.sidebar";

afterEach(() => {
  localStorage.clear();
});

describe("useSidebarExpanded", () => {
  it.each([
    ["nothing kept", null, true],
    ["expanded kept", "expanded", true],
    ["collapsed kept", "collapsed", false],
    ["a word this build does not know", "sideways", true],
  ])("opens as this device kept it: %s", (_case, kept, expanded) => {
    if (kept !== null) {
      localStorage.setItem(KEPT_AS, kept);
    }

    const { result } = renderHook(() => useSidebarExpanded());

    expect(result.current[0]).toBe(expanded);
  });

  it.each([
    [false, "collapsed"],
    [true, "expanded"],
  ])(
    "shows the choice at once, and keeps it for the next time this device opens a page: %s",
    (chosen, keptAs) => {
      localStorage.setItem(KEPT_AS, chosen ? "collapsed" : "expanded");
      const { result } = renderHook(() => useSidebarExpanded());

      act(() => result.current[1](chosen));

      expect(result.current[0]).toBe(chosen);
      expect(localStorage.getItem(KEPT_AS)).toBe(keptAs);
      expect(renderHook(() => useSidebarExpanded()).result.current[0]).toBe(
        chosen,
      );
    },
  );

  it("opens expanded, and still shows a choice, where the device refuses to keep anything", () => {
    vi.spyOn(Storage.prototype, "getItem").mockImplementation(() => {
      throw new DOMException("refused", "SecurityError");
    });
    vi.spyOn(Storage.prototype, "setItem").mockImplementation(() => {
      throw new DOMException("refused", "QuotaExceededError");
    });

    const { result } = renderHook(() => useSidebarExpanded());
    const opened = result.current[0];
    act(() => result.current[1](false));

    expect(opened).toBe(true);
    expect(result.current[0]).toBe(false);
  });
});
