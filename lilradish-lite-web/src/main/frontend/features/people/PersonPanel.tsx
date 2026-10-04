import Box from "@mui/material/Box";
import Typography from "@mui/material/Typography";
import { useState } from "react";

import type { Problem } from "../../api/problem";
import type { PoolPersonPanel } from "../../api/pool/people";
import {
  refusedForWhatStillHolds,
  takeOut,
} from "../../api/pool/people/{subjectId}";
import {
  grant,
  withdraw,
} from "../../api/pool/people/{subjectId}/estate-roles/{role}";
import { ProblemView } from "../../app/ProblemView";
import { say } from "../../i18n/app";
import { ActButton } from "../../lib/action/ActButton";
import { ActDialog } from "../../lib/action/ActDialog";
import { Press } from "../../lib/action/Press";
import { isolatedInText } from "../../lib/direction/isolated";
import type { Reason } from "../../lib/action/Press";
import { Field, Fields } from "../../lib/form/Fields";
import { Part } from "../../lib/layout/Part";
import { LIST_SX } from "../../lib/layout/parts";
import type { Action } from "../../lib/request/useAction";
import { nameOrNumber } from "../../lib/text/names";
import type { Asked, Outcome } from "./changes";
import {
  ESTATE_ROLES,
  heldInOrder,
  isEstateRole,
  roleSaid,
} from "./estateRoles";
import { whatHasToGoFirst } from "./whatHasToGoFirst";

const QUIET_SX = { color: "text.secondary" };

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

/** A change to this person refused, and which control asked it of them as they then stood. */
export interface Refused {
  readonly place: "roles" | "removal";
  readonly of: PoolPersonPanel;
  readonly problem: Problem;
}

/**
 * One person in the pool. What a group trusts them with inside it is the
 * group's, and is nowhere here.
 *
 * Every change to them runs under the action handed in, which its holder owns
 * and settles; a refusal is said beside the control that asked.
 */
export function PersonPanel({
  person,
  changing,
  waiting,
  mayChangeRoles,
  refused,
  onAsk,
}: {
  readonly person: PoolPersonPanel;
  readonly changing: Action<Outcome>;
  /** Something else the reader can see is out, which holds every control too. */
  readonly waiting: boolean;
  readonly mayChangeRoles: boolean;
  readonly refused: Refused | null;
  /** Told which control asked, as it asks. */
  readonly onAsk: (asked: Asked) => void;
}) {
  const { subjectId } = person;
  const [confirmation, setConfirmation] = useState<{
    seenProblem: Problem | null;
  } | null>(null);
  const held = heldInOrder(person.estateRoles);
  // Every role this build knows, and one it does not only where it is held.
  const rows = [...ESTATE_ROLES, ...held.filter((role) => !isEstateRole(role))];
  const firstToGo = whatHasToGoFirst(person);
  const rolesRefused = refused?.place === "roles" ? refused.problem : null;
  // A refusal for what still held them is answered once they have been read
  // again and nothing has to go first.
  const removalRefused =
    refused?.place === "removal" &&
    !(
      refusedForWhatStillHolds(refused.problem) &&
      refused.of !== person &&
      firstToGo === undefined
    )
      ? refused.problem
      : null;
  const confirmOpen =
    confirmation !== null && confirmation.seenProblem === changing.problem;

  return (
    <>
      <Part title={say("person.who")} level="h3" depth={3}>
        <Fields>
          <Field label={say("person.userId")}>
            <bdi>{person.userId}</bdi>
          </Field>
          <Field label={say("person.name")}>
            {person.displayName === undefined ? (
              say("person.nameless")
            ) : (
              <bdi>{person.displayName}</bdi>
            )}
          </Field>
        </Fields>
      </Part>
      <Part title={say("person.estateRoles")} level="h3" depth={3}>
        <Box component="ul" role="list" sx={LIST_SX}>
          {rows.map((role) => {
            const holds = person.estateRoles.has(role);
            const offered =
              mayChangeRoles &&
              isEstateRole(role) &&
              !person.lastGrantingRoles.has(role);
            return (
              <Box component="li" key={role} sx={ROLE_SX}>
                <Box component="span" sx={ROLE_NAME_SX}>
                  <bdi>{roleSaid(role)}</bdi>
                </Box>
                <Typography component="span" variant="body2" sx={QUIET_SX}>
                  {say(holds ? "person.holds" : "person.holdsNot")}
                </Typography>
                {offered ? (
                  // One element whichever way it points, so the control just
                  // pressed is the one that still has the keyboard after.
                  <ActButton
                    action={changing}
                    warns={holds}
                    waiting={waiting}
                    act={(signal) => {
                      onAsk({ place: "roles", of: person });
                      return (holds ? withdraw : grant)(
                        subjectId,
                        role,
                        signal,
                      ).then((changed) => ({ changed }));
                    }}
                  >
                    {say(holds ? "person.withdraw" : "person.grant", {
                      role: roleSaid(role),
                    })}
                  </ActButton>
                ) : null}
              </Box>
            );
          })}
        </Box>
        {rolesRefused === null ? null : (
          <ProblemView
            problem={rolesRefused}
            rule={{ act: "grant_estate_role" }}
          />
        )}
      </Part>
      <Part title={say("person.groups")} level="h3" depth={3}>
        {person.groups.length === 0 ? (
          <Typography variant="body2">{say("person.inNoGroup")}</Typography>
        ) : (
          <Box component="ul" role="list" sx={LIST_SX}>
            {/* Keyed by position: a list read whole and never reordered, whose
                names nothing here promises are distinct. */}
            {person.groups.map((group, at) => (
              <li key={at}>
                <bdi>{group}</bdi>
              </li>
            ))}
          </Box>
        )}
      </Part>
      <Box sx={ROW_SX}>
        <Press
          warns
          unavailable={waiting || changing.running}
          reason={asReason(firstToGo)}
          onPress={() => setConfirmation({ seenProblem: changing.problem })}
        >
          {say("person.remove")}
        </Press>
        <ActDialog
          open={confirmOpen}
          title={say("person.removeConfirm", {
            name: isolatedInText(nameOrNumber(person)),
          })}
          onShut={() => setConfirmation(null)}
          action={changing}
          act={(signal) => {
            setConfirmation({ seenProblem: null });
            onAsk({ place: "removal", of: person });
            return takeOut(subjectId, signal).then(() => {
              setConfirmation(null);
              return { removed: person };
            });
          }}
          actLabel={say("confirm.remove")}
          warns
          reason={asReason(firstToGo)}
          abandonLabel={say("confirm.cancel")}
          underway={say("person.removeUnderway")}
          refusal={null}
        >
          {say("person.removeConfirmBody")}
        </ActDialog>
        {removalRefused === null || confirmOpen ? null : (
          <ProblemView problem={removalRefused} rule={{ act: "keep_pool" }} />
        )}
      </Box>
    </>
  );
}

function asReason(words: string | undefined): Reason | undefined {
  return words === undefined ? undefined : { severity: "warning", words };
}
