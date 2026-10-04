import { ThemeProvider } from "@mui/material/styles";
import { render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";

import { theme } from "../theme/theme";
import { HiddenShown, HiddenShownAcross } from "./HiddenShown";

afterEach(() => {
  vi.restoreAllMocks();
});

const of = (...codePoints: number[]) => String.fromCodePoint(...codePoints);

const TURNS_ORDER = (code: string) =>
  `A character that would turn the order text is shown in is here, drawn instead of obeyed: U+${code}.`;

const SHOWS_NOTHING = (code: string) =>
  `A character that shows nothing is here: U+${code}.`;

function drawing(text: string) {
  return render(
    <ThemeProvider theme={theme}>
      <bdi>
        <HiddenShown text={text} />
      </bdi>
    </ThemeProvider>,
  ).container;
}

describe("HiddenShown", () => {
  it("draws text holding nothing hidden as it is, with no marker", () => {
    const drawn = drawing("A kettle leaks.\n\tTwice.");

    expect(drawn.querySelector("bdi")?.textContent).toBe(
      "A kettle leaks.\n\tTwice.",
    );
    expect(screen.queryByRole("img")).toBeNull();
  });

  it("draws an override where it stands as its code, named for what it would do, and never the override itself", () => {
    const drawn = drawing(`abc${of(0x202e)}fed`);
    const marker = screen.getByRole("img", { name: TURNS_ORDER("202E") });

    expect(marker).toHaveTextContent(/^U\+202E$/);
    expect(marker).toHaveAttribute("title", TURNS_ORDER("202E"));
    expect(drawn.querySelector("bdi")?.textContent).toBe("abcU+202Efed");
    expect(drawn.innerHTML).not.toContain(of(0x202e));
  });

  it.each([
    ["a soft hyphen, written in four digits", 0xad, "00AD"],
    ["a zero-width space", 0x200b, "200B"],
    ["a tag, written in five", 0xe0041, "E0041"],
    ["a carriage return not ending a line", 0xd, "000D"],
    ["an escape", 0x1b, "001B"],
    ["a delete", 0x7f, "007F"],
    ["a next line", 0x85, "0085"],
    ["a line separator", 0x2028, "2028"],
    ["a paragraph separator", 0x2029, "2029"],
    ["a null", 0x0, "0000"],
  ])(
    "draws %s as its code, said to show nothing, and never the character",
    (_case, codePoint, code) => {
      const drawn = drawing(`a${of(codePoint)}b`);
      const marker = screen.getByRole("img", { name: SHOWS_NOTHING(code) });

      expect(marker).toHaveTextContent(new RegExp(`^U\\+${code}$`));
      expect(drawn.querySelector("bdi")?.textContent).toBe(`aU+${code}b`);
      expect(drawn.innerHTML).not.toContain(of(codePoint));
    },
  );

  it("draws a joiner doing its work in an emoji as it is, with no marker", () => {
    const drawn = drawing(of(0x2764, 0xfe0f, 0x200d, 0x1f525));

    expect(drawn.querySelector("bdi")?.textContent).toBe(
      of(0x2764, 0xfe0f, 0x200d, 0x1f525),
    );
    expect(screen.queryByRole("img")).toBeNull();
  });

  it("draws a value of only a control as exactly one marker said to show nothing, never the control", () => {
    const drawn = drawing(of(0x1));
    const marker = screen.getByRole("img", { name: SHOWS_NOTHING("0001") });

    expect(screen.getAllByRole("img")).toEqual([marker]);
    expect(drawn.querySelector("bdi")?.textContent).toBe("U+0001");
    expect(drawn.innerHTML).not.toContain(of(0x1));
  });

  it("draws a line ended with a carriage return and a line feed as it is, with no marker", () => {
    const drawn = drawing("a\r\nb");

    expect(screen.queryByRole("img")).toBeNull();
    expect(drawn.querySelector("bdi")?.textContent).toBe("a\r\nb");
  });

  it("draws a carriage return before one ending a line as its marker, and the line's end as it is", () => {
    const drawn = drawing("a\r\r\nb");

    expect(
      screen.getAllByRole("img").map((each) => each.getAttribute("title")),
    ).toEqual([SHOWS_NOTHING("000D")]);
    expect(drawn.querySelector("bdi")?.textContent).toBe("aU+000D\r\nb");
  });

  it("draws a joiner after a control as a marker of its own, never left to work", () => {
    const drawn = drawing(`a${of(0x1, 0x200d)}b`);

    expect(screen.getAllByRole("img").map((each) => each.textContent)).toEqual([
      "U+0001",
      "U+200D",
    ]);
    expect(drawn.querySelector("bdi")?.textContent).toBe("aU+0001U+200Db");
    expect(drawn.innerHTML).not.toContain(of(0x200d));
  });

  it("draws a control over and over as one marker saying how many", () => {
    const drawn = drawing(of(0x1).repeat(1_234));

    expect(screen.getByRole("img")).toHaveAttribute(
      "title",
      "1,234 characters that show nothing are here, one after another: U+0001.",
    );
    expect(drawn.querySelector("bdi")?.textContent).toBe("U+0001 ×1,234");
    expect(drawn.innerHTML).not.toContain(of(0x1));
  });

  it("draws no marker for a value of controls needing one more than are drawn, leaving them out and saying how many", () => {
    const drawn = drawing(`${`a${of(0x1)}`.repeat(1_000)}b\r`);

    expect(screen.queryByRole("img")).toBeNull();
    expect(drawn.querySelector("bdi > span")).toHaveTextContent(
      /^1,001 characters that show nothing or would turn the order/,
    );
    expect(drawn.innerHTML).not.toContain(of(0x1));
    expect(drawn.innerHTML).not.toContain("\r");
  });

  it("draws a character over and over as one marker saying how many, its count grouped, never the character", () => {
    const drawn = drawing(`a${of(0x200b).repeat(8_388_608)}b`);
    const marker = screen.getByRole("img", {
      name: "8,388,608 characters that show nothing are here, one after another: U+200B.",
    });

    expect(screen.getAllByRole("img")).toEqual([marker]);
    expect(marker).toHaveTextContent(/^U\+200B ×8,388,608$/);
    expect(drawn.querySelector("bdi")?.textContent).toBe("aU+200B ×8,388,608b");
    expect(drawn.innerHTML).not.toContain(of(0x200b));
  });

  it("draws an override over and over as one marker saying how many would turn the order", () => {
    drawing(`a${of(0x202e, 0x202e)}b`);

    expect(screen.getByRole("img")).toHaveAttribute(
      "title",
      "2 characters that would turn the order text is shown in are here, one after another, drawn instead of obeyed: U+202E.",
    );
    expect(screen.getByRole("img")).toHaveTextContent(/^U\+202E ×2$/);
  });

  it("draws two characters each over and over as a marker apiece", () => {
    drawing(of(0x200b, 0x200b, 0xfeff));

    expect(screen.getAllByRole("img").map((each) => each.textContent)).toEqual([
      "U+200B ×2",
      "U+FEFF",
    ]);
  });

  it("names a marker by its title alone, so it is not read again as its description", () => {
    drawing(`a${of(0x200b)}b`);
    const marker = screen.getByRole("img", { name: SHOWS_NOTHING("200B") });

    expect(marker).not.toHaveAttribute("aria-label");
    expect(marker).toHaveAttribute("title", SHOWS_NOTHING("200B"));
  });

  it("draws every marker of a value needing as many as are drawn, and says nothing left out", () => {
    const drawn = drawing(
      `${`a${of(0x200b)}`.repeat(999)}a${of(0x200b, 0x200b)}`,
    );

    expect(screen.getAllByRole("img")).toHaveLength(1_000);
    expect(drawn.textContent).not.toContain("left out");
  });

  it("draws no marker for a value needing one more than are drawn, leaving out every character it would mark and saying how many above the rest", () => {
    const drawn = drawing(
      `${`a${of(0x200b)}`.repeat(1_000)}b${of(0x202e, 0x202e)}`,
    );
    const above = drawn.querySelector("bdi > span");

    expect(screen.queryByRole("img")).toBeNull();
    expect(above).toHaveTextContent(
      "1,002 characters that show nothing or would turn the order text is shown in are left out of what is drawn here: too many to mark where each stands.",
    );
    expect(above).toHaveAttribute("dir", "auto");
    expect(drawn.querySelector("bdi")?.textContent).toBe(
      `${above?.textContent}${"a".repeat(1_000)}b`,
    );
    expect(drawn.innerHTML).not.toContain(of(0x200b));
    expect(drawn.innerHTML).not.toContain(of(0x202e));
  });

  it("draws each marker as a box of its own in a fixed pitch, set apart in its own direction", () => {
    drawing(`a${of(0x200b)}b`);
    const marker = screen.getByRole("img");

    expect(marker).toHaveAttribute("dir", "ltr");
    expect(getComputedStyle(marker).borderTopStyle).toBe("solid");
    expect(getComputedStyle(marker).display).toBe("inline-block");
    expect(getComputedStyle(marker).fontFamily).toBe("ui-monospace, monospace");
  });

  it("draws nothing of nothing", () => {
    const drawn = drawing("");

    expect(drawn.querySelector("bdi")).toBeEmptyDOMElement();
  });
});

describe("HiddenShownAcross", () => {
  const HALF = `a${of(0x200b)}`.repeat(500);

  const LAST = `b${of(0x202e, 0x202e, 0x202e)}`;

  const LEFT_OUT = (count: string) =>
    `${count} characters that show nothing or would turn the order text is shown in are left out of what is drawn here: too many to mark where each stands.`;

  /** Each text of `texts` drawn apart within one value holding them all, a list and fields deep. */
  function drawingAcross(texts: readonly string[]) {
    return render(
      <ThemeProvider theme={theme}>
        <HiddenShownAcross value={{ notes: texts.slice(1), title: texts[0] }}>
          {texts.map((text, at) => (
            <bdi key={at}>
              <HiddenShown text={text} />
            </bdi>
          ))}
        </HiddenShownAcross>
      </ThemeProvider>,
    ).container;
  }

  it("draws every marker of a value whose texts need as many across them as are drawn, a text standing twice counted twice, and says nothing left out", () => {
    const drawn = drawingAcross([HALF, HALF]);

    expect(screen.getAllByRole("img")).toHaveLength(1_000);
    expect(drawn.textContent).not.toContain("left out");
  });

  it("draws no marker in any text of a value needing one more across them than are drawn, saying once above them all how many characters it left out of them all", () => {
    const drawn = drawingAcross([HALF, LAST, HALF]);

    expect(screen.queryByRole("img")).toBeNull();
    expect(drawn.firstElementChild).toHaveTextContent(LEFT_OUT("1,003"));
    expect(drawn.firstElementChild).toHaveAttribute("dir", "auto");
    expect(screen.getAllByText(/left out/)).toHaveLength(1);
    expect(
      [...drawn.querySelectorAll("bdi")].map((each) => each.textContent),
    ).toEqual(["a".repeat(500), "b", "a".repeat(500)]);
    expect(drawn.innerHTML).not.toContain(of(0x200b));
    expect(drawn.innerHTML).not.toContain(of(0x202e));
  });

  it("draws a value within another as the one around it is, saying nothing of it again", () => {
    const drawn = render(
      <ThemeProvider theme={theme}>
        <HiddenShownAcross value={[HALF, HALF, LAST]}>
          <HiddenShown text={HALF} />
          <HiddenShownAcross value={[HALF, LAST]}>
            <bdi>
              <HiddenShown text={LAST} />
            </bdi>
          </HiddenShownAcross>
        </HiddenShownAcross>
      </ThemeProvider>,
    ).container;

    expect(screen.queryByRole("img")).toBeNull();
    expect(screen.getAllByText(/left out/)).toHaveLength(1);
    expect(drawn.querySelector("bdi")?.textContent).toBe("b");
  });

  it("refuses to draw a text within a value that does not hold it, rather than judge it alone", () => {
    vi.spyOn(console, "error").mockImplementation(() => {});

    expect(() =>
      render(
        <HiddenShownAcross value={[HALF]}>
          <HiddenShown text={LAST} />
        </HiddenShownAcross>,
      ),
    ).toThrow("a text was drawn within a value that does not hold it");
  });
});
