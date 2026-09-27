import { describe, expect, it } from "vitest";

import { answering, refusalOf } from "../testutil/answering";
import { searchDirectory } from "./people";
import { NOT_A_PROBLEM_DOCUMENT } from "./problem";

function addressAsked(sent: ReturnType<typeof answering>): URL {
  return new URL(String(sent.mock.calls[0]![0]), "http://reader.test");
}

const ADA = { userId: "000130", displayName: "Ada Lovelace" };

const GRACE = {
  userId: "000140",
  displayName: "Grace Hopper",
  subjectId: "00000002-0000-4000-8000-000000000140",
};

describe("searchDirectory", () => {
  /**
   * Whatever would mean something in a query string arrives as itself once
   * the parameter is decoded, spaces at either end included.
   */
  it("asks the directory's own address with what was typed, as typed, and nothing else", async () => {
    const typed = " Gr%_\\&=+/#?ace ";
    const sent = answering(['{"items":[],"more":false}', 200]);

    await searchDirectory(typed, new AbortController().signal);

    const asked = addressAsked(sent);
    expect(asked.pathname).toBe("/api/people");
    expect([...asked.searchParams]).toEqual([["search", typed]]);
  });

  it("reads each match, and who of them is already in the pool", async () => {
    answering([JSON.stringify({ items: [ADA, GRACE], more: true }), 200]);

    const found = await searchDirectory("a", new AbortController().signal);

    expect(found).toEqual({ items: [ADA, GRACE], more: true });
    expect("subjectId" in found.items[0]!).toBe(false);
  });

  it("carries no member of a match it was not built to read", async () => {
    answering([
      JSON.stringify({
        items: [{ ...ADA, department: "Analytical Engines" }],
        more: false,
      }),
      200,
    ]);

    const found = await searchDirectory("ada", new AbortController().signal);

    expect(Object.keys(found.items[0]!).sort()).toEqual([
      "displayName",
      "userId",
    ]);
  });

  /** Nothing stands in for a name the directory does not hold. */
  it.each([
    ["out of the pool", { userId: "000150" }],
    [
      "in the pool",
      { userId: "000160", subjectId: "00000002-0000-4000-8000-000000000160" },
    ],
  ])("reads a match with no name as having none, %s", async (_, nameless) => {
    answering([JSON.stringify({ items: [nameless], more: false }), 200]);

    const found = await searchDirectory(
      nameless.userId,
      new AbortController().signal,
    );

    expect(found).toEqual({ items: [nameless], more: false });
    expect("displayName" in found.items[0]!).toBe(false);
  });

  it.each([
    ["no word on whether there are more", { items: [ADA] }],
    ["more that is not a yes or a no", { items: [ADA], more: "yes" }],
    ["no list of matches", { more: false }],
    [
      "a match with no user number",
      { items: [{ displayName: "Ada" }], more: false },
    ],
    [
      "a name that is not text",
      { items: [{ ...ADA, displayName: null }], more: false },
    ],
    [
      "a name that is empty",
      { items: [{ ...ADA, displayName: "" }], more: false },
    ],
    [
      "a pool address that is not text",
      { items: [{ ...GRACE, subjectId: null }], more: false },
    ],
    ["a match that is no document", { items: ["000130"], more: false }],
  ])("refuses an answer carrying %s", async (_, body) => {
    answering([JSON.stringify(body), 200]);

    const failure = await refusalOf(
      searchDirectory("a", new AbortController().signal),
    );

    expect(failure.problem).toEqual({
      status: 200,
      code: NOT_A_PROBLEM_DOCUMENT,
    });
  });

  it("carries the server's refusal of what was typed as the server's own code", async () => {
    answering(['{"code":"PEOPLE_SEARCH_UNUSABLE","detail":"d"}', 400]);

    const failure = await refusalOf(
      searchDirectory("\u0007", new AbortController().signal),
    );

    expect(failure.problem).toEqual({
      status: 400,
      code: "PEOPLE_SEARCH_UNUSABLE",
      detail: "d",
    });
  });
});
