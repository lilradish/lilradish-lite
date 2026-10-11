import { ThemeProvider } from "@mui/material/styles";
import { act, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { theme } from "../theme/theme";
import { HiddenShown, HiddenShownAcross } from "./HiddenShown";
import { FormattedProseAcross, ModelProse, ProseScope } from "./ModelProse";
import { prepareProseTrees } from "./prosePreparation";

const { readFileSync } = await vi.importActual<{
  readFileSync(path: string, encoding: "utf8"): string;
}>("node:fs");
const { dirname, join } = await vi.importActual<{
  dirname(path: string): string;
  join(...paths: string[]): string;
}>("node:path");
const { fileURLToPath } = await vi.importActual<{
  fileURLToPath(url: string): string;
}>("node:url");
const markdownCss = readFileSync(
  join(dirname(fileURLToPath(import.meta.url)), "modelProseMarkdown.css"),
  "utf8",
);
const tokenCss = readFileSync(
  join(
    dirname(fileURLToPath(import.meta.url)),
    "../theme/tokens.generated.css",
  ),
  "utf8",
);

let markdownStyle: HTMLStyleElement;
beforeEach(() => {
  markdownStyle = document.createElement("style");
  // jsdom does not load a relative @import in an inline style. Draw its real
  // dependency too, while checking the production import remains connected.
  expect(markdownCss).toContain('@import "../theme/tokens.generated.css";');
  markdownStyle.textContent =
    tokenCss +
    markdownCss.replace('@import "../theme/tokens.generated.css";', "");
  document.head.append(markdownStyle);
});
afterEach(() => {
  markdownStyle.remove();
  vi.useRealTimers();
});

class PendingReader {
  static instances: PendingReader[] = [];
  onmessage: ((event: MessageEvent) => void) | null = null;
  onerror: (() => void) | null = null;
  onmessageerror: (() => void) | null = null;
  terminate = vi.fn();
  request?: { texts: string[]; formattedTexts: string[] };
  constructor() {
    PendingReader.instances.push(this);
  }
  postMessage(request: { texts: string[]; formattedTexts: string[] }) {
    this.request = request;
  }
  finish() {
    this.onmessage?.({
      data: {
        prepared: prepareProseTrees(
          this.request!.texts,
          this.request!.formattedTexts,
        ),
      },
    } as MessageEvent);
  }
}

function drawn(text: string) {
  return render(
    <ThemeProvider theme={theme}>
      <HiddenShownAcross value={text}>
        <ModelProse
          text={text}
          plain={
            <bdi style={{ whiteSpace: "pre-wrap" }}>
              <HiddenShown text={text} />
            </bdi>
          }
        />
      </HiddenShownAcross>
    </ThemeProvider>,
  );
}

/** jsdom keeps var() in computed styles; follow the actual emitted definition. */
function roleStyle(element: Element, property: string): string {
  const value = getComputedStyle(element).getPropertyValue(property);
  const reference = /^var\((--[\w-]+)\)$/.exec(value);
  if (reference === null) return value;
  const defined = getComputedStyle(document.documentElement)
    .getPropertyValue(reference[1])
    .trim();
  expect(
    defined,
    `${reference[1]} must be present in the emitted stylesheet`,
  ).not.toBe("");
  return defined;
}

describe("ModelProse", () => {
  it.each([
    ["---\nname: Imaginary cargo\n---\n", "# Body\n**Keep this**"],
    ["---\r\nname: Imaginary cargo\r\n---\r\n", "# Body\r\n**Keep this**"],
    ["---\n---", ""],
    ["---\n---\n", "# Body\n**Keep this**"],
    ["---\nname: Imaginary cargo\n---", ""],
  ])(
    "keeps a closed leading block exactly before its formatted body: %s",
    (prefix, body) => {
      const text = prefix + body;
      const { container } = drawn(text);
      const literal = container.querySelector("pre.model-prose-front-matter")!;
      expect(literal.querySelector("code")?.textContent).toBe(prefix);
      expect(literal.querySelectorAll("h1,h2,strong,hr")).toHaveLength(0);
      expect(roleStyle(literal, "font-size")).toBe("0.78125rem");
      expect(roleStyle(literal, "line-height")).toBe("1.25rem");
      expect(roleStyle(literal, "font-weight")).toBe("400");
      expect(roleStyle(literal, "font-family")).toBe("ui-monospace,monospace");
      expect(getComputedStyle(literal).backgroundColor).toBe(
        "var(--lr-code-ground)",
      );
      expect(roleStyle(literal.querySelector("code")!, "font-size")).toBe(
        "100%",
      );
      if (body !== "") {
        expect(literal.nextElementSibling?.tagName).toBe("H1");
        expect(container.querySelector("h1")?.textContent).toBe("Body");
        expect(container.querySelector("strong")?.textContent).toBe(
          "Keep this",
        );
      } else expect(container.querySelectorAll("h1,strong")).toHaveLength(0);
      fireEvent.click(screen.getByRole("button", { name: "Show as written" }));
      expect(container.querySelector("bdi")?.textContent).toBe(text);
      expect(container.querySelector("pre")).toBeNull();
      fireEvent.click(screen.getByRole("button", { name: "Show formatted" }));
      expect(container.querySelector("pre code")?.textContent).toBe(prefix);
    },
  );

  it.each([
    "---\nname: Imaginary cargo",
    "---\nname: Imaginary cargo\n--- ",
    " ---\nname: Imaginary cargo\n---",
    "--- \nname: Imaginary cargo\n---",
    "----\nname: Imaginary cargo\n---",
    "\n---\nname: Imaginary cargo\n---",
    "\ufeff---\nname: Imaginary cargo\n---",
  ])(
    "leaves unclosed or non-leading delimiter spellings in ordinary Markdown: %s",
    (text) => {
      const { container } = drawn(text);
      expect(container.querySelector(".model-prose-front-matter")).toBeNull();
      expect(container.querySelector(".model-prose-markdown")).not.toBeNull();
      if (text.startsWith("\ufeff"))
        expect(container.querySelector('[role="img"]')?.textContent).toBe(
          "U+FEFF",
        );
    },
  );

  it("keeps leading definitions, entities, HTML and code literal without lending references to the body", () => {
    const prefix =
      "---\n[ref]: https://example.invalid/secret\n&#8203; **Bold** `code` <img src=x>\n---\n";
    const { container } = drawn(
      prefix + "[Unresolved][ref]\n\n**Body&#8203;**\n\n---",
    );
    expect(container.querySelector("pre code")?.textContent).toBe(prefix);
    expect(container.querySelector("p")?.textContent).toBe("[Unresolved][ref]");
    expect(container.querySelector("strong")?.textContent).toBe("BodyU+200B");
    expect(container.querySelectorAll('[role="img"]')).toHaveLength(1);
    expect(container.querySelectorAll("a,img,iframe")).toHaveLength(0);
    expect(container.querySelectorAll("hr")).toHaveLength(1);
  });

  it("counts hidden characters in the prefix, decoded body and duplicate values together", () => {
    const text =
      "---\n" + "a\u200b".repeat(251) + "\n---\n" + "b&#8203;".repeat(250);
    const { container } = render(
      <FormattedProseAcross value={[text, text]}>
        <ModelProse text={text} plain={<HiddenShown text={text} />} />
        <ModelProse text={text} plain={<HiddenShown text={text} />} />
      </FormattedProseAcross>,
    );
    expect(container.querySelectorAll("pre")).toHaveLength(2);
    expect(container.querySelectorAll('[role="img"]')).toHaveLength(0);
    expect(container.textContent).toContain(
      "1,002 invisible or text-direction characters",
    );
    expect(container.textContent).not.toContain("\u200b");
    expect(container.textContent).not.toContain("&#8203;");
  });

  it("marks actual hidden characters in the leading literal while leaving entity spelling intact", () => {
    const { container } = drawn("---\n&#8203; a\u200b\u202e\n---\nBody");
    expect(container.querySelector("pre code")?.textContent).toBe(
      "---\n&#8203; aU+200BU+202E\n---\n",
    );
    expect(container.querySelectorAll('pre [role="img"]')).toHaveLength(2);
    expect(container.querySelector("p")?.textContent).toBe("Body");
  });

  it.each(["unclosed prefix", "body", "non-leading prefix"])(
    "keeps the whole-value depth guard when the %s is too deep",
    (part) => {
      const deep = "> ".repeat(33) + "Imaginary quotation";
      const text =
        part === "unclosed prefix"
          ? `---\n${deep}\nBody`
          : part === "non-leading prefix"
            ? `\ufeff---\n${deep}\n---\nBody`
            : `---\nname: Imaginary\n---\n${deep}`;
      expect(prepareProseTrees([text], [text]).trees.size).toBe(0);
      const { container } = drawn(text);
      expect(container.querySelector("bdi")?.textContent).toBe(
        text.replace("\ufeff", "U+FEFF"),
      );
      expect(container.querySelectorAll("pre,blockquote")).toHaveLength(0);
      expect(screen.queryByRole("button")).toBeNull();
    },
  );

  it.each([">".repeat(33) + " metadata", "*".repeat(66)])(
    "formats the shallow body after a raw prefix containing %s",
    (metadata) => {
      const prefix = `---\n${metadata}\n---\n`;
      const text = prefix + "**body**";
      expect(prepareProseTrees([text], [text]).trees.size).toBe(1);
      const { container } = drawn(text);
      expect(container.querySelector("pre code")?.textContent).toBe(prefix);
      expect(container.querySelector("strong")?.textContent).toBe("body");
      expect(container.querySelectorAll("blockquote,em")).toHaveLength(0);
      fireEvent.click(screen.getByRole("button", { name: "Show as written" }));
      expect(container.querySelector("bdi")?.textContent).toBe(text);
      fireEvent.click(screen.getByRole("button", { name: "Show formatted" }));
      expect(container.querySelector("pre code")?.textContent).toBe(prefix);
      expect(container.querySelector("strong")?.textContent).toBe("body");
    },
  );

  it("keeps raw prefix punctuation and hidden characters through background preparation", () => {
    vi.useFakeTimers();
    PendingReader.instances = [];
    vi.stubGlobal("Worker", PendingReader);
    const prefix = "---\r\n" + ">".repeat(33) + " a\u200b\r\n---\r\n";
    const text = prefix + "**body&#8203;**\n" + "Details ".repeat(32768);
    const { container } = drawn(text);
    expect(PendingReader.instances).toHaveLength(1);
    act(() => PendingReader.instances[0]!.finish());
    act(() => vi.runAllTimers());
    expect(container.querySelector("pre code")?.textContent).toBe(
      prefix.replace("\u200b", "U+200B"),
    );
    expect(container.querySelector("strong")?.textContent).toBe("bodyU+200B");
    expect(container.querySelectorAll('[role="img"]')).toHaveLength(2);
    fireEvent.click(screen.getByRole("button", { name: "Show as written" }));
    act(() => PendingReader.instances.at(-1)!.finish());
    act(() => vi.runAllTimers());
    expect(container.querySelector("bdi")?.textContent).toBe(
      text.replaceAll("\u200b", "U+200B"),
    );
  });

  it("replaces an incomplete background read with a closed prefix, keeps staging hidden and ignores the old completion", () => {
    vi.useFakeTimers();
    PendingReader.instances = [];
    vi.stubGlobal("Worker", PendingReader);
    const incomplete =
      "---\nname: Imaginary cargo\n" + "Details ".repeat(32768);
    const prefix = incomplete + "\n---\n";
    const complete = prefix + "# Body\n\n" + "x\n\n".repeat(300);
    const contents = (text: string) => (
      <ModelProse text={text} plain={<bdi>{text}</bdi>} />
    );
    const { container, rerender, unmount } = render(contents(incomplete));
    const old = PendingReader.instances[0]!;
    rerender(contents(complete));
    const current = PendingReader.instances.at(-1)!;
    expect(old.terminate).toHaveBeenCalled();
    act(() => old.finish());
    expect(container.querySelector("pre")).toBeNull();
    act(() => current.finish());
    act(() => vi.advanceTimersToNextTimer());
    const literal = container.querySelector("pre")!;
    expect(literal).not.toBeVisible();
    expect(literal.querySelector("code")?.textContent).toBe(prefix);
    expect(container.querySelectorAll("p").length).toBeLessThan(300);
    act(() => vi.runAllTimers());
    expect(literal).toBeVisible();
    expect(container.querySelector("pre")).toBe(literal);
    expect(container.querySelector("h1")?.textContent).toBe("Body");
    expect(container.querySelectorAll("p")).toHaveLength(300);
    rerender(contents(incomplete));
    act(() => PendingReader.instances.at(-1)!.finish());
    act(() => vi.advanceTimersToNextTimer());
    unmount();
    expect(vi.getTimerCount()).toBe(0);
    expect(container.childNodes).toHaveLength(0);
  });

  it("falls back to the exact whole closed value when background preparation fails", () => {
    PendingReader.instances = [];
    vi.stubGlobal("Worker", PendingReader);
    const text =
      "---\nname: Imaginary cargo\n---\n# Body\n" + "Details ".repeat(32768);
    const { container } = drawn(text);
    act(() =>
      PendingReader.instances[0]!.onmessage?.({
        data: { failed: true },
      } as MessageEvent),
    );
    expect(container.querySelector("bdi")?.textContent).toBe(text);
    expect(container.querySelector("pre")).toBeNull();
    expect(
      screen.getByText("Shown as written: it couldn't be formatted here."),
    ).toBeInTheDocument();
    expect(screen.queryByRole("button")).toBeNull();
  });
  it("uses six closer heading sizes only inside a value card", () => {
    const text =
      "# First\n\n## Second\n\n### Third\n\n#### Fourth\n\n##### Fifth\n\n###### Sixth";
    const { container } = render(
      <ThemeProvider theme={theme}>
        <div data-value-content>
          <ModelProse text={text} plain={<bdi>{text}</bdi>} />
        </div>
        <ModelProse text="# Outside" plain={<bdi># Outside</bdi>} />
      </ThemeProvider>,
    );
    const card = container.querySelector("[data-value-content]")!;
    expect(
      [...card.querySelectorAll("h1,h2,h3,h4,h5,h6")].map((heading) =>
        roleStyle(heading, "font-size"),
      ),
    ).toEqual([
      "1.25rem",
      "1.0625rem",
      "0.9375rem",
      "0.875rem",
      "0.8125rem",
      "0.75rem",
    ]);
    expect(roleStyle(card.querySelector("h1")!, "line-height")).toBe("1.75rem");
    expect(roleStyle(card.querySelector("h2")!, "line-height")).toBe("1.5rem");
    expect(roleStyle(card.querySelector("h6")!, "color")).toBe("#58666c");
    expect(
      roleStyle([...container.querySelectorAll("h1")].at(-1)!, "font-size"),
    ).toBe("1.75rem");
  });

  it("shows a value card's background wait only after 300ms and cancels it when shown as written", () => {
    vi.useFakeTimers();
    PendingReader.instances = [];
    vi.stubGlobal("Worker", PendingReader);
    const text = "# Imaginary long record\n" + "Details ".repeat(32768);
    const { container } = render(
      <ThemeProvider theme={theme}>
        <ProseScope independent valueCard>
          <ModelProse text={text} plain={<bdi>{text}</bdi>} />
        </ProseScope>
      </ThemeProvider>,
    );
    expect(PendingReader.instances).toHaveLength(1);
    expect(screen.queryByText(/Loading|Still reading/)).toBeNull();
    act(() => vi.advanceTimersByTime(299));
    expect(screen.queryByText(/Still reading/)).toBeNull();
    act(() => vi.advanceTimersByTime(1));
    expect(screen.getByText("Still reading\u2026")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Show as written" }));
    expect(container.querySelector("bdi")?.textContent).toBe(text);
    expect(screen.queryByText(/Still reading/)).toBeNull();
    expect(PendingReader.instances[0]!.terminate).toHaveBeenCalled();
    fireEvent.click(screen.getByRole("button", { name: "Show formatted" }));
    expect(PendingReader.instances).toHaveLength(2);
    expect(screen.queryByText(/Still reading/)).toBeNull();
    act(() => PendingReader.instances[1]!.finish());
    act(() => vi.runAllTimers());
    expect(screen.queryByText(/Still reading/)).toBeNull();
    expect(container.querySelector("h1")).toHaveTextContent(
      "Imaginary long record",
    );
  });

  it("cancels a value card's waiting timer and worker when access is lost", () => {
    vi.useFakeTimers();
    PendingReader.instances = [];
    vi.stubGlobal("Worker", PendingReader);
    const text =
      "---\nname: Imaginary private cargo\n---\n# Imaginary private record\n" +
      "Details ".repeat(32768);
    const contents = (allowed: boolean) => (
      <ThemeProvider theme={theme}>
        <ProseScope independent valueCard>
          {allowed ? (
            <ModelProse text={text} plain={<bdi>{text}</bdi>} />
          ) : (
            <span>Private value withheld.</span>
          )}
        </ProseScope>
      </ThemeProvider>
    );
    const { container, rerender } = render(contents(true));
    const worker = PendingReader.instances[0]!;
    rerender(contents(false));
    act(() => vi.runAllTimers());
    expect(worker.terminate).toHaveBeenCalled();
    expect(container).toHaveTextContent("Private value withheld.");
    expect(container).not.toHaveTextContent(/Still reading|Imaginary private/);
    expect(screen.queryByRole("button")).toBeNull();
    act(() => worker.finish());
    expect(container.querySelector("h1")).toBeNull();
  });

  it("retains a legal thirty-two-level quotation and falls back to the original spelling at thirty-three", () => {
    const quoted = `${"> ".repeat(32)}An invented quotation stays readable.`;
    const view = drawn(quoted);
    expect(view.container.querySelectorAll("blockquote")).toHaveLength(32);
    expect(
      view.container.querySelector(".model-prose-markdown"),
    ).toHaveTextContent("An invented quotation stays readable.");
    fireEvent.click(screen.getByRole("button", { name: "Show as written" }));
    expect(
      view.container.querySelector("[data-value-content]") ?? view.container,
    ).toHaveTextContent(quoted);
    view.unmount();
    const refused = `${"> ".repeat(33)}An invented quotation stays readable.`;
    const fallback = drawn(refused);
    expect(fallback.container.querySelector("blockquote")).toBeNull();
    expect(fallback.container).toHaveTextContent(refused);
  });
  it("uses six Markdown heading sizes and keeps ordinary words and tables within the value surface", () => {
    const { container } = drawn(
      "# First\n\n## Second\n\n### Third\n\n#### Fourth\n\n##### Fifth\n\n###### Sixth\n\nAverylonginventedwordthatstayswhole\n\n| Item | Amount |\n| --- | --- |\n| Imaginary crate | 12 |\n\n---\n\n[x] Done\n\n[] Waiting",
    );
    expect(
      [...container.querySelectorAll("h1, h2, h3, h4, h5, h6")].map((heading) =>
        roleStyle(heading, "font-size"),
      ),
    ).toEqual([
      "1.75rem",
      "1.3125rem",
      "1.09375rem",
      "0.875rem",
      "0.875rem",
      "0.875rem",
    ]);
    const body = container.querySelector(".model-prose-markdown")!;
    expect(getComputedStyle(body).wordBreak).toBe("normal");
    expect(getComputedStyle(body).overflowWrap).toBe("anywhere");
    expect(getComputedStyle(container.querySelector("td")!).overflowWrap).toBe(
      "normal",
    );
    expect(getComputedStyle(container.querySelector("table")!).overflowX).toBe(
      "auto",
    );
    expect(container.querySelector("hr")).not.toBeNull();
    expect(body.textContent).toContain("[x] Done");
    expect(body.textContent).toContain("[] Waiting");
    expect(container.querySelector('input[type="checkbox"]')).toBeNull();
  });
  it("removes converter list separators while retaining authored lines, literal HTML and code", () => {
    const text =
      "# Imaginary notes\n\n- First\n\n  Second\n\n- Third\n\nKeep\nthis line.\n\n<span>\nLiteral evidence\n</span>\n\n```\nCode\nkeeps its lines.\n```";
    const { container } = drawn(text);
    expect(
      [...container.querySelectorAll("li")].map((each) => each.textContent),
    ).toEqual(["FirstSecond", "Third"]);
    for (const list of container.querySelectorAll("ul, li")) {
      expect(
        [...list.childNodes].some(
          (node) =>
            node.nodeType === Node.TEXT_NODE && node.textContent === "\n",
        ),
      ).toBe(false);
    }
    expect(container.querySelector("h1")?.textContent).toBe("Imaginary notes");
    expect(roleStyle(container.querySelector("h1")!, "font-size")).toBe(
      "1.75rem",
    );
    expect(
      container.querySelector(".model-prose-markdown")?.textContent,
    ).toContain("Keep\nthis line.");
    expect(
      container.querySelector(".model-prose-markdown")?.textContent,
    ).toContain("<span>\nLiteral evidence\n</span>");
    expect(container.querySelector("pre code")?.textContent).toBe(
      "Code\nkeeps its lines.\n",
    );
    expect(
      container.querySelector(".model-prose-markdown span span"),
    ).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: "Show as written" }));
    expect(container.querySelector("bdi")?.textContent).toBe(text);
    expect(container.querySelectorAll("h1, ul, pre")).toHaveLength(0);
  });
  it("lets a pending large read show all its writing and then finish formatting", () => {
    vi.useFakeTimers();
    PendingReader.instances = [];
    vi.stubGlobal("Worker", PendingReader);
    const text = "# Imaginary long record\n" + "Details ".repeat(32768);
    const { container } = drawn(text);
    expect(screen.getByText("Loading…")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Show as written" }));
    expect(container.querySelector("bdi")?.textContent).toBe(text);
    fireEvent.click(screen.getByRole("button", { name: "Show formatted" }));
    expect(screen.getByText("Loading…")).toBeInTheDocument();
    act(() => PendingReader.instances[0]!.finish());
    expect(screen.getByText("Loading…")).toBeInTheDocument();
    act(() => vi.runAllTimers());
    expect(screen.queryByText("Loading…")).toBeNull();
    expect(container.querySelector("h1")?.textContent).toBe(
      "Imaginary long record",
    );
    expect(PendingReader.instances[0]!.terminate).toHaveBeenCalled();
  });

  it("ends waiting with the exact original when the worker sends its caught-failure message", () => {
    PendingReader.instances = [];
    vi.stubGlobal("Worker", PendingReader);
    const text = "# Imaginary failed message\n" + "Facts ".repeat(50000);
    const { container } = drawn(text);
    expect(screen.getByText("Loading…")).toBeInTheDocument();
    act(() =>
      PendingReader.instances[0]!.onmessage?.({
        data: { failed: true },
      } as MessageEvent),
    );
    expect(screen.queryByText("Loading…")).toBeNull();
    expect(container.querySelector("bdi")?.textContent).toBe(text);
    expect(
      screen.getByText("Shown as written: it couldn't be formatted here."),
    ).toBeInTheDocument();
    expect(screen.queryByRole("button")).toBeNull();
    expect(container.querySelectorAll("h1,[hidden]")).toHaveLength(0);
    expect(PendingReader.instances[0]!.terminate).toHaveBeenCalled();
  });

  it("keeps every staging fragment inaccessible and reuses its DOM when formatted reading becomes ready", () => {
    vi.useFakeTimers({ toFake: ["setTimeout", "clearTimeout"] });
    PendingReader.instances = [];
    vi.stubGlobal("Worker", PendingReader);
    const text = "# Imaginary dense record\n\n" + "x\n\n".repeat(16000);
    const { container } = drawn(text);
    act(() => PendingReader.instances[0]!.finish());
    act(() => vi.advanceTimersToNextTimer());
    const first = container.querySelector("h1");
    expect(first).not.toBeNull();
    expect(first).not.toBeVisible();
    expect(screen.queryByRole("heading")).toBeNull();
    expect(container.querySelectorAll("p").length).toBeLessThan(16000);
    fireEvent.click(screen.getByRole("button", { name: "Show as written" }));
    expect(container.querySelector("bdi")?.textContent).toBe(text);
    act(() => vi.runAllTimers());
    expect(first).not.toBeVisible();
    const formatted = screen.getByRole("button", { name: "Show formatted" });
    const ready = performance.now();
    fireEvent.click(formatted);
    console.log(
      JSON.stringify({
        stage: "dense-ready-reveal-JSDOM",
        revealMs: performance.now() - ready,
      }),
    );
    expect(screen.queryByText("Loading…")).toBeNull();
    expect(container.querySelector("h1")).toBe(first);
    expect(first).toBeVisible();
    expect(container.querySelectorAll("p")).toHaveLength(16000);
  }, 20000);

  it("removes mounted staging and cancels its unexecuted continuations when the value changes", () => {
    vi.useFakeTimers();
    PendingReader.instances = [];
    vi.stubGlobal("Worker", PendingReader);
    const text = "# Imaginary old dense record\n\n" + "x\n\n".repeat(16000);
    const contents = (value: string) => (
      <ModelProse text={value} plain={<bdi>{value}</bdi>} />
    );
    const { container, rerender, unmount } = render(contents(text));
    act(() => PendingReader.instances[0]!.finish());
    act(() => vi.advanceTimersToNextTimer());
    expect(container.querySelector("[hidden] h1")).not.toBeNull();
    expect(vi.getTimerCount()).toBeGreaterThan(0);
    rerender(contents("# Imaginary new record\nFacts"));
    expect(vi.getTimerCount()).toBe(0);
    act(() => vi.runAllTimers());
    expect(container.textContent).not.toContain("old dense record");
    expect(container.querySelector("h1")?.textContent).toBe(
      "Imaginary new record",
    );
    rerender(contents(text));
    act(() => PendingReader.instances.at(-1)!.finish());
    act(() => vi.advanceTimersToNextTimer());
    unmount();
    expect(vi.getTimerCount()).toBe(0);
    expect(container.childNodes).toHaveLength(0);
  });

  it.each(["onerror", "onmessageerror"] as const)(
    "shows the whole original with the approved reason when background preparation fails through %s",
    (failure) => {
      PendingReader.instances = [];
      vi.stubGlobal("Worker", PendingReader);
      const text = "# Imaginary unlaid record\n" + "Details ".repeat(32768);
      const { container } = drawn(text);
      expect(screen.getByText("Loading…")).toBeInTheDocument();
      act(() => PendingReader.instances[0]![failure]?.());
      expect(screen.queryByText("Loading…")).toBeNull();
      expect(container.querySelector("bdi")?.textContent).toBe(text);
      expect(
        screen.getByText("Shown as written: it couldn't be formatted here."),
      ).toBeInTheDocument();
      expect(screen.queryByRole("button")).toBeNull();
      expect(container.querySelectorAll("h1,[hidden]")).toHaveLength(0);
      expect(PendingReader.instances[0]!.terminate).toHaveBeenCalled();
    },
  );

  it.each(["constructor", "postMessage"] as const)(
    "shows the whole original with the approved reason when background startup fails through %s",
    (failure) => {
      PendingReader.instances = [];
      class FailingReader extends PendingReader {
        constructor() {
          if (failure === "constructor") {
            throw new Error("The background reader could not be constructed.");
          }
          super();
        }
        postMessage() {
          throw new Error(
            "The background reader could not accept its request.",
          );
        }
      }
      vi.stubGlobal("Worker", FailingReader);
      const text = "# Imaginary unread startup\n" + "Details ".repeat(32768);
      const { container } = drawn(text);
      expect(screen.queryByText("Loading…")).toBeNull();
      expect(container.querySelector("bdi")?.textContent).toBe(text);
      expect(
        screen.getByText("Shown as written: it couldn't be formatted here."),
      ).toBeInTheDocument();
      expect(screen.queryByRole("button")).toBeNull();
      expect(container.querySelectorAll("h1,[hidden]")).toHaveLength(0);
      expect(PendingReader.instances).toHaveLength(
        failure === "constructor" ? 0 : 1,
      );
      if (failure === "postMessage") {
        expect(PendingReader.instances[0]!.terminate).toHaveBeenCalled();
      }
    },
  );

  it("cancels a replaced or unmounted read and ignores its old completion", () => {
    PendingReader.instances = [];
    vi.stubGlobal("Worker", PendingReader);
    const text = "# Imaginary old record\n" + "Details ".repeat(32768);
    const contents = (value: string) => (
      <ThemeProvider theme={theme}>
        <ModelProse text={value} plain={<bdi>{value}</bdi>} />
      </ThemeProvider>
    );
    const { container, rerender, unmount } = render(contents(text));
    const old = PendingReader.instances[0]!;
    rerender(contents("# Imaginary new record\nNew facts"));
    expect(old.terminate).toHaveBeenCalled();
    act(() => old.finish());
    expect(container.querySelector("h1")?.textContent).toBe(
      "Imaginary new record",
    );
    expect(container.textContent).not.toContain("Imaginary old record");
    rerender(contents(text));
    const later = PendingReader.instances.at(-1)!;
    unmount();
    expect(later.terminate).toHaveBeenCalled();
  });

  it("keeps a sparse table linear in its actual cells, including every overflowing cell", () => {
    const rows = 400;
    const text =
      "| A | B |\n| --- | --- |\n" +
      Array.from({ length: rows - 1 }, () => "| item |").join("\n") +
      "\n| " +
      Array.from({ length: rows }, (_, at) => `imaginary-${at}`).join(" | ") +
      " |";
    const { container } = drawn(text);
    expect(container.querySelectorAll("th,td")).toHaveLength(
      2 + rows - 1 + rows,
    );
    expect(container.querySelectorAll("tbody tr")).toHaveLength(rows);
    expect(
      container.querySelector("tbody tr:last-child td:last-child")?.textContent,
    ).toBe("imaginary-399");
  });

  it.each([
    ["\ufeff**Visible**", 1, 1],
    ["**A**\u200dB", 0, 0],
    ["**A**\u200dB\n\n\u200dB", 1, 1],
    ["**A** \u200dB", 1, 1],
    ["**A**\u200d\u200dB", 0, 0],
    ["**A**\u200d\u200d\u200dB", 1, 1],
  ])(
    "judges actual inline adjacency without merging independent paragraphs: %s",
    (text, formatted, written) => {
      const { container } = drawn(text);
      expect(container.querySelectorAll('[role="img"]')).toHaveLength(
        formatted,
      );
      if (text.startsWith("\ufeff")) {
        expect(container.querySelector("p")?.textContent).toBe("U+FEFFVisible");
        expect(container.querySelector("strong")?.textContent).toBe("Visible");
      }
      fireEvent.click(screen.getByRole("button", { name: "Show as written" }));
      expect(container.querySelectorAll('[role="img"]')).toHaveLength(written);
    },
  );

  it("switches a whole nested reading once and resets on reopening without remounting its controls", () => {
    const contents = (open: boolean) => (
      <ThemeProvider theme={theme}>
        <ProseScope resetKey={open}>
          <ModelProse
            text="# Imaginary first\nDetails"
            plain={<bdi># Imaginary first\nDetails</bdi>}
          />
          <ProseScope>
            <ModelProse
              text="## Imaginary second\nDetails"
              plain={<bdi>## Imaginary second\nDetails</bdi>}
            />
          </ProseScope>
        </ProseScope>
      </ThemeProvider>
    );
    const { container, rerender } = render(contents(false));
    expect(
      screen.getAllByRole("button", { name: "Show as written" }),
    ).toHaveLength(1);
    fireEvent.click(screen.getByRole("button", { name: "Show as written" }));
    expect(container.querySelectorAll("h1,h2")).toHaveLength(0);
    expect(container.querySelectorAll("bdi")).toHaveLength(2);
    rerender(contents(true));
    expect(container.querySelectorAll("h1,h2")).toHaveLength(2);
    expect(screen.queryByRole("button", { name: "Show formatted" })).toBeNull();
    expect(
      screen.getAllByRole("button", { name: "Show as written" }),
    ).toHaveLength(1);
  });
  it("lays out business prose with GFM and preserves ordinary line breaks", () => {
    const text =
      "# Imaginary Mooncrate claims\n\nFirst line\nSecond line\n\n- **Keep** the box\n- ~~Discard~~ it\n\n> Photograph it\n\n---\n\nUse `DEMO-CLAIM-17`.\n\n| Item | Invented fee |\n| --- | --- |\n| Box | 12 pretend credits |";
    const { container } = drawn(text);
    expect(
      screen.getByRole("heading", { name: "Imaginary Mooncrate claims" }),
    ).toBeInTheDocument();
    expect(container.querySelector("p")?.textContent).toBe(
      "First line\nSecond line",
    );
    expect(container.querySelector("strong")?.textContent).toBe("Keep");
    expect(container.querySelector("del")?.textContent).toBe("Discard");
    expect(container.querySelector("blockquote")?.textContent).toContain(
      "Photograph it",
    );
    expect(container.querySelectorAll("hr")).toHaveLength(1);
    expect(container.querySelector("code")?.textContent).toBe("DEMO-CLAIM-17");
    expect(container.querySelectorAll("td")).toHaveLength(2);
    expect(container.querySelectorAll("a,img,input")).toHaveLength(0);
    expect(container.querySelector("bdi")).toBeNull();
  });

  it("keeps all addresses, titles, definitions, HTML, code information and extra cells in their original positions without requests", () => {
    const fetch = vi.spyOn(globalThis, "fetch");
    const text =
      '[guide](https://example.com/claims "Imaginary guide")\nhttps://example.net/status\n[https://example.org/full](https://example.org/full)\n[mail](mailto:demo@example.invalid)\n[local](file:///imaginary/path)\n[script](javascript:alert(1))\n![diagram](https://example.com/image.png "Imaginary diagram")\n\n[ref][rules]\n\n[rules]: https://example.com/rules "Rule title"\n\n<iframe src="https://example.com/frame"></iframe>\n\n```fictional-language extra-info\nclaim = 17\n```\n\n| A | B |\n| --- | --- |\n| first | second | third |\n\n- [x] Checked\n\n[^note]\n\n[^note]: Imaginary note';
    try {
      const { container } = drawn(text);
      expect(container.textContent).toContain(
        "guide (https://example.com/claims) (Imaginary guide)",
      );
      expect(
        container.textContent?.match(/https:\/\/example\.org\/full/g),
      ).toHaveLength(1);
      for (const content of [
        "https://example.net/status",
        "mailto:demo@example.invalid",
        "file:///imaginary/path",
        "javascript:alert(1)",
        "diagram (https://example.com/image.png) (Imaginary diagram)",
        '[rules]: https://example.com/rules "Rule title"',
        '<iframe src="https://example.com/frame"></iframe>',
        "fictional-language extra-info",
        "[x] Checked",
        "[^note]: Imaginary note",
      ])
        expect(container.textContent).toContain(content);
      expect(
        container.querySelector("pre")?.previousElementSibling?.textContent,
      ).toBe("fictional-language extra-info");
      expect(
        [...container.querySelectorAll("tbody td")].map(
          (cell) => cell.textContent,
        ),
      ).toEqual(["first", "second", "third"]);
      expect(container.textContent!.indexOf("[rules]:")).toBeLessThan(
        container.textContent!.indexOf("<iframe"),
      );
      expect(
        container.querySelectorAll("a,img,iframe,input,script"),
      ).toHaveLength(0);
      expect(fetch).not.toHaveBeenCalled();
    } finally {
      fetch.mockRestore();
    }
  });

  it("marks literal and decoded hidden characters inside the formatted text where each stands", () => {
    const text = `before${String.fromCodePoint(0x200b)}after **left&#8203;right**\n\n[guide](https://example.com/a "title${String.fromCodePoint(0x202e)}end")`;
    const { container } = drawn(text);
    expect(container.querySelector("p")?.textContent).toBe(
      "beforeU+200Bafter leftU+200Bright",
    );
    expect(container.querySelector("strong")?.textContent).toBe(
      "leftU+200Bright",
    );
    expect(container.textContent).toContain("titleU+202Eend");
    expect(container.querySelectorAll('[role="img"]')).toHaveLength(3);
    expect(container.textContent).not.toContain(String.fromCodePoint(0x200b));
    expect(container.textContent).not.toContain(String.fromCodePoint(0x202e));
    expect(container.querySelectorAll("a,img")).toHaveLength(0);
  });

  it("applies the marker bound to all decoded fragments together", () => {
    const text = Array.from(
      { length: 1001 },
      (_, at) => `**item${at}&#8203;**`,
    ).join("\n\n");
    const { container } = drawn(text);
    expect(container.textContent).toContain(
      "1,001 invisible or text-direction characters",
    );
    expect(container.querySelectorAll("strong")).toHaveLength(1001);
    expect(container.querySelectorAll('[role="img"]')).toHaveLength(0);
    expect(container.textContent).not.toContain(String.fromCodePoint(0x200b));
    expect(container.textContent).not.toContain("U+200B");
  });

  it.each([
    "> ".repeat(33) + "Imaginary policy",
    "*".repeat(33) + "Imaginary policy" + "*".repeat(33),
  ])(
    "shows the whole value as written with a reason when the depth exceeds 32",
    (text) => {
      const { container } = drawn(text);
      expect(container.querySelector("bdi")?.textContent).toBe(text);
      expect(
        screen.getByText(
          "Shown as written: its lists, quotes or emphasis are nested more than 32 levels deep, too deep to format.",
        ),
      ).toBeInTheDocument();
      expect(screen.queryByRole("button")).toBeNull();
      expect(container.querySelectorAll("blockquote,em,strong")).toHaveLength(
        0,
      );
    },
  );

  it("switches each value independently and starts formatted when opened again", () => {
    const first = drawn("# Imaginary first\nSecond line");
    const second = drawn("# Imaginary second\nSecond line");
    fireEvent.click(
      screen.getAllByRole("button", { name: "Show as written" })[0]!,
    );
    expect(first.container.querySelector("bdi")?.textContent).toBe(
      "# Imaginary first\nSecond line",
    );
    expect(first.container.querySelector("h1")).toBeNull();
    expect(second.container.querySelector("h1")?.textContent).toBe(
      "Imaginary second",
    );
    fireEvent.click(screen.getByRole("button", { name: "Show formatted" }));
    expect(first.container.querySelector("h1")?.textContent).toBe(
      "Imaginary first",
    );
    fireEvent.click(
      screen.getAllByRole("button", { name: "Show as written" })[0]!,
    );
    first.unmount();
    const reopened = drawn("# Imaginary first\nSecond line");
    expect(reopened.container.querySelector("h1")?.textContent).toBe(
      "Imaginary first",
    );
    expect(screen.queryByRole("button", { name: "Show formatted" })).toBeNull();
  });
});
