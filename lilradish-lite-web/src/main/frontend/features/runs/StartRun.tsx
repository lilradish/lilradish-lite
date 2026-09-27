import Box from "@mui/material/Box";
import Button from "@mui/material/Button";
import MenuItem from "@mui/material/MenuItem";
import Stack from "@mui/material/Stack";
import TextField from "@mui/material/TextField";
import Typography from "@mui/material/Typography";
import { useCallback, useRef, useState, type RefObject } from "react";

import type { FillReason } from "../../api/filling";
import {
  readOffered,
  type OfferedVersion,
  type OfferedWorkflow,
} from "../../api/groups/{groupId}/offered-workflows";
import {
  refusedAsNotOffered,
  refusedForTheName,
  startRun,
  type StartedRun,
} from "../../api/groups/{groupId}/runs";
import type { Problem } from "../../api/problem";
import { Async } from "../../app/Async";
import { ProblemView } from "../../app/ProblemView";
import { say } from "../../i18n/app";
import { readersIntl } from "../../i18n/intl";
import { ActButton } from "../../lib/action/ActButton";
import { ActDialog } from "../../lib/action/ActDialog";
import { Press, type Reason } from "../../lib/action/Press";
import { isolatedInText } from "../../lib/direction/isolated";
import { codePointsIn } from "../../lib/filling/writing";
import { ACTS_SX, QUIET_SX } from "../../lib/layout/parts";
import { Notice } from "../../lib/notice/Notice";
import { useAction, type Action } from "../../lib/request/useAction";
import { useResource } from "../../lib/request/useResource";
import {
  FillForm,
  type Drafted,
  type Left,
  type Unread,
} from "./fill/FillForm";
import { draftOf, suggestedName, type LevelDraft } from "./fill/fillDrafts";
import { useFillDraft } from "./fill/useFillDraft";
import { MOST_IN_A_RUN_NAME, runNameFits } from "./runName";

const START_RULE = { inGroup: "start_run" } as const;

const NONE_OFFERED: readonly OfferedWorkflow[] = [];

const FIELDS_SX = { pt: 1 };

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

/** The name as the reader left it for one workflow, which starts again whenever the workflow does. */
interface Naming {
  readonly entryId: string;
  /** None until the reader changes the name, which is suggested until then. */
  readonly typed: string | null;
  /** The name last refused, said to be so while it is the name. */
  readonly refused: string | null;
}

/** What a start was asked with, so what is answered is taken to what was sent and no later change. */
interface Sent {
  readonly entryId: string;
  readonly name: string;
}

/** One run as it is being started, whichever way the page is drawn. */
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
  /** Why it cannot start yet; absent where it can. */
  readonly held: Reason | undefined;
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
 * chosen only once asked for, a name already suggested, and what that version takes.
 */
