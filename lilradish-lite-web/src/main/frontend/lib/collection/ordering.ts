/** A table sorts by one column at a time. */
export interface Ordering<K extends string> {
  readonly column: K;
  readonly descending: boolean;
}

/**
 * Server spelling is a column led by a hyphen for descending. Unknown columns
 * use `opening`: an editable address must not make a table claim a sort it lacks.
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
