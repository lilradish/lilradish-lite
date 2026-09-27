import type {
  DeclaredField,
  FieldKind,
  PinnedList,
} from "../../api/declaration";
import type { ContentProblem } from "../../api/groups/{groupId}/{kind}/{entryId}/versions";
import type {
  Binding,
  BindingSource,
  OfferedCodeStep,
  Step,
  WorkflowVersion,
} from "../../api/groups/{groupId}/{kind}/{entryId}/versions/{versionId}";
import { constantRead, constantTyped } from "./constantDrafts";
import type {
  SentBinding,
  SentCase,
  SentChoice,
  SentProducer,
  SentRuns,
  SentStep,
} from "../../api/groups/{groupId}/{kind}/{entryId}/versions/{versionId}/steps";
import { say, type MessageId } from "../../i18n/app";
import { fieldNameFits, oneLineWithin } from "../../lib/text/legibility";
import { problemWords } from "./ContentProblems";
import {
  draftOf,
  heldFor,
  sentOf,
  type Demands,
  type DraftField,
} from "./declarationDrafts";
import WORKFLOW_DEMANDS from "./workflowDemands.json";

/** What a workflow's own halves ask at each depth, paired with the server's by a shared table. */
export const WORKFLOW_TAKES = WORKFLOW_DEMANDS.takes as Demands;

export const WORKFLOW_GIVES = WORKFLOW_DEMANDS.gives as Demands;

/** Level with the server's bound on the steps of one version. */
export const MOST_STEPS = 256;

// Level with the column a route case's term is held in.
const LONGEST_TERM = 128;

// Level with the server's most fields in a declaration, and most terms in a list, where the one meant is not known.
const MOST_FIELDS = 256;

const MOST_TERMS = 256;

const WHOLE = /^[1-9][0-9]*$/;

const LARGEST_TRIES = 2147483647;

// Whether an input or an output is filled is judged here, live, by the server's own rule; what the server said
// of it is as of the last read.
const JUDGED_HERE: ReadonlySet<string> = new Set([
  "input_unbound",
  "output_unbound",
]);

/**
 * Where a binding reads: nothing chosen yet, what the workflow takes, a step by its row, or a constant as typed.
 * A step is named by its row and not its place, so moving it keeps what reads it.
 */
export type SourceDraft =
  | { readonly from: "none" }
  | { readonly from: "input"; readonly path: string }
  | { readonly from: "step"; readonly stepKey: string; readonly path: string }
  | {
      readonly from: "constant";
      readonly typed: string;
      /** The JSON text as read, sent back as it was until something is typed over it. */
      readonly read?: string;
    };

export interface BindingDraft {
  readonly target: string;
  readonly source: SourceDraft;
  /** The key the server read it under, which what it said of the binding names; none once it is changed. */
  readonly bindingId?: string;
}

export interface CaseDraft {
  /** Which row this is, for as long as the editor holds it; never sent. */
  readonly key: string;
  /** The key the server read it under, sent back so what it leads to is kept; none for a case added. */
  readonly caseId: string | null;
  readonly fallback: boolean;
  readonly term: string;
  /** The workflow version it leads to, "" where none is chosen yet. */
  readonly workflow: string;
  readonly bindings: readonly BindingDraft[];
}

export type RunsDraft =
  | { readonly kind: "none" }
  | { readonly kind: "question" | "workflow"; readonly version: string }
  | { readonly kind: "code_step"; readonly codeStep: string }
  | RouteDraft;

export interface RouteDraft {
  readonly kind: "route";
  readonly discriminator: SourceDraft;
  /** The key what it chooses by was read under, as `BindingDraft.bindingId` is. */
  readonly discriminatorId?: string;
  readonly gives: readonly DraftField[];
  readonly cases: readonly CaseDraft[];
}

/** One step as typed, "" where nothing is; who produces and reviews is kept across a change of what it runs. */
export interface StepDraft {
  readonly key: string;
  /** The key the server read it under, sent back so what it pins is kept; none for a step added. */
  readonly stepId: string | null;
  readonly name: string;
  readonly runs: RunsDraft;
  readonly producer: "" | "person" | "model" | "code";
  readonly model: string;
  readonly mode: string;
  readonly toldWhatHappened: boolean;
  readonly tries: string;
  readonly reviewer: "person" | "model";
  readonly reviewerModel: string;
  readonly reviewerMode: string;
  readonly bindings: readonly BindingDraft[];
}

