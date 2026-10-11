import { render } from "@testing-library/react";
import { createElement, useEffect } from "react";
import {
  afterEach,
  aroundAll,
  beforeAll,
  describe,
  expect,
  it,
  onTestFinished,
  vi,
} from "vitest";

import { expectConsoleErrors } from "./testutil/consoleErrors";

it("retains errors raised when the public cleanup unmounts a component", () => {
  function Receipt() {
    useEffect(() => () => console.error("Receipt detached"), []);
    return createElement("p", null, "Receipt");
  }
  const rendered = render(createElement(Receipt));
  onTestFinished(() => {
    expect(rendered.container).toBeEmptyDOMElement();
    expectConsoleErrors(["Receipt detached"]);
  });

  expect(rendered.getByText("Receipt")).toBeInTheDocument();
});

describe("suite cleanup", () => {
  const released = vi.fn();
  aroundAll(async (runSuite) => {
    await runSuite();
    expect(released).toHaveBeenCalledOnce();
    expectConsoleErrors(["Suite resource detached"]);
  });
  beforeAll(() => () => {
    released();
    console.error("Suite resource detached");
  });

  it("keeps the suite resource until the rendered test is finished", () => {
    const drawn = render(createElement("p", null, "Receipt read"));
    expect(drawn.getByText("Receipt read")).toBeInTheDocument();
    expect(released).not.toHaveBeenCalled();
    expectConsoleErrors();
  });
});

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

describe("the focus events this environment reports", () => {
  afterEach(() => {
    document.body.replaceChildren();
  });

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
