import { render } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import { isolated, isolatedInText } from "./isolated";

const FIRST_STRONG_ISOLATE = 0x2068;
const RIGHT_TO_LEFT_ISOLATE = 0x2067;
const POP_DIRECTIONAL_ISOLATE = 0x2069;
const RIGHT_TO_LEFT_OVERRIDE = 0x202e;

function pointsOf(text: string): number[] {
  return [...text].map((each) => each.codePointAt(0)!);
}

/** Text with its controls written as code points, never as characters in the source. */
function textOf(...parts: (string | number)[]): string {
  return parts
    .map((part) =>
      typeof part === "number" ? String.fromCodePoint(part) : part,
    )
    .join("");
}

describe("isolated", () => {
  it("puts words in an element of their own that takes its direction from them", () => {
    const { container } = render(<p>{isolated("Ada Lovelace")}</p>);

    const [only] = container.firstElementChild!.children;

    expect(container.firstElementChild!.children).toHaveLength(1);
    expect(only!.tagName).toBe("BDI");
    expect(only!.textContent).toBe("Ada Lovelace");
  });

  it("leaves a number as the bare text it is", () => {
    const { container } = render(<p>{isolated(3)}</p>);

    const paragraph = container.firstElementChild!;

    expect(paragraph.childNodes).toHaveLength(1);
    expect(paragraph.firstChild!.nodeType).toBe(Node.TEXT_NODE);
    expect(paragraph.textContent).toBe("3");
  });

  it("leaves a node exactly as the caller built it", () => {
    const { container } = render(<p>{isolated(<b>Ada Lovelace</b>)}</p>);

    const paragraph = container.firstElementChild!;

    expect(paragraph.innerHTML).toBe("<b>Ada Lovelace</b>");
    expect(paragraph.querySelector("bdi")).toBeNull();
  });
});

describe("isolatedInText", () => {
  it("brackets words holding no isolate control between a first-strong isolate and its close, changing nothing inside", () => {
    const words = textOf("Gr", RIGHT_TO_LEFT_OVERRIDE, "ace");

    expect(pointsOf(isolatedInText(words))).toEqual([
      FIRST_STRONG_ISOLATE,
      ...pointsOf(words),
      POP_DIRECTIONAL_ISOLATE,
    ]);
  });

  /** Left in, it would end the isolate in the middle of the words and set the rest loose. */
  it("drops a close the words hold with nothing open inside them", () => {
    const words = textOf(
      "Pay",
      POP_DIRECTIONAL_ISOLATE,
      RIGHT_TO_LEFT_OVERRIDE,
      "roll",
    );

    expect(pointsOf(isolatedInText(words))).toEqual([
      FIRST_STRONG_ISOLATE,
      ...pointsOf(textOf("Pay", RIGHT_TO_LEFT_OVERRIDE, "roll")),
      POP_DIRECTIONAL_ISOLATE,
    ]);
  });

  /** Left open, it would take this isolate's close as its own and leave this one open. */
  it("closes an isolate the words leave open before closing its own", () => {
    const words = textOf("Pay", RIGHT_TO_LEFT_ISOLATE, "roll");

    expect(pointsOf(isolatedInText(words))).toEqual([
      FIRST_STRONG_ISOLATE,
      ...pointsOf(words),
      POP_DIRECTIONAL_ISOLATE,
      POP_DIRECTIONAL_ISOLATE,
    ]);
  });

  /** Left in, each would end the paragraph, and the isolate with it, in the middle of the words. */
  it.each([0x000a, 0x000d, 0x001c, 0x001d, 0x001e, 0x0085, 0x2029])(
    "writes the paragraph separator %s as a space",
    (separator) => {
      const words = textOf("Pay", separator, RIGHT_TO_LEFT_OVERRIDE, "roll");

      expect(pointsOf(isolatedInText(words))).toEqual([
        FIRST_STRONG_ISOLATE,
        ...pointsOf(textOf("Pay ", RIGHT_TO_LEFT_OVERRIDE, "roll")),
        POP_DIRECTIONAL_ISOLATE,
      ]);
    },
  );

  /** A line separator ends a line and not a paragraph, so the isolate holds across it. */
  it("keeps a line separator as it is", () => {
    const words = textOf("Pay", 0x2028, "roll");

    expect(pointsOf(isolatedInText(words))).toEqual([
      FIRST_STRONG_ISOLATE,
      ...pointsOf(words),
      POP_DIRECTIONAL_ISOLATE,
    ]);
  });

  it("keeps an isolate the words open and close themselves as it is", () => {
    const words = textOf(
      "a",
      RIGHT_TO_LEFT_ISOLATE,
      "b",
      POP_DIRECTIONAL_ISOLATE,
      "c",
    );

    expect(pointsOf(isolatedInText(words))).toEqual([
      FIRST_STRONG_ISOLATE,
      ...pointsOf(words),
      POP_DIRECTIONAL_ISOLATE,
    ]);
  });
});
