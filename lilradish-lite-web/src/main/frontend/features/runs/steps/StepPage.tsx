import Box from "@mui/material/Box";
import { VersionLabel } from "../../../lib/text/VersionLabel";
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
import type { GroupStanding } from "../../../api/standing";
import type { Problem } from "../../../api/problem";
import { Async } from "../../../app/Async";
import { ProblemView } from "../../../app/ProblemView";
import { movesTheReader } from "../../../app/refusal";
import { useGroup } from "../../../app/standing/RequireGroup";
import { useStandingRead } from "../../../app/standing/StandingContext";
import { say } from "../../../i18n/app";
import { visible } from "../../../i18n/visible";
import { Press } from "../../../lib/action/Press";
import { isolatedInText } from "../../../lib/direction/isolated";
import { Field, Fields } from "../../../lib/form/Fields";
import { PageHeading } from "../../../lib/heading/PageHeading";
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
import { ValueStatusChip } from "../fill/ValueCard";
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
import { LengthPanel } from "./LengthPanel";
import { BeneathLinks } from "./BeneathLinks";
import { AskAgainButton } from "./AskAgainButton";
import { declaredFor, fieldAt, labelAt, type Declaring } from "./declared";
import { ReviewControls, searchWaitingOnReader } from "./ReviewControls";
import { SearchReviewTry } from "./SearchRecord";
import { ProseScope } from "../../../lib/text/ModelProse";
import { StateNote } from "../../../lib/text/StateNote";
import { StateNews, type Stated } from "./StateNews";
import { withheldSaid } from "./stepActs";
import { whoSaid } from "./stepProducersAndReviewers";
import { StepStateBadge } from "./StepStateBadge";
import { stepStateWorded } from "./stepStates";
import { spokenName, stepTitle } from "./stepTitle";
import { tryBeyondVisible, standingSaid } from "./stepTries";
import { whereSaid } from "./stepWhereabouts";
import { TriesList } from "./TriesList";
import { TryRead } from "./TryRead";
import { TrySendingButton } from "./TrySendingButton";
import { Turnaways } from "./Turnaways";
import { useKeptUp } from "./useRunRead";
import { WentInValues } from "./WentInValues";

function waitOfStep(page: StepAnswer): number | undefined {
  return page.rereadAfterSeconds;
}

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
  const group = useGroup();
  const { runId = "", stepId = "" } = useParams();
  return (
    <RunReadAccess key={`${group.groupId}/${runId}`}>
      <StepPageWithin group={group} runId={runId} stepId={stepId} />
    </RunReadAccess>
  );
}

