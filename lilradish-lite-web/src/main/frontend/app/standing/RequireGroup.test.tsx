import { ThemeProvider } from "@mui/material/styles";
import { act, render, screen } from "@testing-library/react";
import { useEffect } from "react";
import {
  MemoryRouter,
  Route,
  RouterProvider,
  Routes,
  createMemoryRouter,
  useLocation,
} from "react-router";
import { describe, expect, it } from "vitest";

import { mayIn, type Standing } from "../../api/standing";
import type { Resource } from "../../lib/request/useResource";
import { theme } from "../../lib/theme/theme";
import { noticesIn } from "../../testutil/notices";
import { answered, inGroup, stillReading } from "../../testutil/standingRead";
import type { GroupGate } from "../destinations";
import { RequireGroup, RequireMyWork, useGroup } from "./RequireGroup";
import { StandingProvider, useStandingRead } from "./StandingContext";

const PAYROLL_ID = "00000003-0000-4000-8000-000000000931";

const TRIAGE_ID = "00000003-0000-4000-8000-000000000932";

const SOMEBODY_ELSES = "00000003-0000-4000-8000-000000000939";

/** Payroll's members page, as the address writes it with the key. */
const PAYROLL_MEMBERS = "/groups/PAYROLL/members";

const SCREEN = "Everybody in this group";

const REFUSED =
  "A group's members are seen only by a role in it that may see them.";

const MISSING = "The address does not name anything here.";

const MY_WORK_REFUSED = "My work is open only to somebody in a group.";

const MAY_CHANGE = "May change who is in it";

const MAY_NOT_CHANGE = "May not change who is in it";

/** Where the reader was before the address under test, so going back has somewhere to land. */
const BEFORE = "/system/people";

/**
 * The page beneath the gate, saying which group it was handed and what it may
 * do there, and noting each address it is on while mounted.
 */
function Members({ mountedAt }: { readonly mountedAt: string[] }) {
  const { pathname } = useLocation();
  const group = useGroup();
  useEffect(() => {
    mountedAt.push(pathname);
  }, [mountedAt, pathname]);
  return (
    <>
      <p>{`${SCREEN} of ${group.name}`}</p>
      <p>{mayIn(group, "change_membership") ? MAY_CHANGE : MAY_NOT_CHANGE}</p>
    </>
  );
}

/**
 * Beside the gate, drawn again whenever either of the gate's inputs moves, and
 * noting after each commit the address at which what the gate drew holds an alert.
 */
function Alerts({ alertedAt }: { readonly alertedAt: string[] }) {
  const { pathname } = useLocation();
  useStandingRead();
  useEffect(() => {
    if (document.querySelector('[role="alert"]') !== null) {
      alertedAt.push(pathname);
    }
  });
  return null;
}

/**
 * The gate at an address, under a real router whose history the test reads,
 * the address followed from `BEFORE`; `redraw` hands it a standing read again.
 */
function gated(
  read: Resource<Standing>,
  reachedBy: GroupGate | null,
  at: string,
  state?: unknown,
) {
  const mountedAt: string[] = [];
  const alertedAt: string[] = [];
  const { pathname, search, hash } = new URL(at, "http://reader.test");
  const router = createMemoryRouter(
    [
      {
        path: "/groups/:group/members",
        element: (
          <>
            <RequireGroup reachedBy={reachedBy}>
              <Members mountedAt={mountedAt} />
            </RequireGroup>
            <Alerts alertedAt={alertedAt} />
          </>
        ),
      },
      { path: BEFORE, element: <p>Before</p> },
    ],
    {
      initialEntries: [BEFORE, { pathname, search, hash, state }],
      initialIndex: 1,
    },
  );
  const draw = (standing: Resource<Standing>) => (
    <ThemeProvider theme={theme}>
      <StandingProvider read={standing}>
        <RouterProvider router={router} />
      </StandingProvider>
    </ThemeProvider>
  );
  const mounted = render(draw(read));
  return {
    router,
    mountedAt,
    alertedAt,
    where: () => {
      const { location } = router.state;
      return `${location.pathname}${location.search}${location.hash}`;
    },
    redraw: (standing: Resource<Standing>) => mounted.rerender(draw(standing)),
  };
}

