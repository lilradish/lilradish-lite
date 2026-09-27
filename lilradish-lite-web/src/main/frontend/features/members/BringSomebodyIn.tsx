import Checkbox from "@mui/material/Checkbox";
import FormControl from "@mui/material/FormControl";
import FormControlLabel from "@mui/material/FormControlLabel";
import FormGroup from "@mui/material/FormGroup";
import FormLabel from "@mui/material/FormLabel";
import Stack from "@mui/material/Stack";
import { useCallback, useState } from "react";

import { bringIn, type GroupRole } from "../../api/groups/{groupId}/members";
import { searchPoolOutside } from "../../api/groups/{groupId}/pool/search";
import type { PersonInPool } from "../../api/pool/search";
import { RequestFailed, type Problem } from "../../api/problem";
import { ProblemView } from "../../app/ProblemView";
import { movesTheReader } from "../../app/refusal";
import type { Rule } from "../../app/standing/actRules";
import { say } from "../../i18n/app";
import { ActDialog } from "../../lib/action/ActDialog";
import { Press, type Reason } from "../../lib/action/Press";
import type { Action } from "../../lib/request/useAction";
import { FindOne } from "../../lib/search/FindOne";
import { personTitle } from "../people/personTitle";
import type { Asked, Outcome } from "./changes";
import { GROUP_ROLES, groupRoleSaid } from "./groupRoles";

const FIELDS_SX = { pt: 1 };

/** What bringing somebody in, and searching for them, takes. */
const CHANGES: Rule = { inGroup: "change_membership" };

/**
 * The control that brings somebody in the pool into a group, and the dialog
 * it opens: a search over the pool, which leaves out whoever is in the group
 * already, and a choice of one or more roles. The pool is not this page's
 * subject, so it is searched and never listed: nobody is offered until
 * something is typed.
 *
 * Bringing somebody in runs under the action handed in, which its holder owns
 * and settles, so it outlives this dialog and this control; while any change
 * under it is out, neither opens.
 */
export function BringSomebodyIn({
  groupId,
  changing,
  refused,
  onAsk,
  onMoved,
}: {
  readonly groupId: string;
  readonly changing: Action<Outcome>;
  /** Bringing somebody in, refused. */
  readonly refused: Problem | null;
  /** Told that somebody is being brought in, as it is asked. */
  readonly onAsk: (asked: Asked) => void;
  /**
   * Told a search was refused for the reader's standing, which has moved.
   * Must be stable, for the reason `FindOne` gives of its search.
   */
  readonly onMoved: () => void;
}) {
  const [open, setOpen] = useState(false);
  // Each opening starts from nothing typed, nobody picked and no role chosen,
  // even one begun while the last is still leaving.
  const [opening, setOpening] = useState(0);

  return (
    <>
      <Press
        variant="contained"
        unavailable={changing.running}
        onPress={() => {
          setOpening((count) => count + 1);
          setOpen(true);
        }}
      >
        {say("members.bringIn")}
      </Press>
      <Bringing
        key={opening}
        groupId={groupId}
        open={open}
        changing={changing}
        refused={refused}
        onAsk={onAsk}
        onMoved={onMoved}
        onShut={() => setOpen(false)}
      />
    </>
  );
}

function Bringing({
  groupId,
  open,
  changing,
  refused,
  onAsk,
  onMoved,
  onShut,
}: {
  readonly groupId: string;
  readonly open: boolean;
  readonly changing: Action<Outcome>;
  readonly refused: Problem | null;
  readonly onAsk: (asked: Asked) => void;
  readonly onMoved: () => void;
  readonly onShut: () => void;
}) {
  const [picked, setPicked] = useState<PersonInPool | null>(null);
  const [chosen, setChosen] = useState<ReadonlySet<GroupRole>>(() => new Set());
  // Only a refusal asked from this opening is this opening's to say.
  const [asked, setAsked] = useState(false);
  const search = useCallback(
    (typed: string, signal: AbortSignal) =>
      searchPoolOutside(groupId, typed, signal).catch((failure: unknown) => {
        if (
          failure instanceof RequestFailed &&
          movesTheReader(failure.problem)
        ) {
          onMoved();
        }
        throw failure;
      }),
    [groupId, onMoved],
  );

  return (
    <ActDialog
      open={open}
      title={say("members.bringIn")}
      onShut={onShut}
      action={changing}
      act={(signal) => {
        // Pressable only once somebody and a role are picked, which is what `reason` says.
        const who = picked!;
        setAsked(true);
        onAsk({ place: "bringIn" });
        return bringIn(
          groupId,
          who.subjectId,
          GROUP_ROLES.filter((role) => chosen.has(role)),
          signal,
        ).then((broughtIn) => {
          onShut();
          return { broughtIn };
        });
      }}
      actLabel={say("candidates.takeOn")}
      reason={heldFor(picked, chosen)}
      abandonLabel={say("candidates.abandon")}
      underway={say("candidates.underway")}
      refusal={
        !asked || refused === null ? null : (
          <ProblemView problem={refused} rule={CHANGES} />
        )
      }
    >
      <Stack spacing={2} sx={FIELDS_SX}>
        <FindOne
          label={say("person.numberOrName")}
          search={search}
          keyOf={subjectIdOf}
          title={personTitle}
          picked={picked}
          onPick={setPicked}
          empty={say("candidates.noMatch")}
          refusal={refusalOf}
          autoFocus
        />
        <FormControl component="fieldset">
          <FormLabel component="legend">{say("candidates.roles")}</FormLabel>
          <FormGroup>
            {GROUP_ROLES.map((role) => (
              <FormControlLabel
                key={role}
                control={
                  <Checkbox
                    checked={chosen.has(role)}
                    onChange={(_event, checked) =>
                      setChosen((now) => {
                        const next = new Set(now);
                        if (checked) {
                          next.add(role);
                        } else {
                          next.delete(role);
                        }
                        return next;
                      })
                    }
                  />
                }
                label={groupRoleSaid(role)}
              />
            ))}
          </FormGroup>
        </FormControl>
      </Stack>
    </ActDialog>
  );
}

/** The first thing still missing, in the order the dialog asks for them. */
function heldFor(
  picked: PersonInPool | null,
  chosen: ReadonlySet<GroupRole>,
): Reason | undefined {
  if (picked === null) {
    return { severity: "info", words: say("candidates.pickFirst") };
  }
  return chosen.size === 0
    ? { severity: "info", words: say("candidates.pickRole") }
    : undefined;
}

function subjectIdOf(person: PersonInPool): string {
  return person.subjectId;
}

function refusalOf(problem: Problem) {
  return <ProblemView problem={problem} rule={CHANGES} />;
}
