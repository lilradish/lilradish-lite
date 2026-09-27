import { describe, expect, it } from "vitest";

import type { DeclaredField, PinnedList } from "../../api/declaration";
import type { ContentProblem } from "../../api/groups/{groupId}/{kind}/{entryId}/versions";
import type {
  OfferedCodeStep,
  WorkflowVersion,
} from "../../api/groups/{groupId}/{kind}/{entryId}/versions/{versionId}";
import { draftOf } from "./declarationDrafts";
import {
  boundTo,
  caseTookVersion,
  fits,
  flagsOf,
  flowOf,
  freshCase,
  freshStep,
  givenBy,
  mostCases,
  movedStep,
  namedVersions,
  pointers,
  pointsForward,
  removedStep,
  sentFlowOf,
  sourcesFor,
  takesOf,
  targetRows,
  termFits,
  termsChosenBy,
  tookVersion,
  triesFit,
  unusedKey,
  type BindingDraft,
  type FlowDraft,
  type RouteDraft,
  type Shape,
  type SourceOffered,
  type StepDraft,
} from "./workflowDrafts";

const LIST: PinnedList = {
  name: "Categories",
  versionId: "l1",
  number: 1,
  standing: "in_service",
};

const COMPLAINT: DeclaredField = {
  fieldId: "f1",
  name: "complaint",
  kind: "text",
  longest: 4000,
  many: false,
  mustBeGiven: true,
};

const CATEGORY: DeclaredField = {
  fieldId: "f2",
  name: "category",
  kind: "term",
  list: LIST,
  many: false,
  mustBeGiven: true,
  stands: "always",
};

const NOTE: DeclaredField = {
  fieldId: "f3",
  name: "note",
  kind: "text",
  many: false,
  mustBeGiven: false,
};

const TRIAGE: PinnedList = {
  name: "Triage",
  versionId: "q1",
  number: 2,
  standing: "in_service",
  newer: { versionId: "q2", number: 3 },
};

const REFERENCE: DeclaredField = {
  fieldId: "k2",
  name: "reference",
  kind: "text",
  longest: 12,
  many: false,
  mustBeGiven: true,
  stands: "always",
};

/** A code step the release offers, which the flow's own code step is not. */
const STAMPING: OfferedCodeStep = {
  name: "stamp_reference",
  takes: [{ ...COMPLAINT, fieldId: "k1" }],
  gives: [REFERENCE],
};

const ESCALATE: PinnedList = {
  name: "Escalate",
  versionId: "w1",
  number: 1,
  standing: "in_service",
  newer: { versionId: "w2", number: 2 },
};

const WORKFLOW: WorkflowVersion = {
  revision: 3,
  takes: [COMPLAINT, NOTE],
  gives: [CATEGORY],
  steps: [
    {
      stepId: "s1",
      name: "triage",
      runs: {
        kind: "question",
        version: TRIAGE,
        takes: [COMPLAINT],
        gives: [CATEGORY],
      },
      producer: {
        kind: "model",
        model: "general",
        mode: "research",
        toldWhatHappened: true,
      },
      tries: 2,
      reviewer: { model: "small" },
      bindings: [
        {
          bindingId: "b1",
          target: "complaint",
          source: { input: "complaint" },
        },
      ],
    },
    {
      stepId: "s2",
      name: "route",
      runs: {
        kind: "route",
        discriminator: {
          bindingId: "b2",
          source: { step: 0, path: "category" },
        },
        gives: [],
        cases: [
          {
            caseId: "c1",
            term: "urgent",
            workflow: ESCALATE,
            takes: [NOTE],
            gives: [],
            bindings: [
              {
                bindingId: "b3",
                target: "note",
                source: { constant: '{"said":1}' },
              },
            ],
          },
          { caseId: "c2", bindings: [] },
        ],
      },
      bindings: [],
    },
    {
      stepId: "s3",
      name: "tidy",
      runs: { kind: "code_step", codeStep: "tidy_up" },
      producer: { kind: "code" },
      bindings: [
        { bindingId: "b5", target: "text", source: { step: 9, path: "gone" } },
        { bindingId: "b6", target: "said", source: { constant: '"as typed"' } },
      ],
    },
  ],
  outputs: [
    {
      bindingId: "b4",
      target: "category",
      source: { step: 0, path: "category" },
    },
  ],
  keepsOwnCeiling: false,
  raiseNeedsApproval: false,
  mayBeHelped: false,
  problems: [],
  sendsPast: [],
  offered: {
    lists: [],
    questions: [
      {
        name: "Triage",
        versionId: "q2",
        number: 3,
        takes: [{ ...COMPLAINT, fieldId: "f9" }],
        gives: [CATEGORY],
      },
    ],
    workflows: [
      { name: "Escalate", versionId: "w2", number: 2, takes: [], gives: [] },
    ],
    codeSteps: [STAMPING],
    models: [],
  },
  terms: new Map([["l1", ["urgent", "later"]]]),
};

const TEXT: Shape = {
  name: "said",
  kind: "text",
  many: false,
  mustBeGiven: true,
  longest: 100,
};

const NAMED = namedVersions(WORKFLOW);

function flowWith(...steps: StepDraft[]): FlowDraft {
  return { steps, outputs: [] };
}

function routeOf(step: StepDraft): RouteDraft {
  if (step.runs.kind !== "route") {
    throw new Error("the fixture's step is no route");
  }
  return step.runs;
}

