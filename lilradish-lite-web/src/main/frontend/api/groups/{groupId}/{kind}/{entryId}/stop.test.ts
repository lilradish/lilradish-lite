import { describe, expect, it } from "vitest";

import { answering } from "../../../../../testutil/answering";
import { letEntryGo, stopEntry } from "./stop";

const GROUP = "00000003-0000-4000-8000-000000000a91";

const ENTRY = "00000006-0000-4000-8000-000000000a91";

const READ = {
  entryId: ENTRY,
  kind: "question",
  name: "Triage",
  acts: ["let_go"],
  versions: [],
};

describe.each([
  ["stopEntry", stopEntry, "PUT"],
  ["letEntryGo", letEntryGo, "DELETE"],
] as const)("%s", (_name, change, method) => {
  it(`asks ${method} of the entry's stop, sending nothing, and reads the entry it answers with`, async () => {
    const sent = answering([JSON.stringify(READ), 200]);

    const read = await change(
      GROUP,
      "question",
      ENTRY,
      new AbortController().signal,
    );

    const [address, init] = sent.mock.calls[0]!;
    expect(address).toBe(`/api/groups/${GROUP}/questions/${ENTRY}/stop`);
    expect(init?.method).toBe(method);
    expect(init?.body).toBeUndefined();
    expect(read).toEqual({ ...READ, acts: new Set(["let_go"]) });
  });
});
