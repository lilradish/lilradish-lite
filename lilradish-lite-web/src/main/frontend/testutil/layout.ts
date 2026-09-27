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
  let width = frame;
  const live = new Set<StandIn>();

  const widthOf = (box: Element) =>
    box === document.documentElement || box === document.body
      ? viewport
      : box.parentElement?.parentElement === document.body
        ? width
        : 0;

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
    width = next;
    await act(async () => {
      for (const observer of live) {
        observer.deliver();
      }
    });
  };
}
