import { useLayoutEffect, useRef, useState } from "react";
import type { Problem } from "../../../api/problem";
import { useAction, type Action } from "../../../lib/request/useAction";

import type { StepAct } from "../../../api/stepActs";
import type { StateRefusalCode } from "../../../api/stepActs";
import type {
  Declared,
  StepRow,
} from "../../../api/groups/{groupId}/runs/{runId}/steps";
import type { GivesOtherwise } from "../../../api/stepActs";
import type { Standing } from "../../../api/standing";
import { STEP_ACT_RULES } from "../actRules";
import { say, type MessageId } from "../../../i18n/app";
import type { ProseRefusal } from "../../../lib/text/legibility";
import {
  declaredFor,
  labelAt,
  laterStepSaid,
  outputSaid,
  type Declaring,
} from "./declared";
import { worded } from "./stepStates";
import { stopsSaidWhere } from "./stepWhereabouts";

/** The actual production's public identity is the only identity used for self-review. */
function mayReviewProduction(step: StepRow, standing: Standing): boolean {
  if (standing.userId === undefined || standing.userId === "") {
    return false;
  }
  if (step.producedByKind === "person") {
    return (
      step.producedByUserId !== null &&
      step.producedByUserId !== "" &&
      step.producedByUserId !== standing.userId
    );
  }
  return (
    (step.producedByKind === "model" || step.producedByKind === "code") &&
    step.producedByUserId === null
  );
}

/** A person's own production is never offered to that person for review. */
export function stepOffers(
  step: StepRow,
  act: StepAct,
  standing: Standing,
  groupId: string,
): boolean {
  return (
    Object.hasOwn(STEP_ACT_RULES, act) &&
    step.stateActs?.has(act) === true &&
    standing.groups
      .find((group) => group.groupId === groupId)
      ?.permissions.has(STEP_ACT_RULES[act]) === true &&
    (act !== "review" || mayReviewProduction(step, standing))
  );
}

/** A reply belongs to the act's admission when it started, even if an intervening revocation is later reversed. */
export function useStepActValidity(
  step: StepRow,
  act: StepAct,
  standing: Standing,
  groupId: string,
  runId: string,
) {
  const permitted =
    standing.groups
      .find((group) => group.groupId === groupId)
      ?.permissions.has(STEP_ACT_RULES[act]) === true;
  const admissionKey = JSON.stringify([
    groupId,
    runId,
    step.stepId,
    act,
    standing.userId,
    permitted,
  ]);
  const readerKey = JSON.stringify([
    groupId,
    runId,
    step.stepId,
    standing.userId,
    permitted,
    step.producedByKind,
    step.producedByUserId,
  ]);
  const key = stepOffers(step, act, standing, groupId)
    ? JSON.stringify([
        groupId,
        runId,
        step.stepId,
        standing.userId,
        step.next?.number,
        step.where?.kind,
        step.where?.number,
        step.where?.since,
        step.producedByKind,
        step.producedByUserId,
      ])
    : null;
  const current = useRef({ key, version: 0 });
  useLayoutEffect(() => {
    if (current.current.key !== key) {
      current.current = { key, version: current.current.version + 1 };
    }
  }, [key]);
  const admission = useRef({ key: admissionKey, version: 0 });
  useLayoutEffect(() => {
    if (admission.current.key !== admissionKey) {
      admission.current = {
        key: admissionKey,
        version: admission.current.version + 1,
      };
    }
  }, [admissionKey]);
  return {
    begin: () => current.current.version,
    still: (version: number | null) =>
      key !== null && version === current.current.version,
    beginAdmission: () => admission.current.version,
    stillAdmitted: (version: number | null) =>
      permitted && version === admission.current.version,
    readerKey,
  };
}

/** An act and its follow-up read keep their own admission, including failures already saved by useAction. */
export function useStepAction<T>(
  validity: ReturnType<typeof useStepActValidity>,
  onSettled: (answer: T) => void,
  onRefused?: (problem: Problem) => void,
  completes?: (answer: T) => boolean,
): Action<T> & { readonly forgetProblem: () => void } {
  const started = useRef<number | null>(null);
  const admittedAt = useRef<number | null>(null);
  const [accepted, setAccepted] = useState<{
    readonly problem: Problem;
    readonly readerKey: string;
  } | null>(null);
  if (accepted !== null && accepted.readerKey !== validity.readerKey) {
    setAccepted(null);
  }
  const action = useAction<T>(
    (answer) => {
      // A quiet read may see this act's completion first; it never restores an admission the reader lost.
      if (
        validity.still(started.current) ||
        (validity.stillAdmitted(admittedAt.current) &&
          completes?.(answer) === true)
      ) {
        onSettled(answer);
      }
    },
    (problem) => {
      if (validity.still(started.current)) {
        setAccepted({ problem, readerKey: validity.readerKey });
        onRefused?.(problem);
      }
    },
  );
  return {
    forgetProblem: () => setAccepted(null),
    run: (act) => {
      started.current = validity.begin();
      admittedAt.current = validity.beginAdmission();
      setAccepted(null);
      action.run(act);
    },
    // A refusal already received can explain its own fresh resource read; changing reader or grants still forgets it.
    problem:
      accepted?.readerKey === validity.readerKey &&
      action.problem === accepted.problem
        ? action.problem
        : null,
    running: action.running,
  };
}

