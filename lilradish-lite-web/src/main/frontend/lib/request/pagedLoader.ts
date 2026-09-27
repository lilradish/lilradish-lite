import { isUnchecked, type Unchecked } from "./document";
import { get } from "./http";
import type { Page } from "./page";

/** How a reader asked for a list. An empty filter narrows nothing. */
export interface ListQuery<O extends string> {
  readonly filter: string;
  /**
   * Always sent, so the table knows which column it is sorted by from what it
   * asked rather than from a copy of the server's default.
   */
  readonly order: O;
}

/**
 * The list at `path` asked for as `query` asks, in the shape
 * `usePagedResource` reads: a changed query is a new loader, which starts the
 * read again at its first page. Nothing typed is rewritten.
 */
export function pagedLoader<T, O extends string>(
  path: string,
  rowFrom: (row: unknown) => T | null,
  query: ListQuery<O>,
): (cursor: string | null, signal: AbortSignal) => Promise<Page<T>> {
  return pagedLoaderSaying(path, rowFrom, query, nothingBeside);
}

/**
 * As `pagedLoader`, the page also carrying what the list says beside its
 * rows, read by `besideFrom`; what it cannot read fails the page, as a row
 * does. Its type keeps it from naming the rows or the cursor, which a last
 * page, having no cursor, would otherwise let it supply.
 */
export function pagedLoaderSaying<T, O extends string, B extends object>(
  path: string,
  rowFrom: (row: unknown) => T | null,
  query: ListQuery<O>,
  besideFrom: (body: Unchecked) => (B & NoPagePart) | null,
): (cursor: string | null, signal: AbortSignal) => Promise<Page<T> & B> {
  return (cursor, signal) => {
    const parameters = new URLSearchParams({ sort: query.order });
    if (query.filter !== "") {
      parameters.set("filter", query.filter);
    }
    if (cursor !== null) {
      parameters.set("cursor", cursor);
    }
    return get(`${path}?${parameters}`, signal, (body) => {
      const page = pageFrom(body, rowFrom);
      if (page === null || !isUnchecked(body)) {
        return null;
      }
      const beside = besideFrom(body);
      return beside === null ? null : { ...beside, ...page };
    });
  };
}

interface NoPagePart {
  readonly items?: never;
  readonly nextCursor?: never;
}

function nothingBeside(): Record<never, never> {
  return {};
}

/**
 * Built row by row; a row that cannot be read, or a cursor that is not one,
 * fails the whole page, a list with a hole in it not being the list.
 */
function pageFrom<T>(
  body: unknown,
  rowFrom: (row: unknown) => T | null,
): Page<T> | null {
  if (!isUnchecked(body) || !Array.isArray(body.items)) {
    return null;
  }
  const items = body.items.map(rowFrom);
  if (!items.every((item): item is T => item !== null)) {
    return null;
  }
  if (!("nextCursor" in body)) {
    return { items };
  }
  const { nextCursor } = body;
  return typeof nextCursor === "string" ? { items, nextCursor } : null;
}
