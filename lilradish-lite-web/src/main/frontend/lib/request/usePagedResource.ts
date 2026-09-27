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
 * One page at a time of a cursor-paginated collection.
 *
 * Forward, or back to the start — never back one. A cursor points one way, and
 * keeping a stack of the ones already visited so a previous page could be
 * offered would be inventing an offset, which is the thing these collections
 * refuse to have.
 *
 * A changed `load` starts again at the first page. A cursor belongs to the
 * query that minted it; carried to another, it names a position that query
 * never had.
 *
 * `load` must be stable, for the reason `useResource` gives, and an unstable
 * one fails loudly here rather than quietly: the reset below runs during
 * render, so React stops it at `Too many re-renders` before a second request
 * goes out. That is an uncaught render error, and the nearest boundary takes
 * the screen down for it — which is the cheaper of the two ways to be told.
 *
 * A refused page takes the rows of the page in hand down with it, which is
 * `useResource`'s rule and not this one: read what it says about an authority
 * withdrawn mid-read before softening this into keeping them.
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
  // Matched as a string rather than against `undefined`: a `null` written where
  // the member should be absent would be a further page at the end of every
  // collection, and the type saying `string | undefined` is not what arrives.
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
