import type { StepRow } from "../../../api/groups/{groupId}/runs/{runId}/steps";

export function spokenName(name: string): string {
  return name.replaceAll("_", " ");
}

export function stepTitle(step: StepRow): string {
  return step.runs.name ?? spokenName(step.name);
}
