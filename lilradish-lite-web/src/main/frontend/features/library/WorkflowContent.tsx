import Box from "@mui/material/Box";
import Table from "@mui/material/Table";
import TableBody from "@mui/material/TableBody";
import TableCell from "@mui/material/TableCell";
import TableContainer from "@mui/material/TableContainer";
import TableHead from "@mui/material/TableHead";
import TableRow from "@mui/material/TableRow";
import Typography from "@mui/material/Typography";
import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useId,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from "react";
import { useSearchParams } from "react-router";

import type { Version } from "../../api/groups/{groupId}/{kind}";
import {
  readWorkflow,
  writtenSinceRead,
  type WorkflowVersion,
} from "../../api/groups/{groupId}/{kind}/{entryId}/versions/{versionId}";
import { writeCeiling } from "../../api/groups/{groupId}/{kind}/{entryId}/versions/{versionId}/ceiling";
import { writeHelp } from "../../api/groups/{groupId}/{kind}/{entryId}/versions/{versionId}/help";
import { writeSteps } from "../../api/groups/{groupId}/{kind}/{entryId}/versions/{versionId}/steps";
import { declareWorkflow } from "../../api/groups/{groupId}/{kind}/{entryId}/versions/{versionId}/{side}";
import type { Problem } from "../../api/problem";
import {
  contentProblemsIn,
  contentProblemTotalIn,
} from "../../api/groups/{groupId}/{kind}/{entryId}/versions";
import { Async } from "../../app/Async";
import { ProblemView } from "../../app/ProblemView";
import { say } from "../../i18n/app";
import { visible } from "../../i18n/visible";
import { ActButton } from "../../lib/action/ActButton";
import { Press } from "../../lib/action/Press";
import { RowPanel } from "../../lib/collection/RowPanel";
import { FIGURE_SETTING } from "../../lib/collection/SortableTable";
import { Part } from "../../lib/layout/Part";
import { INNER_PART_SX, HEADING_SX, SPACED_SX } from "../../lib/layout/parts";
import { Notice } from "../../lib/notice/Notice";
import { useAction, type Action } from "../../lib/request/useAction";
import { useResource, type Resource } from "../../lib/request/useResource";
import { VERSION_ACT_RULES } from "./actRules";
import { BoundRows } from "./BindingControls";
import { WRITTEN_LIMITS } from "./constantDrafts";
import {
  ContentProblems,
  contentOf,
  type ReadContent,
} from "./ContentProblems";
import { DeclarationBuilder } from "./DeclarationBuilder";
import { OPEN_STEP } from "./entryAddress";
import { useDraftPartStatus } from "./useDraftStatus";
import { SendsPast } from "./SendsPast";
import { StepEditor } from "./StepEditor";
import { calledOf, calledVisibleOf, kindSetApart, StepRow } from "./StepRow";
import { RunSettings } from "./WorkflowSettings";
import {
  boundTo,
  flagsOf,
  flowOf,
  freshStep,
  MOST_STEPS,
  movedStep,
  namedVersions,
  removedStep,
  reusableSentFlowOf,
  sentFlowOf,
  sourcesFor,
  targetRows,
  tookVersion,
  unusedKey,
  WORKFLOW_GIVES,
  WORKFLOW_TAKES,
  type FlowDraft,
  type Named,
  type StepDraft,
} from "./workflowDrafts";

export const WorkflowSubmissionRefusal = createContext<Problem | null>(null);

const QUIET_SX = { color: "text.secondary" };

const STEP_PANEL_WIDTH = 464;

/** Where focus goes once the rows change: a row's step or the move pressed on it, or the steps' heading. */
type Focus = { readonly key: string; readonly at?: "up" | "down" } | "heading";

/**
 * A workflow version's content, each part written on its own naming the revision the page read, and edited only
 * where the server says so. A write refused for somebody saving since keeps what was typed and offers to read afresh.
 */
