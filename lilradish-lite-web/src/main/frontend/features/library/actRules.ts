import type { EntryAct, VersionAct } from "../../api/groups/{groupId}/{kind}";
import type { GroupRule } from "../../app/standing/actRules";
import PERMISSIONS from "./actPermissions.json";

/**
 * The permission each act on an entry takes, which is the rule a refusal of it
 * is said as. Read from the one table a spec holds level with the server's
 * acts, so a permission moved there is moved here or fails that spec.
 */
export const ENTRY_ACT_RULES = PERMISSIONS.entry satisfies Record<
  EntryAct,
  string
> as Record<EntryAct, GroupRule>;

/** The same for each act on a version. */
export const VERSION_ACT_RULES = PERMISSIONS.version satisfies Record<
  VersionAct,
  string
> as Record<VersionAct, GroupRule>;
