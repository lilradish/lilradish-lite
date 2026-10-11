import { describe, expect, it } from "vitest";

import type { DeclaredField } from "../../api/declaration";
import {
  addedUnder,
  candidateFields,
  rebaseCandidates,
  changedAt,
  draftOf,
  fileBytesOf,
  fileSizeFits,
  fileTypesOf,
  floorFits,
  freshDraft,
  fileTypesTooMany,
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
const WORKFLOW_GIVES = WORKFLOW.gives as Demands;

const LIST = "00000007-0000-4000-8000-000000000c21";

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
        versionName: null,
        name: "Categories",
        versionId: LIST,
        number: 1,
        standing: "in_service",
      },
    },
  ],
};

describe("candidate whole-half writes", () => {
  const first = { ...freshDraft("first"), fieldId: "first", name: "first" };
  const second = { ...freshDraft("second"), fieldId: "second", name: "second" };
  it("sends one candidate alongside the other stored field, not its closed raw editor", () => {
    const raw = [
      { ...first, label: "Selected" },
      { ...second, help: "Still only typed" },
    ];
    expect(candidateFields([first, second], raw, "first")).toEqual([
      { ...first, label: "Selected" },
      second,
    ]);
    expect(
      rebaseCandidates(
        [first, second],
        [{ ...first, label: "Selected" }, second],
        raw,
        "first",
      ),
    ).toEqual(raw);
  });
  it("adds a candidate without admitting another new or changed field", () => {
    const added = { ...freshDraft("new"), name: "new_field" };
    const another = { ...freshDraft("another"), name: "another_field" };
    expect(
      candidateFields(
        [first],
        [{ ...first, help: "Not sent" }, added, another],
        "new",
      ),
    ).toEqual([first, added]);
  });
  it("saves a child using its stored parent while retaining the parent's raw name afterwards", () => {
    const parent = {
      ...freshDraft("parent"),
      fieldId: "parent",
      name: "parent",
      kind: "fields" as const,
      fields: [first, second],
    };
    const raw = [
      {
        ...parent,
        name: "typed_parent",
        fields: [
          { ...first, label: "Child" },
          { ...second, help: "Closed child" },
        ],
      },
    ];
    const sent = [
      { ...parent, fields: [{ ...first, label: "Child" }, second] },
    ];
    expect(candidateFields([parent], raw, "first")).toEqual(sent);
    expect(rebaseCandidates([parent], sent, raw, "first")).toEqual(raw);
  });
  it("removes only the selected field and preserves the stored neighbour's facts", () => {
    expect(
      candidateFields(
        [first, second],
        [{ ...second, label: "Not sent" }],
        "first",
      ),
    ).toEqual([second]);
  });
  it.each(["new-parent", "new-child"])(
    "rebases a sent new subtree through %s onto its canonical identities",
    (applied) => {
      const child = { ...freshDraft("new-child"), name: "child" };
      const parent = {
        ...freshDraft("new-parent"),
        name: "parent",
        kind: "fields" as const,
        fields: [child],
      };
      const closed = {
        ...freshDraft("closed"),
        name: "closed",
        longest: "000128",
      };
      const canonical = {
        ...parent,
        key: "server-parent",
        fieldId: "server-parent",
        fields: [{ ...child, key: "server-child", fieldId: "server-child" }],
      };

      expect(candidateFields([], [parent, closed], applied)).toEqual([parent]);
      expect(
        rebaseCandidates([], [canonical], [parent, closed], applied),
      ).toEqual([canonical, closed]);
      expect(parent.fields).toEqual([child]);
      expect(closed.longest).toBe("000128");
    },
  );
  it("keeps a closed parent candidate while a new child receives its canonical identity", () => {
    const parent = {
      ...freshDraft("parent"),
      fieldId: "parent",
      name: "parent",
      kind: "fields" as const,
      fields: [first],
    };
    const child = { ...freshDraft("new-child"), name: "child" };
    const raw = [
      { ...parent, label: "Closed raw label", fields: [first, child] },
    ];
    const canonical = {
      ...parent,
      fields: [
        first,
        { ...child, key: "server-child", fieldId: "server-child" },
      ],
    };

    expect(rebaseCandidates([parent], [canonical], raw, child.key)).toEqual([
      { ...canonical, label: "Closed raw label" },
    ]);
  });
  it("moves the selected field without mutating the stored input", () => {
    const stored = [first, second];
    expect(candidateFields(stored, [second, first], "second")).toEqual([
      second,
      first,
    ]);
    expect(stored).toEqual([first, second]);
  });
  it("keeps a server's sibling order while rebasing a content-only candidate", () => {
    const typed = { ...first, help: "Only typed, not confirmed" };
    expect(
      rebaseCandidates([first, second], [second, first], [typed, second], ""),
    ).toEqual([second, typed]);
    expect(
      rebaseCandidates([first, second], [first, second], [second, typed], ""),
    ).toEqual([second, typed]);
  });
  it("reattaches content candidates to canonical keys by confirmed names within each parent", () => {
    const parent = {
      ...freshDraft("parent"),
      name: "parent",
      kind: "fields" as const,
      fields: [first, second],
    };
    const other = {
      ...freshDraft("other"),
      name: "other",
      kind: "fields" as const,
      fields: [{ ...first, key: "other-first", fieldId: null }],
    };
    const canonicalSecond = {
      ...second,
      key: "canonical-second",
      fieldId: "canonical-second",
    };
    const canonicalFirst = {
      ...first,
      key: "canonical-first",
      fieldId: "canonical-first",
    };
    const canonicalParent = {
      ...parent,
      key: "canonical-parent",
      fieldId: "canonical-parent",
      fields: [canonicalSecond, canonicalFirst],
    };
    const canonicalOther = {
      ...other,
      key: "canonical-other",
      fieldId: "canonical-other",
      fields: [
        {
          ...first,
          key: "canonical-other-first",
          fieldId: "canonical-other-first",
        },
      ],
    };
    const typed = [
      {
        ...parent,
        fields: [{ ...first, name: "typed_first", help: "Only typed" }, second],
      },
      {
        ...other,
        fields: [{ ...other.fields[0]!, label: "Other parent candidate" }],
      },
    ];
    expect(
      rebaseCandidates(
        [parent, other],
        [canonicalOther, canonicalParent],
        typed,
        "",
      ),
    ).toEqual([
      {
        ...canonicalOther,
        fields: [
          { ...canonicalOther.fields[0]!, label: "Other parent candidate" },
        ],
      },
      {
        ...canonicalParent,
        fields: [
          canonicalSecond,
          { ...canonicalFirst, name: "typed_first", help: "Only typed" },
        ],
      },
    ]);
    expect(typed[0]!.fields[0]!.key).toBe(first.key);
  });
  it("keeps server order inside a reordered subtree while retaining parent and child text", () => {
    const parent = {
      ...freshDraft("parent"),
      fieldId: "parent",
      name: "parent",
      kind: "fields" as const,
      fields: [first, second],
    };
    const third = { ...freshDraft("third"), fieldId: "third", name: "third" };
    const typedFirst = { ...first, longest: "000128" };
    const candidates = [
      { ...parent, label: "Only typed parent", fields: [typedFirst, second] },
      third,
    ];
    expect(
      rebaseCandidates(
        [parent, third],
        [third, { ...parent, fields: [second, first] }],
        candidates,
        "",
      ),
    ).toEqual([
      third,
      { ...parent, label: "Only typed parent", fields: [second, typedFirst] },
    ]);
    expect(parent.fields).toEqual([first, second]);
    expect(typedFirst.longest).toBe("000128");
  });
  it("rebases closed moved parents and children by their positions while retaining the sent response and raw limits", () => {
    const parent = {
      ...freshDraft("parent"),
      fieldId: "parent",
      name: "parent",
      kind: "fields" as const,
      fields: [first, second],
    };
    const third = { ...freshDraft("third"), fieldId: "third", name: "third" };
    const stored = [parent, third];
    const rawParent = {
      ...parent,
      fields: [second, { ...first, longest: "000128" }],
    };
    const answered = { ...third, label: "Saved third" };
    const candidates = [answered, rawParent];
    expect(
      rebaseCandidates(stored, [parent, answered], candidates, "third"),
    ).toEqual(candidates);
    expect(stored).toEqual([parent, third]);
    expect(parent.fields).toEqual([first, second]);
  });
});

