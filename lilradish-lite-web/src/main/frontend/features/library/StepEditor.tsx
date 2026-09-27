import Box from "@mui/material/Box";
import Checkbox from "@mui/material/Checkbox";
import FormControlLabel from "@mui/material/FormControlLabel";
import MenuItem from "@mui/material/MenuItem";
import TextField from "@mui/material/TextField";
import Typography from "@mui/material/Typography";

import type { DeclaredField } from "../../api/declaration";
import type { ContentProblem } from "../../api/groups/{groupId}/{kind}/{entryId}/versions";
import type {
  HeldModel,
  WorkflowVersion,
} from "../../api/groups/{groupId}/{kind}/{entryId}/versions/{versionId}";
import { say, type MessageId } from "../../i18n/app";
import { Notice } from "../../lib/notice/Notice";
import { fieldNameFits } from "../../lib/text/legibility";
import { Inputs } from "./BindingControls";
import { problemSaid, problemWords } from "./ContentProblems";
import { ModelControls, modelSaid } from "./ModelControls";
import { PinNote, versionSaid } from "./PinNote";
import { RoutePart } from "./RoutePart";
import {
  boundTo,
  flagsOf,
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
  type StepDraft,
} from "./workflowDrafts";

const PART_SX = { mb: 3 };

const ROW_SX = {
  display: "flex",
  flexWrap: "wrap",
  gap: 2,
  alignItems: "center",
  mb: 2,
};

const QUIET_SX = { color: "text.secondary" };

const RUNS_WORDS = {
  none: "workflow.runsChoose",
  question: "workflow.runsQuestion",
  workflow: "workflow.runsWorkflow",
  code_step: "workflow.runsCodeStep",
  route: "workflow.runsRouteOption",
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
  const set = (changed: Partial<StepDraft>) =>
    change((now) => ({ ...now, ...changed }));
  const models = workflow.offered.models;
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
    step.runs.kind === "question" || step.runs.kind === "code_step";
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
        <Box sx={PART_SX}>
          <Typography variant="subtitle2" component="p">
            {say("workflow.problemsHere")}
          </Typography>
          {here.map((problem, index) => (
            <Notice key={`${index}:${problem.code}`} severity="warning">
              {problemSaid(problem)}
            </Notice>
          ))}
        </Box>
      )}
      <Box sx={ROW_SX}>
        {editable ? (
          <TextField
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
          change={(runs) => set({ runs, bindings: [] })}
          take={(versionId) =>
            change((now) => tookVersion(now, versionId, named))
          }
        />
      </Box>
      {produces ? (
        <ProducerPart
          step={step}
          models={models}
          editable={editable}
          set={set}
        />
      ) : null}
      {produces ? (
        <Box sx={ROW_SX}>
          {editable ? (
            <TextField
              size="small"
              label={say("workflow.tries")}
              value={step.tries}
              error={step.tries !== "" && !triesFit(step.tries)}
              helperText={
                step.tries !== "" && !triesFit(step.tries)
                  ? say("workflow.triesLimit")
                  : undefined
              }
              slotProps={{ htmlInput: { inputMode: "numeric" } }}
              onChange={(edit) => set({ tries: edit.target.value })}
            />
          ) : (
            <Typography variant="body2">
              {say("workflow.tries")}
              {": "}
              {step.tries === ""
                ? say("workflow.producesUnchosen")
                : step.tries}
            </Typography>
          )}
        </Box>
      ) : null}
      {produces ? (
        <ReviewerPart
          step={step}
          models={models}
          editable={editable}
          set={set}
        />
      ) : null}
      {step.runs.kind === "route" ? (
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
    case "route":
      return {
        kind: "route",
        discriminator: { from: "none" },
        gives: [],
        cases: [],
      };
  }
}

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
            {versionSaid(named.get(runs.version), runs.version)}
          </Typography>
        )}
        {pinned === undefined ? null : (
          <PinNote pinned={pinned} take={editable ? take : undefined} />
        )}
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
      <TextField
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
        {runs.codeStep === ""
          ? say("workflow.codeStepNone")
          : say("workflow.runsCode", { name: runs.codeStep })}
      </Typography>
    );
  }
  return editable ? null : (
    <Typography variant="body2">{say(RUNS_WORDS[runs.kind])}</Typography>
  );
}

/** Who produces a step's values: a question's a model or a person, a code step's code or a person saying so. */
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
  const asking = step.runs.kind === "question";
  const chosen =
    step.producer === "person" || step.producer === (asking ? "model" : "code")
      ? step.producer
      : "";
  if (!editable) {
    return (
      <Box sx={ROW_SX}>
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
      </Box>
    );
  }
  return (
    <Box sx={ROW_SX}>
      <TextField
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
    </Box>
  );
}

export function producerSaid(step: StepDraft): string {
  switch (step.runs.kind) {
    case "question":
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
      <Box sx={ROW_SX}>
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
    step.runs.kind === "question" && step.producer === "model";
  return (
    <Box sx={ROW_SX}>
      <TextField
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
