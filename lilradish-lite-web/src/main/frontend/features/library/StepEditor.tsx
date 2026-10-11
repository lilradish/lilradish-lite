import Box from "@mui/material/Box";
import Checkbox from "@mui/material/Checkbox";
import FormControlLabel from "@mui/material/FormControlLabel";
import MenuItem from "@mui/material/MenuItem";
import Typography from "@mui/material/Typography";
import { useEffect, useRef } from "react";

import type { DeclaredField } from "../../api/declaration";
import type { VersionDetail } from "../../api/groups/{groupId}/versions/{versionId}";
import { ProblemView } from "../../app/ProblemView";
import type { ContentProblem } from "../../api/groups/{groupId}/{kind}/{entryId}/versions";
import type {
  HeldModel,
  WorkflowVersion,
} from "../../api/groups/{groupId}/{kind}/{entryId}/versions/{versionId}";
import { say, type MessageId } from "../../i18n/app";
import { readersIntl } from "../../i18n/intl";
import { Fields, Field } from "../../lib/form/Fields";
import { LabelledTextField } from "../../lib/form/LabelledTextField";
import { GROUP_SX, SPACED_SX } from "../../lib/layout/parts";
import { Notice } from "../../lib/notice/Notice";
import { fieldNameFits, nameFits } from "../../lib/text/legibility";
import { isolatedInText } from "../../lib/direction/isolated";
import { Inputs } from "./BindingControls";
import { problemSaid, problemWords } from "./ContentProblems";
import { ModelControls, modelSaid } from "./ModelControls";
import { PinNote, versionShown } from "./PinNote";
import {
  EditorVersionPicker,
  useVersionDetail,
  type VersionDetailOwner,
} from "./EditorVersionPicker";
import { RoutePart } from "./RoutePart";
import {
  boundTo,
  bindingFlagsFor,
  labelClashOf,
  pointsForward,
  sourcesFor,
  takesOf,
  targetRows,
  termsChosenBy,
  tookVersion,
  triesFit,
  type BindingDraft,
  type FlowDraft,
  type Named,
  type RunsDraft,
  type SearchDraft,
  type StepDraft,
} from "./workflowDrafts";

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