export interface FlowDraft {
  readonly steps: readonly StepDraft[];
  readonly outputs: readonly BindingDraft[];
}

/** What a field is, as far as whether a value fits it and whether it is always there ask. */
export interface Shape {
  readonly name: string;
  readonly kind: FieldKind;
  readonly many: boolean;
  readonly mustBeGiven: boolean;
  readonly most?: number;
  readonly longest?: number;
  readonly list?: string;
  readonly fields?: readonly Shape[];
}

/** A version a step or a case may name, with what it declares, as the page last read it. */
export interface Named {
  readonly name: string;
  readonly number: number;
  readonly takes: readonly DeclaredField[];
  readonly gives: readonly DeclaredField[];
  /** The version's own standing and the newest of its entry in service, where it is one a step pins already. */
  readonly pinned?: PinnedList;
}

/** A place a value may be read from: what the workflow takes, or a step by its row, with what it declares. */
export interface SourceOffered {
  /** "input", or the step's row. */
  readonly value: string;
  readonly words: string;
  /** What it declares, none where that is not known here. */
  readonly fields: readonly Shape[] | undefined;
}

/** One field a pointer may name, and whether it and every field holding it must be given. */
interface Reached {
  readonly path: string;
  readonly field: Shape;
  readonly given: boolean;
}

/** A field a binding may fill, how deep it sits, what fills it, and what does not hold of it. */
export interface TargetRow {
  readonly path: string;
  readonly depth: number;
  readonly target: Shape;
  readonly binding: BindingDraft | undefined;
  readonly flags: readonly MessageId[];
}

/** What keeps a flow from being sent, and the row it is in. */
interface FlowHeld {
  readonly why: MessageId;
  /** The step's row, where it is one step's. */
  readonly step?: string;
}

export function flowOf(workflow: WorkflowVersion): FlowDraft {
  const keys = workflow.steps.map((step) => step.stepId);
  const gives = workflow.gives.map(shapeOf);
  return {
    steps: workflow.steps.map((step) => stepDraftOf(step, keys)),
    outputs: workflow.outputs.map((binding) =>
      bindingDraftOf(binding, keys, gives),
    ),
  };
}

function bindingDraftOf(
  binding: Binding,
  keys: readonly string[],
  takes: readonly Shape[] | undefined,
): BindingDraft {
  const target = binding.target ?? "";
  return {
    target,
    source: sourceDraftOf(
      binding.source,
      keys,
      takes === undefined ? undefined : fieldAt(takes, target),
    ),
    bindingId: binding.bindingId,
  };
}

function stepDraftOf(step: Step, keys: readonly string[]): StepDraft {
  const runs = step.runs;
  const producer = step.producer;
  return {
    key: step.stepId,
    stepId: step.stepId,
    name: step.name,
    runs:
      runs === undefined
        ? { kind: "none" }
        : runs.kind === "route"
          ? {
              kind: "route",
              discriminator:
                runs.discriminator === undefined
                  ? { from: "none" }
                  : sourceDraftOf(runs.discriminator.source, keys, undefined),
              ...(runs.discriminator === undefined
                ? {}
                : { discriminatorId: runs.discriminator.bindingId }),
              gives: (runs.gives ?? []).map(draftOf),
              cases: (runs.cases ?? []).map((routeCase) => ({
                key: routeCase.caseId,
                caseId: routeCase.caseId,
                fallback: routeCase.term === undefined,
                term: routeCase.term ?? "",
                workflow: routeCase.workflow?.versionId ?? "",
                bindings: routeCase.bindings.map((binding) =>
                  bindingDraftOf(binding, keys, routeCase.takes?.map(shapeOf)),
                ),
              })),
            }
          : runs.kind === "code_step"
            ? { kind: "code_step", codeStep: runs.codeStep ?? "" }
            : { kind: runs.kind, version: runs.version?.versionId ?? "" },
    producer: producer?.kind ?? "",
    model: producer?.model ?? "",
    mode: producer?.mode ?? "",
    toldWhatHappened: producer?.toldWhatHappened ?? false,
    tries: step.tries?.toString() ?? "",
    reviewer: step.reviewer === undefined ? "person" : "model",
    reviewerModel: step.reviewer?.model ?? "",
    reviewerMode: step.reviewer?.mode ?? "",
    bindings: step.bindings.map((binding) =>
      bindingDraftOf(binding, keys, runs?.takes?.map(shapeOf)),
    ),
  };
}

