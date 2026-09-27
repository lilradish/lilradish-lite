import { describe, expect, it } from "vitest";

import { requestsTo, serving } from "../../../../../../../testutil/answering";
import { addTerm } from "./terms";

const GROUP = "00000003-0000-4000-8000-000000000c91";

const ENTRY = "00000006-0000-4000-8000-000000000c91";

const VERSION = "00000007-0000-4000-8000-000000000c91";

const AT_TERMS = `/api/groups/${GROUP}/reference-lists/${ENTRY}/versions/${VERSION}/terms`;

const ANSWER = {
  revision: 3,
  terms: [
    {
      termId: "0000000c-0000-4000-8000-000000000c91",
      term: "Billing",
      meaning: "Money taken wrongly.",
    },
  ],
};

describe("addTerm", () => {
  it("posts the term and what it means beside the revision read, and answers with the list as it then reads", async () => {
    const sent = serving({
      [`POST ${AT_TERMS}`]: [[JSON.stringify(ANSWER), 200]],
    });

    await expect(
      addTerm(
        GROUP,
        ENTRY,
        VERSION,
        2,
        { term: "Billing", meaning: "Money taken wrongly." },
        new AbortController().signal,
      ),
    ).resolves.toEqual(ANSWER);
    expect(requestsTo(sent)).toEqual([`POST ${AT_TERMS}`]);
    expect(JSON.parse(String(sent.mock.calls[0]![1]?.body))).toEqual({
      revision: 2,
      term: "Billing",
      meaning: "Money taken wrongly.",
    });
  });
});
