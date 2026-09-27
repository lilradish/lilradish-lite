import { describe, expect, it } from "vitest";

import type { DeclaredField } from "../../api/declaration";
import {
  addedUnder,
  changedAt,
  draftOf,
  floorFits,
  freshDraft,
  heldFor,
  limitFits,
  movedAt,
  removedAt,
  sentOf,
  type Demands,
  type DraftField,
} from "./declarationDrafts";
import QUESTION from "./questionDemands.json";
import WORKFLOW from "./workflowDemands.json";

const TAKES = QUESTION.takes as Demands;

const GIVES = QUESTION.gives as Demands;

/** A host asking no field what it takes to stand, as a half whose values stood where they were made. */
const STOOD = WORKFLOW.gives as Demands;

const LIST = "00000007-0000-4000-8000-000000000c21";

/** A value given back holding fields, one of which is a term. */
const DETAILS: DeclaredField = {
  fieldId: "details",
  name: "details",
  label: "The details",
  help: "What it names.",
  kind: "fields",
  many: false,
  mustBeGiven: true,
  stands: "above_confidence",
  floor: 80,
  fields: [
    {
      fieldId: "product",
      name: "product",
      kind: "text",
      longest: 128,
      many: true,
      most: 3,
      mustBeGiven: false,
    },
    {
      fieldId: "category",
      name: "category",
      kind: "term",
      many: false,
      mustBeGiven: true,
      list: {
        name: "Categories",
        versionId: LIST,
        number: 1,
        standing: "in_service",
      },
    },
  ],
};

function drafted(changed: Partial<DraftField> = {}): DraftField {
  return { ...freshDraft("row"), name: "summary", longest: "100", ...changed };
}

/** A row holding one row at each level below it, named in turn from the top down. */
function chain(names: readonly string[]): DraftField {
  const [name, ...below] = names;
  return below.length === 0
    ? drafted({ name })
    : drafted({ name, kind: "fields", fields: [chain(below)] });
}

function nested(depth: number): DraftField {
  return chain(Array.from({ length: depth }, () => "a"));
}

describe("draftOf and sentOf", () => {
  /** Held as typed and sent back as read: nothing is lost on the way through a builder. */
  it("sends a field read and left alone exactly as it was read, members its kind and depth take and no others", () => {
    expect(sentOf(draftOf(DETAILS), GIVES)).toEqual({
      fieldId: "details",
      name: "details",
      label: "The details",
      help: "What it names.",
      kind: "fields",
      many: false,
      most: null,
      fields: [
        {
          fieldId: "product",
          name: "product",
          label: null,
          help: null,
          kind: "text",
          many: true,
          most: 3,
          longest: 128,
          mustBeGiven: false,
        },
        {
          fieldId: "category",
          name: "category",
          label: null,
          help: null,
          kind: "term",
          many: false,
          most: null,
          list: LIST,
          mustBeGiven: true,
        },
      ],
      mustBeGiven: true,
      stands: "above_confidence",
      floor: 80,
    });
  });

  it("sends whether a field must be given where each depth asks it, and nothing of standing", () => {
    const sent = sentOf(
      drafted({ kind: "fields", fields: [drafted({ mustBeGiven: false })] }),
      TAKES,
    );

    expect(sent.mustBeGiven).toBe(true);
    expect(sent.fields![0]!.mustBeGiven).toBe(false);
    expect("stands" in sent).toBe(false);
    expect("floor" in sent).toBe(false);
    expect("longest" in sent).toBe(false);
  });

  /** What each depth is asked is the host's to say; the builder only follows it. */
  it("sends no standing where no depth is asked it, whatever was typed for it", () => {
    const sent = sentOf(
      drafted({
        kind: "fields",
        stands: "never",
        mustBeGiven: false,
        fields: [drafted({ stands: "always" })],
      }),
      STOOD,
    );

    expect(Object.keys(sent)).toEqual([
      "fieldId",
      "name",
      "label",
      "help",
      "kind",
      "many",
      "most",
      "fields",
      "mustBeGiven",
    ]);
    expect(Object.keys(sent.fields![0]!)).toEqual([
      "fieldId",
      "name",
      "label",
      "help",
      "kind",
      "many",
      "most",
      "longest",
      "mustBeGiven",
    ]);
  });

  it("sends whether a field must be given at every depth, each as typed there", () => {
    const sent = sentOf(
      drafted({
        kind: "fields",
        mustBeGiven: false,
        fields: [drafted({ mustBeGiven: true })],
      }),
      STOOD,
    );

    expect(sent.mustBeGiven).toBe(false);
    expect(sent.fields![0]!.mustBeGiven).toBe(true);
  });

  /** What a kind, a count or a standing no longer takes is left behind, however much was typed for it. */
  it("leaves out or sends as nothing what the field no longer takes, and what is not chosen yet", () => {
    expect(
      sentOf(
        drafted({
          kind: "number",
          longest: "100",
          many: false,
          most: "5",
          list: LIST,
          stands: "never",
          floor: "80",
          label: "",
        }),
        GIVES,
      ),
    ).toEqual({
      fieldId: null,
      name: "summary",
      label: null,
      help: null,
      kind: "number",
      many: false,
      most: null,
      mustBeGiven: true,
      stands: "never",
      floor: null,
    });
    expect(sentOf(drafted({ longest: "" }), GIVES, false)).toEqual({
      fieldId: null,
      name: "summary",
      label: null,
      help: null,
      kind: "text",
      many: false,
      most: null,
      longest: null,
      mustBeGiven: true,
    });
    expect(sentOf(drafted({ kind: "term", stands: "" }), GIVES)).toMatchObject({
      list: null,
      stands: null,
      floor: null,
    });
  });

  it("starts a field nothing is typed in as text holding one, to be given", () => {
    expect(freshDraft("new-1")).toEqual({
      key: "new-1",
      fieldId: null,
      name: "",
      label: "",
      help: "",
      kind: "text",
      longest: "",
      list: "",
      many: false,
      most: "",
      mustBeGiven: true,
      stands: "",
      floor: "",
      fields: [],
    });
  });
});

