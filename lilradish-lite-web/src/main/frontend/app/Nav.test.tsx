import { ThemeProvider } from "@mui/material/styles";
import { render, screen, within } from "@testing-library/react";
import { userEvent } from "@testing-library/user-event";
import type { ReactNode } from "react";
import { MemoryRouter, useLocation } from "react-router";
import { describe, expect, it } from "vitest";

import type { GroupStanding } from "../api/standing";
import { theme } from "../lib/theme/theme";
import { answered, inGroup } from "../testutil/standingRead";
import { Nav } from "./Nav";
import { StandingProvider, useStanding } from "./standing/StandingContext";
import { useGroupInForce } from "./useGroupInForce";

/** `theme.palette.primary.main`, as a resolved colour is reported back. */
const CURRENT_COLOUR = "rgb(11, 87, 208)";

/** `action.active`, which `theme.ts` leaves at Material's own value. */
const RESTING_COLOUR = "rgba(0, 0, 0, 0.54)";

/**
 * What the server sends a reader who may do every gated thing the sidebar
 * offers. Written out rather than read off the destinations: derived, it would
 * agree with them however they were mis-declared.
 */
const EVERYTHING = [
  "keep_pool",
  "keep_group_register",
  "check_soundness",
  "read_measurements",
];

/**
 * Two standings a reader may arrive with, named for the entries they offer and
 * not for the roles that granted them: a role is never on this wire, so a name
 * taken from one would be a copy of a table this side cannot see.
 */
const OFFERS_PEOPLE_AND_GROUPS = ["keep_pool", "keep_group_register"];
const OFFERS_SOUNDNESS_AND_MEASUREMENTS = [
  "check_soundness",
  "read_measurements",
];

const PAYROLL = "00000003-0000-4000-8000-000000000941";
const TRIAGE = "00000003-0000-4000-8000-000000000942";
const FINANCE = "00000003-0000-4000-8000-000000000943";

/** A group whose members this reader may see, and one where they may not. */
const SEES_MEMBERS = inGroup(PAYROLL, "PAYROLL", "Payroll", [
  "read_membership",
  "start_run",
]);
const SEES_NO_MEMBERS = inGroup(TRIAGE, "TRIAGE", "Triage", ["start_run"]);
const ALSO_SEES_MEMBERS = inGroup(FINANCE, "FINANCE", "Finance", [
  "read_membership",
]);

const MINE = [SEES_MEMBERS, SEES_NO_MEMBERS, ALSO_SEES_MEMBERS];

/** The whole of a group's pages, and all of them but the one a permission gates. */
const EVERY_GROUP_PAGE = [
  "Work",
  "Workflows",
  "Questions",
  "Reference lists",
  "Members",
];
const EVERY_GROUP_PAGE_BUT_MEMBERS = EVERY_GROUP_PAGE.slice(0, 4);

/** Where the router says the reader is, drawn where a spec can read it. */
function Address() {
  return <output aria-label="address">{useLocation().pathname}</output>;
}

/** The sidebar as the frame holds it: the group in force is the frame's, not the sidebar's. */
function Sidebar() {
  return <Nav inForce={useGroupInForce(useStanding().groups)} />;
}

function at(
  path: string,
  standing: Iterable<string> = EVERYTHING,
  groups: readonly GroupStanding[] = [],
) {
  return render(<Sidebar />, {
    wrapper: ({ children }: { readonly children: ReactNode }) => (
      <MemoryRouter initialEntries={[path]}>
        {/* The router is what marks an entry current; the palette is what turns
            the mark into a colour, and an unresolved one is silently dropped. */}
        <ThemeProvider theme={theme}>
          <StandingProvider read={answered(standing, groups)}>
            {children}
            <Address />
          </StandingProvider>
        </ThemeProvider>
      </MemoryRouter>
    ),
  });
}

function labels(): string[] {
  return screen.queryAllByRole("link").map((link) => link.textContent ?? "");
}

/** What a screen reader is told is the current place, in the order rendered. */
function current(): string[] {
  return screen
    .getAllByRole("link")
    .filter((link) => link.getAttribute("aria-current") === "page")
    .map((link) => link.textContent ?? "");
}

/** What a list holds, found by its accessible name. */
function itemsUnder(heading: string): string[] {
  return within(screen.getByRole("list", { name: heading }))
    .getAllByRole("listitem")
    .map((item) => item.textContent ?? "");
}

function iconColour(destination: string): string {
  const icon = screen.getByRole("link", {
    name: destination,
  }).firstElementChild;
  if (icon === null) {
    throw new Error(`${destination} is rendered without an icon`);
  }
  return getComputedStyle(icon).color;
}

function picker(): HTMLElement {
  return screen.getByRole("combobox", { name: "Group" });
}

