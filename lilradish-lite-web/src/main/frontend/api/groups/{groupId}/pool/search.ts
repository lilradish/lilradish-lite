import { atSegment } from "../../../../lib/request/address";
import { get } from "../../../../lib/request/http";
import { GROUPS } from "../../../groups";
import { foundFrom, type FoundInPool } from "../../../pool/search";

/**
 * The pool searched from inside a group, leaving out whoever is in it already.
 * What was typed is sent as typed, for the reason `searchPool` gives.
 */
export function searchPoolOutside(
  groupId: string,
  typed: string,
  signal: AbortSignal,
): Promise<FoundInPool> {
  return atSegment(GROUPS, groupId, (group) =>
    get(
      `${group}/pool/search?${new URLSearchParams({ search: typed })}`,
      signal,
      foundFrom,
    ),
  );
}
