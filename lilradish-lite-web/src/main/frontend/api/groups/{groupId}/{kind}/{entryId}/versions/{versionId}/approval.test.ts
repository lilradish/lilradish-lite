import { describe, expect, it } from "vitest";

import { answering } from "../../../../../../../testutil/answering";
import { approve } from "./approval";

const GROUP = "00000003-0000-4000-8000-000000000ac1";

const ENTRY = "00000006-0000-4000-8000-000000000ac1";

const VERSION = "00000007-0000-4000-8000-000000000ac1";

describe("approve", () => {
  it("asks PUT of the version's approval, sending nothing, and reads the entry it answers with", async () => {
    const sent = answering([
      JSON.stringify({
        entryId: ENTRY,
        kind: "reference_list",
        name: "Regions",
        acts: [],
        versions: [],
      }),
      200,
    ]);

    const read = await approve(
      GROUP,
      "reference_list",
      ENTRY,
      VERSION,
      new AbortController().signal,
    );

    const [address, init] = sent.mock.calls[0]!;
    expect(address).toBe(
      `/api/groups/${GROUP}/reference-lists/${ENTRY}/versions/${VERSION}/approval`,
    );
    expect(init?.method).toBe("PUT");
    expect(init?.body).toBeUndefined();
    expect(read.entryId).toBe(ENTRY);
  });
});