describe("flowOf", () => {
  it("reads every step as an editor writes it, a step read from by its place named by its row", () => {
    const flow = flowOf(WORKFLOW);

    expect(flow.steps[0]).toEqual({
      key: "s1",
      stepId: "s1",
      name: "triage",
      runs: { kind: "question", version: "q1" },
      producer: "model",
      model: "general",
      mode: "research",
      toldWhatHappened: true,
      tries: "2",
      reviewer: "model",
      reviewerModel: "small",
      reviewerMode: "",
      bindings: [
        {
          target: "complaint",
          source: { from: "input", path: "complaint" },
          bindingId: "b1",
        },
      ],
    });
    expect(routeOf(flow.steps[1]!)).toEqual({
      kind: "route",
      discriminator: { from: "step", stepKey: "s1", path: "category" },
      discriminatorId: "b2",
      gives: [],
      cases: [
        {
          key: "c1",
          caseId: "c1",
          fallback: false,
          term: "urgent",
          workflow: "w1",
          bindings: [
            {
              target: "note",
              source: {
                from: "constant",
                typed: '{"said":1}',
                read: '{"said":1}',
              },
              bindingId: "b3",
            },
          ],
        },
        {
          key: "c2",
          caseId: "c2",
          fallback: true,
          term: "",
          workflow: "",
          bindings: [],
        },
      ],
    });
    expect(flow.outputs).toEqual([
      {
        target: "category",
        source: { from: "step", stepKey: "s1", path: "category" },
        bindingId: "b4",
      },
    ]);
  });

  it("reads a code step produced by code as such, and a place naming no step as nothing chosen", () => {
    const tidy = flowOf(WORKFLOW).steps[2]!;

    expect(tidy.runs).toEqual({ kind: "code_step", codeStep: "tidy_up" });
    expect(tidy.producer).toBe("code");
    expect(tidy.reviewer).toBe("person");
    expect(tidy.tries).toBe("");
    expect(tidy.bindings[0]!.source).toEqual({ from: "none" });
  });

  it.each([
    ["text filling one text as itself", COMPLAINT, '"urgent"', "urgent"],
    [
      "text filling a number written out",
      { ...COMPLAINT, kind: "number" },
      '"urgent"',
      '"urgent"',
    ],
    [
      "text filling many texts written out",
      { ...COMPLAINT, many: true },
      '"urgent"',
      '"urgent"',
    ],
    ["a number as written", { ...COMPLAINT, kind: "number" }, "12.50", "12.50"],
    [
      "a number of 38 digits to the digit",
      { ...COMPLAINT, kind: "number" },
      "12345678901234567890123456789012345678",
      "12345678901234567890123456789012345678",
    ],
  ] as const)(
    "types a constant of %s, and keeps the value read beside it",
    (_case, field, value, typed) => {
      const read = flowOf({
        ...WORKFLOW,
        gives: [field],
        outputs: [
          { bindingId: "b9", target: "complaint", source: { constant: value } },
        ],
      });

      expect(read.outputs[0]!.source).toEqual({
        from: "constant",
        typed,
        read: value,
      });
    },
  );
});

describe("fits", () => {
  it.each([
    ["the same shape", TEXT, TEXT, true],
    ["a shorter text", { ...TEXT, longest: 50 }, TEXT, true],
    ["a longer text", { ...TEXT, longest: 150 }, TEXT, false],
    ["a text of no known length", { ...TEXT, longest: undefined }, TEXT, true],
    ["another kind", { ...TEXT, kind: "number" }, TEXT, false],
    ["many into one", { ...TEXT, many: true }, TEXT, false],
    [
      "one that may be empty into one that must be given, judged apart",
      { ...TEXT, mustBeGiven: false },
      TEXT,
      true,
    ],
    [
      "more of many than taken",
      { ...TEXT, many: true, most: 5 },
      { ...TEXT, many: true, most: 3 },
      false,
    ],
    [
      "a term of the same list",
      { ...TEXT, kind: "term", list: "l1" },
      { ...TEXT, kind: "term", list: "l1" },
      true,
    ],
    [
      "a term of another list",
      { ...TEXT, kind: "term", list: "l2" },
      { ...TEXT, kind: "term", list: "l1" },
      false,
    ],
    [
      "fields holding the same names, each fitting",
      { ...TEXT, kind: "fields", fields: [{ ...TEXT, longest: 10 }] },
      { ...TEXT, kind: "fields", fields: [TEXT] },
      true,
    ],
    [
      "fields holding another name",
      { ...TEXT, kind: "fields", fields: [{ ...TEXT, name: "other" }] },
      { ...TEXT, kind: "fields", fields: [TEXT] },
      false,
    ],
    [
      "fields holding one more",
      { ...TEXT, kind: "fields", fields: [TEXT, { ...TEXT, name: "more" }] },
      { ...TEXT, kind: "fields", fields: [TEXT] },
      false,
    ],
  ])("judges %s", (_case, source, target, fitting) => {
    expect(fits(source as Shape, target as Shape)).toBe(fitting);
  });
});

const NESTED: readonly Shape[] = [
  {
    name: "who",
    kind: "fields",
    many: false,
    mustBeGiven: false,
    fields: [
      TEXT,
      {
        name: "tags",
        kind: "fields",
        many: true,
        mustBeGiven: true,
        fields: [TEXT],
      },
    ],
  },
  TEXT,
];

