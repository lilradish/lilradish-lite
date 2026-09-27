import { ThemeProvider } from "@mui/material/styles";
import { act, render, screen, waitFor } from "@testing-library/react";
import { userEvent } from "@testing-library/user-event";
import type { ReactNode } from "react";
import { describe, expect, it, onTestFinished, vi } from "vitest";

import { AppShell } from "./AppShell";
import { theme } from "../lib/theme/theme";

/** Either side of the theme's `md` breakpoint, which is 900px. */
const WIDE = 1280;
const NARROW = 700;

/**
 * This environment implements no `matchMedia` at all, and the hook reading it
 * answers `false` to every query when it is missing — so without this every
 * viewport would look narrow and the wide half would never be exercised.
 */
function viewport(pixels: number) {
  vi.stubGlobal("matchMedia", (query: string) => {
    const floor = /min-width:\s*(\d+)px/.exec(query);
    return {
      matches: floor !== null && pixels >= Number(floor[1]),
      media: query,
      addEventListener: () => {},
      removeEventListener: () => {},
    };
  });
}

function themed({ children }: { readonly children: ReactNode }) {
  return <ThemeProvider theme={theme}>{children}</ThemeProvider>;
}

/**
 * A group heading, and a destination shaped the way a real one is: an icon and
 * the words, both inside the anchor. A pointer lands on one of those rather
 * than on the anchor, which is why the shell walks up from what was hit — and
 * why a fixture of bare anchor text would let a guard that never walks pass.
 */
const NAVIGATION = (
  <>
    <h3>Work</h3>
    <a href="#flows">
      <span aria-hidden="true">
        <svg />
      </span>
      <span>Workflows</span>
    </a>
  </>
);

/**
 * A fresh element every time. React bails out of re-rendering a subtree when
 * it is handed the very same element back, so a hoisted one would make a
 * rerender do nothing at all and every assertion after it meaningless.
 */
function shellElement(at?: string) {
  return (
    <AppShell
      brand={<span>lilradish</span>}
      navigation={NAVIGATION}
      utility={<button>Who you are</button>}
      at={at}
    >
      <p>the work</p>
    </AppShell>
  );
}

function shell(pixels: number) {
  viewport(pixels);
  return render(shellElement(), { wrapper: themed });
}

/**
 * An open drawer is a modal and marks the rest of the document `aria-hidden`,
 * so the ordinary query cannot see the control that opened it. Counting the
 * hidden ones asks about the viewport rather than about what is covering it.
 */
function openControls(): HTMLElement[] {
  return screen.queryAllByRole("button", {
    name: "Open navigation",
    hidden: true,
  });
}

/**
 * The drawer leaves on a transition, so it is still in the document for a
 * moment after it has been dismissed — long enough for an assertion made
 * straight after a click to pass whether or not the click dismissed it.
 *
 * A real wait, not a fake clock: `waitFor` only looks for a faked timer behind
 * a `typeof jest !== "undefined"` guard, and this suite runs without globals,
 * so faking them deadlocks every case that waits rather than speeding this up.
 */
async function settle() {
  await act(async () => {
    await new Promise((resolve) =>
      setTimeout(resolve, theme.transitions.duration.leavingScreen * 2),
    );
  });
}

/** What a tap on a destination actually lands on: the icon, not the words. */
function iconOf(destination: string): Element {
  const icon = screen
    .getByRole("link", { name: destination })
    .querySelector("svg");
  if (icon === null) {
    throw new Error(`${destination} is rendered without an icon`);
  }
  return icon;
}

function precedes(earlier: Element, later: Element): boolean {
  const relation = earlier.compareDocumentPosition(later);
  return (relation & Node.DOCUMENT_POSITION_FOLLOWING) !== 0;
}

