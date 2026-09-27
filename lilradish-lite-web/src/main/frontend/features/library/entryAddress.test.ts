import { describe, expect, it } from "vitest";

import { entryHref, JUST_STARTED, justStarted } from "./entryAddress";
import { LIBRARY_KINDS } from "./libraryKinds";

const GROUP = "00000003-0000-4000-8000-000000000b11";

const ENTRY = "00000006-0000-4000-8000-000000000b11";

describe("entryHref", () => {
  it("names the entry's own page under its kind's page, and no version, which opens the newest", () => {
    expect(entryHref(GROUP, LIBRARY_KINDS.reference_list, ENTRY)).toBe(
      `/groups/${GROUP}/reference-lists/${ENTRY}`,
    );
  });

  it("names the version open in the address, and escapes what it is handed", () => {
    expect(entryHref(GROUP, LIBRARY_KINDS.workflow, "a/b", "v&1")).toBe(
      `/groups/${GROUP}/workflows/a%2Fb?version=v%261`,
    );
  });
});

describe("justStarted", () => {
  it.each([
    ["the state an entry just started is opened with", JUST_STARTED, true],
    ["no state", null, false],
    ["state that says otherwise", { justStarted: "yes" }, false],
    ["another page's state", { from: "/groups" }, false],
  ])("reads %s", (_case, state, started) => {
    expect(justStarted(state)).toBe(started);
  });
});
