import { ThemeProvider } from "@mui/material/styles";
import { render } from "@testing-library/react";
import { RouterProvider, createMemoryRouter } from "react-router";
import { vi } from "vitest";

import { StandingProvider } from "../app/standing/StandingContext";
import { RunPage } from "../features/runs/RunPage";
import { RunReadAccess } from "../features/runs/RunReadAccess";
import { StepPage } from "../features/runs/steps/StepPage";
import { theme } from "../lib/theme/theme";
import { serving, type Reply } from "./answering";
import { laidOutAt } from "./layout";
import { answered, inGroup } from "./standingRead";

export function openStepPage({
  groupId,
  view,
  address,
  routes,
  permissions,
}: {
  readonly groupId: string;
  readonly view: "run" | "step";
  readonly address: string;
  readonly routes: Readonly<Record<string, readonly Reply[]>>;
  readonly permissions: readonly string[];
}) {
  laidOutAt(0, 1200);
  const sent = serving(routes);
  const router = createMemoryRouter(
    [
      view === "run"
        ? { path: "/groups/:groupId/work/:runId", element: <RunPage /> }
        : {
            path: "/groups/:groupId/work/:runId/steps/:stepId",
            element: <StepPage />,
          },
    ],
    { initialEntries: [address] },
  );
  const readAgain = vi.fn();
  const standing = answered(
    [],
    [inGroup(groupId, "CLAIMS", "Claims", permissions)],
  );
  render(
    <ThemeProvider theme={theme}>
      <StandingProvider read={{ ...standing, reload: readAgain }}>
        <RunReadAccess>
          <RouterProvider router={router} />
        </RunReadAccess>
      </StandingProvider>
    </ThemeProvider>,
  );
  return { sent, readAgain, router };
}
