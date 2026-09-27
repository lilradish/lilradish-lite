import { ThemeProvider } from "@mui/material/styles";
import { act, render, screen, waitFor, within } from "@testing-library/react";
import { userEvent } from "@testing-library/user-event";
import { RouterProvider, createMemoryRouter } from "react-router";
import { describe, expect, it, vi } from "vitest";

import { GROUPS_DESTINATION } from "../../app/destinations";
import { StandingProvider } from "../../app/standing/StandingContext";
import { theme } from "../../lib/theme/theme";
import { requestsTo, serving, type Reply } from "../../testutil/answering";
import { answered } from "../../testutil/standingRead";
import { GROUPS_PAGE, GroupsPage } from "./GroupsPage";

/** How long typing has to pause before what was typed is handed on. */
const QUIET_MS = 300;

const GROUPS = "/api/groups";

const PAYROLL = {
  groupId: "00000003-0000-4000-8000-000000000701",
  key: "PAYROLL",
  name: "Payroll",
  canBeAdministered: true,
  memberCount: 2,
};

const EMPTY_ROOM = {
  groupId: "00000003-0000-4000-8000-000000000704",
  key: "VOID",
  name: "Empty room",
  canBeAdministered: false,
  memberCount: 0,
};

const SALARIES = { ...PAYROLL, name: "Salaries" };

const TRIAGE = {
  groupId: "00000003-0000-4000-8000-000000000709",
  key: "TRIAGE",
  name: "Triage",
  canBeAdministered: true,
  memberCount: 1,
};

const ADA = {
  subjectId: "00000002-0000-4000-8000-000000000130",
  userId: "000130",
  displayName: "Ada Lovelace",
};

function listOf(...groups: (typeof PAYROLL)[]): readonly [string, number] {
  return [JSON.stringify({ items: groups }), 200];
}

/** Words set apart inside a sentence, bracketed by the isolate controls written as code points. */
function setApart(words: string): string {
  return `${String.fromCodePoint(0x2068)}${words}${String.fromCodePoint(0x2069)}`;
}

/**
 * The page at an address, under a real router whose history the test reads,
 * with the standing it would be handed — whose reading again the test counts —
 * and only the server stood in for.
 */
