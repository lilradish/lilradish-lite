import type { ReactNode } from "react";

import type { GroupSortColumn, RegisteredGroup } from "../../api/groups";
import { say } from "../../i18n/app";
import type { Ordering } from "../../lib/collection/ordering";

/** Every column the register can be sorted by, closed over the union in both directions. */
export const GROUP_SORTABLE = {
  key: true,
  name: true,
  canBeAdministered: true,
  memberCount: true,
} satisfies Record<GroupSortColumn, true>;

/** On a column no row leaves empty. */
export const BY_NAME: Ordering<GroupSortColumn> = {
  column: "name",
  descending: false,
};

/**
 * The register, one row per group: four facts about the group, each sortable
 * and none naming anybody in it, and last the one control on the row, which
 * has no order to sort by and no heading of its own.
 */
export function groupColumns(
  renameControl: (group: RegisteredGroup) => ReactNode,
) {
  return [
    {
      label: say("group.key"),
      cell: (group: RegisteredGroup) => group.key,
      sortKey: "key",
    },
    {
      label: say("group.heading"),
      cell: (group: RegisteredGroup) => group.name,
      sortKey: "name",
    },
    {
      label: say("group.canBeAdministered"),
      cell: (group: RegisteredGroup) =>
        say(
          group.canBeAdministered
            ? "group.administrable"
            : "group.notAdministrable",
        ),
      sortKey: "canBeAdministered",
    },
    {
      label: say("group.members"),
      cell: (group: RegisteredGroup) => group.memberCount,
      sortKey: "memberCount",
    },
    { label: "", cell: renameControl },
  ] as const;
}
