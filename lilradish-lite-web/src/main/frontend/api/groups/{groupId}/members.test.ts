import { describe, expect, it } from "vitest";

import { answering, refusalOf } from "../../../testutil/answering";
import { NOT_A_PROBLEM_DOCUMENT, NOT_AN_ADDRESS } from "../../problem";
import { atMembers, bringIn, membersLoader, panelFrom } from "./members";

const GROUP = "00000003-0000-4000-8000-000000000971";

const AVO = {
  subjectId: "00000002-0000-4000-8000-000000000971",
  userId: "000971",
  displayName: "Avo Lumerace",
  roles: ["owner", "operator"],
};

const NAMELESS = {
  subjectId: "00000002-0000-4000-8000-000000000972",
  userId: "000972",
  roles: ["overseer"],
};

const AVO_PANEL = { ...AVO, lastChangingRoles: ["owner"], removable: false };

describe("atMembers", () => {
  it("hands over the members' address under the group's escaped segment", async () => {
    const addresses = [GROUP, "a/b"].map((group) =>
      atMembers(group, (address) => Promise.resolve(address)),
    );

    expect(await Promise.all(addresses)).toEqual([
      `/api/groups/${GROUP}/members`,
      "/api/groups/a%2Fb/members",
    ]);
  });

  it("refuses a group that names no address, asking nothing", async () => {
    const failure = await refusalOf(
      atMembers("..", () => Promise.resolve("asked")),
    );

    expect(failure.problem).toEqual({ code: NOT_AN_ADDRESS });
  });
});

describe("membersLoader", () => {
  it("asks for the group's members in the order and with the filter asked, from where the page left off", async () => {
    const sent = answering(['{"items":[]}', 200]);

    await membersLoader(GROUP, { filter: "", order: "userId" })(
      null,
      new AbortController().signal,
    );
    await membersLoader(GROUP, { filter: "Avo", order: "-displayName" })(
      "c1",
      new AbortController().signal,
    );

    expect(sent.mock.calls.map(([address]) => address)).toEqual([
      `/api/groups/${GROUP}/members?sort=userId`,
      `/api/groups/${GROUP}/members?sort=-displayName&filter=Avo&cursor=c1`,
    ]);
  });

  it("reads every member with every role they hold, and a name only where one arrived", async () => {
    answering([
      JSON.stringify({ items: [AVO, NAMELESS], nextCursor: "c2" }),
      200,
    ]);

    const page = await membersLoader(GROUP, { filter: "", order: "userId" })(
      null,
      new AbortController().signal,
    );

    expect(page).toEqual({
      items: [
        { ...AVO, roles: new Set(["owner", "operator"]) },
        { ...NAMELESS, roles: new Set(["overseer"]) },
      ],
      nextCursor: "c2",
    });
    expect("displayName" in page.items[1]!).toBe(false);
  });

  it.each([
    ["no roles", { ...AVO, roles: undefined }],
    ["a name that is no text", { ...AVO, displayName: null }],
    ["no user number", { ...AVO, userId: 7 }],
  ])("refuses a whole page holding a member with %s", async (_case, member) => {
    answering([JSON.stringify({ items: [NAMELESS, member] }), 200]);

    const failure = await refusalOf(
      membersLoader(GROUP, { filter: "", order: "userId" })(
        null,
        new AbortController().signal,
      ),
    );

    expect(failure.problem).toEqual({
      status: 200,
      code: NOT_A_PROBLEM_DOCUMENT,
    });
  });
});

describe("bringIn", () => {
  it("sends the person and the roles, and nothing else, to the group's members", async () => {
    const sent = answering([JSON.stringify(AVO_PANEL), 201]);

    await bringIn(
      GROUP,
      AVO.subjectId,
      ["operator", "owner"],
      new AbortController().signal,
    );

    const [address, init] = sent.mock.calls[0]!;
    expect(address).toBe(`/api/groups/${GROUP}/members`);
    expect(init?.method).toBe("POST");
    expect(JSON.parse(String(init?.body))).toEqual({
      subjectId: AVO.subjectId,
      roles: ["operator", "owner"],
    });
  });

  it("reads the member as the group now holds them", async () => {
    answering([JSON.stringify(AVO_PANEL), 201]);

    const brought = await bringIn(
      GROUP,
      AVO.subjectId,
      ["owner"],
      new AbortController().signal,
    );

    expect(brought).toEqual({
      ...AVO,
      roles: new Set(["owner", "operator"]),
      lastChangingRoles: new Set(["owner"]),
      removable: false,
    });
  });

  it.each([
    ["PERSON_ALREADY_IN_GROUP", 409],
    ["PERSON_NOT_IN_POOL", 400],
    ["ACT_NOT_PERMITTED", 403],
  ])(
    "carries the server's refusal %s as its own code",
    async (code, status) => {
      answering([JSON.stringify({ code }), status]);

      const failure = await refusalOf(
        bringIn(GROUP, AVO.subjectId, ["owner"], new AbortController().signal),
      );

      expect(failure.problem).toEqual({ status, code });
    },
  );
});

describe("panelFrom", () => {
  it("reads somebody holding nothing here as holding nothing, rather than as nobody", () => {
    expect(
      panelFrom({
        ...NAMELESS,
        roles: [],
        lastChangingRoles: [],
        removable: true,
      }),
    ).toEqual({
      ...NAMELESS,
      roles: new Set(),
      lastChangingRoles: new Set(),
      removable: true,
    });
  });

  it.each([
    ["no list of the last roles", { ...AVO, removable: true }],
    [
      "a list of the last roles that is none",
      { ...AVO, lastChangingRoles: "owner", removable: true },
    ],
    ["no word on removal", { ...AVO, lastChangingRoles: [] }],
    [
      "a word on removal that is no yes or no",
      { ...AVO, lastChangingRoles: [], removable: "yes" },
    ],
    ["no document at all", "Avo"],
  ])("refuses %s", (_case, body) => {
    expect(panelFrom(body)).toBeNull();
  });
});
