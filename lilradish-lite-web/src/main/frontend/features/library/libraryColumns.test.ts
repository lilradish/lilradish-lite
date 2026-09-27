import { describe, expect, it } from "vitest";

import type { EntryRow } from "../../api/groups/{groupId}/{kind}";
import { BY_NAME, LIBRARY_SORTABLE, libraryColumns } from "./libraryColumns";

const WAITING: EntryRow = {
  entryId: "00000006-0000-4000-8000-000000000b01",
  name: "Triage",
  inService: 3,
  submitted: true,
  stopped: false,
};

const STOPPED: EntryRow = {
  entryId: "00000006-0000-4000-8000-000000000b02",
  name: "Handle",
  submitted: false,
  stopped: true,
};

describe("libraryColumns", () => {
  it("heads a column for each thing a row says, each sortable by the column the server sorts it by", () => {
    expect(
      libraryColumns().map(({ label, sortKey }) => [label, sortKey]),
    ).toEqual([
      ["Name", "name"],
      ["In service", "inService"],
      ["Submitted", "submitted"],
      ["Stopped", "stopped"],
    ]);
  });

  it.each([
    [
      "one waiting and running",
      WAITING,
      ["Triage", "Version 3", "Waiting", "No"],
    ],
    [
      "one stopped with none in service",
      STOPPED,
      ["Handle", "None", "No", "Stopped"],
    ],
  ])(
    "fills every cell of %s with a fact, none left empty",
    (_case, row, cells) => {
      expect(libraryColumns().map(({ cell }) => cell(row))).toEqual(cells);
    },
  );

  it("opens on the name, which no entry is without, in the order that comes first", () => {
    expect(BY_NAME).toEqual({ column: "name", descending: false });
    expect(Object.keys(LIBRARY_SORTABLE)).toEqual(
      libraryColumns().map(({ sortKey }) => sortKey),
    );
  });
});
