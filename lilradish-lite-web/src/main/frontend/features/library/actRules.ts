import type { EntryAct, VersionAct } from "../../api/groups/{groupId}/{kind}";
import type { GroupRule } from "../../app/standing/actRules";
import PERMISSIONS from "./actPermissions.json";

/** The permission each act on an entry takes when a refusal must be said. */
export const ENTRY_ACT_RULES = PERMISSIONS.entry satisfies Record<
  EntryAct,
  string
> as Record<EntryAct, GroupRule>;

/** The permission each act on a version takes. */
export const VERSION_ACT_RULES = PERMISSIONS.version satisfies Record<
  VersionAct,
  string
> as Record<VersionAct, GroupRule>;
