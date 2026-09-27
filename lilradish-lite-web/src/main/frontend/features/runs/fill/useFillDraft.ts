import { useCallback, useEffect, useId, useRef, useState } from "react";

import {
  fillProblemsIn,
  pathKey,
  type FillField,
  type FillPath,
  type FillProblem,
  type FillReason,
  type FillValues,
} from "../../../api/filling";
import type { Problem } from "../../../api/problem";
import { say } from "../../../i18n/app";
import { placeId, type Drafted, type Left, type Unread } from "./FillForm";
import { draftReader, placedAt, type LevelDraft } from "./fillDrafts";

/** What the server named of what was last sent, until anything is changed after it arrives. */
interface Served {
  /** Each place listed, marked where it stands. */
  readonly marked: readonly FillProblem[];
  /** How many more it found than it listed, which are marked nowhere. */
  readonly unmarked: number;
}

/** What is being filled under one key, which starts again whenever the key does. */
interface Filling {
  readonly key: string;
  readonly fill: LevelDraft;
  /** Each place the reader has changed, and every place holding it, which may now say that it is empty. */
  readonly touched: ReadonlySet<string>;
  /** Each place the keyboard has left, which may now say why what is in it does not fit. */
  readonly left: ReadonlySet<string>;
  /** Each place holding what the browser could not read, which is not nothing. */
  readonly unreadable: ReadonlySet<string>;
  /** How often sending has been asked for; after the first, every place says why what is in it does not fit. */
  readonly attempts: number;
  /** Nothing marked where the fill changed while it was out; none once anything is changed after it arrives. */
  readonly served: Served | null;
}

/** What was sent, so what is answered is taken to what was sent and no later change. */
interface Sent {
  readonly key: string;
  readonly fill: LevelDraft;
}

/** One form's fields as they are being filled, whatever is sent with them. */
export interface FillDraft {
  readonly formId: string;
  readonly fill: LevelDraft;
  readonly onFill: Drafted;
  readonly onLeave: Left;
  readonly onUnread: Unread;
  readonly marks: ReadonlyMap<string, FillReason>;
  /** Every value as it would be sent. */
  readonly values: FillValues;
  /** Where the first of what the page finds wrong stands; absent where it finds nothing. */
  readonly firstUnfit: FillPath | undefined;
  /** What the page finds wrong in what is filled; absent where it finds nothing. */
  readonly unfit: string | undefined;
  /** How many places the last refusal found and marked nowhere. */
  readonly unmarked: number;
  /** Sending held: every place says why it does not fit, and the keyboard goes to `to`, where one is named. */
  readonly holdBack: (to: FillPath | undefined) => void;
  /** What is filled now is what is being sent. */
  readonly sending: () => void;
  /**
   * A refusal of values marked where each place stands, while nothing is changed since it was sent; whether the
   * keyboard is taken to the first of them.
   */
  readonly refusedWith: (problem: Problem) => boolean;
  /** The refusal to say: one of values only until anything is changed after it arrives, even where it marks nothing. */
  readonly saying: (problem: Problem | null) => Problem | null;
}

/**
 * What is filled in `fields`, started by `startWith` and started again whenever `key` changes, read as the server
 * would read it and marked where the page, or the server, finds it does not fit.
 */
