import type { PoolPersonPanel } from "../../api/pool/people";

/** What a change to the pool settled as. */
export type Outcome =
  | { readonly changed: PoolPersonPanel }
  | { readonly removed: PoolPersonPanel }
  | { readonly broughtIn: PoolPersonPanel };

/**
 * Which control asked, and of whom: a refusal is said beside that control
 * while its person is in view, and in the page's own words once they are not.
 * Bringing somebody in is refused only in the dialog that asked it.
 */
export type Asked =
  | { readonly place: "roles" | "removal"; readonly of: PoolPersonPanel }
  | { readonly place: "bringIn" };
