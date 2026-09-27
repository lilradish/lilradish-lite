import type { GroupRole, GroupRoles } from "../../api/groups/{groupId}/members";
import { say, type MessageId } from "../../i18n/app";

/**
 * Each role a group has, in the words a reader is shown, in the order they are
 * listed. For showing only: which controls a reader is offered is the
 * standing's and the member's to say, never a role's.
 */
const WORDS = {
  operator: "groupRole.operator",
  overseer: "groupRole.overseer",
  owner: "groupRole.owner",
} satisfies Record<GroupRole, MessageId>;

// Exact: the table above is closed over the union in both directions.
export const GROUP_ROLES = Object.keys(WORDS) as readonly GroupRole[];

export function isGroupRole(role: string): role is GroupRole {
  return Object.hasOwn(WORDS, role);
}

/** A role this build has no words for is shown as it was spelt, rather than hidden. */
export function groupRoleSaid(role: string): string {
  return isGroupRole(role) ? say(WORDS[role]) : role;
}

/** The roles held, those this build knows in the table's order and any other after them. */
export function groupRolesInOrder(held: GroupRoles): string[] {
  return [
    ...GROUP_ROLES.filter((role) => held.has(role)),
    ...[...held].filter((role) => !isGroupRole(role)),
  ];
}
