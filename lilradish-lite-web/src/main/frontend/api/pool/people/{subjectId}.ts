import type { WordedRefusal } from "../../../i18n/app";
import { atSegment } from "../../../lib/request/address";
import { get, remove } from "../../../lib/request/http";
import type { Problem } from "../../problem";
import { PEOPLE, panelFrom, type PoolPersonPanel } from "../people";

const STILL_HELD: ReadonlySet<string> = new Set([
  "PERSON_HOLDS_ESTATE_ROLES",
  "PERSON_IN_GROUPS",
] satisfies readonly WordedRefusal[]);

export function readPerson(
  subjectId: string,
  signal: AbortSignal,
): Promise<PoolPersonPanel> {
  return atPerson(subjectId, (address) => get(address, signal, panelFrom));
}

/** Answered with no content: there is nobody left in the pool to read. */
export function takeOut(subjectId: string, signal: AbortSignal): Promise<void> {
  return atPerson(subjectId, (address) =>
    remove(address, signal, (body) => (body === undefined ? undefined : null)),
  );
}

/**
 * The refusal does not name what still holds them; reading them again does.
 * Read status before code, as `Problem` requires.
 */
export function refusedForWhatStillHolds(problem: Problem): boolean {
  return problem.status === 409 && STILL_HELD.has(problem.code);
}

/** The person's address handed to `ask`, made as `atSegment` makes one. */
export function atPerson<T>(
  subjectId: string,
  ask: (address: string) => Promise<T>,
): Promise<T> {
  return atSegment(PEOPLE, subjectId, ask);
}
