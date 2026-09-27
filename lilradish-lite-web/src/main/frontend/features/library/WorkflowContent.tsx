import Box from "@mui/material/Box";
import Link from "@mui/material/Link";
import Table from "@mui/material/Table";
import TableBody from "@mui/material/TableBody";
import TableCell from "@mui/material/TableCell";
import TableHead from "@mui/material/TableHead";
import TableRow from "@mui/material/TableRow";
import Typography from "@mui/material/Typography";
import {
  useCallback,
  useEffect,
  useId,
  useMemo,
  useRef,
  useState,
} from "react";
import { Link as RouterLink, useSearchParams } from "react-router";

import type { Version } from "../../api/groups/{groupId}/{kind}";
import {
  readWorkflow,
  writtenSinceRead,
  type OfferedCodeStep,
  type WorkflowVersion,
} from "../../api/groups/{groupId}/{kind}/{entryId}/versions/{versionId}";
import { writeCeiling } from "../../api/groups/{groupId}/{kind}/{entryId}/versions/{versionId}/ceiling";
import { writeHelp } from "../../api/groups/{groupId}/{kind}/{entryId}/versions/{versionId}/help";
import { writeSteps } from "../../api/groups/{groupId}/{kind}/{entryId}/versions/{versionId}/steps";
import { declareWorkflow } from "../../api/groups/{groupId}/{kind}/{entryId}/versions/{versionId}/{side}";
import type { Problem } from "../../api/problem";
import { Async } from "../../app/Async";
import { ProblemView } from "../../app/ProblemView";
import { say } from "../../i18n/app";
import { ActButton } from "../../lib/action/ActButton";
import { Press } from "../../lib/action/Press";
import { RowPanel } from "../../lib/collection/RowPanel";
import { isolatedInText } from "../../lib/direction/isolated";
import { Notice } from "../../lib/notice/Notice";
import { useAction, type Action } from "../../lib/request/useAction";
import { useResource } from "../../lib/request/useResource";
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
import { modelSaid } from "./ModelControls";
import { PinNote } from "./PinNote";
import { SendsPast } from "./SendsPast";
import { producerSaid, StepEditor } from "./StepEditor";
import { CeilingPart, HelpPart } from "./WorkflowSettings";
import {
  boundTo,
  flagsOf,
  flowOf,
  freshStep,
  givenBy,
  MOST_STEPS,
  movedStep,
  namedVersions,
  removedStep,
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

const PART_SX = { mb: 3 };

const HEADING_SX = { mb: 1 };

const QUIET_SX = { color: "text.secondary" };

const CONTROLS_SX = { display: "flex", flexWrap: "wrap", gap: 1 };

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
}) {
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
  // Each write names the revision shown, so one sent while another write or a read is out is refused.
  const waitingBeside = (own: Action<WorkflowVersion>) =>
    read.loading ||
    Object.values(actions).some((other) => other !== own && other.running);
  const editable = version.acts.has("write");
  const at = { groupId, entryId, versionId: version.versionId };

  return (
    <Async read={{ ...read, value: shown }} empty={() => null}>
      {(workflow) =>
        workflow === null ? null : (
          <>
            {stale ? (
              <Box ref={afresh} sx={PART_SX}>
                <Notice severity="warning" alert>
                  {say("workflow.writtenSince")}
                </Notice>
                <Press onPress={reload}>{say("workflow.readAfresh")}</Press>
              </Box>
            ) : null}
            <ContentProblems
              problems={
                workflow.problems.length === 0 ? null : workflow.problems
              }
              content={contentOf(workflow)}
            />
            <SendsPast sendsPast={workflow.sendsPast} steps={workflow.steps} />
            <Parts
              workflow={workflow}
              editable={editable}
              actions={actions}
              waiting={waitingBeside}
              at={at}
            />
          </>
        )
      }
    </Async>
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
  workflow,
  editable,
  actions,
  waiting,
  at,
}: {
  readonly workflow: WorkflowVersion;
  readonly editable: boolean;
  readonly actions: Actions;
  /** Whether anything but the write named is out, which saving that part would be refused beside. */
  readonly waiting: (own: Action<WorkflowVersion>) => boolean;
  readonly at: At;
}) {
  const named = useMemo(() => namedVersions(workflow), [workflow]);
  // What is typed into the steps is kept until the steps the server holds change; another part being written
  // leaves it as typed.
  const savedAs = JSON.stringify([workflow.steps, workflow.outputs]);
  const saved = useMemo(() => flowOf(workflow), [workflow]);
  const [flow, setFlow] = useState(saved);
  const [readAs, setReadAs] = useState(savedAs);
  if (savedAs !== readAs) {
    setReadAs(savedAs);
    setFlow(saved);
  }
  const [flowAsked, setFlowAsked] = useState(false);
  const running = actions.flowing.running;
  const change = (next: (now: FlowDraft) => FlowDraft) => {
    if (!running) {
      setFlow(next);
    }
  };
  const sent = sentFlowOf(flow, workflow, named);
  const savedSent = useMemo(
    () => sentFlowOf(saved, workflow, named),
    [saved, workflow, named],
  );
  const unchanged =
    JSON.stringify([sent.steps, sent.outputs]) ===
    JSON.stringify([savedSent.steps, savedSent.outputs]);
  const heldAt = flow.steps.findIndex((step) => step.key === sent.held?.step);
  const [, setParameters] = useSearchParams();
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
  return (
    <>
      <DeclarationBuilder
        title={say("workflow.takes")}
        heading="h3"
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
        change={change}
      />
      <DeclarationBuilder
        title={say("workflow.gives")}
        heading="h3"
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
        change={change}
      />
      {editable ? (
        <Box sx={PART_SX}>
          <ActButton
            action={actions.flowing}
            waiting={waiting(actions.flowing)}
            reason={
              sent.held !== null
                ? {
                    severity: "info",
                    words:
                      heldAt === -1
                        ? say(sent.held.why, WRITTEN_LIMITS)
                        : say("workflow.heldAt", {
                            step: calledOf(flow.steps[heldAt]!, heldAt),
                            why: say(sent.held.why, WRITTEN_LIMITS),
                          }),
                  }
                : unchanged
                  ? { severity: "info", words: say("workflow.stepsUnchanged") }
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
      <CeilingPart
        workflow={workflow}
        editable={editable}
        action={actions.spending}
        waiting={waiting(actions.spending)}
        save={(ceiling, keeps, raise, signal) =>
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
      />
      <HelpPart
        workflow={workflow}
        editable={editable}
        action={actions.helping}
        waiting={waiting(actions.helping)}
        save={(may, helper, signal) =>
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
}

/**
 * The steps in the order they run, which nothing but moving one reorders, and the step picked beside them; the
 * step picked is held in the address.
 */
function StepsPart({
  flow,
  workflow,
  named,
  editable,
  running,
  change,
}: {
  readonly flow: FlowDraft;
  readonly workflow: WorkflowVersion;
  readonly named: ReadonlyMap<string, Named>;
  readonly editable: boolean;
  readonly running: boolean;
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
  const [parameters, setParameters] = useSearchParams();
  const picked = parameters.get(OPEN_STEP);
  const pick = (key: string | null) =>
    setParameters(
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
    );
  const hrefOf = (key: string) => {
    const next = new URLSearchParams(parameters);
    next.set(OPEN_STEP, key);
    return `?${next}`;
  };
  const position = flow.steps.findIndex((step) => step.key === picked);
  const step = flow.steps[position];
  const changeStep = (key: string, changed: (step: StepDraft) => StepDraft) =>
    change((now) => ({
      ...now,
      steps: now.steps.map((each) => (each.key === key ? changed(each) : each)),
    }));
  // No region of its own: the panel names the list it is drawn beside.
  const table = (
    <Box sx={PART_SX}>
      <Typography
        id={headingId}
        ref={heading}
        tabIndex={-1}
        variant="h6"
        component="h3"
        sx={HEADING_SX}
      >
        {say("workflow.steps")}
      </Typography>
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
        <Table size="small" aria-labelledby={headingId}>
          <TableHead>
            <TableRow>
              <TableCell>{say("workflow.order")}</TableCell>
              <TableCell>{say("workflow.name")}</TableCell>
              <TableCell>{say("workflow.runs")}</TableCell>
              <TableCell>{say("workflow.produces")}</TableCell>
              <TableCell>{say("workflow.reviewedBy")}</TableCell>
              <TableCell>{say("workflow.givesBack")}</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {flow.steps.map((each, index) => {
              const called = calledOf(each, index);
              const pinned = pinnedOf(each, named);
              return (
                <TableRow
                  key={each.key}
                  ref={(row: HTMLTableRowElement | null) => {
                    if (row === null) {
                      rows.current.delete(each.key);
                    } else {
                      rows.current.set(each.key, row);
                    }
                  }}
                  selected={each.key === picked}
                >
                  <TableCell>{index + 1}</TableCell>
                  <TableCell>
                    <Link
                      ref={(open: HTMLElement | null) => {
                        if (open === null) {
                          opens.current.delete(each.key);
                        } else {
                          opens.current.set(each.key, open);
                        }
                      }}
                      component={RouterLink}
                      to={hrefOf(each.key)}
                      replace
                      aria-label={say("workflow.open", { name: called })}
                      aria-current={each.key === picked || undefined}
                    >
                      <bdi>{called}</bdi>
                    </Link>
                    {editable ? (
                      <Box sx={CONTROLS_SX}>
                        <Box component="span" data-part="up">
                          <Press
                            unavailable={running || index === 0}
                            onPress={() => {
                              pending.current = { key: each.key, at: "up" };
                              change((now) => movedStep(now, index, -1));
                            }}
                          >
                            {say("workflow.up", { name: called })}
                          </Press>
                        </Box>
                        <Box component="span" data-part="down">
                          <Press
                            unavailable={
                              running || index === flow.steps.length - 1
                            }
                            onPress={() => {
                              pending.current = { key: each.key, at: "down" };
                              change((now) => movedStep(now, index, 1));
                            }}
                          >
                            {say("workflow.down", { name: called })}
                          </Press>
                        </Box>
                        <Press
                          unavailable={running}
                          onPress={() => {
                            const next =
                              flow.steps[index + 1] ?? flow.steps[index - 1];
                            pending.current =
                              next === undefined
                                ? "heading"
                                : { key: next.key };
                            if (each.key === picked) {
                              pick(null);
                            }
                            change((now) => removedStep(now, index));
                          }}
                        >
                          {say("workflow.remove", { name: called })}
                        </Press>
                      </Box>
                    ) : null}
                  </TableCell>
                  <TableCell>
                    {runsSaid(each, named)}
                    {pinned === undefined ? null : (
                      <PinNote
                        pinned={pinned}
                        called={called}
                        take={
                          editable && !running
                            ? (versionId) =>
                                changeStep(each.key, (now) =>
                                  tookVersion(now, versionId, named),
                                )
                            : undefined
                        }
                      />
                    )}
                  </TableCell>
                  <TableCell>{producerSaid(each)}</TableCell>
                  <TableCell>{reviewedSaid(each)}</TableCell>
                  <TableCell>
                    {givesSaid(each, named, workflow.offered.codeSteps)}
                  </TableCell>
                </TableRow>
              );
            })}
          </TableBody>
        </Table>
      )}
      {editable ? (
        <Box sx={{ mt: 1 }}>
          <Press
            unavailable={running}
            reason={
              flow.steps.length >= MOST_STEPS
                ? { severity: "info", words: say("refusal.STEPS_TOO_MANY") }
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
  return (
    <RowPanel
      open={step !== undefined}
      onClose={() => pick(null)}
      title={step === undefined ? "" : calledOf(step, position)}
      headingLevel="h4"
      list={table}
      listLabel={say("workflow.steps")}
    >
      {step === undefined ? null : (
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

function pinnedOf(
  step: StepDraft,
  named: ReadonlyMap<string, Named>,
): Named["pinned"] {
  return step.runs.kind === "question" || step.runs.kind === "workflow"
    ? named.get(step.runs.version)?.pinned
    : undefined;
}

/** What fills each value the workflow gives back, only ever what a step gives back; one unfilled says so at once. */
function OutputsPart({
  flow,
  workflow,
  named,
  editable,
  change,
}: {
  readonly flow: FlowDraft;
  readonly workflow: WorkflowVersion;
  readonly named: ReadonlyMap<string, Named>;
  readonly editable: boolean;
  readonly change: (next: (now: FlowDraft) => FlowDraft) => void;
}) {
  const headingId = useId();
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
    <Box component="section" aria-labelledby={headingId} sx={PART_SX}>
      <Typography id={headingId} variant="h6" component="h3" sx={HEADING_SX}>
        {say("workflow.outputs")}
      </Typography>
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
    </Box>
  );
}

function calledOf(step: StepDraft, position: number): string {
  return step.name === ""
    ? say("workflow.unnamed", { position: position + 1 })
    : isolatedInText(step.name);
}

function runsSaid(step: StepDraft, named: ReadonlyMap<string, Named>): string {
  const runs = step.runs;
  switch (runs.kind) {
    case "none":
      return say("workflow.runsNothing");
    case "question":
    case "workflow": {
      const version = named.get(runs.version);
      return version === undefined
        ? say("workflow.runsNothing")
        : say("workflow.runsVersion", {
            name: isolatedInText(version.name),
            number: version.number,
          });
    }
    case "code_step":
      return runs.codeStep === ""
        ? say("workflow.runsNothing")
        : say("workflow.runsCode", { name: runs.codeStep });
    case "route":
      return say("workflow.runsRoute");
  }
}

function reviewedSaid(step: StepDraft): string {
  if (step.runs.kind !== "question" && step.runs.kind !== "code_step") {
    return say("workflow.producesNobody");
  }
  return step.reviewer === "model"
    ? modelSaid(step.reviewerModel, step.reviewerMode)
    : say("workflow.producesPerson");
}

function givesSaid(
  step: StepDraft,
  named: ReadonlyMap<string, Named>,
  codeSteps: readonly OfferedCodeStep[],
): string {
  const gives = givenBy(step, named, codeSteps);
  return gives === undefined
    ? say("workflow.givesUnknown")
    : say("workflow.givesCount", { count: gives.length });
}