/** A step read from by its place is named by its row; a place naming no step reads as nothing chosen. */
function sourceDraftOf(
  source: BindingSource,
  keys: readonly string[],
  target: Shape | undefined,
): SourceDraft {
  if ("input" in source) {
    return { from: "input", path: source.input };
  }
  if ("step" in source) {
    const key = keys[source.step];
    return key === undefined
      ? { from: "none" }
      : { from: "step", stepKey: key, path: source.path };
  }
  return {
    from: "constant",
    typed: constantTyped(source.constant, target),
    read: source.constant,
  };
}

/** A field read, as whether a value fits it asks. */
function shapeOf(field: DeclaredField): Shape {
  return {
    name: field.name,
    kind: field.kind,
    many: field.many,
    mustBeGiven: field.mustBeGiven,
    ...(field.most === undefined ? {} : { most: field.most }),
    ...(field.longest === undefined ? {} : { longest: field.longest }),
    ...(field.list === undefined ? {} : { list: field.list.versionId }),
    ...(field.fields === undefined
      ? {}
      : { fields: field.fields.map(shapeOf) }),
  };
}

/** A field being typed, as whether a value fits it asks; a limit not a whole number yet is none. */
function draftShapeOf(draft: DraftField): Shape {
  const most = WHOLE.test(draft.most) ? Number(draft.most) : undefined;
  const longest = WHOLE.test(draft.longest) ? Number(draft.longest) : undefined;
  return {
    name: draft.name,
    kind: draft.kind,
    many: draft.many,
    mustBeGiven: draft.mustBeGiven,
    ...(draft.many && most !== undefined ? { most } : {}),
    ...(draft.kind === "text" && longest !== undefined ? { longest } : {}),
    ...(draft.kind === "term" && draft.list !== "" ? { list: draft.list } : {}),
    ...(draft.kind === "fields"
      ? { fields: draft.fields.map(draftShapeOf) }
      : {}),
  };
}

/**
 * Whether every value {@code source} may hold is one {@code target} takes, as the server judges it: the same kind,
 * one or many alike, nothing longer, more numerous or from another list, and the same names held.
 */
export function fits(source: Shape, target: Shape): boolean {
  if (source.kind !== target.kind || source.many !== target.many) {
    return false;
  }
  if (!within(source.most, target.most)) {
    return false;
  }
  if (target.kind === "text") {
    return within(source.longest, target.longest);
  }
  if (target.kind === "term") {
    return (
      source.list === undefined ||
      target.list === undefined ||
      source.list === target.list
    );
  }
  if (target.kind === "fields") {
    const held = source.fields ?? [];
    const taken = target.fields ?? [];
    return (
      held.length === taken.length &&
      taken.every((field) => {
        const match = held.find((each) => each.name === field.name);
        return match !== undefined && fits(match, field);
      })
    );
  }
  return true;
}

/**
 * Whether each field within {@code target} that must be given is one {@code source} must give too, at every depth,
 * as the server judges it; the outermost pair is judged by the way each was reached.
 */
function givenWithin(source: Shape, target: Shape): boolean {
  if (source.kind !== "fields" || target.kind !== "fields") {
    return true;
  }
  return (target.fields ?? []).every((taken) => {
    const held = source.fields?.find((each) => each.name === taken.name);
    return (
      held === undefined ||
      ((!taken.mustBeGiven || held.mustBeGiven) && givenWithin(held, taken))
    );
  });
}

function within(given: number | undefined, taken: number | undefined): boolean {
  return given === undefined || taken === undefined || given <= taken;
}

