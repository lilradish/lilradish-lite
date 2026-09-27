import { describe, expect, it } from "vitest";

import {
  requestsTo,
  serving,
} from "../../../../../../../../../testutil/answering";
import { moveTerm, type TermWay } from "./{way}";

const GROUP = "00000003-0000-4000-8000-000000000cc1";

const ENTRY = "00000006-0000-4000-8000-000000000cc1";

const VERSION = "00000007-0000-4000-8000-000000000cc1";

const TERM = "0000000c-0000-4000-8000-000000000cc1";

const AT_TERM = `/api/groups/${GROUP}/reference-lists/${ENTRY}/versions/${VERSION}/terms/${TERM}`;

const ANSWER = { revision: 4, terms: [] };

describe("moveTerm", () => {
  it.each(["up", "down"] as TermWay[])(
    "posts the move %s to the way's own address with the revision read and nothing else, and answers with the list as it then reads",
    async (way) => {
      const sent = serving({
        [`POST ${AT_TERM}/${way}`]: [[JSON.stringify(ANSWER), 200]],
      });

      await expect(
        moveTerm(
          GROUP,
          ENTRY,
          VERSION,
          TERM,
          way,
          3,
          new AbortController().signal,
        ),
      ).resolves.toEqual(ANSWER);
      expect(requestsTo(sent)).toEqual([`POST ${AT_TERM}/${way}`]);
      expect(JSON.parse(String(sent.mock.calls[0]![1]?.body))).toEqual({
        revision: 3,
      });
    },
  );
});