/** A reader in Triage and then Payroll, so a page handed the first of them is handed the wrong one. */
function inPayroll(...permissions: string[]) {
  return answered(
    [],
    [
      inGroup(TRIAGE_ID, "TRIAGE", "Triage", ["read_membership"]),
      inGroup(PAYROLL_ID, "PAYROLL", "Payroll", permissions),
    ],
  );
}

describe("RequireGroup", () => {
  it("renders the page to a member whose permissions there reach it, handing it the group its key names", () => {
    const { router, where, mountedAt } = gated(
      inPayroll("read_membership"),
      "read_membership",
      PAYROLL_MEMBERS,
    );

    expect(screen.getByText(`${SCREEN} of Payroll`)).toBeInTheDocument();
    expect(screen.queryByRole("alert")).toBeNull();
    expect(where()).toBe(PAYROLL_MEMBERS);
    expect(router.state.historyAction).toBe("POP");
    expect(mountedAt).toEqual([PAYROLL_MEMBERS]);
  });

  it("renders a page membership alone reaches to a member holding nothing else", () => {
    gated(inPayroll(), null, PAYROLL_MEMBERS);

    expect(screen.getByText(`${SCREEN} of Payroll`)).toBeInTheDocument();
    expect(screen.queryByRole("alert")).toBeNull();
  });

  /** Refused where it stands, in the words of the rule, for a page of a group the reader is in. */
  it("refuses in place a member whose permissions there do not reach it", () => {
    const { mountedAt } = gated(
      inPayroll("start_run", "change_membership"),
      "read_membership",
      PAYROLL_MEMBERS,
    );

    expect(noticesIn(document.body)).toEqual([
      { severity: "error", words: REFUSED },
    ]);
    expect(screen.queryByText(`${SCREEN} of Payroll`)).toBeNull();
    expect(screen.queryByText(MISSING)).toBeNull();
    expect(mountedAt).toEqual([]);
  });

  /**
   * The page mounts once, and only at the address written with the key, so
   * nothing it asks for is asked under the other spelling; Back leaves it.
   */
  it.each([
    ["its key in small letters", "/groups/payroll/members", PAYROLL_MEMBERS],
    ["its key in mixed case", "/groups/PayRoll/members", PAYROLL_MEMBERS],
    ["its identifier", `/groups/${PAYROLL_ID}/members`, PAYROLL_MEMBERS],
    ["its key escaped", "/groups/%50AYROLL/members", PAYROLL_MEMBERS],
    [
      "its key escaped in small letters",
      "/groups/%70ayroll/members",
      PAYROLL_MEMBERS,
    ],
    [
      "its key in small letters, with a slash after the page",
      "/groups/payroll/members/",
      `${PAYROLL_MEMBERS}/`,
    ],
    [
      "its key, under the pages' own segment in capitals",
      "/GROUPS/PAYROLL/members",
      PAYROLL_MEMBERS,
    ],
  ])(
    "opens a group named by %s once, at the address written with its key in capitals",
    async (_case, at, written) => {
      const { router, where, mountedAt } = gated(inPayroll(), null, at);

      expect(
        await screen.findByText(`${SCREEN} of Payroll`),
      ).toBeInTheDocument();
      expect(where()).toBe(written);
      expect(router.state.historyAction).toBe("REPLACE");
      expect(mountedAt).toEqual([written]);
      expect(screen.queryByRole("alert")).toBeNull();

      await act(() => router.navigate(-1));

      expect(where()).toBe(BEFORE);
    },
  );

  it("keeps the query, the fragment and the state of an address it writes again", async () => {
    const state = { justMade: true };
    const { router, where } = gated(
      inPayroll(),
      null,
      `/groups/${PAYROLL_ID}/members?filter=avo#top`,
      state,
    );

    expect(await screen.findByText(`${SCREEN} of Payroll`)).toBeInTheDocument();
    expect(where()).toBe(`${PAYROLL_MEMBERS}?filter=avo#top`);
    expect(router.state.location.state).toEqual(state);
    expect(screen.queryByRole("alert")).toBeNull();
  });

  it("refuses a member the page is out of reach of only once the address is written with the key", async () => {
    const { where, mountedAt, alertedAt } = gated(
      inPayroll("start_run"),
      "read_membership",
      "/groups/payroll/members",
    );

    expect(await screen.findByRole("alert")).toHaveTextContent(REFUSED);
    expect(where()).toBe(PAYROLL_MEMBERS);
    expect([...new Set(alertedAt)]).toEqual([PAYROLL_MEMBERS]);
    expect(screen.queryByText(MISSING)).toBeNull();
    expect(mountedAt).toEqual([]);
  });

  /**
   * Refused, it would say the group is there and somebody else's; written
   * again, it would say what the group is called. The estate changes nothing.
   */
  it.each([
    ["by identifier", `/groups/${SOMEBODY_ELSES}/members`],
    ["by key", "/groups/FINANCE/members"],
    ["by key in small letters", "/groups/finance/members"],
    ["by something no group could be named", "/groups/%E2%80%A6/members"],
    ["by a segment that does not decode", "/groups/%E0%A4%A/members"],
  ])(
    "answers a group the reader is not in, named %s, as missing, whatever they hold elsewhere",
    (_case, at) => {
      const { router, where, mountedAt } = gated(
        answered(
          ["keep_pool", "keep_group_register"],
          [inGroup(PAYROLL_ID, "PAYROLL", "Payroll", ["read_membership"])],
        ),
        "read_membership",
        at,
      );

      expect(screen.getByRole("alert")).toHaveTextContent(MISSING);
      expect(
        screen.getByRole("heading", { name: "No such screen" }),
      ).toBeInTheDocument();
      expect(screen.queryByText(SCREEN, { exact: false })).toBeNull();
      expect(screen.queryByText(REFUSED)).toBeNull();
      expect(where()).toBe(at);
      expect(router.state.historyAction).toBe("POP");
      expect(mountedAt).toEqual([]);
    },
  );

  it("answers a group the reader is not in as missing even where membership alone would reach the page", () => {
    gated(answered([], []), null, PAYROLL_MEMBERS);

    expect(screen.getByRole("alert")).toHaveTextContent(MISSING);
    expect(screen.queryByText(SCREEN, { exact: false })).toBeNull();
  });

  /** What was drawn is taken away once the standing read again no longer holds the group. */
  it("takes the page away once the reader is no longer in the group, answering as for a group that is not there", () => {
    const { redraw, where } = gated(
      inPayroll("read_membership"),
      "read_membership",
      PAYROLL_MEMBERS,
    );
    expect(screen.getByText(`${SCREEN} of Payroll`)).toBeInTheDocument();

    redraw(
      answered(
        [],
        [inGroup(TRIAGE_ID, "TRIAGE", "Triage", ["read_membership"])],
      ),
    );

    expect(screen.getByRole("alert")).toHaveTextContent(MISSING);
    expect(screen.queryByText(SCREEN, { exact: false })).toBeNull();
    expect(screen.queryByText(REFUSED)).toBeNull();
    expect(where()).toBe(PAYROLL_MEMBERS);
  });

  it("refuses in place once the standing read again no longer holds the permission reaching the page", () => {
    const { redraw, where } = gated(
      inPayroll("read_membership"),
      "read_membership",
      PAYROLL_MEMBERS,
    );
    expect(screen.getByText(`${SCREEN} of Payroll`)).toBeInTheDocument();

    redraw(inPayroll("start_run"));

    expect(noticesIn(document.body)).toEqual([
      { severity: "error", words: REFUSED },
    ]);
    expect(screen.queryByText(SCREEN, { exact: false })).toBeNull();
    expect(screen.queryByText(MISSING)).toBeNull();
    expect(where()).toBe(PAYROLL_MEMBERS);
  });

  /** A grant the page itself reads moves with the standing read again, the page staying where it is. */
  it.each([
    [
      "given",
      ["read_membership"],
      ["read_membership", "change_membership"],
      MAY_CHANGE,
      MAY_NOT_CHANGE,
    ],
    [
      "taken",
      ["read_membership", "change_membership"],
      ["read_membership"],
      MAY_NOT_CHANGE,
      MAY_CHANGE,
    ],
  ])(
    "hands the page the group as the standing read again holds it, a permission %s on the page drawn as it now is",
    (_case, before, after, drawn, gone) => {
      const { redraw, mountedAt } = gated(
        inPayroll(...before),
        "read_membership",
        PAYROLL_MEMBERS,
      );
      expect(screen.getByText(gone)).toBeInTheDocument();

      redraw(inPayroll(...after));

      expect(screen.getByText(drawn)).toBeInTheDocument();
      expect(screen.queryByText(gone)).toBeNull();
      expect(screen.getByText(`${SCREEN} of Payroll`)).toBeInTheDocument();
      expect(mountedAt).toEqual([PAYROLL_MEMBERS]);
    },
  );

  /** An emptiness that only means "not yet" is neither a wall nor a missing group. */
  it("waits on a read still out rather than answering on the nothing it holds so far", () => {
    const { where } = gated(
      stillReading(),
      "read_membership",
      "/groups/payroll/members",
    );

    expect(screen.getByText("Loading…")).toBeInTheDocument();
    expect(screen.queryByRole("alert")).toBeNull();
    expect(screen.queryByText(SCREEN, { exact: false })).toBeNull();
    expect(where()).toBe("/groups/payroll/members");
  });
});

