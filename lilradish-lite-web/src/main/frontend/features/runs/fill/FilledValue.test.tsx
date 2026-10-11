import { ThemeProvider } from "@mui/material/styles";
import { act, fireEvent, render, screen, within } from "@testing-library/react";
import { createIntl } from "react-intl";
import { afterEach, describe, expect, it, vi } from "vitest";

import { readFieldsFrom, type ReadField } from "../../../api/filling";
import type { ShownOrKept } from "../../../api/shownValues";
import { theme } from "../../../lib/theme/theme";
import { CurrentFileRunContext } from "./CurrentFileRun";
import * as writing from "../../../lib/filling/writing";
import * as prose from "../../../lib/text/proseMarkdown";
import { prepareProseTrees } from "../../../lib/text/prosePreparation";
import {
  FilledFields,
  FilledValue,
  inOneLine,
  labelledInLine,
} from "./FilledValue";

/** The reader's language where a spec names one; the browser's otherwise. */
const reader = vi.hoisted(() => ({
  language: undefined as string | undefined,
}));

vi.mock(import("../../../i18n/intl"), async (importOriginal) => {
  const original = await importOriginal();
  return {
    ...original,
    get readersIntl() {
      return reader.language === undefined
        ? original.readersIntl
        : createIntl({ locale: reader.language, defaultLocale: "en" });
    },
  };
});

afterEach(() => {
  reader.language = undefined;
  vi.useRealTimers();
});

const UNKNOWN = "This page can't show this yet.";

function nearbyCards() {
  const observers: {
    target?: Element;
    notify: IntersectionObserverCallback;
  }[] = [];
  class Observer {
    private readonly entry: (typeof observers)[number];
    constructor(notify: IntersectionObserverCallback) {
      this.entry = { notify };
      observers.push(this.entry);
    }
    observe(target: Element) {
      this.entry.target = target;
    }
    disconnect() {}
  }
  vi.stubGlobal("IntersectionObserver", Observer);
  return (target: Element) =>
    act(() => {
      const observer = observers.find((entry) => entry.target === target)!;
      observer.notify(
        [{ isIntersecting: true, target } as IntersectionObserverEntry],
        {} as IntersectionObserver,
      );
    });
}

describe("declared value-card names", () => {
  it("gives every top-level item its state and foot without passing them to nested field cards", () => {
    render(
      <ThemeProvider theme={theme}>
        <FilledValue
          cards
          shut={false}
          label="Reports"
          field={{
            name: "reports",
            kind: "fields",
            most: 2,
            mustBeGiven: false,
            fields: [{ name: "detail", kind: "text", mustBeGiven: false }],
          }}
          shown={{
            value: [{ detail: "First fact" }, { detail: "Second fact" }],
          }}
          status="refused"
          foot={<span>Inventory needs checking.</span>}
        />
      </ThemeProvider>,
    );
    const top = [...document.querySelectorAll("section")].filter(
      (card) => card.parentElement?.closest("section") === null,
    );
    expect(top).toHaveLength(2);
    for (const card of top) {
      expect(card).toHaveAccessibleDescription("Rejected in review");
      expect(card).toHaveTextContent("Inventory needs checking.");
      const nested = card.querySelector("section")!;
      expect(nested).not.toHaveAttribute("aria-describedby");
      expect(nested).not.toHaveTextContent(
        /Inventory needs checking|Rejected in review/,
      );
    }
  });

  it.each([
    [undefined, "\u2068topic\u2069"],
    ["topic", "\u2068topic\u2069"],
    ["Topic", "\u2068Topic\u2069 · topic"],
    ["\u2067Topic\u2069", "\u2068\u2067Topic\u2069\u2069 · topic"],
  ] as const)(
    "reads legal wire label %s with its binding name only where different",
    (label, title) => {
      const fields = readFieldsFrom([
        {
          name: "topic",
          kind: "text",
          mustBeGiven: false,
          lines: "one",
          longest: 512,
          ...(label === undefined ? {} : { label }),
        },
      ]);
      expect(fields).not.toBeNull();
      const { container } = render(
        <ThemeProvider theme={theme}>
          <FilledFields
            fields={fields!}
            values={{ topic: "A shipment" }}
            shut={false}
            cards
          />
        </ThemeProvider>,
      );
      const card = container.querySelector("section")!;
      const titleNode = card.querySelector('[role="term"]')!;
      expect(titleNode.textContent).toBe(title);
      expect(screen.getByText("A shipment")).toBeVisible();
      expect(card.querySelectorAll("code")).toHaveLength(
        label === undefined || label === "topic" ? 0 : 1,
      );
    },
  );
});

const UNREADABLE = "This page can't read this value.";

const EARLIER_SHAPE =
  "Shown as originally given; it no longer matches the field's current format.";

function field(kind: string, more: Partial<ReadField> = {}): ReadField {
  return { name: kind, kind, mustBeGiven: false, ...more };
}

function drawing(shown: ShownOrKept, of?: ReadField, shut = true) {
  return render(
    <ThemeProvider theme={theme}>
      <FilledValue field={of} label="Notes" shown={shown} shut={shut} />
    </ThemeProvider>,
  ).container;
}

const RIGHT_TO_LEFT_OVERRIDE = String.fromCodePoint(0x202e);

const ZERO_WIDTH_SPACE = String.fromCodePoint(0x200b);

/** Each a marker, so that many of them in a row need as many markers. */
const markedApart = (markers: number) => `a${ZERO_WIDTH_SPACE}`.repeat(markers);

const setApart = (words: string) =>
  `${String.fromCodePoint(0x2068)}${words}${String.fromCodePoint(0x2069)}`;

const FILE_FIELD = field("file", {
  types: [".pdf", ".txt"],
  maximumBytes: 12,
});

