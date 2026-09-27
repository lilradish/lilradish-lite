import { ThemeProvider } from "@mui/material/styles";
import { render, screen } from "@testing-library/react";
import type { ReactNode } from "react";
import { MemoryRouter, Route, Routes } from "react-router";
import { describe, expect, it } from "vitest";

import type { Standing } from "../../api/standing";
import type { Resource } from "../../lib/request/useResource";
import { theme } from "../../lib/theme/theme";
import { noticesIn } from "../../testutil/notices";
import { answered, inGroup, stillReading } from "../../testutil/standingRead";
import type { GroupGate } from "../destinations";
import { RequireGroup, RequireMyWork } from "./RequireGroup";
import { StandingProvider } from "./StandingContext";

const PAYROLL = "00000003-0000-4000-8000-000000000931";

const SOMEBODY_ELSES = "00000003-0000-4000-8000-000000000939";

const SCREEN = "Everybody in this group";

const REFUSED =
  "A group's members are seen only by a role in it that may see them.";

const MISSING = "The address does not name anything here.";

const MY_WORK_REFUSED = "My work is open only to somebody in a group.";

function framed(read: Resource<Standing>, children: ReactNode, at: string) {
  return render(
    <ThemeProvider theme={theme}>
      <MemoryRouter initialEntries={[at]}>
        <StandingProvider read={read}>
          <Routes>
            <Route path="/groups/:groupId/members" element={children} />
          </Routes>
        </StandingProvider>
      </MemoryRouter>
    </ThemeProvider>,
  );
}

function gated(
  read: Resource<Standing>,
  reachedBy: GroupGate | null,
  groupId = PAYROLL,
) {
  return framed(
    read,
    <RequireGroup reachedBy={reachedBy}>
      <p>{SCREEN}</p>
    </RequireGroup>,
    `/groups/${groupId}/members`,
  );
}

function inPayroll(...permissions: string[]) {
  return answered([], [inGroup(PAYROLL, "PAYROLL", "Payroll", permissions)]);
}

describe("RequireGroup", () => {
  it("renders the page to a member whose permissions there reach it", () => {
    gated(inPayroll("read_membership"), "read_membership");

    expect(screen.getByText(SCREEN)).toBeInTheDocument();
    expect(screen.queryByRole("alert")).toBeNull();
  });

  it("renders a page membership alone reaches to a member holding nothing else", () => {
    gated(inPayroll(), null);

    expect(screen.getByText(SCREEN)).toBeInTheDocument();
    expect(screen.queryByRole("alert")).toBeNull();
  });

  /** Refused where it stands, in the words of the rule, for a page of a group the reader is in. */
  it("refuses in place a member whose permissions there do not reach it", () => {
    gated(inPayroll("start_run", "change_membership"), "read_membership");

    expect(noticesIn(document.body)).toEqual([
      { severity: "error", words: REFUSED },
    ]);
    expect(screen.queryByText(SCREEN)).toBeNull();
    expect(screen.queryByText(MISSING)).toBeNull();
  });

  /** Refused, it would say the group is there and somebody else's; the estate changes nothing. */
  it("answers a group the reader is not in as missing, whatever they hold elsewhere", () => {
    gated(
      answered(
        ["keep_pool", "keep_group_register"],
        [inGroup(PAYROLL, "PAYROLL", "Payroll", ["read_membership"])],
      ),
      "read_membership",
      SOMEBODY_ELSES,
    );

    expect(screen.getByRole("alert")).toHaveTextContent(MISSING);
    expect(
      screen.getByRole("heading", { name: "No such screen" }),
    ).toBeInTheDocument();
    expect(screen.queryByText(SCREEN)).toBeNull();
    expect(screen.queryByText(REFUSED)).toBeNull();
  });

  it("answers a group the reader is not in as missing even where membership alone would reach the page", () => {
    gated(answered([], []), null);

    expect(screen.getByRole("alert")).toHaveTextContent(MISSING);
    expect(screen.queryByText(SCREEN)).toBeNull();
  });

  /** An emptiness that only means "not yet" is neither a wall nor a missing group. */
  it("waits on a read still out rather than answering on the nothing it holds so far", () => {
    gated(stillReading(), "read_membership");

    expect(screen.getByText("Still reading…")).toBeInTheDocument();
    expect(screen.queryByRole("alert")).toBeNull();
    expect(screen.queryByText(SCREEN)).toBeNull();
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

    expect(screen.getByText("Still reading…")).toBeInTheDocument();
    expect(screen.queryByRole("alert")).toBeNull();
  });
});
