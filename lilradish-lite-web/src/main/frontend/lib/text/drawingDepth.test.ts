import Markdown from "react-markdown";
import { describe, expect, it } from "vitest";

import CASES from "./drawingDepth.cases.json";
import { drawingDepth, drawingTooDeep, emphasisDepth } from "./drawingDepth";

/** A row of the depth table, shaped for the server's half of the limit to read as well as this one. */
interface Case {
  readonly typed: string;
  readonly times?: number;
  readonly depth: number;
  readonly emphasis: number;
  readonly accepted: boolean;
}

interface MarkdownNode {
  readonly type: string;
  readonly children?: readonly MarkdownNode[];
}

const DEPTH_CASES = CASES.depth as readonly Case[];

const BYTE_ORDER_MARK = String.fromCharCode(0xfeff);

const NO_BREAK_SPACE = String.fromCharCode(0xa0);

const IDEOGRAPHIC_SPACE = String.fromCharCode(0x3000);

const CASES_PER_SHAPE = {
  bent: 3000,
  scrambled: 3000,
  deep: 3000,
  emphasised: 3000,
  jumbled: 3000,
  stacked: 1000,
} as const;

const CONTAINERS: ReadonlySet<string> = new Set(["blockquote", "listItem"]);

const EMPHASES: ReadonlySet<string> = new Set(["emphasis", "strong"]);

/** How deep each shape must nest somewhere in its run, so that a run never counting fewer says something. */
const DEEPEST_MADE = { bent: 9, scrambled: 4, deep: 33 } as const;

/** The same for emphasis. */
const DEEPEST_EMPHASISED = { emphasised: 10, jumbled: 4, stacked: 33 } as const;

function typedIn(each: Case): string {
  return each.typed.repeat(each.times ?? 1);
}

/** How deep the reader's own Markdown nests the nodes named, in the tree it lays a drawing out from. */
function nestedDepth(drawing: string, nesting: ReadonlySet<string>): number {
  let tree: MarkdownNode | undefined;
  Markdown({
    children: drawing,
    remarkPlugins: [
      () => (parsed: MarkdownNode) => {
        tree = parsed;
        return { type: "root", children: [] };
      },
    ],
  });
  let deepest = 0;
  const pending: [MarkdownNode, number][] = [[tree!, 0]];
  for (let next = pending.pop(); next !== undefined; next = pending.pop()) {
    const [node, above] = next;
    const depth = above + (nesting.has(node.type) ? 1 : 0);
    deepest = Math.max(deepest, depth);
    for (const child of node.children ?? []) {
      pending.push([child, depth]);
    }
  }
  return deepest;
}

function seeded(seed: number): () => number {
  let state = seed >>> 0;
  return () => {
    state = (state + 0x6d2b79f5) >>> 0;
    let mixed = state;
    mixed = Math.imul(mixed ^ (mixed >>> 15), mixed | 1);
    mixed ^= mixed + Math.imul(mixed ^ (mixed >>> 7), mixed | 61);
    return ((mixed ^ (mixed >>> 14)) >>> 0) / 4294967296;
  };
}

/** Nested quotes and lists made at random, the same ones on every run. */
class Drawings {
  private readonly random: () => number;

  constructor(seed: number) {
    this.random = seeded(seed);
  }

  /** Written as a person would, then bent: continuations dropped, widened or narrowed, tabs, blank lines, fences. */
  bent(): string {
    const open: { readonly quote: boolean; readonly offset: number }[] = [];
    const lines: string[] = [];
    const lineCount = 1 + this.below(12);
    for (let line = 0; line < lineCount; line += 1) {
      if (this.chance(0.1)) {
        lines.push(this.chance(0.5) ? "" : this.gap(this.below(6)));
        continue;
      }
      open.length = this.chance(0.2)
        ? this.below(open.length + 1)
        : Math.max(0, open.length - (this.chance(0.3) ? this.below(2) : 0));
      let text = "";
      for (const each of open) {
        text += each.quote
          ? this.gap(this.chance(0.2) ? this.below(4) : 0) +
            ">" +
            this.pick([" ", " ", " ", "\t", ""])
          : this.gap(
              Math.max(
                0,
                each.offset +
                  (this.chance(0.15) ? this.below(3) : 0) -
                  (this.chance(0.1) ? 1 : 0),
              ),
            );
      }
      const opened = this.chance(0.15)
        ? 0
        : 1 + this.below(this.chance(0.1) ? 8 : 3);
      for (let each = 0; each < opened; each += 1) {
        text += this.chance(0.2) ? this.gap(this.below(4)) : "";
        if (this.chance(0.4)) {
          text += this.chance(0.7) ? "> " : ">";
          open.push({ quote: true, offset: 0 });
        } else {
          const marker = this.listMarker();
          const after = this.pick([0, 1, 1, 1, 2, 3, 4, 5]);
          text +=
            marker + (after > 0 && this.chance(0.2) ? "\t" : " ".repeat(after));
          open.push({
            quote: false,
            offset: marker.length + Math.max(1, Math.min(after, 4)),
          });
        }
      }
      lines.push(
        text +
          this.pick(["a", "", "```", "# h", "---", "<div>", "    code", " x"]),
      );
    }
    return lines.join(this.pick(["\n", "\n", "\n", "\r", "\r\n"]));
  }

