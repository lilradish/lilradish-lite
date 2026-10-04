import ArrowDownwardIcon from "@mui/icons-material/ArrowDownward";
import ArrowUpwardIcon from "@mui/icons-material/ArrowUpward";
import DeleteOutlinedIcon from "@mui/icons-material/DeleteOutlined";
import Box from "@mui/material/Box";
import Checkbox from "@mui/material/Checkbox";
import FormControlLabel from "@mui/material/FormControlLabel";
import MenuItem from "@mui/material/MenuItem";
import TextField from "@mui/material/TextField";
import Typography from "@mui/material/Typography";
import { memo, useCallback, useEffect, useRef, type ReactNode } from "react";

import type { DeclaredField } from "../../api/declaration";
import type { ContentProblem } from "../../api/groups/{groupId}/{kind}/{entryId}/versions";
import type {
  HeldModel,
  WorkflowVersion,
} from "../../api/groups/{groupId}/{kind}/{entryId}/versions/{versionId}";
import { say, type MessageId } from "../../i18n/app";
import { IconPress } from "../../lib/action/IconPress";
import { Press } from "../../lib/action/Press";
import { GROUP_SX, SPACED_SX } from "../../lib/layout/parts";
import { Notice } from "../../lib/notice/Notice";
import { fieldNameFits } from "../../lib/text/legibility";
import { Inputs, sourceVisibleOf } from "./BindingControls";
import { problemSaid, problemWords } from "./ContentProblems";
import { ModelControls, modelSaid } from "./ModelControls";
import { PinNote, versionSaid } from "./PinNote";
import { RoutePart } from "./RoutePart";
import {
  boundTo,
  flagsOf,
  pointsForward,
  pointers,
  searchTextRefused,
  shapeHasFile,
  sourcesFor,
  takesOf,
  targetRows,
  termsChosenBy,
  tookVersion,
  triesFit,
  unusedKey,
  type BindingDraft,
  type FlowDraft,
  type Named,
  type RunsDraft,
  type SearchDraft,
  type SearchPartDraft,
  type SourceOffered,
  type StepDraft,
} from "./workflowDrafts";

const ROW_SX = {
  display: "flex",
  flexWrap: "wrap",
  gap: 2,
  alignItems: "center",
  mb: 2,
};

const FIELDS_SX = {
  display: "flex",
  flexDirection: "column",
  alignItems: "stretch",
  gap: 2,
  mb: 2,
  "& > .MuiFormControl-root": { width: "100%", minWidth: 0 },
};
const GROUPED_FIELDS_SX = { ...FIELDS_SX, ...GROUP_SX };
const SECTION_HEADING_SX = { mb: 2 };

const QUIET_SX = { color: "text.secondary" };

const RUNS_WORDS = {
  none: "workflow.runsChoose",
  question: "workflow.runsQuestion",
  workflow: "workflow.runsWorkflow",
  code_step: "workflow.runsCodeStep",
  route: "workflow.runsRoute",
  search: "workflow.runsSearch",
} as const satisfies Record<RunsDraft["kind"], MessageId>;

/**
 * One step as the panel beside the steps draws it: what it runs, who produces and may review its values, and
 * what fills each input; edited where the draft may be, and what submitting would refuse said where it is.
 */
