import { describe, expect, it } from "vitest";

import { requestsTo, serving } from "../../../../../../testutil/answering";
import {
  questionFrom,
  readQuestion,
  readReferenceList,
  readWorkflow,
  referenceListFrom,
  workflowFrom,
  writtenSinceRead,
} from "./{versionId}";

const GROUP = "00000003-0000-4000-8000-000000000c51";

const ENTRY = "00000006-0000-4000-8000-000000000c51";

const VERSION = "00000007-0000-4000-8000-000000000c51";

// More digits than a double carries, so a constant read through one is seen rounded.
const DIGITS_38 = "12345678901234567890123456789012345678";

const QUESTION = {
  revision: 4,
  instruction: "Say which category.",
  takes: [
    {
      fieldId: "f1",
      name: "complaint",
      kind: "text",
      many: false,
      mustBeGiven: true,
    },
  ],
  gives: [
    {
      fieldId: "f2",
      name: "summary",
      kind: "text",
      longest: 1000,
      many: false,
      mustBeGiven: true,
      stands: "always",
    },
  ],
  added: [
    {
      name: "summary",
      kind: "text",
      longest: 1000,
      many: false,
      mustBeGiven: true,
      confidence: false,
    },
  ],
  lists: [{ name: "Categories", versionId: "v1", number: 1 }],
};

const LIST = {
  revision: 2,
  note: "Pick the narrowest.\n\tNever two.",
  terms: [
    {
      termId: "0000000c-0000-4000-8000-000000000c51",
      term: "Billing",
      meaning: "Money taken wrongly.",
    },
    {
      termId: "0000000c-0000-4000-8000-000000000c52",
      term: "Delivery",
      meaning: "Late, lost or broken in transit.",
    },
  ],
};

describe("readQuestion", () => {
  it("reads a question's version at the address the question kind's versions are at", async () => {
    const sent = serving({
      [`GET /api/groups/${GROUP}/questions/${ENTRY}/versions/${VERSION}`]: [
        [JSON.stringify(QUESTION), 200],
      ],
    });

    await expect(
      readQuestion(GROUP, ENTRY, VERSION, new AbortController().signal),
    ).resolves.toEqual(QUESTION);
    expect(requestsTo(sent)).toEqual([
      `GET /api/groups/${GROUP}/questions/${ENTRY}/versions/${VERSION}`,
    ]);
  });
});

describe("readReferenceList", () => {
  it("reads a list's version at the address the reference list kind's versions are at", async () => {
    const sent = serving({
      [`GET /api/groups/${GROUP}/reference-lists/${ENTRY}/versions/${VERSION}`]:
        [[JSON.stringify(LIST), 200]],
    });

    await expect(
      readReferenceList(GROUP, ENTRY, VERSION, new AbortController().signal),
    ).resolves.toEqual(LIST);
    expect(requestsTo(sent)).toEqual([
      `GET /api/groups/${GROUP}/reference-lists/${ENTRY}/versions/${VERSION}`,
    ]);
  });
});

describe("writtenSinceRead", () => {
  it.each([
    [
      "the server refusing a draft written since it was read",
      { status: 409, code: "DRAFT_WRITTEN_SINCE_READ" },
      true,
    ],
    [
      "the same words minted here, where no server answered",
      { code: "DRAFT_WRITTEN_SINCE_READ" },
      false,
    ],
    [
      "a refusal for the version's standing",
      { status: 409, code: "VERSION_STANDING_REFUSES" },
      false,
    ],
  ])("tells %s apart: %j", (_case, problem, written) => {
    expect(writtenSinceRead(problem)).toBe(written);
  });
});

