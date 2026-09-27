import { get } from "../../../../../../lib/request/http";
import {
  countFrom,
  isUnchecked,
  listOf,
  optional,
  present,
  textFrom,
} from "../../../../../../lib/request/document";
import {
  addedFrom,
  declaredFrom,
  listVersionFrom,
  pinnedFrom,
  type AddedField,
  type DeclaredField,
  type ListVersion,
  type PinnedList,
} from "../../../../../declaration";
import { servedAmong, type Problem } from "../../../../../problem";
import {
  atVersion,
  contentProblemFrom,
  type ContentProblem,
} from "../versions";

const WRITTEN_SINCE_READ: ReadonlySet<string> = new Set([
  "DRAFT_WRITTEN_SINCE_READ",
]);

/** One version of a question as the library reads it, which every write to it answers with too. */
export interface QuestionVersion {
  /** What a write to it sends back as the one it read. */
  readonly revision: number;
  /** Absent where it tells nobody anything yet. */
  readonly instruction?: string;
  readonly takes: readonly DeclaredField[];
  readonly gives: readonly DeclaredField[];
  /** What a model is told of what it gives back; absent where that half is not whole yet. */
  readonly added?: readonly AddedField[];
  /** Every list of the group's in service, which is all a field of terms may pin. */
  readonly lists: readonly ListVersion[];
}

/** One version of a reference list as the library reads it, which every write to it answers with too. */
export interface ReferenceListVersion {
  /** What a write to it sends back as the one it read. */
  readonly revision: number;
  /** Absent where it says nothing on choosing among its terms. */
  readonly note?: string;
  /** In the order the version gives them. */
  readonly terms: readonly ListTerm[];
}

/** One term of a list. `termId` addresses a write to it, and is kept until it is removed. */
export interface ListTerm {
  readonly termId: string;
  readonly term: string;
  readonly meaning: string;
  /** Present only where a term before it is this one as submitting compares them, which refuses it. */
  readonly alikeEarlier?: true;
}

export function readQuestion(
  groupId: string,
  entryId: string,
  versionId: string,
  signal: AbortSignal,
): Promise<QuestionVersion> {
  return atVersion(groupId, "question", entryId, versionId, (address) =>
    get(address, signal, questionFrom),
  );
}

export function readReferenceList(
  groupId: string,
  entryId: string,
  versionId: string,
  signal: AbortSignal,
): Promise<ReferenceListVersion> {
  return atVersion(groupId, "reference_list", entryId, versionId, (address) =>
    get(address, signal, referenceListFrom),
  );
}

/**
 * Whether a write was refused for the draft having been written since the page
 * read it, which only reading it afresh puts right. The status is read before
 * the code, as `Problem` requires.
 */
export function writtenSinceRead(problem: Problem): boolean {
  return servedAmong(problem, WRITTEN_SINCE_READ);
}

/** Built member by member; a version missing any of what it is drawn from is not one this side can show. */
export function questionFrom(body: unknown): QuestionVersion | null {
  if (!isUnchecked(body)) {
    return null;
  }
  const revision = revisionFrom(body.revision);
  const instruction = optional(body, "instruction", (said) =>
    typeof said === "string" ? said : null,
  );
  const takes = listOf(body.takes, declaredFrom);
  const gives = listOf(body.gives, declaredFrom);
  const added = optional(body, "added", (told) => listOf(told, addedFrom));
  const lists = listOf(body.lists, listVersionFrom);
  if (
    revision === null ||
    instruction === null ||
    takes === null ||
    gives === null ||
    added === null ||
    lists === null
  ) {
    return null;
  }
  return {
    revision,
    takes,
    gives,
    lists,
    ...(instruction === undefined ? {} : { instruction }),
    ...(added === undefined ? {} : { added }),
  };
}

/** What a step may run, spelt the way the server spells it and held level with its list for the reason `SurfaceAct` gives. */
export type RunsKind = "question" | "workflow" | "code_step" | "route";

/** Who produces a step's values, spelt and held level as `RunsKind` is. */
export type ProducerKind = "model" | "person" | "code";

/** What a step asks a model to do, spelt and held level as `RunsKind` is. */
export type SendingRole = "producing" | "reviewing";

/** A step that could send a model more than it takes, worked out as the version was read; it refuses nothing. */
export interface SendPast {
  readonly stepId: string;
  readonly role: SendingRole;
  readonly model: string;
  /** About how many of the model's own units past what it takes. */
  readonly past: number;
}

/**
 * Where a bound value comes from: what the workflow takes, a step by its place among the steps, or a constant.
 * A constant crosses as its JSON text both ways, so none of its numbers is ever held as a double and rounded.
 */
export type BindingSource =
  | { readonly input: string }
  | { readonly step: number; readonly path: string }
  | { readonly constant: string };

