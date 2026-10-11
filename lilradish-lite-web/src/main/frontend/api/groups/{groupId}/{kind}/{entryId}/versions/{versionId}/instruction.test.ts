import { describe, expect, it } from "vitest";

import { serving } from "../../../../../../../testutil/answering";
import { instruct } from "./instruction";

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

describe("instruct", () => {
  it.each([
    ["an instruction", "Say why.\n\tBriefly."],
    ["none, where it is to say nothing", null],
  ])(
    "puts %s in place of what the version held, beside the revision read, and answers with the version as it then reads",
    async (_case, instruction) => {
      const sent = serving({
        [`PUT /api/groups/${GROUP}/questions/${ENTRY}/versions/${VERSION}/instruction`]:
          [[JSON.stringify(ANSWER), 200]],
      });

      const signal = new AbortController().signal;
      await expect(
        instruct(GROUP, ENTRY, VERSION, 7, instruction, signal),
      ).resolves.toEqual(ANSWER);
      expect(sent.mock.calls).toHaveLength(1);
      expect(sent.mock.calls[0]![0]).toBe(
        `/api/groups/${GROUP}/questions/${ENTRY}/versions/${VERSION}/instruction`,
      );
      expect(sent.mock.calls[0]![1]?.method).toBe("PUT");
      expect(sent.mock.calls[0]![1]?.signal).toBe(signal);
      expect(JSON.parse(String(sent.mock.calls[0]![1]?.body))).toEqual({
        revision: 7,
        instruction,
      });
    },
  );
});
