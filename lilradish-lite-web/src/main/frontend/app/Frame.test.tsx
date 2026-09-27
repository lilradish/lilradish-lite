import { ThemeProvider } from "@mui/material/styles";
import { render, screen, waitFor } from "@testing-library/react";
import { userEvent } from "@testing-library/user-event";
import { RouterProvider, createMemoryRouter } from "react-router";
import { describe, expect, it, vi } from "vitest";

import { useStandingResource, type Standing } from "../api/standing";
import type { Resource } from "../lib/request/useResource";
import { theme } from "../lib/theme/theme";
import {
  answered,
  inGroup,
  refused,
  stillReading,
} from "../testutil/standingRead";
import { Frame } from "./Frame";
import { PRODUCT_NAME } from "./product";

// Stubbed at the hook rather than at `fetch`: the three reads below are states
// the frame draws from, and reaching each through a response makes it a race.
// The rest of the module stays real, the navigation gating on it.
vi.mock("../api/standing", async (importOriginal) => ({
  ...(await importOriginal<typeof import("../api/standing")>()),
  useStandingResource: vi.fn(),
}));

/** The routed screen, as a marker only this outlet can put on the page. */
const SCREEN = "Everybody this system knows";

const NOT_SIGNED_IN = "You are not signed in.";

/**
 * This environment implements no `matchMedia`, and the hook reading it answers
 * `false` to every query when it is missing — so without this the shell would
 * believe the viewport narrow, keep its drawer shut, and render no navigation
 * landmark at all.
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
 * A real child route, so the outlet has somewhere to render. A stub in the
 * frame's place would leave the one thing under test — whether the screen
 * underneath is drawn at all — asserted against a stand-in for it.
 */
function framing(read: Resource<Standing>) {
  wideViewport();
  vi.mocked(useStandingResource).mockReturnValue(read);
  return render(
    <ThemeProvider theme={theme}>
      <RouterProvider
        router={createMemoryRouter(
          [
            {
              path: "/",
              element: <Frame />,
              children: [{ index: true, element: <p>{SCREEN}</p> }],
            },
          ],
          { initialEntries: ["/"] },
        )}
      />
    </ThemeProvider>,
  );
}

const PAYROLL = inGroup(
  "00000003-0000-4000-8000-000000000971",
  "PAYROLL",
  "Payroll",
  [],
);
const TRIAGE = inGroup(
  "00000003-0000-4000-8000-000000000972",
  "TRIAGE",
  "Triage",
  [],
);

/** A narrow viewport, where the sidebar is a drawer that is gone while it is shut. */
function framingNarrowly(path: string) {
  vi.stubGlobal("matchMedia", (query: string) => ({
    matches: false,
    media: query,
    addEventListener: () => {},
    removeEventListener: () => {},
  }));
  vi.mocked(useStandingResource).mockReturnValue(
    answered(["keep_pool"], [PAYROLL, TRIAGE]),
  );
  const router = createMemoryRouter(
    [
      {
        path: "/",
        element: <Frame />,
        children: [
          { index: true, element: <p>{SCREEN}</p> },
          { path: "system/people", element: <p>People</p> },
          { path: "groups/:groupId/:segment", element: <p>A group's page</p> },
        ],
      },
    ],
    { initialEntries: [path] },
  );
  render(
    <ThemeProvider theme={theme}>
      <RouterProvider router={router} />
    </ThemeProvider>,
  );
  return router;
}

async function openingTheDrawer() {
  await userEvent.click(
    screen.getByRole("button", { name: "Open navigation" }),
  );
}

async function pickingTriage() {
  await userEvent.click(screen.getByRole("combobox", { name: "Group" }));
  await userEvent.click(screen.getByRole("option", { name: "Triage TRIAGE" }));
}

describe("Frame", () => {
  it("draws the screen underneath once the read has settled, with nothing refused", () => {
    framing(answered(["keep_pool"]));

    expect(screen.getByText(SCREEN)).toBeInTheDocument();
    expect(screen.queryByRole("alert")).toBeNull();
  });

  /**
   * A refusal of this read is a refusal of the frame: it is who the reader is
   * that could not be learnt, so no screen under here is truthful. The served
   * code is said as a sentence, which is also what proves the refusal went
   * through the frame rather than something else having raised an alert.
   */
  it("shows the refusal instead of the screen where who the reader is could not be learnt", () => {
    framing(refused({ status: 401, code: "NOT_SIGNED_IN" }));

    expect(screen.getByRole("alert")).toHaveTextContent(NOT_SIGNED_IN);
    expect(screen.queryByText(SCREEN)).toBeNull();
  });

  /**
   * Nothing here waits on the read. The screens that cannot be drawn without an
   * answer wait for it themselves, and the one every reader may be on is drawn
   * at once — so arriving anywhere at all does not cost a round trip for an
   * answer the frame has not been refused.
   */
  it("draws the frame and the screen while the read is still out rather than waiting on it", () => {
    framing(stillReading());

    expect(screen.getByText(PRODUCT_NAME)).toBeInTheDocument();
    expect(
      screen.getByRole("navigation", { name: "Site" }),
    ).toBeInTheDocument();
    expect(screen.getByText(SCREEN)).toBeInTheDocument();
    expect(screen.queryByRole("alert")).toBeNull();
  });

  /** The drawer is gone while shut, so the group in force has to outlive it. */
  it("still names the group picked once the drawer has shut behind a link and opened again", async () => {
    framingNarrowly("/");
    await openingTheDrawer();
    await pickingTriage();

    await userEvent.click(screen.getByRole("link", { name: "People" }));
    await waitFor(() => expect(screen.queryByRole("navigation")).toBeNull());
    await openingTheDrawer();

    expect(screen.getByRole("combobox", { name: "Group" })).toHaveValue(
      "Triage",
    );
  });

  it("puts the drawer away once a group picked on a group's page has opened that page", async () => {
    const router = framingNarrowly(`/groups/${PAYROLL.groupId}/work`);
    await openingTheDrawer();

    await pickingTriage();

    expect(router.state.location.pathname).toBe(
      `/groups/${TRIAGE.groupId}/work`,
    );
    await waitFor(() => expect(screen.queryByRole("navigation")).toBeNull());
  });

  it("leaves the drawer open where a group picked away from every group's pages opens nothing", async () => {
    framingNarrowly("/system/people");
    await openingTheDrawer();

    await pickingTriage();

    expect(
      screen.getByRole("navigation", { name: "Site" }),
    ).toBeInTheDocument();
    expect(screen.getByRole("combobox", { name: "Group" })).toHaveValue(
      "Triage",
    );
  });
});
