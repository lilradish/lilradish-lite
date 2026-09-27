import { describe, expect, it } from "vitest";

import { answering, refusalOf } from "../../../../../testutil/answering";
import { NOT_A_PROBLEM_DOCUMENT, NOT_AN_ADDRESS } from "../../../../problem";
import { grant, withdraw } from "./{role}";

const SUBJECT = "00000002-0000-4000-8000-00000000014c";

const GRACE = {
  subjectId: SUBJECT,
  userId: "000140",
  displayName: "Grace Hopper",
  estateRoles: ["steward"],
  lastGrantingRoles: ["steward"],
  groups: [],
  seeded: false,
};

/** The two changes differ only in method, so each case is asked of both. */
const CHANGES = [
  ["grant", grant, "PUT"],
  ["withdraw", withdraw, "DELETE"],
] as const;

describe.each(CHANGES)("%s", (_name, change, method) => {
  it("asks for the role at the person's own address, carrying nothing", async () => {
    const sent = answering([JSON.stringify(GRACE), 200]);

    await change(SUBJECT, "watcher", new AbortController().signal);

    const [address, init] = sent.mock.calls[0]!;
    expect(address).toBe(`/api/pool/people/${SUBJECT}/estate-roles/watcher`);
    expect(init?.method).toBe(method);
    expect(init?.body).toBeUndefined();
  });

  it("reads the person as the pool now holds them", async () => {
    answering([JSON.stringify(GRACE), 200]);

    const person = await change(
      SUBJECT,
      "steward",
      new AbortController().signal,
    );

    expect(person).toEqual({
      ...GRACE,
      estateRoles: new Set(["steward"]),
      lastGrantingRoles: new Set(["steward"]),
    });
  });

  it("refuses an answer that is not a person, under the status it arrived with", async () => {
    answering(['{"estateRoles":["steward"]}', 200]);

    const failure = await refusalOf(
      change(SUBJECT, "steward", new AbortController().signal),
    );

    expect(failure.problem).toEqual({
      status: 200,
      code: NOT_A_PROBLEM_DOCUMENT,
    });
  });

  /** What an address may be is `atPerson`'s to say; here, only that the change goes through it. */
  it("goes through the person's address as atPerson makes it, refusing what it refuses", async () => {
    const sent = answering([JSON.stringify(GRACE), 200]);

    const failure = await refusalOf(
      change("..", "steward", new AbortController().signal),
    );

    expect(failure.problem).toEqual({ code: NOT_AN_ADDRESS });
    expect(sent).not.toHaveBeenCalled();
  });

  it("puts the role after the person's escaped segment", async () => {
    const sent = answering(['{"code":"PERSON_NOT_IN_VIEW"}', 404]);

    await refusalOf(change("../x", "steward", new AbortController().signal));

    expect(sent.mock.calls.map(([address]) => address)).toEqual([
      "/api/pool/people/..%2Fx/estate-roles/steward",
    ]);
  });

  it.each([
    ["PERSON_NOT_IN_VIEW", 404],
    ["ACT_NOT_PERMITTED", 403],
  ])(
    "carries the server's refusal %s as its own code",
    async (code, status) => {
      answering([JSON.stringify({ code, detail: "d" }), status]);

      const failure = await refusalOf(
        change(SUBJECT, "steward", new AbortController().signal),
      );

      expect(failure.problem).toEqual({ status, code, detail: "d" });
    },
  );
});

describe("withdraw", () => {
  it("carries the refusal to leave nobody who may grant an estate role as the server's own code", async () => {
    answering(['{"code":"LAST_ESTATE_ROLE_GRANTOR","detail":"d"}', 409]);

    const failure = await refusalOf(
      withdraw(SUBJECT, "steward", new AbortController().signal),
    );

    expect(failure.problem).toEqual({
      status: 409,
      code: "LAST_ESTATE_ROLE_GRANTOR",
      detail: "d",
    });
  });
});
