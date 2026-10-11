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

import { RequireGroup } from "../../app/standing/RequireGroup";
import { StandingProvider } from "../../app/standing/StandingContext";
import { theme } from "../../lib/theme/theme";
import { requestsTo, serving, type Reply } from "../../testutil/answering";
import { namingGroupsByKeyAlone } from "../../testutil/groupAddresses";
import { answered, inGroup } from "../../testutil/standingRead";
import { deferred } from "../../testutil/deferred";
import { justMade } from "./entryAddress";
import { LibraryPage } from "./LibraryPage";
import { LIBRARY_KINDS } from "./libraryKinds";

const GROUP = "00000003-0000-4000-8000-000000000b41";

const OTHER_GROUP = "00000003-0000-4000-8000-000000000b43";

const KEY = "CLAIMS";

const PAGE = `/groups/${KEY}/questions`;

const LIST = `/api/groups/${GROUP}/questions`;

const WRITES = ["read_membership", "author_entry"];

const TRIAGE = {
  versionName: null,
  entryId: "00000006-0000-4000-8000-000000000b41",
  name: "Triage",
  purpose: "Sorts claims.",
  inService: 2,
  submitted: true,
  stopped: false,
};

const HANDLE = {
  versionName: null,
  entryId: "00000006-0000-4000-8000-000000000b42",
  name: "Handle",
  submitted: false,
  stopped: true,
};

const LISTED: Reply = [JSON.stringify({ items: [TRIAGE, HANDLE] }), 200];

interface Opening {
  readonly permissions?: readonly string[];
  readonly routes?: Readonly<Record<string, readonly Reply[]>>;
  readonly otherGroup?: boolean;
}

/**
 * The page at an address, under a real router whose location the test reads,
 * with the standing it would be handed — whose reading again the test counts —
 * and only the server stood in for.
 */