function drafted(changed: Partial<DraftField> = {}): DraftField {
  return { ...freshDraft("row"), name: "summary", longest: "100", ...changed };
}

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
  it("keeps a text field's line choice through drafting and omits it only while another kind is sent", () => {
    const one = draftOf({ ...DETAILS.fields![0]!, lines: "one" });
    const older = draftOf(DETAILS.fields![0]!);

    expect(one.lines).toBe("one");
    expect(older.lines).toBe("several");
    expect(sentOf(one, TAKES).lines).toBe("one");
    expect(sentOf(older, TAKES).lines).toBe("several");
    expect("lines" in sentOf({ ...one, kind: "number" }, TAKES)).toBe(false);
    expect(sentOf({ ...one, kind: "text" }, TAKES).lines).toBe("one");
  });

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
          lines: "several",
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
      WORKFLOW_GIVES,
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
      "lines",
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
      WORKFLOW_GIVES,
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
      lines: "one",
      mustBeGiven: true,
    });
    expect(sentOf(drafted({ kind: "term", stands: "" }), GIVES)).toMatchObject({
      list: null,
      stands: null,
      floor: null,
    });
  });

  it("reads file constraints in their order and sends their byte value back", () => {
    const field = {
      fieldId: "file-1",
      name: "documents",
      kind: "file" as const,
      types: [".pdf", ".png"],
      maximumBytes: 10 * 1024 * 1024,
      many: true,
      most: 3,
      mustBeGiven: true,
    };

    const draft = draftOf(field);
    const sent = sentOf(draft, TAKES);

    expect(draft.fileTypes).toBe(".pdf\n.png");
    expect(draft.fileSize).toBe("10");
    expect(draft.fileSizeUnit).toBe("MB");
    expect(sent).toMatchObject({
      kind: "file",
      types: [".pdf", ".png"],
      maximumBytes: 10485760,
      most: 3,
    });
    expect("longest" in sent).toBe(false);
    expect("list" in sent).toBe(false);
    expect("fields" in sent).toBe(false);
    expect(heldFor([draft], TAKES)).toBeNull();
  });

  it("keeps file input while another kind is sent without file members", () => {
    const file = drafted({
      kind: "file",
      fileTypes: ".pdf",
      fileSize: "2",
      fileSizeUnit: "GB",
    });
    const other = { ...file, kind: "number" as const };

    expect(sentOf(file, TAKES)).toMatchObject({
      types: [".pdf"],
      maximumBytes: 2147483648,
    });
    expect("types" in sentOf(other, TAKES)).toBe(false);
    expect("maximumBytes" in sentOf(other, TAKES)).toBe(false);
    expect(sentOf({ ...other, kind: "file" }, TAKES).maximumBytes).toBe(
      2147483648,
    );
  });

  it.each([
    ["1", "bytes", 1],
    ["2", "KB", 2048],
    ["3", "MB", 3145728],
    ["4", "GB", 4294967296],
    ["9007199254740991", "bytes", Number.MAX_SAFE_INTEGER],
    ["8796093022207", "KB", 9007199254739968],
    ["8589934591", "MB", 9007199253692416],
    ["8388607", "GB", 9007198180999168],
  ] as const)(
    "projects and sends a valid file size of %s %s as bytes",
    (fileSize, fileSizeUnit, bytes) => {
      const draft = drafted({
        kind: "file",
        fileTypes: " .PDF \n .png ",
        fileSize,
        fileSizeUnit,
      });

      expect(fileBytesOf(fileSize, fileSizeUnit)).toBe(bytes);
      expect(fileTypesOf(draft.fileTypes)).toEqual([".pdf", ".png"]);
      expect(heldFor([draft], TAKES)).toBeNull();
      expect(sentOf(draft, TAKES)).toMatchObject({
        types: [".pdf", ".png"],
        maximumBytes: bytes,
      });
      expect(sentOf(draft, TAKES)).not.toHaveProperty("longest");
    },
  );

  it.each([
    ["", "bytes", true],
    ["0", "bytes", false],
    ["01", "bytes", false],
    ["-1", "KB", false],
    ["1.5", "MB", false],
    ["1e3", "bytes", false],
    ["9007199254740992", "bytes", false],
    ["8796093022208", "KB", false],
    ["8589934592", "MB", false],
    ["8388608", "GB", false],
  ] as const)(
    "keeps an unchosen or invalid file size %s %s out of the byte projection",
    (fileSize, fileSizeUnit, valid) => {
      const draft = drafted({
        kind: "file",
        fileTypes: ".pdf",
        fileSize,
        fileSizeUnit,
      });

      expect(fileBytesOf(fileSize, fileSizeUnit)).toBeNull();
      expect(fileSizeFits(fileSize, fileSizeUnit)).toBe(valid);
      expect(heldFor([draft], TAKES)).toEqual(
        valid ? null : { why: "refusal.FILE_SIZE_LIMIT_UNUSABLE", place: [0] },
      );
      if (valid) {
        expect(sentOf(draft, TAKES).maximumBytes).toBeNull();
      }
    },
  );

  it("starts a field nothing is typed in as text holding one, to be given", () => {
    expect(freshDraft("new-1")).toEqual({
      key: "new-1",
      fieldId: null,
      name: "",
      label: "",
      help: "",
      kind: "text",
      longest: "",
      lines: "one",
      list: "",
      fileTypes: "",
      fileSize: "",
      fileSizeUnit: "bytes",
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

  it.each([
    ["", "1", "bytes", "declaration.fileTypesInvalid"],
    [".png\n.PNG", "1", "bytes", "declaration.fileTypesInvalid"],
    ["image/*", "1", "bytes", "declaration.fileTypesInvalid"],
    [".png; charset=utf-8", "1", "bytes", "declaration.fileTypesInvalid"],
    [".png", "0", "bytes", "refusal.FILE_SIZE_LIMIT_UNUSABLE"],
    [".png", "1.5", "KB", "refusal.FILE_SIZE_LIMIT_UNUSABLE"],
    [".png", "8388608", "GB", "refusal.FILE_SIZE_LIMIT_UNUSABLE"],
  ] as const)(
    "holds an invalid file constraint at its row for %s and %s %s",
    (fileTypes, fileSize, fileSizeUnit, why) => {
      const file = drafted({ kind: "file", fileTypes, fileSize, fileSizeUnit });

      expect(heldFor([drafted({ name: "first" }), file], TAKES)).toEqual({
        why,
        place: [1],
      });
      expect(heldFor([{ ...file, kind: "number" }], TAKES)).toBeNull();
    },
  );

  it("accepts sixty-four file types and refuses the next before checking their spelling", () => {
    const types = Array.from({ length: 64 }, (_, index) => `.x${index}`);
    const accepted = drafted({
      kind: "file",
      fileTypes: types.join("\n"),
      fileSize: "1",
    });
    const tooMany = {
      ...accepted,
      fileTypes: `bad;type\n${types.join("\n")}`,
    };

    expect(fileTypesTooMany(accepted.fileTypes)).toBe(false);
    expect(heldFor([accepted], TAKES)).toBeNull();
    expect(sentOf(accepted, TAKES).types).toEqual(types);
    expect(fileTypesTooMany(tooMany.fileTypes)).toBe(true);
    expect(heldFor([tooMany], TAKES)).toEqual({
      why: "refusal.FILE_TYPES_TOO_MANY",
      place: [0],
    });
  });

  it.each([
    [`.${"A".repeat(32)}`, true],
    [`.${"a".repeat(33)}`, false],
    [`.${"A".repeat(30)}.B`, true],
    [`.${"a".repeat(31)}.b`, false],
  ] as const)("holds or sends the whole extension %s", (extension, fits) => {
    const file = drafted({ kind: "file", fileTypes: extension, fileSize: "1" });
    expect(heldFor([file], TAKES)).toEqual(
      fits ? null : { why: "declaration.fileTypesInvalid", place: [0] },
    );
    if (fits) {
      expect(sentOf(file, TAKES).types).toEqual([extension.toLowerCase()]);
    }
  });

  it("accepts the largest safe byte count and rejects one more across units", () => {
    const file = drafted({
      kind: "file",
      fileTypes: ".PDF\n.png",
      fileSize: String(Number.MAX_SAFE_INTEGER),
      fileSizeUnit: "bytes",
    });

    expect(heldFor([file], TAKES)).toBeNull();
    expect(sentOf(file, TAKES).types).toEqual([".pdf", ".png"]);
    expect(sentOf(file, TAKES).maximumBytes).toBe(Number.MAX_SAFE_INTEGER);
    expect(
      heldFor(
        [{ ...file, fileSize: String(Number.MAX_SAFE_INTEGER + 1) }],
        TAKES,
      ),
    ).toEqual({ why: "refusal.FILE_SIZE_LIMIT_UNUSABLE", place: [0] });
  });

  it("keeps an unchosen file size empty when a draft is read and sends none until a valid size is entered", () => {
    const file = drafted({
      kind: "file",
      fileTypes: ".pdf",
      fileSize: "",
      fileSizeUnit: "bytes",
    });
    expect(heldFor([file], TAKES)).toBeNull();
    expect(sentOf(file, TAKES).maximumBytes).toBeNull();
    expect(
      draftOf({
        fieldId: "file-1",
        name: "documents",
        kind: "file",
        types: [".pdf"],
        many: false,
        mustBeGiven: false,
      }).fileSize,
    ).toBe("");
    expect(heldFor([{ ...file, fileSize: "0" }], TAKES)).toEqual({
      why: "refusal.FILE_SIZE_LIMIT_UNUSABLE",
      place: [0],
    });
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
