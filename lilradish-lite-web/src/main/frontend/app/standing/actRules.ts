import type { GroupPermission, SurfaceAct } from "../../api/standing";
import { say, type MessageId } from "../../i18n/app";
import type { GroupGate } from "../destinations";

/** A permission a group grants, spelt as one or refused by the compiler. */
type Granted<Permission extends GroupPermission> = Permission;

/**
 * A permission inside a group that a page there is reached by, or that a
 * control there asks: a rule a refusal has words for.
 */
export type GroupRule =
  | GroupGate
  | Granted<"change_membership">
  | Granted<"author_entry">
  | Granted<"approve_entry">
  | Granted<"revoke_entry">
  | Granted<"start_run">
  | Granted<"answer_step">
  | Granted<"review_at_gate">;

/** The rule a refused request met: an act across the estate, or a permission inside a group. */
export type Rule =
  { readonly act: SurfaceAct } | { readonly inGroup: GroupRule };

/** The rule a reader not holding the act has met, which names the rule and not them. */
export function ruleOf(act: SurfaceAct): string {
  return say(`reach.${act}` satisfies MessageId);
}

/** The same inside a group, by the permission the page is reached by or the control asks. */
export function ruleInGroup(permission: GroupRule): string {
  return say(`inGroup.${permission}` satisfies MessageId);
}

/** Either rule, in the words written for it. */
export function ruleSaid(rule: Rule): string {
  return "act" in rule ? ruleOf(rule.act) : ruleInGroup(rule.inGroup);
}
