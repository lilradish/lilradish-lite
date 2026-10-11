import { isUnchecked, type Unchecked } from "../lib/request/document";
import { get } from "../lib/request/http";

const DIRECTORY = "/api/people";

/** `subjectId` is present exactly while they are in the pool. */
export interface PersonInDirectory {
  readonly userId: string;
  /**
   * Always show isolated: it may carry a bidirectional control.
   * Absent where the directory holds no name.
   */
  readonly displayName?: string;
  readonly subjectId?: string;
}

/** The first of the matches, and whether the directory holds more than were sent. */
interface FoundInDirectory {
  readonly items: readonly PersonInDirectory[];
  readonly more: boolean;
}

/**
 * Sent as typed; only the server decides what matches a user number or a name.
 * A module function keeps its identity stable for callers keying reads on it.
 */
export function searchDirectory(
  typed: string,
  signal: AbortSignal,
): Promise<FoundInDirectory> {
  return get(
    `${DIRECTORY}?${new URLSearchParams({ search: typed })}`,
    signal,
    foundFrom,
  );
}

function foundFrom(body: unknown): FoundInDirectory | null {
  if (
    !isUnchecked(body) ||
    !Array.isArray(body.items) ||
    typeof body.more !== "boolean"
  ) {
    return null;
  }
  const items = body.items.map(personFrom);
  return items.every((item): item is PersonInDirectory => item !== null)
    ? { items, more: body.more }
    : null;
}

function personFrom(row: unknown): PersonInDirectory | null {
  if (!isUnchecked(row)) {
    return null;
  }
  const { userId } = row;
  if (typeof userId !== "string") {
    return null;
  }
  const named = namedFrom(row, userId);
  if (named === null || !("subjectId" in row)) {
    return named;
  }
  const { subjectId } = row;
  return typeof subjectId === "string" ? { ...named, subjectId } : null;
}

function namedFrom(row: Unchecked, userId: string): PersonInDirectory | null {
  if (!("displayName" in row)) {
    return { userId };
  }
  const { displayName } = row;
  return typeof displayName === "string" && displayName !== ""
    ? { userId, displayName }
    : null;
}
