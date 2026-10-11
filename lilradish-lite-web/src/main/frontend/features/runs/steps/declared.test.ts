import type { ReadField } from "../../../api/filling";
import { describe, expect, it } from "vitest";

import type { FillField } from "../../../api/filling";
import type {
  Declared,
  StepRow,
} from "../../../api/groups/{groupId}/runs/{runId}/steps";
import {
  declaredFor,
  fieldAt,
  fromSaid,
  labelAt,
  laterStepSaid,
  outputSaid,
  type Declaring,
} from "./declared";

const RUN_VERSION = "00000007-0000-4000-8000-000000000c91";

const QUESTION_VERSION = "00000007-0000-4000-8000-000000000c92";

const setApart = (words: string) =>
  `${String.fromCodePoint(0x2068)}${words}${String.fromCodePoint(0x2069)}`;

const CONTACT: FillField = {
  name: "contact",
  label: "Contact",
  kind: "fields",
  mustBeGiven: true,
  fields: [
    { name: "phone_number", kind: "text", longest: 20, mustBeGiven: false },
    {
      name: "name",
      label: "Their name",
      kind: "text",
      longest: 20,
      mustBeGiven: true,
    },
  ],
};

const TICKET: FillField = {
  name: "ticket",
  label: "Ticket",
  kind: "text",
  longest: 4000,
  mustBeGiven: true,
};

const SUMMARY: FillField = {
  name: "summary",
  label: "Summary",
  kind: "text",
  longest: 100,
  mustBeGiven: true,
};

const REFERENCE: FillField = {
  name: "reference",
  label: "Reference",
  kind: "text",
  longest: 20,
  mustBeGiven: true,
};

const TOTAL: FillField = {
  name: "total",
  label: "Total claimed",
  kind: "text",
  longest: 20,
  mustBeGiven: true,
};

const DECLARATIONS: ReadonlyMap<string, Declared> = new Map([
  [RUN_VERSION, { takes: [TICKET, CONTACT], gives: [TOTAL] }],
  [
    QUESTION_VERSION,
    { takes: [{ ...TICKET, label: "Text" }], gives: [SUMMARY] },
  ],
  ["file_claim", { takes: [], gives: [REFERENCE] }],
]);

function step(changed: Partial<StepRow>): StepRow {
  return {
    producedByUserId: null,
    producedByKind: null,
    stepId: "00000009-0000-4000-8000-000000000c91",
    order: 1,
    name: "summarise_it",
    runs: {
      versionName: null,
      kind: "question",
      name: "Summarise",
      versionId: QUESTION_VERSION,
    },
    state: "done",
    takesFrom: [],
    cost: {},
    stateActs: new Set([]),
    stateRefusals: [],
    ...changed,
  };
}

const SUMMARISE = step({});

const FILE_IT = step({
  stepId: "00000009-0000-4000-8000-000000000c95",
  order: 2,
  name: "file_it",
  runs: { versionName: null, kind: "code_step", codeStep: "file_claim" },
});

const DECLARING: Declaring = {
  runId: "00000008-0000-4000-8000-000000000c91",
  declarations: DECLARATIONS,
  runVersionId: RUN_VERSION,
  steps: [SUMMARISE, FILE_IT],
};

