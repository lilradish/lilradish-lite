import Box from "@mui/material/Box";
import Button from "@mui/material/Button";
import Link from "@mui/material/Link";
import Typography from "@mui/material/Typography";
import { useCallback, useId, useLayoutEffect, useRef, useState } from "react";
import { Link as RouterLink, useLocation } from "react-router";

import type { ReadField } from "../../../api/filling";
import type {
  RunSteps,
  StepRow,
} from "../../../api/groups/{groupId}/runs/{runId}/steps";
import {
  readStep,
  type StepAnswer,
  type TriedValue,
} from "../../../api/groups/{groupId}/runs/{runId}/steps/{stepId}";
import { Async } from "../../../app/Async";
import { say } from "../../../i18n/app";
import { isolatedInText } from "../../../lib/direction/isolated";
import { Field, Fields } from "../../../lib/form/Fields";
import { ACTS_SX, QUIET_SX } from "../../../lib/layout/parts";
import { whenText } from "../../../lib/time/When";
import { FilledValue } from "../fill/FilledValue";
import { listQuery, stepAnchor, stepHref, WITHIN_THE_RUN } from "../runAddress";
import { AnswerHere } from "./AnswerHere";
import { AskAgainButton } from "./AskAgainButton";
import { declaredFor, fieldAt, labelAt, takenSaid } from "./declared";
import { Message } from "./Message";
import { ReviewControls, waitingOnReader } from "./ReviewControls";
import {
  decisionSaid,
  endedSaid,
  misfitSaid,
  standingSaid,
  whereSaid,
  withheldSaid,
} from "./stepStates";
import { stepTitle } from "./stepTitle";
import { TryPanel } from "./TryPanel";
import { TrySendingButton } from "./TrySendingButton";
import { heldTry, Turnaways } from "./Turnaways";
import { useKeptUp } from "./useRunRead";

const TAKEN_SX = { m: 0, pl: 2 };

/**
 * Opening Details reads the step's own page, kept up as often as the run's steps are while it is open; turning to an
 * earlier try reads it once. Where a value waits on the reader's review, what went in and what came out are drawn
 * in full with it, and the step a review is answered with is handed to `onShown`, what is open read again with it.
 */
