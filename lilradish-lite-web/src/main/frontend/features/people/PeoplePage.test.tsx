import { ThemeProvider } from "@mui/material/styles";
import { act, render, screen, waitFor, within } from "@testing-library/react";
import { userEvent } from "@testing-library/user-event";
import { RouterProvider, createMemoryRouter } from "react-router";
import { describe, expect, it, vi } from "vitest";

import { PEOPLE_DESTINATION } from "../../app/destinations";
import { StandingProvider } from "../../app/standing/StandingContext";
import { theme } from "../../lib/theme/theme";
import { requestsTo, serving, type Reply } from "../../testutil/answering";
import { deferred } from "../../testutil/deferred";
import { firstSight } from "../../testutil/firstSight";
import { appearancesOf } from "../../testutil/heard";
import { laidOutAt } from "../../testutil/layout";
import { noticesIn } from "../../testutil/notices";
import { answered } from "../../testutil/standingRead";
import { PEOPLE_PAGE, PeoplePage } from "./PeoplePage";

/** Room for the list and the panel beside it, and too little for both. */
const WIDE = 1280;
const NARROW = 700;

/** Wider than either: what the list is given is less than the window. */
const VIEWPORT = 1920;

/** How long typing has to pause before what was typed is handed on. */
const QUIET_MS = 300;

const KEEPS_THE_POOL_AND_GRANTS_ROLES = ["keep_pool", "grant_estate_role"];

const ADA = {
  subjectId: "00000002-0000-4000-8000-000000000130",
  userId: "000130",
  displayName: "Ada Lovelace",
};

const GRACE = {
  subjectId: "00000002-0000-4000-8000-000000000140",
  userId: "000140",
  displayName: "Grace Hopper",
};

/** In the pool, and on no page this list is ever answered with. */
const HEDY = {
  subjectId: "00000002-0000-4000-8000-000000000150",
  userId: "000150",
  displayName: "Hedy Lamarr",
};

const POOL = "/api/pool/people";

function personAt(person: { readonly subjectId: string }): string {
  return `${POOL}/${person.subjectId}`;
}

type Someone = typeof ADA;

function row(person: Someone, estateRoles: string[] = [], groupCount = 0) {
  return { ...person, estateRoles, groupCount };
}

function listOf(...rows: ReturnType<typeof row>[]): readonly [string, number] {
  return [JSON.stringify({ items: rows }), 200];
}

function panelOf(
  person: Someone,
  estateRoles: string[] = [],
  groups: string[] = [],
): readonly [string, number] {
  return [
    JSON.stringify({
      ...person,
      estateRoles,
      lastGrantingRoles: [],
      groups,
      seeded: false,
    }),
    200,
  ];
}

const ADA_AND_GRACE = listOf(row(ADA, ["steward"], 2), row(GRACE));

/** The directory's answer to a search finding Hedy, who is not yet in the pool. */
const HEDY_IN_DIRECTORY: Reply = [
  JSON.stringify({
    items: [{ userId: HEDY.userId, displayName: HEDY.displayName }],
    more: false,
  }),
  200,
];

/** Words set apart inside a sentence, bracketed by the isolate controls written as code points. */
function setApart(words: string): string {
  return `${String.fromCodePoint(0x2068)}${words}${String.fromCodePoint(0x2069)}`;
}

interface Opening {
  readonly acts?: readonly string[];
  readonly routes?: Readonly<Record<string, readonly Reply[]>>;
  readonly width?: number;
}

/**
 * The page at an address, under a real router whose history the test reads,
 * with the standing it would be handed — whose reading again the test counts —
 * and only the server stood in for. A second address stands for anywhere else
 * the reader could go.
 */
function opening(
  address: string,
  {
    acts = KEEPS_THE_POOL_AND_GRANTS_ROLES,
    routes = {},
    width = WIDE,
  }: Opening = {},
) {
  laidOutAt(width, VIEWPORT);
  const sent = serving({
    [`GET ${POOL}`]: [ADA_AND_GRACE],
    [`GET ${personAt(ADA)}`]: [panelOf(ADA, ["steward"], ["Payroll"])],
    [`GET ${personAt(GRACE)}`]: [panelOf(GRACE)],
    [`GET ${personAt(HEDY)}`]: [panelOf(HEDY)],
    "GET /api/people": [HEDY_IN_DIRECTORY],
    ...routes,
  });
  const router = createMemoryRouter(
    [
      {
        path: `${PEOPLE_PAGE}/:subjectId?`,
        element: <PeoplePage title={PEOPLE_DESTINATION.label} />,
      },
      { path: "/elsewhere", element: <p>Elsewhere</p> },
    ],
    { initialEntries: [address] },
  );
  const readAgain = vi.fn();
  render(
    <ThemeProvider theme={theme}>
      <StandingProvider read={{ ...answered(acts), reload: readAgain }}>
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
    async leave() {
      await act(async () => router.navigate("/elsewhere"));
    },
    async go(path: string) {
      await act(async () => router.navigate(path));
    },
  };
}

/**
 * The user numbers listed, once the first page has landed. Found whether or
 * not a covering panel has hidden the list from queries.
 */
async function listed(): Promise<string[]> {
  await screen.findByRole("link", { name: "000140", hidden: true });
  return firstCells();
}

function table(): HTMLElement {
  return screen.getByRole("table", {
    name: "People in the pool",
    hidden: true,
  });
}

