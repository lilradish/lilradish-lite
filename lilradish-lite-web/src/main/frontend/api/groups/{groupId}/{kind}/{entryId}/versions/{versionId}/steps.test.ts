import { describe, expect, it } from "vitest";

import { requestsTo, serving } from "../../../../../../../testutil/answering";
import { writeSteps, type SentBinding, type SentStep } from "./steps";

const GROUP = "00000003-0000-4000-8000-000000000c81";

const ENTRY = "00000006-0000-4000-8000-000000000c81";

const VERSION = "00000007-0000-4000-8000-000000000c81";

const AT_STEPS = `/api/groups/${GROUP}/workflows/${ENTRY}/versions/${VERSION}/steps`;

const ANSWER = {
  revision: 6,
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

const STEPS: readonly SentStep[] = [
  {
    stepId: "0000000c-0000-4000-8000-000000000c81",
    name: "triage",
    runs: { kind: "question", version: "00000007-0000-4000-8000-000000000c82" },
    producer: { kind: "person" },
    tries: 2,
    reviewer: null,
    bindings: [{ target: "complaint", source: { input: "complaint" } }],
  },
  {
    stepId: null,
    name: "route",
    runs: {
      kind: "route",
      discriminator: { step: 0, path: "category" },
      gives: [],
      cases: [{ caseId: null, term: null, workflow: null, bindings: [] }],
    },
    bindings: [],
  },
  {
    stepId: null,
    name: "tidy",
    runs: { kind: "code_step", codeStep: "tidy_up" },
    producer: { kind: "code" },
    tries: null,
    reviewer: null,
    bindings: [],
  },
];

const OUTPUTS: readonly SentBinding[] = [
  { target: "summary", source: { constant: '"none"' } },
];

describe("writeSteps", () => {
  it("puts every step and what fills each value given back in one document naming the revision read, and reads the version it answers with", async () => {
    const sent = serving({
      [`PUT ${AT_STEPS}`]: [[JSON.stringify(ANSWER), 200]],
    });

    await expect(
      writeSteps(
        GROUP,
        ENTRY,
        VERSION,
        5,
        STEPS,
        OUTPUTS,
        new AbortController().signal,
      ),
    ).resolves.toEqual({ ...ANSWER, terms: new Map() });
    expect(requestsTo(sent)).toEqual([`PUT ${AT_STEPS}`]);
    expect(JSON.parse(String(sent.mock.calls[0]![1]?.body))).toEqual({
      revision: 5,
      steps: STEPS,
      outputs: OUTPUTS,
    });
  });
});