export function StepEditor({
  step,
  position,
  flow,
  workflow,
  named,
  editable,
  change,
}: {
  readonly step: StepDraft;
  readonly position: number;
  readonly flow: FlowDraft;
  readonly workflow: WorkflowVersion;
  readonly named: ReadonlyMap<string, Named>;
  readonly editable: boolean;
  readonly change: (change: (step: StepDraft) => StepDraft) => void;
}) {
  const latestChange = useRef(change);
  useEffect(() => {
    latestChange.current = change;
  }, [change]);
  const changeSearch = useCallback(
    (update: (runs: SearchDraft) => SearchDraft) =>
      latestChange.current((now) =>
        now.runs.kind === "search" ? { ...now, runs: update(now.runs) } : now,
      ),
    [],
  );
  const set = (changed: Partial<StepDraft>) =>
    change((now) => ({ ...now, ...changed }));
  const models = workflow.offered.models;
  const searchWay =
    step.runs.kind === "search" ? step.runs.searchWay : undefined;
  const selectedWay =
    searchWay === undefined
      ? undefined
      : workflow.offered.searchWays.find((way) => way.name === searchWay);
  const producerModels =
    step.runs.kind !== "search"
      ? models
      : selectedWay === undefined
        ? []
        : models.filter((model) => model.tools.includes(selectedWay.tool));
  const sources = sourcesFor(flow, position, workflow, named, step);
  const rowsOf = (
    takes: readonly DeclaredField[],
    bindings: readonly BindingDraft[],
    problems: readonly ContentProblem[],
  ) =>
    targetRows(
      takes,
      bindings,
      problems,
      (binding, target) =>
        flagsOf(binding, target, flow, position, problems, sources),
      "contentProblem.input_unbound",
    );
  const produces =
    step.runs.kind === "question" ||
    step.runs.kind === "code_step" ||
    step.runs.kind === "search";
  const here = workflow.problems.filter(
    (problem) =>
      problem.stepId === step.stepId &&
      problem.caseId === undefined &&
      problem.fieldId === undefined &&
      problem.bindingId === undefined,
  );
  const takes = takesOf(step, named, workflow.offered.codeSteps);
  const discriminatorId =
    step.runs.kind === "route" ? step.runs.discriminatorId : undefined;
  return (
    <Box>
      {here.length === 0 ? null : (
        <Box sx={SPACED_SX}>
          <Typography variant="subtitle2">
            {say("workflow.problemsHere")}
          </Typography>
          {here.map((problem, index) => (
            <Notice key={`${index}:${problem.code}`} severity="warning">
              {problemSaid(
                problem,
                step.runs.kind === "none" ? undefined : step.runs.kind,
              )}
            </Notice>
          ))}
        </Box>
      )}
      <Box sx={FIELDS_SX}>
        {editable ? (
          <TextField
            fullWidth
            size="small"
            label={say("workflow.stepName")}
            value={step.name}
            autoFocus={step.stepId === null && step.name === ""}
            error={step.name !== "" && !fieldNameFits(step.name)}
            helperText={
              step.name !== "" && !fieldNameFits(step.name)
                ? say("refusal.STEP_NAME_UNUSABLE")
                : undefined
            }
            onChange={(edit) => set({ name: edit.target.value })}
          />
        ) : null}
        {editable ? (
          <TextField
            fullWidth
            select
            size="small"
            label={say("workflow.stepRuns")}
            value={step.runs.kind}
            onChange={(edit) =>
              set({
                runs: freshRuns(edit.target.value as RunsDraft["kind"]),
                bindings: [],
              })
            }
          >
            {(Object.keys(RUNS_WORDS) as RunsDraft["kind"][]).map((kind) => (
              <MenuItem key={kind} value={kind}>
                {say(RUNS_WORDS[kind])}
              </MenuItem>
            ))}
          </TextField>
        ) : null}
        <RunsChosen
          step={step}
          workflow={workflow}
          named={named}
          editable={editable}
          change={(runs) =>
            change((now) => ({
              ...now,
              runs,
              bindings: [],
              ...(runs.kind === "search" &&
              now.runs.kind === "search" &&
              runs.searchWay !== now.runs.searchWay
                ? { model: "", mode: "" }
                : {}),
            }))
          }
          take={(versionId) =>
            change((now) => tookVersion(now, versionId, named))
          }
        />
      </Box>
      {produces ? (
        <>
          <ProducerPart
            step={step}
            models={producerModels}
            editable={editable}
            set={set}
          />
          <ReviewerPart
            step={step}
            models={models}
            editable={editable}
            set={set}
          />
        </>
      ) : null}
      {step.runs.kind === "search" ? (
        <SearchQuestion
          runs={step.runs}
          sources={sources}
          problems={workflow.problems}
          flow={flow}
          position={position}
          editable={editable}
          sourceSignature={JSON.stringify(sources)}
          orderSignature={JSON.stringify(flow.steps.map((each) => each.key))}
          change={changeSearch}
        />
      ) : step.runs.kind === "route" ? (
        <RoutePart
          runs={step.runs}
          stepId={step.stepId}
          workflow={workflow}
          named={named}
          sources={sources}
          chosenBy={[
            ...(pointsForward(flow, position, step.runs.discriminator)
              ? (["workflow.pointsForward"] as const)
              : []),
            ...workflow.problems
              .filter(
                (problem) =>
                  problem.bindingId !== undefined &&
                  problem.bindingId === discriminatorId,
              )
              .map(problemWords),
          ]}
          terms={termsChosenBy(step.runs, flow, workflow, named)}
          rowsOf={rowsOf}
          editable={editable}
          change={(runs) => set({ runs })}
        />
      ) : (
        <Inputs
          heading="workflow.inputs"
          level="h5"
          depth={3}
          rows={
            takes === undefined
              ? undefined
              : rowsOf(
                  takes,
                  step.bindings,
                  workflow.problems.filter(
                    (problem) =>
                      problem.stepId === step.stepId &&
                      problem.caseId === undefined,
                  ),
                )
          }
          sources={sources}
          editable={editable}
          change={(target, source) =>
            change((now) => ({
              ...now,
              bindings: boundTo(now.bindings, target, source),
            }))
          }
          unknown={
            step.runs.kind === "code_step" && step.runs.codeStep !== ""
              ? "workflow.codeStepUndeclared"
              : "workflow.inputsUnknown"
          }
        />
      )}
    </Box>
  );
}

