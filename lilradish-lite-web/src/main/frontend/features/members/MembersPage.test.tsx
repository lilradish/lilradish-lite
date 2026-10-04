import { ThemeProvider } from "@mui/material/styles";
import {
  act,
  cleanup,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import { userEvent } from "@testing-library/user-event";
import { RouterProvider, createMemoryRouter } from "react-router";
import { describe, expect, it, vi } from "vitest";

import { StandingProvider } from "../../app/standing/StandingContext";
import { theme } from "../../lib/theme/theme";
import { requestsTo, serving, type Reply } from "../../testutil/answering";
import { firstSight } from "../../testutil/firstSight";
import { appearancesOf } from "../../testutil/heard";
import { laidOutAt } from "../../testutil/layout";
import { answered, inGroup } from "../../testutil/standingRead";
import { deferred } from "../../testutil/deferred";
import { MembersPage } from "./MembersPage";

/** Room for the list and the panel beside it, and too little for both. */
const WIDE = 1280;
const NARROW = 700;

const VIEWPORT = 1920;

const GROUP = "00000003-0000-4000-8000-0000000009e1";

const OTHER_GROUP = "00000003-0000-4000-8000-0000000009e4";

const OTHER_LIST = `/api/groups/${OTHER_GROUP}/members`;

const PAGE = `/groups/${GROUP}/members`;

const LIST = `/api/groups/${GROUP}/members`;

const CURRENCY = `/api/groups/${GROUP}/currency`;

const SEES = ["read_membership", "start_run"];

const CHANGES = [...SEES, "change_membership"];

const AVO = {
  subjectId: "00000002-0000-4000-8000-0000000009e1",
  userId: "0009e1",
  displayName: "Avo Lumerace",
};

const GLINN = {
  subjectId: "00000002-0000-4000-8000-0000000009e2",
  userId: "0009e2",
  displayName: "Glinn Havven",
};

const OLIVE = {
  subjectId: "00000002-0000-4000-8000-0000000009e3",
  userId: "0009e3",
  displayName: "Olive Out",
};

type Someone = typeof AVO;

function memberAt(person: Someone): string {
  return `${LIST}/${person.subjectId}`;
}

function listOf(
  ...rows: (readonly [Someone, string[]])[]
): readonly [string, number] {
  return [
    JSON.stringify({
      items: rows.map(([person, roles]) => ({ ...person, roles })),
    }),
    200,
  ];
}

function panelOf(
  person: Someone,
  roles: string[],
  lastChangingRoles: string[] = [],
  removable = lastChangingRoles.length === 0,
): readonly [string, number] {
  return [
    JSON.stringify({ ...person, roles, lastChangingRoles, removable }),
    200,
  ];
}

const OTHER_READS = {
  [`GET ${OTHER_LIST}`]: [listOf([GLINN, ["owner"]])],
  [`GET ${OTHER_LIST}/${GLINN.subjectId}`]: [
    panelOf(GLINN, ["owner"], ["owner"], false),
  ],
  [`GET /api/groups/${OTHER_GROUP}/currency`]: [
    [JSON.stringify({ chosen: "EUR", offered: ["EUR", "JPY"] }), 200],
  ],
} as const satisfies Record<string, readonly Reply[]>;

const AVO_AND_GLINN = listOf([AVO, ["owner"]], [GLINN, ["operator"]]);

/** Words set apart inside a sentence, bracketed by the isolate controls written as code points. */
function setApart(words: string): string {
  return `${String.fromCodePoint(0x2068)}${words}${String.fromCodePoint(0x2069)}`;
}

interface Opening {
  readonly permissions?: readonly string[];
  readonly routes?: Readonly<Record<string, readonly Reply[]>>;
  readonly width?: number;
  readonly otherGroup?: boolean;
}

/**
 * The page at an address, under a real router whose history the test reads,
 * with the standing it would be handed — whose reading again the test counts —
 * and only the server stood in for.
 */
function opening(
  address: string,
  {
    permissions = CHANGES,
    routes = {},
    width = WIDE,
    otherGroup = false,
  }: Opening = {},
) {
  laidOutAt(width, VIEWPORT);
  const sent = serving({
    [`GET ${CURRENCY}`]: [
      permissions.includes("change_membership")
        ? [JSON.stringify({ chosen: "EUR", offered: ["EUR", "JPY"] }), 200]
        : [JSON.stringify({ chosen: "EUR" }), 200],
    ],
    [`GET ${LIST}`]: [AVO_AND_GLINN],
    [`GET ${memberAt(AVO)}`]: [panelOf(AVO, ["owner"], ["owner"])],
    [`GET ${memberAt(GLINN)}`]: [panelOf(GLINN, ["operator"])],
    ...routes,
  });
  const router = createMemoryRouter(
    [
      {
        path: "/groups/:groupId/members/:subjectId?",
        element: <MembersPage />,
      },
    ],
    { initialEntries: [address] },
  );
  const readAgain = vi.fn();
  const standing = answered(
    [],
    [
      inGroup(GROUP, "PAYROLL", "Payroll", permissions),
      ...(otherGroup
        ? [inGroup(OTHER_GROUP, "SAMPLE", "Demo Sample", permissions)]
        : []),
    ],
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
    reached: () => router.state.historyAction,
    async go(path: string) {
      await act(async () => router.navigate(path));
    },
  };
}

/** Every request about the members, leaving out the currency's, which is read on its own. */
function membershipAsked(sent: ReturnType<typeof serving>): string[] {
  return requestsTo(sent).filter((each) => !each.startsWith(`GET ${CURRENCY}`));
}

/** The user numbers listed, once the first page has landed. */
async function listed(): Promise<string[]> {
  await screen.findByRole("link", { name: "0009e1" });
  const table = screen.getByRole("table", { name: "Members of the group" });
  const [, ...rows] = within(table).getAllByRole<HTMLTableRowElement>("row");
  return rows.map((each) => each.cells[0]!.textContent ?? "");
}

function panel(): HTMLElement {
  return screen.getByRole("region", {
    name: /^(Avo Lumerace|Glinn Havven|Olive Out|Member)$/,
  });
}

/** What the page's own line holds, whatever above it hides it. */
function said(): string {
  const table = screen.getByRole("table", {
    name: "Members of the group",
    hidden: true,
  });
  const [filter] = screen.getAllByRole("searchbox", {
    name: "User number or name",
    hidden: true,
  });
  return (
    screen
      .getAllByRole("status", { hidden: true })
      .find(
        (each) =>
          (filter!.compareDocumentPosition(each) &
            Node.DOCUMENT_POSITION_FOLLOWING) !==
            0 &&
          !table.parentElement!.contains(each) &&
          each.closest('[role="dialog"]') === null,
      )?.textContent ?? ""
  );
}

/** What every status a screen reader can reach holds: none that anything above it hides. */
function heardLines(): string[] {
  return screen.queryAllByRole("status").map((each) => each.textContent ?? "");
}

/** Waited for: a line goes in a frame or two after what hid it has gone. */
async function heard(words: string) {
  await waitFor(() => expect(heardLines()).toContain(words));
}

const GLINN_OUT = `${setApart("Glinn Havven")} is no longer in this group.`;

const OLIVE_IN = `${setApart("Olive Out")} is in this group now.`;

async function bringingInOlive() {
  await userEvent.click(
    screen.getByRole("button", { name: "Bring somebody in" }),
  );
  await userEvent.type(
    screen.getByRole("searchbox", { name: "User number or name" }),
    "ol",
  );
  await userEvent.click(
    await screen.findByRole("radio", {}, { timeout: 2000 }),
  );
  await userEvent.click(screen.getByRole("checkbox", { name: "Overseer" }));
  await userEvent.click(screen.getByRole("button", { name: "Take them on" }));
}

/** The search finding Olive, and taking her on answered as the group then holds her. */
const TAKING_OLIVE_ON = {
  [`GET /api/groups/${GROUP}/pool/search`]: [
    [JSON.stringify({ items: [OLIVE], more: false }), 200],
  ],
  [`POST ${LIST}`]: [[panelOf(OLIVE, ["overseer"])[0], 201]],
} as const satisfies Record<string, readonly Reply[]>;

describe("MembersPage", () => {
  /** A page read on its own says which membership it is. */
  it("names the group in its title, and lists every member with every role they hold", async () => {
    opening(PAGE);

    expect(
      screen.getByRole("heading", {
        level: 1,
        name: `Members of ${setApart("Payroll")}`,
      }),
    ).toBeInTheDocument();
    expect(await listed()).toEqual(["0009e1", "0009e2"]);
  });

  it.each([
    ["draws", CHANGES, 1],
    ["does not draw", SEES, 0],
  ])(
    "%s the control bringing somebody in, as the reader may change the membership or not",
    async (_case, permissions, drawn) => {
      opening(PAGE, { permissions });

      await listed();

      expect(
        screen.queryAllByRole("button", { name: "Bring somebody in" }),
      ).toHaveLength(drawn);
    },
  );

  it.each([
    ["may change", CHANGES, 1],
    ["may only see", SEES, 0],
  ])(
    "draws the currency under the title, and Change only where the reader %s",
    async (_case, permissions, changes) => {
      opening(PAGE, { permissions });
      await listed();

      const title = screen.getByRole("heading", { level: 1 });
      const summary = await screen.findByText("Currency: EUR — Euro");
      const filter = screen.getByRole("searchbox", {
        name: "User number or name",
      });

      expect(
        title.compareDocumentPosition(summary) &
          Node.DOCUMENT_POSITION_FOLLOWING,
      ).not.toBe(0);
      expect(
        summary.compareDocumentPosition(filter) &
          Node.DOCUMENT_POSITION_FOLLOWING,
      ).not.toBe(0);
      expect(screen.queryAllByRole("button", { name: "Change" })).toHaveLength(
        changes,
      );
      expect(screen.queryByRole("combobox", { name: "Currency" })).toBeNull();
      if (changes === 1) {
        await userEvent.click(screen.getByRole("button", { name: "Change" }));
        expect(
          screen.getByRole("combobox", { name: "Currency" }),
        ).toBeInTheDocument();
      }
    },
  );

  it("says in the page's own line the currency just chosen", async () => {
    opening(PAGE, {
      routes: {
        [`PUT ${CURRENCY}`]: [
          [JSON.stringify({ chosen: "JPY", offered: ["EUR", "JPY"] }), 200],
        ],
      },
    });
    await listed();

    await userEvent.click(
      await screen.findByRole("button", { name: "Change" }),
    );
    await userEvent.click(screen.getByRole("combobox", { name: "Currency" }));
    await userEvent.click(
      await screen.findByRole("option", { name: "JPY — Japanese Yen" }),
    );

    await heard("This group's costs are read in JPY — Japanese Yen now.");
    expect(said()).toBe(
      "This group's costs are read in JPY — Japanese Yen now.",
    );
    expect(screen.queryByRole("alert")).toBeNull();
  });

  it("narrows the members as the filter is typed, asking the group's list for what was typed", async () => {
    const { sent } = opening(`${PAGE}?filter=glinn`, {
      routes: { [`GET ${LIST}`]: [listOf([GLINN, ["operator"]])] },
    });

    await screen.findByRole("link", { name: "0009e2" });

    expect(membershipAsked(sent)).toEqual([
      `GET ${LIST}?sort=userId&filter=glinn`,
    ]);
  });

  /** The row picked is part of the address, so a link reopens the list with it picked. */
  it("opens the member the address names beside the list, with the roles they hold", async () => {
    opening(`${PAGE}/${GLINN.subjectId}`);

    const roles = await screen.findByRole("heading", {
      level: 3,
      name: "Roles",
    });

    expect(roles).toBeInTheDocument();
    expect(panel()).toHaveAccessibleName("Glinn Havven");
    expect(
      within(panel()).getByRole("switch", { name: "Overseer" }),
    ).toBeInTheDocument();
  });

  /** Read again, list and standing, since a change to the reader's own roles moves what they may do. */
  it("gives a role, showing the member as the group now holds them and reading the list and the standing again", async () => {
    const { sent, readAgain } = opening(`${PAGE}/${GLINN.subjectId}`, {
      routes: {
        [`PUT ${memberAt(GLINN)}/roles/overseer`]: [
          panelOf(GLINN, ["operator", "overseer"]),
        ],
      },
    });
    await userEvent.click(
      await screen.findByRole("switch", { name: "Overseer" }),
    );

    expect(
      await within(panel()).findByRole("switch", {
        name: "Overseer",
      }),
    ).toBeInTheDocument();
    expect(readAgain).toHaveBeenCalled();
    await waitFor(() =>
      expect(
        requestsTo(sent).filter((each) => each.startsWith(`GET ${LIST}?`)),
      ).toHaveLength(2),
    );
    expect(
      requestsTo(sent).filter((each) => each === `GET ${memberAt(GLINN)}`),
    ).toHaveLength(1);
  });

  it.each(["answer", "refusal"])(
    "keeps the same person's roles in a new group when the former group's change %s arrives late",
    async (ending) => {
      const otherPage = `/groups/${OTHER_GROUP}/members/${GLINN.subjectId}`;
      const { go, at, readAgain } = opening(`${PAGE}/${GLINN.subjectId}`, {
        otherGroup: true,
        routes: OTHER_READS,
      });
      await screen.findByRole("switch", { name: "Overseer" });
      const held = deferred<Response>();
      const ordinaryFetch = globalThis.fetch;
      const sent = vi.fn<typeof fetch>((input, init) =>
        init?.method === "PUT" &&
        new URL(String(input), "http://reader.test").pathname ===
          `${memberAt(GLINN)}/roles/overseer`
          ? held.promise
          : ordinaryFetch(input, init),
      );
      vi.stubGlobal("fetch", sent);

      await userEvent.click(screen.getByRole("switch", { name: "Overseer" }));
      expect(
        sent.mock.calls.some(
          ([input, init]) =>
            init?.method === "PUT" &&
            String(input).includes(`${memberAt(GLINN)}/roles/overseer`),
        ),
      ).toBe(true);
      await go(otherPage);
      await screen.findByRole("heading", { level: 1, name: /Demo Sample/ });
      expect(
        within(panel()).getByRole("switch", { name: "Owner" }),
      ).toBeChecked();

      await act(async () =>
        held.settle(
          new Response(
            ending === "answer"
              ? panelOf(GLINN, ["operator", "overseer"])[0]
              : JSON.stringify({ code: "ACT_NOT_PERMITTED" }),
            {
              status: ending === "answer" ? 200 : 403,
              headers: { "Content-Type": "application/json" },
            },
          ),
        ),
      );

      expect(at()).toBe(otherPage);
      expect(
        within(panel()).getByRole("switch", { name: "Owner" }),
      ).toBeChecked();
      expect(
        within(panel()).getByRole("switch", { name: "Operator" }),
      ).not.toBeChecked();
      expect(
        within(panel()).getByRole("switch", { name: "Overseer" }),
      ).not.toBeChecked();
      expect(screen.queryByRole("alert")).toBeNull();
      expect(readAgain).not.toHaveBeenCalled();
    },
  );

  it("keeps the chosen member while a different member's change answers in the same group", async () => {
    const held = deferred<ReturnType<typeof panelOf>>();
    const { go, at, sent, readAgain } = opening(`${PAGE}/${GLINN.subjectId}`, {
      routes: { [`PUT ${memberAt(GLINN)}/roles/overseer`]: [held.promise] },
    });
    await userEvent.click(
      await screen.findByRole("switch", { name: "Overseer" }),
    );
    expect(requestsTo(sent)).toContain(`PUT ${memberAt(GLINN)}/roles/overseer`);

    await go(`${PAGE}/${AVO.subjectId}`);
    expect(
      await within(panel()).findByRole("switch", { name: "Owner" }),
    ).toBeChecked();
    await act(async () =>
      held.settle(panelOf(GLINN, ["operator", "overseer"])),
    );

    expect(at()).toBe(`${PAGE}/${AVO.subjectId}`);
    expect(
      within(panel()).getByRole("switch", { name: "Owner" }),
    ).toBeChecked();
    expect(
      within(panel()).getByRole("switch", { name: "Overseer" }),
    ).not.toBeChecked();
    expect(readAgain).toHaveBeenCalledOnce();
    await waitFor(() =>
      expect(
        membershipAsked(sent).filter((asked) =>
          asked.startsWith(`GET ${LIST}?`),
        ),
      ).toHaveLength(2),
    );
  });

  it("does not close a member in a new group when their old group's removal answers", async () => {
    const otherPage = `/groups/${OTHER_GROUP}/members/${GLINN.subjectId}`;
    const { go, at, readAgain } = opening(`${PAGE}/${GLINN.subjectId}`, {
      otherGroup: true,
      routes: OTHER_READS,
    });
    await userEvent.click(
      await screen.findByRole("button", { name: "Remove from the group" }),
    );
    const held = deferred<Response>();
    const ordinaryFetch = globalThis.fetch;
    vi.stubGlobal(
      "fetch",
      vi.fn<typeof fetch>((input, init) =>
        init?.method === "DELETE" &&
        new URL(String(input), "http://reader.test").pathname ===
          memberAt(GLINN)
          ? held.promise
          : ordinaryFetch(input, init),
      ),
    );
    await userEvent.click(screen.getByRole("button", { name: "Remove" }));
    await go(otherPage);
    expect(
      await within(panel()).findByRole("switch", { name: "Owner" }),
    ).toBeChecked();

    await act(async () => held.settle(new Response(null, { status: 204 })));

    expect(at()).toBe(otherPage);
    expect(
      within(panel()).getByRole("switch", { name: "Owner" }),
    ).toBeChecked();
    expect(screen.queryByText(GLINN_OUT)).toBeNull();
    expect(readAgain).not.toHaveBeenCalled();
  });

  it("does not bring somebody into the new group when the former group's answer arrives", async () => {
    const otherPage = `/groups/${OTHER_GROUP}/members`;
    const { go, at, readAgain } = opening(PAGE, {
      otherGroup: true,
      routes: { ...OTHER_READS, ...TAKING_OLIVE_ON },
    });
    await listed();
    const held = deferred<Response>();
    const ordinaryFetch = globalThis.fetch;
    vi.stubGlobal(
      "fetch",
      vi.fn<typeof fetch>((input, init) =>
        init?.method === "POST" &&
        new URL(String(input), "http://reader.test").pathname === LIST
          ? held.promise
          : ordinaryFetch(input, init),
      ),
    );
    await bringingInOlive();
    await go(otherPage);
    expect(
      await screen.findByRole("heading", { level: 1, name: /Demo Sample/ }),
    ).toBeInTheDocument();

    await act(async () =>
      held.settle(
        new Response(panelOf(OLIVE, ["overseer"])[0], {
          status: 201,
          headers: { "Content-Type": "application/json" },
        }),
      ),
    );

    expect(at()).toBe(otherPage);
    expect(screen.queryByText(OLIVE_IN)).toBeNull();
    expect(screen.queryByRole("heading", { name: /Olive Out/ })).toBeNull();
    expect(readAgain).not.toHaveBeenCalled();
  });

  /** The last role taken, the panel stays on them, so a role given brings them back without a search. */
  it("keeps the panel on somebody whose last role was just taken, showing them holding nothing", async () => {
    opening(`${PAGE}/${GLINN.subjectId}`, {
      routes: {
        [`DELETE ${memberAt(GLINN)}/roles/operator`]: [panelOf(GLINN, [])],
      },
    });
    await userEvent.click(
      await screen.findByRole("switch", { name: "Operator" }),
    );

    expect(
      await within(panel()).findByText(
        "No longer a member of this group. Giving a role here makes them one again.",
      ),
    ).toBeInTheDocument();
    expect(
      within(panel()).getByRole("switch", { name: "Operator" }),
    ).toBeInTheDocument();
  });

  /** Shut, and replaced rather than pushed: the way back would lead to somebody gone. */
  it("removes a member, shutting the panel and saying they are no longer in the group", async () => {
    const view = opening(`${PAGE}/${GLINN.subjectId}`, {
      routes: { [`DELETE ${memberAt(GLINN)}`]: [["", 204]] },
    });
    await userEvent.click(
      await screen.findByRole("button", { name: "Remove from the group" }),
    );
    await userEvent.click(screen.getByRole("button", { name: "Remove" }));

    expect(await screen.findByText(GLINN_OUT)).toBeInTheDocument();
    expect(view.at()).toBe(PAGE);
    expect(view.reached()).toBe("REPLACE");
    await waitFor(() =>
      expect(
        screen.queryByRole("heading", { level: 3, name: "Roles" }),
      ).toBeNull(),
    );
  });

  /** While the panel covers the list, everything around it is hidden, and a line written then is never heard. */
  it("says somebody is out of the group only once the covering panel they were taken out from has gone", async () => {
    const view = opening(`${PAGE}/${GLINN.subjectId}`, {
      width: NARROW,
      routes: { [`DELETE ${memberAt(GLINN)}`]: [["", 204]] },
    });
    const appearances = appearancesOf(GLINN_OUT);
    const whileLeaving = firstSight(() =>
      view.at() === PAGE
        ? {
            said: said(),
            covering: document.querySelectorAll('[role="dialog"]').length,
          }
        : undefined,
    );
    await userEvent.click(
      await screen.findByRole("button", { name: "Remove from the group" }),
    );
    await userEvent.click(screen.getByRole("button", { name: "Remove" }));

    await heard(GLINN_OUT);

    expect(whileLeaving()).toEqual({ said: "", covering: 2 });
    expect(appearances()).toEqual([{ hidden: false }]);
  });

  /**
   * Their panel covers the list and hides the page's line, so the panel says
   * it; a line said again once the reader shut it would come late and twice.
   */
  it("says somebody brought in over a narrow list is in the group inside the panel that answered it, and never again once it is shut", async () => {
    opening(PAGE, { width: NARROW, routes: TAKING_OLIVE_ON });
    await listed();
    const appearances = appearancesOf(OLIVE_IN);

    await bringingInOlive();
    const covering = await screen.findByRole("dialog", { name: "Olive Out" });
    await heard(OLIVE_IN);
    const whileCovered = {
      said: said(),
      inPanel: within(covering)
        .getAllByRole("status")
        .map((each) => each.textContent)
        .filter((words) => words !== ""),
    };
    await userEvent.click(
      within(covering).getByRole("button", { name: "Close" }),
    );
    await waitFor(() =>
      expect(screen.queryByRole("dialog", { hidden: true })).toBeNull(),
    );

    expect(whileCovered).toEqual({ said: "", inPanel: [OLIVE_IN] });
    expect(screen.queryByRole("dialog")).toBeNull();
    expect(said()).toBe("");
    expect(appearances()).toEqual([{ hidden: false }]);
  });

  /** One element whichever way it points, so the control just pressed still has the keyboard after. */
  it("leaves the keyboard on the control just pressed, which now takes away what it gave", async () => {
    opening(`${PAGE}/${GLINN.subjectId}`, {
      routes: {
        [`PUT ${memberAt(GLINN)}/roles/overseer`]: [
          panelOf(GLINN, ["operator", "overseer"]),
        ],
      },
    });
    await userEvent.click(
      await screen.findByRole("switch", { name: "Overseer" }),
    );

    const taking = await within(panel()).findByRole("switch", {
      name: "Overseer",
    });

    expect(document.activeElement).toBe(taking);
  });

  /**
   * Somebody else moved the group since it was read: the list and the member
   * are read again, so the panel stops offering what was just refused.
   */
  it.each([
    [
      "a role given to somebody taken out meanwhile",
      "Overseer",
      `PUT ${memberAt(GLINN)}/roles/overseer`,
      ['{"code":"MEMBER_NOT_IN_VIEW"}', 404] as const,
      ['{"code":"MEMBER_NOT_IN_VIEW"}', 404] as const,
      "Overseer",
    ],
    [
      "a removal of somebody left the last who may change the membership",
      "Remove from the group",
      `DELETE ${memberAt(GLINN)}`,
      ['{"code":"LAST_MEMBERSHIP_CHANGER"}', 409] as const,
      panelOf(GLINN, ["operator", "owner"], ["owner"]),
      "Remove from the group",
    ],
  ])(
    "reads the list and the member again where %s is refused, and offers it no longer",
    async (_case, control, route, refusal, readAgainAs, gone) => {
      const { sent, readAgain } = opening(`${PAGE}/${GLINN.subjectId}`, {
        routes: {
          [`GET ${memberAt(GLINN)}`]: [
            panelOf(GLINN, ["operator"]),
            readAgainAs,
          ],
          [route]: [refusal],
        },
      });
      await userEvent.click(
        await screen.findByRole(
          _case.startsWith("a role") ? "switch" : "button",
          { name: control },
        ),
      );
      if (control === "Remove from the group") {
        await userEvent.click(screen.getByRole("button", { name: "Remove" }));
      }

      await waitFor(() => {
        if (gone === "Overseer") {
          expect(screen.queryByRole("switch", { name: gone })).toBeNull();
        } else {
          expect(screen.queryByRole("button", { name: gone })).toBeNull();
        }
      });
      expect(membershipAsked(sent)).toEqual([
        `GET ${LIST}?sort=userId`,
        `GET ${memberAt(GLINN)}`,
        route,
        `GET ${LIST}?sort=userId`,
        `GET ${memberAt(GLINN)}`,
      ]);
      expect(readAgain).not.toHaveBeenCalled();
    },
  );

  /** The person taken on is picked, and shown as the change answered rather than read again. */
  it("opens the member just brought in, as the change answered, saying they are in the group now", async () => {
    const view = opening(PAGE, { routes: TAKING_OLIVE_ON });
    await listed();

    await bringingInOlive();

    expect(await screen.findByText(OLIVE_IN)).toBeInTheDocument();
    expect(view.at()).toBe(`${PAGE}/${OLIVE.subjectId}`);
    expect(
      await within(panel()).findByRole("switch", {
        name: "Overseer",
      }),
    ).toBeInTheDocument();
    cleanup();
    expect(requestsTo(view.sent)).not.toContain(`GET ${memberAt(OLIVE)}`);
  });

  /** The frame is still drawing a group the reader has left, and reading the standing again moves it. */
  it.each([
    ["the group is none of the reader's", '{"code":"GROUP_NOT_IN_VIEW"}', 404],
    [
      "the reader may no longer see the members",
      '{"code":"ACT_NOT_PERMITTED"}',
      403,
    ],
  ])(
    "reads the standing again where the list is refused because %s",
    async (_case, body, status) => {
      const { readAgain } = opening(PAGE, {
        routes: { [`GET ${LIST}`]: [[body, status]] },
      });

      await screen.findByRole("alert");

      await waitFor(() => expect(readAgain).toHaveBeenCalled());
    },
  );

  it("reads nothing again for a refusal that says nothing of the reader", async () => {
    const { readAgain } = opening(`${PAGE}/${OLIVE.subjectId}`, {
      routes: {
        [`GET ${memberAt(OLIVE)}`]: [['{"code":"MEMBER_NOT_IN_VIEW"}', 404]],
      },
    });

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "That person is not a member of this group.",
    );
    await listed();
    cleanup();
    expect(readAgain).not.toHaveBeenCalled();
  });

  /** Refused beside the control while its member is in view, and the standing read again. */
  it("says a change refused for the reader's standing beside the control, and reads the standing again", async () => {
    const { readAgain } = opening(`${PAGE}/${GLINN.subjectId}`, {
      routes: {
        [`PUT ${memberAt(GLINN)}/roles/owner`]: [
          ['{"code":"ACT_NOT_PERMITTED"}', 403],
        ],
      },
    });
    await userEvent.click(await screen.findByRole("switch", { name: "Owner" }));

    expect(await within(panel()).findByRole("alert")).toHaveTextContent(
      "A group's membership is changed only by a role in it that may change it.",
    );
    expect(readAgain).toHaveBeenCalled();
  });

  /** Once the reader has moved on from them, what was not done is said in the page's own words. */
  it("says a change refused for a member no longer picked in the page's own words", async () => {
    let release!: () => void;
    const held = new Promise<readonly [string, number]>((settle) => {
      release = () => settle(['{"code":"LAST_MEMBERSHIP_CHANGER"}', 409]);
    });
    const view = opening(`${PAGE}/${GLINN.subjectId}`, {
      routes: { [`DELETE ${memberAt(GLINN)}`]: [held] },
    });
    await userEvent.click(
      await screen.findByRole("button", { name: "Remove from the group" }),
    );
    await userEvent.click(screen.getByRole("button", { name: "Remove" }));
    await view.go(`${PAGE}/${AVO.subjectId}`);
    await within(panel()).findByRole("heading", { level: 3, name: "Roles" });

    await act(async () => release());

    expect(
      await screen.findByText(
        `${setApart("Glinn Havven")} is still in this group: Nobody else in this group could change its membership.`,
      ),
    ).toBeInTheDocument();
    expect(view.at()).toBe(`${PAGE}/${AVO.subjectId}`);
  });
});
