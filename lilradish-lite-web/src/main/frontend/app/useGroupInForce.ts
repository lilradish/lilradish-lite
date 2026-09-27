import { useState } from "react";
import { useMatch, useNavigate } from "react-router";

import type { GroupStanding } from "../api/standing";
import { GROUP_ITEMS, IN_A_GROUP, groupPage, opensIn } from "./destinations";

// Matches a row picked on the page too; `choose` opens the page alone, the row being this group's.
const ON_A_GROUP_PAGE = `${IN_A_GROUP}/:groupId/:segment/*` as const;

export interface GroupInForce {
  /** Null only for a reader in no group at all. */
  readonly group: GroupStanding | null;
  readonly choose: (next: GroupStanding) => void;
}

/**
 * The group the address names, else the last in force here, else the first;
 * a pick on a group's page moves to that page there, never to a refusal.
 */
export function useGroupInForce(
  groups: readonly GroupStanding[],
): GroupInForce {
  const on = useMatch(ON_A_GROUP_PAGE)?.params;
  const navigate = useNavigate();
  const [last, setLast] = useState<string | null>(null);

  // Adjusted while rendering, so the render in which the address moves is
  // drawn again with it before anything is shown.
  const named = groups.find((each) => each.groupId === on?.groupId);
  if (named !== undefined && named.groupId !== last) {
    setLast(named.groupId);
  }
  const group =
    groups.find((each) => each.groupId === last) ?? groups[0] ?? null;

  function choose(next: GroupStanding) {
    const page = GROUP_ITEMS.find((each) => each.segment === on?.segment);
    if (page === undefined) {
      setLast(next.groupId);
      return;
    }
    const opened = opensIn(next, page)
      ? page
      : GROUP_ITEMS.find((each) => opensIn(next, each));
    if (opened !== undefined) {
      navigate(groupPage(next.groupId, opened.segment));
    }
  }

  return { group, choose };
}
