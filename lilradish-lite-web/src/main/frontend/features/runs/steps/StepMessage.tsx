import Box from "@mui/material/Box";
import Button, { buttonClasses } from "@mui/material/Button";
import Typography from "@mui/material/Typography";
import {
  useCallback,
  useEffect,
  useId,
  useLayoutEffect,
  useRef,
  useState,
} from "react";
import { useLocation } from "react-router";

import type { ReadField } from "../../../api/filling";
import type {
  RunSteps,
  StepRow,
} from "../../../api/groups/{groupId}/runs/{runId}/steps";
import {
  readStep,
  type StepAnswer,
} from "../../../api/groups/{groupId}/runs/{runId}/steps/{stepId}";
import type { GroupStanding } from "../../../api/standing";
import type { Problem } from "../../../api/problem";
import { ProblemView } from "../../../app/ProblemView";
import { useStanding } from "../../../app/standing/StandingContext";
import type { TriedValue } from "../../../api/stepTries";
import { Async } from "../../../app/Async";
import { say } from "../../../i18n/app";
import { isolatedInText } from "../../../lib/direction/isolated";
import { Disclosure } from "../../../lib/action/Disclosure";
import { ActGroup } from "../../../lib/action/ActGroup";
import { Field, Fields } from "../../../lib/form/Fields";
import {
  HEADER_ACTS_SX,
  QUIET_SX,
  SPACED_ABOVE_SX,
} from "../../../lib/layout/parts";
import { useCalendarNow, whenText } from "../../../lib/time/When";
import { FilledValue, writtenValueOf } from "../fill/FilledValue";
import { CopyFailure, CopyValue } from "../fill/ValueCard";
import { useRunReadAccess } from "../RunReadAccess";
import { stepAnchor } from "../runAddress";
import { AnswerHere } from "./AnswerHere";
import { LengthPanel } from "./LengthPanel";
import { BeneathLinks } from "./BeneathLinks";
import { AskAgainButton } from "./AskAgainButton";
import { DecisionDrawn, LengthDecisionDrawn } from "./DecisionDrawn";
import { declaredFor, fieldAt, labelAt } from "./declared";
import { Message } from "./Message";
import {
  ReviewControls,
  searchWaitingOnReader,
  waitingOnReader,
} from "./ReviewControls";
import { SearchReviewTry } from "./SearchRecord";
import { withheldSaid } from "./stepActs";
import { StepStateBadge } from "./StepStateBadge";
import { stepTitle } from "./stepTitle";
import {
  tryBeyondVisible,
  tryEndedVisible,
  erroredForVisible,
  misfitSaid,
} from "./stepTries";
import { whereSaid } from "./stepWhereabouts";
import {
  DeclinedDrawn,
  ModelReplyDrawn,
  UnreadableResponseDrawn,
} from "./TryPanel";
import { TryMaterial, TryRead } from "./TryRead";
import { StepDetails } from "./StepDetails";
import { TrySendingButton } from "./TrySendingButton";
import { Turnaways } from "./Turnaways";
import { useKeptUp } from "./useRunRead";

const NO_WAIT = () => undefined;

