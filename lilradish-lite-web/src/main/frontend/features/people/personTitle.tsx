import type { ReactNode } from "react";

/**
 * Somebody as a search offers them: their user number, then their name where
 * one is held, each set apart; nothing stands in for a name not held.
 */
export function personTitle(person: {
  readonly userId: string;
  readonly displayName?: string;
}): ReactNode {
  if (person.displayName === undefined) {
    return <bdi>{person.userId}</bdi>;
  }
  return (
    <>
      <bdi>{person.userId}</bdi> <bdi>{person.displayName}</bdi>
    </>
  );
}
