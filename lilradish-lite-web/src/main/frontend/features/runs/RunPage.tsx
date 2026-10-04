import Box from "@mui/material/Box";
import Link from "@mui/material/Link";
import LinearProgress from "@mui/material/LinearProgress";
import Typography from "@mui/material/Typography";
import {
  useId,
  useLayoutEffect,
  useMemo,
  useRef,
  useState,
  type ReactNode,
  type RefObject,
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
import { groupPage, WORK_ITEM } from "../../app/destinations";
import { ProblemView } from "../../app/ProblemView";
import type { Rule } from "../../app/standing/actRules";
import { useActedOn } from "../../app/useActedOn";
import { say, type MessageId } from "../../i18n/app";
import { visible } from "../../i18n/visible";
import { ActButton } from "../../lib/action/ActButton";
import { Press } from "../../lib/action/Press";
import { useKeptChoice } from "../../lib/device/useKeptChoice";
import { isolatedInText } from "../../lib/direction/isolated";
import { Field } from "../../lib/form/Fields";
import { PageHeading } from "../../lib/heading/PageHeading";
import { LEAST_TABLE_WIDTH } from "../../lib/collection/RowPanel";
import { ACTS_SX, QUIET_SX } from "../../lib/layout/parts";
import { TwoColumns } from "../../lib/layout/TwoColumns";

import { Breadcrumbs, type Crumb } from "../../lib/navigation/Breadcrumbs";
import type { Action } from "../../lib/request/useAction";
import type { Resource } from "../../lib/request/useResource";
import { nameOrNumber } from "../../lib/text/names";
import { SystemHint } from "../../lib/text/HiddenShownIn";
import { useCalendarNow, whenText } from "../../lib/time/When";
import { entryHref } from "../library/entryAddress";
import { LIBRARY_KINDS } from "../library/libraryKinds";
import { AboutIt } from "./AboutIt";
import { RUN_ACT_RULES } from "./actRules";
import { CurrentFileRunContext } from "./fill/CurrentFileRun";
import { ChangeCeiling } from "./ChangeCeiling";
import { RenameRun } from "./RenameRun";
import { RunReadAccess, useRunReadAccess } from "./RunReadAccess";
import { RunStateBadge } from "./RunStateBadge";
import {
  historyQuery,
  listQuery,
  runHref,
  stepHref,
  WITHIN_THE_RUN,
} from "./runAddress";
import { runWhereSaid, stateWorded } from "./runStates";
import { countSaid, Spent } from "./spend";
import { messageNamed, RunConversation } from "./steps/RunConversation";
import { RunInFullDetail } from "./steps/RunInFullDetail";
import { StepsSoFar } from "./steps/StepsSoFar";

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

const KEPT_DRAWING_AS = "lilradish.run.drawing";

/** Conversation first, as a run is drawn on a device that has kept no way of drawing one. */
const RUN_DRAWINGS = ["conversation", "fullDetail"] as const;

type RunDrawing = (typeof RUN_DRAWINGS)[number];

/** Each drawing's way to the other, and the words it is drawn with. */
const OTHER_DRAWING = {
  conversation: ["fullDetail", "step.showFullDetail"],
  fullDetail: ["conversation", "step.showAsConversation"],
} as const satisfies Record<RunDrawing, readonly [RunDrawing, MessageId]>;

export function RunPage() {
  const { groupId = "", runId = "" } = useParams();
  return (
    <RunReadAccess key={`${groupId}/${runId}`}>
      <RunPageWithin groupId={groupId} runId={runId} />
    </RunReadAccess>
  );
}

function RunPageWithin({
  groupId,
  runId,
}: {
  readonly groupId: string;
  readonly runId: string;
}) {
  const { hash } = useLocation();
  const access = useRunReadAccess();
  const [drawing, setDrawing] = useKeptChoice(KEPT_DRAWING_AS, RUN_DRAWINGS);
  // Only a conversation draws a step's message to take the keyboard; the run's name takes it wherever none is drawn.
  const [aimedAt] = useState(drawing === "conversation" ? hash : "");
  const reading = useRunRead(groupId, runId);
  const [acted, setActed] = useState<Acted | null>(null);
  // Kept while neither changes: `StateNews` takes a new object for a new read, and empties what it was saying.
  const read = useMemo(
    () => shownWith(reading.value, acted),
    [reading.value, acted],
  );
  const stepShown = (answer: StepAnswer) => {
    setActed({ over: reading.value, answer });
    reading.quietly();
  };
  if (access.problem !== null) {
    return (
      <>
        <RunPath run={null} />
        <ProblemView problem={access.problem} />
      </>
    );
  }
  return (
    <CurrentFileRunContext.Provider
      value={read === null ? null : { groupId, runId: read.run.runId }}
    >
      <RunPath run={read?.run ?? null} />
      <StateNews
        reading={read}
        statesOf={
          drawing === "fullDetail"
            ? STATES_IN_FULL_DETAIL
            : STATES_IN_CONVERSATION
        }
      />
      <RunDrawn
        read={{ ...reading, value: read?.run ?? null }}
        steps={read?.steps ?? null}
        focusOnArrival={
          read === null || messageNamed(read.steps.steps, aimedAt) === undefined
        }
        onAnswered={reading.quietly}
        drawing={drawing}
        onDrawing={setDrawing}
      >
        {read === null ? null : drawing === "fullDetail" ? (
          <RunInFullDetail groupId={groupId} reading={read} />
        ) : (
          <RunConversation
            groupId={groupId}
            reading={read}
            onShown={stepShown}
          />
        )}
      </RunDrawn>
    </CurrentFileRunContext.Provider>
  );
}

/**
 * The way from a run, or one of its steps, back through every run above it to History as it was filtered and sorted,
 * and to Work. Work and History are drawn before the run is read, and all a refused run is drawn with.
 */
export function RunPath({
  run,
  step,
}: {
  readonly run: Run | null;
  /** What the step is called on a step's page, none while it is read; absent on the run's own page. */
  readonly step?: string | null;
}) {
  const { groupId = "" } = useParams();
  const search = listQuery(useLocation().search);
  const work = groupPage(groupId, WORK_ITEM.segment);
  const trail: Crumb[] = [
    { label: say(WORK_ITEM.label), path: work, search: "" },
    { label: say("work.history"), path: work, search: historyQuery(search) },
  ];
  if (run === null) {
    return <Breadcrumbs trail={trail} />;
  }
  const runs = [
    ...run.runsAbove.map((above) => ({
      runId: above.runId,
      title: runTitle(above.name, above.number),
    })),
    { runId: run.runId, title: runTitle(run.name, run.number) },
  ];
  const crumbs = runs.map(({ runId, title }) => ({
    label: title,
    path: runHref(groupId, runId),
    search,
  }));
  return step === undefined ? (
    <Breadcrumbs
      trail={[...trail, ...crumbs.slice(0, -1)]}
      current={crumbs.at(-1)!.label}
    />
  ) : (
    <Breadcrumbs trail={[...trail, ...crumbs]} current={step ?? undefined} />
  );
}

/** A run by its name, or by its number where it has none, as a run beneath has. */
function runTitle(name: string | undefined, number: number): string {
  return name === undefined
    ? say("run.title", { number })
    : isolatedInText(name);
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
      name: runTitle(run.name, run.number),
      state: stateWorded(run.state),
    },
    ...steps.steps.map((step) => ({
      key: step.stepId,
      name: isolatedInText(stepNamed(step)),
      state: stepStateWorded(step.state),
    })),
  ];
}

