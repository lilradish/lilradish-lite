import type { RunAct } from "../../api/groups/{groupId}/runs/{runId}";
import type { StepAct } from "../../api/groups/{groupId}/runs/{runId}/steps";
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
