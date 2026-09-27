import { ThemeProvider } from "@mui/material/styles";
import { render, screen, waitFor, within } from "@testing-library/react";
import { userEvent } from "@testing-library/user-event";
import { RouterProvider, createMemoryRouter } from "react-router";
import { describe, expect, it, vi } from "vitest";

import { StandingProvider } from "../../app/standing/StandingContext";
import { theme } from "../../lib/theme/theme";
import { requestsTo, serving, type Reply } from "../../testutil/answering";
import { laidOutAt } from "../../testutil/layout";
import { answered, inGroup } from "../../testutil/standingRead";
import { JUST_STARTED } from "./entryAddress";
import { EntryPage } from "./EntryPage";
import { LIBRARY_KINDS } from "./libraryKinds";

// Renaming's rule moved to one it never takes, which the renaming case below reads back.
vi.mock("./actRules", async (importOriginal) => {
  const actual = await importOriginal<typeof import("./actRules")>();
  return {
    ...actual,
    ENTRY_ACT_RULES: { ...actual.ENTRY_ACT_RULES, rename: "approve_entry" },
  };
});

const GROUP = "00000003-0000-4000-8000-000000000b51";

const ENTRY_ID = "00000006-0000-4000-8000-000000000b51";

const PAGE = `/groups/${GROUP}/questions/${ENTRY_ID}`;

const ENTRY = `/api/groups/${GROUP}/questions/${ENTRY_ID}`;

const ADA = { userId: "000b51", displayName: "Ada Lovelace" };

const GRACE = { userId: "000b52", displayName: "Grace Hopper" };

const HANDLE = "00000006-0000-4000-8000-000000000b59";

const HANDLING = "00000007-0000-4000-8000-000000000b59";

const IN_SERVICE = {
  versionId: "00000007-0000-4000-8000-000000000b52",
  number: 2,
  standing: "in_service",
  writers: [ADA],
  writtenByMigration: false,
  approval: { approver: GRACE },
  acts: ["retire"],
  pinnedBy: [
    {
      entryId: HANDLE,
      kind: "workflow",
      name: "Handle",
      versionId: HANDLING,
      number: 4,
    },
  ],
};

const SEEDED = {
  versionId: "00000007-0000-4000-8000-000000000b51",
  number: 1,
  standing: "in_service",
  writers: [],
  writtenByMigration: true,
  approval: {},
  acts: [],
  pinnedBy: [],
};

const DRAFT = {
  versionId: "00000007-0000-4000-8000-000000000b53",
  number: 3,
  standing: "draft",
  writers: [GRACE],
  writtenByMigration: false,
  acts: ["write", "submit"],
  pinnedBy: [],
};

const SUBMITTED = {
  ...DRAFT,
  standing: "submitted",
  acts: ["withdraw", "approve"],
};

const TRIAGE = {
  entryId: ENTRY_ID,
  kind: "question",
  name: "Triage",
  purpose: "Sorts claims.",
  acts: ["start_draft", "rename", "stop"],
  versions: [IN_SERVICE, SEEDED],
};

/** Grace's draft of Triage, with the two in service beneath it. */
const DRAFTED = {
  ...TRIAGE,
  acts: ["rename", "stop"],
  versions: [DRAFT, IN_SERVICE, SEEDED],
};

function reply(entry: object, status = 200): Reply {
  return [JSON.stringify(entry), status];
}

/** What every version of Triage holds, which its kind's editor reads as it is opened. */
const QUESTION = {
  revision: 1,
  instruction: "Say which category the claim falls under.",
  takes: [],
  gives: [],
  lists: [],
};

/** A draft holding two fields of one name inside another, and a field of fields holding none. */
const HELD_QUESTION = {
  ...QUESTION,
  takes: [
    {
      fieldId: "0000000b-0000-4000-8000-000000000b7f",
      name: "sender",
      kind: "fields",
      many: false,
      mustBeGiven: true,
      fields: [],
    },
  ],
  gives: [
    {
      fieldId: "0000000b-0000-4000-8000-000000000b70",
      name: "details",
      kind: "fields",
      many: false,
      mustBeGiven: true,
      stands: "always",
      fields: [
        {
          fieldId: "0000000b-0000-4000-8000-000000000b71",
          name: "product",
          kind: "text",
          many: false,
          mustBeGiven: true,
        },
        {
          fieldId: "0000000b-0000-4000-8000-000000000b72",
          name: "product",
          kind: "text",
          longest: 64,
          many: false,
          mustBeGiven: false,
        },
      ],
    },
  ],
};

