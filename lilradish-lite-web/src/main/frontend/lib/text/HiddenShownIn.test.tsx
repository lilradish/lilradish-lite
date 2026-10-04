import { ThemeProvider } from "@mui/material/styles";
import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import { theme } from "../theme/theme";
import { HiddenShownIn } from "./HiddenShownIn";

const of = (...codePoints: number[]) => String.fromCodePoint(...codePoints);

function drawing(sentence: (place: string) => string, text: string) {
  return render(
    <ThemeProvider theme={theme}>
      <p>
        <HiddenShownIn sentence={sentence} text={text} />
      </p>
    </ThemeProvider>,
  ).container;
}

describe("HiddenShownIn", () => {
  it("keeps the data isolated while giving only surrounding words the system hint style", () => {
    const drawn = render(
      <ThemeProvider theme={theme}>
        <p>
          <HiddenShownIn
            hint
            sentence={(place) => `Refused: ${place} (cut short here)`}
            text="Demo Sample"
          />
        </p>
      </ThemeProvider>,
    ).container;
    const paragraph = drawn.querySelector("p")!;
    const before = paragraph.querySelector("span")!;
    const data = paragraph.querySelector("bdi")!;
    const after = paragraph.querySelectorAll("span")[1];

    expect(paragraph).toHaveTextContent(
      "Refused: Demo Sample (cut short here)",
    );
    expect(before.textContent).toBe("Refused:");
    expect(after.textContent).toBe("(cut short here)");
    expect(getComputedStyle(before).fontStyle).toBe("italic");
    expect(getComputedStyle(after).fontStyle).toBe("italic");
    expect(data).toHaveTextContent("Demo Sample");
    expect(data.closest("span")).toBeNull();
  });

  it.each([
    [
      "between its words",
      (place: string) => `They said: ${place} (cut short here)`,
      "They said: Too short. (cut short here)",
    ],
    [
      "at its end",
      (place: string) => `Refused: ${place}`,
      "Refused: Too short.",
    ],
    [
      "at its start",
      (place: string) => `${place} (cut short here)`,
      "Too short. (cut short here)",
    ],
  ])(
    "sets the text %s, isolated, and nothing else in its place",
    (_case, sentence, whole) => {
      const drawn = drawing(sentence, "Too short.");

      expect(drawn.querySelector("p")?.textContent).toBe(whole);
      expect(
        [...drawn.querySelectorAll("bdi")].map((each) => each.textContent),
      ).toEqual(["Too short."]);
      expect(drawn.innerHTML).not.toContain(of(0));
    },
  );

  it.each([
    ["within it", `a${of(0)}b`, "Refused: aU+0000b", 1],
    ["alone", of(0), "Refused: U+0000", 1],
    [
      "at either end, twice over",
      `${of(0, 0)}a${of(0)}`,
      "Refused: U+0000 ×2aU+0000",
      2,
    ],
  ])(
    "never cuts the text where a null stands %s, drawing it as its marker inside the one isolate",
    (_case, text, whole, markers) => {
      const drawn = drawing((place) => `Refused: ${place}`, text);

      expect(drawn.querySelectorAll("bdi")).toHaveLength(1);
      expect(screen.getAllByRole("img")).toHaveLength(markers);
      expect(drawn.querySelector("p")?.textContent).toBe(whole);
      expect(drawn.innerHTML).not.toContain(of(0));
    },
  );

  it("draws a character in the text that would turn the order as its marker, inside the isolate", () => {
    const drawn = drawing((place) => `Refused: ${place}`, `ab${of(0x202e)}c`);
    const marker = screen.getByRole("img");

    expect(marker.closest("bdi")).toBe(drawn.querySelector("bdi"));
    expect(drawn.querySelector("p")?.textContent).toBe("Refused: abU+202Ec");
    expect(drawn.innerHTML).not.toContain(of(0x202e));
  });
});
