import { ThemeProvider } from "@mui/material/styles";
import { act, render, screen, waitFor, within } from "@testing-library/react";
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
import { MembersPage } from "./MembersPage";

/** Room for the list and the panel beside it, and too little for both. */
const WIDE = 1280;
const NARROW = 700;

const VIEWPORT = 1920;

const GROUP = "00000003-0000-4000-8000-0000000009e1";

const PAGE = `/groups/${GROUP}/members`;

const LIST = `/api/groups/${GROUP}/members`;

const CURRENCY = `/api/groups/${GROUP}/currency`;

const SEES = ["read_membership", "start_run"];

const CHANGES = [...SEES, "change_membership"];

const ADA = {
  subjectId: "00000002-0000-4000-8000-0000000009e1",
  userId: "0009e1",
  displayName: "Ada Lovelace",
};

const GRACE = {
  subjectId: "00000002-0000-4000-8000-0000000009e2",
  userId: "0009e2",
  displayName: "Grace Hopper",
};

const OLIVE = {
  subjectId: "00000002-0000-4000-8000-0000000009e3",
  userId: "0009e3",
  displayName: "Olive Out",
};

type Someone = typeof ADA;

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

const ADA_AND_GRACE = listOf([ADA, ["owner"]], [GRACE, ["operator"]]);

/** Words set apart inside a sentence, bracketed by the isolate controls written as code points. */
function setApart(words: string): string {
  return `${String.fromCodePoint(0x2068)}${words}${String.fromCodePoint(0x2069)}`;
}

interface Opening {
  readonly permissions?: readonly string[];
  readonly routes?: Readonly<Record<string, readonly Reply[]>>;
  readonly width?: number;
}

/**
 * The page at an address, under a real router whose history the test reads,
 * with the standing it would be handed — whose reading again the test counts —
 * and only the server stood in for.
 */
