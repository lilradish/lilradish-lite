import type { ClientOnErrorFunction } from "react-router";

/**
 * Every error caught on this side, and the one place any is written down. Not
 * named `reportError`: that is a browser global, and a missed import calls it.
 */
export function reportCaught(error: unknown): void {
  console.error(error);
}

/**
 * Render errors also reach React's `onCaughtError`; report here only errors
 * without `errorInfo`, which never rendered, to avoid recording them twice.
 */
export const reportRouterError: ClientOnErrorFunction = (
  error,
  { errorInfo },
) => {
  if (errorInfo === undefined) {
    reportCaught(error);
  }
};
