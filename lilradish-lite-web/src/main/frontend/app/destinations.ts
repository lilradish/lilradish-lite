// One module each, never the barrel: that package publishes every Material
// icon, and naming the module is what keeps the bundle to the ones used.
import AccountTree from "@mui/icons-material/AccountTree";
import FactCheck from "@mui/icons-material/FactCheck";
import Groups from "@mui/icons-material/Groups";
import Inbox from "@mui/icons-material/Inbox";
import ListAlt from "@mui/icons-material/ListAlt";
import People from "@mui/icons-material/People";
import QueryStats from "@mui/icons-material/QueryStats";
import Quiz from "@mui/icons-material/Quiz";
import Work from "@mui/icons-material/Work";
import Workspaces from "@mui/icons-material/Workspaces";
import type SvgIcon from "@mui/material/SvgIcon";

import { SEGMENTS } from "../api/groups/{groupId}/{kind}";
import {
  mayIn,
  type GroupPermission,
  type GroupStanding,
  type SurfaceAct,
} from "../api/standing";
import { GROUPS_PAGE } from "../features/groups/GroupsPage";
import { MEASUREMENTS_PAGE } from "../features/measurements/MeasurementsPage";
import { PEOPLE_PAGE } from "../features/people/PeoplePage";
import type { MessageId } from "../i18n/app";

interface Destination {
  /** Rooted: a relative one would resolve against whatever screen is in view. */
  readonly to: string;
  readonly label: MessageId;
  readonly icon: typeof SvgIcon;
  /**
   * What the server has to let this reader do before the place is worth
   * offering. Required, because an entry gating on nothing is offered to
   * everyone and so is one row that survives the emptying below — which tells a
   * reader holding nothing that there is a section here they are not in.
   *
   * Which act a screen needs is written on its declaration rather than in a
   * table beside it.
   */
  readonly act: SurfaceAct;
}

interface DestinationGroup {
  readonly label: MessageId;
  readonly items: readonly Destination[];
}

/** The estate's pages; each route is guarded by, and titled from, the entry that offers it. */
export const PEOPLE_DESTINATION = {
  to: PEOPLE_PAGE,
  label: "destination.people",
  icon: People,
  act: "keep_pool",
} as const satisfies Destination;

export const GROUPS_DESTINATION = {
  to: GROUPS_PAGE,
  label: "destination.groups",
  icon: Workspaces,
  act: "keep_group_register",
} as const satisfies Destination;

export const SOUNDNESS_DESTINATION = {
  to: "/system/soundness",
  label: "destination.soundness",
  icon: FactCheck,
  act: "check_soundness",
} as const satisfies Destination;

export const MEASUREMENTS_DESTINATION = {
  to: MEASUREMENTS_PAGE,
  label: "destination.measurements",
  icon: QueryStats,
  act: "read_measurements",
} as const satisfies Destination;

/**
 * Everywhere a person can go in the estate, and nothing else.
 *
 * Every entry is gated, which is what makes a reader holding nothing see no
 * group heading rather than an empty one — and so learn nothing about what this
 * system would have offered somebody else. The start page is deliberately not
 * among them: it is where a reader already is, and an entry leading there would
 * be the one row that survives the emptying.
 *
 * Labels are message ids, so the words sit with every other sentence while the
 * grouping and the order stay here, where they are structure rather than text.
 */
export const DESTINATION_GROUPS: readonly DestinationGroup[] = [
  {
    label: "destinationGroup.system",
    items: [
      PEOPLE_DESTINATION,
      GROUPS_DESTINATION,
      SOUNDNESS_DESTINATION,
      MEASUREMENTS_DESTINATION,
    ],
  },
];

/** What waits on the reader in every group they are in; being in any group at all reaches it. */
export const MY_WORK = {
  to: "/my-work",
  label: "destination.myWork",
  icon: Inbox,
} as const satisfies Omit<Destination, "act">;

/** What the address of every page of a group begins with, before the group. */
export const IN_A_GROUP = "/groups";

export interface GroupItem {
  /** The last segment of the address, after the group it is in. */
  readonly segment: string;
  readonly label: MessageId;
  readonly icon: typeof SvgIcon;
  /** Null gates on membership, not on nothing: only the reader's own groups are ever drawn. */
  readonly reachedBy: GroupPermission | null;
}

/** Who is in the group and what each of them holds there. */
export const MEMBERS_ITEM = {
  segment: "members",
  label: "destination.members",
  icon: Groups,
  reachedBy: "read_membership",
} as const satisfies GroupItem;

/** The group's library, one page per kind of entry, each at the segment its entries are addressed under. */
export const WORKFLOWS_ITEM = {
  segment: SEGMENTS.workflow,
  label: "destination.workflows",
  icon: AccountTree,
  reachedBy: null,
} as const satisfies GroupItem;

export const QUESTIONS_ITEM = {
  segment: SEGMENTS.question,
  label: "destination.questions",
  icon: Quiz,
  reachedBy: null,
} as const satisfies GroupItem;

export const REFERENCE_LISTS_ITEM = {
  segment: SEGMENTS.reference_list,
  label: "destination.referenceLists",
  icon: ListAlt,
  reachedBy: null,
} as const satisfies GroupItem;

/** What the group has running, each run opening as a page of its own under it. */
export const WORK_ITEM = {
  segment: "work",
  label: "destination.work",
  icon: Work,
  reachedBy: null,
} as const satisfies GroupItem;

/** The pages every group has, in drawn order: by how often touched, so administration goes last. */
export const GROUP_ITEMS = [
  WORK_ITEM,
  WORKFLOWS_ITEM,
  QUESTIONS_ITEM,
  REFERENCE_LISTS_ITEM,
  MEMBERS_ITEM,
] as const satisfies readonly GroupItem[];

/** A permission some group page is reached by: a rule a refusal has words for. */
export type GroupGate = NonNullable<(typeof GROUP_ITEMS)[number]["reachedBy"]>;

/** One page of one group. The identifier is one the server sent, escaped all the same. */
export function groupPage(groupId: string, segment: string): string {
  return `${IN_A_GROUP}/${encodeURIComponent(groupId)}/${segment}`;
}

/** Whether the reader's grants in the group reach the page. */
export function opensIn(group: GroupStanding, item: GroupItem): boolean {
  return item.reachedBy === null || mayIn(group, item.reachedBy);
}
