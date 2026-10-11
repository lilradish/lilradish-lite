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
 * A refusal because the group moved since it was read means the page is stale;
 * re-reading shows why. Read status before code, as `Problem` requires.
 */
export function refusedForWhatMoved(problem: Problem): boolean {
  return servedAmong(problem, MOVED);
}
