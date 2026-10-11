import { act, renderHook } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { useProseEdit } from "./useProseEdit";

describe("prose editing ownership", () => {
  it("keeps typing across blur-like redraws and drops it only on Cancel", () => {
    const section = { current: null };
    const { result, rerender } = renderHook(
      ({ said, running }) =>
        useProseEdit(said, undefined, true, running, section),
      { initialProps: { said: "Read carefully.", running: false } },
    );
    expect(result.current.editing).toBe(false);
    act(() => result.current.begin());
    act(() => result.current.change("Read carefully. Keep the names."));
    rerender({ said: "Read carefully.", running: false });
    expect(result.current.editing).toBe(true);
    expect(result.current.typed).toBe("Read carefully. Keep the names.");
    act(() => result.current.cancel());
    expect(result.current.editing).toBe(false);
    expect(result.current.typed).toBe("Read carefully.");
  });

  it("does not cancel or replace the text while its request is running", () => {
    const section = { current: null };
    const { result, rerender } = renderHook(
      ({ running }) =>
        useProseEdit(undefined, undefined, true, running, section),
      { initialProps: { running: false } },
    );
    act(() => result.current.begin());
    act(() => result.current.change("Keep the label."));
    rerender({ running: true });
    act(() => {
      result.current.change("Discard it.");
      result.current.cancel();
    });
    expect(result.current.editing).toBe(true);
    expect(result.current.typed).toBe("Keep the label.");
    act(() => result.current.saved());
    expect(result.current.editing).toBe(false);
  });

  it("rewrites dirty references and resets to a genuinely different read", () => {
    const section = { current: null };
    const { result, rerender } = renderHook(
      ({
        said,
        rewritten,
      }: {
        said: string;
        rewritten?: (text: string) => string;
      }) => useProseEdit(said, rewritten, true, false, section),
      {
        initialProps: { said: "Read {{a}}." } as {
          said: string;
          rewritten?: (text: string) => string;
        },
      },
    );
    act(() => result.current.begin());
    act(() => result.current.change("Read {{a}}. Preserve this."));
    rerender({
      said: "Read {{b}}.",
      rewritten: (text) => text.replace("{{a}}", "{{b}}"),
    });
    expect(result.current.typed).toBe("Read {{b}}. Preserve this.");
    rerender({ said: "Use the revision now read." });
    expect(result.current.typed).toBe("Use the revision now read.");
    expect(result.current.editing).toBe(true);
  });

  it("does not offer an editor after writing permission is lost", () => {
    const section = { current: null };
    const { result } = renderHook(() =>
      useProseEdit("Keep this.", undefined, false, false, section),
    );
    act(() => result.current.begin());
    expect(result.current.editing).toBe(false);
  });
  it("keeps the current editor when an earlier save finishes after another read", () => {
    const section = { current: null };
    const { result, rerender } = renderHook(
      ({ said }) => useProseEdit(said, undefined, true, false, section),
      {
        initialProps: { said: "Earlier read." },
      },
    );
    act(() => result.current.begin());
    act(() => result.current.change("Earlier typed."));
    const earlierSaved = result.current.saved;
    rerender({ said: "Current read." });
    act(() => result.current.change("Current typed."));
    act(earlierSaved);
    expect(result.current.editing).toBe(true);
    expect(result.current.typed).toBe("Current typed.");
  });
});
