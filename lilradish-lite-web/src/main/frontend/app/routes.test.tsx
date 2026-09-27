import { ThemeProvider } from "@mui/material/styles";
import { act, render, screen, waitFor, within } from "@testing-library/react";
import { userEvent } from "@testing-library/user-event";
import { RouterProvider, createMemoryRouter } from "react-router";
import { describe, expect, it, onTestFinished, vi } from "vitest";

import { theme } from "../lib/theme/theme";
import { requestsTo, serving, type Reply } from "../testutil/answering";
import { deferred } from "../testutil/deferred";
import { laidOutAt } from "../testutil/layout";
import { Nav } from "./Nav";
import { ROUTES } from "./routes";
import { SystemPage } from "./SystemPage";

// Doubles so a case can make a screen, or the frame, throw while drawn; every
// other case draws the real one, which nothing the server sends makes throw.
vi.mock("./SystemPage", async (importOriginal) => {
  const actual = await importOriginal<typeof import("./SystemPage")>();
  return { SystemPage: vi.fn(actual.SystemPage) };
});
vi.mock("./Nav", async (importOriginal) => {
  const actual = await importOriginal<typeof import("./Nav")>();
  return { Nav: vi.fn(actual.Nav) };
});

/**
 * Standings named for what they open, never for the role that granted them:
 * the wire carries acts, not roles.
 */
const OPENS_PEOPLE_AND_GROUPS = ["keep_pool", "keep_group_register"];
const OPENS_SOUNDNESS_AND_MEASUREMENTS = [
  "check_soundness",
  "read_measurements",
];

const OPENS_PEOPLE_AND_GROUPS_AND_GRANTS_ROLES = [
  "keep_pool",
  "keep_group_register",
  "grant_estate_role",
];

/** Each page's refusal, which names the rule of the act that page asks for. */
const REFUSED = {
  people: "The pool is seen and changed only by a role that may keep it.",
  groups:
    "The register of groups is seen and changed only by a role that may keep it.",
  soundness: "Soundness is read and checked only by a role that may check it.",
  measurements:
    "Measurements is read only by a role that may read what the estate measures.",
};

const GRACE = {
  subjectId: "00000002-0000-4000-8000-000000000140",
  userId: "000140",
  displayName: "Grace Hopper",
};

const POOL: Reply = [
  JSON.stringify({
    items: [{ ...GRACE, estateRoles: ["steward"], groupCount: 0 }],
  }),
  200,
];

const REGISTER: Reply = [
  JSON.stringify({
    items: [
      {
        groupId: "00000003-0000-4000-8000-000000000701",
        key: "PAYROLL",
        name: "Payroll",
        canBeAdministered: true,
        memberCount: 2,
      },
    ],
  }),
  200,
];

/** A group as the standing sends it. */
interface GroupSent {
  readonly groupId: string;
  readonly key: string;
  readonly name: string;
  readonly permissions: readonly string[];
}

const SEES_MEMBERS: GroupSent = {
  groupId: "00000003-0000-4000-8000-000000000951",
  key: "PAYROLL",
  name: "Payroll",
  permissions: ["read_membership", "start_run"],
};

const SEES_NO_MEMBERS: GroupSent = {
  groupId: "00000003-0000-4000-8000-000000000952",
  key: "TRIAGE",
  name: "Triage",
  permissions: ["start_run"],
};

/** Every permission there is, which a group's founder holds. */
const FOUNDED: GroupSent = {
  groupId: "00000003-0000-4000-8000-000000000953",
  key: "INTAKE",
  name: "Intake",
  permissions: ["read_membership", "change_membership", "start_run"],
};

const NOT_THEIRS = "00000003-0000-4000-8000-000000000959";

const MEMBERS_REFUSED =
  "A group's members are seen only by a role in it that may see them.";

/** The members page's heading, which names the group set apart from the words around it. */
const PAYROLL_MEMBERS = `Members of ${String.fromCodePoint(0x2068)}Payroll${String.fromCodePoint(0x2069)}`;

/** The members of the group whose members the reader may see, and its currency, as the server answers them. */
const LISTED_MEMBERS = {
  [`GET /api/groups/${SEES_MEMBERS.groupId}/members`]: [['{"items":[]}', 200]],
  [`GET /api/groups/${SEES_MEMBERS.groupId}/currency`]: [["{}", 200]],
} as const satisfies Record<string, readonly Reply[]>;

/** What the group whose members the reader may not see has running, and may start, as the server answers them. */
const TRIAGE_WORK = {
  [`GET /api/groups/${SEES_NO_MEMBERS.groupId}/runs`]: [
    ['{"items":[],"reading":"own"}', 200],
  ],
  [`GET /api/groups/${SEES_NO_MEMBERS.groupId}/offered-workflows`]: [
    ['{"workflows":[]}', 200],
  ],
} as const satisfies Record<string, readonly Reply[]>;