  /** Any run of the characters the estimate reads, and a few it stops at. */
  scrambled(): string {
    const alphabet = [
      ..." \t>-*+19.)a\n\r`",
      " ",
      ">",
      NO_BREAK_SPACE,
      BYTE_ORDER_MARK,
    ];
    let text = "";
    const length = 1 + this.below(40);
    for (let each = 0; each < length; each += 1) {
      text += this.pick(alphabet);
    }
    return text;
  }

  /** Well-formed nesting up to 40 deep, line feeds only: where the limit is met. */
  deep(): string {
    const open: { readonly quote: boolean; readonly offset: number }[] = [];
    const lines: string[] = [];
    const lineCount = 1 + this.below(10);
    for (let line = 0; line < lineCount; line += 1) {
      if (this.chance(0.1)) {
        lines.push("");
        continue;
      }
      open.length = this.chance(0.7)
        ? open.length
        : this.below(open.length + 1);
      let text = open
        .map((each) => (each.quote ? "> " : " ".repeat(each.offset)))
        .join("");
      const opened = this.below(Math.min(7, 41 - open.length));
      for (let each = 0; each < opened; each += 1) {
        if (this.chance(0.4)) {
          text += "> ";
          open.push({ quote: true, offset: 0 });
        } else {
          const marker = this.chance(0.6)
            ? this.pick(["-", "*", "+"])
            : String(1 + this.below(9)) + this.pick([".", ")"]);
          text += marker + " ";
          open.push({ quote: false, offset: marker.length + 1 });
        }
      }
      lines.push(text + (this.chance(0.9) ? "x" : ""));
    }
    return lines.join("\n");
  }

  /** Emphasis nested at random among words, then bent: delimiters dropped, swapped or escaped, lines broken. */
  emphasised(): string {
    const paragraphs: string[] = [];
    const count = 1 + this.below(4);
    for (let each = 0; each < count; each += 1) {
      paragraphs.push(this.emphasis(this.below(9)));
    }
    return paragraphs.join(
      this.pick([
        "\n\n",
        "\n \t\n",
        "\r\r",
        "\r\n\r\n",
        `\n${NO_BREAK_SPACE}\n`,
        `\n${IDEOGRAPHIC_SPACE}\n`,
        "\n",
        "\r",
        " ",
      ]),
    );
  }

  /** Any run of the characters the emphasis count reads, and a few beside them. */
  jumbled(): string {
    const alphabet = [
      ..."**__ a1\\.\n\r\t",
      NO_BREAK_SPACE,
      BYTE_ORDER_MARK,
      "é",
      "日",
    ];
    let text = "";
    const length = 1 + this.below(40);
    for (let each = 0; each < length; each += 1) {
      text += this.pick(alphabet);
    }
    return text;
  }

  /** Well-formed emphasis up to 40 deep, every opening before every closing: where the limit is met. */
  stacked(): string {
    const delimiters: string[] = [];
    const levels = 1 + this.below(40);
    for (let each = 0; each < levels; each += 1) {
      delimiters.push(this.pick(["*", "_", "**", "__"]));
    }
    const opened = delimiters.map((each) => `${each}${this.word()}`);
    const closed = delimiters.map((each) => `${this.word()}${each}`);
    return `${opened.join(this.pick([" ", "\n"]))} ${closed.reverse().join(" ")}`;
  }

