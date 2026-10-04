import { useEffect, useState } from "react";
import { useLocation, useNavigate } from "react-router";

import { groupPage, WORK_ITEM } from "../../app/destinations";

/** Work's two tabs, as an address names them; New run first, as Work opens on a device that has kept no tab. */
export const WORK_TABS = ["newRun", "history"] as const;

export type WorkTab = (typeof WORK_TABS)[number];

/** Where an address names the tab Work draws, which no filter or order names of itself. */
export const NAMED_TAB = "tab";

/** The tab an address names; none where it names none, or one this build does not know. */
export function tabNamed(search: string): WorkTab | undefined {
  const named = new URLSearchParams(search).get(NAMED_TAB);
  return WORK_TABS.find((each) => each === named);
}

/** A run's own page, under what the group has running. The identifier is escaped all the same. */
export function runHref(groupId: string, runId: string): string {
  return `${groupPage(groupId, WORK_ITEM.segment)}/${encodeURIComponent(runId)}`;
}

/** What a step's page picked, held in its address beside the list's own: the value whose tries it lists, and the try open. */
export const PICKED_VALUE = "value";

export const PICKED_TRY = "try";

/**
 * The list's own query, without what a step's page picked or the tab Work was drawn on: a link leaving the step keeps
 * the one, never the other, and the way back names the tab it goes back to.
 */
export function listQuery(search: string): string {
  const query = new URLSearchParams(search);
  query.delete(PICKED_VALUE);
  query.delete(PICKED_TRY);
  query.delete(NAMED_TAB);
  const kept = query.toString();
  return kept === "" ? "" : `?${kept}`;
}

/** History as the list's own query left it: its filter and order, and the tab named. */
export function historyQuery(search: string): string {
  const query = new URLSearchParams(listQuery(search));
  query.set(NAMED_TAB, "history" satisfies WorkTab);
  return `?${query.toString()}`;
}

/** A link's state from a run to one of its steps, so the step's page starts the keyboard at its heading. */
export const WITHIN_THE_RUN = { withinTheRun: true } as const;

/**
 * Whether this page was opened by such a link, read once as it is drawn; the address then forgets it, so the page
 * drawn anew at the same address starts the keyboard nowhere.
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
