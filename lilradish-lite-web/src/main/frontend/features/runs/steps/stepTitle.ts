import type { StepRow } from "../../../api/groups/{groupId}/runs/{runId}/steps";

/** A name a workflow gives a step, read as a person reads it. */
export function spokenName(name: string): string {
  return name.replaceAll("_", " ");
}

/** What the step runs where that has a name for people; its own name where code or a route runs. */
export function stepTitle(step: StepRow): string {
  return step.runs.name ?? spokenName(step.name);
}
