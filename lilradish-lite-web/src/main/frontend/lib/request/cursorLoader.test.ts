import { describe, expect, it } from "vitest";

import { NOT_A_PROBLEM_DOCUMENT, RequestFailed } from "../../api/problem";
import { answering, refusalOf } from "../../testutil/answering";
import { cursorLoader } from "./cursorLoader";

function wordFrom(row: unknown): string | null {
  return typeof row === "string" ? row : null;
}

describe("cursorLoader", () => {
  it("asks the first page without a cursor or an unrequested query", async () => {
    const sent = answering([JSON.stringify({ items: ["Demo Sample"] }), 200]);
    const signal = new AbortController().signal;

    const page = await cursorLoader("/api/offered", wordFrom)(null, signal);

    expect(page).toEqual({ items: ["Demo Sample"] });
    expect(sent.mock.calls).toHaveLength(1);
    expect(sent.mock.calls[0]?.[0]).toBe("/api/offered");
    expect(sent.mock.calls[0]?.[1]).toMatchObject({ signal });
  });

  it("keeps the typed query and opaque cursor separate without changing the caller's query", async () => {
    const typed = " Pay%_\\&=+/#?roll ";
    const query = new URLSearchParams({ filter: typed });
    const sent = answering([
      JSON.stringify({ items: ["first", "second"], nextCursor: "after" }),
      200,
    ]);

    const page = await cursorLoader(
      "/api/offered",
      wordFrom,
      query,
    )("AAEC_-", new AbortController().signal);

    const asked = new URL(
      String(sent.mock.calls[0]?.[0]),
      "http://reader.test",
    );
    expect([...asked.searchParams]).toEqual([
      ["filter", typed],
      ["cursor", "AAEC_-"],
    ]);
    expect(query.toString()).toBe(
      new URLSearchParams({ filter: typed }).toString(),
    );
    expect(page).toEqual({ items: ["first", "second"], nextCursor: "after" });
  });

  it("does not let a caller-supplied cursor move the first page", async () => {
    const sent = answering([JSON.stringify({ items: [] }), 200]);

    await cursorLoader(
      "/api/offered",
      wordFrom,
      new URLSearchParams({ filter: "Demo Sample", cursor: "old" }),
    )(null, new AbortController().signal);

    const asked = new URL(
      String(sent.mock.calls[0]?.[0]),
      "http://reader.test",
    );
    expect([...asked.searchParams]).toEqual([["filter", "Demo Sample"]]);
  });

  it.each([
    ["no items", { nextCursor: "after" }],
    ["a bad item", { items: ["readable", 7] }],
    ["a null cursor", { items: ["readable"], nextCursor: null }],
    ["a non-document", ["readable"]],
  ])("refuses the whole page with %s", async (_, body) => {
    answering([JSON.stringify(body), 200]);

    const failure = await refusalOf(
      cursorLoader("/api/offered", wordFrom)(
        null,
        new AbortController().signal,
      ),
    );

    expect(failure.problem).toEqual({
      status: 200,
      code: NOT_A_PROBLEM_DOCUMENT,
    });
  });

  it("passes a served refusal without turning it into a bad page", async () => {
    answering(['{"code":"NO_VIEW"}', 403]);

    const failure = await refusalOf(
      cursorLoader("/api/offered", wordFrom)(
        null,
        new AbortController().signal,
      ),
    );

    expect(failure).toBeInstanceOf(RequestFailed);
    expect(failure.problem).toEqual({ status: 403, code: "NO_VIEW" });
  });
});
