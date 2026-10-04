import { lazy, Suspense, type ReactNode } from "react";
import type { RouteObject } from "react-router";

import { LIBRARY_KINDS } from "../features/library/libraryKinds";
import { say } from "../i18n/app";
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

const GroupsPage = lazy(async () => ({
  default: (await import("../features/groups/GroupsPage")).GroupsPage,
}));
const EntryPage = lazy(async () => ({
  default: (await import("../features/library/EntryPage")).EntryPage,
}));
const LibraryPage = lazy(async () => ({
  default: (await import("../features/library/LibraryPage")).LibraryPage,
}));
const MeasurementsPage = lazy(async () => ({
  default: (await import("../features/measurements/MeasurementsPage"))
    .MeasurementsPage,
}));
const MembersPage = lazy(async () => ({
  default: (await import("../features/members/MembersPage")).MembersPage,
}));
const PeoplePage = lazy(async () => ({
  default: (await import("../features/people/PeoplePage")).PeoplePage,
}));
const WorkPage = lazy(async () => ({
  default: (await import("../features/runs/WorkPage")).WorkPage,
}));
const OpenedRun = lazy(async () => ({
  default: (await import("../features/runs/WorkPage")).OpenedRun,
}));

function Deferred({ children }: { readonly children: ReactNode }) {
  return (
    <Suspense fallback={<p role="status">{say("read.pending")}</p>}>
      {children}
    </Suspense>
  );
}

/**
 * A group's page as it is built: one whose row picked is part of its own
 * address, under the name `picks` gives it, or one whose row opens a page of
 * its own, addressed under the name `by` gives it, and never both.
 */
type GroupScreen =
  | {
      readonly screen: ReactNode;
      readonly picks: "subjectId";
      readonly opens?: never;
      readonly by?: never;
    }
  | {
      readonly screen: ReactNode;
      readonly opens: ReactNode;
      readonly by: "entryId" | "runId";
      readonly picks?: never;
    };

/** Every group page built, by its segment; any other is its placeholder. */
const GROUP_SCREENS: Partial<
  Record<(typeof GROUP_ITEMS)[number]["segment"], GroupScreen>
> = {
  ...Object.fromEntries(
    Object.values(LIBRARY_KINDS).map((of): [string, GroupScreen] => [
      of.item.segment,
      {
        screen: (
          <Deferred>
            <LibraryPage of={of} />
          </Deferred>
        ),
        opens: (
          <Deferred>
            <EntryPage of={of} />
          </Deferred>
        ),
        by: "entryId",
      },
    ]),
  ),
  [MEMBERS_ITEM.segment]: {
    screen: (
      <Deferred>
        <MembersPage />
      </Deferred>
    ),
    picks: "subjectId",
  },
  [WORK_ITEM.segment]: {
    screen: (
      <Deferred>
        <WorkPage />
      </Deferred>
    ),
    opens: (
      <Deferred>
        <OpenedRun />
      </Deferred>
    ),
    by: "runId",
  },
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
              path: `${page}/:${built.by}`,
              element: (
                <RequireGroup reachedBy={item.reachedBy}>
                  {built.opens}
                </RequireGroup>
              ),
            },
          ]),
    ];
  }),
  {
    path: `${IN_A_GROUP}/:groupId/${WORK_ITEM.segment}/:runId/steps/:stepId`,
    element: (
      <RequireGroup reachedBy={WORK_ITEM.reachedBy}>
        <Deferred>
          <OpenedRun />
        </Deferred>
      </RequireGroup>
    ),
  },
  {
    path: `${PEOPLE_DESTINATION.to}/:subjectId?`,
    element: (
      <RequireStanding act={PEOPLE_DESTINATION.act}>
        <Deferred>
          <PeoplePage title={PEOPLE_DESTINATION.label} />
        </Deferred>
      </RequireStanding>
    ),
  },
  {
    path: GROUPS_DESTINATION.to,
    element: (
      <RequireStanding act={GROUPS_DESTINATION.act}>
        <Deferred>
          <GroupsPage title={GROUPS_DESTINATION.label} />
        </Deferred>
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
        <Deferred>
          <MeasurementsPage
            title={MEASUREMENTS_DESTINATION.label}
            act={MEASUREMENTS_DESTINATION.act}
          />
        </Deferred>
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
 * where they were. A row that opens a page of its own, as an entry or a run
 * does, is a route beside the list's; one step of a run is the one route beside
 * the run's, drawn by the same screen.
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