describe("pointers", () => {
  it("names every field in reading order, going into one holding fields and never into many", () => {
    expect(pointers(NESTED).map((pointer) => pointer.path)).toEqual([
      "who",
      "who.said",
      "who.tags",
      "said",
    ]);
  });

  it("says a field is always there only where it and every field holding it must be given", () => {
    expect(
      pointers(NESTED).map((pointer) => [pointer.path, pointer.given]),
    ).toEqual([
      ["who", false],
      ["who.said", false],
      ["who.tags", false],
      ["said", true],
    ]);
  });
});

describe("namedVersions", () => {
  it("names what is offered, and every version pinned with what the page read of its pin", () => {
    expect([...NAMED.keys()].sort()).toEqual(["q1", "q2", "w1", "w2"]);
    expect(NAMED.get("q1")).toEqual({
      name: "Triage",
      number: 2,
      takes: [COMPLAINT],
      gives: [CATEGORY],
      pinned: TRIAGE,
    });
    expect(NAMED.get("q2")!.pinned).toBeUndefined();
  });
});

describe("takesOf", () => {
  const CODE_STEPS = WORKFLOW.offered.codeSteps;

  it("reads what a pinned version takes, as it declares it", () => {
    expect(takesOf(flowOf(WORKFLOW).steps[0]!, NAMED, CODE_STEPS)).toEqual([
      COMPLAINT,
    ]);
  });

  it("reads what a code step the release offers takes, as the release declares it", () => {
    expect(
      takesOf(
        {
          ...freshStep("n"),
          runs: { kind: "code_step", codeStep: "stamp_reference" },
        },
        NAMED,
        CODE_STEPS,
      ),
    ).toEqual(STAMPING.takes);
  });

  it("reads a route as taking nothing, and a code step not offered, none chosen, or a version not known as unknown", () => {
    const flow = flowOf(WORKFLOW);

    expect(takesOf(flow.steps[1]!, NAMED, CODE_STEPS)).toEqual([]);
    expect(takesOf(flow.steps[2]!, NAMED, CODE_STEPS)).toBeUndefined();
    expect(
      takesOf(
        { ...freshStep("n"), runs: { kind: "code_step", codeStep: "" } },
        NAMED,
        CODE_STEPS,
      ),
    ).toBeUndefined();
    expect(
      takesOf(
        { ...freshStep("n"), runs: { kind: "question", version: "x" } },
        NAMED,
        CODE_STEPS,
      ),
    ).toBeUndefined();
    expect(takesOf(freshStep("n"), NAMED, CODE_STEPS)).toBeUndefined();
  });
});

describe("givenBy", () => {
  it("reads what a pinned version gives, and a route's as typed", () => {
    const route: StepDraft = {
      ...freshStep("r"),
      runs: {
        kind: "route",
        discriminator: { from: "none" },
        gives: [{ ...draftOf(COMPLAINT), mustBeGiven: false }],
        cases: [],
      },
    };

    expect(
      givenBy(flowOf(WORKFLOW).steps[0]!, NAMED, WORKFLOW.offered.codeSteps),
    ).toEqual([
      {
        name: "category",
        kind: "term",
        many: false,
        mustBeGiven: true,
        list: "l1",
      },
    ]);
    expect(givenBy(route, NAMED, WORKFLOW.offered.codeSteps)).toEqual([
      {
        name: "complaint",
        kind: "text",
        many: false,
        mustBeGiven: false,
        longest: 4000,
      },
    ]);
  });

  it("reads what a code step the release offers gives back, as the release declares it", () => {
    expect(
      givenBy(
        {
          ...freshStep("n"),
          runs: { kind: "code_step", codeStep: "stamp_reference" },
        },
        NAMED,
        WORKFLOW.offered.codeSteps,
      ),
    ).toEqual([
      {
        name: "reference",
        kind: "text",
        many: false,
        mustBeGiven: true,
        longest: 12,
      },
    ]);
  });

  it("reads a code step the release does not offer, and a step running nothing yet, as unknown", () => {
    expect(
      givenBy(flowOf(WORKFLOW).steps[2]!, NAMED, WORKFLOW.offered.codeSteps),
    ).toBeUndefined();
    expect(
      givenBy(freshStep("n"), NAMED, WORKFLOW.offered.codeSteps),
    ).toBeUndefined();
  });
});

describe("sourcesFor", () => {
  const flow = flowOf(WORKFLOW);

  it("offers a step what the workflow takes and only the steps before it", () => {
    const sources = sourcesFor(flow, 1, WORKFLOW, NAMED, flow.steps[1]);

    expect(sources.map((source) => source.value)).toEqual(["input", "s1"]);
    expect(sources[1]!.words).toBe("Step 1: triage");
  });

  it("keeps a later step a binding of the step reads already, to be said to point forward", () => {
    const reading: StepDraft = {
      ...flow.steps[0]!,
      bindings: [
        {
          target: "complaint",
          source: { from: "step", stepKey: "s3", path: "x" },
        },
      ],
    };

    const sources = sourcesFor(
      { ...flow, steps: [reading, flow.steps[1]!, flow.steps[2]!] },
      0,
      WORKFLOW,
      NAMED,
      reading,
    );

    expect(sources.map((source) => source.value)).toEqual(["input", "s3"]);
  });

  it("offers what the workflow gives back every step, and not what it takes", () => {
    const sources = sourcesFor(
      flow,
      flow.steps.length,
      WORKFLOW,
      NAMED,
      undefined,
    );

    expect(sources.map((source) => source.value)).toEqual(["s1", "s2", "s3"]);
  });

  it("offers what a code step before it gives back, as the release declares it, and nothing of one it does not offer", () => {
    const stamping: StepDraft = {
      ...freshStep("s4"),
      runs: { kind: "code_step", codeStep: "stamp_reference" },
    };

    const sources = sourcesFor(
      { ...flow, steps: [...flow.steps, stamping] },
      4,
      WORKFLOW,
      NAMED,
      undefined,
    );

    expect(
      sources.map((source) => source.fields?.map((field) => field.name)),
    ).toEqual([["category"], [], undefined, ["reference"]]);
  });
});

