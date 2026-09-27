import { ThemeProvider } from "@mui/material/styles";
import { act, render, screen } from "@testing-library/react";
import { userEvent } from "@testing-library/user-event";
import type { ReactNode } from "react";
import { describe, expect, it, vi } from "vitest";

import { deferred } from "../../testutil/deferred";
import type { Page } from "../request/page";
import {
  usePagedResource,
  type PagedResource,
} from "../request/usePagedResource";
import { theme } from "../theme/theme";
import { PagingControls } from "./PagingControls";

/** `theme.palette.action.disabled`, which `theme.ts` leaves at Material's own value. */
const DISABLED_COLOUR = "rgba(0, 0, 0, 0.26)";

function themed({ children }: { children: ReactNode }) {
  return <ThemeProvider theme={theme}>{children}</ThemeProvider>;
}

function paged(over: Partial<PagedResource<unknown>>): PagedResource<unknown> {
  return {
    items: [],
    problem: null,
    loading: false,
    onFirstPage: true,
    hasNext: false,
    next: vi.fn(),
    first: vi.fn(),
    reload: vi.fn(),
    ...over,
  };
}

type Read = ReturnType<typeof deferred<Page<string>>>;

/**
 * The controls over the real paging hook, each read held until the test
 * answers it: what matters is what the controls do while a read is out, and
 * a stand-in page object would only show what the test chose to hand it.
 */
function browsing() {
  const reads: Read[] = [];
  const load = (_cursor: string | null) => {
    const read = deferred<Page<string>>();
    reads.push(read);
    return read.promise;
  };
  function Browsing() {
    return <PagingControls page={usePagedResource(load)} />;
  }
  render(<Browsing />, { wrapper: themed });
  return {
    async answer(page: Page<string>) {
      await act(async () => reads[reads.length - 1]!.settle(page));
    },
  };
}

/** On the second of three pages, with the way on and the way back both open. */
async function onTheMiddlePage() {
  const collection = browsing();
  await collection.answer({ items: ["one"], nextCursor: "two" });
  await userEvent.click(screen.getByRole("button", { name: "Next page" }));
  await collection.answer({ items: ["two"], nextCursor: "three" });
  return collection;
}

/**
 * A control that becomes disabled stops being somewhere focus can be, and the
 * browser then moves focus to the document itself; one that is removed takes
 * focus with it at once. Either way the keyboard starts again from the top.
 */
function expectKeyboardStillOn(label: string) {
  const control = screen.queryByRole("button", { name: label });
  expect(document.activeElement).toBe(control);
  expect(control).not.toBeDisabled();
}

function unavailable(label: string): boolean {
  const control = screen.getByRole("button", { name: label });
  return control.getAttribute("aria-disabled") === "true";
}

