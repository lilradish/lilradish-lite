import { describe, expect, it } from "vitest";

import { answering, refusalOf } from "../../testutil/answering";
import { NOT_A_PROBLEM_DOCUMENT, NOT_AN_ADDRESS } from "../problem";
import { renameGroup } from "./{groupId}";

const RENAMED = {
  groupId: "00000003-0000-4000-8000-000000000701",
  key: "PAYROLL",
  name: "Salaries",
  canBeAdministered: true,
  memberCount: 2,
};

describe("renameGroup", () => {
  /** A key is given once, so a rename carries the name alone. */
  it("sends the new name alone to the group's own address, and reads the group as it now stands", async () => {
    const sent = answering([JSON.stringify(RENAMED), 200]);

    const renamed = await renameGroup(
      RENAMED.groupId,
      "Salaries",
      new AbortController().signal,
    );

    expect(renamed).toEqual(RENAMED);
    expect(String(sent.mock.calls[0]![0])).toBe(
      `/api/groups/${RENAMED.groupId}`,
    );
    expect(sent.mock.calls[0]![1]!.method).toBe("PATCH");
    expect(JSON.parse(String(sent.mock.calls[0]![1]!.body))).toEqual({
      name: "Salaries",
    });
  });

  it("escapes the address as one segment", async () => {
    const sent = answering([JSON.stringify(RENAMED), 200]);

    await renameGroup("a/b?c", "Salaries", new AbortController().signal);

    expect(String(sent.mock.calls[0]![0])).toBe("/api/groups/a%2Fb%3Fc");
  });

  /** Escaping leaves them as they are, and the address would resolve away to the register itself. */
  it.each([".", ".."])(
    "never sends %s as an address, refusing it as one naming nothing",
    async (groupId) => {
      const sent = answering([JSON.stringify(RENAMED), 200]);

      const failure = await refusalOf(
        renameGroup(groupId, "Salaries", new AbortController().signal),
      );

      expect(failure.problem).toEqual({ code: NOT_AN_ADDRESS });
      expect(sent).not.toHaveBeenCalled();
    },
  );

  it("carries the server's refusal of the name under the server's own code", async () => {
    answering(['{"code":"GROUP_NAME_TAKEN","detail":"d"}', 409]);

    const failure = await refusalOf(
      renameGroup(RENAMED.groupId, "Payroll", new AbortController().signal),
    );

    expect(failure.problem).toEqual({
      status: 409,
      code: "GROUP_NAME_TAKEN",
      detail: "d",
    });
  });

  it("refuses an answer that is not a group, under the status it arrived with", async () => {
    answering([JSON.stringify({ ...RENAMED, key: null }), 200]);

    const failure = await refusalOf(
      renameGroup(RENAMED.groupId, "Salaries", new AbortController().signal),
    );

    expect(failure.problem).toEqual({
      status: 200,
      code: NOT_A_PROBLEM_DOCUMENT,
    });
  });
});