/** Every field a pointer may name, in the order the level reads, never through a field holding many. */
export function pointers(
  level: readonly Shape[],
  above = "",
  givenAbove = true,
): Reached[] {
  return level.flatMap((field) => {
    const path = above === "" ? field.name : `${above}.${field.name}`;
    const given = givenAbove && field.mustBeGiven;
    const below =
      field.kind === "fields" && !field.many
        ? pointers(field.fields ?? [], path, given)
        : [];
    return [{ path, field, given }, ...below];
  });
}

/** The field a pointer names at {@code level}, or none where it names nothing or passes through many. */
function fieldAt(level: readonly Shape[], path: string): Shape | undefined {
  return pointers(level).find((pointer) => pointer.path === path)?.field;
}

/** Every version the page may name, those pinned already with what the page read of their pins. */
export function namedVersions(workflow: WorkflowVersion): Map<string, Named> {
  const named = new Map<string, Named>();
  const add = (
    pinned: PinnedList | undefined,
    takes: readonly DeclaredField[] | undefined,
    gives: readonly DeclaredField[] | undefined,
  ) => {
    if (pinned !== undefined) {
      named.set(pinned.versionId, {
        name: pinned.name,
        number: pinned.number,
        takes: takes ?? [],
        gives: gives ?? [],
        pinned,
      });
    }
  };
  for (const offered of [
    ...workflow.offered.questions,
    ...workflow.offered.workflows,
  ]) {
    named.set(offered.versionId, offered);
  }
  for (const step of workflow.steps) {
    add(step.runs?.version, step.runs?.takes, step.runs?.gives);
    for (const routeCase of step.runs?.cases ?? []) {
      add(routeCase.workflow, routeCase.takes, routeCase.gives);
    }
  }
  return named;
}

/**
 * What a step takes, as what it runs declares it, a code step's as the release declares it; none where that is not
 * known to the page, a code step not offered among them.
 */
export function takesOf(
  step: StepDraft,
  named: ReadonlyMap<string, Named>,
  codeSteps: readonly OfferedCodeStep[],
): readonly DeclaredField[] | undefined {
  const runs = step.runs;
  switch (runs.kind) {
    case "route":
      return [];
    case "question":
    case "workflow":
      return named.get(runs.version)?.takes;
    case "code_step":
      return codeSteps.find((offered) => offered.name === runs.codeStep)?.takes;
    case "none":
      return undefined;
  }
}

/**
 * What a step gives back, as what it runs declares it, a route's as typed and a code step's as the release declares
 * it; none where that is not known.
 */
export function givenBy(
  step: StepDraft,
  named: ReadonlyMap<string, Named>,
  codeSteps: readonly OfferedCodeStep[],
): readonly Shape[] | undefined {
  const runs = step.runs;
  switch (runs.kind) {
    case "route":
      return runs.gives.map(draftShapeOf);
    case "question":
    case "workflow":
      return named.get(runs.version)?.gives.map(shapeOf);
    case "code_step":
      return codeSteps
        .find((offered) => offered.name === runs.codeStep)
        ?.gives.map(shapeOf);
    case "none":
      return undefined;
  }
}

/**
 * Where a step's values may be read from: what the workflow takes, where that is offered, and each step before
 * it; a later step a binding already reads is kept, to be said to point forward.
 */
export function sourcesFor(
  flow: FlowDraft,
  position: number,
  workflow: WorkflowVersion,
  named: ReadonlyMap<string, Named>,
  step: StepDraft | undefined,
): SourceOffered[] {
  const read = new Set<string>();
  const readBy = (source: SourceDraft) => {
    if (source.from === "step") {
      read.add(source.stepKey);
    }
  };
  step?.bindings.forEach((binding) => readBy(binding.source));
  if (step?.runs.kind === "route") {
    readBy(step.runs.discriminator);
    step.runs.cases.forEach((routeCase) =>
      routeCase.bindings.forEach((binding) => readBy(binding.source)),
    );
  }
  return [
    ...(step === undefined
      ? []
      : [
          {
            value: "input",
            words: say("workflow.fromInput"),
            fields: workflow.takes.map(shapeOf),
          },
        ]),
    ...flow.steps
      .map((each, index) => ({ each, index }))
      .filter(
        ({ each, index }) =>
          each.key !== step?.key && (index < position || read.has(each.key)),
      )
      .map(({ each, index }) => ({
        value: each.key,
        words: say("workflow.fromStep", {
          position: index + 1,
          name:
            each.name === ""
              ? say("workflow.unnamed", { position: index + 1 })
              : each.name,
        }),
        fields: givenBy(each, named, workflow.offered.codeSteps),
      })),
  ];
}

