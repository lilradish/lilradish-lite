import { describe, expect, it } from "vitest";

import { inGroup } from "../testutil/standingRead";
import {
  DESTINATION_GROUPS,
  GROUP_ITEMS,
  groupPage,
  MY_WORK,
  opensIn,
} from "./destinations";

const DESTINATIONS = DESTINATION_GROUPS.flatMap((group) => group.items);

const PAYROLL = "00000003-0000-4000-8000-000000000921";

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
   * The other way of getting it wrong is no longer a way: an entry gating on
   * nothing would be offered to everyone, and the declaration demands an act.
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
  it("addresses a page by the group's identifier, beneath the pages every group has", () => {
    expect(groupPage(PAYROLL, "members")).toBe(`/groups/${PAYROLL}/members`);
  });

  /** An identifier is one segment whatever it holds, so it cannot climb out of its group. */
  it("keeps an identifier to its one segment", () => {
    expect(groupPage("a/../b?c#d", "work")).toBe(
      "/groups/a%2F..%2Fb%3Fc%23d/work",
    );
  });
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
    const group = inGroup(PAYROLL, "PAYROLL", "Payroll", permissions);
    const item = GROUP_ITEMS.find((each) => each.segment === segment)!;

    expect(opensIn(group, item)).toBe(opens);
  });
});
