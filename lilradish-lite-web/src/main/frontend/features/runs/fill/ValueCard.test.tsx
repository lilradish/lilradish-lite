import { ThemeProvider } from "@mui/material/styles";
import { useState } from "react";
import {
  act,
  fireEvent,
  isInaccessible,
  render,
  screen,
  within,
} from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, onTestFinished, vi } from "vitest";

import { theme } from "../../../lib/theme/theme";
import { ModelProse } from "../../../lib/text/ModelProse";
import { HiddenShown } from "../../../lib/text/HiddenShown";
import { CurrentFileRunContext } from "./CurrentFileRun";
import { FilledFields, FilledValue } from "./FilledValue";
import { CopyFailure, CopyValue, ValueCard } from "./ValueCard";

function contentHeight(height: number, label?: string) {
  vi.spyOn(Element.prototype, "getBoundingClientRect").mockImplementation(
    function (this: Element) {
      return new DOMRect(
        0,
        0,
        200,
        this.parentElement?.hasAttribute("data-value-content") &&
          (label === undefined ||
            this.closest("section")?.querySelector("h3")?.textContent === label)
          ? height
          : 0,
      );
    },
  );
}

/**
 * The markers drawn in `part`. Under jsdom, judging a marker in the accessibility tree costs a computed style apiece,
 * which for hundreds outlasts the test under load: the run's ends are judged for it.
 */
function markersIn(part: HTMLElement) {
  const markers = within(part).queryAllByRole("img", { hidden: true });
  for (const end of new Set([markers[0], markers.at(-1)])) {
    if (end !== undefined) expect(isInaccessible(end)).toBe(false);
  }
  return markers;
}

function CopyControls({ text }: { readonly text: string }) {
  const [failed, setFailed] = useState(false);
  return (
    <>
      <div>
        <CopyValue text={text} onCopyFailed={setFailed} />
      </div>
      <CopyFailure failed={failed} />
    </>
  );
}

function replaceCopyBoundary(
  clipboard: Pick<Clipboard, "writeText"> | undefined,
  exec: (command: string) => boolean,
) {
  const priorClipboard = Object.getOwnPropertyDescriptor(
    navigator,
    "clipboard",
  );
  const priorCommand = Object.getOwnPropertyDescriptor(document, "execCommand");
  Object.defineProperty(navigator, "clipboard", {
    configurable: true,
    value: clipboard,
  });
  Object.defineProperty(document, "execCommand", {
    configurable: true,
    value: exec,
  });
  return () => {
    if (priorClipboard === undefined)
      Reflect.deleteProperty(navigator, "clipboard");
    else Object.defineProperty(navigator, "clipboard", priorClipboard);
    if (priorCommand === undefined)
      Reflect.deleteProperty(document, "execCommand");
    else Object.defineProperty(document, "execCommand", priorCommand);
  };
}

function measureContent(dimensions: {
  body: number;
  client: number;
  scroll: number;
}) {
  vi.spyOn(Element.prototype, "getBoundingClientRect").mockImplementation(
    function (this: Element) {
      return new DOMRect(
        0,
        0,
        200,
        this.parentElement?.hasAttribute("data-value-content")
          ? dimensions.body
          : 0,
      );
    },
  );
  vi.spyOn(Element.prototype, "clientHeight", "get").mockImplementation(
    function (this: Element) {
      return this.hasAttribute("data-value-content") ? dimensions.client : 0;
    },
  );
  vi.spyOn(Element.prototype, "scrollHeight", "get").mockImplementation(
    function (this: Element) {
      return this.hasAttribute("data-value-content") ? dimensions.scroll : 0;
    },
  );
}

