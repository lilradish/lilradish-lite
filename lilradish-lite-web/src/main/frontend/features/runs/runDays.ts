import { namedDay } from "../../lib/time/When";

/** The days the runs are listed under, in the reader's own time, in drawn order. */
export type RunDay = "today" | "yesterday" | "earlier";

export interface RunsOfADay<T> {
  readonly day: RunDay;
  readonly rows: readonly T[];
}

const DAYS: readonly RunDay[] = ["today", "yesterday", "earlier"];

/**
 * Each row under the day of the instant `at` gives it, in the order given, a day holding none left out. A day
 * `When` names is that day; one later than now is today, and any other, or an instant that is none, is earlier.
 */
export function byDay<T>(
  rows: readonly T[],
  at: (row: T) => string,
  now: Date,
): readonly RunsOfADay<T>[] {
  const under = new Map<RunDay, T[]>(DAYS.map((day) => [day, []]));
  for (const row of rows) {
    under.get(dayOf(new Date(at(row)), now))!.push(row);
  }
  return DAYS.map((day) => ({ day, rows: under.get(day)! })).filter(
    (listed) => listed.rows.length > 0,
  );
}

function dayOf(at: Date, now: Date): RunDay {
  const days = Number.isNaN(at.getTime())
    ? null
    : namedDay(at > now ? now : at, now);
  return days === 0 ? "today" : days === 1 ? "yesterday" : "earlier";
}
