import { buttonClasses } from "@mui/material/Button";
import { ThemeProvider } from "@mui/material/styles";
import {
  act,
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import { userEvent } from "@testing-library/user-event";
import { RouterProvider, createMemoryRouter } from "react-router";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { RequireGroup } from "../../app/standing/RequireGroup";
import { StandingProvider } from "../../app/standing/StandingContext";
import { theme } from "../../lib/theme/theme";
import { requestsTo, serving, type Reply } from "../../testutil/answering";
import { drawnAtDepth, drawnOf } from "../../testutil/depths";
import { namingGroupsByKeyAlone } from "../../testutil/groupAddresses";
import { regionsSaid } from "../../testutil/heard";
import { libraryLaidOutAt } from "./libraryLayout.testHelper";
import { answered, inGroup } from "../../testutil/standingRead";
import { deferred } from "../../testutil/deferred";
import { styledClassOf, writtenRules } from "../../testutil/writtenRules";
import { JUST_MADE } from "./entryAddress";
import { EntryPage } from "./EntryPage";
import { isolatedInText } from "../../lib/direction/isolated";
import { LIBRARY_KINDS } from "./libraryKinds";

// Editing's rule moved to one it never takes, which the editing case below reads back.
vi.mock("./actRules", async (importOriginal) => {
  const actual = await importOriginal<typeof import("./actRules")>();
  return {
    ...actual,
    ENTRY_ACT_RULES: { ...actual.ENTRY_ACT_RULES, rename: "approve_entry" },
  };
});

const GROUP = "00000003-0000-4000-8000-000000000b51";

const KEY = "CLAIMS";

const ENTRY_ID = "00000006-0000-4000-8000-000000000b51";

const PAGE = `/groups/${KEY}/questions/${ENTRY_ID}`;

const ENTRY = `/api/groups/${GROUP}/questions/${ENTRY_ID}`;

const AVO = { userId: "000b51", displayName: "Avo Lumerace" };

const GLINN = { userId: "000b52", displayName: "Glinn Havven" };

const HANDLE = "00000006-0000-4000-8000-000000000b59";

const HANDLING = "00000007-0000-4000-8000-000000000b59";

const IN_SERVICE = {
  versionName: null,
  versionId: "00000007-0000-4000-8000-000000000b52",
  number: 2,
  standing: "in_service",
  writers: [AVO],
  writtenByMigration: false,
  approval: { approver: GLINN },
  stateActs: ["retire"],
  pinnedBy: [
    {
      versionName: null,
      entryId: HANDLE,
      kind: "workflow",
      name: "Handle",
      versionId: HANDLING,
      number: 4,
    },
  ],
};

const SEEDED = {
  versionName: null,
  versionId: "00000007-0000-4000-8000-000000000b51",
  number: 1,
  standing: "in_service",
  writers: [],
  writtenByMigration: true,
  approval: {},
  stateActs: [],
  pinnedBy: [],
};

const DRAFT = {
  versionName: null,
  versionId: "00000007-0000-4000-8000-000000000b53",
  number: 3,
  standing: "draft",
  writers: [GLINN],
  writtenByMigration: false,
  stateActs: ["write", "submit"],
  pinnedBy: [],
};

const SUBMITTED = {
  ...DRAFT,
  standing: "submitted",
  stateActs: ["withdraw", "approve"],
};

const TRIAGE = {
  entryId: ENTRY_ID,
  kind: "question",
  name: "Triage",
  purpose: "Sorts claims.",
  stateActs: ["start_draft", "rename", "stop"],
  versions: [IN_SERVICE, SEEDED],
};

const DRAFTED = {
  ...TRIAGE,
  stateActs: ["rename", "stop"],
  versions: [DRAFT, IN_SERVICE, SEEDED],
};

function reply(entry: object, status = 200): Reply {
  return [JSON.stringify(entry), status];
}

const QUESTION = {
  revision: 1,
  searchWays: [],
  searchShapeNeedsRefresh: false,
  instruction: "Say which category the claim falls under.",
  takes: [],
  gives: [],
};

const TERM = {
  termId: "0000000a-0000-4000-8000-000000000b81",
  term: "Billing",
  meaning: "Money taken wrongly.",
};

const COMPLAINT = {
  fieldId: "0000000b-0000-4000-8000-000000000b81",
  name: "complaint",
  kind: "text",
  longest: 4000,
  many: false,
  mustBeGiven: true,
};

const SUMMARY = {
  ...COMPLAINT,
  fieldId: "0000000b-0000-4000-8000-000000000b82",
  name: "summary",
};

const READY_SUMMARY = { ...SUMMARY, stands: "always" };

const READY_NOTE = {
  ...READY_SUMMARY,
  fieldId: "0000000b-0000-4000-8000-000000000b83",
  name: "note",
};

const READY_QUESTION = { ...QUESTION, gives: [READY_SUMMARY] };

const ASKING = {
  stepId: "0000000c-0000-4000-8000-000000000b81",
  name: "triage",
  runs: {
    kind: "question",
    version: {
      versionName: null,
      name: "Triage",
      versionId: IN_SERVICE.versionId,
      number: 2,
      standing: "in_service",
    },
    takes: [COMPLAINT],
    gives: [SUMMARY],
  },
  producer: { kind: "person" },
  tries: 2,
  bindings: [
    {
      bindingId: "0000000e-0000-4000-8000-000000000b81",
      target: "complaint",
      source: { input: "complaint" },
    },
  ],
};

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

const WORKFLOW = {
  revision: 1,
  takes: [COMPLAINT],
  gives: [READY_SUMMARY],
  steps: [
    {
      ...ASKING,
      name: "Demo Sample",
      runs: { ...ASKING.runs, gives: [READY_SUMMARY] },
    },
  ],
  outputs: [
    {
      bindingId: "0000000e-0000-4000-8000-000000000b82",
      target: "summary",
      source: { step: 0, path: "summary" },
    },
  ],
  keepsOwnCeiling: false,
  raiseNeedsApproval: false,
  mayBeHelped: false,
  problems: [],
  sendsPast: [],
  offered: {
    codeSteps: [],
    searchWays: [],
    models: [],
  },
  terms: {},
};

const REVIEWING = {
  ...WORKFLOW.steps[0]!,
  stepId: "0000000c-0000-4000-8000-000000000b85",
  name: "review",
  bindings: [],
};

const TWO_STEPS = { ...WORKFLOW, steps: [WORKFLOW.steps[0]!, REVIEWING] };

const CONTENT_READS = Object.fromEntries(
  [IN_SERVICE, SEEDED, DRAFT].map((version) => [
    `GET ${ENTRY}/versions/${version.versionId}`,
    [reply(QUESTION)],
  ]),
);

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
        path: "/groups/:group/:segment/:entryId",
        element: (
          <RequireGroup reachedBy={LIBRARY_KINDS[of].item.reachedBy}>
            <EntryPage of={LIBRARY_KINDS[of]} />
          </RequireGroup>
        ),
      },
    ],
    { initialEntries: [{ pathname, search, state }] },
  );
  namingGroupsByKeyAlone(router, [GROUP]);
  const readAgain = vi.fn();
  const standing = answered(
    [],
    [
      inGroup(GROUP, KEY, "Claims", [
        "read_membership",
        "author_entry",
        "approve_entry",
        "revoke_entry",
      ]),
    ],
    AVO.userId,
  );
  const draw = (current = standing) => (
    <ThemeProvider theme={theme}>
      <StandingProvider read={{ ...current, reload: readAgain }}>
        <RouterProvider router={router} />
      </StandingProvider>
    </ThemeProvider>
  );
  const mounted = render(draw());
  return {
    sent,
    readAgain,
    changeStanding(permissions: readonly string[], userId?: string) {
      mounted.rerender(
        draw(
          answered([], [inGroup(GROUP, KEY, "Claims", permissions)], userId),
        ),
      );
    },
    at: () =>
      `${router.state.location.pathname}${router.state.location.search}`,
    async go(path: string) {
      await act(async () => router.navigate(path));
    },
  };
}

async function opened(): Promise<HTMLElement> {
  return screen.findByRole("heading", { level: 1, name: /Triage/ });
}

function versions(): HTMLElement {
  return screen.getByRole("region", { name: "Versions" });
}

function openVersionRegion(): HTMLElement {
  return screen.getByRole("region", { name: /^Version \d+$/ });
}

/** The open version as drawn beneath a step's drawer: in sight, out of reach. */
function openVersionBeneath(): HTMLElement {
  return screen.getByRole("region", { name: /^Version \d+$/, hidden: true });
}

/** Shuts a step's drawer by its own control, leaving the page in reach. */
async function shutStep(): Promise<void> {
  const drawer = document.querySelector<HTMLElement>(
    '[role="dialog"][aria-modal="true"]',
  )!;
  expect(drawer).toBeVisible();
  await userEvent.click(
    within(drawer).getAllByLabelText("Close", { selector: "button" })[0]!,
  );
  await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
}

async function confirmRetire() {
  await userEvent.click(
    within(openVersionRegion()).getByRole("button", { name: "Retire" }),
  );
  const dialog = screen.getByRole("dialog", { name: "Retire Version 2?" });
  expect(dialog).toHaveTextContent(
    "Nothing new will be offered it, and whatever already names it goes on as it is. It cannot be put back in service.",
  );
  await userEvent.click(within(dialog).getByRole("button", { name: "Retire" }));
}

function versionActionButtons(): HTMLElement[] {
  const heading = within(openVersionRegion()).getByRole("heading", {
    name: /^Version \d+$/,
  });
  const line = heading.parentElement!;
  return line.tagName === "DIV"
    ? within(line)
        .queryAllByRole("button")
        .filter((button) => button.classList.contains(buttonClasses.root))
    : [];
}

function theVersion(number: number): HTMLElement {
  const heading = screen.getByRole("heading", {
    level: 2,
    name: new RegExp(`^Version ${number}(?: \\u00b7 .*)?$`),
  });
  return heading.closest("section")!;
}

function actsDrawn(): string[] {
  return versionActionButtons().map((each) => each.textContent ?? "");
}

function versionRows(): string[][] {
  return within(versions())
    .getAllByRole("link")
    .map((link) => [
      link.firstElementChild!.textContent!,
      link.querySelector("bdi")?.textContent ?? "",
      link.children[1]!.textContent!,
    ]);
}

function details(): HTMLElement {
  return screen.getByRole("heading", { name: "Details" }).closest("section")!;
}

function pinned(number: number): HTMLElement {
  return screen.getByRole("region", { name: `Version ${number} is pinned by` });
}

