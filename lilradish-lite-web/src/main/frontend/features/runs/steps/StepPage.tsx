import Box from "@mui/material/Box";
import Button from "@mui/material/Button";
import Link from "@mui/material/Link";
import Typography from "@mui/material/Typography";
import {
  Fragment,
  useCallback,
  useEffect,
  useId,
  useState,
  type ReactNode,
} from "react";
import {
  Link as RouterLink,
  useLocation,
  useParams,
  useSearchParams,
} from "react-router";

import {
  readSteps,
  type StepRow,
  type StepRuns,
} from "../../../api/groups/{groupId}/runs/{runId}/steps";
import {
  readStep,
  type StepAnswer,
} from "../../../api/groups/{groupId}/runs/{runId}/steps/{stepId}";
import { readRun, type Run } from "../../../api/groups/{groupId}/runs/{runId}";
import { Async } from "../../../app/Async";
import { ProblemView } from "../../../app/ProblemView";
import { movesTheReader } from "../../../app/refusal";
import { useStandingRead } from "../../../app/standing/StandingContext";
import { say } from "../../../i18n/app";
import { isolatedInText } from "../../../lib/direction/isolated";
import { Field, Fields } from "../../../lib/form/Fields";
import { PageHeading } from "../../../lib/heading/PageHeading";
import { BESIDE_FROM } from "../../../lib/collection/RowPanel";
import { QUIET_SX, SPACED_FAR_ABOVE_SX } from "../../../lib/layout/parts";
import { TwoColumns } from "../../../lib/layout/TwoColumns";
import { useCalendarNow } from "../../../lib/time/When";

import { Notice } from "../../../lib/notice/Notice";
import { useResource } from "../../../lib/request/useResource";
import { entryHref } from "../../library/entryAddress";
import { LIBRARY_KINDS } from "../../library/libraryKinds";
import { StepKindTag } from "../../library/StepKindTag";
import { CurrentFileRunContext } from "../fill/CurrentFileRun";
import { FilledValue } from "../fill/FilledValue";
import {
  listQuery,
  PICKED_TRY,
  PICKED_VALUE,
  runHref,
  useArrivedWithinTheRun,
} from "../runAddress";
import { AboutIt } from "../AboutIt";

import { RunPath } from "../RunPage";
import { RunReadAccess, useRunReadAccess } from "../RunReadAccess";
import { stateWorded } from "../runStates";
import { CostDrawn } from "../spend";
import { AnswerHere } from "./AnswerHere";
import { BeneathLinks } from "./BeneathLinks";
import { AskAgainButton } from "./AskAgainButton";
import { declaredFor, fieldAt, labelAt, type Declaring } from "./declared";
import { ReviewControls, searchWaitingOnReader } from "./ReviewControls";
import { SearchReviewTry, SearchText } from "./SearchRecord";
import { StateNews, type Stated } from "./StateNews";
import { StepStateBadge } from "./StepStateBadge";
import {
  tryBeyondVisible,
  standingSaid,
  stepStateWorded,
  whereSaid,
  whoSaid,
  withheldSaid,
} from "./stepStates";
import { spokenName, stepTitle } from "./stepTitle";
import { TriesList } from "./TriesList";
import { TrySendingButton } from "./TrySendingButton";
import { Turnaways } from "./Turnaways";
import { useKeptUp } from "./useRunRead";
import { WentInValues } from "./WentInValues";

function waitOfStep(page: StepAnswer): number | undefined {
  return page.rereadAfterSeconds;
}

/** The run by the name the step's page gives it, then the step by its heading. */
function statesOfStep({ run, step }: StepAnswer): Stated[] {
  return [
    {
      key: run.runId,
      name: say("run.title", { number: run.number }),
      state: stateWorded(run.state),
    },
    {
      key: step.stepId,
      name: isolatedInText(stepTitle(step)),
      state: stepStateWorded(step.state),
    },
  ];
}

/** A step's act answer stays shown while an older read is set aside and a fresh read begins. */
export function StepPage() {
  const { groupId = "", runId = "", stepId = "" } = useParams();
  return (
    <RunReadAccess key={`${groupId}/${runId}`}>
      <StepPageWithin groupId={groupId} runId={runId} stepId={stepId} />
    </RunReadAccess>
  );
}

