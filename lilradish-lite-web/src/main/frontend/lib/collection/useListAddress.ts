import { useSearchParams } from "react-router";

import { orderAsked, orderSpelled, type Ordering } from "./ordering";

const FILTER = "filter";

const SORT = "sort";

/**
 * A list's filter and order as the address holds them, and how each is
 * written back: by a functional update, which keeps whatever else the address
 * holds, and in place of the entry it was on — a filter typed or a heading
 * pressed is no step back. An emptied filter leaves the address.
 */
export function useListAddress<K extends string>(
  sortable: Readonly<Record<K, true>>,
  opening: Ordering<K>,
) {
  const [parameters, setParameters] = useSearchParams();
  const filter = parameters.get(FILTER) ?? "";
  const order = orderAsked(parameters.get(SORT), sortable, opening);

  const onFilter = (typed: string) =>
    setParameters(
      (previous) => {
        const next = new URLSearchParams(previous);
        if (typed === "") {
          next.delete(FILTER);
        } else {
          next.set(FILTER, typed);
        }
        return next;
      },
      { replace: true },
    );

  const onOrder = (next: Ordering<K>) =>
    setParameters(
      (previous) => {
        const ordered = new URLSearchParams(previous);
        ordered.set(SORT, orderSpelled(next));
        return ordered;
      },
      { replace: true },
    );

  return { filter, order, sort: orderSpelled(order), onFilter, onOrder };
}
