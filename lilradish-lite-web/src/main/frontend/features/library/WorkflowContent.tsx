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
  useLayoutEffect,
  useMemo,
  useRef,
  useState,
  type ComponentProps,
  type ReactNode,
  type RefObject,
} from "react";
import { useSearchParams } from "react-router";

import type { Version } from "../../api/groups/{groupId}/{kind}";
import type { DeclaredField } from "../../api/declaration";
import type { VersionDetail } from "../../api/groups/{groupId}/versions/{versionId}";
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
import { Press, type Reason } from "../../lib/action/Press";
import { RowPanel } from "../../lib/collection/RowPanel";
import { focusDrawerHeading } from "../../lib/layout/RowDrawer";
import { FIGURE_SETTING } from "../../lib/collection/SortableTable";
import { isolatedInText } from "../../lib/direction/isolated";
import { Part } from "../../lib/layout/Part";
import { UnsavedMark } from "../../lib/layout/UnsavedMark";
import { INNER_PART_SX, HEADING_SX, SPACED_SX } from "../../lib/layout/parts";
import { TOKENS } from "../../lib/theme/tokens";
import { Notice } from "../../lib/notice/Notice";
import { StatusLine, type Line } from "../../lib/notice/StatusLine";
import { useAction, type Action } from "../../lib/request/useAction";
import { useResource, type Resource } from "../../lib/request/useResource";
import { VERSION_ACT_RULES } from "./actRules";
import { BoundRows } from "./BindingControls";
import {
  ContentProblems,
  contentOf,
  type ReadContent,
} from "./ContentProblems";
import { DeclarationBuilder, KeptCandidates } from "./DeclarationBuilder";
import { fieldAtPlace, fieldPlace } from "./declarationDrafts";
import {
  useVersionDetail,
  versionDetailOwner,
  type VersionDetailOwner,
} from "./EditorVersionPicker";
import { OPEN_STEP } from "./entryAddress";
import { locatePart, useDraftPartStatus } from "./useDraftStatus";
import {
  FLOW_UNIT,
  FlowFieldLocationContext,
  FlowFieldMoveContext,
} from "./RoutePart";
import { SendsPast } from "./SendsPast";
import { StepEditor } from "./StepEditor";
import { calledOf, calledVisibleOf, kindSetApart, StepRow } from "./StepRow";
import { heldReason, StepsSaid, StepsSave } from "./StepsSaving";
import { useHeardRefusal } from "./useHeardRefusal";
import { keptStepsOf, useKeptCandidates } from "./useKeptCandidates";
import { RunSettings } from "./WorkflowSettings";
import {
  boundTo,
  flowOf,
  freshStep,
  MOST_STEPS,
  movedStep,
  namedVersions,
  removedStep,
  referencedVersionDetails,
  reusableSentFlowOf,
  sentFlowOf,
  tookVersion,
  unusedKey,
  WORKFLOW_GIVES,
  WORKFLOW_TAKES,
  type FlowDraft,
  type Named,
  type StepDraft,
} from "./workflowDrafts";
import { flowChangeAt, outputRowsOf } from "./workflowUnsaved";

export const WorkflowSubmissionRefusal = createContext<Problem | null>(null);

const QUIET_SX = { color: "text.secondary" };

const DOCUMENT_PARTS_SX = {
  "& > :not(:empty)": {
    my: 0,
    py: TOKENS.space.part,
    borderTop: 1,
    borderColor: "surface.rule",
  },
  "& section:has(> h3), & section:has(> div > h3), & [data-flow-part=steps] > div":
    { my: 0 },
};

const REMOVE_AT_END_SX = { marginInlineStart: "auto" };

const SAID_IN_DRAWER_SX = { mb: 2 };

/** Where focus goes once the rows change: a row's step or the move pressed on it, the steps' heading or the step drawer's. */
type Focus =
  | {
      readonly key: string;
      readonly at?: "up" | "down";
      readonly from?: string;
      readonly pressed?: Element | null;
    }
  | {
      readonly key: string;
      readonly from: string;
      readonly field: string;
      readonly by: -1 | 1;
      readonly owner: Element | null;
      readonly pressed: Element | null;
      readonly fallback: Element | null;
    }
  | "heading"
  | "drawerHeading";

type Half = "takes" | "gives";

/**
 * A workflow version's content, each part written on its own naming the revision the page read, and edited only
 * where the server says so. A write refused for somebody saving since keeps what was typed and offers to read afresh.
 */
