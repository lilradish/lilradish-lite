import { useEffect, useState } from "react";
import { useLocation, useNavigate } from "react-router";

import { groupPage, WORK_ITEM } from "../../app/destinations";

/** A run's own page, under what the group has running. The identifier is escaped all the same. */
export function runHref(groupId: string, runId: string): string {
  return `${groupPage(groupId, WORK_ITEM.segment)}/${encodeURIComponent(runId)}`;
}

/** What a step's page picked, held in its address beside the list's own: the value whose tries it lists, and the try open. */
export const PICKED_VALUE = "value";

export const PICKED_TRY = "try";

/** The list's own query, without what a step's page picked: a link leaving the step keeps the one, never the other. */
export function listQuery(search: string): string {
  const query = new URLSearchParams(search);
  query.delete(PICKED_VALUE);
  query.delete(PICKED_TRY);
  const kept = query.toString();
  return kept === "" ? "" : `?${kept}`;
}

/** A link's state from a run to one of its steps or back, so the page it opens starts the keyboard at its heading. */
export const WITHIN_THE_RUN = { withinTheRun: true } as const;

/**
 * Whether this page was opened by such a link, read once as it is drawn; the address then forgets it, so a page
 * drawn anew at the same address, as a change of drawing draws one, starts the keyboard nowhere.
 */
export function useArrivedWithinTheRun(): boolean {
  const location = useLocation();
  const navigate = useNavigate();
  const [arrived] = useState(() => arrivedWithinTheRun(location.state));
  useEffect(() => {
    if (arrivedWithinTheRun(location.state)) {
      const { pathname, search, hash } = location;
      void navigate({ pathname, search, hash }, { replace: true, state: null });
    }
  }, [location, navigate]);
  return arrived;
}

function arrivedWithinTheRun(state: unknown): boolean {
  return (
    typeof state === "object" &&
    state !== null &&
    "withinTheRun" in state &&
    state.withinTheRun === true
  );
}

/** One step's page, in the run's place under what the group has running. */
export function stepHref(
  groupId: string,
  runId: string,
  stepId: string,
): string {
  return `${runHref(groupId, runId)}/steps/${encodeURIComponent(stepId)}`;
}

/** What a step's message is found by within its run's conversation, as an address's fragment names it. */
export function stepAnchor(stepId: string): string {
  return `step-${stepId}`;
}