describe("opened prose preparation", () => {
  it("preserves an explicit list role and ordered item labels on bullet-free many value cards", () => {
    const { container } = render(
      <ThemeProvider theme={theme}>
        <FilledValue
          cards
          shut={false}
          label="Reports"
          field={field("text", { most: 2 })}
          shown={{ value: ["First shipment", "Second shipment"] }}
        />
      </ThemeProvider>,
    );
    const list = screen.getByRole("list");
    expect(list.tagName).toBe("UL");
    expect(list).toHaveAttribute("role", "list");
    expect(getComputedStyle(list).listStyle).toBe("none");
    expect(
      [...list.querySelectorAll(":scope > li")].map(
        (item) => item.querySelector("[data-value-content]")?.textContent,
      ),
    ).toEqual(["First shipment", "Second shipment"]);
    expect(
      [...list.querySelectorAll("h3")].map((heading) => heading.textContent),
    ).toEqual(["Reports, item 1", "Reports, item 2"]);
    expect(container.querySelectorAll("ul")).toHaveLength(1);
  });

  it("prepares only a nearby member of a closed many value, with the whole value still supplying its character budget", async () => {
    const approach = nearbyCards();
    const jobs: {
      texts: readonly string[];
      formattedTexts: readonly string[];
    }[] = [];
    class Reader {
      onmessage = null;
      onerror = null;
      onmessageerror = null;
      postMessage(payload: (typeof jobs)[number]) {
        jobs.push(payload);
      }
      terminate() {}
    }
    vi.stubGlobal("Worker", Reader);
    try {
      const texts = ["a", "b", "c"].map((letter) => letter.repeat(2097152));
      const { container } = render(
        <ThemeProvider theme={theme}>
          <FilledValue
            cards
            shut
            label="Reports"
            field={field("text", {
              lines: "several",
              most: 3,
              longest: 2097152,
            })}
            shown={{ value: texts }}
          />
        </ThemeProvider>,
      );
      await act(async () => {});
      expect(jobs).toEqual([]);
      expect(container.querySelectorAll("[data-value-content]")).toHaveLength(
        3,
      );
      expect(
        [...container.querySelectorAll("[data-value-content]")].map(
          (value) => value.textContent,
        ),
      ).toEqual(["", "", ""]);
      expect(
        screen.queryByRole("button", { name: "Show as written" }),
      ).toBeNull();
      approach(container.querySelectorAll("[data-value-content]")[1]!);
      await act(async () => {});
      expect(jobs.length).toBeGreaterThan(0);
      expect(jobs.at(-1)).toEqual({ texts, formattedTexts: [texts[1]] });
      expect(
        screen.getAllByRole("button", { name: "Show as written" }),
      ).toHaveLength(1);
      expect(container.textContent).not.toContain("Still reading");
    } finally {
      vi.unstubAllGlobals();
    }
  });

  it("mounts formatted content only for a nearby card and keeps its preview when shut again", () => {
    const approach = nearbyCards();
    vi.spyOn(Element.prototype, "getBoundingClientRect").mockImplementation(
      function (this: Element) {
        return new DOMRect(
          0,
          0,
          200,
          this.parentElement?.hasAttribute("data-value-content") ? 400 : 0,
        );
      },
    );
    const texts = Array.from(
      { length: 80 },
      (_, index) => `**Item ${index}** ${"x".repeat(1000)}`,
    );
    const { container } = render(
      <ThemeProvider theme={theme}>
        <FilledValue
          cards
          shut
          label="Reports"
          field={field("text", { lines: "several", most: 80 })}
          shown={{ value: texts }}
        />
      </ThemeProvider>,
    );
    expect(container.querySelectorAll("strong")).toHaveLength(0);
    expect(container.querySelectorAll("[data-value-content]")).toHaveLength(80);
    expect(screen.queryByRole("button", { name: "Show all" })).toBeNull();
    const nearby = container.querySelectorAll("[data-value-content]")[27]!;
    approach(nearby);
    expect(container.querySelectorAll("strong")).toHaveLength(1);
    expect(container.querySelector("strong")).toHaveTextContent("Item 27");
    const card = within(nearby.closest("section")!);
    // Under jsdom, naming each of the 80 copy buttons costs a computed style apiece: only the card's own are named,
    // and across the page the one collapsed toggle is found by its state alone.
    const theOneCollapsed = () => {
      const collapsed = screen.getAllByRole("button", { expanded: false });
      expect(collapsed).toHaveLength(1);
      return collapsed[0];
    };
    expect(theOneCollapsed()).toBe(
      card.getByRole("button", { name: "Show all" }),
    );
    fireEvent.click(card.getByRole("button", { name: "Show all" }));
    fireEvent.click(card.getByRole("button", { name: "Show less" }));
    expect(container.querySelectorAll("strong")).toHaveLength(1);
    expect(card.getByRole("button", { name: "Show all" })).toHaveAttribute(
      "aria-expanded",
      "false",
    );
    expect(theOneCollapsed()).toBe(
      card.getByRole("button", { name: "Show all" }),
    );
    vi.unstubAllGlobals();
  });

  it("does not recount a large closed value on its caller's rerender", () => {
    nearbyCards();
    const count = vi.spyOn(writing, "codePointsIn");
    try {
      const text = "x".repeat(2097152);
      const drawn = (
        <ThemeProvider theme={theme}>
          <FilledValue
            cards
            shut
            label="Report"
            field={field("text", { lines: "several" })}
            shown={{ value: text }}
          />
        </ThemeProvider>
      );
      const { rerender } = render(drawn);
      count.mockClear();
      rerender(
        <ThemeProvider theme={theme}>
          <FilledValue
            cards
            shut
            label="Report"
            field={field("text", { lines: "several" })}
            shown={{ value: text }}
          />
        </ThemeProvider>,
      );
      expect(count).not.toHaveBeenCalledWith(text);
      expect(
        screen.queryByRole("button", { name: "Show as written" }),
      ).toBeNull();
      expect(document.querySelector("[data-value-content]")!.textContent).toBe(
        "",
      );
    } finally {
      count.mockRestore();
      vi.unstubAllGlobals();
    }
  });

  it.each([false, true])(
    "keeps several-line text plain through nested fields and many when formatting is disabled, shut %s",
    (shut) => {
      const container = render(
        <FilledValue
          label="Report"
          shown={{ value: { notes: ["**Urgent**", "*Next*"] } }}
          shut={shut}
          formatted={false}
          field={field("fields", {
            fields: [
              field("text", { name: "notes", lines: "several", most: 2 }),
            ],
          })}
        />,
      ).container;

      expect(
        [...container.querySelectorAll("li")].map((item) => item.textContent),
      ).toEqual(["**Urgent**", "*Next*"]);
      expect(container.querySelector("strong, em")).toBeNull();
      expect(screen.queryByRole("button")).toBeNull();
    },
  );
  it("routes adjacent table separators by their actual cells and keeps every empty and overflowing cell", () => {
    vi.useFakeTimers({ toFake: ["setTimeout", "clearTimeout"] });
    let receive: ((event: MessageEvent) => void) | null = null;
    class Reader {
      set onmessage(listener: ((event: MessageEvent) => void) | null) {
        receive = listener;
      }
      postMessage() {}
      terminate() {}
    }
    vi.stubGlobal("Worker", Reader);
    const text = "| A | B |\n| --- | --- |\n" + "|".repeat(16001);
    const prepare = vi.spyOn(prose, "prepareProse");
    const container = drawing(
      { value: text },
      field("text", { lines: "several", longest: 300000 }),
      false,
    );
    console.log(
      JSON.stringify({
        stage: "adjacent-table-before-response-JSDOM",
        characters: text.length,
        synchronousPreparations: prepare.mock.calls.length,
        actualCells: container.querySelectorAll("th,td").length,
      }),
    );
    expect(prepare).not.toHaveBeenCalled();
    expect(screen.getByText("Loading…")).toBeInTheDocument();
    const prepared = prepareProseTrees([text], [text]);
    act(() => receive?.({ data: { prepared } } as MessageEvent));
    act(() => vi.advanceTimersToNextTimer());
    const first = container.querySelector("th");
    expect(first).not.toBeNull();
    expect(first).not.toBeVisible();
    expect(container.querySelectorAll("td").length).toBeLessThan(16000);
    act(() => vi.runAllTimers());
    expect(container.querySelectorAll("td")).toHaveLength(16000);
    expect(container.querySelectorAll("th")).toHaveLength(2);
    expect(container.querySelectorAll("tbody tr")).toHaveLength(1);
    expect(container.querySelector("th")).toBe(first);
    expect(first).toBeVisible();
    expect(
      [...container.querySelectorAll("td")].every(
        (cell) => cell.textContent === "",
      ),
    ).toBe(true);
  }, 20000);
  it.each(["short dense", "dense with long tail", "single large paragraph"])(
    "prepares a legal %s and reveals the same incrementally mounted DOM",
    (kind) => {
      vi.useFakeTimers({ toFake: ["setTimeout", "clearTimeout"] });
      const durations: number[] = [];
      const schedule = globalThis.setTimeout;
      vi.spyOn(globalThis, "setTimeout").mockImplementation(
        (work, delay, ...arguments_) =>
          schedule(() => {
            const began = performance.now();
            if (typeof work === "function") work(...arguments_);
            durations.push(performance.now() - began);
          }, delay),
      );
      let receive: ((event: MessageEvent) => void) | null = null;
      class Reader {
        set onmessage(listener: ((event: MessageEvent) => void) | null) {
          receive = listener;
        }
        postMessage() {}
        terminate() {}
      }
      vi.stubGlobal("Worker", Reader);
      const single = kind === "single large paragraph";
      const longTail = kind === "dense with long tail";
      const text = single
        ? "Ordinary invented facts "
            .repeat(Math.ceil(8388500 / 24))
            .slice(0, 8388500)
        : "x\n\n".repeat(16000) +
          (longTail ? "Invented fact ".repeat(16000) : "");
      const paragraphs = single ? 1 : longTail ? 16001 : 16000;
      const prepare = vi.spyOn(prose, "prepareProse");
      const container = drawing(
        { value: text },
        field("text", {
          lines: "several",
          longest: Math.max(300000, text.length),
        }),
        false,
      );
      expect(text).toHaveLength(single ? 8388500 : longTail ? 272000 : 48000);
      expect(JSON.stringify({ notes: text }).length).toBeLessThan(8388608);
      expect(prepare).not.toHaveBeenCalled();
      expect(screen.getByText("Loading…")).toBeInTheDocument();
      const preparationBegan = performance.now();
      const prepared = prepareProseTrees([text], [text]);
      const preparationMs = performance.now() - preparationBegan;
      const cloneBegan = performance.now();
      const cloned = structuredClone(prepared);
      const cloneMs = performance.now() - cloneBegan;
      const responseBegan = performance.now();
      act(() => receive?.({ data: { prepared: cloned } } as MessageEvent));
      const responseBeforeTasksMs = performance.now() - responseBegan;
      expect(container.querySelectorAll("[hidden] p").length).toBeLessThan(
        paragraphs,
      );
      act(() => vi.advanceTimersToNextTimer());
      const first = container.querySelector(single ? "p" : "[hidden] p");
      expect(first).not.toBeNull();
      if (!single) expect(first).not.toBeVisible();
      const began = performance.now();
      act(() => vi.runAllTimers());
      console.log(
        JSON.stringify({
          stage: `dense-${text.length}-JSDOM`,
          preparationMs,
          nodeStructuredCloneMs: cloneMs,
          responseBeforeTasksMs,
          totalTasksMs: performance.now() - began,
          tasks: durations.length,
          maxTaskMs: Math.max(...durations),
          sumTaskMs: durations.reduce((sum, duration) => sum + duration, 0),
          finalTaskIncludingRevealMs: durations.at(-1),
        }),
      );
      expect(screen.queryByText("Loading…")).toBeNull();
      expect(container.querySelectorAll("p")).toHaveLength(paragraphs);
      expect(container.querySelector("p")).toBe(first);
      expect(first).toBeVisible();
      if (single)
        expect(container.querySelector("p")?.textContent).toBe(text.trimEnd());
      else
        expect(
          [...container.querySelectorAll("p")]
            .slice(0, 16000)
            .every((p) => p.textContent === "x"),
        ).toBe(true);
      if (longTail)
        expect(container.querySelector("p:last-child")?.textContent).toBe(
          "Invented fact ".repeat(16000).trimEnd(),
        );
    },
    40_000,
  );
  it("cancels already mounted hidden staging when the real reader loses permission", () => {
    vi.useFakeTimers({ toFake: ["setTimeout", "clearTimeout"] });
    let receive: ((event: MessageEvent) => void) | null = null;
    const terminate = vi.fn();
    class Reader {
      set onmessage(listener: ((event: MessageEvent) => void) | null) {
        receive = listener;
      }
      postMessage() {}
      terminate = terminate;
    }
    vi.stubGlobal("Worker", Reader);
    const text = "# Imaginary private record\n\n" + "x\n\n".repeat(4000);
    const declared = field("text", { lines: "several" });
    const { container, rerender } = render(
      <FilledValue
        field={declared}
        label="Notes"
        shown={{ value: text }}
        shut={false}
      />,
    );
    const completion = receive as ((event: MessageEvent) => void) | null;
    const prepared = prepareProseTrees([text], [text]);
    act(() => completion?.({ data: { prepared } } as MessageEvent));
    act(() => vi.advanceTimersToNextTimer());
    expect(container.querySelector("[hidden] h1")).not.toBeNull();
    expect(vi.getTimerCount()).toBeGreaterThan(0);
    rerender(
      <FilledValue
        field={declared}
        label="Notes"
        shown={{ withheld: true }}
        shut={false}
      />,
    );
    expect(vi.getTimerCount()).toBe(0);
    act(() => completion?.({ data: { prepared } } as MessageEvent));
    act(() => vi.runAllTimers());
    expect(container.textContent).toBe(
      "You don't have permission to see this.",
    );
    expect(container.querySelectorAll("[hidden],h1,p,button")).toHaveLength(0);
    expect(terminate).toHaveBeenCalled();
  });
  it("cancels a pending value when the actual reader withholds it", () => {
    const cancel = vi.fn();
    class Reader {
      postMessage() {}
      terminate = cancel;
    }
    vi.stubGlobal("Worker", Reader);
    const text = "# Imaginary private note\n" + "Invented fact ".repeat(20000);
    const declared = field("text", { lines: "several" });
    const { container, rerender } = render(
      <FilledValue
        field={declared}
        label="Notes"
        shown={{ value: text }}
        shut={false}
      />,
    );
    expect(screen.getByText("Loading…")).toBeInTheDocument();
    rerender(
      <FilledValue
        field={declared}
        label="Notes"
        shown={{ withheld: true }}
        shut={false}
      />,
    );
    expect(cancel).toHaveBeenCalled();
    expect(container.textContent).toBe(
      "You don't have permission to see this.",
    );
    expect(screen.queryByText("Loading…")).toBeNull();
    expect(screen.queryByRole("button")).toBeNull();
  });
  it("routes one legal many-valued preparation by its combined size rather than each short member", () => {
    const request = vi.fn();
    const cancelled = vi.fn();
    class Reader {
      postMessage = request;
      terminate = cancelled;
    }
    vi.stubGlobal("Worker", Reader);
    const members = Array.from({ length: 64 }, (_, at) =>
      (`# Imaginary note ${at}\n` + "Invented fact ".repeat(10000)).slice(
        0,
        130000,
      ),
    );
    expect(
      members.every(
        (text) => text.length <= 130000 && text.length < 256 * 1024,
      ),
    ).toBe(true);
    expect(JSON.stringify({ notes: members }).length).toBeLessThan(8388608);
    const prepare = vi.spyOn(prose, "prepareProse");
    const container = drawing(
      { value: members },
      field("text", { lines: "several", longest: 130000, most: 64 }),
      false,
    );
    expect(prepare).not.toHaveBeenCalled();
    expect(request).toHaveBeenCalledTimes(1);
    expect(request.mock.calls[0]![0].texts).toEqual(members);
    expect(request.mock.calls[0]![0].formattedTexts).toEqual(members);
    expect(screen.getAllByText("Loading…")).toHaveLength(64);
    expect(container.querySelectorAll("h1")).toHaveLength(0);
    expect(
      screen.getAllByRole("button", { name: "Show as written" }),
    ).toHaveLength(1);
  });
  it("defensively keeps one-line declarations literal even if a stored value contains line breaks", () => {
    const text =
      "# Imaginary heading\n**Invented body**\n<aside>Literal</aside>\nhttps://example.invalid";
    const container = drawing(
      { value: text },
      field("text", { lines: "one" }),
      false,
    );
    expect(container.querySelector("bdi")?.textContent).toBe(text);
    expect(
      container.querySelectorAll("h1,strong,a,img,aside,button"),
    ).toHaveLength(0);
  });
  it("leaves closed prose unprepared and reuses an equivalent opened value", () => {
    const prepare = vi.spyOn(prose, "prepareProse");
    const text = "# Imaginary notes\n" + "Details ".repeat(4096);
    const contents = () => (
      <ThemeProvider theme={theme}>
        <FilledValue
          field={field("text", { lines: "several" })}
          label="Notes"
          shown={{ value: text }}
          shut
        />
      </ThemeProvider>
    );
    const { container, rerender } = render(contents());
    expect(prepare).not.toHaveBeenCalled();
    expect(container.querySelector("h1")).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: "Notes, in full" }));
    expect(prepare).toHaveBeenCalledTimes(1);
    expect(screen.queryByText("Loading…")).toBeNull();
    expect(container.querySelector("h1")?.textContent).toBe("Imaginary notes");
    rerender(contents());
    expect(prepare).toHaveBeenCalledTimes(1);
    fireEvent.click(screen.getByRole("button", { name: "Show as written" }));
    fireEvent.click(screen.getByRole("button", { name: "Notes, in full" }));
    expect(screen.queryByRole("button", { name: "Show formatted" })).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: "Notes, in full" }));
    expect(container.querySelector("h1")?.textContent).toBe("Imaginary notes");
    expect(
      screen.getByRole("button", { name: "Show as written" }),
    ).toBeInTheDocument();
  });
});