export function WorkflowContent({
  groupId,
  entryId,
  version,
  editable,
  readCount,
  onRefused,
  onWritten,
  onShown,
  wrapDocument,
}: {
  readonly groupId: string;
  readonly entryId: string;
  readonly version: Version;
  readonly editable: boolean;
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
  const [halvesAfresh, setHalvesAfresh] = useState<{
    readonly count: number;
    readonly from: Half | null;
  }>({ count: 0, from: null });
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
  const candidates = useKeptCandidates();
  const loadSaved = () => {
    candidates.forgetAll();
    reload();
  };
  const readAfresh = (from: Half) => () => {
    setHalvesAfresh((now) => ({ count: now.count + 1, from }));
    loadSaved();
  };
  const actions = {
    taking: useAction<WorkflowVersion>(written, onRefused),
    giving: useAction<WorkflowVersion>(written, onRefused),
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
  const at = { groupId, entryId, versionId: version.versionId };

  return (
    <Parts
      read={{ ...read, value: shown }}
      editable={editable}
      actions={actions}
      waiting={waitingBeside}
      at={at}
      afresh={halvesAfresh}
      readAfresh={readAfresh}
      candidates={candidates}
      wrapDocument={wrapDocument}
      stale={
        shown === null || !stale ? null : (
          <Box ref={afresh} sx={SPACED_SX}>
            <Notice severity="warning" alert>
              {say("workflow.writtenSince")}
            </Notice>
            <Press onPress={loadSaved}>{say("draft.readAfresh")}</Press>
          </Box>
        )
      }
      before={
        shown === null ? null : (
          <>
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
  afresh,
  readAfresh,
  candidates,
  stale,
}: {
  readonly read: Resource<WorkflowVersion | null>;
  readonly before: ReactNode;
  /** That somebody saved since, said at the head of the step's drawer while it covers the page, and above the steps otherwise. */
  readonly stale: ReactNode;
  readonly wrapDocument?: (content: ReactNode) => ReactNode;
  readonly editable: boolean;
  readonly actions: Actions;
  /** Whether anything but the write named is out, which saving that part would be refused beside. */
  readonly waiting: (own: Action<WorkflowVersion>) => boolean;
  readonly at: At;
  /** How often a half asked to load the saved draft, and which last: each ask draws both halves anew. */
  readonly afresh: { readonly count: number; readonly from: Half | null };
  readonly readAfresh: (from: Half) => () => void;
  readonly candidates: ReturnType<typeof useKeptCandidates>;
}) {
  const loaded = read.value;
  const stepsNode = useRef<HTMLDivElement>(null);
  const outputsNode = useRef<HTMLDivElement>(null);
  const outputsHeading = useRef<HTMLHeadingElement>(null);
  const stepEditorNode = useRef<HTMLDivElement>(null);
  const [revealing, setRevealing] = useState<object | undefined>(undefined);
  const saved = useMemo(
    () => (loaded === null ? null : flowOf(loaded)),
    [loaded],
  );
  // Pin descriptions may refresh without changing the steps being edited.
  const savedAs = useMemo(
    () =>
      `${at.groupId}:${at.entryId}:${at.versionId}:${JSON.stringify(saved)}`,
    [at.groupId, at.entryId, at.versionId, saved],
  );
  const [owner, setOwner] = useState({
    source: loaded,
    flow: saved,
    details: new Map<string, VersionDetail>(),
  });
  const flow = owner.flow;
  const named = useMemo(
    () => namedWith(loaded, owner.details.values()),
    [loaded, owner.details],
  );
  const workflow = useMemo(() => {
    if (loaded === null) return null;
    const terms = new Map(loaded.terms);
    for (const detail of owner.details.values())
      for (const [id, held] of detail.terms) terms.set(id, held);
    return { ...loaded, terms };
  }, [loaded, owner.details]);
  const [readAs, setReadAs] = useState(savedAs);
  if (savedAs !== readAs) {
    setReadAs(savedAs);
    setOwner({ source: loaded, flow: saved, details: new Map() });
  } else if (owner.source !== loaded) {
    setOwner((now) => ({ ...now, source: loaded }));
  }
  // A step's field changes not yet taken in, kept while its drawer is shut;
  // a save of the steps carries them over, loading the saved draft lets them go.
  const { holding, prune } = candidates;
  const keptSteps = useMemo(
    () => keptStepsOf(holding, flow?.steps),
    [holding, flow?.steps],
  );
  const running = actions.flowing.running;
  // Kept under the keys a save answers before its steps arrive; none is let go until then.
  useEffect(() => {
    if (flow !== null && !running) prune(flow.steps);
  }, [flow, running, prune]);
  const change = useCallback(
    (next: (now: FlowDraft) => FlowDraft) => {
      if (!running) {
        setOwner((now) =>
          now.flow === null
            ? now
            : (() => {
                const flow = next(now.flow);
                return {
                  ...now,
                  flow,
                  details: referencedVersionDetails(flow, now.details),
                };
              })(),
        );
      }
    },
    [running],
  );
  const blocked = waiting(actions.flowing);
  const resolvingDisabled = !editable || running || blocked || read.loading;
  const detailInput = useMemo(
    () => ({
      source: loaded,
      savedAs,
      steps: flow?.steps,
      disabled: resolvingDisabled,
    }),
    [loaded, savedAs, flow?.steps, resolvingDisabled],
  );
  const [detailsOwned, setDetailsOwned] = useState(() => ({
    input: detailInput,
    owners: new Map<string, VersionDetailOwner>(),
  }));
  if (
    detailsOwned.input !== detailInput ||
    detailsOwned.owners.size !== (flow?.steps.length ?? 0)
  ) {
    const owners = new Map<string, VersionDetailOwner>();
    const sameAuthority =
      detailsOwned.input.source === loaded &&
      detailsOwned.input.savedAs === savedAs &&
      detailsOwned.input.disabled === resolvingDisabled;
    for (const [position, step] of (flow?.steps ?? []).entries()) {
      const before = detailsOwned.input.steps?.[position];
      const held =
        sameAuthority && before?.key === step.key && before.runs === step.runs
          ? detailsOwned.owners.get(step.key)
          : undefined;
      owners.set(step.key, held ?? versionDetailOwner());
    }
    setDetailsOwned({ input: detailInput, owners });
  }
  const resolve = useCallback(
    (
      expected: StepDraft,
      position: number,
      detail: VersionDetail | null,
      changed: (
        step: StepDraft,
        named: ReadonlyMap<string, Named>,
      ) => StepDraft,
    ) => {
      if (resolvingDisabled) return;
      setOwner((now) => {
        if (
          now.source !== loaded ||
          now.flow === null ||
          now.flow.steps[position]?.key !== expected.key ||
          now.flow.steps[position]?.runs !== expected.runs
        )
          return now;
        const details = new Map(now.details);
        if (detail !== null) details.set(detail.version.versionId, detail);
        const resolved = namedWith(loaded, [
          ...now.details.values(),
          ...(detail === null ? [] : [detail]),
        ]);
        const flow = {
          ...now.flow,
          steps: now.flow.steps.map((step) =>
            step.key === expected.key ? changed(step, resolved) : step,
          ),
        };
        return {
          ...now,
          flow,
          details: referencedVersionDetails(flow, details),
        };
      });
    },
    [loaded, resolvingDisabled],
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
  const stepsUnsaved = useMemo(
    () =>
      sent !== null &&
      savedSentAs !== null &&
      JSON.stringify(sent.steps) !== savedSentAs[0],
    [sent, savedSentAs],
  );
  const outputsUnsaved = useMemo(
    () =>
      sent !== null &&
      savedSentAs !== null &&
      JSON.stringify(sent.outputs) !== savedSentAs[1],
    [sent, savedSentAs],
  );
  const unchanged = !stepsUnsaved && !outputsUnsaved;
  const [parameters, setParameters] = useSearchParams();
  const picked = parameters.get(OPEN_STEP);
  const latestPicked = useRef(picked);
  useEffect(() => {
    latestPicked.current = picked;
  }, [picked]);
  const refusalAt = useRef<HTMLDivElement>(null);
  const heard = useHeardRefusal(actions.flowing, !unchanged, refusalAt);
  const [fieldMoved, setFieldMoved] = useState<{
    readonly step: string;
    readonly line: Line;
  } | null>(null);
  const [stepMoved, setStepMoved] = useState<Line | null>(null);
  const moveReasonId = useId();
  const moveHeld =
    sent?.held == null ? undefined : heldReason(sent.held, flow!);
  const { letGo } = heard;
  // Shutting the step's drawer, or picking another, leaves the part.
  const [pickedSeen, setPickedSeen] = useState(picked);
  if (picked !== pickedSeen) {
    setPickedSeen(picked);
    letGo();
  }
  // Said until the next change once the save or the removal it follows has landed.
  const [dropped, setDropped] = useState<{
    readonly with: FlowDraft | null;
  } | null>(null);
  const droppedSaid = dropped !== null;
  if (dropped !== null && !running && flow !== null) {
    if (dropped.with === null) setDropped({ with: flow });
    else if (dropped.with !== flow) setDropped(null);
  }
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
  const stepsHeading = useRef<HTMLHeadingElement>(null);
  const stepOpens = useRef(new Map<string, HTMLElement>());
  const stepRows = useRef(new Map<string, HTMLElement>());
  const stepFocus = useRef<Focus | null>(null);
  useEffect(() => {
    const focus = stepFocus.current;
    if (
      focus !== null &&
      typeof focus === "object" &&
      "field" in focus &&
      (picked !== focus.key ||
        !flow?.steps.some((step) => step.key === focus.key))
    ) {
      if (picked !== focus.from && picked !== focus.key)
        stepFocus.current = null;
      return;
    }
    if (
      focus !== null &&
      typeof focus === "object" &&
      "from" in focus &&
      !("field" in focus) &&
      !flow?.steps.some((step) => step.key === focus.key)
    )
      return;
    stepFocus.current = null;
    if (focus === "heading") {
      stepsHeading.current?.focus();
    } else if (focus === "drawerHeading") {
      focusDrawerHeading(stepEditorNode.current);
    } else if (focus !== null && "field" in focus) {
      const active = document.activeElement;
      if (
        picked !== focus.key ||
        (active !== document.body &&
          active !== focus.owner &&
          active !== focus.pressed &&
          active !== focus.fallback)
      )
        return;
      const row = [
        ...(stepEditorNode.current?.querySelectorAll<HTMLElement>(
          "[data-field-key]",
        ) ?? []),
      ].find((each) => each.getAttribute("data-field-key") === focus.field);
      const direction = focus.by < 0 ? "up" : "down";
      (
        row?.querySelector<HTMLElement>(`[data-part="${direction}"] button`) ??
        row?.querySelector<HTMLElement>("[data-part] button")
      )?.focus();
    } else if (focus !== null) {
      if (
        focus.from !== undefined &&
        document.activeElement !== document.body &&
        document.activeElement !== focus.pressed
      )
        return;
      (focus.at === undefined
        ? stepOpens.current.get(focus.key)
        : (stepRows.current
            .get(focus.key)
            ?.querySelector<HTMLElement>(`[data-part="${focus.at}"] button`) ??
          stepRows.current
            .get(focus.key)
            ?.querySelector<HTMLElement>("[data-part] button"))
      )?.focus();
    }
  }, [flow?.steps, picked]);
  const { forget, lend, rekey } = candidates;
  const latestSteps = useRef(flow?.steps);
  useEffect(() => {
    latestSteps.current = flow?.steps;
  }, [flow?.steps]);
  const takeOut = useCallback(
    (index: number, focusAfter: string | undefined, wasPicked: boolean) => {
      stepFocus.current =
        focusAfter === undefined ? "heading" : { key: focusAfter };
      const removed = latestSteps.current?.[index];
      if (removed !== undefined && forget(removed.key))
        setDropped({ with: null });
      if (wasPicked) {
        pick(null);
      }
      change((now) => removedStep(now, index));
    },
    [change, pick, forget],
  );
  const position = flow?.steps.findIndex((step) => step.key === picked) ?? -1;
  const step = flow?.steps[position];
  const editorScope =
    workflow === null
      ? ""
      : `${at.groupId}:${at.entryId}:${at.versionId}:${workflow.revision}:${position}`;
  const keptKind = flow?.steps.find((each) => each.key === picked)?.runs.kind;
  const keeping = useMemo(
    () =>
      picked === null || keptKind === undefined
        ? null
        : lend(picked, keptKind, `${editorScope}:${picked}:${keptKind}`),
    [picked, keptKind, editorScope, lend],
  );
  // The server answers the steps in the order sent, so the step picked keeps its place under the key it now has.
  const repick = (keys: readonly string[], answered: WorkflowVersion) =>
    latestSetParameters.current(
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
  const moving = useRef(false);
  const latestMove = useRef<
    ((key: string, index: number, by: -1 | 1) => void) | null
  >(null);
  useLayoutEffect(() => {
    latestMove.current = (key, index, by) => {
      if (
        moving.current ||
        !editable ||
        running ||
        blocked ||
        read.loading ||
        flow === null ||
        workflow === null ||
        sent?.held !== null ||
        flow.steps[index]?.key !== key
      )
        return;
      const proposed = movedStep(flow, index, by);
      if (proposed === flow) return;
      const compiled = sentFlowOf(proposed, workflow, named);
      if (compiled.held !== null) return;
      const keys = proposed.steps.map((step) => step.key);
      const nextIndex = keys.indexOf(key);
      const pressed = document.activeElement;
      let ownsFocus = true;
      const movedAway = (event: FocusEvent) => {
        if (event.target !== document.body && event.target !== pressed)
          ownsFocus = false;
      };
      const pointedAway = (event: PointerEvent) => {
        if (!(event.target instanceof Node && pressed?.contains(event.target)))
          ownsFocus = false;
      };
      const tabbedAway = (event: KeyboardEvent) => {
        if (event.key === "Tab" || event.key === "Escape") ownsFocus = false;
      };
      document.addEventListener("focusin", movedAway);
      document.addEventListener("pointerdown", pointedAway);
      document.addEventListener("keydown", tabbedAway);
      moving.current = true;
      setStepMoved(null);
      heard.sent();
      actions.flowing.run(async (signal) => {
        try {
          const answered = await writeSteps(
            at.groupId,
            at.entryId,
            at.versionId,
            workflow.revision,
            compiled.steps,
            compiled.outputs,
            signal,
          );
          if (!signal.aborted) {
            const moved = answered.steps[nextIndex];
            repick(keys, answered);
            if (
              rekey(
                keys,
                answered.steps.map((step) => step.stepId),
              )
            )
              setDropped({ with: null });
            if (moved !== undefined) {
              setStepMoved({
                severity: "info",
                words: say("declaration.moved", {
                  label: isolatedInText(moved.label || moved.name),
                  position: nextIndex + 1,
                  count: answered.steps.length,
                }),
              });
              if (ownsFocus)
                stepFocus.current = {
                  key: moved.stepId,
                  from: key,
                  pressed,
                  at: by < 0 ? "up" : "down",
                };
            }
          }
          return answered;
        } finally {
          moving.current = false;
          document.removeEventListener("focusin", movedAway);
          document.removeEventListener("pointerdown", pointedAway);
          document.removeEventListener("keydown", tabbedAway);
        }
      });
    };
  });
  const move = useCallback(
    (key: string, index: number, by: -1 | 1) =>
      latestMove.current?.(key, index, by),
    [],
  );
  const keptAny = keptSteps.size > 0;
  useDraftPartStatus(
    "flow",
    !unchanged || keptAny,
    running || waiting(actions.flowing),
    {
      saving: running,
      unit: FLOW_UNIT,
      node: stepsUnsaved || keptAny ? stepsNode : outputsNode,
      locate: () => {
        if (
          flow === null ||
          saved === null ||
          workflow === null ||
          sent === null ||
          savedSent === null
        )
          return;
        const changedAt = flowChangeAt({
          flow,
          saved,
          sent,
          savedSent,
          stepsUnsaved,
          keptSteps,
          workflow,
          named,
        });
        if (changedAt.at === "steps") {
          if (stepsNode.current !== null) locatePart(stepsNode.current);
        } else if (changedAt.at === "step") {
          pick(changedAt.key);
          setRevealing({
            focus: () => {
              const editor = stepEditorNode.current;
              if (editor === null) return;
              const control = changedAt.control;
              const target =
                control?.part === "binding"
                  ? editor.querySelector<HTMLElement>(
                      `[data-path="${CSS.escape(control.path!)}"]`,
                    )
                  : control === undefined
                    ? null
                    : editor.querySelector<HTMLElement>(
                        `[data-flow-control="${control.part}"]`,
                      );
              if (target !== null && control?.label !== undefined) {
                const label = [
                  ...target.querySelectorAll<HTMLElement>("label"),
                ].find((each) => each.textContent === say(control.label!));
                const id = label?.id;
                const labelled = label?.getAttribute("for");
                const input =
                  (labelled == null
                    ? null
                    : document.getElementById(labelled)) ??
                  (id === undefined
                    ? null
                    : target.querySelector<HTMLElement>(
                        `[aria-labelledby~="${CSS.escape(id)}"]`,
                      ));
                locatePart(input ?? target);
              } else
                locatePart(
                  (control?.bindingControl === undefined
                    ? null
                    : target?.querySelector<HTMLElement>(
                        `[data-binding-control="${control.bindingControl}"]`,
                      )) ??
                    target ??
                    editor,
                );
            },
          });
        } else {
          const row =
            changedAt.path === null
              ? null
              : outputsNode.current?.querySelector<HTMLElement>(
                  `tbody > tr[data-path="${CSS.escape(changedAt.path)}"]`,
                );
          if (row === null || row === undefined)
            outputsHeading.current?.focus();
          else
            locatePart(
              (changedAt.control === undefined
                ? null
                : row.querySelector<HTMLElement>(
                    `[data-binding-control="${changedAt.control}"]`,
                  )) ?? row,
            );
        }
      },
    },
  );
  const stepOpen = read.problem === null && step !== undefined;
  // One save, drawn on the page and at the foot of the step's drawer, which
  // covers the page's while it is open; both read the one draft and action.
  const stepsSave =
    workflow === null || flow === null || sent === null ? null : (
      <StepsSave
        unsaved={!unchanged}
        action={actions.flowing}
        waiting={waiting(actions.flowing)}
        flow={flow}
        held={sent.held}
        act={(signal) => {
          heard.sent();
          const keys = flow.steps.map((step) => step.key);
          const fromPage = !stepOpen;
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
            if (
              rekey(
                keys,
                answered.steps.map((each) => each.stepId),
              )
            )
              setDropped({ with: null });
            stepFocus.current = fromPage ? "heading" : "drawerHeading";
            return answered;
          });
        }}
      />
    );
  const saving = stepsSave !== null && (!unchanged || running);
  useEffect(() => {
    if (heard.problem !== null)
      refusalAt.current?.scrollIntoView?.({ block: "start" });
  }, [heard.problem, refusalAt]);
  const stepsSaid =
    !running && heard.problem === null && !droppedSaid ? null : (
      <StepsSaid
        running={running}
        refusal={heard.problem}
        settled={heard.settled}
        dropped={droppedSaid}
        workflow={workflow}
      />
    );
  const contents =
    workflow === null || flow === null || sent === null ? null : (
      <>
        <DeclarationBuilder
          key={`takes:${afresh.count}`}
          title={say("workflow.takes")}
          heading="h3"
          depth={2}
          demands={WORKFLOW_TAKES}
          fields={workflow.takes}
          scope={`${at.groupId}:${at.entryId}:${at.versionId}:takes`}
          editable={editable}
          waiting={waiting(actions.taking)}
          saveLabel={say("workflow.saveTakes")}
          underway={say("workflow.underway")}
          rule={VERSION_ACT_RULES.write}
          action={actions.taking}
          focusOnArrival={afresh.from === "takes"}
          onReadAfresh={readAfresh("takes")}
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
        <Box ref={stepsNode} data-flow-part="steps">
          <StepsPart
            detailOwners={detailsOwned.owners}
            flow={flow}
            workflow={workflow}
            named={named}
            editable={editable}
            running={running || blocked || read.loading}
            moveReason={moveHeld}
            moveReasonId={moveReasonId}
            moved={stepMoved}
            unsaved={stepsUnsaved}
            picked={picked}
            pick={pick}
            change={change}
            heading={stepsHeading}
            opens={stepOpens}
            rows={stepRows}
            move={move}
            scope={`${at.groupId}:${at.entryId}:${at.versionId}:${workflow.revision}`}
            resolvingDisabled={resolvingDisabled}
            resolve={resolve}
          />
        </Box>
        <DeclarationBuilder
          key={`gives:${afresh.count}`}
          title={say("workflow.gives")}
          hint={say("workflow.givesHint")}
          heading="h3"
          depth={2}
          demands={WORKFLOW_GIVES}
          fields={workflow.gives}
          scope={`${at.groupId}:${at.entryId}:${at.versionId}:gives`}
          editable={editable}
          waiting={waiting(actions.giving)}
          saveLabel={say("workflow.saveGives")}
          underway={say("workflow.underway")}
          rule={VERSION_ACT_RULES.write}
          action={actions.giving}
          focusOnArrival={afresh.from === "gives"}
          onReadAfresh={readAfresh("gives")}
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
        <Box ref={outputsNode}>
          <OutputsPart
            flow={flow}
            workflow={workflow}
            named={named}
            editable={editable}
            unsaved={outputsUnsaved}
            change={change}
            headingRef={outputsHeading}
          />
        </Box>
        {editable && (saving || stepsSaid !== null) ? (
          <Box sx={SPACED_SX}>
            {stepsSave}
            {stepOpen || stepsSaid === null ? null : (
              <div ref={refusalAt} tabIndex={-1}>
                {stepsSaid}
              </div>
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
            {stepOpen ? null : stale}
            {before}
            <Box
              sx={wrapDocument === undefined ? undefined : DOCUMENT_PARTS_SX}
            >
              {contents}
            </Box>
          </>
        )
      }
    </Async>
  );
  const rowDocument = (
    <RowPanel
      over
      reveal={revealing}
      open={stepOpen}
      onClose={() => pick(null)}
      title={step === undefined ? "" : calledOf(step, position)}
      visibleTitle={
        step === undefined ? undefined : calledVisibleOf(step, position)
      }
      headingLevel="h4"
      besideHeading={step === undefined ? undefined : kindSetApart(step)}
      list={list}
      listLabel={say("workflow.steps")}
      listRole={contents === null ? "region" : "group"}
      above={
        stepOpen ? (
          <>
            {stale !== null || stepsSaid !== null ? (
              <Box ref={refusalAt} tabIndex={-1} sx={SAID_IN_DRAWER_SX}>
                {stale}
                {stepsSaid}
              </Box>
            ) : null}
            <StatusLine
              said={
                fieldMoved?.step === step?.key
                  ? (fieldMoved?.line ?? null)
                  : null
              }
            />
          </>
        ) : null
      }
      foot={
        !editable || flow === null ? undefined : (
          <>
            {stepsSave}
            {unchanged ? null : <UnsavedMark />}
            <Box sx={REMOVE_AT_END_SX}>
              <Press
                tier="quiet"
                unavailable={running}
                onPress={() =>
                  takeOut(
                    position,
                    (flow.steps[position + 1] ?? flow.steps[position - 1])?.key,
                    true,
                  )
                }
              >
                {say("workflow.removeThis")}
              </Press>
            </Box>
          </>
        )
      }
    >
      {read.problem !== null ||
      step === undefined ||
      flow === null ||
      workflow === null ? null : (
        <FlowFieldMoveContext.Provider
          value={{
            action: actions.flowing,
            waiting: blocked,
            reason:
              sent?.held == null ? undefined : heldReason(sent.held, flow),
            save: async (stepKey, gives, signal, focus) => {
              if (!editable || running || blocked)
                throw new Error(
                  "The steps cannot be written while another write is out",
                );
              const moving = flow.steps.find((each) => each.key === stepKey);
              if (moving?.runs.kind !== "route")
                throw new Error("The route is no longer in these steps");
              const proposed = {
                ...flow,
                steps: flow.steps.map((each) =>
                  each === moving
                    ? { ...each, runs: { ...moving.runs, gives: [...gives] } }
                    : each,
                ),
              };
              const compiled = sentFlowOf(proposed, workflow, named);
              if (compiled.held !== null)
                throw new Error("The steps do not hold as written");
              const place = fieldPlace(gives, focus.key);
              const path = place?.map(
                (_, index) =>
                  fieldAtPlace(gives, place.slice(0, index + 1))!.name,
              );
              const pressed = document.activeElement;
              const owner =
                stepEditorNode.current?.closest('[role="dialog"]') ?? null;
              const half = pressed?.closest("section");
              const fallback = document.getElementById(
                half?.getAttribute("aria-labelledby") ?? "",
              );
              let ownsFocus = true;
              const movedAway = (event: FocusEvent) => {
                if (
                  event.target !== document.body &&
                  event.target !== owner &&
                  event.target !== pressed &&
                  event.target !== fallback
                )
                  ownsFocus = false;
              };
              const pointedElsewhere = () => {
                ownsFocus = false;
              };
              const tabbedAway = (event: KeyboardEvent) => {
                if (event.key === "Tab" || event.key === "Escape")
                  ownsFocus = false;
              };
              document.addEventListener("focusin", movedAway);
              document.addEventListener("pointerdown", pointedElsewhere);
              document.addEventListener("keydown", tabbedAway);
              setFieldMoved(null);
              heard.sent();
              const keys = flow.steps.map((each) => each.key);
              try {
                const answered = await writeSteps(
                  at.groupId,
                  at.entryId,
                  at.versionId,
                  workflow.revision,
                  compiled.steps,
                  compiled.outputs,
                  signal,
                );
                if (!signal.aborted) {
                  const step = answered.steps[keys.indexOf(stepKey)];
                  let fields =
                    step?.runs?.kind === "route" ? step.runs.gives : undefined;
                  let field: DeclaredField | undefined;
                  let position = 0;
                  let count = 0;
                  for (const name of path ?? []) {
                    const index =
                      fields?.findIndex((each) => each.name === name) ?? -1;
                    position = index + 1;
                    count = fields?.length ?? 0;
                    field = fields?.[index];
                    fields = field?.fields;
                  }
                  if (field !== undefined && step !== undefined)
                    setFieldMoved({
                      step: step.stepId,
                      line: {
                        severity: "info",
                        words: say("declaration.moved", {
                          label: isolatedInText(field.label || field.name),
                          position,
                          count,
                        }),
                      },
                    });
                  if (
                    ownsFocus &&
                    latestPicked.current === stepKey &&
                    step !== undefined &&
                    field !== undefined
                  )
                    stepFocus.current = {
                      key: step.stepId,
                      from: stepKey,
                      field: field.fieldId,
                      by: focus.by,
                      owner,
                      pressed,
                      fallback,
                    };
                  repick(keys, answered);
                  if (
                    rekey(
                      keys,
                      answered.steps.map((each) => each.stepId),
                    )
                  )
                    setDropped({ with: null });
                }
                return answered;
              } finally {
                document.removeEventListener("focusin", movedAway);
                document.removeEventListener("pointerdown", pointedElsewhere);
                document.removeEventListener("keydown", tabbedAway);
              }
            },
          }}
        >
          <FlowFieldLocationContext.Provider value={() => setRevealing({})}>
            <KeptCandidates.Provider value={keeping}>
              <div ref={stepEditorNode}>
                <StepEditor
                  details={owner.details}
                  detailOwner={detailsOwned.owners.get(step.key)}
                  key={step.key}
                  step={step}
                  position={position}
                  flow={flow}
                  workflow={workflow}
                  named={named}
                  editable={editable && !running}
                  change={(changed) => changeStep(step.key, changed)}
                  scope={editorScope}
                  resolve={(detail, changed) =>
                    resolve(step, position, detail, changed)
                  }
                />
              </div>
            </KeptCandidates.Provider>
          </FlowFieldLocationContext.Provider>
        </FlowFieldMoveContext.Provider>
      )}
    </RowPanel>
  );
  return wrapDocument === undefined ? rowDocument : wrapDocument(rowDocument);
}

/**
 * The steps in the order they run; nothing but moving one reorders them.
 */
function StepsPart({
  detailOwners,
  flow,
  workflow,
  named,
  editable,
  running,
  unsaved,
  picked,
  pick,
  change,
  heading,
  opens,
  rows,
  move,
  moveReason,
  moveReasonId,
  moved,
  scope,
  resolvingDisabled,
  resolve,
}: {
  /** Where the keyboard goes once the rows change, which its host decides for the step's drawer too. */
  readonly heading: RefObject<HTMLHeadingElement | null>;
  readonly opens: RefObject<Map<string, HTMLElement>>;
  readonly rows: RefObject<Map<string, HTMLElement>>;
  readonly move: (key: string, index: number, by: -1 | 1) => void;
  readonly moveReason?: Reason;
  readonly moveReasonId: string;
  readonly moved: Line | null;
  readonly detailOwners: ReadonlyMap<string, VersionDetailOwner>;
  readonly flow: FlowDraft;
  readonly workflow: WorkflowVersion;
  readonly named: ReadonlyMap<string, Named>;
  readonly editable: boolean;
  readonly running: boolean;
  readonly unsaved: boolean;
  readonly picked: string | null;
  readonly pick: (key: string | null) => void;
  readonly change: (next: (now: FlowDraft) => FlowDraft) => void;
  readonly scope: string;
  readonly resolvingDisabled: boolean;
  readonly resolve: (
    expected: StepDraft,
    position: number,
    detail: VersionDetail | null,
    change: (step: StepDraft, named: ReadonlyMap<string, Named>) => StepDraft,
  ) => void;
}) {
  const headingId = useId();
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
    [rows],
  );
  const registerOpen = useCallback(
    (key: string, open: HTMLElement | null) =>
      setOrDelete(opens.current, key, open),
    [opens],
  );
  // No region of its own: the panel names the list.
  const table = (
    <Box sx={INNER_PART_SX}>
      <Box
        sx={{
          display: "flex",
          alignItems: "center",
          gap: 1,
          flexWrap: "wrap",
          ...HEADING_SX,
        }}
      >
        <Typography
          id={headingId}
          ref={heading}
          tabIndex={-1}
          variant="h3"
          sx={{ ...HEADING_SX, mb: 0 }}
        >
          {say("workflow.steps")}
        </Typography>
        {editable && unsaved ? <UnsavedMark /> : null}
      </Box>
      <StatusLine said={moved} />
      {moveReason === undefined ? null : (
        <Notice id={moveReasonId} severity={moveReason.severity}>
          {moveReason.visible ?? moveReason.words}
        </Notice>
      )}
      {flow.steps.length === 0 ? (
        <Box component="details">
          <Typography component="summary" variant="body2" sx={QUIET_SX}>
            {say("workflow.stepsNone")}
          </Typography>
          {/* Submitting refuses a workflow with no step; said where the steps are typed, before any is saved. */}
          <Typography variant="body2">
            {say("contentProblem.no_steps")}
          </Typography>
        </Box>
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
                <TableCell data-compact="true">
                  {say("workflow.produces")}
                </TableCell>
                <TableCell data-compact="true">
                  {say("workflow.reviewedBy")}
                </TableCell>
                <TableCell data-compact="true">
                  {say("workflow.givesBack")}
                </TableCell>
                {editable ? <TableCell data-compact="true" /> : null}
              </TableRow>
            </TableHead>
            <TableBody>
              {flow.steps.map((each, index) => (
                <ResolvedStepRow
                  key={each.key}
                  detailOwner={detailOwners.get(each.key)}
                  step={each}
                  position={index}
                  last={index === flow.steps.length - 1}
                  picked={each.key === picked}
                  href={hrefOf(each.key)}
                  named={named}
                  codeSteps={workflow.offered.codeSteps}
                  editable={editable}
                  running={running}
                  moveHeld={moveReason !== undefined}
                  moveReasonId={moveReasonId}
                  onRegisterRow={registerRow}
                  onRegisterOpen={registerOpen}
                  onMove={move}
                  scope={scope}
                  resolvingDisabled={resolvingDisabled}
                  resolve={resolve}
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

function ResolvedStepRow({
  detailOwner,
  scope,
  resolvingDisabled,
  resolve,
  ...row
}: Omit<ComponentProps<typeof StepRow>, "onTakeVersion"> & {
  readonly detailOwner?: VersionDetailOwner;
  readonly scope: string;
  readonly resolvingDisabled: boolean;
  readonly resolve: (
    expected: StepDraft,
    position: number,
    detail: VersionDetail | null,
    change: (step: StepDraft, named: ReadonlyMap<string, Named>) => StepDraft,
  ) => void;
}) {
  const runs = row.step.runs;
  const detail = useVersionDetail({
    owner: detailOwner,
    kind: runs.kind === "workflow" ? "workflow" : "question",
    scope: `${scope}:${row.step.key}:${row.position}:${runs.kind === "question" || runs.kind === "workflow" || runs.kind === "search" ? runs.version : ""}:${runs.kind === "search" ? runs.searchWay : ""}`,
    disabled: resolvingDisabled,
    searchWay: runs.kind === "search" ? runs.searchWay : undefined,
    onRead: (chosen) =>
      resolve(row.step, row.position, chosen, (now, named) =>
        tookVersion(now, chosen?.version.versionId ?? "", named),
      ),
  });
  const readDetail = detail.read;
  const takeVersion = useCallback(
    (_key: string, versionId: string) => readDetail(versionId),
    [readDetail],
  );
  return (
    <>
      <StepRow {...row} onTakeVersion={takeVersion} />
      {detail.problem === null ? null : (
        <TableRow>
          <TableCell colSpan={row.editable ? 7 : 6}>
            <ProblemView problem={detail.problem} />
          </TableCell>
        </TableRow>
      )}
    </>
  );
}

/** The versions the workflow read names, with those read since for its steps over them. */
function namedWith(
  loaded: WorkflowVersion | null,
  details: Iterable<VersionDetail>,
): Map<string, Named> {
  const named =
    loaded === null ? new Map<string, Named>() : namedVersions(loaded);
  for (const detail of details) {
    if (detail.kind !== "reference_list")
      named.set(detail.version.versionId, {
        ...detail.version,
        takes: detail.takes,
        gives: detail.gives,
        pinned: detail.version,
      });
  }
  return named;
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
  headingRef,
}: {
  readonly flow: FlowDraft;
  readonly workflow: WorkflowVersion;
  readonly named: ReadonlyMap<string, Named>;
  readonly editable: boolean;
  readonly unsaved: boolean;
  readonly change: (next: (now: FlowDraft) => FlowDraft) => void;
  readonly headingRef: RefObject<HTMLHeadingElement | null>;
}) {
  const { rows, sources } = outputRowsOf(flow, workflow, named);
  return (
    <Part
      title={say("workflow.outputs")}
      unsaved={editable && unsaved}
      level="h3"
      depth={2}
      headingRef={headingRef}
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