/** Nothing chosen within a kind yet: a version, a code step, or a route with no case. */
function freshRuns(kind: RunsDraft["kind"]): RunsDraft {
  switch (kind) {
    case "none":
      return { kind: "none" };
    case "question":
    case "workflow":
      return { kind, version: "" };
    case "code_step":
      return { kind: "code_step", codeStep: "" };
    case "search":
      return {
        kind: "search",
        searchWay: "",
        question: [],
        standings: { title: "", body: "", references: "", other: "" },
      };
    case "route":
      return {
        kind: "route",
        discriminator: { from: "none" },
        gives: [],
        cases: [],
      };
  }
}

/** Which one of its kind a step runs; the kind itself the panel's head sets apart, so a route here says nothing. */
function RunsChosen({
  step,
  workflow,
  named,
  editable,
  change,
  take,
}: {
  readonly step: StepDraft;
  readonly workflow: WorkflowVersion;
  readonly named: ReadonlyMap<string, Named>;
  readonly editable: boolean;
  readonly change: (runs: RunsDraft) => void;
  readonly take: (versionId: string) => void;
}) {
  const runs = step.runs;
  if (runs.kind === "question" || runs.kind === "workflow") {
    const offered =
      runs.kind === "question"
        ? workflow.offered.questions
        : workflow.offered.workflows;
    const choices = [
      ...(runs.version === "" ||
      offered.some((each) => each.versionId === runs.version)
        ? []
        : [runs.version]),
      ...offered.map((each) => each.versionId),
    ];
    const pinned = named.get(runs.version)?.pinned;
    return (
      <>
        {editable ? (
          <TextField
            fullWidth
            select
            size="small"
            label={say("workflow.version")}
            value={runs.version}
            onChange={(edit) =>
              change({ kind: runs.kind, version: edit.target.value })
            }
          >
            <MenuItem value="">{say("workflow.versionNone")}</MenuItem>
            {choices.map((versionId) => (
              <MenuItem key={versionId} value={versionId}>
                {versionSaid(named.get(versionId), versionId)}
              </MenuItem>
            ))}
          </TextField>
        ) : (
          <Typography variant="body2">
            {runs.version === ""
              ? say("workflow.runsNothing")
              : versionSaid(named.get(runs.version), runs.version)}
          </Typography>
        )}
        {pinned === undefined ? null : (
          <PinNote pinned={pinned} take={editable ? take : undefined} />
        )}
      </>
    );
  }
  if (runs.kind === "search") {
    const offered = workflow.offered.searchWays;
    const selectedWay = offered.find((way) => way.name === runs.searchWay);
    const names = [
      ...(runs.searchWay === "" ||
      offered.some((way) => way.name === runs.searchWay)
        ? []
        : [runs.searchWay]),
      ...offered.map((way) => way.name),
    ];
    return editable ? (
      <TextField
        fullWidth
        select
        size="small"
        label={say("workflow.searchWay")}
        value={runs.searchWay}
        onChange={(edit) => change({ ...runs, searchWay: edit.target.value })}
      >
        <MenuItem value="">{say("workflow.searchWayNone")}</MenuItem>
        {names.map((name) => {
          const way = offered.find((each) => each.name === name);
          return (
            <MenuItem key={name} value={name}>
              <Box>
                <Typography variant="body2">{name}</Typography>
                {way === undefined ? null : (
                  <Typography variant="caption" sx={QUIET_SX}>
                    {say("search.wayNeeds", { tool: way.tool })}
                  </Typography>
                )}
              </Box>
            </MenuItem>
          );
        })}
      </TextField>
    ) : (
      <Box>
        <Typography variant="body2">
          {runs.searchWay === ""
            ? say("workflow.searchWayNone")
            : runs.searchWay}
        </Typography>
        {selectedWay === undefined ? null : (
          <Typography variant="caption" sx={QUIET_SX}>
            {say("search.wayNeeds", { tool: selectedWay.tool })}
          </Typography>
        )}
      </Box>
    );
  }
  if (runs.kind === "code_step") {
    const offered = workflow.offered.codeSteps.map((codeStep) => codeStep.name);
    const choices =
      runs.codeStep === "" || offered.includes(runs.codeStep)
        ? offered
        : [runs.codeStep, ...offered];
    return editable ? (
      <TextField
        fullWidth
        select
        size="small"
        label={say("workflow.codeStep")}
        value={runs.codeStep}
        onChange={(edit) =>
          change({ kind: "code_step", codeStep: edit.target.value })
        }
      >
        <MenuItem value="">{say("workflow.codeStepNone")}</MenuItem>
        {choices.map((name) => (
          <MenuItem key={name} value={name}>
            {name}
          </MenuItem>
        ))}
      </TextField>
    ) : (
      <Typography variant="body2">
        {runs.codeStep === "" ? say("workflow.runsNothing") : runs.codeStep}
      </Typography>
    );
  }
  return editable || runs.kind === "route" ? null : (
    <Typography variant="body2">{say("workflow.runsNothing")}</Typography>
  );
}

