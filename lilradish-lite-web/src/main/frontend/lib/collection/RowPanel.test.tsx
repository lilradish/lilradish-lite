import { ThemeProvider } from "@mui/material/styles";
import {
  act,
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import { userEvent } from "@testing-library/user-event";
import { useLayoutEffect, useState, type ReactNode } from "react";
import { createPortal } from "react-dom";
import { describe, expect, it, onTestFinished, vi } from "vitest";

import { laidOutAt } from "../../testutil/layout";
import type { Line } from "../notice/StatusLine";
import { theme } from "../theme/theme";
import { RowPanel } from "./RowPanel";

/** The panel's own threshold: a table's least width, a gap, and the panel. */
const ROOM_FOR_BOTH = 944;

/** How far past that threshold a covering panel waits before it steps aside. */
const SCROLLBAR_ALLOWANCE = 24;

const WIDE = 1280;
const NARROW = 700;

/** Wider than any frame here: what the list is given is less than the window. */
const VIEWPORT = 1920;

/** `theme.zIndex.modal`, which `theme.ts` leaves at Material's own value. */
const MODAL_LAYER = "1300";

function themed({ children }: { readonly children: ReactNode }) {
  return <ThemeProvider theme={theme}>{children}</ThemeProvider>;
}

/**
 * A panel over a list whose one row opens it, as a screen holds one: `open`
 * is the caller's, and shutting the panel is the caller setting it false.
 *
 * A form drawn and replaced within one render leaves little to look at after,
 * so traces are kept: how often the content was put in and taken out, every
 * change to what is hidden from assistive technology, which form stood before
 * the observer had said anything, and every key press heard above the panel.
 */
async function opened(
  width: number,
  openAtFirst: boolean,
  saidOver: Line | null = null,
) {
  const resizeTo = laidOutAt(width, VIEWPORT);
  // The box the panel is given, standing where the layout gives it a width,
  // while what listens above it stays above it in the tree.
  const frame = document.createElement("div");
  document.body.append(frame);
  onTestFinished(() => frame.remove());
  const closes = vi.fn();
  const heardAbove = vi.fn();
  const content = { mounts: 0, unmounts: 0 };
  const hidings: MutationRecord[] = [];
  const watcher = new MutationObserver((records) => hidings.push(...records));
  watcher.observe(document.body, {
    subtree: true,
    attributeFilter: ["aria-hidden"],
  });

  /** Content with controls of its own, as a panel's content has: one shuts it as a change might. */
  function Content({
    onShut,
    onForgetRow,
  }: {
    readonly onShut: () => void;
    readonly onForgetRow: () => void;
  }) {
    useLayoutEffect(() => {
      content.mounts += 1;
      return () => {
        content.unmounts += 1;
      };
    }, []);
    return (
      <>
        <p>Details of Ada Lovelace</p>
        <button onClick={onShut}>Done with Ada</button>
        <button onClick={onForgetRow}>Take the row away</button>
        <button onKeyDown={(event) => event.stopPropagation()}>
          Keeps its keys
        </button>
      </>
    );
  }

  function Screen() {
    const [open, setOpen] = useState(openAtFirst);
    const [row, setRow] = useState(true);
    return (
      <div onKeyDown={heardAbove}>
        {createPortal(
          <RowPanel
            open={open}
            onClose={() => {
              closes();
              setOpen(false);
            }}
            title="Ada Lovelace"
            list={
              row ? (
                <button onClick={() => setOpen(true)}>Ada Lovelace</button>
              ) : null
            }
            listLabel="Records"
            saidOver={saidOver}
          >
            <Content
              onShut={() => setOpen(false)}
              onForgetRow={() => setRow(false)}
            />
          </RowPanel>,
          frame,
        )}
      </div>
    );
  }

  render(<Screen />, { wrapper: themed });
  const unobserved = formsShown();
  // The observer's first notice arrives after `observe`, never inside it.
  await act(async () => {});
  hidings.push(...watcher.takeRecords());
  watcher.disconnect();
  return { resizeTo, closes, heardAbove, content, hidings, unobserved };
}

/** Counted whether or not a covering panel has hidden the rest from queries. */
function formsShown() {
  return {
    beside: screen.queryAllByRole("region", {
      name: "Ada Lovelace",
      hidden: true,
    }).length,
    over: screen.queryAllByRole("dialog", { hidden: true }).length,
  };
}

const ADA_IN: Line = { severity: "info", words: "Ada Lovelace is in." };

const BESIDE = { beside: 1, over: 0 };
const OVER = { beside: 0, over: 1 };

/** The row that opens the panel, found whether or not the panel covers it. */
function row(): HTMLElement {
  return screen.getByRole("button", { name: "Ada Lovelace", hidden: true });
}

/** The list's own box, found whether or not the panel covers it. */
function listBox(): HTMLElement {
  return screen.getByRole("region", { name: "Records", hidden: true });
}

/**
 * The drawer leaves on a transition, and what happens once it has gone happens
 * after it. Real time rather than a faked clock, which `waitFor` in this suite
 * cannot see and would deadlock on.
 */
async function settle() {
  await act(async () => {
    await new Promise((resolve) =>
      setTimeout(resolve, theme.transitions.duration.leavingScreen * 2),
    );
  });
}

describe("RowPanel", () => {
  it.each([
    ["beside the list", WIDE, BESIDE],
    ["over the list", NARROW, OVER],
  ])(
    "puts its content in the document once, %s, at the width it opens at",
    async (_where, width, forms) => {
      const { content } = await opened(width, true);

      expect(formsShown()).toEqual(forms);
      expect(content.mounts).toBe(1);
      expect(content.unmounts).toBe(0);
    },
  );

  it.each([
    ["wide enough for both", WIDE],
    ["too narrow for both", NARROW],
  ])(
    "shows the list alone while nothing is open, %s",
    async (_where, width) => {
      const { content } = await opened(width, false);

      expect(formsShown()).toEqual({ beside: 0, over: 0 });
      expect(content.mounts).toBe(0);
      expect(row()).toBeVisible();
    },
  );

  it("names the list's own box, where the keyboard is put down", async () => {
    await opened(WIDE, false);

    expect(listBox()).toBe(row().parentElement);
    expect(listBox()).toHaveAttribute("tabindex", "-1");
  });

  /** Covering the list, however briefly, hides it from a screen reader and takes focus with it. */
  it("hides nothing of the list, not even for a moment, when it opens beside it", async () => {
    const { hidings } = await opened(WIDE, true);

    expect(hidings).toEqual([]);
    expect(formsShown()).toEqual(BESIDE);
  });

  /** A panel that waited for the observer would be missing from the first frame drawn. */
  it.each([
    ["beside the list", WIDE, BESIDE],
    ["over the list", NARROW, OVER],
  ])(
    "stands %s before the observer has said anything about the width",
    async (_where, width, forms) => {
      const { unobserved } = await opened(width, true);

      expect(unobserved).toEqual(forms);
    },
  );

  it.each([
    ["short of the room for both", ROOM_FOR_BOTH - 1, OVER],
    [
      "at the room for both, with less than a scrollbar to spare",
      ROOM_FOR_BOTH + SCROLLBAR_ALLOWANCE - 1,
      OVER,
    ],
    [
      "at the room for both and a scrollbar",
      ROOM_FOR_BOTH + SCROLLBAR_ALLOWANCE,
      BESIDE,
    ],
  ])("opens %s in the form that width calls for", async (_at, width, forms) => {
    await opened(width, true);

    expect(formsShown()).toEqual(forms);
  });

  /**
   * The window has room for both and the box the list was given does not,
   * both as it opens and once that box has been observed to change.
   */
  it("covers the list where its own box is narrow inside a wide window", async () => {
    const { resizeTo } = await opened(NARROW, true);
    const opening = formsShown();

    await resizeTo(NARROW - 1);

    expect(opening).toEqual(OVER);
    expect(formsShown()).toEqual(OVER);
  });

  /**
   * Standing beside the list can make the page tall enough for a scrollbar,
   * and covering it takes that scrollbar away again. One threshold both ways
   * would switch on each, back and forth, for as long as the page is open.
   */
  it.each([
    [
      "beside, narrowed to exactly the room for both",
      WIDE,
      ROOM_FOR_BOTH,
      BESIDE,
    ],
    ["beside, narrowed below the room for both", WIDE, ROOM_FOR_BOTH - 1, OVER],
    [
      "over, widened to less than a scrollbar past the room",
      NARROW,
      ROOM_FOR_BOTH + SCROLLBAR_ALLOWANCE - 1,
      OVER,
    ],
    [
      "over, widened to a scrollbar past the room",
      NARROW,
      ROOM_FOR_BOTH + SCROLLBAR_ALLOWANCE,
      BESIDE,
    ],
  ])("stands %s in the form that follows", async (_how, from, to, forms) => {
    const { resizeTo } = await opened(from, true);

    await resizeTo(to);

    expect(formsShown()).toEqual(forms);
  });

  /** Which row is open is in the address, and a width is no reason to drop it. */
  it("stays open while the width crosses the threshold one way and back", async () => {
    const { resizeTo, closes } = await opened(WIDE, true);

    await resizeTo(NARROW);
    const narrowed = formsShown();
    await resizeTo(WIDE);

    expect(narrowed).toEqual(OVER);
    expect(formsShown()).toEqual(BESIDE);
    expect(screen.getByText("Details of Ada Lovelace")).toBeVisible();
    expect(closes).not.toHaveBeenCalled();
  });

  it("stands beside the list as a region named by its own heading, with nothing to shut it", async () => {
    await opened(WIDE, true);

    const region = screen.getByRole("region", { name: "Ada Lovelace" });

    expect(
      within(region).getByRole("heading", { level: 2, name: "Ada Lovelace" }),
    ).toBeVisible();
    expect(within(region).getByText("Details of Ada Lovelace")).toBeVisible();
    expect(
      screen.queryByRole("button", { name: "Close", hidden: true }),
    ).toBeNull();
  });

  it("heads itself at the level it is given, one below the heading its list sits under", () => {
    laidOutAt(WIDE, VIEWPORT);

    render(
      <RowPanel
        open
        onClose={() => undefined}
        title="Ada Lovelace"
        headingLevel="h4"
        list={<p>Everybody</p>}
        listLabel="People"
      >
        <p>Details of Ada Lovelace</p>
      </RowPanel>,
      { wrapper: themed },
    );

    expect(
      screen.getByRole("heading", { level: 4, name: "Ada Lovelace" }),
    ).toBeVisible();
    expect(screen.queryByRole("heading", { level: 2 })).toBeNull();
  });

  it("leaves the list where it was and reachable while it stands beside it", async () => {
    await opened(WIDE, true);

    const list = screen.getByRole("button", { name: "Ada Lovelace" });
    const region = screen.getByRole("region", { name: "Ada Lovelace" });

    expect(
      list.compareDocumentPosition(region) & Node.DOCUMENT_POSITION_FOLLOWING,
    ).not.toBe(0);
    expect(region.contains(list)).toBe(false);
  });

  /**
   * Named from the paper, which is the dialog. Named on the drawer itself the
   * name lands on the presentational root around it, and the dialog has none.
   */
  it("covers the list as a dialog named by its own heading", async () => {
    await opened(NARROW, true);

    const dialog = screen.getByRole("dialog", { name: "Ada Lovelace" });

    expect(
      within(dialog).getByRole("heading", { level: 2, name: "Ada Lovelace" }),
    ).toBeVisible();
    expect(within(dialog).getByText("Details of Ada Lovelace")).toBeVisible();
    expect(formsShown()).toEqual(OVER);
  });

  /** A modal is under nothing: whatever a frame raises over its own drawers stays below it. */
  it("stands on the modal layer while it covers the list", async () => {
    await opened(NARROW, true);

    const layer = screen.getByRole("dialog").parentElement;

    expect(layer).not.toBeNull();
    expect(getComputedStyle(layer!).zIndex).toBe(MODAL_LAYER);
  });

  /** The page's own line is hidden behind it then, and words written there are never heard. */
  it("says the page's line inside itself while it covers the list, where it is heard", async () => {
    await opened(NARROW, true, ADA_IN);

    const dialog = screen.getByRole("dialog", { name: "Ada Lovelace" });

    await waitFor(() =>
      expect(within(dialog).getByRole("status")).toHaveTextContent(
        ADA_IN.words,
      ),
    );
    expect(
      screen
        .queryAllByRole("status")
        .filter((each) => each.textContent === ADA_IN.words),
    ).toHaveLength(1);
  });

  it("says nothing of the page's beside the list, where the page's own line is heard", async () => {
    await opened(WIDE, true, ADA_IN);

    const region = screen.getByRole("region", { name: "Ada Lovelace" });

    expect(within(region).queryAllByRole("status")).toEqual([]);
    expect(screen.queryByText(ADA_IN.words)).toBeNull();
  });

  it("shuts from its own close control only once, and only when covering the list", async () => {
    const { closes } = await opened(NARROW, true);

    await userEvent.click(screen.getByRole("button", { name: "Close" }));
    await settle();

    expect(closes).toHaveBeenCalledOnce();
    expect(formsShown()).toEqual({ beside: 0, over: 0 });
  });

  it("shuts on Escape when it covers the list", async () => {
    const { closes } = await opened(NARROW, true);

    await userEvent.keyboard("{Escape}");
    await settle();

    expect(closes).toHaveBeenCalledOnce();
    expect(formsShown()).toEqual({ beside: 0, over: 0 });
  });

  /** A control inside the content is where the keyboard is while it covers the list. */
  it("shuts on Escape pressed on a control inside its content", async () => {
    const { closes } = await opened(NARROW, true);
    screen.getByRole("button", { name: "Done with Ada" }).focus();

    await userEvent.keyboard("{Escape}");
    await settle();

    expect(closes).toHaveBeenCalledOnce();
    expect(formsShown()).toEqual({ beside: 0, over: 0 });
  });

  /**
   * The content stands in the drawer's element but belongs to this tree, and
   * React runs its events along both: what listens above hears each twice.
   */
  it.each([
    ["beside the list", WIDE, 1],
    ["over the list", NARROW, 2],
  ])(
    "hands a key pressed in its content to what listens above, %s, this many times",
    async (_where, width, times) => {
      const { heardAbove } = await opened(width, true);

      fireEvent.keyDown(screen.getByRole("button", { name: "Done with Ada" }), {
        key: "a",
      });

      expect(heardAbove).toHaveBeenCalledTimes(times);
    },
  );

  it("does not shut on Escape pressed on a control of the content that keeps its keys to itself", async () => {
    const { closes } = await opened(NARROW, true);
    screen.getByRole("button", { name: "Keeps its keys" }).focus();

    await userEvent.keyboard("{Escape}");
    await settle();

    expect(closes).not.toHaveBeenCalled();
    expect(formsShown()).toEqual(OVER);
  });

  /**
   * Opened straight from an address there was no control to open it, so none
   * to go back to; the list is the next thing a reader was going to use.
   */
  it.each([
    ["its close control", () => screen.getByRole("button", { name: "Close" })],
    [
      "the shade around it",
      // The shade is the drawer's one part hidden from assistive technology.
      () =>
        screen
          .getByRole("dialog")
          .parentElement!.querySelector<HTMLElement>(
            ':scope > [aria-hidden="true"]',
          )!,
    ],
  ])(
    "hands focus to the list once shut from %s, where it was opened from an address",
    async (_from, shutter) => {
      await opened(NARROW, true);

      await userEvent.click(shutter());
      await settle();

      expect(document.activeElement).toBe(listBox());
    },
  );

  it("hands focus back to the row that opened it once shut from its own control", async () => {
    await opened(NARROW, false);
    await userEvent.click(row());
    expect(formsShown()).toEqual(OVER);

    await userEvent.keyboard("{Escape}");
    await settle();

    expect(document.activeElement).toBe(row());
  });

  /** Shut by the caller — the row gone, the address moved on — the list is what is left. */
  it("hands focus to the list, not to the row that opened it, once the caller shuts it", async () => {
    await opened(NARROW, false);
    await userEvent.click(row());

    await userEvent.click(
      screen.getByRole("button", { name: "Done with Ada" }),
    );
    await settle();

    expect(document.activeElement).toBe(listBox());
    expect(document.activeElement).not.toBe(row());
  });

  /**
   * A covering panel hides the list until it has finished leaving; the
   * keyboard put down there sooner lands on something a screen reader cannot
   * reach. Judged in the focus event itself, as the list then stood.
   */
  it.each([
    [
      "the caller shuts it, on the list",
      () => screen.getByRole("button", { name: "Done with Ada" }),
      listBox,
    ],
    [
      "it is shut from its own control, on the row",
      () => screen.getByRole("button", { name: "Close" }),
      row,
    ],
  ])(
    "puts the keyboard down only once a covering panel has left, when %s",
    async (_case, shutter, landing) => {
      await opened(NARROW, false);
      await userEvent.click(row());
      const landings: { readonly on: Element; readonly hidden: boolean }[] = [];
      const heard = (event: FocusEvent) =>
        landings.push({
          on: event.target as Element,
          hidden:
            (event.target as Element).closest('[aria-hidden="true"]') !== null,
        });
      document.addEventListener("focusin", heard);
      onTestFinished(() => document.removeEventListener("focusin", heard));

      await userEvent.click(shutter());
      const whileLeaving = formsShown();
      await settle();

      expect(whileLeaving).toEqual(OVER);
      expect(landings.at(-1)).toEqual({ on: landing(), hidden: false });
      expect(landings.filter((each) => each.hidden)).toEqual([]);
    },
  );

  it("hands focus to the list once shut from its own control, where the row that opened it has gone", async () => {
    await opened(NARROW, false);
    await userEvent.click(row());
    await userEvent.click(
      screen.getByRole("button", { name: "Take the row away" }),
    );

    await userEvent.click(screen.getByRole("button", { name: "Close" }));
    await settle();

    expect(document.activeElement).toBe(listBox());
  });

  /**
   * Drawn anew in the other form, the content would lose whatever it holds —
   * a change still out, what was typed — and the control the keyboard was on.
   */
  it.each([
    ["beside the list to over it", WIDE, NARROW],
    ["over the list to beside it", NARROW, WIDE],
  ])(
    "keeps its content, and the keyboard on it, as it moves from %s",
    async (_how, from, to) => {
      const { resizeTo, content } = await opened(from, true);
      const control = screen.getByRole("button", { name: "Done with Ada" });
      control.focus();

      await resizeTo(to);

      expect(content.mounts).toBe(1);
      expect(content.unmounts).toBe(0);
      expect(
        screen.getByRole("button", { name: "Done with Ada", hidden: true }),
      ).toBe(control);
      expect(document.activeElement).toBe(control);
    },
  );

  /** The control that shut it goes with it, and the list is the next thing a reader was going to use. */
  it("hands focus to the list when shut beside it from inside its content", async () => {
    await opened(WIDE, true);

    await userEvent.click(
      screen.getByRole("button", { name: "Done with Ada" }),
    );

    expect(formsShown()).toEqual({ beside: 0, over: 0 });
    expect(document.activeElement).toBe(listBox());
  });

  it("leaves focus where it is when shut beside the list from outside its content", async () => {
    await opened(WIDE, true);
    const outside = document.createElement("button");
    document.body.append(outside);
    outside.focus();

    await act(async () => {
      screen.getByRole("button", { name: "Done with Ada" }).click();
    });

    expect(document.activeElement).toBe(outside);
    outside.remove();
  });

  it("takes its content out at once when shut beside the list", async () => {
    const { content } = await opened(WIDE, true);

    await userEvent.click(
      screen.getByRole("button", { name: "Done with Ada" }),
    );

    expect(content.unmounts).toBe(1);
    expect(content.mounts).toBe(1);
    expect(screen.queryByText("Details of Ada Lovelace")).toBeNull();
  });

  /** Taken out before the drawer has left, it would empty the drawer on its way out. */
  it("keeps its content while a covering panel leaves, then takes it out", async () => {
    const { content } = await opened(NARROW, true);

    await userEvent.click(screen.getByRole("button", { name: "Close" }));
    const whileLeaving = content.unmounts;
    await settle();

    expect(whileLeaving).toBe(0);
    expect(content.unmounts).toBe(1);
    expect(screen.queryByText("Details of Ada Lovelace")).toBeNull();
  });

  it.each([
    ["beside the list", WIDE],
    ["over the list", NARROW],
  ])(
    "puts a new content in when opened again after being shut, %s",
    async (_where, width) => {
      const { content } = await opened(width, true);
      await userEvent.click(
        screen.getByRole("button", { name: "Done with Ada" }),
      );
      await settle();

      await userEvent.click(row());

      expect(content.mounts).toBe(2);
      expect(content.unmounts).toBe(1);
      expect(screen.getByText("Details of Ada Lovelace")).toBeInTheDocument();
    },
  );

  /** Named through its heading, whose name is what is read out, and which carries no control character. */
  it.each([
    ["beside the list", WIDE, "region"],
    ["over the list", NARROW, "dialog"],
  ] as const)(
    "isolates its title in an element of its own, %s",
    async (_where, width, role) => {
      await opened(width, true);

      const heading = screen.getByRole("heading", { level: 2 });

      expect(heading.firstElementChild?.tagName).toBe("BDI");
      expect(
        screen.getByRole(role, { name: "Ada Lovelace" }),
      ).toBeInTheDocument();
    },
  );
});