/** The work page's heading, which names the group set apart as the members page's does. */
const TRIAGE_WORK_HEADING = `Work of ${String.fromCodePoint(0x2068)}Triage${String.fromCodePoint(0x2069)}`;

const GRACE_PANEL: Reply = [
  JSON.stringify({
    ...GRACE,
    estateRoles: ["steward"],
    lastGrantingRoles: [],
    groups: [],
    seeded: false,
  }),
  200,
];

/**
 * This environment implements no `matchMedia`, and the hook reading it answers
 * `false` to every query when it is missing — so without this the shell would
 * believe every viewport narrow, keep its drawer shut, and render none of the
 * navigation these assertions are about.
 */
function wideViewport() {
  vi.stubGlobal("matchMedia", (query: string) => ({
    matches: /min-width/.test(query),
    media: query,
    addEventListener: () => {},
    removeEventListener: () => {},
  }));
}

/**
 * The whole application at one address, reading what it reads the way it does
 * in a browser: through each endpoint, over a real `Response`. Stubbing the
 * hooks instead would leave the wiring between the screens untested.
 *
 * Nothing is laid out here, so a panel measuring its own box finds no room
 * beside its list and covers it.
 */
function opening(
  path: string,
  acts: readonly string[],
  routes: Readonly<Record<string, readonly Reply[]>> = {},
  groups: readonly GroupSent[] = [],
) {
  wideViewport();
  laidOutAt(0, 1920);
  const sent = serving({
    "GET /api/standing": [[JSON.stringify({ acts, groups }), 200]],
    "GET /api/pool/people": [POOL],
    [`GET /api/pool/people/${GRACE.subjectId}`]: [GRACE_PANEL],
    "GET /api/groups": [REGISTER],
    ...routes,
  });
  render(
    <ThemeProvider theme={theme}>
      <RouterProvider
        router={createMemoryRouter(ROUTES, { initialEntries: [path] })}
      />
    </ThemeProvider>,
  );
  return sent;
}

/** The same address, opened while the endpoint has yet to answer at all. */
function openingBeforeTheServerAnswers(path: string) {
  wideViewport();
  const held = deferred<Response>();
  vi.stubGlobal("fetch", vi.fn<typeof fetch>().mockReturnValue(held.promise));
  return render(
    <ThemeProvider theme={theme}>
      <RouterProvider
        router={createMemoryRouter(ROUTES, { initialEntries: [path] })}
      />
    </ThemeProvider>,
  );
}

function offered(): string[] {
  return screen.queryAllByRole("link").map((link) => link.textContent ?? "");
}

const BROKEN_BY = "Cannot read properties of undefined (reading 'name')";

/** The soundness screen throws while drawn, every other screen as it is; quiet, since React logs it. */
function breakingTheSoundnessScreen() {
  const drawn = vi.mocked(SystemPage);
  const real = drawn.getMockImplementation()!;
  drawn.mockImplementation((props) => {
    if (props.title === "destination.soundness") {
      throw new TypeError(BROKEN_BY);
    }
    return real(props);
  });
  onTestFinished(() => {
    drawn.mockReset();
  });
  vi.spyOn(console, "error").mockImplementation(() => {});
}

/** The sidebar throws while drawn, and with it the frame; quiet, since React logs it. */
function breakingTheFrame() {
  const drawn = vi.mocked(Nav);
  drawn.mockImplementation(() => {
    throw new TypeError(BROKEN_BY);
  });
  onTestFinished(() => {
    drawn.mockReset();
  });
  vi.spyOn(console, "error").mockImplementation(() => {});
}

