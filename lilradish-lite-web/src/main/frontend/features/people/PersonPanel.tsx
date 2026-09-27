import Box from "@mui/material/Box";
import Typography from "@mui/material/Typography";

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
import type { Reason } from "../../lib/action/Press";
import { Field, Fields } from "../../lib/form/Fields";
import { Notice } from "../../lib/notice/Notice";
import type { Action } from "../../lib/request/useAction";
import type { Asked, Outcome } from "./changes";
import {
  ESTATE_ROLES,
  heldInOrder,
  isEstateRole,
  roleSaid,
} from "./estateRoles";
import { whatHasToGoFirst } from "./whatHasToGoFirst";

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

  return (
    <>
      <Typography variant="subtitle2" component="h3" sx={PART_SX}>
        {say("person.who")}
      </Typography>
      <Box sx={STACKED_SX}>
        <Notice severity="info">{say("person.whereHeld")}</Notice>
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
      </Box>

      <Typography variant="subtitle2" component="h3" sx={PART_SX}>
        {say("person.estateRoles")}
      </Typography>
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

      <Typography variant="subtitle2" component="h3" sx={PART_SX}>
        {say("person.groups")}
      </Typography>
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

      <Box sx={ROW_SX}>
        {person.seeded ? (
          <Notice severity="info">{say("person.seeded")}</Notice>
        ) : null}
        <ActButton
          action={changing}
          waiting={waiting}
          act={(signal) => {
            onAsk({ place: "removal", of: person });
            return takeOut(subjectId, signal).then(() => ({
              removed: person,
            }));
          }}
          reason={asReason(firstToGo)}
        >
          {say("person.remove")}
        </ActButton>
        {removalRefused === null ? null : (
          <ProblemView problem={removalRefused} rule={{ act: "keep_pool" }} />
        )}
      </Box>
    </>
  );
}

function asReason(words: string | undefined): Reason | undefined {
  return words === undefined ? undefined : { severity: "warning", words };
}
