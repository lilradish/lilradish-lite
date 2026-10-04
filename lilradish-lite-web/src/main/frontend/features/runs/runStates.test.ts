import { describe, expect, it } from "vitest";

import type { Run } from "../../api/groups/{groupId}/runs/{runId}";
import { runWhereSaid, stoppedByCeilingsSaid } from "./runStates";

const RUN: Run = {
  runId: "current",
  number: 7,
  runsAbove: [],
  workflow: { entryId: "workflow", name: "Demo Sample", version: 1 },
  startedAt: "2026-09-24T08:00:00Z",
  state: "stopped",
  stopped: {
    at: "2026-09-24T08:15:00Z",
    by: { userId: "reader", displayName: "Avo" },
  },
  spend: { sent: "0", cameBack: "0", spent: "0", cameBackUnknown: false },
  ceiling: { raiseNeedsApproval: false },
  acts: new Set(),
  conversationEvents: [],
};

describe("runWhereSaid", () => {
  it("shows the current done or running state even when a historical stop remains", () => {
    expect(runWhereSaid({ ...RUN, state: "done" })).toBe("Done");
    expect(runWhereSaid({ ...RUN, state: "running" })).toBe("Running");
    expect(runWhereSaid(RUN, new Date("2026-09-24T09:00:00Z"))).toMatch(
      /^Stopped by/,
    );
  });

  it("names each child ceiling once alongside its own ceiling", () => {
    const words = stoppedByCeilingsSaid(
      RUN.runId,
      [
        { runId: RUN.runId, number: 7 },
        { runId: "child-a", number: 8 },
        { runId: "child-b", number: 9 },
      ],
      "at noon",
    );
    expect(words).toBe(
      "Stopped by its ceiling and the ceilings of runs 8 and 9, at noon",
    );
    expect(words).not.toContain("run 7");
  });
});
