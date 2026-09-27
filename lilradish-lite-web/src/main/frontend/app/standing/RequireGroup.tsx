import type { ReactNode } from "react";
import { useParams } from "react-router";

import { mayIn } from "../../api/standing";
import { say } from "../../i18n/app";
import type { GroupGate } from "../destinations";
import { NoSuchScreen } from "../RouteError";
import { ruleInGroup } from "./actRules";
import { OnceAnswered, Refused } from "./RequireStanding";

/**
 * A page of the group the address names: missing where the reader is not in
 * it, so nothing says it exists, and refused in place where they are.
 */
export function RequireGroup({
  reachedBy,
  children,
}: {
  readonly reachedBy: GroupGate | null;
  readonly children: ReactNode;
}) {
  const { groupId } = useParams();

  return (
    <OnceAnswered>
      {(standing) => {
        const group = standing.groups.find((each) => each.groupId === groupId);
        if (group === undefined) {
          return <NoSuchScreen />;
        }
        return reachedBy === null || mayIn(group, reachedBy) ? (
          <>{children}</>
        ) : (
          <Refused rule={ruleInGroup(reachedBy)} />
        );
      }}
    </OnceAnswered>
  );
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