/** The field a source reads, where it is a place and the page knows what that place declares. */
function reachedBy(
  source: SourceDraft,
  sources: readonly SourceOffered[],
): Reached | undefined {
  if (source.from !== "input" && source.from !== "step") {
    return undefined;
  }
  const place = source.from === "input" ? "input" : source.stepKey;
  const fields = sources.find((each) => each.value === place)?.fields;
  return fields === undefined
    ? undefined
    : pointers(fields).find((pointer) => pointer.path === source.path);
}

/**
 * What to say against one binding: that it reads a step not before it, what the server said of it as read, and
 * that what it reads may be empty where what it fills must be given.
 */
export function flagsOf(
  binding: BindingDraft,
  target: Shape,
  flow: FlowDraft,
  position: number,
  problems: readonly ContentProblem[],
  sources: readonly SourceOffered[],
): MessageId[] {
  const reached = reachedBy(binding.source, sources);
  return [
    ...(pointsForward(flow, position, binding.source)
      ? (["workflow.pointsForward"] as const)
      : []),
    ...problems
      .filter(
        (problem) =>
          binding.bindingId !== undefined &&
          problem.bindingId === binding.bindingId,
      )
      .map(problemWords),
    ...(reached !== undefined &&
    ((target.mustBeGiven && !reached.given) ||
      !givenWithin(reached.field, target))
      ? (["contentProblem.source_may_be_empty"] as const)
      : []),
  ];
}

/**
 * Every field a binding may fill, in reading order and down through one holding fields but not below one bound
 * whole, each with what does not hold of it; unfilled as the server judges it.
 */
export function targetRows(
  takes: readonly DeclaredField[],
  bindings: readonly BindingDraft[],
  problems: readonly ContentProblem[],
  judged: (binding: BindingDraft, target: Shape) => readonly MessageId[],
  unfilled: MessageId,
): TargetRow[] {
  const whole = new Set(bindings.map((binding) => binding.target));
  const held = new Set(
    bindings.flatMap((binding) =>
      binding.target
        .split(".")
        .map((_name, index, names) => names.slice(0, index + 1).join(".")),
    ),
  );
  const rows: TargetRow[] = [];
  const walk = (
    level: readonly DeclaredField[],
    above: string,
    depth: number,
    judging: boolean,
  ) => {
    for (const field of level) {
      const path = above === "" ? field.name : `${above}.${field.name}`;
      const opens = field.kind === "fields" && !field.many;
      const target = shapeOf(field);
      const binding = bindings.find((each) => each.target === path);
      const unbound = judging && !whole.has(path) && !(opens && held.has(path));
      rows.push({
        path,
        depth,
        target,
        binding,
        flags: [
          ...new Set([
            ...(binding === undefined ? [] : judged(binding, target)),
            ...problems
              .filter(
                (problem) =>
                  problem.fieldId === field.fieldId &&
                  !JUDGED_HERE.has(problem.code),
              )
              .map(problemWords),
            ...(unbound ? [unfilled] : []),
          ]),
        ],
      });
      if (opens && !whole.has(path)) {
        walk(field.fields ?? [], path, depth + 1, judging && held.has(path));
      }
    }
  };
  walk(takes, "", 0, true);
  return rows;
}

export function pointsForward(
  flow: FlowDraft,
  position: number,
  source: SourceDraft,
): boolean {
  if (source.from !== "step") {
    return false;
  }
  return (
    flow.steps.findIndex((step) => step.key === source.stepKey) >= position
  );
}

