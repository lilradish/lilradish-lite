import Box from "@mui/material/Box";
import Button from "@mui/material/Button";
import Link from "@mui/material/Link";
import Typography from "@mui/material/Typography";
import { useCallback, useEffect, useId, useState, type ReactNode } from "react";
import {
  Link as RouterLink,
  useLocation,
  useParams,
  useSearchParams,
} from "react-router";

import type {
  StepRow,
  StepRuns,
} from "../../../api/groups/{groupId}/runs/{runId}/steps";
import {
  readStep,
  type StepAnswer,
} from "../../../api/groups/{groupId}/runs/{runId}/steps/{stepId}";
import { Async } from "../../../app/Async";
import { movesTheReader } from "../../../app/refusal";
import { useStandingRead } from "../../../app/standing/StandingContext";
import { say } from "../../../i18n/app";
import { isolatedInText } from "../../../lib/direction/isolated";
import { Field, Fields } from "../../../lib/form/Fields";
import { PageHeading } from "../../../lib/heading/PageHeading";
import { QUIET_SX } from "../../../lib/layout/parts";
import { Notice } from "../../../lib/notice/Notice";
import { entryHref } from "../../library/entryAddress";
import { LIBRARY_KINDS } from "../../library/libraryKinds";
import { FilledValue } from "../fill/FilledValue";
import {
  listQuery,
  PICKED_TRY,
  PICKED_VALUE,
  runHref,
  useArrivedWithinTheRun,
  WITHIN_THE_RUN,
} from "../runAddress";
import { stateWorded } from "../runStates";
import { CostDrawn } from "../spend";
import { AnswerHere } from "./AnswerHere";
import { AskAgainButton } from "./AskAgainButton";
import { declaredFor, fieldAt, labelAt, type Declaring } from "./declared";
import { ReviewControls } from "./ReviewControls";
import { StateNews, type Stated } from "./StateNews";
import {
  standingSaid,
  stepStateWorded,
  whereSaid,
  whoSaid,
  withheldSaid,
} from "./stepStates";
import { spokenName, stepTitle } from "./stepTitle";
import { TriesList } from "./TriesList";
import { TrySendingButton } from "./TrySendingButton";
import { heldTry, Turnaways } from "./Turnaways";
import { useKeptUp } from "./useRunRead";
import { WentInValues } from "./WentInValues";

const BACK_SX = { display: "inline-block", mb: 1 };

const PART_SX = { mt: 3 };

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

/**
 * One step of one run, in the run's place: a way back to the run first, then the step, and the review it offers
 * the reader. The step an act answers with is shown until the step is read again, which it is at once, a read
 * already out let go; and `onActed` is told.
 */
export function StepPage({ onActed }: { readonly onActed: () => void }) {
  const { groupId = "", runId = "", stepId = "" } = useParams();
  const { search } = useLocation();
  const reloadStanding = useStandingRead().reload;
  const load = useCallback(
    (signal: AbortSignal): Promise<StepAnswer | null> =>
      readStep(groupId, runId, stepId, signal),
    [groupId, runId, stepId],
  );
  const kept = useKeptUp(load, waitOfStep);
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
    onActed();
  };
  const arriving = useArrivedWithinTheRun();

  useEffect(() => {
    if (read.problem !== null && movesTheReader(read.problem)) {
      reloadStanding();
    }
  }, [read.problem, reloadStanding]);

  return (
    <>
      <StateNews reading={read.value} statesOf={statesOfStep} />
      <Link
        component={RouterLink}
        to={`${runHref(groupId, runId)}${listQuery(search)}`}
        state={WITHIN_THE_RUN}
        sx={BACK_SX}
      >
        {say("step.back")}
      </Link>
      <Async read={read} empty={() => null}>
        {(page) =>
          page === null ? null : (
            <StepDrawn
              groupId={groupId}
              page={page}
              focusOnArrival={arriving}
              onShown={shown}
            />
          )
        }
      </Async>
    </>
  );
}

