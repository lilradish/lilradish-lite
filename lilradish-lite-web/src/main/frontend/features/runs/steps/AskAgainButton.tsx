import Box from "@mui/material/Box";
import Typography from "@mui/material/Typography";
import { useEffect, useId, useLayoutEffect, useRef, useState } from "react";

import type { StepRow } from "../../../api/groups/{groupId}/runs/{runId}/steps";
import {
  readStep,
  type StepAnswer,
} from "../../../api/groups/{groupId}/runs/{runId}/steps/{stepId}";
import {
  askAgain,
  refusedAsChanged,
  refusedAsMovedOn,
} from "../../../api/groups/{groupId}/runs/{runId}/steps/{stepId}/tries/{number}";
import { ProblemView } from "../../../app/ProblemView";
import { movesTheReader } from "../../../app/refusal";
import type { Rule } from "../../../app/standing/actRules";
import { useStandingRead } from "../../../app/standing/StandingContext";
import { say } from "../../../i18n/app";
import { ActButton } from "../../../lib/action/ActButton";
import { SPACED_ABOVE_SX } from "../../../lib/layout/parts";
import { STEP_ACT_RULES } from "../actRules";
import { stepOffers, useStepActValidity, useStepAction } from "./stepActs";
import { useRunReadAccess } from "../RunReadAccess";
import { actedFirstSaid, movedOnNotice, staleFor } from "./movedOn";

const ASK_RULE: Rule = { inGroup: STEP_ACT_RULES.ask_again };

export function AskAgainButton({
  groupId,
  runId,
  step,
  held,
  onRunning,
  landsOn,
  onShown,
}: {
  readonly groupId: string;
  readonly runId: string;
  readonly step: StepRow;
  /** Whether an answer to the step is out, which the ask waits on. */
  readonly held: boolean;
  readonly onRunning: (running: boolean) => void;
  /** The element the keyboard goes to once the ask's answer is shown, as the button may go with it. */
  readonly landsOn: string;
  readonly onShown: (answer: StepAnswer) => void;
}) {
  const noticeId = useId();
  const standingRead = useStandingRead();
  const reloadStanding = standingRead.reload;
  const standing = standingRead.value;
  const access = useRunReadAccess();
  const focusNext = useRef<string | null>(null);
  const [sentFor, setSentFor] = useState<number | null>(null);
  const [readAgainAt, setReadAgainAt] = useState<number | null>(null);
  const [firstSaid, setFirstSaid] = useState<string | null>(null);
  const next = step.next;
  const validity = useStepActValidity(
    step,
    "ask_again",
    standing,
    groupId,
    runId,
  );
  const requestVersion = useRef<number | null>(null);
  const stale = staleFor(step, sentFor, readAgainAt);

  const rereading = useStepAction<StepAnswer>(
    validity,
    (answer) => {
      if (!validity.still(requestVersion.current)) return;
      focusNext.current = noticeId;
      setReadAgainAt(answer.step.next?.number ?? null);
      setFirstSaid(actedFirstSaid(answer, sentFor));
      onShown(answer);
    },
    () => {
      focusNext.current = noticeId;
    },
  );

  const asking = useStepAction<StepAnswer>(
    validity,
    (answer) => {
      if (!validity.still(requestVersion.current)) return;
      focusNext.current = landsOn;
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

  const running = asking.running || rereading.running;
  useEffect(() => {
    onRunning(running);
  }, [onRunning, running]);

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
        asking.problem,
        refusedAsMovedOn,
        rereading.running,
        firstSaid,
        ASK_RULE,
      );
  const rereadRefusal = stale ? null : rereading.problem;

  return (
    <>
      {!stepOffers(step, "ask_again", standing, groupId) ||
      next === undefined ? null : (
        <Box sx={SPACED_ABOVE_SX}>
          <ActButton
            tier="secondary"
            action={asking}
            waiting={rereading.running || held}
            act={(signal) => {
              requestVersion.current = validity.begin();
              setSentFor(next.number);
              setFirstSaid(null);
              return askAgain(groupId, runId, step.stepId, next.number, signal);
            }}
          >
            {say("step.askAgain")}
          </ActButton>
          {/* Said once beside both: Answer it here says it wherever it is drawn. */}
          {!next.beyond ||
          step.tries === undefined ||
          stepOffers(step, "answer", standing, groupId) ? null : (
            <Typography variant="body2">
              {say("step.nextBeyond", {
                number: next.number,
                allowed: step.tries.declared,
              })}
            </Typography>
          )}
        </Box>
      )}
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
