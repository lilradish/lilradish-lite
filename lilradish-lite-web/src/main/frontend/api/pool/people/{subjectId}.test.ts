import { describe, expect, it, vi } from "vitest";

import { answering, refusalOf } from "../../../testutil/answering";
import { NOT_A_PROBLEM_DOCUMENT, NOT_AN_ADDRESS } from "../../problem";
import {
  atPerson,
  readPerson,
  refusedForWhatStillHolds,
  takeOut,
} from "./{subjectId}";

const SUBJECT = "00000002-0000-4000-8000-00000000014c";

const GRACE = {
  subjectId: SUBJECT,
  userId: "000140",
  displayName: "Grace Hopper",
  estateRoles: ["watcher"],
  lastGrantingRoles: [],
  groups: ["Payroll", "finance"],
  seeded: true,
};

describe("readPerson", () => {
  it("asks the person's own address, the identifier as it arrived", async () => {
    const sent = answering([JSON.stringify(GRACE), 200]);

    await readPerson(SUBJECT, new AbortController().signal);
    await readPerson(SUBJECT.toUpperCase(), new AbortController().signal);

    expect(sent.mock.calls.map(([address]) => address)).toEqual([
      `/api/pool/people/${SUBJECT}`,
      `/api/pool/people/${SUBJECT.toUpperCase()}`,
    ]);
  });

  /** What an address may be is `atPerson`'s to say; here, only that the read goes through it. */
  it("goes through the person's address as atPerson makes it, refusing what it refuses", async () => {
    const sent = answering([JSON.stringify(GRACE), 200]);

    const failure = await refusalOf(
      readPerson("..", new AbortController().signal),
    );

    expect(failure.problem).toEqual({ code: NOT_AN_ADDRESS });
    expect(sent).not.toHaveBeenCalled();
  });

  it("reads what they hold, the groups they are in by name, and whether a migration seeded them", async () => {
    answering([JSON.stringify(GRACE), 200]);

    const person = await readPerson(SUBJECT, new AbortController().signal);

    expect(person).toEqual({
      ...GRACE,
      estateRoles: new Set(["watcher"]),
      lastGrantingRoles: new Set(),
    });
  });

  it("refuses an answer that is not a person, under the status it arrived with", async () => {
    answering([JSON.stringify({ ...GRACE, seeded: "yes" }), 200]);

    const failure = await refusalOf(
      readPerson(SUBJECT, new AbortController().signal),
    );

    expect(failure.problem).toEqual({
      status: 200,
      code: NOT_A_PROBLEM_DOCUMENT,
    });
  });

  it("carries somebody not in the pool as the server's own refusal, saying no more than it did", async () => {
    answering(['{"code":"PERSON_NOT_IN_VIEW","detail":"d"}', 404]);

    const failure = await refusalOf(
      readPerson(SUBJECT, new AbortController().signal),
    );

    expect(failure.problem).toEqual({
      status: 404,
      code: "PERSON_NOT_IN_VIEW",
      detail: "d",
    });
  });
});

describe("takeOut", () => {
  it("asks for the person's own address to go, and settles on no content", async () => {
    const sent = answering(["", 204]);

    await expect(
      takeOut(SUBJECT, new AbortController().signal),
    ).resolves.toBeUndefined();

    const [address, init] = sent.mock.calls[0]!;
    expect(address).toBe(`/api/pool/people/${SUBJECT}`);
    expect(init?.method).toBe("DELETE");
  });

  it("goes through the person's address as atPerson makes it, refusing what it refuses", async () => {
    const sent = answering(["", 204]);

    const failure = await refusalOf(takeOut(".", new AbortController().signal));

    expect(failure.problem).toEqual({ code: NOT_AN_ADDRESS });
    expect(sent).not.toHaveBeenCalled();
  });

  /** Somebody still here is not gone because a document came back where none was promised. */
  it("refuses a document where no content was promised", async () => {
    answering([JSON.stringify(GRACE), 200]);

    const failure = await refusalOf(
      takeOut(SUBJECT, new AbortController().signal),
    );

    expect(failure.problem).toEqual({
      status: 200,
      code: NOT_A_PROBLEM_DOCUMENT,
    });
  });

  /** Neither names what still holds them; the panel is what names it. */
  it.each(["PERSON_HOLDS_ESTATE_ROLES", "PERSON_IN_GROUPS"])(
    "carries the server's refusal %s as its own code, saying no more than it did",
    async (code) => {
      answering([JSON.stringify({ code, detail: "d" }), 409]);

      const failure = await refusalOf(
        takeOut(SUBJECT, new AbortController().signal),
      );

      expect(failure.problem).toEqual({ status: 409, code, detail: "d" });
    },
  );
});

describe("refusedForWhatStillHolds", () => {
  it.each(["PERSON_HOLDS_ESTATE_ROLES", "PERSON_IN_GROUPS"])(
    "takes a conflict over %s for a refusal about what still holds them",
    (code) => {
      expect(refusedForWhatStillHolds({ status: 409, code })).toBe(true);
    },
  );

  /** The status is read before the code: the same code under another status is some other refusal. */
  it.each([
    [
      "the same code under another status",
      { status: 400, code: "PERSON_IN_GROUPS" },
    ],
    [
      "the same code with no status at all",
      { code: "PERSON_HOLDS_ESTATE_ROLES" },
    ],
    ["another conflict", { status: 409, code: "LAST_ESTATE_ROLE_GRANTOR" }],
  ])("takes %s for something else", (_case, problem) => {
    expect(refusedForWhatStillHolds(problem)).toBe(false);
  });
});

describe("atPerson", () => {
  it("hands the person's own address to what asks, and answers with what it answered", async () => {
    const ask = vi.fn((address: string) => Promise.resolve(`asked ${address}`));

    await expect(atPerson(SUBJECT, ask)).resolves.toBe(
      `asked /api/pool/people/${SUBJECT}`,
    );
    expect(ask).toHaveBeenCalledOnce();
  });

  /** Escaping leaves both as they are, and the address would resolve them away before it was sent. */
  it.each([".", ".."])("refuses %o here, asking nothing", async (address) => {
    const ask = vi.fn(() => Promise.resolve("asked"));

    const failure = await refusalOf(atPerson(address, ask));

    expect(failure.problem).toEqual({ code: NOT_AN_ADDRESS });
    expect(ask).not.toHaveBeenCalled();
  });

  /**
   * Anything else is the server's to judge, sent as one escaped segment so
   * nothing in it can reach another address.
   */
  it.each([
    ["../x", "/api/pool/people/..%2Fx"],
    ["../standing?x=1#y", "/api/pool/people/..%2Fstanding%3Fx%3D1%23y"],
    ["2-0-4000-8000-14c", "/api/pool/people/2-0-4000-8000-14c"],
  ])("hands on %o as the single segment %s", async (address, asked) => {
    const ask = vi.fn((sentTo: string) => Promise.resolve(sentTo));

    await expect(atPerson(address, ask)).resolves.toBe(asked);
  });
});
