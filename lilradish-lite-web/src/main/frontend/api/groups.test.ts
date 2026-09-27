import { describe, expect, it } from "vitest";

import { answering, refusalOf } from "../testutil/answering";
import { createGroup, groupsLoader, type GroupQuery } from "./groups";
import { NOT_A_PROBLEM_DOCUMENT } from "./problem";

function addressAsked(sent: ReturnType<typeof answering>, call = 0): URL {
  return new URL(String(sent.mock.calls[call]![0]), "http://reader.test");
}

const EVERY_GROUP: GroupQuery = { filter: "", order: "name" };

const PAYROLL = {
  groupId: "00000003-0000-4000-8000-000000000701",
  key: "PAYROLL",
  name: "Payroll",
  canBeAdministered: true,
  memberCount: 2,
};

const EMPTY_ROOM = {
  groupId: "00000003-0000-4000-8000-000000000704",
  key: "VOID",
  name: "Empty room",
  canBeAdministered: false,
  memberCount: 0,
};

describe("groupsLoader", () => {
  it("asks the register's own address with the order it wants and nothing else when nothing narrows it", async () => {
    const sent = answering(['{"items":[]}', 200]);

    await groupsLoader(EVERY_GROUP)(null, new AbortController().signal);

    const asked = addressAsked(sent);
    expect(asked.pathname).toBe("/api/groups");
    expect([...asked.searchParams]).toEqual([["sort", "name"]]);
  });

  it("sends what was typed, the order and the cursor as they are, each under its own parameter", async () => {
    const typed = " Pay%_\\&=+/#?roll ";
    const sent = answering(['{"items":[]}', 200]);

    await groupsLoader({ filter: typed, order: "-canBeAdministered" })(
      "AAEC_-",
      new AbortController().signal,
    );

    const asked = addressAsked(sent);
    expect(asked.searchParams.getAll("filter")).toEqual([typed]);
    expect(asked.searchParams.getAll("sort")).toEqual(["-canBeAdministered"]);
    expect(asked.searchParams.getAll("cursor")).toEqual(["AAEC_-"]);
    expect([...asked.searchParams.keys()].sort()).toEqual([
      "cursor",
      "filter",
      "sort",
    ]);
  });

  it("reads each group under its own members, and none it was not built to read", async () => {
    answering([
      JSON.stringify({
        items: [PAYROLL, { ...EMPTY_ROOM, createdBy: "somebody" }],
        nextCursor: "AAEC_-",
      }),
      200,
    ]);

    const page = await groupsLoader(EVERY_GROUP)(
      null,
      new AbortController().signal,
    );

    expect(page.items).toEqual([PAYROLL, EMPTY_ROOM]);
    expect(Object.keys(page.items[1]!).sort()).toEqual([
      "canBeAdministered",
      "groupId",
      "key",
      "memberCount",
      "name",
    ]);
    expect(page.nextCursor).toBe("AAEC_-");
  });

  it("reads the last page as having no cursor, rather than a null one", async () => {
    answering([JSON.stringify({ items: [PAYROLL] }), 200]);

    const page = await groupsLoader(EVERY_GROUP)(
      null,
      new AbortController().signal,
    );

    expect("nextCursor" in page).toBe(false);
  });

  /** A group that cannot be read in full is a hole in the register, so the whole page is refused. */
  it.each([
    ["no list of groups", { nextCursor: "AAEC" }],
    ["a group with no key", { items: [{ ...PAYROLL, key: undefined }] }],
    ["a group with no name", { items: [{ ...PAYROLL, name: null }] }],
    ["a group with no address", { items: [{ ...PAYROLL, groupId: 7 }] }],
    [
      "a standing that is not a yes or a no",
      { items: [{ ...PAYROLL, canBeAdministered: "yes" }] },
    ],
    [
      "a count that is not a number",
      { items: [{ ...PAYROLL, memberCount: "2" }] },
    ],
    [
      "a count that is not whole",
      { items: [{ ...PAYROLL, memberCount: 1.5 }] },
    ],
    ["a negative count", { items: [{ ...PAYROLL, memberCount: -1 }] }],
    ["a cursor that is null", { items: [], nextCursor: null }],
    ["a group that is no document", { items: ["PAYROLL"] }],
  ])("refuses a page carrying %s", async (_, body) => {
    answering([JSON.stringify(body), 200]);

    const failure = await refusalOf(
      groupsLoader(EVERY_GROUP)(null, new AbortController().signal),
    );

    expect(failure.problem).toEqual({
      status: 200,
      code: NOT_A_PROBLEM_DOCUMENT,
    });
  });
});

describe("createGroup", () => {
  /** The key goes as typed, and nothing else the caller held rides along with the three members. */
  it("sends the name, the key as typed and who in the pool is its first member, and nothing else", async () => {
    const sent = answering([JSON.stringify(PAYROLL), 201]);
    const asked = {
      name: "Payroll",
      key: "payroll",
      subjectId: "00000002-0000-4000-8000-000000000501",
      picked: "somebody",
    };

    const created = await createGroup(asked, new AbortController().signal);

    expect(created).toEqual(PAYROLL);
    expect(addressAsked(sent).pathname).toBe("/api/groups");
    expect(sent.mock.calls[0]![1]!.method).toBe("POST");
    expect(JSON.parse(String(sent.mock.calls[0]![1]!.body))).toEqual({
      name: "Payroll",
      key: "payroll",
      subjectId: "00000002-0000-4000-8000-000000000501",
    });
  });

  it("carries the server's refusal of the key under the server's own code", async () => {
    answering(['{"code":"GROUP_KEY_TAKEN","detail":"d"}', 409]);

    const failure = await refusalOf(
      createGroup(
        { name: "Salaries", key: "PAYROLL", subjectId: "x" },
        new AbortController().signal,
      ),
    );

    expect(failure.problem).toEqual({
      status: 409,
      code: "GROUP_KEY_TAKEN",
      detail: "d",
    });
  });

  it("refuses an answer that is not a group, under the status it arrived with", async () => {
    answering([JSON.stringify({ ...PAYROLL, memberCount: null }), 201]);

    const failure = await refusalOf(
      createGroup(
        { name: "Payroll", key: "PAYROLL", subjectId: "x" },
        new AbortController().signal,
      ),
    );

    expect(failure.problem).toEqual({
      status: 201,
      code: NOT_A_PROBLEM_DOCUMENT,
    });
  });
});
