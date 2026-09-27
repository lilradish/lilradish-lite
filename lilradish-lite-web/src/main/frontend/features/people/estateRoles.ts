import type { EstateRole, EstateRoles } from "../../api/pool/people";
import { say, type MessageId } from "../../i18n/app";

/**
 * Each role the estate grants, in the words a reader is shown, in the order
 * they are listed. For showing only: which controls a reader is offered is the
 * standing's to say, never a role's.
 */
const WORDS = {
  steward: "estateRole.steward",
  watcher: "estateRole.watcher",
} satisfies Record<EstateRole, MessageId>;

// Exact: the table above is closed over the union in both directions.
export const ESTATE_ROLES = Object.keys(WORDS) as readonly EstateRole[];

export function isEstateRole(role: string): role is EstateRole {
  return Object.hasOwn(WORDS, role);
}

/** A role this build has no words for is shown as it was spelt, rather than hidden. */
export function roleSaid(role: string): string {
  return isEstateRole(role) ? say(WORDS[role]) : role;
}

/** The roles held, those this build knows in the table's order and any other after them. */
export function heldInOrder(held: EstateRoles): string[] {
  return [
    ...ESTATE_ROLES.filter((role) => held.has(role)),
    ...[...held].filter((role) => !isEstateRole(role)),
  ];
}
