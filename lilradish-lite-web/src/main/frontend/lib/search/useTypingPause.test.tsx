import TextField from "@mui/material/TextField";
import { ThemeProvider } from "@mui/material/styles";
import { act, fireEvent, render, screen } from "@testing-library/react";
import type { ReactNode } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { theme } from "../theme/theme";
import { useTypingPause } from "./useTypingPause";

/** How long typing has to pause before what was typed is handed on. */
const QUIET_MS = 300;

const FROM_OUTSIDE = "bob";

function themed({ children }: { readonly children: ReactNode }) {
  return <ThemeProvider theme={theme}>{children}</ThemeProvider>;
}

/** A box as a caller holds one, with a control that sets its text from outside. */
function Host({ onPause }: { readonly onPause: (typed: string) => void }) {
  const typing = useTypingPause("", onPause);
  return (
    <>
      <TextField type="search" label="Words" {...typing.field} />
      <button type="button" onClick={() => typing.setText(FROM_OUTSIDE)}>
        Replace
      </button>
    </>
  );
}

/**
 * A fresh element on every render: React skips re-rendering a subtree handed
 * the very same element back, and a rerender that does nothing proves nothing.
 */
function hosted(onPause = vi.fn()) {
  const view = render(<Host onPause={onPause} />, { wrapper: themed });
  return {
    onPause,
    box: screen.getByRole<HTMLInputElement>("searchbox"),
    rehost(handler: (typed: string) => void) {
      view.rerender(<Host onPause={handler} />);
    },
    replace() {
      fireEvent.click(screen.getByRole("button", { name: "Replace" }));
    },
    unmount: view.unmount,
  };
}

function type(box: HTMLInputElement, text: string) {
  fireEvent.change(box, { target: { value: text } });
}

function composing(box: HTMLInputElement, text: string) {
  fireEvent.input(box, { target: { value: text }, isComposing: true });
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

describe("useTypingPause", () => {
  it("shows what is typed at once, before anything is handed on", () => {
    const { box, onPause } = hosted();

    type(box, "ada");

    expect(box).toHaveValue("ada");
    expect(onPause).not.toHaveBeenCalled();
  });

  it("hands on to the handler it holds when the pause ends, not the one it held when typing began", () => {
    const { box, onPause, rehost } = hosted();
    const later = vi.fn();
    type(box, "ad");

    rehost(later);
    wait(QUIET_MS);

    expect(later.mock.calls).toEqual([["ad"]]);
    expect(onPause).not.toHaveBeenCalled();
  });

  it("hands on nothing once it has gone, even what it was waiting to send", () => {
    const { box, onPause, unmount } = hosted();
    type(box, "ad");

    unmount();
    wait(QUIET_MS * 3);

    expect(onPause).not.toHaveBeenCalled();
  });

  it("hands on what was typed exactly once, and only once typing has paused", () => {
    const { box, onPause } = hosted();
    type(box, "a");
    wait(QUIET_MS - 100);
    type(box, "ad");

    wait(QUIET_MS - 1);
    const beforeThePause = onPause.mock.calls.length;
    wait(1);
    const atThePause = [...onPause.mock.calls];
    wait(QUIET_MS * 3);

    expect(beforeThePause).toBe(0);
    expect(atThePause).toEqual([["ad"]]);
    expect(onPause.mock.calls).toEqual([["ad"]]);
  });

  /** Typed on and then back again: the pause is counted from the last keystroke, not the first. */
  it("counts the pause from the last keystroke, when the text comes back to what it was", () => {
    const { box, onPause } = hosted();
    type(box, "ad");
    wait(QUIET_MS - 100);
    type(box, "ada");
    wait(50);
    type(box, "ad");

    wait(QUIET_MS - 1);
    const beforeThePause = onPause.mock.calls.length;
    wait(1);

    expect(beforeThePause).toBe(0);
    expect(onPause.mock.calls).toEqual([["ad"]]);
  });

  it("hands on nothing typed before its text was set from outside", () => {
    const { box, onPause, replace } = hosted();
    type(box, "x");

    replace();
    wait(QUIET_MS * 3);

    expect(box).toHaveValue(FROM_OUTSIDE);
    expect(onPause).not.toHaveBeenCalled();
  });

  it("hands on nothing while a composition is under way, however long it takes", () => {
    const { box, onPause } = hosted();
    fireEvent.compositionStart(box);

    composing(box, "z");
    composing(box, "zh");
    wait(QUIET_MS * 3);

    expect(box).toHaveValue("zh");
    expect(onPause).not.toHaveBeenCalled();
  });

  it("hands on nothing typed before a composition that began within the pause", () => {
    const { box, onPause } = hosted();
    type(box, "a");
    fireEvent.compositionStart(box);

    composing(box, "az");
    wait(QUIET_MS * 3);

    expect(onPause).not.toHaveBeenCalled();
  });

  it("hands on what a composition settled on, once it has ended and typing paused", () => {
    const { box, onPause } = hosted();
    fireEvent.compositionStart(box);
    composing(box, "zh");
    composing(box, "中");

    fireEvent.compositionEnd(box);
    wait(QUIET_MS - 1);
    const beforeThePause = onPause.mock.calls.length;
    wait(1);

    expect(beforeThePause).toBe(0);
    expect(onPause.mock.calls).toEqual([["中"]]);
  });
});