function StepPageWithin({
  group,
  runId,
  stepId,
}: {
  readonly group: GroupStanding;
  readonly runId: string;
  readonly stepId: string;
}) {
  const { groupId } = group;
  const access = useRunReadAccess();
  const guardRead = access.read;
  const standingRead = useStandingRead();
  const reloadStanding = standingRead.reload;
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
                group={group}
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
  group,
  page,
  steps,
  focusOnArrival,
  onShown,
}: {
  readonly group: GroupStanding;
  readonly page: StepAnswer;
  readonly steps: readonly StepRow[] | undefined;
  readonly focusOnArrival: boolean;
  readonly onShown: (answer: StepAnswer) => void;
}) {
  const { groupId } = group;
  const standing = useStandingRead().value;
  const now = useCalendarNow();
  const { search } = useLocation();
  const cameOutId = useId();
  const [askOut, setAskOut] = useState(false);
  const [answerOut, setAnswerOut] = useState(false);
  const [lengthRefusal, setLengthRefusal] = useState<Problem | null>(null);
  const { run, step } = page;
  const [parameters] = useSearchParams();
  const searchTry = searchWaitingOnReader(step, page, standing, groupId);
  const pickedSearch =
    searchTry !== undefined &&
    searchTry.number === Number(parameters.get(PICKED_TRY) ?? Number.NaN);
  const declared = declaredFor(step, page.declarations);
  const [, ...why] = whereSaid(step, declared?.gives, group, now, search);
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
      <TryRead
        group={group}
        runId={run.runId}
        stepId={step.stepId}
        shown={page.triesMade.find(
          (aTry) =>
            aTry.number === Number(parameters.get(PICKED_TRY) ?? Number.NaN),
        )}
        waiting={searchTry}
      >
        <SearchReviewTry.Provider
          value={pickedSearch ? null : (searchTry?.number ?? null)}
        >
          <TwoColumns
            panelTitle={say("run.aboutIt")}
            wideAtLeast={640}
            wide={
              <>
                {heldBack ? (
                  <Notice severity="warning">
                    {why.map((line, at) => (
                      <Fragment key={at}>
                        {at === 0 ? null : " "} {line}
                      </Fragment>
                    ))}
                    {turnedAway === undefined ? null : (
                      <Turnaways turnedAway={turnedAway} />
                    )}
                  </Notice>
                ) : null}
                {lengthRefusal === null ? null : (
                  <ProblemView problem={lengthRefusal} />
                )}
                {step.length === undefined ? null : (
                  <LengthPanel
                    length={step.length}
                    group={group}
                    declaring={declaring}
                    stepId={step.stepId}
                    onShown={onShown}
                    onRefused={setLengthRefusal}
                  />
                )}
                {withheldSaid(step, declaring, standing, groupId).map(
                  (line) => (
                    <Typography key={line} variant="body2">
                      {line}
                    </Typography>
                  ),
                )}
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
                status={
                  <>
                    <StepStateBadge state={step.state} />
                    {(heldBack ? [] : why).map((line, at) => (
                      <StateNote key={at}>{line}</StateNote>
                    ))}
                  </>
                }
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
                      {runsSaid(group, step.runs)}
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
                      <Field label={say("step.subRun")}>
                        <BeneathLinks group={group} beneath={step.beneath} />
                      </Field>
                    )}
                    {step.tries === undefined ||
                    step.tries.current < 1 ? null : (
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
                    <Field label={say("run.usage")}>
                      <CostDrawn cost={step.cost} />
                    </Field>
                  </>
                }
              />
            }
          />
        </SearchReviewTry.Provider>
      </TryRead>
    </>
  );
}

function runsSaid(group: GroupStanding, runs: StepRuns): ReactNode {
  const { kind, entryId, name, version, versionId, codeStep } = runs;
  if (
    (kind === "question" || kind === "workflow" || kind === "search") &&
    entryId !== undefined &&
    name !== undefined &&
    version !== undefined
  ) {
    return (
      <>
        {kind === "search" && runs.searchWay !== undefined ? (
          say("step.runsSearch", {
            way: isolatedInText(spokenName(runs.searchWay)),
          })
        ) : (
          <StepKindTag kind={kind} />
        )}{" "}
        <Link
          component={RouterLink}
          to={entryHref(
            group,
            LIBRARY_KINDS[kind === "search" ? "question" : kind],
            entryId,
            versionId,
          )}
        >
          {visible("run.workflowVersion", {
            label: (
              <VersionLabel
                entry={name}
                number={version}
                name={runs.versionName}
              />
            ),
          })}
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

/** The picked value and try live in the URL so a link can reopen them. */
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
  const group = useGroup();
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
        <ProseScope>
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
                  {each.standing === undefined ? (
                    <ValueStatusChip status={each.now} />
                  ) : (
                    <FilledValue
                      cards
                      field={fieldAt(gives, each.field)}
                      label={labelAt(gives, each.field)}
                      shown={each.standing}
                      shut={false}
                      status={each.now}
                      foot={
                        each.now === "none" ? (
                          <Typography variant="body2">
                            {standingSaid(each.now)}
                          </Typography>
                        ) : undefined
                      }
                    />
                  )}
                  {each.standing === undefined && each.now === "none" ? (
                    <Typography variant="body2">
                      {standingSaid(each.now)}
                    </Typography>
                  ) : null}
                  {each.fromRun === undefined ? null : (
                    <Link
                      component={RouterLink}
                      to={`${runHref(group, each.fromRun.runId)}${listQuery(search)}`}
                    >
                      {say("run.title", { number: each.fromRun.number })}
                    </Link>
                  )}
                  {cameOut.length > 1 ? (
                    <Press
                      tier="quiet"
                      aria-pressed={each.field === field}
                      onPress={() => picking(each.field, null)}
                    >
                      {say("step.seeTries", {
                        field: labelAt(gives, each.field),
                      })}
                    </Press>
                  ) : null}
                </Field>
              ))}
            </Fields>
          )}
        </ProseScope>
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
          group={group}
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
