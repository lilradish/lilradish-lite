import Box from "@mui/material/Box";
import Button from "@mui/material/Button";
import MenuItem from "@mui/material/MenuItem";
import Stack from "@mui/material/Stack";
import TextField from "@mui/material/TextField";
import Typography from "@mui/material/Typography";
import { useRef, useState, type RefObject } from "react";

import type { FillReason } from "../../api/filling";
import type {
  OfferedVersion,
  OfferedWorkflow,
} from "../../api/groups/{groupId}/offered-workflows";
import {
  refusedAsNotOffered,
  refusedForTheName,
  startRun,
  type StartedRun,
} from "../../api/groups/{groupId}/runs";
import type { Problem } from "../../api/problem";
import { ProblemView } from "../../app/ProblemView";
import { say } from "../../i18n/app";
import { readersIntl } from "../../i18n/intl";
import { ActButton } from "../../lib/action/ActButton";
import { Press } from "../../lib/action/Press";
import { codePointsIn } from "../../lib/filling/writing";
import { ACTS_SX, QUIET_SX } from "../../lib/layout/parts";
import { Notice } from "../../lib/notice/Notice";
import { useAction, type Action } from "../../lib/request/useAction";
import {
  FillForm,
  type Drafted,
  type Left,
  type Unread,
} from "./fill/FillForm";
import { draftOf, type LevelDraft } from "./fill/fillDrafts";
import { useFillDraft } from "./fill/useFillDraft";
import { MOST_IN_A_RUN_NAME, runNameFits } from "./runName";

const START_RULE = { inGroup: "start_run" } as const;

const SAID_SX = { mt: 2 };

// A name may carry a bidirectional control, so its box takes its direction from it.
const NAME_INPUT = { htmlInput: { dir: "auto" } };

/** What was said of a start, each where the reader looks for it: a run begun, or refused. */
export interface StartSettled {
  readonly onStarted: (started: StartedRun) => void;
  /** The version is no longer one to start, so what is offered is read again. */
  readonly onNotOffered: () => void;
  /** Every refusal, so one saying the reader's standing has moved can be acted on. */
  readonly onProblem: (problem: Problem) => void;
}

/** The name as the reader left it. */
interface Naming {
  /** None until the reader changes the name, which is suggested until then. */
  readonly typed: string | null;
  /** The name last refused, said to be so while it is the name. */
  readonly refused: string | null;
}

const NOTHING_NAMED: Naming = { typed: null, refused: null };

/** One run as it is being started. */
interface StartDraft {
  readonly version: OfferedVersion | undefined;
  readonly onVersion: (versionId: string) => void;
  readonly name: string;
  readonly nameRef: RefObject<HTMLInputElement | null>;
  readonly onName: (typed: string) => void;
  readonly nameRefused: boolean;
  readonly formId: string;
  readonly fill: LevelDraft;
  readonly onFill: Drafted;
  readonly onLeave: Left;
  readonly onUnread: Unread;
  readonly marks: ReadonlyMap<string, FillReason>;
  /** What the page finds wrong in what is filled, which asking for a start takes the keyboard to. */
  readonly unfit: string | undefined;
  /** The last refusal, while what it was said of is still what is asked. */
  readonly refusal: Problem | null;
  readonly unmarked: number;
  readonly act: (signal: AbortSignal) => Promise<StartedRun>;
  /** Asking with anything the page finds wrong sends nothing and takes the keyboard to the first of it. */
  readonly action: Action<StartedRun>;
}

/**
 * Starting a run beneath the workflow picked from the cards: the newest version in service, which another is
 * chosen only once asked for, a name already suggested, and what that version takes. Nothing here starts the
 * name or what is filled again for another workflow, so it is drawn anew for each.
 */
export function StartHere({
  groupId,
  workflow,
  settled,
  nameRef,
  onAbandon,
}: {
  readonly groupId: string;
  readonly workflow: OfferedWorkflow;
  readonly settled: StartSettled;
  readonly nameRef: RefObject<HTMLInputElement | null>;
  readonly onAbandon: () => void;
}) {
  const start = useStartDraft(groupId, workflow, settled, nameRef);
  return (
    <div>
      <StartFields start={start} workflow={workflow} />
      <Box sx={SAID_SX}>
        <StartSaid start={start} />
      </Box>
      <Box sx={ACTS_SX}>
        <ActButton variant="contained" action={start.action} act={start.act}>
          {say("work.start")}
        </ActButton>
        <Press
          variant="neutral"
          reason={
            start.action.running
              ? { severity: "warning", words: say("work.startUnderway") }
              : undefined
          }
          onPress={onAbandon}
        >
          {say("work.abandon")}
        </Press>
      </Box>
    </div>
  );
}