function bodyRows(): HTMLTableRowElement[] {
  const [, ...rows] = within(table()).getAllByRole<HTMLTableRowElement>("row", {
    hidden: true,
  });
  return rows;
}

function firstCells(): string[] {
  return bodyRows().map((each) => each.cells[0]!.textContent ?? "");
}

function rolesOf(userId: string): string {
  const found = bodyRows().find(
    (each) => each.cells[0]!.textContent === userId,
  );
  return found?.cells[2]!.textContent ?? "";
}

/** The line the page says what it has done in, found whether or not a covering panel hides it. */
function said(): string {
  return saidLine()?.textContent ?? "";
}

/** What the page's own line holds, as the notices it is drawn in. */
function saidAs() {
  return noticesIn(saidLine()!);
}

function saidLine(): HTMLElement | undefined {
  return screen
    .getAllByRole("status", { hidden: true })
    .find((each) => !table().parentElement!.contains(each));
}

/** What every status a screen reader can reach holds: none that anything above it hides. */
function heardLines(): string[] {
  return screen.queryAllByRole("status").map((each) => each.textContent ?? "");
}

/** Waited for: a line goes in a frame or two after what hid it has gone. */
async function heard(words: string) {
  await waitFor(() => expect(heardLines()).toContain(words));
}

const GRACE_OUT = `${setApart("Grace Hopper")} is out of the pool.`;

const HEDY_IN = `${setApart("Hedy Lamarr")} is in the pool.`;

const STILL_IN_GROUP = `${setApart("Grace Hopper")} is still in the pool: They have to leave this group first: ${setApart("Payroll")}.`;

/**
 * The page's own filter, named with the words of the search that brings
 * somebody in: both take a user number or a name.
 */
function filterBox(): HTMLElement {
  return screen.getByRole("searchbox", { name: "User number or name" });
}

/** The pause that hands typing on, waited out in real time. */
async function typedInto(box: HTMLElement, text: string) {
  await userEvent.type(box, text);
  await act(async () => {
    await new Promise((resolve) => setTimeout(resolve, QUIET_MS));
  });
}

/** A covering panel leaves on a transition, and what follows it happens once it has gone. */
async function settled() {
  await act(async () => {
    await new Promise((resolve) =>
      setTimeout(resolve, theme.transitions.duration.leavingScreen * 2),
    );
  });
}

/** The list's own box, named, where the keyboard is put down once the panel is gone. */
function expectKeyboardOnTheList() {
  const focused = document.activeElement;
  expect(focused).toBe(
    screen.getByRole("region", { name: "People in the pool" }),
  );
  expect(focused?.contains(table())).toBe(true);
}

async function bringingIn(name: string) {
  await userEvent.click(screen.getByRole("button", { name: "Add somebody" }));
  await typedInto(
    screen.getByRole("searchbox", { name: "User number or name" }),
    "hedy",
  );
  await userEvent.click(await screen.findByRole("radio", { name }));
  await userEvent.click(
    screen.getByRole("button", { name: "Bring into the pool" }),
  );
}

