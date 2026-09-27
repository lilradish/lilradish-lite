import { isUnchecked } from "../../lib/request/document";
import { get } from "../../lib/request/http";

const POOL_SEARCH = "/api/pool/search";

/** Somebody in the pool a search found, and nothing they hold. */
export interface PersonInPool {
  readonly subjectId: string;
  readonly userId: string;
  /** May carry a bidirectional control, so always shown isolated. Absent where no name is held for them. */
  readonly displayName?: string;
}

/** The first of the matches, and whether the pool holds more than were sent. */
export interface FoundInPool {
  readonly items: readonly PersonInPool[];
  readonly more: boolean;
}

/**
 * What was typed, sent as typed: which part of it matches a user number and
 * which a name is the server's to say.
 */
export function searchPool(
  typed: string,
  signal: AbortSignal,
): Promise<FoundInPool> {
  return get(
    `${POOL_SEARCH}?${new URLSearchParams({ search: typed })}`,
    signal,
    foundFrom,
  );
}

/**
 * Built member by member; one match that cannot be read refuses the whole
 * answer. Every search of the pool is answered in this shape, wherever from.
 */
export function foundFrom(body: unknown): FoundInPool | null {
  if (
    !isUnchecked(body) ||
    !Array.isArray(body.items) ||
    typeof body.more !== "boolean"
  ) {
    return null;
  }
  const items = body.items.map(personFrom);
  return items.every((item): item is PersonInPool => item !== null)
    ? { items, more: body.more }
    : null;
}

function personFrom(row: unknown): PersonInPool | null {
  if (!isUnchecked(row)) {
    return null;
  }
  const { subjectId, userId } = row;
  if (typeof subjectId !== "string" || typeof userId !== "string") {
    return null;
  }
  if (!("displayName" in row)) {
    return { subjectId, userId };
  }
  const { displayName } = row;
  return typeof displayName === "string" && displayName !== ""
    ? { subjectId, userId, displayName }
    : null;
}