/** One input filled, or what a route chooses by, which alone names no target. */
export interface Binding {
  readonly bindingId: string;
  readonly target?: string;
  readonly source: BindingSource;
}

/** A model a version names, and the mode it runs in, absent where it runs as it is. */
interface ModelChoice {
  readonly model: string;
  readonly mode?: string;
}

interface Producer {
  readonly kind: ProducerKind;
  readonly model?: string;
  readonly mode?: string;
  readonly toldWhatHappened?: boolean;
}

/** One way a route may go; the fallback holds no term, and a case leading nowhere yet no workflow. */
interface RouteCase {
  readonly caseId: string;
  readonly term?: string;
  readonly workflow?: PinnedList;
  readonly takes?: readonly DeclaredField[];
  readonly gives?: readonly DeclaredField[];
  readonly bindings: readonly Binding[];
}

/** What a step runs, only the members its kind takes present. */
interface Runs {
  readonly kind: RunsKind;
  readonly version?: PinnedList;
  readonly takes?: readonly DeclaredField[];
  readonly gives?: readonly DeclaredField[];
  readonly codeStep?: string;
  readonly discriminator?: Binding;
  readonly cases?: readonly RouteCase[];
}

/** One step, in the order it runs; what nothing has chosen yet is absent. */
export interface Step {
  readonly stepId: string;
  readonly name: string;
  readonly runs?: Runs;
  readonly producer?: Producer;
  readonly tries?: number;
  readonly reviewer?: ModelChoice;
  readonly bindings: readonly Binding[];
}

/** A version of the group's in service that a step or a case may pin, with what it declares. */
interface DeclaredVersion extends ListVersion {
  readonly takes: readonly DeclaredField[];
  readonly gives: readonly DeclaredField[];
}

export interface HeldModel {
  readonly name: string;
  readonly modes: readonly string[];
}

/**
 * A code step published to the group that this release holds, with what the
 * release declares of it; each field's key holds while the release does.
 */
export interface OfferedCodeStep {
  readonly name: string;
  readonly takes: readonly DeclaredField[];
  readonly gives: readonly DeclaredField[];
}

interface WorkflowOffers {
  readonly lists: readonly ListVersion[];
  readonly questions: readonly DeclaredVersion[];
  readonly workflows: readonly DeclaredVersion[];
  readonly codeSteps: readonly OfferedCodeStep[];
  readonly models: readonly HeldModel[];
}

/** One version of a workflow as the library reads it, which every write to it answers with too. */
export interface WorkflowVersion {
  /** The revision the page read, which every write names. */
  readonly revision: number;
  readonly takes: readonly DeclaredField[];
  readonly gives: readonly DeclaredField[];
  readonly steps: readonly Step[];
  readonly outputs: readonly Binding[];
  /** In digits, absent where runs of it have none. */
  readonly ceiling?: string;
  readonly keepsOwnCeiling: boolean;
  readonly raiseNeedsApproval: boolean;
  readonly mayBeHelped: boolean;
  readonly helper?: ModelChoice;
  /** What submitting would refuse of it now, each placed as a refusal places it. */
  readonly problems: readonly ContentProblem[];
  /** In the order its steps run, producing before reviewing. */
  readonly sendsPast: readonly SendPast[];
  readonly offered: WorkflowOffers;
  /** The terms, in order, of every list a field it names pins, by the list's version. */
  readonly terms: ReadonlyMap<string, readonly string[]>;
}

const RUNS_KINDS = {
  question: true,
  workflow: true,
  code_step: true,
  route: true,
} satisfies Record<RunsKind, true>;

const PRODUCER_KINDS = {
  model: true,
  person: true,
  code: true,
} satisfies Record<ProducerKind, true>;

const SENDING_ROLES = {
  producing: true,
  reviewing: true,
} satisfies Record<SendingRole, true>;

export function readWorkflow(
  groupId: string,
  entryId: string,
  versionId: string,
  signal: AbortSignal,
): Promise<WorkflowVersion> {
  return atVersion(groupId, "workflow", entryId, versionId, (address) =>
    get(address, signal, workflowFrom),
  );
}

