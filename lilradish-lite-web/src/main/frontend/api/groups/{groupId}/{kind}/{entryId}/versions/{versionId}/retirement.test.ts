import { describe, expect, it } from "vitest";

import { answering } from "../../../../../../../testutil/answering";
import { retire } from "./retirement";

const GROUP = "00000003-0000-4000-8000-000000000ad1";

const ENTRY = "00000006-0000-4000-8000-000000000ad1";

const VERSION = "00000007-0000-4000-8000-000000000ad1";

describe("retire", () => {
  it("asks PUT of the version's retirement, sending nothing, and reads the entry it answers with", async () => {
    const sent = answering([
      JSON.stringify({
        entryId: ENTRY,
        kind: "question",
        name: "Triage",
        acts: [],
        versions: [],
      }),
      200,
    ]);

    const read = await retire(
      GROUP,
      "question",
      ENTRY,
      VERSION,
      new AbortController().signal,
    );

    const [address, init] = sent.mock.calls[0]!;
    expect(address).toBe(
      `/api/groups/${GROUP}/questions/${ENTRY}/versions/${VERSION}/retirement`,
    );
    expect(init?.method).toBe("PUT");
    expect(init?.body).toBeUndefined();
    expect(read.entryId).toBe(ENTRY);
  });
});
