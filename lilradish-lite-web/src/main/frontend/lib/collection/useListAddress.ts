import { useSearchParams } from "react-router";

import { orderAsked, orderSpelled, type Ordering } from "./ordering";

const FILTER = "filter";

const SORT = "sort";

/**
 * Functional updates preserve other address entries. Filters and sort changes
 * replace history: typing or pressing a heading is no step back.
 * An emptied filter leaves the address.
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
