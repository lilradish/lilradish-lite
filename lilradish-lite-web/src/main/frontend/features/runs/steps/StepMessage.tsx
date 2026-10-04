import Box from "@mui/material/Box";
import Button from "@mui/material/Button";
import Link from "@mui/material/Link";
import Typography from "@mui/material/Typography";
import {
  useCallback,
  useEffect,
  useId,
  useLayoutEffect,
  useRef,
  useState,
} from "react";
import { Link as RouterLink, useLocation } from "react-router";

import type { ReadField } from "../../../api/filling";
import type {
  Declared,
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
import { Disclosure } from "../../../lib/action/Disclosure";
import { Field, Fields } from "../../../lib/form/Fields";
import {
  HEADER_ACTS_SX,
  QUIET_SX,
  SPACED_ABOVE_SX,
} from "../../../lib/layout/parts";
import { useCalendarNow, whenText } from "../../../lib/time/When";
import { FilledValue } from "../fill/FilledValue";
import { useRunReadAccess } from "../RunReadAccess";
import { listQuery, stepAnchor, stepHref, WITHIN_THE_RUN } from "../runAddress";
import { AnswerHere } from "./AnswerHere";
import { BeneathLinks } from "./BeneathLinks";
import { AskAgainButton } from "./AskAgainButton";
import { DecisionDrawn } from "./DecisionDrawn";
import {
  declaredFor,
  fieldAt,
  labelAt,
  takenSaid,
  type Declaring,
} from "./declared";
import { Message } from "./Message";
import {
  ReviewControls,
  searchWaitingOnReader,
  waitingOnReader,
} from "./ReviewControls";
import { SearchReviewTry, SearchText } from "./SearchRecord";
import {
  tryBeyondVisible,
  tryEndedVisible,
  erroredForVisible,
  misfitSaid,
  standingSaid,
  whereSaid,
  withheldSaid,
} from "./stepStates";
import { StepStateBadge } from "./StepStateBadge";
import { stepTitle } from "./stepTitle";
import { DeclinedDrawn, TryPanel, UnreadableResponseDrawn } from "./TryPanel";
import { TrySendingButton } from "./TrySendingButton";
import { Turnaways } from "./Turnaways";
import { useKeptUp } from "./useRunRead";

const TAKEN_SX = { m: 0, pl: 2 };

const NO_WAIT = () => undefined;

// The step summary has no revision, so an open detail compares its content after each parent read.
export function StepMessage({
  groupId,
  reading,
  step,
  handTries,
  onShown,
}: {
  readonly groupId: string;
  readonly reading: RunSteps;
  readonly step: StepRow;
  readonly handTries?: ReadonlySet<number>;
  readonly onShown: (answer: StepAnswer) => void;
}) {
  const now = useCalendarNow();
  const { search } = useLocation();
  const [open, setOpen] = useState(false);
  const [accountOpen, setAccountOpen] = useState(false);
  const [turnedTo, setTurnedTo] = useState<number | null>(null);
  const [askOut, setAskOut] = useState(false);
  const [answerOut, setAnswerOut] = useState(false);
  const titleId = useId();
  const detailsId = useId();
  const accountId = useId();
  const earlierControl = useRef<HTMLButtonElement>(null);
  const laterControl = useRef<HTMLButtonElement>(null);
  const focusOnceTurned = useRef<HTMLButtonElement | null>(null);
  const keyboardOnAccount = useRef(false);
  const lastStepSnapshot = useRef<string | null>(null);
  const lastReading = useRef<RunSteps | null>(null);
  const tries = step.tries;
  const machineTries = Array.from(
    { length: tries?.current ?? 0 },
    (_, index) => index + 1,
  ).filter((number) => !handTries?.has(number));
  const shownNumber = turnedTo ?? machineTries.at(-1);
  const showingEarlier =
    shownNumber !== undefined && shownNumber !== tries?.current;
  const currentIsHand =
    tries !== undefined && handTries?.has(tries.current) === true;
  const waiting = waitingOnReader(step);
  const heldSearch =
    step.runs.kind === "search" &&
    step.where?.kind === "held_back" &&
    (step.where.reason === "too_long" || step.where.reason === "turned_away");
  const needed =
    open ||
    showingEarlier ||
    (step.runs.kind === "search" && waiting.length > 0) ||
    (heldSearch && accountOpen);
  const read = useStepRead(groupId, reading.run.runId, step.stepId, needed);
  const stepSnapshot = needed ? JSON.stringify(step) : null;
  const { quietly, reload, loading, problem } = read;
  useEffect(() => {
    if (stepSnapshot !== null && lastStepSnapshot.current !== null) {
      if (lastStepSnapshot.current !== stepSnapshot) {
        if (loading) {
          reload();
        } else {
          quietly();
        }
      } else if (lastReading.current !== reading && problem !== null) {
        reload();
      }
    }
    lastStepSnapshot.current = stepSnapshot;
    lastReading.current = stepSnapshot === null ? null : reading;
  }, [stepSnapshot, reading, loading, problem, quietly, reload]);
  const declared = declaredFor(step, reading.declarations);
  const gives = declared?.gives;
  const [, ...why] = whereSaid(step, gives, groupId, now, search);
  // Held on a stop alone, it is held since the stop, which the stop's own line says; any other since is its own.
  const since =
    step.where?.reason === "entry_stopped" && step.where.stopped !== undefined
      ? undefined
      : step.where?.since;
  const triedSaid =
    tries === undefined || shownNumber === undefined || shownNumber < 1
      ? null
      : say(shownNumber > tries.declared ? "step.tryBeyond" : "step.tryOf", {
          number: shownNumber,
          allowed: tries.declared,
        });

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
    runId: reading.run.runId,
    declarations: reading.declarations,
    runVersionId: reading.run.versionId,
    steps: reading.steps,
    step,
  };
  const account = [
    ...(triedSaid === null
      ? []
      : [
          <Typography key="tries" variant="body2">
            {shownNumber !== undefined &&
            tries !== undefined &&
            shownNumber > tries.declared
              ? tryBeyondVisible(shownNumber, tries.declared)
              : triedSaid}
          </Typography>,
        ]),
    ...why.map((line, at) => (
      <Typography key={`why-${at}`} variant="body2" sx={QUIET_SX}>
        {line}
      </Typography>
    )),
    ...(since === undefined
      ? []
      : [
          <Typography key="since" variant="body2" sx={QUIET_SX}>
            {say("step.since", { when: whenText(since, now) })}
          </Typography>,
        ]),
    ...(step.where?.turnedAway === undefined
      ? []
      : [<Turnaways key="turned" turnedAway={step.where.turnedAway} />]),
    ...withheldSaid(step, declaring).map((line) => (
      <Typography key={`withheld-${line}`} variant="body2">
        {line}
      </Typography>
    )),
  ];
  const heldWhere =
    heldSearch &&
    read.value?.step.stepId === step.stepId &&
    read.value.step.where?.kind === "held_back" &&
    read.value.step.where.reason === step.where?.reason &&
    read.value.step.where.since === step.where?.since &&
    read.value.step.next?.number === step.next?.number &&
    read.value.step.tries?.current === step.tries?.current
      ? read.value.step.where
      : undefined;
  const heldQuestion = heldWhere?.searchQuestion;
  if (heldWhere?.searchQuestionWithheld === true) {
    account.push(
      <Typography key="search-withheld" variant="body2">
        {say("step.searchWithheld")}
      </Typography>,
    );
  } else if (heldQuestion !== undefined) {
    account.push(
      <Fields key="search-question">
        <Field
          label={say(
            step.where?.reason === "too_long"
              ? "step.heldSearchQuestionUnsent"
              : "step.heldSearchQuestionTurnedAway",
          )}
        >
          <SearchText text={heldQuestion} />
        </Field>
      </Fields>,
    );
  }
  const accounted = account.length > 0;
  if (!accounted && accountOpen) {
    setAccountOpen(false);
  }

  // A read again may leave nothing to account for, taking the control under the keyboard: the message takes it.
  useLayoutEffect(() => {
    if (accounted || !keyboardOnAccount.current) {
      return;
    }
    keyboardOnAccount.current = false;
    if (document.activeElement === document.body) {
      document.getElementById(stepAnchor(step.stepId))?.focus();
    }
  }, [accounted, step.stepId]);
  const shown = (answer: StepAnswer) => {
    if (needed) {
      if (read.loading) {
        read.reload();
      } else {
        read.quietly();
      }
    }
    if (needed) {
      lastStepSnapshot.current = JSON.stringify(answer.step);
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
        shownNumber={shownNumber}
        declared={declared}
        declaring={declaring}
      />
    );

  return (
    <SearchReviewTry.Provider
      value={searchWaitingOnReader(step, read.value)?.number ?? null}
    >
      <Message
        anchor={stepAnchor(step.stepId)}
        title={isolatedInText(stepTitle(step))}
        titleId={titleId}
        kind={step.runs.kind}
        cost={step.cost}
      >
        <Typography variant="body1">
          {accounted ? (
            <Disclosure
              open={accountOpen}
              controls={accountId}
              describedBy={titleId}
              onFocus={() => {
                keyboardOnAccount.current = true;
              }}
              onBlur={() => {
                // Taking the focused control away fires no blur, so this still says the keyboard was on it.
                keyboardOnAccount.current = false;
              }}
              onToggle={() => setAccountOpen((was) => !was)}
            >
              <StepStateBadge state={step.state} />
            </Disclosure>
          ) : (
            <StepStateBadge state={step.state} />
          )}
        </Typography>
        {!accounted ? null : (
          <div id={accountId} hidden={!accountOpen}>
            {accountOpen ? account : null}
          </div>
        )}
        {step.beneath === undefined ? null : (
          <BeneathLinks groupId={groupId} beneath={step.beneath} />
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
          heading="h3"
          landsOn={stepAnchor(step.stepId)}
          onShown={shown}
        />
        {!showingEarlier ? (
          currentIsHand ? null : (
            <GivenValues
              values={(step.gaveBack ?? []).filter(
                ({ field }) => !waiting.includes(field),
              )}
              gives={gives}
              shut={waiting.length === 0}
            />
          )
        ) : (
          <Async read={read} empty={() => null}>
            {(answer) => {
              const turned = answer?.triesMade.find(
                (each) => each.number === shownNumber,
              );
              if (turned === undefined) {
                return null;
              }
              return (
                <>
                  {turned.values.length === 0 ? (
                    <Typography variant="body2">
                      {tryEndedVisible(turned)}
                    </Typography>
                  ) : (
                    <GivenValues
                      values={turned.values}
                      gives={gives}
                      shut={true}
                    />
                  )}
                  {(turned.review.didNotFit ?? turned.didNotFit) ===
                    undefined ||
                  (turned.review.didNotFit ?? turned.didNotFit) ===
                    "model_declined" ||
                  turned.values.length !== 0 ? null : (
                    <Typography variant="body2" sx={QUIET_SX}>
                      {misfitSaid(
                        (turned.review.didNotFit ?? turned.didNotFit)!,
                      )}
                    </Typography>
                  )}
                  {turned.didNotFit === "model_declined" ? (
                    <>
                      <Typography variant="body2" sx={QUIET_SX}>
                        {misfitSaid(turned.didNotFit)}
                      </Typography>
                      <DeclinedDrawn declined={turned.declined} />
                    </>
                  ) : null}
                  <UnreadableResponseDrawn
                    response={turned.unreadableResponse}
                  />
                  {turned.review.didNotFit === "model_declined" ? (
                    <>
                      <Typography variant="body2" sx={QUIET_SX}>
                        {misfitSaid(turned.review.didNotFit)}
                      </Typography>
                      <DeclinedDrawn declined={turned.review.declined} />
                    </>
                  ) : null}
                  <UnreadableResponseDrawn
                    response={turned.review.unreadableResponse}
                  />
                  {turned.erroredFor === undefined ? null : (
                    <Typography variant="body2" sx={QUIET_SX}>
                      {erroredForVisible(
                        turned.erroredFor,
                        declared,
                        declaring,
                      )}
                    </Typography>
                  )}
                </>
              );
            }}
          </Async>
        )}
        <ReviewControls
          groupId={groupId}
          runId={reading.run.runId}
          step={step}
          inHand={read.value}
          declaring={declaring}
          heading="h3"
          landsOn={stepAnchor(step.stepId)}
          onShown={shown}
        />
        {/* Details, after them, is the frame's last child even while hidden, so the frame's last-child margin reset
          never reaches them. */}
        <Box sx={{ ...HEADER_ACTS_SX, mt: 2 }}>
          {machineTries.length <= 1 ? null : (
            <Box sx={HEADER_ACTS_SX}>
              <Button
                ref={earlierControl}
                variant="outlined"
                color="inherit"
                size="small"
                aria-describedby={titleId}
                disabled={shownNumber === machineTries[0]}
                onClick={() => {
                  const index = machineTries.indexOf(shownNumber!);
                  const to = machineTries[index - 1]!;
                  turning(to, index === 1, laterControl.current);
                }}
              >
                {say("step.earlierTry")}
              </Button>
              <Button
                ref={laterControl}
                variant="outlined"
                color="inherit"
                size="small"
                aria-describedby={titleId}
                disabled={shownNumber === machineTries.at(-1)}
                onClick={() => {
                  const index = machineTries.indexOf(shownNumber!);
                  const next = machineTries[index + 1]!;
                  const to = next === machineTries.at(-1) ? null : next;
                  turning(to, to === null, earlierControl.current);
                }}
              >
                {say("step.laterTry")}
              </Button>
            </Box>
          )}
          <Button
            variant="outlined"
            color="inherit"
            size="small"
            aria-expanded={open}
            aria-controls={detailsId}
            aria-describedby={titleId}
            onClick={() => setOpen((was) => !was)}
          >
            {say("step.details")}
          </Button>
        </Box>
        <Box id={detailsId} hidden={!open} sx={SPACED_ABOVE_SX}>
          {!open ? null : !showingEarlier ? (
            <Async read={read} empty={() => null}>
              {details}
            </Async>
          ) : read.problem === null ? (
            details(read.value)
          ) : null}
        </Box>
      </Message>
    </SearchReviewTry.Provider>
  );
}

function useStepRead(
  groupId: string,
  runId: string,
  stepId: string,
  needed: boolean,
) {
  const access = useRunReadAccess();
  const guardRead = access.read;
  const load = useCallback(
    (signal: AbortSignal): Promise<StepAnswer | null> =>
      needed
        ? guardRead(signal, () => readStep(groupId, runId, stepId, signal))
        : Promise.resolve(null),
    [groupId, runId, stepId, needed, guardRead],
  );
  return useKeptUp(load, NO_WAIT);
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
              <DecisionDrawn decision={each.decision} />
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
  shownNumber,
  declared,
  declaring,
}: {
  readonly groupId: string;
  readonly answer: StepAnswer;
  readonly reading: RunSteps;
  readonly step: StepRow;
  readonly shownNumber: number | undefined;
  readonly declared: Declared | undefined;
  readonly declaring: Declaring;
}) {
  const { search } = useLocation();
  const tried = answer.triesMade;
  const shown = tried.find((each) => each.number === shownNumber);
  return (
    <>
      {shown === undefined ? (
        step.tries === undefined || step.tries.current === 0 ? (
          <Typography variant="body2">{say("step.noTries")}</Typography>
        ) : null
      ) : (
        <TryPanel
          aTry={shown}
          field={undefined}
          declared={declared}
          declaring={declaring}
        />
      )}
      <Fields>
        <Field label={say("step.takesFrom")}>
          {step.takesFrom.length === 0 ? (
            say("step.takesNothing")
          ) : (
            <Box component="ul" sx={TAKEN_SX}>
              {step.takesFrom.map((taken) => (
                <li key={taken.sourcePartId ?? taken.input}>
                  {takenSaid(taken, step, declaring)}
                </li>
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
