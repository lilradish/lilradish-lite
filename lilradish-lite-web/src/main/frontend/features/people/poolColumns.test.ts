import { describe, expect, it } from "vitest";

import type { PoolPerson } from "../../api/pool/people";
import { BY_USER_NUMBER, POOL_SORTABLE, poolColumns } from "./poolColumns";

/** Words the cell sets apart, bracketed by the isolate controls written as code points. */
function setApart(words: string): string {
  return `${String.fromCodePoint(0x2068)}${words}${String.fromCodePoint(0x2069)}`;
}

const GRACE: PoolPerson = {
  subjectId: "00000002-0000-4000-8000-000000000140",
  userId: "000140",
  displayName: "Grace Hopper",
  estateRoles: new Set(["watcher", "steward"]),
  groupCount: 2,
};

const NAMELESS: PoolPerson = {
  subjectId: "00000002-0000-4000-8000-000000000130",
  userId: "000130",
  estateRoles: new Set(),
  groupCount: 0,
};

function cellsOf(person: PoolPerson) {
  return poolColumns().map((column) => column.cell(person));
}

describe("poolColumns", () => {
  /** Which columns there are, and which of them sort, is the rule itself. */
  it("heads the pool with who they are, what they hold and how many groups, sorting by all but the roles", () => {
    expect(
      poolColumns().map((column) => [
        column.label,
        "sortKey" in column ? column.sortKey : null,
      ]),
    ).toEqual([
      ["User number", "userId"],
      ["Name", "displayName"],
      ["Estate roles", null],
      ["Groups", "groupCount"],
    ]);
  });

  it("shows the roles held in the reader's words, in their own order and each set apart, and the groups only as a count", () => {
    expect(cellsOf(GRACE)).toEqual([
      "000140",
      "Grace Hopper",
      `${setApart("Steward")}, ${setApart("Watcher")}`,
      2,
    ]);
  });

  it("shows nothing where no name or no role is held, and a count of none", () => {
    expect(cellsOf(NAMELESS)).toEqual(["000130", "", "", 0]);
  });

  /** Spelt by whoever published it, so it is set apart like any word that arrived. */
  it("shows a role this build has no words for as it was spelt", () => {
    expect(
      cellsOf({ ...NAMELESS, estateRoles: new Set(["auditor", "steward"]) })[2],
    ).toBe(`${setApart("Steward")}, ${setApart("auditor")}`);
  });
});

describe("POOL_SORTABLE", () => {
  /** An address may ask for exactly the columns a heading offers to sort by, and no other. */
  it("holds exactly the columns whose heading sorts", () => {
    expect(Object.keys(POOL_SORTABLE).sort()).toEqual(
      poolColumns()
        .flatMap((column) => ("sortKey" in column ? [column.sortKey] : []))
        .sort(),
    );
  });
});

describe("BY_USER_NUMBER", () => {
  it("opens the pool on its user numbers ascending, a column no row leaves empty", () => {
    expect(BY_USER_NUMBER).toEqual({ column: "userId", descending: false });
  });
});