describe("declared text layout", () => {
  it("sets a leading literal apart only for the several-line field when identical values have different declarations", () => {
    const prefix = "---\nname: Imaginary cargo\n---\n";
    const text = prefix + "# Body\n**Finding**";
    const container = drawing(
      { value: { label: text, body: text } },
      field("fields", {
        fields: [
          field("text", { name: "label", lines: "one" }),
          field("text", { name: "body", lines: "several" }),
        ],
      }),
      false,
    );
    expect(container.querySelector("dd bdi")?.textContent).toBe(text);
    expect(container.querySelectorAll("pre")).toHaveLength(1);
    expect(container.querySelector("pre code")?.textContent).toBe(prefix);
    expect(container.querySelectorAll("h1,strong")).toHaveLength(2);
    fireEvent.click(screen.getByRole("button", { name: "Show as written" }));
    expect(container.querySelectorAll("pre,h1,strong")).toHaveLength(0);
    expect(
      [...container.querySelectorAll("dd bdi")].map((node) => node.textContent),
    ).toEqual([text, text]);
  });
  it("keeps identical spelling distinct when one field is a line and the other allows several", () => {
    const text = "**Imaginary label**";
    const container = drawing(
      { value: { label: text, body: text } },
      field("fields", {
        fields: [
          field("text", { name: "label", lines: "one" }),
          field("text", { name: "body", lines: "several" }),
        ],
      }),
      false,
    );
    expect(container.querySelector("dd bdi")?.textContent).toBe(text);
    expect(container.querySelectorAll("strong")).toHaveLength(1);
    expect(container.querySelector("strong")?.textContent).toBe(
      "Imaginary label",
    );
    fireEvent.click(screen.getByRole("button", { name: "Show as written" }));
    expect(container.querySelectorAll("strong")).toHaveLength(0);
    expect(
      [...container.querySelectorAll("dd bdi")].map(
        (element) => element.textContent,
      ),
    ).toEqual([text, text]);
  });
  it("counts raw single-line and decoded Markdown markers as one structured value in each mode", () => {
    const raw = `a${ZERO_WIDTH_SPACE}`.repeat(600);
    const body = "Evidence\n" + "b&#8203;".repeat(600);
    const container = drawing(
      { value: { raw, body } },
      field("fields", {
        fields: [
          field("text", { name: "raw", lines: "one" }),
          field("text", { name: "body", lines: "several" }),
        ],
      }),
      false,
    );
    expect(container.textContent).toContain(
      "1,200 invisible or text-direction characters",
    );
    expect(container.querySelectorAll('[role="img"]')).toHaveLength(0);
    expect(container.textContent).not.toContain("U+200B");
    expect(container.textContent).not.toContain(ZERO_WIDTH_SPACE);
    fireEvent.click(screen.getByRole("button", { name: "Show as written" }));
    expect(container.querySelectorAll('[role="img"]')).toHaveLength(600);
    expect(container.textContent).toContain("b&#8203;");
    expect(container.textContent).not.toContain(
      "1,200 invisible or text-direction characters",
    );
    expect(
      screen.getAllByRole("button", { name: "Show formatted" }),
    ).toHaveLength(1);
  });
  it("formats multiline values from any source and reopens a shut value formatted", () => {
    const container = drawing(
      { value: "# Imaginary claim\nKeep the original box." },
      field("text", { lines: "several" }),
    );
    fireEvent.click(screen.getByRole("button", { name: /Notes.*in full/i }));
    expect(container.querySelector("h1")?.textContent).toBe("Imaginary claim");
    fireEvent.click(screen.getByRole("button", { name: "Show as written" }));
    expect(container.querySelector("h1")).toBeNull();
    expect(container.querySelector("bdi")?.textContent).toBe(
      "# Imaginary claim\nKeep the original box.",
    );
    fireEvent.click(screen.getByRole("button", { name: /Notes.*in full/i }));
    fireEvent.click(screen.getByRole("button", { name: /Notes.*in full/i }));
    expect(container.querySelector("h1")?.textContent).toBe("Imaginary claim");
    expect(screen.queryByRole("button", { name: "Show formatted" })).toBeNull();
  });

  it("keeps a declared one-line value character for character with no Markdown controls", () => {
    const text = "**DEMO-CODE-17** https://example.com/claim";
    const container = drawing(
      { value: text },
      field("text", { lines: "one" }),
      false,
    );
    expect(container.querySelector("bdi")?.textContent).toBe(text);
    expect(container.querySelectorAll("strong,a,img,button")).toHaveLength(0);
  });
});

const FILE_VALUE = {
  id: "0000000f-0000-4000-8000-000000000001",
  name: "Demo Sample.pdf",
  size: 5,
};

function drawingFile(
  shown: ShownOrKept,
  of: ReadField = FILE_FIELD,
  inLine = false,
) {
  return render(
    <ThemeProvider theme={theme}>
      <CurrentFileRunContext.Provider
        value={{ groupId: "group-now", runId: "run-now" }}
      >
        <FilledValue
          field={of}
          label="Attachment"
          shown={shown}
          shut={false}
          inLine={inLine}
        />
      </CurrentFileRunContext.Provider>
    </ThemeProvider>,
  ).container;
}