describe("questionFrom", () => {
  it("reads a version saying nothing yet, and nothing added, with neither member", () => {
    const { instruction: _said, added: _told, ...unsaid } = QUESTION;

    const read = questionFrom(unsaid)!;

    expect(read).toEqual(unsaid);
    expect("instruction" in read).toBe(false);
    expect("added" in read).toBe(false);
  });

  it.each([
    ["no revision", { ...QUESTION, revision: undefined }],
    ["a revision of none", { ...QUESTION, revision: 0 }],
    ["a revision that is no whole number", { ...QUESTION, revision: 1.5 }],
    ["a revision spelt as text", { ...QUESTION, revision: "4" }],
    ["an instruction that is null", { ...QUESTION, instruction: null }],
    ["a half that is no list", { ...QUESTION, takes: {} }],
    [
      "a field that cannot be read",
      { ...QUESTION, gives: [{ name: "summary" }] },
    ],
    [
      "what is added that cannot be read",
      { ...QUESTION, added: [{ name: "summary" }] },
    ],
    ["no lists", { ...QUESTION, lists: undefined }],
    ["no document at all", "question"],
  ])("reads nothing out of %s", (_case, body) => {
    expect(questionFrom(body)).toBeNull();
  });
});

const PINNED = {
  name: "Triage",
  versionId: "v2",
  number: 2,
  standing: "in_service",
};

const ASKING = {
  stepId: "s1",
  name: "triage",
  runs: {
    kind: "question",
    version: PINNED,
    takes: [QUESTION.takes[0]],
    gives: [QUESTION.gives[0]],
  },
  producer: { kind: "model", model: "general", mode: "research" },
  tries: 3,
  reviewer: { model: "small" },
  bindings: [
    { bindingId: "b1", target: "complaint", source: { input: "complaint" } },
  ],
};

const ROUTING = {
  stepId: "s2",
  name: "route",
  runs: {
    kind: "route",
    discriminator: { bindingId: "b2", source: { step: 0, path: "summary" } },
    gives: [],
    cases: [
      {
        caseId: "c1",
        term: "urgent",
        workflow: PINNED,
        takes: [],
        gives: [],
        bindings: [
          { bindingId: "b3", target: "note", source: { constant: DIGITS_38 } },
        ],
      },
      { caseId: "c2", bindings: [] },
    ],
  },
  bindings: [],
};

const WORKFLOW = {
  revision: 4,
  takes: [QUESTION.takes[0]],
  gives: [QUESTION.gives[0]],
  steps: [ASKING, ROUTING, { stepId: "s3", name: "", bindings: [] }],
  outputs: [
    {
      bindingId: "b4",
      target: "summary",
      source: { step: 0, path: "summary" },
    },
  ],
  ceiling: "9007199254740991",
  keepsOwnCeiling: true,
  raiseNeedsApproval: false,
  mayBeHelped: true,
  helper: { model: "general", mode: "research" },
  problems: [{ code: "runs_missing", part: "steps", stepId: "s3" }],
  sendsPast: [
    { stepId: "s1", role: "producing", model: "general", past: 2 ** 53 },
    { stepId: "s1", role: "reviewing", model: "small", past: 1 },
  ],
  offered: {
    lists: [{ name: "Categories", versionId: "v1", number: 1 }],
    questions: [
      {
        name: "Triage",
        versionId: "v2",
        number: 2,
        takes: [QUESTION.takes[0]],
        gives: [],
      },
    ],
    workflows: [],
    codeSteps: [
      {
        name: "tidy_up",
        takes: [QUESTION.takes[0]],
        gives: [QUESTION.gives[0]],
      },
    ],
    models: [{ name: "general", modes: ["research"] }],
  },
  terms: { v1: ["urgent", "later"], v3: [] },
};

const READ = {
  ...WORKFLOW,
  terms: new Map([
    ["v1", ["urgent", "later"]],
    ["v3", []],
  ]),
};

describe("readWorkflow", () => {
  it("reads a workflow's version at the address the workflow kind's versions are at", async () => {
    const sent = serving({
      [`GET /api/groups/${GROUP}/workflows/${ENTRY}/versions/${VERSION}`]: [
        [JSON.stringify(WORKFLOW), 200],
      ],
    });

    await expect(
      readWorkflow(GROUP, ENTRY, VERSION, new AbortController().signal),
    ).resolves.toEqual(READ);
    expect(requestsTo(sent)).toEqual([
      `GET /api/groups/${GROUP}/workflows/${ENTRY}/versions/${VERSION}`,
    ]);
  });
});

