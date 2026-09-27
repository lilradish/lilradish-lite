import type {
  Member,
  MemberSortColumn,
} from "../../api/groups/{groupId}/members";
import { say } from "../../i18n/app";
import type { Ordering } from "../../lib/collection/ordering";
import { joinedInCell } from "../people/wordLists";
import { groupRoleSaid, groupRolesInOrder } from "./groupRoles";

/** Every column a group's members can be sorted by, closed over the union in both directions. */
export const MEMBER_SORTABLE = {
  userId: true,
  displayName: true,
} satisfies Record<MemberSortColumn, true>;

/** On a column no row leaves empty. */
export const BY_USER_NUMBER: Ordering<MemberSortColumn> = {
  column: "userId",
  descending: false,
};

/**
 * A group's members, one row per member, the first cell the link that picks
 * them. A set of roles has no order to sort by.
 */
export function memberColumns() {
  return [
    {
      label: say("person.userId"),
      cell: (member: Member) => member.userId,
      sortKey: "userId",
    },
    {
      label: say("person.name"),
      cell: (member: Member) => member.displayName ?? "",
      sortKey: "displayName",
    },
    {
      label: say("member.roles"),
      cell: (member: Member) =>
        joinedInCell(groupRolesInOrder(member.roles).map(groupRoleSaid)),
    },
  ] as const;
}
