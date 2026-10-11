import { createContext, useContext, type ReactNode } from "react";
import { Navigate, useLocation, useParams } from "react-router";

import { mayIn, type GroupStanding } from "../../api/standing";
import { say } from "../../i18n/app";
import { groupAddressed, groupNamed, type GroupGate } from "../destinations";
import { NoSuchScreen } from "../RouteError";
import { ruleInGroup } from "./actRules";
import { OnceAnswered, Refused } from "./RequireStanding";

const GroupContext = createContext<GroupStanding | null>(null);

/**
 * A page of the group the address names: missing where the reader is not in
 * it, so nothing says it exists, and refused in place where they are.
 *
 * Replaces a non-key spelling with the group's key, so Back never returns to the other spelling.
 */
export function RequireGroup({
  reachedBy,
  children,
}: {
  readonly reachedBy: GroupGate | null;
  readonly children: ReactNode;
}) {
  const { group: named } = useParams();
  const { pathname, search, hash, state } = useLocation();

  return (
    <OnceAnswered>
      {(standing) => {
        const group = groupNamed(standing.groups, named);
        if (group === undefined) {
          return <NoSuchScreen />;
        }
        const written = groupAddressed(group, pathname);
        if (written !== pathname) {
          return (
            <Navigate
              to={{ pathname: written, search, hash }}
              state={state}
              replace
            />
          );
        }
        return reachedBy === null || mayIn(group, reachedBy) ? (
          <GroupContext value={group}>{children}</GroupContext>
        ) : (
          <Refused rule={ruleInGroup(reachedBy)} />
        );
      }}
    </OnceAnswered>
  );
}

/**
 * Throws where no `RequireGroup` stands above, rather than guessing one from the address.
 */
export function useGroup(): GroupStanding {
  const group = useContext(GroupContext);
  if (group === null) {
    throw new Error("useGroup was called outside a RequireGroup");
  }
  return group;
}

/** My work, refused in place to somebody in no group: it gathers from groups and nowhere else. */
export function RequireMyWork({ children }: { readonly children: ReactNode }) {
  return (
    <OnceAnswered>
      {(standing) =>
        standing.groups.length > 0 ? (
          <>{children}</>
        ) : (
          <Refused rule={say("myWork.rule")} />
        )
      }
    </OnceAnswered>
  );
}
