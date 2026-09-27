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
  takenSaid,
  type Declaring,
} from "./declared";

const RUN_VERSION = "00000007-0000-4000-8000-000000000c91";

const QUESTION_VERSION = "00000007-0000-4000-8000-000000000c92";

const setApart = (words: string) =>
  `${String.fromCodePoint(0x2068)}${words}${String.fromCodePoint(0x2069)}`;

/** A contact, whose phone has no label of its own. */
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

/** The run's own version, a question's a step pins, and a code step's its release holds, by its name. */
const DECLARATIONS: ReadonlyMap<string, Declared> = new Map([
  [RUN_VERSION, { takes: [TICKET, CONTACT], gives: [] }],
  [
    QUESTION_VERSION,
    { takes: [{ ...TICKET, label: "Text" }], gives: [SUMMARY] },
  ],
  ["file_claim", { takes: [], gives: [REFERENCE] }],
]);

function step(changed: Partial<StepRow>): StepRow {
  return {
    stepId: "00000009-0000-4000-8000-000000000c91",
    order: 1,
    name: "summarise_it",
    runs: { kind: "question", name: "Summarise", versionId: QUESTION_VERSION },
    state: "done",
    takesFrom: [],
    cost: {},
    acts: [],
    withheld: [],
    ...changed,
  };
}

const SUMMARISE = step({});

const DECLARING: Declaring = {
  declarations: DECLARATIONS,
  runVersionId: RUN_VERSION,
};

describe("declaredFor", () => {
  it("finds what the version a step runs declares, and nothing for one that runs no version or one not read", () => {
    expect(declaredFor(SUMMARISE, DECLARATIONS)?.gives).toEqual([SUMMARY]);
    expect(declaredFor(step({ runs: { kind: "route" } }), DECLARATIONS)).toBe(
      undefined,
    );
    expect(
      declaredFor(
        step({ runs: { kind: "question", versionId: "elsewhere" } }),
        DECLARATIONS,
      ),
    ).toBe(undefined);
  });

  it("finds what a code step declares by its name, and nothing for one whose release the read does not hold", () => {
    expect(
      declaredFor(
        step({ runs: { kind: "code_step", codeStep: "file_claim" } }),
        DECLARATIONS,
      )?.gives,
    ).toEqual([REFERENCE]);
    expect(
      declaredFor(
        step({ runs: { kind: "code_step", codeStep: "tally" } }),
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
  it.each([
    ["one level by its label", "ticket", `${setApart("Ticket")}`],
    [
      "each level by its label, from the first down, the outer first",
      "contact.name",
      `${setApart("Contact")} › ${setApart("Their name")}`,
    ],
    [
      "a level with no label by its name as it is declared",
      "contact.phone_number",
      `${setApart("Contact")} › ${setApart("phone_number")}`,
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

describe("takenSaid", () => {
  it("says an input by the label the step's version gives it, and where it comes from by the run's", () => {
    const said = takenSaid(
      { input: "ticket", from: { kind: "run_input", path: "ticket" } },
      SUMMARISE,
      DECLARING,
    );

    expect(said).toBe(
      `${setApart("Text")}: ${setApart("Ticket")}, which the run was started with`,
    );
  });
});

describe("fromSaid", () => {
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
      `${setApart("Summary")}, which ${setApart("summarise it")} gave back`,
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
      `${setApart("Reference")}, which ${setApart("file it")} gave back`,
    );
    expect(said).not.toContain("file_claim");
  });

  it.each([
    [
      "a workflow's version, which the read never declares",
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
        `${setApart("summary")}, which ${setApart("sort it out")} gave back`,
      );
      expect(said).not.toContain("Summary");
    },
  );

  it.each([
    ["a constant", { kind: "constant" }, "a value written into the workflow"],
    [
      "a kind this build does not know",
      { kind: "archive", path: "a" },
      "Not something this page can say yet.",
    ],
    [
      "the run's input at no path",
      { kind: "run_input" },
      "Not something this page can say yet.",
    ],
    [
      "a step by no name",
      { kind: "step", path: "summary", stepId: "gone" },
      "Not something this page can say yet.",
    ],
  ])("says %s in the reader's words", (_case, from, said) => {
    expect(fromSaid(from, DECLARING)).toBe(said);
  });
});
