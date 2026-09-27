import type { WordedRefusal } from "../../../../i18n/app";
import { atSegment } from "../../../../lib/request/address";
import { get, remove } from "../../../../lib/request/http";
import { servedAmong, type Problem } from "../../../problem";
import { atMembers, panelFrom, type MemberPanel } from "../members";

const MOVED: ReadonlySet<string> = new Set([
  "LAST_MEMBERSHIP_CHANGER",
  "MEMBER_NOT_IN_VIEW",
  "PERSON_NOT_IN_POOL",
  "PERSON_ALREADY_IN_GROUP",
] satisfies readonly WordedRefusal[]);

/** The member's address handed to `ask`, each identifier escaped as `atSegment` escapes one. */
export function atMember<T>(
  groupId: string,
  subjectId: string,
  ask: (address: string) => Promise<T>,
): Promise<T> {
  return atMembers(groupId, (members) => atSegment(members, subjectId, ask));
}

export function readMember(
  groupId: string,
  subjectId: string,
  signal: AbortSignal,
): Promise<MemberPanel> {
  return atMember(groupId, subjectId, (address) =>
    get(address, signal, panelFrom),
  );
}

/** Answered with no content: there is nothing of them left in the group to read. */
export function takeOut(
  groupId: string,
  subjectId: string,
  signal: AbortSignal,
): Promise<void> {
  return atMember(groupId, subjectId, (address) =>
    remove(address, signal, (body) => (body === undefined ? undefined : null)),
  );
}

/**
 * Whether a change was refused for how the group stands now, which somebody
 * else moved since the page read it: what the page shows is out of date, and
 * reading it again is what shows why. The status is read before the code, as
 * `Problem` requires.
 */
export function refusedForWhatMoved(problem: Problem): boolean {
  return servedAmong(problem, MOVED);
}