function StepDrawn({
  groupId,
  page,
  focusOnArrival,
  onShown,
}: {
  readonly groupId: string;
  readonly page: StepAnswer;
  readonly focusOnArrival: boolean;
  readonly onShown: (answer: StepAnswer) => void;
}) {
  const cameOutId = useId();
  const [askOut, setAskOut] = useState(false);
  const [answerOut, setAnswerOut] = useState(false);
  const { run, step } = page;
  const declared = declaredFor(step, page.declarations);
  const [state, ...why] = whereSaid(step, declared?.gives);
  const heldBack = step.state === "held_back" && why.length > 0;
  const declaring: Declaring = {
    declarations: page.declarations,
    runVersionId: run.versionId,
  };
  const turnedAway = step.where?.turnedAway;
  return (
    <>
      <PageHeading
        level={2}
        focusOnArrival={focusOnArrival}
        title={isolatedInText(stepTitle(step))}
      />
      {heldBack ? (
        <Notice severity="warning">
          {why.join(" ")}
          {turnedAway === undefined ? null : (
            <Turnaways turnedAway={turnedAway} />
          )}
        </Notice>
      ) : null}
      <Fields>
        <Field label={say("work.run")}>
          {say("run.title", { number: run.number })}
        </Field>
        <Field label={say("step.order")}>
          {say("step.orderOf", { order: step.order, of: run.progress.of })}
        </Field>
        <Field label={say("step.runs")}>{runsSaid(groupId, step.runs)}</Field>
        <Field label={say("step.producedBy")}>
          {step.producer === undefined
            ? say("step.producesNothing")
            : whoSaid(step.producer)}
        </Field>
        <Field label={say("step.reviewedBy")}>{reviewerSaid(step)}</Field>
        <Field label={say("run.where")}>
          {state}
          {(heldBack ? [] : why).map((line, at) => (
            <Typography key={at} variant="body2" sx={QUIET_SX}>
              {line}
            </Typography>
          ))}
        </Field>
        {step.tries === undefined ? null : (
          <Field label={say("step.tries")}>
            {say(step.tries.beyond ? "step.tryBeyond" : "step.tryOf", {
              number: step.tries.current,
              allowed: step.tries.declared,
            })}
          </Field>
        )}
        <Field label={say("step.cost")}>
          <CostDrawn cost={step.cost} />
        </Field>
      </Fields>
      {withheldSaid(step).map((line) => (
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
        heading="h3"
        landsOn={cameOutId}
        onShown={onShown}
      />
      <WentIn page={page} />
      <ReviewControls
        groupId={groupId}
        runId={run.runId}
        step={step}
        declaring={declaring}
        heading="h3"
        landsOn={cameOutId}
        onShown={onShown}
      />
      <CameOut page={page} headingId={cameOutId} />
    </>
  );
}

/** A question or a workflow at the version pinned, opening it; code by its name; or a route. */
function runsSaid(groupId: string, runs: StepRuns): ReactNode {
  const { kind, entryId, name, version, versionId, codeStep } = runs;
  if (
    (kind === "question" || kind === "workflow") &&
    entryId !== undefined &&
    name !== undefined &&
    version !== undefined
  ) {
    return (
      <Link
        component={RouterLink}
        to={entryHref(groupId, LIBRARY_KINDS[kind], entryId, versionId)}
      >
        {say("run.workflowVersion", { name: isolatedInText(name), version })}
      </Link>
    );
  }
  if (kind === "code_step" && codeStep !== undefined) {
    return say("step.runsCode", {
      name: isolatedInText(spokenName(codeStep)),
    });
  }
  return say(kind === "route" ? "step.runsRoute" : "step.unknown");
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
function WentIn({ page }: { readonly page: StepAnswer }) {
  const headingId = useId();
  const { step, wentIn } = page;
  const takes = declaredFor(step, page.declarations)?.takes;
  const declaring: Declaring = {
    declarations: page.declarations,
    runVersionId: page.run.versionId,
  };
  return (
    <Box component="section" aria-labelledby={headingId} sx={PART_SX}>
      <Typography id={headingId} variant="h6" component="h3">
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
          <WentInValues wentIn={wentIn} takes={takes} declaring={declaring} />
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
  headingId,
}: {
  readonly page: StepAnswer;
  /** Where the keyboard goes once a review it asked for is answered. */
  readonly headingId: string;
}) {
  const triesId = useId();
  const [parameters, setParameters] = useSearchParams();
  const { step, cameOut } = page;
  const gives = declaredFor(step, page.declarations)?.gives;
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
      <Box component="section" aria-labelledby={headingId} sx={PART_SX}>
        <Typography id={headingId} tabIndex={-1} variant="h6" component="h3">
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
                {cameOut.length > 1 ? (
                  <Button
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
      <Box component="section" aria-labelledby={triesId} sx={PART_SX}>
        <Typography id={triesId} variant="h6" component="h3">
          {field === undefined
            ? say("step.theTries")
            : say("step.triesOfValue", { field: labelAt(gives, field) })}
        </Typography>
        <TriesList
          key={field}
          tries={page.triesMade}
          allowed={step.tries?.declared}
          heldTry={heldTry(step, page.triesMade)}
          field={field}
          gives={gives}
          labelledBy={triesId}
          picked={Number.isSafeInteger(tryNumber) ? tryNumber : null}
          onPick={(number) => picking(field, number)}
        />
      </Box>
    </>
  );
}