const SearchQuestion = memo(
  function SearchQuestion({
    runs,
    sources,
    problems,
    flow,
    position,
    editable,
    sourceSignature,
    change,
  }: {
    readonly runs: SearchDraft;
    readonly sources: readonly SourceOffered[];
    readonly problems: readonly ContentProblem[];
    readonly flow: FlowDraft;
    readonly position: number;
    readonly editable: boolean;
    readonly sourceSignature: string;
    readonly orderSignature: string;
    readonly change: (update: (runs: SearchDraft) => SearchDraft) => void;
  }) {
    const changePart = useCallback(
      (index: number, part: SearchPartDraft) =>
        change((current) => ({
          ...current,
          question: current.question.map((held, at) =>
            at === index ? part : held,
          ),
        })),
      [change],
    );
    const movePart = useCallback(
      (index: number, by: -1 | 1) =>
        change((current) => {
          const there = index + by;
          if (there < 0 || there >= current.question.length) return current;
          const question = [...current.question];
          [question[index], question[there]] = [
            question[there]!,
            question[index]!,
          ];
          return { ...current, question };
        }),
      [change],
    );
    const removePart = useCallback(
      (index: number) =>
        change((current) => ({
          ...current,
          question: current.question.filter((_part, at) => at !== index),
        })),
      [change],
    );
    const sourceByValue = new Map(
      sources.map((source) => [source.value, source]),
    );
    const pointersBySource = new Map<string, ReturnType<typeof pointers>>();
    const pointersFor = (place: SourceOffered | undefined) => {
      if (place?.fields === undefined) return [];
      let held = pointersBySource.get(place.value);
      if (held === undefined) {
        held = pointers(place.fields);
        pointersBySource.set(place.value, held);
      }
      return held;
    };
    const sourceChoices = sources.map((each) => (
      <MenuItem key={each.value} value={each.value}>
        {sourceVisibleOf(each)}
      </MenuItem>
    ));
    const positions = new Map(
      flow.steps.map((step, index) => [step.key, index]),
    );
    const full = runs.question.length >= 256;
    return (
      <Box sx={GROUP_SX}>
        <Typography variant="h5" sx={SECTION_HEADING_SX}>
          {say("workflow.searchQuestion")}
        </Typography>
        {runs.question.map((part, index) => {
          const source = "source" in part ? part.source : undefined;
          const place =
            source?.from === "input"
              ? sourceByValue.get("input")
              : source?.from === "step"
                ? sourceByValue.get(source.stepKey)
                : undefined;
          return (
            <SearchQuestionPart
              key={part.key}
              part={part}
              index={index}
              last={index === runs.question.length - 1}
              editable={editable}
              place={place}
              pointersHere={pointersFor(place)}
              sourceChoices={sourceChoices}
              sourceSignature={sourceSignature}
              pointsForward={
                source?.from === "step" &&
                (positions.get(source.stepKey) ?? -1) >= position
              }
              problems={problems}
              changePart={changePart}
              movePart={movePart}
              removePart={removePart}
            />
          );
        })}
        {editable ? (
          <Box sx={ROW_SX}>
            <Press
              unavailable={full}
              reason={
                full
                  ? {
                      severity: "warning",
                      words: say("workflow.searchPartsFull", { most: 256 }),
                    }
                  : undefined
              }
              onPress={() =>
                change((current) => ({
                  ...current,
                  question: [
                    ...current.question,
                    {
                      key: unusedKey(
                        "search",
                        current.question.map((part) => part.key),
                      ),
                      text: "",
                    },
                  ],
                }))
              }
            >
              {say("workflow.searchAddText")}
            </Press>
            <Press
              unavailable={full}
              reason={
                full
                  ? {
                      severity: "warning",
                      words: say("workflow.searchPartsFull", { most: 256 }),
                    }
                  : undefined
              }
              onPress={() =>
                change((current) => ({
                  ...current,
                  question: [
                    ...current.question,
                    {
                      key: unusedKey(
                        "search",
                        current.question.map((part) => part.key),
                      ),
                      source: { from: "none" },
                    },
                  ],
                }))
              }
            >
              {say("workflow.searchAddValue")}
            </Press>
          </Box>
        ) : null}
        <Typography variant="h6" sx={SECTION_HEADING_SX}>
          {say("workflow.searchStanding", { field: say("workflow.givesBack") })}
        </Typography>
        <Box sx={FIELDS_SX}>
          {(["title", "body", "references", "other"] as const).map((field) => (
            <TextField
              fullWidth
              key={field}
              select
              size="small"
              label={say("workflow.searchStanding", { field })}
              value={runs.standings[field]}
              disabled={!editable}
              onChange={(edit) =>
                change((current) => ({
                  ...current,
                  standings: {
                    ...current.standings,
                    [field]: edit.target
                      .value as SearchDraft["standings"][typeof field],
                  },
                }))
              }
            >
              <MenuItem value="">{say("workflow.searchStandingNone")}</MenuItem>
              <MenuItem value="always">
                {say("workflow.searchStandingAlways")}
              </MenuItem>
              <MenuItem value="never">
                {say("workflow.searchStandingReview")}
              </MenuItem>
            </TextField>
          ))}
        </Box>
      </Box>
    );
  },
  (before, after) =>
    before.runs === after.runs &&
    before.problems === after.problems &&
    before.position === after.position &&
    before.editable === after.editable &&
    before.change === after.change &&
    before.sourceSignature === after.sourceSignature &&
    before.orderSignature === after.orderSignature,
);

