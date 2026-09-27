import { put, remove } from "../../../../../lib/request/http";
import { atRun, runFrom, type Run } from "../{runId}";

/** Taking effect at once; stopped already, it stays as it was stopped. */
export function stopRun(
  groupId: string,
  runId: string,
  signal: AbortSignal,
): Promise<Run> {
  return atRun(groupId, runId, (run) => put(`${run}/stop`, signal, runFrom));
}

export function openRunAgain(
  groupId: string,
  runId: string,
  signal: AbortSignal,
): Promise<Run> {
  return atRun(groupId, runId, (run) => remove(`${run}/stop`, signal, runFrom));
}
