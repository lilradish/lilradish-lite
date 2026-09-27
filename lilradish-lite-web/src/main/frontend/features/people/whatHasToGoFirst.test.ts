import { describe, expect, it } from "vitest";

import { whatHasToGoFirst } from "./whatHasToGoFirst";

/** Words set apart inside a sentence, bracketed by the isolate controls written as code points. */
function setApart(words: string): string {
  return `${String.fromCodePoint(0x2068)}${words}${String.fromCodePoint(0x2069)}`;
}

describe("whatHasToGoFirst", () => {
  it.each([
    [
      "one role",
      ["steward"],
      [],
      `Their estate role has to be withdrawn first: ${setApart("Steward")}.`,
    ],
    [
      "two roles",
      ["watcher", "steward"],
      [],
      `Their estate roles have to be withdrawn first: ${setApart("Steward")} and ${setApart("Watcher")}.`,
    ],
    [
      "one group",
      [],
      ["Payroll"],
      `They have to leave this group first: ${setApart("Payroll")}.`,
    ],
    [
      "two groups",
      [],
      ["Payroll", "finance"],
      `They have to leave these groups first: ${setApart("Payroll")} and ${setApart("finance")}.`,
    ],
    [
      "one of each, roles first",
      ["steward"],
      ["Payroll"],
      `Their estate role has to be withdrawn first: ${setApart("Steward")}. ` +
        `They have to leave this group first: ${setApart("Payroll")}.`,
    ],
  ])("names %s, counted", (_case, roles, groups, words) => {
    expect(whatHasToGoFirst({ estateRoles: new Set(roles), groups })).toBe(
      words,
    );
  });

  it("names nothing where nothing holds them", () => {
    expect(
      whatHasToGoFirst({ estateRoles: new Set<string>(), groups: [] }),
    ).toBeUndefined();
  });
});