describe("AppShell", () => {
  /**
   * The destinations stand between the address bar and the work, and a
   * keyboard arrives at the top of the document on every screen.
   */
  it("hands a keyboard the work first, at something able to hold focus", async () => {
    shell(WIDE);

    await userEvent.tab();

    const skip = screen.getByRole("link", { name: "Skip to content" });
    expect(document.activeElement).toBe(skip);
    const work = document.querySelector<HTMLElement>(
      skip.getAttribute("href") ?? "",
    );
    expect(work?.tagName).toBe("MAIN");
    work?.focus();
    expect(document.activeElement).toBe(work);
  });

  /**
   * A router keeps the way back in the history entry's state. Following the
   * fragment would push an entry holding none, and Back would stop on it.
   */
  it("takes the keyboard to the work without a history entry or a change of address", async () => {
    shell(WIDE);
    window.history.replaceState({ journey: "kept" }, "");
    onTestFinished(() => window.history.replaceState(null, ""));
    const entries = window.history.length;
    await userEvent.tab();

    await userEvent.keyboard("{Enter}");

    expect(document.activeElement?.tagName).toBe("MAIN");
    expect(window.location.hash).toBe("");
    expect(window.history.length).toBe(entries);
    expect(window.history.state).toEqual({ journey: "kept" });
  });

  /**
   * Shrunk and clipped rather than taken out: a skip link removed from the
   * document, or given `display: none`, is one nobody can reach either.
   */
  it("keeps the skip link off the page without keeping it from the keyboard", () => {
    shell(WIDE);

    const resting = getComputedStyle(
      screen.getByRole("link", { name: "Skip to content" }),
    );

    expect(resting.width).toBe("1px");
    expect(resting.clipPath).toBe("inset(50%)");
    expect(screen.getByRole("link", { name: "Skip to content" })).toBeVisible();
  });

  /**
   * One drawer whose variant follows the viewport, not one drawer per
   * viewport: the second would offer a screen reader two navigations under the
   * same name and no way to tell which one is showing.
   */
  it("keeps the destinations in view on a wide viewport, with nothing to open", () => {
    shell(WIDE);

    const landmarks = screen.getAllByRole("navigation");

    expect(landmarks).toHaveLength(1);
    expect(landmarks[0]).toHaveAccessibleName("Site");
    expect(
      screen.queryByRole("button", { name: "Open navigation" }),
    ).toBeNull();
  });

  it("folds the destinations behind one control on a narrow viewport", async () => {
    shell(NARROW);

    expect(screen.queryByRole("navigation")).toBeNull();
    await userEvent.click(
      screen.getByRole("button", { name: "Open navigation" }),
    );

    const landmarks = screen.getAllByRole("navigation");
    expect(landmarks).toHaveLength(1);
    expect(landmarks[0]).toHaveAccessibleName("Site");
  });

  /**
   * A drawer left over the screen it had just navigated to would have the
   * reader dismissing it to see what they asked for. Closed from a keyboard
   * here, which is the half that does not follow from a pointer working.
   */
  it("puts the destinations away again once one of them has been chosen", async () => {
    shell(NARROW);
    await userEvent.click(
      screen.getByRole("button", { name: "Open navigation" }),
    );

    screen.getByRole("link", { name: "Workflows" }).focus();
    await userEvent.keyboard("{Enter}");

    await waitFor(() => expect(screen.queryByRole("navigation")).toBeNull());
  });

  /**
   * The half a keyboard cannot show: a pointer never lands on the anchor, only
   * on the icon or the words inside it, so the drawer has to look up from what
   * was hit. A guard reading the target alone leaves the drawer over every
   * screen a tap navigates to, and passes every other case in this file.
   */
  it("puts them away when a tap lands on a destination's icon rather than its anchor", async () => {
    shell(NARROW);
    await userEvent.click(
      screen.getByRole("button", { name: "Open navigation" }),
    );

    await userEvent.click(iconOf("Workflows"));

    await waitFor(() => expect(screen.queryByRole("navigation")).toBeNull());
  });

  /**
   * Everything in the drawer is inside the thing that dismisses it, so a tap
   * on a heading, on the padding, or at the end of a text selection would cost
   * the reader the navigation and a second trip to the control.
   */
  it("keeps the destinations up when a tap on them went nowhere", async () => {
    shell(NARROW);
    await userEvent.click(
      screen.getByRole("button", { name: "Open navigation" }),
    );

    await userEvent.click(screen.getByRole("heading", { name: "Work" }));
    await settle();

    expect(screen.getAllByRole("navigation")).toHaveLength(1);
  });

  /**
   * Changing your mind is the other way out, and the keyboard that opened the
   * drawer has to come back to where it left rather than to the top of the
   * document.
   */
  it("lets a reader back out of the destinations and hands the keyboard back", async () => {
    shell(NARROW);
    const control = screen.getByRole("button", { name: "Open navigation" });
    await userEvent.click(control);

    await userEvent.keyboard("{Escape}");

    await waitFor(() => expect(screen.queryByRole("navigation")).toBeNull());
    expect(document.activeElement).toBe(control);
  });

  /** Half-typed words in the navigation are the reader's, and shutting the drawer is no reason to lose them. */
  it("keeps what the destinations hold while shut, out of reach until opened again", async () => {
    viewport(NARROW);
    render(
      <AppShell
        brand={<span>lilradish</span>}
        navigation={<input aria-label="Find a group" />}
        utility={null}
      >
        <p>the work</p>
      </AppShell>,
      { wrapper: themed },
    );
    const control = screen.getByRole("button", { name: "Open navigation" });
    await userEvent.click(control);
    await userEvent.type(screen.getByLabelText("Find a group"), "Pay");
    await userEvent.keyboard("{Escape}");
    await settle();

    const whileShut = screen.queryByRole("textbox", { name: "Find a group" });
    await userEvent.click(control);

    expect(whileShut).toBeNull();
    expect(screen.getByRole("textbox", { name: "Find a group" })).toHaveValue(
      "Pay",
    );
  });

  /**
   * A rotation or a restored window changes the viewport under a drawer that
   * is already open. Widening answers the request to see the destinations;
   * carrying that answer back down would raise the drawer and its shade over a
   * reader who asked for neither.
   */
  it("does not reopen the destinations on the way back from a wide viewport", async () => {
    const { rerender } = shell(NARROW);
    await userEvent.click(
      screen.getByRole("button", { name: "Open navigation" }),
    );

    viewport(WIDE);
    rerender(shellElement());
    expect(openControls()).toEqual([]);
    viewport(NARROW);
    rerender(shellElement());

    expect(openControls()).toHaveLength(1);
    expect(screen.queryByRole("navigation")).toBeNull();
  });

  /** A move made from inside the drawer without a link, a group picked, still puts it away. */
  it("puts the destinations away once the reader is somewhere else, and not before", async () => {
    viewport(NARROW);
    const { rerender } = render(shellElement("/here"), { wrapper: themed });
    await userEvent.click(
      screen.getByRole("button", { name: "Open navigation" }),
    );

    rerender(shellElement("/here"));
    const stillOpen = screen.queryByRole("navigation") !== null;
    rerender(shellElement("/there"));

    expect(stillOpen).toBe(true);
    await waitFor(() => expect(screen.queryByRole("navigation")).toBeNull());
  });

  it("orders the parts of the frame itself and takes none of their contents", () => {
    shell(WIDE);

    const brand = screen.getByText("lilradish");
    const utility = screen.getByRole("button", { name: "Who you are" });
    const navigation = screen.getByRole("navigation", { name: "Site" });
    const work = screen.getByText("the work");

    expect(precedes(brand, utility)).toBe(true);
    expect(precedes(utility, navigation)).toBe(true);
    expect(precedes(navigation, work)).toBe(true);
  });
});