function opening(
  address: string,
  routes: Readonly<Record<string, readonly Reply[]>> = {},
) {
  const sent = serving({
    [`GET ${GROUPS}`]: [listOf(EMPTY_ROOM, PAYROLL)],
    "GET /api/pool/search": [
      [JSON.stringify({ items: [ADA], more: false }), 200],
    ],
    ...routes,
  });
  const router = createMemoryRouter(
    [
      {
        path: GROUPS_PAGE,
        element: <GroupsPage title={GROUPS_DESTINATION.label} />,
      },
    ],
    { initialEntries: [address] },
  );
  const readAgain = vi.fn();
  render(
    <ThemeProvider theme={theme}>
      <StandingProvider
        read={{ ...answered(["keep_group_register"]), reload: readAgain }}
      >
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

function table(): HTMLElement {
  return screen.getByRole("table", { name: "Groups in the register" });
}

function bodyRows(): HTMLTableRowElement[] {
  const [, ...rows] = within(table()).getAllByRole<HTMLTableRowElement>("row");
  return rows;
}

/** Every cell of every row, once the first page has landed. */
async function listed(): Promise<string[][]> {
  await within(table()).findByText("PAYROLL");
  return bodyRows().map((row) =>
    [...row.cells].map((cell) => cell.textContent ?? ""),
  );
}

function filterBox(): HTMLElement {
  return screen.getByRole("searchbox", { name: "Part of a group's name" });
}

/** The pause that hands typing on, waited out in real time. */
async function typedInto(box: HTMLElement, text: string) {
  await userEvent.type(box, text);
  await act(async () => {
    await new Promise((resolve) => setTimeout(resolve, QUIET_MS));
  });
}

describe("GroupsPage", () => {
  it("lists every group by name ascending, each with its key, whether it can be administered and how many are in it", async () => {
    const { sent } = opening(GROUPS_PAGE);

    expect(await listed()).toEqual([
      ["VOID", "Empty room", "No", "0", "Rename"],
      ["PAYROLL", "Payroll", "Yes", "2", "Rename"],
    ]);
    expect(screen.getByRole("columnheader", { name: "Group" })).toHaveAttribute(
      "aria-sort",
      "ascending",
    );
    expect(requestsTo(sent)).toEqual([`GET ${GROUPS}?sort=name`]);
  });

  it("offers Create a group level with the heading", async () => {
    opening(GROUPS_PAGE);
    await listed();

    const heading = screen.getByRole("heading", { level: 1, name: "Groups" });

    expect(
      within(heading.parentElement!).getByRole("button", {
        name: "Create a group",
      }),
    ).toBeInTheDocument();
  });

  /** The estate cannot enter a group, so a row is no way in: nothing on it links, and pressing it goes nowhere. */
  it("opens nothing from a row, which carries no link and leaves the address where it was when pressed", async () => {
    const { sent, at } = opening(GROUPS_PAGE);
    await listed();

    await userEvent.click(within(table()).getByText("Payroll"));

    expect(within(table()).queryAllByRole("link")).toEqual([]);
    expect(at()).toBe(GROUPS_PAGE);
    expect(screen.queryByRole("dialog")).toBeNull();
    expect(requestsTo(sent)).toEqual([`GET ${GROUPS}?sort=name`]);
  });

  it("asks for the filter and the order the address holds, and says both", async () => {
    const { sent } = opening(`${GROUPS_PAGE}?filter=pay&sort=-memberCount`);
    await listed();

    expect(requestsTo(sent)).toEqual([
      `GET ${GROUPS}?sort=-memberCount&filter=pay`,
    ]);
    expect(filterBox()).toHaveValue("pay");
    expect(
      screen.getByRole("columnheader", { name: "Members" }),
    ).toHaveAttribute("aria-sort", "descending");
  });

  it("sorts by the heading pressed, in the address in place of the entry it was on", async () => {
    const { sent, at, reached } = opening(`${GROUPS_PAGE}?filter=o`);
    await listed();

    await userEvent.click(
      screen.getByRole("button", { name: "Can be administered" }),
    );

    expect(at()).toBe(`${GROUPS_PAGE}?filter=o&sort=canBeAdministered`);
    expect(reached()).toBe("REPLACE");
    await waitFor(() =>
      expect(requestsTo(sent)).toEqual([
        `GET ${GROUPS}?sort=name&filter=o`,
        `GET ${GROUPS}?sort=canBeAdministered&filter=o`,
      ]),
    );
    expect(
      screen.getByRole("columnheader", { name: "Group" }),
    ).not.toHaveAttribute("aria-sort");
  });

  /** The control on a row has no order, so its column offers none. */
  it("offers no sort on the column holding the row's control", async () => {
    opening(GROUPS_PAGE);
    await listed();

    const headings = within(table()).getAllByRole("columnheader");

    expect(headings.map((heading) => heading.textContent)).toEqual([
      "Key",
      "Group",
      "Can be administered",
      "Members",
      "",
    ]);
    expect(within(headings[4]!).queryByRole("button")).toBeNull();
  });

  it("writes what is typed into the filter into the address, and asks for the register it narrows", async () => {
    const { sent, at } = opening(GROUPS_PAGE);
    await listed();

    await typedInto(filterBox(), "pay");

    await waitFor(() => expect(at()).toBe(`${GROUPS_PAGE}?filter=pay`));
    await waitFor(() =>
      expect(requestsTo(sent)).toEqual([
        `GET ${GROUPS}?sort=name`,
        `GET ${GROUPS}?sort=name&filter=pay`,
      ]),
    );
  });

  it.each([
    [GROUPS_PAGE, "No group has been created yet."],
    [`${GROUPS_PAGE}?filter=zz`, "No group's name holds that."],
  ])("says so where %s lists no group", async (address, sentence) => {
    opening(address, { [`GET ${GROUPS}`]: [listOf()] });

    expect(await screen.findByText(sentence)).toBeInTheDocument();
    expect(bodyRows()).toEqual([]);
  });

  it("shows the refusal of the list as the rule of keeping the register, rather than saying there are no groups", async () => {
    opening(GROUPS_PAGE, {
      [`GET ${GROUPS}`]: [['{"code":"ACT_NOT_PERMITTED"}', 403]],
    });

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "The register of groups is seen and changed only by a role that may keep it.",
    );
    expect(screen.queryByText("No group has been created yet.")).toBeNull();
  });

  it("creates a group, reads the register and the reader's standing again, and says it is in", async () => {
    const { sent, readAgain } = opening(GROUPS_PAGE, {
      [`GET ${GROUPS}`]: [
        listOf(EMPTY_ROOM, PAYROLL),
        listOf(EMPTY_ROOM, PAYROLL, TRIAGE),
      ],
      [`POST ${GROUPS}`]: [[JSON.stringify(TRIAGE), 201]],
    });
    await listed();

    await userEvent.click(
      screen.getByRole("button", { name: "Create a group" }),
    );
    await userEvent.type(
      screen.getByRole("textbox", { name: "Name" }),
      "Triage",
    );
    await userEvent.type(
      screen.getByRole("textbox", { name: "Key" }),
      "triage",
    );
    await typedInto(
      screen.getByRole("searchbox", { name: "User number or name" }),
      "ada",
    );
    await userEvent.click(
      await screen.findByRole("radio", { name: "000130 Ada Lovelace" }),
    );
    await userEvent.click(screen.getByRole("button", { name: "Create" }));

    expect(
      await screen.findByText(`${setApart("Triage")} is in the register.`),
    ).toBeInTheDocument();
    await within(table()).findByText("TRIAGE");
    expect(requestsTo(sent)).toEqual([
      `GET ${GROUPS}?sort=name`,
      "GET /api/pool/search?search=ada",
      `POST ${GROUPS}`,
      `GET ${GROUPS}?sort=name`,
    ]);
    expect(readAgain).toHaveBeenCalledOnce();
  });

  it("reads the reader's standing again when creating a group is refused the act", async () => {
    const { readAgain } = opening(GROUPS_PAGE, {
      [`POST ${GROUPS}`]: [['{"code":"ACT_NOT_PERMITTED"}', 403]],
    });
    await listed();

    await userEvent.click(
      screen.getByRole("button", { name: "Create a group" }),
    );
    await userEvent.type(
      screen.getByRole("textbox", { name: "Name" }),
      "Triage",
    );
    await userEvent.type(
      screen.getByRole("textbox", { name: "Key" }),
      "TRIAGE",
    );
    await typedInto(
      screen.getByRole("searchbox", { name: "User number or name" }),
      "ada",
    );
    await userEvent.click(
      await screen.findByRole("radio", { name: "000130 Ada Lovelace" }),
    );
    await userEvent.click(screen.getByRole("button", { name: "Create" }));

    await waitFor(() => expect(readAgain).toHaveBeenCalledOnce());
  });

  /** Rename is the one thing on a row that acts, and it enters nothing: the address stays on the register. */
  it("opens a rename of that row's group, holding its name and its key not to be changed, without entering it", async () => {
    const { sent, at } = opening(GROUPS_PAGE);
    await listed();

    await userEvent.click(
      screen.getByRole("button", { name: `Rename ${setApart("Payroll")}` }),
    );

    const dialog = screen.getByRole("dialog", { name: "Rename a group" });
    expect(within(dialog).getByRole("textbox", { name: "Name" })).toHaveValue(
      "Payroll",
    );
    expect(within(dialog).getByRole("definition")).toHaveTextContent("PAYROLL");
    expect(within(dialog).queryByRole("textbox", { name: "Key" })).toBeNull();
    expect(at()).toBe(GROUPS_PAGE);
    expect(requestsTo(sent)).toEqual([`GET ${GROUPS}?sort=name`]);
  });

  it("renames a group, reads the register and the reader's standing again, and says so", async () => {
    const { sent, readAgain } = opening(GROUPS_PAGE, {
      [`GET ${GROUPS}`]: [
        listOf(EMPTY_ROOM, PAYROLL),
        listOf(EMPTY_ROOM, SALARIES),
      ],
      [`PATCH ${GROUPS}/${PAYROLL.groupId}`]: [[JSON.stringify(SALARIES), 200]],
    });
    await listed();
    await userEvent.click(
      screen.getByRole("button", { name: `Rename ${setApart("Payroll")}` }),
    );
    const name = screen.getByRole("textbox", { name: "Name" });
    await userEvent.clear(name);
    await userEvent.type(name, "Salaries");

    await userEvent.click(screen.getByRole("button", { name: "Rename" }));

    expect(
      await screen.findByText(
        `The group is called ${setApart("Salaries")} now.`,
      ),
    ).toBeInTheDocument();
    await within(table()).findByText("Salaries");
    expect(within(table()).queryByText("Payroll")).toBeNull();
    expect(bodyRows()[1]!.cells[0]!.textContent).toBe("PAYROLL");
    expect(requestsTo(sent)).toEqual([
      `GET ${GROUPS}?sort=name`,
      `PATCH ${GROUPS}/${PAYROLL.groupId}`,
      `GET ${GROUPS}?sort=name`,
    ]);
    expect(readAgain).toHaveBeenCalledOnce();
  });

  it("abandons a rename, changing nothing and reading nothing again", async () => {
    const { sent, readAgain } = opening(GROUPS_PAGE);
    const before = await listed();
    await userEvent.click(
      screen.getByRole("button", { name: `Rename ${setApart("Payroll")}` }),
    );
    await userEvent.type(
      screen.getByRole("textbox", { name: "Name" }),
      " and wages",
    );

    await userEvent.click(screen.getByRole("button", { name: "Cancel" }));

    await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
    expect(await listed()).toEqual(before);
    expect(screen.queryByText(/is called|is in the register/)).toBeNull();
    expect(requestsTo(sent)).toEqual([`GET ${GROUPS}?sort=name`]);
    expect(readAgain).not.toHaveBeenCalled();
  });

  it("opens each rename afresh on the row it was pressed on", async () => {
    opening(GROUPS_PAGE);
    await listed();
    await userEvent.click(
      screen.getByRole("button", { name: `Rename ${setApart("Payroll")}` }),
    );
    await userEvent.type(screen.getByRole("textbox", { name: "Name" }), "!");
    await userEvent.click(screen.getByRole("button", { name: "Cancel" }));
    await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());

    await userEvent.click(
      screen.getByRole("button", { name: `Rename ${setApart("Empty room")}` }),
    );

    expect(screen.getByRole("textbox", { name: "Name" })).toHaveValue(
      "Empty room",
    );
    expect(screen.getByRole("definition")).toHaveTextContent("VOID");
  });

  /** Two changes at once would settle in whichever order they land, so a row's Rename waits for the other. */
  it("holds every row's Rename while a group is being created", async () => {
    opening(GROUPS_PAGE, { [`POST ${GROUPS}`]: [new Promise(() => {})] });
    await listed();
    await userEvent.click(
      screen.getByRole("button", { name: "Create a group" }),
    );
    await userEvent.type(
      screen.getByRole("textbox", { name: "Name" }),
      "Triage",
    );
    await userEvent.type(
      screen.getByRole("textbox", { name: "Key" }),
      "triage",
    );
    await typedInto(
      screen.getByRole("searchbox", { name: "User number or name" }),
      "ada",
    );
    await userEvent.click(
      await screen.findByRole("radio", { name: "000130 Ada Lovelace" }),
    );

    await userEvent.click(screen.getByRole("button", { name: "Create" }));

    expect(
      screen.getByRole("button", {
        name: `Rename ${setApart("Payroll")}`,
        hidden: true,
      }),
    ).toHaveAttribute("aria-disabled", "true");
  });
});
