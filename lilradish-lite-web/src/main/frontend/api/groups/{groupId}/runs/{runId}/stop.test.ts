import { describe, expect, it } from "vitest";

import { answering } from "../../../../../testutil/answering";
import { openRunAgain, stopRun } from "./stop";

const GROUP = "00000003-0000-4000-8000-000000000c21";

const RUN = "00000008-0000-4000-8000-000000000c21";

const READ = {
  runId: RUN,
  number: 1,
  name: "Claim from Ada",
  workflow: {
    entryId: "00000006-0000-4000-8000-000000000c21",
    name: "Handle a claim",
    version: 1,
  },
  startedBy: { userId: "000c21" },
  startedAt: "2026-09-25T08:00:00Z",
  state: "running",
  spend: { sent: "0", cameBack: "0", spent: "0", cameBackUnknown: false },
  ceiling: { raiseNeedsApproval: false },
  acts: ["stop"],
};

describe.each([
  ["stopRun", stopRun, "PUT"],
  ["openRunAgain", openRunAgain, "DELETE"],
] as const)("%s", (_name, change, method) => {
  it(`asks ${method} of the run's stop, sending nothing, and reads the run it answers with`, async () => {
    const sent = answering([JSON.stringify(READ), 200]);

    const read = await change(GROUP, RUN, new AbortController().signal);

    const [address, init] = sent.mock.calls[0]!;
    expect(address).toBe(`/api/groups/${GROUP}/runs/${RUN}/stop`);
    expect(init?.method).toBe(method);
    expect(init?.body).toBeUndefined();
    expect(read).toEqual({ ...READ, acts: new Set(["stop"]) });
  });
});
