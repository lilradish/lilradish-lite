import { describe, expect, it, vi } from "vitest";

import {
  fillFieldsFrom,
  pathKey,
  type FillField,
  type FillProblem,
  type FillValues,
} from "../../../api/filling";
import TABLE from "../../../lib/filling/filling.cases.json";
import { valueRefused } from "../../../lib/filling/writing";
import {
  draftFrom,
  draftOf,
  draftReader,
  offsetOf,
  offsetWritten,
  oneDraftOf,
  placedAt,
  suggestedName,
  type LevelDraft,
} from "./fillDrafts";

vi.mock("../../../lib/filling/writing", async (importOriginal) => {
  const actual =
    await importOriginal<typeof import("../../../lib/filling/writing")>();
  return { ...actual, valueRefused: vi.fn(actual.valueRefused) };
});

/** One level as it is sent, what reading it names, and what is kept where nothing is named. */
interface StructuralCase {
  readonly case: string;
  readonly sent: LevelDraft;
  readonly problems: readonly FillProblem[];
  readonly kept?: FillValues;
}

const STRUCTURAL_FIELDS = fillFieldsFrom(TABLE.fields) ?? [];

const STRUCTURAL = TABLE.cases as readonly StructuralCase[];

const NONE_UNREADABLE: ReadonlySet<string> = new Set();

const COMPLAINT: FillField = {
  name: "complaint",
  kind: "text",
  longest: 10,
  mustBeGiven: true,
};

const AMOUNT: FillField = {
  name: "amount",
  kind: "number",
  mustBeGiven: false,
};

const URGENT: FillField = {
  name: "urgent",
  kind: "yes_no",
  mustBeGiven: false,
};

const AT: FillField = { name: "at", kind: "moment", mustBeGiven: false };

const TAGS: FillField = {
  name: "tags",
  kind: "text",
  longest: 6,
  most: 2,
  mustBeGiven: true,
};

/** Fields that may be left empty, holding one that must be given. */
const CONTACT: FillField = {
  name: "contact",
  kind: "fields",
  mustBeGiven: false,
  fields: [
    { name: "email", kind: "text", longest: 20, mustBeGiven: true },
    { name: "phones", kind: "text", longest: 12, most: 2, mustBeGiven: false },
  ],
};

/** Fields that must be given, holding only what may be left empty. */
const ADDRESS: FillField = {
  name: "address",
  kind: "fields",
  mustBeGiven: true,
  fields: [{ name: "street", kind: "text", longest: 20, mustBeGiven: false }],
};

const ITEMS: FillField = {
  name: "items",
  kind: "fields",
  most: 2,
  mustBeGiven: false,
  fields: [
    { name: "name", kind: "text", longest: 10, mustBeGiven: true },
    { name: "count", kind: "number", mustBeGiven: false },
  ],
};

const FIELDS = [COMPLAINT, AMOUNT, URGENT, AT, TAGS, CONTACT, ADDRESS, ITEMS];

/** As little as fits: what must be given, and nothing else. */
const LEAST: LevelDraft = {
  ...draftOf(FIELDS),
  complaint: "Broken",
  tags: ["kettle"],
  address: { street: "1 Main" },
};

/** One reading by a reader of its own, which carries nothing over from any other. */
function readDraft(
  fields: readonly FillField[],
  draft: LevelDraft,
  unreadable: ReadonlySet<string> = NONE_UNREADABLE,
) {
  return draftReader()(fields, draft, unreadable);
}

describe("draftOf", () => {
  it("starts every field empty: many with none, a moment with nothing chosen, and fields holding their own", () => {
    expect(draftOf(FIELDS)).toEqual({
      complaint: "",
      amount: "",
      urgent: "",
      at: { date: "", time: "", offset: null },
      tags: [],
      contact: { email: "", phones: [] },
      address: { street: "" },
      items: [],
    });
  });
});

describe("oneDraftOf", () => {
  it("starts one more of many as one of it empty, whatever it is", () => {
    expect(oneDraftOf(TAGS)).toBe("");
    expect(oneDraftOf(ITEMS)).toEqual({ name: "", count: "" });
  });
});