describe("declaredFor", () => {
  it("uses a route's own declaration and a pinned workflow's declaration when either supplies a search input", () => {
    const route = { ...FILE_IT, runs: { versionName: null, kind: "route" } };
    const declarations = new Map<string, Declared>([
      [`route:${route.stepId}`, { takes: [], gives: [TOTAL] }],
      [QUESTION_VERSION, { takes: [], gives: [SUMMARY] }],
    ]);
    const declaring = { ...DECLARING, declarations };

    expect(declaredFor(route, declarations)).toEqual({
      takes: [],
      gives: [TOTAL],
    });
    expect(
      fromSaid(
        { kind: "step", stepId: route.stepId, name: "file_it", path: "total" },
        declaring,
      ),
    ).toContain("Total claimed");
    expect(
      fromSaid(
        {
          kind: "step",
          stepId: route.stepId,
          name: "file_it",
          path: "summary",
          versionId: QUESTION_VERSION,
        },
        declaring,
      ),
    ).toContain("Summary");
    expect(
      declaredFor({ ...route, stepId: "not-read" }, declarations),
    ).toBeUndefined();
  });
  it("keeps a search way distinct from a code step with the same name", () => {
    const declarations = new Map<string, Declared>([
      ["file_claim", { takes: [TICKET], gives: [REFERENCE] }],
      ["search:file_claim", { takes: [], gives: [SUMMARY] }],
    ]);

    expect(
      declaredFor(
        step({
          runs: { versionName: null, kind: "search", searchWay: "file_claim" },
        }),
        declarations,
      ),
    ).toEqual({ takes: [], gives: [SUMMARY] });
    expect(declaredFor(FILE_IT, declarations)).toEqual({
      takes: [TICKET],
      gives: [REFERENCE],
    });
    expect(
      declaredFor(
        step({
          runs: { versionName: null, kind: "search", searchWay: "other" },
        }),
        declarations,
      ),
    ).toBeUndefined();
  });
  it("finds what the version a step runs declares, and nothing for one that runs no version or one not read", () => {
    expect(declaredFor(SUMMARISE, DECLARATIONS)?.gives).toEqual([SUMMARY]);
    expect(
      declaredFor(
        step({ runs: { versionName: null, kind: "route" } }),
        DECLARATIONS,
      ),
    ).toBe(undefined);
    expect(
      declaredFor(
        step({
          runs: { versionName: null, kind: "question", versionId: "elsewhere" },
        }),
        DECLARATIONS,
      ),
    ).toBe(undefined);
  });

  it("finds what a code step declares by its name, and nothing for one whose release the read does not hold", () => {
    expect(
      declaredFor(
        step({
          runs: {
            versionName: null,
            kind: "code_step",
            codeStep: "file_claim",
          },
        }),
        DECLARATIONS,
      )?.gives,
    ).toEqual([REFERENCE]);
    expect(
      declaredFor(
        step({
          runs: { versionName: null, kind: "code_step", codeStep: "tally" },
        }),
        DECLARATIONS,
      ),
    ).toBe(undefined);
  });
});

describe("fieldAt", () => {
  it.each([
    ["the first level", "contact", CONTACT],
    ["a level within fields", "contact.name", CONTACT.fields![1]],
    ["a name declared nowhere", "contact.fax", undefined],
    ["a level within a field that holds none", "ticket.name", undefined],
  ])("finds the field at %s", (_case, path, found) => {
    expect(fieldAt([TICKET, CONTACT], path)).toBe(found);
  });

  it("finds nothing where nothing is declared", () => {
    expect(fieldAt(undefined, "ticket")).toBe(undefined);
  });
});

describe("labelAt", () => {
  it("reads each declared field without a label by its name with underscores as spaces", () => {
    const fields: readonly ReadField[] = [
      {
        name: "claim_receipt",
        kind: "fields",
        mustBeGiven: true,
        fields: [
          { name: "grand_total", kind: "text", longest: 20, mustBeGiven: true },
        ],
      },
    ];
    expect(labelAt(fields, "claim_receipt.grand_total")).toBe(
      `${setApart("claim receipt")} › ${setApart("grand total")}`,
    );
  });

  it.each([
    ["one level by its label", "ticket", `${setApart("Ticket")}`],
    [
      "each level by its label, from the first down, the outer first",
      "contact.name",
      `${setApart("Contact")} › ${setApart("Their name")}`,
    ],
    [
      "a level with no label by its name, underscores read as spaces",
      "contact.phone_number",
      `${setApart("Contact")} › ${setApart("phone number")}`,
    ],
    [
      "a level declared nowhere by its name, underscores read as spaces",
      "fax_number",
      setApart("fax number"),
    ],
  ])("says %s, each set apart", (_case, path, said) => {
    expect(labelAt([TICKET, CONTACT], path)).toBe(said);
  });
});