/** Each step by the name a drawing gives it: as The steps names it, or as its message is titled. */
const STEP_NAMED = {
  conversation: stepTitle,
  fullDetail: (step: StepRow) => spokenName(step.name),
} as const satisfies Record<RunDrawing, (step: StepRow) => string>;

const STATES_IN_FULL_DETAIL = statesOfRun(STEP_NAMED.fullDetail);

const STATES_IN_CONVERSATION = statesOfRun(STEP_NAMED.conversation);

/**
 * One run under its name, what the drawing holds wide on the leading side, and About it narrow on the trailing side,
 * the same in either drawing: every control drawn from the acts the server offers, every act shown as the run it
 * answers with, and a refusal saying the reader or the run moved reading again whichever did.

 */
function RunDrawn({
  read,
  steps,
  focusOnArrival,
  onAnswered,
  drawing,
  onDrawing,

  children,
}: {
  readonly read: Resource<Run | null>;
  /** None while they are read. */
  readonly steps: RunSteps | null;
  readonly focusOnArrival: boolean;
  readonly onAnswered: () => void;
  readonly drawing: RunDrawing;
  readonly onDrawing: (next: RunDrawing) => void;
  /** What the drawing holds, while the run is drawn. */

  readonly children?: ReactNode;
}) {
  const { groupId = "" } = useParams();
  const search = listQuery(useLocation().search);
  const now = useCalendarNow();
  const ceilingId = useId();
  const ceilingPart = useRef<HTMLDivElement>(null);

  const answeredLast = useRef(false);
  const { shown, showingAnswerWhileReading, changing } = useActedOn(
    read,
    refusedForWhatMoved,
    {
      answered: () => {
        answeredLast.current = true;
        if (!read.loading) {
          onAnswered();
        }
      },
    },
  );
  const [asked, setAsked] = useState<RunAct | null>(null);
  const [opened, setOpened] = useState<{
    readonly dialog: "rename" | "ceiling";
    readonly count: number;
    readonly targetRunId?: string;
  } | null>(null);
  const [dialogOpen, setDialogOpen] = useState(false);

  // An act's answer may take its control with it, so the keyboard starts again at the ceiling; only once, as a
  // read again later is no act of the reader's.
  useLayoutEffect(() => {
    if (!answeredLast.current) {
      return;
    }
    answeredLast.current = false;
    if (document.activeElement === document.body) {
      ceilingPart.current?.focus();
    }
  }, [shown]);

  const refusedAt = (acts: readonly RunAct[], rule: Rule) =>
    asked !== null && acts.includes(asked) && changing.problem !== null ? (
      <ProblemView problem={changing.problem} rule={rule} />
    ) : null;

  const opening = (
    dialog: "rename" | "ceiling",
    act: RunAct,
    targetRunId?: string,
  ) => {
    setAsked(act);
    setOpened((last) => ({
      dialog,
      count: (last?.count ?? 0) + 1,
      targetRunId,
    }));
    setDialogOpen(true);
  };

  return (
    <Async
      read={{
        ...read,
        value: shown,
        loading: read.loading && !showingAnswerWhileReading,
      }}
      empty={() => null}
    >
      {(run) => {
        if (run === null) {
          return null;
        }
        const switchedBy = offers(run, "stop") ? "stop" : "open_again";
        const own = "heldBy" in run.ceiling ? null : run.ceiling;
        const editingChild = run.ceilingsBeneath?.find(
          ({ run: child }) => child.runId === opened?.targetRunId,
        );
        const editingCeiling =
          opened?.targetRunId === undefined ? own : editingChild?.ceiling;
        const [otherDrawing, otherWords] = OTHER_DRAWING[drawing];
        const progress =
          steps === null
            ? null
            : say("step.progress", {
                done: steps.run.progress.done,
                of: steps.run.progress.of,
              });
        return (
          <>
            <PageHeading
              focusOnArrival={focusOnArrival}
              title={runTitle(run.name, run.number)}
            />
            <TwoColumns
              wideAtLeast={LEAST_TABLE_WIDTH}
              stickyNarrow={drawing === "conversation"}
              wide={children}
              narrow={
                <Box
                  sx={
                    drawing === "conversation"
                      ? { height: "100%", "& > section": { minHeight: "100%" } }
                      : undefined
                  }
                >
                  <AboutIt
                    status={<RunStateBadge state={run.state} />}
                    facts={
                      <>
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
                          {run.above === undefined
                            ? run.startedBy === undefined
                              ? whenText(run.startedAt, now)
                              : say("run.startedBy", {
                                  name: isolatedInText(
                                    nameOrNumber(run.startedBy),
                                  ),
                                  when: whenText(run.startedAt, now),
                                })
                            : say("run.startedAbove", {
                                step: isolatedInText(
                                  spokenName(run.from!.stepName),
                                ),
                                number: run.from!.number,
                                when: whenText(run.startedAt, now),
                              })}
                        </Field>
                        {run.above === undefined ? null : (
                          <Field label={say("run.above")}>
                            {runLink(
                              groupId,
                              run.above,
                              search,
                              say("run.openAbove", {
                                number: run.from!.number,
                              }),
                            )}{" "}
                            <Link
                              component={RouterLink}
                              to={`${stepHref(groupId, run.from!.runId, run.from!.stepId)}${search}`}
                              state={WITHIN_THE_RUN}
                            >
                              {say("run.openStartingStep")}
                            </Link>
                          </Field>
                        )}
                        <Field label={say("run.where")}>
                          {whereNowDrawn(run, steps, STEP_NAMED[drawing], now)}
                          {steps === null ? null : (
                            <>
                              <LinearProgress
                                variant="determinate"
                                value={
                                  steps.run.progress.of === 0
                                    ? 0
                                    : (100 * steps.run.progress.done) /
                                      steps.run.progress.of
                                }
                                aria-label={progress ?? undefined}
                              />
                              <Typography variant="body2" sx={QUIET_SX}>
                                {progress}
                              </Typography>
                            </>
                          )}
                        </Field>
                        <Field label={say("run.cost")}>
                          <Spent spend={run.spend} />
                        </Field>
                        <Field
                          label={
                            <span id={ceilingId}>{say("run.ceiling")}</span>
                          }
                        >
                          <CeilingPart
                            ref={ceilingPart}
                            labelledBy={ceilingId}
                            groupId={groupId}
                            run={run}
                            search={search}
                            changing={changing}
                            refusedAt={refusedAt}
                            onAsked={setAsked}
                            onChange={(targetRunId) =>
                              opening("ceiling", "change_ceiling", targetRunId)
                            }
                          />
                        </Field>
                      </>
                    }
                  >
                    <Box sx={ACTS_SX}>
                      {offers(run, "stop") || offers(run, "open_again") ? (
                        <ActButton
                          action={changing}
                          warns={switchedBy === "stop"}
                          act={(signal) => {
                            setAsked(switchedBy);
                            return (
                              switchedBy === "stop" ? stopRun : openRunAgain
                            )(groupId, run.runId, signal);
                          }}
                        >
                          {say(RUN_ACTS[switchedBy])}
                        </ActButton>
                      ) : null}
                      {offers(run, "rename") ? (
                        <Press
                          unavailable={changing.running}
                          onPress={() => opening("rename", "rename")}
                        >
                          {say(RUN_ACTS.rename)}
                        </Press>
                      ) : null}
                    </Box>
                    {refusedAt(["stop", "open_again"], {
                      inGroup: RUN_ACT_RULES[switchedBy],
                    })}
                    {drawing === "conversation" && steps !== null ? (
                      <StepsSoFar
                        steps={steps.steps}
                        wideAtLeast={LEAST_TABLE_WIDTH}
                      />
                    ) : null}
                    <Press navigation onPress={() => onDrawing(otherDrawing)}>
                      {say(otherWords)}
                    </Press>
                  </AboutIt>
                </Box>
              }
            />

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
            {opened?.dialog === "ceiling" && editingCeiling != null ? (
              <ChangeCeiling
                key={opened.count}
                groupId={groupId}
                runId={run.runId}
                targetRunId={opened.targetRunId}
                targetRunNumber={editingChild?.run.number}
                ceiling={editingCeiling}
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

/**
 * The ceiling a run is held to: the one above it at the top, or its own, and for a run at the top each run beneath it
 * that holds one of its own, with what may be done to each and why an act on a raise was refused.
 */
function CeilingPart({
  ref,
  labelledBy,
  groupId,
  run,
  search,
  changing,
  refusedAt,
  onAsked,
  onChange,
}: {
  /** Where the keyboard starts again once an act's answer takes its control away. */
  readonly ref: RefObject<HTMLDivElement | null>;
  readonly labelledBy: string;
  readonly groupId: string;
  readonly run: Run;
  readonly search: string;
  readonly changing: Action<Run | null>;
  readonly refusedAt: (acts: readonly RunAct[], rule: Rule) => ReactNode;
  readonly onAsked: (act: RunAct) => void;
  /** Asked with the run beneath whose ceiling is to change, and with none for the run's own. */
  readonly onChange: (targetRunId?: string) => void;
}) {
  const own = "heldBy" in run.ceiling ? null : run.ceiling;
  return (
    <Box ref={ref} role="group" aria-labelledby={labelledBy} tabIndex={-1}>
      {own === null && "heldBy" in run.ceiling
        ? runLink(
            groupId,
            run.ceiling.heldBy.runId,
            search,
            say("run.heldBy", { number: run.ceiling.heldBy.number }),
          )
        : null}
      {own === null ? null : (
        <OwnCeilingPart
          groupId={groupId}
          runId={run.runId}
          acts={run.above === undefined ? run.acts : new Set()}
          own={own}
          changing={changing}
          onAsked={onAsked}
          onChange={() => onChange()}
        />
      )}
      {run.above === undefined
        ? run.ceilingsBeneath?.map((beneath) => (
            <Box
              key={beneath.run.runId}
              role="group"
              aria-label={say("run.ceilingOf", { number: beneath.run.number })}
            >
              <Typography variant="body2">
                {runLink(
                  groupId,
                  beneath.run.runId,
                  search,
                  say("run.ceilingOf", { number: beneath.run.number }),
                )}
              </Typography>
              <OwnCeilingPart
                groupId={groupId}
                runId={run.runId}
                acts={beneath.acts}
                changeLabel={say("run.changeCeilingOf", {
                  number: beneath.run.number,
                })}
                own={beneath.ceiling}
                changing={changing}
                onAsked={onAsked}
                onChange={() => onChange(beneath.run.runId)}
              />
            </Box>
          ))
        : null}
      {refusedAt(["approve_raise", "refuse_raise"], {
        inGroup: RUN_ACT_RULES.approve_raise,
      })}
      {refusedAt(["withdraw_raise"], {
        inGroup: RUN_ACT_RULES.withdraw_raise,
      })}
    </Box>
  );
}

/** The ceiling the run holds of its own, any raise waiting on it, and what may be done to either. */
function OwnCeilingPart({
  groupId,
  runId,
  acts,
  changeLabel,
  own,
  changing,
  onAsked,
  onChange,
}: {
  readonly groupId: string;
  readonly runId: string;
  readonly acts: ReadonlySet<string>;
  readonly changeLabel?: string;
  readonly own: OwnCeiling;
  readonly changing: Action<Run | null>;
  readonly onAsked: (act: RunAct) => void;
  readonly onChange: () => void;
}) {
  const waiting = own.waiting;
  const now = useCalendarNow();
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
                when: whenText(waiting.askedAt, now),
              })
            : say("raise.to", {
                name: isolatedInText(nameOrNumber(waiting.askedBy)),
                when: whenText(waiting.askedAt, now),
                to: countSaid(waiting.to),
              })}
        </Typography>
      )}
      <Box sx={ACTS_SX}>
        {acts.has("change_ceiling") ? (
          <Press unavailable={changing.running} onPress={onChange}>
            {changeLabel ?? say(RUN_ACTS.change_ceiling)}
          </Press>
        ) : null}
        {waiting === undefined
          ? null
          : DECISIONS.filter(([act]) => acts.has(act)).map(
              ([act, decision]) => (
                <ActButton
                  key={act}
                  action={changing}
                  act={(signal) => {
                    onAsked(act);
                    return decideRaise(
                      groupId,
                      runId,
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

/** Where it is; while it runs, on which step, by the name the drawing gives that step. */
function whereNowSaid(
  run: Run,
  steps: RunSteps | null,
  stepNamed: (step: StepRow) => string,
  now: Date,
): string {
  const at = run.at;
  if (at === undefined || run.state !== "running") {
    return runWhereSaid(run, now);
  }
  return say("runState.runningAt", {
    step: isolatedInText(stepNowNamed(at, steps, stepNamed)),
  });
}

function stepNowNamed(
  at: NonNullable<Run["at"]>,
  steps: RunSteps | null,
  stepNamed: (step: StepRow) => string,
): string {
  const row = steps?.steps.find((each) => each.stepId === at.stepId);
  return row === undefined ? spokenName(at.name) : stepNamed(row);
}

function whereNowDrawn(
  run: Run,
  steps: RunSteps | null,
  stepNamed: (step: StepRow) => string,
  now: Date,
): ReactNode {
  const at = run.at;
  if (at === undefined || run.state !== "running") {
    return whereNowSaid(run, steps, stepNamed, now);
  }
  return visible("runState.runningAtVisible", {
    hint: <SystemHint>{say("runState.runningAtHint")}</SystemHint>,
    step: <bdi>{isolatedInText(stepNowNamed(at, steps, stepNamed))}</bdi>,
  });
}
