import { describe, expect, it } from "vitest";

import type { Version } from "../../api/groups/{groupId}/{kind}";
import {
  NEWEST_FIRST,
  versionColumns,
  versionsInOrder,
} from "./versionColumns";

const AVO = { userId: "000c51", displayName: "Avo Lumerace" };

const NAMELESS = { userId: "000c52" };

function version(over: Partial<Version>): Version {
  return {
    versionId: `00000007-0000-4000-8000-00000000${String(over.number ?? 1).padStart(4, "0")}`,
    number: 1,
    standing: "draft",
    writers: [AVO],
    writtenByMigration: false,
    acts: new Set(),
    pinnedBy: [],
    ...over,
  };
}

function setApart(words: string): string {
  return `${String.fromCodePoint(0x2068)}${words}${String.fromCodePoint(0x2069)}`;
}

/** Each cell of a version's row, as the table is handed it. */
function cellsOf(row: Version): unknown[] {
  return versionColumns().map((column) => column.cell(row));
}

describe("versionColumns", () => {
  /** Who wrote it and who approved it are names, which have no order a reader would sort by. */
  it("heads a column each for the version, its standing, who wrote it and who approved it, the first two sorting", () => {
    const columns = versionColumns();

    expect(columns.map((column) => column.label)).toEqual([
      "Version",
      "Standing",
      "Written by",
      "Approved by",
    ]);
    expect(
      columns.map((column) => ("sortKey" in column ? column.sortKey : null)),
    ).toEqual(["number", "standing", null, null]);
  });

  it.each([
    [
      "a draft somebody wrote, approved by nobody yet",
      version({ number: 3 }),
      ["Version 3", "Draft", setApart("Avo Lumerace"), ""],
    ],
    [
      "a version in service, its writers each set apart and the one with no name by number",
      version({
        number: 2,
        standing: "in_service",
        writers: [AVO, NAMELESS],
        approval: { approver: AVO },
      }),
      [
        "Version 2",
        "In service",
        `${setApart("Avo Lumerace")}, ${setApart("000c52")}`,
        "Avo Lumerace",
      ],
    ],
    [
      "one a migration wrote and put into service",
      version({
        standing: "in_service",
        writers: [],
        writtenByMigration: true,
        approval: {},
      }),
      [
        "Version 1",
        "In service",
        "Made when this system was set up; nobody wrote it.",
        "Put into service when this system was set up; nobody approved it.",
      ],
    ],
    [
      "one a migration began and somebody wrote since, at a standing with no words here",
      version({ standing: "archived", writtenByMigration: true }),
      ["Version 1", "archived", setApart("Avo Lumerace"), ""],
    ],
  ])("draws %s", (_case, row, cells) => {
    expect(cellsOf(row)).toEqual(cells);
  });
});

describe("versionsInOrder", () => {
  const RETIRED = version({ number: 1, standing: "retired" });
  const IN_SERVICE = version({ number: 2, standing: "in_service" });
  const UNWORDED = version({ number: 3, standing: "archived" });
  const SUBMITTED = version({ number: 4, standing: "submitted" });
  const NEWER_IN_SERVICE = version({ number: 5, standing: "in_service" });
  const DRAFT = version({ number: 6, standing: "draft" });

  /** Newest first, as the server sends them: a reorder that kept the order handed in would hide behind it. */
  const SENT = [
    DRAFT,
    NEWER_IN_SERVICE,
    SUBMITTED,
    UNWORDED,
    IN_SERVICE,
    RETIRED,
  ];

  it.each([
    [
      "by number, the highest first",
      NEWEST_FIRST,
      [DRAFT, NEWER_IN_SERVICE, SUBMITTED, UNWORDED, IN_SERVICE, RETIRED],
    ],
    [
      "by number, the lowest first",
      { column: "number", descending: false },
      [RETIRED, IN_SERVICE, UNWORDED, SUBMITTED, NEWER_IN_SERVICE, DRAFT],
    ],
    [
      "by how far along each standing is, one with no words past them all, versions alike as they came",
      { column: "standing", descending: false },
      [DRAFT, SUBMITTED, NEWER_IN_SERVICE, IN_SERVICE, RETIRED, UNWORDED],
    ],
    [
      "the same the other way, versions alike still as they came",
      { column: "standing", descending: true },
      [UNWORDED, RETIRED, NEWER_IN_SERVICE, IN_SERVICE, SUBMITTED, DRAFT],
    ],
  ] as const)("puts them in order %s", (_case, order, ordered) => {
    expect(versionsInOrder(SENT, order)).toEqual(ordered);
  });

  it("leaves the versions it was handed in the order they came", () => {
    const handed = [...SENT];

    versionsInOrder(handed, { column: "standing", descending: false });

    expect(handed).toEqual(SENT);
  });
});
