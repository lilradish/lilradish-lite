import { describe, expect, it, vi } from "vitest";

import type { DeclaredField, PinnedList } from "../../api/declaration";
import type { VersionDetail } from "../../api/groups/{groupId}/versions/{versionId}";
import type { ContentProblem } from "../../api/groups/{groupId}/{kind}/{entryId}/versions";
import type {
  OfferedCodeStep,
  Step,
  WorkflowVersion,
} from "../../api/groups/{groupId}/{kind}/{entryId}/versions/{versionId}";
import { draftOf } from "./declarationDrafts";
import { nameFits } from "../../lib/text/legibility";
import {
  boundTo,
  caseTookVersion,
  fits,
  bindingFlagsFor,
  flowOf,
  freshCase,
  freshStep,
  labelClashOf,
  givenBy,
  mostCases,
  movedStep,
  namedVersions,
  pointers,
  pointsForward,
  removedStep,
  referencedFieldDetails,
  referencedVersionDetails,
  reusableSentFlowOf,
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
  versionName: null,
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
  versionName: null,
  name: "Triage",
  versionId: "q1",
  number: 2,
  standing: "in_service",
  newer: { versionName: null, versionId: "q2", number: 3 },
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
  versionName: null,
  name: "Escalate",
  versionId: "w1",
  number: 1,
  standing: "in_service",
  newer: { versionName: null, versionId: "w2", number: 2 },
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
    bindings: [
      { bindingId: "p1", target: "complaint", source: { input: "complaint" } },
      {
        bindingId: "p2",
        target: "category",
        source: { step: 0, path: "category" },
      },
    ],
    runs: {
      kind: "search",
      version: {
        versionName: null,
        name: "Find evidence",
        versionId: "q-search",
        number: 1,
        standing: "in_service",
      },
      takes: [COMPLAINT, CATEGORY],
      searchWay: "web",
      gives: SEARCH_WAY.gives,
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
NAMED.set("q2", {
  versionName: null,
  name: "Triage",
  number: 3,
  takes: [{ ...COMPLAINT, fieldId: "f9" }],
  gives: [CATEGORY],
});
NAMED.set("w2", {
  versionName: null,
  name: "Escalate",
  number: 2,
  takes: [],
  gives: [],
});

describe("details held by a draft", () => {
  function detailsFor(...ids: string[]): Map<string, VersionDetail> {
    return new Map(
      ids.map((versionId) => [
        versionId,
        {
          kind: "reference_list",
          version: { ...LIST, versionId, standing: "retired" },
          terms: new Map([[versionId, ["urgent", "later"]]]),
        },
      ]),
    );
  }

  it("keeps a shared retired list until its last nested field is removed", () => {
    const category = draftOf(CATEGORY);
    const record = draftOf({
      fieldId: "record",
      name: "decision",
      kind: "fields",
      many: false,
      mustBeGiven: true,
      fields: [CATEGORY],
    });
    const details = detailsFor("l1", "discarded");
    const twoReferences = referencedFieldDetails([category, record], details);
    expect([...twoReferences.keys()]).toEqual(["l1"]);
    expect(twoReferences.get("l1")).toBe(details.get("l1"));
    const nestedOnly = referencedFieldDetails([record], twoReferences);
    expect(nestedOnly).toBe(twoReferences);
    expect([...referencedFieldDetails([], nestedOnly).keys()]).toEqual([]);
  });

  it("does not invalidate detail identity for unrelated text or a field-kind round trip", () => {
    const field = draftOf(CATEGORY);
    const details = detailsFor("l1");
    expect(
      referencedFieldDetails(
        [{ ...field, name: "renamed", kind: "text" }],
        details,
      ),
    ).toBe(details);
    expect(referencedFieldDetails([field], details)).toBe(details);
  });

  it("keeps repeated step, case and nested-list references, then releases removed owners", () => {
    const question = {
      ...freshStep("q"),
      runs: { kind: "question", version: "q1" },
    } as const;
    const repeat = { ...question, key: "repeat" };
    const route = {
      ...freshStep("route"),
      runs: {
        kind: "route",
        discriminator: { from: "none" },
        gives: [draftOf(CATEGORY)],
        cases: [{ ...freshCase("case"), workflow: "w1" }],
      },
    } as const;
    const flow = { steps: [question, repeat, route], outputs: [] };
    const details = detailsFor("q1", "w1", "l1", "discarded");
    const held = referencedVersionDetails(flow, details);
    expect([...held.keys()]).toEqual(["q1", "w1", "l1"]);
    expect(
      referencedVersionDetails({ ...flow, steps: [repeat, route] }, held),
    ).toBe(held);
    const withoutRoute = referencedVersionDetails(
      { ...flow, steps: [repeat] },
      held,
    );
    expect([...withoutRoute.keys()]).toEqual(["q1"]);
    expect([
      ...referencedVersionDetails(
        { steps: [], outputs: [] },
        withoutRoute,
      ).keys(),
    ]).toEqual([]);
  });

  it("keeps different route owners and inactive nested retired fields until their last owner is removed", () => {
    const inactive = {
      ...draftOf(CATEGORY),
      kind: "text" as const,
      list: "retired-list",
    };
    const nested = {
      ...draftOf(CATEGORY),
      kind: "fields" as const,
      list: "",
      fields: [inactive],
    };
    const first = {
      ...freshStep("first"),
      runs: {
        kind: "route" as const,
        discriminator: { from: "none" as const },
        gives: [nested],
        cases: [{ ...freshCase("a"), workflow: "retired-workflow" }],
      },
    };
    const second = {
      ...first,
      key: "second",
      runs: {
        ...first.runs,
        cases: [{ ...freshCase("b"), workflow: "other-workflow" }],
      },
    };
    const details = detailsFor(
      "retired-list",
      "retired-workflow",
      "other-workflow",
      "discarded",
    );
    const kept = referencedVersionDetails(
      { steps: [first, second], outputs: [] },
      details,
    );
    expect([...kept.keys()]).toEqual([
      "retired-list",
      "retired-workflow",
      "other-workflow",
    ]);
    for (const [id, detail] of kept) expect(detail).toBe(details.get(id));
    expect(
      referencedVersionDetails({ steps: [first, second], outputs: [] }, kept),
    ).toBe(kept);
    const remaining = referencedVersionDetails(
      { steps: [second], outputs: [] },
      kept,
    );
    expect([...remaining.keys()]).toEqual(["retired-list", "other-workflow"]);
    expect(remaining.get("retired-list")).toBe(details.get("retired-list"));
    expect(
      referencedVersionDetails({ steps: [], outputs: [] }, remaining).size,
    ).toBe(0);
  });
});

describe("namedVersions", () => {
  it.each([null, "اسم 📦"])(
    "keeps the current pinned name %s beside its identifier without changing sent bindings",
    (versionName) => {
      const first = WORKFLOW.steps[0]!;
      const workflow: WorkflowVersion = {
        ...WORKFLOW,
        steps: [
          {
            ...first,
            runs: { ...first.runs!, version: { ...TRIAGE, versionName } },
          },
          ...WORKFLOW.steps.slice(1),
        ],
      };
      const named = namedVersions(workflow);
      expect(named.get(TRIAGE.versionId)).toEqual({
        name: TRIAGE.name,
        number: TRIAGE.number,
        versionName,
        takes: [COMPLAINT],
        gives: [CATEGORY],
        pinned: { ...TRIAGE, versionName },
      });
      expect(named.has("q2")).toBe(false);
      expect(sentFlowOf(flowOf(workflow), workflow, named)).toEqual(
        sentFlowOf(flowOf(WORKFLOW), WORKFLOW, NAMED),
      );
    },
  );
});

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
      label: "",
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
  it("names actual pins independently of versions resolved after a candidate selection", () => {
    const pinned = namedVersions(WORKFLOW);
    expect([...pinned.keys()].sort()).toEqual(["q1", "w1"]);
    expect(pinned.get("q1")).toEqual({
      name: "Triage",
      number: 2,
      versionName: null,
      takes: [COMPLAINT],
      gives: [CATEGORY],
      pinned: TRIAGE,
    });
    expect(pinned.has("q2")).toBe(false);
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

      const [field] = givenBy(route, NAMED, [])!;

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
    const [unchosen, number, receiving] = givenBy(route, NAMED, [])!;

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
      givenBy(flowOf(WORKFLOW).steps[2]!, NAMED, WORKFLOW.offered.codeSteps),
    ).toBeUndefined();
    expect(
      givenBy(freshStep("n"), NAMED, WORKFLOW.offered.codeSteps),
    ).toBeUndefined();
  });
});