/** The name, the version where it is asked for or offered to be, and a control to each field the version takes. */
function StartFields({
  start,
  workflow,
}: {
  readonly start: StartDraft;
  readonly workflow: OfferedWorkflow;
}) {
  const [asked, setAsked] = useState(false);
  const { version, name } = start;
  const fits = runNameFits(name);
  const another = workflow.versions.length > 1;
  return (
    <Stack spacing={2}>
      <TextField
        label={say("work.nameThisRun")}
        value={name}
        onChange={(edit) => start.onName(edit.target.value)}
        inputRef={start.nameRef}
        error={!fits || start.nameRefused}
        helperText={
          start.nameRefused
            ? say("refusal.RUN_NAME_UNUSABLE")
            : fits
              ? undefined
              : say("runName.limit")
        }
        slotProps={NAME_INPUT}
        required
        fullWidth
      />
      {asked ? (
        <TextField
          select
          label={say("work.version")}
          value={version?.versionId ?? ""}
          onChange={(edit) => start.onVersion(edit.target.value)}
          // Drawn in place of the control that asked for it, which takes the keyboard with it.
          autoFocus
        >
          {workflow.versions.map((each) => (
            <MenuItem key={each.versionId} value={each.versionId}>
              {say("work.versionNumbered", { number: each.number })}
            </MenuItem>
          ))}
        </TextField>
      ) : another ? (
        <div>
          <Button size="small" onClick={() => setAsked(true)}>
            {say("fill.chooseVersion")}
          </Button>
        </div>
      ) : null}
      {version === undefined ? null : version.takes.length === 0 ? (
        <Typography variant="body2" sx={QUIET_SX}>
          {say("work.nothingTaken")}
        </Typography>
      ) : (
        <FillForm
          key={version.versionId}
          formId={start.formId}
          fields={version.takes}
          draft={start.fill}
          onDraft={start.onFill}
          onLeave={start.onLeave}
          onUnread={start.onUnread}
          marks={start.marks}
        />
      )}
    </Stack>
  );
}

/**
 * What is said beside Start: a refusal where nothing else says it, as a name refused is said on the name and a
 * version gone by the page, with how many places it found and marked nowhere; and what the page finds wrong.
 */
function StartSaid({ start }: { readonly start: StartDraft }) {
  const { refusal, unmarked, unfit } = start;
  return (
    <>
      {refusal === null ||
      refusedForTheName(refusal) ||
      refusedAsNotOffered(refusal) ? null : (
        <ProblemView problem={refusal} rule={START_RULE} />
      )}
      <div role="status">
        {unmarked > 0 ? (
          <Notice severity="info">
            {say("fill.moreProblems", { count: unmarked })}
          </Notice>
        ) : null}
      </div>
      {unfit === undefined ? null : <Notice severity="info">{unfit}</Notice>}
    </>
  );
}

function useStartDraft(
  groupId: string,
  workflow: OfferedWorkflow,
  settled: StartSettled,
  nameRef: RefObject<HTMLInputElement | null>,
): StartDraft {
  const newest = workflow.versions[0];
  const nameSent = useRef<string | null>(null);
  const [drawnAt] = useState(() => new Date());
  const [naming, setNaming] = useState(NOTHING_NAMED);
  const [chosen, setChosen] = useState(newest?.versionId ?? "");
  const version =
    workflow.versions.find((each) => each.versionId === chosen) ?? newest;
  const versionId = version?.versionId ?? "";
  // The version gone from what was read again: start again on the newest.
  if (versionId !== chosen) {
    setChosen(versionId);
  }
  const takes = version?.takes ?? [];
  const draft = useFillDraft(takes, versionId, () => draftOf(takes), false);
  const name = naming.typed ?? nameOfTheMoment(workflow.name, drawnAt);

  const action = useAction(settled.onStarted, (problem) => {
    settled.onProblem(problem);
    draft.refusedWith(problem);
    if (refusedForTheName(problem)) {
      setNaming((prior) => ({ ...prior, refused: nameSent.current }));
      nameRef.current?.focus();
    }
    if (refusedAsNotOffered(problem)) {
      settled.onNotOffered();
    }
  });

  const act = (signal: AbortSignal) => {
    nameSent.current = name;
    draft.sending();
    return startRun(
      groupId,
      {
        name,
        versionId,
        values: draft.values,
        uploads: draft.uploads,
        hasFilePositions: draft.hasFilePositions,
      },
      signal,
    );
  };
  const run = (asked: (signal: AbortSignal) => Promise<StartedRun>) => {
    const first = draft.firstUnfit;
    if (runNameFits(name) && first === undefined) {
      action.run(asked);
      return;
    }
    if (runNameFits(name)) {
      draft.holdBack(first);
    } else {
      draft.holdBack(undefined);
      nameRef.current?.focus();
    }
  };

  return {
    version,
    onVersion: setChosen,
    name,
    nameRef,
    onName: (typed) => setNaming((prior) => ({ ...prior, typed })),
    nameRefused: naming.refused === name,
    formId: draft.formId,
    fill: draft.fill,
    onFill: draft.onFill,
    onLeave: draft.onLeave,
    onUnread: draft.onUnread,
    marks: draft.marks,
    unfit: draft.unfit,
    refusal: draft.saying(action.problem),
    unmarked: draft.unmarked,
    act,
    action: { ...action, run },
  };
}

/** The workflow's name and when the form was drawn, the name cut short where the two would not fit in a name. */
function nameOfTheMoment(workflow: string, at: Date): string {
  const time = readersIntl.formatDate(at, {
    day: "numeric",
    month: "short",
    hour: "2-digit",
    minute: "2-digit",
  });
  const whole = say("work.nameSuggested", { workflow, time });
  const over = codePointsIn(whole) - MOST_IN_A_RUN_NAME;
  if (over <= 0) {
    return whole;
  }
  const kept = [...workflow].slice(
    0,
    Math.max(0, codePointsIn(workflow) - over),
  );
  return say("work.nameSuggested", { workflow: kept.join(""), time });
}
