import type { RegisteredGroup } from "../../api/groups";

export type Outcome =
  { readonly created: RegisteredGroup } | { readonly renamed: RegisteredGroup };
