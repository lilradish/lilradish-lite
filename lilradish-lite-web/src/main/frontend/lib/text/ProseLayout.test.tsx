import { act, render, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { useMemo, useState } from "react";

import { ProseLayoutQueue } from "./ProseLayout";
import { renderProseInParts } from "./proseMarkdown";
import { prepareProseTrees, proseNeedsBackground } from "./prosePreparation";

afterEach(() => vi.useRealTimers());

function Mounted({ texts }: { readonly texts: readonly string[] }) {
  const [ready, setReady] = useState(false);
  const prepared = useMemo(() => prepareProseTrees(texts, texts), [texts]);
  const queue = useMemo(
    () =>
      new ProseLayoutQueue(
        () => setReady(true),
        () => {
          throw new Error("Unexpected layout failure");
        },
      ),
    [],
  );
  const parts = useMemo(
    () => renderProseInParts(prepared, queue),
    [prepared, queue],
  );
  return (
    <div hidden={!ready}>
      {texts.map((text, at) => (
        <div key={at}>{parts.rendered.get(text)}</div>
      ))}
    </div>
  );
}

describe("bounded prose layout", () => {
  it.each(["", "---\nname: Imaginary cargo\n---\n"])(
    "mounts void rules and hard breaks without children through the background layout: %s",
    (prefix) => {
      vi.useFakeTimers();
      const { container } = render(
        <Mounted texts={[prefix + "# Body\n\n---\n\nFirst  \nSecond"]} />,
      );
      act(() => vi.runAllTimers());
      expect(container.firstElementChild).not.toHaveAttribute("hidden");
      expect(container.querySelectorAll("hr")).toHaveLength(1);
      expect(container.querySelectorAll("br")).toHaveLength(1);
      expect(container.querySelector("hr")?.childNodes).toHaveLength(0);
      expect(container.querySelector("br")?.childNodes).toHaveLength(0);
      expect(container.querySelector("p")?.textContent).toBe("First\nSecond");
      expect(container.querySelectorAll("pre")).toHaveLength(
        prefix === "" ? 0 : 1,
      );
      if (prefix !== "")
        expect(container.querySelector("pre code")?.textContent).toBe(prefix);
    },
  );
  it("keeps the leading literal before all staged body blocks and reveals them together", () => {
    vi.useFakeTimers();
    const prefix = "---\nname: Imaginary cargo\n---\n";
    const { container, unmount } = render(
      <Mounted texts={[prefix + "# Body\n\n" + "Fact\n\n".repeat(300)]} />,
    );
    act(() => vi.advanceTimersToNextTimer());
    const literal = container.querySelector("pre")!;
    expect(literal.querySelector("code")?.textContent).toBe(prefix);
    expect(literal).not.toBeVisible();
    expect(container.querySelectorAll("p").length).toBeLessThan(300);
    act(() => vi.runAllTimers());
    expect(literal).toBeVisible();
    expect(literal.nextElementSibling?.tagName).toBe("H1");
    expect(container.querySelectorAll("p")).toHaveLength(300);
    expect(container.querySelector("p:last-child")?.textContent).toBe("Fact");
    unmount();
    expect(vi.getTimerCount()).toBe(0);
  });
  it("lets the real event loop advance while one large container is being mounted", async () => {
    let pulses = 0;
    const ticker = setInterval(() => {
      pulses += 1;
    }, 5);
    try {
      const { container } = render(
        <Mounted texts={["`Imaginary` ".repeat(6000)]} />,
      );
      await waitFor(
        () => expect(container.firstElementChild).not.toHaveAttribute("hidden"),
        { timeout: 10000 },
      );
      expect(pulses).toBeGreaterThan(3);
      expect(container.querySelectorAll("code")).toHaveLength(6000);
      expect(container.querySelectorAll("p")).toHaveLength(1);
    } finally {
      clearInterval(ticker);
    }
  }, 15000);
  it.each([
    ["x\n\n".repeat(16000), true],
    ["**x** ".repeat(16000), true],
    ["- x\n".repeat(16000), true],
    ["| A |\n| --- |\n" + "| x |\n".repeat(16000), true],
    ["# Imaginary ordinary note\n" + "Details ".repeat(4096), false],
  ])(
    "routes actual structural work without defining a reading limit: %s",
    (text, background) => {
      expect(proseNeedsBackground([text])).toBe(background);
    },
  );

  it.each([
    ["list", "- Imaginary item\n".repeat(3000), "li", 3000],
    [
      "table",
      "| A | B |\n| --- | --- |\n" +
        "| Imaginary | cell | overflow |\n".repeat(3000),
      "td",
      9000,
    ],
    ["paragraph", "`Imaginary` ".repeat(6000), "code", 6000],
  ])(
    "yields inside one large %s, preserving its whole structure and spelling",
    (_kind, text, selector, count) => {
      vi.useFakeTimers();
      const { container } = render(<Mounted texts={[text]} />);
      act(() => vi.advanceTimersToNextTimer());
      const first = container.querySelector(selector);
      expect(first).not.toBeNull();
      expect(first).not.toBeVisible();
      expect(container.querySelectorAll(selector).length).toBeLessThan(
        Number(count),
      );
      expect(vi.getTimerCount()).toBeGreaterThan(0);
      act(() => vi.runAllTimers());
      expect(container.querySelectorAll(selector)).toHaveLength(Number(count));
      expect(container.querySelector(selector)).toBe(first);
      expect(first).toBeVisible();
      if (selector === "td") {
        expect(container.querySelectorAll("tbody tr")).toHaveLength(3000);
        expect(
          container.querySelector("tbody tr:last-child td:last-child")
            ?.textContent,
        ).toBe("overflow");
        expect(container.querySelectorAll("th")).toHaveLength(2);
      } else if (selector === "code") {
        expect(container.querySelectorAll("p")).toHaveLength(1);
        expect(container.querySelector("p")?.textContent).toBe(
          "Imaginary ".repeat(6000).trimEnd(),
        );
      } else {
        expect(container.querySelectorAll("ul")).toHaveLength(1);
        expect(container.querySelector("li:last-child")?.textContent).toBe(
          "Imaginary item",
        );
      }
    },
    20000,
  );

  it("keeps duplicate occurrences in the whole-value marker budget and labels each remaining marker", () => {
    vi.useFakeTimers();
    const text = "**Imaginary&#8203;**";
    const many = Array.from({ length: 1001 }, () => text);
    const { container, unmount } = render(<Mounted texts={many} />);
    act(() => vi.runAllTimers());
    expect(container.querySelectorAll("strong")).toHaveLength(1001);
    expect(container.querySelectorAll('[role="img"]')).toHaveLength(0);
    expect(container.textContent).toBe("Imaginary".repeat(1001));
    unmount();
    const single = render(<Mounted texts={[text, text]} />).container;
    act(() => vi.runAllTimers());
    expect(single.querySelectorAll('[role="img"]')).toHaveLength(2);
    expect(single.querySelector("strong")?.textContent).toBe("ImaginaryU+200B");
    expect(
      single.querySelector('[role="img"]')?.getAttribute("title"),
    ).toContain("U+200B");
  }, 20000);
});
