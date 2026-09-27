import { renderHook, waitFor } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import { answering } from "../testutil/answering";
import { inGroup } from "../testutil/standingRead";
import {
  holds,
  mayIn,
  useStandingResource,
  type GroupPermission,
  type Standing,
  type SurfaceAct,
} from "./standing";

/**
 * The whole vocabulary, so a sweep over it is a sweep over every door there is
 * — and a claim the compiler holds in both directions. A union is gone by the
 * time this runs, so the names have to be written out; taken as the keys of a
 * record over the union, an act added to the type leaves this no longer
 * satisfying it and an act dropped leaves a key here naming nothing. A plain
 * list would only catch the second, and would quietly shrink on the first.
 */
const EVERY_ACT = Object.keys({
  keep_pool: true,
  grant_estate_role: true,
  keep_group_register: true,
  check_soundness: true,
  read_measurements: true,
} satisfies Record<SurfaceAct, true>) as readonly SurfaceAct[];

/** The same for a group's, held in both directions for the same reason. */
const EVERY_GROUP_PERMISSION = Object.keys({
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
} satisfies Record<GroupPermission, true>) as readonly GroupPermission[];

const PAYROLL = "00000003-0000-4000-8000-000000000911";

const TRIAGE = "00000003-0000-4000-8000-000000000912";

function reachable(acts: Iterable<string>): SurfaceAct[] {
  const standing: Standing = { acts: new Set(acts), groups: [] };
  return EVERY_ACT.filter((act) => holds(standing, act));
}

function permittedIn(permissions: Iterable<string>): GroupPermission[] {
  const group = inGroup(PAYROLL, "PAYROLL", "Payroll", permissions);
  return EVERY_GROUP_PERMISSION.filter((permission) =>
    mayIn(group, permission),
  );
}

/** A standing as it arrived, its sets spread so that what was kept can be compared. */
function spread(standing: Standing) {
  return {
    acts: [...standing.acts],
    groups: standing.groups.map((group) => ({
      ...group,
      permissions: [...group.permissions],
    })),
  };
}

async function standingRead(body: string, status = 200) {
  const sent = answering([body, status]);
  const { result } = renderHook(() => useStandingResource());
  await waitFor(() => expect(result.current.loading).toBe(false));
  return { read: result.current, sent };
}

describe("holds", () => {
  it("reaches exactly what the server named, and nothing standing beside it", () => {
    expect(reachable(["keep_pool", "check_soundness"])).toEqual([
      "keep_pool",
      "check_soundness",
    ]);
  });

  /**
   * The direction everything here is built to fail in. A standing nobody
   * filled in is not a reader who may do anything; it is a reader nothing is known
   * about, and the two are only ever told apart in favour of the closed door.
   */
  it("reaches nothing at all where the server named nothing", () => {
    expect(reachable([])).toEqual([]);
  });

  it("keeps an act this build does not know and opens no door with it", () => {
    const held = ["keep_group_register", "commission_a_satellite"];

    expect(reachable(held)).toEqual(["keep_group_register"]);
  });

  /** A group's permissions are not the estate's, even where a word were spelt alike. */
  it("reaches no act on anything the reader holds only inside a group", () => {
    const standing: Standing = {
      acts: new Set(),
      groups: [inGroup(PAYROLL, "PAYROLL", "Payroll", EVERY_GROUP_PERMISSION)],
    };

    expect(EVERY_ACT.filter((act) => holds(standing, act))).toEqual([]);
  });
});

describe("mayIn", () => {
  it("permits in a group exactly what the server named there", () => {
    expect(permittedIn(["read_membership", "start_run"])).toEqual([
      "read_membership",
      "start_run",
    ]);
  });

  it("permits nothing in a group where the server named nothing there", () => {
    expect(permittedIn([])).toEqual([]);
  });

  it("permits nothing on a word this build does not know", () => {
    expect(permittedIn(["launch_the_satellite"])).toEqual([]);
  });
});