// The step summary has no revision, so an open detail compares its content after each parent read.
export function StepMessage({
  group,
  reading,
  step,
  handTries,
  onShown,
}: {
  readonly group: GroupStanding;
  readonly reading: RunSteps;
  readonly step: StepRow;
  readonly handTries?: ReadonlySet<number>;
  readonly onShown: (answer: StepAnswer) => void;
}) {
  const { groupId } = group;
  const access = useRunReadAccess();
  const standing = useStanding();
  const now = useCalendarNow();
  const { search } = useLocation();
  const [open, setOpen] = useState(false);
  const [copyFailed, setCopyFailed] = useState(false);
  const [accountOpen, setAccountOpen] = useState(false);
  const [turnedTo, setTurnedTo] = useState<number | null>(null);
  const [askOut, setAskOut] = useState(false);
  const [answerOut, setAnswerOut] = useState(false);
  const [lengthRefusal, setLengthRefusal] = useState<Problem | null>(null);
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
  const earlierHeld = shownNumber === machineTries[0];
  const laterHeld = shownNumber === machineTries.at(-1);
  const showingEarlier =
    shownNumber !== undefined && shownNumber !== tries?.current;
  const currentIsHand =
    tries !== undefined && handTries?.has(tries.current) === true;
  const waiting = waitingOnReader(step, standing, groupId);
  const needsDecisions =
    !currentIsHand &&
    tries !== undefined &&
    tries.current > 0 &&
    step.gaveBack?.some(
      ({ now: state }) => state === "refused" || state === "refused_for_length",
    ) === true;
  const needed =
    open ||
    showingEarlier ||
    (needsDecisions && access.problem === null) ||
    (step.runs.kind === "search" && waiting.length > 0);
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
  const currentDecisionTry =
    access.problem === null &&
    read.problem === null &&
    read.value?.step.stepId === step.stepId &&
    read.value.step.tries?.current === tries?.current
      ? read.value.triesMade.find((each) => each.number === tries?.current)
      : undefined;
  const currentDecisions = new Map(
    currentDecisionTry?.values.map((each) => [each.field, each]),
  );
  const currentValues = (step.gaveBack ?? [])
    .filter(({ field }) => !waiting.includes(field))
    .map((each) => {
      const reviewed = currentDecisions.get(each.field);
      return reviewed?.decision !== undefined &&
        reviewed.now === each.now &&
        "withheld" in reviewed === "withheld" in each &&
        (each.now === "refused" || each.now === "refused_for_length")
        ? { ...each, decision: reviewed.decision }
        : each;
    });
  const copiedValues = showingEarlier
    ? (read.value?.triesMade.find((each) => each.number === shownNumber)
        ?.values ?? [])
    : (step.gaveBack ?? []);
  const copiedInputs = showingEarlier
    ? []
    : (step.wentIn ?? []).flatMap((each) => {
        const field = fieldAt(declared?.takes, each.input);
        const raw = writtenValueOf(field, each);
        return raw === undefined
          ? []
          : [`${field?.label ?? field?.name ?? each.input}\n${raw}`];
      });
  const copiedStep = [
    stepTitle(step),
    ...copiedInputs,
    ...copiedValues.flatMap((each) => {
      const raw = writtenValueOf(fieldAt(gives, each.field), each);
      const declaredField = fieldAt(gives, each.field);
      return raw === undefined
        ? []
        : [
            `${declaredField?.label ?? declaredField?.name ?? each.field}\n${raw}`,
          ];
    }),
  ].join("\n\n");
  const [, ...why] = whereSaid(step, gives, group, now, search);
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
    ...withheldSaid(step, declaring, standing, groupId).map((line) => (
      <Typography key={`withheld-${line}`} variant="body2">
        {line}
      </Typography>
    )),
  ];
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
      <StepDetails
        group={group}
        answer={answer}
        reading={reading}
        step={step}
        shownNumber={shownNumber}
        declared={declared}
        declaring={declaring}
      />
    );

  return (
    <TryRead
      group={group}
      runId={reading.run.runId}
      stepId={step.stepId}
      shown={
        open || showingEarlier
          ? read.value?.triesMade.find((aTry) => aTry.number === shownNumber)
          : undefined
      }
      waiting={searchWaitingOnReader(step, read.value, standing, groupId)}
    >
      <SearchReviewTry.Provider
        value={
          searchWaitingOnReader(step, read.value, standing, groupId)?.number ??
          null
        }
      >
        <Message
          anchor={stepAnchor(step.stepId)}
          title={isolatedInText(stepTitle(step))}
          titleId={titleId}
          kind={step.runs.kind}
          proseOpened={open}
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
            <BeneathLinks group={group} beneath={step.beneath} />
          )}
          {lengthRefusal === null ? null : (
            <ProblemView problem={lengthRefusal} />
          )}
          {step.length === undefined ? null : (
            <LengthPanel
              length={step.length}
              group={group}
              declaring={declaring}
              stepId={step.stepId}
              onShown={shown}
              onRefused={setLengthRefusal}
              message
            />
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
                group={group}
                values={currentValues}
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
                        group={group}
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
                    {turned.erroredFor === undefined ? null : (
                      <Typography variant="body2" sx={QUIET_SX}>
                        {erroredForVisible(
                          turned.erroredFor,
                          declared,
                          declaring,
                        )}
                      </Typography>
                    )}
                    <TryMaterial number={turned.number}>
                      {(material) => (
                        <>
                          {turned.didNotFit === "model_declined" ? (
                            <>
                              <Typography variant="body2" sx={QUIET_SX}>
                                {misfitSaid(turned.didNotFit)}
                              </Typography>
                              <DeclinedDrawn
                                declined={material?.declined}
                                replyDrawn={material?.modelReply !== undefined}
                              />
                            </>
                          ) : null}
                          <UnreadableResponseDrawn
                            response={material?.unreadableResponse}
                          />
                          {turned.didNotFit !== "model_declined" ||
                          material?.modelReply === undefined ? null : (
                            <Fields>
                              <Field label={say("step.producedBy")}>
                                <ModelReplyDrawn reply={material.modelReply} />
                              </Field>
                            </Fields>
                          )}
                          {turned.review.didNotFit === "model_declined" ? (
                            <>
                              <Typography variant="body2" sx={QUIET_SX}>
                                {misfitSaid(turned.review.didNotFit)}
                              </Typography>
                              <DeclinedDrawn
                                declined={material?.review?.declined}
                                replyDrawn={
                                  material?.review?.modelReply !== undefined
                                }
                              />
                            </>
                          ) : null}
                          <UnreadableResponseDrawn
                            response={material?.review?.unreadableResponse}
                          />
                          {turned.review.didNotFit !== "model_declined" ||
                          material?.review?.modelReply === undefined ? null : (
                            <Fields>
                              <Field label={say("step.reviewedBy")}>
                                <ModelReplyDrawn
                                  reply={material.review.modelReply}
                                />
                              </Field>
                            </Fields>
                          )}
                        </>
                      )}
                    </TryMaterial>
                  </>
                );
              }}
            </Async>
          )}
          {/* Details, after them, is the frame's last child even while hidden, so the frame's last-child margin reset
          never reaches them. */}
          <Box sx={{ ...HEADER_ACTS_SX, mt: 2 }}>
            <CopyValue
              text={copiedStep}
              describedBy={titleId}
              step
              onCopyFailed={setCopyFailed}
            />
            {machineTries.length <= 1 ? null : (
              <ActGroup>
                <Button
                  ref={earlierControl}
                  variant="text"
                  color="inherit"
                  size="small"
                  aria-describedby={titleId}
                  fullWidth
                  aria-disabled={earlierHeld || undefined}
                  className={earlierHeld ? buttonClasses.disabled : undefined}
                  disableFocusRipple={earlierHeld}
                  onClick={() => {
                    if (earlierHeld) return;
                    const index = machineTries.indexOf(shownNumber!);
                    const to = machineTries[index - 1]!;
                    turning(to, index === 1, laterControl.current);
                  }}
                >
                  {say("step.earlierTry")}
                </Button>
                <Button
                  ref={laterControl}
                  variant="text"
                  color="inherit"
                  size="small"
                  aria-describedby={titleId}
                  fullWidth
                  aria-disabled={laterHeld || undefined}
                  className={laterHeld ? buttonClasses.disabled : undefined}
                  disableFocusRipple={laterHeld}
                  onClick={() => {
                    if (laterHeld) return;
                    const index = machineTries.indexOf(shownNumber!);
                    const next = machineTries[index + 1]!;
                    const to = next === machineTries.at(-1) ? null : next;
                    turning(to, to === null, earlierControl.current);
                  }}
                >
                  {say("step.laterTry")}
                </Button>
              </ActGroup>
            )}
            <Button
              variant="text"
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
          <CopyFailure failed={copyFailed} />
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
        <ReviewControls
          message
          groupId={groupId}
          runId={reading.run.runId}
          step={step}
          inHand={read.value}
          declaring={declaring}
          heading="h3"
          landsOn={stepAnchor(step.stepId)}
          onShown={shown}
        />
      </SearchReviewTry.Provider>
    </TryRead>
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

function GivenValues({
  group,
  values,
  gives,
  shut,
}: {
  readonly group: GroupStanding;
  readonly values: readonly TriedValue[];
  readonly gives: readonly ReadField[] | undefined;
  readonly shut: boolean;
}) {
  return values.length === 0 ? null : (
    <Box>
      {values.map((each) => (
        <Box key={each.field}>
          <FilledValue
            cards
            field={fieldAt(gives, each.field)}
            label={labelAt(gives, each.field)}
            shown={each}
            shut={shut}
            status={each.now}
            foot={
              each.now === "none" ? (
                say("step.nowNone")
              ) : each.now === "refused_for_length" &&
                each.decision !== undefined ? (
                <LengthDecisionDrawn
                  decision={each.decision}
                  group={group}
                  field={labelAt(gives, each.field)}
                />
              ) : each.now === "refused" &&
                each.decision?.outcome === "refused" &&
                (each.decision.why !== undefined ||
                  each.decision.withheld === true) ? (
                <DecisionDrawn decision={each.decision} />
              ) : undefined
            }
          />
        </Box>
      ))}
    </Box>
  );
}
