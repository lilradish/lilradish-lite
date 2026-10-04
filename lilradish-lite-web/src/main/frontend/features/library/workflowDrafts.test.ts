import { describe, expect, it, vi } from "vitest";

import type { DeclaredField, PinnedList } from "../../api/declaration";
import type { ContentProblem } from "../../api/groups/{groupId}/{kind}/{entryId}/versions";
import type {
  OfferedCodeStep,
  Step,
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
  reusableSentFlowOf,
  searchTextRefused,
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
    searchWays: [],
    models: [],
  },
  terms: new Map([["l1", ["urgent", "later"]]]),
};

const SEARCH_WAY = {
  name: "web",
  tool: "web_search",
  gives: [{ ...COMPLAINT, fieldId: "f10", name: "title" }],
};

function searchingWorkflow(): WorkflowVersion {
  const search: Step = {
    stepId: "s4",
    name: "find",
    bindings: [],
    runs: {
      kind: "search",
      searchWay: "web",
      gives: SEARCH_WAY.gives,
      question: [
        { text: "Find " },
        { partId: "p1", from: { input: "complaint" } },
        { partId: "p2", from: { step: 0, path: "category" } },
      ],
      standings: { title: "always", body: "never" },
    },
    producer: { kind: "model", model: "general", toldWhatHappened: true },
    tries: 2,
    reviewer: { model: "small" },
  };
  return {
    ...WORKFLOW,
    steps: [...WORKFLOW.steps, search],
    offered: { ...WORKFLOW.offered, searchWays: [SEARCH_WAY] },
  };
}

const TEXT: Shape = {
  name: "said",
  kind: "text",
  many: false,
  mustBeGiven: true,
  longest: 100,
};

const NAMED = namedVersions(WORKFLOW);