describe("heldFor", () => {
  it.each([
    [
      "a name the server refuses",
      drafted({ name: "Summary" }),
      "refusal.FIELD_NAME_UNUSABLE",
    ],
    ["no name at all", drafted({ name: "" }), "refusal.FIELD_NAME_UNUSABLE"],
    [
      "a label past its bound",
      drafted({ label: "l".repeat(129) }),
      "declaration.labelLimit",
    ],
    [
      "help on two lines",
      drafted({ help: "Two\nlines" }),
      "declaration.helpLimit",
    ],
    ["a length of none", drafted({ longest: "0" }), "declaration.limit"],
    [
      "a length past the largest stored",
      drafted({ longest: "2147483648" }),
      "declaration.limit",
    ],
    [
      "a count that is no whole number",
      drafted({ many: true, most: "2.5" }),
      "declaration.limit",
    ],
    [
      "a floor past a hundred",
      drafted({ stands: "above_confidence", floor: "101" }),
      "declaration.floorLimit",
    ],
  ])("holds a half back for %s, at its row", (_case, draft, why) => {
    expect(heldFor([drafted({ name: "first" }), draft], GIVES)).toEqual({
      why,
      place: [1],
    });
  });

  /** Not chosen yet is for submitting to name, and what is not sent is not judged. */
  it("holds nothing against what is only not chosen yet, nor against what would not be sent", () => {
    expect(
      heldFor(
        [
          drafted({
            longest: "",
            many: true,
            most: "",
            stands: "above_confidence",
            floor: "",
          }),
          drafted({
            name: "count",
            kind: "number",
            longest: "none",
            most: "none",
            floor: "none",
          }),
        ],
        GIVES,
      ),
    ).toBeNull();
    expect(
      heldFor([drafted({ stands: "above_confidence", floor: "500" })], TAKES),
    ).toBeNull();
    expect(
      heldFor(
        [
          drafted({
            kind: "fields",
            fields: [drafted({ stands: "above_confidence", floor: "500" })],
          }),
        ],
        GIVES,
      ),
    ).toBeNull();
  });

  it("judges a field held inside another, and names the first in the order the half reads, where it is", () => {
    expect(
      heldFor(
        [
          drafted({
            kind: "fields",
            fields: [drafted(), drafted({ help: "Two\nlines" })],
          }),
          drafted({ name: "Later" }),
        ],
        GIVES,
      ),
    ).toEqual({ why: "declaration.helpLimit", place: [0, 1] });
    expect(
      heldFor(
        [drafted({ kind: "text", fields: [drafted({ name: "Held" })] })],
        GIVES,
      ),
    ).toBeNull();
  });

  it("holds a field 32 deep and no deeper, as the server does", () => {
    expect(heldFor([nested(32)], TAKES)).toBeNull();
    expect(heldFor([nested(33)], TAKES)).toEqual({
      why: "refusal.DECLARATION_TOO_DEEP",
      place: Array.from({ length: 33 }, () => 0),
    });
  });

  it("holds names down to a field joined by dots up to 1023 characters, as the server does", () => {
    const down = (last: number) =>
      chain([
        ...Array.from({ length: 15 }, () => "n".repeat(63)),
        "m".repeat(last),
        "a",
      ]);

    expect(heldFor([down(61)], TAKES)).toBeNull();
    expect(heldFor([down(62)], TAKES)).toEqual({
      why: "refusal.DECLARATION_TOO_DEEP",
      place: Array.from({ length: 17 }, () => 0),
    });
  });

  it("holds 256 fields at every depth together and no more, as the server does", () => {
    const rows = (count: number) =>
      Array.from({ length: count }, (_row, index) =>
        drafted({ key: `r${index}`, name: `r${index}` }),
      );

    expect(heldFor([...rows(254), nested(2)], TAKES)).toBeNull();
    expect(heldFor([...rows(255), nested(2)], TAKES)).toEqual({
      why: "refusal.DECLARATION_TOO_LARGE",
      place: [255, 0],
    });
  });
});

