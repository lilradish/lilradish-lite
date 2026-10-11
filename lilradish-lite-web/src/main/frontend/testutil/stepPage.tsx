import { ThemeProvider } from "@mui/material/styles";
import { render } from "@testing-library/react";
import { RouterProvider, createMemoryRouter } from "react-router";
import { vi } from "vitest";

import { WORK_ITEM } from "../app/destinations";
import { RequireGroup } from "../app/standing/RequireGroup";
import { StandingProvider } from "../app/standing/StandingContext";
import { RunPage } from "../features/runs/RunPage";
import { RunReadAccess } from "../features/runs/RunReadAccess";
import { StepPage } from "../features/runs/steps/StepPage";
import { theme } from "../lib/theme/theme";
import { serving, type Reply } from "./answering";
import { namingGroupsByKeyAlone } from "./groupAddresses";
import { pageLaidOutAt } from "./layout";
import { KEY } from "./runPage";
import { answered, inGroup } from "./standingRead";

export function openStepPage({
  groupId,
  view,
  address,
  routes,
  permissions,
  userId,
}: {
  readonly groupId: string;
  readonly view: "run" | "step";
  readonly address: string;
  readonly routes: Readonly<Record<string, readonly Reply[]>>;
  readonly permissions: readonly string[];
  readonly userId?: string;
}) {
  pageLaidOutAt(1400, 1600);
  const sent = serving(routes);
  const router = createMemoryRouter(
    [
      view === "run"
        ? {
            path: "/groups/:group/work/:runId",
            element: (
              <RequireGroup reachedBy={WORK_ITEM.reachedBy}>
                <RunPage />
              </RequireGroup>
            ),
          }
        : {
            path: "/groups/:group/work/:runId/steps/:stepId",
            element: (
              <RequireGroup reachedBy={WORK_ITEM.reachedBy}>
                <StepPage />
              </RequireGroup>
            ),
          },
    ],
    { initialEntries: [address] },
  );
  namingGroupsByKeyAlone(router, [groupId]);
  const readAgain = vi.fn();
  const standing = answered(
    [],
    [inGroup(groupId, KEY, "Claims", permissions)],
    userId,
  );
  const draw = (current = standing) => (
    <ThemeProvider theme={theme}>
      <StandingProvider read={{ ...current, reload: readAgain }}>
        <RunReadAccess>
          <RouterProvider router={router} />
        </RunReadAccess>
      </StandingProvider>
    </ThemeProvider>
  );
  const mounted = render(draw());
  return {
    sent,
    readAgain,
    router,
    changeStanding(
      currentPermissions: readonly string[],
      currentUser = userId,
    ) {
      mounted.rerender(
        draw(
          answered(
            [],
            [inGroup(groupId, KEY, "Claims", currentPermissions)],
            currentUser,
          ),
        ),
      );
    },
  };
}