describe("fromSaid", () => {
  it("uses the source step's display name across runs without changing its machine path", () => {
    const source = {
      kind: "step",
      path: "summary",
      stepId: SUMMARISE.stepId,
      name: "summarise_it",
      label: "要約する\u202e",
      versionId: QUESTION_VERSION,
      runId: "another-run",
      runNumber: 6,
    };

    expect(fromSaid(source, { ...DECLARING, runId: "this-run" })).toBe(
      `${setApart("Summary")} (output of ${setApart("要約する\u202e")} in run 6)`,
    );
    expect(fromSaid(source, { ...DECLARING, runId: "another-run" })).toBe(
      `${setApart("Summary")} (output of ${setApart("要約する\u202e")})`,
    );
    expect(fromSaid(source, DECLARING)).not.toContain("summarise it");
    expect(source.path).toBe("summary");
    expect(source.name).toBe("summarise_it");
    expect(source.stepId).toBe(SUMMARISE.stepId);
  });
  it("uses the published source run for a step, and keeps same-run wording", () => {
    const source = {
      kind: "step",
      path: "summary",
      stepId: SUMMARISE.stepId,
      name: "summarise_it",
      versionId: QUESTION_VERSION,
      runId: "another-run",
      runNumber: 6,
    };
    const otherRun = fromSaid(source, { ...DECLARING, runId: "this-run" });
    const thisRun = fromSaid(source, {
      ...DECLARING,
      runId: "another-run",
    });

    expect(otherRun).toBe(
      `${setApart("Summary")} (output of ${setApart("summarise it")} in run 6)`,
    );
    expect(otherRun).not.toBe(
      `${setApart("Summary")} (output of ${setApart("summarise it")})`,
    );
    expect(thisRun).toBe(
      `${setApart("Summary")} (output of ${setApart("summarise it")})`,
    );
  });

  it("names another run's input by its source path, without borrowing this run's label", () => {
    const source = {
      kind: "run_input",
      path: "ticket",
      runId: "another-run",
      runNumber: 6,
    };
    const otherRun = fromSaid(source, { ...DECLARING, runId: "this-run" });
    const thisRun = fromSaid(source, {
      ...DECLARING,
      runId: "another-run",
    });

    expect(otherRun).toBe(`${setApart("ticket")} (input of run 6)`);
    expect(otherRun).not.toContain(setApart("Ticket"));
    expect(thisRun).toBe(`${setApart("Ticket")} (run input)`);
  });

  it("names what an earlier step gave back by that step's own name, underscores read as spaces, and the label the version it pins gives", () => {
    const said = fromSaid(
      {
        kind: "step",
        path: "summary",
        stepId: SUMMARISE.stepId,
        name: "summarise_it",
        versionId: QUESTION_VERSION,
      },
      DECLARING,
    );

    expect(said).toBe(
      `${setApart("Summary")} (output of ${setApart("summarise it")})`,
    );
    expect(said).not.toContain("Summarise");
  });

  it("names what an earlier code step gave back by the label its release gives, which the read holds by the code step's name", () => {
    const said = fromSaid(
      {
        kind: "step",
        path: "reference",
        stepId: "00000009-0000-4000-8000-000000000c93",
        name: "file_it",
        codeStep: "file_claim",
      },
      DECLARING,
    );

    expect(said).toBe(
      `${setApart("Reference")} (output of ${setApart("file it")})`,
    );
    expect(said).not.toContain("file_claim");
  });

  it.each([
    [
      "a version absent from this read",
      { versionId: "00000007-0000-4000-8000-000000000c99" },
    ],
    ["a code step whose release the read does not hold", { codeStep: "tally" }],
    ["neither, though the read holds the step", { stepId: SUMMARISE.stepId }],
  ])(
    "names a field from an earlier step pinning %s by its name, underscores read as spaces",
    (_case, source) => {
      const said = fromSaid(
        {
          kind: "step",
          path: "summary",
          stepId: "00000009-0000-4000-8000-000000000c94",
          name: "sort_it_out",
          ...source,
        },
        DECLARING,
      );

      expect(said).toBe(
        `${setApart("summary")} (output of ${setApart("sort it out")})`,
      );
      expect(said).not.toContain("Summary");
    },
  );

  it.each([
    ["a constant", { kind: "constant" }, "a fixed value set in the workflow"],
    [
      "a kind this build does not know",
      { kind: "archive", path: "a" },
      "This page can't show this yet.",
    ],
    [
      "the run's input at no path",
      { kind: "run_input" },
      "This page can't show this yet.",
    ],
    [
      "a step by no name",
      { kind: "step", path: "summary", stepId: "gone" },
      "This page can't show this yet.",
    ],
  ])("says %s in the reader's words", (_case, from, said) => {
    expect(fromSaid(from, DECLARING)).toBe(said);
  });
});

describe("laterStepSaid", () => {
  it("does not guess a reader while the step-list read holds no matching step", () => {
    expect(
      laterStepSaid(FILE_IT.stepId, { ...DECLARING, steps: [SUMMARISE] }),
    ).toBeNull();
  });

  it.each([
    [
      "by the name of what it runs, where that has one for people",
      SUMMARISE.stepId,
      setApart("Summarise"),
    ],
    [
      "by its own name, underscores read as spaces, where what it runs has none",
      FILE_IT.stepId,
      setApart("file it"),
    ],
  ])("names a later step the read holds %s", (_case, stepId, said) => {
    expect(laterStepSaid(stepId, DECLARING)).toBe(said);
  });

  it("names the consuming step as a later step when the step-list read failed", () => {
    const said = laterStepSaid(FILE_IT.stepId, {
      ...DECLARING,
      steps: undefined,
    });

    expect(said).toBe("a later step");
  });
});

describe("outputSaid", () => {
  it.each([
    ["by its label", "total", setApart("Total claimed")],
    [
      "one the run's version declares nowhere by its name, underscores read as spaces",
      "grand_total",
      setApart("grand total"),
    ],
  ])(
    "names a field of what the run's workflow gives back %s",
    (_case, path, said) => {
      expect(outputSaid(path, DECLARING)).toBe(said);
    },
  );
});