export function WorkflowContent({
  groupId,
  entryId,
  version,
  readCount,
  onRefused,
  onWritten,
  onShown,
  wrapDocument,
}: {
  readonly groupId: string;
  readonly entryId: string;
  readonly version: Version;
  /** Changed each time the page reads the entry again, which is when this reads the workflow again too. */
  readonly readCount: number;
  readonly onRefused: (problem: Problem) => void;
  /** A part was written, so whatever the page holds of the version before it is stale. */
  readonly onWritten: () => void;
  readonly onShown: (content: ReadContent | null) => void;
  readonly wrapDocument?: (content: ReactNode) => ReactNode;
}) {
  const submissionRefusal = useContext(WorkflowSubmissionRefusal);
  const refusedProblems =
    submissionRefusal === null ? null : contentProblemsIn(submissionRefusal);
  const refusedTotal =
    submissionRefusal === null
      ? null
      : contentProblemTotalIn(submissionRefusal);
  const load = useCallback(
    (signal: AbortSignal): Promise<WorkflowVersion | null> =>
      readWorkflow(groupId, entryId, version.versionId, signal),
    [groupId, entryId, version.versionId],
  );
  const read = useResource<WorkflowVersion | null>(load, null);
  const reload = read.reload;
  const readAt = useRef(readCount);
  useEffect(() => {
    if (readAt.current !== readCount) {
      readAt.current = readCount;
      reload();
    }
  }, [readCount, reload]);
  const [shown, setShown] = useState(read.value);
  const [readShown, setReadShown] = useState(read.value);
  const [stale, setStale] = useState(false);
  if (read.value !== readShown) {
    setReadShown(read.value);
    setShown(read.value);
    setStale(false);
  }
  useEffect(() => {
    onShown(shown === null ? null : contentOf(shown));
  }, [shown, onShown]);
  const afresh = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (stale) {
      afresh.current?.querySelector("button")?.focus();
    }
  }, [stale]);
  const written = (answered: WorkflowVersion) => {
    setShown(answered);
    setStale(false);
    onWritten();
  };
  const refused = (problem: Problem) => {
    if (writtenSinceRead(problem)) {
      setStale(true);
    }
    onRefused(problem);
  };
  const actions = {
    taking: useAction<WorkflowVersion>(written, refused),
    giving: useAction<WorkflowVersion>(written, refused),
    flowing: useAction<WorkflowVersion>(written, refused),
    spending: useAction<WorkflowVersion>(written, refused),
    helping: useAction<WorkflowVersion>(written, refused),
  };
  useDraftPartStatus(
    "read",
    false,
    read.loading || Object.values(actions).some((action) => action.running),
  );
  // Each write names the revision shown, so one sent while another write or a read is out is refused.
  const waitingBeside = (own: Action<WorkflowVersion>) =>
    read.loading ||
    Object.values(actions).some((other) => other !== own && other.running);
  const editable = version.acts.has("write");
  const at = { groupId, entryId, versionId: version.versionId };

  return (
    <Parts
      read={{ ...read, value: shown }}
      editable={editable}
      actions={actions}
      waiting={waitingBeside}
      at={at}
      wrapDocument={wrapDocument}
      before={
        shown === null ? null : (
          <>
            {stale ? (
              <Box ref={afresh} sx={SPACED_SX}>
                <Notice severity="warning" alert>
                  {say("workflow.writtenSince")}
                </Notice>
                <Press onPress={reload}>{say("workflow.readAfresh")}</Press>
              </Box>
            ) : null}
            <ContentProblems
              problems={
                refusedProblems ??
                (shown.problems.length === 0 ? null : shown.problems)
              }
              problemTotal={
                refusedProblems === null ? shown.problemTotal : refusedTotal
              }
              content={contentOf(shown)}
            />
            <SendsPast sendsPast={shown.sendsPast} steps={shown.steps} />
          </>
        )
      }
    />
  );
}

interface Actions {
  readonly taking: Action<WorkflowVersion>;
  readonly giving: Action<WorkflowVersion>;
  readonly flowing: Action<WorkflowVersion>;
  readonly spending: Action<WorkflowVersion>;
  readonly helping: Action<WorkflowVersion>;
}

interface At {
  readonly groupId: string;
  readonly entryId: string;
  readonly versionId: string;
}

