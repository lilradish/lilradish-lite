import { describe, expect, it } from "vitest";

import { inGroup } from "../../testutil/standingRead";
import { entryHref, JUST_MADE, justMade } from "./entryAddress";
import { LIBRARY_KINDS } from "./libraryKinds";

const TRIAGE = inGroup(
  "00000003-0000-4000-8000-000000000b11",
  "TRIAGE",
  "Triage",
  [],
);

const ENTRY = "00000006-0000-4000-8000-000000000b11";

describe("entryHref", () => {
  it("names the entry's own page under its kind's page in the group named by its key, and no version, which opens the newest", () => {
    expect(entryHref(TRIAGE, LIBRARY_KINDS.reference_list, ENTRY)).toBe(
      `/groups/TRIAGE/reference-lists/${ENTRY}`,
    );
  });

  it("names the version open in the address, and escapes what it is handed", () => {
    expect(entryHref(TRIAGE, LIBRARY_KINDS.workflow, "a/b", "v&1")).toBe(
      "/groups/TRIAGE/workflows/a%2Fb?version=v%261",
    );
  });
});

describe("justMade", () => {
  it.each([
    ["the state an entry just made is opened with", JUST_MADE, true],
    ["no state", null, false],
    ["state that says otherwise", { justMade: "yes" }, false],
    ["another page's state", { from: "/groups" }, false],
  ])("reads %s", (_case, state, made) => {
    expect(justMade(state)).toBe(made);
  });
});