/** An act the reader's roles do not reach; whom it waits on instead is said where the step is. */
const NOT_PERMITTED = {
  answer: "step.mayNotAnswer",
  ask_again: "step.mayNotAskAgain",
  review: "step.mayNotReview",
  try_sending: "step.mayNotTrySending",
} as const satisfies Record<StepAct, MessageId>;

/** Every other refusal an act is withheld for, said as pressing it would be refused. */
const WITHHELD_FOR = {
  RUN_STOPPED: "refusal.RUN_STOPPED",
  ENTRY_STOPPED: "refusal.ENTRY_STOPPED",
  REVIEW_NOT_A_PERSONS: "refusal.REVIEW_NOT_A_PERSONS",
  ASK_AGAIN_NOT_OFFERED: "refusal.ASK_AGAIN_NOT_OFFERED",
  CODE_STEP_GIVES_OTHERWISE: "refusal.CODE_STEP_GIVES_OTHERWISE",
} as const satisfies Record<StateRefusalCode, MessageId>;

export const REASON_WORDS = {
  missing: "refusal.REASON_MISSING",
  crlf: "refusal.PROSE_LINE_BREAK_CRLF",
  direction_control: "refusal.PROSE_DIRECTION_CONTROL",
  tag: "refusal.PROSE_TAG_CHARACTER",
  unusable: "refusal.REASON_UNUSABLE",
} as const satisfies Record<ProseRefusal | "missing", MessageId>;

/** An absent readBy denotes an unavailable reference list, not a missing downstream consumer. */
export function givesOtherwiseSaid(
  refused: GivesOtherwise,
  declared: Declared | undefined,
  declaring: Declaring,
): string {
  const { readBy } = refused;
  const field = labelAt(declared?.gives, refused.field);
  if (readBy === undefined) {
    return say("step.erroredForNowGivesAListNotHere", { field });
  }
  if ("step" in readBy) {
    const step = laterStepSaid(readBy.step, declaring);
    return step === null
      ? say("step.unknown")
      : say("step.erroredForNowGivesOtherwise", { field, step });
  }
  return say("step.erroredForNowGivesOtherwiseOutput", {
    field,
    output: outputSaid(readBy.output, declaring),
  });
}

export function withheldSaid(
  step: StepRow,
  declaring: Declaring,
  standing: Standing,
  groupId: string,
): string[] {
  const saidWhere = stopsSaidWhere(step.where);
  const reasons = new Map(
    (step.stateRefusals ?? []).map(({ act, refusal }) => [act, refusal]),
  );
  const offered = new Set([...(step.stateActs ?? []), ...reasons.keys()]);
  const permissions = standing.groups.find(
    (group) => group.groupId === groupId,
  )?.permissions;
  const ordered = [
    ...Object.keys(STEP_ACT_RULES).filter((act) => offered.has(act)),
    ...[...offered].filter((act) => !Object.hasOwn(STEP_ACT_RULES, act)),
  ];
  const said = ordered.flatMap((act) => {
    const refusal = reasons.get(act);
    if (refusal === "STEP_MOVED_ON" || refusal === "TRY_SENDING_NOT_OFFERED") {
      return [];
    }
    if (!Object.hasOwn(STEP_ACT_RULES, act)) {
      return [say("step.unknown")];
    }
    const known = act as StepAct;
    const permits = permissions?.has(STEP_ACT_RULES[known]) === true;
    if (known === "review" && refusal === "REVIEW_NOT_A_PERSONS" && !permits) {
      return [];
    }
    if (!permits) {
      return [worded(NOT_PERMITTED, act)];
    }
    if (refusal === undefined) {
      return known === "review" &&
        step.producedByKind === "person" &&
        standing.userId !== undefined &&
        standing.userId !== "" &&
        step.producedByUserId === standing.userId
        ? [say("step.reviewsOwn")]
        : [];
    }
    if (saidWhere.includes(refusal)) {
      return [];
    }
    const which =
      refusal === "CODE_STEP_GIVES_OTHERWISE" &&
      step.givesOtherwise !== undefined
        ? givesOtherwiseSaid(
            step.givesOtherwise,
            declaredFor(step, declaring.declarations),
            declaring,
          )
        : null;
    return which === null
      ? [worded(WITHHELD_FOR, refusal)]
      : [worded(WITHHELD_FOR, refusal), which];
  });
  return [...new Set(said)];
}
