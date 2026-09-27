import { isUnchecked, wordsIn } from "../../lib/request/document";
import { post } from "../../lib/request/http";
import type { Page } from "../../lib/request/page";
import { pagedLoader, type ListQuery } from "../../lib/request/pagedLoader";

export const PEOPLE = "/api/pool/people";

/**
 * The roles the estate grants, spelt the way the server spells them, and held
 * level with the server's list for the reason `SurfaceAct` gives.
 */
export type EstateRole = "steward" | "watcher";

/** Held as the server said them, for the reason `Standing` gives: an unknown role is kept. */
export type EstateRoles = ReadonlySet<string>;

/** The columns the pool can be sorted by, spelt as the server takes them. */
export type PoolSortColumn = "userId" | "displayName" | "groupCount";

/** One column, led by a hyphen for descending. */
export type PoolOrder = PoolSortColumn | `-${PoolSortColumn}`;

export type PoolQuery = ListQuery<PoolOrder>;

/** One row of the pool. `subjectId` addresses the row and is never shown. */
export interface PoolPerson {
  readonly subjectId: string;
  readonly userId: string;
  /** May carry a bidirectional control, so always shown isolated: `dir="auto"` on an
   * element of its own, or between U+2068 and U+2069 inside text or an attribute. */
  readonly displayName?: string;
  readonly estateRoles: EstateRoles;
  readonly groupCount: number;
}

/** One person in the pool, as a read of them and every change to them answers. */
export interface PoolPersonPanel {
  readonly subjectId: string;
  readonly userId: string;
  readonly displayName?: string;
  readonly estateRoles: EstateRoles;
  /**
   * The roles held that nobody else in the pool could grant an estate role
   * without, so withdrawing them is not offered.
   */
  readonly lastGrantingRoles: EstateRoles;
  readonly groups: readonly string[];
  readonly seeded: boolean;
}

export function peopleLoader(
  query: PoolQuery,
): (cursor: string | null, signal: AbortSignal) => Promise<Page<PoolPerson>> {
  return pagedLoader(PEOPLE, personFrom, query);
}

/** Somebody in the directory, brought into the pool by their user number exactly as the directory holds it. */
export function bringIn(
  userId: string,
  signal: AbortSignal,
): Promise<PoolPersonPanel> {
  return post(PEOPLE, { userId }, signal, panelFrom);
}

/** Built member by member, for the reason the rows of the list are. */
export function panelFrom(body: unknown): PoolPersonPanel | null {
  if (!isUnchecked(body)) {
    return null;
  }
  const { subjectId, userId, groups, seeded } = body;
  const roles = wordsIn(body.estateRoles);
  const lastGranting = wordsIn(body.lastGrantingRoles);
  if (
    typeof subjectId !== "string" ||
    typeof userId !== "string" ||
    roles === null ||
    lastGranting === null ||
    !Array.isArray(groups) ||
    !groups.every((group): group is string => typeof group === "string") ||
    typeof seeded !== "boolean"
  ) {
    return null;
  }
  const read = {
    subjectId,
    userId,
    estateRoles: new Set(roles),
    lastGrantingRoles: new Set(lastGranting),
    groups,
    seeded,
  };
  if (!("displayName" in body)) {
    return read;
  }
  const { displayName } = body;
  return typeof displayName === "string" ? { ...read, displayName } : null;
}

/** Built member by member; a row missing what identifies it fails the whole page. */
function personFrom(row: unknown): PoolPerson | null {
  if (!isUnchecked(row)) {
    return null;
  }
  const { subjectId, userId, groupCount } = row;
  const roles = wordsIn(row.estateRoles);
  if (
    typeof subjectId !== "string" ||
    typeof userId !== "string" ||
    roles === null ||
    !isCount(groupCount)
  ) {
    return null;
  }
  const listed = { subjectId, userId, estateRoles: new Set(roles), groupCount };
  if (!("displayName" in row)) {
    return listed;
  }
  const { displayName } = row;
  return typeof displayName === "string" ? { ...listed, displayName } : null;
}

function isCount(value: unknown): value is number {
  return typeof value === "number" && Number.isSafeInteger(value) && value >= 0;
}