  private emphasis(levels: number): string {
    if (levels === 0) {
      return this.word();
    }
    const kept = this.pick(["*", "_", "**", "__", "***", "___"]);
    const bent = () =>
      this.chance(0.1) ? this.pick(["", "\\*", "*", "_", "\\_"]) : kept;
    return (
      this.pick(["", " ", "a ", "é", "日本", ".", "(", "\n"]) +
      bent() +
      this.emphasis(levels - 1) +
      bent() +
      this.pick(["", " ", " a", "é", "語", ".", ")", "\n"])
    );
  }

  private word(): string {
    return this.pick([
      "a",
      "word",
      "snake_case",
      "{{field_name}}",
      "é_é",
      "日本",
      "x.y",
      "!",
      `a${NO_BREAK_SPACE}b`,
    ]);
  }

  private listMarker(): string {
    if (this.chance(0.25)) {
      return this.pick(["-", "*", "+"]);
    }
    const digits = this.pick([1, 1, 1, 2, 3, 9, 10]);
    let number = String(1 + this.below(9));
    while (number.length < digits) {
      number += String(this.below(10));
    }
    return number + this.pick([".", ")"]);
  }

  private gap(columns: number): string {
    return columns >= 4 && this.chance(0.3)
      ? "\t" + this.gap(columns - 4)
      : " ".repeat(columns);
  }

  private below(bound: number): number {
    return Math.floor(this.random() * bound);
  }

  private chance(probability: number): boolean {
    return this.random() < probability;
  }

  private pick<T>(choices: readonly T[]): T {
    return choices[this.below(choices.length)]!;
  }
}

describe("drawingDepth", () => {
  it("holds cases to count, an empty table agreeing with anything", () => {
    expect(DEPTH_CASES.length).toBeGreaterThan(0);
  });

  it.each(DEPTH_CASES)(
    "counts %j as deep as the depth table gives it",
    (each) => {
      expect(drawingDepth(typedIn(each))).toBe(each.depth);
    },
  );

  it.each(Object.keys(DEEPEST_MADE) as (keyof typeof DEEPEST_MADE)[])(
    "never counts a %s drawing shallower than the reader's Markdown nests it",
    (shape) => {
      const drawings = new Drawings(20261001);
      const undercounted: { drawing: string; nested: number }[] = [];
      let deepestNested = 0;
      for (let each = 0; each < CASES_PER_SHAPE[shape]; each += 1) {
        const drawing = drawings[shape]();
        const nested = nestedDepth(drawing, CONTAINERS);
        deepestNested = Math.max(deepestNested, nested);
        if (drawingDepth(drawing) < nested) {
          undercounted.push({ drawing, nested });
        }
      }

      expect(undercounted).toEqual([]);
      expect(deepestNested).toBeGreaterThanOrEqual(DEEPEST_MADE[shape]);
    },
  );
});

describe("emphasisDepth", () => {
  it.each(DEPTH_CASES)(
    "counts the emphasis of %j as deep as the depth table gives it",
    (each) => {
      expect(emphasisDepth(typedIn(each))).toBe(each.emphasis);
    },
  );

  it.each(
    Object.keys(DEEPEST_EMPHASISED) as (keyof typeof DEEPEST_EMPHASISED)[],
  )(
    "never counts the emphasis of a %s drawing shallower than the reader's Markdown nests it",
    (shape) => {
      const drawings = new Drawings(20261001);
      const undercounted: { drawing: string; nested: number }[] = [];
      let deepestNested = 0;
      for (let each = 0; each < CASES_PER_SHAPE[shape]; each += 1) {
        const drawing = drawings[shape]();
        const nested = nestedDepth(drawing, EMPHASES);
        deepestNested = Math.max(deepestNested, nested);
        if (emphasisDepth(drawing) < nested) {
          undercounted.push({ drawing, nested });
        }
      }

      expect(undercounted).toEqual([]);
      expect(deepestNested).toBeGreaterThanOrEqual(DEEPEST_EMPHASISED[shape]);
    },
  );
});

describe("drawingTooDeep", () => {
  it.each(DEPTH_CASES)(
    "refuses %j exactly where the depth table refuses it",
    (each) => {
      expect(drawingTooDeep(typedIn(each))).toBe(!each.accepted);
    },
  );
});
