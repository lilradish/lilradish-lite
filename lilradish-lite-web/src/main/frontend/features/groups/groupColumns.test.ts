import { describe, expect, it, vi } from "vitest";

import type { RegisteredGroup } from "../../api/groups";
import { BY_NAME, GROUP_SORTABLE, groupColumns } from "./groupColumns";

const PAYROLL: RegisteredGroup = {
  groupId: "00000003-0000-4000-8000-000000000701",
  key: "PAYROLL",
  name: "Payroll",
  canBeAdministered: true,
  memberCount: 2,
};

const EMPTY_ROOM: RegisteredGroup = {
  groupId: "00000003-0000-4000-8000-000000000704",
  key: "VOID",
  name: "Empty room",
  canBeAdministered: false,
  memberCount: 0,
};

function cellsOf(group: RegisteredGroup) {
  return groupColumns((each) => `control for ${each.key}`).map((column) =>
    column.cell(group),
  );
}

describe("groupColumns", () => {
  /** Which columns there are, and which of them sort, is the rule itself. */
  it("heads the register with its key, its name, whether it can be administered and how many are in it, all sorting, then a control that does not", () => {
    expect(
      groupColumns(() => null).map((column) => [
        column.label,
        "sortKey" in column ? column.sortKey : null,
      ]),
    ).toEqual([
      ["Key", "key"],
      ["Group", "name"],
      ["Can be administered", "canBeAdministered"],
      ["Members", "memberCount"],
      ["", null],
    ]);
  });

  it.each([
    [PAYROLL, ["PAYROLL", "Payroll", "Yes", 2, "control for PAYROLL"]],
    [EMPTY_ROOM, ["VOID", "Empty room", "No", 0, "control for VOID"]],
  ])(
    "shows %o's facts in the reader's words, and the control it is handed for that row last",
    (group, cells) => {
      expect(cellsOf(group)).toEqual(cells);
    },
  );

  it("asks for the row's control with that row's group, and with no other", () => {
    const control = vi.fn(() => null);

    groupColumns(control)[4].cell(PAYROLL);

    expect(control.mock.calls).toEqual([[PAYROLL]]);
  });
});

describe("GROUP_SORTABLE", () => {
  /** An address may ask for exactly the columns a heading offers to sort by, and no other. */
  it("holds exactly the columns whose heading sorts", () => {
    expect(Object.keys(GROUP_SORTABLE).sort()).toEqual(
      groupColumns(() => null)
        .flatMap((column) => ("sortKey" in column ? [column.sortKey] : []))
        .sort(),
    );
  });
});

describe("BY_NAME", () => {
  it("opens the register on its names ascending, a column no row leaves empty", () => {
    expect(BY_NAME).toEqual({ column: "name", descending: false });
  });
});
