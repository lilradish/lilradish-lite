import type { ClientOnErrorFunction } from "react-router";

/**
 * Every error caught on this side, and the one place any is written down. Not
 * named `reportError`: that is a browser global, and a missed import calls it.
 */
export function reportCaught(error: unknown): void {
  console.error(error);
}

/**
 * The router's errors, into the one entry. A render error reaches React's
 * `onCaughtError` as well; only one that never rendered (a loader's, an
 * action's) comes without `errorInfo`, so only that one is reported here.
 */
export const reportRouterError: ClientOnErrorFunction = (
  error,
  { errorInfo },
) => {
  if (errorInfo === undefined) {
    reportCaught(error);
  }
};
