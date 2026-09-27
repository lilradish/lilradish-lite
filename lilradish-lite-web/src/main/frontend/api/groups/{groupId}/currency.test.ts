import { describe, expect, it } from "vitest";

import { answering, refusalOf } from "../../../testutil/answering";
import { NOT_A_PROBLEM_DOCUMENT, NOT_AN_ADDRESS } from "../../problem";
import { chooseCurrency, readCurrency } from "./currency";

const GROUP = "00000003-0000-4000-8000-000000000e51";

describe("readCurrency", () => {
  it("asks for the group's currency under the group's escaped segment", async () => {
    const sent = answering(["{}", 200]);

    await readCurrency(GROUP, new AbortController().signal);
    await readCurrency("a/b", new AbortController().signal);

    expect(sent.mock.calls.map(([address]) => address)).toEqual([
      `/api/groups/${GROUP}/currency`,
      "/api/groups/a%2Fb/currency",
    ]);
  });

  it("refuses a group that names no address, asking nothing", async () => {
    const sent = answering(["{}", 200]);

    const failure = await refusalOf(
      readCurrency("..", new AbortController().signal),
    );

    expect(failure.problem).toEqual({ code: NOT_AN_ADDRESS });
    expect(sent).not.toHaveBeenCalled();
  });

  it.each([
    [
      "a currency chosen and the ones offered",
      { chosen: "EUR", offered: ["EUR", "JPY"] },
    ],
    ["a currency chosen and none offered", { chosen: "EUR" }],
    ["none chosen and the ones offered", { offered: ["USD"] }],
    ["none chosen, and an offer of nothing", { offered: [] }],
    ["none chosen and none offered", {}],
  ])("reads %s, and leaves out what did not arrive", async (_case, body) => {
    answering([JSON.stringify(body), 200]);

    const read = await readCurrency(GROUP, new AbortController().signal);

    expect(read).toEqual(body);
    expect(Object.keys(read).sort()).toEqual(Object.keys(body).sort());
  });

  it.each([
    ["a code in small letters", { chosen: "eur" }],
    ["a code of four letters", { chosen: "EURO" }],
    ["a chosen currency sent as null", { chosen: null }],
    ["an offer that is no list", { offered: "EUR" }],
    ["an offer holding a code that is none", { offered: ["EUR", 978] }],
    ["no document at all", ["EUR"]],
  ])("refuses the whole answer holding %s", async (_case, body) => {
    answering([JSON.stringify(body), 200]);

    const failure = await refusalOf(
      readCurrency(GROUP, new AbortController().signal),
    );

    expect(failure.problem).toEqual({
      status: 200,
      code: NOT_A_PROBLEM_DOCUMENT,
    });
  });
});

describe("chooseCurrency", () => {
  it("puts the code and nothing else to the group's currency, and reads what it answers with", async () => {
    const sent = answering([
      JSON.stringify({ chosen: "JPY", offered: ["EUR", "JPY"] }),
      200,
    ]);

    const chosen = await chooseCurrency(
      GROUP,
      "JPY",
      new AbortController().signal,
    );

    const [address, init] = sent.mock.calls[0]!;
    expect(address).toBe(`/api/groups/${GROUP}/currency`);
    expect(init?.method).toBe("PUT");
    expect(String(init?.body)).toBe(JSON.stringify({ currency: "JPY" }));
    expect(chosen).toEqual({ chosen: "JPY", offered: ["EUR", "JPY"] });
  });

  it.each([
    ["CURRENCY_NOT_PRICED", 400],
    ["ACT_NOT_PERMITTED", 403],
    ["GROUP_NOT_IN_VIEW", 404],
  ])(
    "carries the server's refusal %s as its own code",
    async (code, status) => {
      answering([JSON.stringify({ code }), status]);

      const failure = await refusalOf(
        chooseCurrency(GROUP, "GBP", new AbortController().signal),
      );

      expect(failure.problem).toEqual({ status, code });
    },
  );
});
