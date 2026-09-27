import { useLocation } from "react-router";

/**
 * The search a screen was reached with, read back out of history state. It
 * travels there rather than in the URL because it belongs to the journey, not
 * to the page: in the address bar it would make two ways of reaching one screen
 * look like two different screens.
 *
 * Arriving directly, from a pasted link, carries no state and so restores no
 * filter. That is the plain list, not a failure.
 */
export function returnSearch(state: unknown): string {
  if (typeof state === "object" && state !== null && "from" in state) {
    return typeof state.from === "string" ? state.from : "";
  }
  return "";
}

/**
 * What a link leading further in carries. The two ends of one contract:
 * `useReturnState` produces it, a navigable row hands it on.
 */
export interface ReturnState {
  readonly from: string;
}

/**
 * What to hand a link leading further in, so the screen it opens can offer a
 * way back. A screen that is itself a return target declares its own search;
 * every other screen passes on the one it was reached with, so a filter
 * survives more than a single hop.
 */
export function useReturnState(from?: string): ReturnState {
  const location = useLocation();
  // `??`, not `||`: an unfiltered list declares an empty search, and that is an
  // answer, not an absence.
  return { from: from ?? returnSearch(location.state) };
}