const CONTENT_READS = Object.fromEntries(
  [IN_SERVICE, SEEDED, DRAFT].map((version) => [
    `GET ${ENTRY}/versions/${version.versionId}`,
    [reply(QUESTION)],
  ]),
);

/** What the server says when it refuses. */
function refusal(code: string, status: number, more: object = {}): Reply {
  return [JSON.stringify({ code, ...more }), status];
}

/**
 * The page at an address, under a real router whose location the test reads,
 * with the standing it would be handed — whose reading again the test counts —
 * and only the server stood in for.
 */
function opening(
  address: string,
  routes: Readonly<Record<string, readonly Reply[]>> = {},
  state?: unknown,
  of: keyof typeof LIBRARY_KINDS = "question",
) {
  const sent = serving({
    [`GET ${ENTRY}`]: [reply(TRIAGE)],
    ...CONTENT_READS,
    ...routes,
  });
  const { pathname, search } = new URL(address, "http://reader.test");
  const router = createMemoryRouter(
    [
      {
        path: "/groups/:groupId/:segment/:entryId",
        element: <EntryPage of={LIBRARY_KINDS[of]} />,
      },
    ],
    { initialEntries: [{ pathname, search, state }] },
  );
  const readAgain = vi.fn();
  const standing = answered(
    [],
    [inGroup(GROUP, "CLAIMS", "Claims", ["read_membership"])],
  );
  render(
    <ThemeProvider theme={theme}>
      <StandingProvider read={{ ...standing, reload: readAgain }}>
        <RouterProvider router={router} />
      </StandingProvider>
    </ThemeProvider>,
  );
  return {
    sent,
    readAgain,
    at: () =>
      `${router.state.location.pathname}${router.state.location.search}`,
  };
}

async function opened(): Promise<HTMLElement> {
  return screen.findByRole("heading", { level: 1, name: /Triage/ });
}

function versions(): HTMLElement {
  return screen.getByRole("region", { name: "Versions" });
}

function acts(): HTMLElement {
  return screen.getByRole("region", { name: "What can be done" });
}

function theVersion(number: number): HTMLElement {
  return screen.getByRole("region", { name: `Version ${number}` });
}

function actsDrawn(): string[] {
  return within(acts())
    .queryAllByRole("button")
    .map((each) => each.textContent ?? "");
}

/** Every version row's words, newest first. */
function versionRows(): string[] {
  return within(within(versions()).getByRole("list", { name: "Versions" }))
    .getAllByRole("link")
    .map((each) => each.textContent ?? "");
}

function picked(): string {
  return (
    within(versions())
      .getAllByRole("link")
      .find((each) => each.getAttribute("aria-current") === "true")
      ?.textContent ?? ""
  );
}

function entryReads(sent: ReturnType<typeof serving>): number {
  return requestsTo(sent).filter((each) => each === `GET ${ENTRY}`).length;
}

function setApart(words: string): string {
  return `${String.fromCodePoint(0x2068)}${words}${String.fromCodePoint(0x2069)}`;
}

