import { describe, expect, it } from "vitest";

import { answering } from "../../../../testutil/answering";
import { detailFrom, readVersionDetail } from "./{versionId}";

const VERSION = {
  versionId: "pinned",
  name: "Categories",
  number: 1,
  versionName: null,
  standing: "retired",
  newer: { versionId: "newest", number: 2, versionName: "New 📦" },
};
const TERM = {
  fieldId: "f",
  name: "category",
  kind: "term",
  many: false,
  mustBeGiven: true,
  list: VERSION,
};

describe("independent version detail", () => {
  it("reads a retired reference pin without requiring a candidate page or fabricating halves", async () => {
    const body = {
      kind: "reference_list",
      version: VERSION,
      terms: { pinned: ["later", "urgent"] },
    };
    const sent = answering([JSON.stringify(body), 200]);
    expect(
      await readVersionDetail("group", "pinned", new AbortController().signal),
    ).toEqual({ ...body, terms: new Map([["pinned", ["later", "urgent"]]]) });
    expect(sent.mock.calls[0]![0]).toBe("/api/groups/group/versions/pinned");
    expect(sent.mock.calls[0]![1]?.method ?? "GET").toBe("GET");
  });

  it("holds recursive pinned terms in order with both complete workflow halves", () => {
    const body = {
      kind: "workflow",
      version: VERSION,
      takes: [
        {
          fieldId: "block",
          name: "block",
          kind: "fields",
          many: false,
          mustBeGiven: true,
          fields: [TERM],
        },
      ],
      gives: [],
      terms: { pinned: ["urgent", "later"] },
    };
    expect(detailFrom(body)).toEqual({
      ...body,
      terms: new Map([["pinned", ["urgent", "later"]]]),
    });
    expect(detailFrom({ ...body, terms: {} })).toBeNull();
    expect(detailFrom({ ...body, gives: undefined })).toBeNull();
    expect(detailFrom({ ...body, searchMatches: false })).toBeNull();
  });

  it.each([undefined, null, "false"])(
    "requires a real boolean question qualification, got %j",
    (searchMatches) => {
      expect(
        detailFrom({
          kind: "question",
          version: VERSION,
          takes: [],
          gives: [],
          terms: {},
          searchMatches,
        }),
      ).toBeNull();
    },
  );

  it("distinguishes an ordinary question from a matching search question", () => {
    const body = {
      kind: "question",
      version: VERSION,
      takes: [],
      gives: [],
      terms: {},
      searchMatches: false,
    };
    expect(detailFrom(body)).toEqual({ ...body, terms: new Map() });
    expect(detailFrom({ ...body, searchMatches: true })).toBeNull();
    expect(
      detailFrom({ ...body, searchMatches: true, searchWay: "web" }),
    ).toEqual({
      ...body,
      searchMatches: true,
      searchWay: "web",
      terms: new Map(),
    });
  });

  it.each([
    { takes: [] },
    { gives: [] },
    { searchWay: "web" },
    { searchMatches: false },
    { terms: {} },
    { version: { ...VERSION, versionName: undefined } },
  ])("rejects an incomplete or invented reference detail %j", (change) => {
    expect(
      detailFrom({
        kind: "reference_list",
        version: VERSION,
        terms: { pinned: ["urgent"] },
        ...change,
      }),
    ).toBeNull();
  });
});
