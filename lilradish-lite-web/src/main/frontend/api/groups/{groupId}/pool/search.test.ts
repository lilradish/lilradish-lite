import { describe, expect, it } from "vitest";

import { answering, refusalOf } from "../../../../testutil/answering";
import { NOT_A_PROBLEM_DOCUMENT, NOT_AN_ADDRESS } from "../../../problem";
import { searchPoolOutside } from "./search";

const GROUP = "00000003-0000-4000-8000-0000000009a1";

const AVO = {
  subjectId: "00000002-0000-4000-8000-0000000009a1",
  userId: "0009a1",
  displayName: "Avo Lumerace",
};

const OLIVE = {
  subjectId: "00000002-0000-4000-8000-0000000009a2",
  userId: "0009a2",
  displayName: "Olive Lumerace",
};

describe("searchPoolOutside", () => {
  it("asks the pool's search inside the group with what was typed, as typed, and nothing else", async () => {
    const typed = " Gr%_\\&=+/#?ace ";
    const sent = answering(['{"items":[],"more":false}', 200]);

    await searchPoolOutside(GROUP, typed, new AbortController().signal);

    const asked = new URL(String(sent.mock.calls[0]![0]), "http://reader.test");
    expect(asked.pathname).toBe(`/api/groups/${GROUP}/pool/search`);
    expect([...asked.searchParams]).toEqual([["search", typed]]);
  });

  it("reads the matches as the pool's own search reads them", async () => {
    answering([JSON.stringify({ items: [AVO, OLIVE], more: true }), 200]);

    const found = await searchPoolOutside(
      GROUP,
      "avo",
      new AbortController().signal,
    );

    expect(found).toEqual({ items: [AVO, OLIVE], more: true });
  });

  it("refuses an answer the pool's own search would refuse", async () => {
    answering([JSON.stringify({ items: [AVO] }), 200]);

    const failure = await refusalOf(
      searchPoolOutside(GROUP, "avo", new AbortController().signal),
    );

    expect(failure.problem).toEqual({
      status: 200,
      code: NOT_A_PROBLEM_DOCUMENT,
    });
  });

  it("refuses a group that names no address, asking nothing", async () => {
    const sent = answering(['{"items":[],"more":false}', 200]);

    const failure = await refusalOf(
      searchPoolOutside("..", "avo", new AbortController().signal),
    );

    expect(failure.problem).toEqual({ code: NOT_AN_ADDRESS });
    expect(sent).not.toHaveBeenCalled();
  });
});
