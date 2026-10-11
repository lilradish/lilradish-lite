import Box from "@mui/material/Box";
import Stack from "@mui/material/Stack";
import TextField from "@mui/material/TextField";
import Typography from "@mui/material/Typography";
import Dialog from "@mui/material/Dialog";
import DialogTitle from "@mui/material/DialogTitle";
import DialogContent from "@mui/material/DialogContent";
import DialogActions from "@mui/material/DialogActions";
import {
  useLayoutEffect,
  useRef,
  useState,
  type RefObject,
  type ReactNode,
} from "react";

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
import { ActGroup } from "../../lib/action/ActGroup";
import { Press } from "../../lib/action/Press";
import { codePointsIn } from "../../lib/filling/writing";
import { isolatedInText } from "../../lib/direction/isolated";
import { QUIET_SX } from "../../lib/layout/parts";
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
import { VersionPicker } from "../library/VersionPicker";
import { VersionLabel, versionLabelText } from "../../lib/text/VersionLabel";

const START_RULE = { inGroup: "start_run" } as const;

const SAID_SX = { mt: 2 };

// A name may carry a bidirectional control, so its box takes its direction from it.
const NAME_INPUT = { htmlInput: { dir: "auto" } };

export interface StartSettled {
  readonly onStarted: (started: StartedRun) => void;
  /** The version is no longer one to start, so what is offered is read again. */
  readonly onNotOffered: () => void;
  /** Every refusal, so one saying the reader's standing has moved can be acted on. */
  readonly onProblem: (problem: Problem) => void;
}

interface Naming {
  /** None until the reader changes the name, which is suggested until then. */
  readonly typed: string | null;
  /** The name last refused, said to be so while it is the name. */
  readonly refused: string | null;
}

const NOTHING_NAMED: Naming = { typed: null, refused: null };

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
  readonly unfit: string | undefined;
  /** The last refusal, while what it was said of is still what is asked. */
  readonly refusal: Problem | null;
  readonly unmarked: number;
  readonly act: (signal: AbortSignal) => Promise<StartedRun>;
  /** Asking with anything the page finds wrong sends nothing and takes the keyboard to the first of it. */
  readonly action: Action<StartedRun>;
}

/** Nothing here starts the name or what is filled again for another workflow, so it is drawn anew for each. */
export function StartHere({
  groupId,
  workflow,
  settled,
  nameRef,
  onAbandon,
  verification,
}: {
  readonly groupId: string;
  readonly workflow: OfferedWorkflow;
  readonly settled: StartSettled;
  readonly nameRef: RefObject<HTMLInputElement | null>;
  readonly onAbandon: () => void;
  readonly verification?: ReactNode;
}) {
  const [inputs, setInputs] = useState(false);
  const start = useStartDraft(groupId, workflow, settled, nameRef, () =>
    setInputs(false),
  );
  const hasInputs = (start.version?.takes.length ?? 0) > 0;
  const showingInputs = inputs && hasInputs;
  return (
    <Dialog
      open
      disableRestoreFocus
      fullWidth
      maxWidth="md"
      onClose={start.action.running ? undefined : onAbandon}
    >
      <DialogTitle>
        {say("work.startDialog", { workflow: isolatedInText(workflow.name) })}
      </DialogTitle>
      <DialogContent>
        <Typography variant="h2" sx={{ mb: 2 }}>
          {say(showingInputs ? "work.inputs" : "work.versionAndName")}
        </Typography>
        <StartFields
          groupId={groupId}
          start={start}
          workflow={workflow}
          inputs={showingInputs}
        />
        <Box sx={SAID_SX}>
          <StartSaid start={start} showFill={showingInputs || !hasInputs} />
          {verification}
        </Box>
      </DialogContent>
      <DialogActions>
        <ActGroup>
          {!showingInputs ? null : (
            <Press tier="neutral" fill onPress={() => setInputs(false)}>
              {say("work.back")}
            </Press>
          )}
          <Press
            tier="neutral"
            fill
            reason={
              start.action.running
                ? { severity: "warning", words: say("work.startUnderway") }
                : undefined
            }
            onPress={onAbandon}
          >
            {say("work.cancel")}
          </Press>
          {hasInputs && !showingInputs ? (
            <Press
              tier="primary"
              fill
              onPress={() => {
                if (runNameFits(start.name)) setInputs(true);
                else nameRef.current?.focus();
              }}
            >
              {say("work.next")}
            </Press>
          ) : (
            <ActButton
              tier="primary"
              fill
              action={start.action}
              act={start.act}
            >
              {say("work.startRun")}
            </ActButton>
          )}
        </ActGroup>
      </DialogActions>
    </Dialog>
  );
}