describe("EntryPage", () => {
  it("heads the page with the entry's name, what it is for, its kind and whether it is stopped", async () => {
    opening(PAGE);

    expect(await opened()).toHaveTextContent("Triage");
    expect(screen.getAllByRole("term").map((term) => term.textContent)).toEqual(
      ["What it is for", "Kind", "Stopped"],
    );
    expect(
      screen.getAllByRole("definition").map((each) => each.textContent),
    ).toEqual(["Sorts claims.", "Question", "No. It may be run."]);
  });

  it("says who stopped a stopped entry, and when, and that it says nothing of what it is for", async () => {
    const { purpose: _said, ...unsaid } = TRIAGE;
    opening(PAGE, {
      [`GET ${ENTRY}`]: [
        reply({
          ...unsaid,
          stopped: { by: ADA, at: "2026-09-24T08:15:00Z" },
          acts: ["let_go"],
        }),
      ],
    });
    await opened();

    const [purpose, , stopped] = screen
      .getAllByRole("definition")
      .map((each) => each.textContent ?? "");
    expect(purpose).toBe("It says nothing of what it is for.");
    expect(stopped).toMatch(
      new RegExp(
        `^Yes, by ${setApart("Ada Lovelace")}, .+\\. Nothing runs it until it is let go\\.$`,
      ),
    );
    expect(
      screen.getByRole("button", { name: "Let it be used again" }),
    ).toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: "Stop it being used" }),
    ).toBeNull();
  });

  it.each([
    [
      "every act on the entry",
      ["start_draft", "rename", "stop"],
      ["Stop it being used", "Rename", "New draft"],
    ],
    ["none of them", [], []],
  ])(
    "draws the controls on the entry the server says the reader may use: %s",
    async (_case, entryActs, drawn) => {
      opening(PAGE, {
        [`GET ${ENTRY}`]: [reply({ ...TRIAGE, acts: entryActs })],
      });
      await opened();

      expect(
        [
          "Stop it being used",
          "Let it be used again",
          "Rename",
          "New draft",
        ].filter((name) => screen.queryByRole("button", { name }) !== null),
      ).toEqual(drawn);
    },
  );

  /** A version a migration put in service says nobody approved it, never as though somebody had. */
  it("lists the versions newest first, each with its standing, who wrote it and who approved it, the newest open", async () => {
    opening(PAGE);
    await opened();

    expect(versionRows()).toEqual([
      `Version 2In service · Written by ${setApart("Ada Lovelace")} · Approved by ${setApart("Grace Hopper")}`,
      "Version 1In service · A migration wrote it. Nobody here did. · A migration put it into service. Nobody approved it.",
    ]);
    expect(picked()).toMatch(/^Version 2/);
  });

  it("opens the version the address names, and the newest where it names one the entry does not have", async () => {
    opening(`${PAGE}?version=${SEEDED.versionId}`);
    await opened();

    expect(picked()).toMatch(/^Version 1/);
    expect(theVersion(1)).toBeInTheDocument();
    expect(actsDrawn()).toEqual([]);
  });

  it("links each version to the address that opens it", async () => {
    const { at } = opening(PAGE);
    await opened();

    await userEvent.click(
      within(versions()).getByRole("link", { name: /^Version 1/ }),
    );

    expect(at()).toBe(`${PAGE}?version=${SEEDED.versionId}`);
    expect(picked()).toMatch(/^Version 1/);
  });

  it("says of the open version how many in service pin it, links each, and draws what it holds", async () => {
    const { sent } = opening(PAGE);
    await opened();

    expect(actsDrawn()).toEqual(["Retire"]);
    expect(theVersion(2)).toHaveTextContent("One version in service pins it.");
    const pinners = within(theVersion(2)).getByRole("list", {
      name: "One version in service pins it.",
    });
    expect(
      within(pinners)
        .getAllByRole("link")
        .map((link) => [link.textContent, link.getAttribute("href")]),
    ).toEqual([
      [
        `${setApart("Handle")} (Workflow), version 4`,
        `/groups/${GROUP}/workflows/${HANDLE}?version=${HANDLING}`,
      ],
    ]);
    expect(
      await within(theVersion(2)).findByText(
        "Say which category the claim falls under.",
      ),
    ).toBeInTheDocument();
    expect(requestsTo(sent)).toContain(
      `GET ${ENTRY}/versions/${IN_SERVICE.versionId}`,
    );
    expect(theVersion(2)).not.toHaveTextContent("Waiting on");
  });

  it("says of a submitted version that it waits on anybody who may approve an entry here", async () => {
    opening(PAGE, {
      [`GET ${ENTRY}`]: [reply({ ...DRAFTED, versions: [SUBMITTED, SEEDED] })],
    });
    await opened();

    expect(theVersion(3)).toHaveTextContent(
      "Waiting on anybody who may approve an entry here.",
    );
    expect(theVersion(3)).toHaveTextContent("Nothing in service pins it.");
    expect(within(theVersion(3)).queryByRole("list")).toBeNull();
  });

  /** Each act's request and the rule its refusal is said as, the whole table pressed. */
  it.each([
    [
      "Submit",
      DRAFTED,
      "PUT",
      "submission",
      DRAFT,
      "started, written and submitted",
    ],
    [
      "Withdraw",
      { ...DRAFTED, versions: [SUBMITTED, SEEDED] },
      "DELETE",
      "submission",
      SUBMITTED,
      "started, written and submitted",
    ],
    [
      "Approve",
      { ...DRAFTED, versions: [SUBMITTED, SEEDED] },
      "PUT",
      "approval",
      SUBMITTED,
      "put into service, and a run's raised ceiling approved or refused,",
    ],
    [
      "Retire",
      TRIAGE,
      "PUT",
      "retirement",
      IN_SERVICE,
      "retired, stopped and let go",
    ],
  ])(
    "asks %s of the open version, and says a refusal of the act as the rule it asks",
    async (control, entry, method, part, version, rule) => {
      const address = `${ENTRY}/versions/${version.versionId}/${part}`;
      const { sent, readAgain } = opening(PAGE, {
        [`GET ${ENTRY}`]: [reply(entry)],
        [`${method} ${address}`]: [refusal("ACT_NOT_PERMITTED", 403)],
      });
      await opened();

      await userEvent.click(
        within(acts()).getByRole("button", { name: control }),
      );

      expect(await within(acts()).findByRole("alert")).toHaveTextContent(
        `A group's entries are ${rule} only by a role in it that may`,
      );
      expect(requestsTo(sent)).toContain(`${method} ${address}`);
      expect(readAgain).toHaveBeenCalledTimes(1);
      await waitFor(() => expect(entryReads(sent)).toBe(2));
    },
  );

  it.each([
    [
      "Stop it being used",
      "PUT",
      "/stop",
      "retired, stopped and let go",
      TRIAGE,
    ],
    [
      "Let it be used again",
      "DELETE",
      "/stop",
      "retired, stopped and let go",
      {
        ...TRIAGE,
        stopped: { by: ADA, at: "2026-09-24T08:15:00Z" },
        acts: ["let_go"],
      },
    ],
    [
      "New draft",
      "POST",
      "/versions",
      "started, written and submitted",
      TRIAGE,
    ],
  ])(
    "asks %s of the entry, and says a refusal of the act as the rule it asks",
    async (control, method, path, rule, entry) => {
      const { sent } = opening(PAGE, {
        [`GET ${ENTRY}`]: [reply(entry)],
        [`${method} ${ENTRY}${path}`]: [refusal("ACT_NOT_PERMITTED", 403)],
      });
      await opened();

      await userEvent.click(screen.getByRole("button", { name: control }));

      expect(await screen.findByRole("alert")).toHaveTextContent(
        `A group's entries are ${rule} only by a role in it that may`,
      );
      expect(requestsTo(sent)).toContain(`${method} ${ENTRY}${path}`);
    },
  );

  /** The table is stood in for with a rule renaming takes nowhere, so only the table's word can reach the page. */
  it("renames the entry as the one change of its name and what it is for, and says a refusal as the table's rule for renaming", async () => {
    const { sent } = opening(PAGE, {
      [`PATCH ${ENTRY}`]: [refusal("ACT_NOT_PERMITTED", 403)],
    });
    await opened();

    await userEvent.click(screen.getByRole("button", { name: "Rename" }));
    const dialog = screen.getByRole("dialog", { name: "Rename this entry" });
    await userEvent.clear(
      within(dialog).getByRole("textbox", { name: "Name" }),
    );
    await userEvent.type(
      within(dialog).getByRole("textbox", { name: "Name" }),
      "Intake",
    );
    await userEvent.click(
      within(dialog).getByRole("button", { name: "Rename" }),
    );

    expect(await within(dialog).findByRole("alert")).toHaveTextContent(
      "A group's entries are put into service, and a run's raised ceiling approved or refused, only by a role in it that may approve an entry.",
    );
    const renamed = sent.mock.calls.find(
      ([, init]) => init?.method === "PATCH",
    );
    expect(JSON.parse(String(renamed![1]?.body))).toEqual({
      name: "Intake",
      purpose: "Sorts claims.",
    });
  });

  /** No line is written after an act: the page is drawn again as the change answered. */
  it("stops the entry, showing it as the change answered without reading it again or saying anything more", async () => {
    const { sent } = opening(PAGE, {
      [`PUT ${ENTRY}/stop`]: [
        reply({
          ...TRIAGE,
          stopped: { by: ADA, at: "2026-09-24T08:15:00Z" },
          acts: ["rename", "let_go"],
        }),
      ],
    });
    await opened();
    const control = screen.getByRole("button", { name: "Stop it being used" });

    await userEvent.click(control);

    expect(
      await screen.findByRole("button", { name: "Let it be used again" }),
    ).toBe(control);
    expect(requestsTo(sent)).toEqual([
      `GET ${ENTRY}`,
      `GET ${ENTRY}/versions/${IN_SERVICE.versionId}`,
      `PUT ${ENTRY}/stop`,
    ]);
    expect(screen.queryByRole("alert")).toBeNull();
    expect(
      screen
        .queryAllByRole("status")
        .filter((each) => !theVersion(2).contains(each))
        .map((each) => each.textContent),
    ).toEqual([""]);
    expect(
      within(theVersion(2))
        .queryAllByRole("status")
        .map((each) => each.textContent),
    ).toEqual([""]);
  });

  /** The control pressed goes with the act it offered, so the keyboard is kept in the part that asked. */
  it("opens a draft just started, the newest, keeping the keyboard in the versions", async () => {
    const { at } = opening(`${PAGE}?version=${SEEDED.versionId}`, {
      [`POST ${ENTRY}/versions`]: [reply(DRAFTED, 201)],
    });
    await opened();

    await userEvent.click(screen.getByRole("button", { name: "New draft" }));

    await waitFor(() => expect(picked()).toMatch(/^Version 3/));
    expect(at()).toBe(PAGE);
    expect(actsDrawn()).toEqual(["Submit"]);
    expect(screen.queryByRole("button", { name: "New draft" })).toBeNull();
    expect(document.activeElement).toBe(
      screen.getByRole("heading", { name: "Versions" }),
    );
  });

  it("keeps the keyboard in What can be done where the act pressed is no longer offered", async () => {
    opening(PAGE, {
      [`GET ${ENTRY}`]: [reply(DRAFTED)],
      [`PUT ${ENTRY}/versions/${DRAFT.versionId}/submission`]: [
        reply({ ...DRAFTED, versions: [SUBMITTED, IN_SERVICE, SEEDED] }),
      ],
    });
    await opened();

    await userEvent.click(
      within(acts()).getByRole("button", { name: "Submit" }),
    );

    await waitFor(() => expect(actsDrawn()).toEqual(["Withdraw", "Approve"]));
    expect(document.activeElement).toBe(
      screen.getByRole("heading", { name: "What can be done" }),
    );
  });

  /** What moved under the page is read again, so no control is left offering what was just refused. */
  it("says a refusal for what moved beside the control that asked, and reads the entry again", async () => {
    const { sent, readAgain } = opening(PAGE, {
      [`GET ${ENTRY}`]: [
        reply(TRIAGE),
        reply({ ...TRIAGE, versions: [{ ...IN_SERVICE, acts: [] }, SEEDED] }),
      ],
      [`PUT ${ENTRY}/versions/${IN_SERVICE.versionId}/retirement`]: [
        refusal("VERSION_STANDING_REFUSES", 409),
      ],
    });
    await opened();

    await userEvent.click(
      within(acts()).getByRole("button", { name: "Retire" }),
    );

    expect(await within(acts()).findByRole("alert")).toHaveTextContent(
      "That version's standing does not admit this.",
    );
    await waitFor(() => expect(entryReads(sent)).toBe(2));
    await waitFor(() => expect(actsDrawn()).toEqual([]));
    expect(readAgain).not.toHaveBeenCalled();
  });

  /** Read again, the entry no longer offers the act refused, so the control pressed is gone with it. */
  it("keeps the keyboard in What can be done where a refused act is read again out of being offered", async () => {
    opening(PAGE, {
      [`GET ${ENTRY}`]: [
        reply(TRIAGE),
        reply({ ...TRIAGE, versions: [{ ...IN_SERVICE, acts: [] }, SEEDED] }),
      ],
      [`PUT ${ENTRY}/versions/${IN_SERVICE.versionId}/retirement`]: [
        refusal("VERSION_STANDING_REFUSES", 409),
      ],
    });
    await opened();

    await userEvent.click(
      within(acts()).getByRole("button", { name: "Retire" }),
    );

    await waitFor(() => expect(actsDrawn()).toEqual([]));
    expect(document.activeElement).toBe(
      screen.getByRole("heading", { name: "What can be done" }),
    );
  });

  it.each([
    [
      "a draft, which taking the version in service changes",
      DRAFTED,
      "submission",
      "Submit",
      "Taking the version in service in place of each retired one is a change to this draft.",
    ],
    [
      "a submitted version, which has to be withdrawn first",
      { ...DRAFTED, versions: [SUBMITTED, IN_SERVICE, SEEDED] },
      "approval",
      "Approve",
      "A submitted version cannot be changed: withdraw it first, then take the version in service in place of each retired one.",
    ],
  ])(
    "names each pin retired since beside What can be done, with what replaces it, on %s",
    async (_case, entry, part, control, way) => {
      const { sent } = opening(PAGE, {
        [`GET ${ENTRY}`]: [reply(entry)],
        [`PUT ${ENTRY}/versions/${DRAFT.versionId}/${part}`]: [
          refusal("VERSION_PINS_RETIRED", 409, {
            pins: [
              {
                entryId: "00000006-0000-4000-8000-000000000b61",
                kind: "reference_list",
                name: "Regions",
                pinned: {
                  versionId: "00000007-0000-4000-8000-000000000b61",
                  number: 1,
                },
                newestInService: {
                  versionId: "00000007-0000-4000-8000-000000000b62",
                  number: 3,
                },
              },
              {
                entryId: "00000006-0000-4000-8000-000000000b63",
                kind: "question",
                name: "Sort",
                pinned: {
                  versionId: "00000007-0000-4000-8000-000000000b63",
                  number: 2,
                },
              },
            ],
          }),
        ],
      });
      await opened();

      await userEvent.click(
        within(acts()).getByRole("button", { name: control }),
      );

      const pins = await within(acts()).findByRole("list", {
        name: "Pinned, and retired since",
      });
      expect(
        within(pins)
          .getAllByRole("listitem")
          .map((each) => each.textContent),
      ).toEqual([
        `${setApart("Regions")} (Reference list), version 1, is retired. Version 3 is in service. Open ${setApart("Regions")}, version 3`,
        `${setApart("Sort")} (Question), version 2, is retired. None of it is in service.`,
      ]);
      expect(within(pins).getByRole("link").getAttribute("href")).toBe(
        `/groups/${GROUP}/reference-lists/00000006-0000-4000-8000-000000000b61?version=00000007-0000-4000-8000-000000000b62`,
      );
      expect(acts()).toHaveTextContent(way);
      expect(within(acts()).getByRole("alert")).toHaveTextContent(
        "This version pins a version retired since.",
      );
      expect(entryReads(sent)).toBe(1);
    },
  );

  /**
   * Each place in the order the server named it: the part, and a field found by its key in the version as the
   * page read it, named down to it, with its place where a fellow shares its name.
   */
  it("names every place a submission is refused for beside What can be done, each where it is", async () => {
    const { sent } = opening(PAGE, {
      [`GET ${ENTRY}`]: [reply(DRAFTED)],
      [`GET ${ENTRY}/versions/${DRAFT.versionId}`]: [reply(HELD_QUESTION)],
      [`PUT ${ENTRY}/versions/${DRAFT.versionId}/submission`]: [
        refusal("VERSION_CONTENT_DOES_NOT_HOLD", 409, {
          problems: [
            { code: "instruction_missing", part: "instruction" },
            {
              code: "longest_missing",
              part: "gives",
              fieldId: "0000000b-0000-4000-8000-000000000b71",
            },
            {
              code: "name_repeated",
              part: "gives",
              fieldId: "0000000b-0000-4000-8000-000000000b72",
            },
            {
              code: "no_fields_held",
              part: "takes",
              fieldId: "0000000b-0000-4000-8000-000000000b7f",
            },
            {
              code: "list_missing",
              part: "gives",
              fieldId: "0000000b-0000-4000-8000-000000000b7e",
            },
            { code: "a_problem_not_worded", part: "a_part_not_worded" },
            { code: "asking_past_largest", part: "asking", excess: 1234567 },
            { code: "asking_past_largest", part: "asking", excess: 1 },
            {
              code: "asking_past_largest",
              part: "asking",
              excess: Number.MAX_SAFE_INTEGER,
            },
            {
              code: "asking_past_largest",
              part: "asking",
              excess: Number.MAX_SAFE_INTEGER + 1,
            },
            { code: "asking_past_largest", part: "asking" },
          ],
        }),
      ],
    });
    await opened();
    await within(theVersion(3)).findByRole("region", {
      name: "The declared answer",
    });

    await userEvent.click(
      within(acts()).getByRole("button", { name: "Submit" }),
    );

    const places = await within(acts()).findByRole("list", {
      name: "What does not hold yet",
    });
    expect(
      within(places)
        .getAllByRole("listitem")
        .map((each) => each.textContent),
    ).toEqual([
      "The instruction: It tells whoever answers it nothing yet.",
      `The declared answer, ${setApart("details")}.${setApart("product (field 1 there)")}: A field of text says how long its value may be, and this one does not yet.`,
      `The declared answer, ${setApart("details")}.${setApart("product (field 2 there)")}: A field beside it already has this name.`,
      `What it takes, ${setApart("sender")}: A field holding fields declares at least one, and this one declares none yet.`,
      "The declared answer: A field of terms names the list they come from, and this one does not yet.",
      "a_part_not_worded: Something here does not hold.",
      "One asking of it: It could send 1,234,567 characters more than 8,388,608, the most one asking may send.",
      "One asking of it: It could send 1 character more than 8,388,608, the most one asking may send.",
      "One asking of it: It could send 9,007,199,254,740,991 characters more than 8,388,608, the most one asking may send.",
      "One asking of it: It could send more than 9,007,199,254,740,991 characters past 8,388,608, the most one asking may send.",
      "One asking of it: Something here does not hold.",
    ]);
    expect(within(acts()).getByRole("alert")).toHaveTextContent(
      "Some of what this version holds does not hold yet. Each place is named.",
    );
    expect(
      within(acts()).queryByRole("list", { name: "Pinned, and retired since" }),
    ).toBeNull();
    expect(entryReads(sent)).toBe(1);
  });

  /** A workflow's content lists what submitting would refuse already, so a refusal's places are not listed twice. */
  it("names every place a workflow's submission is refused for once, where its content lists them", async () => {
    const workflows = `/api/groups/${GROUP}/workflows/${ENTRY_ID}`;
    const problems = [{ code: "runs_missing", part: "steps", stepId: "s9" }];
    laidOutAt(1400, 1400);
    opening(
      `/groups/${GROUP}/workflows/${ENTRY_ID}`,
      {
        [`GET ${workflows}`]: [reply({ ...DRAFTED, kind: "workflow" })],
        [`GET ${workflows}/versions/${DRAFT.versionId}`]: [
          reply({
            revision: 1,
            takes: [],
            gives: [],
            steps: [],
            outputs: [],
            keepsOwnCeiling: false,
            raiseNeedsApproval: false,
            mayBeHelped: false,
            problems,
            sendsPast: [],
            offered: {
              lists: [],
              questions: [],
              workflows: [],
              codeSteps: [],
              models: [],
            },
            terms: {},
          }),
        ],
        [`PUT ${workflows}/versions/${DRAFT.versionId}/submission`]: [
          refusal("VERSION_CONTENT_DOES_NOT_HOLD", 409, { problems }),
        ],
      },
      undefined,
      "workflow",
    );
    await opened();
    await within(theVersion(3)).findByRole("list", {
      name: "What does not hold yet",
    });

    await userEvent.click(
      within(acts()).getByRole("button", { name: "Submit" }),
    );

    expect(await within(acts()).findByRole("alert")).toHaveTextContent(
      "Some of what this version holds does not hold yet. Each place is named.",
    );
    expect(
      screen.getAllByRole("list", { name: "What does not hold yet" }),
    ).toHaveLength(1);
    expect(
      within(theVersion(3)).getByRole("list", {
        name: "What does not hold yet",
      }),
    ).toHaveTextContent("The steps: It says nothing yet of what it runs.");
  });

  /** Neither refusal hides the other: the places and the pins retired since are both named. */
  it("names the pins retired since beside the places a submission is refused for", async () => {
    opening(PAGE, {
      [`GET ${ENTRY}`]: [reply(DRAFTED)],
      [`PUT ${ENTRY}/versions/${DRAFT.versionId}/submission`]: [
        refusal("VERSION_CONTENT_DOES_NOT_HOLD", 409, {
          problems: [{ code: "instruction_missing", part: "instruction" }],
          pins: [
            {
              entryId: "00000006-0000-4000-8000-000000000b61",
              kind: "reference_list",
              name: "Regions",
              pinned: {
                versionId: "00000007-0000-4000-8000-000000000b61",
                number: 1,
              },
            },
          ],
        }),
      ],
    });
    await opened();

    await userEvent.click(
      within(acts()).getByRole("button", { name: "Submit" }),
    );

    expect(
      await within(acts()).findByRole("list", {
        name: "What does not hold yet",
      }),
    ).toHaveTextContent(
      "The instruction: It tells whoever answers it nothing yet.",
    );
    expect(
      within(
        within(acts()).getByRole("list", { name: "Pinned, and retired since" }),
      )
        .getAllByRole("listitem")
        .map((each) => each.textContent),
    ).toEqual([
      `${setApart("Regions")} (Reference list), version 1, is retired. None of it is in service.`,
    ]);
  });

  /** A write changes what a refusal named and who wrote the version, so neither is left as it was. */
  it("drops the places a submission was refused for once a part is written, and reads the entry again", async () => {
    const { sent } = opening(PAGE, {
      [`GET ${ENTRY}`]: [reply(DRAFTED)],
      [`PUT ${ENTRY}/versions/${DRAFT.versionId}/submission`]: [
        refusal("VERSION_CONTENT_DOES_NOT_HOLD", 409, {
          problems: [{ code: "instruction_missing", part: "instruction" }],
        }),
      ],
      [`PUT ${ENTRY}/versions/${DRAFT.versionId}/instruction`]: [
        reply({ ...QUESTION, instruction: "Say which." }),
      ],
    });
    await opened();
    await userEvent.click(
      within(acts()).getByRole("button", { name: "Submit" }),
    );
    await within(acts()).findByRole("list", { name: "What does not hold yet" });

    const box = within(theVersion(3)).getByRole("textbox", {
      name: "The instruction",
    });
    await userEvent.clear(box);
    await userEvent.type(box, "Say which.");
    await userEvent.click(
      within(theVersion(3)).getByRole("button", {
        name: "Save the instruction",
      }),
    );

    await waitFor(() =>
      expect(
        within(acts()).queryByRole("list", { name: "What does not hold yet" }),
      ).toBeNull(),
    );
    expect(within(acts()).queryByRole("alert")).toBeNull();
    await waitFor(() => expect(entryReads(sent)).toBe(2));
  });

  it("reads the reader's standing again where the entry is refused for a group they are no longer in", async () => {
    const { readAgain } = opening(PAGE, {
      [`GET ${ENTRY}`]: [refusal("GROUP_NOT_IN_VIEW", 404)],
    });

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "That group is not in view.",
    );
    expect(readAgain).toHaveBeenCalledTimes(1);
  });

  it("answers an entry not in view with that refusal and nothing of an entry", async () => {
    const { readAgain } = opening(PAGE, {
      [`GET ${ENTRY}`]: [refusal("ENTRY_NOT_IN_VIEW", 404)],
    });

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "That entry is not in this group's library.",
    );
    expect(screen.queryByRole("heading", { level: 1 })).toBeNull();
    expect(readAgain).not.toHaveBeenCalled();
  });

  /** Started from a page now left, the control that started it is gone with that page. */
  it("puts the keyboard on the entry's heading where it was just started", async () => {
    opening(PAGE, {}, JUST_STARTED);
    const heading = await opened();

    await waitFor(() => expect(document.activeElement).toBe(heading));
  });

  it("leaves the keyboard alone on an entry opened plainly", async () => {
    opening(PAGE);

    await opened();

    expect(document.activeElement).toBe(document.body);
  });
});