export function useFillDraft(
  fields: readonly FillField[],
  key: string,
  startWith: () => LevelDraft,
): FillDraft {
  const formId = useId();
  const sent = useRef<Sent | null>(null);
  const landing = useRef<FillPath | null>(null);
  const [reader] = useState(() => draftReader());
  const [held, setFilling] = useState(() => fillingFor(key, startWith()));
  const [marksHeld, setMarks] = useState<{
    readonly said: string;
    readonly marks: ReadonlyMap<string, FillReason>;
  }>(() => ({ said: "[]", marks: new Map() }));
  const filling = held.key === key ? held : fillingFor(key, startWith());
  if (filling !== held) {
    setFilling(filling);
  }
  const read = reader(fields, filling.fill, filling.unreadable);
  // Kept the same while what it says is, so a control whose mark has not moved is not drawn again.
  const found = marksOf(read.problems, filling);
  const foundSaid = JSON.stringify([...found]);
  const marks = marksHeld.said === foundSaid ? marksHeld.marks : found;
  if (marks !== marksHeld.marks) {
    setMarks({ said: foundSaid, marks });
  }

  useEffect(() => {
    const to = landing.current;
    landing.current = null;
    if (to !== null) {
      document.getElementById(placeId(formId, to))?.focus();
    }
  });

  const onFill = useCallback<Drafted>(
    (path, next, takenAway) =>
      setFilling((prior) => {
        const moved = (keys: ReadonlySet<string>) =>
          takenAway === undefined ? keys : movedPast(keys, path, takenAway);
        return {
          ...prior,
          fill: placedAt(prior.fill, path, next),
          touched: withEveryHolder(moved(prior.touched), path),
          left: moved(prior.left),
          unreadable: moved(prior.unreadable),
          served: null,
        };
      }),
    [],
  );
  const onLeave = useCallback<Left>(
    (left) =>
      setFilling((prior) =>
        prior.left.has(pathKey(left))
          ? prior
          : { ...prior, left: new Set(prior.left).add(pathKey(left)) },
      ),
    [],
  );
  const onUnread = useCallback<Unread>(
    (path, unreadable) =>
      setFilling((prior) => {
        const place = pathKey(path);
        if (prior.unreadable.has(place) === unreadable) {
          return prior;
        }
        const next = new Set(prior.unreadable);
        if (unreadable) {
          next.add(place);
        } else {
          next.delete(place);
        }
        return { ...prior, unreadable: next };
      }),
    [],
  );

  return {
    formId,
    fill: filling.fill,
    onFill,
    onLeave,
    onUnread,
    marks,
    values: read.values,
    firstUnfit: read.problems[0]?.path,
    unfit: unfitSaid(read.problems),
    unmarked: filling.served?.unmarked ?? 0,
    holdBack: (to) => {
      landing.current = to ?? null;
      setFilling((prior) => ({ ...prior, attempts: prior.attempts + 1 }));
    },
    sending: () => {
      sent.current = { key: filling.key, fill: filling.fill };
      setFilling((prior) =>
        prior.attempts > 0 ? prior : { ...prior, attempts: 1 },
      );
    },
    // Of the render the answer lands in, so a fill changed since is not the one it was said of.
    refusedWith: (problem) => {
      const named = fillProblemsIn(problem);
      if (named === null) {
        return false;
      }
      const asked = sent.current;
      const unchanged =
        asked?.fill === filling.fill && asked.key === filling.key;
      setFilling((prior) => ({
        ...prior,
        served: unchanged
          ? {
              marked: named.listed,
              unmarked: named.found - named.listed.length,
            }
          : { marked: [], unmarked: 0 },
      }));
      if (unchanged) {
        landing.current = named.listed[0]!.path;
      }
      return unchanged;
    },
    saying: (problem) =>
      problem !== null &&
      fillProblemsIn(problem) !== null &&
      filling.served === null
        ? null
        : problem,
  };
}

function fillingFor(key: string, fill: LevelDraft): Filling {
  return {
    key,
    fill,
    touched: new Set(),
    left: new Set(),
    unreadable: new Set(),
    attempts: 0,
    served: null,
  };
}

/** The place changed and every place holding it, so fields or one of many holding nothing say they lack it. */
function withEveryHolder(
  touched: ReadonlySet<string>,
  path: FillPath,
): ReadonlySet<string> {
  const along = new Set(touched);
  for (let depth = 1; depth <= path.length; depth += 1) {
    along.add(pathKey(path.slice(0, depth)));
  }
  return along;
}

/** Each place under the many at `path` as it stands once the one at `takenAway` is gone: its own dropped, each after it one down. */
function movedPast(
  keys: ReadonlySet<string>,
  path: FillPath,
  takenAway: number,
): ReadonlySet<string> {
  const moved = new Set<string>();
  for (const key of keys) {
    const place = JSON.parse(key) as FillPath;
    const index = place[path.length];
    const under = path.every((step, depth) => place[depth] === step);
    if (!under || typeof index !== "number" || index < takenAway) {
      moved.add(key);
    } else if (index > takenAway) {
      moved.add(pathKey(place.toSpliced(path.length, 1, index - 1)));
    }
  }
  return moved;
}

/**
 * What the page finds wrong where it is typed: an empty place once the reader has changed it, anything else once
 * the keyboard has left it, and everything once sending was asked for; and what the server named of what was last
 * sent over it.
 */
function marksOf(
  found: readonly FillProblem[],
  filling: Filling,
): ReadonlyMap<string, FillReason> {
  const marks = new Map<string, FillReason>();
  for (const { path, reason } of found) {
    const key = pathKey(path);
    if (
      filling.attempts > 0 ||
      (reason === "missing" ? filling.touched.has(key) : filling.left.has(key))
    ) {
      marks.set(key, reason);
    }
  }
  for (const { path, reason } of filling.served?.marked ?? []) {
    marks.set(pathKey(path), reason);
  }
  return marks;
}

function unfitSaid(found: readonly FillProblem[]): string | undefined {
  if (found.some(({ reason }) => reason !== "missing")) {
    return say("work.heldUnfit");
  }
  return found.length === 0 ? undefined : say("work.heldMissing");
}
