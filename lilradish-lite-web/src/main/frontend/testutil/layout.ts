import { act } from "@testing-library/react";
import { vi } from "vitest";

/**
 * The layout this environment never does, for a component that measures its
 * own box. The document's root and body span the viewport; the box handed to
 * the component takes the width given for the frame, and every box inside it
 * reports none, asked directly or observed.
 *
 * An observer behaves as the platform's does: a first notice arrives some time
 * after `observe`, never inside it, and only where the box is not the zero it
 * is taken to start at; after that, a notice only for a width that differs
 * from the last one reported, and none at all once disconnected.
 *
 * It sits in the source tree because the specs do: this project has no
 * separate test root to put it in. Only a spec may import it. The coverage
 * report leaves this directory out by path, so a production module importing
 * it would ship code that no report counts.
 */
export function laidOutAt(frame: number, viewport: number) {
  vi.stubGlobal("innerWidth", viewport);
  let width = frame;
  return measuredBy(
    (box) =>
      box === document.documentElement || box === document.body
        ? viewport
        : box.parentElement?.parentElement === document.body
          ? width
          : 0,
    (next) => {
      width = next;
    },
  );
}

/**
 * A whole page laid out `page` wide, as `laidOutAt` measures and observes it:
 * every box is as wide as the page, less the cards and side panels around it.
 * A box takes the room a panel standing beside its list leaves, with the gap
 * between them, and less the padding and border of each box it is inside.
 */
export function pageLaidOutAt(page: number, viewport: number) {
  vi.stubGlobal("innerWidth", viewport);
  let width = page;
  const besidePanelOf = (list: Element) => {
    const frame = list.parentElement;
    if (frame === null || getComputedStyle(list).flexGrow !== "1") return null;
    return (
      [...frame.children].find(
        (child) =>
          child !== list &&
          child.tagName === "SECTION" &&
          !(child as HTMLElement).hidden &&
          getComputedStyle(child).flexShrink === "0",
      ) ?? null
    );
  };
  const insetOf = (box: Element) => {
    const drawn = getComputedStyle(box);
    return [
      drawn.paddingLeft,
      drawn.paddingRight,
      drawn.borderLeftWidth,
      drawn.borderRightWidth,
    ]
      .map((side) => Number.parseFloat(side))
      .filter(Number.isFinite)
      .reduce((sum, side) => sum + side, 0);
  };
  const widthOf = (box: Element) => {
    if (box === document.documentElement || box === document.body)
      return viewport;
    let room = width;
    let inset = 0;
    let above = box.parentElement;
    while (above !== null) {
      const panel = besidePanelOf(above);
      if (panel !== null) {
        const panelWidth = Number.parseFloat(getComputedStyle(panel).width);
        room -= inset + (Number.isFinite(panelWidth) ? panelWidth + 24 : 0);
        inset = 0;
      } else {
        inset += insetOf(above);
      }
      above = above.parentElement;
    }
    return Math.max(0, room);
  };
  return measuredBy(widthOf, (next) => {
    width = next;
  });
}

function measuredBy(
  widthOf: (box: Element) => number,
  widen: (next: number) => void,
) {
  const live = new Set<StandIn>();

  vi.spyOn(Element.prototype, "getBoundingClientRect").mockImplementation(
    function (this: Element) {
      return new DOMRect(0, 0, widthOf(this), 0);
    },
  );

  class StandIn implements ResizeObserver {
    private readonly notify: ResizeObserverCallback;
    private readonly reported = new Map<Element, number>();

    constructor(notify: ResizeObserverCallback) {
      this.notify = notify;
    }

    observe(target: Element) {
      this.reported.set(target, 0);
      live.add(this);
      queueMicrotask(() => this.deliver());
    }

    unobserve(target: Element) {
      this.reported.delete(target);
    }

    disconnect() {
      this.reported.clear();
      live.delete(this);
    }

    deliver() {
      const changed = [...this.reported]
        .filter(([target, last]) => last !== widthOf(target))
        .map(([target]) => target);
      if (changed.length === 0) {
        return;
      }
      this.notify(
        changed.map((target) => {
          const now = widthOf(target);
          this.reported.set(target, now);
          const size = [{ inlineSize: now, blockSize: 0 }];
          return {
            target,
            contentRect: new DOMRect(0, 0, now, 0),
            borderBoxSize: size,
            contentBoxSize: size,
            devicePixelContentBoxSize: size,
          };
        }),
        this,
      );
    }
  }

  vi.stubGlobal("ResizeObserver", StandIn);

  /** The frame takes the new width, and each observer of it hears so. */
  return async function resizeTo(next: number) {
    widen(next);
    await act(async () => {
      for (const observer of live) {
        observer.deliver();
      }
    });
  };
}
