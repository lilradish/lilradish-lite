import { atSegment } from "../../../lib/request/address";
import { isUnchecked, wordsIn } from "../../../lib/request/document";
import { post } from "../../../lib/request/http";
import type { Page } from "../../../lib/request/page";
import { pagedLoader, type ListQuery } from "../../../lib/request/pagedLoader";
import { GROUPS } from "../../groups";

/**
 * The roles a group has, spelt the way the server spells them, and held level
 * with the server's list for the reason `SurfaceAct` gives.
 */
export type GroupRole = "operator" | "overseer" | "owner";

/** Held as the server said them, for the reason `Standing` gives: an unknown role is kept. */
export type GroupRoles = ReadonlySet<string>;

/** The columns a group's members can be sorted by, spelt as the server takes them. */
export type MemberSortColumn = "userId" | "displayName";

/** One column, led by a hyphen for descending. */
export type MemberOrder = MemberSortColumn | `-${MemberSortColumn}`;

export type MemberQuery = ListQuery<MemberOrder>;

/** One member of a group. `subjectId` addresses the row and is never shown. */
export interface Member {
  readonly subjectId: string;
  readonly userId: string;
  /** Shown isolated wherever it is shown, for the reason `PoolPerson` gives. */
  readonly displayName?: string;
  readonly roles: GroupRoles;
}

/**
 * Somebody as a group holds them, as a read of them and every change to them
 * answers: holding no role once their membership has ended.
 */
export interface MemberPanel extends Member {
  /**
   * The roles held that nobody else in the group could change its membership
   * without, so taking them is not offered.
   */
  readonly lastChangingRoles: GroupRoles;
  /** Whether taking them out of the group is offered, as the server decides it. */
  readonly removable: boolean;
}

/** The group's members' address handed to `ask`, made as `atSegment` makes one. */
export function atMembers<T>(
  groupId: string,
  ask: (address: string) => Promise<T>,
): Promise<T> {
  return atSegment(GROUPS, groupId, (group) => ask(`${group}/members`));
}

export function membersLoader(
  groupId: string,
  query: MemberQuery,
): (cursor: string | null, signal: AbortSignal) => Promise<Page<Member>> {
  return (cursor, signal) =>
    atMembers(groupId, (members) =>
      pagedLoader(members, memberFrom, query)(cursor, signal),
    );
}

/** Somebody in the pool, taken on holding exactly the roles named. */
export function bringIn(
  groupId: string,
  subjectId: string,
  roles: readonly GroupRole[],
  signal: AbortSignal,
): Promise<MemberPanel> {
  return atMembers(groupId, (members) =>
    post(members, { subjectId, roles }, signal, panelFrom),
  );
}

/** Built member by member, for the reason the rows of the list are. */
export function panelFrom(body: unknown): MemberPanel | null {
  const member = memberFrom(body);
  if (member === null || !isUnchecked(body)) {
    return null;
  }
  const lastChanging = wordsIn(body.lastChangingRoles);
  const { removable } = body;
  return lastChanging === null || typeof removable !== "boolean"
    ? null
    : { ...member, lastChangingRoles: new Set(lastChanging), removable };
}

/** Built member by member; a row missing what identifies it fails the whole page. */
function memberFrom(row: unknown): Member | null {
  if (!isUnchecked(row)) {
    return null;
  }
  const { subjectId, userId } = row;
  const roles = wordsIn(row.roles);
  if (
    typeof subjectId !== "string" ||
    typeof userId !== "string" ||
    roles === null
  ) {
    return null;
  }
  const listed = { subjectId, userId, roles: new Set(roles) };
  if (!("displayName" in row)) {
    return listed;
  }
  const { displayName } = row;
  return typeof displayName === "string" ? { ...listed, displayName } : null;
}
