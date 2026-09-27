import { describe, expect, it } from "vitest";

import type { GroupPermission, SurfaceAct } from "../../api/standing";
import { ESTATE_ROLES, roleSaid } from "../../features/people/estateRoles";
import { say } from "../../i18n/app";
import type { APP_EN } from "../../i18n/en";
import { ruleInGroup, ruleOf, ruleSaid, type GroupRule } from "./actRules";

/**
 * The sentences are the rule itself: each names what the act is for, in the
 * reader's words. Keyed by act, so an act added to the vocabulary fails the
 * build here until its rule is written down.
 */
const RULE_OF_EACH_ACT = {
  keep_pool: "The pool is seen and changed only by a role that may keep it.",
  grant_estate_role:
    "Estate roles are granted and withdrawn only by a role that may grant them.",
  keep_group_register:
    "The register of groups is seen and changed only by a role that may keep it.",
  check_soundness:
    "Soundness is read and checked only by a role that may check it.",
  read_measurements:
    "Measurements is read only by a role that may read what the estate measures.",
} satisfies Record<SurfaceAct, string>;

/**
 * Keyed by every permission a group's page is reached by or a control there
 * asks, for the same reason.
 */
const RULE_OF_EACH_GROUP_RULE = {
  read_membership:
    "A group's members are seen only by a role in it that may see them.",
  change_membership:
    "A group's membership is changed only by a role in it that may change it.",
  author_entry:
    "A group's entries are started, written and submitted only by a role in it that may write one.",
  approve_entry:
    "A group's entries are put into service, and a run's raised ceiling approved or refused, only by a role in it that may approve an entry.",
  revoke_entry:
    "A group's entries are retired, stopped and let go only by a role in it that may revoke one.",
  start_run:
    "A group's runs are started, renamed, stopped, opened again and given a ceiling, and a raise asked withdrawn, only by a role in it that may start one.",
  answer_step:
    "A group's steps are answered, and asked for again, only by a role in it that may answer one.",
  review_at_gate:
    "A group's steps are reviewed, what they give back assured or refused, only by a role in it that may review them.",
} satisfies Record<GroupRule, string>;

const RULES = Object.entries(RULE_OF_EACH_ACT) as [SurfaceAct, string][];

const GROUP_RULES = Object.entries(RULE_OF_EACH_GROUP_RULE) as [
  GroupRule,
  string,
][];

/** Every rule the catalogue words is one of an act: a rule for no act is one nothing can reach. */
type RuleId = Extract<keyof typeof APP_EN, `reach.${string}`>;
const EVERY_RULE_NAMES_AN_ACT: RuleId extends `reach.${SurfaceAct}`
  ? true
  : false = true;

/** And every rule inside a group is one of a permission some page or control there asks. */
type GroupRuleId = Extract<keyof typeof APP_EN, `inGroup.${string}`>;
const EVERY_GROUP_RULE_NAMES_A_GATE: GroupRuleId extends `inGroup.${GroupRule}`
  ? true
  : false = true;

/** And every rule inside a group is a permission a group grants, never a word of this side's own. */
const EVERY_GROUP_RULE_IS_A_PERMISSION: GroupRule extends GroupPermission
  ? true
  : false = true;

/** No rule may name an act by how it is spelt, or a role by any name. */
const NOT_THE_READERS_WORDS = [
  ...RULES.map(([act]) => act),
  ...GROUP_RULES.map(([permission]) => permission),
  ...ESTATE_ROLES,
  ...ESTATE_ROLES.map(roleSaid),
  "operator",
  "overseer",
  "owner",
];

function saysNothingItShouldNot(said: string): string[] {
  const lower = said.toLowerCase();
  return /\byou(r)?\b/.test(lower)
    ? ["you"]
    : NOT_THE_READERS_WORDS.filter((word) =>
        lower.includes(word.toLowerCase()),
      );
}

describe("ruleOf", () => {
  it.each(RULES)("says the rule behind %s", (act, rule) => {
    expect(ruleOf(act)).toBe(rule);
  });

  it("words a rule for every act and for nothing else", () => {
    expect(EVERY_RULE_NAMES_AN_ACT).toBe(true);
  });

  it.each(RULES)(
    "says nothing of the reader, of any act's spelling, or of any role in the rule behind %s",
    (act) => {
      expect(saysNothingItShouldNot(ruleOf(act))).toEqual([]);
    },
  );
});

describe("ruleInGroup", () => {
  it.each(GROUP_RULES)("says the rule behind %s", (permission, rule) => {
    expect(ruleInGroup(permission)).toBe(rule);
  });

  it("words a rule for every permission a group's page is reached by or a control there asks, and for nothing else", () => {
    expect(EVERY_GROUP_RULE_NAMES_A_GATE).toBe(true);
    expect(EVERY_GROUP_RULE_IS_A_PERMISSION).toBe(true);
  });

  it.each(GROUP_RULES)(
    "says nothing of the reader, of any spelling, or of any role in the rule behind %s",
    (permission) => {
      expect(saysNothingItShouldNot(ruleInGroup(permission))).toEqual([]);
    },
  );
});

describe("ruleSaid", () => {
  it.each(RULES)(
    "says an act's rule as ruleOf says it, for %s",
    (act, rule) => {
      expect(ruleSaid({ act })).toBe(rule);
    },
  );

  it.each(GROUP_RULES)(
    "says a group's rule as ruleInGroup says it, for %s",
    (inGroup, rule) => {
      expect(ruleSaid({ inGroup })).toBe(rule);
    },
  );
});

describe("the rule of My work", () => {
  it("says nothing of the reader, of any spelling, or of any role", () => {
    const rule = say("myWork.rule");

    expect(rule).toBe("My work is open only to somebody in a group.");
    expect(saysNothingItShouldNot(rule)).toEqual([]);
  });
});
