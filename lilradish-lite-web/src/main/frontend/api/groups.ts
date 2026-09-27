import { isUnchecked } from "../lib/request/document";
import { post } from "../lib/request/http";
import type { Page } from "../lib/request/page";
import { pagedLoader, type ListQuery } from "../lib/request/pagedLoader";

export const GROUPS = "/api/groups";

/** The columns the register can be sorted by, spelt as the server takes them. */
export type GroupSortColumn =
  "key" | "name" | "canBeAdministered" | "memberCount";

/** One column, led by a hyphen for descending. */
export type GroupOrder = GroupSortColumn | `-${GroupSortColumn}`;

export type GroupQuery = ListQuery<GroupOrder>;

/**
 * One group in the register: facts about the group, and nobody in it.
 * `groupId` addresses it and is never shown.
 */
export interface RegisteredGroup {
  readonly groupId: string;
  readonly key: string;
  /**
   * May carry a format character, a bidirectional control among them, so always
   * shown isolated: `dir="auto"` on an element of its own, or between U+2068 and
   * U+2069 inside text or an attribute.
   */
  readonly name: string;
  readonly canBeAdministered: boolean;
  readonly memberCount: number;
}

/**
 * A group to be made, and who in the pool is its first member. The key is sent
 * as typed: holding it in capitals is the server's.
 */
export interface NewGroup {
  readonly name: string;
  readonly key: string;
  readonly subjectId: string;
}

export function groupsLoader(
  query: GroupQuery,
): (
  cursor: string | null,
  signal: AbortSignal,
) => Promise<Page<RegisteredGroup>> {
  return pagedLoader(GROUPS, groupFrom, query);
}

/** Built member by member, so nothing else the caller holds is sent with it. */
export function createGroup(
  group: NewGroup,
  signal: AbortSignal,
): Promise<RegisteredGroup> {
  const { name, key, subjectId } = group;
  return post(GROUPS, { name, key, subjectId }, signal, groupFrom);
}

/** Built member by member; a group missing any of them is not one this side can show. */
export function groupFrom(body: unknown): RegisteredGroup | null {
  if (!isUnchecked(body)) {
    return null;
  }
  const { groupId, key, name, canBeAdministered, memberCount } = body;
  if (
    typeof groupId !== "string" ||
    typeof key !== "string" ||
    typeof name !== "string" ||
    typeof canBeAdministered !== "boolean" ||
    typeof memberCount !== "number" ||
    !Number.isSafeInteger(memberCount) ||
    memberCount < 0
  ) {
    return null;
  }
  return { groupId, key, name, canBeAdministered, memberCount };
}
