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
import type { MessageId } from "../i18n/app";
import { GROUPS_PAGE, MEASUREMENTS_PAGE, PEOPLE_PAGE } from "./addresses";

interface Destination {
  /** Rooted: a relative one would resolve against whatever screen is in view. */
  readonly to: string;
  readonly label: MessageId;
  readonly icon: typeof SvgIcon;
  /**
   * Each page declares its required act. An ungated entry survives emptying
   * and discloses its section to a reader with no grants.
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
 * Gating every entry hides even the heading from readers with no grants.
 * The start page is excluded: they are already there, and its entry would survive emptying.
 * Labels are message ids; grouping and order stay here so translation cannot change structure.
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

export const IN_A_GROUP = "/groups";

export interface GroupItem {
  /** The last segment of the address, after the group it is in. */
  readonly segment: string;
  readonly label: MessageId;
  readonly icon: typeof SvgIcon;
  /** Null gates on membership, not on nothing: only the reader's own groups are ever drawn. */
  readonly reachedBy: GroupPermission | null;
}

export const MEMBERS_ITEM = {
  segment: "members",
  label: "destination.members",
  icon: Groups,
  reachedBy: "read_membership",
} as const satisfies GroupItem;

/** Each library page sits at the segment its entries are addressed under. */
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

/** Each run opens as a page of its own under it. */
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

/** One page of one group, named by its key: what a reader can say and write down. Escaped all the same. */
export function groupPage(group: GroupStanding, segment: string): string {
  return `${IN_A_GROUP}/${encodeURIComponent(group.key)}/${segment}`;
}

/**
 * The reader's own group an address names, by its key in any case or by its identifier; none for any
 * other, so a group they are not in reads exactly as one that does not exist.
 */
export function groupNamed(
  groups: readonly GroupStanding[],
  named: string | undefined,
): GroupStanding | undefined {
  if (named === undefined) {
    return undefined;
  }
  // English letters alone: `toUpperCase` on the whole would let a letter outside them pass as the capital it folds to.
  const folded = named.replace(/[a-z]/g, (letter) => letter.toUpperCase());
  return groups.find((each) => each.key === folded || each.groupId === named);
}

const GROUP_SEGMENT = IN_A_GROUP.split("/").length;

/** Whatever follows the group in the path is kept as written. */
export function groupAddressed(group: GroupStanding, pathname: string): string {
  return groupPage(
    group,
    pathname
      .split("/")
      .slice(GROUP_SEGMENT + 1)
      .join("/"),
  );
}

export function opensIn(group: GroupStanding, item: GroupItem): boolean {
  return item.reachedBy === null || mayIn(group, item.reachedBy);
}