describe("ROUTES", () => {
  it("opens a screen this reader's standing names, with nothing refused on it", async () => {
    opening("/system/people", OPENS_PEOPLE_AND_GROUPS);

    expect(
      await screen.findByRole("heading", { level: 1, name: "People" }),
    ).toBeInTheDocument();
    expect(screen.queryByRole("alert")).toBeNull();
  });

  /** The row may be on no page in hand, so the person is read on their own. */
  it("opens the pool with the person an address names picked, read on their own", async () => {
    const sent = opening(`/system/people/${GRACE.subjectId}`, ["keep_pool"]);

    const panel = await screen.findByRole("dialog", { name: "Grace Hopper" });

    expect(within(panel).getByText("000140")).toBeInTheDocument();
    expect(requestsTo(sent)).toEqual([
      "GET /api/standing",
      "GET /api/pool/people?sort=userId",
      `GET /api/pool/people/${GRACE.subjectId}`,
    ]);
  });

  /**
   * One route for the list and a row picked in it: picking is then the same
   * screen with more in its address, and the list already read stays read.
   */
  it("picks a row without reading the list again or leaving the screen", async () => {
    const sent = opening("/system/people", ["keep_pool"]);
    const row = await screen.findByRole("link", { name: "000140" });

    await userEvent.click(row);

    expect(
      await screen.findByRole("dialog", { name: "Grace Hopper" }),
    ).toBeInTheDocument();
    expect(requestsTo(sent)).toEqual([
      "GET /api/standing",
      "GET /api/pool/people?sort=userId",
      `GET /api/pool/people/${GRACE.subjectId}`,
    ]);
    expect(row.isConnected).toBe(true);
  });

  /** A standing that keeps no pool opens nothing of it. */
  it("refuses the person an address names to a standing that keeps no pool, asking the server nothing about it", async () => {
    const sent = opening(
      `/system/people/${GRACE.subjectId}`,
      OPENS_SOUNDNESS_AND_MEASUREMENTS,
    );

    expect(await screen.findByRole("alert")).toHaveTextContent(REFUSED.people);
    expect(screen.queryByRole("heading", { name: "People" })).toBeNull();
    expect(requestsTo(sent)).toEqual(["GET /api/standing"]);
  });

  /**
   * What the reader may do is read again after they change an estate role, so
   * a steward who withdraws their own steward role is shown the wall at once
   * rather than controls the server would refuse.
   */
  it("reads the reader's own standing again after an estate role changes, and stops at the wall it now draws", async () => {
    const sent = opening(`/system/people/${GRACE.subjectId}`, [], {
      "GET /api/standing": [
        [
          JSON.stringify({ acts: OPENS_PEOPLE_AND_GROUPS_AND_GRANTS_ROLES }),
          200,
        ],
        [JSON.stringify({ acts: [] }), 200],
      ],
      [`DELETE /api/pool/people/${GRACE.subjectId}/estate-roles/steward`]: [
        [
          JSON.stringify({
            ...GRACE,
            estateRoles: [],
            lastGrantingRoles: [],
            groups: [],
            seeded: false,
          }),
          200,
        ],
      ],
    });

    await userEvent.click(
      await screen.findByRole("button", { name: "Withdraw Steward" }),
    );

    expect(await screen.findByRole("alert")).toHaveTextContent(REFUSED.people);
    expect(screen.queryByRole("button", { name: /Withdraw|Grant/ })).toBeNull();
    expect(offered()).toEqual(["Skip to content"]);
    expect(
      requestsTo(sent).filter((each) => each === "GET /api/standing"),
    ).toHaveLength(2);
  });

  /**
   * The page stays drawn while the standing is read again, with no wait said
   * over it to push it down; only what it offers changes once the answer lands.
   */
  it("offers the destinations a role granted to the reader themselves reaches, keeping the page as it was while it reads", async () => {
    const readAgain = deferred<readonly [string, number]>();
    opening(`/system/people/${GRACE.subjectId}`, [], {
      "GET /api/standing": [
        [
          JSON.stringify({ acts: OPENS_PEOPLE_AND_GROUPS_AND_GRANTS_ROLES }),
          200,
        ],
        readAgain.promise,
      ],
      [`PUT /api/pool/people/${GRACE.subjectId}/estate-roles/watcher`]: [
        [
          JSON.stringify({
            ...GRACE,
            estateRoles: ["steward", "watcher"],
            lastGrantingRoles: [],
            groups: [],
            seeded: false,
          }),
          200,
        ],
      ],
    });
    const grant = await screen.findByRole("button", { name: "Grant Watcher" });
    const heading = screen.getByRole("heading", {
      level: 1,
      name: "People",
      hidden: true,
    });
    const before = heading.previousElementSibling;

    await userEvent.click(grant);
    await waitFor(() => expect(grant).toHaveTextContent("Withdraw Watcher"));
    const whileReading = {
      waiting: screen.queryAllByText("Still reading…", { exact: true }).length,
      heading: heading.isConnected,
      before: heading.previousElementSibling === before,
    };
    await act(async () =>
      readAgain.settle([
        JSON.stringify({
          acts: [
            ...OPENS_PEOPLE_AND_GROUPS_AND_GRANTS_ROLES,
            ...OPENS_SOUNDNESS_AND_MEASUREMENTS,
          ],
        }),
        200,
      ]),
    );

    expect(whileReading).toEqual({ waiting: 0, heading: true, before: true });
    const site = screen.getByRole("navigation", { name: "Site", hidden: true });
    await waitFor(() =>
      expect(
        within(site)
          .getAllByRole("link", { hidden: true })
          .map((link) => link.textContent),
      ).toEqual(["People", "Groups", "Soundness", "Measurements"]),
    );
    expect(grant.isConnected).toBe(true);
    expect(grant).toHaveTextContent("Withdraw Watcher");
    expect(screen.queryByRole("alert")).toBeNull();
  });

  /**
   * Refused where the reader is standing. The frame is still there and so is
   * every destination they do reach, so the way out is the way they came in —
   * not a redirect, which takes the address they followed away without saying
   * what became of it, and not "no such screen", which is a lie about a screen
   * that exists.
   */
  it("refuses a screen the reader's standing does not name, leaving the frame and the sidebar standing", async () => {
    opening("/system/soundness", OPENS_PEOPLE_AND_GROUPS);

    expect(await screen.findByRole("alert")).toHaveTextContent(
      REFUSED.soundness,
    );
    expect(screen.queryByRole("heading", { name: "Soundness" })).toBeNull();

    // The frame did not go with the screen: what this reader does reach is
    // still on offer, which is what makes the refusal survivable.
    expect(offered()).toEqual(["Skip to content", "People", "Groups"]);
  });

  it("answers a screen that breaks while drawn inside the frame, the sidebar still standing", async () => {
    breakingTheSoundnessScreen();
    opening("/system/soundness", OPENS_SOUNDNESS_AND_MEASUREMENTS);

    expect(
      await screen.findByRole("heading", {
        level: 1,
        name: "Something went wrong on this page.",
      }),
    ).toBeInTheDocument();
    expect(offered()).toEqual(["Skip to content", "Soundness", "Measurements"]);
    expect(screen.queryByText(BROKEN_BY)).toBeNull();
    expect(screen.queryByRole("heading", { name: "Soundness" })).toBeNull();
    expect(screen.queryByText("Something went wrong.")).toBeNull();
  });

  /** The sidebar went with it, so the words point at no menu, and nothing of the frame is left drawn. */
  it("answers the frame itself breaking with a screen of its own, pointing at no menu", async () => {
    breakingTheFrame();
    opening("/system/soundness", OPENS_SOUNDNESS_AND_MEASUREMENTS);

    expect(
      await screen.findByRole("heading", {
        level: 1,
        name: "Something went wrong.",
      }),
    ).toBeInTheDocument();
    expect(screen.getByRole("alert")).toHaveTextContent(
      "Nothing you saved is lost. Reload to carry on.",
    );
    expect(screen.queryByText(/menu/)).toBeNull();
    expect(screen.queryByRole("navigation")).toBeNull();
    expect(offered()).toEqual([]);
    expect(screen.queryByText(BROKEN_BY)).toBeNull();
  });

  it("leaves a screen that broke for another page through the sidebar, which draws as it would have", async () => {
    breakingTheSoundnessScreen();
    opening("/system/soundness", OPENS_SOUNDNESS_AND_MEASUREMENTS, {
      "GET /api/measurements": [['{"models":[],"system":[]}', 200]],
    });
    await screen.findByText("Something went wrong on this page.");

    await userEvent.click(screen.getByRole("link", { name: "Measurements" }));

    expect(
      await screen.findByRole("heading", { level: 1, name: "Measurements" }),
    ).toBeInTheDocument();
    expect(screen.queryByText("Something went wrong on this page.")).toBeNull();
  });

  /**
   * Two disjoint standings: neither adds up to the other, so each opens its own
   * screens and meets the wall on the other's.
   */
  it.each([
    ["people and groups", OPENS_PEOPLE_AND_GROUPS, "/system/groups", "Groups"],
    [
      "soundness and measurements",
      OPENS_SOUNDNESS_AND_MEASUREMENTS,
      "/system/soundness",
      "Soundness",
    ],
  ])(
    "opens for a standing naming %s the screen that standing names",
    async (_named, standing, path, screenName) => {
      opening(path, standing);

      expect(
        await screen.findByRole("heading", { level: 1, name: screenName }),
      ).toBeInTheDocument();
      expect(screen.queryByRole("alert")).toBeNull();
    },
  );

  it.each([
    [
      "people and groups",
      OPENS_PEOPLE_AND_GROUPS,
      "/system/measurements",
      REFUSED.measurements,
    ],
    [
      "soundness and measurements",
      OPENS_SOUNDNESS_AND_MEASUREMENTS,
      "/system/groups",
      REFUSED.groups,
    ],
  ])(
    "stops a standing naming %s at a screen the other standing names",
    async (_named, standing, path, refused) => {
      opening(path, standing);

      expect(await screen.findByRole("alert")).toHaveTextContent(refused);
      expect(screen.queryByRole("heading", { level: 1 })).toBeNull();
    },
  );

  /**
   * The whole of the withholding, read as a person would meet it. A reader the
   * server named nothing for lands somewhere that works, and no word anywhere
   * on the page — not a heading, not a dimmed row, not a control drawn and dead
   * — says that a system management section is something this application has.
   */
  it("shows a reader holding nothing a page that says nothing about what else exists", async () => {
    opening("/", []);

    expect(await screen.findByRole("heading", { level: 1 })).toHaveTextContent(
      "lilradish",
    );
    expect(offered()).toEqual(["Skip to content"]);
    expect(screen.queryByText(/system management/i)).toBeNull();
    expect(
      screen.queryByText(/people|groups|soundness|measurements/i),
    ).toBeNull();
    expect(screen.queryByRole("alert")).toBeNull();
  });

  /**
   * The start page leads nowhere on its own. A redirect from here would have to
   * choose a destination, and for a reader holding nothing there is none to
   * choose — so it would either land them on a refusal or first have to say
   * what they may reach.
   */
  it("leaves a reader on the start page rather than sending them somewhere", async () => {
    opening("/", OPENS_PEOPLE_AND_GROUPS);

    expect(await screen.findByRole("heading", { level: 1 })).toHaveTextContent(
      "lilradish",
    );
    expect(screen.queryByText("Nothing is built here yet.")).toBeNull();
  });

  /**
   * The start page reads nothing this endpoint answers, and is the one screen
   * every reader may be on. So it is drawn while the read is still out rather
   * than behind it — otherwise arriving anywhere at all costs a round trip and
   * a database query for an answer the screen never looks at.
   */
  it("draws the start page before the endpoint has answered at all", async () => {
    openingBeforeTheServerAnswers("/");

    expect(await screen.findByRole("heading", { level: 1 })).toHaveTextContent(
      "lilradish",
    );
    expect(screen.queryByText("Still reading…")).toBeNull();

    // And the navigation still says nothing, which is the direction the
    // unanswered read has to fail in.
    expect(offered()).toEqual(["Skip to content"]);
  });

  /**
   * The other side of the same read, and the reason it is still one read. A
   * screen that is guarded cannot be drawn on an emptiness that only means "not
   * yet", so it waits — and says so rather than showing the wall it would show
   * a reader who really holds nothing.
   */
  it("makes a guarded screen wait on the read rather than refusing on what it holds so far", async () => {
    openingBeforeTheServerAnswers("/system/people");

    expect(await screen.findByText("Still reading…")).toBeInTheDocument();
    expect(screen.queryByRole("alert")).toBeNull();
    expect(screen.queryByRole("heading", { name: "People" })).toBeNull();
  });

  /**
   * The other half of the refusal: an address that names nothing is answered as
   * missing, which is exactly what a screen that exists and is withheld must
   * never be answered as.
   */
  it("answers an address naming no screen as missing rather than as withheld, the sidebar still standing", async () => {
    opening("/system/budgets", OPENS_PEOPLE_AND_GROUPS);

    expect(await screen.findByText("No such screen")).toBeInTheDocument();
    await screen.findByRole("link", { name: "People" });
    expect(offered()).toEqual([
      "Skip to content",
      "People",
      "Groups",
      "Back to the start",
    ]);
    expect(screen.queryByText(/opens only for a role/)).toBeNull();
    expect(screen.queryByText(/Something went wrong/)).toBeNull();
  });

  /**
   * Signing in, somebody in groups is shown one of them in force with its
   * pages beneath it, without being sent anywhere to see it.
   */
  it("names the first of the reader's groups in force on arrival, and draws its pages", async () => {
    opening("/", [], {}, [SEES_MEMBERS, SEES_NO_MEMBERS]);

    expect(await screen.findByRole("combobox", { name: "Group" })).toHaveValue(
      "Payroll",
    );
    expect(offered()).toEqual([
      "Skip to content",
      "My work",
      "Work",
      "Workflows",
      "Questions",
      "Reference lists",
      "Members",
    ]);
    expect(screen.getByRole("heading", { level: 1 })).toHaveTextContent(
      "lilradish",
    );
  });

  it("names no group and draws no group's pages for a reader in none", async () => {
    opening("/", OPENS_PEOPLE_AND_GROUPS);

    expect(
      await screen.findByRole("link", { name: "People" }),
    ).toBeInTheDocument();
    expect(screen.queryByRole("combobox")).toBeNull();
    expect(offered()).toEqual(["Skip to content", "People", "Groups"]);
  });

  it.each([
    [
      "membership alone reaches",
      `/groups/${SEES_NO_MEMBERS.groupId}/work`,
      TRIAGE_WORK_HEADING,
    ],
    [
      "the reader's permissions there reach",
      `/groups/${SEES_MEMBERS.groupId}/members`,
      PAYROLL_MEMBERS,
    ],
  ])(
    "opens a page of a group the reader is in that %s",
    async (_case, path, heading) => {
      opening(path, [], { ...LISTED_MEMBERS, ...TRIAGE_WORK }, [
        SEES_MEMBERS,
        SEES_NO_MEMBERS,
      ]);

      expect(
        await screen.findByRole("heading", { level: 1, name: heading }),
      ).toBeInTheDocument();
      expect(screen.queryByRole("alert")).toBeNull();
    },
  );

  /** The library is reached by membership alone, so a reader who may see nothing else there reaches each kind. */
  it.each([
    ["workflows", "Workflows"],
    ["questions", "Questions"],
    ["reference-lists", "Reference lists"],
  ])(
    "opens the group's %s to a reader holding nothing there but membership",
    async (segment, heading) => {
      const sent = opening(
        `/groups/${SEES_NO_MEMBERS.groupId}/${segment}`,
        [],
        {
          [`GET /api/groups/${SEES_NO_MEMBERS.groupId}/${segment}`]: [
            ['{"items":[]}', 200],
          ],
        },
        [SEES_NO_MEMBERS],
      );

      expect(
        await screen.findByRole("heading", {
          level: 1,
          name: `${heading} of ${String.fromCodePoint(0x2068)}Triage${String.fromCodePoint(0x2069)}`,
        }),
      ).toBeInTheDocument();
      expect(screen.queryByRole("alert")).toBeNull();
      expect(requestsTo(sent)).toContain(
        `GET /api/groups/${SEES_NO_MEMBERS.groupId}/${segment}?sort=name`,
      );
    },
  );

  /** An entry opens a page of its own under its kind's, reached as the kind's page is. */
  it("opens an entry's own page in a group the reader is in, by membership alone", async () => {
    const entry = "00000006-0000-4000-8000-000000000951";
    opening(
      `/groups/${SEES_NO_MEMBERS.groupId}/questions/${entry}`,
      [],
      {
        [`GET /api/groups/${SEES_NO_MEMBERS.groupId}/questions/${entry}`]: [
          [
            JSON.stringify({
              entryId: entry,
              kind: "question",
              name: "Sort claims",
              acts: [],
              versions: [],
            }),
            200,
          ],
        ],
      },
      [SEES_NO_MEMBERS],
    );

    expect(
      await screen.findByRole("heading", { level: 1, name: /Sort claims/ }),
    ).toBeInTheDocument();
    expect(screen.queryByRole("alert")).toBeNull();
    expect(
      screen.queryByRole("table", { name: "The group's questions" }),
    ).toBeNull();
    expect(screen.queryByText("Nothing is built here yet.")).toBeNull();
  });

  it("answers an entry's page in a group the reader is not in as missing, asking nothing of it", async () => {
    const entry = "00000006-0000-4000-8000-000000000952";
    const sent = opening(`/groups/${NOT_THEIRS}/questions/${entry}`, [], {}, [
      SEES_MEMBERS,
    ]);

    expect(
      await screen.findByRole("heading", { level: 1, name: "No such screen" }),
    ).toBeInTheDocument();
    expect(requestsTo(sent)).toEqual(["GET /api/standing"]);
  });

  /** A run opens under Work, reached as Work is; what the reader may read of it is the server's. */
  it("opens a run under Work in a group the reader is in, by membership alone", async () => {
    const run = "00000008-0000-4000-8000-000000000951";
    opening(
      `/groups/${SEES_NO_MEMBERS.groupId}/work/${run}`,
      [],
      {
        ...TRIAGE_WORK,
        [`GET /api/groups/${SEES_NO_MEMBERS.groupId}/runs/${run}/steps`]: [
          [
            JSON.stringify({
              run: {
                runId: run,
                number: 4,
                versionId: "00000007-0000-4000-8000-000000000951",
                state: "running",
                acts: [],
                progress: { done: 0, of: 0 },
              },
              declarations: {},
              gaveBack: { declares: "nothing" },
              steps: [],
            }),
            200,
          ],
        ],
        [`GET /api/groups/${SEES_NO_MEMBERS.groupId}/runs/${run}`]: [
          [
            JSON.stringify({
              runId: run,
              number: 4,
              name: "Claim from Ada",
              workflow: {
                entryId: "00000006-0000-4000-8000-000000000951",
                name: "Handle a claim",
                version: 1,
              },
              startedAt: "2026-09-24T08:00:00Z",
              state: "running",
              spend: {
                sent: "0",
                cameBack: "0",
                spent: "0",
                cameBackUnknown: false,
              },
              ceiling: { raiseNeedsApproval: false },
              acts: [],
            }),
            200,
          ],
        ],
      },
      [SEES_NO_MEMBERS],
    );

    expect(
      await screen.findByRole("heading", { level: 2, name: /Claim from Ada/ }),
    ).toBeInTheDocument();
    expect(screen.queryByRole("alert")).toBeNull();
    expect(screen.queryByText("Nothing is built here yet.")).toBeNull();
  });

  it("answers a run's page in a group the reader is not in as missing, asking nothing of it", async () => {
    const run = "00000008-0000-4000-8000-000000000952";
    const sent = opening(`/groups/${NOT_THEIRS}/work/${run}`, [], {}, [
      SEES_MEMBERS,
    ]);

    expect(
      await screen.findByRole("heading", { level: 1, name: "No such screen" }),
    ).toBeInTheDocument();
    expect(requestsTo(sent)).toEqual(["GET /api/standing"]);
  });

  /** A step opens in its run's place under Work, reached as Work is. */
  it("opens a step of a run under Work in a group the reader is in, by membership alone", async () => {
    const run = "00000008-0000-4000-8000-000000000953";
    const step = "00000009-0000-4000-8000-000000000953";
    const row = {
      stepId: step,
      order: 1,
      name: "file_it",
      runs: { kind: "route" },
      state: "not_started",
      takesFrom: [],
      cost: { callsAModel: false },
      acts: [],
      withheld: [],
    };
    opening(
      `/groups/${SEES_NO_MEMBERS.groupId}/work/${run}/steps/${step}`,
      [],
      {
        ...TRIAGE_WORK,
        [`GET /api/groups/${SEES_NO_MEMBERS.groupId}/runs/${run}/steps/${step}`]:
          [
            [
              JSON.stringify({
                run: {
                  runId: run,
                  number: 4,
                  versionId: "00000007-0000-4000-8000-000000000953",
                  state: "running",
                  acts: [],
                  progress: { done: 0, of: 1 },
                },
                declarations: {},
                step: row,
                triesMade: [],
              }),
              200,
            ],
          ],
      },
      [SEES_NO_MEMBERS],
    );

    expect(
      await screen.findByRole("heading", { level: 2, name: /file it/ }),
    ).toBeInTheDocument();
    expect(
      screen.getByRole("heading", { level: 1, name: TRIAGE_WORK_HEADING }),
    ).toBeInTheDocument();
    expect(screen.queryByRole("alert")).toBeNull();
  });

  it("answers a step's page in a group the reader is not in as missing, asking nothing of it", async () => {
    const run = "00000008-0000-4000-8000-000000000952";
    const step = "00000009-0000-4000-8000-000000000952";
    const sent = opening(
      `/groups/${NOT_THEIRS}/work/${run}/steps/${step}`,
      [],
      {},
      [SEES_MEMBERS],
    );

    expect(
      await screen.findByRole("heading", { level: 1, name: "No such screen" }),
    ).toBeInTheDocument();
    expect(requestsTo(sent)).toEqual(["GET /api/standing"]);
  });

  /** Reached on purpose, a page the reader may not open is owed the rule that refused it. */
  it("refuses in place a page of a group the reader is in that their permissions there do not reach, the frame standing", async () => {
    opening(`/groups/${SEES_NO_MEMBERS.groupId}/members`, [], {}, [
      SEES_MEMBERS,
      SEES_NO_MEMBERS,
    ]);

    expect(await screen.findByRole("alert")).toHaveTextContent(MEMBERS_REFUSED);
    expect(screen.queryByRole("heading", { name: "Members" })).toBeNull();
    expect(screen.getByRole("combobox", { name: "Group" })).toHaveValue(
      "Triage",
    );
    expect(offered()).toContain("Work");
  });

  /**
   * A group the reader is not in answers exactly as an address naming
   * nothing, and in the frame: nothing on the page says the group is there,
   * whose it is, or what it is called — whatever the reader holds in the estate.
   */
  it("answers a page of a group the reader is not in as missing, saying nothing of that group", async () => {
    opening(`/groups/${NOT_THEIRS}/work`, OPENS_PEOPLE_AND_GROUPS, {}, [
      SEES_MEMBERS,
    ]);

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "The address does not name anything here.",
    );
    expect(
      screen.getByRole("heading", { level: 1, name: "No such screen" }),
    ).toBeInTheDocument();
    expect(document.body.textContent).not.toContain(NOT_THEIRS);
    expect(screen.queryByText(MEMBERS_REFUSED)).toBeNull();
    expect(screen.getByRole("combobox", { name: "Group" })).toHaveValue(
      "Payroll",
    );
  });

  /**
   * Changing the group on a page the new group does not open to the reader is
   * a move, and a move shows no refusal: the first page they do reach opens.
   */
  it("opens the first page the group picked reaches, and no refusal, where it does not reach the page the reader was on", async () => {
    opening(
      `/groups/${SEES_MEMBERS.groupId}/members`,
      [],
      { ...LISTED_MEMBERS, ...TRIAGE_WORK },
      [SEES_MEMBERS, SEES_NO_MEMBERS],
    );
    await screen.findByRole("heading", { level: 1, name: PAYROLL_MEMBERS });

    await userEvent.click(screen.getByRole("combobox", { name: "Group" }));
    await userEvent.click(
      screen.getByRole("option", { name: "Triage TRIAGE" }),
    );

    expect(
      await screen.findByRole("heading", {
        level: 1,
        name: TRIAGE_WORK_HEADING,
      }),
    ).toBeInTheDocument();
    expect(screen.queryByRole("alert")).toBeNull();
    expect(screen.queryByText(MEMBERS_REFUSED)).toBeNull();
  });

  it("opens what waits on the reader for somebody in a group", async () => {
    opening("/my-work", [], {}, [SEES_NO_MEMBERS]);

    expect(
      await screen.findByRole("heading", { level: 1, name: "My work" }),
    ).toBeInTheDocument();
    expect(screen.queryByRole("alert")).toBeNull();
  });

  it("refuses what waits on the reader in place to somebody in no group, whatever they hold in the estate", async () => {
    opening("/my-work", OPENS_PEOPLE_AND_GROUPS);

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "My work is open only to somebody in a group.",
    );
    expect(screen.queryByRole("heading", { name: "My work" })).toBeNull();
    expect(offered()).toEqual(["Skip to content", "People", "Groups"]);
  });

  /** A steward who founds a group with themselves at its head is in it, and the sidebar says so at once. */
  it("names in the picker a group the reader has just created with themselves as its founder", async () => {
    opening("/system/groups", OPENS_PEOPLE_AND_GROUPS, {
      "GET /api/standing": [
        [JSON.stringify({ acts: OPENS_PEOPLE_AND_GROUPS, groups: [] }), 200],
        [
          JSON.stringify({ acts: OPENS_PEOPLE_AND_GROUPS, groups: [FOUNDED] }),
          200,
        ],
      ],
      "GET /api/pool/search": [
        [JSON.stringify({ items: [GRACE], more: false }), 200],
      ],
      "POST /api/groups": [
        [
          JSON.stringify({
            groupId: FOUNDED.groupId,
            key: FOUNDED.key,
            name: FOUNDED.name,
            canBeAdministered: true,
            memberCount: 1,
          }),
          201,
        ],
      ],
    });
    await screen.findByRole("heading", { level: 1, name: "Groups" });
    expect(screen.queryByRole("combobox", { name: "Group" })).toBeNull();

    await userEvent.click(
      screen.getByRole("button", { name: "Create a group" }),
    );
    await userEvent.type(
      screen.getByRole("textbox", { name: "Name" }),
      "Intake",
    );
    await userEvent.type(
      screen.getByRole("textbox", { name: "Key" }),
      "intake",
    );
    await userEvent.type(
      screen.getByRole("searchbox", { name: "User number or name" }),
      "grace",
    );
    await userEvent.click(
      await screen.findByRole("radio", { name: "000140 Grace Hopper" }),
    );
    await userEvent.click(screen.getByRole("button", { name: "Create" }));

    expect(await screen.findByRole("combobox", { name: "Group" })).toHaveValue(
      "Intake",
    );
  });

  it("names a group the reader is in by its new name once they have renamed it", async () => {
    opening(
      "/system/groups",
      OPENS_PEOPLE_AND_GROUPS,
      {
        "GET /api/standing": [
          [
            JSON.stringify({
              acts: OPENS_PEOPLE_AND_GROUPS,
              groups: [SEES_MEMBERS],
            }),
            200,
          ],
          [
            JSON.stringify({
              acts: OPENS_PEOPLE_AND_GROUPS,
              groups: [{ ...SEES_MEMBERS, name: "Salaries" }],
            }),
            200,
          ],
        ],
        "GET /api/groups": [
          [
            JSON.stringify({
              items: [
                {
                  groupId: SEES_MEMBERS.groupId,
                  key: SEES_MEMBERS.key,
                  name: "Payroll",
                  canBeAdministered: true,
                  memberCount: 2,
                },
              ],
            }),
            200,
          ],
        ],
        [`PATCH /api/groups/${SEES_MEMBERS.groupId}`]: [
          [
            JSON.stringify({
              groupId: SEES_MEMBERS.groupId,
              key: SEES_MEMBERS.key,
              name: "Salaries",
              canBeAdministered: true,
              memberCount: 2,
            }),
            200,
          ],
        ],
      },
      [SEES_MEMBERS],
    );
    const picker = await screen.findByRole("combobox", { name: "Group" });
    expect(picker).toHaveValue("Payroll");

    await userEvent.click(
      await screen.findByRole("button", { name: /^Rename .*Payroll/ }),
    );
    const name = screen.getByRole("textbox", { name: "Name" });
    await userEvent.clear(name);
    await userEvent.type(name, "Salaries");
    await userEvent.click(screen.getByRole("button", { name: "Rename" }));

    await waitFor(() => expect(picker).toHaveValue("Salaries"));
  });
});