/** Built member by member; a version missing any of what it is drawn from is not one this side can show. */
export function workflowFrom(body: unknown): WorkflowVersion | null {
  if (!isUnchecked(body)) {
    return null;
  }
  const { keepsOwnCeiling, raiseNeedsApproval, mayBeHelped } = body;
  const revision = revisionFrom(body.revision);
  const takes = listOf(body.takes, declaredFrom);
  const gives = listOf(body.gives, declaredFrom);
  const steps = listOf(body.steps, stepFrom);
  const outputs = listOf(body.outputs, bindingFrom);
  const ceiling = optional(body, "ceiling", textFrom);
  const helper = optional(body, "helper", choiceFrom);
  const problems = listOf(body.problems, contentProblemFrom);
  const sendsPast = listOf(body.sendsPast, sendPastFrom);
  const offered = offersFrom(body.offered);
  const terms = termsFrom(body.terms);
  if (
    revision === null ||
    typeof keepsOwnCeiling !== "boolean" ||
    typeof raiseNeedsApproval !== "boolean" ||
    typeof mayBeHelped !== "boolean" ||
    takes === null ||
    gives === null ||
    steps === null ||
    outputs === null ||
    ceiling === null ||
    helper === null ||
    problems === null ||
    sendsPast === null ||
    offered === null ||
    terms === null
  ) {
    return null;
  }
  return {
    revision,
    takes,
    gives,
    steps,
    outputs,
    keepsOwnCeiling,
    raiseNeedsApproval,
    mayBeHelped,
    problems,
    sendsPast,
    offered,
    terms,
    ...(ceiling === undefined ? {} : { ceiling }),
    ...(helper === undefined ? {} : { helper }),
  };
}

function termsFrom(body: unknown): Map<string, readonly string[]> | null {
  if (!isUnchecked(body)) {
    return null;
  }
  const terms = new Map<string, readonly string[]>();
  for (const [list, offered] of Object.entries(body)) {
    const read = listOf(offered, textFrom);
    if (read === null) {
      return null;
    }
    terms.set(list, read);
  }
  return terms;
}

/** Past a double's exact integers the figure arrives rounded, which an about figure may be. */
function sendPastFrom(body: unknown): SendPast | null {
  if (
    !isUnchecked(body) ||
    typeof body.stepId !== "string" ||
    typeof body.role !== "string" ||
    !Object.hasOwn(SENDING_ROLES, body.role) ||
    typeof body.model !== "string" ||
    typeof body.past !== "number" ||
    !Number.isInteger(body.past) ||
    body.past < 1
  ) {
    return null;
  }
  return {
    stepId: body.stepId,
    role: body.role as SendingRole,
    model: body.model,
    past: body.past,
  };
}

function stepFrom(body: unknown): Step | null {
  if (!isUnchecked(body)) {
    return null;
  }
  const { stepId, name } = body;
  const runs = optional(body, "runs", runsFrom);
  const producer = optional(body, "producer", producerFrom);
  const tries = optional(body, "tries", countFrom);
  const reviewer = optional(body, "reviewer", choiceFrom);
  const bindings = listOf(body.bindings, bindingFrom);
  if (
    typeof stepId !== "string" ||
    typeof name !== "string" ||
    [runs, producer, tries, reviewer, bindings].includes(null)
  ) {
    return null;
  }
  return {
    stepId,
    name,
    bindings: bindings!,
    ...(runs === undefined ? {} : { runs: runs! }),
    ...(producer === undefined ? {} : { producer: producer! }),
    ...(tries === undefined ? {} : { tries: tries! }),
    ...(reviewer === undefined ? {} : { reviewer: reviewer! }),
  };
}

function runsFrom(body: unknown): Runs | null {
  if (
    !isUnchecked(body) ||
    typeof body.kind !== "string" ||
    !Object.hasOwn(RUNS_KINDS, body.kind)
  ) {
    return null;
  }
  const members = {
    version: optional(body, "version", pinnedFrom),
    takes: optional(body, "takes", (held) => listOf(held, declaredFrom)),
    gives: optional(body, "gives", (held) => listOf(held, declaredFrom)),
    codeStep: optional(body, "codeStep", textFrom),
    discriminator: optional(body, "discriminator", bindingFrom),
    cases: optional(body, "cases", (held) => listOf(held, caseFrom)),
  };
  if (Object.values(members).includes(null)) {
    return null;
  }
  return { kind: body.kind as RunsKind, ...present(members) };
}

function caseFrom(body: unknown): RouteCase | null {
  if (!isUnchecked(body) || typeof body.caseId !== "string") {
    return null;
  }
  const members = {
    term: optional(body, "term", textFrom),
    workflow: optional(body, "workflow", pinnedFrom),
    takes: optional(body, "takes", (held) => listOf(held, declaredFrom)),
    gives: optional(body, "gives", (held) => listOf(held, declaredFrom)),
  };
  const bindings = listOf(body.bindings, bindingFrom);
  if (Object.values(members).includes(null) || bindings === null) {
    return null;
  }
  return { caseId: body.caseId, bindings, ...present(members) };
}

function bindingFrom(body: unknown): Binding | null {
  if (!isUnchecked(body) || typeof body.bindingId !== "string") {
    return null;
  }
  const target = optional(body, "target", textFrom);
  const source = sourceFrom(body.source);
  if (target === null || source === null) {
    return null;
  }
  return target === undefined
    ? { bindingId: body.bindingId, source }
    : { bindingId: body.bindingId, target, source };
}