describe("useGroup", () => {
  /** A group guessed from the address would be one no gate ever admitted. */
  it("throws where no RequireGroup stands above, rather than guessing a group from the address that names one", () => {
    function Unguarded() {
      useGroup();
      return null;
    }

    expect(() =>
      render(
        <StandingProvider read={inPayroll("read_membership")}>
          <MemoryRouter initialEntries={[PAYROLL_MEMBERS]}>
            <Routes>
              <Route path="/groups/:group/members" element={<Unguarded />} />
            </Routes>
          </MemoryRouter>
        </StandingProvider>,
      ),
    ).toThrow("useGroup was called outside a RequireGroup");
  });
});

describe("RequireMyWork", () => {
  function gatedAnywhere(read: Resource<Standing>) {
    return render(
      <ThemeProvider theme={theme}>
        <StandingProvider read={read}>
          <RequireMyWork>
            <p>{SCREEN}</p>
          </RequireMyWork>
        </StandingProvider>
      </ThemeProvider>,
    );
  }

  it("renders the page to somebody in any group, however little they hold there", () => {
    gatedAnywhere(inPayroll());

    expect(screen.getByText(SCREEN)).toBeInTheDocument();
    expect(screen.queryByRole("alert")).toBeNull();
  });

  /** Somebody in no group is refused in place, whatever the estate lets them do. */
  it("refuses in place somebody in no group, whatever they hold across the estate", () => {
    gatedAnywhere(answered(["keep_pool", "check_soundness"]));

    expect(noticesIn(document.body)).toEqual([
      { severity: "error", words: MY_WORK_REFUSED },
    ]);
    expect(screen.queryByText(SCREEN)).toBeNull();
  });

  it("waits on a read still out rather than refusing on the nothing it holds so far", () => {
    gatedAnywhere(stillReading());

    expect(screen.getByText("Loading…")).toBeInTheDocument();
    expect(screen.queryByRole("alert")).toBeNull();
  });
});