describe("draftFrom", () => {
  /** Every field given as the server sends a try's values back: none as null, many as a list. */
  const GIVEN: FillValues = {
    complaint: "Broken",
    amount: "12.5",
    urgent: "true",
    at: "2026-09-26T10:15:30+02:00",
    tags: ["kettle", "lid"],
    contact: { email: "ada@example.org", phones: ["0123"] },
    address: { street: "1 Main" },
    items: [{ name: "kettle", count: null }],
  };

  it("holds each value as its control holds it, a moment in its own offset, and none as nothing chosen", () => {
    expect(draftFrom(FIELDS, GIVEN)).toEqual({
      complaint: "Broken",
      amount: "12.5",
      urgent: "true",
      at: { date: "2026-09-26", time: "10:15:30", offset: "+02:00" },
      tags: ["kettle", "lid"],
      contact: { email: "ada@example.org", phones: ["0123"] },
      address: { street: "1 Main" },
      items: [{ name: "kettle", count: "" }],
    });
  });

  it("is read back as exactly the values it was drawn from, naming nothing", () => {
    const read = readDraft(FIELDS, draftFrom(FIELDS, GIVEN));

    expect(read.values).toEqual(GIVEN);
    expect(read.problems).toEqual([]);
  });

  it("keeps a moment's fraction of a second of up to three digits and an offset west of UTC as they were written", () => {
    const written = "0999-01-02T03:04:05.123-05:30";

    const drawn = draftFrom([AT], { at: written });

    expect(drawn.at).toEqual({
      date: "0999-01-02",
      time: "03:04:05.123",
      offset: "-05:30",
    });
    expect(readDraft([AT], drawn).values).toEqual({ at: written });
  });

  it("leaves as nothing filled a moment whose fraction of a second runs past the three digits a time box holds", () => {
    const drawn = draftFrom([{ ...AT, mustBeGiven: true }], {
      at: "0999-01-02T03:04:05.1234-05:30",
    });

    expect(drawn.at).toEqual({ date: "", time: "", offset: null });
    expect(readDraft([{ ...AT, mustBeGiven: true }], drawn).problems).toEqual([
      { path: ["at"], reason: "missing" },
    ]);
  });

  it("leaves as nothing filled what is not given, and what is shaped as no control of its field holds", () => {
    const drawn = draftFrom(FIELDS, {
      complaint: null,
      amount: ["12"],
      urgent: { yes: "true" },
      at: "2026-09-26 10:15",
      tags: "kettle",
      contact: "ada@example.org",
      items: null,
    });

    expect(drawn).toEqual(draftOf(FIELDS));
    expect(readDraft(FIELDS, drawn).problems).toEqual([
      { path: ["complaint"], reason: "missing" },
      { path: ["tags"], reason: "missing" },
      { path: ["address"], reason: "missing" },
    ]);
  });

  it("draws one of many that is none, or shaped as none of its field's, as one left empty, keeping its place", () => {
    const drawn = draftFrom([TAGS, ITEMS], {
      tags: [null, "lid", ["x"]],
      items: [["kettle"], { name: "lid" }],
    });

    expect(drawn).toEqual({
      tags: ["", "lid", ""],
      items: [
        { name: "", count: "" },
        { name: "lid", count: "" },
      ],
    });
  });
});

describe("placedAt", () => {
  it("places a value within one of many fields, making anew only what holds it and leaving the draft it was given as it was", () => {
    const draft: LevelDraft = {
      ...LEAST,
      items: [
        { name: "kettle", count: "" },
        { name: "lid", count: "" },
      ],
    };

    const placed = placedAt(draft, ["items", 1, "count"], "2");

    expect(placed.items).toEqual([
      { name: "kettle", count: "" },
      { name: "lid", count: "2" },
    ]);
    expect((placed.items as readonly LevelDraft[])[0]).toBe(
      (draft.items as readonly LevelDraft[])[0],
    );
    expect(placed.address).toBe(draft.address);
    expect(placed.at).toBe(draft.at);
    expect((draft.items as readonly LevelDraft[])[1]!.count).toBe("");
  });

  it("places many whole at the field's own path", () => {
    const placed = placedAt(LEAST, ["tags"], ["kettle", "lid"]);

    expect(placed).toEqual({ ...LEAST, tags: ["kettle", "lid"] });
  });
});