function StepPageWithin({
  groupId,
  runId,
  stepId,
}: {
  readonly groupId: string;
  readonly runId: string;
  readonly stepId: string;
}) {
  const access = useRunReadAccess();
  const guardRead = access.read;
  const reloadStanding = useStandingRead().reload;
  const load = useCallback(
    (signal: AbortSignal): Promise<StepAnswer | null> =>
      guardRead(signal, () => readStep(groupId, runId, stepId, signal)),
    [groupId, runId, stepId, guardRead],
  );
  const kept = useKeptUp(load, waitOfStep);
  const loadSteps = useCallback(
    (signal: AbortSignal) =>
      guardRead(signal, () => readSteps(groupId, runId, signal)),
    [groupId, runId, guardRead],
  );
  const listed = useResource(loadSteps, null);
  // Read once for the way back alone, which names every run above it: the step's own read names none of them.
  const loadRun = useCallback(
    (signal: AbortSignal): Promise<Run | null> =>
      guardRead(signal, () => readRun(groupId, runId, signal)),
    [groupId, runId, guardRead],
  );
  const run = useResource(loadRun, null);
  const [acted, setActed] = useState<{
    readonly over: StepAnswer | null;
    readonly answer: StepAnswer;
  } | null>(null);
  const read = {
    ...kept,
    value:
      acted !== null && acted.over === kept.value ? acted.answer : kept.value,
  };
  const shown = (answer: StepAnswer) => {
    setActed({ over: kept.value, answer });
    kept.quietly();
  };
  const arriving = useArrivedWithinTheRun();

  useEffect(() => {
    if (read.problem !== null && movesTheReader(read.problem)) {
      reloadStanding();
    }
  }, [read.problem, reloadStanding]);

  if (access.problem !== null) {
    return (
      <>
        <RunPath run={null} />
        <ProblemView problem={access.problem} />
      </>
    );
  }

  return (
    <>
      <RunPath
        run={run.value}
        step={
          read.value === null
            ? null
            : isolatedInText(stepTitle(read.value.step))
        }
      />
      <StateNews reading={read.value} statesOf={statesOfStep} />
      <Async read={read} empty={() => null}>
        {(page) =>
          page === null ? null : (
            <CurrentFileRunContext.Provider
              value={{ groupId, runId: page.run.runId }}
            >
              <StepDrawn
                groupId={groupId}
                page={page}
                steps={
                  listed.problem === null
                    ? (listed.value?.steps ?? [])
                    : undefined
                }
                focusOnArrival={arriving}
                onShown={shown}
              />
            </CurrentFileRunContext.Provider>
          )
        }
      </Async>
    </>
  );
}

