import { put, remove } from "../../../../../../lib/request/http";
import { panelFrom, type GroupRole, type MemberPanel } from "../../../members";
import { atMember } from "../../{subjectId}";

/**
 * Given to anybody in the pool, which makes somebody holding nothing here a
 * member again. The role goes into the address unescaped, which holds only
 * while it is typed as one of the published spellings.
 */
export function give(
  groupId: string,
  subjectId: string,
  role: GroupRole,
  signal: AbortSignal,
): Promise<MemberPanel> {
  return atMember(groupId, subjectId, (member) =>
    put(`${member}/roles/${role}`, signal, panelFrom),
  );
}

/** Taken, the last role ends their membership, and they are answered holding nothing. */
export function take(
  groupId: string,
  subjectId: string,
  role: GroupRole,
  signal: AbortSignal,
): Promise<MemberPanel> {
  return atMember(groupId, subjectId, (member) =>
    remove(`${member}/roles/${role}`, signal, panelFrom),
  );
}
