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
import { useAction } from "../../../lib/request/useAction";
import { STEP_ACT_RULES } from "../actRules";
import { actedFirstSaid, movedOnNotice, staleFor } from "./movedOn";

const PART_SX = { mt: 2 };

const ASK_RULE: Rule = { inGroup: STEP_ACT_RULES.ask_again };

/**
 * Ask again, drawn only where the step offers it the reader: the try it owes, asked of whoever it names. The step
 * the ask is answered with is handed to `onShown`; one refused as changed since it was read is read again, and that
 * is handed on too.
 */
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
  const reloadStanding = useStandingRead().reload;
  const focusNext = useRef<string | null>(null);
  const [sentFor, setSentFor] = useState<number | null>(null);
  const [readAgainAt, setReadAgainAt] = useState<number | null>(null);
  const [firstSaid, setFirstSaid] = useState<string | null>(null);
  const next = step.next;
  const stale = staleFor(step, sentFor, readAgainAt);

  const rereading = useAction<StepAnswer>(
    (answer) => {
      focusNext.current = noticeId;
      setReadAgainAt(answer.step.next?.number ?? null);
      setFirstSaid(actedFirstSaid(answer, sentFor));
      onShown(answer);
    },
    () => {
      focusNext.current = noticeId;
    },
  );

  const asking = useAction<StepAnswer>(
    (answer) => {
      focusNext.current = landsOn;
      onShown(answer);
    },
    (problem) => {
      if (movesTheReader(problem)) {
        reloadStanding();
      }
      if (refusedAsChanged(problem)) {
        rereading.run((signal) =>
          readStep(groupId, runId, step.stepId, signal),
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
      {!step.acts.includes("ask_again") || next === undefined ? null : (
        <Box sx={PART_SX}>
          <ActButton
            action={asking}
            waiting={rereading.running || held}
            act={(signal) => {
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
          step.acts.includes("answer") ? null : (
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
        <Box id={noticeId} tabIndex={-1} sx={PART_SX}>
          {notice}
          {rereadRefusal === null ? null : (
            <ProblemView problem={rereadRefusal} />
          )}
        </Box>
      )}
    </>
  );
}
