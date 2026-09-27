import { render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";

import { namedDay, When, whenText } from "./When";

/**
 * Only `Date` is faked. Fake timers would also take the scheduling React and
 * Testing Library rely on, and nothing here needs them.
 */
function now(iso: string): void {
  vi.useFakeTimers({ toFake: ["Date"] });
  vi.setSystemTime(new Date(iso));
}

afterEach(() => {
  vi.useRealTimers();
});

describe("namedDay", () => {
  /** Built in the reader's own time, which is the time the days are counted in; a day with no name is dated. */
  it.each([
    [
      "the same day, hours apart",
      new Date(2026, 8, 18, 0, 5),
      new Date(2026, 8, 18, 23, 55),
      0,
    ],
    [
      "a crossing of midnight",
      new Date(2026, 8, 18, 23, 50),
      new Date(2026, 8, 19, 0, 10),
      1,
    ],
    [
      "a day the clocks go back",
      new Date(2026, 9, 24, 12, 0),
      new Date(2026, 9, 25, 12, 0),
      1,
    ],
    [
      "three days",
      new Date(2026, 8, 15, 7, 0),
      new Date(2026, 8, 18, 15, 0),
      null,
    ],
    [
      "a day after now",
      new Date(2026, 8, 19, 7, 0),
      new Date(2026, 8, 18, 15, 0),
      null,
    ],
  ])("counts %s by the calendar", (_case, at, asOf, days) => {
    expect(namedDay(at, asOf)).toBe(days);
  });
});

describe("whenText", () => {
  it("names today by the day rather than by the date", () => {
    now("2026-09-18T15:00:00Z");

    const said = whenText("2026-09-18T07:00:00Z");

    expect(said).toContain("today");
    expect(said).not.toContain("Sep 18");
  });

  it("names yesterday by the day rather than by the date", () => {
    now("2026-09-18T15:00:00Z");

    const said = whenText("2026-09-17T07:00:00Z");

    expect(said).toContain("yesterday");
    expect(said).not.toContain("Sep 17");
  });

  it("falls back to a date once the day has no name", () => {
    now("2026-09-18T15:00:00Z");

    const said = whenText("2026-09-15T07:00:00Z");

    expect(said).toContain("Sep 15");
    expect(said).not.toContain("days ago");
  });

  /**
   * Ten minutes apart and two different days. The reader is naming the day, so
   * the clock distance between them is not what decides.
   */
  it("counts a crossing of midnight as a whole day", () => {
    now("2026-09-18T22:10:00Z"); // 00:10 in Berlin, the 19th

    const said = whenText("2026-09-18T21:50:00Z");

    expect(said).toContain("yesterday");
    expect(said).not.toContain("today");
  });

  /**
   * Berlin loses an hour overnight on 2026-03-29, so the two local midnights
   * are 23 hours apart — 0.958 days. Flooring that would call yesterday today,
   * twice a year, and only in zones that change their clocks.
   */
  it("still calls it yesterday across the night the clocks change", () => {
    now("2026-03-30T08:00:00Z"); // 10:00 in Berlin, the day after the change

    const said = whenText("2026-03-29T07:00:00Z");

    expect(said).toContain("yesterday");
    expect(said).not.toContain("today");
  });

  it("says a date for an instant still to come, not a countdown to it", () => {
    now("2026-09-18T15:00:00Z");

    const said = whenText("2026-09-21T07:00:00Z");

    expect(said).toContain("Sep 21");
    expect(said).not.toContain("in 3 days");
  });

  it("hands back whatever arrived when it is not a time at all", () => {
    expect(whenText("not a date")).toBe("not a date");
  });
});

describe("When", () => {
  it("keeps the exact instant beside the readable one", () => {
    now("2026-09-18T15:00:00Z");

    render(<When iso="2026-09-17T07:00:00Z" />);

    const shown = screen.getByText(/yesterday/);

    expect(shown.tagName).toBe("TIME");
    expect(shown).toHaveAttribute("dateTime", "2026-09-17T07:00:00Z");
    expect(shown).toHaveAttribute("title", "2026-09-17T07:00:00Z");
  });

  it("says the same thing as the bare-string form", () => {
    now("2026-09-18T15:00:00Z");

    render(<When iso="2026-09-17T07:00:00Z" />);

    expect(screen.getByRole("time").textContent).toBe(
      whenText("2026-09-17T07:00:00Z"),
    );
  });

  it("shows what arrived, and no element, when it is not a time at all", () => {
    const { container } = render(<When iso="not a date" />);

    expect(container.textContent).toBe("not a date");
    expect(container.querySelector("time")).toBeNull();
  });
});
