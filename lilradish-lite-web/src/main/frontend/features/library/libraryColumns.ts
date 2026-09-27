import type {
  EntryRow,
  LibrarySortColumn,
} from "../../api/groups/{groupId}/{kind}";
import { say } from "../../i18n/app";
import type { Ordering } from "../../lib/collection/ordering";

/** Every column a group's entries can be sorted by, closed over the union in both directions. */
export const LIBRARY_SORTABLE = {
  name: true,
  inService: true,
  submitted: true,
  stopped: true,
} satisfies Record<LibrarySortColumn, true>;

/** On a column no row leaves empty. */
export const BY_NAME: Ordering<LibrarySortColumn> = {
  column: "name",
  descending: false,
};

/** One row per entry, the first cell the link that opens it; every cell a fact, none left empty. */
export function libraryColumns() {
  return [
    {
      label: say("entry.name"),
      cell: (row: EntryRow) => row.name,
      sortKey: "name",
    },
    {
      label: say("entry.inService"),
      cell: (row: EntryRow) =>
        row.inService === undefined
          ? say("entry.noneInService")
          : say("entry.inServiceVersion", { number: row.inService }),
      sortKey: "inService",
    },
    {
      label: say("entry.submitted"),
      cell: (row: EntryRow) =>
        say(row.submitted ? "entry.waiting" : "entry.notWaiting"),
      sortKey: "submitted",
    },
    {
      label: say("entry.stopped"),
      cell: (row: EntryRow) =>
        say(row.stopped ? "entry.isStopped" : "entry.notStopped"),
      sortKey: "stopped",
    },
  ] as const;
}