/** Each source is its own members alone, so one holding two ways in is none. */
function sourceFrom(body: unknown): BindingSource | null {
  if (!isUnchecked(body)) {
    return null;
  }
  const members = Object.keys(body);
  if (members.length === 1 && typeof body.input === "string") {
    return { input: body.input };
  }
  if (
    members.length === 2 &&
    Number.isSafeInteger(body.step) &&
    typeof body.path === "string"
  ) {
    return { step: body.step as number, path: body.path };
  }
  if (members.length === 1 && isJsonText(body.constant)) {
    return { constant: body.constant };
  }
  return null;
}

/** Parsed only to be sure it is JSON; what the parse holds is dropped, being read through doubles. */
function isJsonText(text: unknown): text is string {
  if (typeof text !== "string") {
    return false;
  }
  try {
    JSON.parse(text);
    return true;
  } catch {
    return false;
  }
}

function producerFrom(body: unknown): Producer | null {
  if (
    !isUnchecked(body) ||
    typeof body.kind !== "string" ||
    !Object.hasOwn(PRODUCER_KINDS, body.kind)
  ) {
    return null;
  }
  const members = {
    model: optional(body, "model", textFrom),
    mode: optional(body, "mode", textFrom),
    toldWhatHappened: optional(body, "toldWhatHappened", (said) =>
      typeof said === "boolean" ? said : null,
    ),
  };
  if (Object.values(members).includes(null)) {
    return null;
  }
  return { kind: body.kind as ProducerKind, ...present(members) };
}

function choiceFrom(body: unknown): ModelChoice | null {
  if (!isUnchecked(body) || typeof body.model !== "string") {
    return null;
  }
  const mode = optional(body, "mode", textFrom);
  if (mode === null) {
    return null;
  }
  return mode === undefined
    ? { model: body.model }
    : { model: body.model, mode };
}

function offersFrom(body: unknown): WorkflowOffers | null {
  if (!isUnchecked(body)) {
    return null;
  }
  const lists = listOf(body.lists, listVersionFrom);
  const questions = listOf(body.questions, declaredVersionFrom);
  const workflows = listOf(body.workflows, declaredVersionFrom);
  const codeSteps = listOf(body.codeSteps, codeStepFrom);
  const models = listOf(body.models, modelFrom);
  if (
    lists === null ||
    questions === null ||
    workflows === null ||
    codeSteps === null ||
    models === null
  ) {
    return null;
  }
  return { lists, questions, workflows, codeSteps, models };
}

function declaredVersionFrom(body: unknown): DeclaredVersion | null {
  const listed = listVersionFrom(body);
  if (listed === null || !isUnchecked(body)) {
    return null;
  }
  const takes = listOf(body.takes, declaredFrom);
  const gives = listOf(body.gives, declaredFrom);
  return takes === null || gives === null ? null : { ...listed, takes, gives };
}

/** Built member by member, as a version offered with what it declares is. */
function codeStepFrom(body: unknown): OfferedCodeStep | null {
  if (!isUnchecked(body) || typeof body.name !== "string") {
    return null;
  }
  const takes = listOf(body.takes, declaredFrom);
  const gives = listOf(body.gives, declaredFrom);
  return takes === null || gives === null
    ? null
    : { name: body.name, takes, gives };
}

function modelFrom(body: unknown): HeldModel | null {
  if (!isUnchecked(body) || typeof body.name !== "string") {
    return null;
  }
  const modes = listOf(body.modes, textFrom);
  return modes === null ? null : { name: body.name, modes };
}

/** Built member by member, as `questionFrom` is. */
export function referenceListFrom(body: unknown): ReferenceListVersion | null {
  if (!isUnchecked(body)) {
    return null;
  }
  const revision = revisionFrom(body.revision);
  const note = optional(body, "note", textFrom);
  const terms = listOf(body.terms, termFrom);
  if (revision === null || note === null || terms === null) {
    return null;
  }
  return note === undefined ? { revision, terms } : { revision, note, terms };
}

function termFrom(body: unknown): ListTerm | null {
  if (!isUnchecked(body)) {
    return null;
  }
  const { termId, term, meaning } = body;
  // Left out where false, so a false that arrives is no answer the server gives.
  const alikeEarlier = optional(body, "alikeEarlier", (said) =>
    said === true ? true : null,
  );
  if (
    typeof termId !== "string" ||
    typeof term !== "string" ||
    typeof meaning !== "string" ||
    alikeEarlier === null
  ) {
    return null;
  }
  return alikeEarlier === undefined
    ? { termId, term, meaning }
    : { termId, term, meaning, alikeEarlier };
}

/** The server counts from one, so nothing below it is a revision it sent. */
function revisionFrom(said: unknown): number | null {
  return Number.isSafeInteger(said) && (said as number) > 0
    ? (said as number)
    : null;
}
