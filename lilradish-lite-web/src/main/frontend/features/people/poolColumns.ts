import type { PoolPerson, PoolSortColumn } from "../../api/pool/people";
import { say } from "../../i18n/app";
import type { Ordering } from "../../lib/collection/ordering";
import { heldInOrder, roleSaid } from "./estateRoles";
import { joinedInCell } from "./wordLists";

/** Every column the pool can be sorted by, closed over the union in both directions. */
export const POOL_SORTABLE = {
  userId: true,
  displayName: true,
  groupCount: true,
} satisfies Record<PoolSortColumn, true>;

/** On a column no row leaves empty. */
export const BY_USER_NUMBER: Ordering<PoolSortColumn> = {
  column: "userId",
  descending: false,
};

/**
 * The pool, one row per person, the first cell the link that picks them. A
 * set of roles has no order to sort by, and which groups somebody is in is
 * the panel's to say: the list counts them.
 */
export function poolColumns() {
  return [
    {
      label: say("person.userId"),
      cell: (person: PoolPerson) => person.userId,
      sortKey: "userId",
    },
    {
      label: say("person.name"),
      cell: (person: PoolPerson) => person.displayName ?? "",
      sortKey: "displayName",
    },
    {
      label: say("person.estateRoles"),
      cell: (person: PoolPerson) =>
        joinedInCell(heldInOrder(person.estateRoles).map(roleSaid)),
    },
    {
      label: say("person.groups"),
      cell: (person: PoolPerson) => person.groupCount,
      sortKey: "groupCount",
    },
  ] as const;
}