/** The terms of the list a route chooses by, in order; none where the page does not know them. */
export function termsChosenBy(
  route: RouteDraft,
  flow: FlowDraft,
  workflow: WorkflowVersion,
  named: ReadonlyMap<string, Named>,
): readonly string[] | undefined {
  const source = route.discriminator;
  if (source.from !== "input" && source.from !== "step") {
    return undefined;
  }
  const read =
    source.from === "step"
      ? flow.steps.find((step) => step.key === source.stepKey)
      : undefined;
  const level =
    source.from === "input"
      ? workflow.takes.map(shapeOf)
      : read === undefined
        ? undefined
        : givenBy(read, named, workflow.offered.codeSteps);
  const field = level === undefined ? undefined : fieldAt(level, source.path);
  return field?.kind === "term" && field.list !== undefined
    ? workflow.terms.get(field.list)
    : undefined;
}

/** How many cases a route may hold: one on each term its list offers, and a fallback. */
export function mostCases(terms: readonly string[] | undefined): number {
  return (terms?.length ?? MOST_TERMS) + 1;
}

/** The flow with the step at {@code index} swapped with the one {@code by} rows from it; none past either end. */
export function movedStep(
  flow: FlowDraft,
  index: number,
  by: -1 | 1,
): FlowDraft {
  const there = index + by;
  if (there < 0 || there >= flow.steps.length) {
    return flow;
  }
  const steps = [...flow.steps];
  [steps[index], steps[there]] = [steps[there]!, steps[index]!];
  return { ...flow, steps };
}

/** The flow without the step at {@code index}; whatever read it reads nothing chosen. */
export function removedStep(flow: FlowDraft, index: number): FlowDraft {
  const gone = flow.steps[index]?.key;
  const unread = (source: SourceDraft): SourceDraft =>
    source.from === "step" && source.stepKey === gone
      ? { from: "none" }
      : source;
  const unbound = (binding: BindingDraft): BindingDraft => ({
    ...binding,
    source: unread(binding.source),
  });
  return {
    steps: flow.steps
      .filter((_step, at) => at !== index)
      .map((step) => ({
        ...step,
        bindings: step.bindings.map(unbound),
        runs:
          step.runs.kind === "route"
            ? {
                ...step.runs,
                discriminator: unread(step.runs.discriminator),
                cases: step.runs.cases.map((routeCase) => ({
                  ...routeCase,
                  bindings: routeCase.bindings.map(unbound),
                })),
              }
            : step.runs,
      })),
    outputs: flow.outputs.map(unbound),
  };
}

/** A step running {@code versionId} in place of what it pins, keeping each binding whose target it still declares. */
export function tookVersion(
  step: StepDraft,
  versionId: string,
  named: ReadonlyMap<string, Named>,
): StepDraft {
  if (step.runs.kind !== "question" && step.runs.kind !== "workflow") {
    return step;
  }
  return {
    ...step,
    runs: { kind: step.runs.kind, version: versionId },
    bindings: stillDeclared(step.bindings, versionId, named),
  };
}

/** A case leading to {@code versionId} in place of what it pins, keeping bindings as `tookVersion` does. */
export function caseTookVersion(
  routeCase: CaseDraft,
  versionId: string,
  named: ReadonlyMap<string, Named>,
): CaseDraft {
  return {
    ...routeCase,
    workflow: versionId,
    bindings: stillDeclared(routeCase.bindings, versionId, named),
  };
}

function stillDeclared(
  bindings: readonly BindingDraft[],
  versionId: string,
  named: ReadonlyMap<string, Named>,
): BindingDraft[] {
  const takes = (named.get(versionId)?.takes ?? []).map(shapeOf);
  return bindings
    .filter((binding) => fieldAt(takes, binding.target) !== undefined)
    .map(({ target, source }) => ({ target, source }));
}

export function unusedKey(prefix: string, keys: readonly string[]): string {
  const taken = new Set(keys);
  let count = keys.length + 1;
  while (taken.has(`${prefix}-${count}`)) {
    count += 1;
  }
  return `${prefix}-${count}`;
}