describe("workflowFrom", () => {
  it("reads every step's kind as it arrived, a constant as its JSON text to the digit", () => {
    expect(workflowFrom(WORKFLOW)).toEqual(READ);
  });

  it("reads the terms of every list in their order, a list offering none among them", () => {
    const read = workflowFrom(WORKFLOW)!;

    expect(read.terms.get("v1")).toEqual(["urgent", "later"]);
    expect(read.terms.get("v3")).toEqual([]);
    expect([...read.terms.keys()]).toEqual(["v1", "v3"]);
  });

  it("reads a code step produced by code as produced by code", () => {
    const tidying = {
      stepId: "s4",
      name: "tidy",
      runs: { kind: "code_step", codeStep: "tidy_up" },
      producer: { kind: "code" },
      bindings: [],
    };

    const read = workflowFrom({ ...WORKFLOW, steps: [tidying] })!;

    expect(read.steps[0]!.producer).toEqual({ kind: "code" });
  });

  it("reads what a code step declares where the server answers it, and nothing where it does not", () => {
    const declared = {
      kind: "code_step",
      codeStep: "tidy_up",
      takes: [QUESTION.takes[0]],
      gives: [QUESTION.gives[0]],
    };
    const tidying = { stepId: "s4", name: "tidy", bindings: [] };

    const read = workflowFrom({
      ...WORKFLOW,
      steps: [
        { ...tidying, runs: declared },
        {
          ...tidying,
          stepId: "s5",
          runs: { kind: "code_step", codeStep: "tidy_up" },
        },
      ],
    })!;

    expect(read.steps[0]!.runs).toEqual(declared);
    expect(read.steps[1]!.runs).toEqual({
      kind: "code_step",
      codeStep: "tidy_up",
    });
  });

  it("reads a version with no ceiling and no helper, and a step with nothing chosen, without those members", () => {
    const { ceiling: _ceiling, helper: _helper, ...unset } = READ;

    const read = workflowFrom({ ...unset, terms: WORKFLOW.terms })!;

    expect(read).toEqual(unset);
    expect("ceiling" in read).toBe(false);
    expect("helper" in read).toBe(false);
    expect(Object.keys(read.steps[2]!).sort()).toEqual([
      "bindings",
      "name",
      "stepId",
    ]);
  });

  it.each([
    ["a revision that is no whole number", { ...WORKFLOW, revision: "4" }],
    ["a revision of none", { ...WORKFLOW, revision: 0 }],
    ["no problems", { ...WORKFLOW, problems: undefined }],
    ["a problem that cannot be read", { ...WORKFLOW, problems: [{}] }],
    [
      "nothing said of what its steps could send",
      { ...WORKFLOW, sendsPast: undefined },
    ],
    [
      "a step sending past a model for a reason not spelt here",
      {
        ...WORKFLOW,
        sendsPast: [{ ...WORKFLOW.sendsPast[1], role: "helping" }],
      },
    ],
    [
      "a step sending past a model by nothing",
      { ...WORKFLOW, sendsPast: [{ ...WORKFLOW.sendsPast[1], past: 0 }] },
    ],
    [
      "a step sending past a model by a part of a unit",
      { ...WORKFLOW, sendsPast: [{ ...WORKFLOW.sendsPast[1], past: 1.5 }] },
    ],
    [
      "a step sending past a model it does not name",
      { ...WORKFLOW, sendsPast: [{ ...WORKFLOW.sendsPast[1], model: null }] },
    ],
    [
      "a step sending past a model, named by no key",
      { ...WORKFLOW, sendsPast: [{ ...WORKFLOW.sendsPast[1], stepId: 7 }] },
    ],
    ["nothing offered", { ...WORKFLOW, offered: null }],
    [
      "offered models that cannot be read",
      { ...WORKFLOW, offered: { ...WORKFLOW.offered, models: [{}] } },
    ],
    [
      "a code step offered by its name alone",
      { ...WORKFLOW, offered: { ...WORKFLOW.offered, codeSteps: ["tidy_up"] } },
    ],
    [
      "a code step offered with nothing said of what it gives back",
      {
        ...WORKFLOW,
        offered: {
          ...WORKFLOW.offered,
          codeSteps: [{ name: "tidy_up", takes: [] }],
        },
      },
    ],
    [
      "a code step offered taking a field that cannot be read",
      {
        ...WORKFLOW,
        offered: {
          ...WORKFLOW.offered,
          codeSteps: [{ name: "tidy_up", takes: [{}], gives: [] }],
        },
      },
    ],
    ["a ceiling that is a number", { ...WORKFLOW, ceiling: 12 }],
    ["no terms", { ...WORKFLOW, terms: undefined }],
    ["terms listed by no list's version", { ...WORKFLOW, terms: [["urgent"]] }],
    ["a list's terms that are no list", { ...WORKFLOW, terms: { v1: "a" } }],
    ["a term that is no text", { ...WORKFLOW, terms: { v1: ["a", 1] } }],
    ["a helper naming no model", { ...WORKFLOW, helper: { mode: "research" } }],
    [
      "a step of a kind not spelt here",
      {
        ...WORKFLOW,
        steps: [{ ...ASKING, runs: { ...ASKING.runs, kind: "script" } }],
      },
    ],
    [
      "a producer of a kind not spelt here",
      { ...WORKFLOW, steps: [{ ...ASKING, producer: { kind: "robot" } }] },
    ],
    [
      "tries that are no whole number",
      { ...WORKFLOW, steps: [{ ...ASKING, tries: 1.5 }] },
    ],
    [
      "a source holding two ways in",
      {
        ...WORKFLOW,
        outputs: [
          {
            bindingId: "b4",
            target: "summary",
            source: { input: "complaint", constant: 1 },
          },
        ],
      },
    ],
    [
      "a constant that is a number and not its text",
      {
        ...WORKFLOW,
        outputs: [
          { bindingId: "b4", target: "summary", source: { constant: 1 } },
        ],
      },
    ],
    [
      "a constant whose text is no JSON",
      {
        ...WORKFLOW,
        outputs: [
          { bindingId: "b4", target: "summary", source: { constant: "{" } },
        ],
      },
    ],
    [
      "a case with no key",
      {
        ...WORKFLOW,
        steps: [
          {
            ...ROUTING,
            runs: { ...ROUTING.runs, cases: [{ bindings: [] }] },
          },
        ],
      },
    ],
    ["no document at all", "workflow"],
  ])("reads nothing out of %s", (_case, body) => {
    expect(workflowFrom(body)).toBeNull();
  });
});

