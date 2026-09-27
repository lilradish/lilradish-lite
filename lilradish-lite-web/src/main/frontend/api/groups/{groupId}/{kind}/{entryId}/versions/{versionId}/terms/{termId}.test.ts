import { describe, expect, it } from "vitest";

import { NOT_AN_ADDRESS } from "../../../../../../../problem";
import {
  refusalOf,
  requestsTo,
  serving,
} from "../../../../../../../../testutil/answering";
import { atTerm, rewordTerm } from "./{termId}";

const GROUP = "00000003-0000-4000-8000-000000000ca1";

const ENTRY = "00000006-0000-4000-8000-000000000ca1";

const VERSION = "00000007-0000-4000-8000-000000000ca1";

const TERM = "0000000c-0000-4000-8000-000000000ca1";

const AT_TERMS = `/api/groups/${GROUP}/reference-lists/${ENTRY}/versions/${VERSION}/terms`;

const ANSWER = {
  revision: 5,
  terms: [{ termId: TERM, term: "Refunds", meaning: "Money owed back." }],
};

describe("atTerm", () => {
  it("hands over the term's address under its list's version, each identifier escaped", async () => {
    const addresses = [TERM, "a/b"].map((term) =>
      atTerm(GROUP, ENTRY, VERSION, term, (address) =>
        Promise.resolve(address),
      ),
    );

    expect(await Promise.all(addresses)).toEqual([
      `${AT_TERMS}/${TERM}`,
      `${AT_TERMS}/a%2Fb`,
    ]);
  });

  it("refuses a term that names no address, asking nothing", async () => {
    const failure = await refusalOf(
      atTerm(GROUP, ENTRY, VERSION, "..", () => Promise.resolve("asked")),
    );

    expect(failure.problem).toEqual({ code: NOT_AN_ADDRESS });
  });
});

describe("rewordTerm", () => {
  it("puts the term and what it means in place of what it held, beside the revision read, and answers with the list as it then reads", async () => {
    const sent = serving({
      [`PUT ${AT_TERMS}/${TERM}`]: [[JSON.stringify(ANSWER), 200]],
    });

    await expect(
      rewordTerm(
        GROUP,
        ENTRY,
        VERSION,
        TERM,
        4,
        { term: "Refunds", meaning: "Money owed back." },
        new AbortController().signal,
      ),
    ).resolves.toEqual(ANSWER);
    expect(requestsTo(sent)).toEqual([`PUT ${AT_TERMS}/${TERM}`]);
    expect(JSON.parse(String(sent.mock.calls[0]![1]?.body))).toEqual({
      revision: 4,
      term: "Refunds",
      meaning: "Money owed back.",
    });
  });
});