export function freshStep(key: string): StepDraft {
  return {
    key,
    stepId: null,
    name: "",
    runs: { kind: "none" },
    producer: "",
    model: "",
    mode: "",
    toldWhatHappened: false,
    tries: "",
    reviewer: "person",
    reviewerModel: "",
    reviewerMode: "",
    bindings: [],
  };
}

export function freshCase(key: string): CaseDraft {
  return {
    key,
    caseId: null,
    fallback: false,
    term: "",
    workflow: "",
    bindings: [],
  };
}

/**
 * The bindings with the one for {@code target} set to {@code source}, and any filling part of it or what holds
 * it taken away; nothing chosen takes away only its own.
 */
export function boundTo(
  bindings: readonly BindingDraft[],
  target: string,
  source: SourceDraft,
): BindingDraft[] {
  if (source.from === "none") {
    return bindings.filter((binding) => binding.target !== target);
  }
  const kept = bindings.filter(
    (binding) =>
      binding.target !== target &&
      !binding.target.startsWith(`${target}.`) &&
      !target.startsWith(`${binding.target}.`),
  );
  return [...kept, { target, source }];
}

/**
 * The flow as it is sent, and the first thing in reading order the server would refuse on arrival, or null. A
 * source chosen with nothing within it is not sent; reading a later step is, and submitting names it.
 */
export function sentFlowOf(
  flow: FlowDraft,
  workflow: WorkflowVersion,
  named: ReadonlyMap<string, Named>,
): {
  readonly steps: SentStep[];
  readonly outputs: SentBinding[];
  readonly held: FlowHeld | null;
} {
  const places = new Map(flow.steps.map((step, index) => [step.key, index]));
  const held: FlowHeld[] = [];
  const hold = (why: MessageId, step?: string) => {
    held.push(step === undefined ? { why } : { why, step });
  };
  const sourceOf = (
    source: SourceDraft,
    target: Shape | undefined,
    filling: string | undefined,
    row: string | undefined,
  ): BindingSource | null => {
    switch (source.from) {
      case "none":
        return null;
      case "input":
        return source.path === "" ? null : { input: source.path };
      case "step": {
        const index = places.get(source.stepKey);
        if (index === undefined || source.stepKey === filling) {
          hold("workflow.sourceItself", row);
          return null;
        }
        return source.path === "" ? null : { step: index, path: source.path };
      }
      case "constant": {
        if (source.read !== undefined) {
          return { constant: source.read };
        }
        const read = constantRead(source.typed, target);
        if ("why" in read) {
          hold(read.why, row);
          return null;
        }
        return { constant: read.written };
      }
    }
  };
  const bindingsOf = (
    bindings: readonly BindingDraft[],
    takes: readonly Shape[] | undefined,
    filling: string | undefined,
    row: string | undefined,
  ): SentBinding[] => {
    const sent = bindings.flatMap((binding) => {
      const source = sourceOf(
        binding.source,
        takes === undefined ? undefined : fieldAt(takes, binding.target),
        filling,
        row,
      );
      return source === null ? [] : [{ target: binding.target, source }];
    });
    if (sent.length > (takes === undefined ? MOST_FIELDS : counted(takes))) {
      hold("refusal.BINDINGS_PAST_INPUTS", row);
    }
    return sent;
  };
  const steps = flow.steps.map((step): SentStep => {
    if (!fieldNameFits(step.name)) {
      hold("refusal.STEP_NAME_UNUSABLE", step.key);
    }
    const produces =
      step.runs.kind === "question" || step.runs.kind === "code_step";
    if (produces && step.tries !== "" && !triesFit(step.tries)) {
      hold("workflow.triesLimit", step.key);
    }
    if (produces && step.reviewer === "model" && step.reviewerModel === "") {
      hold("workflow.reviewerUnchosen", step.key);
    }
    const runs = runsOf(step, flow, workflow, named, hold, (bindings, takes) =>
      bindingsOf(bindings, takes, undefined, step.key),
    );
    const discriminator =
      step.runs.kind === "route"
        ? sourceOf(step.runs.discriminator, undefined, step.key, step.key)
        : null;
    return {
      stepId: step.stepId,
      name: step.name,
      runs:
        runs !== null && runs.kind === "route"
          ? { ...runs, discriminator }
          : runs,
      ...(produces
        ? {
            producer: producerOf(step),
            tries: step.tries === "" ? null : Number(step.tries),
            reviewer: reviewerOf(step),
          }
        : {}),
      bindings: bindingsOf(
        step.bindings,
        takesOf(step, named, workflow.offered.codeSteps)?.map(shapeOf),
        step.key,
        step.key,
      ),
    };
  });
  const outputs = bindingsOf(
    flow.outputs,
    workflow.gives.map(shapeOf),
    undefined,
    undefined,
  );
  return { steps, outputs, held: held[0] ?? null };
}