function StartFields({
  groupId,
  start,
  workflow,
  inputs,
}: {
  readonly groupId: string;
  readonly start: StartDraft;
  readonly workflow: OfferedWorkflow;
  readonly inputs: boolean;
}) {
  const [asked, setAsked] = useState(false);
  const inputFields = useRef<HTMLDivElement>(null);
  const { version, name } = start;
  const fits = runNameFits(name);
  const another = workflow.versions.length > 1;
  useLayoutEffect(() => {
    if (inputs)
      inputFields.current
        ?.querySelector<HTMLElement>(
          '[role="combobox"]:not([aria-disabled="true"]), input:not([type="hidden"]):not([aria-hidden="true"]):not([tabindex="-1"]):not(:disabled), textarea:not(:disabled), button:not(:disabled)',
        )
        ?.focus();
    else start.nameRef.current?.focus();
  }, [inputs, start.nameRef]);
  return (
    <>
      <Box hidden={inputs}>
        <Stack spacing={2}>
          <TextField
            label={say("work.nameThisRun")}
            value={name}
            onChange={(edit) => start.onName(edit.target.value)}
            inputRef={start.nameRef}
            autoFocus
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
            <VersionPicker
              groupId={groupId}
              kind="workflow"
              label={say("work.version")}
              value={version?.versionId ?? ""}
              onChange={start.onVersion}
              readOnly={start.action.running}
              choices={workflow.versions.map((each) => ({
                versionId: each.versionId,
                label: versionLabelText({
                  number: each.number,
                  name: each.versionName,
                  numberLabel: say("work.versionNumbered", {
                    number: each.number,
                  }),
                }),
                shown: (
                  <VersionLabel
                    number={each.number}
                    name={each.versionName}
                    numberLabel={say("work.versionNumbered", {
                      number: each.number,
                    })}
                  />
                ),
              }))}
              // Drawn in place of the control that asked for it, which takes the keyboard with it.
              autoFocus
            />
          ) : another ? (
            <div>
              <Press tier="secondary" onPress={() => setAsked(true)}>
                {say("fill.chooseVersion")}
              </Press>
            </div>
          ) : null}
          {version === undefined || version.takes.length !== 0 ? null : (
            <Typography variant="body2" sx={QUIET_SX}>
              {say("work.nothingTaken")}
            </Typography>
          )}
        </Stack>
      </Box>
      <Box ref={inputFields} hidden={!inputs}>
        {version === undefined ? null : (
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
      </Box>
    </>
  );
}

function StartSaid({
  start,
  showFill,
}: {
  readonly start: StartDraft;
  readonly showFill: boolean;
}) {
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
      {!showFill || unfit === undefined ? null : (
        <Notice severity="info">{unfit}</Notice>
      )}
    </>
  );
}

function useStartDraft(
  groupId: string,
  workflow: OfferedWorkflow,
  settled: StartSettled,
  nameRef: RefObject<HTMLInputElement | null>,
  onNameRefused: () => void,
): StartDraft {
  const newest = workflow.versions[0];
  const nameSent = useRef<string | null>(null);
  const [drawnAt] = useState(() => new Date());
  const [naming, setNaming] = useState(NOTHING_NAMED);
  const [chosen, setChosen] = useState(newest?.versionId ?? "");
  const version =
    workflow.versions.find((each) => each.versionId === chosen) ?? newest;
  const versionId = version?.versionId ?? "";
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
      onNameRefused();
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
