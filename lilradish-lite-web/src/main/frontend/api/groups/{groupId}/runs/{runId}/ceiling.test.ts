import { describe, expect, it } from "vitest";

import { answering } from "../../../../../testutil/answering";
import { changeCeiling } from "./ceiling";

const GROUP = "00000003-0000-4000-8000-000000000c31";

const RUN = "00000008-0000-4000-8000-000000000c31";

const READ = {
  runId: RUN,
  number: 1,
  name: "Claim from Avo",
  workflow: {
    versionName: null,
    entryId: "00000006-0000-4000-8000-000000000c31",
    name: "Handle a claim",
    version: 1,
  },
  startedBy: { userId: "000c31" },
  runsAbove: [],
  startedAt: "2026-09-25T08:00:00Z",
  conversationEvents: [],
  state: "running",
  spend: { sent: "0", cameBack: "0", spent: "0", cameBackUnknown: false },
  ceiling: { inForce: "9007199254740991", raiseNeedsApproval: false },
  stateActs: ["stop", "change_ceiling"],
};

describe("changeCeiling", () => {
  it.each([
    ["a count, as the digits typed", "9007199254740991"],
    ["none, as null", null],
  ])(
    "asks PATCH of the run's ceiling with %s and nothing else, and reads the run it answers with",
    async (_case, ceiling) => {
      const sent = answering([JSON.stringify(READ), 200]);

      const read = await changeCeiling(
        GROUP,
        RUN,
        ceiling,
        new AbortController().signal,
      );

      const [address, init] = sent.mock.calls[0]!;
      expect(address).toBe(`/api/groups/${GROUP}/runs/${RUN}/ceiling`);
      expect(init?.method).toBe("PATCH");
      expect(String(init?.body)).toBe(JSON.stringify({ ceiling }));
      expect(read.ceiling).toEqual(READ.ceiling);
    },
  );
});

describe("changeCeiling below the root", () => {
  it("uses the root address and names the child only in the request body", async () => {
    const child = "00000008-0000-4000-8000-000000000c32";
    const sent = answering([JSON.stringify(READ), 200]);

    await changeCeiling(GROUP, RUN, null, new AbortController().signal, child);

    const [address, init] = sent.mock.calls[0]!;
    expect(address).toBe(`/api/groups/${GROUP}/runs/${RUN}/ceiling`);
    expect(init?.method).toBe("PATCH");
    expect(JSON.parse(String(init?.body))).toEqual({
      ceiling: null,
      runId: child,
    });
    expect(address).not.toContain(child);
  });
});
