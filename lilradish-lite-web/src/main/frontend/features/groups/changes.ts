import type { RegisteredGroup } from "../../api/groups";

/** What a change to the register settled as. */
export type Outcome =
  { readonly created: RegisteredGroup } | { readonly renamed: RegisteredGroup };
