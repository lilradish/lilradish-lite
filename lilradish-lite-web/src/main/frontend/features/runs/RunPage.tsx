import Box from "@mui/material/Box";
import { VersionLabel } from "../../lib/text/VersionLabel";
import Link from "@mui/material/Link";
import LinearProgress from "@mui/material/LinearProgress";
import Typography from "@mui/material/Typography";
import ToggleButton from "@mui/material/ToggleButton";
import ToggleButtonGroup from "@mui/material/ToggleButtonGroup";
import {
  useId,
  useLayoutEffect,
  useMemo,
  useRef,
  useState,
  type ReactNode,
  type Ref,
  type RefObject,
} from "react";
import { Link as RouterLink, useLocation, useParams } from "react-router";

import {
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
import type { GroupStanding } from "../../api/standing";
import { Async } from "../../app/Async";
import { groupPage, WORK_ITEM } from "../../app/destinations";
import { ProblemView } from "../../app/ProblemView";
import type { Rule } from "../../app/standing/actRules";
import { useGroup } from "../../app/standing/RequireGroup";
import { useStanding } from "../../app/standing/StandingContext";
import { useActedOn } from "../../app/useActedOn";
import { say, type MessageId } from "../../i18n/app";
import { visible } from "../../i18n/visible";
import { ActGroup } from "../../lib/action/ActGroup";
import { ActButton } from "../../lib/action/ActButton";
import { InlineEditableValue } from "../../lib/action/InlineEditableValue";
import { useKeptChoice } from "../../lib/device/useKeptChoice";
import { isolatedInText } from "../../lib/direction/isolated";
import { Field } from "../../lib/form/Fields";
import { PageHeading } from "../../lib/heading/PageHeading";
import { LEAST_TABLE_WIDTH } from "../../lib/collection/RowPanel";
import { ACTS_SX, HEADING_SX, QUIET_SX } from "../../lib/layout/parts";
import { PANEL_PART_SX } from "../../lib/layout/SidePanelHost";
import { TwoColumns } from "../../lib/layout/TwoColumns";

import { Breadcrumbs, type Crumb } from "../../lib/navigation/Breadcrumbs";
import type { Action } from "../../lib/request/useAction";
import type { Resource } from "../../lib/request/useResource";
import { nameOrNumber } from "../../lib/text/names";
import { StateNote } from "../../lib/text/StateNote";
import { useCalendarNow, whenText } from "../../lib/time/When";
import { entryHref } from "../library/entryAddress";
import { LIBRARY_KINDS } from "../library/libraryKinds";
import { AboutIt } from "./AboutIt";
import { RUN_ACT_RULES, runOffers } from "./actRules";
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

export function RunPage() {
  const group = useGroup();
  const { runId = "" } = useParams();
  return (
    <RunReadAccess key={`${group.groupId}/${runId}`}>
      <RunPageWithin group={group} runId={runId} />
    </RunReadAccess>
  );
}

function RunPageWithin({
  group,
  runId,
}: {
  readonly group: GroupStanding;
  readonly runId: string;
}) {
  const { groupId } = group;
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
          <RunInFullDetail group={group} reading={read} />
        ) : (
          <RunConversation group={group} reading={read} onShown={stepShown} />
        )}
      </RunDrawn>
    </CurrentFileRunContext.Provider>
  );
}

/**
 * History retains its filter and sort through the trail above the run.
 * Work and History remain available before the run is read, including when it is refused.
 */
