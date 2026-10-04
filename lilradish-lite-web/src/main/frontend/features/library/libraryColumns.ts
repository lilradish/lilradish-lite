import Box from "@mui/material/Box";
import Chip from "@mui/material/Chip";
import Typography from "@mui/material/Typography";
import { createElement } from "react";

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
  status: true,
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
      cell: (row: EntryRow) =>
        createElement(
          Box,
          null,
          createElement("bdi", null, row.name),
          createElement(
            Typography,
            { variant: "body2", sx: { color: "text.secondary" } },
            row.purpose === undefined
              ? say("entry.saysNothing")
              : createElement("bdi", null, row.purpose),
          ),
        ),
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
      label: say("library.status"),
      cell: (row: EntryRow) =>
        row.submitted || row.stopped
          ? createElement(
              Box,
              { sx: { display: "flex", flexWrap: "wrap", gap: 0.5 } },
              row.submitted
                ? createElement(Chip, {
                    label: say("entry.statusWaiting"),
                    size: "small",
                    color: "primary",
                    variant: "outlined",
                  })
                : null,
              row.stopped
                ? createElement(Chip, {
                    label: say("entry.stopped"),
                    size: "small",
                    color: "warning",
                    variant: "outlined",
                  })
                : null,
            )
          : "—",
      sortKey: "status",
    },
  ] as const;
}