export function StepMessage({
  groupId,
  reading,
  step,
  onShown,
}: {
  readonly groupId: string;
  readonly reading: RunSteps;
  readonly step: StepRow;
  readonly onShown: (answer: StepAnswer) => void;
}) {
  const [open, setOpen] = useState(false);
  const [turnedTo, setTurnedTo] = useState<number | null>(null);
  const [askOut, setAskOut] = useState(false);
  const [answerOut, setAnswerOut] = useState(false);
  const titleId = useId();
  const detailsId = useId();
  const earlierControl = useRef<HTMLButtonElement>(null);
  const laterControl = useRef<HTMLButtonElement>(null);
  const focusOnceTurned = useRef<HTMLButtonElement | null>(null);
  const read = useStepRead(
    groupId,
    reading.run.runId,
    step.stepId,
    open || turnedTo !== null,
    open ? reading.rereadAfterSeconds : undefined,
  );
  const gives = declaredFor(step, reading.declarations)?.gives;
  const waiting = waitingOnReader(step);
  const [state, ...why] = whereSaid(step, gives);
  // Held on a stop alone, it is held since the stop, which the stop's own line says; any other since is its own.
  const since =
    step.where?.reason === "entry_stopped" && step.where.stopped !== undefined
      ? undefined
      : step.where?.since;
  const tries = step.tries;
  const shownNumber = turnedTo ?? tries?.current;

  // A control turned to its bound is disabled under the keyboard, which would drop to the page: the other takes it.
  useLayoutEffect(() => {
    focusOnceTurned.current?.focus();
    focusOnceTurned.current = null;
  }, [turnedTo]);

  const turning = (
    to: number | null,
    bound: boolean,
    other: HTMLButtonElement | null,
  ) => {
    focusOnceTurned.current = bound ? other : null;
    setTurnedTo(to);
  };

  const declaring = {
    declarations: reading.declarations,
    runVersionId: reading.run.versionId,
  };
  const shown = (answer: StepAnswer) => {
    if (open || turnedTo !== null) {
      read.quietly();
    }
    onShown(answer);
  };

  // One read, so one `Async`: with a try turned to, the try's own says how the read went, and Details only draws it.
  const details = (answer: StepAnswer | null) =>
    answer === null ? null : (
      <Details
        groupId={groupId}
        answer={answer}
        reading={reading}
        step={step}
        turnedTo={turnedTo}
        gives={gives}
      />
    );

  return (
    <Message
      anchor={stepAnchor(step.stepId)}
      title={isolatedInText(stepTitle(step))}
      titleId={titleId}
    >
      <Typography variant="body1">{state}</Typography>
      {why.map((line, at) => (
        <Typography key={at} variant="body2" sx={QUIET_SX}>
          {line}
        </Typography>
      ))}
      {since === undefined ? null : (
        <Typography variant="body2" sx={QUIET_SX}>
          {say("step.since", { when: whenText(since) })}
        </Typography>
      )}
      {step.where?.turnedAway === undefined ? null : (
        <Turnaways turnedAway={step.where.turnedAway} />
      )}
      {tries === undefined || shownNumber === undefined ? null : (
        <Typography variant="body2">
          {say(
            (turnedTo === null ? tries.beyond : turnedTo > tries.declared)
              ? "step.tryBeyond"
              : "step.tryOf",
            { number: shownNumber, allowed: tries.declared },
          )}
        </Typography>
      )}
      <TrySendingButton
        groupId={groupId}
        runId={reading.run.runId}
        step={step}
        onShown={shown}
      />
      <AskAgainButton
        groupId={groupId}
        runId={reading.run.runId}
        step={step}
        held={answerOut}
        onRunning={setAskOut}
        landsOn={stepAnchor(step.stepId)}
        onShown={shown}
      />
      <AnswerHere
        groupId={groupId}
        runId={reading.run.runId}
        step={step}
        inHand={read.value}
        held={askOut}
        onRunning={setAnswerOut}
        declaring={declaring}
        wentInDrawn={false}
        heading="h4"
        landsOn={stepAnchor(step.stepId)}
        onShown={shown}
      />
      {turnedTo === null ? (
        <GivenValues
          values={(step.gaveBack ?? []).filter(
            ({ field }) => !waiting.includes(field),
          )}
          gives={gives}
          shut={waiting.length === 0}
        />
      ) : (
        <Async read={read} empty={() => null}>
          {(answer) => {
            const turned = answer?.triesMade.find(
              (each) => each.number === turnedTo,
            );
            if (turned === undefined) {
              return null;
            }
            return turned.values.length === 0 ? (
              <>
                <Typography variant="body2">
                  {endedSaid(turned.ended)}
                </Typography>
                {turned.didNotFit === undefined ? null : (
                  <Typography variant="body2" sx={QUIET_SX}>
                    {misfitSaid(turned.didNotFit)}
                  </Typography>
                )}
              </>
            ) : (
              <GivenValues values={turned.values} gives={gives} shut={true} />
            );
          }}
        </Async>
      )}
      <ReviewControls
        groupId={groupId}
        runId={reading.run.runId}
        step={step}
        declaring={declaring}
        heading="h4"
        landsOn={stepAnchor(step.stepId)}
        onShown={shown}
      />
      {withheldSaid(step).map((line) => (
        <Typography key={line} variant="body2">
          {line}
        </Typography>
      ))}
      <Box sx={ACTS_SX}>
        {tries === undefined || tries.current <= 1 ? null : (
          <>
            <Button
              ref={earlierControl}
              size="small"
              aria-describedby={titleId}
              disabled={shownNumber === undefined || shownNumber <= 1}
              onClick={() => {
                const to = (shownNumber ?? 1) - 1;
                turning(to, to <= 1, laterControl.current);
              }}
            >
              {say("step.earlierTry")}
            </Button>
            <Button
              ref={laterControl}
              size="small"
              aria-describedby={titleId}
              disabled={turnedTo === null}
              onClick={() => {
                const to =
                  turnedTo === null || turnedTo + 1 >= tries.current
                    ? null
                    : turnedTo + 1;
                turning(to, to === null, earlierControl.current);
              }}
            >
              {say("step.laterTry")}
            </Button>
          </>
        )}
        <Button
          size="small"
          aria-expanded={open}
          aria-controls={detailsId}
          aria-describedby={titleId}
          onClick={() => setOpen((was) => !was)}
        >
          {say("step.details")}
        </Button>
      </Box>
      <div id={detailsId} hidden={!open}>
        {!open ? null : turnedTo === null ? (
          <Async read={read} empty={() => null}>
            {details}
          </Async>
        ) : read.problem === null ? (
          details(read.value)
        ) : null}
      </div>
    </Message>
  );
}

