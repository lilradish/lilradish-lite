import { useState } from "react";

import { searchDirectory, type PersonInDirectory } from "../../api/people";
import { bringIn } from "../../api/pool/people";
import type { Problem } from "../../api/problem";
import { ProblemView } from "../../app/ProblemView";
import { say } from "../../i18n/app";
import { ActDialog } from "../../lib/action/ActDialog";
import { Press } from "../../lib/action/Press";
import type { Action } from "../../lib/request/useAction";
import { FindOne } from "../../lib/search/FindOne";
import type { Asked, Outcome } from "./changes";
import { personTitle } from "./personTitle";

/**
 * The control that brings somebody in the directory into the pool, and the
 * dialog it opens. The directory is not this page's subject, so it is searched
 * and never listed: nobody is offered until something is typed.
 *
 * Bringing somebody in runs under the action handed in, which its holder owns
 * and settles, so it outlives this dialog and this control; while any change
 * under it is out, neither opens.
 */
export function AddSomebody({
  changing,
  refused,
  onAsk,
}: {
  readonly changing: Action<Outcome>;
  /** Bringing somebody in, refused. */
  readonly refused: Problem | null;
  /** Told who is being brought in, as it is asked. */
  readonly onAsk: (asked: Asked) => void;
}) {
  const [open, setOpen] = useState(false);
  // Each opening starts from nothing typed and nobody picked, even one begun
  // while the last is still leaving.
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
        {say("pool.add")}
      </Press>
      <Bringing
        key={opening}
        open={open}
        changing={changing}
        refused={refused}
        onAsk={onAsk}
        onShut={() => setOpen(false)}
      />
    </>
  );
}

function Bringing({
  open,
  changing,
  refused,
  onAsk,
  onShut,
}: {
  readonly open: boolean;
  readonly changing: Action<Outcome>;
  readonly refused: Problem | null;
  readonly onAsk: (asked: Asked) => void;
  readonly onShut: () => void;
}) {
  const [picked, setPicked] = useState<PersonInDirectory | null>(null);
  // Only a refusal asked from this opening is this opening's to say.
  const [asked, setAsked] = useState(false);

  return (
    <ActDialog
      open={open}
      title={say("pool.add")}
      onShut={onShut}
      action={changing}
      act={(signal) => {
        // Pressable only once somebody is picked, which is what `reason` says.
        const who = picked!;
        setAsked(true);
        onAsk({ place: "bringIn" });
        return bringIn(who.userId, signal).then((broughtIn) => {
          onShut();
          return { broughtIn };
        });
      }}
      actLabel={say("directory.bringIn")}
      reason={
        picked === null
          ? { severity: "info", words: say("directory.pickFirst") }
          : undefined
      }
      abandonLabel={say("directory.abandon")}
      underway={say("directory.underway")}
      refusal={
        !asked || refused === null ? null : (
          <ProblemView problem={refused} rule={{ act: "keep_pool" }} />
        )
      }
    >
      <FindOne
        label={say("person.numberOrName")}
        search={searchDirectory}
        keyOf={userIdOf}
        title={personTitle}
        whyNot={alreadyInPool}
        picked={picked}
        onPick={setPicked}
        empty={say("directory.noMatch")}
        refusal={refusalOf}
        autoFocus
      />
    </ActDialog>
  );
}

function userIdOf(person: PersonInDirectory): string {
  return person.userId;
}

function alreadyInPool(person: PersonInDirectory): string | null {
  return person.subjectId === undefined ? null : say("directory.inPool");
}

function refusalOf(problem: Problem) {
  return <ProblemView problem={problem} rule={{ act: "keep_pool" }} />;
}