function editVersionProse(number: number, label: string): void {
  fireEvent.click(
    within(theVersion(number)).getByRole("button", { name: label }),
  );
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
  beforeEach(() => {
    localStorage.removeItem("lilradish.panel.version");
    libraryLaidOutAt(1800, 2000);
  });
  it("keeps the filled kind beside the entry title and the spaced lighter version name before its standing", async () => {
    opening(PAGE, {
      [`GET ${ENTRY}`]: [
        reply({
          ...TRIAGE,
          versions: [{ ...IN_SERVICE, versionName: "Claim review" }],
        }),
      ],
    });
    await opened();
    const title = screen.getByRole("heading", { level: 1, name: /Triage/ });
    const kind = within(title.parentElement!.parentElement!).getByText(
      "Question",
    );
    expect(title.parentElement!.nextElementSibling).toContainElement(kind);
    expect(kind).toHaveTextContent("Question");
    expect(kind).toHaveStyle({
      backgroundColor: theme.palette.kind.question.ground,
      color: theme.palette.kind.question.text,
    });
    expect(getComputedStyle(title.parentElement!).flexGrow).toBe("0");
    const number = within(theVersion(2)).getByRole("heading", {
      level: 2,
      name: "Version 2",
    });
    const name = within(theVersion(2)).getByRole("button", {
      name: "Claim review, editable",
    });
    const standing = within(number.parentElement!).getByText("In service");
    expect(name.parentElement!.textContent).toBe(" \u00b7 Claim review");
    expect(getComputedStyle(name.parentElement!).color).not.toBe(
      getComputedStyle(number).color,
    );
    expect(
      number.compareDocumentPosition(name) & Node.DOCUMENT_POSITION_FOLLOWING,
    ).toBe(Node.DOCUMENT_POSITION_FOLLOWING);
    expect(
      name.compareDocumentPosition(standing) & Node.DOCUMENT_POSITION_FOLLOWING,
    ).toBe(Node.DOCUMENT_POSITION_FOLLOWING);
  });

  it.each([null, "\u0627\u0633\u0645 \u{1f4e6}"])(
    "keeps an unauthorised version name %s read-only without an empty placeholder",
    async (name) => {
      const { sent, changeStanding } = opening(PAGE, {
        [`GET ${ENTRY}`]: [
          reply({
            ...TRIAGE,
            versions: [{ ...IN_SERVICE, versionName: name }],
          }),
        ],
      });
      await opened();
      await act(async () => changeStanding(["read_membership"], AVO.userId));
      const heading = screen.getByRole("heading", {
        level: 2,
        name: name === null ? "Version 2" : `Version 2 \u00b7 ${name}`,
      });
      const region = theVersion(2);
      expect(
        within(region).queryByRole("button", {
          name: /editable|Add a name/,
        }),
      ).toBeNull();
      expect(within(region).queryByRole("textbox")).toBeNull();
      expect(within(region).queryByText("Add a name")).toBeNull();
      if (name !== null)
        expect(within(heading).getByText(name)).toHaveAttribute("dir", "auto");
      expect(
        requestsTo(sent).filter((request) => !request.startsWith("GET ")),
      ).toEqual([]);
    },
  );

  it("isolates an unsaved name when switching versions and writes only the second version's exact name", async () => {
    const first = { ...IN_SERVICE, versionName: "First saved name" };
    const second = { ...SEEDED, versionName: "Second saved name" };
    const raw = "Second revised \u{1f4e6}";
    const renamed = { ...second, versionName: raw };
    const before = { ...TRIAGE, versions: [first, second] };
    const after = { ...before, versions: [first, renamed] };
    const address = `${ENTRY}/versions/${second.versionId}/name`;
    const { sent, go } = opening(PAGE, {
      [`GET ${ENTRY}`]: [reply(before), reply(after)],
      [`PUT ${address}`]: [reply(after)],
    });
    await opened();
    await userEvent.click(
      within(theVersion(2)).getByRole("button", {
        name: "First saved name, editable",
      }),
    );
    fireEvent.change(screen.getByRole("textbox", { name: "Version name" }), {
      target: { value: "First unsaved draft" },
    });
    await go(`${PAGE}?version=${second.versionId}`);
    expect(
      await within(theVersion(1)).findByRole("button", {
        name: "Second saved name, editable",
      }),
    ).toBeVisible();
    expect(screen.queryByRole("textbox", { name: "Version name" })).toBeNull();
    expect(theVersion(1)).not.toHaveTextContent("First unsaved draft");
    await userEvent.click(
      within(theVersion(1)).getByRole("button", {
        name: "Second saved name, editable",
      }),
    );
    const input = screen.getByRole("textbox", { name: "Version name" });
    expect(input).toHaveValue("Second saved name");
    fireEvent.change(input, { target: { value: raw } });
    await userEvent.keyboard("{Enter}");
    expect(
      await within(theVersion(1)).findByRole("button", {
        name: `${raw}, editable`,
      }),
    ).toBeVisible();
    expect(versionRows().map((row) => row.slice(0, 2))).toEqual([
      ["Version 2", "First saved name"],
      ["Version 1", raw],
    ]);
    expect(
      within(theVersion(1)).getByRole("heading", { name: "Version 1" })
        .parentElement,
    ).toHaveTextContent(raw);
    await go(`${PAGE}?version=${first.versionId}`);
    expect(
      await within(theVersion(2)).findByRole("button", {
        name: "First saved name, editable",
      }),
    ).toBeVisible();
    expect(screen.queryByRole("textbox", { name: "Version name" })).toBeNull();
    expect(theVersion(2)).not.toHaveTextContent("First unsaved draft");
    expect(
      requestsTo(sent).filter((request) => !request.startsWith("GET ")),
    ).toEqual([`PUT ${address}`]);
    const writes = sent.mock.calls.filter(([, init]) => init?.method === "PUT");
    expect(writes).toHaveLength(1);
    expect(JSON.parse(String(writes[0]![1]?.body))).toEqual({ name: raw });
  });

  it.each([
    [
      "ordinary",
      IN_SERVICE,
      "In service",
      setApart("Avo Lumerace"),
      "Glinn Havven",
    ],
    ["migration without writers", SEEDED, "In service", "System", "System"],
    [
      "mixed named and nameless writers",
      { ...IN_SERVICE, writers: [AVO, { userId: "000b53" }] },
      "In service",
      `${setApart("Avo Lumerace")}, ${setApart("000b53")}`,
      "Glinn Havven",
    ],
    [
      "unknown archived standing",
      { ...IN_SERVICE, standing: "archived" },
      "archived",
      setApart("Avo Lumerace"),
      "Glinn Havven",
    ],
    [
      "migration with an actual writer",
      { ...SEEDED, writers: [AVO] },
      "In service",
      setApart("Avo Lumerace"),
      "System",
    ],
    [
      "migration with an actual writer and unknown archived standing",
      { ...SEEDED, standing: "archived", writers: [AVO] },
      "archived",
      setApart("Avo Lumerace"),
      "System",
    ],
    [
      "multiple writers",
      { ...IN_SERVICE, standing: "retired", writers: [AVO, GLINN] },
      "Retired",
      `${setApart("Avo Lumerace")}, ${setApart("Glinn Havven")}`,
      "Glinn Havven",
    ],
    [
      "writer without a display name",
      { ...DRAFT, writers: [{ userId: "000b53" }] },
      "Draft",
      setApart("000b53"),
      null,
    ],
    [
      "submitted without approval",
      SUBMITTED,
      "Submitted",
      setApart("Glinn Havven"),
      null,
    ],
  ] as const)(
    "reads the actual fact values for %s",
    async (_case, version, standing, writers, approver) => {
      opening(PAGE, {
        [`GET ${ENTRY}`]: [reply({ ...TRIAGE, versions: [version] })],
      });
      await opened();
      const region = theVersion(version.number);
      const facts = details().querySelector("dl")!;
      const values = Object.fromEntries(
        Array.from(facts.querySelectorAll("dt"), (term) => [
          term.textContent,
          term.nextElementSibling!,
        ]),
      );
      expect(
        Object.values(values).every((value) => value.tagName === "DD"),
      ).toBe(true);
      expect(values["Standing"]!.textContent).toBe(standing);
      expect(values["Written by"]!.textContent).toBe(writers);
      if (approver === null) {
        expect(values["Approved by"]).toBeUndefined();
        expect(within(facts).queryByText("Approved by")).toBeNull();
      } else if (approver === "System") {
        expect(values["Approved by"]!.textContent).toBe(approver);
        expect(values["Approved by"]!.querySelector("bdi")).toBeNull();
      } else {
        expect(values["Approved by"]!.textContent).toBe(approver);
        expect(values["Approved by"]!.querySelector("bdi")).toHaveAttribute(
          "dir",
          "auto",
        );
      }
      if (version.pinnedBy.length === 0)
        expect(pinned(version.number)).toHaveTextContent("Nothing in service");
      else
        expect(
          within(pinned(version.number)).getByRole("link"),
        ).toHaveAccessibleName(`${setApart("Handle")} \u00b7 Version 4`);
      if (version.standing === "submitted") {
        const waiting = within(region).getByText(
          "Waiting on anybody who may approve an entry here.",
        );
        expect(
          within(region).getByText(
            "Waiting on anybody who may approve an entry here.",
          ),
        ).toBe(waiting);
      }
    },
  );

  it.each([
    ["workflow", "workflows"],
    ["question", "questions"],
    ["reference_list", "reference-lists"],
  ] as const)(
    "edits either actual value of a %s independently on blur, preserving the other description",
    async (kind, segment) => {
      const address = `/api/groups/${GROUP}/${segment}/${ENTRY_ID}`;
      const before = { ...TRIAGE, kind, versions: [], stateActs: ["rename"] };
      const renamed = { ...before, name: "Intake" };
      const described = { ...renamed, purpose: "Routes claims." };
      const { sent } = opening(
        `/groups/${KEY}/${segment}/${ENTRY_ID}`,
        {
          [`GET ${address}`]: [reply(before), reply(renamed), reply(described)],
          [`PATCH ${address}`]: [reply(renamed), reply(described)],
        },
        undefined,
        kind,
      );
      await opened();
      await userEvent.click(
        screen.getByRole("button", { name: "Triage, editable" }),
      );
      const name = screen.getByRole("textbox", { name: "Name" });
      expect(name).toHaveFocus();
      expect(screen.getAllByRole("heading", { level: 1 })).toHaveLength(1);
      expect(screen.getByRole("heading", { level: 1 })).not.toContainElement(
        name.closest("form"),
      );
      expect(
        screen.queryByRole("textbox", { name: "What it is for" }),
      ).toBeNull();
      await userEvent.clear(name);
      await userEvent.type(name, "Intake");
      await userEvent.click(screen.getByRole("heading", { name: "Versions" }));
      expect(
        await screen.findByRole("button", { name: "Intake, editable" }),
      ).toBeVisible();
      expect(
        screen.getByRole("button", { name: "Sorts claims., editable" }),
      ).toBeVisible();
      await userEvent.click(
        screen.getByRole("button", { name: "Sorts claims., editable" }),
      );
      const purpose = screen.getByRole("textbox", { name: "What it is for" });
      expect(purpose).toHaveFocus();
      expect(purpose).toHaveAccessibleDescription(
        "One line saying what it does, for whoever picks it. It may be left empty.",
      );
      expect(screen.queryByRole("textbox", { name: "Name" })).toBeNull();
      await userEvent.clear(purpose);
      await userEvent.type(purpose, "Routes claims.");
      await userEvent.click(screen.getByRole("heading", { name: "Versions" }));
      expect(
        await screen.findByRole("button", { name: "Routes claims., editable" }),
      ).toBeVisible();
      expect(
        screen.getByRole("button", { name: "Intake, editable" }),
      ).toBeVisible();
      const changes = sent.mock.calls.filter(
        ([, init]) => init?.method === "PATCH",
      );
      expect(changes).toHaveLength(2);
      expect(changes.map(([, init]) => JSON.parse(String(init?.body)))).toEqual(
        [
          { name: "Intake", purpose: "Sorts claims." },
          { name: "Intake", purpose: "Routes claims." },
        ],
      );
      expect(
        requestsTo(sent).filter((request) => !request.startsWith("GET ")),
      ).toEqual([`PATCH ${address}`, `PATCH ${address}`]);
      expect(screen.queryByRole("dialog")).toBeNull();
    },
  );

  it("removes entry controls on loss of standing and approves only for a known reader who wrote none of the version", async () => {
    const grants = [
      "read_membership",
      "author_entry",
      "approve_entry",
      "revoke_entry",
    ];
    const { sent, changeStanding } = opening(PAGE, {
      [`GET ${ENTRY}`]: [
        reply({ ...DRAFTED, versions: [SUBMITTED, IN_SERVICE, SEEDED] }),
      ],
    });
    await opened();
    expect(screen.getByRole("button", { name: "Approve" })).toBeVisible();
    expect(
      screen.getByRole("button", { name: "Stop it being used" }),
    ).toBeVisible();

    await act(async () => changeStanding(grants, GLINN.userId));
    expect(screen.queryByRole("button", { name: "Approve" })).toBeNull();
    await act(async () => changeStanding(grants));
    expect(screen.queryByRole("button", { name: "Approve" })).toBeNull();
    await act(async () => changeStanding(["read_membership"], AVO.userId));
    for (const name of ["Approve", "Withdraw", "Stop it being used", "Edit"]) {
      expect(screen.queryByRole("button", { name })).toBeNull();
    }
    await act(async () => changeStanding(grants, AVO.userId));
    expect(screen.getByRole("button", { name: "Approve" })).toBeVisible();
    expect(
      screen.getByRole("button", { name: "Stop it being used" }),
    ).toBeVisible();
    expect(entryReads(sent)).toBe(1);
    expect(
      requestsTo(sent).some((request) => !request.startsWith("GET ")),
    ).toBe(false);
  });

  it.each(["empty", "refused"] as const)(
    "keeps workflow version metadata and controls through loading and %s content",
    async (outcome) => {
      libraryLaidOutAt(1400, 1400);
      const address = `/api/groups/${GROUP}/workflows/${ENTRY_ID}`;
      const content = deferred<readonly [string, number]>();
      const { sent } = opening(
        `/groups/${KEY}/workflows/${ENTRY_ID}`,
        {
          [`GET ${address}`]: [reply({ ...DRAFTED, kind: "workflow" })],
          [`GET ${address}/versions/${DRAFT.versionId}`]: [content.promise],
        },
        undefined,
        "workflow",
      );
      await opened();
      const document = openVersionRegion();
      const title = within(document).getByRole("heading", {
        name: "Version 3",
      });
      const submit = within(document).getByRole("button", { name: "Submit" });
      expect(document).toHaveTextContent("Loading…");
      expect(pinned(3)).toHaveTextContent("Nothing in service");
      expect(submit).toHaveAttribute("aria-disabled", "true");
      await act(async () =>
        content.settle(
          await (outcome === "refused"
            ? refusal("ENTRY_GONE", 404)
            : reply({
                ...WORKFLOW,
                takes: [],
                gives: [],
                steps: [],
                outputs: [],
              })),
        ),
      );
      if (outcome === "empty")
        await within(document).findByText("No step yet.");
      else
        await waitFor(() => expect(document).not.toHaveTextContent("Loading…"));
      expect(openVersionRegion()).toBe(document);
      expect(within(document).getByRole("heading", { name: "Version 3" })).toBe(
        title,
      );
      expect(within(document).getByRole("button", { name: "Submit" })).toBe(
        submit,
      );
      expect(pinned(3)).toHaveTextContent("Nothing in service");
      if (outcome === "refused") {
        expect(submit).toHaveAttribute("aria-disabled", "true");
        await act(async () => submit.focus());
        await userEvent.keyboard("{Enter}");
        expect(
          requestsTo(sent).some((request) => request.endsWith("/submission")),
        ).toBe(false);
      } else {
        await waitFor(() =>
          expect(submit).not.toHaveAttribute("aria-disabled", "true"),
        );
      }
      expect(screen.queryByRole("textbox", { name: "Name" })).toBeNull();
    },
  );

  it("keeps one step editor in its drawer over the page, outside the version card, through measured changes, and reports its unsaved draft", async () => {
    localStorage.setItem("lilradish.panel.version", "collapsed");
    const resize = libraryLaidOutAt(1132, 1800);
    const address = `/api/groups/${GROUP}/workflows/${ENTRY_ID}`;
    const { at } = opening(
      `/groups/${KEY}/workflows/${ENTRY_ID}?step=${ASKING.stepId}&keep=yes`,
      {
        [`GET ${address}`]: [reply({ ...DRAFTED, kind: "workflow" })],
        [`GET ${address}/versions/${DRAFT.versionId}`]: [reply(WORKFLOW)],
      },
      undefined,
      "workflow",
    );
    const input = await screen.findByRole("textbox", { name: "Name" });
    const document = openVersionBeneath();
    const drawer = screen.getByRole("dialog", {
      name: setApart("Demo Sample"),
    });
    expect(document).not.toContainElement(drawer);
    expect(drawer).toContainElement(input);
    expect(getComputedStyle(drawer.firstElementChild!).backgroundColor).toBe(
      getComputedStyle(document).backgroundColor,
    );
    expect(screen.getAllByRole("textbox", { name: "Name" })).toHaveLength(1);
    const submit = within(document).getByRole("button", {
      name: "Submit",
      hidden: true,
    });
    await waitFor(() =>
      expect(submit).not.toHaveAttribute("aria-disabled", "true"),
    );
    await userEvent.type(input, " changed");
    expect(submit).toHaveAttribute("aria-disabled", "true");
    expect(at()).toContain("keep=yes");
    for (const width of [1108, 1107, 1131, 1132, 639]) {
      await resize(width);
      expect(
        screen.getByRole("dialog", { name: setApart("Demo Sample changed") }),
      ).toBe(drawer);
      expect(drawer).toContainElement(input);
      expect(input).toHaveFocus();
      expect(
        screen.queryByRole("region", { name: setApart("Demo Sample changed") }),
      ).toBeNull();
    }
    expect(openVersionBeneath()).toBe(document);
    expect(screen.getAllByRole("textbox", { name: "Name" })).toEqual([input]);
    expect(input).toHaveValue("Demo Sample changed");
    expect(submit).toHaveAttribute("aria-disabled", "true");
    expect(at()).toContain("keep=yes");
  });

  it.each([
    ["question", "questions", READY_QUESTION],
    [
      "workflow",
      "workflows",
      {
        revision: 1,
        takes: [COMPLAINT],
        gives: [SUMMARY],
        steps: [
          { ...ASKING, runs: { ...ASKING.runs, gives: [READY_SUMMARY] } },
        ],
        outputs: [
          {
            bindingId: "0000000e-0000-4000-8000-000000000b82",
            target: "summary",
            source: { step: 0, path: "summary" },
          },
        ],
        keepsOwnCeiling: false,
        raiseNeedsApproval: false,
        mayBeHelped: false,
        problems: [],
        sendsPast: [],
        offered: {
          codeSteps: [],
          searchWays: [],
          models: [],
        },
        terms: {},
      },
    ],
    ["reference_list", "reference-lists", { revision: 1, terms: [TERM] }],
  ] as const)(
    "allows a fully read %s draft to be submitted once",
    async (kind, segment, content) => {
      if (kind === "workflow") {
        libraryLaidOutAt(1400, 1400);
      }
      const address = `/api/groups/${GROUP}/${segment}/${ENTRY_ID}`;
      const { sent } = opening(
        `/groups/${KEY}/${segment}/${ENTRY_ID}`,
        {
          [`GET ${address}`]: [reply({ ...DRAFTED, kind })],
          [`GET ${address}/versions/${DRAFT.versionId}`]: [reply(content)],
          [`PUT ${address}/versions/${DRAFT.versionId}/submission`]: [
            reply({
              ...DRAFTED,
              kind,
              versions: [SUBMITTED, IN_SERVICE, SEEDED],
            }),
          ],
        },
        undefined,
        kind,
      );
      await opened();
      const submitControl = within(openVersionRegion()).getByRole("button", {
        name: "Submit",
      });
      await waitFor(() =>
        expect(submitControl).not.toHaveAttribute("aria-disabled", "true"),
      );
      await userEvent.click(submitControl);

      await waitFor(() =>
        expect(
          requestsTo(sent).filter(
            (request) =>
              request ===
              `PUT ${address}/versions/${DRAFT.versionId}/submission`,
          ),
        ).toHaveLength(1),
      );
      expect(
        within(openVersionRegion()).queryByRole("button", { name: "Submit" }),
      ).toBeNull();
    },
  );

  it("holds submission while a part is unsaved or saving, then submits the saved version once", async () => {
    const saving = deferred<readonly [string, number]>();
    const { sent } = opening(PAGE, {
      [`GET ${ENTRY}`]: [reply(DRAFTED)],
      [`GET ${ENTRY}/versions/${DRAFT.versionId}`]: [reply(READY_QUESTION)],
      [`PUT ${ENTRY}/versions/${DRAFT.versionId}/instruction`]: [
        saving.promise,
      ],
      [`PUT ${ENTRY}/versions/${DRAFT.versionId}/submission`]: [
        reply({ ...DRAFTED, versions: [SUBMITTED, IN_SERVICE, SEEDED] }),
      ],
    });
    await opened();
    await within(theVersion(3)).findByRole("button", {
      name: "Edit the instruction",
    });
    editVersionProse(3, "Edit the instruction");
    const instruction = await within(theVersion(3)).findByRole("textbox", {
      name: "The instruction",
    });
    const submitControl = within(openVersionRegion()).getByRole("button", {
      name: "Submit",
    });
    expect(submitControl).not.toHaveAttribute("aria-disabled", "true");

    await act(async () => instruction.focus());
    instruction.textContent += " Further detail.";
    const caret = document.createRange();
    caret.selectNodeContents(instruction);
    caret.collapse(false);
    window.getSelection()!.removeAllRanges();
    window.getSelection()!.addRange(caret);
    fireEvent.input(instruction);
    const locate = within(openVersionRegion()).getByRole("button", {
      name: "1 part unsaved",
    });
    await userEvent.click(locate);
    expect(instruction).toHaveFocus();
    expect(
      requestsTo(sent).some((request) => !request.startsWith("GET ")),
    ).toBe(false);
    expect(submitControl).toHaveAttribute("aria-disabled", "true");
    await act(async () => submitControl.focus());
    await userEvent.keyboard("{Enter}");
    expect(requestsTo(sent)).not.toContain(
      `PUT ${ENTRY}/versions/${DRAFT.versionId}/submission`,
    );

    await userEvent.click(
      within(theVersion(3)).getByRole("button", {
        name: "Save the instruction",
      }),
    );
    expect(submitControl).toHaveAttribute("aria-disabled", "true");
    saving.settle([
      JSON.stringify({
        ...READY_QUESTION,
        revision: 2,
        instruction: " Further detail.",
      }),
      200,
    ]);
    await waitFor(() =>
      expect(submitControl).not.toHaveAttribute("aria-disabled", "true"),
    );
    await userEvent.click(submitControl);
    await waitFor(() =>
      expect(
        requestsTo(sent).filter((request) => request.endsWith("/submission")),
      ).toHaveLength(1),
    );
    expect(
      within(openVersionRegion()).queryByRole("button", { name: "Submit" }),
    ).toBeNull();
  });

  it("holds a reference list's submission through a note edit and its write", async () => {
    const address = `/api/groups/${GROUP}/reference-lists/${ENTRY_ID}`;
    const note = { revision: 1, note: "Pick the narrowest.", terms: [TERM] };
    const saving = deferred<readonly [string, number]>();
    const { sent } = opening(
      `/groups/${KEY}/reference-lists/${ENTRY_ID}`,
      {
        [`GET ${address}`]: [reply({ ...DRAFTED, kind: "reference_list" })],
        [`GET ${address}/versions/${DRAFT.versionId}`]: [reply(note)],
        [`PUT ${address}/versions/${DRAFT.versionId}/note`]: [saving.promise],
        [`PUT ${address}/versions/${DRAFT.versionId}/submission`]: [
          reply({
            ...DRAFTED,
            kind: "reference_list",
            versions: [SUBMITTED, IN_SERVICE, SEEDED],
          }),
        ],
      },
      undefined,
      "reference_list",
    );
    await opened();
    const submit = within(openVersionRegion()).getByRole("button", {
      name: "Submit",
    });
    await waitFor(() =>
      expect(submit).not.toHaveAttribute("aria-disabled", "true"),
    );
    await within(theVersion(3)).findByRole("button", { name: "Edit the note" });
    editVersionProse(3, "Edit the note");
    const box = await screen.findByRole("textbox", { name: "The note" });
    await userEvent.type(box, " Again.");
    expect(submit).toHaveAttribute("aria-disabled", "true");
    await userEvent.click(
      screen.getByRole("button", { name: "Save the note" }),
    );
    expect(submit).toHaveAttribute("aria-disabled", "true");
    expect(requestsTo(sent)).not.toContain(
      `PUT ${address}/versions/${DRAFT.versionId}/submission`,
    );
    saving.settle([
      JSON.stringify({
        ...note,
        revision: 2,
        note: "Pick the narrowest. Again.",
      }),
      200,
    ]);
    await waitFor(() =>
      expect(submit).not.toHaveAttribute("aria-disabled", "true"),
    );
    await userEvent.click(submit);
    await waitFor(() =>
      expect(
        requestsTo(sent).filter((request) => request.endsWith("/submission")),
      ).toHaveLength(1),
    );
  });

  it.each([
    [1400, "expanded"],
    [1199, "collapsed"],
  ] as const)(
    "holds the real page Details entry while its step is open at %s, restoring the device's %s choice",
    async (width, choice) => {
      localStorage.setItem("lilradish.panel.version", choice);
      const { sent } = openingWorkflow(TWO_STEPS, undefined, width);
      const row = await screen.findByRole("link", {
        name: `Open ${isolatedInText("review")}`,
      });
      const fold = screen.getByRole("button", { name: "Details" });
      await userEvent.click(row);
      const step = await screen.findByRole("dialog", {
        name: isolatedInText("review"),
      });
      expect(fold).toHaveAttribute("aria-expanded", "false");
      expect(fold).toBeDisabled();
      fireEvent.click(fold);
      expect(fold).toHaveAttribute("aria-expanded", "false");
      expect(screen.queryByRole("dialog", { name: "Details" })).toBeNull();
      expect(step).toBeVisible();
      expect(localStorage.getItem("lilradish.panel.version")).toBe(choice);
      await shutStep();
      expect(fold).not.toBeDisabled();
      expect(fold).toHaveAttribute(
        "aria-expanded",
        String(choice === "expanded"),
      );
      expect(localStorage.getItem("lilradish.panel.version")).toBe(choice);
      expect(
        requestsTo(sent).every((request) => request.startsWith("GET ")),
      ).toBe(true);
    },
  );

  /** A workflow draft whose help setting is changed and then shut away, its own write and a second read held. */
  async function hiddenSetting() {
    libraryLaidOutAt(1400, 1400);
    const address = `/api/groups/${GROUP}/workflows/${ENTRY_ID}`;
    const contentAddress = `${address}/versions/${DRAFT.versionId}`;
    const saving = deferred<readonly [string, number]>();
    const reading = deferred<readonly [string, number]>();
    const { sent } = opening(
      `/groups/${KEY}/workflows/${ENTRY_ID}`,
      {
        [`GET ${address}`]: [reply({ ...DRAFTED, kind: "workflow" })],
        [`GET ${contentAddress}`]: [reply(WORKFLOW), reading.promise],
        [`PUT ${contentAddress}/help`]: [saving.promise],
      },
      undefined,
      "workflow",
    );
    await opened();
    const settings = await screen.findByRole("button", {
      name: "Run settings",
    });
    await userEvent.click(settings);
    const mayHelp = screen.getByRole("switch", { name: "Runs may be helped" });
    await userEvent.click(mayHelp);
    await userEvent.click(settings);
    return {
      address,
      contentAddress,
      saving,
      reading,
      sent,
      settings,
      mayHelp,
    };
  }

  /** The same, with a step renamed beside it and the setting's own write sent and still out. */
  async function savingHelpBesideAStep() {
    const drafted = await hiddenSetting();
    await userEvent.click(
      within(screen.getByRole("table", { name: "The steps" })).getByRole(
        "link",
        { name: `Open ${isolatedInText(WORKFLOW.steps[0]!.name)}` },
      ),
    );
    const name = within(
      await screen.findByRole("dialog", {
        name: isolatedInText(WORKFLOW.steps[0]!.name),
      }),
    ).getByLabelText("Name", { selector: "input" });
    await userEvent.clear(name);
    await userEvent.paste(`${ASKING.name}_candidate`);
    expect(
      within(name.closest('[role="dialog"]')!).getByRole("button", {
        name: "Save the steps and what fills what it gives back",
      }),
    ).not.toHaveAttribute("aria-disabled", "true");
    await shutStep();
    const version = openVersionRegion();
    const saveSteps = within(version).getByRole("button", {
      name: "Save the steps and what fills what it gives back",
    });
    expect(saveSteps).not.toHaveAttribute("aria-disabled", "true");
    expect(
      within(version).getByRole("button", {
        name: "2 parts unsaved",
      }),
    ).toBeVisible();
    await userEvent.click(drafted.settings);
    await userEvent.click(
      within(version).getByRole("button", {
        name: "Save whether a run may be helped",
      }),
    );
    return { ...drafted, saveSteps, version };
  }

  /** The same, the setting's write refused for somebody saving since and the draft asked for afresh. */
  async function loadingSavedDraft() {
    const drafted = await savingHelpBesideAStep();
    await act(async () =>
      drafted.saving.settle(await refusal("DRAFT_WRITTEN_SINCE_READ", 409)),
    );
    expect(screen.queryByText("Still saving")).toBeNull();
    await userEvent.click(
      await within(drafted.version).findByRole("button", {
        name: "Load the saved draft",
      }),
    );
    return drafted;
  }

  it("locates a hidden workflow setting from the count of parts unsaved, opening it with its value kept and sending nothing", async () => {
    const { address, contentAddress, sent, settings, mayHelp } =
      await hiddenSetting();
    expect(settings).toHaveAttribute("aria-expanded", "false");
    expect(
      screen.queryByRole("switch", { name: "Runs may be helped" }),
    ).toBeNull();
    const locate = within(openVersionRegion()).getByRole("button", {
      name: "1 part unsaved",
    });
    expect(
      screen.queryByRole("button", { name: "1 parts unsaved" }),
    ).toBeNull();
    expect(screen.queryByText("Still saving")).toBeNull();

    await userEvent.click(locate);

    expect(settings).toHaveAttribute("aria-expanded", "true");
    expect(mayHelp).toHaveFocus();
    expect(mayHelp).toBeChecked();
    expect(requestsTo(sent)).toEqual([
      `GET ${address}`,
      `GET ${contentAddress}`,
    ]);
  });

  it("says Still saving once and only for its own write, holding the steps' save beside it", async () => {
    const { saveSteps } = await savingHelpBesideAStep();

    expect(
      within(openVersionRegion()).getByText("Still saving", { exact: true }),
    ).toBeVisible();
    expect(saveSteps).toHaveAttribute("aria-disabled", "true");
    expect(screen.getAllByText("Still saving", { exact: true })).toHaveLength(
      1,
    );
  });

  it("stops saying Still saving once its write is refused, and says nothing of it while the draft is read afresh", async () => {
    const { contentAddress, sent } = await loadingSavedDraft();

    await waitFor(() =>
      expect(
        requestsTo(sent).filter(
          (request) => request === `GET ${contentAddress}`,
        ),
      ).toHaveLength(2),
    );
    expect(screen.queryByText("Still saving")).toBeNull();
    expect(
      requestsTo(sent).filter((request) => request.startsWith("PUT ")),
    ).toEqual([`PUT ${contentAddress}/help`]);
  });

  it("keeps a step renamed, counted alone and free to save, once the saved draft is read afresh", async () => {
    const { reading, saveSteps, version } = await loadingSavedDraft();

    await act(async () =>
      reading.settle(
        await reply({ ...WORKFLOW, revision: 2, mayBeHelped: true }),
      ),
    );

    await waitFor(() =>
      expect(
        within(version).getByRole("button", { name: "1 part unsaved" }),
      ).toBeVisible(),
    );
    expect(saveSteps).not.toHaveAttribute("aria-disabled", "true");
    expect(screen.queryByText("Still saving")).toBeNull();
    await userEvent.click(
      within(version).getByRole("link", {
        name: `Open ${isolatedInText(`${ASKING.name}_candidate`)}`,
      }),
    );
    const drawer = document.querySelector<HTMLElement>(
      '[role="dialog"][aria-modal="true"]',
    )!;
    expect(within(drawer).getByRole("textbox", { name: "Name" })).toHaveValue(
      `${ASKING.name}_candidate`,
    );
  });

  it("counts a route candidate and its parent flow as one unsaved unit alongside a separate setting, in sight beneath the step's drawer", async () => {
    const { sent, help } = await routeCandidateBesideSetting();

    expect(
      within(openVersionBeneath()).getByRole("button", {
        name: "2 parts unsaved",
        hidden: true,
      }),
    ).toBeVisible();
    expect(
      screen.queryByRole("button", { name: "3 parts unsaved" }),
    ).toBeNull();
    expect(screen.queryByText("Still saving")).toBeNull();
    expect(
      requestsTo(sent).every((request) => request.startsWith("GET ")),
    ).toBe(true);
    expect(help).toBeInTheDocument();
    expect(help).toHaveValue("Raw route candidate.");
  });

  it("opens the first part unsaved as the page reads, the steps before the run settings, opening the step changed and putting the keyboard on its first control, sending nothing", async () => {
    const { sent, editor } = await routeCandidateBesideSetting();
    await userEvent.click(
      within(editor.closest('[role="dialog"]')!).getByRole("button", {
        name: "Done",
      }),
    );
    await shutStep();
    const steps = screen.getByRole("table", { name: "The steps" });
    const settings = screen.getByRole("button", { name: "Run settings" });
    expect(
      steps.compareDocumentPosition(settings) &
        Node.DOCUMENT_POSITION_FOLLOWING,
    ).toBeTruthy();
    await act(async () => settings.focus());

    await userEvent.click(
      within(openVersionRegion()).getByRole("button", {
        name: "2 parts unsaved",
      }),
    );

    const drawer = await screen.findByRole("dialog", {
      name: isolatedInText("choose_draft"),
    });
    const name = within(drawer).getByRole("textbox", { name: "Name" });
    await waitFor(() => expect(name).toHaveFocus());
    expect(name).toHaveValue("choose_draft");
    expect(settings).not.toHaveFocus();
    expect(
      screen.getByRole("heading", {
        level: 3,
        name: "The steps",
        hidden: true,
      }),
    ).not.toHaveFocus();
    expect(
      requestsTo(sent).every((request) => request.startsWith("GET ")),
    ).toBe(true);
    const givesHeading = within(drawer).getByText(
      "What every case gives back",
      { selector: "h5" },
    );
    const gives = givesHeading.closest("section")!;
    expect(gives).toHaveAccessibleName("What every case gives back");
    const open = within(gives).getByRole("button", { name: /^Open/ });
    await userEvent.click(open);
    const reopened = document.getElementById(
      open.getAttribute("aria-controls")!,
    )!;
    expect(
      within(reopened).getByLabelText("Help", { selector: "input,textarea" }),
    ).toHaveValue("Raw route candidate.");
  });

  it.each([
    ["first", "summary", "note"],
    ["second", "note", "summary"],
  ] as const)(
    "puts the keyboard, from the count of parts unsaved, on the From of the %s value the workflow gives back, the one changed, sending nothing",
    async (_case, changed, other) => {
      const { sent } = openingWorkflow({
        ...WORKFLOW,
        gives: [READY_SUMMARY, READY_NOTE],
        steps: [
          {
            ...WORKFLOW.steps[0]!,
            runs: { ...ASKING.runs, gives: [READY_SUMMARY, READY_NOTE] },
          },
        ],
        outputs: [],
      });
      const output = await screen.findByRole("region", {
        name: "What fills each value it gives back",
      });
      const fromOf = (name: string) =>
        within(within(output).getByRole("rowgroup", { name })).getByRole(
          "combobox",
          { name: /^From, / },
        );
      await userEvent.click(fromOf(changed));
      await userEvent.click(screen.getByRole("option", { name: /^Step 1: / }));
      await userEvent.click(
        within(
          within(output).getByRole("rowgroup", { name: changed }),
        ).getByRole("combobox", { name: /^Which, / }),
      );
      await userEvent.click(screen.getByRole("option", { name: changed }));
      const locate = within(openVersionRegion()).getByRole("button", {
        name: "1 part unsaved",
      });

      await userEvent.click(locate);

      expect(fromOf(changed)).toHaveFocus();
      expect(fromOf(other)).not.toHaveFocus();
      expect(locate).not.toHaveFocus();
      expect(
        screen.getByRole("heading", { level: 3, name: "The steps" }),
      ).not.toHaveFocus();
      expect(
        requestsTo(sent).every((request) => request.startsWith("GET ")),
      ).toBe(true);
    },
  );

  it.each(["which", "constant"] as const)(
    "locates the actual changed %s control without changing its From or sending a request",
    async (control) => {
      const { sent } = openingWorkflow(
        {
          ...WORKFLOW,
          gives: [READY_SUMMARY],
          steps: [
            {
              ...WORKFLOW.steps[0]!,
              runs: { ...ASKING.runs, gives: [READY_SUMMARY, READY_NOTE] },
              bindings:
                control === "constant"
                  ? [
                      {
                        ...ASKING.bindings[0]!,
                        source: { constant: '"Saved"' },
                      },
                    ]
                  : ASKING.bindings,
            },
          ],
          outputs: WORKFLOW.outputs,
        },
        control === "constant" ? WORKFLOW.steps[0]!.stepId : undefined,
      );
      const owner = await screen.findByRole(
        control === "constant" ? "dialog" : "region",
        {
          name:
            control === "constant"
              ? isolatedInText(WORKFLOW.steps[0]!.name)
              : "What fills each value it gives back",
        },
      );
      const group = within(owner).getByRole("rowgroup", {
        name: control === "constant" ? "complaint" : "summary",
      });
      const changed =
        control === "which"
          ? within(group).getByLabelText(/^Which, /, {
              selector: '[role="combobox"]',
            })
          : within(group).getByLabelText(/^Value, /, {
              selector: "input,textarea",
            });
      if (control === "which") {
        await userEvent.click(changed);
        await userEvent.click(screen.getByRole("option", { name: "note" }));
      } else {
        await userEvent.clear(changed);
        await userEvent.type(changed, '"Changed"');
        await shutStep();
      }
      await userEvent.click(
        within(openVersionRegion()).getByRole("button", {
          name: "1 part unsaved",
        }),
      );
      const located =
        control === "constant"
          ? await screen.findByLabelText("Value, complaint", {
              selector: "input,textarea",
            })
          : changed;
      await waitFor(() => expect(located).toHaveFocus());
      const from =
        control === "constant"
          ? screen.getByLabelText("From, complaint", {
              selector: '[role="combobox"]',
            })
          : within(group).getByLabelText(/^From, /, {
              selector: '[role="combobox"]',
            });
      expect(from).not.toHaveFocus();
      expect(from).toHaveTextContent(
        control === "constant" ? "A constant" : /^Step 1:/,
      );
      expect(screen.queryAllByRole("dialog")).toHaveLength(
        control === "constant" ? 1 : 0,
      );
      expect(
        requestsTo(sent).every((request) => request.startsWith("GET ")),
      ).toBe(true);
    },
  );

  it("locates the changed display name in a step binding's owner without expanding its field panels", async () => {
    const { sent } = openingWorkflow(TWO_STEPS, REVIEWING.stepId);
    const step = await screen.findByRole("dialog", {
      name: isolatedInText("review"),
    });
    const label = within(step).getByLabelText("Display name", {
      selector: "input",
    });
    await userEvent.type(label, "Review claims");
    await shutStep();
    await userEvent.click(
      within(openVersionRegion()).getByRole("button", {
        name: "1 part unsaved",
      }),
    );
    const reopened = await screen.findByRole("dialog", {
      name: isolatedInText("Review claims"),
    });
    await waitFor(() =>
      expect(
        within(reopened).getByLabelText("Display name", { selector: "input" }),
      ).toHaveFocus(),
    );
    expect(
      within(reopened).getByLabelText("Name", { selector: "input" }),
    ).not.toHaveFocus();
    expect(screen.getAllByRole("dialog")).toHaveLength(1);
    expect(
      requestsTo(sent).every((request) => request.startsWith("GET ")),
    ).toBe(true);
  });

  it("opens, from the count of parts unsaved, the step changed among the steps, putting the keyboard on its first control, the other step left shut", async () => {
    const { sent } = openingWorkflow(TWO_STEPS, REVIEWING.stepId);
    const drawn = await screen.findByRole("dialog", {
      name: isolatedInText("review"),
    });
    await userEvent.type(
      within(drawn).getByRole("textbox", { name: "Name" }),
      "_again",
    );
    await shutStep();
    const locate = within(openVersionRegion()).getByRole("button", {
      name: "1 part unsaved",
    });

    await userEvent.click(locate);

    const drawer = await screen.findByRole("dialog", {
      name: isolatedInText("review_again"),
    });
    const name = within(drawer).getByRole("textbox", { name: "Name" });
    await waitFor(() => expect(name).toHaveFocus());
    expect(name).toHaveValue("review_again");
    expect(
      screen.queryByRole("dialog", { name: isolatedInText("Demo Sample") }),
    ).toBeNull();
    expect(
      requestsTo(sent).every((request) => request.startsWith("GET ")),
    ).toBe(true);
  });

  it("puts the keyboard, from the count of parts unsaved, on the steps' heading where a step was only taken out, opening no step", async () => {
    const { sent } = openingWorkflow(TWO_STEPS);
    await userEvent.click(
      await screen.findByRole("link", {
        name: `Open ${isolatedInText("review")}`,
      }),
    );
    await userEvent.click(
      within(
        await screen.findByRole("dialog", { name: isolatedInText("review") }),
      ).getByRole("button", { name: "Remove this step" }),
    );
    await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
    const locate = within(openVersionRegion()).getByRole("button", {
      name: "1 part unsaved",
    });

    await userEvent.click(locate);

    expect(
      screen.getByRole("heading", { level: 3, name: "The steps" }),
    ).toHaveFocus();
    expect(locate).not.toHaveFocus();
    expect(screen.queryByRole("dialog")).toBeNull();
    expect(
      requestsTo(sent).every((request) => request.startsWith("GET ")),
    ).toBe(true);
  });

  function openingWorkflow(content: object, openStep?: string, width = 1400) {
    libraryLaidOutAt(width, width);
    const address = `/api/groups/${GROUP}/workflows/${ENTRY_ID}`;
    return opening(
      `/groups/${KEY}/workflows/${ENTRY_ID}${openStep === undefined ? "" : `?step=${openStep}`}`,
      {
        [`GET ${address}`]: [reply({ ...DRAFTED, kind: "workflow" })],
        [`GET ${address}/versions/${DRAFT.versionId}`]: [reply(content)],
      },
      undefined,
      "workflow",
    );
  }

  async function routeStepDrawer() {
    const step = await waitFor(() => {
      const drawer = document.querySelector<HTMLElement>(
        '[role="dialog"][aria-modal="true"]',
      );
      expect(drawer).toHaveAccessibleName(isolatedInText("choose"));
      return drawer!;
    });
    expect(step).toBeVisible();
    return step;
  }

  function routeFieldsIn(step: HTMLElement) {
    const heading = within(step).getByText("What every case gives back", {
      selector: "h5",
    });
    const fields = heading.closest("section")!;
    expect(fields).toHaveAccessibleName("What every case gives back");
    return fields;
  }

  async function openRouteField(fields: HTMLElement) {
    const open = within(fields).getByRole("button", { name: /^Open/ });
    await userEvent.click(open);
    const editor = document.getElementById(
      open.getAttribute("aria-controls")!,
    )!;
    expect(editor).toBeInTheDocument();
    return editor;
  }

  function routeFieldDrawer(inside: HTMLElement) {
    const field = inside.closest('[role="dialog"]');
    if (!(field instanceof HTMLElement))
      throw new Error("The field's drawer is missing.");
    return field;
  }

  async function closeRouteStep(step: HTMLElement) {
    await userEvent.click(
      within(step).getAllByLabelText("Close", { selector: "button" })[0]!,
    );
    await waitFor(() => expect(step).not.toBeInTheDocument());
    expect(screen.queryByRole("dialog")).toBeNull();
  }

  /** A route step's field changed and not yet done, its step's drawer shut by `shutting` with the change open. */
  async function routeCandidateShut(shutting: "Close" | "Escape") {
    libraryLaidOutAt(1400, 1400);
    const address = `/api/groups/${GROUP}/workflows/${ENTRY_ID}`;
    const routed = {
      ...WORKFLOW,
      steps: [
        {
          ...ASKING,
          name: "choose",
          producer: undefined,
          bindings: [],
          runs: { kind: "route", gives: [READY_SUMMARY], cases: [] },
        },
      ],
    };
    const { sent } = opening(
      `/groups/${KEY}/workflows/${ENTRY_ID}`,
      {
        [`GET ${address}`]: [reply({ ...DRAFTED, kind: "workflow" })],
        [`GET ${address}/versions/${DRAFT.versionId}`]: [reply(routed)],
      },
      undefined,
      "workflow",
    );
    const openStep = await screen.findByRole("link", {
      name: `Open ${isolatedInText("choose")}`,
    });
    const version = openVersionRegion();
    await userEvent.click(openStep);
    const step = await routeStepDrawer();
    const editor = await openRouteField(routeFieldsIn(step));
    const field = routeFieldDrawer(editor);
    await userEvent.click(
      within(editor).getByLabelText("Help", { selector: "input,textarea" }),
    );
    await userEvent.paste("Raw route candidate.");
    expect(
      within(field).queryByRole("button", { name: "Done" }),
    ).not.toBeNull();
    if (shutting === "Close") await closeRouteStep(step);
    else {
      await userEvent.keyboard("{Escape}");
      await waitFor(() => expect(field).not.toBeInTheDocument());
      expect(screen.getAllByRole("dialog")).toHaveLength(1);
      await userEvent.keyboard("{Escape}");
      await waitFor(() => expect(step).not.toBeInTheDocument());
      expect(screen.queryByRole("dialog")).toBeNull();
    }
    return { sent, version, openStep };
  }

  it.each([
    ["Close", "open on it"],
    ["Escape", "shut, its row marked unsaved"],
  ] as const)(
    "keeps a route field's change not yet done when its step's drawer is shut by %s, counts it unsaved, and opens the step again with the field %s as it was left",
    async (shutting, _left) => {
      const { sent, version, openStep } = await routeCandidateShut(shutting);

      expect(
        within(version).getByRole("button", {
          name: "1 part unsaved",
        }),
      ).toBeVisible();
      await userEvent.click(openStep);
      const step = await routeStepDrawer();
      const gives = routeFieldsIn(step);
      if (shutting === "Escape") {
        expect(screen.queryByRole("textbox", { name: "Help" })).toBeNull();
        expect(within(gives).getAllByText("Unsaved").length).toBeGreaterThan(0);
        await openRouteField(gives);
      }

      expect(
        await within(step).findByRole("textbox", { name: "Help" }),
      ).toHaveValue("Raw route candidate.");
      expect(
        requestsTo(sent).every((request) => request.startsWith("GET ")),
      ).toBe(true);
    },
  );

  it.each(["Close", "Escape"] as const)(
    "locates a route field's change kept while its step's drawer was shut by %s from the count of parts unsaved, retaining whether its field was open",
    async (shutting) => {
      const { sent, version } = await routeCandidateShut(shutting);

      await userEvent.click(
        within(version).getByRole("button", {
          name: "1 part unsaved",
        }),
      );

      const step = await routeStepDrawer();
      if (shutting === "Escape") {
        await waitFor(() =>
          expect(
            within(step).getByLabelText("Name", { selector: "input" }),
          ).toHaveFocus(),
        );
        expect(
          within(step).queryByLabelText("Help", { selector: "input,textarea" }),
        ).toBeNull();
        await openRouteField(routeFieldsIn(step));
      }
      const help = await within(step).findByLabelText("Help", {
        selector: "input,textarea",
      });
      expect(help).toHaveValue("Raw route candidate.");
      expect(
        requestsTo(sent).every((request) => request.startsWith("GET ")),
      ).toBe(true);
    },
  );

  it("gives the keyboard back to the route field's row once the field opened from the count of parts unsaved is shut, never to the count beneath the step's drawer", async () => {
    const { version } = await routeCandidateShut("Close");
    const count = within(version).getByRole("button", {
      name: "1 part unsaved",
    });
    await userEvent.click(count);
    const step = await routeStepDrawer();
    const help = await within(step).findByRole("textbox", { name: "Help" });
    const field = routeFieldDrawer(help);
    expect(field).toHaveAttribute("aria-modal", "false");

    await userEvent.click(within(field).getByRole("button", { name: "Close" }));

    await waitFor(() => expect(field).not.toBeInTheDocument());
    const gives = routeFieldsIn(step);
    expect(within(gives).getByRole("button", { name: /^Open/ })).toHaveFocus();
    expect(count).not.toHaveFocus();
  });

  it.each(["Close", "Escape"] as const)(
    "lets a route field's change kept while its step's drawer was shut by %s go only by Cancel, counting nothing unsaved after",
    async (shutting) => {
      const { version, openStep } = await routeCandidateShut(shutting);
      await userEvent.click(openStep);
      const step = await routeStepDrawer();
      if (shutting === "Escape") await openRouteField(routeFieldsIn(step));
      const help = await within(step).findByRole("textbox", { name: "Help" });
      const field = routeFieldDrawer(help);

      await userEvent.click(
        within(field).getByRole("button", { name: "Cancel" }),
      );
      await waitFor(() => expect(field).not.toBeInTheDocument());
      await closeRouteStep(step);

      expect(
        within(version).queryByRole("button", {
          name: /parts? unsaved/,
        }),
      ).toBeNull();
      await userEvent.click(openStep);
      await routeStepDrawer();
      expect(screen.queryByRole("textbox", { name: "Help" })).toBeNull();
    },
  );

  /** A route step open with its name and what every case gives back changed, and whether runs may be helped changed. */
  async function routeCandidateBesideSetting() {
    libraryLaidOutAt(1400, 1400);
    const address = `/api/groups/${GROUP}/workflows/${ENTRY_ID}`;
    const routed = {
      ...WORKFLOW,
      steps: [
        {
          ...ASKING,
          name: "choose",
          producer: undefined,
          bindings: [],
          runs: { kind: "route", gives: [READY_SUMMARY], cases: [] },
        },
      ],
    };
    const { sent } = opening(
      `/groups/${KEY}/workflows/${ENTRY_ID}`,
      {
        [`GET ${address}`]: [reply({ ...DRAFTED, kind: "workflow" })],
        [`GET ${address}/versions/${DRAFT.versionId}`]: [reply(routed)],
      },
      undefined,
      "workflow",
    );
    await userEvent.click(
      await screen.findByRole("button", { name: "Run settings" }),
    );
    const version = openVersionRegion();
    await userEvent.click(
      within(version).getByRole("switch", { name: "Runs may be helped" }),
    );
    expect(
      within(version).getByRole("button", {
        name: "1 part unsaved",
      }),
    ).toBeVisible();
    expect(
      screen.queryByRole("button", { name: "2 parts unsaved" }),
    ).toBeNull();
    await userEvent.click(
      within(version).getByRole("link", {
        name: `Open ${isolatedInText("choose")}`,
      }),
    );
    const drawer = await screen.findByRole("dialog", {
      name: isolatedInText("choose"),
    });
    await userEvent.click(
      within(drawer).getByLabelText("Name", { selector: "input" }),
    );
    await userEvent.paste("_draft");
    const givesHeading = within(drawer).getByText(
      "What every case gives back",
      { selector: "h5" },
    );
    const gives = givesHeading.closest("section")!;
    expect(gives).toHaveAccessibleName("What every case gives back");
    const open = within(gives).getByRole("button", { name: /^Open/ });
    await userEvent.click(open);
    const editor = document.getElementById(
      open.getAttribute("aria-controls")!,
    )!;
    const help = within(editor).getByLabelText("Help", {
      selector: "input,textarea",
    });
    await userEvent.click(help);
    await userEvent.paste("Raw route candidate.");
    return { sent, help, editor };
  }

  it("holds a workflow's submission through a setting edit and its write", async () => {
    libraryLaidOutAt(1400, 1400);
    const address = `/api/groups/${GROUP}/workflows/${ENTRY_ID}`;
    const content = {
      revision: 1,
      takes: [COMPLAINT],
      gives: [SUMMARY],
      steps: [{ ...ASKING, runs: { ...ASKING.runs, gives: [READY_SUMMARY] } }],
      outputs: [
        {
          bindingId: "0000000e-0000-4000-8000-000000000b82",
          target: "summary",
          source: { step: 0, path: "summary" },
        },
      ],
      keepsOwnCeiling: false,
      raiseNeedsApproval: false,
      mayBeHelped: false,
      problems: [],
      sendsPast: [],
      offered: {
        codeSteps: [],
        searchWays: [],
        models: [],
      },
      terms: {},
    };
    const saving = deferred<readonly [string, number]>();
    const { sent } = opening(
      `/groups/${KEY}/workflows/${ENTRY_ID}`,
      {
        [`GET ${address}`]: [reply({ ...DRAFTED, kind: "workflow" })],
        [`GET ${address}/versions/${DRAFT.versionId}`]: [reply(content)],
        [`PUT ${address}/versions/${DRAFT.versionId}/help`]: [saving.promise],
        [`PUT ${address}/versions/${DRAFT.versionId}/submission`]: [
          reply({
            ...DRAFTED,
            kind: "workflow",
            versions: [SUBMITTED, IN_SERVICE, SEEDED],
          }),
        ],
      },
      undefined,
      "workflow",
    );
    await opened();
    const submit = within(openVersionRegion()).getByRole("button", {
      name: "Submit",
    });
    await waitFor(() =>
      expect(submit).not.toHaveAttribute("aria-disabled", "true"),
    );
    await userEvent.click(screen.getByRole("button", { name: "Run settings" }));
    await userEvent.click(
      screen.getByRole("switch", { name: "Runs may be helped" }),
    );
    expect(submit).toHaveAttribute("aria-disabled", "true");
    await userEvent.click(
      screen.getByRole("button", { name: "Save whether a run may be helped" }),
    );
    expect(submit).toHaveAttribute("aria-disabled", "true");
    expect(requestsTo(sent)).not.toContain(
      `PUT ${address}/versions/${DRAFT.versionId}/submission`,
    );
    saving.settle([
      JSON.stringify({ ...content, revision: 2, mayBeHelped: true }),
      200,
    ]);
    await waitFor(() =>
      expect(submit).not.toHaveAttribute("aria-disabled", "true"),
    );
    await userEvent.click(submit);
    await waitFor(() =>
      expect(
        requestsTo(sent).filter((request) => request.endsWith("/submission")),
      ).toHaveLength(1),
    );
  });

  it("heads the page with its name, a kind tag and quiet purpose, without a stop line while running", async () => {
    opening(PAGE);

    const heading = await opened();
    expect(heading).toHaveTextContent("Triage");
    expect(heading.parentElement?.parentElement).toHaveTextContent("Question");
    expect(screen.getByText("Sorts claims.")).toHaveStyle({
      color: theme.palette.text.secondary,
    });
    expect(screen.queryByText(/^Stopped by /)).toBeNull();
  });

  it("shows who stopped a stopped entry and when in warning colour, with its empty purpose", async () => {
    const { purpose: _said, ...unsaid } = TRIAGE;
    opening(PAGE, {
      [`GET ${ENTRY}`]: [
        reply({
          ...unsaid,
          stopped: { by: AVO, at: "2026-09-24T08:15:00Z" },
          stateActs: ["let_go"],
        }),
      ],
    });
    await opened();

    expect(
      screen.getByText("It says nothing of what it is for."),
    ).toBeVisible();
    const stopped = screen.getByText(/^Stopped by /);
    expect(stopped).toHaveTextContent(
      new RegExp(`^Stopped by ${setApart("Avo Lumerace")} · .+$`),
    );
    expect(stopped).toHaveStyle({ color: theme.palette.warning.main });
    expect(
      screen.getByRole("button", { name: "Let it be used again" }),
    ).toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: "Stop it being used" }),
    ).toBeNull();
  });

  it("describes a stopper whose name can no longer be found", async () => {
    opening(PAGE, {
      [`GET ${ENTRY}`]: [
        reply({
          ...TRIAGE,
          stopped: { by: { userId: AVO.userId }, at: "2026-09-24T08:15:00Z" },
          stateActs: ["let_go"],
        }),
      ],
    });
    await opened();

    const stopped = screen.getByText(/^Stopped by /);
    expect(stopped).toHaveTextContent(
      /^Stopped by somebody who can no longer be found · .+$/,
    );
    expect(stopped).not.toHaveTextContent(AVO.userId);
    expect(stopped).toHaveStyle({ color: theme.palette.warning.main });
  });

  it.each([
    [
      "every act on the entry",
      ["start_draft", "rename", "stop"],
      [
        "Stop it being used",
        "Triage, editable",
        "Sorts claims., editable",
        "New draft",
      ],
    ],
    ["none of them", [], []],
  ])(
    "draws the controls on the entry the server says the reader may use: %s",
    async (_case, entryActs, drawn) => {
      opening(PAGE, {
        [`GET ${ENTRY}`]: [reply({ ...TRIAGE, stateActs: entryActs })],
      });
      await opened();

      expect(
        [
          "Stop it being used",
          "Let it be used again",
          "Triage, editable",
          "Sorts claims., editable",
          "New draft",
        ].filter((name) => screen.queryByRole("button", { name }) !== null),
      ).toEqual(drawn);
    },
  );

  /** Stopping the entry and retiring a version take something out of use; every other act, undoing one included, does not. */
  it.each([
    [
      "an entry that may be stopped, its version in service",
      TRIAGE,
      ["Stop it being used", "Retire"],
      ["New draft"],
    ],
    [
      "a stopped entry, its version submitted",
      {
        ...DRAFTED,
        stopped: { by: AVO, at: "2026-09-24T08:15:00Z" },
        stateActs: ["let_go", "rename"],
        versions: [SUBMITTED, SEEDED],
      },
      [],
      ["Let it be used again", "Withdraw", "Approve"],
    ],
    ["a draft", DRAFTED, ["Stop it being used"], ["Submit"]],
  ])(
    "draws only what stops or takes out of use in the warning colour, on %s",
    async (_case, entry, warning, plain) => {
      opening(PAGE, { [`GET ${ENTRY}`]: [reply(entry)] });
      await opened();

      const entryButtons = screen
        .getAllByRole("button")
        .filter((each) =>
          [
            "Stop it being used",
            "Let it be used again",
            "Edit",
            "New draft",
          ].includes(each.textContent ?? ""),
        );
      const drawn = [...entryButtons, ...versionActionButtons()];

      expect(
        drawn
          .filter((each) => each.classList.contains(buttonClasses.colorWarning))
          .map((each) => each.textContent),
      ).toEqual(warning);
      expect(
        drawn
          .filter((each) => each.classList.contains(buttonClasses.colorPrimary))
          .map((each) => each.textContent),
      ).toEqual(plain);
    },
  );

  it("tables the versions newest first, each with its standing, who wrote it and who approved it, the newest open", async () => {
    opening(PAGE);
    await opened();

    expect(versionRows()).toEqual([
      ["Version 2", "", "In service"],
      ["Version 1", "", "In service"],
    ]);
    expect(picked()).toBe("Version 2 In service");
    expect(
      within(versions()).queryByRole("button", { name: /Add a name/ }),
    ).toBeNull();
    const add = within(theVersion(2)).getByRole("button", {
      name: "Add a name for Version 2",
    });
    expect(add.closest("a, td, h2")).toBeNull();
    expect(add.parentElement?.parentElement).toContainElement(
      within(theVersion(2)).getByRole("heading", { name: "Version 2" }),
    );
  });

  it("lists one version while leaving its content open", async () => {
    opening(PAGE, {
      [`GET ${ENTRY}`]: [reply({ ...TRIAGE, versions: [IN_SERVICE] })],
    });
    await opened();

    expect(versionRows()).toEqual([["Version 2", "", "In service"]]);
    expect(within(versions()).queryByRole("table")).toBeNull();
    expect(theVersion(2)).toBeInTheDocument();
  });

  it("opens the version the address names, including its own content", async () => {
    const { sent } = opening(`${PAGE}?version=${SEEDED.versionId}`, {
      [`GET ${ENTRY}/versions/${SEEDED.versionId}`]: [
        reply({ ...QUESTION, instruction: "Read the older category." }),
      ],
    });
    await opened();

    expect(picked()).toMatch(/^Version 1/);
    expect(theVersion(1)).toBeInTheDocument();
    await within(theVersion(1)).findByText("Read the older category.");
    expect(theVersion(1)).toHaveTextContent("Read the older category.");
    expect(requestsTo(sent)).toContain(
      `GET ${ENTRY}/versions/${SEEDED.versionId}`,
    );
  });

  it("opens the newest version and its content when the address names no version here", async () => {
    const missing = "00000007-0000-4000-8000-000000000b99";
    const content = deferred<readonly [string, number]>();
    const { sent } = opening(`${PAGE}?version=${missing}`, {
      [`GET ${ENTRY}/versions/${IN_SERVICE.versionId}`]: [content.promise],
    });
    await opened();

    expect(picked()).toMatch(/^Version 2/);
    expect(theVersion(2)).toHaveTextContent("Loading…");
    await waitFor(() => {
      expect(requestsTo(sent)).toContain(
        `GET ${ENTRY}/versions/${IN_SERVICE.versionId}`,
      );
    });
    expect(requestsTo(sent)).not.toContain(`GET ${ENTRY}/versions/${missing}`);

    await act(async () => {
      content.settle([
        JSON.stringify({
          ...QUESTION,
          instruction: "Read the current category.",
        }),
        200,
      ]);
    });

    await within(theVersion(2)).findByText("Read the current category.");
    expect(theVersion(2)).toHaveTextContent("Read the current category.");
    expect(theVersion(2)).not.toHaveTextContent("Loading…");
    expect(
      requestsTo(sent).filter(
        (request) =>
          request === `GET ${ENTRY}/versions/${IN_SERVICE.versionId}`,
      ),
    ).toHaveLength(1);
  });

  it.each([
    ["no act on it", SEEDED, []],
    ["an act on it", IN_SERVICE, ["Retire"]],
  ])(
    "draws only admitted actions at the end of version %s's title line",
    async (_case, version, drawn) => {
      opening(`${PAGE}?version=${version.versionId}`);
      await opened();

      const heading = within(theVersion(version.number)).getByRole("heading", {
        name: `Version ${version.number}`,
      });
      expect(actsDrawn()).toEqual(drawn);
      expect(
        screen.queryByRole("region", { name: "What can be done" }),
      ).toBeNull();
      if (drawn.length > 0) {
        expect(heading.parentElement).toContainElement(
          versionActionButtons()[0]!,
        );
      }
    },
  );

  it.each([
    ["its writer, the version a draft", DRAFTED, ["Submit"], [], []],
    [
      "an approver, the version submitted",
      { ...DRAFTED, versions: [SUBMITTED, SEEDED] },
      ["Approve"],
      ["Withdraw"],
      [],
    ],
    [
      "one who may retire it, the version in service",
      TRIAGE,
      [],
      [],
      ["Retire"],
    ],
  ])(
    "fills only the act that moves the version forward, for %s",
    async (_case, entry, filled, plain, warning) => {
      opening(PAGE, { [`GET ${ENTRY}`]: [reply(entry)] });
      await opened();

      const drawn = versionActionButtons();
      const drawnAs = (has: (each: HTMLElement) => boolean) =>
        drawn.filter(has).map((each) => each.textContent);

      expect(
        drawnAs((each) => each.classList.contains(buttonClasses.contained)),
      ).toEqual(filled);
      expect(
        drawnAs(
          (each) =>
            each.classList.contains(buttonClasses.outlined) &&
            each.classList.contains(buttonClasses.colorPrimary),
        ),
      ).toEqual(plain);
      expect(
        drawnAs(
          (each) =>
            each.classList.contains(buttonClasses.outlined) &&
            each.classList.contains(buttonClasses.colorWarning),
        ),
      ).toEqual(warning);
      expect(drawn).toHaveLength(filled.length + plain.length + warning.length);
    },
  );

  it("frames the version open and the side panel each as one card of the page, none within another", async () => {
    opening(PAGE);
    await opened();

    const cards = [details(), theVersion(2)];

    expect(drawnOf(theVersion(2))).toEqual(drawnAtDepth(1));
    expect(drawnOf(details())).toEqual({
      ...drawnAtDepth(1),
      padding: "18px 22px",
    });
    expect(
      cards.flatMap((outer) =>
        cards.filter((inner) => inner !== outer && outer.contains(inner)),
      ),
    ).toEqual([]);
  });

  it("draws Versions and Pinned by as parts of the side panel's card, each ruled off across it and inset like it, with no card of their own", async () => {
    opening(PAGE);
    await opened();

    for (const part of [versions(), pinned(2).parentElement!]) {
      const style = getComputedStyle(part);
      expect([
        style.borderTopWidth,
        style.borderTopStyle,
        style.borderTopColor,
        style.marginLeft,
        style.marginRight,
        style.paddingLeft,
        style.paddingRight,
        style.paddingTop,
        style.marginTop,
      ]).toEqual([
        "1px",
        "solid",
        "rgb(228, 235, 238)",
        "calc(-1 * var(--card-side-padding))",
        "calc(-1 * var(--card-side-padding))",
        "var(--card-side-padding)",
        "var(--card-side-padding)",
        "18px",
        "18px",
      ]);
      expect(style.getPropertyValue("--card-side-padding")).toBe("22px");
      expect([
        style.borderLeftStyle,
        style.backgroundColor,
        style.borderRadius,
      ]).toEqual(["none", "rgba(0, 0, 0, 0)", ""]);
    }
    expect(getComputedStyle(pinned(2)).borderLeftStyle).toBe("none");
    expect(getComputedStyle(pinned(2)).marginTop).toBe("0px");
  });

  it("starts Details' facts under the drawer's ruled-off header where it lies over a narrow page, and rules Versions and Pinned by off across the whole drawer, inset as the drawer is", async () => {
    libraryLaidOutAt(900, 1600);
    localStorage.setItem("lilradish.panel.version", "expanded");
    opening(PAGE);
    const panel = await screen.findByRole("dialog", { name: "Details" });

    const header = within(panel).getByRole("heading", {
      name: "Details",
    }).parentElement!;
    const body = header.nextElementSibling!;
    const facts = panel.querySelector("dl")!;
    expect(getComputedStyle(body).padding).toBe("18px 24px");
    expect([
      getComputedStyle(header).padding,
      getComputedStyle(header).borderBottomStyle,
    ]).toEqual(["18px 24px 14px", "solid"]);
    expect(body.firstElementChild!.textContent).toBe("");
    expect(body.firstElementChild!.nextElementSibling).toContainElement(facts);
    expect(facts.parentElement!.firstElementChild).toBe(facts);
    expect(getComputedStyle(facts).marginTop).toBe("0px");
    for (const part of [versions(), pinned(2).parentElement!]) {
      expect(panel).toContainElement(part);
      const style = getComputedStyle(part);
      expect([
        style.marginLeft,
        style.marginRight,
        style.paddingLeft,
        style.paddingRight,
        style.getPropertyValue("--card-side-padding"),
        style.borderTopWidth,
        style.borderTopColor,
      ]).toEqual([
        "calc(-1 * var(--card-side-padding))",
        "calc(-1 * var(--card-side-padding))",
        "var(--card-side-padding)",
        "var(--card-side-padding)",
        "24px",
        "1px",
        "rgb(228, 235, 238)",
      ]);
    }
  });

  it("lays Details' facts on the side panel's facts grid, each label in a column 112px wide beside its value, quieter and smaller than it", async () => {
    opening(PAGE);
    await opened();

    const facts = details().querySelector("dl")!;
    const grid = getComputedStyle(facts);
    expect([
      grid.display,
      grid.gridTemplateColumns,
      grid.alignItems,
      grid.margin,
    ]).toEqual(["grid", "112px minmax(0, 1fr)", "baseline", "0px"]);
    expect(writtenRules()).toMatch(
      new RegExp(`\\.${styledClassOf(facts)}\\{[^}]*gap:14px 12px;`),
    );
    expect([...facts.children].map((child) => child.tagName)).toEqual([
      "DT",
      "DD",
      "DT",
      "DD",
      "DT",
      "DD",
    ]);
    const label = getComputedStyle(within(facts).getByText("Written by"));
    const value = getComputedStyle(
      within(facts).getByText("Written by").nextElementSibling!,
    );
    expect([
      label.fontSize,
      label.lineHeight,
      label.fontWeight,
      label.color,
      label.overflowWrap,
    ]).toEqual(["13px", "24px", "600", "rgb(88, 102, 108)", "anywhere"]);
    expect([value.fontSize, value.lineHeight, value.fontWeight]).toEqual([
      "14px",
      "24px",
      "400",
    ]);
    expect(writtenRules()).not.toContain("calc(8px - 20px)");
  });

  it("starts Details' facts 16px under its heading beside the open version, with nothing between to shift them", async () => {
    opening(PAGE);
    await opened();

    const heading = within(details()).getByRole("heading", {
      name: "Details",
    });
    const facts = details().querySelector("dl")!;
    expect(getComputedStyle(heading).marginBottom).toBe("16px");
    const body = heading.closest("header")!.nextElementSibling!;
    expect(body).toContainElement(facts);
    expect(body.firstElementChild!.firstElementChild!.firstElementChild).toBe(
      facts,
    );
    expect(getComputedStyle(facts).marginTop).toBe("0px");
    expect(facts.parentElement!.getAttribute("class")).toBeNull();
  });

  it("lays Details, Versions and Pinned by in the side panel beside the open version", async () => {
    opening(PAGE);
    await opened();
    const side = details();
    const sideParts = [side.querySelector("dl")!, versions(), pinned(2)];
    expect(sideParts.every((part) => side.contains(part))).toBe(true);
    for (let index = 1; index < sideParts.length; index++) {
      expect(
        sideParts[index - 1]!.compareDocumentPosition(sideParts[index]!) &
          Node.DOCUMENT_POSITION_FOLLOWING,
      ).toBe(Node.DOCUMENT_POSITION_FOLLOWING);
    }
    expect(side).not.toContainElement(theVersion(2));
    const main = theVersion(2).closest('[role="group"]')!;
    expect(main.parentElement!).toContainElement(side);
    expect(getComputedStyle(main.parentElement!).display).toBe("flex");
  });

  it("links each version to the address that opens it", async () => {
    const { at } = opening(PAGE);
    await opened();
    const link = within(versions()).getByRole("link", { name: /^Version 1/ });
    expect(link).toHaveAttribute("href", `${PAGE}?version=${SEEDED.versionId}`);

    await userEvent.click(link);

    expect(at()).toBe(`${PAGE}?version=${SEEDED.versionId}`);
    expect(picked()).toMatch(/^Version 1/);
  });

  it.each([null, "collapsed", "expanded"] as const)(
    "uses a narrow container's first choice or remembered side-panel choice %s",
    async (choice) => {
      libraryLaidOutAt(900, 1600);
      if (choice !== null)
        localStorage.setItem("lilradish.panel.version", choice);
      const { sent } = opening(PAGE);
      await waitFor(() =>
        expect(document.querySelector("h1")).toHaveTextContent("Triage"),
      );
      const strip = screen.getAllByRole("button", {
        name: "Details",
        hidden: true,
      })[0]!;
      expect(strip.textContent).toBe("Details");
      expect(strip).toHaveAttribute(
        "aria-expanded",
        String(choice === "expanded"),
      );
      if (choice === "expanded") {
        expect(
          await screen.findByRole("dialog", { name: "Details" }),
        ).toBeVisible();
        await userEvent.click(screen.getByRole("button", { name: "Close" }));
        await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
        expect(strip).toBeVisible();
        expect(strip).toHaveAttribute("aria-expanded", "false");
      } else {
        expect(screen.queryByRole("dialog")).toBeNull();
        expect(theVersion(2)).toBeVisible();
        await userEvent.click(strip);
        expect(
          await screen.findByRole("dialog", { name: "Details" }),
        ).toBeVisible();
      }
      expect(entryReads(sent)).toBe(1);
      expect(
        requestsTo(sent).some((request) => !request.startsWith("GET ")),
      ).toBe(false);
    },
  );

  it.each(["Close", "Escape", "backdrop"] as const)(
    "folds the narrow side panel back to its strip across the page by %s, with the keyboard on the strip",
    async (shutting) => {
      libraryLaidOutAt(900, 1600);
      localStorage.setItem("lilradish.panel.version", "collapsed");
      opening(PAGE);
      await waitFor(() =>
        expect(document.querySelector("h1")).toHaveTextContent("Triage"),
      );
      const strip = screen.getByRole("button", {
        name: "Details",
        expanded: false,
      });
      expect(strip.textContent).toBe("Details");
      expect(
        strip.compareDocumentPosition(theVersion(2)) &
          Node.DOCUMENT_POSITION_FOLLOWING,
      ).toBe(Node.DOCUMENT_POSITION_FOLLOWING);
      expect(getComputedStyle(strip).height).toBe("48px");
      expect(getComputedStyle(strip).width).toBe("100%");
      expect(strip).not.toHaveAttribute("aria-label");

      await userEvent.click(strip);
      const dialog = await screen.findByRole("dialog", { name: "Details" });
      expect(strip).toHaveAttribute("aria-expanded", "true");
      expect(
        document.getElementById(strip.getAttribute("aria-controls")!),
      ).toBe(dialog);
      expect(within(dialog).getByRole("region", { name: "Versions" })).toBe(
        versions(),
      );
      expect(localStorage.getItem("lilradish.panel.version")).toBe("expanded");
      if (shutting === "Close")
        await userEvent.click(
          within(dialog).getByRole("button", { name: "Close" }),
        );
      else if (shutting === "Escape") await userEvent.keyboard("{Escape}");
      else
        await userEvent.click(
          document.querySelector<HTMLElement>(".MuiBackdrop-root")!,
        );

      await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
      expect(strip).toHaveAttribute("aria-expanded", "false");
      expect(strip).toHaveFocus();
      expect(theVersion(2)).toBeVisible();
      expect(localStorage.getItem("lilradish.panel.version")).toBe("collapsed");
    },
  );

  it.each([
    [1226, "region"],
    [1225, "dialog"],
  ] as const)(
    "folds Details to its strip for a field's drawer only while it is open, the drawer at %s pixels a %s, without keeping either as the device's choice",
    async (width, role) => {
      const resize = libraryLaidOutAt(width, 2000);
      localStorage.setItem("lilradish.panel.version", "expanded");
      opening(PAGE, {
        [`GET ${ENTRY}`]: [reply(DRAFTED)],
        [`GET ${ENTRY}/versions/${DRAFT.versionId}`]: [reply(READY_QUESTION)],
      });
      await opened();
      const fold = screen.getByRole("button", { name: "Details" });
      expect(fold).toHaveAttribute("aria-expanded", "true");
      const open = await within(theVersion(3)).findByRole("button", {
        name: `Open ${setApart("summary")}`,
      });

      await userEvent.click(open);
      await resize(width);

      const drawer = await screen.findByRole(role, {
        name: "The declared answer \xb7 Field 1 of 1",
      });
      expect(fold).toHaveAttribute("aria-expanded", "false");
      expect(getComputedStyle(fold.closest("section")!).width).toBe("56px");
      expect(localStorage.getItem("lilradish.panel.version")).toBe("expanded");

      await userEvent.click(
        within(drawer).getByRole("button", { name: "Close" }),
      );

      await waitFor(() =>
        expect(
          screen.queryByRole(role, {
            name: "The declared answer \xb7 Field 1 of 1",
          }),
        ).toBeNull(),
      );
      await waitFor(() =>
        expect(fold).toHaveAttribute("aria-expanded", "true"),
      );
      await waitFor(() => expect(details()).toContainElement(versions()));
      await waitFor(() => expect(open).toHaveFocus());
      expect(localStorage.getItem("lilradish.panel.version")).toBe("expanded");
    },
  );

  it("keeps Details folded and the field drawer reachable while that field is open", async () => {
    const resize = libraryLaidOutAt(1400, 2000);
    localStorage.setItem("lilradish.panel.version", "collapsed");
    opening(PAGE, {
      [`GET ${ENTRY}`]: [reply(DRAFTED)],
      [`GET ${ENTRY}/versions/${DRAFT.versionId}`]: [reply(READY_QUESTION)],
    });
    await opened();
    const fold = screen.getByRole("button", { name: "Details" });
    await userEvent.click(
      await within(theVersion(3)).findByRole("button", {
        name: `Open ${setApart("summary")}`,
      }),
    );
    await resize(1400);
    await screen.findByRole("region", {
      name: "The declared answer \xb7 Field 1 of 1",
    });
    expect(localStorage.getItem("lilradish.panel.version")).toBe("collapsed");

    fireEvent.click(fold);
    await resize(1400);

    expect(fold).toHaveAttribute("aria-expanded", "false");
    expect(
      await screen.findByRole("region", {
        name: "The declared answer \xb7 Field 1 of 1",
      }),
    ).toBeVisible();
    expect(
      screen.queryByRole("dialog", {
        name: "The declared answer \xb7 Field 1 of 1",
      }),
    ).toBeNull();
    expect(localStorage.getItem("lilradish.panel.version")).toBe("collapsed");
  });

  it("keeps the wide side panel's strip down its side, with no strip across the page", async () => {
    libraryLaidOutAt(1800, 2000);
    localStorage.setItem("lilradish.panel.version", "collapsed");
    opening(PAGE);
    await opened();

    const fold = screen.getByRole("button", { name: "Details" });
    expect(screen.getAllByRole("button", { name: "Details" })).toEqual([fold]);
    expect(fold).toHaveAttribute("aria-label", "Details");
    expect(fold.textContent).toBe("");
    const side = fold.closest("section")!;
    expect(fold).toHaveAttribute("aria-expanded", "false");
    expect(getComputedStyle(side).width).toBe("56px");
    expect(screen.queryByRole("dialog")).toBeNull();

    await userEvent.click(fold);

    expect(fold).toHaveAttribute("aria-expanded", "true");
    expect(screen.queryByRole("dialog")).toBeNull();
    expect(details()).toBe(side);
    expect(side).toContainElement(versions());
  });

  it("opens another entry newest first while retaining the device side-panel choice", async () => {
    const other = `/api/groups/${GROUP}/questions/${HANDLE}`;
    const handling = { ...IN_SERVICE, versionId: HANDLING, number: 4 };
    const { go } = opening(PAGE, {
      [`GET ${other}`]: [
        reply({
          ...TRIAGE,
          entryId: HANDLE,
          name: "Handle",
          versions: [handling, SEEDED],
        }),
      ],
      [`GET ${other}/versions/${HANDLING}`]: [reply(QUESTION)],
    });
    await opened();
    await userEvent.click(screen.getByRole("button", { name: "Details" }));

    await go(`/groups/${KEY}/questions/${HANDLE}`);

    await screen.findByRole("heading", { level: 1, name: /Handle/ });
    const fold = screen.getByRole("button", { name: "Details" });
    expect(fold).toHaveAttribute("aria-expanded", "false");
    await userEvent.click(fold);
    expect(versionRows().map(([number]) => number)).toEqual([
      "Version 4",
      "Version 1",
    ]);
  });

  it("opens a different entry with its one listed version and content", async () => {
    const other = `/api/groups/${GROUP}/questions/${HANDLE}`;
    const handling = { ...IN_SERVICE, versionId: HANDLING, number: 4 };
    opening(PAGE, {
      [`GET ${other}`]: [
        reply({
          ...TRIAGE,
          entryId: HANDLE,
          name: "Handle",
          versions: [handling],
        }),
      ],
      [`GET ${other}/versions/${HANDLING}`]: [reply(QUESTION)],
    });
    await opened();

    await userEvent.click(within(pinned(2)).getByRole("link"));

    await screen.findByRole("heading", { level: 1, name: /Handle/ });
    expect(versionRows()).toEqual([["Version 4", "", "In service"]]);
    expect(within(versions()).queryByRole("table")).toBeNull();
    expect(theVersion(4)).toBeInTheDocument();
  });

  it("keeps the side panel shut while reading the same entry again and reopens its newest-first list", async () => {
    const { sent } = opening(PAGE, {
      [`GET ${ENTRY}`]: [
        reply(TRIAGE),
        reply({
          ...TRIAGE,
          versions: [{ ...IN_SERVICE, stateActs: [] }, SEEDED],
        }),
      ],
      [`PUT ${ENTRY}/versions/${IN_SERVICE.versionId}/retirement`]: [
        refusal("VERSION_STANDING_REFUSES", 409),
      ],
    });
    await opened();
    await userEvent.click(screen.getByRole("button", { name: "Details" }));

    await confirmRetire();
    await waitFor(() => expect(entryReads(sent)).toBe(2));
    await waitFor(() => expect(actsDrawn()).toEqual([]));

    const toggle = screen.getByRole("button", { name: "Details" });
    const expandedAfterReading = toggle.getAttribute("aria-expanded");
    await userEvent.click(toggle);

    expect(expandedAfterReading).toBe("false");
    expect(versionRows().map(([number]) => number)).toEqual([
      "Version 2",
      "Version 1",
    ]);
  });

  it("says of the open version how many in service pin it, links each, and draws what it holds", async () => {
    const { sent } = opening(PAGE);
    await opened();

    expect(actsDrawn()).toEqual(["Retire"]);
    expect(pinned(2)).toHaveTextContent("Version 2 is pinned by");
    const pinners = within(pinned(2)).getByRole("list", {
      name: "Pinned by",
    });
    expect(
      within(pinners)
        .getAllByRole("link")
        .map((link) => [link.textContent, link.getAttribute("href")]),
    ).toEqual([
      [
        `Handle \u00b7 Version 4`,
        `/groups/${KEY}/workflows/${HANDLE}?version=${HANDLING}`,
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
    const pin = within(pinners).getByRole("link");
    expect(pin).toHaveAccessibleName(`${setApart("Handle")} \u00b7 Version 4`);
    expect(pin.textContent).toBe(`Handle \u00b7 Version 4`);
    expect(getComputedStyle(pin).fontStyle).not.toBe("italic");
    const kind = within(pinners).getByText("Workflow");
    expect(kind.closest("a")).toBeNull();
    expect(kind.querySelector("svg")).toBeNull();
    expect(getComputedStyle(kind).color).toBe("rgb(64, 60, 57)");
    expect(getComputedStyle(kind).backgroundColor).toBe("rgb(232, 230, 227)");
    expect(theVersion(2)).not.toHaveTextContent("Waiting on");
  });

  it("draws known and unknown pinners while linking only known kinds", async () => {
    const { sent } = opening(PAGE, {
      [`GET ${ENTRY}`]: [
        reply({
          ...TRIAGE,
          versions: [
            {
              ...IN_SERVICE,
              pinnedBy: [
                ...IN_SERVICE.pinnedBy,
                {
                  versionName: null,
                  entryId: "00000006-0000-4000-8000-000000000b64",
                  kind: "unlisted_kind",
                  name: "Guide",
                  versionId: "00000007-0000-4000-8000-000000000b64",
                  number: 7,
                },
              ],
            },
            SEEDED,
          ],
        }),
      ],
    });
    await opened();

    expect(actsDrawn()).toEqual(["Retire"]);
    expect(pinned(2)).toHaveTextContent("2 versions:");
    const pinners = within(pinned(2)).getByRole("list", {
      name: "Pinned by",
    });
    expect(
      within(pinners)
        .getAllByRole("link")
        .map((link) => [link.textContent, link.getAttribute("href")]),
    ).toEqual([
      [
        `Handle \u00b7 Version 4`,
        `/groups/${KEY}/workflows/${HANDLE}?version=${HANDLING}`,
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
    const unknown = within(pinners).getAllByRole("listitem")[1]!;
    expect(unknown).toHaveTextContent(`unlisted_kindGuide \u00b7 Version 7`);
    expect(within(unknown).queryByRole("link")).toBeNull();
    expect(unknown.querySelector("a[href]")).toBeNull();
    const pin = within(pinners).getByRole("link");
    expect(pin).toHaveAccessibleName(`${setApart("Handle")} \u00b7 Version 4`);
    expect(pin.textContent).toBe(`Handle \u00b7 Version 4`);
    expect(getComputedStyle(pin).fontStyle).not.toBe("italic");
    expect(theVersion(2)).not.toHaveTextContent("Waiting on");
  });

  it.each([20, 21, 22])(
    "shows all four facts under the title and limits %s named pinners to the first twenty",
    async (count) => {
      const pins = Array.from({ length: count }, (_, index) => ({
        ...IN_SERVICE.pinnedBy[0]!,
        kind: index % 2 === 0 ? "workflow" : "question",
        name: `Invented pinner ${index + 1}`,
        number: index + 1,
        versionId: `00000007-0000-4000-8000-${String(index + 1).padStart(12, "0")}`,
        versionName: index === 0 ? "Night-side only" : null,
      }));
      opening(PAGE, {
        [`GET ${ENTRY}`]: [
          reply({
            ...TRIAGE,
            versions: [{ ...IN_SERVICE, pinnedBy: pins }, SEEDED],
          }),
        ],
      });
      await opened();
      const region = theVersion(2);
      const facts = details().querySelector("dl")!;
      expect(
        Array.from(facts.querySelectorAll("dt"), (term) => term.textContent),
      ).toEqual(["Standing", "Written by", "Approved by"]);
      const heading = within(region).getByRole("heading", {
        name: "Version 2",
      });
      expect(facts.closest("section")).toHaveAccessibleName("Details");
      expect(heading.closest("section")).not.toContainElement(facts);
      expect(getComputedStyle(facts).borderTopWidth).toBe("0px");
      expect(facts.querySelectorAll("dt")).toHaveLength(3);
      const pinsPart = pinned(2);
      expect(pinsPart).toHaveTextContent(`${count} versions:`);
      if (count === 20) expect(pinsPart).not.toHaveTextContent(/and \d+ more/);
      else
        expect(
          within(pinsPart).getByText(`and ${count - 20} more`, { exact: true }),
        ).toBeVisible();
      const pinners = within(pinsPart).getByRole("list", { name: "Pinned by" });
      expect(within(pinners).getAllByRole("listitem")).toHaveLength(20);
      expect(within(pinners).getAllByRole("link")).toHaveLength(20);
      expect(
        within(pinners)
          .getAllByRole("link")
          .map((link) => [link.textContent, link.getAttribute("href")]),
      ).toEqual(
        pins
          .slice(0, 20)
          .map((pin) => [
            `${pin.name} \u00b7 Version ${pin.number}${pin.versionName === null ? "" : ` \u00b7 ${pin.versionName}`}`,
            `/groups/${KEY}/${pin.kind === "workflow" ? "workflows" : "questions"}/${pin.entryId}?version=${pin.versionId}`,
          ]),
      );
      expect(pinsPart).not.toHaveTextContent("Invented pinner 21");
      expect(pinsPart).not.toHaveTextContent("Invented pinner 22");
      expect(
        within(pinsPart).queryByRole("button", { name: /more|all/i }),
      ).toBeNull();
      const named = within(pinners).getByRole("link", {
        name: `${setApart("Invented pinner 1")} \u00b7 Version 1 \u00b7 ${setApart("Night-side only")}`,
      });
      const name = within(named).getByText("Night-side only");
      expect(name).toHaveAttribute("dir", "auto");
      expect(getComputedStyle(name).fontWeight).toBe("400");
      const question = within(pinners).getAllByText("Question")[0]!;
      expect(question.querySelector("svg")).toBeNull();
      expect(getComputedStyle(question).color).toBe("rgb(92, 87, 33)");
      expect(getComputedStyle(question).backgroundColor).toBe(
        "rgb(243, 242, 219)",
      );
    },
  );

  it("says of a submitted version that it waits on anybody who may approve an entry here", async () => {
    opening(PAGE, {
      [`GET ${ENTRY}`]: [reply({ ...DRAFTED, versions: [SUBMITTED, SEEDED] })],
    });
    await opened();

    expect(theVersion(3)).toHaveTextContent(
      "Waiting on anybody who may approve an entry here.",
    );
    expect(pinned(3)).toHaveTextContent("Nothing in service");
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
      "A group's entries are made, written and submitted only by a role in it that may write one.",
    ],
    [
      "Withdraw",
      { ...DRAFTED, versions: [SUBMITTED, SEEDED] },
      "DELETE",
      "submission",
      SUBMITTED,
      "A group's entries are made, written and submitted only by a role in it that may write one.",
    ],
    [
      "Approve",
      { ...DRAFTED, versions: [SUBMITTED, SEEDED] },
      "PUT",
      "approval",
      SUBMITTED,
      "Only someone in this group who can approve entries can put workflows, questions and reference lists into service, or approve or reject a usage limit change.",
    ],
    [
      "Retire",
      TRIAGE,
      "PUT",
      "retirement",
      IN_SERVICE,
      "A group's entries are retired, stopped and let go only by a role in it that may revoke one.",
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

      if (control === "Retire") {
        await confirmRetire();
      } else {
        await userEvent.click(
          within(openVersionRegion()).getByRole("button", { name: control }),
        );
      }

      if (control === "Retire") {
        await waitFor(() =>
          expect(
            screen.queryByRole("dialog", { name: "Retire Version 2?" }),
          ).toBeNull(),
        );
      }
      expect(
        await within(openVersionRegion()).findByRole("alert"),
      ).toHaveTextContent(rule);
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
        stopped: { by: AVO, at: "2026-09-24T08:15:00Z" },
        stateActs: ["let_go"],
      },
    ],
    ["New draft", "POST", "/versions", "made, written and submitted", TRIAGE],
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

  /** The table is stood in for with a rule editing takes nowhere, so only the table's word can reach the page. */
  it("edits the entry's name in place while preserving its purpose, and says a refusal as the table's rule for editing", async () => {
    const { sent } = opening(PAGE, {
      [`PATCH ${ENTRY}`]: [refusal("ACT_NOT_PERMITTED", 403)],
    });
    await opened();

    await userEvent.click(
      screen.getByRole("button", { name: "Triage, editable" }),
    );
    const dialog = screen
      .getByRole("textbox", { name: "Name" })
      .closest("form")!;
    expect(screen.queryByRole("dialog")).toBeNull();
    await userEvent.clear(
      within(dialog).getByRole("textbox", { name: "Name" }),
    );
    await userEvent.type(
      within(dialog).getByRole("textbox", { name: "Name" }),
      "Intake",
    );
    await userEvent.click(within(dialog).getByRole("button", { name: "Save" }));

    expect(await within(dialog).findByRole("alert")).toHaveTextContent(
      "Only someone in this group who can approve entries can put workflows, questions and reference lists into service, or approve or reject a usage limit change.",
    );
    const edited = sent.mock.calls.find(([, init]) => init?.method === "PATCH");
    expect(JSON.parse(String(edited![1]?.body))).toEqual({
      name: "Intake",
      purpose: "Sorts claims.",
    });
  });

  it.each([
    ["Name", "Triage, editable", "Triage"],
    ["What it is for", "Sorts claims., editable", "Sorts claims."],
  ])(
    "cancels editing %s back to its own value without a request",
    async (label, control, original) => {
      const { sent } = opening(PAGE);
      await opened();
      const before = requestsTo(sent);
      await userEvent.click(screen.getByRole("button", { name: control }));
      const input = screen.getByRole("textbox", { name: label });
      expect(input).toHaveValue(original);
      await userEvent.type(input, " revised");
      await userEvent.keyboard("{Escape}");
      expect(screen.queryByRole("textbox", { name: label })).toBeNull();
      expect(screen.getByRole("button", { name: control })).toHaveFocus();
      expect(requestsTo(sent)).toEqual(before);
      expect(
        sent.mock.calls.filter(([, init]) => init?.method === "PATCH"),
      ).toEqual([]);
    },
  );

  it("keeps the purpose input's hint and invalid explanation linked through correction and refusal", async () => {
    const { sent } = opening(PAGE, {
      [`PATCH ${ENTRY}`]: [refusal("ACT_NOT_PERMITTED", 403)],
    });
    await opened();
    const before = requestsTo(sent);
    const trigger = screen.getByRole("button", {
      name: "Sorts claims., editable",
    });
    await userEvent.click(trigger);
    const input = screen.getByRole("textbox", { name: "What it is for" });
    const hint =
      "One line saying what it does, for whoever picks it. It may be left empty.";
    expect(input).toHaveAccessibleDescription(hint);
    fireEvent.change(input, { target: { value: "a".repeat(513) } });
    expect(input).toHaveAccessibleDescription(
      `${hint} What it is for is at most 512 characters on one line, with something in it that shows.`,
    );
    expect(input).toHaveAttribute("aria-invalid", "true");
    await userEvent.keyboard("{Enter}");
    expect(requestsTo(sent)).toEqual(before);
    fireEvent.change(input, { target: { value: "Routes claims." } });
    expect(input).toHaveAccessibleDescription(hint);
    await userEvent.keyboard("{Enter}");
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "Only someone in this group who can approve entries",
    );
    await waitFor(() => expect(input).toHaveFocus());
    expect(input).toHaveValue("Routes claims.");
    expect(input).toHaveAccessibleDescription(hint);
    const writes = sent.mock.calls.filter(
      ([, init]) => init?.method === "PATCH",
    );
    expect(writes).toHaveLength(1);
    expect(JSON.parse(String(writes[0]![1]?.body))).toEqual({
      name: "Triage",
      purpose: "Routes claims.",
    });
    await userEvent.keyboard("{Escape}");
    expect(
      screen.queryByRole("textbox", { name: "What it is for" }),
    ).toBeNull();
    expect(
      screen.getByRole("button", { name: "Sorts claims., editable" }),
    ).toHaveFocus();
    expect(
      sent.mock.calls.filter(([, init]) => init?.method === "PATCH"),
    ).toHaveLength(1);
  });

  /** No line is written after an act: the page is drawn again as the change answered. */
  it("stops the entry, showing it as the change answered without reading it again or saying anything more", async () => {
    const { sent } = opening(PAGE, {
      [`PUT ${ENTRY}/stop`]: [
        reply({
          ...TRIAGE,
          stopped: { by: AVO, at: "2026-09-24T08:15:00Z" },
          stateActs: ["rename", "let_go"],
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
    // The group's gate, the page and the version open each hold a region of their own.
    expect(regionsSaid()).toEqual(["", "", ""]);
    expect(
      within(theVersion(2))
        .queryAllByRole("status")
        .map((each) => each.textContent),
    ).toEqual([""]);
  });

  it("keeps a stop answer visible while replacing an ordinary read in flight", async () => {
    const unanswered: Reply = new Promise(() => {});
    const { sent, readAgain } = opening(PAGE, {
      [`GET ${ENTRY}`]: [reply(TRIAGE), unanswered, unanswered],
      [`PUT ${ENTRY}/stop`]: [
        refusal("ACT_NOT_PERMITTED", 403),
        reply({
          ...TRIAGE,
          stopped: { by: AVO, at: "2026-09-24T08:15:00Z" },
          stateActs: ["rename", "let_go"],
        }),
      ],
    });
    await opened();

    await userEvent.click(
      screen.getByRole("button", { name: "Stop it being used" }),
    );
    await screen.findByRole("alert");
    await waitFor(() => expect(entryReads(sent)).toBe(2));
    await userEvent.click(
      screen.getByRole("button", { name: "Stop it being used" }),
    );

    expect(
      await screen.findByRole("button", { name: "Let it be used again" }),
    ).toBeInTheDocument();
    expect(entryReads(sent)).toBe(3);
    expect(readAgain).toHaveBeenCalledOnce();
    expect(screen.queryByRole("alert")).toBeNull();
  });

  it.each(["answer", "refusal"])(
    "keeps another entry under its own address when the first one's stopped %s arrives late",
    async (ending) => {
      const otherAddress = `/groups/${KEY}/questions/${HANDLE}`;
      const otherEntry = `/api/groups/${GROUP}/questions/${HANDLE}`;
      const { go, at } = opening(PAGE, {
        [`GET ${otherEntry}`]: [
          reply({
            ...TRIAGE,
            entryId: HANDLE,
            name: "Demo Sample",
            stateActs: [],
            versions: [],
          }),
        ],
      });
      await opened();
      const held = deferred<Response>();
      const ordinaryFetch = globalThis.fetch;
      const sent = vi.fn<typeof fetch>((input, init) =>
        `${init?.method ?? "GET"} ${new URL(String(input), "http://reader.test").pathname}` ===
        `PUT ${ENTRY}/stop`
          ? held.promise
          : ordinaryFetch(input, init),
      );
      vi.stubGlobal("fetch", sent);

      await userEvent.click(
        screen.getByRole("button", { name: "Stop it being used" }),
      );
      expect(
        sent.mock.calls.some(
          ([input, init]) =>
            init?.method === "PUT" && String(input).includes(`${ENTRY}/stop`),
        ),
      ).toBe(true);
      await go(otherAddress);
      expect(
        await screen.findByRole("heading", { level: 1, name: /Demo Sample/ }),
      ).toBeInTheDocument();

      await act(async () => {
        held.settle(
          new Response(
            JSON.stringify(
              ending === "answer"
                ? {
                    ...TRIAGE,
                    stopped: { by: AVO, at: "2026-09-24T08:15:00Z" },
                  }
                : { code: "ACT_NOT_PERMITTED" },
            ),
            {
              status: ending === "answer" ? 200 : 403,
              headers: { "Content-Type": "application/json" },
            },
          ),
        );
      });

      expect(at()).toBe(otherAddress);
      expect(
        screen.getByRole("heading", { level: 1, name: /Demo Sample/ }),
      ).toBeInTheDocument();
      expect(
        screen.queryByRole("heading", { level: 1, name: /Triage/ }),
      ).toBeNull();
      expect(screen.queryByRole("alert")).toBeNull();
    },
  );

  /** The control pressed goes with the act it offered, so the keyboard is kept in the part that asked. */
  it("opens a draft just made, the newest, keeping the keyboard in the versions", async () => {
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

  it("keeps the keyboard in the version where the act pressed is no longer offered", async () => {
    opening(PAGE, {
      [`GET ${ENTRY}`]: [reply(DRAFTED)],
      [`PUT ${ENTRY}/versions/${DRAFT.versionId}/submission`]: [
        reply({ ...DRAFTED, versions: [SUBMITTED, IN_SERVICE, SEEDED] }),
      ],
    });
    await opened();

    await userEvent.click(
      within(openVersionRegion()).getByRole("button", { name: "Submit" }),
    );

    await waitFor(() => expect(actsDrawn()).toEqual(["Withdraw", "Approve"]));
    expect(document.activeElement).toBe(
      screen.getByRole("heading", { name: "Version 3" }),
    );
  });

  it("cancels retiring a version without sending a request", async () => {
    const { sent } = opening(PAGE);
    await opened();
    await userEvent.click(
      within(openVersionRegion()).getByRole("button", { name: "Retire" }),
    );
    expect(
      screen.getByRole("dialog", { name: "Retire Version 2?" }),
    ).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "Cancel" }));
    await waitFor(() =>
      expect(
        screen.queryByRole("dialog", { name: "Retire Version 2?" }),
      ).toBeNull(),
    );
    expect(
      requestsTo(sent).filter((each) => each.includes("/retirement")),
    ).toEqual([]);
  });

  /** What moved under the page is read again, so no control is left offering what was just refused. */
  it("says a refusal for what moved beside the control that asked, and reads the entry again", async () => {
    const { sent, readAgain } = opening(PAGE, {
      [`GET ${ENTRY}`]: [
        reply(TRIAGE),
        reply({
          ...TRIAGE,
          versions: [{ ...IN_SERVICE, stateActs: [] }, SEEDED],
        }),
      ],
      [`PUT ${ENTRY}/versions/${IN_SERVICE.versionId}/retirement`]: [
        refusal("VERSION_STANDING_REFUSES", 409),
      ],
    });
    await opened();

    await confirmRetire();
    await waitFor(() =>
      expect(
        screen.queryByRole("dialog", { name: "Retire Version 2?" }),
      ).toBeNull(),
    );

    expect(
      await within(openVersionRegion()).findByRole("alert"),
    ).toHaveTextContent("That version's standing does not admit this.");
    await waitFor(() => expect(entryReads(sent)).toBe(2));
    await waitFor(() => expect(actsDrawn()).toEqual([]));
    expect(readAgain).not.toHaveBeenCalled();
  });

  /** Read again, the entry no longer offers the act refused, so the control pressed is gone with it. */
  it("keeps the keyboard in the version where a refused act is read again out of being offered", async () => {
    opening(PAGE, {
      [`GET ${ENTRY}`]: [
        reply(TRIAGE),
        reply({
          ...TRIAGE,
          versions: [{ ...IN_SERVICE, stateActs: [] }, SEEDED],
        }),
      ],
      [`PUT ${ENTRY}/versions/${IN_SERVICE.versionId}/retirement`]: [
        refusal("VERSION_STANDING_REFUSES", 409),
      ],
    });
    await opened();

    await confirmRetire();

    await waitFor(() => expect(actsDrawn()).toEqual([]));
    expect(document.activeElement).toBe(
      screen.getByRole("heading", { name: "Version 2" }),
    );
  });

  /** The last act goes away, and the keyboard stays with the version that offered it. */
  it("puts the keyboard on the version's heading where the act pressed leaves nothing to be done", async () => {
    opening(PAGE, {
      [`PUT ${ENTRY}/versions/${IN_SERVICE.versionId}/retirement`]: [
        reply({
          ...TRIAGE,
          versions: [
            { ...IN_SERVICE, standing: "retired", stateActs: [] },
            SEEDED,
          ],
        }),
      ],
    });
    await opened();

    await confirmRetire();

    await waitFor(() => expect(actsDrawn()).toEqual([]));
    expect(document.activeElement).toBe(
      screen.getByRole("heading", { name: "Version 2" }),
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
    "names each pin retired since within the version, with what replaces it, on %s",
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
                  versionName: null,
                  versionId: "00000007-0000-4000-8000-000000000b61",
                  number: 1,
                },
                newestInService: {
                  versionName: null,
                  versionId: "00000007-0000-4000-8000-000000000b62",
                  number: 3,
                },
              },
              {
                entryId: "00000006-0000-4000-8000-000000000b63",
                kind: "question",
                name: "Sort",
                pinned: {
                  versionName: "Spring sort",
                  versionId: "00000007-0000-4000-8000-000000000b63",
                  number: 2,
                },
              },
              {
                entryId: "00000006-0000-4000-8000-000000000b64",
                kind: "unlisted_kind",
                name: "Guide",
                pinned: {
                  versionName: null,
                  versionId: "00000007-0000-4000-8000-000000000b64",
                  number: 1,
                },
                newestInService: {
                  versionName: null,
                  versionId: "00000007-0000-4000-8000-000000000b65",
                  number: 3,
                },
              },
            ],
          }),
        ],
      });
      await opened();

      await userEvent.click(
        within(openVersionRegion()).getByRole("button", { name: control }),
      );

      const pins = await within(openVersionRegion()).findByRole("list", {
        name: "Pinned, and retired since",
      });
      expect(
        within(pins)
          .getAllByRole("listitem")
          .map((each) => each.textContent),
      ).toEqual([
        `Reference listRegions \u00b7 Version 1 is retired. Version 3 is in service. Open Regions \u00b7 Version 3`,
        `QuestionSort \u00b7 Version 2 \u00b7 Spring sort is retired, and no version of it is in service.`,
        `unlisted_kindGuide \u00b7 Version 1 is retired. Version 3 is in service. Open Guide \u00b7 Version 3`,
      ]);
      const [replaceable, unreplaceable, unknown] =
        within(pins).getAllByRole("listitem");
      for (const [item, kind, segments] of [
        [
          replaceable,
          "Reference list",
          [
            [`Regions \u00b7 Version 1`, false],
            [" is retired. Version 3 is in service.", true],
          ],
        ],
        [
          unreplaceable,
          "Question",
          [
            [`Sort \u00b7 Version 2 \u00b7 Spring sort`, false],
            [" is retired, and no version of it is in service.", true],
          ],
        ],
      ] as const) {
        const [tag, sentence, ...rest] = Array.from(item!.children);
        expect(rest).toEqual([]);
        expect(tag!.textContent).toBe(kind);
        expect(item!.textContent).not.toContain("(");
        expect(getComputedStyle(tag!).fontStyle).not.toBe("italic");
        expect(
          Array.from(sentence!.children)
            .filter((child) => child.tagName === "SPAN")
            .map((child) => [
              child.textContent,
              getComputedStyle(child).fontStyle === "italic",
            ]),
        ).toEqual(segments);
      }
      const [entryName, versionName] = unreplaceable!.querySelectorAll("bdi");
      expect(entryName!.textContent).toBe("Sort");
      expect(versionName!.textContent).toBe("Spring sort");
      expect(getComputedStyle(versionName!).color).toBe("rgb(88, 102, 108)");
      expect(
        replaceable!.children[1]!.children[0]!.querySelector("bdi ~ bdi"),
      ).toBeNull();
      expect(within(pins).getByRole("link")).toHaveAccessibleName(
        `Open ${setApart("Regions")} \u00b7 Version 3`,
      );
      expect(within(pins).getByRole("link").getAttribute("href")).toBe(
        `/groups/${KEY}/reference-lists/00000006-0000-4000-8000-000000000b61?version=00000007-0000-4000-8000-000000000b62`,
      );
      expect(unknown!.children[0]!.textContent).toBe("unlisted_kind");
      expect(within(unknown!).queryByRole("link")).toBeNull();
      expect(unknown!.querySelector("a[href]")).toBeNull();
      expect(
        requestsTo(sent).filter((each) => each.startsWith("PUT ")),
      ).toEqual([`PUT ${ENTRY}/versions/${DRAFT.versionId}/${part}`]);
      expect(openVersionRegion()).toHaveTextContent(way);
      expect(within(openVersionRegion()).getByRole("alert")).toHaveTextContent(
        "This version pins a version retired since.",
      );
      expect(entryReads(sent)).toBe(1);
    },
  );

  /**
   * Each place in the order the server named it: the part, and a field found by its key in the version as the
   * page read it, named down to it, with its place where a fellow shares its name.
   */
  it("names every place a submission is refused for within the version, each where it is", async () => {
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
      within(openVersionRegion()).getByRole("button", { name: "Submit" }),
    );

    const places = await within(openVersionRegion()).findByRole("list", {
      name: "What does not hold yet",
    });
    const items = within(places).getAllByRole("listitem");
    const expected = [
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
    ];
    expect(items.map((each) => each.getAttribute("aria-label"))).toEqual(
      expected,
    );
    expect(items.map((each) => each.textContent)).toEqual(
      expected.map((each) =>
        each
          .replaceAll(String.fromCodePoint(0x2068), "")
          .replaceAll(String.fromCodePoint(0x2069), ""),
      ),
    );
    expect(
      items.map((each) =>
        [...each.querySelectorAll("bdi")].map((data) => data.textContent),
      ),
    ).toEqual([
      [],
      ["details", "product"],
      ["details", "product"],
      ["sender"],
      [],
      ["a_part_not_worded"],
      [],
      [],
      [],
      [],
      [],
    ]);
    expect(within(openVersionRegion()).getByRole("alert")).toHaveTextContent(
      "Some of what this version holds does not hold yet. Each place is named.",
    );
    expect(
      within(openVersionRegion()).queryByRole("list", {
        name: "Pinned, and retired since",
      }),
    ).toBeNull();
    expect(entryReads(sent)).toBe(1);
  });

  it("shows a workflow submission refusal in place of an older read report, once", async () => {
    const workflows = `/api/groups/${GROUP}/workflows/${ENTRY_ID}`;
    const problems = [{ code: "runs_missing", part: "steps", stepId: "s9" }];
    libraryLaidOutAt(1400, 1400);
    opening(
      `/groups/${KEY}/workflows/${ENTRY_ID}`,
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
              codeSteps: [],
              searchWays: [],
              models: [],
            },
            terms: {},
          }),
        ],
        [`PUT ${workflows}/versions/${DRAFT.versionId}/submission`]: [
          refusal("VERSION_CONTENT_DOES_NOT_HOLD", 409, {
            problems: [{ code: "no_steps", part: "steps" }],
            problemTotal: 4,
          }),
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
      within(openVersionRegion()).getByRole("button", { name: "Submit" }),
    );

    expect(
      await within(openVersionRegion()).findByRole("alert"),
    ).toHaveTextContent(
      "Some of what this version holds does not hold yet. Each place is named.",
    );
    expect(
      screen.getAllByRole("list", { name: "What does not hold yet" }),
    ).toHaveLength(1);
    expect(
      within(theVersion(3)).getByRole("list", {
        name: "What does not hold yet",
      }),
    ).toHaveTextContent(
      "The steps: It holds no step, so it could give nothing back and act on nothing.",
    );
    expect(
      screen.getByText("3 more things do not hold and are not listed."),
    ).toBeVisible();
    expect(
      screen.queryByText(/It says nothing yet of what it runs/),
    ).not.toBeInTheDocument();
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
                versionName: null,
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
      within(openVersionRegion()).getByRole("button", { name: "Submit" }),
    );

    expect(
      await within(openVersionRegion()).findByRole("list", {
        name: "What does not hold yet",
      }),
    ).toHaveTextContent(
      "The instruction: It tells whoever answers it nothing yet.",
    );
    expect(
      within(
        within(openVersionRegion()).getByRole("list", {
          name: "Pinned, and retired since",
        }),
      )
        .getAllByRole("listitem")
        .map((each) => each.textContent),
    ).toEqual([
      `Reference listRegions \u00b7 Version 1 is retired, and no version of it is in service.`,
    ]);
  });

  it("says how many more problems a refused submission counted after its visible places", async () => {
    opening(PAGE, {
      [`GET ${ENTRY}`]: [reply(DRAFTED)],
      [`PUT ${ENTRY}/versions/${DRAFT.versionId}/submission`]: [
        refusal("VERSION_CONTENT_DOES_NOT_HOLD", 409, {
          problems: [{ code: "instruction_missing", part: "instruction" }],
          problemTotal: 2,
        }),
      ],
    });
    await opened();
    await userEvent.click(
      within(openVersionRegion()).getByRole("button", { name: "Submit" }),
    );

    expect(
      await screen.findByText("1 more thing does not hold and is not listed."),
    ).toBeVisible();
    expect(
      within(openVersionRegion()).getByRole("list", {
        name: "What does not hold yet",
      }),
    ).toHaveTextContent(
      "The instruction: It tells whoever answers it nothing yet.",
    );
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
      within(openVersionRegion()).getByRole("button", { name: "Submit" }),
    );
    await within(openVersionRegion()).findByRole("list", {
      name: "What does not hold yet",
    });

    editVersionProse(3, "Edit the instruction");
    const box = within(theVersion(3)).getByRole("textbox", {
      name: "The instruction",
    });
    await act(async () => box.focus());
    box.textContent = "Say which.";
    const caret = document.createRange();
    caret.selectNodeContents(box);
    caret.collapse(false);
    window.getSelection()!.removeAllRanges();
    window.getSelection()!.addRange(caret);
    fireEvent.input(box);
    await userEvent.click(
      within(theVersion(3)).getByRole("button", {
        name: "Save the instruction",
      }),
    );

    await waitFor(() =>
      expect(
        within(openVersionRegion()).queryByRole("list", {
          name: "What does not hold yet",
        }),
      ).toBeNull(),
    );
    expect(within(openVersionRegion()).queryByRole("alert")).toBeNull();
    await waitFor(() => expect(entryReads(sent)).toBe(2));
  });

  it("reads the reader's standing again where the entry is refused for a group they are no longer in", async () => {
    const { readAgain } = opening(PAGE, {
      [`GET ${ENTRY}`]: [refusal("GROUP_NOT_IN_VIEW", 404)],
    });

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "That group is not in view.",
    );
    // Reread from a passive effect, which React may run in a task after the commit that drew the alert.
    await waitFor(() => expect(readAgain).toHaveBeenCalledTimes(1));
    // Unmounting flushes the passive effects of commits already made, so a further reread is counted too.
    cleanup();
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
    // Unmounting flushes any passive effect still pending; before it, a reread may just not have run yet.
    cleanup();
    expect(readAgain).not.toHaveBeenCalled();
  });

  /** Made from a page now left, the control that made it is gone with that page. */
  it("puts the keyboard on the entry's heading where it was just made", async () => {
    opening(PAGE, {}, JUST_MADE);
    const heading = await opened();

    await waitFor(() => expect(document.activeElement).toBe(heading));
  });

  it("leaves the keyboard alone on an entry opened plainly", async () => {
    opening(PAGE);

    await opened();

    expect(document.activeElement).toBe(document.body);
  });
});
