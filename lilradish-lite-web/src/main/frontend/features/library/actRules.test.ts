import { describe, expect, it } from "vitest";

import type {
  Entry,
  EntryAct,
  Version,
  VersionAct,
} from "../../api/groups/{groupId}/{kind}";
import type { GroupPermission, Standing } from "../../api/standing";
import { APP_EN } from "../../i18n/en";
import { inGroup } from "../../testutil/standingRead";
import {
  ENTRY_ACT_RULES,
  VERSION_ACT_RULES,
  entryOffers,
  versionOffers,
} from "./actRules";

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

const EVERY_PERMISSION = {
  read_membership: true,
  change_membership: true,
  start_run: true,
  read_own_runs: true,
  read_all_runs: true,
  read_inference_content: true,
  answer_step: true,
  review_at_gate: true,
  author_entry: true,
  approve_entry: true,
  revoke_entry: true,
} satisfies Record<GroupPermission, true>;

const VERSION_STATE_ACTS = {
  draft: ["write", "submit"],
  submitted: ["withdraw", "approve"],
  in_service: ["retire"],
  retired: [],
} satisfies Record<Version["standing"], readonly VersionAct[]>;

describe.each([
  ["an entry", ENTRY_ACT_RULES, EVERY_ENTRY_ACT],
  ["a version", VERSION_ACT_RULES, EVERY_VERSION_ACT],
] as const)("the rules of the acts on %s", (_case, rules, every) => {
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

const VERSION: Version = {
  versionName: null,
  versionId: "imaginary-submitted-version",
  number: 3,
  standing: "submitted",
  writers: [{ userId: "writer", displayName: "Same name" }],
  writtenByMigration: false,
  pinnedBy: [],
  stateActs: new Set(["withdraw", "approve"]),
};

const ENTRY: Entry = {
  entryId: "imaginary-question",
  kind: "question",
  name: "Sort an imaginary delivery",
  stateActs: new Set(["rename", "stop"]),
  versions: [VERSION],
};

function standing(
  permissions: readonly string[],
  userId?: string,
  groupId = "group",
): Standing {
  return {
    acts: new Set(),
    groups: [inGroup(groupId, "CLAIMS", "Claims", permissions)],
    ...(userId === undefined ? {} : { userId }),
  };
}

describe("entry and version controls from the same resource facts", () => {
  it.each([
    [
      ["author_entry"],
      ["start_draft", "rename", "stop"],
      { start_draft: true, rename: true, stop: false, let_go: false },
    ],
    [
      ["revoke_entry"],
      ["start_draft", "rename", "let_go"],
      { start_draft: false, rename: false, stop: false, let_go: true },
    ],
    [
      [],
      ["start_draft", "rename", "stop"],
      { start_draft: false, rename: false, stop: false, let_go: false },
    ],
  ] as const)(
    "offers exactly the entry acts allowed by grants %j and state facts %j",
    (permissions, stateActs, expected) => {
      const entry = { ...ENTRY, stateActs: new Set(stateActs) };
      const reader = standing(permissions);
      const actual = Object.fromEntries(
        Object.keys(EVERY_ENTRY_ACT).map((act) => [
          act,
          entryOffers(entry, act as EntryAct, reader, "group"),
        ]),
      );
      expect(Object.keys(expected)).toHaveLength(4);
      expect(Object.keys(actual)).toHaveLength(Object.keys(expected).length);
      expect(actual).toEqual(expected);
    },
  );

  it.each([
    [
      "draft",
      Object.keys(EVERY_PERMISSION),
      true,
      {
        write: true,
        submit: true,
        withdraw: false,
        approve: false,
        retire: false,
      },
    ],
    [
      "draft",
      ["approve_entry"],
      false,
      {
        write: false,
        submit: false,
        withdraw: false,
        approve: false,
        retire: false,
      },
    ],
    [
      "submitted",
      Object.keys(EVERY_PERMISSION),
      false,
      {
        write: false,
        submit: false,
        withdraw: true,
        approve: true,
        retire: false,
      },
    ],
    [
      "submitted",
      Object.keys(EVERY_PERMISSION),
      true,
      {
        write: false,
        submit: false,
        withdraw: true,
        approve: false,
        retire: false,
      },
    ],
    [
      "submitted",
      ["approve_entry"],
      false,
      {
        write: false,
        submit: false,
        withdraw: false,
        approve: true,
        retire: false,
      },
    ],
    [
      "in_service",
      Object.keys(EVERY_PERMISSION),
      true,
      {
        write: false,
        submit: false,
        withdraw: false,
        approve: false,
        retire: true,
      },
    ],
    [
      "in_service",
      ["author_entry", "approve_entry"],
      false,
      {
        write: false,
        submit: false,
        withdraw: false,
        approve: false,
        retire: false,
      },
    ],
    [
      "retired",
      Object.keys(EVERY_PERMISSION),
      false,
      {
        write: false,
        submit: false,
        withdraw: false,
        approve: false,
        retire: false,
      },
    ],
  ] as const)(
    "offers exactly the version acts for standing %s, grants %j and authorship %s",
    (at, permissions, wroteIt, expected) => {
      const version = {
        ...VERSION,
        standing: at,
        stateActs: new Set(VERSION_STATE_ACTS[at]),
      };
      const reader = standing(permissions, wroteIt ? "writer" : "another");
      const actual = Object.fromEntries(
        Object.keys(EVERY_VERSION_ACT).map((act) => [
          act,
          versionOffers(version, act as VersionAct, reader, "group"),
        ]),
      );
      expect(Object.keys(expected)).toHaveLength(5);
      expect(Object.keys(actual)).toHaveLength(Object.keys(expected).length);
      expect(actual).toEqual(expected);
    },
  );

  it.each([
    [[], false, false],
    [["author_entry"], true, false],
    [["revoke_entry"], false, true],
    [["author_entry", "revoke_entry"], true, true],
  ] as const)(
    "intersects entry facts with the current group's grants %j",
    (permissions, rename, stop) => {
      const reader = standing(permissions);
      expect(entryOffers(ENTRY, "rename", reader, "group")).toBe(rename);
      expect(entryOffers(ENTRY, "stop", reader, "group")).toBe(stop);
      expect(entryOffers(ENTRY, "start_draft", reader, "group")).toBe(false);
    },
  );

  it.each([
    ["writer", false],
    ["another", true],
    [undefined, false],
  ] as const)(
    "approves only a known non-writer, identity %s",
    (userId, approved) => {
      const reader = standing(["approve_entry", "author_entry"], userId);
      expect(versionOffers(VERSION, "approve", reader, "group")).toBe(approved);
      expect(versionOffers(VERSION, "withdraw", reader, "group")).toBe(true);
    },
  );

  it("does not grant a known act from another group or from an unknown state word", () => {
    expect(
      entryOffers(
        ENTRY,
        "rename",
        standing(["author_entry"], "reader", "elsewhere"),
        "group",
      ),
    ).toBe(false);
    const unknown = {
      ...ENTRY,
      stateActs: new Set(["constructor", "invented_act"]),
    };
    expect(
      entryOffers(unknown, "rename", standing(["author_entry"]), "group"),
    ).toBe(false);
    expect(
      entryOffers(
        unknown,
        "constructor" as EntryAct,
        standing(["author_entry"]),
        "group",
      ),
    ).toBe(false);
  });

  it("does not grant a version act from another group or from an unknown state word", () => {
    expect(
      versionOffers(
        VERSION,
        "approve",
        standing(["approve_entry"], "reader", "elsewhere"),
        "group",
      ),
    ).toBe(false);
    const unknown = {
      ...VERSION,
      stateActs: new Set(["constructor", "invented_act"]),
    };
    const reader = standing(["approve_entry"], "reader");
    expect(versionOffers(unknown, "approve", reader, "group")).toBe(false);
    expect(
      versionOffers(unknown, "constructor" as VersionAct, reader, "group"),
    ).toBe(false);
  });

  it.each([
    ["first-writer", false],
    ["second-writer", false],
    ["another", true],
  ] as const)(
    "identifies every writer by user id when display names match, reader %s",
    (userId, approved) => {
      const version = {
        ...VERSION,
        writers: [
          { userId: "first-writer", displayName: "Same name" },
          { userId: "second-writer", displayName: "Same name" },
        ],
      };
      expect(
        versionOffers(
          version,
          "approve",
          standing(["approve_entry"], userId),
          "group",
        ),
      ).toBe(approved);
    },
  );

  it.each([null, new Set<string>()])(
    "withdraws controls when this read says %j rather than keeping previous facts",
    (stateActs) => {
      const reader = standing(
        ["author_entry", "approve_entry", "revoke_entry"],
        "another",
      );
      expect(
        entryOffers({ ...ENTRY, stateActs }, "rename", reader, "group"),
      ).toBe(false);
      expect(
        versionOffers({ ...VERSION, stateActs }, "approve", reader, "group"),
      ).toBe(false);
    },
  );
});
