import type {
  Version,
  VersionStanding,
} from "../../api/groups/{groupId}/{kind}";
import { say } from "../../i18n/app";
import type { Ordering } from "../../lib/collection/ordering";
import { nameOrNumber } from "../../lib/text/names";
import { joinedInCell } from "../people/wordLists";
import { standingSaid } from "./libraryKinds";

export type VersionColumn = "number" | "standing";

export const NEWEST_FIRST: Ordering<VersionColumn> = {
  column: "number",
  descending: true,
};

/** How far along the way a version goes each standing is, closed over the standings in both directions. */
const STANDING_RANKS = {
  draft: 0,
  submitted: 1,
  in_service: 2,
  retired: 3,
} satisfies Record<VersionStanding, number>;

const PAST_EVERY_STANDING = Object.keys(STANDING_RANKS).length;

/** One row per version, the first cell the link that opens it; who wrote and who approved it do not sort. */
export function versionColumns() {
  return [
    {
      label: say("versions.version"),
      cell: (version: Version) =>
        say("version.number", { number: version.number }),
      sortKey: "number",
    },
    {
      label: say("versions.standing"),
      cell: (version: Version) => standingSaid(version.standing),
      sortKey: "standing",
    },
    {
      label: say("versions.writtenBy"),
      cell: (version: Version) =>
        version.writtenByMigration && version.writers.length === 0
          ? say("version.writtenByNobody")
          : joinedInCell(version.writers.map(nameOrNumber)),
    },
    {
      label: say("versions.approvedBy"),
      cell: (version: Version) =>
        version.approval === undefined
          ? ""
          : version.approval.approver === undefined
            ? say("version.approvedByNobody")
            : nameOrNumber(version.approval.approver),
    },
  ] as const;
}

/**
 * The versions in the order asked, a standing by how far along it is and one this build has no words for past
 * them all; versions alike keep the order they came in, which is newest first.
 */
export function versionsInOrder(
  versions: readonly Version[],
  order: Ordering<VersionColumn>,
): Version[] {
  const direction = order.descending ? -1 : 1;
  const rankOf =
    order.column === "number"
      ? (version: Version) => version.number
      : (version: Version) =>
          Object.hasOwn(STANDING_RANKS, version.standing)
            ? STANDING_RANKS[version.standing as VersionStanding]
            : PAST_EVERY_STANDING;
  return [...versions].sort(
    (left, right) => direction * (rankOf(left) - rankOf(right)),
  );
}
