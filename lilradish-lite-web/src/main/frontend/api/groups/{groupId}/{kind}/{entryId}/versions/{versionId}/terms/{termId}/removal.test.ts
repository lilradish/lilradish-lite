import { describe, expect, it } from "vitest";

import {
  requestsTo,
  serving,
} from "../../../../../../../../../testutil/answering";
import { removeTerm } from "./removal";

const GROUP = "00000003-0000-4000-8000-000000000cb1";

const ENTRY = "00000006-0000-4000-8000-000000000cb1";

const VERSION = "00000007-0000-4000-8000-000000000cb1";

const TERM = "0000000c-0000-4000-8000-000000000cb1";

const AT_TERM = `/api/groups/${GROUP}/reference-lists/${ENTRY}/versions/${VERSION}/terms/${TERM}`;

const ANSWER = { revision: 9, terms: [] };

describe("removeTerm", () => {
  it("posts the term's removal with the revision read and nothing else, and answers with the list as it then reads", async () => {
    const sent = serving({
      [`POST ${AT_TERM}/removal`]: [[JSON.stringify(ANSWER), 200]],
    });

    await expect(
      removeTerm(GROUP, ENTRY, VERSION, TERM, 8, new AbortController().signal),
    ).resolves.toEqual(ANSWER);
    expect(requestsTo(sent)).toEqual([`POST ${AT_TERM}/removal`]);
    expect(JSON.parse(String(sent.mock.calls[0]![1]?.body))).toEqual({
      revision: 8,
    });
  });
});
