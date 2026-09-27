import type { Problem } from "../api/problem";
import type { GroupStanding, Standing } from "../api/standing";
import type { Resource } from "../lib/request/useResource";

/**
 * A standing read that has already settled, for the specs of everything
 * downstream of the one component that issues it. Those components take the
 * read rather than the answer, because the difference between "nothing yet" and
 * "nothing" is what decides whether a screen waits or refuses.
 *
 * It sits in the source tree because the specs do: this project has no separate
 * test root to put it in. Only a spec may import it — it is kept out of the
 * coverage report and no build input names it, and a production module
 * importing it would quietly undo both.
 */
export function answered(
  acts: Iterable<string>,
  groups: readonly GroupStanding[] = [],
): Resource<Standing> {
  return {
    value: { acts: new Set(acts), groups },
    problem: null,
    loading: false,
    reload: noop,
  };
}

/** A group the reader is in, holding exactly the permissions named. */
export function inGroup(
  groupId: string,
  key: string,
  name: string,
  permissions: Iterable<string>,
): GroupStanding {
  return { groupId, key, name, permissions: new Set(permissions) };
}

/** A read still out: the standing is empty, and that emptiness means nothing yet. */
export function stillReading(): Resource<Standing> {
  return { value: nothing(), problem: null, loading: true, reload: noop };
}

/** A read the server refused, which takes the standing with it. */
export function refused(problem: Problem): Resource<Standing> {
  return { value: nothing(), problem, loading: false, reload: noop };
}

function nothing(): Standing {
  return { acts: new Set(), groups: [] };
}

function noop() {}