describe("referenceListFrom", () => {
  it("reads a list saying nothing on choosing among its terms, and holding none, with no note", () => {
    const read = referenceListFrom({ revision: 1, terms: [] })!;

    expect(read).toEqual({ revision: 1, terms: [] });
    expect("note" in read).toBe(false);
  });

  it("marks a term the server judged alike to one before it, and leaves the mark off the one it was not", () => {
    const marked = {
      ...LIST,
      terms: [LIST.terms[0], { ...LIST.terms[1], alikeEarlier: true }],
    };

    const read = referenceListFrom(marked)!;

    expect(read.terms[1]!.alikeEarlier).toBe(true);
    expect("alikeEarlier" in read.terms[0]!).toBe(false);
    expect(read).toEqual(marked);
  });

  it.each([
    ["no revision", { ...LIST, revision: undefined }],
    ["a revision of none", { ...LIST, revision: 0 }],
    ["a note that is null", { ...LIST, note: null }],
    ["terms that are no list", { ...LIST, terms: {} }],
    [
      "a term without its key",
      { ...LIST, terms: [{ term: "Billing", meaning: "Money." }] },
    ],
    [
      "a term saying nothing of what it means",
      { ...LIST, terms: [{ ...LIST.terms[0], meaning: undefined }] },
    ],
    [
      "a term marked alike as false, which the server leaves out",
      { ...LIST, terms: [{ ...LIST.terms[0], alikeEarlier: false }] },
    ],
    [
      "a term marked alike as null",
      { ...LIST, terms: [{ ...LIST.terms[0], alikeEarlier: null }] },
    ],
    [
      "a term marked alike in text",
      { ...LIST, terms: [{ ...LIST.terms[0], alikeEarlier: "true" }] },
    ],
    ["no document at all", "list"],
  ])("reads nothing out of %s", (_case, body) => {
    expect(referenceListFrom(body)).toBeNull();
  });
});
