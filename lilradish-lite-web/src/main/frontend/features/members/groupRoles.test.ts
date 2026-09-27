import { describe, expect, it } from "vitest";

import {
  GROUP_ROLES,
  groupRoleSaid,
  groupRolesInOrder,
  isGroupRole,
} from "./groupRoles";

describe("GROUP_ROLES", () => {
  it("lists every role a group has, in the order they are shown", () => {
    expect(GROUP_ROLES).toEqual(["operator", "overseer", "owner"]);
  });
});

describe("isGroupRole", () => {
  it.each(["operator", "overseer", "owner"])("knows %s", (role) => {
    expect(isGroupRole(role)).toBe(true);
  });

  /** Asked of the table's own members, never of what every object inherits. */
  it.each(["steward", "Owner", "", "toString", "constructor", "__proto__"])(
    "does not take %o for a role",
    (word) => {
      expect(isGroupRole(word)).toBe(false);
    },
  );
});

describe("groupRoleSaid", () => {
  it.each([
    ["operator", "Operator"],
    ["overseer", "Overseer"],
    ["owner", "Owner"],
  ])("says %s in the reader's words", (role, words) => {
    expect(groupRoleSaid(role)).toBe(words);
  });

  /** Hidden, it would let somebody holding it read as holding nothing. */
  it("shows a role this build has no words for as it was spelt", () => {
    expect(groupRoleSaid("captain")).toBe("captain");
  });
});

describe("groupRolesInOrder", () => {
  it("puts the roles held in the order they are shown, whatever order they arrived in", () => {
    expect(groupRolesInOrder(new Set(["owner", "operator"]))).toEqual([
      "operator",
      "owner",
    ]);
  });

  it("puts a role this build does not know after those it does, and leaves out what is not held", () => {
    expect(groupRolesInOrder(new Set(["captain", "overseer"]))).toEqual([
      "overseer",
      "captain",
    ]);
  });

  it("holds nothing for somebody holding nothing", () => {
    expect(groupRolesInOrder(new Set())).toEqual([]);
  });
});
