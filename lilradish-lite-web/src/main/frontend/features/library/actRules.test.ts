import { describe, expect, it } from "vitest";

import type { EntryAct, VersionAct } from "../../api/groups/{groupId}/{kind}";
import { APP_EN } from "../../i18n/en";
import { ENTRY_ACT_RULES, VERSION_ACT_RULES } from "./actRules";

const EVERY_ENTRY_ACT = {
  start_draft: true,
  rename: true,
  stop: true,
  let_go: true,
} satisfies Record<EntryAct, true>;

const EVERY_VERSION_ACT = {
  write: true,
  submit: true,
  withdraw: true,
  approve: true,
  retire: true,
} satisfies Record<VersionAct, true>;

describe.each([
  ["an entry", ENTRY_ACT_RULES, EVERY_ENTRY_ACT],
  ["a version", VERSION_ACT_RULES, EVERY_VERSION_ACT],
] as const)("the rules of the acts on %s", (_case, rules, every) => {
  /** The table is JSON, which no compiler closes: an act left out is one whose refusal is said as nothing. */
  it("hold a rule for every act, and for no other", () => {
    expect(Object.keys(rules).toSorted()).toEqual(
      Object.keys(every).toSorted(),
    );
  });

  it("name only rules there are words for", () => {
    expect(
      Object.values(rules).filter(
        (rule) => !Object.hasOwn(APP_EN, `inGroup.${rule}`),
      ),
    ).toEqual([]);
  });
});