describe("PeoplePage", () => {
  it("lists the pool by user number ascending, and asks for it in that order", async () => {
    const { sent } = opening("/system/people");

    expect(await listed()).toEqual(["000130", "000140"]);
    expect(
      screen.getByRole("columnheader", { name: "User number" }),
    ).toHaveAttribute("aria-sort", "ascending");
    expect(requestsTo(sent)).toEqual([`GET ${POOL}?sort=userId`]);
  });

  /** A link, a new tab, or the way back reopens the list as it was left. */
  it("asks for the filter and the order the address holds, and says both", async () => {
    const { sent } = opening("/system/people?filter=gr&sort=-groupCount");
    await listed();

    expect(requestsTo(sent)).toEqual([
      `GET ${POOL}?sort=-groupCount&filter=gr`,
    ]);
    expect(filterBox()).toHaveValue("gr");
    expect(
      screen.getByRole("columnheader", { name: "Groups" }),
    ).toHaveAttribute("aria-sort", "descending");
  });

  it("says nobody in the pool matches a filter that found nobody, and lists nobody", async () => {
    opening("/system/people?filter=zz", {
      routes: { [`GET ${POOL}`]: [listOf()] },
    });

    expect(
      await screen.findByText("Nobody in the pool matches that."),
    ).toBeInTheDocument();
    expect(bodyRows()).toEqual([]);
    expect(screen.queryByText("Nobody is in the pool.")).toBeNull();
  });

  /** "Nobody matches", said over a list the server would not read, is a lie nobody could catch. */
  it("shows the refusal of the list rather than saying nobody matches", async () => {
    opening("/system/people?filter=zz", {
      routes: {
        [`GET ${POOL}`]: [['{"code":"LIST_FILTER_UNUSABLE"}', 400]],
      },
    });

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "That filter is too long, or holds a character that cannot be searched for.",
    );
    expect(screen.queryByText("Nobody in the pool matches that.")).toBeNull();
    expect(bodyRows()).toEqual([]);
  });

  it("grants a role from the answer, reads the list and the reader's standing again, and keeps the keyboard on the control", async () => {
    const { sent, readAgain } = opening(`/system/people/${GRACE.subjectId}`, {
      routes: {
        [`GET ${POOL}`]: [
          ADA_AND_GRACE,
          listOf(row(ADA, ["steward"], 2), row(GRACE, ["watcher"])),
        ],
        [`PUT ${personAt(GRACE)}/estate-roles/watcher`]: [
          panelOf(GRACE, ["watcher"]),
        ],
      },
    });
    await listed();
    const grant = await screen.findByRole("button", { name: "Grant Watcher" });

    await userEvent.click(grant);

    await waitFor(() => expect(rolesOf("000140")).toBe(setApart("Watcher")));
    expect(grant).toHaveTextContent("Withdraw Watcher");
    expect(document.activeElement).toBe(grant);
    expect(readAgain).toHaveBeenCalledOnce();
    expect(requestsTo(sent)).toEqual([
      `GET ${POOL}?sort=userId`,
      `GET ${personAt(GRACE)}`,
      `PUT ${personAt(GRACE)}/estate-roles/watcher`,
      `GET ${POOL}?sort=userId`,
    ]);
  });

  /** The control pressed goes with the panel, and the panel puts the keyboard on the list. */
  it.each([
    ["beside the list", WIDE],
    ["over the list", NARROW],
  ])(
    "takes somebody out from a panel %s: their row leaves, the panel shuts, the address goes back to the list in place of theirs, and the keyboard lands on the list",
    async (_where, width) => {
      const { sent, at, reached, readAgain } = opening(
        `/system/people/${GRACE.subjectId}?sort=userId`,
        {
          width,
          routes: {
            [`GET ${POOL}`]: [ADA_AND_GRACE, listOf(row(ADA, ["steward"], 2))],
            [`DELETE ${personAt(GRACE)}`]: [["", 204]],
          },
        },
      );
      await listed();

      await userEvent.click(
        await screen.findByRole("button", { name: "Remove from the pool" }),
      );
      await waitFor(() => expect(firstCells()).toEqual(["000130"]));
      await settled();

      expect(at()).toBe("/system/people?sort=userId");
      expect(reached()).toBe("REPLACE");
      expect(screen.queryByRole("region", { name: "Grace Hopper" })).toBeNull();
      expect(screen.queryByRole("dialog")).toBeNull();
      expectKeyboardOnTheList();
      expect(readAgain).not.toHaveBeenCalled();
      expect(requestsTo(sent)).toEqual([
        `GET ${POOL}?sort=userId`,
        `GET ${personAt(GRACE)}`,
        `DELETE ${personAt(GRACE)}`,
        `GET ${POOL}?sort=userId`,
      ]);
    },
  );

  /** While the panel covers the list, everything around it is hidden, and a line written then is never heard. */
  it("says somebody is out of the pool only once the covering panel they were taken out from has gone", async () => {
    const { at } = opening(`/system/people/${GRACE.subjectId}`, {
      width: NARROW,
      routes: { [`DELETE ${personAt(GRACE)}`]: [["", 204]] },
    });
    const appearances = appearancesOf(GRACE_OUT);
    const whileLeaving = firstSight(() =>
      at() === PEOPLE_PAGE
        ? {
            said: said(),
            covering: document.querySelectorAll('[role="dialog"]').length,
          }
        : undefined,
    );
    await userEvent.click(
      await screen.findByRole("button", { name: "Remove from the pool" }),
    );

    await heard(GRACE_OUT);

    expect(whileLeaving()).toEqual({ said: "", covering: 1 });
    expect(appearances()).toEqual([{ hidden: false }]);
  });

  it("says somebody is out of the pool at once where they were taken out from beside the list", async () => {
    opening(`/system/people/${GRACE.subjectId}`, {
      routes: { [`DELETE ${personAt(GRACE)}`]: [["", 204]] },
    });
    const appearances = appearancesOf(GRACE_OUT);

    await userEvent.click(
      await screen.findByRole("button", { name: "Remove from the pool" }),
    );

    await heard(GRACE_OUT);

    expect(saidAs()).toEqual([{ severity: "info", words: GRACE_OUT }]);
    expect(appearances()).toEqual([{ hidden: false }]);
    expect(screen.queryByRole("alert")).toBeNull();
  });

  /** The row that opened it is gone once the list is read again, and the focus trap would hand the keyboard to it. */
  it("puts the keyboard on the list, not on the row that opened it, once the person it covered is taken out", async () => {
    opening("/system/people", {
      width: NARROW,
      routes: {
        [`GET ${POOL}`]: [ADA_AND_GRACE, listOf(row(ADA, ["steward"], 2))],
        [`DELETE ${personAt(GRACE)}`]: [["", 204]],
      },
    });
    await userEvent.click(await screen.findByRole("link", { name: "000140" }));

    await userEvent.click(
      await screen.findByRole("button", { name: "Remove from the pool" }),
    );
    await waitFor(() => expect(firstCells()).toEqual(["000130"]));
    await settled();

    expectKeyboardOnTheList();
  });

  /** The change is the page's, not the panel's: its answer is taken in wherever the reader has gone. */
  it("reads the list again when a removal lands after another row was picked, leaving the reader on that row", async () => {
    const removal = deferred<readonly [string, number]>();
    const { sent, at } = opening(`/system/people/${GRACE.subjectId}`, {
      routes: {
        [`GET ${POOL}`]: [ADA_AND_GRACE, listOf(row(ADA, ["steward"], 2))],
        [`DELETE ${personAt(GRACE)}`]: [removal.promise],
      },
    });
    await listed();
    await userEvent.click(
      await screen.findByRole("button", { name: "Remove from the pool" }),
    );
    await userEvent.click(screen.getByRole("link", { name: "000130" }));
    await screen.findByRole("region", { name: "Ada Lovelace" });

    await act(async () => removal.settle(["", 204]));

    await waitFor(() => expect(firstCells()).toEqual(["000130"]));
    expect(at()).toBe(`/system/people/${ADA.subjectId}`);
    expect(screen.getByRole("region", { name: "Ada Lovelace" })).toBeVisible();
    expect(said()).toBe(GRACE_OUT);
    expect(requestsTo(sent)).toEqual([
      `GET ${POOL}?sort=userId`,
      `GET ${personAt(GRACE)}`,
      `DELETE ${personAt(GRACE)}`,
      `GET ${personAt(ADA)}`,
      `GET ${POOL}?sort=userId`,
    ]);
  });

  it("reads the list again when a grant lands after a covering panel was shut, leaving the list open", async () => {
    const granted = deferred<readonly [string, number]>();
    const { sent, at } = opening(`/system/people/${GRACE.subjectId}`, {
      width: NARROW,
      routes: {
        [`GET ${POOL}`]: [
          ADA_AND_GRACE,
          listOf(row(ADA, ["steward"], 2), row(GRACE, ["watcher"])),
        ],
        [`PUT ${personAt(GRACE)}/estate-roles/watcher`]: [granted.promise],
      },
    });
    const panel = await screen.findByRole("dialog", { name: "Grace Hopper" });
    await userEvent.click(
      within(panel).getByRole("button", { name: "Grant Watcher" }),
    );
    await userEvent.click(within(panel).getByRole("button", { name: "Close" }));
    await settled();

    await act(async () => granted.settle(panelOf(GRACE, ["watcher"])));

    await waitFor(() => expect(rolesOf("000140")).toBe(setApart("Watcher")));
    expect(at()).toBe(PEOPLE_PAGE);
    expect(screen.queryByRole("dialog")).toBeNull();
    expect(requestsTo(sent)).toEqual([
      `GET ${POOL}?sort=userId`,
      `GET ${personAt(GRACE)}`,
      `PUT ${personAt(GRACE)}/estate-roles/watcher`,
      `GET ${POOL}?sort=userId`,
    ]);
  });

  /** Taken into the page as it stands when the answer lands, not as it stood when asked. */
  it("keeps a filter typed while a removal was out, and the list it asks for", async () => {
    const removal = deferred<readonly [string, number]>();
    const { sent, at } = opening(
      `/system/people/${GRACE.subjectId}?sort=userId`,
      { routes: { [`DELETE ${personAt(GRACE)}`]: [removal.promise] } },
    );
    await listed();
    await userEvent.click(
      await screen.findByRole("button", { name: "Remove from the pool" }),
    );
    await typedInto(filterBox(), "ad");

    await act(async () => removal.settle(["", 204]));

    await waitFor(() =>
      expect(at()).toBe("/system/people?sort=userId&filter=ad"),
    );
    expect(filterBox()).toHaveValue("ad");
    expect(requestsTo(sent)).toEqual([
      `GET ${POOL}?sort=userId`,
      `GET ${personAt(GRACE)}`,
      `DELETE ${personAt(GRACE)}`,
      `GET ${POOL}?sort=userId&filter=ad`,
      `GET ${POOL}?sort=userId&filter=ad`,
    ]);
  });

  /** The answer that brought them in is what the pool holds of them; reading them again would ask for it twice. */
  it("brings somebody in: their address is pushed with the list's query, the list is read again, it is said, and they are picked without being read", async () => {
    const { sent, at, reached } = opening("/system/people?sort=userId", {
      routes: { [`POST ${POOL}`]: [[panelOf(HEDY)[0], 201]] },
    });
    await listed();

    await bringingIn("000150 Hedy Lamarr");
    await heard(HEDY_IN);

    expect(screen.getByRole("region", { name: "Hedy Lamarr" })).toBeVisible();
    expect(at()).toBe(`/system/people/${HEDY.subjectId}?sort=userId`);
    expect(reached()).toBe("PUSH");
    expect(requestsTo(sent)).toEqual([
      `GET ${POOL}?sort=userId`,
      "GET /api/people?search=hedy",
      `POST ${POOL}`,
      `GET ${POOL}?sort=userId`,
    ]);
  });

  /**
   * The dialog hides the page until it has finished leaving, and words written
   * under it are never heard; nor would they be later, for being uncovered.
   */
  it("says somebody is in the pool only once the dialog that brought them in has gone", async () => {
    const { at } = opening("/system/people", {
      routes: { [`POST ${POOL}`]: [[panelOf(HEDY)[0], 201]] },
    });
    await listed();
    const appearances = appearancesOf(HEDY_IN);
    const whileLeaving = firstSight(() =>
      at() === `${PEOPLE_PAGE}/${HEDY.subjectId}`
        ? {
            said: said(),
            leaving: document.querySelectorAll('[role="dialog"]').length,
          }
        : undefined,
    );

    await bringingIn("000150 Hedy Lamarr");
    await heard(HEDY_IN);

    expect(whileLeaving()).toEqual({ said: "", leaving: 1 });
    expect(screen.queryByRole("dialog")).toBeNull();
    expect(saidAs()).toEqual([{ severity: "info", words: HEDY_IN }]);
    expect(appearances()).toEqual([{ hidden: false }]);
  });

  /**
   * Their panel covers the list and hides the page's line, so the panel says
   * it; a line said again once the reader shut it would come late and twice.
   */
  it("says somebody brought in over a narrow list is in the pool inside the panel that answered it, and never again once it is shut", async () => {
    opening("/system/people", {
      width: NARROW,
      routes: { [`POST ${POOL}`]: [[panelOf(HEDY)[0], 201]] },
    });
    await listed();
    const appearances = appearancesOf(HEDY_IN);

    await bringingIn("000150 Hedy Lamarr");
    const panel = await screen.findByRole("dialog", { name: "Hedy Lamarr" });
    await heard(HEDY_IN);
    await settled();
    const whileCovered = {
      said: said(),
      inPanel: within(panel)
        .getAllByRole("status")
        .map((each) => each.textContent)
        .filter((words) => words !== ""),
      adding: screen.queryAllByRole("dialog", {
        name: "Add somebody",
        hidden: true,
      }).length,
    };
    await userEvent.click(within(panel).getByRole("button", { name: "Close" }));
    await settled();
    await settled();

    expect(whileCovered).toEqual({ said: "", inPanel: [HEDY_IN], adding: 0 });
    expect(screen.queryByRole("dialog")).toBeNull();
    expect(said()).toBe("");
    expect(appearances()).toEqual([{ hidden: false }]);
  });

  /** Only what the shut panel itself answered ends with it. */
  it("still says a line about somebody out of view once the reader shuts another person's covering panel", async () => {
    const removal = deferred<readonly [string, number]>();
    opening(`/system/people/${GRACE.subjectId}`, {
      width: NARROW,
      routes: { [`DELETE ${personAt(GRACE)}`]: [removal.promise] },
    });
    const appearances = appearancesOf(GRACE_OUT);
    const grace = await screen.findByRole("dialog", { name: "Grace Hopper" });
    await userEvent.click(
      within(grace).getByRole("button", { name: "Remove from the pool" }),
    );
    await userEvent.click(within(grace).getByRole("button", { name: "Close" }));
    await settled();
    await userEvent.click(screen.getByRole("link", { name: "000130" }));
    const ada = await screen.findByRole("dialog", { name: "Ada Lovelace" });

    await act(async () => removal.settle(["", 204]));
    const whileCovered = said();
    await userEvent.click(within(ada).getByRole("button", { name: "Close" }));
    await heard(GRACE_OUT);

    expect(whileCovered).toBe("");
    expect(appearances()).toEqual([{ hidden: false }]);
  });

  /** Their address was open all along, so nothing moves and nothing else would read them. */
  it("shows somebody brought back whose address is already open, and reads them afresh when picked again later", async () => {
    const { sent, at, go } = opening(`/system/people/${HEDY.subjectId}`, {
      routes: {
        [`GET ${personAt(HEDY)}`]: [
          ['{"code":"PERSON_NOT_IN_VIEW"}', 404],
          panelOf(HEDY, ["watcher"]),
        ],
        [`POST ${POOL}`]: [[panelOf(HEDY)[0], 201]],
      },
    });
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "That person is not in the pool.",
    );

    await bringingIn("000150 Hedy Lamarr");
    const panel = await screen.findByRole("region", { name: "Hedy Lamarr" });
    const broughtBack = {
      held: within(panel).getAllByText("Not held").length,
      refused: within(panel).queryByRole("alert"),
      readsOfThem: requestsTo(sent).filter(
        (each) => each === `GET ${personAt(HEDY)}`,
      ).length,
    };
    await go(`/system/people/${ADA.subjectId}`);
    await screen.findByRole("region", { name: "Ada Lovelace" });
    await go(`/system/people/${HEDY.subjectId}`);
    const readAgain = await screen.findByRole("region", {
      name: "Hedy Lamarr",
    });

    expect(broughtBack).toEqual({ held: 2, refused: null, readsOfThem: 1 });
    expect(at()).toBe(`/system/people/${HEDY.subjectId}`);
    await waitFor(() =>
      expect(within(readAgain).getAllByText("Held")).toHaveLength(1),
    );
    expect(
      requestsTo(sent).filter((each) => each === `GET ${personAt(HEDY)}`),
    ).toEqual([`GET ${personAt(HEDY)}`, `GET ${personAt(HEDY)}`]);
  });

  it("reads the person and the list again when taking them out is refused for what still holds them, naming it, the keyboard left on the control", async () => {
    const { sent } = opening(`/system/people/${GRACE.subjectId}`, {
      routes: {
        [`GET ${POOL}`]: [
          ADA_AND_GRACE,
          listOf(row(ADA, ["steward"], 2), row(GRACE, ["steward"])),
        ],
        [`GET ${personAt(GRACE)}`]: [
          panelOf(GRACE),
          panelOf(GRACE, ["steward"]),
        ],
        [`DELETE ${personAt(GRACE)}`]: [
          ['{"code":"PERSON_HOLDS_ESTATE_ROLES"}', 409],
        ],
      },
    });
    await listed();
    const remove = await screen.findByRole("button", {
      name: "Remove from the pool",
    });

    await userEvent.click(remove);

    await waitFor(() =>
      expect(remove).toHaveAccessibleDescription(
        `Their estate role has to be withdrawn first: ${setApart("Steward")}.`,
      ),
    );
    await waitFor(() => expect(rolesOf("000140")).toBe(setApart("Steward")));
    expect(screen.getByRole("alert")).toHaveTextContent(
      "Somebody holding an estate role cannot be taken out of the pool.",
    );
    expect(document.activeElement).toBe(remove);
    expect(requestsTo(sent)).toEqual([
      `GET ${POOL}?sort=userId`,
      `GET ${personAt(GRACE)}`,
      `DELETE ${personAt(GRACE)}`,
      `GET ${POOL}?sort=userId`,
      `GET ${personAt(GRACE)}`,
    ]);
  });

  /** Another steward may have taken the act away: the standing is read again, so dead controls go. */
  it("reads the reader's standing again when a change is refused the act, and says that act's rule beside the control", async () => {
    const { readAgain } = opening(`/system/people/${GRACE.subjectId}`, {
      routes: {
        [`PUT ${personAt(GRACE)}/estate-roles/watcher`]: [
          ['{"code":"ACT_NOT_PERMITTED"}', 403],
        ],
      },
    });

    await userEvent.click(
      await screen.findByRole("button", { name: "Grant Watcher" }),
    );

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "Estate roles are granted and withdrawn only by a role that may grant them.",
    );
    expect(readAgain).toHaveBeenCalledOnce();
  });

  it("reads the reader's standing again when bringing somebody in is refused the act", async () => {
    const { readAgain } = opening("/system/people", {
      routes: { [`POST ${POOL}`]: [['{"code":"ACT_NOT_PERMITTED"}', 403]] },
    });
    await listed();

    await bringingIn("000150 Hedy Lamarr");

    expect(
      await within(screen.getByRole("dialog")).findByRole("alert"),
    ).toHaveTextContent(
      "The pool is seen and changed only by a role that may keep it.",
    );
    expect(readAgain).toHaveBeenCalledOnce();
    expect(said()).toBe("");
  });

  /** Said beside the control while its person is in view, and in the page's own line once they are not. */
  it("says a change refused after the reader moved on in the page's line, naming whom it was asked of", async () => {
    const refusal = deferred<readonly [string, number]>();
    opening(`/system/people/${GRACE.subjectId}`, {
      routes: {
        [`PUT ${personAt(GRACE)}/estate-roles/watcher`]: [refusal.promise],
      },
    });
    await userEvent.click(
      await screen.findByRole("button", { name: "Grant Watcher" }),
    );
    await userEvent.click(screen.getByRole("link", { name: "000130" }));
    const panel = await screen.findByRole("region", { name: "Ada Lovelace" });

    await act(async () =>
      refusal.settle(['{"code":"LAST_ESTATE_ROLE_GRANTOR"}', 409]),
    );

    expect(saidAs()).toEqual([
      {
        severity: "error",
        words: `${setApart("Grace Hopper")}'s estate roles were not changed: Withdrawing this would leave nobody who may grant an estate role.`,
      },
    ]);
    expect(within(panel).queryByRole("alert")).toBeNull();
  });

  /**
   * Nothing on screen names what still holds somebody out of view — the list
   * only counts groups — so they are read again, once, to name it.
   */
  it("names what still holds somebody out of view whose removal was refused for it, reading them once more to do so", async () => {
    const refusal = deferred<readonly [string, number]>();
    const { sent } = opening(`/system/people/${GRACE.subjectId}`, {
      routes: {
        [`GET ${personAt(GRACE)}`]: [
          panelOf(GRACE),
          panelOf(GRACE, [], ["Payroll"]),
        ],
        [`DELETE ${personAt(GRACE)}`]: [refusal.promise],
      },
    });
    await listed();
    await userEvent.click(
      await screen.findByRole("button", { name: "Remove from the pool" }),
    );
    await userEvent.click(screen.getByRole("link", { name: "000130" }));
    await screen.findByRole("region", { name: "Ada Lovelace" });

    await act(async () => refusal.settle(['{"code":"PERSON_IN_GROUPS"}', 409]));

    await heard(STILL_IN_GROUP);

    expect(saidAs()).toEqual([{ severity: "error", words: STILL_IN_GROUP }]);
    expect(requestsTo(sent)).toEqual([
      `GET ${POOL}?sort=userId`,
      `GET ${personAt(GRACE)}`,
      `DELETE ${personAt(GRACE)}`,
      `GET ${personAt(ADA)}`,
      `GET ${personAt(GRACE)}`,
      `GET ${POOL}?sort=userId`,
    ]);
  });

  /** The read out began before this answer and would land over it; one begun now lands after. */
  it("reads the person again when a change to them lands while a read of them is out", async () => {
    const granted = deferred<readonly [string, number]>();
    const readOut = deferred<readonly [string, number]>();
    const { sent, go } = opening(`/system/people/${GRACE.subjectId}`, {
      routes: {
        [`GET ${personAt(GRACE)}`]: [
          panelOf(GRACE),
          readOut.promise,
          panelOf(GRACE, ["watcher"]),
        ],
        [`PUT ${personAt(GRACE)}/estate-roles/watcher`]: [granted.promise],
      },
    });
    await userEvent.click(
      await screen.findByRole("button", { name: "Grant Watcher" }),
    );
    await go(`/system/people/${ADA.subjectId}`);
    await screen.findByRole("region", { name: "Ada Lovelace" });
    await go(`/system/people/${GRACE.subjectId}`);

    await act(async () => granted.settle(panelOf(GRACE, ["watcher"])));
    await act(async () => readOut.settle(panelOf(GRACE)));

    const panel = await screen.findByRole("region", { name: "Grace Hopper" });
    await waitFor(() =>
      expect(
        within(panel).getByRole("button", { name: "Withdraw Watcher" }),
      ).toBeInTheDocument(),
    );
    expect(
      requestsTo(sent).filter((each) => each === `GET ${personAt(GRACE)}`),
    ).toHaveLength(3);
  });

  /** Anything asked of them now would be asked of a person about to be replaced on screen. */
  it("holds every change to the person while they are read again", async () => {
    const reread = deferred<readonly [string, number]>();
    const { sent } = opening(`/system/people/${GRACE.subjectId}`, {
      routes: {
        [`GET ${personAt(GRACE)}`]: [panelOf(GRACE), reread.promise],
        [`DELETE ${personAt(GRACE)}`]: [['{"code":"PERSON_IN_GROUPS"}', 409]],
      },
    });
    await listed();
    await userEvent.click(
      await screen.findByRole("button", { name: "Remove from the pool" }),
    );
    await screen.findByRole("alert");
    const grant = screen.getByRole("button", { name: "Grant Watcher" });

    grant.focus();
    await userEvent.keyboard("{Enter}");

    expect(grant).toHaveAttribute("aria-disabled", "true");
    expect(requestsTo(sent).filter((each) => each.startsWith("PUT"))).toEqual(
      [],
    );

    await act(async () => reread.settle(panelOf(GRACE, [], ["Payroll"])));

    expect(grant).not.toHaveAttribute("aria-disabled");
  });

  /** One change at a time, whichever control asked it. */
  it("holds Add somebody while a change to a person is out, and a person's controls while somebody is brought in", async () => {
    const granted = deferred<readonly [string, number]>();
    const broughtIn = deferred<readonly [string, number]>();
    opening(`/system/people/${GRACE.subjectId}`, {
      routes: {
        [`PUT ${personAt(GRACE)}/estate-roles/watcher`]: [granted.promise],
        [`POST ${POOL}`]: [broughtIn.promise],
      },
    });
    const grant = await screen.findByRole("button", { name: "Grant Watcher" });

    await userEvent.click(grant);
    const addWhileGranting = screen
      .getByRole("button", { name: "Add somebody" })
      .getAttribute("aria-disabled");
    await act(async () => granted.settle(panelOf(GRACE, ["watcher"])));
    await bringingIn("000150 Hedy Lamarr");

    expect(addWhileGranting).toBe("true");
    expect(
      screen.getByRole("button", { name: "Withdraw Watcher", hidden: true }),
    ).toHaveAttribute("aria-disabled", "true");
  });

  /** A pick is its own address, read on its own, so narrowing the list is no reason to drop it. */
  it("writes what is typed into the address in place of the entry it was on, keeping the order and the person picked", async () => {
    const { sent, at, reached } = opening(
      `/system/people/${GRACE.subjectId}?sort=displayName`,
    );
    await listed();
    await screen.findByRole("region", { name: "Grace Hopper" });

    await typedInto(filterBox(), "gr");

    expect(at()).toBe(
      `/system/people/${GRACE.subjectId}?sort=displayName&filter=gr`,
    );
    expect(reached()).toBe("REPLACE");
    await waitFor(() =>
      expect(requestsTo(sent)).toEqual([
        `GET ${POOL}?sort=displayName`,
        `GET ${personAt(GRACE)}`,
        `GET ${POOL}?sort=displayName&filter=gr`,
      ]),
    );
    expect(screen.getByRole("region", { name: "Grace Hopper" })).toBeVisible();
  });

  it("takes the filter out of the address once the box is emptied", async () => {
    const { at } = opening("/system/people?filter=g&sort=userId");
    await listed();

    await typedInto(filterBox(), "{Backspace}");

    expect(at()).toBe("/system/people?sort=userId");
  });

  it("sorts by the heading pressed, in the address in place of the entry it was on", async () => {
    const { sent, at, reached } = opening("/system/people?filter=a");
    await listed();

    await userEvent.click(screen.getByRole("button", { name: "Name" }));

    expect(at()).toBe("/system/people?filter=a&sort=displayName");
    expect(reached()).toBe("REPLACE");
    await waitFor(() =>
      expect(requestsTo(sent)).toEqual([
        `GET ${POOL}?sort=userId&filter=a`,
        `GET ${POOL}?sort=displayName&filter=a`,
      ]),
    );
    expect(
      screen.getByRole("columnheader", { name: "User number" }),
    ).not.toHaveAttribute("aria-sort");
  });

  it("offers Add somebody level with the heading to a reader who may keep the pool", async () => {
    opening("/system/people");
    await listed();

    const heading = screen.getByRole("heading", { level: 1, name: "People" });

    expect(
      within(heading.parentElement!).getByRole("button", {
        name: "Add somebody",
      }),
    ).toBeInTheDocument();
  });

  /** Opened from an address there is no row it was opened from, and the list is what comes next. */
  it("opens the same address over the list where there is no room, and shutting it goes back to the list with its query, the keyboard on the list", async () => {
    const { at, reached } = opening(
      `/system/people/${GRACE.subjectId}?filter=r`,
      { width: NARROW },
    );
    const panel = await screen.findByRole("dialog", { name: "Grace Hopper" });

    await userEvent.click(within(panel).getByRole("button", { name: "Close" }));
    await settled();

    expect(at()).toBe("/system/people?filter=r");
    expect(reached()).toBe("PUSH");
    expect(screen.queryByRole("dialog")).toBeNull();
    expectKeyboardOnTheList();
  });

  it("hands the keyboard back to the row a covering panel was opened from once it is shut", async () => {
    opening("/system/people", { width: NARROW });
    const link = await screen.findByRole("link", { name: "000140" });
    await userEvent.click(link);
    const panel = await screen.findByRole("dialog", { name: "Grace Hopper" });

    await userEvent.click(within(panel).getByRole("button", { name: "Close" }));
    await settled();

    expect(document.activeElement).toBe(link);
  });

  /** Emptied as it goes, a panel on its way out would flash a wait for somebody nobody asked for. */
  it("keeps the person in a covering panel for as long as it is leaving", async () => {
    const { sent } = opening(`/system/people/${GRACE.subjectId}`, {
      width: NARROW,
    });
    const panel = await screen.findByRole("dialog", { name: "Grace Hopper" });

    await userEvent.click(within(panel).getByRole("button", { name: "Close" }));

    expect(panel.isConnected).toBe(true);
    expect(within(panel).getByText("000140")).toBeInTheDocument();
    expect(within(panel).queryByText("Still reading…")).toBeNull();
    expect(requestsTo(sent)).toEqual([
      `GET ${POOL}?sort=userId`,
      `GET ${personAt(GRACE)}`,
    ]);
  });

  it("links each row to its person, carrying the list's filter and order", async () => {
    opening("/system/people?filter=a&sort=displayName");
    await listed();

    expect(
      screen.getByRole("link", { name: "000140" }).getAttribute("href"),
    ).toBe(`/system/people/${GRACE.subjectId}?filter=a&sort=displayName`);
  });

  it("picks a row by pushing its address, reading that person once and the list no more", async () => {
    const { sent, at, reached } = opening("/system/people?sort=userId");
    const link = await screen.findByRole("link", { name: "000140" });

    await userEvent.click(link);

    const panel = await screen.findByRole("region", { name: "Grace Hopper" });
    expect(at()).toBe(`/system/people/${GRACE.subjectId}?sort=userId`);
    expect(reached()).toBe("PUSH");
    expect(within(panel).getByText("000140")).toBeVisible();
    expect(link).toHaveAttribute("aria-current", "true");
    expect(document.activeElement).toBe(link);
    expect(requestsTo(sent)).toEqual([
      `GET ${POOL}?sort=userId`,
      `GET ${personAt(GRACE)}`,
    ]);
  });

  it("opens an address naming somebody on no page in hand with them picked beside the list", async () => {
    const { sent } = opening(`/system/people/${HEDY.subjectId}`);

    const panel = await screen.findByRole("region", { name: "Hedy Lamarr" });

    expect(within(panel).getByText("000150")).toBeVisible();
    expect(await listed()).toEqual(["000130", "000140"]);
    expect(
      screen
        .getAllByRole("link")
        .filter((each) => each.hasAttribute("aria-current")),
    ).toEqual([]);
    expect(requestsTo(sent)).toEqual([
      `GET ${POOL}?sort=userId`,
      `GET ${personAt(HEDY)}`,
    ]);
  });

  /** A read of the person refused the act is said as the rule of keeping the pool, as the list's is. */
  it("says a read of the person refused the act as the rule of keeping the pool", async () => {
    opening(`/system/people/${GRACE.subjectId}`, {
      routes: {
        [`GET ${personAt(GRACE)}`]: [['{"code":"ACT_NOT_PERMITTED"}', 403]],
      },
    });

    const panel = await screen.findByRole("region", { name: "Person" });

    expect(await within(panel).findByRole("alert")).toHaveTextContent(
      "The pool is seen and changed only by a role that may keep it.",
    );
  });

  it("draws no role control for a reader who may keep the pool but not grant an estate role", async () => {
    opening(`/system/people/${ADA.subjectId}`, { acts: ["keep_pool"] });

    const panel = await screen.findByRole("region", { name: "Ada Lovelace" });

    expect(within(panel).getByText("Held")).toBeVisible();
    expect(
      within(panel)
        .queryAllByRole("button")
        .map((each) => each.textContent),
    ).toEqual(["Remove from the pool"]);
  });

  /** Leaving abandons what is still out, and nothing is taken into a page that has gone. */
  it("abandons a change still out when the reader leaves, and reads nothing after", async () => {
    const granted = deferred<readonly [string, number]>();
    const { sent, leave } = opening(`/system/people/${GRACE.subjectId}`, {
      routes: {
        [`PUT ${personAt(GRACE)}/estate-roles/watcher`]: [granted.promise],
      },
    });
    await listed();
    await userEvent.click(
      await screen.findByRole("button", { name: "Grant Watcher" }),
    );
    const change = sent.mock.results.at(-1)!.value as Promise<Response>;

    await leave();
    await act(async () => granted.settle(panelOf(GRACE, ["watcher"])));

    await expect(change).rejects.toMatchObject({ name: "AbortError" });
    expect(screen.getByText("Elsewhere")).toBeInTheDocument();
    expect(requestsTo(sent)).toEqual([
      `GET ${POOL}?sort=userId`,
      `GET ${personAt(GRACE)}`,
      `PUT ${personAt(GRACE)}/estate-roles/watcher`,
    ]);
  });
});