/** Every part in the order a reader meets it; the steps and what fills what it gives back are held as typed together. */
function Parts({
  read,
  before,
  wrapDocument,
  editable,
  actions,
  waiting,
  at,
}: {
  readonly read: Resource<WorkflowVersion | null>;
  readonly before: ReactNode;
  readonly wrapDocument?: (content: ReactNode) => ReactNode;
  readonly editable: boolean;
  readonly actions: Actions;
  /** Whether anything but the write named is out, which saving that part would be refused beside. */
  readonly waiting: (own: Action<WorkflowVersion>) => boolean;
  readonly at: At;
}) {
  const workflow = read.value;
  const named = useMemo(
    () =>
      workflow === null ? new Map<string, Named>() : namedVersions(workflow),
    [workflow],
  );
  const saved = useMemo(
    () => (workflow === null ? null : flowOf(workflow)),
    [workflow],
  );
  // Pin descriptions may refresh without changing the steps being edited.
  const savedAs = useMemo(() => JSON.stringify(saved), [saved]);
  const [flow, setFlow] = useState(saved);
  const [readAs, setReadAs] = useState(savedAs);
  if (savedAs !== readAs) {
    setReadAs(savedAs);
    setFlow(saved);
  }
  const [flowAsked, setFlowAsked] = useState(false);
  const running = actions.flowing.running;
  const change = useCallback(
    (next: (now: FlowDraft) => FlowDraft) => {
      if (!running) {
        setFlow((now) => (now === null ? null : next(now)));
      }
    },
    [running],
  );
  const sendFlow = useMemo(() => reusableSentFlowOf(), []);
  const sent = useMemo(
    () =>
      flow === null || workflow === null
        ? null
        : sendFlow(flow, workflow, named),
    [sendFlow, flow, workflow, named],
  );
  const savedSent = useMemo(
    () =>
      saved === null || workflow === null
        ? null
        : sentFlowOf(saved, workflow, named),
    [saved, workflow, named],
  );
  const savedSentAs = useMemo(
    () =>
      savedSent === null
        ? null
        : [JSON.stringify(savedSent.steps), JSON.stringify(savedSent.outputs)],
    [savedSent],
  );
  const stepsUnsaved =
    sent !== null &&
    savedSentAs !== null &&
    JSON.stringify(sent.steps) !== savedSentAs[0];
  const outputsUnsaved =
    sent !== null &&
    savedSentAs !== null &&
    JSON.stringify(sent.outputs) !== savedSentAs[1];
  const unchanged = !stepsUnsaved && !outputsUnsaved;
  useDraftPartStatus("flow", !unchanged, running || waiting(actions.flowing));
  const heldAt =
    flow?.steps.findIndex((step) => step.key === sent?.held?.step) ?? -1;
  const [parameters, setParameters] = useSearchParams();
  const picked = parameters.get(OPEN_STEP);
  // The router's setter changes with the address; read through this, picking a step redraws only the rows it marks.
  const latestSetParameters = useRef(setParameters);
  useEffect(() => {
    latestSetParameters.current = setParameters;
  }, [setParameters]);
  const pick = useCallback(
    (key: string | null) =>
      latestSetParameters.current(
        (now) => {
          const next = new URLSearchParams(now);
          if (key === null) {
            next.delete(OPEN_STEP);
          } else {
            next.set(OPEN_STEP, key);
          }
          return next;
        },
        { replace: true },
      ),
    [],
  );
  const changeStep = useCallback(
    (key: string, changed: (step: StepDraft) => StepDraft) =>
      change((now) => ({
        ...now,
        steps: now.steps.map((each) =>
          each.key === key ? changed(each) : each,
        ),
      })),
    [change],
  );
  const position = flow?.steps.findIndex((step) => step.key === picked) ?? -1;
  const step = flow?.steps[position];
  // The server answers the steps in the order sent, so the step picked keeps its place under the key it now has.
  const repick = (keys: readonly string[], answered: WorkflowVersion) =>
    setParameters(
      (now) => {
        const picked = now.get(OPEN_STEP);
        const at = picked === null ? -1 : keys.indexOf(picked);
        if (at === -1) {
          return now;
        }
        const next = new URLSearchParams(now);
        const stepId = answered.steps[at]?.stepId;
        if (stepId === undefined) {
          next.delete(OPEN_STEP);
        } else {
          next.set(OPEN_STEP, stepId);
        }
        return next;
      },
      { replace: true },
    );
  const contents =
    workflow === null || flow === null || sent === null ? null : (
      <>
        <DeclarationBuilder
          title={say("workflow.takes")}
          heading="h3"
          depth={2}
          demands={WORKFLOW_TAKES}
          fields={workflow.takes}
          lists={workflow.offered.lists}
          editable={editable}
          waiting={waiting(actions.taking)}
          saveLabel={say("workflow.saveTakes")}
          underway={say("workflow.underway")}
          rule={VERSION_ACT_RULES.write}
          action={actions.taking}
          save={(fields, signal) =>
            declareWorkflow(
              at.groupId,
              at.entryId,
              at.versionId,
              "takes",
              workflow.revision,
              fields,
              signal,
            )
          }
        />
        <StepsPart
          flow={flow}
          workflow={workflow}
          named={named}
          editable={editable}
          running={running}
          unsaved={stepsUnsaved}
          picked={picked}
          pick={pick}
          changeStep={changeStep}
          change={change}
        />
        <DeclarationBuilder
          title={say("workflow.gives")}
          heading="h3"
          depth={2}
          demands={WORKFLOW_GIVES}
          fields={workflow.gives}
          lists={workflow.offered.lists}
          editable={editable}
          waiting={waiting(actions.giving)}
          saveLabel={say("workflow.saveGives")}
          underway={say("workflow.underway")}
          rule={VERSION_ACT_RULES.write}
          action={actions.giving}
          save={(fields, signal) =>
            declareWorkflow(
              at.groupId,
              at.entryId,
              at.versionId,
              "gives",
              workflow.revision,
              fields,
              signal,
            )
          }
        />
        <OutputsPart
          flow={flow}
          workflow={workflow}
          named={named}
          editable={editable}
          unsaved={outputsUnsaved}
          change={change}
        />
        {editable ? (
          <Box sx={SPACED_SX}>
            <ActButton
              action={actions.flowing}
              variant={!unchanged ? "contained" : undefined}
              waiting={waiting(actions.flowing)}
              reason={
                sent.held !== null
                  ? {
                      severity: "warning",
                      words:
                        heldAt === -1
                          ? say(sent.held.why, { ...WRITTEN_LIMITS, most: 256 })
                          : say("workflow.heldAt", {
                              step: calledOf(flow.steps[heldAt]!, heldAt),
                              why: say(sent.held.why, {
                                ...WRITTEN_LIMITS,
                                most: 256,
                              }),
                            }),
                      visible:
                        heldAt === -1
                          ? undefined
                          : visible("workflow.heldAt", {
                              step: (
                                <>
                                  {calledVisibleOf(
                                    flow.steps[heldAt]!,
                                    heldAt,
                                    "inherit",
                                  )}
                                </>
                              ),
                              why: (
                                <Box
                                  component="span"
                                  sx={{ typography: "systemHint" }}
                                >
                                  {say(sent.held.why, {
                                    ...WRITTEN_LIMITS,
                                    most: 256,
                                  })}
                                </Box>
                              ),
                            }),
                    }
                  : unchanged
                    ? {
                        severity: "info",
                        words: say("workflow.stepsUnchanged"),
                      }
                    : undefined
              }
              act={(signal) => {
                setFlowAsked(true);
                const keys = flow.steps.map((step) => step.key);
                return writeSteps(
                  at.groupId,
                  at.entryId,
                  at.versionId,
                  workflow.revision,
                  sent.steps,
                  sent.outputs,
                  signal,
                ).then((answered) => {
                  repick(keys, answered);
                  return answered;
                });
              }}
            >
              {say("workflow.saveSteps")}
            </ActButton>
            {running ? (
              <Notice severity="info">{say("workflow.underway")}</Notice>
            ) : null}
            {!flowAsked || actions.flowing.problem === null ? null : (
              <ProblemView
                problem={actions.flowing.problem}
                rule={{ inGroup: VERSION_ACT_RULES.write }}
              />
            )}
          </Box>
        ) : null}
        <RunSettings
          workflow={workflow}
          editable={editable}
          spending={actions.spending}
          helping={actions.helping}
          waiting={waiting}
          saveCeiling={(ceiling, keeps, raise, signal) =>
            writeCeiling(
              at.groupId,
              at.entryId,
              at.versionId,
              workflow.revision,
              ceiling,
              keeps,
              raise,
              signal,
            )
          }
          saveHelp={(may, helper, signal) =>
            writeHelp(
              at.groupId,
              at.entryId,
              at.versionId,
              workflow.revision,
              may,
              helper,
              signal,
            )
          }
        />
      </>
    );
  const list = (
    <Async read={read} empty={() => null}>
      {() =>
        contents === null ? null : (
          <>
            {before}
            {contents}
          </>
        )
      }
    </Async>
  );
  return (
    <RowPanel
      open={read.problem === null && step !== undefined}
      onClose={() => pick(null)}
      title={step === undefined ? "" : calledOf(step, position)}
      visibleTitle={
        step === undefined ? undefined : calledVisibleOf(step, position)
      }
      headingLevel="h4"
      width={STEP_PANEL_WIDTH}
      leastListWidth={620}
      besideHeading={step === undefined ? undefined : kindSetApart(step)}
      list={wrapDocument === undefined ? list : wrapDocument(list)}
      listLabel={say("workflow.steps")}
    >
      {read.problem !== null ||
      step === undefined ||
      flow === null ||
      workflow === null ? null : (
        <StepEditor
          key={step.key}
          step={step}
          position={position}
          flow={flow}
          workflow={workflow}
          named={named}
          editable={editable && !running}
          change={(changed) => changeStep(step.key, changed)}
        />
      )}
    </RowPanel>
  );
}

