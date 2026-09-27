import Box from "@mui/material/Box";
import Typography from "@mui/material/Typography";
import { useId, useLayoutEffect, useRef } from "react";

import type { MemberPanel } from "../../api/groups/{groupId}/members";
import { takeOut } from "../../api/groups/{groupId}/members/{subjectId}";
import {
  give,
  take,
} from "../../api/groups/{groupId}/members/{subjectId}/roles/{role}";
import type { Problem } from "../../api/problem";
import { ProblemView } from "../../app/ProblemView";
import type { Rule } from "../../app/standing/actRules";
import { say } from "../../i18n/app";
import { ActButton } from "../../lib/action/ActButton";
import { Notice } from "../../lib/notice/Notice";
import type { Action } from "../../lib/request/useAction";
import type { Asked, Outcome } from "./changes";
import {
  GROUP_ROLES,
  groupRoleSaid,
  groupRolesInOrder,
  isGroupRole,
} from "./groupRoles";

const PART_SX = { mt: 3, mb: 1 };

const QUIET_SX = { color: "text.secondary" };

// Its markers taken away, so every list drawn with it is named a list outright.
const LIST_SX = { listStyleType: "none", m: 0, p: 0 };

const ROLE_SX = {
  display: "flex",
  flexWrap: "wrap",
  alignItems: "center",
  gap: 1,
  py: 0.5,
};

const ROLE_NAME_SX = { flexGrow: 1 };

const STACKED_SX = { display: "flex", flexDirection: "column", gap: 1 };

const ROW_SX = { ...STACKED_SX, mt: 3 };

/** What every control here asks. */
const CHANGES: Rule = { inGroup: "change_membership" };

/** A change to this member refused, and which control asked it of them as they then stood. */
export interface Refused {
  readonly place: "roles" | "removal";
  readonly problem: Problem;
}

/**
 * One member of a group, and every role the group has: whether they hold it,
 * and, to a reader who may change the membership, the control giving it or
 * taking it away. Somebody whose last role was just taken is shown holding
 * nothing, and a role given makes them a member again.
 *
 * What nobody else here could change the membership without is not offered
 * for taking, and whoever the server says may not be taken out is not offered
 * for removal; the panel says why.
 *
 * Every change runs under the action handed in, which its holder owns and
 * settles; a refusal is said beside the control that asked.
 */
export function RolesPanel({
  groupId,
  member,
  changing,
  waiting,
  mayChange,
  refused,
  onAsk,
}: {
  readonly groupId: string;
  readonly member: MemberPanel;
  readonly changing: Action<Outcome>;
  /** Something else the reader can see is out, which holds every control too. */
  readonly waiting: boolean;
  readonly mayChange: boolean;
  readonly refused: Refused | null;
  /** Told which control asked, as it asks. */
  readonly onAsk: (asked: Asked) => void;
}) {
  const { subjectId } = member;
  const headingId = useId();
  const heading = useRef<HTMLHeadingElement>(null);
  const offering = useRef(mayChange);
  const held = groupRolesInOrder(member.roles);
  const rows = [...GROUP_ROLES, ...held.filter((role) => !isGroupRole(role))];
  const isMember = member.roles.size > 0;

  // The control the keyboard was on can go with the right to use it; the keyboard stays in the panel.
  useLayoutEffect(() => {
    if (
      offering.current &&
      !mayChange &&
      document.activeElement === document.body
    ) {
      heading.current?.focus();
    }
    offering.current = mayChange;
  }, [mayChange]);

  return (
    <>
      <Typography
        ref={heading}
        id={headingId}
        tabIndex={-1}
        variant="subtitle2"
        component="h3"
        sx={PART_SX}
      >
        {say("member.roles")}
      </Typography>
      {isMember ? null : (
        <Notice severity="info">{say("member.noLonger")}</Notice>
      )}
      <Box component="ul" role="list" aria-labelledby={headingId} sx={LIST_SX}>
        {rows.map((role) => {
          const holds = member.roles.has(role);
          const offered =
            mayChange &&
            isGroupRole(role) &&
            !member.lastChangingRoles.has(role);
          return (
            <Box component="li" key={role} sx={ROLE_SX}>
              <Box component="span" sx={ROLE_NAME_SX}>
                <bdi>{groupRoleSaid(role)}</bdi>
              </Box>
              <Typography component="span" variant="body2" sx={QUIET_SX}>
                {say(holds ? "person.holds" : "person.holdsNot")}
              </Typography>
              {offered ? (
                // One element whichever way it points, so the control just
                // pressed is the one that still has the keyboard after.
                <ActButton
                  action={changing}
                  waiting={waiting}
                  act={(signal) => {
                    onAsk({ place: "roles", of: member });
                    return (holds ? take : give)(
                      groupId,
                      subjectId,
                      role,
                      signal,
                    ).then((changed) => ({ changed }));
                  }}
                >
                  {say(holds ? "member.take" : "member.give", {
                    role: groupRoleSaid(role),
                  })}
                </ActButton>
              ) : null}
            </Box>
          );
        })}
      </Box>
      {mayChange && isMember && !member.removable ? (
        <Notice severity="info">{say("member.lastChanger")}</Notice>
      ) : null}
      {refused?.place === "roles" ? (
        <ProblemView problem={refused.problem} rule={CHANGES} />
      ) : null}

      {mayChange && isMember && member.removable ? (
        <Box sx={ROW_SX}>
          <ActButton
            action={changing}
            waiting={waiting}
            act={(signal) => {
              onAsk({ place: "removal", of: member });
              return takeOut(groupId, subjectId, signal).then(() => ({
                removed: member,
              }));
            }}
          >
            {say("member.remove")}
          </ActButton>
        </Box>
      ) : null}
      {refused?.place === "removal" ? (
        <ProblemView problem={refused.problem} rule={CHANGES} />
      ) : null}
    </>
  );
}
