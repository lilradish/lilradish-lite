import { isUnchecked, wordsIn, type Unchecked } from "../lib/request/document";
import { get } from "../lib/request/http";
import { useResource, type Resource } from "../lib/request/useResource";

const STANDING = "/api/standing";

/**
 * The estate's acts, spelt the way the server publishes them. A surface is
 * gated on an act and never on a role: roles bundle acts, and a side reading
 * roles would be keeping a second copy of that table.
 *
 * No compiler ties this list to the server's — one is erased before anything
 * runs and the other is in another language — so what holds the two level is a
 * spec beside the server's own table, which reads this declaration as text.
 */
export type SurfaceAct =
  | "keep_pool"
  | "grant_estate_role"
  | "keep_group_register"
  | "check_soundness"
  | "read_measurements";

/**
 * A group's permissions as published, held level with the server's the way
 * `SurfaceAct` is; a group's page is gated on one and never on a role.
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

/** A group the reader is in, addressed by `groupId` and never by `key`. */
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

/** The same question inside one group, answered by that group's grants alone. */
export function mayIn(
  group: GroupStanding,
  permission: GroupPermission,
): boolean {
  return group.permissions.has(permission);
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
  return {
    acts: new Set(wordsIn(answer.acts) ?? []),
    groups: Array.isArray(answer.groups)
      ? answer.groups.flatMap(groupFrom)
      : [],
  };
}

/** A group that cannot be addressed or shown is left out, and the rest kept. */
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
 * Who the reader is, learnt in the one place it is learnt. Nothing downstream
 * asks for its own: a second read is a second answer, and two screens
 * disagreeing about what somebody may do is the shape that gets debugged as a
 * permissions bug.
 *
 * A refusal takes the standing with it and the frame renders a reader who may
 * go nowhere, which is the direction this has to fail in.
 */
export function useStandingResource(): Resource<Standing> {
  return useResource(readStanding, NOTHING);
}
