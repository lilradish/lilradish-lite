import { act, renderHook } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";

import { useShownAfter } from "./useShownAfter";

afterEach(() => vi.useRealTimers());

describe("useShownAfter", () => {
  it("shows only after the full delay and hides as soon as the wait ends", async () => {
    vi.useFakeTimers();
    const { result, rerender } = renderHook(
      ({ active }) => useShownAfter(active),
      { initialProps: { active: true } },
    );
    await act(() => vi.advanceTimersByTimeAsync(299));
    expect(result.current).toBe(false);
    await act(() => vi.advanceTimersByTimeAsync(1));
    expect(result.current).toBe(true);
    rerender({ active: false });
    expect(result.current).toBe(false);
    expect(vi.getTimerCount()).toBe(0);
  });

  it("cancels a short wait and starts a fresh delay for the next wait", async () => {
    vi.useFakeTimers();
    const { result, rerender } = renderHook(
      ({ active }) => useShownAfter(active),
      { initialProps: { active: true } },
    );
    await act(() => vi.advanceTimersByTimeAsync(250));
    rerender({ active: false });
    rerender({ active: true });
    await act(() => vi.advanceTimersByTimeAsync(299));
    expect(result.current).toBe(false);
    await act(() => vi.advanceTimersByTimeAsync(1));
    expect(result.current).toBe(true);
  });

  it("hides an old result and cancels its timer when the active cycle changes", async () => {
    vi.useFakeTimers();
    const { result, rerender, unmount } = renderHook(
      ({ cycle }) => useShownAfter(true, 300, cycle),
      { initialProps: { cycle: "first" } },
    );
    await act(() => vi.advanceTimersByTimeAsync(300));
    expect(result.current).toBe(true);
    rerender({ cycle: "second" });
    expect(result.current).toBe(false);
    await act(() => vi.advanceTimersByTimeAsync(200));
    rerender({ cycle: "third" });
    await act(() => vi.advanceTimersByTimeAsync(299));
    expect(result.current).toBe(false);
    await act(() => vi.advanceTimersByTimeAsync(1));
    expect(result.current).toBe(true);
    unmount();
    expect(vi.getTimerCount()).toBe(0);
  });

  it("restarts a changed delay and removes an outstanding timer on unmount", async () => {
    vi.useFakeTimers();
    const { result, rerender, unmount } = renderHook(
      ({ delay }) => useShownAfter(true, delay),
      { initialProps: { delay: 300 } },
    );
    await act(() => vi.advanceTimersByTimeAsync(250));
    rerender({ delay: 500 });
    await act(() => vi.advanceTimersByTimeAsync(499));
    expect(result.current).toBe(false);
    unmount();
    expect(vi.getTimerCount()).toBe(0);
  });
});