const FILE: DeclaredField = {
  fieldId: "attachment",
  name: "attachment",
  kind: "file",
  types: ["application/pdf"],
  maximumBytes: 1024,
  many: true,
  most: 2,
  mustBeGiven: true,
};

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
    [
      "several lines into one",
      { ...TEXT, lines: "several" },
      { ...TEXT, lines: "one" },
      false,
    ],
    ["an older text into one", TEXT, { ...TEXT, lines: "one" }, false],
    ["one line into several", { ...TEXT, lines: "one" }, TEXT, true],
    [
      "fields holding several lines into one",
      { ...TEXT, kind: "fields", fields: [{ ...TEXT, lines: "several" }] },
      { ...TEXT, kind: "fields", fields: [{ ...TEXT, lines: "one" }] },
      false,
    ],
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
    [
      "a smaller file of an accepted type",
      { ...TEXT, kind: "file", types: ["application/pdf"], maximumBytes: 1024 },
      {
        ...TEXT,
        kind: "file",
        types: ["application/pdf", "image/png"],
        maximumBytes: 2048,
      },
      true,
    ],
    [
      "a file with another type",
      { ...TEXT, kind: "file", types: ["image/png"], maximumBytes: 1024 },
      { ...TEXT, kind: "file", types: ["application/pdf"], maximumBytes: 2048 },
      false,
    ],
    [
      "a file past the receiving byte limit",
      { ...TEXT, kind: "file", types: ["application/pdf"], maximumBytes: 4096 },
      { ...TEXT, kind: "file", types: ["application/pdf"], maximumBytes: 2048 },
      false,
    ],
    [
      "a draft file without a byte limit yet",
      { ...TEXT, kind: "file", types: ["application/pdf"] },
      { ...TEXT, kind: "file", types: ["application/pdf"], maximumBytes: 2048 },
      true,
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
  it.each([
    ["1", "bytes", 1],
    ["1", "KB", 1024],
    ["1", "MB", 1048576],
    ["1", "GB", 1073741824],
    ["9007199254740991", "bytes", Number.MAX_SAFE_INTEGER],
    ["", "bytes", undefined],
    ["0", "bytes", undefined],
    ["01", "bytes", undefined],
    ["1.5", "KB", undefined],
    ["9007199254740992", "bytes", undefined],
    ["8796093022208", "KB", undefined],
    ["8589934592", "MB", undefined],
    ["8388608", "GB", undefined],
  ] as const)(
    "projects a live route file size %s %s without inventing an unchosen limit",
    (fileSize, fileSizeUnit, maximumBytes) => {
      const route: StepDraft = {
        ...freshStep("files"),
        runs: {
          kind: "route",
          discriminator: { from: "none" },
          cases: [],
          gives: [
            {
              ...draftOf(FILE),
              fileTypes: " APPLICATION/PDF \n image/png ",
              fileSize,
              fileSizeUnit,
            },
          ],
        },
      };

      const [field] = givenBy(route, NAMED, [], [])!;

      expect(field).toMatchObject({
        types: ["application/pdf", "image/png"],
        most: 2,
        many: true,
      });
      expect(field!.maximumBytes).toBe(maximumBytes);
      if (maximumBytes === undefined) {
        expect(field).not.toHaveProperty("maximumBytes");
      }
      expect(field).not.toHaveProperty("fileSize");
      expect(field).not.toHaveProperty("fileSizeUnit");
    },
  );

  it("leaves live file types unchosen and does not leak file constraints into another kind", () => {
    const route: StepDraft = {
      ...freshStep("files"),
      runs: {
        kind: "route",
        discriminator: { from: "none" },
        cases: [],
        gives: [
          { ...draftOf(FILE), fileTypes: "", fileSize: "" },
          { ...draftOf(FILE), name: "count", kind: "number" },
          {
            ...draftOf(FILE),
            name: "receiving",
            fileTypes: "image/png",
            fileSize: "1",
            fileSizeUnit: "bytes",
          },
        ],
      },
    };
    const [unchosen, number, receiving] = givenBy(route, NAMED, [], [])!;

    expect(unchosen).not.toHaveProperty("types");
    expect(unchosen).not.toHaveProperty("maximumBytes");
    expect(fits(unchosen!, receiving!)).toBe(true);
    expect(number).toMatchObject({ kind: "number", name: "count" });
    expect(number).not.toHaveProperty("types");
    expect(number).not.toHaveProperty("maximumBytes");
  });

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
      givenBy(
        flowOf(WORKFLOW).steps[0]!,
        NAMED,
        WORKFLOW.offered.codeSteps,
        WORKFLOW.offered.searchWays,
      ),
    ).toEqual([
      {
        name: "category",
        kind: "term",
        many: false,
        mustBeGiven: true,
        list: "l1",
      },
    ]);
    expect(
      givenBy(
        route,
        NAMED,
        WORKFLOW.offered.codeSteps,
        WORKFLOW.offered.searchWays,
      ),
    ).toEqual([
      {
        name: "complaint",
        kind: "text",
        many: false,
        mustBeGiven: false,
        longest: 4000,
        lines: "several",
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
        WORKFLOW.offered.searchWays,
      ),
    ).toEqual([
      {
        name: "reference",
        kind: "text",
        many: false,
        mustBeGiven: true,
        longest: 12,
        lines: "several",
      },
    ]);
  });

  it("reads a code step the release does not offer, and a step running nothing yet, as unknown", () => {
    expect(
      givenBy(
        flowOf(WORKFLOW).steps[2]!,
        NAMED,
        WORKFLOW.offered.codeSteps,
        WORKFLOW.offered.searchWays,
      ),
    ).toBeUndefined();
    expect(
      givenBy(
        freshStep("n"),
        NAMED,
        WORKFLOW.offered.codeSteps,
        WORKFLOW.offered.searchWays,
      ),
    ).toBeUndefined();
  });
});