describe("useStandingResource", () => {
  it("holds what the server said this reader may do", async () => {
    const { read } = await standingRead(
      '{"acts":["keep_pool","keep_group_register"],"groups":[]}',
    );

    expect(spread(read.value)).toEqual({
      acts: ["keep_pool", "keep_group_register"],
      groups: [],
    });
    expect(read.problem).toBeNull();
  });

  /** In the order sent, which is the order the reader is shown them in. */
  it("holds each group the reader is in, as sent and in the order sent", async () => {
    const { read } = await standingRead(
      JSON.stringify({
        acts: [],
        groups: [
          {
            groupId: TRIAGE,
            key: "TRIAGE",
            name: "Triage",
            permissions: ["start_run"],
          },
          {
            groupId: PAYROLL,
            key: "PAYROLL",
            name: "Payroll",
            permissions: ["read_membership", "launch_the_satellite"],
          },
        ],
      }),
    );

    expect(spread(read.value).groups).toEqual([
      {
        groupId: TRIAGE,
        key: "TRIAGE",
        name: "Triage",
        permissions: ["start_run"],
      },
      {
        groupId: PAYROLL,
        key: "PAYROLL",
        name: "Payroll",
        permissions: ["read_membership", "launch_the_satellite"],
      },
    ]);
  });

  /** One malformed group is not a reason to draw the reader in none. */
  it("leaves out a group it could not address or show, keeping the rest", async () => {
    const { read } = await standingRead(
      JSON.stringify({
        acts: ["keep_pool"],
        groups: [
          { key: "LOST", name: "Unaddressed", permissions: [] },
          { groupId: TRIAGE, key: "TRIAGE", permissions: [] },
          { groupId: TRIAGE, name: "Triage", permissions: [] },
          { groupId: 7, key: "SEVEN", name: "Numbered", permissions: [] },
          "Payroll",
          null,
          { groupId: PAYROLL, key: "PAYROLL", name: "Payroll" },
        ],
      }),
    );

    expect(spread(read.value)).toEqual({
      acts: ["keep_pool"],
      groups: [
        { groupId: PAYROLL, key: "PAYROLL", name: "Payroll", permissions: [] },
      ],
    });
  });

  /**
   * Asked of nobody in particular and carrying nothing about who is asking:
   * the identity reaches the server without this side's help.
   */
  it("asks the one address, with no parameter and nothing naming a credential", async () => {
    const { sent } = await standingRead('{"acts":[],"groups":[]}');

    expect(sent).toHaveBeenCalledOnce();
    expect(sent.mock.calls[0]![0]).toBe("/api/standing");
    expect(JSON.stringify(sent.mock.calls[0]![1])).not.toMatch(
      /cookie|authorization|user/i,
    );
  });

  /**
   * A reader the server granted nothing is a described reader. The read
   * settled, nothing was refused, and the answer is that there is nowhere to
   * go — which must not arrive as a failure, because a failure is a thing the
   * reader would be told to retry.
   */
  it("carries an empty standing as an answer rather than as a refusal", async () => {
    const { read } = await standingRead('{"acts":[],"groups":[]}');

    expect(spread(read.value)).toEqual({ acts: [], groups: [] });
    expect(read.problem).toBeNull();
  });

  /**
   * The server may publish a word this build has never heard of without
   * waiting for this side to be rebuilt. What arrives is kept as it came,
   * because narrowing it here would make this build's vocabulary the authority
   * on what the server said.
   */
  it("keeps a word this build does not know, and drops what is not a word at all", async () => {
    const { read } = await standingRead(
      '{"acts":["keep_pool","commission_a_satellite",7,null],"groups":[]}',
    );

    expect([...read.value.acts]).toEqual([
      "keep_pool",
      "commission_a_satellite",
    ]);
  });

  /**
   * A document short of the members it was asked for is not a reader who may
   * do everything, and it is not a crash either. Both of those are reachable
   * from here — a spread over `undefined` throws, and a fallback over the whole
   * document would hand back whatever else it carried.
   */
  it.each([
    ["an object naming neither", "{}"],
    ["lists that are not lists", '{"acts":"keep_pool","groups":{"0":{}}}'],
    ["no object at all", '["keep_pool"]'],
  ])("reaches nothing where the answer is %s", async (_case, body) => {
    const { read } = await standingRead(body);

    expect(spread(read.value)).toEqual({ acts: [], groups: [] });
    expect(read.problem).toBeNull();
  });

  /**
   * The direction that matters most. A refused read leaves the reader holding
   * nothing rather than holding the last answer, and carries the server's own
   * code so the frame can say which wall this was.
   */
  it("reaches nothing when the server refuses, and says why", async () => {
    const { read } = await standingRead('{"code":"NOT_SIGNED_IN"}', 401);

    expect(spread(read.value)).toEqual({ acts: [], groups: [] });
    expect(read.problem).toEqual({ status: 401, code: "NOT_SIGNED_IN" });
  });
});
