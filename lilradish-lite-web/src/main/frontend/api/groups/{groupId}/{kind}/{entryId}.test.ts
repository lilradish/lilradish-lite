import { describe, expect, it } from "vitest";

import { answering, refusalOf } from "../../../../testutil/answering";
import { NOT_AN_ADDRESS } from "../../../problem";
import {
  atEntry,
  readEntry,
  refusedForWhatMoved,
  renameEntry,
} from "./{entryId}";

const GROUP = "00000003-0000-4000-8000-000000000a81";

const ENTRY = "00000006-0000-4000-8000-000000000a81";

const READ = {
  entryId: ENTRY,
  kind: "workflow",
  name: "Handle",
  acts: [],
  versions: [],
};

describe("atEntry", () => {
  it("hands over the entry's address under its kind, each identifier escaped", async () => {
    const addresses = [ENTRY, "a/b"].map((entry) =>
      atEntry(GROUP, "reference_list", entry, (address) =>
        Promise.resolve(address),
      ),
    );

    expect(await Promise.all(addresses)).toEqual([
      `/api/groups/${GROUP}/reference-lists/${ENTRY}`,
      `/api/groups/${GROUP}/reference-lists/a%2Fb`,
    ]);
  });

  it("refuses an entry that names no address, asking nothing", async () => {
    const failure = await refusalOf(
      atEntry(GROUP, "workflow", ".", () => Promise.resolve("asked")),
    );

    expect(failure.problem).toEqual({ code: NOT_AN_ADDRESS });
  });
});

describe("readEntry", () => {
  it("reads the entry at its address", async () => {
    const sent = answering([JSON.stringify(READ), 200]);

    const read = await readEntry(
      GROUP,
      "workflow",
      ENTRY,
      new AbortController().signal,
    );

    expect(sent.mock.calls[0]![0]).toBe(
      `/api/groups/${GROUP}/workflows/${ENTRY}`,
    );
    expect(read).toEqual({ ...READ, acts: new Set() });
  });
});

describe("renameEntry", () => {
  it("sends the name and what it is for as one change, and nothing else", async () => {
    const sent = answering([JSON.stringify(READ), 200]);

    await renameEntry(
      GROUP,
      "workflow",
      ENTRY,
      { name: "Handle", purpose: "Handles a claim." },
      new AbortController().signal,
    );

    const [address, init] = sent.mock.calls[0]!;
    expect(address).toBe(`/api/groups/${GROUP}/workflows/${ENTRY}`);
    expect(init?.method).toBe("PATCH");
    expect(JSON.parse(String(init?.body))).toEqual({
      name: "Handle",
      purpose: "Handles a claim.",
    });
  });
});

describe("refusedForWhatMoved", () => {
  it.each([
    "ENTRY_NOT_IN_VIEW",
    "VERSION_NOT_IN_VIEW",
    "DRAFT_ALREADY_STARTED",
    "VERSION_STANDING_REFUSES",
    "APPROVER_WROTE_VERSION",
  ])(
    "says the entry moved under the page where the server answered %s",
    (code) => {
      expect(refusedForWhatMoved({ status: 409, code })).toBe(true);
    },
  );

  it.each([
    ["a refusal of the act", { status: 403, code: "ACT_NOT_PERMITTED" }],
    ["a name taken", { status: 409, code: "ENTRY_NAME_TAKEN" }],
    [
      "pins retired since, which name what to show",
      { status: 409, code: "VERSION_PINS_RETIRED" },
    ],
    ["a body refused", { status: 400, code: "BODY_UNUSABLE" }],
    [
      "a code of the same spelling this side minted",
      { code: "ENTRY_NOT_IN_VIEW" },
    ],
  ])("says nothing moved for %s", (_case, problem) => {
    expect(refusedForWhatMoved(problem)).toBe(false);
  });
});
