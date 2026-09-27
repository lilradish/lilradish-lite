import { act, renderHook, waitFor } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import { usePagedResource } from "../../lib/request/usePagedResource";
import { answering, refusalOf } from "../../testutil/answering";
import { NOT_A_PROBLEM_DOCUMENT } from "../problem";
import { bringIn, panelFrom, peopleLoader, type PoolQuery } from "./people";

function addressAsked(sent: ReturnType<typeof answering>, call = 0): URL {
  return new URL(String(sent.mock.calls[call]![0]), "http://reader.test");
}

const EVERYBODY: PoolQuery = { filter: "", order: "userId" };

const GRACE = {
  subjectId: "00000002-0000-4000-8000-000000000140",
  userId: "000140",
  displayName: "Grace Hopper",
  estateRoles: ["steward", "watcher"],
  groupCount: 2,
};

describe("peopleLoader", () => {
  /**
   * The order is sent even when it is the one the server would fall back to,
   * so which column the table is sorted by is always what it asked.
   */
  it("asks the pool's own address with the order it wants and nothing else when nothing narrows it", async () => {
    const sent = answering(['{"items":[]}', 200]);

    await peopleLoader(EVERYBODY)(null, new AbortController().signal);

    const asked = addressAsked(sent);
    expect(asked.pathname).toBe("/api/pool/people");
    expect([...asked.searchParams]).toEqual([["sort", "userId"]]);
  });

  /**
   * What a reader typed reaches the server as typed: a space at either end, and
   * every character that would mean something in a query string or a pattern,
   * arrive as themselves once the parameter is decoded.
   */
  it("sends what was typed, the order and the cursor as they are, each under its own parameter", async () => {
    const typed = " Gr%_\\&=+/#?ace ";
    const sent = answering(['{"items":[]}', 200]);

    await peopleLoader({ filter: typed, order: "-groupCount" })(
      "AAEC_-",
      new AbortController().signal,
    );

    const asked = addressAsked(sent);
    expect(asked.searchParams.getAll("filter")).toEqual([typed]);
    expect(asked.searchParams.getAll("sort")).toEqual(["-groupCount"]);
    expect(asked.searchParams.getAll("cursor")).toEqual(["AAEC_-"]);
    expect([...asked.searchParams.keys()].sort()).toEqual([
      "cursor",
      "filter",
      "sort",
    ]);
  });

  it("reads each row under its own members, a name only where one is held", async () => {
    answering([
      JSON.stringify({
        items: [
          GRACE,
          {
            subjectId: "00000002-0000-4000-8000-000000000130",
            userId: "000130",
            estateRoles: [],
            groupCount: 0,
          },
        ],
      }),
      200,
    ]);

    const page = await peopleLoader(EVERYBODY)(
      null,
      new AbortController().signal,
    );

    expect(page.items).toEqual([
      { ...GRACE, estateRoles: new Set(["steward", "watcher"]) },
      {
        subjectId: "00000002-0000-4000-8000-000000000130",
        userId: "000130",
        estateRoles: new Set(),
        groupCount: 0,
      },
    ]);
    expect("displayName" in page.items[1]!).toBe(false);
    expect("nextCursor" in page).toBe(false);
  });

  it("keeps where the next page begins, and a role this build does not know, and drops members it does not read", async () => {
    answering([
      JSON.stringify({
        items: [
          {
            ...GRACE,
            estateRoles: ["auditor"],
            createdBy: "00000000-0000-4000-8000-000000000000",
          },
        ],
        nextCursor: "AAEC_-",
      }),
      200,
    ]);

    const page = await peopleLoader(EVERYBODY)(
      null,
      new AbortController().signal,
    );

    expect(page.nextCursor).toBe("AAEC_-");
    expect(page.items[0]!.estateRoles).toEqual(new Set(["auditor"]));
    expect(Object.keys(page.items[0]!).sort()).toEqual([
      "displayName",
      "estateRoles",
      "groupCount",
      "subjectId",
      "userId",
    ]);
  });

  /**
   * A row missing what identifies it cannot be shown without a hole, and a
   * cursor that is null would either end the list early or ask past its end —
   * so each is the whole document refused, under the status it arrived with.
   */
  it.each([
    ["no list of rows", { nextCursor: "AAEC" }],
    ["a row with no user number", { items: [{ ...GRACE, userId: undefined }] }],
    [
      "a count that is not a number",
      { items: [{ ...GRACE, groupCount: "2" }] },
    ],
    ["a count that is not whole", { items: [{ ...GRACE, groupCount: 1.5 }] }],
    ["a negative count", { items: [{ ...GRACE, groupCount: -1 }] }],
    ["a row listing no roles", { items: [{ ...GRACE, estateRoles: null }] }],
    ["a name that is not text", { items: [{ ...GRACE, displayName: null }] }],
    ["a cursor that is null", { items: [], nextCursor: null }],
    ["a row that is no document", { items: ["000140"] }],
  ])("refuses a page carrying %s", async (_, body) => {
    answering([JSON.stringify(body), 200]);

    const failure = await refusalOf(
      peopleLoader(EVERYBODY)(null, new AbortController().signal),
    );

    expect(failure.problem).toEqual({
      status: 200,
      code: NOT_A_PROBLEM_DOCUMENT,
    });
  });

  it("carries the server's refusal of a cursor as the server's own code", async () => {
    answering(['{"code":"LIST_CURSOR_UNUSABLE","detail":"d"}', 400]);

    const failure = await refusalOf(
      peopleLoader(EVERYBODY)("AAEC", new AbortController().signal),
    );

    expect(failure.problem).toEqual({
      status: 400,
      code: "LIST_CURSOR_UNUSABLE",
      detail: "d",
    });
  });

  /**
   * The loader is what the paged read is keyed on, so it has to be one value
   * per query — held here in a constant, as a screen would hold it in its own
   * memo — and the cursor the first page carried is what the second is asked
   * with.
   */
  it("reads the pool a page at a time, asking for each page with the cursor the one before it carried", async () => {
    const sent = answering(
      [JSON.stringify({ items: [GRACE], nextCursor: "AAEC" }), 200],
      ['{"items":[]}', 200],
    );
    const load = peopleLoader({ filter: "grace", order: "displayName" });
    const { result } = renderHook(() => usePagedResource(load));
    await waitFor(() => expect(result.current.hasNext).toBe(true));

    act(() => {
      result.current.next();
    });
    await waitFor(() => expect(result.current.onFirstPage).toBe(false));
    await waitFor(() => expect(result.current.loading).toBe(false));

    expect(addressAsked(sent, 0).searchParams.has("cursor")).toBe(false);
    expect(addressAsked(sent, 1).searchParams.get("cursor")).toBe("AAEC");
    expect(addressAsked(sent, 1).searchParams.get("filter")).toBe("grace");
    expect(addressAsked(sent, 1).searchParams.get("sort")).toBe("displayName");
    expect(result.current.items).toEqual([]);
    expect(result.current.hasNext).toBe(false);
  });
});

