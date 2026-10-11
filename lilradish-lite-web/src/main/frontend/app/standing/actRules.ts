import type { GroupPermission, SurfaceAct } from "../../api/standing";
import { say, type MessageId } from "../../i18n/app";
import type { GroupGate } from "../destinations";

type CheckedPermission<Permission extends GroupPermission> = Permission;

/**
 * Gates a group page or control; each permission has words for a refusal.
 */
export type GroupRule =
  | GroupGate
  | CheckedPermission<"change_membership">
  | CheckedPermission<"author_entry">
  | CheckedPermission<"approve_entry">
  | CheckedPermission<"revoke_entry">
  | CheckedPermission<"start_run">
  | CheckedPermission<"answer_step">
  | CheckedPermission<"review_at_gate">;

export type Rule =
  { readonly act: SurfaceAct } | { readonly inGroup: GroupRule };

/** The rule a reader not holding the act has met, which names the rule and not them. */
export function ruleOf(act: SurfaceAct): string {
  return say(`reach.${act}` satisfies MessageId);
}

export function ruleInGroup(permission: GroupRule): string {
  return say(`inGroup.${permission}` satisfies MessageId);
}

export function ruleSaid(rule: Rule): string {
  return "act" in rule ? ruleOf(rule.act) : ruleInGroup(rule.inGroup);
}
