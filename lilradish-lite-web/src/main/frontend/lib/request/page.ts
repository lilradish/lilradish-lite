import { isUnchecked } from "./document";

/**
 * Cursor-paginated without totals or page numbers by design. A total is a
 * separate query over unseen rows under the reader's momentary authority,
 * already stale on arrival in a direction nobody can observe.
 *
 * The last page omits `nextCursor`; it is never null. Comparing with null
 * would invent a next page where the document omitted it.
 */
export interface Page<T> {
  readonly items: readonly T[];
  readonly nextCursor?: string;
}

/**
 * Built row by row; a row that cannot be read, or a cursor that is not one,
 * fails the whole page, a list with a hole in it not being the list.
 */
export function pageFrom<T>(
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
