import { describe, expect, it } from "vitest";

import { answering, refusalOf } from "../../../testutil/answering";
import { NOT_A_PROBLEM_DOCUMENT, NOT_AN_ADDRESS } from "../../problem";
import { offeredLoader } from "./offered-workflows";

const GROUP = "00000003-0000-4000-8000-000000000c31";

const COMPLAINT = {
  name: "complaint",
  kind: "text",
  longest: 2000,
  mustBeGiven: true,
};

const HANDLE = {
  entryId: "00000006-0000-4000-8000-000000000c31",
  name: "Handle a claim",
  purpose: "Sorts a claim out.",
  versions: [
    {
      versionId: "00000007-0000-4000-8000-000000000c32",
      number: 2,
      takes: [COMPLAINT],
    },
    {
      versionId: "00000007-0000-4000-8000-000000000c31",
      number: 1,
      takes: [],
    },
  ],
};

const REFUND = {
  entryId: "00000006-0000-4000-8000-000000000c32",
  name: "Pay a refund",
  versions: [
    {
      versionId: "00000007-0000-4000-8000-000000000c33",
      number: 4,
      takes: [],
    },
  ],
};

describe("offeredLoader", () => {
  it("reads what the group may start a run of at its address, in the order sent, a purpose only where one arrived", async () => {
    const sent = answering([
      JSON.stringify({ items: [HANDLE, REFUND], nextCursor: "later" }),
      200,
    ]);

    const offered = await offeredLoader(GROUP)(
      null,
      new AbortController().signal,
    );

    expect(sent.mock.calls[0]![0]).toBe(
      `/api/groups/${GROUP}/offered-workflows`,
    );
    expect(offered).toEqual({ items: [HANDLE, REFUND], nextCursor: "later" });
    expect(Object.keys(offered.items[1]!).toSorted()).toEqual(
      Object.keys(REFUND).toSorted(),
    );
  });

  it("reads each version in the order sent, with what it takes in declared order", async () => {
    answering([JSON.stringify({ items: [HANDLE] }), 200]);

    const [offered] = (
      await offeredLoader(GROUP)(null, new AbortController().signal)
    ).items;

    expect(offered!.versions.map((version) => version.number)).toEqual([2, 1]);
    expect(offered!.versions[0]!.takes).toEqual([COMPLAINT]);
    expect(offered!.versions[1]!.takes).toEqual([]);
  });

  it("hands the server the unaltered name filter and opaque cursor without a sort or limit", async () => {
    const sent = answering([JSON.stringify({ items: [REFUND] }), 200]);
    const load = offeredLoader(GROUP, "  A%_ß  ");
    const signal = new AbortController().signal;

    expect(await load(null, signal)).toEqual({ items: [REFUND] });
    expect(await load("next/page", signal)).toEqual({ items: [REFUND] });
    expect(sent.mock.calls.map(([input]) => String(input))).toEqual([
      `/api/groups/${GROUP}/offered-workflows?filter=++A%25_%C3%9F++`,
      `/api/groups/${GROUP}/offered-workflows?filter=++A%25_%C3%9F++&cursor=next%2Fpage`,
    ]);
    expect(sent.mock.calls.every(([, init]) => init?.signal === signal)).toBe(
      true,
    );
  });

  it.each([
    ["the old envelope", { workflows: [HANDLE] }],
    ["a null cursor", { items: [HANDLE], nextCursor: null }],
    ["a malformed cursor", { items: [HANDLE], nextCursor: 4 }],
  ])("refuses %s as an unreadable page", async (_case, body) => {
    answering([JSON.stringify(body), 200]);

    const failure = await refusalOf(
      offeredLoader(GROUP)(null, new AbortController().signal),
    );

    expect(failure.problem).toEqual({
      status: 200,
      code: NOT_A_PROBLEM_DOCUMENT,
    });
  });

  it("refuses a group that names no address, asking nothing", async () => {
    const sent = answering(['{"items":[]}', 200]);

    const failure = await refusalOf(
      offeredLoader("..")(null, new AbortController().signal),
    );

    expect(failure.problem).toEqual({ code: NOT_AN_ADDRESS });
    expect(sent).not.toHaveBeenCalled();
  });

  it.each([
    ["no list of workflows", {}],
    [
      "a workflow with no identifier",
      { items: [{ ...REFUND, entryId: undefined }] },
    ],
    ["a workflow with no name", { items: [{ ...REFUND, name: undefined }] }],
    ["a purpose that is no text", { items: [{ ...REFUND, purpose: 5 }] }],
    [
      "a workflow with no list of versions",
      { items: [{ ...REFUND, versions: undefined }] },
    ],
    [
      "a workflow with no version in service",
      { items: [{ ...REFUND, versions: [] }] },
    ],
    [
      "a version with no identifier",
      {
        items: [
          { ...REFUND, versions: [{ ...REFUND.versions[0], versionId: 7 }] },
        ],
      },
    ],
    [
      "a version numbered in no whole number",
      {
        items: [
          { ...REFUND, versions: [{ ...REFUND.versions[0], number: "4" }] },
        ],
      },
    ],
    [
      "a version saying nothing of what it takes",
      {
        items: [
          {
            ...REFUND,
            versions: [{ ...REFUND.versions[0], takes: undefined }],
          },
        ],
      },
    ],
    [
      "a field this side cannot draw",
      {
        items: [
          {
            ...REFUND,
            versions: [
              {
                ...REFUND.versions[0],
                takes: [{ ...COMPLAINT, kind: "colour" }],
              },
            ],
          },
        ],
      },
    ],
  ])(
    "refuses an answer holding %s, as a document this side cannot read",
    async (_case, body) => {
      answering([JSON.stringify(body), 200]);

      const failure = await refusalOf(
        offeredLoader(GROUP)(null, new AbortController().signal),
      );

      expect(failure.problem).toEqual({
        status: 200,
        code: NOT_A_PROBLEM_DOCUMENT,
      });
    },
  );
});
