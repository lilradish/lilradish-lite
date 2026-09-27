import Box from "@mui/material/Box";
import Stack from "@mui/material/Stack";
import TextField from "@mui/material/TextField";
import Typography from "@mui/material/Typography";
import { useId, useState } from "react";

import { createGroup } from "../../api/groups";
import { searchPool, type PersonInPool } from "../../api/pool/search";
import type { Problem } from "../../api/problem";
import { ProblemView } from "../../app/ProblemView";
import { say } from "../../i18n/app";
import { ActDialog } from "../../lib/action/ActDialog";
import { Press, type Reason } from "../../lib/action/Press";
import type { Action } from "../../lib/request/useAction";
import { FindOne } from "../../lib/search/FindOne";
import { personTitle } from "../people/personTitle";
import type { Outcome } from "./changes";
import { nameFits } from "../../lib/text/legibility";
import { keyFits } from "./typedLimits";

const FIELDS_SX = { pt: 1 };

// A name may carry a bidirectional control, so its box takes its direction from it.
const NAME_INPUT = { htmlInput: { dir: "auto" } };

/**
 * The control that creates a group, and the dialog it opens: a name, a key,
 * and who in the pool may change its membership. The pool is not this page's
 * subject, so it is searched and never listed: nobody is offered until
 * something is typed.
 *
 * Creating runs under the action handed in, which its holder owns and settles,
 * so it outlives this dialog; while it is out, the control opens nothing.
 */
export function CreateAGroup({
  changing,
}: {
  readonly changing: Action<Outcome>;
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
        {say("group.create")}
      </Press>
      <Creating
        key={opening}
        open={open}
        changing={changing}
        onShut={() => setOpen(false)}
      />
    </>
  );
}

/** Each limit is checked where it is typed, and again by the server, whose check is the one that holds. */
function Creating({
  open,
  changing,
  onShut,
}: {
  readonly open: boolean;
  readonly changing: Action<Outcome>;
  readonly onShut: () => void;
}) {
  const founderId = useId();
  const [name, setName] = useState("");
  const [key, setKey] = useState("");
  const [picked, setPicked] = useState<PersonInPool | null>(null);
  // Only a refusal asked from this opening is this opening's to say.
  const [asked, setAsked] = useState(false);
  const nameHolds = nameFits(name);
  const keyHolds = keyFits(key);

  return (
    <ActDialog
      open={open}
      title={say("group.create")}
      onShut={onShut}
      action={changing}
      act={(signal) => {
        // Pressable only once somebody is picked, which is what `reason` says.
        const founder = picked!;
        setAsked(true);
        return createGroup(
          { name, key, subjectId: founder.subjectId },
          signal,
        ).then((created) => {
          onShut();
          return { created };
        });
      }}
      actLabel={say("group.make")}
      reason={heldFor(nameHolds, keyHolds, picked)}
      abandonLabel={say("group.abandon")}
      underway={say("group.underway")}
      refusal={
        !asked || changing.problem === null ? null : (
          <ProblemView
            problem={changing.problem}
            rule={{ act: "keep_group_register" }}
          />
        )
      }
    >
      <Stack spacing={2} sx={FIELDS_SX}>
        <TextField
          label={say("group.name")}
          value={name}
          onChange={(edit) => setName(edit.target.value)}
          error={name !== "" && !nameHolds}
          helperText={
            name !== "" && !nameHolds ? say("group.nameLimit") : undefined
          }
          slotProps={NAME_INPUT}
          autoFocus
          fullWidth
        />
        <TextField
          label={say("group.key")}
          value={key}
          onChange={(edit) => setKey(edit.target.value)}
          error={key !== "" && !keyHolds}
          helperText={
            key !== "" && !keyHolds
              ? say("group.keyLimit")
              : say("group.keyHint")
          }
          fullWidth
        />
        <Box role="group" aria-labelledby={founderId}>
          <Typography id={founderId} variant="subtitle2" component="p">
            {say("group.founder")}
          </Typography>
          <FindOne
            label={say("person.numberOrName")}
            search={searchPool}
            keyOf={subjectIdOf}
            title={personTitle}
            picked={picked}
            onPick={setPicked}
            empty={say("pool.noMatch")}
            refusal={refusalOf}
          />
        </Box>
      </Stack>
    </ActDialog>
  );
}

/** The first thing still missing, in the order the dialog asks for them. */
function heldFor(
  nameHolds: boolean,
  keyHolds: boolean,
  picked: PersonInPool | null,
): Reason | undefined {
  if (!nameHolds) {
    return { severity: "info", words: say("group.nameLimit") };
  }
  if (!keyHolds) {
    return { severity: "info", words: say("group.keyLimit") };
  }
  return picked === null
    ? { severity: "info", words: say("group.pickFirst") }
    : undefined;
}

function subjectIdOf(person: PersonInPool): string {
  return person.subjectId;
}

function refusalOf(problem: Problem) {
  return (
    <ProblemView problem={problem} rule={{ act: "keep_group_register" }} />
  );
}