function opening(
  address: string,
  { permissions = CHANGES, routes = {}, width = WIDE }: Opening = {},
) {
  laidOutAt(width, VIEWPORT);
  const sent = serving({
    [`GET ${CURRENCY}`]: [
      permissions.includes("change_membership")
        ? [JSON.stringify({ chosen: "EUR", offered: ["EUR", "JPY"] }), 200]
        : [JSON.stringify({ chosen: "EUR" }), 200],
    ],
    [`GET ${LIST}`]: [ADA_AND_GRACE],
    [`GET ${memberAt(ADA)}`]: [panelOf(ADA, ["owner"], ["owner"])],
    [`GET ${memberAt(GRACE)}`]: [panelOf(GRACE, ["operator"])],
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
    [inGroup(GROUP, "PAYROLL", "Payroll", permissions)],
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
    name: /^(Ada Lovelace|Grace Hopper|Olive Out|Member)$/,
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

/** A covering panel's leaving, waited out in real time. */
async function settled() {
  await act(async () => {
    await new Promise((resolve) =>
      setTimeout(resolve, theme.transitions.duration.leavingScreen * 2),
    );
  });
}

const GRACE_OUT = `${setApart("Grace Hopper")} is no longer in this group.`;

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
    ["a choice of currency", CHANGES, 1, 0],
    ["the currency in words", SEES, 0, 1],
  ])(
    "draws %s level with the title, as the reader may change the membership or not",
    async (_case, permissions, choices, words) => {
      opening(PAGE, { permissions });
      await listed();

      const titleRow = screen.getByRole("heading", { level: 1 }).parentElement!;

      expect(
        await within(titleRow).findAllByText(/^(Currency: )?EUR — Euro$/),
      ).toHaveLength(1);
      expect(
        within(titleRow).queryAllByRole("combobox", { name: "Currency" }),
      ).toHaveLength(choices);
      expect(
        within(titleRow).queryAllByText("Currency: EUR — Euro"),
      ).toHaveLength(words);
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
      await screen.findByRole("combobox", { name: "Currency" }),
    );
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
    const { sent } = opening(`${PAGE}?filter=grace`, {
      routes: { [`GET ${LIST}`]: [listOf([GRACE, ["operator"]])] },
    });

    await screen.findByRole("link", { name: "0009e2" });

    expect(membershipAsked(sent)).toEqual([
      `GET ${LIST}?sort=userId&filter=grace`,
    ]);
  });

  /** The row picked is part of the address, so a link reopens the list with it picked. */
  it("opens the member the address names beside the list, with the roles they hold", async () => {
    opening(`${PAGE}/${GRACE.subjectId}`);

    const roles = await screen.findByRole("heading", {
      level: 3,
      name: "Roles",
    });

    expect(roles).toBeInTheDocument();
    expect(panel()).toHaveAccessibleName("Grace Hopper");
    expect(
      within(panel()).getByRole("button", { name: "Give Overseer" }),
    ).toBeInTheDocument();
  });

  /** Read again, list and standing, since a change to the reader's own roles moves what they may do. */
  it("gives a role, showing the member as the group now holds them and reading the list and the standing again", async () => {
    const { sent, readAgain } = opening(`${PAGE}/${GRACE.subjectId}`, {
      routes: {
        [`PUT ${memberAt(GRACE)}/roles/overseer`]: [
          panelOf(GRACE, ["operator", "overseer"]),
        ],
      },
    });
    await userEvent.click(
      await screen.findByRole("button", { name: "Give Overseer" }),
    );

    expect(
      await within(panel()).findByRole("button", {
        name: "Take Overseer away",
      }),
    ).toBeInTheDocument();
    expect(readAgain).toHaveBeenCalled();
    await waitFor(() =>
      expect(
        requestsTo(sent).filter((each) => each.startsWith(`GET ${LIST}?`)),
      ).toHaveLength(2),
    );
    expect(
      requestsTo(sent).filter((each) => each === `GET ${memberAt(GRACE)}`),
    ).toHaveLength(1);
  });

  /** The last role taken, the panel stays on them, so a role given brings them back without a search. */
  it("keeps the panel on somebody whose last role was just taken, showing them holding nothing", async () => {
    opening(`${PAGE}/${GRACE.subjectId}`, {
      routes: {
        [`DELETE ${memberAt(GRACE)}/roles/operator`]: [panelOf(GRACE, [])],
      },
    });
    await userEvent.click(
      await screen.findByRole("button", { name: "Take Operator away" }),
    );

    expect(
      await within(panel()).findByText(
        "No longer a member of this group. Giving a role here makes them one again.",
      ),
    ).toBeInTheDocument();
    expect(
      within(panel()).getByRole("button", { name: "Give Operator" }),
    ).toBeInTheDocument();
  });

  /** Shut, and replaced rather than pushed: the way back would lead to somebody gone. */
  it("removes a member, shutting the panel and saying they are no longer in the group", async () => {
    const view = opening(`${PAGE}/${GRACE.subjectId}`, {
      routes: { [`DELETE ${memberAt(GRACE)}`]: [["", 204]] },
    });
    await userEvent.click(
      await screen.findByRole("button", { name: "Remove from the group" }),
    );

    expect(await screen.findByText(GRACE_OUT)).toBeInTheDocument();
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
    const view = opening(`${PAGE}/${GRACE.subjectId}`, {
      width: NARROW,
      routes: { [`DELETE ${memberAt(GRACE)}`]: [["", 204]] },
    });
    const appearances = appearancesOf(GRACE_OUT);
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

    await heard(GRACE_OUT);

    expect(whileLeaving()).toEqual({ said: "", covering: 1 });
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
    await settled();
    await settled();

    expect(whileCovered).toEqual({ said: "", inPanel: [OLIVE_IN] });
    expect(screen.queryByRole("dialog")).toBeNull();
    expect(said()).toBe("");
    expect(appearances()).toEqual([{ hidden: false }]);
  });

  /** One element whichever way it points, so the control just pressed still has the keyboard after. */
  it("leaves the keyboard on the control just pressed, which now takes away what it gave", async () => {
    opening(`${PAGE}/${GRACE.subjectId}`, {
      routes: {
        [`PUT ${memberAt(GRACE)}/roles/overseer`]: [
          panelOf(GRACE, ["operator", "overseer"]),
        ],
      },
    });
    await userEvent.click(
      await screen.findByRole("button", { name: "Give Overseer" }),
    );

    const taking = await within(panel()).findByRole("button", {
      name: "Take Overseer away",
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
      "Give Overseer",
      `PUT ${memberAt(GRACE)}/roles/overseer`,
      ['{"code":"MEMBER_NOT_IN_VIEW"}', 404] as const,
      ['{"code":"MEMBER_NOT_IN_VIEW"}', 404] as const,
      "Give Overseer",
    ],
    [
      "a removal of somebody left the last who may change the membership",
      "Remove from the group",
      `DELETE ${memberAt(GRACE)}`,
      ['{"code":"LAST_MEMBERSHIP_CHANGER"}', 409] as const,
      panelOf(GRACE, ["operator", "owner"], ["owner"]),
      "Remove from the group",
    ],
  ])(
    "reads the list and the member again where %s is refused, and offers it no longer",
    async (_case, control, route, refusal, readAgainAs, gone) => {
      const { sent, readAgain } = opening(`${PAGE}/${GRACE.subjectId}`, {
        routes: {
          [`GET ${memberAt(GRACE)}`]: [
            panelOf(GRACE, ["operator"]),
            readAgainAs,
          ],
          [route]: [refusal],
        },
      });
      await userEvent.click(
        await screen.findByRole("button", { name: control }),
      );

      await waitFor(() =>
        expect(screen.queryByRole("button", { name: gone })).toBeNull(),
      );
      expect(membershipAsked(sent)).toEqual([
        `GET ${LIST}?sort=userId`,
        `GET ${memberAt(GRACE)}`,
        route,
        `GET ${LIST}?sort=userId`,
        `GET ${memberAt(GRACE)}`,
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
      await within(panel()).findByRole("button", {
        name: "Take Overseer away",
      }),
    ).toBeInTheDocument();
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
    expect(readAgain).not.toHaveBeenCalled();
  });

  /** Refused beside the control while its member is in view, and the standing read again. */
  it("says a change refused for the reader's standing beside the control, and reads the standing again", async () => {
    const { readAgain } = opening(`${PAGE}/${GRACE.subjectId}`, {
      routes: {
        [`PUT ${memberAt(GRACE)}/roles/owner`]: [
          ['{"code":"ACT_NOT_PERMITTED"}', 403],
        ],
      },
    });
    await userEvent.click(
      await screen.findByRole("button", { name: "Give Owner" }),
    );

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
    const view = opening(`${PAGE}/${GRACE.subjectId}`, {
      routes: { [`DELETE ${memberAt(GRACE)}`]: [held] },
    });
    await userEvent.click(
      await screen.findByRole("button", { name: "Remove from the group" }),
    );
    await userEvent.click(screen.getByRole("link", { name: "0009e1" }));
    await within(panel()).findByRole("heading", { level: 3, name: "Roles" });

    await act(async () => release());

    expect(
      await screen.findByText(
        `${setApart("Grace Hopper")} is still in this group: Nobody else in this group could change its membership.`,
      ),
    ).toBeInTheDocument();
    expect(view.at()).toBe(`${PAGE}/${ADA.subjectId}`);
  });
});
