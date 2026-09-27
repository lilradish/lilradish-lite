import { describe, expect, it } from "vitest";

import { answering, refusalOf } from "../../../../../../testutil/answering";
import { NOT_A_PROBLEM_DOCUMENT, NOT_AN_ADDRESS } from "../../../../../problem";
import { give, take } from "./{role}";

const GROUP = "00000003-0000-4000-8000-000000000991";

const SUBJECT = "00000002-0000-4000-8000-000000000991";

const GRACE = {
  subjectId: SUBJECT,
  userId: "000991",
  displayName: "Grace Hopper",
  roles: ["operator"],
  lastChangingRoles: [],
  removable: true,
};

/** The two changes differ only in method, so each case is asked of both. */
const CHANGES = [
  ["give", give, "PUT"],
  ["take", take, "DELETE"],
] as const;

describe.each(CHANGES)("%s", (_name, change, method) => {
  it("asks for the role at the member's own address inside the group, carrying nothing", async () => {
    const sent = answering([JSON.stringify(GRACE), 200]);

    await change(GROUP, SUBJECT, "overseer", new AbortController().signal);

    const [address, init] = sent.mock.calls[0]!;
    expect(address).toBe(
      `/api/groups/${GROUP}/members/${SUBJECT}/roles/overseer`,
    );
    expect(init?.method).toBe(method);
    expect(init?.body).toBeUndefined();
  });

  it("reads the member as the group now holds them", async () => {
    answering([JSON.stringify(GRACE), 200]);

    const member = await change(
      GROUP,
      SUBJECT,
      "operator",
      new AbortController().signal,
    );

    expect(member).toEqual({
      ...GRACE,
      roles: new Set(["operator"]),
      lastChangingRoles: new Set(),
      removable: true,
    });
  });

  it("refuses an answer that is not a member, under the status it arrived with", async () => {
    answering(['{"roles":["operator"]}', 200]);

    const failure = await refusalOf(
      change(GROUP, SUBJECT, "operator", new AbortController().signal),
    );

    expect(failure.problem).toEqual({
      status: 200,
      code: NOT_A_PROBLEM_DOCUMENT,
    });
  });

  it("goes through the member's address as atMember makes it, refusing what it refuses", async () => {
    const sent = answering([JSON.stringify(GRACE), 200]);

    const failure = await refusalOf(
      change(GROUP, "..", "operator", new AbortController().signal),
    );

    expect(failure.problem).toEqual({ code: NOT_AN_ADDRESS });
    expect(sent).not.toHaveBeenCalled();
  });
});

describe("take", () => {
  /** Nobody else could change the membership without it, and the server keeps it. */
  it("carries the refusal to take the last role that lets anybody change the membership as the server's own code", async () => {
    answering(['{"code":"LAST_MEMBERSHIP_CHANGER"}', 409]);

    const failure = await refusalOf(
      take(GROUP, SUBJECT, "owner", new AbortController().signal),
    );

    expect(failure.problem).toEqual({
      status: 409,
      code: "LAST_MEMBERSHIP_CHANGER",
    });
  });
});
