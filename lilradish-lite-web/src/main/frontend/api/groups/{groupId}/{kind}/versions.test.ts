import { describe, expect, it } from "vitest";

import { answering } from "../../../../testutil/answering";
import { candidatesFrom, findVersions } from "./versions";

const FIRST = {
  versionId: "first",
  name: "Straße",
  number: 3,
  versionName: "Current 📦",
};

describe("editor version candidates", () => {
  it("reads one bounded page in its server order, including a visible incompatible question", async () => {
    const body = {
      items: [{ ...FIRST, searchWay: "web", searchMatches: false }],
      more: true,
    };
    const sent = answering([JSON.stringify(body), 200]);
    const page = await findVersions(
      "group",
      "question",
      "!%_ Straße",
      "web",
      new AbortController().signal,
    );
    expect(page).toEqual(body);
    expect(sent).toHaveBeenCalledTimes(1);
    expect(sent.mock.calls[0]![0]).toBe(
      "/api/groups/group/questions/versions?filter=%21%25_+Stra%C3%9Fe&searchWay=web",
    );
    expect(sent.mock.calls[0]![1]?.method ?? "GET").toBe("GET");
  });

  it.each(["reference_list", "workflow"] as const)(
    "admits %s without inventing a search qualification",
    (kind) => {
      expect(candidatesFrom({ items: [FIRST], more: false }, kind)).toEqual({
        items: [FIRST],
        more: false,
      });
      expect(
        candidatesFrom(
          { items: [{ ...FIRST, searchMatches: false }], more: false },
          kind,
        ),
      ).toBeNull();
    },
  );

  it.each([
    {},
    { items: [] },
    { items: [], more: "false" },
    { items: [FIRST], more: false },
    { items: [{ ...FIRST, searchMatches: true }], more: false },
    { items: [{ ...FIRST, searchMatches: "false" }], more: false },
    {
      items: [{ ...FIRST, versionName: undefined, searchMatches: false }],
      more: false,
    },
    {
      items: Array.from({ length: 21 }, (_, i) => ({
        ...FIRST,
        versionId: String(i),
        searchMatches: false,
      })),
      more: true,
    },
    {
      items: [
        { ...FIRST, searchMatches: false },
        { ...FIRST, searchMatches: false },
      ],
      more: false,
    },
  ])("rejects incomplete, duplicate or overlong candidate page %j", (body) => {
    expect(candidatesFrom(body, "question")).toBeNull();
  });
});
