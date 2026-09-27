import { describe, expect, it } from "vitest";

import { answering, refusalOf } from "../../../testutil/answering";
import { NOT_A_PROBLEM_DOCUMENT, NOT_AN_ADDRESS } from "../../problem";
import {
  atLibrary,
  entryFrom,
  libraryLoader,
  startEntry,
  type EntryKind,
} from "./{kind}";

const GROUP = "00000003-0000-4000-8000-000000000a71";

const ADA = { userId: "000a71", displayName: "Ada Lovelace" };

const NAMELESS = { userId: "000a72" };

const TRIAGE = {
  entryId: "00000006-0000-4000-8000-000000000a71",
  name: "Triage",
  inService: 2,
  submitted: true,
  stopped: false,
};

const WAITING = {
  entryId: "00000006-0000-4000-8000-000000000a72",
  name: "Waiting",
  submitted: false,
  stopped: true,
};

const DRAFT = {
  versionId: "00000007-0000-4000-8000-000000000a73",
  number: 3,
  standing: "draft",
  writers: [NAMELESS, ADA],
  writtenByMigration: false,
  acts: ["write", "submit"],
  pinnedBy: [],
};

const IN_SERVICE = {
  versionId: "00000007-0000-4000-8000-000000000a72",
  number: 2,
  standing: "in_service",
  writers: [ADA],
  writtenByMigration: false,
  approval: { approver: NAMELESS },
  acts: ["retire"],
  pinnedBy: [
    {
      entryId: "00000006-0000-4000-8000-000000000a79",
      kind: "workflow",
      name: "Handle",
      versionId: "00000007-0000-4000-8000-000000000a79",
      number: 4,
    },
  ],
};

const SEEDED = {
  versionId: "00000007-0000-4000-8000-000000000a71",
  number: 1,
  standing: "retired",
  writers: [],
  writtenByMigration: true,
  approval: {},
  acts: [],
  pinnedBy: [],
};

const ENTRY = {
  entryId: TRIAGE.entryId,
  kind: "question",
  name: "Triage",
  purpose: "Sorts what comes in.",
  stopped: { by: ADA, at: "2026-09-24T08:15:00Z" },
  acts: ["rename", "let_go"],
  versions: [DRAFT, IN_SERVICE, SEEDED],
};

/** The entry as read: every list of words a set, and every member left out left out. */
const READ = {
  ...ENTRY,
  acts: new Set(["rename", "let_go"]),
  versions: [
    { ...DRAFT, acts: new Set(["write", "submit"]) },
    { ...IN_SERVICE, acts: new Set(["retire"]) },
    { ...SEEDED, acts: new Set() },
  ],
};

describe("atLibrary", () => {
  it.each([
    ["workflow", "workflows"],
    ["question", "questions"],
    ["reference_list", "reference-lists"],
  ] satisfies [EntryKind, string][])(
    "hands over the %s entries' address under the group's escaped segment",
    async (kind, segment) => {
      const addresses = [GROUP, "a/b"].map((group) =>
        atLibrary(group, kind, (address) => Promise.resolve(address)),
      );

      expect(await Promise.all(addresses)).toEqual([
        `/api/groups/${GROUP}/${segment}`,
        `/api/groups/a%2Fb/${segment}`,
      ]);
    },
  );

  it("refuses a group that names no address, asking nothing", async () => {
    const failure = await refusalOf(
      atLibrary("..", "question", () => Promise.resolve("asked")),
    );

    expect(failure.problem).toEqual({ code: NOT_AN_ADDRESS });
  });
});

describe("libraryLoader", () => {
  it("asks for the kind's entries in the order and with the filter asked, from where the page left off", async () => {
    const sent = answering(['{"items":[]}', 200]);

    await libraryLoader(GROUP, "workflow", { filter: "", order: "name" })(
      null,
      new AbortController().signal,
    );
    await libraryLoader(GROUP, "reference_list", {
      filter: "Tri",
      order: "-inService",
    })("c1", new AbortController().signal);

    expect(sent.mock.calls.map(([address]) => address)).toEqual([
      `/api/groups/${GROUP}/workflows?sort=name`,
      `/api/groups/${GROUP}/reference-lists?sort=-inService&filter=Tri&cursor=c1`,
    ]);
  });

  it("reads every row, with the version in service only where one arrived", async () => {
    answering([JSON.stringify({ items: [TRIAGE, WAITING] }), 200]);

    const page = await libraryLoader(GROUP, "question", {
      filter: "",
      order: "name",
    })(null, new AbortController().signal);

    expect(page).toEqual({ items: [TRIAGE, WAITING] });
    expect("inService" in page.items[1]!).toBe(false);
  });

  it.each([
    ["no name", { ...TRIAGE, name: undefined }],
    ["a version in service that is no number", { ...TRIAGE, inService: "2" }],
    ["a version in service that is none", { ...TRIAGE, inService: 0 }],
    ["no word on whether one waits", { ...TRIAGE, submitted: "yes" }],
    ["no word on whether it is stopped", { ...TRIAGE, stopped: undefined }],
    ["no row at all", "Triage"],
  ])("refuses a whole page holding a row with %s", async (_case, row) => {
    answering([JSON.stringify({ items: [WAITING, row] }), 200]);

    const failure = await refusalOf(
      libraryLoader(GROUP, "question", { filter: "", order: "name" })(
        null,
        new AbortController().signal,
      ),
    );

    expect(failure.problem).toEqual({
      status: 200,
      code: NOT_A_PROBLEM_DOCUMENT,
    });
  });
});

