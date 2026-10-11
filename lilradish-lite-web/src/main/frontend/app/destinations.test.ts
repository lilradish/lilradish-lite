import { describe, expect, it } from "vitest";

import { inGroup } from "../testutil/standingRead";
import {
  DESTINATION_GROUPS,
  GROUP_ITEMS,
  groupAddressed,
  groupNamed,
  groupPage,
  MY_WORK,
  opensIn,
} from "./destinations";

const DESTINATIONS = DESTINATION_GROUPS.flatMap((group) => group.items);

const PAYROLL_ID = "00000003-0000-4000-8000-000000000921";

const PAYROLL = inGroup(PAYROLL_ID, "PAYROLL", "Payroll", []);

const TRIAGE = inGroup(
  "00000003-0000-4000-8000-000000000922",
  "TRIAGE",
  "Triage",
  [],
);

const STUFF = inGroup(
  "00000003-0000-4000-8000-000000000923",
  "STUFF",
  "Stuff",
  [],
);

const KIOSK = inGroup(
  "00000003-0000-4000-8000-000000000924",
  "KIOSK",
  "Kiosk",
  [],
);

/** Every place an entry leads, the estate's, My work and each page of a group. */
const PATHS = [
  ...DESTINATIONS.map((item) => item.to),
  MY_WORK.to,
  ...GROUP_ITEMS.map((item) => groupPage(PAYROLL, item.segment)),
];

describe("DESTINATION_GROUPS", () => {
  /** A relative path resolves against the screen in view, right only where written. */
  it("roots every path, so an entry means one place from anywhere", () => {
    expect(PATHS.filter((to) => !to.startsWith("/"))).toEqual([]);
    expect(PATHS.length).toBeGreaterThan(0);
  });

  it("sends no two entries to the same place", () => {
    expect(new Set(PATHS).size).toBe(PATHS.length);
    expect(PATHS.length).toBeGreaterThan(0);
  });

  /**
   * The mapping written out, because none of it is computable: which act a
   * place needs follows from what the screen does, and this is where that was
   * decided. An act swapped for another the server also publishes still gates
   * the place — on the wrong key, held by people it was never meant to admit —
   * which is a working gate to every eye but this one.
   *
   * An entry gating on nothing would be offered to everyone, so the
   * declaration demands an act.
   */
  it("demands of each place exactly the act that place was decided to need", () => {
    expect(DESTINATIONS.map((item) => [item.to, item.act])).toEqual([
      ["/system/people", "keep_pool"],
      ["/system/groups", "keep_group_register"],
      ["/system/soundness", "check_soundness"],
      ["/system/measurements", "read_measurements"],
    ]);
  });

  /**
   * Two entries sharing an act would make one grant open two doors, which is
   * the shape a widened role hides in: the second door is never written down as
   * something the role was given.
   */
  it("gates no two places on the one act", () => {
    const acts = DESTINATIONS.map((item) => item.act);

    expect(new Set(acts).size).toBe(acts.length);
  });

  it("gives every entry and every group words of its own", () => {
    const labels = [
      ...DESTINATION_GROUPS.map((group) => group.label),
      ...DESTINATIONS.map((item) => item.label),
      MY_WORK.label,
      ...GROUP_ITEMS.map((item) => item.label),
    ];

    expect(new Set(labels).size).toBe(labels.length);
    expect(labels.length).toBeGreaterThan(0);
  });
});

describe("MY_WORK", () => {
  it("stands at one address of its own, outside every group's", () => {
    expect(MY_WORK.to).toBe("/my-work");
  });
});

describe("GROUP_ITEMS", () => {
  /** The whole table written out: a swapped or dropped gate fails nowhere but here. */
  it("draws each page of a group in its order, reached by exactly what it was decided to need", () => {
    expect(GROUP_ITEMS.map((item) => [item.segment, item.reachedBy])).toEqual([
      ["work", null],
      ["workflows", null],
      ["questions", null],
      ["reference-lists", null],
      ["members", "read_membership"],
    ]);
  });
});