export function StepEditor({
  step,
  position,
  flow,
  workflow,
  named,
  editable,
  change,
  scope,
  resolve,
  details,
  detailOwner,
}: {
  readonly step: StepDraft;
  readonly position: number;
  readonly flow: FlowDraft;
  readonly workflow: WorkflowVersion;
  readonly named: ReadonlyMap<string, Named>;
  readonly editable: boolean;
  readonly change: (change: (step: StepDraft) => StepDraft) => void;
  readonly scope: string;
  readonly details: ReadonlyMap<string, VersionDetail>;
  readonly detailOwner?: VersionDetailOwner;
  readonly resolve: (
    detail: VersionDetail | null,
    change: (step: StepDraft, named: ReadonlyMap<string, Named>) => StepDraft,
  ) => void;
}) {
  const latestChange = useRef(change);
  useEffect(() => {
    latestChange.current = change;
  }, [change]);
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
  const flags = bindingFlagsFor(flow, position, sources);
  const rowsOf = (
    takes: readonly DeclaredField[],
    bindings: readonly BindingDraft[],
    problems: readonly ContentProblem[],
  ) =>
    targetRows(
      takes,
      bindings,
      problems,
      (binding, target) => flags(binding, target, problems),
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
  const labelClash = labelClashOf(step, flow.steps);
  const labelRefused = step.label !== "" && !nameFits(step.label);
  const runsChosen = (
    <RunsChosen
      detailOwner={detailOwner}
      step={step}
      workflow={workflow}
      named={named}
      editable={editable}
      scope={`${scope}:${step.key}:${step.runs.kind}`}
      resolve={resolve}
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
    />
  );
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
                step.runs.kind === "search" ? step.runs.searchWay : undefined,
                step.runs.kind === "search"
                  ? named.get(step.runs.version)
                  : undefined,
              )}
            </Notice>
          ))}
        </Box>
      )}
      {editable ? null : (
        <Fields inPanel>
          {step.runs.kind === "route" ? null : (
            <Field label={say("workflow.runs")}>{runsChosen}</Field>
          )}
          {produces ? (
            <>
              <Field label={say("workflow.produces")}>
                {producerSaid(step)}
                {step.producer === "model" && step.toldWhatHappened ? (
                  <Typography
                    sx={{ ...QUIET_SX, typography: "supportingProse" }}
                  >
                    {say("workflow.told")}
                  </Typography>
                ) : null}
              </Field>
              <Field label={say("workflow.attempts")}>
                {step.tries === ""
                  ? say("workflow.producesUnchosen")
                  : readersIntl.formatNumber(BigInt(step.tries))}
              </Field>
              <Field label={say("workflow.reviewedBy")}>
                {step.reviewer === "model"
                  ? modelSaid(step.reviewerModel, step.reviewerMode)
                  : say("workflow.reviewerPerson")}
              </Field>
            </>
          ) : null}
        </Fields>
      )}
      {editable ? (
        <Box sx={FIELDS_SX} data-flow-control="runs-group">
          {editable ? (
            <LabelledTextField
              fullWidth
              size="small"
              label={say("workflow.stepName")}
              helperBefore
              slotProps={{ htmlInput: { "data-flow-control": "name" } }}
              value={step.name}
              autoFocus={step.stepId === null && step.name === ""}
              error={step.name !== "" && !fieldNameFits(step.name)}
              helperText={
                step.name !== "" && !fieldNameFits(step.name)
                  ? say("refusal.STEP_NAME_UNUSABLE")
                  : say("workflow.stepNameHint")
              }
              onChange={(edit) => set({ name: edit.target.value })}
            />
          ) : null}
          {editable ? (
            <LabelledTextField
              fullWidth
              size="small"
              label={say("workflow.stepLabel")}
              helperBefore
              value={step.label}
              error={labelRefused || labelClash !== undefined}
              helperText={
                labelRefused
                  ? say("refusal.STEP_LABEL_UNUSABLE")
                  : labelClash === undefined
                    ? say("workflow.stepLabelHint")
                    : say("contentProblem.step_label_repeated", {
                        name: isolatedInText(labelClash),
                      })
              }
              slotProps={{
                htmlInput: { dir: "auto", "data-flow-control": "label" },
              }}
              onChange={(edit) => set({ label: edit.target.value })}
            />
          ) : null}
          {editable ? (
            <LabelledTextField
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
            </LabelledTextField>
          ) : null}
          {runsChosen}
        </Box>
      ) : null}
      {produces && editable ? (
        <>
          <ProducerPart step={step} models={producerModels} set={set} />
          <ReviewerPart step={step} models={models} set={set} />
        </>
      ) : null}
      {step.runs.kind === "search" ? (
        <SearchStandings
          runs={step.runs}
          editable={editable}
          change={(runs) => set({ runs })}
        />
      ) : null}
      {step.runs.kind === "route" ? (
        <RoutePart
          stepKey={step.key}
          details={details}
          scope={`${scope}:${step.key}:${step.runs.kind}`}
          resolve={(detail, changed) =>
            resolve(detail, (now, resolved) =>
              now.runs.kind === "route"
                ? { ...now, runs: changed(now.runs, resolved) }
                : now,
            )
          }
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
        version: "",
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
  scope,
  resolve,
  detailOwner,
}: {
  readonly step: StepDraft;
  readonly workflow: WorkflowVersion;
  readonly named: ReadonlyMap<string, Named>;
  readonly editable: boolean;
  readonly change: (runs: RunsDraft) => void;
  readonly scope: string;
  readonly detailOwner?: VersionDetailOwner;
  readonly resolve: (
    detail: VersionDetail | null,
    change: (step: StepDraft, named: ReadonlyMap<string, Named>) => StepDraft,
  ) => void;
}) {
  const runs = step.runs;
  const picking = (detail: VersionDetail | null) =>
    resolve(detail, (now, resolved) => {
      if (now.runs.kind === "search")
        return tookVersion(now, detail?.version.versionId ?? "", resolved);
      if (now.runs.kind !== "question" && now.runs.kind !== "workflow")
        return now;
      return {
        ...now,
        runs: { ...now.runs, version: detail?.version.versionId ?? "" },
        bindings: [],
      };
    });
  const newer = useVersionDetail({
    owner: detailOwner,
    kind: runs.kind === "workflow" ? "workflow" : "question",
    scope: `${scope}:${runs.kind === "question" || runs.kind === "workflow" || runs.kind === "search" ? runs.version : ""}:${runs.kind === "search" ? runs.searchWay : ""}`,
    disabled: !editable,
    searchWay: runs.kind === "search" ? runs.searchWay : undefined,
    onRead: (detail) =>
      resolve(detail, (now, resolved) =>
        tookVersion(now, detail?.version.versionId ?? "", resolved),
      ),
  });
  if (runs.kind === "question" || runs.kind === "workflow") {
    const pinned = named.get(runs.version)?.pinned;
    return (
      <>
        {editable ? (
          <EditorVersionPicker
            detailOwner={detailOwner}
            kind={runs.kind}
            label={say("workflow.version")}
            value={runs.version}
            noneLabel={say("workflow.versionNone")}
            scope={`${scope}:${runs.version}`}
            selected={pinned}
            onPick={picking}
          />
        ) : (
          <Typography sx={{ typography: "factValue" }}>
            {runs.version === ""
              ? say("workflow.runsNothing")
              : versionShown(named.get(runs.version), runs.version)}
          </Typography>
        )}
        {pinned === undefined ? null : (
          <PinNote pinned={pinned} take={editable ? newer.read : undefined} />
        )}
        {newer.problem === null ? null : (
          <ProblemView problem={newer.problem} />
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
    const pinned = named.get(runs.version)?.pinned;
    const questionPicker = (
      <>
        {editable ? (
          <EditorVersionPicker
            detailOwner={detailOwner}
            kind="question"
            label={say("workflow.version")}
            value={runs.version}
            noneLabel={say("workflow.versionNone")}
            hint={say("question.searchPickerHint", { way: runs.searchWay })}
            scope={`${scope}:${runs.version}:${runs.searchWay}`}
            selected={pinned}
            searchWay={runs.searchWay}
            disabled={runs.searchWay === ""}
            onPick={picking}
          />
        ) : (
          <Typography sx={{ typography: "factValue" }}>
            {versionShown(named.get(runs.version), runs.version)}
          </Typography>
        )}
        {pinned === undefined ? null : (
          <PinNote
            pinned={pinned}
            take={
              editable && pinned.newer !== undefined && runs.searchWay !== ""
                ? newer.read
                : undefined
            }
          />
        )}
        {newer.problem === null ? null : (
          <ProblemView problem={newer.problem} />
        )}
      </>
    );
    const wayPicker = editable ? (
      <LabelledTextField
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
      </LabelledTextField>
    ) : (
      <Box>
        <Typography sx={{ typography: "factValue" }}>
          {runs.searchWay === ""
            ? say("workflow.searchWayNone")
            : runs.searchWay}
        </Typography>
        {selectedWay === undefined ? null : (
          <Typography sx={{ ...QUIET_SX, typography: "supportingProse" }}>
            {say("search.wayNeeds", { tool: selectedWay.tool })}
          </Typography>
        )}
      </Box>
    );
    return (
      <>
        {wayPicker}
        {questionPicker}
      </>
    );
  }
  if (runs.kind === "code_step") {
    const offered = workflow.offered.codeSteps.map((codeStep) => codeStep.name);
    const choices =
      runs.codeStep === "" || offered.includes(runs.codeStep)
        ? offered
        : [runs.codeStep, ...offered];
    return editable ? (
      <LabelledTextField
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
      </LabelledTextField>
    ) : (
      <Typography sx={{ typography: "factValue" }}>
        {runs.codeStep === "" ? say("workflow.runsNothing") : runs.codeStep}
      </Typography>
    );
  }
  return editable || runs.kind === "route" ? null : (
    <Typography sx={{ typography: "factValue" }}>
      {say("workflow.runsNothing")}
    </Typography>
  );
}

function SearchStandings({
  runs,
  editable,
  change,
}: {
  readonly runs: SearchDraft;
  readonly editable: boolean;
  readonly change: (runs: SearchDraft) => void;
}) {
  if (!editable) {
    return (
      <Box sx={GROUP_SX}>
        <Fields properties>
          {(["title", "body", "references", "other"] as const).map((field) => (
            <Field
              key={field}
              label={say("workflow.searchStanding", { field })}
            >
              {say(
                runs.standings[field] === "always"
                  ? "workflow.searchStandingAlways"
                  : runs.standings[field] === "never"
                    ? "workflow.searchStandingReview"
                    : "workflow.searchStandingNone",
              )}
            </Field>
          ))}
        </Fields>
      </Box>
    );
  }
  return (
    <Box sx={GROUP_SX}>
      <Typography variant="h6" sx={SECTION_HEADING_SX}>
        {say("workflow.searchStanding", { field: say("workflow.givesBack") })}
      </Typography>
      <Box sx={FIELDS_SX}>
        {(["title", "body", "references", "other"] as const).map((field) => (
          <LabelledTextField
            fullWidth
            key={field}
            select
            size="small"
            label={say("workflow.searchStanding", { field })}
            value={runs.standings[field]}
            disabled={!editable}
            onChange={(edit) =>
              change({
                ...runs,
                standings: {
                  ...runs.standings,
                  [field]: edit.target
                    .value as SearchDraft["standings"][typeof field],
                },
              })
            }
          >
            <MenuItem value="">{say("workflow.searchStandingNone")}</MenuItem>
            <MenuItem value="always">
              {say("workflow.searchStandingAlways")}
            </MenuItem>
            <MenuItem value="never">
              {say("workflow.searchStandingReview")}
            </MenuItem>
          </LabelledTextField>
        ))}
      </Box>
    </Box>
  );
}

function ProducerPart({
  step,
  models,
  set,
}: {
  readonly step: StepDraft;
  readonly models: readonly HeldModel[];
  readonly set: (changed: Partial<StepDraft>) => void;
}) {
  const asking = step.runs.kind === "question" || step.runs.kind === "search";
  const chosen =
    step.producer === "person" || step.producer === (asking ? "model" : "code")
      ? step.producer
      : "";
  const triesPast = step.tries !== "" && !triesFit(step.tries);
  return (
    <Box sx={GROUPED_FIELDS_SX} data-flow-control="producer">
      <LabelledTextField
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
      </LabelledTextField>
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
          data-flow-control="toldWhatHappened"
          control={
            <Checkbox
              checked={step.toldWhatHappened}
              onChange={(_event, checked) => set({ toldWhatHappened: checked })}
            />
          }
          label={say("workflow.told")}
        />
      ) : null}
      <LabelledTextField
        fullWidth
        size="small"
        label={say("workflow.tries")}
        helperBefore
        value={step.tries}
        error={triesPast}
        helperText={triesPast ? say("workflow.triesLimit") : undefined}
        slotProps={{
          htmlInput: { inputMode: "numeric", "data-flow-control": "tries" },
        }}
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
  set,
}: {
  readonly step: StepDraft;
  readonly models: readonly HeldModel[];
  readonly set: (changed: Partial<StepDraft>) => void;
}) {
  const modelProduces =
    (step.runs.kind === "question" || step.runs.kind === "search") &&
    step.producer === "model";
  return (
    <Box sx={GROUPED_FIELDS_SX} data-flow-control="reviewer">
      <LabelledTextField
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
      </LabelledTextField>
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
