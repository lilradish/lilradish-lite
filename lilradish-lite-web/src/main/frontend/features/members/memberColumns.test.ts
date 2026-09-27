import { describe, expect, it } from "vitest";

import type { Member } from "../../api/groups/{groupId}/members";
import {
  BY_USER_NUMBER,
  MEMBER_SORTABLE,
  memberColumns,
} from "./memberColumns";

/** Words the cell sets apart, bracketed by the isolate controls written as code points. */
function setApart(words: string): string {
  return `${String.fromCodePoint(0x2068)}${words}${String.fromCodePoint(0x2069)}`;
}

const ADA: Member = {
  subjectId: "00000002-0000-4000-8000-0000000009b1",
  userId: "0009b1",
  displayName: "Ada Lovelace",
  roles: new Set(["owner", "overseer", "operator"]),
};

const NAMELESS: Member = {
  subjectId: "00000002-0000-4000-8000-0000000009b2",
  userId: "0009b2",
  roles: new Set(["operator"]),
};

function cellsOf(member: Member) {
  return memberColumns().map((column) => column.cell(member));
}

describe("memberColumns", () => {
  /** Which columns there are, and which of them sort, is the rule itself. */
  it("heads the members with who they are and what they hold here, sorting by all but the roles", () => {
    expect(
      memberColumns().map((column) => [
        column.label,
        "sortKey" in column ? column.sortKey : null,
      ]),
    ).toEqual([
      ["User number", "userId"],
      ["Name", "displayName"],
      ["Roles", null],
    ]);
  });

  /** Every role they hold, and never only the highest of them. */
  it("shows every role held in the reader's words, in their own order and each set apart", () => {
    expect(cellsOf(ADA)).toEqual([
      "0009b1",
      "Ada Lovelace",
      `${setApart("Operator")}, ${setApart("Overseer")}, ${setApart("Owner")}`,
    ]);
  });

  /** The user number stands alone, and nothing is invented in the name's place. */
  it("shows nothing where no name is held", () => {
    expect(cellsOf(NAMELESS)).toEqual(["0009b2", "", setApart("Operator")]);
  });
});

describe("MEMBER_SORTABLE", () => {
  /** An address may ask for exactly the columns a heading offers to sort by, and no other. */
  it("holds exactly the columns whose heading sorts", () => {
    expect(Object.keys(MEMBER_SORTABLE).sort()).toEqual(
      memberColumns()
        .flatMap((column) => ("sortKey" in column ? [column.sortKey] : []))
        .sort(),
    );
  });
});

describe("BY_USER_NUMBER", () => {
  it("opens the members on their user numbers ascending, a column no row leaves empty", () => {
    expect(BY_USER_NUMBER).toEqual({ column: "userId", descending: false });
  });
});