const SearchQuestionPart = memo(
  function SearchQuestionPart({
    part,
    index,
    last,
    editable,
    place,
    pointersHere,
    sourceChoices,
    pointsForward,
    problems,
    changePart,
    movePart,
    removePart,
  }: {
    readonly part: SearchPartDraft;
    readonly index: number;
    readonly last: boolean;
    readonly editable: boolean;
    readonly place: SourceOffered | undefined;
    readonly pointersHere: ReturnType<typeof pointers>;
    readonly sourceChoices: ReactNode;
    readonly sourceSignature: string;
    readonly pointsForward: boolean;
    readonly problems: readonly ContentProblem[];
    readonly changePart: (index: number, part: SearchPartDraft) => void;
    readonly movePart: (index: number, by: -1 | 1) => void;
    readonly removePart: (index: number) => void;
  }) {
    const source = "source" in part ? part.source : undefined;
    const path =
      source?.from === "input" || source?.from === "step" ? source.path : "";
    const chosen = pointersHere.find((pointer) => pointer.path === path);
    const short =
      "text" in part
        ? Array.from(part.text).slice(0, 40).join("") ||
          say("workflow.searchPart", { position: index + 1 })
        : path === ""
          ? say("workflow.searchPart", { position: index + 1 })
          : say("search.partOf", {
              field: path,
              source: place?.words ?? say("workflow.fromNothing"),
            });
    const errors: string[] = [];
    if (source !== undefined) {
      if (pointsForward) errors.push(say("workflow.pointsForward"));
      if (chosen !== undefined && shapeHasFile(chosen.field))
        errors.push(say("contentProblem.question_takes_file"));
      if ("source" in part && part.partId !== undefined)
        errors.push(
          ...problems
            .filter((problem) => problem.bindingId === part.partId)
            .map((problem) => problemSaid(problem)),
        );
    }
    const refusal = "text" in part ? searchTextRefused(part.text) : null;
    return (
      <Box sx={{ mb: 2 }}>
        <Box sx={ROW_SX}>
          <Typography variant="subtitle2">
            <bdi>{say("workflow.searchPart", { position: index + 1 })}</bdi>
          </Typography>
          {editable ? (
            <>
              <IconPress
                icon={<ArrowUpwardIcon />}
                navigation
                label={say("search.partUp", { part: short })}
                unavailable={index === 0}
                onPress={() => movePart(index, -1)}
              >
                {say("workflow.upTip")}
              </IconPress>
              <IconPress
                icon={<ArrowDownwardIcon />}
                navigation
                label={say("search.partDown", { part: short })}
                unavailable={last}
                onPress={() => movePart(index, 1)}
              >
                {say("workflow.downTip")}
              </IconPress>
              <IconPress
                icon={<DeleteOutlinedIcon />}
                warns
                label={say("search.partRemove", { part: short })}
                unavailable={false}
                onPress={() => removePart(index)}
              >
                {say("workflow.removeTip")}
              </IconPress>
            </>
          ) : null}
        </Box>
        {"text" in part ? (
          <TextField
            fullWidth
            multiline
            minRows={2}
            label={say("workflow.searchText")}
            value={part.text}
            disabled={!editable}
            error={refusal !== null}
            helperText={refusal === null ? undefined : say(refusal)}
            slotProps={{ htmlInput: { dir: "auto" } }}
            onChange={(edit) =>
              changePart(index, { key: part.key, text: edit.target.value })
            }
          />
        ) : (
          <Box sx={FIELDS_SX}>
            <TextField
              fullWidth
              select
              size="small"
              label={say("workflow.from")}
              value={
                source?.from === "input"
                  ? "input"
                  : source?.from === "step"
                    ? source.stepKey
                    : "none"
              }
              disabled={!editable}
              onChange={(edit) =>
                changePart(index, {
                  key: part.key,
                  source:
                    edit.target.value === "none"
                      ? { from: "none" }
                      : edit.target.value === "input"
                        ? { from: "input", path: "" }
                        : {
                            from: "step",
                            stepKey: edit.target.value,
                            path: "",
                          },
                })
              }
            >
              <MenuItem value="none">{say("workflow.fromNothing")}</MenuItem>
              {sourceChoices}
            </TextField>
            <TextField
              fullWidth
              select
              size="small"
              label={say("workflow.pointer")}
              value={path}
              disabled={
                !editable || source?.from === "none" || place === undefined
              }
              onChange={(edit) =>
                changePart(index, {
                  key: part.key,
                  source:
                    source?.from === "input"
                      ? { from: "input", path: edit.target.value }
                      : source?.from === "step"
                        ? { ...source, path: edit.target.value }
                        : { from: "none" },
                })
              }
            >
              <MenuItem value="">{say("workflow.pointerNone")}</MenuItem>
              {pointersHere
                .filter(
                  (pointer) =>
                    !shapeHasFile(pointer.field) || pointer.path === path,
                )
                .map((pointer) => (
                  <MenuItem key={pointer.path} value={pointer.path}>
                    {pointer.path}
                  </MenuItem>
                ))}
            </TextField>
          </Box>
        )}
        {errors.map((error, at) => (
          <Notice key={part.key + ":" + at} severity="warning">
            {error}
          </Notice>
        ))}
      </Box>
    );
  },
  (before, after) =>
    before.part === after.part &&
    before.index === after.index &&
    before.last === after.last &&
    before.editable === after.editable &&
    before.sourceSignature === after.sourceSignature &&
    before.pointsForward === after.pointsForward &&
    before.problems === after.problems &&
    before.changePart === after.changePart &&
    before.movePart === after.movePart &&
    before.removePart === after.removePart,
);