/**
 * The steps in the order they run; nothing but moving one reorders them.
 */
function StepsPart({
  flow,
  workflow,
  named,
  editable,
  running,
  unsaved,
  picked,
  pick,
  changeStep,
  change,
}: {
  readonly flow: FlowDraft;
  readonly workflow: WorkflowVersion;
  readonly named: ReadonlyMap<string, Named>;
  readonly editable: boolean;
  readonly running: boolean;
  readonly unsaved: boolean;
  readonly picked: string | null;
  readonly pick: (key: string | null) => void;
  readonly changeStep: (
    key: string,
    changed: (step: StepDraft) => StepDraft,
  ) => void;
  readonly change: (next: (now: FlowDraft) => FlowDraft) => void;
}) {
  const headingId = useId();
  const heading = useRef<HTMLHeadingElement>(null);
  const opens = useRef(new Map<string, HTMLElement>());
  const rows = useRef(new Map<string, HTMLElement>());
  const pending = useRef<Focus | null>(null);
  useEffect(() => {
    const focus = pending.current;
    pending.current = null;
    if (focus === "heading") {
      heading.current?.focus();
    } else if (focus !== null) {
      (focus.at === undefined
        ? opens.current.get(focus.key)
        : rows.current
            .get(focus.key)
            ?.querySelector<HTMLElement>(`[data-part="${focus.at}"] button`)
      )?.focus();
    }
  }, [flow.steps]);
  const [parameters] = useSearchParams();
  const hrefOf = (key: string) => {
    const next = new URLSearchParams(parameters);
    next.set(OPEN_STEP, key);
    return `?${next}`;
  };
  // Each row is handed these rather than its own, so typing in the step picked redraws that step's row alone.
  const registerRow = useCallback(
    (key: string, row: HTMLElement | null) =>
      setOrDelete(rows.current, key, row),
    [],
  );
  const registerOpen = useCallback(
    (key: string, open: HTMLElement | null) =>
      setOrDelete(opens.current, key, open),
    [],
  );
  const move = useCallback(
    (key: string, index: number, by: -1 | 1) => {
      pending.current = { key, at: by < 0 ? "up" : "down" };
      change((now) => movedStep(now, index, by));
    },
    [change],
  );
  const takeOut = useCallback(
    (index: number, focusAfter: string | undefined, wasPicked: boolean) => {
      pending.current =
        focusAfter === undefined ? "heading" : { key: focusAfter };
      if (wasPicked) {
        pick(null);
      }
      change((now) => removedStep(now, index));
    },
    [change, pick],
  );
  const takeVersion = useCallback(
    (key: string, versionId: string) =>
      changeStep(key, (now) => tookVersion(now, versionId, named)),
    [changeStep, named],
  );
  // No region of its own: the panel names the list it is drawn beside.
  const table = (
    <Box sx={INNER_PART_SX}>
      <Box
        sx={{
          display: "flex",
          alignItems: "baseline",
          gap: 1,
          flexWrap: "wrap",
        }}
      >
        <Typography
          id={headingId}
          ref={heading}
          tabIndex={-1}
          variant="h3"
          sx={HEADING_SX}
        >
          {say("workflow.steps")}
        </Typography>
        {editable && unsaved ? (
          <Typography
            component="span"
            variant="body2"
            color="text.secondary"
            sx={{ typography: "systemHint" }}
          >
            {say("draft.unsaved")}
          </Typography>
        ) : null}
      </Box>
      {flow.steps.length === 0 ? (
        <>
          <Typography variant="body2" sx={QUIET_SX}>
            {say("workflow.stepsNone")}
          </Typography>
          {/* Submitting refuses a workflow with no step; said where the steps are typed, before any is saved. */}
          <Typography variant="body2">
            {say("contentProblem.no_steps")}
          </Typography>
        </>
      ) : (
        <TableContainer>
          <Table size="small" aria-labelledby={headingId}>
            <TableHead>
              <TableRow>
                <TableCell {...FIGURE_SETTING}>
                  {say("workflow.order")}
                </TableCell>
                <TableCell>{say("workflow.name")}</TableCell>
                <TableCell>{say("workflow.runs")}</TableCell>
                <TableCell>{say("workflow.produces")}</TableCell>
                <TableCell>{say("workflow.reviewedBy")}</TableCell>
                <TableCell>{say("workflow.givesBack")}</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {flow.steps.map((each, index) => (
                <StepRow
                  key={each.key}
                  step={each}
                  position={index}
                  last={index === flow.steps.length - 1}
                  focusAfterTakingOut={
                    (flow.steps[index + 1] ?? flow.steps[index - 1])?.key
                  }
                  picked={each.key === picked}
                  href={hrefOf(each.key)}
                  named={named}
                  codeSteps={workflow.offered.codeSteps}
                  searchWays={workflow.offered.searchWays}
                  editable={editable}
                  running={running}
                  onRegisterRow={registerRow}
                  onRegisterOpen={registerOpen}
                  onMove={move}
                  onTakeOut={takeOut}
                  onTakeVersion={takeVersion}
                />
              ))}
            </TableBody>
          </Table>
        </TableContainer>
      )}
      {editable ? (
        <Box sx={{ mt: 1 }}>
          <Press
            unavailable={running}
            reason={
              flow.steps.length >= MOST_STEPS
                ? { severity: "warning", words: say("refusal.STEPS_TOO_MANY") }
                : undefined
            }
            onPress={() => {
              const added = freshStep(
                unusedKey(
                  "step",
                  flow.steps.map((each) => each.key),
                ),
              );
              change((now) => ({ ...now, steps: [...now.steps, added] }));
              pick(added.key);
            }}
          >
            {say("workflow.addStep")}
          </Press>
        </Box>
      ) : null}
    </Box>
  );
  return table;
}

