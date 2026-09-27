import Box from "@mui/material/Box";
import { useEffect, useState } from "react";
import { useLocation, useNavigate } from "react-router";

import type { StepRow } from "../../../api/groups/{groupId}/runs/{runId}/steps";
import type { StepAnswer } from "../../../api/groups/{groupId}/runs/{runId}/steps/{stepId}";
import { say } from "../../../i18n/app";
import { stepAnchor } from "../runAddress";
import { RunEndMessage } from "./RunEndMessage";
import { StartedWithMessage } from "./StartedWithMessage";
import { StepMessage } from "./StepMessage";
import type { RunReading } from "./useRunRead";
import { WhatComesNext } from "./WhatComesNext";

// Its markers are taken off, and some engines stop reading a list without them out as a list.
const LIST_SX = { listStyle: "none", m: 0, p: 0 };

/**
 * Where the address names a drawn step's message, the keyboard starts there once, as the page arrives. The address
 * then forgets it, so a conversation drawn anew at the same address starts the keyboard nowhere.
 */
export function RunConversation({
  groupId,
  reading,
  onShown,
}: {
  readonly groupId: string;
  readonly reading: RunReading;
  /** A step as an act on it answered it, or as it was read again once one was refused. */
  readonly onShown: (answer: StepAnswer) => void;
}) {
  const { run, steps } = reading;
  const navigate = useNavigate();
  const [arrival] = useState(useLocation());
  const [firstRead] = useState(steps);

  useEffect(() => {
    if (arrival.hash === "") {
      return;
    }
    const aimed = reachedOf(firstRead.steps).find(
      (step) => arrival.hash === `#${stepAnchor(step.stepId)}`,
    );
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
          groupId={groupId}
          run={run}
          takes={steps.declarations.get(steps.run.versionId)?.takes}
        />
        {reachedOf(steps.steps).map((step) => (
          <StepMessage
            key={step.stepId}
            groupId={groupId}
            reading={steps}
            step={step}
            onShown={onShown}
          />
        ))}
        <RunEndMessage run={run} reading={steps} />
      </Box>
      <WhatComesNext steps={steps.steps} />
    </>
  );
}

function reachedOf(steps: readonly StepRow[]): StepRow[] {
  return steps.filter((step) => step.state !== "not_started");
}
