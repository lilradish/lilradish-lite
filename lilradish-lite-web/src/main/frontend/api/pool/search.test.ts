import { describe, expect, it } from "vitest";

import { answering, refusalOf } from "../../testutil/answering";
import { NOT_A_PROBLEM_DOCUMENT } from "../problem";
import { searchPool } from "./search";

const ADA = {
  subjectId: "00000002-0000-4000-8000-000000000130",
  userId: "000130",
  displayName: "Ada Lovelace",
};

const NAMELESS = {
  subjectId: "00000002-0000-4000-8000-000000000150",
  userId: "000150",
};

describe("searchPool", () => {
  it("asks the pool's search with what was typed, as typed, and nothing else", async () => {
    const typed = " Gr%_\\&=+/#?ace ";
    const sent = answering(['{"items":[],"more":false}', 200]);

    await searchPool(typed, new AbortController().signal);

    const asked = new URL(String(sent.mock.calls[0]![0]), "http://reader.test");
    expect(asked.pathname).toBe("/api/pool/search");
    expect([...asked.searchParams]).toEqual([["search", typed]]);
  });

  /** Nothing stands in for a name the pool does not hold, and nothing anybody holds is read. */
  it("reads each match by who they are, a name only where one is held, and nothing else", async () => {
    answering([
      JSON.stringify({
        items: [{ ...ADA, estateRoles: ["steward"] }, NAMELESS],
        more: true,
      }),
      200,
    ]);

    const found = await searchPool("a", new AbortController().signal);

    expect(found).toEqual({ items: [ADA, NAMELESS], more: true });
    expect("displayName" in found.items[1]!).toBe(false);
    expect(Object.keys(found.items[0]!).sort()).toEqual([
      "displayName",
      "subjectId",
      "userId",
    ]);
  });

  it.each([
    ["no word on whether there are more", { items: [ADA] }],
    ["more that is not a yes or a no", { items: [ADA], more: "yes" }],
    ["no list of matches", { more: false }],
    [
      "a match with no address",
      { items: [{ ...ADA, subjectId: null }], more: false },
    ],
    [
      "a match with no user number",
      { items: [{ ...ADA, userId: 130 }], more: false },
    ],
    [
      "a name that is not text",
      { items: [{ ...ADA, displayName: null }], more: false },
    ],
    [
      "a name that is empty",
      { items: [{ ...ADA, displayName: "" }], more: false },
    ],
    ["a match that is no document", { items: ["000130"], more: false }],
  ])("refuses an answer carrying %s", async (_, body) => {
    answering([JSON.stringify(body), 200]);

    const failure = await refusalOf(
      searchPool("a", new AbortController().signal),
    );

    expect(failure.problem).toEqual({
      status: 200,
      code: NOT_A_PROBLEM_DOCUMENT,
    });
  });

  it("carries the server's refusal of what was typed as the server's own code", async () => {
    answering(['{"code":"PEOPLE_SEARCH_UNUSABLE","detail":"d"}', 400]);

    const failure = await refusalOf(
      searchPool("\u0007", new AbortController().signal),
    );

    expect(failure.problem).toEqual({
      status: 400,
      code: "PEOPLE_SEARCH_UNUSABLE",
      detail: "d",
    });
  });
});
