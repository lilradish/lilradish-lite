import { useSyncExternalStore } from "react";

import { readersIntl } from "../../i18n/intl";
import { say } from "../../i18n/lib";

const DAY_MILLIS = 24 * 60 * 60 * 1000;

const listeners = new Set<() => void>();
let day = 0;
let timer: ReturnType<typeof setTimeout> | undefined;

function noticeDay(): void {
  const current = midnight(new Date());
  if (current !== day) {
    day = current;
    listeners.forEach((listener) => listener());
  }
}

function scheduleDay(): void {
  clearTimeout(timer);
  const now = new Date();
  const next = new Date(now.getFullYear(), now.getMonth(), now.getDate() + 1);
  timer = setTimeout(
    () => {
      noticeDay();
      scheduleDay();
    },
    Math.max(1, next.getTime() - now.getTime()),
  );
}

function returnedToPage(): void {
  if (document.visibilityState === "visible") {
    noticeDay();
    scheduleDay();
  }
}

function subscribeDay(listener: () => void): () => void {
  listeners.add(listener);
  if (listeners.size === 1) {
    noticeDay();
    scheduleDay();
    document.addEventListener("visibilitychange", returnedToPage);
  }
  return () => {
    listeners.delete(listener);
    if (listeners.size === 0) {
      clearTimeout(timer);
      timer = undefined;
      document.removeEventListener("visibilitychange", returnedToPage);
    }
  };
}

function daySnapshot(): number {
  return day;
}

/** The reader's current calendar date, shared by every time on this page. */
export function useCalendarNow(): Date {
  useSyncExternalStore(subscribeDay, daySnapshot, daySnapshot);
  return new Date();
}

function midnight(at: Date): number {
  return new Date(at.getFullYear(), at.getMonth(), at.getDate()).getTime();
}

/**
 * The reader's calendar days before `now` that `at` falls on, where a reader names that day: today or yesterday.
 * Null for any other, a later day included, which a reader dates instead.
 */
export function namedDay(at: Date, now: Date): 0 | 1 | null {
  // Round, not floor: a DST day is 23 or 25 hours.
  const days = Math.round((midnight(now) - midnight(at)) / DAY_MILLIS);
  return days === 0 || days === 1 ? days : null;
}

/** Named by the day it falls on where `namedDay` names one: it is the day a reader is naming. */
function spoken(at: Date, now: Date): string {
  const time = readersIntl.formatTime(at, {
    hour: "2-digit",
    minute: "2-digit",
  });
  const days = namedDay(at, now);
  if (days !== null) {
    return say("when.relative", {
      time,
      day: readersIntl.formatRelativeTime(-days, "day", { numeric: "auto" }),
    });
  }
  return say("when.dated", {
    date: readersIntl.formatDate(at, { day: "numeric", month: "short" }),
    time,
  });
}

/**
 * The same wall clock as a bare string, for the places that can hold no
 * element — a breadcrumb's label, which is also its key.
 *
 * `iso` must be ISO 8601. `new Date` parses far more than that, "March 5, 2026"
 * included, and anything it accepts but the spec does not becomes an invalid
 * `datetime` on the element below.
 */
export function whenText(iso: string, now = new Date()): string {
  const at = new Date(iso);
  return Number.isNaN(at.getTime()) ? iso : spoken(at, now);
}

/**
 * An instant as a person keeps time, with the exact one a hover away.
 *
 * The ISO string is the truth and is unreadable at a glance; "09:00 yesterday"
 * is readable and stops being true once the day turns. Both are here, which is
 * what the `time` element is for — the machine reads `dateTime`, the reader
 * reads the text.
 */
export function When({ iso }: { readonly iso: string }) {
  const now = useCalendarNow();
  const at = new Date(iso);
  if (Number.isNaN(at.getTime())) {
    // Never "Invalid Date": what arrived is what is shown.
    return <>{iso}</>;
  }
  return (
    <time dateTime={iso} title={iso}>
      {spoken(at, now)}
    </time>
  );
}
