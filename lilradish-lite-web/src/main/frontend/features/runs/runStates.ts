import type { NumberedRun } from "../../api/runFacts";
import AutorenewIcon from "@mui/icons-material/Autorenew";
import CheckCircleIcon from "@mui/icons-material/CheckCircle";
import ErrorOutlinedIcon from "@mui/icons-material/ErrorOutlined";
import PauseCircleOutlinedIcon from "@mui/icons-material/PauseCircleOutlined";
import type SvgIcon from "@mui/material/SvgIcon";

import type { Run, RunState } from "../../api/groups/{groupId}/runs/{runId}";
import { say, type MessageId } from "../../i18n/app";
import { isolatedInText } from "../../lib/direction/isolated";
import { nameOrNumber } from "../../lib/text/names";
import type { StateToneName } from "../../lib/theme/tokens";
import { whenText } from "../../lib/time/When";
import { countSaid } from "./spend";

const STATES = {
  running: {
    words: "runState.running",
    tone: "running",
    icon: AutorenewIcon,
  },
  stopped: {
    words: "runState.stopped",
    tone: "stopped",
    icon: PauseCircleOutlinedIcon,
  },
  failed: {
    words: "runState.failed",
    tone: "failed",
    icon: ErrorOutlinedIcon,
  },
  done: {
    words: "runState.done",
    tone: "done",
    icon: CheckCircleIcon,
  },
} as const satisfies Record<
  RunState,
  {
    readonly words: MessageId;
    readonly tone: StateToneName;
    readonly icon: typeof SvgIcon;
  }
>;

export function runStateMark(state: string): (typeof STATES)[RunState] | null {
  return Object.hasOwn(STATES, state) ? STATES[state as RunState] : null;
}

/** A state this build has words for, or a plain saying that it has none; never the server's spelling. */
export function stateSaid(state: string): string {
  return stateWorded(state) ?? say("runState.unknown");
}

/** The words for a state this build has words for, and null for any other. */
export function stateWorded(state: string): string | null {
  const mark = runStateMark(state);
  return mark === null ? null : say(mark.words);
}

export function runWhereSaid(run: Run, now = new Date()): string {
  if (run.state !== "stopped") {
    return stateSaid(run.state);
  }
  const stopped = run.stopped;
  if (stopped === undefined) {
    return stateSaid(run.state);
  }
  const when = whenText(stopped.at, now);
  if ("by" in stopped) {
    return say("run.stoppedBy", {
      name: isolatedInText(nameOrNumber(stopped.by)),
      when,
    });
  }
  return stoppedByCeilingsSaid(
    run.runId,
    stopped.ceilings ?? [stopped.ceilingOf],
    when,
  );
}

/** The own and other ceilings that stopped this run, with each other run numbered once. */
export function stoppedByCeilingsSaid(
  runId: string,
  ceilings: readonly NumberedRun[],
  when: string,
): string {
  const own = ceilings.some((ceiling) => ceiling.runId === runId);
  const others = ceilings.filter((ceiling) => ceiling.runId !== runId);
  if (others.length === 0) {
    return say("run.stoppedByOwnCeiling", { when });
  }
  if (!own && others.length === 1) {
    return say("run.stoppedByCeilingOf", {
      number: countSaid(String(others[0]!.number)),
      when,
    });
  }
  const numbers = new Intl.ListFormat("en", {
    style: "long",
    type: "conjunction",
  }).format(others.map(({ number }) => countSaid(String(number))));
  return own
    ? say("run.stoppedByOwnAndOtherCeilings", {
        count: others.length,
        numbers,
        when,
      })
    : say("run.stoppedByOtherCeilings", { numbers, when });
}