describe("flagsOf", () => {
  const flow = flowOf(WORKFLOW);
  const sources = sourcesFor(flow, 1, WORKFLOW, NAMED, flow.steps[1]);
  const reading = (path: string): BindingDraft => ({
    target: "x",
    source: { from: "input", path },
  });

  it("says what fills what must be given may be empty where what it reads need not be given", () => {
    expect(flagsOf(reading("note"), TEXT, flow, 1, [], sources)).toEqual([
      "contentProblem.source_may_be_empty",
    ]);
  });

  it("says nothing where what it reads is always there, or where what it fills need not be given", () => {
    expect(flagsOf(reading("complaint"), TEXT, flow, 1, [], sources)).toEqual(
      [],
    );
    expect(
      flagsOf(
        reading("note"),
        { ...TEXT, mustBeGiven: false },
        flow,
        1,
        [],
        sources,
      ),
    ).toEqual([]);
  });

  it.each([
    ["may be empty, what it fills must be given", false, true, true],
    ["must be given, as what it fills must", true, true, false],
    ["must be given, what it fills need not be", true, false, false],
    ["may be empty, as what it fills may", false, false, false],
  ] as const)(
    "judges a field held within one bound whole whose source's field %s",
    (_case, sourceMustBe, targetMustBe, flagged) => {
      const contact = (emailMustBe: boolean): Shape => ({
        ...TEXT,
        name: "contact",
        kind: "fields",
        fields: [{ ...TEXT, name: "email", mustBeGiven: emailMustBe }],
      });
      const offered: SourceOffered[] = [
        {
          value: "input",
          words: "What the workflow takes",
          fields: [contact(sourceMustBe)],
        },
      ];

      expect(
        flagsOf(
          { target: "contact", source: { from: "input", path: "contact" } },
          contact(targetMustBe),
          flow,
          1,
          [],
          offered,
        ),
      ).toEqual(flagged ? ["contentProblem.source_may_be_empty"] : []);
    },
  );

  it("says a binding reads a step not before it, and what the server said of it under its key alone", () => {
    const problems: ContentProblem[] = [
      { code: "source_unknown", part: "steps", bindingId: "b7" },
      { code: "source_does_not_fit", part: "steps", bindingId: "b8" },
    ];

    const flags = flagsOf(
      {
        target: "x",
        source: { from: "step", stepKey: "s3", path: "y" },
        bindingId: "b7",
      },
      TEXT,
      flow,
      1,
      problems,
      sources,
    );

    expect(flags).toEqual([
      "workflow.pointsForward",
      "contentProblem.source_unknown",
    ]);
  });
});

describe("targetRows", () => {
  const ADDRESS: DeclaredField = {
    fieldId: "f5",
    name: "address",
    kind: "fields",
    many: false,
    mustBeGiven: true,
    fields: [
      { ...COMPLAINT, fieldId: "f6", name: "street" },
      { ...COMPLAINT, fieldId: "f7", name: "city" },
    ],
  };
  const TAKES = [ADDRESS, COMPLAINT];
  const judged = (binding: BindingDraft) =>
    binding.source.from === "constant"
      ? (["workflow.nothingFits"] as const)
      : [];
  const rows = (
    bindings: readonly BindingDraft[],
    problems: readonly ContentProblem[] = [],
  ) =>
    targetRows(
      TAKES,
      bindings,
      problems,
      judged,
      "contentProblem.input_unbound",
    );

  it("offers every field down through one holding fields, each at its depth, and says what nothing fills", () => {
    const drawn = rows([]);

    expect(drawn.map((row) => [row.path, row.depth])).toEqual([
      ["address", 0],
      ["address.street", 1],
      ["address.city", 1],
      ["complaint", 0],
    ]);
    expect(drawn.map((row) => row.flags)).toEqual([
      ["contentProblem.input_unbound"],
      [],
      [],
      ["contentProblem.input_unbound"],
    ]);
  });

  it("offers nothing below a field bound whole, and says nothing is unfilled there", () => {
    const whole: BindingDraft = {
      target: "address",
      source: { from: "input", path: "address" },
    };

    const drawn = rows([whole]);

    expect(drawn.map((row) => row.path)).toEqual(["address", "complaint"]);
    expect(drawn[0]!.binding).toBe(whole);
    expect(drawn[0]!.flags).toEqual([]);
  });

  it("says each field left unfilled within one partly filled, and not the field holding them", () => {
    const drawn = rows([
      { target: "address.city", source: { from: "constant", typed: "x" } },
    ]);

    expect(drawn.map((row) => [row.path, row.flags])).toEqual([
      ["address", []],
      ["address.street", ["contentProblem.input_unbound"]],
      ["address.city", ["workflow.nothingFits"]],
      ["complaint", ["contentProblem.input_unbound"]],
    ]);
  });

  it("says against a field what the server said of it, once, and not whether it is filled, which is judged here", () => {
    const drawn = rows(
      [{ target: "complaint", source: { from: "input", path: "complaint" } }],
      [
        { code: "input_unbound", part: "steps", fieldId: "f1" },
        { code: "target_unknown", part: "steps", fieldId: "f1" },
        { code: "target_unknown", part: "steps", fieldId: "f1" },
        { code: "no_fields_held", part: "steps", fieldId: "f5" },
      ],
    );

    expect(drawn[3]!.flags).toEqual(["contentProblem.target_unknown"]);
    expect(drawn[0]!.flags).toEqual([
      "contentProblem.no_fields_held",
      "contentProblem.input_unbound",
    ]);
  });
});

