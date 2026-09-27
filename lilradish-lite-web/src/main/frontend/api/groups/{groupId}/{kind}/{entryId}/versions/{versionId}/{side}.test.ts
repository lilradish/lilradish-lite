import { describe, expect, it } from "vitest";

import type { DeclarationSide } from "../../../../../../declaration";
import { requestsTo, serving } from "../../../../../../../testutil/answering";
import { declare, declareWorkflow } from "./{side}";

const GROUP = "00000003-0000-4000-8000-000000000c71";

const ENTRY = "00000006-0000-4000-8000-000000000c71";

const VERSION = "00000007-0000-4000-8000-000000000c71";

const AT_VERSION = `/api/groups/${GROUP}/questions/${ENTRY}/versions/${VERSION}`;

const ANSWER = { revision: 3, takes: [], gives: [], lists: [] };

const FIELD = {
  fieldId: "0000000b-0000-4000-8000-000000000a51",
  name: "urgent",
  label: null,
  help: null,
  kind: "yes_no",
  many: false,
  most: null,
  mustBeGiven: false,
} as const;

describe("declare", () => {
  it.each(["takes", "gives"] as DeclarationSide[])(
    "puts the half it names, %s, whole in place of what it held, beside the revision read, at that half's own address",
    async (side) => {
      const sent = serving({
        [`PUT ${AT_VERSION}/${side}`]: [[JSON.stringify(ANSWER), 200]],
      });

      await expect(
        declare(
          GROUP,
          ENTRY,
          VERSION,
          side,
          2,
          [FIELD],
          new AbortController().signal,
        ),
      ).resolves.toEqual(ANSWER);
      expect(requestsTo(sent)).toEqual([`PUT ${AT_VERSION}/${side}`]);
      expect(JSON.parse(String(sent.mock.calls[0]![1]?.body))).toEqual({
        revision: 2,
        fields: [FIELD],
      });
    },
  );
});

const AT_WORKFLOW = `/api/groups/${GROUP}/workflows/${ENTRY}/versions/${VERSION}`;

const WORKFLOW = {
  revision: 5,
  takes: [],
  gives: [],
  steps: [],
  outputs: [],
  keepsOwnCeiling: false,
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

describe("declareWorkflow", () => {
  it.each(["takes", "gives"] as DeclarationSide[])(
    "puts the half it names, %s, at the workflow's address for it, naming the revision read beside the fields",
    async (side) => {
      const sent = serving({
        [`PUT ${AT_WORKFLOW}/${side}`]: [[JSON.stringify(WORKFLOW), 200]],
      });

      await expect(
        declareWorkflow(
          GROUP,
          ENTRY,
          VERSION,
          side,
          4,
          [FIELD],
          new AbortController().signal,
        ),
      ).resolves.toEqual({ ...WORKFLOW, terms: new Map() });
      expect(requestsTo(sent)).toEqual([`PUT ${AT_WORKFLOW}/${side}`]);
      expect(JSON.parse(String(sent.mock.calls[0]![1]?.body))).toEqual({
        revision: 4,
        fields: [FIELD],
      });
    },
  );
});
