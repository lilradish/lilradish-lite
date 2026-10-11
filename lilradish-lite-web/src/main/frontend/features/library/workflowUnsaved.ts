import type { MessageId } from "../../i18n/app";
import type { WorkflowVersion } from "../../api/groups/{groupId}/{kind}/{entryId}/versions/{versionId}";
import {
  bindingFlagsFor,
  sentFlowOf,
  sourcesFor,
  targetRows,
  type FlowDraft,
  type Named,
  type StepDraft,
  type SourceDraft,
} from "./workflowDrafts";

export type Sent = ReturnType<typeof sentFlowOf>;

/** Where the count of parts unsaved takes the reader among the steps and what fills what the workflow gives back. */
export type FlowChange =
  | {
      readonly at: "step";
      readonly key: string;
      readonly control?: {
        readonly part: string;
        readonly label?: MessageId;
        readonly path?: string;
        readonly bindingControl?: "from" | "which" | "typed";
      };
    }
  | { readonly at: "steps" }
  | {
      readonly at: "output";
      readonly path: string | null;
      readonly control?: "from" | "which" | "typed";
    };

export function outputRowsOf(
  flow: FlowDraft,
  workflow: WorkflowVersion,
  named: ReadonlyMap<string, Named>,
) {
  const position = flow.steps.length;
  const sources = sourcesFor(flow, position, workflow, named, undefined);
  const problems = workflow.problems.filter(
    (problem) => problem.part === "gives",
  );
  const flags = bindingFlagsFor(flow, position, sources);
  const rows = targetRows(
    workflow.gives,
    flow.outputs,
    problems,
    (binding, target) => flags(binding, target, problems),
    "workflow.outputUnbound",
  );
  return { rows, sources };
}

/**
 * The first change the reader is taken to: a step changed, or one holding a change kept while its drawer was shut,
 * before the steps as a whole, where only some were taken out; otherwise the first output filled otherwise.
 */
export function flowChangeAt({
  flow,
  saved,
  sent,
  savedSent,
  stepsUnsaved,
  keptSteps,
  workflow,
  named,
}: {
  readonly flow: FlowDraft;
  readonly saved: FlowDraft;
  readonly sent: Sent;
  readonly savedSent: Sent;
  readonly stepsUnsaved: boolean;
  readonly keptSteps: ReadonlySet<string>;
  readonly workflow: WorkflowVersion;
  readonly named: ReadonlyMap<string, Named>;
}): FlowChange {
  if (stepsUnsaved || keptSteps.size > 0) {
    const changed = stepsUnsaved
      ? firstStepChanged(flow, saved, sent, savedSent)
      : null;
    if (changed !== null) return { at: "step", ...changed };
    const key = flow.steps.find((each) => keptSteps.has(each.key))?.key;
    return key === undefined ? { at: "steps" } : { at: "step", key };
  }
  const changed = outputsChanged(sent, savedSent);
  const path =
    outputRowsOf(flow, workflow, named).rows.find((row) =>
      changed.has(row.path),
    )?.path ?? null;
  const control =
    path === null
      ? undefined
      : sourceControlChanged(
          flow.outputs.find((each) => each.target === path)?.source,
          saved.outputs.find((each) => each.target === path)?.source,
        );
  return { at: "output", path, ...(control === undefined ? {} : { control }) };
}

/** The first step, in the order they run, that is new, changed or moved up past one before it; none where only some were taken out. */
function firstStepChanged(
  flow: FlowDraft,
  saved: FlowDraft,
  sent: Sent,
  savedSent: Sent,
): {
  readonly key: string;
  readonly control?: Extract<FlowChange, { at: "step" }>["control"];
} | null {
  const savedAt = new Map(saved.steps.map((step, at) => [step.key, at]));
  let before = -1;
  for (const [at, step] of flow.steps.entries()) {
    const was = savedAt.get(step.key);
    if (was === undefined || was < before) return { key: step.key };
    if (JSON.stringify(sent.steps[at]) !== JSON.stringify(savedSent.steps[was]))
      return {
        key: step.key,
        control: controlChanged(step, saved.steps[was]!),
      };
    before = was;
  }
  return null;
}

function controlChanged(
  step: StepDraft,
  saved: StepDraft,
): Extract<FlowChange, { at: "step" }>["control"] {
  if (step.name !== saved.name) return { part: "name" };
  if (step.label !== saved.label) return { part: "label" };
  if (JSON.stringify(step.runs) !== JSON.stringify(saved.runs)) {
    const label: MessageId =
      step.runs.kind !== saved.runs.kind
        ? "workflow.stepRuns"
        : step.runs.kind === "code_step"
          ? "workflow.codeStep"
          : step.runs.kind === "search" &&
              saved.runs.kind === "search" &&
              step.runs.searchWay !== saved.runs.searchWay
            ? "workflow.searchWay"
            : "workflow.version";
    return { part: "runs-group", label };
  }
  if (step.producer !== saved.producer)
    return { part: "producer", label: "workflow.producer" };
  if (step.producer === "model") {
    if (step.model !== saved.model)
      return { part: "producer", label: "workflow.model" };
    if (step.mode !== saved.mode)
      return { part: "producer", label: "workflow.mode" };
    if (step.toldWhatHappened !== saved.toldWhatHappened)
      return { part: "toldWhatHappened" };
  }
  if (step.tries !== saved.tries) return { part: "tries" };
  if (step.reviewer !== saved.reviewer)
    return { part: "reviewer", label: "workflow.reviewer" };
  if (step.reviewer === "model") {
    if (step.reviewerModel !== saved.reviewerModel)
      return { part: "reviewer", label: "workflow.model" };
    if (step.reviewerMode !== saved.reviewerMode)
      return { part: "reviewer", label: "workflow.mode" };
  }
  const binding = step.bindings.find(
    (each) =>
      JSON.stringify(each.source) !==
      JSON.stringify(
        saved.bindings.find((was) => was.target === each.target)?.source,
      ),
  );
  return binding === undefined
    ? undefined
    : {
        part: "binding",
        path: binding.target,
        bindingControl: sourceControlChanged(
          binding.source,
          saved.bindings.find((was) => was.target === binding.target)?.source,
        ),
      };
}

function sourceControlChanged(
  source: SourceDraft | undefined,
  saved: SourceDraft | undefined,
): "from" | "which" | "typed" | undefined {
  if (source === undefined || saved === undefined) return undefined;
  if (
    source.from !== saved.from ||
    (source.from === "step" &&
      saved.from === "step" &&
      source.stepKey !== saved.stepKey)
  )
    return "from";
  if (
    (source.from === "input" || source.from === "step") &&
    (saved.from === "input" || saved.from === "step") &&
    source.path !== saved.path
  )
    return "which";
  if (
    source.from === "constant" &&
    saved.from === "constant" &&
    (source.typed !== saved.typed || source.read !== saved.read)
  )
    return "typed";
  return undefined;
}

/** The targets of the outputs filled otherwise than as saved, or no longer, or newly. */
function outputsChanged(sent: Sent, savedSent: Sent): ReadonlySet<string> {
  const savedBy = new Map(
    savedSent.outputs.map((each) => [each.target, JSON.stringify(each.source)]),
  );
  const sentBy = new Map(
    sent.outputs.map((each) => [each.target, JSON.stringify(each.source)]),
  );
  return new Set(
    [...sentBy.keys(), ...savedBy.keys()].filter(
      (target) => sentBy.get(target) !== savedBy.get(target),
    ),
  );
}
