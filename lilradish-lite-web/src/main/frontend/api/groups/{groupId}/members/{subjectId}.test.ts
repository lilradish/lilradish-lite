import { describe, expect, it } from "vitest";

import { answering, refusalOf } from "../../../../testutil/answering";
import { NOT_A_PROBLEM_DOCUMENT, NOT_AN_ADDRESS } from "../../../problem";
import {
  atMember,
  readMember,
  refusedForWhatMoved,
  takeOut,
} from "./{subjectId}";

const GROUP = "00000003-0000-4000-8000-000000000981";

const SUBJECT = "00000002-0000-4000-8000-000000000981";

const ADA = {
  subjectId: SUBJECT,
  userId: "000981",
  displayName: "Ada Lovelace",
  roles: ["owner"],
  lastChangingRoles: ["owner"],
  removable: false,
};

describe("atMember", () => {
  it("escapes the group and the member each as one segment", async () => {
    const address = await atMember("g/1", "s/1", (asked) =>
      Promise.resolve(asked),
    );

    expect(address).toBe("/api/groups/g%2F1/members/s%2F1");
  });

  it.each([
    ["the group", "..", SUBJECT],
    ["the member", GROUP, "."],
  ])(
    "refuses an address that %s would lead away from, asking nothing",
    async (_case, group, subject) => {
      const failure = await refusalOf(
        atMember(group, subject, () => Promise.resolve("asked")),
      );

      expect(failure.problem).toEqual({ code: NOT_AN_ADDRESS });
    },
  );
});

describe("readMember", () => {
  it("asks the member's own address inside the group, and reads what the group holds of them", async () => {
    const sent = answering([JSON.stringify(ADA), 200]);

    const member = await readMember(
      GROUP,
      SUBJECT,
      new AbortController().signal,
    );

    expect(sent.mock.calls.map(([address]) => address)).toEqual([
      `/api/groups/${GROUP}/members/${SUBJECT}`,
    ]);
    expect(member).toEqual({
      ...ADA,
      roles: new Set(["owner"]),
      lastChangingRoles: new Set(["owner"]),
      removable: false,
    });
  });

  it.each([
    ["MEMBER_NOT_IN_VIEW", 404],
    ["GROUP_NOT_IN_VIEW", 404],
  ])(
    "carries the server's refusal %s as its own code",
    async (code, status) => {
      answering([JSON.stringify({ code }), status]);

      const failure = await refusalOf(
        readMember(GROUP, SUBJECT, new AbortController().signal),
      );

      expect(failure.problem).toEqual({ status, code });
    },
  );
});

describe("takeOut", () => {
  it("asks for the member's removal at their address, carrying nothing, and takes no content as done", async () => {
    const sent = answering(["", 204]);

    const done = await takeOut(GROUP, SUBJECT, new AbortController().signal);

    const [address, init] = sent.mock.calls[0]!;
    expect(address).toBe(`/api/groups/${GROUP}/members/${SUBJECT}`);
    expect(init?.method).toBe("DELETE");
    expect(init?.body).toBeUndefined();
    expect(done).toBeUndefined();
  });

  it("refuses a removal answered with content, which no removal answers with", async () => {
    answering([JSON.stringify(ADA), 200]);

    const failure = await refusalOf(
      takeOut(GROUP, SUBJECT, new AbortController().signal),
    );

    expect(failure.problem).toEqual({
      status: 200,
      code: NOT_A_PROBLEM_DOCUMENT,
    });
  });

  it("carries the refusal to leave nobody who may change the membership as the server's own code", async () => {
    answering(['{"code":"LAST_MEMBERSHIP_CHANGER"}', 409]);

    const failure = await refusalOf(
      takeOut(GROUP, SUBJECT, new AbortController().signal),
    );

    expect(failure.problem).toEqual({
      status: 409,
      code: "LAST_MEMBERSHIP_CHANGER",
    });
  });
});

describe("refusedForWhatMoved", () => {
  /** Each says the group stands otherwise than the page last read it. */
  it.each([
    [409, "LAST_MEMBERSHIP_CHANGER"],
    [404, "MEMBER_NOT_IN_VIEW"],
    [400, "PERSON_NOT_IN_POOL"],
    [409, "PERSON_ALREADY_IN_GROUP"],
  ])("takes a served %s %s for the group having moved", (status, code) => {
    expect(refusedForWhatMoved({ status, code })).toBe(true);
  });

  /** Neither says anything of the group: one is about the reader, the other never arrived. */
  it.each([
    ["a refusal of the act", { status: 403, code: "ACT_NOT_PERMITTED" }],
    ["the same code with no status", { code: "MEMBER_NOT_IN_VIEW" }],
  ])("takes %s for something else", (_case, problem) => {
    expect(refusedForWhatMoved(problem)).toBe(false);
  });
});
