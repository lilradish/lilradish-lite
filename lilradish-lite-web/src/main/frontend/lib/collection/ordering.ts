/** One column and a direction, which is the whole of an order: a table sorts by one column at a time. */
export interface Ordering<K extends string> {
  readonly column: K;
  readonly descending: boolean;
}

/**
 * The order an address asks for, spelt as the server takes it: a column,
 * led by a hyphen for descending. One naming no column in `sortable` is read
 * as naming none: the address is the reader's to edit, and a table cannot say
 * it is sorted by a column it does not have.
 */
export function orderAsked<K extends string>(
  sort: string | null,
  sortable: Readonly<Record<K, true>>,
  opening: Ordering<K>,
): Ordering<K> {
  if (sort === null) {
    return opening;
  }
  const descending = sort.startsWith("-");
  const column = descending ? sort.slice(1) : sort;
  return Object.hasOwn(sortable, column)
    ? { column: column as K, descending }
    : opening;
}

export function orderSpelled<K extends string>(
  order: Ordering<K>,
): K | `-${K}` {
  return order.descending ? `-${order.column}` : order.column;
}
