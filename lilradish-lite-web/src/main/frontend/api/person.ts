import { isUnchecked, optional, textFrom } from "../lib/request/document";

/** Somebody an answer names, by what identifies them to a reader. */
export interface Person {
  readonly userId: string;
  /** Shown isolated wherever it is shown: a name may carry a directional control. */
  readonly displayName?: string;
}

/** Somebody as an answer names them; one whose name is anything but text is not somebody this side can show. */
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
