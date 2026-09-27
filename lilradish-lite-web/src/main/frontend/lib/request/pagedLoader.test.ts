import { describe, expect, it } from "vitest";

import { NOT_A_PROBLEM_DOCUMENT } from "../../api/problem";
import { answering, refusalOf } from "../../testutil/answering";
import { pagedLoader, pagedLoaderSaying } from "./pagedLoader";

/** A row of no list in particular: a word, or nothing a page may hold. */
function wordFrom(row: unknown): string | null {
  return typeof row === "string" ? row : null;
}

function addressAsked(sent: ReturnType<typeof answering>): URL {
  return new URL(String(sent.mock.calls[0]![0]), "http://reader.test");
}

describe("pagedLoader", () => {
  it("asks the list's address with the order alone where nothing narrows it and nothing came before", async () => {
    const sent = answering(['{"items":[]}', 200]);

    await pagedLoader("/api/things", wordFrom, { filter: "", order: "name" })(
      null,
      new AbortController().signal,
    );

    expect(addressAsked(sent).pathname).toBe("/api/things");
    expect([...addressAsked(sent).searchParams]).toEqual([["sort", "name"]]);
  });

  it("sends what was typed, the order and the cursor as they are, each under its own parameter", async () => {
    const typed = " Pay%_\\&=+/#?roll ";
    const sent = answering(['{"items":[]}', 200]);

    await pagedLoader("/api/things", wordFrom, {
      filter: typed,
      order: "-name",
    })("AAEC_-", new AbortController().signal);

    expect([...addressAsked(sent).searchParams]).toEqual([
      ["sort", "-name"],
      ["filter", typed],
      ["cursor", "AAEC_-"],
    ]);
  });

  it("reads each row as the list reads one, and where the next page begins", async () => {
    answering(['{"items":["a","b"],"nextCursor":"AAEC"}', 200]);

    const page = await pagedLoader("/api/things", wordFrom, {
      filter: "",
      order: "name",
    })(null, new AbortController().signal);

    expect(page).toEqual({ items: ["a", "b"], nextCursor: "AAEC" });
  });

  it("reads the last page as having no cursor, rather than a null one", async () => {
    answering(['{"items":["a"]}', 200]);

    const page = await pagedLoader("/api/things", wordFrom, {
      filter: "",
      order: "name",
    })(null, new AbortController().signal);

    expect("nextCursor" in page).toBe(false);
  });

  /** A list with a hole in it is not the list, so the whole page is refused. */
  it.each([
    ["no list of rows", { nextCursor: "AAEC" }],
    ["a row the list cannot read", { items: ["a", 7] }],
    ["a cursor that is null", { items: [], nextCursor: null }],
    ["no document at all", ["a"]],
  ])("refuses a page carrying %s", async (_, body) => {
    answering([JSON.stringify(body), 200]);

    const failure = await refusalOf(
      pagedLoader("/api/things", wordFrom, { filter: "", order: "name" })(
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

/** Said beside the rows: a tone, where the document carries a word for one. */
function toneFrom(body: Readonly<Record<string, unknown>>) {
  return typeof body.tone === "string" ? { tone: body.tone } : null;
}

describe("pagedLoaderSaying", () => {
  it("reads what a page says beside its rows, and the rows and cursor as any page's", async () => {
    answering([
      JSON.stringify({ items: ["a"], nextCursor: "AAEC", tone: "calm" }),
      200,
    ]);

    const page = await pagedLoaderSaying(
      "/api/things",
      wordFrom,
      { filter: "", order: "name" },
      toneFrom,
    )(null, new AbortController().signal);

    expect(page).toEqual({ items: ["a"], nextCursor: "AAEC", tone: "calm" });
  });

  it.each([
    ["nothing beside its rows", { items: ["a"] }],
    [
      "rows it cannot read, however well it says the rest",
      { items: [7], tone: "calm" },
    ],
  ])("refuses a page carrying %s", async (_, body) => {
    answering([JSON.stringify(body), 200]);

    const failure = await refusalOf(
      pagedLoaderSaying(
        "/api/things",
        wordFrom,
        { filter: "", order: "name" },
        toneFrom,
      )(null, new AbortController().signal),
    );

    expect(failure.problem).toEqual({
      status: 200,
      code: NOT_A_PROBLEM_DOCUMENT,
    });
  });
});
