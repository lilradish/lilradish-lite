import { describe, expect, it } from "vitest";

import { atLibrary, type EntryKind } from "../../api/groups/{groupId}/{kind}";
import { GROUP_ITEMS } from "../../app/destinations";
import { LIBRARY_KINDS, kindSaid, pageOf, standingSaid } from "./libraryKinds";

describe("LIBRARY_KINDS", () => {
  /** A page and the address it reads are segments of the same spelling, so a kind moved on one moves on both. */
  it.each(Object.entries(LIBRARY_KINDS))(
    "puts the %s page at a group's own item, at the segment its entries are addressed under",
    async (kind, of) => {
      const address = await atLibrary("g", kind as EntryKind, (at) =>
        Promise.resolve(at),
      );

      expect(of.kind).toBe(kind);
      expect(GROUP_ITEMS).toContain(of.item);
      expect(address).toBe(`/api/groups/g/${of.item.segment}`);
    },
  );

  it("gives each kind a page of its own, reached by membership alone", () => {
    const items = Object.values(LIBRARY_KINDS).map((of) => of.item);

    expect(new Set(items).size).toBe(items.length);
    expect(items.map((item) => item.reachedBy)).toEqual([null, null, null]);
  });
});

describe("pageOf", () => {
  it("finds each kind's page, and none for a kind this build has no page for", () => {
    expect(pageOf("reference_list")).toBe(LIBRARY_KINDS.reference_list);
    expect(pageOf("dashboard")).toBeUndefined();
    expect(pageOf("toString")).toBeUndefined();
  });
});

describe("kindSaid", () => {
  it.each([
    ["workflow", "Workflow"],
    ["question", "Question"],
    ["reference_list", "Reference list"],
    ["dashboard", "dashboard"],
  ])(
    "says %s as %s, and a kind it has no words for as it was spelt",
    (kind, said) => {
      expect(kindSaid(kind)).toBe(said);
    },
  );
});

describe("standingSaid", () => {
  it.each([
    ["draft", "Draft"],
    ["submitted", "Submitted"],
    ["in_service", "In service"],
    ["retired", "Retired"],
    ["archived", "archived"],
  ])(
    "says %s as %s, and a standing it has no words for as it was spelt",
    (standing, said) => {
      expect(standingSaid(standing)).toBe(said);
    },
  );
});
