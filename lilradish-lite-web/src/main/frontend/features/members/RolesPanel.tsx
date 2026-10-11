import Box from "@mui/material/Box";
import { useId, useLayoutEffect, useRef, useState } from "react";

import type {
  GroupRole,
  MemberPanel,
} from "../../api/groups/{groupId}/members";
import { takeOut } from "../../api/groups/{groupId}/members/{subjectId}";
import {
  give,
  take,
} from "../../api/groups/{groupId}/members/{subjectId}/roles/{role}";
import type { Problem } from "../../api/problem";
import { ProblemView } from "../../app/ProblemView";
import type { Rule } from "../../app/standing/actRules";
import { say } from "../../i18n/app";
import { ActDialog } from "../../lib/action/ActDialog";
import { Press } from "../../lib/action/Press";
import { isolatedInText } from "../../lib/direction/isolated";
import { Part } from "../../lib/layout/Part";
import { PANEL_PART_SX } from "../../lib/layout/SidePanelHost";
import { LIST_SX } from "../../lib/layout/parts";
import { Notice } from "../../lib/notice/Notice";
import type { Action } from "../../lib/request/useAction";
import { nameOrNumber } from "../../lib/text/names";
import { RoleLine, useKeyboardKeptAfterChange } from "../people/RoleLine";
import type { Asked, Outcome } from "./changes";
import {
  GROUP_ROLES,
  groupRoleSaid,
  groupRolesInOrder,
  isGroupRole,
} from "./groupRoles";

const STACKED_SX = { display: "flex", flexDirection: "column", gap: 1 };

const ROW_SX = { ...STACKED_SX, mt: 3 };

const CHANGES: Rule = { inGroup: "change_membership" };

/** A change to this member refused, and which control asked it of them as they then stood. */
export interface Refused {
  readonly place: "roles" | "removal";
  readonly problem: Problem;
}

/**
 * One member of a group, and every role the group has: whether they hold it,
 * and, to a reader who may change the membership, the control granting it or
 * withdrawing it. Somebody whose last role was just withdrawn is shown holding
 * nothing, and a role granted makes them a member again.
 *
 * What nobody else here could change the membership without is not offered
 * for withdrawing, and whoever the server says may not be taken out is not
 * offered for removal; the panel says why.
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
  readonly onAsk: (asked: Asked) => void;
}) {
  const { subjectId } = member;
  const headingId = useId();
  const heading = useRef<HTMLHeadingElement>(null);
  const offering = useRef(mayChange);
  const held = groupRolesInOrder(member.roles);
  const rows = [...GROUP_ROLES, ...held.filter((role) => !isGroupRole(role))];
  const isMember = member.roles.size > 0;
  const [confirmation, setConfirmation] = useState<{
    seenProblem: Problem | null;
  } | null>(null);
  const confirmOpen =
    confirmation !== null && confirmation.seenProblem === changing.problem;

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
  const isOffered = (role: GroupRole) =>
    mayChange && !member.lastChangingRoles.has(role);
  const asking = useKeyboardKeptAfterChange(
    heading,
    changing.running,
    isOffered,
  );

  return (
    <>
      <Box data-panel-part sx={PANEL_PART_SX}>
        <Part
          title={say("member.roles")}
          level="h3"
          depth={3}
          headingId={headingId}
          headingRef={heading}
        >
          {isMember ? null : (
            <Notice severity="info">{say("member.noLonger")}</Notice>
          )}
          <Box
            component="ul"
            role="list"
            aria-labelledby={headingId}
            sx={LIST_SX}
          >
            {rows.map((role) => {
              const holds = member.roles.has(role);
              return (
                <RoleLine
                  key={role}
                  said={groupRoleSaid(role)}
                  holds={holds}
                  change={
                    isGroupRole(role) && isOffered(role)
                      ? {
                          action: changing,
                          waiting,
                          act: (signal) => {
                            asking(role);
                            onAsk({ place: "roles", of: member });
                            return (holds ? take : give)(
                              groupId,
                              subjectId,
                              role,
                              signal,
                            ).then((changed) => ({ changed }));
                          },
                        }
                      : null
                  }
                />
              );
            })}
          </Box>
          {mayChange && isMember && !member.removable ? (
            <Notice severity="info">{say("member.lastChanger")}</Notice>
          ) : null}
          {refused?.place === "roles" ? (
            <ProblemView problem={refused.problem} rule={CHANGES} />
          ) : null}
        </Part>
      </Box>
      {mayChange && isMember && member.removable ? (
        <Box sx={ROW_SX}>
          <Press
            tier="danger"
            unavailable={waiting || changing.running}
            onPress={() => setConfirmation({ seenProblem: changing.problem })}
          >
            {say("member.remove")}
          </Press>
          <ActDialog
            open={confirmOpen}
            title={say("member.removeConfirm", {
              name: isolatedInText(nameOrNumber(member)),
            })}
            onShut={() => setConfirmation(null)}
            action={changing}
            act={(signal) => {
              setConfirmation({ seenProblem: null });
              onAsk({ place: "removal", of: member });
              return takeOut(groupId, subjectId, signal).then(() => {
                setConfirmation(null);
                return { removed: member };
              });
            }}
            actLabel={say("confirm.remove")}
            tier="dangerConfirm"
            abandonLabel={say("confirm.cancel")}
            underway={say("member.removeUnderway")}
            refusal={null}
          >
            {say("member.removeConfirmBody")}
          </ActDialog>
        </Box>
      ) : null}
      {refused?.place === "removal" && !confirmOpen ? (
        <ProblemView problem={refused.problem} rule={CHANGES} />
      ) : null}
    </>
  );
}