/**
 * Who produces a step's values — a question's a model or a person, a code step's code or a person saying so — and
 * how many tries they may make.
 */
function ProducerPart({
  step,
  models,
  editable,
  set,
}: {
  readonly step: StepDraft;
  readonly models: readonly HeldModel[];
  readonly editable: boolean;
  readonly set: (changed: Partial<StepDraft>) => void;
}) {
  const asking = step.runs.kind === "question" || step.runs.kind === "search";
  const chosen =
    step.producer === "person" || step.producer === (asking ? "model" : "code")
      ? step.producer
      : "";
  if (!editable) {
    return (
      <Box sx={GROUPED_FIELDS_SX}>
        <Typography variant="body2">
          {say("workflow.producer")}
          {": "}
          {producerSaid(step)}
        </Typography>
        {chosen === "model" && step.toldWhatHappened ? (
          <Typography variant="body2" sx={QUIET_SX}>
            {say("workflow.told")}
          </Typography>
        ) : null}
        <Typography variant="body2">
          {say("workflow.tries")}
          {": "}
          {step.tries === "" ? say("workflow.producesUnchosen") : step.tries}
        </Typography>
      </Box>
    );
  }
  const triesPast = step.tries !== "" && !triesFit(step.tries);
  return (
    <Box sx={GROUPED_FIELDS_SX}>
      <TextField
        fullWidth
        select
        size="small"
        label={say("workflow.producer")}
        value={chosen}
        onChange={(edit) =>
          set({ producer: edit.target.value as StepDraft["producer"] })
        }
      >
        <MenuItem value="">{say("workflow.producerNone")}</MenuItem>
        {asking ? (
          <MenuItem value="person">{say("workflow.producerPerson")}</MenuItem>
        ) : (
          <MenuItem value="code">{say("workflow.producerCode")}</MenuItem>
        )}
        {asking ? (
          <MenuItem value="model">{say("workflow.producerModel")}</MenuItem>
        ) : (
          <MenuItem value="person">
            {say("workflow.producerPersonDone")}
          </MenuItem>
        )}
      </TextField>
      {chosen === "model" ? (
        <ModelControls
          models={models}
          model={step.model}
          mode={step.mode}
          change={(model, mode) => set({ model, mode })}
        />
      ) : null}
      {chosen === "model" ? (
        <FormControlLabel
          control={
            <Checkbox
              checked={step.toldWhatHappened}
              onChange={(_event, checked) => set({ toldWhatHappened: checked })}
            />
          }
          label={say("workflow.told")}
        />
      ) : null}
      <TextField
        fullWidth
        size="small"
        label={say("workflow.tries")}
        value={step.tries}
        error={triesPast}
        helperText={triesPast ? say("workflow.triesLimit") : undefined}
        slotProps={{ htmlInput: { inputMode: "numeric" } }}
        onChange={(edit) => set({ tries: edit.target.value })}
      />
    </Box>
  );
}

