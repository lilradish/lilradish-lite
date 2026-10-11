import { isUnchecked, wordsIn, type Unchecked } from "../lib/request/document";
import { get } from "../lib/request/http";
import { useResource, type Resource } from "../lib/request/useResource";
import type { Person } from "./person";

const STANDING = "/api/standing";

/**
 * Acts use the server's spelling. Gate on acts, never duplicate role bundles here.
 * No compiler ties the languages together: a spec beside the server's table
 * reads this erased declaration as text to hold the two level.
 */
export type SurfaceAct =
  | "keep_pool"
  | "grant_estate_role"
  | "keep_group_register"
  | "check_soundness"
  | "read_measurements";

/**
 * Permissions use the server's spelling, held level as `SurfaceAct` is.
 * Group pages are gated on a permission, never a role.
 */
export type GroupPermission =
  | "read_membership"
  | "change_membership"
  | "start_run"
  | "read_own_runs"
  | "read_all_runs"
  | "read_inference_content"
  | "answer_step"
  | "review_at_gate"
  | "author_entry"
  | "approve_entry"
  | "revoke_entry";

/** A group the reader is in, asked about by `groupId` and never by `key`; a page's address names it by `key`. */
export interface GroupStanding {
  readonly groupId: string;
  readonly key: string;
  /** Shown isolated wherever it is shown: a name may carry a directional control. */
  readonly name: string;
  readonly permissions: ReadonlySet<string>;
}

/**
 * Words kept as the server sent them, so one this build does not know gates
 * nothing rather than failing; the groups are the reader's own, in its order.
 */
export interface Standing {
  /** The reader's own, as an answer naming a person names them; absent where the server knows them as nobody. */
  readonly userId?: string;
  readonly acts: ReadonlySet<string>;
  readonly groups: readonly GroupStanding[];
}

/**
 * Whether the act is within reach. Never whether an attempt at it would be
 * admitted: what may be reached is decided per surface, and what a given
 * attempt runs into is decided against the one thing it names.
 */
export function holds(standing: Standing, act: SurfaceAct): boolean {
  return standing.acts.has(act);
}

export function mayIn(
  group: GroupStanding,
  permission: GroupPermission,
): boolean {
  return group.permissions.has(permission);
}

/**
 * Whether the person an answer names is the reader, told by the user each is
 * known by and never by a name, which two people may share. Nobody named, or a
 * reader known as nobody, is never the reader.
 */
export function isTheReader(
  standing: Standing,
  person: Person | undefined,
): boolean {
  return standing.userId !== undefined && person?.userId === standing.userId;
}

/**
 * Held as a module constant, for the reason `useResource` gives: the read is
 * keyed on the value handed in, and one built per render reads forever.
 */
const NOTHING: Standing = { acts: new Set(), groups: [] };

function readStanding(signal: AbortSignal): Promise<Standing> {
  return get(STANDING, signal, standingFrom);
}

/**
 * A document short of a list is a reader who may do nothing there, never one
 * this side fails on.
 */
function standingFrom(body: unknown): Standing {
  const answer: Unchecked = isUnchecked(body) ? body : {};
  const standing = {
    acts: new Set(wordsIn(answer.acts) ?? []),
    groups: Array.isArray(answer.groups)
      ? answer.groups.flatMap(groupFrom)
      : [],
  };
  return typeof answer.userId === "string"
    ? { userId: answer.userId, ...standing }
    : standing;
}

function groupFrom(listed: unknown): GroupStanding[] {
  if (
    !isUnchecked(listed) ||
    typeof listed.groupId !== "string" ||
    typeof listed.key !== "string" ||
    typeof listed.name !== "string"
  ) {
    return [];
  }
  return [
    {
      groupId: listed.groupId,
      key: listed.key,
      name: listed.name,
      permissions: new Set(wordsIn(listed.permissions) ?? []),
    },
  ];
}

/**
 * Downstream shares this read: separate answers could disagree about permissions.
 * A refusal discards the standing, so the frame fails with no destinations.
 */
export function useStandingResource(): Resource<Standing> {
  return useResource(readStanding, NOTHING);
}
