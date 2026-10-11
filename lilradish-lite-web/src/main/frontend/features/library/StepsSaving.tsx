import Box from "@mui/material/Box";

import type { WorkflowVersion } from "../../api/groups/{groupId}/{kind}/{entryId}/versions/{versionId}";
import {
  contentProblemsIn,
  contentProblemTotalIn,
} from "../../api/groups/{groupId}/{kind}/{entryId}/versions";
import type { Problem } from "../../api/problem";
import { ProblemView } from "../../app/ProblemView";
import { asksToSendAgain } from "../../app/refusal";
import { say } from "../../i18n/app";
import { visible } from "../../i18n/visible";
import type { Reason } from "../../lib/action/Press";
import { SaveButton } from "../../lib/action/SaveButton";
import { isolatedInText } from "../../lib/direction/isolated";
import { Notice } from "../../lib/notice/Notice";
import type { Action } from "../../lib/request/useAction";
import { VERSION_ACT_RULES } from "./actRules";
import { WRITTEN_LIMITS } from "./constantDrafts";
import { ContentProblems, contentOf } from "./ContentProblems";
import { calledOf, calledVisibleOf } from "./StepRow";
import type { FlowDraft } from "./workflowDrafts";
import type { Sent } from "./workflowUnsaved";

const HELD_WHY_SX = { typography: "systemHint" };

/** Why the steps cannot be sent as they are, at the step that holds them where it is one step's. */
export function heldReason(
  held: NonNullable<Sent["held"]>,
  flow: FlowDraft,
): Reason {
  const why = say(held.why, {
    ...WRITTEN_LIMITS,
    ...(held.name === undefined ? {} : { name: isolatedInText(held.name) }),
  });
  const at = flow.steps.findIndex((step) => step.key === held.step);
  const step = flow.steps[at];
  if (step === undefined) return { severity: "warning", words: why };
  return {
    severity: "warning",
    words: say("workflow.heldAt", { step: calledOf(step, at), why }),
    visible: visible("workflow.heldAt", {
      step: <>{calledVisibleOf(step, at, "inherit")}</>,
      why: (
        <Box component="span" sx={HELD_WHY_SX}>
          {why}
        </Box>
      ),
    }),
  };
}

/** The one save of the steps and what fills what the workflow gives back, drawn on the page and in the step's drawer. */
export function StepsSave({
  unsaved,
  action,
  waiting,
  flow,
  held,
  act,
}: {
  readonly unsaved: boolean;
  readonly action: Action<WorkflowVersion>;
  readonly waiting: boolean;
  readonly flow: FlowDraft;
  readonly held: Sent["held"];
  readonly act: (signal: AbortSignal) => Promise<WorkflowVersion>;
}) {
  return (
    <SaveButton
      unsaved={unsaved}
      action={action}
      waiting={waiting}
      reason={held === null ? undefined : heldReason(held, flow)}
      label={say("workflow.saveSteps")}
      act={act}
    >
      {say("entry.save")}
    </SaveButton>
  );
}

/** What saving the steps is doing, or what refused it; once nothing is left to save, never a way on to sending it. */
export function StepsSaid({
  running,
  refusal,
  settled,
  dropped,
  workflow,
}: {
  readonly running: boolean;
  readonly refusal: Problem | null;
  readonly settled: boolean;
  /** A field change not confirmed was let go of with the step it was in. */
  readonly dropped: boolean;
  readonly workflow: WorkflowVersion | null;
}) {
  return (
    <>
      {running ? (
        <Notice severity="info">{say("workflow.underway")}</Notice>
      ) : null}
      {dropped ? (
        <Notice severity="warning">{say("workflow.unconfirmedDropped")}</Notice>
      ) : null}
      {refusal === null ? null : (
        <>
          {settled && asksToSendAgain(refusal) ? null : (
            <ProblemView
              problem={refusal}
              rule={{ inGroup: VERSION_ACT_RULES.write }}
            />
          )}
          <ContentProblems
            problems={contentProblemsIn(refusal)}
            problemTotal={contentProblemTotalIn(refusal)}
            content={workflow === null ? null : contentOf(workflow)}
          />
        </>
      )}
    </>
  );
}