async function picking(group: GroupStanding) {
  await userEvent.click(picker());
  await userEvent.click(
    screen.getByRole("option", { name: `${group.name} ${group.key}` }),
  );
}

function address(): string {
  return screen.getByRole("status", { name: "address" }).textContent ?? "";
}

describe("Nav", () => {
  /**
   * Nothing smaller than a place. A person, a group and a reading are one
   * thing's detail and are reached through the screen that lists them; a
   * sidebar that grew an entry per person would stop being a map, which is the
   * only thing it is for.
   */
  it("offers every place the estate has, and nothing smaller", () => {
    at("/");

    expect(labels()).toEqual(["People", "Groups", "Soundness", "Measurements"]);
  });

  /**
   * A heading a reader can see and the name the group is announced by are the
   * same words in the same place — which is only true while the heading sits
   * beside the list rather than inside it as one more thing in it.
   */
  it("heads the group where it can be read and lists that group's own", () => {
    at("/");

    expect(itemsUnder("System management")).toEqual([
      "People",
      "Groups",
      "Soundness",
      "Measurements",
    ]);
  });

  /**
   * The direction this has to fail in, and the whole of what a reader holding
   * no estate role is shown. Not a dimmed row, not a row that refuses when it
   * is reached, and not a heading standing over nothing: no word anywhere says
   * that a system management section is a thing this application has.
   */
  it("says nothing at all to a reader the server named nothing for", () => {
    at("/", []);

    expect(labels()).toEqual([]);
    expect(screen.queryByText("System management")).toBeNull();
    expect(screen.queryByRole("list")).toBeNull();
    expect(screen.queryByRole("combobox")).toBeNull();
  });

  /** A place published after this build is a place nothing here leads to. */
  it("opens no entry on an act this build does not know", () => {
    at("/", ["commission_a_satellite"]);

    expect(labels()).toEqual([]);
  });

  /**
   * Two disjoint standings, each drawn on its own. Neither adds up to the other,
   * so what a reader holding one is offered is exactly half of this sidebar and
   * the other half is not drawn — which is the same rule the server enforces,
   * read here as what a person actually sees.
   */
  it.each([
    ["people and groups", OFFERS_PEOPLE_AND_GROUPS, ["People", "Groups"]],
    [
      "soundness and measurements",
      OFFERS_SOUNDNESS_AND_MEASUREMENTS,
      ["Soundness", "Measurements"],
    ],
  ])(
    "offers a standing naming %s exactly those places",
    (_named, standing, offered) => {
      at("/", standing);

      expect(labels()).toEqual(offered);
      expect(itemsUnder("System management")).toEqual(offered);
    },
  );

  /**
   * A heading kept over an emptied group names nothing, and the list that
   * points back at it for its own name is left holding nothing — so a screen
   * reader is offered a group, announces it, and finds it empty.
   */
  it("takes the group's heading away with the last entry under it", () => {
    at("/", []);

    expect(screen.queryByText("System management")).toBeNull();
    expect(
      screen.queryByRole("list", { name: "System management" }),
    ).toBeNull();
  });

  /**
   * The router decides which entry is current, and every address under it
   * begins with the same prefix — which is what makes a comparison written by
   * hand here mark two entries at once. The lenses are separate paths for the
   * same reason: a search string is not part of that decision.
   */
  it.each([
    ["a screen of its own", "/system/groups", "Groups"],
    ["one whose address is a prefix of no other", "/system/people", "People"],
    ["the last of them", "/system/measurements", "Measurements"],
  ])("marks %s, and marks nothing else", (_case, path, marked) => {
    at(path);

    expect(current()).toEqual([marked]);
  });

  it("paints the entry being looked at and leaves every other one resting", () => {
    at("/system/groups");

    expect(iconColour("Groups")).toBe(CURRENT_COLOUR);
    expect(iconColour("People")).toBe(RESTING_COLOUR);
  });

  /** The order a reader's day runs in, with administration last at both levels. */
  it("draws what waits on the reader, then the group in force and its pages, then the estate's", () => {
    at("/", OFFERS_PEOPLE_AND_GROUPS, [SEES_MEMBERS, SEES_NO_MEMBERS]);

    expect(labels()).toEqual([
      "My work",
      ...EVERY_GROUP_PAGE,
      "People",
      "Groups",
    ]);
    expect(picker()).toHaveValue("Payroll");
  });

  /** Only what the reader's permissions in the group in force reach is drawn; another group's lend nothing. */
  it.each([
    [
      "may see the members there",
      SEES_MEMBERS,
      SEES_NO_MEMBERS,
      EVERY_GROUP_PAGE,
    ],
    ["may not", SEES_NO_MEMBERS, SEES_MEMBERS, EVERY_GROUP_PAGE_BUT_MEMBERS],
  ])(
    "draws for a reader who %s exactly the pages that reaches",
    (_case, inForce, other, drawn) => {
      at(`/groups/${inForce.groupId}/work`, [], [other, inForce]);

      expect(itemsUnder(inForce.name)).toEqual(drawn);
      expect(labels()).toEqual(["My work", ...drawn]);
    },
  );

  it("leads each page of the group in force to that group's own address", () => {
    at("/", [], [SEES_MEMBERS]);

    expect(
      within(screen.getByRole("list", { name: "Payroll" }))
        .getAllByRole("link")
        .map((link) => link.getAttribute("href")),
    ).toEqual([
      `/groups/${PAYROLL}/work`,
      `/groups/${PAYROLL}/workflows`,
      `/groups/${PAYROLL}/questions`,
      `/groups/${PAYROLL}/reference-lists`,
      `/groups/${PAYROLL}/members`,
    ]);
    expect(screen.getByRole("link", { name: "My work" })).toHaveAttribute(
      "href",
      "/my-work",
    );
  });

  /** No group, no group's pages, nothing gathering from groups, whatever the estate lets them do. */
  it("names no group and draws no group's pages for a reader in none, whatever they hold in the estate", () => {
    at("/", EVERYTHING, []);

    expect(screen.queryByRole("combobox")).toBeNull();
    expect(labels()).toEqual(["People", "Groups", "Soundness", "Measurements"]);
    expect(screen.queryByText("My work")).toBeNull();
  });

  it("names the group the address is in, rather than the first", () => {
    at(`/groups/${TRIAGE}/workflows`, [], [SEES_MEMBERS, SEES_NO_MEMBERS]);

    expect(picker()).toHaveValue("Triage");
    expect(current()).toEqual(["Workflows"]);
  });

  /** An address naming a group the reader is not in draws no group of its own: it is not there. */
  it("names the first of the reader's groups where the address names a group they are not in", () => {
    at(
      "/groups/00000003-0000-4000-8000-000000000949/work",
      [],
      [SEES_MEMBERS, SEES_NO_MEMBERS],
    );

    expect(picker()).toHaveValue("Payroll");
    expect(current()).toEqual([]);
  });

  /** Off a group's pages the page belongs to no group, so a pick redraws the sidebar and nothing else. */
  it("draws the group picked off a group's pages, leaving the reader on the page they were on", async () => {
    at("/system/people", OFFERS_PEOPLE_AND_GROUPS, [
      SEES_MEMBERS,
      SEES_NO_MEMBERS,
    ]);

    await picking(SEES_NO_MEMBERS);

    expect(picker()).toHaveValue("Triage");
    expect(itemsUnder("Triage")).toEqual(EVERY_GROUP_PAGE_BUT_MEMBERS);
    expect(screen.queryByRole("list", { name: "Payroll" })).toBeNull();
    expect(address()).toBe("/system/people");
  });

  it("opens on a group's page the same page in the group picked", async () => {
    at(`/groups/${PAYROLL}/workflows`, [], MINE);

    await picking(SEES_NO_MEMBERS);

    expect(address()).toBe(`/groups/${TRIAGE}/workflows`);
    expect(picker()).toHaveValue("Triage");
    expect(current()).toEqual(["Workflows"]);
  });

  it("opens Members in the group picked where the reader may see the members there too", async () => {
    at(`/groups/${PAYROLL}/members`, [], MINE);

    await picking(ALSO_SEES_MEMBERS);

    expect(address()).toBe(`/groups/${FINANCE}/members`);
    expect(current()).toEqual(["Members"]);
  });

  /** A move is not a refusal: where Members is not reached, the first page that is opens. */
  it("opens the first page the group picked reaches where it does not reach Members", async () => {
    at(`/groups/${PAYROLL}/members`, [], MINE);

    await picking(SEES_NO_MEMBERS);

    expect(address()).toBe(`/groups/${TRIAGE}/work`);
    expect(current()).toEqual(["Work"]);
    expect(itemsUnder("Triage")).toEqual(EVERY_GROUP_PAGE_BUT_MEMBERS);
  });

  /** Leaving a group's pages for the estate's keeps the group that was in force, not the first. */
  it("keeps the group last in force once the reader leaves its pages", async () => {
    at(`/groups/${TRIAGE}/work`, OFFERS_PEOPLE_AND_GROUPS, [
      SEES_MEMBERS,
      SEES_NO_MEMBERS,
    ]);

    await userEvent.click(screen.getByRole("link", { name: "People" }));

    expect(address()).toBe("/system/people");
    expect(picker()).toHaveValue("Triage");
  });
});
