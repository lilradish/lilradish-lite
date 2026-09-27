import Box from "@mui/material/Box";
import Button from "@mui/material/Button";
import Link from "@mui/material/Link";
import Typography from "@mui/material/Typography";
import {
  useId,
  useLayoutEffect,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from "react";
import { Link as RouterLink, useLocation, useParams } from "react-router";

import {
  offers,
  refusedForWhatMoved,
  type OwnCeiling,
  type Run,
  type RunAct,
} from "../../api/groups/{groupId}/runs/{runId}";
import {
  decideRaise,
  type RaiseDecision,
} from "../../api/groups/{groupId}/runs/{runId}/ceiling-changes/{changeId}";
import type {
  RunSteps,
  StepRow,
} from "../../api/groups/{groupId}/runs/{runId}/steps";
import type { StepAnswer } from "../../api/groups/{groupId}/runs/{runId}/steps/{stepId}";
import {
  openRunAgain,
  stopRun,
} from "../../api/groups/{groupId}/runs/{runId}/stop";
import { Async } from "../../app/Async";
import { ProblemView } from "../../app/ProblemView";
import type { Rule } from "../../app/standing/actRules";
import { useActedOn } from "../../app/useActedOn";
import { say, type MessageId } from "../../i18n/app";
import { ActButton } from "../../lib/action/ActButton";
import { Press } from "../../lib/action/Press";
import { isolatedInText } from "../../lib/direction/isolated";
import { Field, Fields } from "../../lib/form/Fields";
import { PageHeading } from "../../lib/heading/PageHeading";
import { ACTS_SX, HEADER_ACTS_SX, QUIET_SX } from "../../lib/layout/parts";
import type { Action } from "../../lib/request/useAction";
import type { Resource } from "../../lib/request/useResource";
import { nameOrNumber } from "../../lib/text/names";
import { whenText } from "../../lib/time/When";
import { entryHref } from "../library/entryAddress";
import { LIBRARY_KINDS } from "../library/libraryKinds";
import { RUN_ACT_RULES } from "./actRules";
import { ChangeCeiling } from "./ChangeCeiling";
import { RenameRun } from "./RenameRun";
import { listQuery, runHref, useArrivedWithinTheRun } from "./runAddress";
import { runWhereSaid, stateWorded } from "./runStates";
import { countSaid, Spent } from "./spend";
import { RunConversation } from "./steps/RunConversation";
import { RunInDetail } from "./steps/RunInDetail";
import { RunMore } from "./steps/RunMore";
import { StateNews, type Stated } from "./steps/StateNews";
import { stepStateWorded } from "./steps/stepStates";
import { spokenName, stepTitle } from "./steps/stepTitle";
import { useRunRead, type RunReading } from "./steps/useRunRead";

/** The words each act on a run is drawn with, closed over the acts in both directions. */
const RUN_ACTS = {
  stop: "run.stop",
  open_again: "run.openAgain",
  rename: "run.rename",
  change_ceiling: "run.changeCeiling",
  approve_raise: "raise.approve",
  refuse_raise: "raise.refuse",
  withdraw_raise: "raise.withdraw",
} as const satisfies Record<RunAct, MessageId>;

/** Each act deciding a raise, in the order drawn, with what it asks of the server. */
const DECISIONS = [
  ["approve_raise", "approval"],
  ["refuse_raise", "refusal"],
  ["withdraw_raise", "withdrawal"],
] as const satisfies readonly (readonly [RunAct, RaiseDecision])[];

/** In detail, or as a conversation, which offers the way to the detail from within it. */
type Drawn =
  | { readonly drawing: "detail" }
  | { readonly drawing: "conversation"; readonly onDetail: () => void };

/** The header a conversation draws in place of the detail's, and what it draws it from. */
interface Conversing {
  readonly steps: RunSteps | null;
  readonly onDetail: () => void;
}

/**
 * One run with its steps, drawn as Work is: in detail, or as a conversation. The run and its steps are read
 * together, and both read again once an act on the run, or on one of its steps, has answered; `onActed` is told.
 */
export function RunPage({
  focusOnArrival,
  onActed,
  ...drawn
}: Drawn & {
  /** Where it was begun by an act whose control is gone: the keyboard starts again at its name. */
  readonly focusOnArrival?: boolean;
  readonly onActed: () => void;
}) {
  const { groupId = "", runId = "" } = useParams();
  const arrived = useArrivedWithinTheRun();
  const reading = useRunRead(groupId, runId);
  const [acted, setActed] = useState<Acted | null>(null);
  // Kept while neither changes: `StateNews` takes a new object for a new read, and empties what it was saying.
  const read = useMemo(
    () => shownWith(reading.value, acted),
    [reading.value, acted],
  );
  const answered = () => {
    reading.quietly();
    onActed();
  };
  const stepShown = (answer: StepAnswer) => {
    setActed({ over: reading.value, answer });
    answered();
  };
  return (
    <>
      <StateNews
        reading={read}
        statesOf={
          drawn.drawing === "detail" ? STATES_IN_DETAIL : STATES_IN_CONVERSATION
        }
      />
      <RunHeader
        read={{ ...reading, value: read?.run ?? null }}
        focusOnArrival={focusOnArrival === true || arrived}
        onAnswered={answered}
        conversation={
          drawn.drawing === "detail"
            ? undefined
            : { steps: read?.steps ?? null, onDetail: drawn.onDetail }
        }
      >
        {read === null ? null : drawn.drawing === "detail" ? (
          <RunInDetail groupId={groupId} reading={read} />
        ) : (
          <RunConversation
            groupId={groupId}
            reading={read}
            onShown={stepShown}
          />
        )}
      </RunHeader>
    </>
  );
}

/** A step as an act on it answered, over the read it was answered on. */
interface Acted {
  readonly over: RunReading | null;
  readonly answer: StepAnswer;
}

/**
 * The read with a step's answer in place of its row and the answer's header in place of the read's, until the run
 * is read again: the run itself only a read of the run can say.
 */
function shownWith(
  read: RunReading | null,
  acted: Acted | null,
): RunReading | null {
  if (read === null || acted === null || acted.over !== read) {
    return read;
  }
  const { answer } = acted;
  return {
    run: read.run,
    steps: {
      ...read.steps,
      run: answer.run,
      steps: read.steps.steps.map((each) =>
        each.stepId === answer.step.stepId ? answer.step : each,
      ),
    },
  };
}

/** The run by the name its heading gives it, then each step by the name the drawing gives it. */
function statesOfRun(
  stepNamed: (step: StepRow) => string,
): (reading: RunReading) => Stated[] {
  return ({ run, steps }) => [
    {
      key: run.runId,
      name:
        run.name === undefined
          ? say("run.title", { number: run.number })
          : isolatedInText(run.name),
      state: stateWorded(run.state),
    },
    ...steps.steps.map((step) => ({
      key: step.stepId,
      name: isolatedInText(stepNamed(step)),
      state: stepStateWorded(step.state),
    })),
  ];
}

const STATES_IN_DETAIL = statesOfRun((step) => spokenName(step.name));

const STATES_IN_CONVERSATION = statesOfRun(stepTitle);

/**
 * One run's header: every control drawn from the acts the server offers, every act shown as the run it answers
 * with, and a refusal saying the reader or the run moved reading again whichever did. As a conversation it says
 * where the run is and how far it has got, and keeps the rest behind More.
 */
function RunHeader({
  read,
  focusOnArrival,
  onAnswered,
  conversation,
  children,
}: {
  readonly read: Resource<Run | null>;
  readonly focusOnArrival: boolean;
  readonly onAnswered?: () => void;
  readonly conversation?: Conversing;
  /** What is drawn under the header, while the run is. */
  readonly children?: ReactNode;
}) {
  const { groupId = "" } = useParams();
  const search = listQuery(useLocation().search);
  const ceilingId = useId();
  const moreId = useId();
  const ceilingPart = useRef<HTMLDivElement>(null);
  const moreControl = useRef<HTMLButtonElement>(null);
  const answeredLast = useRef(false);
  const { shown, changing } = useActedOn(read, refusedForWhatMoved, {
    answered: () => {
      answeredLast.current = true;
      onAnswered?.();
    },
  });
  const [asked, setAsked] = useState<RunAct | null>(null);
  const [opened, setOpened] = useState<{
    readonly dialog: "rename" | "ceiling";
    readonly count: number;
  } | null>(null);
  const [dialogOpen, setDialogOpen] = useState(false);
  const [moreOpen, setMoreOpen] = useState(false);

  // An act's answer may take its control with it, so the keyboard starts again at the ceiling, or at More where
  // that keeps the ceiling shut; only once, as a read again later is no act of the reader's.
  useLayoutEffect(() => {
    if (!answeredLast.current) {
      return;
    }
    answeredLast.current = false;
    if (document.activeElement === document.body) {
      (ceilingPart.current ?? moreControl.current)?.focus();
    }
  }, [shown]);

  const refusedAt = (acts: readonly RunAct[], rule?: Rule) =>
    asked !== null && acts.includes(asked) && changing.problem !== null ? (
      <ProblemView problem={changing.problem} rule={rule} />
    ) : null;

  const opening = (dialog: "rename" | "ceiling", act: RunAct) => {
    setAsked(act);
    setOpened((last) => ({ dialog, count: (last?.count ?? 0) + 1 }));
    setDialogOpen(true);
  };

  return (
    <Async read={{ ...read, value: shown }} empty={() => null}>
      {(run) => {
        if (run === null) {
          return null;
        }
        const switchedBy = offers(run, "stop") ? "stop" : "open_again";
        const own = "heldBy" in run.ceiling ? null : run.ceiling;
        const renaming = offers(run, "rename") ? (
          <Press
            unavailable={changing.running}
            onPress={() => opening("rename", "rename")}
          >
            {say(RUN_ACTS.rename)}
          </Press>
        ) : null;
        const ceilingLabel = <span id={ceilingId}>{say("run.ceiling")}</span>;
        const ceiling = (
          <Box
            ref={ceilingPart}
            role="group"
            aria-labelledby={ceilingId}
            tabIndex={-1}
          >
            {own === null && "heldBy" in run.ceiling
              ? runLink(
                  groupId,
                  run.ceiling.heldBy.runId,
                  search,
                  say("run.heldBy", {
                    number: run.ceiling.heldBy.number,
                  }),
                )
              : null}
            {own === null ? null : (
              <OwnCeilingPart
                run={run}
                own={own}
                changing={changing}
                onAsked={setAsked}
                onChange={() => opening("ceiling", "change_ceiling")}
                groupId={groupId}
              />
            )}
            {refusedAt(["approve_raise", "refuse_raise"], {
              inGroup: RUN_ACT_RULES.approve_raise,
            })}
            {refusedAt(["withdraw_raise"], {
              inGroup: RUN_ACT_RULES.withdraw_raise,
            })}
          </Box>
        );
        return (
          <>
            <PageHeading
              level={2}
              focusOnArrival={focusOnArrival}
              title={
                run.name === undefined
                  ? say("run.title", { number: run.number })
                  : isolatedInText(run.name)
              }
              actions={
                <Box sx={HEADER_ACTS_SX}>
                  {offers(run, "stop") || offers(run, "open_again") ? (
                    <ActButton
                      action={changing}
                      act={(signal) => {
                        setAsked(switchedBy);
                        return (switchedBy === "stop" ? stopRun : openRunAgain)(
                          groupId,
                          run.runId,
                          signal,
                        );
                      }}
                    >
                      {say(RUN_ACTS[switchedBy])}
                    </ActButton>
                  ) : null}
                  {conversation === undefined ? (
                    renaming
                  ) : (
                    <Button
                      ref={moreControl}
                      aria-expanded={moreOpen}
                      aria-controls={moreId}
                      onClick={() => setMoreOpen((was) => !was)}
                    >
                      {say("step.more")}
                    </Button>
                  )}
                </Box>
              }
            />
            {refusedAt(["stop", "open_again"], {
              inGroup: RUN_ACT_RULES[switchedBy],
            })}
            {conversation === undefined ? (
              <Fields>
                <Field label={say("run.number")}>{run.number}</Field>
                <Field label={say("run.workflow")}>
                  <Link
                    component={RouterLink}
                    to={entryHref(
                      groupId,
                      LIBRARY_KINDS.workflow,
                      run.workflow.entryId,
                    )}
                  >
                    {say("run.workflowVersion", {
                      name: isolatedInText(run.workflow.name),
                      version: run.workflow.version,
                    })}
                  </Link>
                </Field>
                <Field label={say("run.started")}>
                  {run.startedBy === undefined
                    ? say("run.startedAbove", {
                        when: whenText(run.startedAt),
                      })
                    : say("run.startedBy", {
                        name: isolatedInText(nameOrNumber(run.startedBy)),
                        when: whenText(run.startedAt),
                      })}
                </Field>
                {run.above === undefined ? null : (
                  <Field label={say("run.above")}>
                    {runLink(groupId, run.above, search, say("run.openAbove"))}
                  </Field>
                )}
                <Field label={say("run.where")}>{runWhereSaid(run)}</Field>
                <Field label={say("run.cost")}>
                  <Spent spend={run.spend} />
                </Field>
                <Field label={ceilingLabel}>{ceiling}</Field>
              </Fields>
            ) : (
              <>
                <Typography variant="body1">
                  {whereNowSaid(run, conversation.steps)}
                </Typography>
                {conversation.steps === null ? null : (
                  <Typography variant="body2" sx={QUIET_SX}>
                    {say("step.progress", {
                      done: conversation.steps.run.progress.done,
                      of: conversation.steps.run.progress.of,
                    })}
                  </Typography>
                )}
                <RunMore
                  id={moreId}
                  open={moreOpen}
                  spend={run.spend}
                  ceilingLabel={ceilingLabel}
                  ceiling={ceiling}
                  renaming={renaming}
                  onDetail={conversation.onDetail}
                />
              </>
            )}
            {children}
            {opened?.dialog === "rename" ? (
              <RenameRun
                key={opened.count}
                groupId={groupId}
                run={run}
                open={dialogOpen}
                changing={changing}
                onShut={() => setDialogOpen(false)}
              />
            ) : null}
            {opened?.dialog === "ceiling" && own !== null ? (
              <ChangeCeiling
                key={opened.count}
                groupId={groupId}
                runId={run.runId}
                ceiling={own}
                open={dialogOpen}
                changing={changing}
                onShut={() => setDialogOpen(false)}
              />
            ) : null}
          </>
        );
      }}
    </Async>
  );
}

/** The ceiling the run holds of its own, any raise waiting on it, and what may be done to either. */
function OwnCeilingPart({
  groupId,
  run,
  own,
  changing,
  onAsked,
  onChange,
}: {
  readonly groupId: string;
  readonly run: Run;
  readonly own: OwnCeiling;
  readonly changing: Action<Run | null>;
  readonly onAsked: (act: RunAct) => void;
  readonly onChange: () => void;
}) {
  const waiting = own.waiting;
  return (
    <>
      <Typography variant="body1">
        {own.inForce === undefined
          ? say("run.noCeiling")
          : countSaid(own.inForce)}
      </Typography>
      {waiting === undefined ? null : (
        <Typography variant="body2" sx={QUIET_SX}>
          {waiting.to === undefined
            ? say("raise.away", {
                name: isolatedInText(nameOrNumber(waiting.askedBy)),
                when: whenText(waiting.askedAt),
              })
            : say("raise.to", {
                name: isolatedInText(nameOrNumber(waiting.askedBy)),
                when: whenText(waiting.askedAt),
                to: countSaid(waiting.to),
              })}
        </Typography>
      )}
      <Box sx={ACTS_SX}>
        {offers(run, "change_ceiling") ? (
          <Press unavailable={changing.running} onPress={onChange}>
            {say(RUN_ACTS.change_ceiling)}
          </Press>
        ) : null}
        {waiting === undefined
          ? null
          : DECISIONS.filter(([act]) => offers(run, act)).map(
              ([act, decision]) => (
                <ActButton
                  key={act}
                  action={changing}
                  act={(signal) => {
                    onAsked(act);
                    return decideRaise(
                      groupId,
                      run.runId,
                      waiting.changeId,
                      decision,
                      signal,
                    );
                  }}
                >
                  {say(RUN_ACTS[act])}
                </ActButton>
              ),
            )}
      </Box>
    </>
  );
}

/** A link to another run of the group, which opens where this one is, the list's address kept. */
function runLink(
  groupId: string,
  runId: string,
  search: string,
  words: string,
): ReactNode {
  return (
    <Link component={RouterLink} to={`${runHref(groupId, runId)}${search}`}>
      {words}
    </Link>
  );
}

/** Where it is; while it runs, on which step, by the title that step's message has. */
function whereNowSaid(run: Run, steps: RunSteps | null): string {
  const at = run.at;
  if (
    at === undefined ||
    run.stopped !== undefined ||
    run.state !== "running"
  ) {
    return runWhereSaid(run);
  }
  const row = steps?.steps.find((each) => each.stepId === at.stepId);
  return say("runState.runningAt", {
    step: isolatedInText(
      row === undefined ? spokenName(at.name) : stepTitle(row),
    ),
  });
}
