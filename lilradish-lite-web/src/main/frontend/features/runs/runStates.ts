import type { Run, RunState } from "../../api/groups/{groupId}/runs/{runId}";
import { say, type MessageId } from "../../i18n/app";
import { isolatedInText } from "../../lib/direction/isolated";
import { nameOrNumber } from "../../lib/text/names";
import { whenText } from "../../lib/time/When";

/** The words each state is said in, wherever a run is listed or read, closed over the states the server works out. */
const STATES = {
  running: "runState.running",
  stopped: "runState.stopped",
  failed: "runState.failed",
  done: "runState.done",
} as const satisfies Record<RunState, MessageId>;

/** A state this build has words for, or a plain saying that it has none; never the server's spelling. */
export function stateSaid(state: string): string {
  return stateWorded(state) ?? say("runState.unknown");
}

/** The words for a state this build has words for, and null for any other. */
export function stateWorded(state: string): string | null {
  return Object.hasOwn(STATES, state) ? say(STATES[state as RunState]) : null;
}

/** Stopped by somebody, or by a ceiling reached, which names nobody; else the state the server works out. */
export function runWhereSaid(run: Run): string {
  const stopped = run.stopped;
  if (stopped === undefined) {
    return stateSaid(run.state);
  }
  const when = whenText(stopped.at);
  if ("by" in stopped) {
    return say("run.stoppedBy", {
      name: isolatedInText(nameOrNumber(stopped.by)),
      when,
    });
  }
  return stopped.ceilingOf.runId === run.runId
    ? say("run.stoppedByOwnCeiling", { when })
    : say("run.stoppedByCeilingOf", {
        number: stopped.ceilingOf.number,
        when,
      });
}
