import type { ReactNode } from "react";
import type { RouteObject } from "react-router";

import { GroupsPage } from "../features/groups/GroupsPage";
import { EntryPage } from "../features/library/EntryPage";
import { LibraryPage } from "../features/library/LibraryPage";
import { LIBRARY_KINDS } from "../features/library/libraryKinds";
import { MeasurementsPage } from "../features/measurements/MeasurementsPage";
import { MembersPage } from "../features/members/MembersPage";
import { PeoplePage } from "../features/people/PeoplePage";
import { WorkPage } from "../features/runs/WorkPage";
import {
  GROUP_ITEMS,
  GROUPS_DESTINATION,
  IN_A_GROUP,
  MEASUREMENTS_DESTINATION,
  MEMBERS_ITEM,
  MY_WORK,
  PEOPLE_DESTINATION,
  SOUNDNESS_DESTINATION,
  WORK_ITEM,
} from "./destinations";
import { Frame } from "./Frame";
import { Landing } from "./Landing";
import { FrameError, NoSuchScreen, ScreenError } from "./RouteError";
import { SystemPage } from "./SystemPage";
import { RequireGroup, RequireMyWork } from "./standing/RequireGroup";
import { RequireStanding } from "./standing/RequireStanding";

/**
 * A group's page as it is built: one whose row picked is part of its own
 * address, under the name `picks` gives it, or one whose row opens a page of
 * its own, and never both.
 */
type GroupScreen =
  | {
      readonly screen: ReactNode;
      readonly picks: "subjectId" | "runId";
      readonly opens?: never;
    }
  | {
      readonly screen: ReactNode;
      readonly opens: ReactNode;
      readonly picks?: never;
    };

/** Every group page built, by its segment; any other is its placeholder. */
const GROUP_SCREENS: Partial<
  Record<(typeof GROUP_ITEMS)[number]["segment"], GroupScreen>
> = {
  ...Object.fromEntries(
    Object.values(LIBRARY_KINDS).map((of): [string, GroupScreen] => [
      of.item.segment,
      { screen: <LibraryPage of={of} />, opens: <EntryPage of={of} /> },
    ]),
  ),
  [MEMBERS_ITEM.segment]: { screen: <MembersPage />, picks: "subjectId" },
  [WORK_ITEM.segment]: { screen: <WorkPage />, picks: "runId" },
};

/** Every screen, each under the guard it is reached by, and last the answer to any other address. */
const SCREENS: RouteObject[] = [
  { index: true, element: <Landing /> },
  {
    path: MY_WORK.to,
    element: (
      <RequireMyWork>
        <SystemPage title={MY_WORK.label} />
      </RequireMyWork>
    ),
  },
  ...GROUP_ITEMS.flatMap((item) => {
    const built = GROUP_SCREENS[item.segment];
    const page = `${IN_A_GROUP}/:groupId/${item.segment}`;
    return [
      {
        path: `${page}${built?.picks === undefined ? "" : `/:${built.picks}?`}`,
        element: (
          <RequireGroup reachedBy={item.reachedBy}>
            {built?.screen ?? <SystemPage title={item.label} />}
          </RequireGroup>
        ),
      },
      ...(built?.opens === undefined
        ? []
        : [
            {
              path: `${page}/:entryId`,
              element: (
                <RequireGroup reachedBy={item.reachedBy}>
                  {built.opens}
                </RequireGroup>
              ),
            },
          ]),
    ];
  }),
  // An errorElement on this route or the run's wraps one of them alone in a boundary, and the changed tree
  // remounts WorkPage between the two, reading the list again.
  {
    path: `${IN_A_GROUP}/:groupId/${WORK_ITEM.segment}/:runId/steps/:stepId`,
    element: (
      <RequireGroup reachedBy={WORK_ITEM.reachedBy}>
        <WorkPage />
      </RequireGroup>
    ),
  },
  {
    path: `${PEOPLE_DESTINATION.to}/:subjectId?`,
    element: (
      <RequireStanding act={PEOPLE_DESTINATION.act}>
        <PeoplePage title={PEOPLE_DESTINATION.label} />
      </RequireStanding>
    ),
  },
  {
    path: GROUPS_DESTINATION.to,
    element: (
      <RequireStanding act={GROUPS_DESTINATION.act}>
        <GroupsPage title={GROUPS_DESTINATION.label} />
      </RequireStanding>
    ),
  },
  {
    path: SOUNDNESS_DESTINATION.to,
    element: (
      <RequireStanding act={SOUNDNESS_DESTINATION.act}>
        <SystemPage title={SOUNDNESS_DESTINATION.label} />
      </RequireStanding>
    ),
  },
  {
    path: MEASUREMENTS_DESTINATION.to,
    element: (
      <RequireStanding act={MEASUREMENTS_DESTINATION.act}>
        <MeasurementsPage
          title={MEASUREMENTS_DESTINATION.label}
          act={MEASUREMENTS_DESTINATION.act}
        />
      </RequireStanding>
    ),
  },
  { path: "*", element: <NoSuchScreen /> },
];

/**
 * Every address this application answers, and what guards each one.
 *
 * The guard is on the route rather than inside the screen, so a screen cannot
 * come to exist that forgot to ask. It is also why a screen not built yet is
 * declared all the same: an address that names no route is answered "no such
 * screen", which is a different thing from a screen that is there and
 * withheld — and only the second is the truth about it.
 *
 * A row picked in a list is an optional segment of the list's own route, not a
 * route beside it, so the list and a row picked in it are one screen by
 * declaration: picking a row leaves the list, and the control that picked it,
 * where they were. One step of a run is the one route beside it: it draws the
 * same Work screen at the same place, so moving between a run and its steps
 * keeps the list as it is too.
 *
 * Nested under one frame, because a refusal has to be shown where the reader is
 * standing, with the way out still on screen — and so are an address naming
 * nothing, answered by the last of the screens, and a screen that breaks, which
 * is why the screens share a boundary of their own beneath the frame. The
 * frame's own boundary is left only the frame itself failing.
 */
export const ROUTES: RouteObject[] = [
  {
    path: "/",
    element: <Frame />,
    errorElement: <FrameError />,
    children: [{ errorElement: <ScreenError />, children: SCREENS }],
  },
];