describe("draftReader", () => {
  it("sends each value as typed, none where nothing was, and names no problem where everything fits", () => {
    const read = readDraft(FIELDS, {
      ...LEAST,
      complaint: " Broken\n",
      amount: "12.50",
      urgent: "false",
      contact: { email: "ada@example.org", phones: [] },
      items: [{ name: "kettle", count: "" }],
    });

    expect(read.values).toEqual({
      complaint: " Broken\n",
      amount: "12.50",
      urgent: "false",
      at: null,
      tags: ["kettle"],
      contact: { email: "ada@example.org", phones: [] },
      address: { street: "1 Main" },
      items: [{ name: "kettle", count: null }],
    });
    expect(read.problems).toEqual([]);
  });

  it("names every place that does not fit in declared order, a field before what it holds, and sends none there", () => {
    const read = readDraft(FIELDS, {
      ...LEAST,
      complaint: "   ",
      amount: "-0",
      tags: ["longer than six", ""],
    });

    expect(read.problems).toEqual([
      { path: ["complaint"], reason: "missing" },
      { path: ["amount"], reason: "malformed" },
      { path: ["tags", 0], reason: "too_long" },
      { path: ["tags", 1], reason: "missing" },
    ]);
    expect(read.values.complaint).toBeNull();
    expect(read.values.amount).toBeNull();
    expect(read.values.tags).toEqual([null, null]);
  });

  it("names many holding more than its most once, at the field, reading none of them and sending none", () => {
    const read = readDraft(FIELDS, {
      ...LEAST,
      tags: ["longer than six", "", "x"],
    });

    expect(read.problems).toEqual([{ path: ["tags"], reason: "too_many" }]);
    expect(read.values.tags).toBeNull();
  });

  it("names many that must be given and hold none as missing, at the field and nowhere else", () => {
    const read = readDraft(FIELDS, { ...LEAST, tags: [] });

    expect(read.problems).toEqual([{ path: ["tags"], reason: "missing" }]);
    expect(read.values.tags).toEqual([]);
  });

  it("reads fields that may be left empty and hold nothing as no value, naming nothing missing within them", () => {
    const read = readDraft(FIELDS, LEAST);

    expect(read.values.contact).toBeNull();
    expect(read.problems).toEqual([]);
  });

  it("names what is missing within fields that may be left empty once anything in them is given", () => {
    const read = readDraft(FIELDS, {
      ...LEAST,
      contact: { email: "", phones: ["0123"] },
    });

    expect(read.problems).toEqual([
      { path: ["contact", "email"], reason: "missing" },
    ]);
    expect(read.values.contact).toEqual({ email: null, phones: ["0123"] });
  });

  it("names fields that must be given and hold nothing as missing at the fields, and nothing within them", () => {
    const read = readDraft(FIELDS, { ...LEAST, address: { street: "" } });

    expect(read.problems).toEqual([{ path: ["address"], reason: "missing" }]);
    expect(read.values.address).toBeNull();
  });

  it("names one of many fields holding nothing as missing at its place, and nothing within it", () => {
    const read = readDraft(FIELDS, {
      ...LEAST,
      items: [{ name: "", count: "" }],
    });

    expect(read.problems).toEqual([{ path: ["items", 0], reason: "missing" }]);
    expect(read.values.items).toEqual([null]);
  });

  it("keeps fields holding nothing but a value that does not fit as fields, naming that value", () => {
    const read = readDraft(FIELDS, {
      ...LEAST,
      items: [{ name: "", count: "1e3" }],
    });

    expect(read.problems).toEqual([
      { path: ["items", 0, "name"], reason: "missing" },
      { path: ["items", 0, "count"], reason: "malformed" },
    ]);
    expect(read.values.items).toEqual([{ name: null, count: null }]);
  });

  it("writes a moment chosen to the minute to the second, at the reader's own offset on the day chosen", () => {
    const summer = readDraft(FIELDS, {
      ...LEAST,
      at: { date: "2026-07-01", time: "09:30", offset: null },
    });
    const winter = readDraft(FIELDS, {
      ...LEAST,
      at: { date: "2026-12-01", time: "09:30:15", offset: null },
    });

    expect(summer.values.at).toBe("2026-07-01T09:30:00+02:00");
    expect(winter.values.at).toBe("2026-12-01T09:30:15+01:00");
    expect(summer.problems).toEqual([]);
  });

  it("writes a moment at the offset chosen for it, whatever the reader's own", () => {
    const read = readDraft(FIELDS, {
      ...LEAST,
      at: { date: "2026-07-01", time: "09:30", offset: "-03:30" },
    });

    expect(read.values.at).toBe("2026-07-01T09:30:00-03:30");
  });

  it("names a moment with a date and no time as not written as a moment is", () => {
    const read = readDraft(FIELDS, {
      ...LEAST,
      at: { date: "2026-07-01", time: "", offset: null },
    });

    expect(read.problems).toEqual([{ path: ["at"], reason: "malformed" }]);
    expect(read.values.at).toBeNull();
  });

  it("names a moment at a time the reader's clock springs past as not written as a moment is, sending none", () => {
    const read = readDraft(FIELDS, {
      ...LEAST,
      at: { date: "2026-03-29", time: "02:30", offset: null },
    });

    expect(read.problems).toEqual([{ path: ["at"], reason: "malformed" }]);
    expect(read.values.at).toBeNull();
  });

  it("writes a time the reader's clock springs past at an offset chosen for it, and the first minute after the spring at the reader's own", () => {
    const chosen = readDraft(FIELDS, {
      ...LEAST,
      at: { date: "2026-03-29", time: "02:30", offset: "+01:00" },
    });
    const after = readDraft(FIELDS, {
      ...LEAST,
      at: { date: "2026-03-29", time: "03:00", offset: null },
    });

    expect(chosen.values.at).toBe("2026-03-29T02:30:00+01:00");
    expect(after.values.at).toBe("2026-03-29T03:00:00+02:00");
    expect([...chosen.problems, ...after.problems]).toEqual([]);
  });

  it("names a place holding what the browser could not read as not written as its field takes it, and sends none there", () => {
    const unreadable = new Set([pathKey(["at"])]);

    const read = readDraft(FIELDS, LEAST, unreadable);

    expect(read.problems).toEqual([{ path: ["at"], reason: "malformed" }]);
    expect(read.values.at).toBeNull();
    expect(read.values.complaint).toBe("Broken");
  });

  it("judges again only the place whose value changed, keeping every other place's verdict from the reading before", () => {
    const reader = draftReader();
    const before: LevelDraft = {
      ...LEAST,
      amount: "12.50",
      contact: { email: "ada@example.org", phones: [] },
    };
    reader(FIELDS, before, NONE_UNREADABLE);
    vi.mocked(valueRefused).mockClear();

    const read = reader(
      FIELDS,
      placedAt(before, ["amount"], "1e3"),
      NONE_UNREADABLE,
    );

    expect(
      vi.mocked(valueRefused).mock.calls.map(([, typed]) => typed),
    ).toEqual(["1e3"]);
    expect(read.problems).toEqual([{ path: ["amount"], reason: "malformed" }]);
    expect(read.values.contact).toEqual({
      email: "ada@example.org",
      phones: [],
    });
  });

  it("judges a place again where its field is another, though it holds what it held", () => {
    const reader = draftReader();
    reader(FIELDS, LEAST, NONE_UNREADABLE);
    vi.mocked(valueRefused).mockClear();
    const shorter = FIELDS.map((field) =>
      field === COMPLAINT ? { ...COMPLAINT, longest: 3 } : field,
    );

    const read = reader(shorter, LEAST, NONE_UNREADABLE);

    expect(
      vi.mocked(valueRefused).mock.calls.map(([, typed]) => typed),
    ).toEqual(["Broken"]);
    expect(read.problems).toEqual([
      { path: ["complaint"], reason: "too_long" },
    ]);
  });
});