describe("value cards", () => {
  describe("copy fallback", () => {
    it.each(["missing", "refused"] as const)(
      "copies complete raw text through a single copy event when the modern clipboard is %s",
      async (modern) => {
        const user = userEvent.setup();
        const text = "**Invented shipment**\n\u200bKeep the exact spelling. 🚀";
        const setData = vi.fn();
        const write = vi
          .fn()
          .mockRejectedValue(new DOMException("Denied", "NotAllowedError"));
        const command = vi.fn((name: string) => {
          expect(name).toBe("copy");
          const event = new Event("copy", { bubbles: true, cancelable: true });
          Object.defineProperty(event, "clipboardData", { value: { setData } });
          document.dispatchEvent(event);
          expect(event.defaultPrevented).toBe(true);
          return true;
        });
        // A timed-out test runs on into the next one: a restore in its own finally would land there.
        onTestFinished(
          replaceCopyBoundary(
            modern === "missing" ? undefined : { writeText: write },
            command,
          ),
        );
        const added = vi.spyOn(document, "addEventListener");
        const removed = vi.spyOn(document, "removeEventListener");
        const view = render(
          <ThemeProvider theme={theme}>
            <CopyControls text={text} />
          </ThemeProvider>,
        );
        const status = screen.getByRole("status");
        expect(status).toBeEmptyDOMElement();
        const copy = screen.getByRole("button", { name: "Copy" });
        if (modern === "missing") {
          await act(async () => {
            copy.focus();
            fireEvent.click(copy);
            expect(command).toHaveBeenCalledExactlyOnceWith("copy");
          });
        } else {
          await user.click(copy);
        }
        expect(view.container.querySelectorAll("button")).toHaveLength(1);
        expect(view.container.querySelectorAll('[role="status"]')).toHaveLength(
          1,
        );
        expect(copy).toHaveAccessibleName("Copied");
        expect(copy).toBeVisible();
        expect(copy).toHaveFocus();
        expect(view.container.querySelector('[role="status"]')).toBe(status);
        expect(isInaccessible(status)).toBe(false);
        expect(status).toBeVisible();
        expect(status).toBeEmptyDOMElement();
        expect(command).toHaveBeenCalledExactlyOnceWith("copy");
        expect(setData).toHaveBeenCalledExactlyOnceWith("text/plain", text);
        expect(write).toHaveBeenCalledTimes(modern === "missing" ? 0 : 1);
        const registration = added.mock.calls.find(
          ([type]) => type === "copy",
        )!;
        expect(registration[2]).toEqual({ once: true });
        expect(removed).toHaveBeenCalledWith("copy", registration[1]);
        const laterData = vi.fn();
        fireEvent.copy(document, { clipboardData: { setData: laterData } });
        expect(laterData).not.toHaveBeenCalled();
        view.rerender(
          <ThemeProvider theme={theme}>
            <CopyControls text="A second invented finding" />
          </ThemeProvider>,
        );
        expect(copy).toHaveAccessibleName("Copy");
        await user.click(copy);
        expect(setData.mock.calls).toEqual([
          ["text/plain", text],
          ["text/plain", "A second invented finding"],
        ]);
        expect(view.container).not.toHaveTextContent(
          /Couldn't copy|Something went wrong|broken_here/,
        );
      },
    );

    it.each(["false", "no event", "throws", "cannot write"] as const)(
      "keeps focus and explains a failed copy below the controls when the fallback %s",
      async (failure) => {
        const user = userEvent.setup();
        const write = vi
          .fn()
          .mockRejectedValue(new DOMException("Denied", "NotAllowedError"));
        const setData = vi.fn(() => {
          if (failure === "cannot write")
            throw new Error("Clipboard refused event data");
        });
        const command = vi.fn(() => {
          if (failure === "throws") throw new DOMException("Unavailable");
          if (failure !== "no event") {
            fireEvent.copy(document, { clipboardData: { setData } });
          }
          return failure !== "false";
        });
        onTestFinished(replaceCopyBoundary({ writeText: write }, command));
        const added = vi.spyOn(document, "addEventListener");
        const removed = vi.spyOn(document, "removeEventListener");
        contentHeight(400);
        const view = render(
          <ThemeProvider theme={theme}>
            <ValueCard label="Report" written="Original finding">
              <span>Original finding</span>
            </ValueCard>
          </ThemeProvider>,
        );
        const status = screen.getByRole("status");
        expect(status).toBeEmptyDOMElement();
        const copy = screen.getByRole("button", { name: "Copy" });
        await user.click(copy);
        expect(copy).toHaveFocus();
        expect(copy).not.toHaveAttribute("aria-disabled");
        expect(screen.getByRole("status")).toBe(status);
        expect(status).toHaveTextContent(
          "Couldn't copy here. Select the text and copy it instead.",
        );
        expect(status).toHaveStyle({
          marginTop: "4px",
          textAlign: "right",
          color: "rgb(154, 79, 0)",
          fontSize: "13px",
          lineHeight: "20px",
          fontWeight: "400",
          gap: "6px",
        });
        expect(status.previousElementSibling).toBe(copy.parentElement);
        expect(copy.parentElement).not.toContainElement(status);
        expect(status.querySelector("svg")).toHaveAttribute(
          "aria-hidden",
          "true",
        );
        expect(status.querySelector("svg")).toHaveStyle({
          width: "16px",
          height: "16px",
          marginTop: "2px",
        });
        const content = screen
          .getByRole("region", { name: "Report" })
          .querySelector("[data-value-content]")!;
        expect(content).not.toContainElement(status);
        expect(content).toHaveAttribute("inert");
        expect(
          screen.getByRole("button", { name: "Show all" }),
        ).toBeInTheDocument();
        expect(status.parentElement!.nextElementSibling).toBe(content);
        expect(status).toHaveAttribute("data-copy-control");
        expect(view.container).not.toHaveTextContent(
          /Something went wrong|broken_here/,
        );
        expect(screen.queryByRole("button", { name: "Copied" })).toBeNull();
        expect(command).toHaveBeenCalledExactlyOnceWith("copy");
        const registration = added.mock.calls.find(
          ([type]) => type === "copy",
        )!;
        expect(removed).toHaveBeenCalledWith("copy", registration[1]);
        fireEvent.copy(document, { clipboardData: { setData } });
        expect(setData).toHaveBeenCalledTimes(
          failure === "throws" || failure === "no event" ? 0 : 1,
        );
        view.unmount();
        expect(screen.queryByRole("status")).toBeNull();
      },
    );

    it("clears the failure at the next attempt, keeps it cleared on success, and restores the normal copy label after two seconds", async () => {
      const user = userEvent.setup();
      const write = vi
        .fn()
        .mockRejectedValueOnce(new Error("Denied"))
        .mockResolvedValueOnce(undefined);
      onTestFinished(replaceCopyBoundary({ writeText: write }, () => false));
      render(
        <ThemeProvider theme={theme}>
          <CopyControls text="An invented finding" />
        </ThemeProvider>,
      );
      await user.click(screen.getByRole("button", { name: "Copy" }));
      expect(screen.getByRole("status")).not.toBeEmptyDOMElement();
      onTestFinished(() => {
        vi.useRealTimers();
      });
      vi.useFakeTimers();
      fireEvent.click(screen.getByRole("button", { name: "Copy" }));
      expect(screen.getByRole("status")).toBeEmptyDOMElement();
      await act(async () => {});
      expect(
        screen.getByRole("button", { name: "Copied" }),
      ).toBeInTheDocument();
      act(() => vi.advanceTimersByTime(1999));
      expect(
        screen.getByRole("button", { name: "Copied" }),
      ).toBeInTheDocument();
      act(() => vi.advanceTimersByTime(1));
      expect(screen.getByRole("button", { name: "Copy" })).toBeInTheDocument();
      expect(screen.getByRole("status")).toBeEmptyDOMElement();
    });

    it("does not fall back or publish a failure when an abandoned modern copy rejects", async () => {
      const user = userEvent.setup();
      let refuse: ((failure: Error) => void) | undefined;
      const write = vi.fn(
        () =>
          new Promise<void>((_, reject) => {
            refuse = reject;
          }),
      );
      const command = vi.fn(() => false);
      onTestFinished(replaceCopyBoundary({ writeText: write }, command));
      const view = render(<CopyControls text="An invented finding" />);
      await user.click(screen.getByRole("button", { name: "Copy" }));
      view.unmount();
      await act(async () => refuse!(new Error("Denied after leaving")));
      expect(command).not.toHaveBeenCalled();
      expect(screen.queryByRole("status")).toBeNull();
    });
  });

  describe("measured preview", () => {
    it.each([
      [16, 191, 0, 0, false],
      [16, 192, 192, 192, false],
      [16, 193, 0, 0, true],
      [20, 240, 240, 240, false],
      [20, 241, 0, 0, true],
      [16, 0, 0, 400, false],
      [16, 40, 192, 193, true],
    ] as const)(
      "uses a strict twelve-rem boundary at root %s px, body %s px, client %s px, scroll %s px",
      (root, body, client, scroll, overflow) => {
        const priorFont = document.documentElement.style.fontSize;
        document.documentElement.style.fontSize = `${root}px`;
        vi.stubGlobal("IntersectionObserver", undefined);
        measureContent({ body, client, scroll });
        try {
          render(
            <ThemeProvider theme={theme}>
              <ValueCard label="Report" written="Every original word">
                <span>Every original word</span>
              </ValueCard>
            </ThemeProvider>,
          );
          const card = screen.getByRole("region", { name: "Report" });
          const content = card.querySelector("[data-value-content]")!;
          expect(content).toHaveTextContent("Every original word");
          expect(content.hasAttribute("inert")).toBe(overflow);
          expect(
            within(card).queryByRole("button", { name: "Show all" }) !== null,
          ).toBe(overflow);
          expect(content).toHaveStyle({
            maxHeight: `${root * 12}px`,
            overflow: "hidden",
          });
        } finally {
          document.documentElement.style.fontSize = priorFont;
          vi.unstubAllGlobals();
        }
      },
    );

    it("remeasures both observed elements as layout grows and shrinks, then disconnects on unmount", () => {
      vi.stubGlobal("IntersectionObserver", undefined);
      const dimensions = { body: 40, client: 0, scroll: 0 };
      measureContent(dimensions);
      let notify: ResizeObserverCallback | undefined;
      const observe = vi.fn();
      const disconnect = vi.fn();
      class Observer {
        constructor(callback: ResizeObserverCallback) {
          notify = callback;
        }
        observe = observe;
        disconnect = disconnect;
      }
      vi.stubGlobal("ResizeObserver", Observer);
      try {
        const view = render(
          <ThemeProvider theme={theme}>
            <ValueCard label="Report" written="Original words">
              <span>Original words</span>
            </ValueCard>
          </ThemeProvider>,
        );
        const card = screen.getByRole("region", { name: "Report" });
        const content = card.querySelector("[data-value-content]")!;
        expect(observe.mock.calls.map(([element]) => element)).toEqual([
          content.firstElementChild,
          content,
        ]);
        expect(
          within(card).queryByRole("button", { name: "Show all" }),
        ).toBeNull();
        dimensions.body = 400;
        act(() => notify!([], {} as ResizeObserver));
        expect(
          within(card).getByRole("button", { name: "Show all" }),
        ).toHaveAttribute("aria-expanded", "false");
        expect(content).toHaveAttribute("inert");
        dimensions.body = 40;
        act(() => notify!([], {} as ResizeObserver));
        expect(
          within(card).queryByRole("button", { name: "Show all" }),
        ).toBeNull();
        expect(content).not.toHaveAttribute("inert");
        expect(content).toHaveTextContent("Original words");
        expect(disconnect).not.toHaveBeenCalled();
        view.unmount();
        expect(disconnect).toHaveBeenCalledTimes(1);
      } finally {
        vi.unstubAllGlobals();
      }
    });

    it("uses mutation and window resize without ResizeObserver and cleans up both subscriptions", () => {
      const priorFont = document.documentElement.style.fontSize;
      document.documentElement.style.fontSize = "16px";
      vi.stubGlobal("IntersectionObserver", undefined);
      vi.stubGlobal("ResizeObserver", undefined);
      const dimensions = { body: 192, client: 0, scroll: 0 };
      measureContent(dimensions);
      let notify: MutationCallback | undefined;
      const observe = vi.fn();
      const disconnect = vi.fn();
      class Observer {
        constructor(callback: MutationCallback) {
          notify = callback;
        }
        observe = observe;
        disconnect = disconnect;
      }
      vi.stubGlobal("MutationObserver", Observer);
      const added = vi.spyOn(window, "addEventListener");
      const removed = vi.spyOn(window, "removeEventListener");
      try {
        const view = render(
          <ThemeProvider theme={theme}>
            <ValueCard label="Report" written="Original words">
              <span>Original words</span>
            </ValueCard>
          </ThemeProvider>,
        );
        const card = screen.getByRole("region", { name: "Report" });
        const content = card.querySelector("[data-value-content]")!;
        expect(observe).toHaveBeenCalledExactlyOnceWith(
          content.firstElementChild,
          { childList: true, subtree: true, characterData: true },
        );
        expect(content).not.toHaveAttribute("inert");
        dimensions.body = 193;
        act(() => notify!([], {} as MutationObserver));
        expect(
          within(card).getByRole("button", { name: "Show all" }),
        ).toBeInTheDocument();
        expect(content).toHaveAttribute("inert");
        document.documentElement.style.fontSize = "20px";
        act(() => window.dispatchEvent(new Event("resize")));
        expect(
          within(card).queryByRole("button", { name: "Show all" }),
        ).toBeNull();
        expect(content).not.toHaveAttribute("inert");
        expect(content).toHaveTextContent("Original words");
        const listener = added.mock.calls.find(([type]) => type === "resize")!;
        view.unmount();
        expect(disconnect).toHaveBeenCalledTimes(1);
        expect(removed).toHaveBeenCalledWith("resize", listener[1]);
      } finally {
        document.documentElement.style.fontSize = priorFont;
        vi.unstubAllGlobals();
      }
    });
  });

  it("holds a pending copy without losing focus or changing its tier, then shows Copied", async () => {
    vi.useFakeTimers();
    let finish: (() => void) | undefined;
    const write = vi.fn(
      () =>
        new Promise<void>((resolve) => {
          finish = resolve;
        }),
    );
    vi.stubGlobal("navigator", {
      ...navigator,
      clipboard: { writeText: write },
    });
    onTestFinished(() => {
      vi.unstubAllGlobals();
    });
    onTestFinished(() => {
      vi.useRealTimers();
    });
    render(
      <ThemeProvider theme={theme}>
        <CopyControls text="An invented fact" />
      </ThemeProvider>,
    );
    const copy = screen.getByRole("button", { name: "Copy" });
    copy.focus();
    fireEvent.click(copy);
    expect(copy).toHaveFocus();
    expect(copy).not.toBeDisabled();
    expect(copy).toHaveAttribute("aria-disabled", "true");
    expect(copy).toHaveStyle({ color: "rgb(20, 75, 108)" });
    fireEvent.click(copy);
    expect(write).toHaveBeenCalledExactlyOnceWith("An invented fact");
    act(() => vi.advanceTimersByTime(299));
    expect(copy.querySelector("svg")).toBeNull();
    act(() => vi.advanceTimersByTime(1));
    expect(copy.querySelector("svg")).not.toBeNull();
    expect(copy).toHaveAccessibleName("Copy, under way");
    await act(async () => finish!());
    expect(screen.getByRole("button", { name: "Copied" })).toHaveFocus();
    expect(copy).not.toHaveAttribute("aria-disabled");
    expect(copy.querySelector("svg")).toBeNull();
  });

  it("copies the entire closed leading block and body while switching only its own card", async () => {
    const user = userEvent.setup();
    const copied = vi.spyOn(navigator.clipboard, "writeText");
    const prefix = "---\r\nname: Imaginary cargo &amp;\r\n---\r\n";
    const text = prefix + "# Body\r\n**Finding**";
    render(
      <ThemeProvider theme={theme}>
        <FilledFields
          cards
          shut={false}
          fields={[
            {
              name: "body",
              label: "Body",
              kind: "text",
              lines: "several",
              mustBeGiven: false,
            },
            {
              name: "notes",
              label: "Notes",
              kind: "text",
              lines: "several",
              mustBeGiven: false,
            },
          ]}
          values={{ body: text, notes: "**Other finding**" }}
        />
      </ThemeProvider>,
    );
    const cards = screen.getAllByRole("region");
    expect(cards).toHaveLength(2);
    const [body, notes] = cards;
    expect(body.querySelector("pre code")?.textContent).toBe(prefix);
    await user.click(within(body).getByRole("button", { name: "Copy" }));
    expect(copied).toHaveBeenCalledExactlyOnceWith(text);
    await user.click(
      within(body).getByRole("button", { name: "Show as written" }),
    );
    expect(body.querySelector("pre")).toBeNull();
    expect(body.querySelector("[data-value-content] bdi")?.textContent).toBe(
      text,
    );
    expect(notes.querySelector("strong")?.textContent).toBe("Other finding");
    expect(
      within(notes).queryByRole("button", { name: "Show formatted" }),
    ).toBeNull();
    await user.click(
      within(body).getByRole("button", { name: "Show formatted" }),
    );
    expect(body.querySelector("pre code")?.textContent).toBe(prefix);
    expect(body.querySelector("h1")?.textContent).toBe("Body");
  });

  it("switches one several-line value without changing its sibling and copies its original spelling", async () => {
    const user = userEvent.setup();
    const copied = vi.spyOn(navigator.clipboard, "writeText");
    const { container } = render(
      <ThemeProvider theme={theme}>
        <FilledFields
          cards
          fields={[
            {
              name: "body",
              label: "Body",
              kind: "text",
              lines: "several",
              mustBeGiven: false,
            },
            {
              name: "notes",
              label: "Notes",
              kind: "text",
              lines: "several",
              mustBeGiven: false,
            },
          ]}
          values={{ body: "**Urgent**", notes: "*Read next*" }}
          shut={false}
        />
      </ThemeProvider>,
    );
    const body = screen.getByRole("region", {
      name: "\u2068Body\u2069 · body",
    });
    const notes = screen.getByRole("region", {
      name: "\u2068Notes\u2069 · notes",
    });
    expect(body.querySelector("strong")).toHaveTextContent("Urgent");
    expect(notes.querySelector("em")).toHaveTextContent("Read next");
    await user.click(
      within(body).getByRole("button", { name: "Show as written" }),
    );
    expect(body.querySelector("strong")).toBeNull();
    expect(body).toHaveTextContent("**Urgent**");
    expect(notes.querySelector("em")).toHaveTextContent("Read next");
    expect(
      within(notes).queryByRole("button", { name: "Show formatted" }),
    ).toBeNull();
    await user.click(within(body).getByRole("button", { name: "Copy" }));
    expect(copied).toHaveBeenCalledExactlyOnceWith("**Urgent**");
    expect(
      within(body).getByRole("button", { name: "Copied" }),
    ).toBeInTheDocument();
    expect(container.querySelectorAll("[data-value-content]")).toHaveLength(2);
  });

  it("expands a long value within its own card and keeps a withheld value out of copy controls", async () => {
    contentHeight(400, "Report");
    const user = userEvent.setup();
    const copied = vi.spyOn(navigator.clipboard, "writeText");
    const text = "A fictional shipment is waiting. ".repeat(12);
    render(
      <ThemeProvider theme={theme}>
        <FilledValue
          cards
          label="Report"
          field={{
            name: "body",
            kind: "text",
            lines: "several",
            mustBeGiven: false,
          }}
          shown={{ value: text }}
          shut
        />
        <FilledValue
          cards
          label="Private notes"
          shown={{ withheld: true }}
          shut
        />
      </ThemeProvider>,
    );
    const report = screen.getByRole("region", { name: "Report" });
    const content = report.querySelector("[data-value-content]");
    const opened = within(report).getByRole("button", { name: "Show all" });
    expect(getComputedStyle(report).padding).toBe("10px 12px");
    expect(opened).toHaveAttribute("aria-expanded", "false");
    expect(content).toHaveTextContent(text.trim());
    expect(content).toHaveAttribute("inert");
    await user.click(within(report).getByRole("button", { name: "Copy" }));
    expect(copied).toHaveBeenCalledExactlyOnceWith(text);
    expect(content).toHaveStyle({ "max-height": "192px", overflow: "hidden" });
    await user.click(opened);
    expect(
      within(report).getByRole("button", { name: "Show less" }),
    ).toHaveAttribute("aria-expanded", "true");
    expect(content).not.toHaveStyle({ overflow: "hidden" });
    expect(content).not.toHaveAttribute("inert");
    expect(content).toHaveTextContent(text.trim());
    const privateNotes = screen.getByRole("region", { name: "Private notes" });
    expect(privateNotes).toHaveTextContent(
      "You don't have permission to see this.",
    );
    expect(within(privateNotes).queryByRole("button")).toBeNull();
  });

  it.each([200, 201])(
    "keeps %s-code-point text fully readable when its actual layout fits",
    (count) => {
      contentHeight(40);
      const text = "\u{1f680}".repeat(count);
      render(
        <ThemeProvider theme={theme}>
          <ValueCard label="Symbols" written={text}>
            <span>{text}</span>
          </ValueCard>
        </ThemeProvider>,
      );
      const card = screen.getByRole("region", { name: "Symbols" });
      expect(card.querySelector("[data-value-content]")).toHaveTextContent(
        text,
      );
      expect(card.querySelector("[data-value-content]")).not.toHaveAttribute(
        "inert",
      );
      expect(
        within(card).queryByRole("button", { name: "Show all" }),
      ).toBeNull();
    },
  );

  it("copies an actual DOM range across cards without their header or button text", () => {
    const { container } = render(
      <ThemeProvider theme={theme}>
        <div>
          <ValueCard label="First" written="First body">
            <span>First body</span>
          </ValueCard>
          <ValueCard label="Second" written="Second body">
            <span>Second body</span>
          </ValueCard>
        </div>
      </ThemeProvider>,
    );
    const contents = container.querySelectorAll("[data-value-content]");
    const first = contents[0]!.querySelector("span")!.firstChild!;
    const second = contents[1]!.querySelector("span")!.firstChild!;
    const range = document.createRange();
    range.setStart(first, 0);
    range.setEnd(second, second.textContent!.length);
    const selection = window.getSelection()!;
    selection.removeAllRanges();
    selection.addRange(range);
    expect(selection.toString()).toContain("First body");
    expect(selection.toString()).toContain("Second body");
    const setData = vi.fn();
    fireEvent.copy(contents[0]!, { clipboardData: { setData } });
    expect(setData).toHaveBeenCalledExactlyOnceWith(
      "text/plain",
      "First body\n\nSecond body",
    );
    expect(setData.mock.calls[0]![1]).not.toContain("Copy");
    expect(setData.mock.calls[0]![1]).not.toContain("FirstFirst");
    selection.removeAllRanges();
  });

  it("preserves paragraph boundaries and partial text in a real formatted selection across cards", async () => {
    const { container } = render(
      <ThemeProvider theme={theme}>
        <FilledFields
          cards
          fields={[
            {
              name: "body",
              label: "Body",
              kind: "text",
              lines: "several",
              mustBeGiven: false,
            },
            {
              name: "notes",
              label: "Notes",
              kind: "text",
              lines: "several",
              mustBeGiven: false,
            },
          ]}
          values={{ body: "alpha\n\nbeta", notes: "gamma" }}
          shut={false}
        />
      </ThemeProvider>,
    );
    await screen.findByText(
      (_, element) =>
        element?.tagName === "P" && element.textContent === "beta",
    );
    const content = container.querySelectorAll("[data-value-content]");
    const paragraphs = content[0]!.querySelectorAll("p");
    expect(paragraphs).toHaveLength(2);
    const range = document.createRange();
    range.setStart(paragraphs[0]!.firstChild!, 2);
    range.setEnd(content[1]!.querySelector("p")!.firstChild!, 3);
    const selection = window.getSelection()!;
    selection.removeAllRanges();
    selection.addRange(range);
    const setData = vi.fn();
    fireEvent.copy(content[0]!, { clipboardData: { setData } });
    expect(setData).toHaveBeenCalledExactlyOnceWith(
      "text/plain",
      "pha\n\nbeta\n\ngam",
    );
    expect(setData.mock.calls[0]![1]).not.toMatch(/Copy|Show all|Body|Notes/);
    selection.removeAllRanges();
  });

  it("separates formatted table cells and rows in an actual copied selection", async () => {
    const { container } = render(
      <ThemeProvider theme={theme}>
        <FilledValue
          cards
          label="Shipment"
          field={{
            name: "shipment",
            kind: "text",
            lines: "several",
            mustBeGiven: false,
          }}
          shown={{
            value: "| Name | Amount |\n| --- | --- |\n| Package | 12 |",
          }}
          shut={false}
        />
      </ThemeProvider>,
    );
    await screen.findByRole("table");
    const content = container.querySelector("[data-value-content]")!;
    const range = document.createRange();
    range.selectNodeContents(content);
    const selection = window.getSelection()!;
    selection.removeAllRanges();
    selection.addRange(range);
    const setData = vi.fn();
    fireEvent.copy(content, { clipboardData: { setData } });
    expect(setData).toHaveBeenCalledExactlyOnceWith(
      "text/plain",
      "Name\tAmount\nPackage\t12",
    );
    expect(setData.mock.calls[0]![1]).not.toContain("Package12");
    selection.removeAllRanges();
  });

  it("keeps repeated inline-code selection exact and confines structural lookup reuse to each copy", async () => {
    const words = Array.from({ length: 64 }, (_, at) =>
      String(at).padStart(2, "0"),
    );
    const { container } = render(
      <ThemeProvider theme={theme}>
        <FilledValue
          cards
          label="Shipment"
          field={{
            name: "body",
            kind: "text",
            lines: "several",
            mustBeGiven: false,
          }}
          shown={{
            value: `Shipment ${words.map((word) => `\`${word}\``).join(" ")}.`,
          }}
          shut={false}
        />
      </ThemeProvider>,
    );
    await screen.findByText("63", { selector: "code" });
    const content = container.querySelector("[data-value-content]")!;
    const range = document.createRange();
    range.selectNodeContents(content);
    const selection = window.getSelection()!;
    selection.removeAllRanges();
    selection.addRange(range);
    const setData = vi.fn();
    const query = vi.spyOn(Element.prototype, "querySelector");
    for (let at = 0; at < 2; at++) {
      query.mockClear();
      fireEvent.copy(content, { clipboardData: { setData } });
      expect(
        query.mock.calls.filter(([selector]) =>
          selector.startsWith(":scope > p, :scope > table"),
        ),
      ).toHaveLength(1);
    }
    expect(setData.mock.calls).toEqual([
      ["text/plain", `Shipment ${words.join(" ")}.`],
      ["text/plain", `Shipment ${words.join(" ")}.`],
    ]);
    query.mockRestore();
    selection.removeAllRanges();
  });

  it("copies visible file metadata between text cards while keeping its download name accessible", () => {
    const { container } = render(
      <ThemeProvider theme={theme}>
        <CurrentFileRunContext.Provider
          value={{ groupId: "group", runId: "current" }}
        >
          <FilledFields
            cards
            shut={false}
            fields={[
              { name: "before", kind: "text", mustBeGiven: false },
              {
                name: "attachment",
                kind: "file",
                types: [".pdf"],
                mustBeGiven: false,
              },
              { name: "after", kind: "text", mustBeGiven: false },
            ]}
            values={{
              before: "First body",
              attachment: {
                id: "93f781ef-bbf6-4d65-8316-fc81dc121a45",
                name: "Imaginary claim.pdf",
                size: 5,
              },
              after: "Last body",
            }}
          />
        </CurrentFileRunContext.Provider>
      </ThemeProvider>,
    );
    const link = screen.getByRole("link", {
      name: /Download.*Imaginary claim.pdf/,
    });
    expect(link).toHaveAttribute(
      "href",
      "/api/groups/group/runs/current/files/93f781ef-bbf6-4d65-8316-fc81dc121a45",
    );
    const contents = container.querySelectorAll("[data-value-content]");
    const range = document.createRange();
    range.setStart(contents[0]!.querySelector("bdi")!.firstChild!, 0);
    const last = contents[2]!.querySelector("bdi")!.firstChild!;
    range.setEnd(last, last.textContent!.length);
    const selection = window.getSelection()!;
    selection.removeAllRanges();
    selection.addRange(range);
    const setData = vi.fn();
    fireEvent.copy(contents[0]!, { clipboardData: { setData } });
    expect(setData).toHaveBeenCalledTimes(1);
    const [type, text] = setData.mock.calls[0]!;
    expect(type).toBe("text/plain");
    expect(text).toMatch(
      /^First body\n\n.*Imaginary claim.pdf 5 B.*\n\nLast body$/,
    );
    expect(text.match(/Imaginary claim.pdf/g)).toHaveLength(1);
    expect(text).not.toMatch(/Download|Copy|Show all/);
    expect(link).toHaveAccessibleName(/Download.*Imaginary claim.pdf/);
    selection.removeAllRanges();
  });

  it.each([
    [
      "waiting_on_review",
      "Waiting for review",
      "rgb(138, 71, 0)",
      "rgb(251, 238, 219)",
    ],
    ["refused", "Rejected in review", "rgb(161, 38, 31)", "rgb(251, 231, 229)"],
    [
      "refused_for_length",
      "Rejected for length",
      "rgb(58, 68, 73)",
      "rgb(255, 255, 255)",
    ],
  ])(
    "describes the card with its %s state outside the heading",
    (status, words, color, ground) => {
      render(
        <ThemeProvider theme={theme}>
          <ValueCard label="Report" written="Facts" status={status}>
            <span>Facts</span>
          </ValueCard>
        </ThemeProvider>,
      );
      const card = screen.getByRole("region", { name: "Report" });
      const heading = within(card).getByRole("heading", {
        name: "Report",
        level: 3,
      });
      const chip = within(card).getByText(words!).parentElement!;
      expect(card).toHaveAccessibleDescription(words!);
      expect(heading).not.toContainElement(chip);
      expect(chip).toHaveStyle({
        color,
        backgroundColor: ground,
        fontSize: "12px",
      });
      expect(heading).toHaveStyle({
        fontSize: "13px",
        color: "rgb(88, 102, 108)",
      });
      expect(chip.querySelector("svg")).toHaveAttribute("aria-hidden", "true");
    },
  );

  it.each([undefined, "stands", "none", "future_status"])(
    "keeps a %s card free of a state description",
    (status) => {
      render(
        <ValueCard label="Report" written="Facts" status={status}>
          <span>Facts</span>
        </ValueCard>,
      );
      const card = screen.getByRole("region", { name: "Report" });
      expect(card).not.toHaveAttribute("aria-describedby");
      expect(card.querySelector("svg")).toBeNull();
    },
  );

  it("lays out a rejection reason with its own character budget and switch at the foot", async () => {
    const user = userEvent.setup();
    render(
      <ThemeProvider theme={theme}>
        <FilledValue
          cards
          shut={false}
          label="Report"
          field={{
            name: "report",
            kind: "text",
            lines: "several",
            mustBeGiven: false,
          }}
          shown={{ value: "**Accepted evidence**" }}
          status="refused"
          foot={
            <ModelProse
              text="*Check the inventory*"
              plain={<bdi>*Check the inventory*</bdi>}
            />
          }
        />
      </ThemeProvider>,
    );
    const card = screen.getByRole("region", { name: "Report" });
    const reason = card.querySelector("em")!;
    const foot = reason.closest(".MuiBox-root")!.parentElement!;
    expect(reason).toHaveTextContent("Check the inventory");
    expect(foot).toHaveStyle({
      marginTop: "10px",
      fontSize: "13px",
      borderTopWidth: "",
    });
    const toggles = within(card).getAllByRole("button", {
      name: "Show as written",
    });
    expect(toggles).toHaveLength(2);
    await user.click(toggles[1]!);
    expect(card.querySelector("em")).toBeNull();
    expect(card.querySelector("strong")).toHaveTextContent("Accepted evidence");
    expect(card).toHaveTextContent("*Check the inventory*");
    await user.click(toggles[0]!);
    expect(card.querySelector("strong")).toBeNull();
    expect(
      within(card).getAllByRole("button", { name: "Show formatted" }),
    ).toHaveLength(2);
  });

  it.each([
    [600, 600],
    [1001, 600],
    [600, 1001],
  ] as const)(
    "keeps the %s-character body and %s-character refusal on independent marker budgets and switches",
    async (bodyCount, reasonCount) => {
      const user = userEvent.setup();
      const copy = vi.spyOn(navigator.clipboard, "writeText");
      const bodyText = `**Evidence**\n${"a\u200b".repeat(bodyCount)}`;
      const reasonText = `*Refusal*\n${"b\u200b".repeat(reasonCount)}`;
      const view = render(
        <ThemeProvider theme={theme}>
          <FilledValue
            cards
            shut={false}
            label="Report"
            field={{
              name: "report",
              kind: "text",
              lines: "several",
              mustBeGiven: false,
            }}
            shown={{ value: bodyText }}
            status="refused"
            foot={
              <ModelProse
                text={reasonText}
                plain={
                  <bdi>
                    <HiddenShown text={reasonText} />
                  </bdi>
                }
              />
            }
          />
        </ThemeProvider>,
      );
      const card = screen.getByRole("region", { name: "Report" });
      const body = card.querySelector("[data-value-content]")! as HTMLElement;
      const foot = card.lastElementChild! as HTMLElement;
      const bodyMarkers = bodyCount > 1000 ? 0 : bodyCount;
      const reasonMarkers = reasonCount > 1000 ? 0 : reasonCount;
      expect(body.querySelector("strong")).toHaveTextContent("Evidence");
      expect(foot.querySelector("em")).toHaveTextContent("Refusal");
      expect(markersIn(body)).toHaveLength(bodyMarkers);
      expect(markersIn(foot)).toHaveLength(reasonMarkers);
      if (reasonCount > 1000) {
        expect(foot).toHaveTextContent(
          "1,001 invisible or text-direction characters are left out here",
        );
      } else {
        expect(foot).not.toHaveTextContent("left out here");
      }
      if (bodyCount > 1000) {
        expect(view.container).toHaveTextContent(
          "1,001 invisible or text-direction characters are left out here",
        );
        expect(foot).not.toHaveTextContent("left out here");
      } else if (reasonCount <= 1000) {
        expect(view.container).not.toHaveTextContent("left out here");
      }
      await user.click(
        within(foot).getByRole("button", { name: "Show as written" }),
      );
      expect(foot.querySelector("em")).toBeNull();
      expect(foot).toHaveTextContent("*Refusal*");
      expect(body.querySelector("strong")).toHaveTextContent("Evidence");
      expect(markersIn(body)).toHaveLength(bodyMarkers);
      expect(markersIn(foot)).toHaveLength(reasonMarkers);
      await user.click(
        within(card).getByRole("button", { name: "Show as written" }),
      );
      expect(body.querySelector("strong")).toBeNull();
      expect(body).toHaveTextContent("**Evidence**");
      expect(markersIn(body)).toHaveLength(bodyMarkers);
      expect(
        within(foot).getByRole("button", { name: "Show formatted" }),
      ).toBeInTheDocument();
      expect(markersIn(foot)).toHaveLength(reasonMarkers);
      await user.click(within(card).getByRole("button", { name: "Copy" }));
      expect(copy).toHaveBeenCalledExactlyOnceWith(bodyText);
    },
  );

  it("mounts an ordinary preview only when its card approaches the viewport, without waiting text", () => {
    let notify: IntersectionObserverCallback | undefined;
    const disconnect = vi.fn();
    class Observer {
      constructor(
        callback: IntersectionObserverCallback,
        options: IntersectionObserverInit,
      ) {
        notify = callback;
        expect(options.rootMargin).toBe("320px");
      }
      observe() {}
      disconnect = disconnect;
    }
    vi.stubGlobal("IntersectionObserver", Observer);
    try {
      render(
        <ThemeProvider theme={theme}>
          <FilledValue
            cards
            shut
            label="Report"
            field={{
              name: "report",
              kind: "text",
              lines: "several",
              mustBeGiven: false,
            }}
            shown={{ value: "**Near the viewport**" }}
          />
        </ThemeProvider>,
      );
      const card = screen.getByRole("region", { name: "Report" });
      expect(card.querySelector("strong")).toBeNull();
      expect(
        within(card).queryByRole("button", { name: "Show as written" }),
      ).toBeNull();
      act(() =>
        notify!(
          [{ isIntersecting: true } as IntersectionObserverEntry],
          {} as IntersectionObserver,
        ),
      );
      expect(card.querySelector("strong")).toHaveTextContent(
        "Near the viewport",
      );
      expect(
        within(card).getByRole("button", { name: "Show as written" }),
      ).toBeInTheDocument();
      expect(card).not.toHaveTextContent(/Loading|Still reading/);
      expect(disconnect).toHaveBeenCalled();
    } finally {
      vi.unstubAllGlobals();
    }
  });

  it("keeps an overflowing preview mounted and removes its fade and inert boundary when expanded", async () => {
    contentHeight(400);
    const user = userEvent.setup();
    const { container } = render(
      <ThemeProvider theme={theme}>
        <ValueCard label="Report" written="Short words">
          <span>Short words</span>
        </ValueCard>
      </ThemeProvider>,
    );
    const content = container.querySelector("[data-value-content]")!;
    const styles = () =>
      Array.from(document.querySelectorAll("style"))
        .map((style) => style.textContent)
        .join(" ");
    expect(content).toHaveTextContent("Short words");
    expect(content).toHaveAttribute("inert");
    expect(styles()).toContain("height:3.5rem");
    expect(styles()).toContain("linear-gradient(transparent, #ffffff)");
    await user.click(screen.getByRole("button", { name: "Show all" }));
    expect(content).not.toHaveAttribute("inert");
    expect(content).not.toHaveStyle({ overflow: "hidden" });
    await user.click(screen.getByRole("button", { name: "Show less" }));
    expect(content).toHaveAttribute("inert");
    expect(content).toHaveTextContent("Short words");
  });
});
