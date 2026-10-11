import { useCallback, useState } from "react";

import type { Problem } from "../../api/problem";
import type { Page } from "./page";
import { useResource } from "./useResource";

export interface PagedResource<T> {
  readonly items: readonly T[];
  readonly problem: Problem | null;
  readonly loading: boolean;
  readonly onFirstPage: boolean;
  readonly hasNext: boolean;
  readonly next: () => void;
  readonly first: () => void;
  readonly reload: () => void;
}

// What an unanswered page holds is known here rather than the caller's to say,
// and one module value is what keeps a fresh blank out of every render.
const NO_PAGE: Page<never> = { items: [] };

/**
 * Only forward or back to the start: stacking old cursors to offer a previous
 * page would invent the offset these collections deliberately omit.
 *
 * A changed `load` restarts at page one; a cursor belongs to its original query.
 *
 * `load` must be stable as `useResource` requires. An unstable identity resets
 * during render: React stops the render loop before a second request goes out,
 * and the nearest boundary catches the render error.
 *
 * A refusal clears rows as `useResource` requires: their authority may have
 * been withdrawn mid-read.
 */
export function usePagedResource<T, B extends object = Record<never, never>>(
  load: (cursor: string | null, signal: AbortSignal) => Promise<Page<T> & B>,
): PagedResource<T> & {
  /** The page last answered, with what it says beside its rows; null while none stands, as after a refusal. */
  readonly answered: (Page<T> & B) | null;
} {
  const [cursor, setCursor] = useState<string | null>(null);
  // Boxed for the reason `useResource` boxes the same thing: React calls a bare
  // function handed to `useState` or to a setter.
  const [source, setSource] = useState({ load });
  if (source.load !== load) {
    setSource({ load });
    setCursor(null);
  }

  const page = useResource<(Page<T> & B) | typeof NO_PAGE>(
    useCallback((signal: AbortSignal) => load(cursor, signal), [load, cursor]),
    NO_PAGE,
  );
  // A later page that comes back empty says nothing of the collection and
  // offers nothing to go on from, so the first page is where the reader goes.
  if (
    cursor !== null &&
    !page.loading &&
    page.problem === null &&
    page.value.items.length === 0
  ) {
    setCursor(null);
  }
  const nextCursor = page.value.nextCursor;
  // Require a string: null instead of an absent member must not invent a next
  // page. A static type does not constrain the arriving document.
  const hasNext = typeof nextCursor === "string";

  const next = useCallback(() => {
    if (typeof nextCursor === "string") {
      setCursor(nextCursor);
    }
  }, [nextCursor]);
  const first = useCallback(() => setCursor(null), []);

  return {
    items: page.value.items,
    problem: page.problem,
    loading: page.loading,
    onFirstPage: cursor === null,
    hasNext,
    next,
    first,
    reload: page.reload,
    answered: answeredOf(page.value),
  };
}

function answeredOf<P extends Page<unknown>>(
  value: P | typeof NO_PAGE,
): P | null {
  return value === NO_PAGE ? null : (value as P);
}
