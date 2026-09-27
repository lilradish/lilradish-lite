import { describe, expect, it } from "vitest";

import { answering, refusalOf } from "../../../../../../testutil/answering";
import { NOT_AN_ADDRESS } from "../../../../../problem";
import { decideRaise } from "./{changeId}";

const GROUP = "00000003-0000-4000-8000-000000000c41";

const RUN = "00000008-0000-4000-8000-000000000c41";

const RAISE = "0000000b-0000-4000-8000-000000000c41";

const READ = {
  runId: RUN,
  number: 1,
  name: "Claim from Ada",
  workflow: {
    entryId: "00000006-0000-4000-8000-000000000c41",
    name: "Handle a claim",
    version: 1,
  },
  startedBy: { userId: "000c41" },
  startedAt: "2026-09-25T08:00:00Z",
  state: "running",
  spend: { sent: "0", cameBack: "0", spent: "0", cameBackUnknown: false },
  ceiling: { inForce: "2000", raiseNeedsApproval: true },
  acts: [],
};

describe("decideRaise", () => {
  it.each(["approval", "refusal", "withdrawal"] as const)(
    "asks PUT of the raise's %s, sending nothing, and reads the run it answers with",
    async (decision) => {
      const sent = answering([JSON.stringify(READ), 200]);

      const read = await decideRaise(
        GROUP,
        RUN,
        RAISE,
        decision,
        new AbortController().signal,
      );

      const [address, init] = sent.mock.calls[0]!;
      expect(address).toBe(
        `/api/groups/${GROUP}/runs/${RUN}/ceiling-changes/${RAISE}/${decision}`,
      );
      expect(init?.method).toBe("PUT");
      expect(init?.body).toBeUndefined();
      expect(read.ceiling).toEqual(READ.ceiling);
    },
  );

  it("escapes the raise's identifier, and refuses one that names no address, asking nothing", async () => {
    const sent = answering([JSON.stringify(READ), 200]);

    await decideRaise(
      GROUP,
      RUN,
      "a/b",
      "approval",
      new AbortController().signal,
    );
    const failure = await refusalOf(
      decideRaise(GROUP, RUN, ".", "approval", new AbortController().signal),
    );

    expect(sent.mock.calls.map(([address]) => address)).toEqual([
      `/api/groups/${GROUP}/runs/${RUN}/ceiling-changes/a%2Fb/approval`,
    ]);
    expect(failure.problem).toEqual({ code: NOT_AN_ADDRESS });
  });
});