describe("pointsForward", () => {
  const flow = flowOf(WORKFLOW);

  it.each([
    [
      "an earlier step",
      { from: "step", stepKey: "s1", path: "category" },
      false,
    ],
    [
      "the step itself",
      { from: "step", stepKey: "s2", path: "category" },
      true,
    ],
    ["a later step", { from: "step", stepKey: "s3", path: "category" }, true],
    ["what the workflow takes", { from: "input", path: "complaint" }, false],
  ])(
    "judges a source reading %s from the second step",
    (_case, source, forward) => {
      expect(pointsForward(flow, 1, source as never)).toBe(forward);
    },
  );
});

describe("termsChosenBy", () => {
  const flow = flowOf(WORKFLOW);
  const route = routeOf(flow.steps[1]!);

  it("reads the terms of the list what a route chooses by pins, from a step or from what the workflow takes", () => {
    expect(termsChosenBy(route, flow, WORKFLOW, NAMED)).toEqual([
      "urgent",
      "later",
    ]);
    expect(
      termsChosenBy(
        { ...route, discriminator: { from: "input", path: "category" } },
        flow,
        { ...WORKFLOW, takes: [CATEGORY] },
        NAMED,
      ),
    ).toEqual(["urgent", "later"]);
  });

  it.each([
    ["nothing chosen", { from: "none" }],
    ["a field that is no term", { from: "input", path: "complaint" }],
    [
      "a step whose gives are not known",
      { from: "step", stepKey: "s3", path: "x" },
    ],
  ] as const)("knows no terms where it chooses by %s", (_case, source) => {
    expect(
      termsChosenBy({ ...route, discriminator: source }, flow, WORKFLOW, NAMED),
    ).toBeUndefined();
  });

  it("knows no terms of a list the page read none of", () => {
    expect(
      termsChosenBy(route, flow, { ...WORKFLOW, terms: new Map() }, NAMED),
    ).toBeUndefined();
  });
});

describe("mostCases", () => {
  it.each([
    ["one on each term, and a fallback", ["a", "b"], 3],
    ["a fallback alone where the list offers none", [], 1],
    [
      "any list's most and a fallback where the list is not known",
      undefined,
      257,
    ],
  ])("lets a route hold %s", (_case, terms, most) => {
    expect(mostCases(terms)).toBe(most);
  });
});

describe("movedStep", () => {
  const flow = flowWith(freshStep("a"), freshStep("b"), freshStep("c"));

  it.each([
    ["up", 1, -1, ["b", "a", "c"]],
    ["down", 1, 1, ["a", "c", "b"]],
  ])(
    "moves a step %s one row, every other where it was",
    (_case, index, by, keys) => {
      expect(
        movedStep(flow, index as number, by as -1 | 1).steps.map(
          (step) => step.key,
        ),
      ).toEqual(keys);
    },
  );

  it.each([
    ["the first up", 0, -1],
    ["the last down", 2, 1],
  ])("leaves the flow as it was when moving %s", (_case, index, by) => {
    expect(movedStep(flow, index, by as -1 | 1)).toBe(flow);
  });
});

describe("removedStep", () => {
  it("drops the step, and whatever read it, a step's, a case's, a route's or an output, reads nothing chosen", () => {
    const flow = flowOf(WORKFLOW);
    const reading: StepDraft = {
      ...flow.steps[2]!,
      bindings: [
        {
          target: "said",
          source: { from: "step", stepKey: "s1", path: "category" },
        },
      ],
    };
    const route = routeOf(flow.steps[1]!);
    const routing: StepDraft = {
      ...flow.steps[1]!,
      runs: {
        ...route,
        cases: [
          {
            ...route.cases[0]!,
            bindings: [
              {
                target: "note",
                source: { from: "step", stepKey: "s1", path: "category" },
              },
            ],
          },
        ],
      },
    };

    const left = removedStep(
      { ...flow, steps: [flow.steps[0]!, routing, reading] },
      0,
    );

    expect(left.steps.map((step) => step.key)).toEqual(["s2", "s3"]);
    expect(left.outputs[0]!.source).toEqual({ from: "none" });
    expect(routeOf(left.steps[0]!).discriminator).toEqual({ from: "none" });
    expect(routeOf(left.steps[0]!).cases[0]!.bindings[0]!.source).toEqual({
      from: "none",
    });
    expect(left.steps[1]!.bindings[0]!.source).toEqual({ from: "none" });
  });

  it("leaves what read another step reading it", () => {
    const flow = flowOf(WORKFLOW);

    const left = removedStep(flow, 2);

    expect(left.outputs[0]!.source).toEqual({
      from: "step",
      stepKey: "s1",
      path: "category",
    });
    expect(routeOf(left.steps[1]!).discriminator).toEqual({
      from: "step",
      stepKey: "s1",
      path: "category",
    });
  });
});