describe("FilledValue", () => {
  it("draws a declared file's name and size with a current-run attachment link", () => {
    const drawn = drawingFile({ value: FILE_VALUE });
    const link = screen.getByRole("link", {
      name: /Download.*Demo Sample.pdf/,
    });

    expect(link).toHaveAttribute(
      "href",
      "/api/groups/group-now/runs/run-now/files/0000000f-0000-4000-8000-000000000001",
    );
    expect(link).toHaveAttribute("download");
    expect(drawn).not.toHaveTextContent("Type:");
    expect(drawn).toHaveTextContent("5 B");
    expect(link).toHaveAccessibleName(
      `Download ${setApart("Demo Sample.pdf")}, 5 B`,
    );
    expect(link).toHaveStyle({ display: "block" });
    expect(
      [...link.querySelectorAll("bdi")]
        .filter((each) => each.closest("[hidden]") === null)
        .map((each) => each.textContent),
    ).toEqual(["Demo Sample.pdf"]);
    expect(drawn).not.toHaveTextContent(FILE_VALUE.id);
    expect(drawn.querySelector("iframe, embed, object")).toBeNull();
  });

  it.each([
    {
      order: "shorter ending first",
      types: [".gz", ".tar.gz"],
      name: "Imaginary receipts.TAR.GZ",
      tag: "TAR.GZ",
    },
    {
      order: "longer ending first",
      types: [".tar.gz", ".gz"],
      name: "Imaginary receipts.TAR.GZ",
      tag: "TAR.GZ",
    },
    {
      order: "no declared ending matches",
      types: [".pdf", ".tar.gz"],
      name: "Imaginary receipts.other.GZ",
      tag: "GZ",
    },
  ])(
    "uses the current file declaration through FilledValue, $order",
    ({ types, name }) => {
      const drawn = drawingFile(
        { value: { ...FILE_VALUE, name } },
        { ...FILE_FIELD, types },
      );
      const link = screen.getByRole("link", {
        name: `Download ${setApart(name)}, 5 B`,
      });

      expect(
        [...link.querySelectorAll("bdi")]
          .filter((each) => each.closest("[hidden]") === null)
          .map((each) => each.textContent),
      ).toEqual([name]);
      expect(link).toHaveAttribute(
        "href",
        "/api/groups/group-now/runs/run-now/files/0000000f-0000-4000-8000-000000000001",
      );
      expect(link).toHaveAttribute("download");
      expect(link).toHaveStyle({ display: "block" });
      expect(screen.getAllByRole("link")).toHaveLength(1);
      expect(drawn).not.toHaveTextContent(FILE_VALUE.id);
      expect(drawn.querySelector("iframe, img, embed, object")).toBeNull();
    },
  );

  it.each(["flat", "nested"] as const)(
    "draws a trusted file without an ending through a %s value",
    (place) => {
      const file = { ...FILE_VALUE, name: "Imaginary receipt" };
      const drawn =
        place === "flat"
          ? drawingFile({ value: file })
          : drawingFile(
              { value: { receipts: { attachment: file } } },
              field("fields", {
                fields: [
                  field("fields", {
                    name: "receipts",
                    fields: [{ ...FILE_FIELD, name: "attachment" }],
                  }),
                ],
              }),
            );
      const link = screen.getByRole("link", {
        name: `Download ${setApart("Imaginary receipt")}, 5 B`,
      });

      expect(link.tagName).toBe("A");
      expect(link).toHaveAccessibleName(
        `Download ${setApart("Imaginary receipt")}, 5 B`,
      );
      expect(
        [...link.querySelectorAll("bdi")]
          .filter((each) => each.closest("[hidden]") === null)
          .map((each) => each.textContent),
      ).toEqual(["Imaginary receipt"]);
      expect(
        [...link.children]
          .filter((each) => !each.hasAttribute("hidden"))
          .map((each) => each.textContent),
      ).toEqual(["Imaginary receipt", "5 B"]);
      expect(link).toHaveTextContent("5 B");
      expect(link).toHaveAttribute(
        "href",
        "/api/groups/group-now/runs/run-now/files/0000000f-0000-4000-8000-000000000001",
      );
      expect(link).toHaveAttribute("download");
      expect(link).not.toHaveAttribute("target");
      expect(link).toHaveStyle({ display: "block" });
      expect(screen.getAllByRole("link")).toEqual([link]);
      expect(drawn).not.toHaveTextContent("PDF");
      expect(drawn).not.toHaveTextContent(UNKNOWN);
      expect(drawn).not.toHaveTextContent(FILE_VALUE.id);
      expect(drawn.querySelector("iframe, img, embed, object")).toBeNull();
    },
  );

  it("keeps declared files in many and nested fields in order, each with its own link", () => {
    const another = {
      ...FILE_VALUE,
      id: "0000000f-0000-4000-8000-000000000002",
      name: "Demo Sample two.pdf",
    };
    const nested = field("fields", {
      fields: [
        { ...FILE_FIELD, name: "first", most: 2 },
        { ...FILE_FIELD, name: "second" },
      ],
    });

    drawingFile(
      { value: { first: [FILE_VALUE, another], second: FILE_VALUE } },
      nested,
    );

    expect(
      screen.getAllByRole("link").map((link) => link.getAttribute("href")),
    ).toEqual([
      "/api/groups/group-now/runs/run-now/files/0000000f-0000-4000-8000-000000000001",
      "/api/groups/group-now/runs/run-now/files/0000000f-0000-4000-8000-000000000002",
      "/api/groups/group-now/runs/run-now/files/0000000f-0000-4000-8000-000000000001",
    ]);
    expect(screen.getAllByRole("link").map((link) => link.textContent)).toEqual(
      [
        expect.stringContaining("Demo Sample.pdf"),
        expect.stringContaining("Demo Sample two.pdf"),
        expect.stringContaining("Demo Sample.pdf"),
      ],
    );
  });

  it("keeps current compound file declarations through many and nested fields", () => {
    const declared = field("fields", {
      fields: [
        field("fields", {
          name: "receipts",
          fields: [
            {
              ...FILE_FIELD,
              name: "attachments",
              most: 2,
              types: [".gz", ".tar.gz"],
            },
            {
              ...FILE_FIELD,
              name: "original",
              types: [".tar.gz", ".gz"],
            },
          ],
        }),
      ],
    });
    const first = { ...FILE_VALUE, name: "Imaginary receipts.TAR.GZ" };
    const second = {
      ...FILE_VALUE,
      id: "0000000f-0000-4000-8000-000000000002",
      name: "Imaginary receipts two.TaR.Gz",
    };
    const original = {
      ...FILE_VALUE,
      id: "0000000f-0000-4000-8000-000000000003",
      name: "Imaginary original.tar.gz",
    };
    const drawn = drawingFile(
      {
        value: {
          receipts: { attachments: [first, second], original },
        },
      },
      declared,
    );
    const links = screen.getAllByRole("link");

    expect(
      links.map((link) =>
        [...link.querySelectorAll("bdi")]
          .filter((each) => each.closest("[hidden]") === null)
          .map((each) => each.textContent),
      ),
    ).toEqual([
      ["Imaginary receipts.TAR.GZ"],
      ["Imaginary receipts two.TaR.Gz"],
      ["Imaginary original.tar.gz"],
    ]);
    expect(links.map((link) => link.getAttribute("href"))).toEqual([
      "/api/groups/group-now/runs/run-now/files/0000000f-0000-4000-8000-000000000001",
      "/api/groups/group-now/runs/run-now/files/0000000f-0000-4000-8000-000000000002",
      "/api/groups/group-now/runs/run-now/files/0000000f-0000-4000-8000-000000000003",
    ]);
    expect(links[0]).toHaveAccessibleName(
      `Download ${setApart("Imaginary receipts.TAR.GZ")}, 5 B`,
    );
    expect(links[1]).toHaveAccessibleName(
      `Download ${setApart("Imaginary receipts two.TaR.Gz")}, 5 B`,
    );
    expect(links[2]).toHaveAccessibleName(
      `Download ${setApart("Imaginary original.tar.gz")}, 5 B`,
    );
    links.forEach((link) => {
      expect(link).toHaveAttribute("download");
      expect(link).toHaveStyle({ display: "block" });
    });
    expect(drawn).not.toHaveTextContent(FILE_VALUE.id);
    expect(drawn).not.toHaveTextContent(UNKNOWN);
    expect(drawn.querySelector("iframe, img, embed, object")).toBeNull();
  });

  it("reads the same file through two different current declarations in one value", () => {
    const file = { ...FILE_VALUE, name: "Imaginary receipts.TAR.GZ" };
    const declared = field("fields", {
      fields: [
        { ...FILE_FIELD, name: "short", types: [".gz"] },
        { ...FILE_FIELD, name: "compound", types: [".gz", ".tar.gz"] },
      ],
    });
    const drawn = drawingFile(
      { value: { short: file, compound: file } },
      declared,
    );
    const links = screen.getAllByRole("link");

    expect(
      links.map((link) =>
        [...link.querySelectorAll("bdi")]
          .filter((each) => each.closest("[hidden]") === null)
          .map((each) => each.textContent),
      ),
    ).toEqual([["Imaginary receipts.TAR.GZ"], ["Imaginary receipts.TAR.GZ"]]);
    links.forEach((link) => {
      expect(link).toHaveAccessibleName(`Download ${setApart(file.name)}, 5 B`);
      expect(link).toHaveAttribute(
        "href",
        "/api/groups/group-now/runs/run-now/files/0000000f-0000-4000-8000-000000000001",
      );
      expect(link).toHaveAttribute("download");
    });
    expect(drawn).not.toHaveTextContent(UNKNOWN);
    expect(drawn.querySelector("iframe, img, embed, object")).toBeNull();
  });

  it("keeps an opened sibling's state and DOM when files disappear and return", () => {
    const notes = "Keep the receipt with this request. ".repeat(9).trimEnd();
    const file = { ...FILE_VALUE, name: "Imaginary receipt.pdf" };
    const other = {
      ...FILE_VALUE,
      id: "0000000f-0000-4000-8000-000000000002",
      name: "Imaginary packing note.txt",
    };
    const declared = field("fields", {
      fields: [
        field("text", { name: "notes", label: "Notes", longest: 400 }),
        { ...FILE_FIELD, name: "attachment", label: "Attachment", most: 2 },
      ],
    });
    const view = (attachments: readonly (typeof file)[]) => (
      <ThemeProvider theme={theme}>
        <CurrentFileRunContext.Provider
          value={{ groupId: "group-now", runId: "run-now" }}
        >
          <FilledValue
            field={declared}
            label="Report"
            shown={{ value: { notes, attachment: [...attachments] } }}
            shut
          />
        </CurrentFileRunContext.Provider>
      </ThemeProvider>
    );
    const { container, rerender } = render(view([file, other]));
    const control = screen.getByRole("button", {
      name: `Report › ${setApart("Notes")}, in full`,
    });
    expect(control).toHaveAttribute("aria-expanded", "false");
    expect(screen.queryByText(notes)).toBeNull();

    fireEvent.click(control);
    const text = screen.getByText(notes, { exact: true });
    const content = document.getElementById(
      control.getAttribute("aria-controls")!,
    );
    expect(content).not.toBeNull();
    expect(content).toBeVisible();
    expect(control).toHaveAttribute("aria-expanded", "true");
    expect(text.textContent).toBe(notes);

    for (const attachments of [[], [file, other]] as const) {
      rerender(view(attachments));
      expect(
        screen.getByRole("button", {
          name: `Report › ${setApart("Notes")}, in full`,
        }),
      ).toBe(control);
      expect(control).toHaveAttribute("aria-expanded", "true");
      expect(
        document.getElementById(control.getAttribute("aria-controls")!),
      ).toBe(content);
      expect(content).toBeVisible();
      expect(screen.getByText(notes, { exact: true })).toBe(text);
      expect(text.textContent).toBe(notes);
      const links = screen.queryAllByRole("link");
      expect(links).toHaveLength(attachments.length);
      if (attachments.length === 0) {
        expect(container).not.toHaveTextContent(file.name);
        expect(container).not.toHaveTextContent(other.name);
      } else {
        attachments.forEach((attachment, index) => {
          expect(links[index]).toHaveAccessibleName(
            `Download ${setApart(attachment.name)}, 5 B`,
          );
          expect(links[index]).toHaveAttribute(
            "href",
            `/api/groups/group-now/runs/run-now/files/${attachment.id}`,
          );
          expect(links[index]).toHaveAttribute("download");
        });
      }
      expect(container).not.toHaveTextContent(UNKNOWN);
      expect(container.querySelector("iframe, img, embed, object")).toBeNull();
    }

    fireEvent.click(control);
    expect(control).toHaveAttribute("aria-expanded", "false");
    expect(content).not.toBeVisible();
    expect(screen.queryByText(notes)).toBeNull();
  });

  it.each([false, true])(
    "returns from file-free values to files with the current declaration, nested %s",
    (nested) => {
      const file = { ...FILE_VALUE, name: "Imaginary receipts.TAR.GZ" };
      const view = (shown: ShownOrKept, declared: ReadField) => (
        <ThemeProvider theme={theme}>
          <CurrentFileRunContext.Provider
            value={{ groupId: "group-now", runId: "run-now" }}
          >
            <FilledValue
              field={declared}
              label="Receipt"
              shown={shown}
              shut={false}
            />
          </CurrentFileRunContext.Provider>
        </ThemeProvider>
      );
      const declared = (types: readonly string[]) => {
        const attachment = {
          ...FILE_FIELD,
          name: "attachment",
          types,
          most: 2,
        };
        return nested ? field("fields", { fields: [attachment] }) : attachment;
      };
      const value = (files: readonly (typeof file)[]) =>
        nested ? { attachment: [...files] } : [...files];
      const { container, rerender } = render(
        view({ value: "Receipt requested." }, field("text", { longest: 40 })),
      );

      expect(container.textContent).toBe("Receipt requested.");
      expect(screen.queryByRole("link")).toBeNull();

      rerender(view({ value: value([]) }, declared([".gz"])));
      expect(screen.queryByRole("link")).toBeNull();
      expect(container).not.toHaveTextContent(file.name);
      expect(container).not.toHaveTextContent(UNKNOWN);

      for (const [types] of [
        [[".gz"], "GZ"],
        [[".gz", ".tar.gz"], "TAR.GZ"],
      ] as const) {
        rerender(view({ value: value([file]) }, declared(types)));
        const link = screen.getByRole("link", {
          name: `Download ${setApart(file.name)}, 5 B`,
        });
        expect(
          [...link.querySelectorAll("bdi")]
            .filter((each) => each.closest("[hidden]") === null)
            .map((each) => each.textContent),
        ).toEqual([file.name]);
        expect(screen.getAllByRole("link")).toEqual([link]);
        expect(link).toHaveAttribute(
          "href",
          "/api/groups/group-now/runs/run-now/files/0000000f-0000-4000-8000-000000000001",
        );
        expect(link).toHaveAttribute("download");
        expect(container).not.toHaveTextContent(UNKNOWN);

        rerender(view({ value: null }, declared(types)));
        expect(container.textContent).toBe("None");
        expect(screen.queryByRole("link")).toBeNull();
        expect(container).not.toHaveTextContent(file.name);
      }

      rerender(
        view({ asKept: "Receipt as originally given." }, declared([".gz"])),
      );
      expect(container).toHaveTextContent("Receipt as originally given.");
      expect(container).toHaveTextContent(EARLIER_SHAPE);
      expect(screen.queryByRole("link")).toBeNull();

      rerender(view({ withheld: true }, declared([".gz"])));
      expect(container.textContent).toBe(
        "You don't have permission to see this.",
      );
      expect(screen.queryByRole("link")).toBeNull();

      rerender(
        view({ value: "Receipt complete." }, field("text", { longest: 40 })),
      );
      expect(container.textContent).toBe("Receipt complete.");
      expect(screen.queryByRole("link")).toBeNull();
      expect(container).not.toHaveTextContent(file.name);
      expect(container.querySelector("iframe, img, embed, object")).toBeNull();
    },
  );

  it("uses the current declaration after rerendering a nested file and returning from unreadable shapes", () => {
    const file = { ...FILE_VALUE, name: "Imaginary receipts.TAR.GZ" };
    const view = (shown: ShownOrKept, types: readonly string[]) => (
      <ThemeProvider theme={theme}>
        <CurrentFileRunContext.Provider
          value={{ groupId: "group-now", runId: "run-now" }}
        >
          <FilledValue
            field={field("fields", {
              fields: [{ ...FILE_FIELD, name: "attachment", types }],
            })}
            label="Receipt"
            shown={shown}
            shut={false}
          />
        </CurrentFileRunContext.Provider>
      </ThemeProvider>
    );
    const normal = { value: { attachment: file } };
    const { container, rerender } = render(view(normal, [".gz"]));
    for (const [types] of [
      [[".gz"], "GZ"],
      [[".gz", ".tar.gz"], "TAR.GZ"],
      [[".pdf"], "GZ"],
    ] as const) {
      rerender(view(normal, types));
      const link = screen.getByRole("link", {
        name: `Download ${setApart(file.name)}, 5 B`,
      });
      expect(
        [...link.querySelectorAll("bdi")]
          .filter((each) => each.closest("[hidden]") === null)
          .map((each) => each.textContent),
      ).toEqual([file.name]);
      expect(link).toHaveAttribute(
        "href",
        "/api/groups/group-now/runs/run-now/files/0000000f-0000-4000-8000-000000000001",
      );
      expect(link).toHaveAttribute("download");
      expect(screen.getAllByRole("link")).toHaveLength(1);
      expect(container).not.toHaveTextContent(UNKNOWN);
    }

    rerender(view({ withheld: true }, [".tar.gz"]));
    expect(container).toHaveTextContent(
      "You don't have permission to see this.",
    );
    expect(container).not.toHaveTextContent(file.name);
    expect(screen.queryByRole("link")).toBeNull();

    const raw = JSON.stringify({ attachment: file });
    rerender(view({ asKept: raw }, [".tar.gz"]));
    expect(screen.getByText(raw).textContent).toBe(raw);
    expect(container).toHaveTextContent(EARLIER_SHAPE);
    expect(screen.queryByRole("link")).toBeNull();

    rerender(view({ value: null }, [".tar.gz"]));
    expect(container.textContent).toBe("None");
    expect(container).not.toHaveTextContent(file.name);
    expect(screen.queryByRole("link")).toBeNull();

    rerender(view({ value: { attachment: { withheld: true } } }, [".tar.gz"]));
    expect(container).toHaveTextContent(UNKNOWN);
    expect(container).not.toHaveTextContent(file.name);
    expect(screen.queryByRole("link")).toBeNull();

    rerender(view(normal, [".tar.gz"]));
    const restored = screen.getByRole("link", {
      name: `Download ${setApart(file.name)}, 5 B`,
    });
    expect(
      [...restored.querySelectorAll("bdi")]
        .filter((each) => each.closest("[hidden]") === null)
        .map((each) => each.textContent),
    ).toEqual([file.name]);
    expect(screen.getAllByRole("link")).toEqual([restored]);
    expect(container).not.toHaveTextContent(UNKNOWN);
    expect(container.querySelector("iframe, img, embed, object")).toBeNull();
  });

  it("counts every repeated file appearance in the shared hidden-character limit", () => {
    const hidden = String.fromCodePoint(0x200b);
    const file = {
      ...FILE_VALUE,
      name: `Imaginary claim.${`a${hidden}`.repeat(100)}`,
    };
    const declared = field("fields", {
      fields: [
        field("text", { name: "note", longest: 40 }),
        { ...FILE_FIELD, name: "attachments", most: 10 },
      ],
    });
    const drawn = drawingFile(
      {
        value: {
          note: `Keep${hidden} this.`,
          attachments: Array.from({ length: 10 }, () => file),
        },
      },
      declared,
    );

    expect(drawn).toHaveTextContent(
      "1,001 invisible or text-direction characters are left out here: too many to mark each one.",
    );
    expect(
      drawn.textContent?.match(/1,001 invisible or text-direction characters/g),
    ).toHaveLength(1);
    expect(drawn.querySelectorAll('[role="img"]')).toHaveLength(0);
    expect(drawn.innerHTML).not.toContain(hidden);
    expect(drawn).toHaveTextContent("Keep this.");
    const links = screen.getAllByRole("link", {
      name: `Download ${setApart(`Imaginary claim.${"a".repeat(100)}`)}, 5 B`,
    });
    expect(links).toHaveLength(10);
    links.forEach((link) => {
      expect(
        [...link.querySelectorAll("bdi")]
          .filter((each) => each.closest("[hidden]") === null)
          .map((each) => each.textContent),
      ).toEqual([`Imaginary claim.${"a".repeat(100)}`]);
      expect(link).toHaveAttribute(
        "href",
        "/api/groups/group-now/runs/run-now/files/0000000f-0000-4000-8000-000000000001",
      );
      expect(link).toHaveAttribute("download");
    });
    expect(drawn.querySelector("iframe, img, embed, object")).toBeNull();
  });

  it("draws hidden text beside a downloadable file and a real field named withheld", () => {
    const privateText = "Demo Sample private note";
    const declared = field("fields", {
      fields: [
        field("text", { name: "privateNote", longest: 100 }),
        { ...FILE_FIELD, name: "attachment" },
        field("text", { name: "withheld", longest: 100 }),
      ],
    });
    const drawn = drawingFile(
      {
        value: {
          privateNote: { withheld: true },
          attachment: FILE_VALUE,
          withheld: "Demo Sample",
        },
      },
      declared,
    );

    expect(drawn).toHaveTextContent("You don't have permission to see this.");
    expect(drawn).toHaveTextContent("Demo Sample");
    expect(
      screen.getByRole("link", { name: /Download.*Demo Sample.pdf/ }),
    ).toHaveAttribute(
      "href",
      "/api/groups/group-now/runs/run-now/files/0000000f-0000-4000-8000-000000000001",
    );
    expect(drawn.innerHTML).not.toContain(privateText);
    expect(screen.queryByRole("link", { name: privateText })).toBeNull();
  });

  it("keeps inline files as separate block cards beside ordinary text without list or paragraph containers", () => {
    const of = field("fields", {
      fields: [
        field("text", { name: "note", longest: 40 }),
        { ...FILE_FIELD, name: "attachments", most: 2 },
      ],
    });
    const drawn = drawingFile(
      {
        value: {
          note: "Keep these receipts.",
          attachments: [
            FILE_VALUE,
            {
              ...FILE_VALUE,
              id: "0000000f-0000-4000-8000-000000000002",
              name: "Imaginary receipt.tar.gz",
            },
          ],
        },
      },
      of,
      true,
    );

    const links = screen.getAllByRole("link");
    expect(links.map((link) => link.getAttribute("href"))).toEqual([
      "/api/groups/group-now/runs/run-now/files/0000000f-0000-4000-8000-000000000001",
      "/api/groups/group-now/runs/run-now/files/0000000f-0000-4000-8000-000000000002",
    ]);
    links.forEach((link) => expect(link).toHaveStyle({ display: "block" }));
    expect(drawn).toHaveTextContent("Keep these receipts.");
    expect(
      drawn.querySelector("ul, li, p, iframe, img, embed, object"),
    ).toBeNull();
    expect(screen.queryByRole("button")).toBeNull();
  });

  it("shares one hidden-character limit across plain siblings and filenames, counting no removed ending tag", () => {
    const hidden = String.fromCodePoint(0x200b);
    const name = `Imaginary claim.${`a${hidden}`.repeat(100)}`;
    const of = field("fields", {
      fields: [
        field("text", { name: "note", longest: 1100 }),
        { ...FILE_FIELD, name: "attachments", most: 5 },
      ],
    });
    const drawn = drawingFile(
      {
        value: {
          note: `Keep${`k${hidden}`.repeat(501)} this.`,
          attachments: [1, 2, 3, 4, 5].map((number) => ({
            ...FILE_VALUE,
            name,
            id: `0000000f-0000-4000-8000-00000000000${number}`,
          })),
        },
      },
      of,
    );

    expect(drawn).toHaveTextContent(
      "1,001 invisible or text-direction characters are left out here: too many to mark each one.",
    );
    expect(
      drawn.textContent?.match(/1,001 invisible or text-direction characters/g),
    ).toHaveLength(1);
    expect(drawn.querySelectorAll('[role="img"]')).toHaveLength(0);
    expect(drawn.innerHTML).not.toContain(hidden);
    expect(drawn).toHaveTextContent(`Keep${"k".repeat(501)} this.`);
    const links = screen.getAllByRole("link", {
      name: `Download ${setApart(`Imaginary claim.${"a".repeat(100)}`)}, 5 B`,
    });
    expect(links).toHaveLength(5);
    const link = links[0];
    expect(
      [...link.querySelectorAll("bdi")]
        .filter((each) => each.closest("[hidden]") === null)
        .map((each) => each.textContent),
    ).toEqual([`Imaginary claim.${"a".repeat(100)}`]);
    expect(link).toHaveAttribute("download");
  });

  it("says a whole fields value is withheld rather than showing its children as none", () => {
    const declared = field("fields", {
      fields: [field("text", { name: "note", longest: 40 })],
    });
    const drawn = drawing({ value: { withheld: true } }, declared);

    expect(drawn).toHaveTextContent("You don't have permission to see this.");
    expect(drawn).not.toHaveTextContent("None");
    expect(drawn).not.toHaveTextContent("note");
    expect(screen.queryByRole("link")).toBeNull();
  });

  it("still reads a real child named withheld in a declared fields value", () => {
    const declared = field("fields", {
      fields: [field("text", { name: "withheld", longest: 40 })],
    });
    const drawn = drawing({ value: { withheld: "Demo Sample" } }, declared);

    expect(drawn).toHaveTextContent("Demo Sample");
    expect(drawn).not.toHaveTextContent(
      "You don't have permission to see this.",
    );
  });

  it("says unknown for a declared file with invalid size and offers no download", () => {
    const bad = { ...FILE_VALUE, size: "5" };
    const declared = drawingFile({ value: bad as never });

    expect(declared).toHaveTextContent(UNKNOWN);
    expect(screen.queryByRole("link")).toBeNull();
    expect(declared).not.toHaveTextContent("Demo Sample.pdf");
  });

  it("shows unknown when a file field holds the wrong one-or-many shape", () => {
    const single = drawingFile({ value: [FILE_VALUE] });
    expect(single).toHaveTextContent(UNKNOWN);
    expect(screen.queryByRole("link")).toBeNull();

    const many = drawingFile({ value: FILE_VALUE }, { ...FILE_FIELD, most: 2 });
    expect(many).toHaveTextContent(UNKNOWN);
    expect(screen.queryByRole("link")).toBeNull();
  });

  it("refuses a malformed file identifier without rendering its hidden characters", () => {
    const hidden = String.fromCodePoint(0x200b).repeat(1_001);
    const drawn = drawingFile({ value: { ...FILE_VALUE, id: hidden } });

    expect(drawn).toHaveTextContent(UNKNOWN);
    expect(drawn).not.toHaveTextContent("left out");
    expect(drawn.innerHTML).not.toContain(hidden);
    expect(screen.queryByRole("link")).toBeNull();
  });

  it.each([
    ["an unusable name", { name: "Demo\nSample.pdf" }],
    ["an unusable type", { type: "application//pdf" }],
  ])("does not link a file with %s", (_case, changed) => {
    const drawn = drawingFile({ value: { ...FILE_VALUE, ...changed } });

    expect(drawn).toHaveTextContent(UNKNOWN);
    expect(screen.queryByRole("link")).toBeNull();
  });

  it("does not turn a withheld or earlier-shape value into file metadata or a link", () => {
    const withheld = drawingFile({ withheld: true });

    expect(withheld).toHaveTextContent(
      "You don't have permission to see this.",
    );
    expect(screen.queryByRole("link")).toBeNull();

    const earlier = drawingFile({ asKept: JSON.stringify(FILE_VALUE) });
    expect(earlier).toHaveTextContent(EARLIER_SHAPE);
    expect(screen.queryByRole("link")).toBeNull();
  });

  it("shows readable metadata without a download link outside a readable run page", () => {
    const drawn = drawing({ value: FILE_VALUE }, FILE_FIELD);

    expect(drawn).toHaveTextContent("Demo Sample.pdf");
    expect(drawn).not.toHaveTextContent("Type:");
    expect(screen.queryByRole("link")).toBeNull();
  });
  it("draws a short text of one line set apart in an element of its own, open, with nothing to open it by", () => {
    const drawn = drawing({ value: "A kettle leaks." }, field("text"));

    expect(drawn.querySelector("bdi")?.textContent).toBe("A kettle leaks.");
    expect(screen.queryByRole("button")).toBeNull();
  });

  it.each([
    ["a text", { value: `Pay ${RIGHT_TO_LEFT_OVERRIDE}001$` }, "text"],
    [
      "one kept in an earlier shape",
      { asKept: `Pay ${RIGHT_TO_LEFT_OVERRIDE}001$` },
      "text",
    ],
    [
      "a number written in a way it cannot be read",
      { value: `1${RIGHT_TO_LEFT_OVERRIDE}2` },
      "number",
    ],
  ] as const)(
    "draws an override in %s where it stands as a marker named for it, never the override itself",
    (_case, shown, kind) => {
      const drawn = drawing(shown, field(kind), false);

      expect(
        drawn.querySelector("bdi [role='img']")?.getAttribute("title"),
      ).toBe(
        "A text-direction character is here, shown instead of applied: U+202E.",
      );
      expect(drawn.querySelector("bdi")?.textContent).toContain("U+202E");
      expect(drawn.innerHTML).not.toContain(RIGHT_TO_LEFT_OVERRIDE);
    },
  );

  const SPREAD = [
    ["one text", field("text"), (markers: number) => markedApart(markers)],
    [
      "many items, several alike",
      field("text", { most: 20 }),
      (markers: number) => [
        ...Array.from({ length: 9 }, () => markedApart(100)),
        markedApart(markers - 900),
      ],
    ],
    [
      "fields within fields, nothing declaring them",
      undefined,
      (markers: number) => ({
        notes: Array.from({ length: 5 }, () => markedApart(100)),
        about: { title: markedApart(markers - 500) },
      }),
    ],
  ] as const;

  it.each(SPREAD)(
    "draws every marker of a value of %s needing as many across it as are drawn, saying nothing left out",
    (_shape, of, spread) => {
      const drawn = drawing({ value: spread(1_000) }, of, false);

      expect(screen.getAllByRole("img")).toHaveLength(1_000);
      expect(drawn.textContent).not.toContain("left out");
    },
  );

  it.each(SPREAD)(
    "draws no marker anywhere in a value of %s needing one more across it than are drawn, saying once above it how many characters it left out",
    (_shape, of, spread) => {
      const drawn = drawing({ value: spread(1_001) }, of, false);

      expect(screen.queryByRole("img")).toBeNull();
      expect(drawn.firstElementChild).toHaveTextContent(
        "1,001 invisible or text-direction characters are left out here: too many to mark each one.",
      );
      expect(screen.getAllByText(/left out/)).toHaveLength(1);
      expect(
        screen
          .getAllByText(/^a+$/)
          .reduce((kept, each) => kept + each.textContent.length, 0),
      ).toBe(1_001);
      expect(drawn.textContent).not.toContain("U+200B");
      expect(drawn.innerHTML).not.toContain(ZERO_WIDTH_SPACE);
    },
  );

  it("draws a control in a text as a marker said to show nothing, a line ended with a carriage return and a line feed as it is, and neither control on the page", () => {
    const drawn = drawing(
      { value: `Leak${String.fromCodePoint(0x1)}\r\nTwice.` },
      field("text"),
      false,
    );

    expect(
      screen.getAllByRole("img").map((each) => each.getAttribute("title")),
    ).toEqual(["An invisible character is here: U+0001."]);
    expect(drawn.querySelector("bdi")?.textContent).toBe(
      "LeakU+0001\r\nTwice.",
    );
    expect(drawn.innerHTML).not.toContain(String.fromCodePoint(0x1));
  });

  it("draws no marker anywhere in a value of many items whose controls and lone carriage returns need one more than are drawn, saying once how many it left out", () => {
    const control = String.fromCodePoint(0x1);
    const drawn = drawing(
      {
        value: [
          `a${control}`.repeat(500),
          "b\r".repeat(500),
          `c${String.fromCodePoint(0x2028)}`,
          "d\r\n",
        ],
      },
      field("text", { most: 20 }),
      false,
    );

    expect(screen.queryByRole("img")).toBeNull();
    expect(drawn.firstElementChild).toHaveTextContent(
      "1,001 invisible or text-direction characters are left out here: too many to mark each one.",
    );
    expect(
      [...drawn.querySelectorAll("bdi")].map((each) => each.textContent),
    ).toEqual(["a".repeat(500), "b".repeat(500), "c", "d\r\n"]);
    expect(drawn.innerHTML).not.toContain(control);
    expect(drawn.innerHTML).not.toContain(String.fromCodePoint(0x2028));
  });

  it("shuts a text of more than one line until it is opened, saying whether it is open, every line kept once it is", () => {
    const drawn = drawing({ value: "Line one\nLine two" }, field("text"));
    const control = screen.getByRole("button", { name: "Notes, in full" });
    expect(control).toHaveAttribute("aria-expanded", "false");
    expect(drawn.querySelector("bdi")).toBeNull();

    fireEvent.click(control);

    expect(control).toHaveAttribute("aria-expanded", "true");
    expect(drawn.querySelector("bdi")?.textContent).toBe("Line one\nLine two");
    expect(
      document.getElementById(control.getAttribute("aria-controls")!),
    ).not.toHaveAttribute("hidden");
  });

  it.each([
    ["200 is open", 200, false],
    ["201 is shut", 201, true],
  ])(
    "counts a text's length in code points as the server does, so one of emoji %s",
    (_case, count, shut) => {
      const drawn = drawing({ value: "😀".repeat(count) }, field("text"));

      expect(screen.queryByRole("button") !== null).toBe(shut);
      expect(drawn.querySelector("bdi") === null).toBe(shut);
    },
  );

  it("draws a long text in full, every line kept, with nothing to open it by, where long texts are not shut", () => {
    const drawn = drawing(
      { value: "Line one\nLine two" },
      field("text"),
      false,
    );

    expect(drawn.querySelector("bdi")?.textContent).toBe("Line one\nLine two");
    expect(screen.queryByRole("button")).toBeNull();
  });

  it("shuts a long text wherever it is, within fields and among many, named by the field it is within and its place among them, leaving a short one beside it open", () => {
    const notes = field("text", { name: "notes", label: "Notes", most: 3 });
    const title = field("text", { name: "title", label: "Title" });
    drawing(
      { value: { notes: ["Seen once.", "Seen\nagain."], title: "Leak" } },
      field("fields", { fields: [notes, title] }),
    );

    expect(
      screen.getAllByRole("button").map((each) => each.textContent),
    ).toEqual([`Notes › ${setApart("Notes")}, item 2, in full`]);
    expect(screen.getByText("Seen once.")).toBeInTheDocument();
    expect(screen.getByText("Leak")).toBeInTheDocument();
    expect(screen.queryByText(/again/)).toBeNull();
  });

  it("names the controls of two long texts in sibling fields apart, each by the field it is within", () => {
    const notes = field("text", { name: "notes", label: "Notes" });
    const group = (name: string, label: string) =>
      field("fields", { name, label, fields: [notes] });
    drawing(
      {
        value: {
          before: { notes: "Seen\nonce." },
          after: { notes: "Seen\nagain." },
        },
      },
      field("fields", {
        fields: [group("before", "Before"), group("after", "After")],
      }),
    );

    expect(
      screen.getAllByRole("button").map((each) => each.textContent),
    ).toEqual([
      `Notes › ${setApart("Before")} › ${setApart("Notes")}, in full`,
      `Notes › ${setApart("After")} › ${setApart("Notes")}, in full`,
    ]);
  });

  it("groups a number as the reader does and rounds none of its digits", () => {
    const drawn = drawing(
      { value: "-12345678901234567890.123456789" },
      field("number"),
    );

    expect(drawn.textContent).toBe("-12,345,678,901,234,567,890.123456789");
  });

  it("keeps every digit written after the point, a nought at the end included", () => {
    expect(drawing({ value: "1.50" }, field("number")).textContent).toBe(
      "1.50",
    );
    expect(drawing({ value: "15" }, field("number")).textContent).toBe("15");
  });

  it("dates a day of the first century in that century, not the one Date.UTC reads it in", () => {
    const drawn = drawing({ value: "0005-03-01" }, field("date"));

    const day = drawn.querySelector("time")!;
    expect(day.getAttribute("datetime")).toBe("0005-03-01");
    expect(day.textContent).toMatch(/^Mar 1, 5$/);
    expect(day.textContent).not.toContain("1905");
  });

  it("says a moment in the offset it was given in, to the second, and beneath it on the reader's own clock", () => {
    const drawn = drawing(
      { value: "2026-09-18T15:00:07+05:30" },
      field("moment"),
    );

    const moment = drawn.querySelector("time")!;
    expect(moment.textContent).toMatch(
      /^Sep 18, 2026, 3:00:07\sPM, UTC\+05:30$/,
    );
    expect(moment.getAttribute("datetime")).toBe("2026-09-18T15:00:07+05:30");
    expect(moment.hasAttribute("title")).toBe(false);
    expect(moment.nextElementSibling?.textContent).toMatch(
      /^Sep 18, 2026, 11:30:07\sAM, your time$/,
    );
  });

  it("says every digit of a fraction of a second written, on both clocks, and the first three cut, never rounded, in the element's own time", () => {
    const drawn = drawing(
      { value: "2026-09-18T15:00:59.999900+05:30" },
      field("moment"),
    );

    const moment = drawn.querySelector("time")!;
    expect(moment.textContent).toMatch(
      /^Sep 18, 2026, 3:00:59\.999900\sPM, UTC\+05:30$/,
    );
    expect(moment.nextElementSibling?.textContent).toMatch(
      /^Sep 18, 2026, 11:30:59\.999900\sAM, your time$/,
    );
    expect(moment.getAttribute("datetime")).toBe(
      "2026-09-18T15:00:59.999+05:30",
    );
  });

  it("writes a moment in the reader's own digits and words, a fraction of a second too, drawn as one without a fraction is", () => {
    reader.language = "ar-EG";
    const withFraction = drawing(
      { value: "2026-09-18T15:00:07.123456+05:30" },
      field("moment"),
    ).querySelector("time")!.nextElementSibling!.textContent!;
    const without = drawing(
      { value: "2026-09-18T15:00:07+05:30" },
      field("moment"),
    ).querySelector("time")!.nextElementSibling!.textContent!;

    expect(withFraction).toContain("٠٧٫١٢٣٤٥٦");
    expect(withFraction).not.toMatch(/[0-9]/);
    expect(withFraction.replace("٫١٢٣٤٥٦", "")).toBe(without);
  });

  it.each([
    ["a day past the end of its month", "2026-02-30", field("date")],
    ["a day in no year a date may be", "0000-01-01", field("date")],
    [
      "a moment past six digits of a second",
      "2026-09-18T15:00:00.1234567+05:30",
      field("moment"),
    ],
    [
      "a moment on a day past the end of its month",
      "2026-02-30T15:00:00Z",
      field("moment"),
    ],
    ["a number written with an exponent", "1e5", field("number")],
    ["yes or no written as a word", "yes", field("yes_no")],
  ])(
    "draws %s as it came, said to be unreadable, and never as the value it would roll over to",
    (_case, value, of) => {
      const drawn = drawing({ value }, of);

      expect(drawn.querySelector("bdi")?.textContent).toBe(value);
      expect(drawn).toHaveTextContent(UNREADABLE);
      expect(drawn.querySelector("time")).toBeNull();
    },
  );

  it.each([
    ["true", "Yes"],
    ["false", "No"],
  ])(
    "says yes or no for %s, never the word it was written with",
    (value, said) => {
      const drawn = drawing({ value }, field("yes_no"));

      expect(drawn.textContent).toBe(said);
    },
  );

  it("says a term with what it means, and a term none of its field's as it came, said to be unreadable", () => {
    const of = field("term", {
      terms: {
        terms: [
          { term: "P1", meaning: "Drop everything" },
          { term: "P2", meaning: "Plan the next review" },
        ],
      },
    });

    const offered = drawing({ value: "P1" }, of).textContent;
    const other = drawing({ value: "P9" }, of);

    expect(offered).toBe(
      `${String.fromCodePoint(0x2068)}P1${String.fromCodePoint(0x2069)}: ${String.fromCodePoint(0x2068)}Drop everything${String.fromCodePoint(0x2069)}`,
    );
    expect(offered).not.toContain(UNREADABLE);
    expect(other.querySelector("bdi")?.textContent).toBe("P9");
    expect(other).toHaveTextContent(UNREADABLE);
  });

  it("lists many as items of a list, each drawn as its field's kind", () => {
    drawing({ value: ["true", "false"] }, field("yes_no", { most: 3 }));

    expect(
      screen.getAllByRole("listitem").map((item) => item.textContent),
    ).toEqual(["Yes", "No"]);
  });

  it("says many given none as none, and draws no list for it", () => {
    const drawn = drawing({ value: [] }, field("text", { most: 3 }));

    expect(drawn.textContent).toBe("None");
    expect(drawn.querySelector("ul")).toBeNull();
  });

  it("runs many on in one line where it stands within one, each drawn as its field's kind, joined as the reader's language joins a list", () => {
    reader.language = "de";

    const drawn = render(
      <FilledValue
        field={field("yes_no", { most: 3 })}
        label="Notes"
        shown={{ value: ["true", "false", "maybe"] }}
        shut={false}
        inLine
      />,
    ).container;

    expect(drawn.textContent).toBe(
      "Yes, No und maybeThis page can't read this value.",
    );
    expect(drawn.querySelector("ul, li, p, div")).toBeNull();
  });

  it("runs fields on in one line where they stand within one, each after its label, a field given none saying so", () => {
    const of = field("fields", {
      fields: [
        field("text", { name: "name", label: "Name", longest: 20 }),
        field("number", { name: "count", label: "Count" }),
      ],
    });

    const drawn = render(
      <FilledValue
        field={of}
        label="Notes"
        shown={{ value: { count: null, name: "Kettle" } }}
        shut={false}
        inLine
      />,
    ).container;

    expect(drawn.textContent).toBe("Name: Kettle and Count: None");
    expect(drawn.querySelector("dl, dt, dd, div")).toBeNull();
  });

  it("draws fields under their labels in declared order, a field given none saying so", () => {
    const of = field("fields", {
      fields: [
        field("text", { name: "name", label: "Name", longest: 20 }),
        field("number", { name: "count", label: "Count" }),
      ],
    });

    drawing({ value: { count: null, name: "Kettle" } }, of);

    expect(screen.getAllByRole("term").map((term) => term.textContent)).toEqual(
      ["Name · name", "Count · count"],
    );
    expect(
      screen.getAllByRole("definition").map((each) => each.textContent),
    ).toEqual(["Kettle", "None"]);
  });

  it("says a value withheld from the reader is withheld, and draws nothing of it as none", () => {
    const drawn = drawing({ withheld: true }, field("text", { longest: 9 }));

    expect(drawn.textContent).toBe("You don't have permission to see this.");
    expect(drawn.textContent).not.toContain("None");
  });

  it.each([
    [
      "withheld",
      { withheld: true } as const,
      "text",
      "You don't have permission to see this.",
    ],
    ["unreadable", { value: "P9" }, "yes_no", UNREADABLE],
    ["kept in an earlier shape", { asKept: "x" }, "text", EARLIER_SHAPE],
    [
      "a moment on the reader's clock",
      { value: "2026-09-18T15:00:07+05:30" },
      "moment",
      /, your time$/,
    ],
  ])(
    "says a value is %s on a line of its own in an element a heading or a link may hold",
    (_case, shown, kind, said) => {
      const drawn = render(
        <ThemeProvider theme={theme}>
          <FilledValue
            field={field(kind)}
            label="Notes"
            shown={shown}
            shut={false}
          />
        </ThemeProvider>,
      ).container;

      const sentence = drawn.querySelector(".MuiTypography-body2")!;
      expect(sentence.tagName).toBe("SPAN");
      expect(sentence).toHaveTextContent(said);
      expect(sentence).toHaveStyle({ display: "block" });
      expect(drawn.querySelector("p, div")).toBeNull();
    },
  );

  it.each([
    ["fields", JSON.stringify({ ticket_notes: "Fire." })],
    ["many", JSON.stringify(["Fire.", "Smoke."])],
    ["text", JSON.stringify("R-7")],
  ])(
    "draws %s kept in a shape the step had before as the text it was kept as, set apart and read as nothing else, saying so beside it",
    (_shape, kept) => {
      const drawn = drawing({ asKept: kept });

      const text = drawn.querySelector("bdi")!;
      expect(text.textContent).toBe(kept);
      expect(getComputedStyle(text).whiteSpace).toBe("pre-wrap");
      expect(drawn).toHaveTextContent(EARLIER_SHAPE);
      expect(screen.queryAllByRole("term")).toEqual([]);
      expect(screen.queryAllByRole("listitem")).toEqual([]);
      expect(screen.queryByRole("button")).toBeNull();
    },
  );

  it.each([
    ["shut until opened where long texts are shut", true],
    ["in full, with nothing to open it by, where they are not", false],
  ])(
    "draws a long text kept in a shape the step had before %s, saying so beside it either way",
    (_case, shut) => {
      const kept = JSON.stringify("😀".repeat(200));

      const drawn = drawing({ asKept: kept }, undefined, shut);

      expect(
        screen.queryByRole("button", { name: "Notes, in full" }) !== null,
      ).toBe(shut);
      expect(drawn.querySelector("bdi")?.textContent).toBe(
        shut ? undefined : kept,
      );
      expect(drawn).toHaveTextContent(EARLIER_SHAPE);
    },
  );

  it("says none kept in a shape the step had before as none, and that it was kept so", () => {
    const drawn = drawing({ asKept: null });

    expect(drawn.textContent).toBe(`None${EARLIER_SHAPE}`);
    expect(drawn.querySelector("bdi")).toBeNull();
  });

  it("says nothing of a shape the step had before beside a value given in its own", () => {
    const drawn = drawing({ value: { ticket_notes: "Fire." } });

    expect(screen.getByRole("definition")).toHaveTextContent("Fire.");
    expect(drawn).not.toHaveTextContent(EARLIER_SHAPE);
  });

  it("says none as none", () => {
    expect(drawing({ value: null }, field("date")).textContent).toBe("None");
  });

  it.each([
    ["many, where its field holds one", ["a", "b"], field("text")],
    ["fields, where its field holds text", { a: "b" }, field("text")],
    ["one of many that is itself many", [["a"]], field("text", { most: 2 })],
    [
      "one of a field of a kind this build does not know",
      "red",
      field("colour"),
    ],
  ])(
    "says a value shaped as %s is something it cannot say yet",
    (_case, value, of) => {
      const drawn = drawing({ value }, of);

      expect(drawn.textContent).toBe(UNKNOWN);
      expect(drawn.querySelector("bdi")).toBeNull();
    },
  );

  it("draws a value nothing declares by its shape alone, fields under their names", () => {
    drawing({ value: { ticket: "Fire" } });

    expect(screen.getByRole("term")).toHaveTextContent("ticket");
    expect(screen.getByRole("definition")).toHaveTextContent("Fire");
  });

  it("names a field nothing declares as a person reads its name, on its label and on its control alike", () => {
    drawing({ value: { ticket_notes: "Fire.\nAgain." } });

    expect(screen.getByRole("term").textContent).toBe("ticket notes");
    expect(screen.getByRole("button").textContent).toBe(
      `Notes › ${setApart("ticket notes")}, in full`,
    );
    expect(document.body.textContent).toContain("ticket notes");
    expect(document.body.textContent).not.toContain("ticket_notes");
  });
});

