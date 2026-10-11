import { say } from "../../i18n/app";
import { visible } from "../../i18n/visible";
import { isolatedInText } from "../../lib/direction/isolated";
import { StateNote } from "../../lib/text/StateNote";
import { StatePill } from "../../lib/text/StatePill";
import { runStateMark } from "./runStates";

export function RunStateBadge({
  state,
  at,
  atLabel,
}: {
  readonly state: string;
  readonly at?: string;
  readonly atLabel?: string;
}) {
  const mark = runStateMark(state);
  if (mark === null) {
    return say("runState.unknown");
  }
  const Icon = mark.icon;
  return (
    <>
      <StatePill
        tone={mark.tone}
        icon={<Icon fontSize="inherit" />}
        label={say(mark.words)}
      />
      {state === "running" && at !== undefined ? (
        <StateNote>
          {visible("runState.nowAt", {
            step: (
              <bdi>{isolatedInText(atLabel ?? at.replaceAll("_", " "))}</bdi>
            ),
          })}
        </StateNote>
      ) : null}
    </>
  );
}