export function StartHere({
  groupId,
  workflow,
  settled,
  onAbandon,
}: {
  readonly groupId: string;
  readonly workflow: OfferedWorkflow;
  readonly settled: StartSettled;
  readonly onAbandon: () => void;
}) {
  const start = useStartDraft(groupId, workflow, settled);
  return (
    <div>
      <StartFields start={start} workflow={workflow} versionAsked={false} />
      <Box sx={SAID_SX}>
        <StartSaid start={start} />
      </Box>
      <Box sx={ACTS_SX}>
        <ActButton action={start.action} act={start.act} reason={start.held}>
          {say("work.start")}
        </ActButton>
        <Press
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

/**
 * Starting a run in a dialog over the page: a workflow of those on offer, then its version with the newest
 * already picked, a name already suggested, and what that version takes. What is offered is read as it opens.
 */
export function StartDialog({
  groupId,
  onShut,
  settled,
}: {
  readonly groupId: string;
  readonly onShut: () => void;
  /** A version no longer offered is said, and what is offered read again, in the dialog itself. */
  readonly settled: Omit<StartSettled, "onNotOffered">;
}) {
  const load = useCallback(
    (signal: AbortSignal) => readOffered(groupId, signal),
    [groupId],
  );
  const offered = useResource(load, NONE_OFFERED);
  const [entryId, setEntryId] = useState("");
  const [readAgain, setReadAgain] = useState(false);
  const workflow = offered.value.find((each) => each.entryId === entryId);
  const start = useStartDraft(groupId, workflow, {
    ...settled,
    onNotOffered: () => {
      setReadAgain(true);
      offered.reload();
    },
  });

  return (
    <ActDialog
      open
      title={say("work.startARun")}
      onShut={onShut}
      action={start.action}
      act={start.act}
      actLabel={say("work.start")}
      reason={start.held}
      abandonLabel={say("work.abandon")}
      underway={say("work.startUnderway")}
      refusal={<StartSaid start={start} />}
    >
      <Stack spacing={2} sx={FIELDS_SX}>
        <div role="status">
          {readAgain ? (
            <Notice severity="warning">{say("work.noLongerOffered")}</Notice>
          ) : null}
        </div>
        <Async
          read={offered}
          rule={START_RULE}
          empty={(workflows) =>
            workflows.length === 0 ? say("work.nothingOffered") : null
          }
        >
          {(workflows) => (
            <TextField
              select
              label={say("run.workflow")}
              value={workflow === undefined ? "" : entryId}
              onChange={(edit) => {
                setEntryId(edit.target.value);
                setReadAgain(false);
              }}
              fullWidth
            >
              {workflows.map((each) => (
                <MenuItem key={each.entryId} value={each.entryId}>
                  {isolatedInText(each.name)}
                </MenuItem>
              ))}
            </TextField>
          )}
        </Async>
        {workflow === undefined ? null : (
          <StartFields start={start} workflow={workflow} versionAsked />
        )}
      </Stack>
    </ActDialog>
  );
}

/** The name, the version where it is asked for or offered to be, and a control to each field the version takes. */
function StartFields({
  start,
  workflow,
  versionAsked,
}: {
  readonly start: StartDraft;
  readonly workflow: OfferedWorkflow;
  readonly versionAsked: boolean;
}) {
  const [asked, setAsked] = useState(versionAsked);
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
          autoFocus={!versionAsked}
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
  workflow: OfferedWorkflow | undefined,
  settled: StartSettled,
): StartDraft {
  const newest = workflow?.versions[0];
  const entryId = workflow?.entryId ?? "";
  const nameRef = useRef<HTMLInputElement>(null);
  const sent = useRef<Sent | null>(null);
  const [drawnAt] = useState(() => new Date());
  const [refusedFor, setRefusedFor] = useState<string | null>(null);
  const [namingHeld, setNaming] = useState(() => namingFor(entryId));
  const [chosen, setChosen] = useState(newest?.versionId ?? "");
  const naming =
    namingHeld.entryId === entryId ? namingHeld : namingFor(entryId);
  if (naming !== namingHeld) {
    setNaming(naming);
  }
  if (refusedFor !== null && refusedFor !== entryId) {
    setRefusedFor(null);
  }
  const version =
    workflow?.versions.find((each) => each.versionId === chosen) ?? newest;
  const versionId = version?.versionId ?? "";
  // Another workflow, or the version gone from what was read again: start again on the newest.
  if (versionId !== chosen) {
    setChosen(versionId);
  }
  const takes = version?.takes ?? [];
  const draft = useFillDraft(takes, versionId, () => draftOf(takes));
  const name =
    naming.typed ??
    suggestedName(takes, draft.fill) ??
    (workflow === undefined ? "" : nameOfTheMoment(workflow.name, drawnAt));

  const action = useAction(settled.onStarted, (problem) => {
    const asked = sent.current;
    settled.onProblem(problem);
    setRefusedFor(asked?.entryId ?? null);
    // Of the render the answer lands in, so a workflow picked since is not the one it was said of.
    if (asked?.entryId !== entryId) {
      return;
    }
    draft.refusedWith(problem);
    if (refusedForTheName(problem)) {
      setNaming((prior) => ({ ...prior, refused: asked?.name ?? null }));
      nameRef.current?.focus();
    }
    if (refusedAsNotOffered(problem)) {
      settled.onNotOffered();
    }
  });

  const refused =
    action.problem !== null && refusedFor === entryId ? action.problem : null;
  const act = (signal: AbortSignal) => {
    sent.current = { entryId, name };
    draft.sending();
    return startRun(groupId, { name, versionId, values: draft.values }, signal);
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
    held:
      workflow === undefined
        ? { severity: "info", words: say("work.pickWorkflow") }
        : undefined,
    unfit: workflow === undefined ? undefined : draft.unfit,
    refusal: draft.saying(refused),
    unmarked: draft.unmarked,
    act,
    action: { ...action, run },
  };
}

function namingFor(entryId: string): Naming {
  return { entryId, typed: null, refused: null };
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
