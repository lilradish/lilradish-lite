import { isUnchecked, optional, textFrom } from "../lib/request/document";

export interface Person {
  readonly userId: string;
  /** Shown isolated wherever it is shown: a name may carry a directional control. */
  readonly displayName?: string;
}

/** A supplied non-text name refuses the person; an absent name does not. */
export function personFrom(body: unknown): Person | null {
  if (!isUnchecked(body) || typeof body.userId !== "string") {
    return null;
  }
  const { userId } = body;
  const displayName = optional(body, "displayName", textFrom);
  if (displayName === null) {
    return null;
  }
  return displayName === undefined ? { userId } : { userId, displayName };
}