/** The step's own page, read only while `needed`, and kept up for as long as `keptUpFor` says. */
function useStepRead(
  groupId: string,
  runId: string,
  stepId: string,
  needed: boolean,
  keptUpFor: number | undefined,
) {
  const load = useCallback(
    (signal: AbortSignal): Promise<StepAnswer | null> =>
      needed ? readStep(groupId, runId, stepId, signal) : Promise.resolve(null),
    [groupId, runId, stepId, needed],
  );
  const waitOf = useCallback(() => keptUpFor, [keptUpFor]);
  return useKeptUp(load, waitOf);
}

/**
 * Each value a try gave back under its label, where it stands, and what its review said of it, if it said; one
 * refused for its length says so whatever its review said.
 */
function GivenValues({
  values,
  gives,
  shut,
}: {
  readonly values: readonly TriedValue[];
  readonly gives: readonly ReadField[] | undefined;
  readonly shut: boolean;
}) {
  return values.length === 0 ? null : (
    <Fields>
      {values.map((each) => (
        <Field key={each.field} label={labelAt(gives, each.field)}>
          <FilledValue
            field={fieldAt(gives, each.field)}
            label={labelAt(gives, each.field)}
            shown={each}
            shut={shut}
          />
          <Typography variant="body2" sx={QUIET_SX}>
            {standingSaid(each.now)}
          </Typography>
          {each.decision === undefined ||
          each.now === "refused_for_length" ? null : (
            <Typography variant="body2" sx={QUIET_SX}>
              {decisionSaid(each.decision)}
            </Typography>
          )}
        </Field>
      ))}
    </Fields>
  );
}

/** The try drawn, as its own page draws one; where each input comes from; and the way to that page. */
function Details({
  groupId,
  answer,
  reading,
  step,
  turnedTo,
  gives,
}: {
  readonly groupId: string;
  readonly answer: StepAnswer;
  readonly reading: RunSteps;
  readonly step: StepRow;
  readonly turnedTo: number | null;
  readonly gives: readonly ReadField[] | undefined;
}) {
  const { search } = useLocation();
  const tried = answer.triesMade;
  const shown =
    turnedTo === null
      ? tried.at(-1)
      : tried.find((each) => each.number === turnedTo);
  const declaring = {
    declarations: reading.declarations,
    runVersionId: reading.run.versionId,
  };
  return (
    <>
      {shown === undefined ? (
        <Typography variant="body2">{say("step.noTries")}</Typography>
      ) : (
        <TryPanel
          aTry={shown}
          field={undefined}
          gives={gives}
          turnawaysWithHold={shown.number === heldTry(answer.step, tried)}
        />
      )}
      <Fields>
        <Field label={say("step.takesFrom")}>
          {step.takesFrom.length === 0 ? (
            say("step.takesNothing")
          ) : (
            <Box component="ul" sx={TAKEN_SX}>
              {step.takesFrom.map((taken) => (
                <li key={taken.input}>{takenSaid(taken, step, declaring)}</li>
              ))}
            </Box>
          )}
        </Field>
      </Fields>
      <Link
        component={RouterLink}
        to={`${stepHref(groupId, reading.run.runId, step.stepId)}${listQuery(search)}`}
        state={WITHIN_THE_RUN}
      >
        {say("step.openStep")}
      </Link>
    </>
  );
}