describe("PagingControls", () => {
  /** Two controls neither of which can ever be pressed is worse than none. */
  it("draws nothing where the collection begins and ends on the one page", () => {
    const { container } = render(
      <PagingControls page={paged({ onFirstPage: true, hasNext: false })} />,
      { wrapper: themed },
    );

    expect(container).toBeEmptyDOMElement();
  });

  /** Drawn and then taken away again by an answer that fits one page. */
  it("draws nothing while a collection's first page is still being read", () => {
    const { container } = render(
      <PagingControls
        page={paged({ onFirstPage: true, hasNext: false, loading: true })}
      />,
      { wrapper: themed },
    );

    expect(container).toBeEmptyDOMElement();
  });

  it("offers what follows, and no way back, on the page a collection opens on", () => {
    render(
      <PagingControls page={paged({ onFirstPage: true, hasNext: true })} />,
      { wrapper: themed },
    );

    expect(unavailable("Next page")).toBe(false);
    expect(unavailable("First page")).toBe(true);
  });

  it("offers the way back, and nothing further, on the last page", () => {
    render(
      <PagingControls page={paged({ onFirstPage: false, hasNext: false })} />,
      { wrapper: themed },
    );

    expect(unavailable("First page")).toBe(false);
    expect(unavailable("Next page")).toBe(true);
  });

  /**
   * Exactly these two labels and nothing beside them, which is what this
   * control is. A cursor points one way, so back-one is not a place it can
   * offer; and a side never told a total or a page number cannot show one, so
   * anything saying where the reader is would be something this invented.
   *
   * Stated as the whole text rather than as a rule about it: a rule against
   * digits misses "Page two of many" and would fail a future "Next 50" that is
   * no position at all.
   */
  it("offers no way back one page, and names no position in the collection", () => {
    const { container } = render(
      <PagingControls page={paged({ onFirstPage: false, hasNext: true })} />,
      { wrapper: themed },
    );

    expect(container.textContent).toBe("First pageNext page");
  });

  it.each([
    ["First page", "first"],
    ["Next page", "next"],
  ] as const)("asks for the %s the button names", async (label, asked) => {
    const page = paged({ onFirstPage: false, hasNext: true });
    render(<PagingControls page={page} />, { wrapper: themed });

    await userEvent.click(screen.getByRole("button", { name: label }));

    expect(page[asked]).toHaveBeenCalledOnce();
    expect(page[asked === "first" ? "next" : "first"]).not.toHaveBeenCalled();
    expect(page.reload).not.toHaveBeenCalled();
  });

  it.each([
    ["First page", { onFirstPage: true, hasNext: true }],
    ["Next page", { onFirstPage: false, hasNext: false }],
  ] as const)(
    "asks for nothing when %s is pressed where it has nowhere to go",
    async (label, where) => {
      const page = paged(where);
      render(<PagingControls page={page} />, { wrapper: themed });

      screen.getByRole("button", { name: label }).focus();
      await userEvent.keyboard("{Enter}[Space]");

      expect(page.first).not.toHaveBeenCalled();
      expect(page.next).not.toHaveBeenCalled();
      expect(page.reload).not.toHaveBeenCalled();
    },
  );

  it("draws a control with nowhere to go in the disabled colour and the other in its own", () => {
    render(
      <PagingControls page={paged({ onFirstPage: true, hasNext: true })} />,
      { wrapper: themed },
    );

    const first = getComputedStyle(
      screen.getByRole("button", { name: "First page" }),
    );
    const next = getComputedStyle(
      screen.getByRole("button", { name: "Next page" }),
    );

    expect(first.color).toBe(DISABLED_COLOUR);
    expect(first.pointerEvents).toBe("none");
    expect(next.color).not.toBe(DISABLED_COLOUR);
    expect(next.pointerEvents).not.toBe("none");
  });

  /** A ripple is the look of a press taking effect, and on this control none does. */
  it.each([
    ["on the first page", true, false],
    ["further on", false, true],
  ])(
    "answers Space on First page with a ripple only where it goes somewhere, %s",
    async (_where, onFirstPage, ripples) => {
      render(<PagingControls page={paged({ onFirstPage, hasNext: true })} />, {
        wrapper: themed,
      });
      await userEvent.tab();
      const control = screen.getByRole("button", { name: "First page" });

      await userEvent.keyboard("{ >}");
      // The ripple is drawn by an effect of the press, one tick after it.
      await act(async () => {});

      expect(document.activeElement).toBe(control);
      expect(
        control.querySelectorAll(".MuiTouchRipple-ripple").length > 0,
      ).toBe(ripples);
    },
  );

  it("keeps the keyboard on Next page while the page it asked for is read", async () => {
    await onTheMiddlePage();

    await userEvent.click(screen.getByRole("button", { name: "Next page" }));

    expectKeyboardStillOn("Next page");
  });

  it("keeps the keyboard on Next page once it has arrived at the last page", async () => {
    const collection = await onTheMiddlePage();
    await userEvent.click(screen.getByRole("button", { name: "Next page" }));

    await collection.answer({ items: ["three"] });

    expectKeyboardStillOn("Next page");
  });

  it("keeps the keyboard on First page while the page it asked for is read", async () => {
    await onTheMiddlePage();

    await userEvent.click(screen.getByRole("button", { name: "First page" }));

    expectKeyboardStillOn("First page");
  });

  it("keeps the keyboard on First page once it has arrived at the first page", async () => {
    const collection = await onTheMiddlePage();
    await userEvent.click(screen.getByRole("button", { name: "First page" }));

    await collection.answer({ items: ["one"], nextCursor: "two" });

    expectKeyboardStillOn("First page");
  });
});