function setOrDelete(
  held: Map<string, HTMLElement>,
  key: string,
  element: HTMLElement | null,
): void {
  if (element === null) {
    held.delete(key);
  } else {
    held.set(key, element);
  }
}

/** What fills each value the workflow gives back, only ever what a step gives back; one unfilled says so at once. */
function OutputsPart({
  flow,
  workflow,
  named,
  editable,
  unsaved,
  change,
}: {
  readonly flow: FlowDraft;
  readonly workflow: WorkflowVersion;
  readonly named: ReadonlyMap<string, Named>;
  readonly editable: boolean;
  readonly unsaved: boolean;
  readonly change: (next: (now: FlowDraft) => FlowDraft) => void;
}) {
  const position = flow.steps.length;
  const sources = sourcesFor(flow, position, workflow, named, undefined);
  const problems = workflow.problems.filter(
    (problem) => problem.part === "gives",
  );
  const rows = targetRows(
    workflow.gives,
    flow.outputs,
    problems,
    (binding, target) =>
      flagsOf(binding, target, flow, position, problems, sources),
    "workflow.outputUnbound",
  );
  return (
    <Part
      title={say("workflow.outputs")}
      actions={
        editable && unsaved ? (
          <Typography
            component="span"
            variant="body2"
            color="text.secondary"
            sx={{ typography: "systemHint" }}
          >
            {say("draft.unsaved")}
          </Typography>
        ) : undefined
      }
      level="h3"
      depth={2}
    >
      {rows.length === 0 ? (
        <Typography variant="body2" sx={QUIET_SX}>
          {say("workflow.outputsNone")}
        </Typography>
      ) : (
        <BoundRows
          rows={rows}
          sources={sources}
          constant={false}
          editable={editable}
          change={(target, source) =>
            change((now) => ({
              ...now,
              outputs: boundTo(now.outputs, target, source),
            }))
          }
        />
      )}
    </Part>
  );
}