describe("FilledFields", () => {
  it("draws only the fields declared, in declared order, leaving out a member nothing declares", () => {
    render(
      <FilledFields
        fields={[field("text", { name: "b", label: "Bee", longest: 5 })]}
        values={{ a: "left out", b: "kept" }}
        shut={true}
      />,
    );

    expect(screen.getAllByRole("term").map((term) => term.textContent)).toEqual(
      ["Bee · b"],
    );
    expect(screen.queryByText("left out")).toBeNull();
  });

  it("says a field declared but given nothing as none, even where its name is one every object inherits", () => {
    render(
      <FilledFields
        fields={[field("text", { name: "constructor", longest: 5 })]}
        values={{}}
        shut={true}
      />,
    );

    expect(screen.getByRole("definition")).toHaveTextContent(/^None$/);
  });

  it("runs fields nothing declares on in one line under their names, each after it", () => {
    const drawn = render(
      <FilledFields
        fields={undefined}
        values={{ ticket_notes: "Fire.", count: "2" }}
        shut={false}
        inLine
      />,
    ).container;

    expect(drawn.textContent).toBe("ticket notes: Fire. and count: 2");
    expect(drawn.querySelector("dl")).toBeNull();
  });
});

describe("inOneLine", () => {
  it.each([
    ["one", ["a"], "a"],
    ["two", ["a", "b"], "a and b"],
    ["three", ["a", "b", "c"], "a, b, and c"],
  ])(
    "joins %s as the reader's language joins things that all hold, each kept as it is drawn",
    (_case, items, said) => {
      const drawn = render(
        <p>{inOneLine(items.map((each) => <bdi>{each}</bdi>))}</p>,
      ).container;

      expect(drawn.textContent).toBe(said);
      expect(
        [...drawn.querySelectorAll("bdi")].map((each) => each.textContent),
      ).toEqual(items);
    },
  );

  it("joins in the reader's own language, not the page's", () => {
    reader.language = "de";

    const drawn = render(<p>{inOneLine(["a", "b"])}</p>).container;

    expect(drawn.textContent).toBe("a und b");
    expect(drawn.textContent).not.toContain("and");
  });
});

describe("labelledInLine", () => {
  it("draws a value after what it is called, the value kept as it is drawn", () => {
    const drawn = render(
      <p>{labelledInLine(<bdi>Name</bdi>, <bdi>Kettle</bdi>)}</p>,
    ).container;

    expect(drawn.textContent).toBe("Name: Kettle");
    expect(
      [...drawn.querySelectorAll("bdi")].map((each) => each.textContent),
    ).toEqual(["Name", "Kettle"]);
  });
});
