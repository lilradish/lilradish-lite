import { describe, expect, it } from "vitest";

import { serving } from "../../../../../../../testutil/answering";
import { draw } from "./drawing";

const GROUP = "00000003-0000-4000-8000-000000000c61";
const ENTRY = "00000006-0000-4000-8000-000000000c61";
const VERSION = "00000007-0000-4000-8000-000000000c61";
const ANSWER = {
  revision: 8,
  takes: [],
  gives: [],
  searchWays: [],
  searchShapeNeedsRefresh: false,
};

describe("draw", () => {
  it.each([
    ["Markdown", "## Case\n{{complaint}}"],
    ["none", null],
  ])(
    "writes %s beside the revision read and reads the version back",
    async (_case, drawing) => {
      const sent = serving({
        [`PUT /api/groups/${GROUP}/questions/${ENTRY}/versions/${VERSION}/drawing`]:
          [[JSON.stringify(ANSWER), 200]],
      });

      const signal = new AbortController().signal;
      await expect(
        draw(GROUP, ENTRY, VERSION, 7, drawing, signal),
      ).resolves.toEqual(ANSWER);
      expect(sent.mock.calls).toHaveLength(1);
      expect(sent.mock.calls[0]![0]).toBe(
        `/api/groups/${GROUP}/questions/${ENTRY}/versions/${VERSION}/drawing`,
      );
      expect(sent.mock.calls[0]![1]?.method).toBe("PUT");
      expect(sent.mock.calls[0]![1]?.signal).toBe(signal);
      expect(JSON.parse(String(sent.mock.calls[0]![1]?.body))).toEqual({
        revision: 7,
        drawing,
      });
    },
  );
});
