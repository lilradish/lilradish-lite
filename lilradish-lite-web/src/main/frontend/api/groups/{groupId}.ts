import { atSegment } from "../../lib/request/address";
import { patch } from "../../lib/request/http";
import { GROUPS, groupFrom, type RegisteredGroup } from "../groups";

/**
 * A group's new name, answered with the group as the register now reads it.
 * The key is never sent: it is given once, when a group is created, and a
 * rename carrying one is refused.
 */
export function renameGroup(
  groupId: string,
  name: string,
  signal: AbortSignal,
): Promise<RegisteredGroup> {
  return atSegment(GROUPS, groupId, (address) =>
    patch(address, { name }, signal, groupFrom),
  );
}
