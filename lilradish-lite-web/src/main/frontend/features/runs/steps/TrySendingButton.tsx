import Box from "@mui/material/Box";
import { useId, useLayoutEffect, useRef, useState } from "react";

import type { StepRow } from "../../../api/groups/{groupId}/runs/{runId}/steps";
import {
  readStep,
  type StepAnswer,
} from "../../../api/groups/{groupId}/runs/{runId}/steps/{stepId}";
import {
  refusedAsChanged,
  refusedAsMovedOn,
  trySending,
} from "../../../api/groups/{groupId}/runs/{runId}/steps/{stepId}/sending";
import { ProblemView } from "../../../app/ProblemView";
import { movesTheReader } from "../../../app/refusal";
import type { Rule } from "../../../app/standing/actRules";
import { useStandingRead } from "../../../app/standing/StandingContext";
import { say } from "../../../i18n/app";
import { ActButton } from "../../../lib/action/ActButton";
import { SPACED_ABOVE_SX } from "../../../lib/layout/parts";
import { Notice } from "../../../lib/notice/Notice";
import { STEP_ACT_RULES } from "../actRules";
import { stepOffers, useStepActValidity, useStepAction } from "./stepActs";
import { useRunReadAccess } from "../RunReadAccess";
import { movedOnNotice } from "./movedOn";

const SEND_RULE: Rule = { inGroup: STEP_ACT_RULES.try_sending };

/** Where the step is, since when, and whether it offers sending: a refusal is said only while all three hold. */
function heldAt(step: StepRow): string | null {
  const where = step.where;
  return where === undefined
    ? null
    : `${where.kind} ${where.since ?? ""} ${step.stateActs?.has("try_sending") === true}`;
}

export function TrySendingButton({
  groupId,
  runId,
  step,
  onShown,
}: {
  readonly groupId: string;
  readonly runId: string;
  readonly step: StepRow;
  readonly onShown: (answer: StepAnswer) => void;
}) {
  const noticeId = useId();
  const standingRead = useStandingRead();
  const reloadStanding = standingRead.reload;
  const standing = standingRead.value;
  const access = useRunReadAccess();
  const focusNext = useRef<string | null>(null);
  const [pressedAt, setPressedAt] = useState<string | null>(null);
  const [readAgainAt, setReadAgainAt] = useState<string | null>(null);
  const [answeredWith, setAnsweredWith] = useState<StepRow | null>(null);
  const at = heldAt(step);
  const validity = useStepActValidity(
    step,
    "try_sending",
    standing,
    groupId,
    runId,
  );
  const requestVersion = useRef<number | null>(null);
  const stale = at !== pressedAt && at !== readAgainAt;

  const rereading = useStepAction<StepAnswer>(
    validity,
    (answer) => {
      if (!validity.still(requestVersion.current)) return;
      focusNext.current = noticeId;
      setPressedAt(null);
      setReadAgainAt(heldAt(answer.step));
      onShown(answer);
    },
    () => {
      focusNext.current = noticeId;
    },
  );

  // Its answer reads the step as it stood before anything was sent, so the offer of sending in it is not a fresh one.
  const sending = useStepAction<StepAnswer>(
    validity,
    (answer) => {
      if (!validity.still(requestVersion.current)) return;
      setAnsweredWith(answer.step);
      onShown(answer);
    },
    (problem) => {
      if (!validity.still(requestVersion.current)) return;
      if (movesTheReader(problem)) {
        reloadStanding();
      }
      if (refusedAsChanged(problem)) {
        rereading.run((signal) =>
          access.read(signal, () =>
            readStep(groupId, runId, step.stepId, signal),
          ),
        );
      }
      if (!refusedAsMovedOn(problem)) {
        focusNext.current = noticeId;
      }
    },
  );

  // Only once an act of the reader's has asked for it: a read again later takes the keyboard nowhere.
  useLayoutEffect(() => {
    const target = focusNext.current;
    focusNext.current = null;
    if (target !== null) {
      document.getElementById(target)?.focus();
    }
  });

  const notice = stale
    ? null
    : movedOnNotice(
        sending.problem,
        refusedAsMovedOn,
        rereading.running,
        null,
        SEND_RULE,
      );
  const rereadRefusal = stale ? null : rereading.problem;
  const sent = step === answeredWith;

  return (
    <>
      {!stepOffers(step, "try_sending", standing, groupId) ? null : (
        <Box sx={SPACED_ABOVE_SX}>
          <ActButton
            tier="secondary"
            action={sending}
            waiting={rereading.running || sent}
            act={(signal) => {
              requestVersion.current = validity.begin();
              setPressedAt(at);
              return trySending(groupId, runId, step.stepId, signal);
            }}
          >
            {say("step.trySending")}
          </ActButton>
          {sending.running ? (
            <Notice severity="info">{say("step.trySendingOut")}</Notice>
          ) : null}
        </Box>
      )}
      {/* There before the words, so they are heard; the keyboard stays on the button, which stays drawn. */}
      <div role="status">
        {sent ? (
          <Notice severity="info">{say("step.trySendingSent")}</Notice>
        ) : null}
      </div>
      {notice === null && rereadRefusal === null ? null : (
        <Box id={noticeId} tabIndex={-1} sx={SPACED_ABOVE_SX}>
          {notice}
          {rereadRefusal === null ? null : (
            <ProblemView problem={rereadRefusal} />
          )}
        </Box>
      )}
    </>
  );
}
