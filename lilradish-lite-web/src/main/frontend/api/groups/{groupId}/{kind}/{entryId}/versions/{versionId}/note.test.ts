import { describe, expect, it } from "vitest";

import { requestsTo, serving } from "../../../../../../../testutil/answering";
import { writeNote } from "./note";

const GROUP = "00000003-0000-4000-8000-000000000c81";

const ENTRY = "00000006-0000-4000-8000-000000000c81";

const VERSION = "00000007-0000-4000-8000-000000000c81";

const AT_NOTE = `/api/groups/${GROUP}/reference-lists/${ENTRY}/versions/${VERSION}/note`;

const ANSWER = { revision: 6, terms: [] };

describe("writeNote", () => {
  it.each([
    ["a note", "Pick the narrowest.\n\tNever two."],
    ["none, where it is to say nothing", null],
  ])(
    "puts %s in place of what the list held, beside the revision read, and answers with the list as it then reads",
    async (_case, note) => {
      const sent = serving({
        [`PUT ${AT_NOTE}`]: [[JSON.stringify(ANSWER), 200]],
      });

      await expect(
        writeNote(GROUP, ENTRY, VERSION, 5, note, new AbortController().signal),
      ).resolves.toEqual(ANSWER);
      expect(requestsTo(sent)).toEqual([`PUT ${AT_NOTE}`]);
      expect(JSON.parse(String(sent.mock.calls[0]![1]?.body))).toEqual({
        revision: 5,
        note,
      });
    },
  );
});