export function producerSaid(step: StepDraft): string {
  switch (step.runs.kind) {
    case "question":
    case "search":
      return step.producer === "person"
        ? say("workflow.producesPerson")
        : step.producer === "model"
          ? modelSaid(step.model, step.mode)
          : say("workflow.producesUnchosen");
    case "code_step":
      return step.producer === "code"
        ? say("workflow.producesCode")
        : step.producer === "person"
          ? say("workflow.producesPersonDone")
          : say("workflow.producesUnchosen");
    case "workflow":
    case "route":
      return say("workflow.producesNobody");
    case "none":
      return say("workflow.producesUnchosen");
  }
}

/** Who may review: a person, or a model and the mode it runs in, chosen apart from whoever produces. */
function ReviewerPart({
  step,
  models,
  editable,
  set,
}: {
  readonly step: StepDraft;
  readonly models: readonly HeldModel[];
  readonly editable: boolean;
  readonly set: (changed: Partial<StepDraft>) => void;
}) {
  if (!editable) {
    return (
      <Box sx={GROUPED_FIELDS_SX}>
        <Typography variant="body2">
          {say("workflow.reviewer")}
          {": "}
          {step.reviewer === "model"
            ? modelSaid(step.reviewerModel, step.reviewerMode)
            : say("workflow.reviewerPerson")}
        </Typography>
      </Box>
    );
  }
  const modelProduces =
    (step.runs.kind === "question" || step.runs.kind === "search") &&
    step.producer === "model";
  return (
    <Box sx={GROUPED_FIELDS_SX}>
      <TextField
        fullWidth
        select
        size="small"
        label={say("workflow.reviewer")}
        value={step.reviewer}
        onChange={(edit) =>
          set({ reviewer: edit.target.value as StepDraft["reviewer"] })
        }
      >
        <MenuItem value="person">{say("workflow.reviewerPerson")}</MenuItem>
        <MenuItem value="model">{say("workflow.reviewerModel")}</MenuItem>
      </TextField>
      {step.reviewer === "model" ? (
        <ModelControls
          models={models}
          model={step.reviewerModel}
          mode={step.reviewerMode}
          change={(reviewerModel, reviewerMode) =>
            set({ reviewerModel, reviewerMode })
          }
        />
      ) : null}
      {step.reviewer === "model" && modelProduces && models.length === 1 ? (
        <Typography variant="body2" sx={QUIET_SX}>
          {say("workflow.reviewerOnlyModel")}
        </Typography>
      ) : null}
    </Box>
  );
}
