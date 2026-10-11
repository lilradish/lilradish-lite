import type { StepAct } from "../../api/stepActs";
import type { Run, RunAct } from "../../api/groups/{groupId}/runs/{runId}";
import type { Standing } from "../../api/standing";

import type { GroupRule } from "../../app/standing/actRules";
import PERMISSIONS from "./actPermissions.json";
import STEP_PERMISSIONS from "./steps/stepActPermissions.json";

/**
 * The permission each act on a run takes, which is the rule a refusal of it is
 * said as; read from the one table a spec holds level with the server's acts.
 */
export const RUN_ACT_RULES = PERMISSIONS satisfies Record<
  RunAct,
  string
> as Record<RunAct, GroupRule>;

/** The same for each act on a step, from a table of its own the same spec holds level. */
export const STEP_ACT_RULES = STEP_PERMISSIONS satisfies Record<
  StepAct,
  string
> as Record<StepAct, GroupRule>;

/** Permission is intersected with state facts, and raise acts also with the public requester identity. */
export function runOffers(
  run: Run,
  act: RunAct,
  standing: Standing,
  groupId: string,
): boolean {
  if (
    !Object.hasOwn(RUN_ACT_RULES, act) ||
    run.stateActs?.has(act) !== true ||
    standing.groups
      .find((group) => group.groupId === groupId)
      ?.permissions.has(RUN_ACT_RULES[act]) !== true
  ) {
    return false;
  }
  if (
    act !== "approve_raise" &&
    act !== "refuse_raise" &&
    act !== "withdraw_raise"
  ) {
    return true;
  }
  const waiting = "heldBy" in run.ceiling ? undefined : run.ceiling.waiting;
  if (standing.userId === undefined || waiting === undefined) {
    return false;
  }
  const asked = waiting.askedBy.userId === standing.userId;
  return act === "withdraw_raise" ? asked : !asked;
}