function runsOf(
  step: StepDraft,
  flow: FlowDraft,
  workflow: WorkflowVersion,
  named: ReadonlyMap<string, Named>,
  hold: (why: MessageId, step?: string) => void,
  bindingsOf: (
    bindings: readonly BindingDraft[],
    takes: readonly Shape[] | undefined,
  ) => SentBinding[],
): SentRuns | null {
  const runs = step.runs;
  switch (runs.kind) {
    case "none":
      return null;
    case "question":
    case "workflow":
      return runs.version === ""
        ? null
        : { kind: runs.kind, version: runs.version };
    case "code_step":
      return {
        kind: "code_step",
        codeStep: runs.codeStep === "" ? null : runs.codeStep,
      };
    case "route": {
      const gives = heldFor(runs.gives, WORKFLOW_GIVES);
      if (gives !== null) {
        hold(gives.why, step.key);
      }
      const terms = new Set<string>();
      for (const routeCase of runs.cases) {
        if (routeCase.fallback) {
          continue;
        }
        if (!termFits(routeCase.term)) {
          hold("workflow.termLimit", step.key);
        } else if (terms.has(routeCase.term)) {
          hold("refusal.CASE_TERM_REPEATED", step.key);
        }
        terms.add(routeCase.term);
      }
      if (
        runs.cases.length >
        mostCases(termsChosenBy(runs, flow, workflow, named))
      ) {
        hold("refusal.CASES_PAST_TERMS", step.key);
      }
      return {
        kind: "route",
        discriminator: null,
        gives: runs.gives.map((draft) => sentOf(draft, WORKFLOW_GIVES)),
        cases: runs.cases.map((routeCase): SentCase => ({
          caseId: routeCase.caseId,
          term: routeCase.fallback ? null : routeCase.term,
          workflow: routeCase.workflow === "" ? null : routeCase.workflow,
          bindings: bindingsOf(
            routeCase.bindings,
            routeCase.workflow === ""
              ? undefined
              : named.get(routeCase.workflow)?.takes.map(shapeOf),
          ),
        })),
      };
    }
  }
}

/** Every field a level holds, at every depth together, as the server counts a declaration's. */
function counted(level: readonly Shape[]): number {
  return level.reduce((sum, field) => sum + 1 + counted(field.fields ?? []), 0);
}

/** A question's values are produced by a model or a person, a code step's by code or a person saying so. */
function producerOf(step: StepDraft): SentProducer | null {
  if (step.producer === "person") {
    return { kind: "person" };
  }
  if (step.runs.kind === "code_step") {
    return step.producer === "code" ? { kind: "code" } : null;
  }
  if (step.producer === "model" && step.model !== "") {
    return {
      kind: "model",
      model: step.model,
      mode: step.mode === "" ? null : step.mode,
      toldWhatHappened: step.toldWhatHappened,
    };
  }
  return null;
}

function reviewerOf(step: StepDraft): SentChoice | null {
  return step.reviewer === "model" && step.reviewerModel !== ""
    ? {
        model: step.reviewerModel,
        mode: step.reviewerMode === "" ? null : step.reviewerMode,
      }
    : null;
}

export function triesFit(typed: string): boolean {
  return WHOLE.test(typed) && Number(typed) <= LARGEST_TRIES;
}

/** One to the column's bound in characters, on one line, as a list's term is. */
export function termFits(typed: string): boolean {
  return oneLineWithin(typed, LONGEST_TERM);
}
