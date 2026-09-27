import { afterEach, describe, expect, it } from "vitest";

function button(): HTMLButtonElement {
  const made = document.createElement("button");
  document.body.append(made);
  return made;
}

/** The related target a focus on `next` reports. */
function relatedTargetOfFocusOn(next: HTMLElement): EventTarget | null {
  let reported: EventTarget | null | undefined;
  next.addEventListener("focus", (event) => {
    reported = event.relatedTarget;
  });
  next.focus();
  return reported ?? null;
}

afterEach(() => {
  document.body.replaceChildren();
});

describe("the focus events this environment reports", () => {
  /** A related target is an element or nothing; the document stands in for neither. */
  it("report no related target for a focus that follows a focused element's removal", () => {
    const removed = button();
    removed.focus();
    removed.remove();

    expect(relatedTargetOfFocusOn(button())).toBeNull();
  });

  it("report the element focus came from where it is still there", () => {
    const left = button();
    left.focus();

    expect(relatedTargetOfFocusOn(button())).toBe(left);
  });
});
