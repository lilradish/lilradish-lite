import type { MemberPanel } from "../../api/groups/{groupId}/members";

/** What a change to a group's membership settled as. */
export type Outcome =
  | { readonly changed: MemberPanel }
  | { readonly removed: MemberPanel }
  | { readonly broughtIn: MemberPanel };

/**
 * Which control asked, and of whom: a refusal is said beside that control
 * while its member is in view, and in the page's own words once they are not.
 * Bringing somebody in is refused only in the dialog that asked it.
 */
export type Asked =
  | { readonly place: "roles" | "removal"; readonly of: MemberPanel }
  | { readonly place: "bringIn" };
