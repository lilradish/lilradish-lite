import { describe, expect, it, vi } from "vitest";

import { NOT_AN_ADDRESS } from "../../api/problem";
import { refusalOf } from "../../testutil/answering";
import { atSegment } from "./address";

describe("atSegment", () => {
  it("hands the address to what asks, and answers with what it answered", async () => {
    const ask = vi.fn((address: string) => Promise.resolve(`asked ${address}`));

    await expect(atSegment("/api/things", "7", ask)).resolves.toBe(
      "asked /api/things/7",
    );
    expect(ask).toHaveBeenCalledOnce();
  });

  /** Escaping leaves both as they are, and the address would resolve them away before it was sent. */
  it.each([".", ".."])("refuses %o here, asking nothing", async (id) => {
    const ask = vi.fn(() => Promise.resolve("asked"));

    const failure = await refusalOf(atSegment("/api/things", id, ask));

    expect(failure.problem).toEqual({ code: NOT_AN_ADDRESS });
    expect(ask).not.toHaveBeenCalled();
  });

  /** Anything else is the server's to judge, as one escaped segment so nothing in it reaches another address. */
  it.each([
    ["../x", "/api/things/..%2Fx"],
    ["a/b?c#d", "/api/things/a%2Fb%3Fc%23d"],
    ["2-0-4000-8000-14c", "/api/things/2-0-4000-8000-14c"],
  ])("hands on %o as the single segment %s", async (id, asked) => {
    const ask = vi.fn((sentTo: string) => Promise.resolve(sentTo));

    await expect(atSegment("/api/things", id, ask)).resolves.toBe(asked);
  });
});