describe("tookVersion", () => {
  it("runs the version taken, keeping each binding whose target it declares and none it does not", () => {
    const asking: StepDraft = {
      ...flowOf(WORKFLOW).steps[0]!,
      bindings: [
        {
          target: "complaint",
          source: { from: "input", path: "complaint" },
          bindingId: "b1",
        },
        { target: "gone", source: { from: "input", path: "note" } },
      ],
    };

    const taken = tookVersion(asking, "q2", NAMED);

    expect(taken.runs).toEqual({ kind: "question", version: "q2" });
    expect(taken.bindings).toEqual([
      { target: "complaint", source: { from: "input", path: "complaint" } },
    ]);
    expect(taken.name).toBe("triage");
  });

  it("leaves a step running no pinned version as it was", () => {
    const tidy = flowOf(WORKFLOW).steps[2]!;

    expect(tookVersion(tidy, "q2", NAMED)).toBe(tidy);
  });
});

describe("caseTookVersion", () => {
  it("leads to the version taken, keeping each binding whose target it declares and none it does not", () => {
    const routeCase = {
      ...freshCase("k"),
      term: "urgent",
      workflow: "w1",
      bindings: [
        {
          target: "complaint",
          source: { from: "input", path: "complaint" },
          bindingId: "b3",
        },
        { target: "note", source: { from: "input", path: "note" } },
      ],
    } as const;

    const taken = caseTookVersion(routeCase, "q2", NAMED);

    expect(taken.workflow).toBe("q2");
    expect(taken.term).toBe("urgent");
    expect(taken.bindings).toEqual([
      { target: "complaint", source: { from: "input", path: "complaint" } },
    ]);
  });
});

describe("unusedKey", () => {
  it.each([
    ["the first", [], "case-1"],
    ["one past those held", ["case-1"], "case-2"],
    [
      "none held already, after one before it was taken out",
      ["case-2"],
      "case-3",
    ],
    ["one beside keys read", ["c1", "c2"], "case-3"],
    ["one past two held where it would start", ["case-3", "case-4"], "case-5"],
  ])("makes %s", (_case, keys, made) => {
    expect(unusedKey("case", keys)).toBe(made);
  });
});

describe("freshStep", () => {
  it("runs nothing and is reviewed by a person", () => {
    const step = freshStep("n1");

    expect(step.stepId).toBeNull();
    expect(step.runs).toEqual({ kind: "none" });
    expect(step.producer).toBe("");
    expect(step.reviewer).toBe("person");
    expect(step.bindings).toEqual([]);
  });
});

describe("freshCase", () => {
  it("is neither the fallback nor leads anywhere", () => {
    expect(freshCase("k1")).toEqual({
      key: "k1",
      caseId: null,
      fallback: false,
      term: "",
      workflow: "",
      bindings: [],
    });
  });
});

describe("boundTo", () => {
  const bindings = [
    { target: "a", source: { from: "input", path: "x" }, bindingId: "b1" },
    { target: "b", source: { from: "input", path: "y" } },
    { target: "c.d", source: { from: "input", path: "z" } },
  ] as const;

  it("puts the one for the target last, read under no key, every other kept", () => {
    expect(boundTo(bindings, "a", { from: "input", path: "z" })).toEqual([
      bindings[1],
      bindings[2],
      { target: "a", source: { from: "input", path: "z" } },
    ]);
  });

  it.each([
    ["what holds it", "c.d.e"],
    ["what it holds", "c"],
  ])(
    "takes away the one filling %s, which would fill it twice",
    (_case, target) => {
      const bound = boundTo(bindings, target, { from: "input", path: "w" });

      expect(bound.map((binding) => binding.target)).toEqual([
        "a",
        "b",
        target,
      ]);
    },
  );

  it("takes the one for the target alone away when nothing is chosen", () => {
    expect(boundTo(bindings, "c", { from: "none" })).toEqual(bindings);
    expect(boundTo(bindings, "a", { from: "none" })).toEqual([
      bindings[1],
      bindings[2],
    ]);
  });
});

