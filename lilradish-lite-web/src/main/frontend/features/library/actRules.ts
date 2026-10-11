import type {
  Entry,
  EntryAct,
  Version,
  VersionAct,
} from "../../api/groups/{groupId}/{kind}";
import type { Standing } from "../../api/standing";
import type { GroupRule } from "../../app/standing/actRules";
import PERMISSIONS from "./actPermissions.json";

/** The permission each act on an entry takes when a refusal must be said. */
export const ENTRY_ACT_RULES = PERMISSIONS.entry satisfies Record<
  EntryAct,
  string
> as Record<EntryAct, GroupRule>;

export const VERSION_ACT_RULES = PERMISSIONS.version satisfies Record<
  VersionAct,
  string
> as Record<VersionAct, GroupRule>;

/** Known state acts meet the current standing's group permissions; unknown words grant nothing. */
export function entryOffers(
  entry: Entry,
  act: EntryAct,
  standing: Standing,
  groupId: string,
): boolean {
  return (
    Object.hasOwn(ENTRY_ACT_RULES, act) &&
    entry.stateActs?.has(act) === true &&
    standing.groups
      .find((group) => group.groupId === groupId)
      ?.permissions.has(ENTRY_ACT_RULES[act]) === true
  );
}

/** Approving additionally requires a known reader who is not among the public writers. */
export function versionOffers(
  version: Version,
  act: VersionAct,
  standing: Standing,
  groupId: string,
): boolean {
  if (
    !Object.hasOwn(VERSION_ACT_RULES, act) ||
    version.stateActs?.has(act) !== true ||
    standing.groups
      .find((group) => group.groupId === groupId)
      ?.permissions.has(VERSION_ACT_RULES[act]) !== true
  ) {
    return false;
  }
  return (
    act !== "approve" ||
    (standing.userId !== undefined &&
      !version.writers.some((writer) => writer.userId === standing.userId))
  );
}