describe("sourcesFor", () => {
  const flow = flowOf(WORKFLOW);

  it("keeps a default step name distinct from a real name with the same words without sending presentation metadata", () => {
    const unnamed = {
      ...flow,
      steps: flow.steps.map((step, index) =>
        index === 0 ? { ...step, name: "" } : step,
      ),
    };
    const named = {
      ...unnamed,
      steps: unnamed.steps.map((step, index) =>
        index === 0 ? { ...step, name: "Step 1, not named yet" } : step,
      ),
    };
    const before = sourcesFor(unnamed, 1, WORKFLOW, NAMED, unnamed.steps[1]);
    const after = sourcesFor(named, 1, WORKFLOW, NAMED, named.steps[1]);
    expect(before[1]!.words).toBe(after[1]!.words);
    expect(before[1]!.step).toEqual({ position: 1, name: "" });
    expect(after[1]!.step).toEqual({
      position: 1,
      name: "Step 1, not named yet",
    });
    expect(before[0]!.step).toBeUndefined();
    expect(before[1]!.fields).toEqual(after[1]!.fields);
    expect(JSON.stringify(before)).not.toBe(JSON.stringify(after));
    const wire = sentFlowOf(named, WORKFLOW, NAMED);
    expect(wire.steps[0]!.name).toBe("Step 1, not named yet");
    expect(wire.steps[0]).not.toHaveProperty("step");
    expect(wire.steps[0]).not.toHaveProperty("words");
  });

  it.each(
    (
      [
        [
          "more target types",
          FILE,
          { ...FILE, types: ["application/pdf", "image/png"] },
          true,
        ],
        [
          "more source types",
          { ...FILE, types: ["application/pdf", "image/png"] },
          FILE,
          false,
        ],
        ["larger source", { ...FILE, maximumBytes: 1025 }, FILE, false],
        ["more source files", { ...FILE, most: 3 }, FILE, false],
        ["equal limits", FILE, FILE, true],
        [
          "one into many",
          { ...FILE, many: false, most: undefined },
          FILE,
          false,
        ],
        [
          "many into one",
          FILE,
          { ...FILE, many: false, most: undefined },
          false,
        ],
        [
          "a nested larger source",
          {
            fieldId: "details",
            name: "attachment",
            kind: "fields",
            many: false,
            mustBeGiven: true,
            fields: [{ ...FILE, maximumBytes: 1025 }],
          },
          {
            fieldId: "details",
            name: "attachment",
            kind: "fields",
            many: false,
            mustBeGiven: true,
            fields: [FILE],
          },
          false,
        ],
      ] satisfies readonly (readonly [
        string,
        DeclaredField,
        DeclaredField,
        boolean,
      ])[]
    ).flatMap(([name, source, target, accepted]) =>
      (["input", "step"] as const).map(
        (from) =>
          [`${name} (${from})`, source, target, accepted, from] as const,
      ),
    ),
  )(
    "judges %s from real file projections when building target rows",
    (_case, sourceField, targetField, accepted, from) => {
      const declared = sourceField;
      const target = targetField;
      const route: StepDraft = {
        ...freshStep("files"),
        name: "Demo Sample",
        runs: {
          kind: "route",
          discriminator: { from: "none" },
          gives: [draftOf(declared)],
          cases: [],
        },
      };
      const binding: BindingDraft = {
        bindingId: "bound-file",
        target: "attachment",
        source:
          from === "input"
            ? { from, path: "attachment" }
            : { from: "step", stepKey: route.key, path: "attachment" },
      };
      const reader = { ...freshStep("reader"), bindings: [binding] };
      const filing = flowWith(route, reader);
      const workflow = { ...WORKFLOW, takes: [declared] };
      const sources = sourcesFor(filing, 1, workflow, NAMED, reader);
      const rows = targetRows(
        [target],
        [binding],
        [],
        (bound, shape) => flagsOf(bound, shape, filing, 1, [], sources),
        "contentProblem.input_unbound",
      );

      expect(sources.map((each) => each.value)).toEqual(["input", "files"]);
      expect(rows).toHaveLength(1);
      expect(rows[0]!.binding).toBe(binding);
      expect(rows[0]!.flags).toEqual(
        accepted ? [] : ["contentProblem.source_does_not_fit"],
      );
      expect(rows[0]!.flags).not.toContain(
        "contentProblem.source_may_be_empty",
      );
      expect(rows[0]!.flags).not.toContain("contentProblem.input_unbound");
    },
  );

  it.each([false, true])(
    "judges required file values separately from their fit, nested %s",
    (nested) => {
      const source: DeclaredField = nested
        ? {
            fieldId: "details",
            name: "attachment",
            kind: "fields",
            many: false,
            mustBeGiven: true,
            fields: [{ ...FILE, mustBeGiven: false }],
          }
        : { ...FILE, mustBeGiven: false };
      const target: DeclaredField = nested
        ? {
            fieldId: "details",
            name: "attachment",
            kind: "fields",
            many: false,
            mustBeGiven: true,
            fields: [FILE],
          }
        : FILE;
      const binding: BindingDraft = {
        target: "attachment",
        source: { from: "input", path: "attachment" },
      };
      const reader = { ...freshStep("reader"), bindings: [binding] };
      const filing = flowWith(reader);
      const sources = sourcesFor(
        filing,
        0,
        { ...WORKFLOW, takes: [source] },
        NAMED,
        reader,
      );
      const rows = targetRows(
        [target],
        [binding],
        [],
        (bound, shape) => flagsOf(bound, shape, filing, 0, [], sources),
        "contentProblem.input_unbound",
      );

      expect(fits(sources[0]!.fields![0]!, rows[0]!.target)).toBe(true);
      expect(rows[0]!.flags).toEqual(["contentProblem.source_may_be_empty"]);
      expect(rows[0]!.flags).not.toContain(
        "contentProblem.source_does_not_fit",
      );
    },
  );

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

  it("names a removed known field while leaving an unknown declaration undecided", () => {
    const removed = reading("removed");
    expect(flagsOf(removed, TEXT, flow, 1, [], sources)).toEqual([
      "contentProblem.source_unknown",
    ]);
    expect(
      flagsOf(
        removed,
        TEXT,
        flow,
        1,
        [],
        [
          {
            value: "input",
            words: "What the workflow takes",
            fields: undefined,
          },
        ],
      ),
    ).toEqual([]);
  });

  it("says when a retained source no longer fits without repeating a server warning", () => {
    const offered: SourceOffered[] = [
      {
        value: "input",
        words: "What the workflow takes",
        fields: [
          { ...TEXT, name: "note", lines: "several", mustBeGiven: false },
        ],
      },
    ];
    const binding: BindingDraft = {
      target: "note",
      source: { from: "input", path: "note" },
      bindingId: "b1",
    };
    const target = { ...TEXT, name: "note", lines: "one" as const };

    expect(flagsOf(binding, target, flow, 1, [], offered)).toEqual([
      "contentProblem.source_does_not_fit",
      "contentProblem.source_may_be_empty",
    ]);
    expect(
      flagsOf(
        binding,
        target,
        flow,
        1,
        [{ code: "source_does_not_fit", part: "steps", bindingId: "b1" }],
        offered,
      ),
    ).toEqual([
      "contentProblem.source_does_not_fit",
      "contentProblem.source_may_be_empty",
    ]);
  });

  it("shows a kept source that stops fitting after a step or route case takes a newer version", () => {
    const version: WorkflowVersion = {
      ...WORKFLOW,
      offered: {
        ...WORKFLOW.offered,
        questions: [
          {
            ...WORKFLOW.offered.questions[0]!,
            takes: [{ ...COMPLAINT, lines: "one" }],
          },
        ],
        workflows: [
          {
            ...WORKFLOW.offered.workflows[0]!,
            takes: [{ ...NOTE, lines: "one" }],
          },
        ],
      },
    };
    const named = namedVersions(version);
    const flow = flowOf(version);
    const step = tookVersion(
      {
        ...flow.steps[0]!,
        bindings: [
          {
            target: "complaint",
            source: { from: "input", path: "complaint" },
            bindingId: "b1",
          },
        ],
      },
      "q2",
      named,
    );
    const stepped = { ...flow, steps: [step, ...flow.steps.slice(1)] };
    const stepSources = sourcesFor(stepped, 0, version, named, step);
    const stepRows = targetRows(
      named.get("q2")!.takes,
      step.bindings,
      [],
      (binding, target) =>
        flagsOf(binding, target, stepped, 0, [], stepSources),
      "contentProblem.input_unbound",
    );

    expect(step.bindings).toEqual([
      {
        target: "complaint",
        source: { from: "input", path: "complaint" },
      },
    ]);
    expect(stepRows[0]?.flags).toEqual(["contentProblem.source_does_not_fit"]);

    const route = flow.steps[1]!;
    if (route.runs.kind !== "route") {
      throw new Error("Expected a route step");
    }
    const routeCase = caseTookVersion(
      {
        ...route.runs.cases[0]!,
        bindings: [
          {
            target: "note",
            source: { from: "input", path: "note" },
            bindingId: "b3",
          },
        ],
      },
      "w2",
      named,
    );
    const routed = {
      ...route,
      runs: {
        ...route.runs,
        cases: [routeCase, ...route.runs.cases.slice(1)],
      },
    };
    const routedFlow = {
      ...flow,
      steps: flow.steps.map((each, index) => (index === 1 ? routed : each)),
    };
    const routeSources = sourcesFor(routedFlow, 1, version, named, routed);
    const caseRows = targetRows(
      named.get("w2")!.takes,
      routeCase.bindings,
      [],
      (binding, target) =>
        flagsOf(binding, target, routedFlow, 1, [], routeSources),
      "contentProblem.input_unbound",
    );

    expect(routeCase.bindings).toEqual([
      { target: "note", source: { from: "input", path: "note" } },
    ]);
    expect(caseRows[0]?.flags).toEqual(["contentProblem.source_does_not_fit"]);
  });

  it("says nothing where what it reads is always there, or where what it fills need not be given", () => {
    expect(
      flagsOf(
        reading("complaint"),
        { ...TEXT, longest: 4000 },
        flow,
        1,
        [],
        sources,
      ),
    ).toEqual([]);
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

  it("does not let an emptied source occupy a whole field or a nested part", () => {
    const drawn = rows([
      { target: "address.city", source: { from: "none" } },
      { target: "complaint", source: { from: "input", path: "" } },
    ]);
    expect(drawn.map((row) => [row.path, row.flags])).toEqual([
      ["address", ["contentProblem.input_unbound"]],
      ["address.street", []],
      ["address.city", []],
      ["complaint", ["contentProblem.input_unbound"]],
    ]);
    expect(drawn[2]!.binding?.source).toEqual({ from: "none" });
    expect(drawn[3]!.binding?.source).toEqual({ from: "input", path: "" });
    expect(drawn[3]!.flags).toContain("contentProblem.input_unbound");
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
  it("sends a search's ordered text and values without read part keys, with every standing and ordinary review settings", () => {
    const workflow = searchingWorkflow();
    const flow = flowOf(workflow);
    const search = flow.steps[3]!;

    expect(search.runs.kind).toBe("search");
    if (search.runs.kind !== "search") throw new Error("search missing");
    expect(search.runs.question).toEqual([
      { key: "text-0", text: "Find " },
      { key: "p1", partId: "p1", source: { from: "input", path: "complaint" } },
      {
        key: "p2",
        partId: "p2",
        source: { from: "step", stepKey: "s1", path: "category" },
      },
    ]);
    expect(
      givenBy(
        search,
        namedVersions(workflow),
        workflow.offered.codeSteps,
        workflow.offered.searchWays,
      ),
    ).toEqual([
      {
        name: "title",
        kind: "text",
        many: false,
        mustBeGiven: true,
        longest: 4000,
        lines: "several",
      },
    ]);
    const sent = sentFlowOf(flow, workflow, namedVersions(workflow));

    expect(sent.held).toBeNull();
    expect(sent.steps[3]).toEqual({
      stepId: "s4",
      name: "find",
      bindings: [],
      runs: {
        kind: "search",
        searchWay: "web",
        question: [
          { text: "Find " },
          { from: { input: "complaint" } },
          { from: { step: 0, path: "category" } },
        ],
        standings: {
          title: "always",
          body: "never",
          references: null,
          other: null,
        },
      },
      producer: {
        kind: "model",
        model: "general",
        mode: null,
        toldWhatHappened: true,
      },
      tries: 2,
      reviewer: { model: "small", mode: null },
    });
  });

  it("keeps a search read tied to its step when order changes and unchooses it when that step is removed", () => {
    const workflow = searchingWorkflow();
    const flow = flowOf(workflow);
    const moved = movedStep(flow, 3, -1);
    const search = moved.steps[2]!;
    const removed = removedStep(flow, 0).steps[2]!;

    expect(search.runs.kind).toBe("search");
    if (search.runs.kind !== "search") throw new Error("search missing");
    expect(search.runs.question[2]).toEqual({
      key: "p2",
      partId: "p2",
      source: { from: "step", stepKey: "s1", path: "category" },
    });
    expect(removed.runs.kind).toBe("search");
    if (removed.runs.kind !== "search") throw new Error("search missing");
    expect(removed.runs.question[1]).toEqual({
      key: "p1",
      partId: "p1",
      source: { from: "input", path: "complaint" },
    });
    expect(removed.runs.question[2]).toEqual({
      key: "p2",
      source: { from: "none" },
    });
  });

  it("takes 256 question parts and refuses a 257th before sending", () => {
    const workflow = searchingWorkflow();
    const flow = flowOf(workflow);
    const search = flow.steps[3]!;
    if (search.runs.kind !== "search") throw new Error("search missing");
    const question = Array.from({ length: 256 }, (_part, index) => ({
      key: `text-${index}`,
      text: "x",
    }));
    const withParts = (count: number): FlowDraft => ({
      ...flow,
      steps: flow.steps.map((step, index) =>
        index === 3
          ? {
              ...search,
              runs: { ...search.runs, question: question.slice(0, count) },
            }
          : step,
      ),
    });

    expect(
      sentFlowOf(withParts(256), workflow, namedVersions(workflow)).held,
    ).toBeNull();
    const tooMany = [...question, { key: "extra", text: "x" }];
    const over = {
      ...flow,
      steps: flow.steps.map((step, index) =>
        index === 3
          ? { ...search, runs: { ...search.runs, question: tooMany } }
          : step,
      ),
    };
    expect(sentFlowOf(over, workflow, namedVersions(workflow)).held?.why).toBe(
      "workflow.searchPartsFull",
    );
  });

  it("builds search sources and reads each declaration at most once per step at the question limit", () => {
    const input: DeclaredField = {
      ...COMPLAINT,
      fieldId: "query",
      name: "query",
      longest: 16,
    };
    const returned = ["title", "body", "references", "other"].map(
      (name): DeclaredField => ({
        ...input,
        fieldId: name,
        name,
        stands: "always",
      }),
    );
    let sourceReads = 0;
    let declarationReads = 0;
    const workflow: WorkflowVersion = {
      ...WORKFLOW,
      get takes() {
        sourceReads += 1;
        return [input];
      },
      gives: [],
      steps: [],
      outputs: [],
      offered: {
        ...WORKFLOW.offered,
        searchWays: [
          {
            name: "web",
            tool: "web_search",
            get gives() {
              declarationReads += 1;
              return returned;
            },
          },
        ],
      },
    };
    const flow: FlowDraft = {
      steps: Array.from({ length: 256 }, (_step, index) => ({
        ...freshStep(`step-${index}`),
        name: `step_${index}`,
        producer: "person",
        tries: "1",
        runs: {
          kind: "search",
          searchWay: "web",
          standings: {
            title: "always",
            body: "always",
            references: "always",
            other: "always",
          },
          question: Array.from({ length: 256 }, (_part, part) => ({
            key: `part-${part}`,
            source: { from: "input", path: "query" },
          })),
        },
      })),
      outputs: [],
    };

    const sent = sentFlowOf(flow, workflow, new Map());

    expect(sent.held).toBeNull();
    expect(sent.steps).toHaveLength(256);
    expect(
      sent.steps.every(
        (step) =>
          step.runs?.kind === "search" &&
          step.runs.question.length === 256 &&
          step.runs.question.every(
            (part) =>
              "from" in part &&
              "input" in part.from &&
              part.from.input === "query",
          ),
      ),
    ).toBe(true);
    expect([sourceReads, declarationReads]).toEqual([256, 255]);
  }, 180000);

  it("reuses unaffected compiled steps while changing the last of 256, and rechecks after reordering", () => {
    let runsRead = 0;
    const steps = Array.from({ length: 256 }, (_step, index): StepDraft => {
      const fresh = freshStep(`step-${index}`);
      return {
        ...fresh,
        name: `step_${index}`,
        get runs() {
          runsRead += 1;
          return fresh.runs;
        },
      };
    });
    const flow: FlowDraft = { steps, outputs: [] };
    const send = reusableSentFlowOf();
    const first = send(flow, WORKFLOW, NAMED);
    expect(runsRead).toBeGreaterThanOrEqual(256);
    expect(first.steps).toEqual(sentFlowOf(flow, WORKFLOW, NAMED).steps);

    runsRead = 0;
    const edited: FlowDraft = {
      ...flow,
      steps: [...steps.slice(0, -1), { ...steps[255]!, name: "changed" }],
    };
    const second = send(edited, WORKFLOW, NAMED);
    expect(runsRead).toBeLessThan(8);
    expect(second.steps).toEqual(sentFlowOf(edited, WORKFLOW, NAMED).steps);

    runsRead = 0;
    const moved = movedStep(edited, 0, 1);
    const reordered = send(moved, WORKFLOW, NAMED);
    expect(runsRead).toBeGreaterThanOrEqual(254);
    expect(reordered.steps).toEqual(sentFlowOf(moved, WORKFLOW, NAMED).steps);
  });

  it("rechecks a later step when the earlier step it reads changes", () => {
    let laterReads = 0;
    const initial = flowOf(WORKFLOW);
    const later = initial.steps[1]!;
    const reading: StepDraft = {
      ...later,
      get runs() {
        laterReads += 1;
        return later.runs;
      },
    };
    const flow = {
      ...initial,
      steps: [initial.steps[0]!, reading, ...initial.steps.slice(2)],
    };
    const send = reusableSentFlowOf();
    send(flow, WORKFLOW, NAMED);
    laterReads = 0;

    const changed = {
      ...flow,
      steps: [
        { ...flow.steps[0]!, runs: { kind: "none" } as const },
        ...flow.steps.slice(1),
      ],
    };
    const sent = send(changed, WORKFLOW, NAMED);
    expect(laterReads).toBeGreaterThan(0);
    expect(sent).toEqual(sentFlowOf(changed, WORKFLOW, NAMED));
  });

  it("refuses a file in a search value while keeping text and earlier values available", () => {
    const workflow = searchingWorkflow();
    const file: DeclaredField = {
      fieldId: "f11",
      name: "attachment",
      kind: "file",
      many: false,
      mustBeGiven: true,
      maximumBytes: 100,
      types: ["text/plain"],
    };
    const withFile = { ...workflow, takes: [...workflow.takes, file] };
    const flow = flowOf(withFile);
    const search = flow.steps[3]!;
    if (search.runs.kind !== "search") throw new Error("search missing");
    const changed = {
      ...flow,
      steps: flow.steps.map((step, index) =>
        index === 3
          ? {
              ...search,
              runs: {
                ...search.runs,
                question: [
                  { key: "text", text: "Find " },
                  {
                    key: "file",
                    source: { from: "input" as const, path: "attachment" },
                  },
                ],
              },
            }
          : step,
      ),
    };

    expect(
      sentFlowOf(changed, withFile, namedVersions(withFile)).held?.why,
    ).toBe("contentProblem.question_takes_file");
    expect(sentFlowOf(flow, withFile, namedVersions(withFile)).held).toBeNull();
  });

  it("refuses written text past its bounds and concealed characters before the general refusal", () => {
    expect(searchTextRefused(" ")).toBeNull();
    expect(searchTextRefused("a".repeat(8192))).toBeNull();
    expect(searchTextRefused("")).toBe("refusal.SEARCH_TEXT_UNUSABLE");
    expect(searchTextRefused("a".repeat(8193))).toBe(
      "refusal.SEARCH_TEXT_UNUSABLE",
    );
    expect(searchTextRefused("\u202e" + "a".repeat(8193))).toBe(
      "refusal.PROSE_DIRECTION_CONTROL",
    );
    expect(searchTextRefused("\r\n" + "a".repeat(8193))).toBe(
      "refusal.PROSE_LINE_BREAK_CRLF",
    );
    expect(
      searchTextRefused(String.fromCodePoint(0xe0001) + "a".repeat(8193)),
    ).toBe("refusal.PROSE_TAG_CHARACTER");
  });

  it("refuses a saved constant's line break when its target now takes one line", () => {
    const typed = `a${String.fromCodePoint(0x0a)}b`;
    const read = JSON.stringify(typed);
    const flow = flowWith({
      ...freshStep("a"),
      name: "asking",
      runs: { kind: "question", version: "q1" },
      bindings: [
        { target: "complaint", source: { from: "constant", typed, read } },
      ],
    });
    const named = new Map(NAMED).set("q1", {
      ...NAMED.get("q1")!,
      takes: [{ ...COMPLAINT, lines: "one" }],
    });

    const sent = sentFlowOf(flow, WORKFLOW, named);

    expect(sent.held).toEqual({ why: "fill.line_break", step: "a" });
    expect(sent.steps[0]!.bindings).toEqual([]);
    expect(flow.steps[0]!.bindings[0]!.source).toEqual({
      from: "constant",
      typed,
      read,
    });
  });

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

  it("stops converting route gives at a sixty-fifth file type and sends sixty-four", () => {
    const flow = flowOf(WORKFLOW);
    const route = routeOf(flow.steps[1]!);
    const names = Array.from(
      { length: 65 },
      (_, index) => `application/x${index}`,
    );
    const tooMany = `${names.join("\n")}\n${"application/after\n".repeat(1024)}`;
    const file = draftOf({
      fieldId: "file",
      name: "documents",
      kind: "file",
      types: ["application/pdf"],
      maximumBytes: 1024,
      many: false,
      mustBeGiven: true,
    });
    const withTypes = (fileTypes: string): FlowDraft => ({
      ...flow,
      steps: flow.steps.map((step, index) =>
        index === 1
          ? {
              ...step,
              runs: { ...route, gives: [{ ...file, fileTypes }] },
            }
          : step,
      ),
    });
    const invalid = withTypes(tooMany);
    const split = vi.spyOn(String.prototype, "split");
    try {
      const sent = sentFlowOf(invalid, WORKFLOW, NAMED);

      expect(sent.held).toEqual({
        why: "refusal.FILE_TYPES_TOO_MANY",
        step: "s2",
      });
      expect(
        split.mock.calls.some(
          ([separator], index) =>
            String(separator) === "\n" &&
            String(split.mock.contexts[index]) === tooMany,
        ),
      ).toBe(false);
      expect(invalid.steps[1]!.runs).toMatchObject({
        gives: [{ fileTypes: tooMany }],
      });
      expect(sent.steps[1]!.runs).toMatchObject({
        kind: "route",
        discriminator: { step: 0, path: "category" },
        gives: [],
        cases: [
          { term: "urgent", workflow: "w1" },
          { term: null, workflow: null },
        ],
      });
    } finally {
      split.mockRestore();
    }

    const valid = sentFlowOf(
      withTypes(names.slice(0, 64).join("\n")),
      WORKFLOW,
      NAMED,
    );
    expect(valid.held).toBeNull();
    expect(valid.steps[1]!.runs).toMatchObject({
      kind: "route",
      discriminator: { step: 0, path: "category" },
      gives: [{ kind: "file", types: names.slice(0, 64) }],
      cases: [
        { term: "urgent", workflow: "w1" },
        { term: null, workflow: null },
      ],
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
