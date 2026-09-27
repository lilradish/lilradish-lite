import { describe, expect, it } from "vitest";

import { serving } from "../../../../../../../testutil/answering";
import { instruct } from "./instruction";

const GROUP = "00000003-0000-4000-8000-000000000c61";

const ENTRY = "00000006-0000-4000-8000-000000000c61";

const VERSION = "00000007-0000-4000-8000-000000000c61";

const ANSWER = { revision: 8, takes: [], gives: [], lists: [] };

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

      await expect(
        instruct(
          GROUP,
          ENTRY,
          VERSION,
          7,
          instruction,
          new AbortController().signal,
        ),
      ).resolves.toEqual(ANSWER);
      expect(JSON.parse(String(sent.mock.calls[0]![1]?.body))).toEqual({
        revision: 7,
        instruction,
      });
    },
  );
});