describe("startEntry", () => {
  it("sends the name and what it is for, and nothing else, to the kind's entries", async () => {
    const sent = answering([JSON.stringify(ENTRY), 201]);

    const started = await startEntry(
      GROUP,
      "question",
      { name: "Triage", purpose: null },
      new AbortController().signal,
    );

    const [address, init] = sent.mock.calls[0]!;
    expect(address).toBe(`/api/groups/${GROUP}/questions`);
    expect(init?.method).toBe("POST");
    expect(JSON.parse(String(init?.body))).toEqual({
      name: "Triage",
      purpose: null,
    });
    expect(started).toEqual(READ);
  });
});

describe("entryFrom", () => {
  it("reads an entry whole: its stop, every version newest first, and each approval as it arrived", () => {
    expect(entryFrom(ENTRY)).toEqual(READ);
  });

  /** A member left out is left out, never read as present and empty. */
  it("reads an entry saying nothing of what it is for, stopped by nobody, and a draft nobody approved", () => {
    const unsaid = {
      entryId: ENTRY.entryId,
      kind: ENTRY.kind,
      name: ENTRY.name,
      acts: ENTRY.acts,
    };
    const read = entryFrom({ ...unsaid, versions: [DRAFT] })!;

    expect(read).toEqual({
      ...unsaid,
      acts: new Set(["rename", "let_go"]),
      versions: [{ ...DRAFT, acts: new Set(["write", "submit"]) }],
    });
    expect(["purpose", "stopped"].filter((member) => member in read)).toEqual(
      [],
    );
    expect("approval" in read.versions[0]!).toBe(false);
  });

  it("reads an approval naming nobody as one nobody made, never as no approval", () => {
    const read = entryFrom({ ...ENTRY, versions: [SEEDED] })!;

    expect(read.versions[0]!.approval).toEqual({});
  });

  it.each([
    ["no document at all", "Triage"],
    ["no kind", { ...ENTRY, kind: 7 }],
    ["no acts", { ...ENTRY, acts: undefined }],
    ["no versions", { ...ENTRY, versions: undefined }],
    ["a purpose that is no text", { ...ENTRY, purpose: null }],
    ["a stop naming nobody", { ...ENTRY, stopped: { at: ENTRY.stopped.at } }],
    ["a stop at no time", { ...ENTRY, stopped: { by: ADA } }],
    [
      "a version with no number",
      { ...ENTRY, versions: [{ ...DRAFT, number: "3" }] },
    ],
    [
      "a version with no acts",
      { ...ENTRY, versions: [{ ...DRAFT, acts: undefined }] },
    ],
    [
      "a version with no writers",
      { ...ENTRY, versions: [{ ...DRAFT, writers: undefined }] },
    ],
    [
      "a version saying nothing of a migration",
      { ...ENTRY, versions: [{ ...DRAFT, writtenByMigration: undefined }] },
    ],
    [
      "a version nobody wrote and no migration did",
      { ...ENTRY, versions: [{ ...DRAFT, writers: [] }] },
    ],
    [
      "a writer with no user number",
      {
        ...ENTRY,
        versions: [{ ...DRAFT, writers: [{ displayName: "Ada" }] }],
      },
    ],
    [
      "a writer whose name is no text",
      {
        ...ENTRY,
        versions: [{ ...DRAFT, writers: [{ ...ADA, displayName: 7 }] }],
      },
    ],
    [
      "an approval that is none",
      { ...ENTRY, versions: [{ ...SEEDED, approval: "nobody" }] },
    ],
    [
      "an approver who is nobody",
      { ...ENTRY, versions: [{ ...IN_SERVICE, approval: { approver: {} } }] },
    ],
    [
      "a pin with no number",
      {
        ...ENTRY,
        versions: [
          {
            ...IN_SERVICE,
            pinnedBy: [{ ...IN_SERVICE.pinnedBy[0], number: null }],
          },
        ],
      },
    ],
    [
      "pins that are no list",
      { ...ENTRY, versions: [{ ...IN_SERVICE, pinnedBy: {} }] },
    ],
  ])("refuses an entry with %s", (_case, body) => {
    expect(entryFrom(body)).toBeNull();
  });
});
