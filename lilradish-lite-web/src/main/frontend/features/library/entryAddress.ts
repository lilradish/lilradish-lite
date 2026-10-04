import { groupPage } from "../../app/destinations";
import { isUnchecked } from "../../lib/request/document";
import type { LibraryKind } from "./libraryKinds";

/** What the address holds of the version open; none opens the newest. */
export const OPEN_VERSION = "version";

/** What the address holds of the step picked in a workflow version's steps, by its row; none picks none. */
export const OPEN_STEP = "step";

/** The history state an entry's page is opened with where it was just made, from a page now left. */
export const JUST_MADE = { justMade: true } as const;

/** Whether the page was opened on an entry just made, as `JUST_MADE` says. */
export function justMade(state: unknown): boolean {
  return isUnchecked(state) && state.justMade === true;
}

/** An entry's own page, on the version named or else on the newest. Identifiers are escaped all the same. */
export function entryHref(
  groupId: string,
  of: LibraryKind,
  entryId: string,
  versionId?: string,
): string {
  const entry = `${groupPage(groupId, of.item.segment)}/${encodeURIComponent(entryId)}`;
  return versionId === undefined
    ? entry
    : `${entry}?${new URLSearchParams({ [OPEN_VERSION]: versionId })}`;
}