const BROUGHT_IN = {
  subjectId: "00000002-0000-4000-8000-000000000140",
  userId: "000140",
  displayName: "Grace Hopper",
  estateRoles: [],
  lastGrantingRoles: [],
  groups: [],
  seeded: false,
};

describe("bringIn", () => {
  it("names the person to the pool's own address by their user number, and nothing besides", async () => {
    const sent = answering([JSON.stringify(BROUGHT_IN), 201]);

    await bringIn(" 000140", new AbortController().signal);

    const [address, init] = sent.mock.calls[0]!;
    expect(address).toBe("/api/pool/people");
    expect(init?.method).toBe("POST");
    expect(JSON.parse(String(init?.body))).toEqual({ userId: " 000140" });
  });

  it("reads the person the pool now holds, in no group and holding nothing", async () => {
    answering([JSON.stringify(BROUGHT_IN), 201]);

    const person = await bringIn("000140", new AbortController().signal);

    expect(person).toEqual({
      ...BROUGHT_IN,
      estateRoles: new Set(),
      lastGrantingRoles: new Set(),
    });
  });

  it("refuses an answer that is not a person, under the status it arrived with", async () => {
    answering([JSON.stringify({ ...BROUGHT_IN, groups: null }), 201]);

    const failure = await refusalOf(
      bringIn("000140", new AbortController().signal),
    );

    expect(failure.problem).toEqual({
      status: 201,
      code: NOT_A_PROBLEM_DOCUMENT,
    });
  });

  it.each([
    ["USER_NOT_IN_DIRECTORY", 400],
    ["PERSON_ALREADY_IN_POOL", 409],
  ])(
    "carries the server's refusal %s as its own code",
    async (code, status) => {
      answering([JSON.stringify({ code, detail: "d" }), status]);

      const failure = await refusalOf(
        bringIn("000140", new AbortController().signal),
      );

      expect(failure.problem).toEqual({ status, code, detail: "d" });
    },
  );
});

const PANEL = {
  subjectId: "00000002-0000-4000-8000-00000000014c",
  userId: "000140",
  displayName: "Grace Hopper",
  estateRoles: ["watcher", "steward"],
  lastGrantingRoles: ["steward"],
  groups: ["Payroll", "finance"],
  seeded: true,
};

describe("panelFrom", () => {
  it("reads what they hold, what of it nobody else could grant without, the groups they are in by name, and whether a migration seeded them", () => {
    expect(panelFrom(PANEL)).toEqual({
      ...PANEL,
      estateRoles: new Set(["watcher", "steward"]),
      lastGrantingRoles: new Set(["steward"]),
    });
  });

  /**
   * Built member by member, so whatever the server might add beside these —
   * what somebody holds inside a group above all — never reaches a screen.
   */
  it("leaves a name out where none is held, and carries no member it was not built to read", () => {
    const { displayName: _, ...nameless } = PANEL;

    const person = panelFrom({
      ...nameless,
      seeded: false,
      groupRoles: { Payroll: "owner" },
    });

    expect(Object.keys(person!).sort()).toEqual([
      "estateRoles",
      "groups",
      "lastGrantingRoles",
      "seeded",
      "subjectId",
      "userId",
    ]);
    expect(person!.seeded).toBe(false);
  });

  it.each([
    ["a group that is not named", { ...PANEL, groups: ["Payroll", 7] }],
    ["no list of groups", { ...PANEL, groups: "Payroll" }],
    ["no word on whether it was seeded", { ...PANEL, seeded: "yes" }],
    ["no user number", { ...PANEL, userId: undefined }],
    ["no identifier", { ...PANEL, subjectId: 14 }],
    ["no list of roles", { ...PANEL, estateRoles: null }],
    [
      "no list of what nobody else could grant without",
      { ...PANEL, lastGrantingRoles: undefined },
    ],
    ["a name that is not text", { ...PANEL, displayName: 7 }],
    ["no document at all", ["000140"]],
    ["nothing at all", undefined],
  ])("refuses a person carrying %s", (_, body) => {
    expect(panelFrom(body)).toBeNull();
  });
});
