import Box from "@mui/material/Box";
import { useEffect, useState } from "react";
import { useLocation, useNavigate } from "react-router";

import type { StepRow } from "../../../api/groups/{groupId}/runs/{runId}/steps";
import type { StepAnswer } from "../../../api/groups/{groupId}/runs/{runId}/steps/{stepId}";
import type { GroupStanding } from "../../../api/standing";
import { say } from "../../../i18n/app";
import { stepAnchor } from "../runAddress";
import { ActMessage } from "./ActMessage";
import { RunEndMessage } from "./RunEndMessage";
import { StartedWithMessage } from "./StartedWithMessage";
import { StepMessage } from "./StepMessage";
import type { RunReading } from "./useRunRead";

// Its markers are taken off, and some engines stop reading a list without them out as a list.
const LIST_SX = { listStyle: "none", m: 0, p: 0 };

/**
 * Where the address names a drawn step's message, the keyboard starts there once, as the page arrives. The address
 * then forgets it, so a conversation drawn anew at the same address starts the keyboard nowhere.
 */
export function RunConversation({
  group,
  reading,
  onShown,
}: {
  readonly group: GroupStanding;
  readonly reading: RunReading;
  readonly onShown: (answer: StepAnswer) => void;
}) {
  const { run, steps } = reading;
  const navigate = useNavigate();
  const [arrival] = useState(useLocation());
  const [firstRead] = useState(steps);
  const reached = reachedOf(steps.steps);
  const byStep = new Map(reached.map((step) => [step.stepId, step]));
  const events = run.conversationEvents;
  const handTries = new Map<string, Set<number>>();
  for (const event of events) {
    if (event.kind === "answered_by_hand") {
      const numbers = handTries.get(event.stepId) ?? new Set<number>();
      numbers.add(event.number);
      handTries.set(event.stepId, numbers);
    }
  }

  useEffect(() => {
    if (arrival.hash === "") {
      return;
    }
    const aimed = messageNamed(firstRead.steps, arrival.hash);
    if (aimed !== undefined) {
      document.getElementById(stepAnchor(aimed.stepId))?.focus();
    }
    const { pathname, search, state } = arrival;
    void navigate({ pathname, search }, { replace: true, state });
  }, [arrival, firstRead, navigate]);

  return (
    <>
      <Box
        component="ol"
        role="list"
        aria-label={say("step.theConversation")}
        sx={LIST_SX}
      >
        <StartedWithMessage
          group={group}
          run={run}
          takes={steps.declarations.get(steps.run.versionId)?.takes}
          declarations={steps.declarations}
          versionId={steps.run.versionId}
        />
        {events.map((event) => {
          if (event.kind === "step_reached") {
            const step = byStep.get(event.stepId);
            return step === undefined ? null : (
              <StepMessage
                key={event.id}
                group={group}
                reading={steps}
                step={step}
                handTries={handTries.get(step.stepId)}
                onShown={onShown}
              />
            );
          }
          return (
            <ActMessage
              key={event.id}
              event={event}
              run={run}
              reading={steps}
              step={"stepId" in event ? byStep.get(event.stepId) : undefined}
              group={group}
            />
          );
        })}
        <RunEndMessage run={run} reading={steps} />
      </Box>
    </>
  );
}

/** Only reached steps have conversation messages. */
export function messageNamed(
  steps: readonly StepRow[],
  hash: string,
): StepRow | undefined {
  return reachedOf(steps).find(
    (step) => hash === `#${stepAnchor(step.stepId)}`,
  );
}

function reachedOf(steps: readonly StepRow[]): StepRow[] {
  return steps.filter((step) => step.state !== "not_started");
}
