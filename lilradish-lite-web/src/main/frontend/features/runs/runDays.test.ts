import { describe, expect, it } from "vitest";

import { namedDay } from "../../lib/time/When";
import { byDay } from "./runDays";

/** 15:00 in Berlin, where the specs keep time, on the 18th. */
const NOW = new Date("2026-09-18T13:00:00Z");

interface Happened {
  readonly name: string;
  readonly at: string;
}

function atOf(row: Happened): string {
  return row.at;
}

describe("byDay", () => {
  it("lists each run under the reader's day of its instant, each day's runs in the order given", () => {
    const rows = [
      { name: "this afternoon", at: "2026-09-18T12:00:00Z" },
      { name: "just after midnight", at: "2026-09-17T22:10:00Z" },
      { name: "just before midnight", at: "2026-09-17T21:50:00Z" },
      { name: "last week", at: "2026-09-11T08:00:00Z" },
      { name: "two days ago", at: "2026-09-16T08:00:00Z" },
    ];

    const listed = byDay(rows, atOf, NOW);

    expect(
      listed.map(({ day, rows: under }) => [day, under.map((row) => row.name)]),
    ).toEqual([
      ["today", ["this afternoon", "just after midnight"]],
      ["yesterday", ["just before midnight"]],
      ["earlier", ["last week", "two days ago"]],
    ]);
  });

  it("leaves out a day no run falls on", () => {
    const rows = [{ name: "last week", at: "2026-09-11T08:00:00Z" }];

    const listed = byDay(rows, atOf, NOW);

    expect(listed.map(({ day }) => day)).toEqual(["earlier"]);
    expect(listed.flatMap((each) => each.rows)).toEqual(rows);
  });

  it("lists no day for no runs", () => {
    expect(byDay([], atOf, NOW)).toEqual([]);
  });

  it.each([
    [
      "later today, a clock ahead of the reader's",
      "2026-09-18T14:00:00Z",
      "today",
    ],
    ["at what is no instant", "not a time", "earlier"],
  ])("lists a run %s", (_case, at, day) => {
    const listed = byDay([{ name: "it", at }], atOf, NOW);

    expect(listed.map((each) => each.day)).toEqual([day]);
    expect(listed.flatMap((each) => each.rows)).toHaveLength(1);
  });

  it("lists a run on a later day under today, though When still dates it rather than naming it", () => {
    const at = "2026-09-19T08:00:00Z";

    const listed = byDay([{ name: "tomorrow", at }], atOf, NOW);

    expect(listed.map((each) => each.day)).toEqual(["today"]);
    expect(namedDay(new Date(at), NOW)).toBeNull();
  });
});
