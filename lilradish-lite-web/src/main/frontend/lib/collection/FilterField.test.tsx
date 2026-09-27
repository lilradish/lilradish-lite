import { ThemeProvider } from "@mui/material/styles";
import { act, fireEvent, render, screen } from "@testing-library/react";
import type { ReactNode } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { theme } from "../theme/theme";
import { FilterField } from "./FilterField";

/** How long typing has to pause before what was typed is handed on. */
const QUIET_MS = 300;

function themed({ children }: { readonly children: ReactNode }) {
  return <ThemeProvider theme={theme}>{children}</ThemeProvider>;
}

/**
 * A fresh element on every render: React skips re-rendering a subtree handed
 * the very same element back, and a rerender that does nothing proves nothing.
 */
/** What the caller says can be typed, which this box has no words of its own for. */
const LABEL = "Name or number";

function field(value: string, onChange = vi.fn()) {
  const view = render(
    <FilterField label={LABEL} value={value} onChange={onChange} />,
    { wrapper: themed },
  );
  return {
    onChange,
    box: screen.getByRole<HTMLInputElement>("searchbox"),
    rerender(next: string, handler = onChange) {
      view.rerender(
        <FilterField label={LABEL} value={next} onChange={handler} />,
      );
    },
  };
}

function type(box: HTMLInputElement, text: string) {
  fireEvent.change(box, { target: { value: text } });
}

function wait(ms: number) {
  act(() => {
    vi.advanceTimersByTime(ms);
  });
}

/**
 * Only the two timer functions are faked, and nothing here waits on the
 * testing library: its waiting looks for a faked clock only where a `jest`
 * global exists, and this suite has none, so it would wait on real time
 * that never passes.
 */
beforeEach(() => {
  vi.useFakeTimers({ toFake: ["setTimeout", "clearTimeout"] });
});

afterEach(() => {
  vi.useRealTimers();
});

describe("FilterField", () => {
  /** Back, forward, or a link: the address changed, and the box says what it now holds. */
  it("takes a value arriving from outside into the box without handing it back", () => {
    const { box, onChange, rerender } = field("ada");

    rerender("bob");
    wait(QUIET_MS * 3);

    expect(box).toHaveValue("bob");
    expect(onChange).not.toHaveBeenCalled();
  });

  /**
   * What this box handed on comes back as its value a moment later, by which
   * time the reader may have typed on. That is not news, and taking it as news
   * would throw away what was typed since.
   */
  it("keeps what was typed since, when the value it handed on comes back to it", () => {
    const { box, onChange, rerender } = field("");
    type(box, "ad");
    wait(QUIET_MS);
    type(box, "ada");

    rerender("ad");
    wait(QUIET_MS);

    expect(box).toHaveValue("ada");
    expect(onChange.mock.calls).toEqual([["ad"], ["ada"]]);
  });

  /** Only the first return is this box's own; back and then forward again is the address. */
  it("takes a value from outside that matches one it handed on before it came back", () => {
    const { box, onChange, rerender } = field("");
    type(box, "ad");
    wait(QUIET_MS);
    rerender("ad");
    rerender("");

    rerender("ad");

    expect(box).toHaveValue("ad");
    expect(onChange.mock.calls).toEqual([["ad"]]);
  });

  it("hands on to the handler it holds when the pause ends, not the one it held when typing began", () => {
    const { box, onChange, rerender } = field("");
    const later = vi.fn();
    type(box, "ad");

    rerender("", later);
    wait(QUIET_MS);

    expect(later.mock.calls).toEqual([["ad"]]);
    expect(onChange).not.toHaveBeenCalled();
  });

  /** Typed, then the address changed under it before the pause ended. */
  it("hands on nothing typed before a value arrived from outside", () => {
    const { box, onChange, rerender } = field("");
    type(box, "x");

    rerender("bob");
    wait(QUIET_MS * 3);

    expect(box).toHaveValue("bob");
    expect(onChange).not.toHaveBeenCalled();
  });

  it("is a search box named by the caller's label, which stays in view, across the whole width", () => {
    const { box } = field("");

    expect(box).toHaveAccessibleName(LABEL);
    expect(box.getAttribute("type")).toBe("search");
    expect(box.labels?.[0]?.textContent).toBe(LABEL);
    expect(screen.queryByText("Filter")).toBeNull();
    expect(box.getAttribute("placeholder")).toBeNull();
    expect(getComputedStyle(box.closest(".MuiFormControl-root")!).width).toBe(
      "100%",
    );
  });
});
