import { act } from "@testing-library/react";
import { vi } from "vitest";

/**
 * A list's box has only the room its frame leaves beside the panel drawn next to
 * it, and a box inside a list only what the cards around it leave of that.
 */
export function libraryLaidOutAt(frame: number, viewport: number) {
  vi.stubGlobal("innerWidth", viewport);
  let width = frame;
  const live = new Set<Measured>();
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
  const availableTo = (box: Element) => {
    let room = width;
    let inset = 0;
    let above = box.parentElement;
    while (above !== null) {
      if (above.getAttribute("role") === "group") {
        room -= inset;
        inset = 0;
        const panel = [...(above.parentElement?.children ?? [])].find(
          (child) => child !== above && !(child as HTMLElement).hidden,
        );
        const panelWidth =
          panel === undefined
            ? Number.NaN
            : Number.parseFloat(getComputedStyle(panel).width);
        if (Number.isFinite(panelWidth)) room -= panelWidth + 24;
      } else {
        inset += insetOf(above);
      }
      above = above.parentElement;
    }
    return Math.max(0, room);
  };
  const widthOf = (box: Element) =>
    box === document.body || box === document.documentElement
      ? viewport
      : availableTo(box);
  vi.spyOn(Element.prototype, "getBoundingClientRect").mockImplementation(
    function (this: Element) {
      return new DOMRect(0, 0, widthOf(this), 0);
    },
  );
  class Measured implements ResizeObserver {
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
      const changed = [...this.reported].filter(
        ([target, old]) => old !== widthOf(target),
      );
      if (changed.length === 0) return;
      this.notify(
        changed.map(([target]) => {
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
  vi.stubGlobal("ResizeObserver", Measured);
  return async (next: number) => {
    width = next;
    await act(async () => {
      for (const observer of live) observer.deliver();
    });
  };
}
