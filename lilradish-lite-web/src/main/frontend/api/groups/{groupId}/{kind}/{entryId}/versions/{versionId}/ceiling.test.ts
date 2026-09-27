import { describe, expect, it } from "vitest";

import { requestsTo, serving } from "../../../../../../../testutil/answering";
import { writeCeiling } from "./ceiling";

const GROUP = "00000003-0000-4000-8000-000000000c91";

const ENTRY = "00000006-0000-4000-8000-000000000c91";

const VERSION = "00000007-0000-4000-8000-000000000c91";

const AT_CEILING = `/api/groups/${GROUP}/workflows/${ENTRY}/versions/${VERSION}/ceiling`;

const ANSWER = {
  revision: 3,
  takes: [],
  gives: [],
  steps: [],
  outputs: [],
  keepsOwnCeiling: true,
  raiseNeedsApproval: false,
  mayBeHelped: false,
  problems: [],
  sendsPast: [],
  offered: {
    lists: [],
    questions: [],
    workflows: [],
    codeSteps: [],
    models: [],
  },
  terms: {},
};

describe("writeCeiling", () => {
  it.each([
    ["a ceiling in digits", "9007199254740991"],
    ["none", null],
  ])(
    "puts %s with both settings beside it, naming the revision read",
    async (_case, ceiling) => {
      const sent = serving({
        [`PUT ${AT_CEILING}`]: [[JSON.stringify(ANSWER), 200]],
      });

      await expect(
        writeCeiling(
          GROUP,
          ENTRY,
          VERSION,
          2,
          ceiling,
          true,
          false,
          new AbortController().signal,
        ),
      ).resolves.toEqual({ ...ANSWER, terms: new Map() });
      expect(requestsTo(sent)).toEqual([`PUT ${AT_CEILING}`]);
      expect(JSON.parse(String(sent.mock.calls[0]![1]?.body))).toEqual({
        revision: 2,
        ceiling,
        keepsOwnCeiling: true,
        raiseNeedsApproval: false,
      });
    },
  );
});