describe("draftReader, over the table the server's reading is run over too (`FillingStructuralIntegrationSpec`)", () => {
  it("holds every field the table declares and cases that fit, each with what is kept, and cases that do not, an empty table agreeing with anything", () => {
    expect(STRUCTURAL_FIELDS.length).toBeGreaterThan(0);
    expect(STRUCTURAL_FIELDS).toHaveLength(TABLE.fields.length);
    expect(STRUCTURAL.some(({ problems }) => problems.length === 0)).toBe(true);
    expect(STRUCTURAL.some(({ problems }) => problems.length > 0)).toBe(true);
    expect(
      STRUCTURAL.every(
        ({ problems, kept }) =>
          (problems.length === 0) === (kept !== undefined),
      ),
    ).toBe(true);
  });

  it.each(STRUCTURAL)(
    "names, for $case, the places the server names, in its order",
    ({ sent, problems }) => {
      expect(readDraft(STRUCTURAL_FIELDS, sent).problems).toEqual(problems);
    },
  );

  it.each(STRUCTURAL.filter(({ kept }) => kept !== undefined))(
    "sends, for $case, every value as the server keeps it",
    ({ sent, kept }) => {
      expect(readDraft(STRUCTURAL_FIELDS, sent).values).toEqual(kept);
    },
  );
});

