import { useKeptChoice } from "../lib/device/useKeptChoice";

const KEPT_AS = "lilradish.navigation.sidebar";

/** Open first: a lost choice hides nothing. */
const SIDEBAR = ["expanded", "collapsed"] as const;

/** Whether the docked sidebar is open as this device last left it, and a way to change that this device keeps. */
export function useSidebarExpanded(): readonly [
  boolean,
  (next: boolean) => void,
] {
  const [kept, keep] = useKeptChoice(KEPT_AS, SIDEBAR);
  return [kept === "expanded", (next) => keep(next ? "expanded" : "collapsed")];
}