describe("sourcesFor", () => {
  const flow = flowOf(WORKFLOW);

  it.each(["route", "question", "workflow", "search", "code_step"] as const)(
    "projects human labels from the actual %s source without changing field names",
    (kind) => {
      const fields: DeclaredField[] = [
        { ...COMPLAINT, name: "title", label: "Returned title" },
        { ...NOTE, name: "body", label: "Returned body" },
      ];
      const labelled: WorkflowVersion = {
        ...WORKFLOW,
        takes: [],
        steps: [
          {
            stepId: "writer",
            name: "writer",
            runs:
              kind === "route"
                ? {
                    kind,
                    discriminator: {
                      bindingId: "discriminator",
                      source: { constant: '"urgent"' },
                    },
                    gives: fields,
                    cases: [],
                  }
                : kind === "code_step"
                  ? { kind, codeStep: STAMPING.name }
                  : {
                      kind,
                      version: TRIAGE,
                      takes: [],
                      gives: fields,
                      ...(kind === "search"
                        ? {
                            searchWay: "web",
                            standings: {
                              title: "always" as const,
                              body: "never" as const,
                            },
                          }
                        : {}),
                    },
            bindings: [],
          },
          { stepId: "reader", name: "reader", bindings: [] },
        ],
        outputs: [],
        offered: {
          ...WORKFLOW.offered,
          codeSteps: [{ ...STAMPING, gives: fields }],
        },
      };
      const draft = flowOf(labelled);
      const sources = sourcesFor(
        draft,
        1,
        labelled,
        namedVersions(labelled),
        draft.steps[1],
      );
      expect(sources).toHaveLength(2);
      const read = sources[1]!.fields!;
      expect(read).toHaveLength(fields.length);
      expect(read.map(({ name, label }) => ({ name, label }))).toEqual([
        { name: "title", label: "Returned title" },
        { name: "body", label: "Returned body" },
      ]);
      expect(sources[1]!.value).toBe(draft.steps[0]!.key);
    },
  );

  it("projects current route draft labels recursively, keeping full author paths and absent labels", () => {
    const route: StepDraft = {
      ...freshStep("route"),
      runs: {
        kind: "route",
        discriminator: { from: "none" },
        gives: [
          draftOf({
            ...COMPLAINT,
            name: "package",
            kind: "fields",
            label: "Returned details",
            fields: [
              { ...COMPLAINT, label: "Original note" },
              { ...NOTE, label: "" },
            ],
          }),
        ],
        cases: [],
      },
    };
    if (route.runs.kind !== "route") throw new Error("Expected a route");
    const current = {
      ...route,
      runs: {
        ...route.runs,
        gives: route.runs.gives.map((field) => ({
          ...field,
          fields: field.fields.map((nested, index) =>
            index === 0 ? { ...nested, label: "Current note" } : nested,
          ),
        })),
      },
    };
    const reader = freshStep("reader");
    const sources = sourcesFor(
      { steps: [current, reader], outputs: [] },
      1,
      WORKFLOW,
      NAMED,
      reader,
    );
    expect(sources).toHaveLength(2);
    const fields = sources[1]!.fields!;
    expect(fields).toHaveLength(1);
    expect(fields[0]).toMatchObject({
      name: "package",
      label: "Returned details",
    });
    const reached = pointers(fields);
    expect(reached).toHaveLength(3);
    expect(
      reached.map(({ path, field }) => ({ path, label: field.label })),
    ).toEqual([
      { path: "package", label: "Returned details" },
      { path: "package.complaint", label: "Current note" },
      { path: "package.note", label: undefined },
    ]);
    expect(fields[0]!.fields![1]).not.toHaveProperty("label");
  });

  it("keeps declaration labels in binding choices without changing the sent flow", () => {
    const labelled = {
      ...WORKFLOW,
      takes: WORKFLOW.takes.map((field, index) =>
        index === 0 ? { ...field, label: "Given complaint" } : field,
      ),
    };
    const sources = sourcesFor(flow, 1, labelled, NAMED, flow.steps[1]);
    expect(sources[0]!.fields![0]).toMatchObject({
      name: WORKFLOW.takes[0]!.name,
      label: "Given complaint",
    });
    expect(sources[0]!.fields).toHaveLength(WORKFLOW.takes.length);
    expect(sentFlowOf(flow, labelled, NAMED)).toEqual(
      sentFlowOf(flow, WORKFLOW, NAMED),
    );
  });

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
    expect(before[1]!.step).toEqual({ position: 1, name: "", label: "" });
    expect(after[1]!.step).toEqual({
      position: 1,
      name: "Step 1, not named yet",
      label: "",
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
      const flags = bindingFlagsFor(filing, 1, sources);
      const rows = targetRows(
        [target],
        [binding],
        [],
        (bound, shape) => flags(bound, shape, []),
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
      const flags = bindingFlagsFor(filing, 0, sources);
      const rows = targetRows(
        [target],
        [binding],
        [],
        (bound, shape) => flags(bound, shape, []),
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

describe("bindingFlagsFor", () => {
  const flow = flowOf(WORKFLOW);
  const sources = sourcesFor(flow, 1, WORKFLOW, NAMED, flow.steps[1]);
  const reading = (path: string): BindingDraft => ({
    target: "x",
    source: { from: "input", path },
  });

  it("keeps nested required paths and first matches within a batch, without expanding unreferenced sources", () => {
    const unused = vi.fn(() => [TEXT]);
    const flags = bindingFlagsFor(flow, 1, [
      {
        value: "input",
        words: "Run inputs",
        fields: [
          {
            ...TEXT,
            name: "contact",
            kind: "fields",
            mustBeGiven: false,
            fields: [{ ...TEXT, name: "email" }],
          },
          { ...TEXT, name: "note", lines: "one" },
          { ...TEXT, name: "note", lines: "several" },
        ],
      },
      { value: "input", words: "Duplicate place", fields: [] },
      {
        value: "unused",
        words: "Unused step",
        get fields() {
          return unused();
        },
      },
    ]);
    expect(flags(reading("contact.email"), TEXT, [])).toEqual([
      "contentProblem.source_may_be_empty",
    ]);
    expect(flags(reading("note"), { ...TEXT, lines: "one" }, [])).toEqual([]);
    expect(flags(reading("removed"), TEXT, [])).toEqual([
      "contentProblem.source_unknown",
    ]);
    expect(unused).not.toHaveBeenCalled();
  });

  it.each<{
    source: Shape;
    changed: Shape;
    target: Shape;
    path: string;
    flags: string[];
  }>([
    {
      source: { ...TEXT, lines: "one" },
      changed: { ...TEXT, lines: "several" },
      target: { ...TEXT, lines: "one" },
      path: TEXT.name,
      flags: ["contentProblem.source_does_not_fit"],
    },
    {
      source: { ...TEXT, longest: 10 },
      changed: { ...TEXT, longest: 11 },
      target: { ...TEXT, longest: 10 },
      path: TEXT.name,
      flags: ["contentProblem.source_does_not_fit"],
    },
    {
      source: TEXT,
      changed: { ...TEXT, kind: "number" },
      target: TEXT,
      path: TEXT.name,
      flags: ["contentProblem.source_does_not_fit"],
    },
    {
      source: TEXT,
      changed: { ...TEXT, many: true },
      target: TEXT,
      path: TEXT.name,
      flags: ["contentProblem.source_does_not_fit"],
    },
    {
      source: { ...TEXT, many: true, most: 2 },
      changed: { ...TEXT, many: true, most: 3 },
      target: { ...TEXT, many: true, most: 2 },
      path: TEXT.name,
      flags: ["contentProblem.source_does_not_fit"],
    },
    {
      source: { ...TEXT, kind: "term", list: "list-1" },
      changed: { ...TEXT, kind: "term", list: "list-2" },
      target: { ...TEXT, kind: "term", list: "list-1" },
      path: TEXT.name,
      flags: ["contentProblem.source_does_not_fit"],
    },
    {
      source: { ...TEXT, kind: "file", types: ["text/plain"] },
      changed: {
        ...TEXT,
        kind: "file",
        types: ["text/plain", "application/pdf"],
      },
      target: { ...TEXT, kind: "file", types: ["text/plain"] },
      path: TEXT.name,
      flags: ["contentProblem.source_does_not_fit"],
    },
    {
      source: { ...TEXT, kind: "file", maximumBytes: 10 },
      changed: { ...TEXT, kind: "file", maximumBytes: 11 },
      target: { ...TEXT, kind: "file", maximumBytes: 10 },
      path: TEXT.name,
      flags: ["contentProblem.source_does_not_fit"],
    },
    {
      source: TEXT,
      changed: { ...TEXT, mustBeGiven: false },
      target: TEXT,
      path: TEXT.name,
      flags: ["contentProblem.source_may_be_empty"],
    },
    {
      source: TEXT,
      changed: { ...TEXT, name: "renamed" },
      target: TEXT,
      path: TEXT.name,
      flags: ["contentProblem.source_unknown"],
    },
    {
      source: { ...TEXT, kind: "fields", fields: [{ ...TEXT, name: "child" }] },
      changed: {
        ...TEXT,
        kind: "fields",
        fields: [{ ...TEXT, name: "child", mustBeGiven: false }],
      },
      target: { ...TEXT, kind: "fields", fields: [{ ...TEXT, name: "child" }] },
      path: TEXT.name,
      flags: ["contentProblem.source_may_be_empty"],
    },
    {
      source: { ...TEXT, kind: "fields", fields: [{ ...TEXT, name: "child" }] },
      changed: {
        ...TEXT,
        kind: "fields",
        many: true,
        fields: [{ ...TEXT, name: "child" }],
      },
      target: TEXT,
      path: `${TEXT.name}.child`,
      flags: ["contentProblem.source_unknown"],
    },
  ])(
    "uses the next batch's complete source shape: $changed",
    ({ source, changed, target, path, flags }) => {
      const offered = (field: Shape): SourceOffered[] => [
        { value: "input", words: "Run inputs", fields: [field] },
      ];
      const before = bindingFlagsFor(flow, 1, offered(source));
      const after = bindingFlagsFor(flow, 1, offered(changed));
      const binding = reading(path);
      expect(before(binding, target, [])).toEqual([]);
      expect(after(binding, target, [])).toEqual(flags);
      expect(before(binding, target, [])).toEqual([]);
    },
  );

  it("keeps missing, unknown and forward sources distinct and reads each call's server problems", () => {
    const flags = bindingFlagsFor(flow, 1, [
      { value: "input", words: "Run inputs", fields: [] },
      { value: "s3", words: "Undeclared step", fields: undefined },
    ]);
    const input = { ...reading("removed"), bindingId: "b1" };
    const later: BindingDraft = {
      target: "x",
      source: { from: "step", stepKey: "s3", path: "unknown" },
    };
    const self: BindingDraft = {
      target: "x",
      source: { from: "step", stepKey: flow.steps[1]!.key, path: "unknown" },
    };
    const removed: BindingDraft = {
      target: "x",
      source: { from: "step", stepKey: "removed", path: "unknown" },
    };
    const constant: BindingDraft = {
      target: "x",
      source: { from: "constant", typed: "Value", read: '"Value"' },
    };
    expect(flags(input, TEXT, [])).toEqual(["contentProblem.source_unknown"]);
    expect(
      flags(input, TEXT, [
        { code: "source_unknown", part: "steps", bindingId: "b1" },
      ]),
    ).toEqual(["contentProblem.source_unknown"]);
    expect(
      flags(input, TEXT, [
        { code: "source_does_not_fit", part: "steps", bindingId: "b1" },
      ]),
    ).toEqual([
      "contentProblem.source_does_not_fit",
      "contentProblem.source_unknown",
    ]);
    expect(flags(later, TEXT, [])).toEqual(["workflow.pointsForward"]);
    expect(flags(self, TEXT, [])).toEqual(["workflow.pointsForward"]);
    expect(flags(removed, TEXT, [])).toEqual(["contentProblem.source_unknown"]);
    expect(flags(constant, TEXT, [])).toEqual([]);
    expect(flags({ target: "x", source: { from: "none" } }, TEXT, [])).toEqual(
      [],
    );
  });

  it("says what fills what must be given may be empty where what it reads need not be given", () => {
    expect(
      bindingFlagsFor(flow, 1, sources)(reading("note"), TEXT, []),
    ).toEqual(["contentProblem.source_may_be_empty"]);
  });

  it("names a removed known field while leaving an unknown declaration undecided", () => {
    const removed = reading("removed");
    expect(bindingFlagsFor(flow, 1, sources)(removed, TEXT, [])).toEqual([
      "contentProblem.source_unknown",
    ]);
    expect(
      bindingFlagsFor(flow, 1, [
        {
          value: "input",
          words: "What the workflow takes",
          fields: undefined,
        },
      ])(removed, TEXT, []),
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

    expect(bindingFlagsFor(flow, 1, offered)(binding, target, [])).toEqual([
      "contentProblem.source_does_not_fit",
      "contentProblem.source_may_be_empty",
    ]);
    expect(
      bindingFlagsFor(flow, 1, offered)(binding, target, [
        { code: "source_does_not_fit", part: "steps", bindingId: "b1" },
      ]),
    ).toEqual([
      "contentProblem.source_does_not_fit",
      "contentProblem.source_may_be_empty",
    ]);
  });

  it("shows a kept source that stops fitting after a step or route case takes a newer version", () => {
    const version = WORKFLOW;
    const named = namedVersions(version);
    named.set("q2", {
      ...NAMED.get("q2")!,
      takes: [{ ...COMPLAINT, lines: "one" }],
    });
    named.set("w2", {
      ...NAMED.get("w2")!,
      takes: [{ ...NOTE, lines: "one" }],
    });
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
    const flagsStep = bindingFlagsFor(stepped, 0, stepSources);
    const stepRows = targetRows(
      named.get("q2")!.takes,
      step.bindings,
      [],
      (binding, target) => flagsStep(binding, target, []),
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
    const flagsCase = bindingFlagsFor(routedFlow, 1, routeSources);
    const caseRows = targetRows(
      named.get("w2")!.takes,
      routeCase.bindings,
      [],
      (binding, target) => flagsCase(binding, target, []),
      "contentProblem.input_unbound",
    );

    expect(routeCase.bindings).toEqual([
      { target: "note", source: { from: "input", path: "note" } },
    ]);
    expect(caseRows[0]?.flags).toEqual(["contentProblem.source_does_not_fit"]);
  });

  it("says nothing where what it reads is always there, or where what it fills need not be given", () => {
    expect(
      bindingFlagsFor(flow, 1, sources)(
        reading("complaint"),
        { ...TEXT, longest: 4000 },
        [],
      ),
    ).toEqual([]);
    expect(
      bindingFlagsFor(flow, 1, sources)(
        reading("note"),
        { ...TEXT, mustBeGiven: false },
        [],
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
        bindingFlagsFor(flow, 1, offered)(
          { target: "contact", source: { from: "input", path: "contact" } },
          contact(targetMustBe),
          [],
        ),
      ).toEqual(flagged ? ["contentProblem.source_may_be_empty"] : []);
    },
  );

  it("says a binding reads a step not before it, and what the server said of it under its key alone", () => {
    const problems: ContentProblem[] = [
      { code: "source_unknown", part: "steps", bindingId: "b7" },
      { code: "source_does_not_fit", part: "steps", bindingId: "b8" },
    ];

    const flags = bindingFlagsFor(flow, 1, sources)(
      {
        target: "x",
        source: { from: "step", stepKey: "s3", path: "y" },
        bindingId: "b7",
      },
      TEXT,
      problems,
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
  it("sends a pinned search question and ordinary ordered inputs with every standing and review setting", () => {
    const workflow = searchingWorkflow();
    const flow = flowOf(workflow);
    const search = flow.steps[3]!;
    expect(search.runs).toEqual({
      kind: "search",
      version: "q-search",
      searchWay: "web",
      standings: { title: "always", body: "never", references: "", other: "" },
    });
    expect(search.bindings).toEqual([
      {
        bindingId: "p1",
        target: "complaint",
        source: { from: "input", path: "complaint" },
      },
      {
        bindingId: "p2",
        target: "category",
        source: { from: "step", stepKey: "s1", path: "category" },
      },
    ]);
    expect(
      givenBy(search, namedVersions(workflow), workflow.offered.codeSteps),
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
      label: null,
      bindings: [
        { target: "complaint", source: { input: "complaint" } },
        { target: "category", source: { step: 0, path: "category" } },
      ],
      runs: {
        kind: "search",
        version: "q-search",
        searchWay: "web",
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
    expect(sent.steps[3]!.runs).not.toHaveProperty("question");
    expect(
      sent.steps[3]!.bindings.every((binding) => !("partId" in binding)),
    ).toBe(true);
  });

  it("keeps a search input tied to its source step when order changes and unchooses a removed source", () => {
    const flow = flowOf(searchingWorkflow());
    const moved = movedStep(flow, 3, -1).steps[2]!;
    const removed = removedStep(flow, 0).steps[2]!;
    expect(moved.bindings[1]).toEqual({
      bindingId: "p2",
      target: "category",
      source: { from: "step", stepKey: "s1", path: "category" },
    });
    expect(removed.bindings[0]).toEqual({
      bindingId: "p1",
      target: "complaint",
      source: { from: "input", path: "complaint" },
    });
    expect(removed.bindings[1]).toEqual({
      bindingId: "p2",
      target: "category",
      source: { from: "none" },
    });
    expect(removed.runs).toEqual(flow.steps[3]!.runs);
  });

  it("sends each of 255 declared inputs and refuses a binding beyond that declaration", () => {
    const workflow = searchingWorkflow();
    const fields = Array.from({ length: 255 }, (_, index): DeclaredField => ({
      ...COMPLAINT,
      fieldId: `target-${index}`,
      name: `target_${index}`,
    }));
    const step = workflow.steps[3]!;
    const expanded = {
      ...workflow,
      steps: [
        ...workflow.steps.slice(0, 3),
        {
          ...step,
          runs: { ...step.runs!, takes: fields },
          bindings: fields.map((field) => ({
            bindingId: field.fieldId,
            target: field.name,
            source: { input: "complaint" },
          })),
        },
      ],
    };
    const flow = flowOf(expanded);
    const named = namedVersions(expanded);
    const valid = sentFlowOf(flow, expanded, named);
    expect(valid.held).toBeNull();
    expect(valid.steps[3]!.bindings).toHaveLength(255);
    expect(valid.steps[3]!.bindings.map((binding) => binding.target)).toEqual(
      fields.map((field) => field.name),
    );
    const search = flow.steps[3]!;
    const over = {
      ...flow,
      steps: [
        ...flow.steps.slice(0, 3),
        {
          ...search,
          bindings: [
            ...search.bindings,
            {
              target: "extra",
              source: { from: "input" as const, path: "complaint" },
            },
          ],
        },
      ],
    };
    expect(sentFlowOf(over, expanded, named).held).toEqual({
      why: "refusal.BINDINGS_PAST_INPUTS",
      step: "s4",
    });
  });

  it("builds all 256 search steps with 255 distinct targets reading one source and caches pinned declarations", () => {
    const fields = Array.from({ length: 255 }, (_, index): DeclaredField => ({
      ...COMPLAINT,
      fieldId: `target-${index}`,
      name: `target_${index}`,
    }));
    let sourceReads = 0;
    let declarationReads = 0;
    const workflow: WorkflowVersion = {
      ...WORKFLOW,
      get takes() {
        sourceReads += 1;
        return [COMPLAINT];
      },
      gives: [],
      steps: [],
      outputs: [],
    };
    const named = new Map([
      [
        "q-search",
        {
          name: "Find evidence",
          number: 1,
          versionName: null,
          takes: fields,
          get gives() {
            declarationReads += 1;
            return SEARCH_WAY.gives;
          },
        },
      ],
    ]);
    const flow: FlowDraft = {
      steps: Array.from({ length: 256 }, (_, index) => ({
        ...freshStep(`step-${index}`),
        name: `step_${index}`,
        producer: "person",
        tries: "1",
        runs: {
          kind: "search",
          version: "q-search",
          searchWay: "web",
          standings: {
            title: "always",
            body: "always",
            references: "always",
            other: "always",
          },
        },
        bindings: fields.map((field) => ({
          target: field.name,
          source: { from: "input", path: "complaint" },
        })),
      })),
      outputs: [],
    };
    const sent = sentFlowOf(flow, workflow, named);
    expect(sent.held).toBeNull();
    expect(sent.steps).toHaveLength(256);
    expect(
      sent.steps.every(
        (step) =>
          step.runs?.kind === "search" &&
          step.runs.version === "q-search" &&
          step.bindings.length === 255 &&
          step.bindings.every(
            (binding, index) =>
              binding.target === fields[index]!.name &&
              "input" in binding.source &&
              binding.source.input === "complaint",
          ),
      ),
    ).toBe(true);
    expect(sourceReads).toBeLessThanOrEqual(256);
    expect(declarationReads).toBeLessThanOrEqual(256);
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
          label: null,
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
          label: null,
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
          label: null,
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
    const names = Array.from({ length: 65 }, (_, index) => `.x${index}`);
    const tooMany = `${names.join("\n")}\n${".after\n".repeat(1024)}`;
    const file = draftOf({
      fieldId: "file",
      name: "documents",
      kind: "file",
      types: [".pdf"],
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

describe("step display names", () => {
  it.each([
    [0xa7ce, 0xa7cf],
    [0xa7d2, 0xa7d3],
    [0xa7d4, 0xa7d5],
    [0x16ea0, 0x16ebb],
    [0x16ea1, 0x16ebc],
    [0x16ea2, 0x16ebd],
    [0x16ea3, 0x16ebe],
    [0x16ea4, 0x16ebf],
    [0x16ea5, 0x16ec0],
    [0x16ea6, 0x16ec1],
    [0x16ea7, 0x16ec2],
    [0x16ea8, 0x16ec3],
    [0x16ea9, 0x16ec4],
    [0x16eaa, 0x16ec5],
    [0x16eab, 0x16ec6],
    [0x16eac, 0x16ec7],
    [0x16ead, 0x16ec8],
    [0x16eae, 0x16ec9],
    [0x16eaf, 0x16eca],
    [0x16eb0, 0x16ecb],
    [0x16eb1, 0x16ecc],
    [0x16eb2, 0x16ecd],
    [0x16eb3, 0x16ece],
    [0x16eb4, 0x16ecf],
    [0x16eb5, 0x16ed0],
    [0x16eb6, 0x16ed1],
    [0x16eb7, 0x16ed2],
    [0x16eb8, 0x16ed3],
  ] as const)(
    "keeps code points %i and %i distinct before saving",
    (left, right) => {
      const label = String.fromCodePoint(left);
      const otherLabel = String.fromCodePoint(right);
      const first = { ...freshStep("first"), name: "first_step", label };
      const other = {
        ...freshStep("other"),
        name: "other_step",
        label: otherLabel,
      };
      const sent = sentFlowOf(flowWith(first, other), WORKFLOW, NAMED);

      expect(labelClashOf(first, [first, other])).toBeUndefined();
      expect(labelClashOf(other, [first, other])).toBeUndefined();
      expect(sent.held).toBeNull();
      expect(sent.steps.map((step) => step.label)).toEqual([label, otherLabel]);
      expect(sent.steps.map((step) => step.name)).toEqual([
        "first_step",
        "other_step",
      ]);
    },
  );

  it("checks every cased Unicode pair without blocking non-ASCII text before the store judges it", () => {
    let checked = 0;
    let asciiCollisions = 0;
    for (let point = 0; point <= 0x10ffff; point++) {
      if (point >= 0xd800 && point <= 0xdfff) continue;
      const label = String.fromCodePoint(point);
      const otherLabel = label.normalize("NFC").toLowerCase();
      if (label === otherLabel || !nameFits(label) || !nameFits(otherLabel))
        continue;
      const first = { ...freshStep("first"), name: "first_step", label };
      const other = {
        ...freshStep("other"),
        name: "other_step",
        label: otherLabel,
      };
      const ascii = "ABCDEFGHIJKLMNOPQRSTUVWXYZ".includes(label);

      expect(
        labelClashOf(first, [first, other]),
        `U+${point.toString(16)}`,
      ).toBe(ascii ? otherLabel : undefined);
      expect(
        labelClashOf(other, [first, other]),
        `U+${point.toString(16)}`,
      ).toBe(ascii ? label : undefined);
      checked++;
      if (ascii) asciiCollisions++;
    }
    expect(checked).toBe(2596);
    expect(asciiCollisions).toBe(26);
  });

  it.each([
    ["", true],
    ["確認する", true],
    ["A\u200dB", true],
    ["A\u202eB", true],
    ["😀".repeat(128), true],
    ["😀".repeat(129), false],
    [" Start", false],
    ["End ", false],
    ["Two  spaces", false],
    ["A\u00a0B", false],
    ["Two\nlines", false],
    ["A\u0085B", false],
    ["\u2066\u2069", false],
  ] as const)("preserves the exact text and bounds %j", (label, accepted) => {
    const step = { ...freshStep("s"), name: "review_it", label };
    const sent = sentFlowOf(flowWith(step), WORKFLOW, NAMED);

    expect(sent.steps[0]).toEqual({
      stepId: null,
      name: "review_it",
      label: label === "" ? null : label,
      runs: null,
      bindings: [],
    });
    expect(sent.held).toEqual(
      accepted ? null : { why: "refusal.STEP_LABEL_UNUSABLE", step: "s" },
    );
    expect(step.label).toBe(label);
  });

  it.each([
    ["Review It", "", "review_it", "review it"],
    ["Review It", "Send reply", "review_it", "Send reply"],
    ["REVIEW", "Review", "second", "Review"],
    ["確認", "確認", "second", "確認"],
    ["إرسال", "إرسال", "second", "إرسال"],
    ["CAFé", "Café", "second", "Café"],
  ] as const)(
    "names another row's label or readable machine name for %s",
    (label, otherLabel, otherName, named) => {
      const first = { ...freshStep("first"), name: "first", label };
      const other = {
        ...freshStep("other"),
        name: otherName,
        label: otherLabel,
      };

      const sent = sentFlowOf(flowWith(first, other), WORKFLOW, NAMED);

      expect(labelClashOf(first, [first, other])).toBe(named);
      expect(sent.held).toEqual({
        why: "contentProblem.step_label_repeated",
        step: "first",
        name: named,
      });
      expect(sent.steps.map((step) => step.name)).toEqual(["first", otherName]);
      expect(sent.steps.map((step) => step.stepId)).toEqual([null, null]);
    },
  );

  it("rechecks all readable names when an otherwise unrelated cached row is renamed", () => {
    const compile = reusableSentFlowOf();
    const first = { ...freshStep("first"), name: "first", label: "Review it" };
    const other = { ...freshStep("other"), name: "send_it" };
    const before = compile(flowWith(first, other), WORKFLOW, NAMED);
    const collision = compile(
      flowWith(first, { ...other, name: "review_it" }),
      WORKFLOW,
      NAMED,
    );
    const cleared = compile(flowWith(first, other), WORKFLOW, NAMED);

    expect(before.held).toBeNull();
    expect(collision.held).toEqual({
      why: "contentProblem.step_label_repeated",
      step: "first",
      name: "review it",
    });
    expect(collision.steps[0]).toBe(before.steps[0]);
    expect(cleared.held).toBeNull();
    expect(cleared.steps[0]).toBe(before.steps[0]);
  });

  it.each([
    ["Straße", "STRASSE"],
    ["ς", "σ"],
    ["İ", "i"],
    ["Café", "Cafe\u0301"],
    ["résumé", "resume"],
    ["Équipe", "E\u0301QUIPE"],
    ["Ａ", "ａ"],
    ["Α", "α"],
  ] as const)(
    "leaves %s and %s for the authoritative server comparison",
    (label, otherLabel) => {
      const first = { ...freshStep("first"), name: "first", label };
      const other = { ...freshStep("other"), name: "other", label: otherLabel };
      const sent = sentFlowOf(flowWith(first, other), WORKFLOW, NAMED);

      expect(labelClashOf(first, [first, other])).toBeUndefined();
      expect(sent.held).toBeNull();
      expect(sent.steps.map((step) => step.label)).toEqual([label, otherLabel]);
    },
  );

  it("keeps display text across a move, a version change and deletion of another step", () => {
    const flow = flowOf({
      ...WORKFLOW,
      steps: WORKFLOW.steps.map((step, index) =>
        index === 0 ? { ...step, label: "確認する" } : step,
      ),
    });
    const moved = movedStep(flow, 0, 1);
    const taken = tookVersion(flow.steps[0]!, "q2", NAMED);
    const remaining = removedStep(flow, 1);

    expect(flow.steps[0]!.label).toBe("確認する");
    expect(moved.steps[1]).toBe(flow.steps[0]);
    expect(taken.label).toBe("確認する");
    expect(taken.name).toBe("triage");
    expect(taken.stepId).toBe("s1");
    expect(remaining.steps[0]!.label).toBe("確認する");
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
