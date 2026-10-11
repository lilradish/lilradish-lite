import { isUnchecked, type Unchecked } from "./document";
import { get } from "./http";
import { pageFrom, type Page } from "./page";

/** An empty filter narrows nothing. */
export interface ListQuery<O extends string> {
  readonly filter: string;
  /**
   * Always sent: the table knows its sort without duplicating the server's default.
   */
  readonly order: O;
}

/**
 * A changed query needs a new loader, restarting `usePagedResource` at page one.
 * Nothing typed is rewritten.
 */
export function pagedLoader<T, O extends string>(
  path: string,
  rowFrom: (row: unknown) => T | null,
  query: ListQuery<O>,
): (cursor: string | null, signal: AbortSignal) => Promise<Page<T>> {
  return pagedLoaderSaying(path, rowFrom, query, nothingBeside);
}

/**
 * `besideFrom` failures refuse the page just as unreadable rows do.
 * Its type excludes rows and cursor, preventing it supplying a cursor for a last page.
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
