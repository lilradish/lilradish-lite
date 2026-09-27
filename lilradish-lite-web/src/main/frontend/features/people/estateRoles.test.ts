import { describe, expect, it } from "vitest";

import {
  ESTATE_ROLES,
  heldInOrder,
  isEstateRole,
  roleSaid,
} from "./estateRoles";

describe("ESTATE_ROLES", () => {
  it("lists every role the estate grants, in the order they are shown", () => {
    expect(ESTATE_ROLES).toEqual(["steward", "watcher"]);
  });
});

describe("isEstateRole", () => {
  it.each(["steward", "watcher"])("knows %s", (role) => {
    expect(isEstateRole(role)).toBe(true);
  });

  /** Asked of the table's own members, never of what every object inherits. */
  it.each(["auditor", "Steward", "", "toString", "constructor", "__proto__"])(
    "does not take %o for a role",
    (word) => {
      expect(isEstateRole(word)).toBe(false);
    },
  );
});

describe("roleSaid", () => {
  it.each([
    ["steward", "Steward"],
    ["watcher", "Watcher"],
  ])("says %s in the reader's words", (role, words) => {
    expect(roleSaid(role)).toBe(words);
  });

  /** Hidden, it would let somebody holding it read as holding nothing. */
  it("shows a role this build has no words for as it was spelt", () => {
    expect(roleSaid("auditor")).toBe("auditor");
  });
});

describe("heldInOrder", () => {
  it("puts the roles held in the order they are shown, whatever order they arrived in", () => {
    expect(heldInOrder(new Set(["watcher", "steward"]))).toEqual([
      "steward",
      "watcher",
    ]);
  });

  it("puts a role this build does not know after those it does, and leaves out what is not held", () => {
    expect(heldInOrder(new Set(["auditor", "watcher"]))).toEqual([
      "watcher",
      "auditor",
    ]);
  });

  it("holds nothing for somebody holding nothing", () => {
    expect(heldInOrder(new Set())).toEqual([]);
  });
});