describe("offsetOf", () => {
  it("follows the reader's own offset at the date and time chosen until one is chosen", () => {
    expect(offsetOf({ date: "2026-03-29", time: "01:59", offset: null })).toBe(
      "+01:00",
    );
    expect(offsetOf({ date: "2026-03-29", time: "03:00", offset: null })).toBe(
      "+02:00",
    );
    expect(
      offsetOf({ date: "2026-03-29", time: "03:00", offset: "+05:45" }),
    ).toBe("+05:45");
  });

  it("writes an offset kept to the second, before standard time, to the nearest minute", () => {
    expect(offsetOf({ date: "1850-01-01", time: "12:00", offset: null })).toBe(
      "+00:53",
    );
  });
});

describe("suggestedName", () => {
  it("is the first line of the first text given, cut at a tab as at any control", () => {
    expect(
      suggestedName(FIELDS, {
        ...LEAST,
        complaint: "Kettle broke\tagain\nSecond line",
      }),
    ).toBe("Kettle broke");
  });

  it("is cut to 60 characters, a character beyond the basic plane counted once", () => {
    const grinning = String.fromCodePoint(0x1f600);

    expect(
      suggestedName(FIELDS, { ...LEAST, complaint: grinning.repeat(70) }),
    ).toBe(grinning.repeat(60));
  });

  it("is none where the first text field's first line is none a run could be called, whatever a later line or text holds", () => {
    expect(
      suggestedName(FIELDS, {
        ...LEAST,
        complaint: "  \nKettle",
        tags: ["second"],
        contact: { email: "ada@example.org", phones: [] },
      }),
    ).toBeNull();
    expect(
      suggestedName(FIELDS, { ...draftOf(FIELDS), complaint: "\n" }),
    ).toBeNull();
  });

  it("is the first of many where the first text field holds many, and none where it holds none or the first is empty", () => {
    const tagsFirst = [TAGS, COMPLAINT];

    expect(
      suggestedName(tagsFirst, {
        tags: ["kettle", "leak"],
        complaint: "Broken",
      }),
    ).toBe("kettle");
    expect(
      suggestedName(tagsFirst, { tags: ["", "leak"], complaint: "Broken" }),
    ).toBeNull();
    expect(
      suggestedName(tagsFirst, { tags: [], complaint: "Broken" }),
    ).toBeNull();
  });

  it("finds the first text field depth first, within fields declared before any text, and within the first of many fields", () => {
    const contactFirst = [AMOUNT, CONTACT, COMPLAINT];
    const itemsFirst = [ITEMS, COMPLAINT];

    expect(
      suggestedName(contactFirst, {
        amount: "12",
        contact: { email: "ada@example.org", phones: [] },
        complaint: "Broken",
      }),
    ).toBe("ada@example.org");
    expect(
      suggestedName(contactFirst, {
        amount: "12",
        contact: { email: "", phones: ["0123"] },
        complaint: "Broken",
      }),
    ).toBeNull();
    expect(
      suggestedName(itemsFirst, {
        items: [
          { name: "kettle", count: "" },
          { name: "lid", count: "" },
        ],
        complaint: "Broken",
      }),
    ).toBe("kettle");
    expect(
      suggestedName(itemsFirst, { items: [], complaint: "Broken" }),
    ).toBeNull();
  });

  it("is none where no text field is declared", () => {
    expect(
      suggestedName([AMOUNT, URGENT], { amount: "12", urgent: "true" }),
    ).toBeNull();
    expect(suggestedName([], {})).toBeNull();
  });
});

describe("offsetWritten", () => {
  it.each([
    [0, "+00:00"],
    [330, "+05:30"],
    [-210, "-03:30"],
    [840, "+14:00"],
    [-840, "-14:00"],
  ])("writes %i minutes east as %s", (minutes, written) => {
    expect(offsetWritten(minutes)).toBe(written);
  });
});
