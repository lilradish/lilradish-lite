import type { createMemoryRouter } from "react-router";
import { expect, onTestFinished } from "vitest";

import { IN_A_GROUP } from "../app/destinations";

/**
 * The production route guard rewrites a group's identifier as its key, so where a link landed
 * cannot tell which it was made with. Every harness drawing a group's pages calls it on the router it builds.
 *
 * Only a spec may import it, for the reason `answering` gives.
 */
export function namingGroupsByKeyAlone(
  router: ReturnType<typeof createMemoryRouter>,
  groupIds: readonly string[],
) {
  const went = [router.state.location.pathname];
  const unsubscribe = router.subscribe((state) => {
    went.push(state.location.pathname);
  });
  const linked: string[] = [];
  let heard = 0;
  // Read when the test is over, after the tree is gone: every href a link ever
  // held is in a record, as an added link's or as a changed one's old value.
  const watcher = new MutationObserver((records) => {
    heard += hrefsIn(records, linked);
  });
  watcher.observe(document.body, {
    subtree: true,
    childList: true,
    attributeFilter: ["href"],
    attributeOldValue: true,
  });
  onTestFinished(() => {
    heard += hrefsIn(watcher.takeRecords(), linked);
    watcher.disconnect();
    unsubscribe();
    // Drawing anything at all is heard, so nothing heard is a watcher that saw nothing, never a clean spec.
    expect(heard).toBeGreaterThan(0);
    const byIdentifier = (address: string) =>
      groupIds.some((groupId) =>
        address.startsWith(`${IN_A_GROUP}/${groupId}`),
      );
    expect(went.filter(byIdentifier)).toEqual([]);
    expect(linked.filter(byIdentifier)).toEqual([]);
  });
}

/** Every href the records hold, into `linked`; how many records there were. */
function hrefsIn(records: readonly MutationRecord[], linked: string[]): number {
  for (const record of records) {
    if (record.type === "attributes") {
      linked.push(
        record.oldValue ?? "",
        (record.target as Element).getAttribute("href") ?? "",
      );
    }
    for (const added of record.addedNodes) {
      if (added instanceof Element) {
        for (const link of [added, ...added.querySelectorAll("[href]")]) {
          linked.push(link.getAttribute("href") ?? "");
        }
      }
    }
  }
  return records.length;
}
