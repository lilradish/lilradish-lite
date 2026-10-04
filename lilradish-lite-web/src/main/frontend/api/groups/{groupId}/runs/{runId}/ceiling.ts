import { patch } from "../../../../../lib/request/http";
import { atRun, runFrom, type Run } from "../{runId}";

/**
 * The ceiling asked for, in digits, or null for none. Whether it holds at once
 * or waits on approval is the server's to judge, against the ceiling in force.
 */
export function changeCeiling(
  groupId: string,
  runId: string,
  ceiling: string | null,
  signal: AbortSignal,
  targetRunId?: string,
): Promise<Run> {
  return atRun(groupId, runId, (run) =>
    patch(
      `${run}/ceiling`,
      { ceiling, ...(targetRunId === undefined ? {} : { runId: targetRunId }) },
      signal,
      runFrom,
    ),
  );
}
