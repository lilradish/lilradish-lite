import type { PoolPersonPanel } from "../../api/pool/people";
import { say } from "../../i18n/app";
import { heldInOrder, roleSaid } from "./estateRoles";
import { joinedInSentence } from "./wordLists";

/**
 * What has to be undone before somebody can leave the pool, named — which is
 * the whole of why a control refused by what they hold is worth drawing — or
 * nothing where nothing holds them.
 */
export function whatHasToGoFirst(
  person: Pick<PoolPersonPanel, "estateRoles" | "groups">,
): string | undefined {
  const roles = heldInOrder(person.estateRoles);
  const { groups } = person;
  const first = [
    roles.length === 0
      ? null
      : say("person.withdrawFirst", {
          count: roles.length,
          roles: joinedInSentence(roles.map(roleSaid)),
        }),
    groups.length === 0
      ? null
      : say("person.leaveFirst", {
          count: groups.length,
          groups: joinedInSentence(groups),
        }),
  ].filter((sentence) => sentence !== null);
  return first.length === 0 ? undefined : first.join(" ");
}