describe("limitFits and floorFits", () => {
  it.each([
    ["", true, true],
    ["1", true, true],
    ["100", true, true],
    ["101", true, false],
    ["2147483647", true, false],
    ["2147483648", false, false],
    ["0", false, false],
    ["-1", false, false],
    ["01", false, false],
    ["1.5", false, false],
    [" 1", false, false],
  ])("judges %j as a limit %s and a floor %s", (typed, limit, floor) => {
    expect(limitFits(typed)).toBe(limit);
    expect(floorFits(typed)).toBe(floor);
  });
});

describe("the rows of a half", () => {
  const half = [
    drafted({ key: "a", name: "a" }),
    drafted({
      key: "b",
      name: "b",
      kind: "fields",
      fields: [
        drafted({ key: "b1", name: "b1" }),
        drafted({ key: "b2", name: "b2" }),
      ],
    }),
  ];

  function keysOf(drafts: readonly DraftField[]): unknown[] {
    return drafts.map((draft) =>
      draft.fields.length === 0 ? draft.key : [draft.key, keysOf(draft.fields)],
    );
  }

  it("changes only the row it names, at whatever depth", () => {
    const changed = changedAt(half, [1, 0], (draft) => ({
      ...draft,
      name: "renamed",
    }));

    expect(changed[1]!.fields[0]!.name).toBe("renamed");
    expect(changed[1]!.fields[1]).toBe(half[1]!.fields[1]);
    expect(changed[0]).toBe(half[0]);
    expect(half[1]!.fields[0]!.name).toBe("b1");
  });

  it("removes a row and the rows it held, and nothing else", () => {
    expect(keysOf(removedAt(half, [1]))).toEqual(["a"]);
    expect(keysOf(removedAt(half, [1, 0]))).toEqual(["a", ["b", ["b2"]]]);
  });

  it("moves a row among its fellows, and nowhere past either end", () => {
    expect(keysOf(movedAt(half, [1], -1))).toEqual([["b", ["b1", "b2"]], "a"]);
    expect(keysOf(movedAt(half, [1, 0], 1))).toEqual([
      "a",
      ["b", ["b2", "b1"]],
    ]);
    expect(keysOf(movedAt(half, [0], -1))).toEqual(keysOf(half));
    expect(keysOf(movedAt(half, [1, 1], 1))).toEqual(keysOf(half));
  });

  it("adds a row last on the first level, or last among what a row holds", () => {
    expect(keysOf(addedUnder(half, null, freshDraft("c")))).toEqual([
      "a",
      ["b", ["b1", "b2"]],
      "c",
    ]);
    expect(keysOf(addedUnder(half, [1], freshDraft("b3")))).toEqual([
      "a",
      ["b", ["b1", "b2", "b3"]],
    ]);
  });
});