describe("groupPage", () => {
  it("addresses a page by the group's key, never its identifier, beneath the pages every group has", () => {
    expect(groupPage(PAYROLL, "members")).toBe("/groups/PAYROLL/members");
  });

  /** What the server sent is one segment whatever it holds, so it cannot climb out of its group. */
  it("keeps a key to its one segment", () => {
    expect(
      groupPage(inGroup(PAYROLL_ID, "a/../b?c#d", "Payroll", []), "work"),
    ).toBe("/groups/a%2F..%2Fb%3Fc%23d/work");
  });
});

describe("groupNamed", () => {
  it.each([
    ["its key as it is held", "PAYROLL", PAYROLL],
    ["its key in small letters", "payroll", PAYROLL],
    ["its key in mixed case", "PayRoll", PAYROLL],
    ["its identifier", PAYROLL_ID, PAYROLL],
    ["the key of another of the reader's groups", "triage", TRIAGE],
    ["a key holding letters others fold to", "stuff", STUFF],
  ])("finds the reader's group by %s", (_case, named, found) => {
    expect(groupNamed([PAYROLL, TRIAGE, STUFF, KIOSK], named)).toBe(found);
  });

  /** A group the reader is not in is not in their standing, so it reads as a key or an identifier no group holds. */
  it.each([
    ["a key no group of theirs holds", "FINANCE"],
    [
      "an identifier no group of theirs holds",
      "00000003-0000-4000-8000-000000000939",
    ],
    [
      "a key with a dotless i folding to its I",
      `tr${String.fromCodePoint(0x131)}age`,
    ],
    [
      "a key with a long s folding to its S",
      `${String.fromCodePoint(0x17f)}tuff`,
    ],
    [
      "a key with a ligature folding to its FF",
      `stu${String.fromCodePoint(0xfb00)}`,
    ],
    [
      "a key with the Kelvin sign for its K",
      `${String.fromCodePoint(0x212a)}iosk`,
    ],
    [
      "a key in full-width letters",
      String.fromCodePoint(
        0xff30,
        0xff21,
        0xff39,
        0xff32,
        0xff2f,
        0xff2c,
        0xff2c,
      ),
    ],
    ["a key with a space after it", "PAYROLL "],
    ["a key cut short", "PAYROL"],
    ["nothing at all", ""],
    ["no group segment", undefined],
  ])("finds nothing for %s", (_case, named) => {
    expect(groupNamed([PAYROLL, TRIAGE, STUFF, KIOSK], named)).toBeUndefined();
  });
});

describe("groupAddressed", () => {
  it.each([
    [
      "its key in another case",
      "/groups/payroll/work/a%2Fb/steps/c",
      "/groups/PAYROLL/work/a%2Fb/steps/c",
    ],
    [
      "its identifier",
      `/groups/${PAYROLL_ID}/members/`,
      "/groups/PAYROLL/members/",
    ],
    [
      "the pages' own segment in capitals",
      "/GROUPS/PAYROLL/work",
      "/groups/PAYROLL/work",
    ],
    ["its key as it is held", "/groups/PAYROLL/work", "/groups/PAYROLL/work"],
  ])(
    "writes a path naming the group by %s with its key, the rest kept as written",
    (_case, pathname, written) => {
      expect(groupAddressed(PAYROLL, pathname)).toBe(written);
    },
  );
});

describe("opensIn", () => {
  it.each([
    ["membership alone reaches a page it gates", [], "work", true],
    [
      "membership alone reaches no page a permission gates",
      [],
      "members",
      false,
    ],
    [
      "the permission a page is reached by reaches it",
      ["read_membership"],
      "members",
      true,
    ],
    [
      "another permission does not",
      ["change_membership", "start_run"],
      "members",
      false,
    ],
  ])("%s", (_case, permissions, segment, opens) => {
    const group = inGroup(PAYROLL_ID, "PAYROLL", "Payroll", permissions);
    const item = GROUP_ITEMS.find((each) => each.segment === segment)!;

    expect(opensIn(group, item)).toBe(opens);
  });
});
