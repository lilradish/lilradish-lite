import { ThemeProvider } from "@mui/material/styles";
import { act, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";

import type { Turnaway } from "../../../api/stepTries";
import { theme } from "../../../lib/theme/theme";
import { requestsTo, serving } from "../../../testutil/answering";
import { Turnaways } from "./Turnaways";

const LEFT_TO_RIGHT_MARK = String.fromCodePoint(0x200e);

const AT = "2026-09-26T09:05:00Z";

function drawing(turnaway: Turnaway) {
  return render(
    <ThemeProvider theme={theme}>
      <Turnaways turnedAway={[turnaway]} />
    </ThemeProvider>,
  ).container;
}

describe("Turnaways", () => {
  it("updates a turned-away day at midnight without another request", () => {
    vi.useFakeTimers({ toFake: ["Date"] });
    vi.setSystemTime(new Date("2026-10-03T21:59:30Z"));
    const sent = serving({});
    const drawn = drawing({
      at: "2026-10-03T21:59:00Z",
      sentAgain: false,
    });
    const line = drawn.querySelector("li")!;
    expect(line).toHaveTextContent("11:59 PM today");

    vi.setSystemTime(new Date("2026-10-03T22:00:00Z"));
    act(() => document.dispatchEvent(new Event("visibilitychange")));

    expect(line).toHaveTextContent("11:59 PM yesterday");
    expect(requestsTo(sent)).toEqual([]);
  });
  it.each([
    [false, "The model said: Not U+200Enow."],
    [true, "The model said: Not U+200Enow. (cut short here)"],
  ])(
    "draws a mark setting a direction in what the model said, cut short %s, as a marker named for it, the words isolated, never the mark itself",
    (cut, said) => {
      const drawn = drawing({
        at: AT,
        said: `Not ${LEFT_TO_RIGHT_MARK}now.`,
        cut,
        sentAgain: false,
      });
      const marker = screen.getByRole("img", {
        name: "A text-direction character is here, shown instead of applied: U+200E.",
      });

      expect(drawn.querySelector("li > p")?.textContent).toBe(said);
      expect(marker.closest("bdi")?.textContent).toBe("Not U+200Enow.");
      expect(drawn.innerHTML).not.toContain(LEFT_TO_RIGHT_MARK);
      const leading = screen.getByText("The model said:");
      expect(getComputedStyle(leading).fontStyle).toBe("italic");
      expect(leading).not.toContainElement(marker.closest("bdi"));
      if (cut) {
        expect(
          getComputedStyle(screen.getByText("(cut short here)")).fontStyle,
        ).toBe("italic");
      } else {
        expect(screen.queryByText("(cut short here)")).toBeNull();
      }
    },
  );

  it("says what the model said is withheld where it is, with no words and no marker", () => {
    const drawn = drawing({ at: AT, withheld: true, sentAgain: false });

    expect(drawn.querySelector("li > p")?.textContent).toBe(
      "You don't have permission to see what the model said.",
    );
    expect(drawn.querySelector("bdi")).toBeNull();
    expect(screen.queryByRole("img")).toBeNull();
    expect(screen.queryByText("The model said:")).toBeNull();
  });

  it("says only when, where the model said nothing", () => {
    const drawn = drawing({ at: AT, sentAgain: false });

    expect(drawn.querySelector("li > p")).toBeNull();
    expect(drawn.querySelector("li")?.textContent).toMatch(/^Turned away /);
  });
});

afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
});
