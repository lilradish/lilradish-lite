import { describe, expect, it } from "vitest";

import { requestsTo, serving } from "../../../../../../../testutil/answering";
import { writeHelp } from "./help";

const GROUP = "00000003-0000-4000-8000-000000000ca1";

const ENTRY = "00000006-0000-4000-8000-000000000ca1";

const VERSION = "00000007-0000-4000-8000-000000000ca1";

const AT_HELP = `/api/groups/${GROUP}/workflows/${ENTRY}/versions/${VERSION}/help`;

const ANSWER = {
  revision: 8,
  takes: [],
  gives: [],
  steps: [],
  outputs: [],
  keepsOwnCeiling: false,
  raiseNeedsApproval: false,
  mayBeHelped: true,
  helper: { model: "general" },
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

describe("writeHelp", () => {
  it.each([
    ["a helper in a mode", true, { model: "general", mode: "research" }],
    ["a helper as it is", true, { model: "general", mode: null }],
    ["no help at all", false, null],
  ])(
    "puts %s, naming the revision read",
    async (_case, mayBeHelped, helper) => {
      const sent = serving({
        [`PUT ${AT_HELP}`]: [[JSON.stringify(ANSWER), 200]],
      });

      await expect(
        writeHelp(
          GROUP,
          ENTRY,
          VERSION,
          7,
          mayBeHelped,
          helper,
          new AbortController().signal,
        ),
      ).resolves.toEqual({ ...ANSWER, terms: new Map() });
      expect(requestsTo(sent)).toEqual([`PUT ${AT_HELP}`]);
      expect(JSON.parse(String(sent.mock.calls[0]![1]?.body))).toEqual({
        revision: 7,
        mayBeHelped,
        helper,
      });
    },
  );
});