function opening(
  address: string,
  { permissions = WRITES, routes = {}, otherGroup = false }: Opening = {},
) {
  const sent = serving({ [`GET ${LIST}`]: [LISTED], ...routes });
  const router = createMemoryRouter(
    [
      {
        path: "/groups/:group/questions",
        element: (
          <RequireGroup reachedBy={LIBRARY_KINDS.question.item.reachedBy}>
            <LibraryPage of={LIBRARY_KINDS.question} />
          </RequireGroup>
        ),
      },
      { path: "/groups/:group/questions/:entryId", element: <p>Entry</p> },
    ],
    { initialEntries: [address] },
  );
  namingGroupsByKeyAlone(router, [GROUP, OTHER_GROUP]);
  const readAgain = vi.fn();
  const standing = answered(
    [],
    [
      inGroup(GROUP, KEY, "Claims", permissions),
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
    state: () => router.state.location.state as unknown,
    async go(path: string) {
      await act(async () => router.navigate(path));
    },
  };
}

function table(): HTMLElement {
  return screen.getByRole("table", { name: "The group's questions" });
}

/** Every row's cells, once the first page has landed. */
async function rows(): Promise<string[][]> {
  await screen.findByRole("link", { name: "Triage" });
  const [, ...listed] =
    within(table()).getAllByRole<HTMLTableRowElement>("row");
  return listed.map((row) =>
    [...row.cells].map((cell) => cell.textContent ?? ""),
  );
}

describe("LibraryPage", () => {
  it("lists the group's entries of its kind by name, each row saying what is in service, waiting and stopped", async () => {
    const { sent } = opening(PAGE);

    expect(await rows()).toEqual([
      ["TriageSorts claims.", "Version 2", "Waiting for approval"],
      ["HandleIt says nothing of what it is for.", "None", "Stopped"],
    ]);
    expect(screen.getByRole("heading", { level: 1 })).toHaveTextContent(
      "Questions of",
    );
    expect(screen.getByRole("heading", { level: 1 })).toHaveTextContent(
      "Claims",
    );
    expect(
      screen.getByRole("link", { name: "Triage" }),
    ).toHaveAccessibleDescription("Sorts claims.");
    expect(
      screen.getByRole("link", { name: "Handle" }),
    ).toHaveAccessibleDescription("It says nothing of what it is for.");
    expect(requestsTo(sent)).toEqual([`GET ${LIST}?sort=name`]);
  });

  it.each([
    [
      "that nothing matches a filter",
      `${PAGE}?filter=zz`,
      "No entry's name here holds that.",
      "This group has no questions yet.",
    ],
    [
      "that the group has none of its kind",
      PAGE,
      "This group has no questions yet.",
      "No entry's name here holds that.",
    ],
  ])("says %s, and not the other", async (_case, address, said, unsaid) => {
    opening(address, {
      routes: { [`GET ${LIST}`]: [['{"items":[]}', 200]] },
    });

    expect(await screen.findByText(said)).toBeInTheDocument();
    expect(screen.queryByText(unsaid)).toBeNull();
  });

  it.each([
    ["may write an entry there", WRITES, 1],
    ["may not", ["read_membership"], 0],
    ["holds nothing but membership", [], 0],
  ])(
    "draws its New button only for a reader who %s",
    async (_case, permissions, drawn) => {
      opening(PAGE, { permissions });
      await rows();

      expect(
        screen.queryAllByRole("button", { name: "New question" }),
      ).toHaveLength(drawn);
    },
  );

  it("sorts by the heading pressed, asking the server in that order and holding it in the address", async () => {
    const { sent, at } = opening(PAGE);
    await rows();

    await userEvent.click(
      within(table()).getByRole("button", { name: "Status" }),
    );

    await waitFor(() =>
      expect(requestsTo(sent)).toContain(`GET ${LIST}?sort=status`),
    );
    expect(at()).toBe(`${PAGE}?sort=status`);
  });

  /** An entry opens a page of its own, on its newest version, and leaves the list's order behind. */
  it("opens each row onto the entry's own page", async () => {
    const { at } = opening(`${PAGE}?sort=-name`);
    await rows();

    await userEvent.click(screen.getByRole("link", { name: "Handle" }));

    expect(await screen.findByText("Entry")).toBeInTheDocument();
    expect(at()).toBe(`${PAGE}/${HANDLE.entryId}`);
  });

  it("opens an entry just made onto its own page", async () => {
    const { at, state } = opening(PAGE, {
      routes: {
        [`POST ${LIST}`]: [
          [
            JSON.stringify({
              entryId: "00000006-0000-4000-8000-000000000b49",
              kind: "question",
              name: "Intake",
              stateActs: [],
              versions: [],
            }),
            201,
          ],
        ],
      },
    });
    await rows();

    await userEvent.click(screen.getByRole("button", { name: "New question" }));
    await userEvent.type(
      screen.getByRole("textbox", { name: "Name" }),
      "Intake",
    );
    await userEvent.click(screen.getByRole("button", { name: "Create" }));

    await waitFor(() =>
      expect(at()).toBe(`${PAGE}/00000006-0000-4000-8000-000000000b49`),
    );
    expect(justMade(state())).toBe(true);
  });

  it("does not open an entry made in the previous group under the group now shown", async () => {
    const otherPage = "/groups/SAMPLE/questions";
    const otherList = `/api/groups/${OTHER_GROUP}/questions`;
    const { go, at, state } = opening(PAGE, {
      otherGroup: true,
      routes: { [`GET ${otherList}`]: [['{"items":[]}', 200]] },
    });
    await rows();
    const held = deferred<Response>();
    const ordinaryFetch = globalThis.fetch;
    const sent = vi.fn<typeof fetch>((input, init) =>
      init?.method === "POST" &&
      new URL(String(input), "http://reader.test").pathname === LIST
        ? held.promise
        : ordinaryFetch(input, init),
    );
    vi.stubGlobal("fetch", sent);

    await userEvent.click(screen.getByRole("button", { name: "New question" }));
    await userEvent.type(
      screen.getByRole("textbox", { name: "Name" }),
      "Demo Sample",
    );
    await userEvent.click(screen.getByRole("button", { name: "Create" }));
    expect(
      sent.mock.calls.some(
        ([input, init]) =>
          init?.method === "POST" && String(input).includes(LIST),
      ),
    ).toBe(true);
    await go(otherPage);
    expect(
      await screen.findByRole("heading", { level: 1, name: /Demo Sample/ }),
    ).toBeInTheDocument();

    await act(async () =>
      held.settle(
        new Response(
          JSON.stringify({
            entryId: "00000006-0000-4000-8000-000000000b49",
            kind: "question",
            name: "Demo Sample",
            stateActs: [],
            versions: [],
          }),
          { status: 201, headers: { "Content-Type": "application/json" } },
        ),
      ),
    );

    expect(at()).toBe(otherPage);
    expect(state()).toBeNull();
    expect(screen.queryByText("Entry")).toBeNull();
    expect(screen.queryByRole("alert")).toBeNull();
  });

  it.each([
    ["the group is none the reader is in", "GROUP_NOT_IN_VIEW", 404, 1],
    ["the list could not be read as asked", "LIST_SORT_UNUSABLE", 400, 0],
  ])(
    "reads the standing again only where the refusal says %s",
    async (_case, code, status, again) => {
      const { readAgain } = opening(PAGE, {
        routes: { [`GET ${LIST}`]: [[JSON.stringify({ code }), status]] },
      });

      await screen.findByRole("alert");

      // Reread from a passive effect, which React may run in a task after the commit that drew the alert.
      await waitFor(() => expect(readAgain).toHaveBeenCalledTimes(again));
      // Unmounting flushes the passive effects of commits already made, so a further reread is counted too.
      cleanup();
      expect(readAgain).toHaveBeenCalledTimes(again);
    },
  );

  it.each([
    ["the act itself", "ACT_NOT_PERMITTED", 403, 1],
    ["the group", "GROUP_NOT_IN_VIEW", 404, 1],
    ["only the name asked for", "ENTRY_NAME_TAKEN", 409, 0],
  ])(
    "reads the standing again where making one is refused for %s",
    async (_case, code, status, again) => {
      const { readAgain, at } = opening(PAGE, {
        routes: { [`POST ${LIST}`]: [[JSON.stringify({ code }), status]] },
      });
      await rows();

      await userEvent.click(
        screen.getByRole("button", { name: "New question" }),
      );
      await userEvent.type(
        screen.getByRole("textbox", { name: "Name" }),
        "Intake",
      );
      await userEvent.click(screen.getByRole("button", { name: "Create" }));

      await screen.findByRole("alert");
      expect(readAgain).toHaveBeenCalledTimes(again);
      expect(at()).toBe(PAGE);
    },
  );
});