describe("sentFlowOf", () => {
  it("sends what was read back as it was, each step by its key, a step read from by its place, and a constant as read", () => {
    const sent = sentFlowOf(flowOf(WORKFLOW), WORKFLOW, NAMED);

    expect(sent).toEqual({
      steps: [
        {
          stepId: "s1",
          name: "triage",
          runs: { kind: "question", version: "q1" },
          producer: {
            kind: "model",
            model: "general",
            mode: "research",
            toldWhatHappened: true,
          },
          tries: 2,
          reviewer: { model: "small", mode: null },
          bindings: [{ target: "complaint", source: { input: "complaint" } }],
        },
        {
          stepId: "s2",
          name: "route",
          runs: {
            kind: "route",
            discriminator: { step: 0, path: "category" },
            gives: [],
            cases: [
              {
                caseId: "c1",
                term: "urgent",
                workflow: "w1",
                bindings: [
                  { target: "note", source: { constant: '{"said":1}' } },
                ],
              },
              { caseId: "c2", term: null, workflow: null, bindings: [] },
            ],
          },
          bindings: [],
        },
        {
          stepId: "s3",
          name: "tidy",
          runs: { kind: "code_step", codeStep: "tidy_up" },
          producer: { kind: "code" },
          tries: null,
          reviewer: null,
          bindings: [{ target: "said", source: { constant: '"as typed"' } }],
        },
      ],
      outputs: [{ target: "category", source: { step: 0, path: "category" } }],
      held: null,
    });
  });

  it("sends a step reading one after it, which only submitting refuses", () => {
    const flow = flowWith(
      {
        ...freshStep("a"),
        name: "first",
        bindings: [
          { target: "x", source: { from: "step", stepKey: "b", path: "y" } },
        ],
      },
      { ...freshStep("b"), name: "second" },
    );

    const sent = sentFlowOf(flow, WORKFLOW, NAMED);

    expect(sent.held).toBeNull();
    expect(sent.steps[0]!.bindings).toEqual([
      { target: "x", source: { step: 1, path: "y" } },
    ]);
  });

  it("sends no binding whose source is chosen and nothing within it yet, as nothing filling it", () => {
    const flow = flowWith({
      ...freshStep("a"),
      name: "first",
      bindings: [
        { target: "x", source: { from: "input", path: "" } },
        { target: "y", source: { from: "input", path: "complaint" } },
      ],
    });

    const sent = sentFlowOf(flow, WORKFLOW, NAMED);

    expect(sent.steps[0]!.bindings).toEqual([
      { target: "y", source: { input: "complaint" } },
    ]);
    expect(sent.held).toBeNull();
  });

  it.each([
    ["text as itself, quoted", "text", "12", '"12"'],
    ["a number as its digits", "number", "12", "12"],
    [
      "a number of 38 digits to the digit, its trailing zero kept",
      "number",
      "-1234567890123456789.0123456789012345670",
      "-1234567890123456789.0123456789012345670",
    ],
  ] as const)(
    "sends a constant typed by what it fills as JSON text: %s",
    (_case, kind, typed, constant) => {
      const flow = flowWith({
        ...freshStep("a"),
        name: "asking",
        runs: { kind: "question", version: "q1" },
        bindings: [
          { target: "complaint", source: { from: "constant", typed } },
        ],
      });
      const named = new Map(NAMED).set("q1", {
        ...NAMED.get("q1")!,
        takes: [{ ...COMPLAINT, kind }],
      });

      const sent = sentFlowOf(flow, WORKFLOW, named);

      expect(sent.steps[0]!.bindings).toEqual([
        { target: "complaint", source: { constant } },
      ]);
      expect(sent.held).toBeNull();
    },
  );

  const asking: StepDraft = {
    ...freshStep("a"),
    name: "asking",
    runs: { kind: "question", version: "q1" },
  };

  const routing: StepDraft = {
    ...freshStep("r"),
    name: "routing",
    runs: {
      kind: "route",
      discriminator: { from: "none" },
      gives: [],
      cases: [{ ...freshCase("k"), term: "urgent" }],
    },
  };

  const route = routeOf(routing);

  it.each([
    [
      "a name the server refuses",
      { ...asking, name: "Asking" },
      "refusal.STEP_NAME_UNUSABLE",
    ],
    ["tries of none", { ...asking, tries: "0" }, "workflow.triesLimit"],
    [
      "tries past the largest",
      { ...asking, tries: "2147483648" },
      "workflow.triesLimit",
    ],
    [
      "a model to review and none chosen",
      { ...asking, reviewer: "model" },
      "workflow.reviewerUnchosen",
    ],
    [
      "a route's field named as the server refuses",
      {
        ...routing,
        runs: { ...route, gives: [{ ...draftOf(COMPLAINT), name: "" }] },
      },
      "refusal.FIELD_NAME_UNUSABLE",
    ],
    [
      "a case on no term that is not the fallback",
      { ...routing, runs: { ...route, cases: [freshCase("k")] } },
      "workflow.termLimit",
    ],
    [
      "two cases on one term",
      {
        ...routing,
        runs: {
          ...route,
          cases: [...route.cases, { ...freshCase("k2"), term: "urgent" }],
        },
      },
      "refusal.CASE_TERM_REPEATED",
    ],
    [
      "more cases than the list chosen by offers terms, and a fallback",
      {
        ...routing,
        runs: {
          ...route,
          discriminator: { from: "input", path: "category" },
          cases: [
            { ...freshCase("k1"), term: "urgent" },
            { ...freshCase("k2"), term: "later" },
            { ...freshCase("k3"), term: "soon" },
            { ...freshCase("k4"), fallback: true },
          ],
        },
      },
      "refusal.CASES_PAST_TERMS",
    ],
    [
      "a constant that is no value written out",
      {
        ...asking,
        bindings: [
          { target: "complaint.x", source: { from: "constant", typed: "{" } },
        ],
      },
      "workflow.constantUnreadable",
    ],
    [
      "a constant the server refuses on arrival",
      {
        ...asking,
        bindings: [
          {
            target: "complaint",
            source: {
              from: "constant",
              typed: "a" + String.fromCodePoint(0x202e) + "b",
            },
          },
        ],
      },
      "refusal.PROSE_DIRECTION_CONTROL",
    ],
    [
      "a step reading itself",
      {
        ...asking,
        bindings: [
          {
            target: "complaint",
            source: { from: "step", stepKey: "a", path: "category" },
          },
        ],
      },
      "workflow.sourceItself",
    ],
    [
      "a route choosing by itself",
      {
        ...routing,
        runs: {
          ...route,
          discriminator: { from: "step", stepKey: "r", path: "x" },
        },
      },
      "workflow.sourceItself",
    ],
    [
      "more bindings than what it runs declares fields",
      {
        ...asking,
        bindings: [
          { target: "complaint", source: { from: "input", path: "complaint" } },
          { target: "other", source: { from: "input", path: "complaint" } },
        ],
      },
      "refusal.BINDINGS_PAST_INPUTS",
    ],
    [
      "a route filling anything of its own",
      {
        ...routing,
        bindings: [
          { target: "x", source: { from: "input", path: "complaint" } },
        ],
      },
      "refusal.BINDINGS_PAST_INPUTS",
    ],
  ])("holds back a flow with %s, naming the step's row", (_case, step, why) => {
    const sent = sentFlowOf(
      flowWith(step as StepDraft),
      { ...WORKFLOW, takes: [COMPLAINT, CATEGORY] },
      NAMED,
    );

    expect(sent.held).toEqual({ why, step: (step as StepDraft).key });
  });

  it("holds nothing back of a flow the server takes on arrival", () => {
    const sent = sentFlowOf(
      flowWith(
        {
          ...asking,
          reviewer: "model",
          reviewerModel: "small",
          bindings: [
            {
              target: "complaint",
              source: { from: "input", path: "complaint" },
            },
          ],
        },
        {
          ...routing,
          runs: {
            ...route,
            discriminator: { from: "input", path: "category" },
            cases: [
              { ...freshCase("k1"), term: "urgent" },
              { ...freshCase("k2"), term: "later" },
              { ...freshCase("k4"), fallback: true },
            ],
          },
        },
      ),
      { ...WORKFLOW, takes: [COMPLAINT, CATEGORY] },
      NAMED,
    );

    expect(sent.held).toBeNull();
  });

  it("holds back more outputs than the workflow gives back fields, naming no step", () => {
    const sent = sentFlowOf(
      {
        steps: [],
        outputs: [
          { target: "category", source: { from: "input", path: "complaint" } },
          { target: "other", source: { from: "input", path: "complaint" } },
        ],
      },
      WORKFLOW,
      NAMED,
    );

    expect(sent.held).toEqual({ why: "refusal.BINDINGS_PAST_INPUTS" });
  });

  it("names the first thing held in reading order", () => {
    const sent = sentFlowOf(
      flowWith(
        { ...asking, name: "Asking", tries: "0" },
        { ...routing, name: "Routing" },
      ),
      WORKFLOW,
      NAMED,
    );

    expect(sent.held).toEqual({
      why: "refusal.STEP_NAME_UNUSABLE",
      step: "a",
    });
  });

  it.each([
    [
      "a question's model, once chosen",
      asking,
      "model",
      "general",
      {
        kind: "model",
        model: "general",
        mode: null,
        toldWhatHappened: false,
      },
    ],
    ["a question's model, none chosen yet, as none", asking, "model", "", null],
    ["a question's person", asking, "person", "", { kind: "person" }],
    ["code for a question, as none", asking, "code", "", null],
    [
      "a code step's code",
      { ...asking, runs: { kind: "code_step", codeStep: "" } },
      "code",
      "",
      { kind: "code" },
    ],
    [
      "a code step's person saying it was done",
      { ...asking, runs: { kind: "code_step", codeStep: "" } },
      "person",
      "",
      { kind: "person" },
    ],
    [
      "a model for a code step, as none",
      { ...asking, runs: { kind: "code_step", codeStep: "" } },
      "model",
      "general",
      null,
    ],
  ] as const)(
    "sends as who produces %s",
    (_case, step, producer, model, sent) => {
      const flow = flowWith({ ...step, producer, model });

      expect(sentFlowOf(flow, WORKFLOW, NAMED).steps[0]!.producer).toEqual(
        sent,
      );
    },
  );

  it("sends a reviewer only once a model is chosen, a person as none", () => {
    const sent = sentFlowOf(
      flowWith(
        { ...asking, reviewer: "model", reviewerModel: "small" },
        { ...asking, key: "p", name: "person" },
      ),
      WORKFLOW,
      NAMED,
    );

    expect(sent.steps.map((step) => step.reviewer)).toEqual([
      { model: "small", mode: null },
      null,
    ]);
  });
});

describe("triesFit", () => {
  it.each([
    ["1", true],
    ["2147483647", true],
    ["2147483648", false],
    ["0", false],
    ["01", false],
    ["", false],
    ["2.5", false],
  ])("judges %s", (typed, fitting) => {
    expect(triesFit(typed)).toBe(fitting);
  });
});

describe("termFits", () => {
  it.each([
    ["one character", "a", true],
    ["the most characters", "a".repeat(128), true],
    ["one past the most", "a".repeat(129), false],
    [
      "the most counted in code points",
      String.fromCodePoint(0x1f600).repeat(128),
      true,
    ],
    ["nothing", "", false],
    ["a line break", "a\nb", false],
    ["a C1 control", "a" + String.fromCodePoint(0x85) + "b", false],
  ])("judges %s", (_case, typed, fitting) => {
    expect(termFits(typed)).toBe(fitting);
  });
});
