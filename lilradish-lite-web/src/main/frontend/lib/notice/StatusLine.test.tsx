import { ThemeProvider } from "@mui/material/styles";
import { act, render, screen } from "@testing-library/react";
import type { ReactNode } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { appearancesOf } from "../../testutil/heard";
import { noticesIn } from "../../testutil/notices";
import { theme } from "../theme/theme";
import { StatusLine, type Line } from "./StatusLine";

const OUT: Line = {
  severity: "info",
  words: "Grace Hopper is out of the pool.",
};

const REFUSED: Line = {
  severity: "error",
  words: "Ada Lovelace is still in the pool: that was refused.",
};

function themed({ children }: { readonly children: ReactNode }) {
  return <ThemeProvider theme={theme}>{children}</ThemeProvider>;
}

/** A line inside a box that stands for the page a modal hides. */
function lineOf(said: Line | null) {
  return (
    <div data-testid="page">
      <StatusLine said={said} />
    </div>
  );
}

/** Hidden as a modal hides what is around it: by the attribute alone. */
async function hidden(covered: boolean) {
  await act(async () => {
    const page = screen.getByTestId("page");
    if (covered) {
      page.setAttribute("aria-hidden", "true");
    } else {
      page.removeAttribute("aria-hidden");
    }
  });
}

/** Frames drawn one at a time, on Vitest's faked animation frames. */
async function framesPass(count: number) {
  for (let drawn = 0; drawn < count; drawn += 1) {
    await act(async () => {
      vi.advanceTimersToNextFrame();
    });
  }
}

/** The region, found whether or not something above it is aria-hidden. */
function region(): HTMLElement {
  return screen.getByRole("status", { hidden: true });
}

/** Mounted with nothing to say, and past the frames that let it speak. */
async function standing() {
  const drawn = render(lineOf(null), { wrapper: themed });
  await framesPass(2);
  return drawn;
}

describe("StatusLine", () => {
  beforeEach(() => {
    vi.useFakeTimers({
      toFake: ["requestAnimationFrame", "cancelAnimationFrame"],
    });
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it("stands empty before its first line, and says it in the notice of its severity", async () => {
    const { rerender } = await standing();
    const before = region();
    const empty = before.textContent;

    rerender(lineOf(OUT));

    expect(empty).toBe("");
    expect(screen.getByRole("status")).toBe(before);
    expect(noticesIn(before)).toEqual([OUT]);
  });

  /** Arriving together, the region and its words are not reliably heard. */
  it("stands empty through its first frames even when it mounts with a line to say", async () => {
    render(lineOf(OUT), { wrapper: themed });
    const mounted = region().textContent;

    await framesPass(1);
    const afterOne = region().textContent;
    await framesPass(1);

    expect({ mounted, afterOne }).toEqual({ mounted: "", afterOne: "" });
    expect(noticesIn(region())).toEqual([OUT]);
  });

  it("says each line in place of the last while nothing above it is aria-hidden", async () => {
    const { rerender } = await standing();
    rerender(lineOf(OUT));

    rerender(lineOf(REFUSED));

    expect(noticesIn(screen.getByRole("status"))).toEqual([REFUSED]);
  });

  /** The same words a second time are no change to the document unless the notice is put in afresh. */
  it("says a new line again even where its words are the last one's", async () => {
    const appearances = appearancesOf(OUT.words);
    const { rerender } = await standing();
    rerender(lineOf(OUT));
    const first = region().firstElementChild;

    rerender(lineOf({ ...OUT }));

    expect(region().firstElementChild).not.toBe(first);
    expect(noticesIn(region())).toEqual([OUT]);
    expect(appearances()).toEqual([{ hidden: false }, { hidden: false }]);
  });

  /**
   * Uncovered, the region comes back into the accessibility tree; words put
   * in before a frame has drawn it there arrive with it.
   */
  it("puts a line in only once nothing above it is aria-hidden, and a frame has been drawn since", async () => {
    const appearances = appearancesOf(OUT.words);
    const { rerender } = await standing();
    await hidden(true);

    rerender(lineOf(OUT));
    const whileHidden = region().textContent;
    await hidden(false);
    const uncovered = region().textContent;
    await framesPass(1);
    const afterOne = region().textContent;
    await framesPass(1);

    expect(appearances()).toEqual([{ hidden: false }]);
    expect({ whileHidden, uncovered, afterOne }).toEqual({
      whileHidden: "",
      uncovered: "",
      afterOne: "",
    });
    expect(noticesIn(screen.getByRole("status"))).toEqual([OUT]);
  });

  it("waits afresh when hidden again before the frames have passed", async () => {
    const { rerender } = await standing();
    await hidden(true);
    rerender(lineOf(OUT));
    await hidden(false);
    await framesPass(1);

    await hidden(true);
    await framesPass(2);

    expect(region().textContent).toBe("");
  });

  it("keeps a line already said while hidden, and does not put it in again once uncovered", async () => {
    const appearances = appearancesOf(OUT.words);
    const { rerender } = await standing();
    rerender(lineOf(OUT));
    const notice = region().firstElementChild;

    await hidden(true);
    const whileHidden = region().firstElementChild;
    await hidden(false);
    await framesPass(2);

    expect(appearances()).toEqual([{ hidden: false }]);
    expect(whileHidden).toBe(notice);
    expect(region().firstElementChild).toBe(notice);
  });

  it("takes the last line down when a new one arrives while hidden, and says only the new one", async () => {
    const appearances = appearancesOf(REFUSED.words);
    const { rerender } = await standing();
    rerender(lineOf(OUT));
    await hidden(true);

    rerender(lineOf(REFUSED));
    const whileHidden = region().textContent;
    await hidden(false);
    await framesPass(2);

    expect(appearances()).toEqual([{ hidden: false }]);
    expect(whileHidden).toBe("");
    expect(noticesIn(screen.getByRole("status"))).toEqual([REFUSED]);
  });
});