function StepDrawn({
  groupId,
  page,
  steps,
  focusOnArrival,
  onShown,
}: {
  readonly groupId: string;
  readonly page: StepAnswer;
  readonly steps: readonly StepRow[] | undefined;
  readonly focusOnArrival: boolean;
  readonly onShown: (answer: StepAnswer) => void;
}) {
  const now = useCalendarNow();
  const { search } = useLocation();
  const cameOutId = useId();
  const [askOut, setAskOut] = useState(false);
  const [answerOut, setAnswerOut] = useState(false);
  const { run, step } = page;
  const [parameters] = useSearchParams();
  const searchTry = searchWaitingOnReader(step, page);
  const pickedSearch =
    searchTry !== undefined &&
    searchTry.number === Number(parameters.get(PICKED_TRY) ?? Number.NaN);
  const declared = declaredFor(step, page.declarations);
  const [, ...why] = whereSaid(step, declared?.gives, groupId, now, search);
  const heldBack = step.state === "held_back" && why.length > 0;
  const declaring: Declaring = {
    runId: run.runId,
    declarations: page.declarations,
    runVersionId: run.versionId,
    steps,
    step,
  };
  const turnedAway = step.where?.turnedAway;
  return (
    <>
      <PageHeading
        focusOnArrival={focusOnArrival}
        title={isolatedInText(stepTitle(step))}
      />
      <SearchReviewTry.Provider
        value={pickedSearch ? null : (searchTry?.number ?? null)}
      >
        <TwoColumns
          wideAtLeast={BESIDE_FROM}
          wide={
            <>
              {heldBack ? (
                <Notice severity="warning">
                  {why.map((line, at) => (
                    <Fragment key={at}>
                      {at === 0 ? null : " "}
                      {line}
                    </Fragment>
                  ))}
                  {turnedAway === undefined ? null : (
                    <Turnaways turnedAway={turnedAway} />
                  )}
                  {step.runs.kind !== "search" ||
                  (step.where?.reason !== "too_long" &&
                    step.where?.reason !== "turned_away") ? null : step.where
                      .searchQuestionWithheld === true ? (
                    <Typography variant="body2">
                      {say("step.searchWithheld")}
                    </Typography>
                  ) : step.where.searchQuestion === undefined ? null : (
                    <Fields>
                      <Field
                        label={say(
                          step.where.reason === "too_long"
                            ? "step.heldSearchQuestionUnsent"
                            : "step.heldSearchQuestionTurnedAway",
                        )}
                      >
                        <SearchText text={step.where.searchQuestion} />
                      </Field>
                    </Fields>
                  )}
                </Notice>
              ) : null}
              {withheldSaid(step, declaring).map((line) => (
                <Typography key={line} variant="body2">
                  {line}
                </Typography>
              ))}
              <TrySendingButton
                groupId={groupId}
                runId={run.runId}
                step={step}
                onShown={onShown}
              />
              <AskAgainButton
                groupId={groupId}
                runId={run.runId}
                step={step}
                held={answerOut}
                onRunning={setAskOut}
                landsOn={cameOutId}
                onShown={onShown}
              />
              <AnswerHere
                groupId={groupId}
                runId={run.runId}
                step={step}
                inHand={page}
                held={askOut}
                onRunning={setAnswerOut}
                declaring={declaring}
                wentInDrawn
                heading="h2"
                landsOn={cameOutId}
                onShown={onShown}
              />
              <WentIn page={page} declaring={declaring} />
              <ReviewControls
                groupId={groupId}
                runId={run.runId}
                step={step}
                inHand={page}
                searchDrawn={pickedSearch}
                declaring={declaring}
                heading="h2"
                landsOn={cameOutId}
                onShown={onShown}
              />
              <CameOut
                page={page}
                declaring={declaring}
                headingId={cameOutId}
              />
            </>
          }
          narrow={
            <AboutIt
              facts={
                <>
                  <Field label={say("work.run")}>
                    {say("run.title", { number: run.number })}
                  </Field>
                  <Field label={say("step.order")}>
                    {say("step.orderOf", {
                      order: step.order,
                      of: run.progress.of,
                    })}
                  </Field>
                  <Field label={say("step.runs")}>
                    {runsSaid(groupId, step.runs)}
                  </Field>
                  {step.runs.kind === "workflow" ||
                  step.runs.kind === "route" ? null : (
                    <>
                      <Field label={say("step.producedBy")}>
                        {step.producer === undefined
                          ? say("step.producesNothing")
                          : whoSaid(step.producer)}
                      </Field>
                      <Field label={say("step.reviewedBy")}>
                        {reviewerSaid(step)}
                      </Field>
                    </>
                  )}
                  {step.beneath === undefined ? null : (
                    <Field label={say("run.above")}>
                      <BeneathLinks groupId={groupId} beneath={step.beneath} />
                    </Field>
                  )}
                  <Field label={say("run.where")}>
                    <StepStateBadge state={step.state} />

                    {(heldBack ? [] : why).map((line, at) => (
                      <Typography key={at} variant="body2" sx={QUIET_SX}>
                        {line}
                      </Typography>
                    ))}
                  </Field>
                  {step.tries === undefined || step.tries.current < 1 ? null : (
                    <Field label={say("step.tries")}>
                      {step.tries.beyond
                        ? tryBeyondVisible(
                            step.tries.current,
                            step.tries.declared,
                          )
                        : say("step.tryOf", {
                            number: step.tries.current,
                            allowed: step.tries.declared,
                          })}
                    </Field>
                  )}
                  <Field label={say("step.cost")}>
                    <CostDrawn cost={step.cost} />
                  </Field>
                </>
              }
            />
          }
        />
      </SearchReviewTry.Provider>
    </>
  );
}

/** The kind beside the pinned question or workflow version, or the code step's name. */
function runsSaid(groupId: string, runs: StepRuns): ReactNode {
  const { kind, entryId, name, version, versionId, codeStep } = runs;
  if (kind === "search" && runs.searchWay !== undefined) {
    return say("step.runsSearch", {
      way: isolatedInText(spokenName(runs.searchWay)),
    });
  }
  if (
    (kind === "question" || kind === "workflow") &&
    entryId !== undefined &&
    name !== undefined &&
    version !== undefined
  ) {
    return (
      <>
        <StepKindTag kind={kind} />{" "}
        <Link
          component={RouterLink}
          to={entryHref(groupId, LIBRARY_KINDS[kind], entryId, versionId)}
        >
          {say("run.workflowVersion", { name: isolatedInText(name), version })}
        </Link>
      </>
    );
  }
  if (kind === "code_step" && codeStep !== undefined) {
    return (
      <>
        <StepKindTag kind={kind} /> {isolatedInText(spokenName(codeStep))}
      </>
    );
  }
  return <StepKindTag kind={kind} />;
}

/** Nobody where its absence says nobody reviews; somebody who may review, where no one person is named. */
function reviewerSaid(step: StepRow): string {
  const reviewer = step.reviewer;
  if (reviewer === undefined) {
    return say("step.nobodyReviews");
  }
  return reviewer.kind === "person" && reviewer.person === undefined
    ? say("step.reviewerPerson")
    : whoSaid(reviewer);
}