export function RunPath({
  run,
  step,
}: {
  readonly run: Run | null;
  /** What the step is called on a step's page, none while it is read; absent on the run's own page. */
  readonly step?: string | null;
}) {
  const group = useGroup();
  const search = listQuery(useLocation().search);
  const work = groupPage(group, WORK_ITEM.segment);
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
    path: runHref(group, runId),
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

function runTitle(name: string | undefined, number: number): string {
  return name === undefined
    ? say("run.title", { number })
    : isolatedInText(name);
}

interface Acted {
  readonly over: RunReading | null;
  readonly answer: StepAnswer;
}

/**
 * A step answer temporarily replaces its row and header on the read it answered.
 * Only a new run read replaces the run itself and ends that overlay.
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

const STEP_NAMED = {
  conversation: stepTitle,
  fullDetail: (step: StepRow) => step.label ?? spokenName(step.name),
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
  const group = useGroup();
  const standing = useStanding();
  const { groupId } = group;
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
  const readControls = useRef(new Map<string, HTMLButtonElement>());
  const returnFocus = useRef<string | null>(null);
  const controlRef = (key: string) => (control: HTMLButtonElement | null) => {
    if (control === null) readControls.current.delete(key);
    else readControls.current.set(key, control);
  };
  useLayoutEffect(() => {
    if (!dialogOpen && !changing.running && returnFocus.current !== null) {
      if (document.activeElement === document.body)
        readControls.current.get(returnFocus.current)?.focus();
      returnFocus.current = null;
    }
  }, [dialogOpen, changing.running]);

  // An act's answer may take its control with it, so the keyboard starts again at the ceiling; only once, as a
  // read again later is no act of the reader's.
  useLayoutEffect(() => {
    if (!answeredLast.current) {
      return;
    }
    answeredLast.current = false;
    if (
      returnFocus.current === null &&
      document.activeElement === document.body
    ) {
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
        const switchedBy = runOffers(run, "stop", standing, groupId)
          ? "stop"
          : "open_again";
        const stopOffered =
          runOffers(run, "stop", standing, groupId) ||
          runOffers(run, "open_again", standing, groupId);
        const stopRefused = refusedAt(["stop", "open_again"], {
          inGroup: RUN_ACT_RULES[switchedBy],
        });
        const own = "heldBy" in run.ceiling ? null : run.ceiling;
        const editingChild = run.ceilingsBeneath?.find(
          ({ run: child }) => child.runId === opened?.targetRunId,
        );
        const editingCeiling =
          opened?.targetRunId === undefined ? own : editingChild?.ceiling;
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
              titleValue={
                runOffers(run, "rename", standing, groupId) ? (
                  <InlineEditableValue
                    ref={controlRef("rename")}
                    characters={128}
                    value={runTitle(run.name, run.number)}
                    disabled={changing.running}
                    onEdit={() => opening("rename", "rename")}
                  />
                ) : undefined
              }
              editingTitle={
                opened?.dialog === "rename" && dialogOpen ? (
                  <RenameRun
                    key={opened.count}
                    groupId={groupId}
                    run={run}
                    open={dialogOpen}
                    changing={changing}
                    onShut={(restoring = false) => {
                      returnFocus.current = restoring ? "rename" : null;
                      setDialogOpen(false);
                    }}
                  />
                ) : undefined
              }
            />
            <ToggleButtonGroup
              exclusive
              value={drawing}
              aria-label={say("run.view")}
              size="small"
              sx={{ mb: 2 }}
              onChange={(_event, next: unknown) => {
                if (next === "conversation" || next === "fullDetail")
                  onDrawing(next);
              }}
            >
              <ToggleButton value="conversation">
                {say("run.conversation")}
              </ToggleButton>
              <ToggleButton value="fullDetail">
                {say("run.fullDetail")}
              </ToggleButton>
            </ToggleButtonGroup>
            <TwoColumns
              panelTitle={say("run.aboutIt")}
              wideAtLeast={LEAST_TABLE_WIDTH}
              stickyNarrow={drawing === "conversation"}
              wide={children}
              narrow={
                <AboutIt
                  status={
                    <>
                      <RunStateBadge
                        state={run.state}
                        at={run.at?.name}
                        atLabel={
                          run.at === undefined
                            ? undefined
                            : stepNowNamed(run.at, steps, STEP_NAMED[drawing])
                        }
                      />
                      {run.state === "stopped" && run.stopped !== undefined ? (
                        <StateNote>{runWhereSaid(run, now)}</StateNote>
                      ) : null}
                    </>
                  }
                  progress={
                    steps === null ? undefined : (
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
                        <StateNote>{progress}</StateNote>
                      </>
                    )
                  }
                  facts={
                    <>
                      <Field label={say("run.number")}>
                        {countSaid(String(run.number))}
                      </Field>
                      <Field label={say("run.workflow")}>
                        <Link
                          component={RouterLink}
                          to={entryHref(
                            group,
                            LIBRARY_KINDS.workflow,
                            run.workflow.entryId,
                          )}
                        >
                          {visible("run.workflowVersion", {
                            label: (
                              <VersionLabel
                                entry={run.workflow.name}
                                number={run.workflow.version}
                                name={run.workflow.versionName}
                              />
                            ),
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
                                run.from!.label ??
                                  spokenName(run.from!.stepName),
                              ),
                              number: run.from!.number,
                              when: whenText(run.startedAt, now),
                            })}
                      </Field>
                      {run.above === undefined ? null : (
                        <Field label={say("run.above")}>
                          {runLink(
                            group,
                            run.above,
                            search,
                            say("run.openAbove", {
                              number: run.from!.number,
                            }),
                          )}{" "}
                          <Link
                            component={RouterLink}
                            to={`${stepHref(group, run.from!.runId, run.from!.stepId)}${search}`}
                            state={WITHIN_THE_RUN}
                          >
                            {say("run.openStartingStep")}
                          </Link>
                        </Field>
                      )}
                      <Field label={say("run.usage")}>
                        <Spent spend={run.spend} />
                      </Field>
                    </>
                  }
                  acts={
                    stopOffered || stopRefused !== null ? (
                      <>
                        {stopOffered ? (
                          <Box sx={ACTS_SX}>
                            <ActButton
                              action={changing}
                              tier={
                                switchedBy === "stop" ? "danger" : "secondary"
                              }
                              act={(signal) => {
                                setAsked(switchedBy);
                                return (
                                  switchedBy === "stop" ? stopRun : openRunAgain
                                )(groupId, run.runId, signal);
                              }}
                            >
                              {say(RUN_ACTS[switchedBy])}
                            </ActButton>
                          </Box>
                        ) : null}
                        {stopRefused}
                      </>
                    ) : undefined
                  }
                >
                  <Box sx={PANEL_PART_SX}>
                    <Typography id={ceilingId} variant="h3" sx={HEADING_SX}>
                      {say("run.ceiling")}
                    </Typography>
                    <CeilingPart
                      ref={ceilingPart}
                      labelledBy={ceilingId}
                      group={group}
                      run={run}
                      search={search}
                      changing={changing}
                      refusedAt={refusedAt}
                      onAsked={setAsked}
                      onChange={(targetRunId) =>
                        opening("ceiling", "change_ceiling", targetRunId)
                      }
                      editing={(targetRunId) =>
                        opened?.dialog === "ceiling" &&
                        dialogOpen &&
                        opened.targetRunId === targetRunId &&
                        editingCeiling != null ? (
                          <ChangeCeiling
                            key={opened.count}
                            groupId={groupId}
                            runId={run.runId}
                            targetRunId={targetRunId}
                            ceiling={editingCeiling}
                            open
                            changing={changing}
                            onShut={(restoring = false) => {
                              returnFocus.current = restoring
                                ? `ceiling:${targetRunId ?? run.runId}`
                                : null;
                              setDialogOpen(false);
                            }}
                          />
                        ) : null
                      }
                      controlRef={(targetRunId) =>
                        controlRef(`ceiling:${targetRunId ?? run.runId}`)
                      }
                    />
                  </Box>
                  {drawing === "conversation" && steps !== null ? (
                    <StepsSoFar steps={steps.steps} />
                  ) : null}
                </AboutIt>
              }
            />
          </>
        );
      }}
    </Async>
  );
}

function CeilingPart({
  ref,
  labelledBy,
  group,
  run,
  search,
  changing,
  refusedAt,
  onAsked,
  onChange,
  editing,
  controlRef,
}: {
  /** Where the keyboard starts again once an act's answer takes its control away. */
  readonly ref: RefObject<HTMLDivElement | null>;
  readonly labelledBy: string;
  readonly group: GroupStanding;
  readonly run: Run;
  readonly search: string;
  readonly changing: Action<Run | null>;
  readonly refusedAt: (acts: readonly RunAct[], rule: Rule) => ReactNode;
  readonly onAsked: (act: RunAct) => void;
  /** Asked with the run beneath whose ceiling is to change, and with none for the run's own. */
  readonly onChange: (targetRunId?: string) => void;
  readonly editing: (targetRunId?: string) => ReactNode;
  readonly controlRef: (targetRunId?: string) => Ref<HTMLButtonElement>;
}) {
  const standing = useStanding();
  const own = "heldBy" in run.ceiling ? null : run.ceiling;
  return (
    <Box ref={ref} role="group" aria-labelledby={labelledBy} tabIndex={-1}>
      {own === null && "heldBy" in run.ceiling
        ? runLink(
            group,
            run.ceiling.heldBy.runId,
            search,
            say("run.heldBy", { number: run.ceiling.heldBy.number }),
          )
        : null}
      {own === null ? null : (
        <OwnCeilingPart
          groupId={group.groupId}
          runId={run.runId}
          acts={
            run.above === undefined
              ? new Set(
                  (Object.keys(RUN_ACT_RULES) as RunAct[]).filter((act) =>
                    runOffers(run, act, standing, group.groupId),
                  ),
                )
              : new Set()
          }
          own={own}
          editing={editing()}
          changing={changing}
          onAsked={onAsked}
          onChange={() => onChange()}
          controlRef={controlRef()}
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
                  group,
                  beneath.run.runId,
                  search,
                  say("run.ceilingOf", { number: beneath.run.number }),
                )}
              </Typography>
              <OwnCeilingPart
                groupId={group.groupId}
                runId={run.runId}
                acts={beneath.acts}
                own={beneath.ceiling}
                editing={editing(beneath.run.runId)}
                changing={changing}
                onAsked={onAsked}
                onChange={() => onChange(beneath.run.runId)}
                controlRef={controlRef(beneath.run.runId)}
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

function OwnCeilingPart({
  groupId,
  runId,
  acts,
  own,
  changing,
  onAsked,
  onChange,
  editing,
  controlRef,
}: {
  readonly groupId: string;
  readonly runId: string;
  readonly acts: ReadonlySet<string>;
  readonly own: OwnCeiling;
  readonly changing: Action<Run | null>;
  readonly onAsked: (act: RunAct) => void;
  readonly onChange: () => void;
  readonly editing: ReactNode;
  readonly controlRef: Ref<HTMLButtonElement>;
}) {
  const waiting = own.waiting;
  const now = useCalendarNow();
  return (
    <>
      {editing ?? (
        <Typography variant="body1">
          {acts.has("change_ceiling") ? (
            <InlineEditableValue
              ref={controlRef}
              value={
                own.inForce === undefined
                  ? say("run.noCeiling")
                  : countSaid(own.inForce)
              }
              disabled={changing.running}
              onEdit={onChange}
            />
          ) : own.inForce === undefined ? (
            say("run.noCeiling")
          ) : (
            countSaid(own.inForce)
          )}
        </Typography>
      )}
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
      <ActGroup>
        {waiting === undefined
          ? null
          : DECISIONS.filter(([act]) => acts.has(act)).map(
              ([act, decision]) => (
                <ActButton
                  key={act}
                  fill
                  tier="secondary"
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
      </ActGroup>
    </>
  );
}

/** A link to another run of the group, which opens where this one is, the list's address kept. */
function runLink(
  group: GroupStanding,
  runId: string,
  search: string,
  words: string,
): ReactNode {
  return (
    <Link component={RouterLink} to={`${runHref(group, runId)}${search}`}>
      {words}
    </Link>
  );
}

function stepNowNamed(
  at: NonNullable<Run["at"]>,
  steps: RunSteps | null,
  stepNamed: (step: StepRow) => string,
): string {
  const row = steps?.steps.find((each) => each.stepId === at.stepId);
  return row === undefined ? (at.label ?? spokenName(at.name)) : stepNamed(row);
}
