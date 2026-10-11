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
  it("heads the register with its four sortable facts, editing in its name column", () => {
    expect(
      groupColumns(() => null).map((column) => [
        column.label,
        "sortKey" in column ? column.sortKey : null,
      ]),
    ).toEqual([
      ["Group", "name"],
      ["Key", "key"],
      ["Can be administered", "canBeAdministered"],
      ["Members", "memberCount"],
    ]);
  });

  it("sets how many are in it as a figure, and no other column", () => {
    expect(
      groupColumns(() => null)
        .filter((column) => "numeric" in column && column.numeric)
        .map((column) => column.label),
    ).toEqual(["Members"]);
  });

  it.each([
    [PAYROLL, ["control for PAYROLL", "PAYROLL", "Yes", "2"]],
    [EMPTY_ROOM, ["control for VOID", "VOID", "No", "0"]],
  ])(
    "shows %o's facts with its editable name in the name column",
    (group, cells) => {
      expect(cellsOf(group)).toEqual(cells);
    },
  );

  it("asks for the row's control with that row's group, and with no other", () => {
    const control = vi.fn(() => null);

    groupColumns(control)[0].cell(PAYROLL);

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
