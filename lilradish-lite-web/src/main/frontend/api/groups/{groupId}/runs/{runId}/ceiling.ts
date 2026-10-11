import { patch } from "../../../../../lib/request/http";
import { atRun, runFrom, type Run } from "../{runId}";

/**
 * Digits, or null for no ceiling. The server compares with the ceiling in force
 * to decide immediate effect or approval.
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
