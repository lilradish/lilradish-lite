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
 * Either a picked row shares this page's address under `picks`, or opens its
 * own page addressed under `by`; never both.
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
    const page = `${IN_A_GROUP}/:group/${item.segment}`;
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
    path: `${IN_A_GROUP}/:group/${WORK_ITEM.segment}/:runId/steps/:stepId`,
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
 * The guard is on the route rather than inside the screen, so a screen cannot
 * come to exist that forgot to ask. It is also why a screen not built yet is
 * declared all the same: an address that names no route is answered "no such
 * screen", which is a different thing from a screen that is there and
 * withheld — and only the second is the truth about it.
 *
 * An optional picked-row segment keeps the list and its pressed control mounted.
 * An opened row instead gets a separate route. A run's step route shares the
 * run's screen.
 *
 * Refusals, unknown addresses and broken screens stay under the frame, keeping
 * the way out visible. A screen boundary below it leaves the frame boundary
 * to catch the frame itself failing.
 */
export const ROUTES: RouteObject[] = [
  {
    path: "/",
    element: <Frame />,
    errorElement: <FrameError />,
    children: [{ errorElement: <ScreenError />, children: SCREENS }],
  },
];
