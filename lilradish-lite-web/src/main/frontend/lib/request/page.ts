import { isUnchecked } from "./document";

/**
 * One page of a collection, as the server hands it over.
 *
 * Cursor-paginated, never offset. There is no total and no page number here,
 * and their absence is the design rather than an omission: a count is a second
 * question about rows the reader is not being shown, answered under whatever
 * that reader may reach at that instant, so it is stale as it arrives and stale
 * in a direction nobody can see.
 *
 * The last page is a `nextCursor` that is absent, not one that is null. Absent
 * is what the document omits, so a reader who compares against null instead
 * would find a further page at the end of every collection.
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