/** Every input, where it came from, and what it was given; or what it would be given, where nothing is sent yet. */
function WentIn({
  page,
  declaring,
}: {
  readonly page: StepAnswer;
  readonly declaring: Declaring;
}) {
  const headingId = useId();
  const { step, wentIn } = page;
  const takes = declaredFor(step, page.declarations)?.takes;
  return (
    <Box
      component="section"
      aria-labelledby={headingId}
      sx={SPACED_FAR_ABOVE_SX}
    >
      <Typography id={headingId} variant="h2">
        {say("step.wentIn")}
      </Typography>
      {wentIn === undefined ? (
        <Typography variant="body2">{say("step.nothingYet")}</Typography>
      ) : wentIn.length === 0 ? (
        <Typography variant="body2">{say("step.takesNothing")}</Typography>
      ) : (
        <>
          {page.wentInFrom === "not_yet_sent" ? (
            <Typography variant="body2" sx={QUIET_SX}>
              {say("step.notYetSent")}
            </Typography>
          ) : null}
          <WentInValues
            wentIn={wentIn}
            takes={takes}
            declaring={declaring}
            drawing={page.drawing}
            under="h2"
          />
        </>
      )}
    </Box>
  );
}

/**
 * Each value it gives back and where it stands, then every try of the one picked, the first until another is. The
 * value and the try picked are held in the address, so a link to the page opens them again.
 */
function CameOut({
  page,
  declaring,
  headingId,
}: {
  readonly page: StepAnswer;
  readonly declaring: Declaring;
  /** Where the keyboard goes once a review it asked for is answered. */
  readonly headingId: string;
}) {
  const { groupId = "" } = useParams();
  const { search } = useLocation();
  const triesId = useId();
  const [parameters, setParameters] = useSearchParams();
  const { step, cameOut } = page;
  const declared = declaredFor(step, page.declarations);
  const gives = declared?.gives;
  const named = cameOut?.map((each) => each.field) ?? [];
  const asked = parameters.get(PICKED_VALUE);
  const field =
    asked !== null &&
    (named.includes(asked) || gives?.some(({ name }) => name === asked))
      ? asked
      : (named[0] ?? gives?.[0]?.name);
  const tryNumber = Number(parameters.get(PICKED_TRY) ?? Number.NaN);

  const picking = (value: string | undefined, number: number | null) =>
    setParameters(
      (previous) => {
        const next = new URLSearchParams(previous);
        if (value !== undefined) {
          next.set(PICKED_VALUE, value);
        }
        if (number === null) {
          next.delete(PICKED_TRY);
        } else {
          next.set(PICKED_TRY, String(number));
        }
        return next;
      },
      { replace: true },
    );

  return (
    <>
      <Box
        component="section"
        aria-labelledby={headingId}
        sx={SPACED_FAR_ABOVE_SX}
      >
        <Typography id={headingId} tabIndex={-1} variant="h2">
          {say("step.cameOut")}
        </Typography>
        {cameOut === undefined ? (
          <Typography variant="body2">
            {say(
              step.state === "not_started"
                ? "step.nothingYet"
                : "step.producedNothing",
            )}
          </Typography>
        ) : (
          <Fields>
            {cameOut.map((each) => (
              <Field key={each.field} label={labelAt(gives, each.field)}>
                {each.standing === undefined ? null : (
                  <FilledValue
                    field={fieldAt(gives, each.field)}
                    label={labelAt(gives, each.field)}
                    shown={each.standing}
                    shut={false}
                  />
                )}
                <Typography variant="body2" sx={QUIET_SX}>
                  {standingSaid(each.now)}
                </Typography>
                {each.fromRun === undefined ? null : (
                  <Link
                    component={RouterLink}
                    to={`${runHref(groupId, each.fromRun.runId)}${listQuery(search)}`}
                  >
                    {say("run.title", { number: each.fromRun.number })}
                  </Link>
                )}
                {cameOut.length > 1 ? (
                  <Button
                    variant="outlined"
                    color="inherit"
                    size="small"
                    aria-pressed={each.field === field}
                    onClick={() => picking(each.field, null)}
                  >
                    {say("step.seeTries", {
                      field: labelAt(gives, each.field),
                    })}
                  </Button>
                ) : null}
              </Field>
            ))}
          </Fields>
        )}
      </Box>
      <Box
        component="section"
        aria-labelledby={triesId}
        sx={SPACED_FAR_ABOVE_SX}
      >
        <Typography id={triesId} variant="h2">
          {field === undefined
            ? say("step.theTries")
            : say("step.triesOfValue", { field: labelAt(gives, field) })}
        </Typography>
        <TriesList
          key={field}
          tries={page.triesMade}
          allowed={step.tries?.declared}
          field={field}
          declared={declared}
          declaring={declaring}
          labelledBy={triesId}
          picked={Number.isSafeInteger(tryNumber) ? tryNumber : null}
          onPick={(number) => picking(field, number)}
        />
      </Box>
    </>
  );
}
