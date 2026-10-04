import {
  allMembersRead,
  countFrom,
  isUnchecked,
  listOf,
  optional,
  present,
  textFrom,
  wordsIn,
  type Unchecked,
} from "../../../../../lib/request/document";
import { get } from "../../../../../lib/request/http";
import {
  isShownValue,
  readFieldsFrom,
  type ReadField,
  type ShownValue,
} from "../../../../filling";
import { personFrom, type Person } from "../../../../person";
import { atRun, DIGITS, spendFrom, type Spend } from "../{runId}";

/** Where a step is, spelt and held level as `RunState` is. */
export type StepState =
  "not_started" | "running" | "held_back" | "waiting" | "failed" | "done";

/** Whom a step waits on, spelt and held level as `StepState` is. */
export type WaitsOn = "review_at_gate" | "model" | "answer_step" | "starter";

/** What may be done to a step, spelt and held level as `StepState` is. */
export type StepAct = "answer" | "ask_again" | "review" | "try_sending";

/** Each kind of a step's whereabouts, spelt and held level as `StepState` is. */
export type WhereKind =
  "running" | "held_back" | "waiting_on_review" | "owed_try" | "failed";

/** What a running step has out, spelt and held level as `StepState` is. */
export type RunningOn = "code" | "call" | "next_try";

/** Why a step is held back, spelt and held level as `StepState` is. */
export type HoldReason =
  | "entry_stopped"
  | "too_long"
  | "turned_away"
  | "code_step_not_held"
  | "search_way_not_held";

/** Why a step failed, its tries spent or a failure written down, spelt and held level as `StepState` is. */
export type FailureReason =
  | "tries_spent"
  | "uncuttable_length"
  | "unclaimed_value"
  | "model_not_deployed"
  | "too_deep"
  | "tool_not_offered"
  | "source_no_longer_matches"
  | "source_list_not_here";

/** What a stop holding a step back stopped, spelt and held level as `StepState` is. */
export type StoppedWhat = "workflow" | "entry";

/** How a model's review of a value waiting on it stands, spelt and held level as `StepState` is. */
export type ReviewSending =
  | "unsent"
  | "out"
  | "turned_away"
  | "too_long"
  | "list_not_here"
  | "takes_no_longer_declared"
  | "no_longer_declared"
  | "holds_a_file";

/** Every refusal an act is withheld for, spelt and held level as `StepState` is; one moved on is never withheld. */
export type WithheldRefusal =
  | "ACT_NOT_PERMITTED"
  | "RUN_STOPPED"
  | "ENTRY_STOPPED"
  | "REVIEW_NOT_A_PERSONS"
  | "REVIEW_OWN_PRODUCTION"
  | "ASK_AGAIN_NOT_OFFERED"
  | "CODE_STEP_GIVES_OTHERWISE";

export interface RunHeader {
  readonly runId: string;
  readonly number: number;
  readonly versionId: string;
  readonly state: string;
  readonly progress: { readonly done: number; readonly of: number };
}

/** Only to be read, so a field of a kind this build does not know is said so rather than refusing the read. */
export interface Declared {
  readonly takes: readonly ReadField[];
  readonly gives: readonly ReadField[];
}

/** Withheld is never none: it is a value the reader may not read, and is said so. */
export type Shown =
  { readonly value: ShownValue } | { readonly withheld: true };

/**
 * A value of a shape its field no longer has, or of a field no longer declared, given as it was kept: its JSON
 * text, none as null, under the name it was kept under.
 */
type AsKept = { readonly asKept: string | null };

/** A step's own value as shown, which only a code step's release can have left in an earlier shape. */
export type ShownOrKept = Shown | AsKept;

/** `field` is the field's names from the first level, joined by dots. */
export type ShownField = Shown & { readonly field: string };

/** A value a try gave back, and where it stands within that try. */
export type GivenValue = ShownOrKept & {
  readonly field: string;
  readonly now: string;
};

export interface GaveBack {
  readonly declares: string;
  readonly standing?: readonly ShownField[];
}

export interface StepRuns {
  readonly kind: string;
  readonly entryId?: string;
  readonly name?: string;
  readonly version?: number;
  readonly versionId?: string;
  readonly codeStep?: string;
  readonly searchWay?: string;
  readonly tool?: string;
}

export interface Who {
  readonly kind: string;
  readonly person?: Person;
  readonly model?: string;
  readonly mode?: string;
}

/** Every member but `kind` belongs to some kinds only; each is kept as it came, a kind unknown here included. */
export interface Where {
  readonly kind: string;
  readonly reason?: string;
  readonly source?: HeldSource;
  readonly failedSourceNoLongerMatches?: SourceNoLongerMatches;
  readonly failedSourceListNotHere?: SourceNoLongerMatches;
  readonly tool?: string;
  readonly searchQuestion?: string;
  readonly searchQuestionWithheld?: true;
  /** Where the model turned it away as what may be spent with it is used up. */
  readonly spentUp?: true;
  /** The model a step failed for not being held, and the mode it was named in, where it runs in one. */
  readonly model?: string;
  readonly mode?: string;
  /** Where that model was the one to review it, rather than to produce it. */
  readonly reviewing?: true;
  readonly review?: string;
  /** The try whose values wait on review, which is the one a review names. */
  readonly number?: number;
  readonly values?: readonly WaitingValue[];
  readonly open?: boolean;
  readonly on?: string;
  readonly stopped?: StepStopped;
  /** Each time a call of the newest try, which the hold holds, was turned away, oldest first. */
  readonly turnedAway?: readonly Turnaway[];
  /** ISO 8601: since when it has been where it is. */
  readonly since?: string;
  /** Absent where it waits on nobody, which is everywhere on a stopped run. */
  readonly waitsOn?: string;
  readonly failedUnclaimed?:
    | { readonly term: string | null; readonly withheld?: never }
    | { readonly term?: never; readonly withheld: true };
  /** How deep runs may go, counting the run at the top: present exactly where it failed for going deeper. */
  readonly failedTooDeep?: { readonly most: number };
}

export interface HeldSource {
  readonly runId: string;
  readonly runNumber: number;
  readonly stepId: string;
  readonly name: string;
  readonly codeStep: string;
}

export interface SourceNoLongerMatches {
  readonly source: HeldSource;
  readonly field: string;
}

interface WaitingValue {
  readonly field: string;
}

interface StepStopped {
  readonly what: string;
  /** Absent where whoever stopped it is no longer to be found. */
  readonly by?: Person;
  /** ISO 8601. */
  readonly at: string;
}

/**
 * One time a model turned a call away, which spent no try and cost nothing. `said` is what the model said, with
 * whether it was cut to its bound; `withheld` stands in for it where the reader may not read what a model said.
 */
export interface Turnaway {
  /** ISO 8601. */
  readonly at: string;
  readonly said?: string;
  readonly cut?: boolean;
  readonly withheld?: true;
  readonly sentAgain: boolean;
}

export interface From {
  readonly kind: string;
  readonly runId?: string;
  readonly runNumber?: number;
  readonly path?: string;
  readonly stepId?: string;
  readonly name?: string;
  /** From an earlier step, the version it pins, which is what `declarations` holds it by. */
  readonly versionId?: string;
  /** From an earlier code step, the code step it runs, which is what `declarations` holds it by. */
  readonly codeStep?: string;
  readonly searchWay?: string;
}

export interface TakesFrom {
  readonly sourcePartId?: string;
  readonly input: string;
  readonly from: From;
}

export interface InputSource {
  readonly targetPath: string;
  readonly from: From;
}

export type InputFrom = {
  readonly input: string;
  readonly sourcePartId?: string;
} & (
  | { readonly from: From; readonly sources?: never }
  | { readonly from?: never; readonly sources: readonly InputSource[] }
);

/** No spend where it calls no model: it cost nothing, which is not a count of nought. */
export interface Cost {
  readonly spend?: Spend;
}

interface NextTry {
  readonly number: number;
  /** Whether it goes past the tries the step declares. */
  readonly beyond: boolean;
}

/** A later step reading the field, or the workflow output it is read into, by its field names joined by dots. */
export type ReadBy = { readonly step: string } | { readonly output: string };

export interface GivesOtherwise {
  readonly field: string;
  readonly readBy?: ReadBy;
}

/** An act the step would offer somebody and does not offer the reader, and the refusal pressing it would meet. */
export interface Withheld {
  readonly act: string;
  readonly refusal: string;
  readonly givesOtherwise?: GivesOtherwise;
}

export interface Beneath {
  readonly runId: string;
  readonly number: number;
  readonly runs: StepRuns;
  readonly at?: {
    readonly runId: string;
    readonly number: number;
    readonly stepId: string;
    readonly name: string;
  };
}

export interface StepRow {
  readonly stepId: string;
  readonly order: number;
  readonly name: string;
  readonly runs: StepRuns;
  readonly beneath?: Beneath;
  readonly producer?: Who;
  readonly reviewer?: Who;
  readonly state: string;
  readonly where?: Where;
  readonly takesFrom: readonly TakesFrom[];
  readonly tries?: {
    readonly current: number;
    readonly declared: number;
    readonly beyond: boolean;
  };
  readonly cost: Cost;
  /** What its newest try gave back; absent until one did. */
  readonly gaveBack?: readonly GivenValue[];
  /** What went into the try waiting, only where the reader may review it. */
  readonly wentIn?: readonly WentIn[];
  readonly next?: NextTry;
  /** Kept as they came, a spelling unknown here included, as `StepAct` says. */
  readonly acts: readonly string[];
  readonly withheld: readonly Withheld[];
}

export type WentIn = ShownOrKept & InputFrom;

export interface RunSteps {
  readonly run: RunHeader;
  /** By version identifier: the run's own, and each a step pins. */
  readonly declarations: ReadonlyMap<string, Declared>;
  readonly gaveBack: GaveBack;
  readonly steps: readonly StepRow[];
  /** Only while the run is running; never under a second, which would read without pause. */
  readonly rereadAfterSeconds?: number;
}

export function readSteps(
  groupId: string,
  runId: string,
  signal: AbortSignal,
): Promise<RunSteps> {
  return atRun(groupId, runId, (run) =>
    get(`${run}/steps`, signal, runStepsFrom),
  );
}

function runStepsFrom(body: unknown): RunSteps | null {
  if (!isUnchecked(body)) {
    return null;
  }
  const run = runHeaderFrom(body.run);
  const declarations = declarationsFrom(body.declarations);
  const gaveBack = gaveBackFrom(body.gaveBack);
  const steps = listOf(body.steps, stepRowFrom);
  const rereadAfterSeconds = rereadFrom(body);
  if (
    run === null ||
    declarations === null ||
    gaveBack === null ||
    steps === null ||
    rereadAfterSeconds === null
  ) {
    return null;
  }
  return {
    run,
    declarations,
    gaveBack,
    steps,
    ...present({ rereadAfterSeconds }),
  };
}

/** How long after a read to read it again, only while the run runs; never under a second, which reads without pause. */
export function rereadFrom(body: Unchecked): number | undefined | null {
  return optional(body, "rereadAfterSeconds", (said) => {
    const seconds = countFrom(said);
    return seconds !== null && seconds >= 1 ? seconds : null;
  });
}

export function runHeaderFrom(body: unknown): RunHeader | null {
  if (!isUnchecked(body) || !isUnchecked(body.progress)) {
    return null;
  }
  const { runId, versionId, state } = body;
  const number = countFrom(body.number);
  const done = countFrom(body.progress.done);
  const of = countFrom(body.progress.of);
  if (
    typeof runId !== "string" ||
    typeof versionId !== "string" ||
    typeof state !== "string" ||
    number === null ||
    done === null ||
    of === null
  ) {
    return null;
  }
  return { runId, number, versionId, state, progress: { done, of } };
}

export function declarationsFrom(
  body: unknown,
): ReadonlyMap<string, Declared> | null {
  if (!isUnchecked(body)) {
    return null;
  }
  const declared = new Map<string, Declared>();
  for (const [versionId, each] of Object.entries(body)) {
    const takes = isUnchecked(each) ? readFieldsFrom(each.takes) : null;
    const gives = isUnchecked(each) ? readFieldsFrom(each.gives) : null;
    if (takes === null || gives === null) {
      return null;
    }
    declared.set(versionId, { takes, gives });
  }
  return declared;
}

function gaveBackFrom(body: unknown): GaveBack | null {
  if (!isUnchecked(body) || typeof body.declares !== "string") {
    return null;
  }
  const standing = optional(body, "standing", (listed) =>
    listOf(listed, shownFieldFrom),
  );
  return standing === null
    ? null
    : { declares: body.declares, ...present({ standing }) };
}

export function stepRowFrom(body: unknown): StepRow | null {
  if (!isUnchecked(body)) {
    return null;
  }
  const { stepId, name, state } = body;
  const order = countFrom(body.order);
  const runs = stepRunsFrom(body.runs);
  const beneath = optional(body, "beneath", beneathFrom);
  const producer = optional(body, "producer", whoFrom);
  const reviewer = optional(body, "reviewer", whoFrom);
  const where = optional(body, "where", whereFrom);
  const takesFrom = listOf(body.takesFrom, takesFromFrom);
  const tries = optional(body, "tries", triesFrom);
  const cost = costFrom(body.cost);
  const gaveBack = optional(body, "gaveBack", (listed) =>
    listOf(listed, givenValueFrom),
  );
  const wentIn = optional(body, "wentIn", (listed) =>
    listOf(listed, wentInFrom),
  );
  const next = optional(body, "next", nextFrom);
  const acts = wordsIn(body.acts);
  const withheld = listOf(body.withheld, withheldFrom);
  const members = { producer, reviewer, where, tries, gaveBack, wentIn, next };
  if (
    typeof stepId !== "string" ||
    typeof name !== "string" ||
    typeof state !== "string" ||
    order === null ||
    runs === null ||
    (where != null &&
      (where.searchQuestion !== undefined ||
        where.searchQuestionWithheld !== undefined) &&
      runs?.kind !== "search") ||
    beneath === null ||
    takesFrom === null ||
    cost === null ||
    acts === null ||
    withheld === null ||
    !allMembersRead(members)
  ) {
    return null;
  }
  return {
    stepId,
    order,
    name,
    runs,
    ...(beneath === undefined ? {} : { beneath }),
    state,
    takesFrom,
    cost,
    acts,
    withheld,
    ...present(members),
  };
}

function beneathFrom(body: unknown): Beneath | null {
  if (!isUnchecked(body) || typeof body.runId !== "string") {
    return null;
  }
  const number = countFrom(body.number);
  const runs = stepRunsFrom(body.runs);
  const at = optional(body, "at", beneathAtFrom);
  return number === null || runs === null || at === null
    ? null
    : { runId: body.runId, number, runs, ...present({ at }) };
}

function beneathAtFrom(body: unknown): NonNullable<Beneath["at"]> | null {
  if (
    !isUnchecked(body) ||
    typeof body.runId !== "string" ||
    typeof body.stepId !== "string" ||
    typeof body.name !== "string"
  ) {
    return null;
  }
  const number = countFrom(body.number);
  return number === null
    ? null
    : { runId: body.runId, number, stepId: body.stepId, name: body.name };
}

export function wentInFrom(body: unknown): WentIn | null {
  if (!isUnchecked(body)) {
    return null;
  }
  const source = inputFrom(body);
  const shown = shownOrKeptFrom(body, [
    "input",
    "sourcePartId",
    "from",
    "sources",
  ]);
  return source === null || shown === null ? null : { ...source, ...shown };
}

export function inputFrom(body: unknown): InputFrom | null {
  if (!isUnchecked(body) || typeof body.input !== "string") {
    return null;
  }
  const sourcePartId = optional(body, "sourcePartId", textFrom);
  if (sourcePartId === null || "from" in body === "sources" in body) {
    return null;
  }
  if ("from" in body) {
    const from = fromFrom(body.from);
    return from === null
      ? null
      : { input: body.input, from, ...present({ sourcePartId }) };
  }
  if (!Array.isArray(body.sources) || body.sources.length === 0) {
    return null;
  }
  const sources = listOf(body.sources, inputSourceFrom);
  return sources === null
    ? null
    : { input: body.input, sources, ...present({ sourcePartId }) };
}

function inputSourceFrom(body: unknown): InputSource | null {
  if (
    !isUnchecked(body) ||
    typeof body.targetPath !== "string" ||
    body.targetPath.split(".").some((part) => part.length === 0)
  ) {
    return null;
  }
  const from = fromFrom(body.from);
  if (from === null) {
    return null;
  }
  switch (from.kind) {
    case "step":
      if (
        from.runId === undefined ||
        from.runNumber === undefined ||
        from.stepId === undefined
      ) {
        return null;
      }
      break;
    case "constant":
      if (from.runId !== undefined || from.stepId !== undefined) {
        return null;
      }
      break;
    case "run_input":
      if (from.stepId !== undefined) {
        return null;
      }
      break;
    default:
      return null;
  }
  return { targetPath: body.targetPath, from };
}

/** A value, or its being withheld: exactly one of the two, where neither or both is no value this side can say. */
function shownFrom(
  body: Unchecked,
  outer: readonly string[] = [],
): Shown | null {
  const withheld = optional(body, "withheld", trueFrom);
  if (withheld === null) {
    return null;
  }
  if (!("value" in body)) {
    return withheld === undefined ||
      Object.keys(body).some(
        (key) => key !== "withheld" && !outer.includes(key),
      )
      ? null
      : { withheld };
  }
  return withheld === undefined && isShownValue(body.value)
    ? { value: body.value }
    : null;
}

/**
 * As `shownFrom`, or a value flagged as given in an earlier shape: text or none, never withheld, and never read as
 * a value of any field.
 */
export function shownOrKeptFrom(
  body: Unchecked,
  outer: readonly string[] = [],
): ShownOrKept | null {
  const earlierShape = optional(body, "earlierShape", trueFrom);
  if (earlierShape === undefined) {
    return shownFrom(body, outer);
  }
  if (earlierShape === null || "withheld" in body) {
    return null;
  }
  const { value } = body;
  return value === null || typeof value === "string" ? { asKept: value } : null;
}

export function whoFrom(body: unknown): Who | null {
  if (!isUnchecked(body) || typeof body.kind !== "string") {
    return null;
  }
  const person = optional(body, "person", personFrom);
  const model = optional(body, "model", textFrom);
  const mode = optional(body, "mode", textFrom);
  if (person === null || model === null || mode === null) {
    return null;
  }
  return { kind: body.kind, ...present({ person, model, mode }) };
}

export function fromFrom(body: unknown): From | null {
  if (!isUnchecked(body) || typeof body.kind !== "string") {
    return null;
  }
  const runId = optional(body, "runId", textFrom);
  const runNumber = optional(body, "runNumber", countFrom);
  const path = optional(body, "path", textFrom);
  const stepId = optional(body, "stepId", textFrom);
  const name = optional(body, "name", textFrom);
  const versionId = optional(body, "versionId", textFrom);
  const codeStep = optional(body, "codeStep", textFrom);
  const searchWay = optional(body, "searchWay", textFrom);
  const members = {
    runId,
    runNumber,
    path,
    stepId,
    name,
    versionId,
    codeStep,
    searchWay,
  };
  if (
    !allMembersRead(members) ||
    (runId === undefined) !== (runNumber === undefined) ||
    (searchWay !== undefined &&
      (body.kind !== "step" ||
        codeStep !== undefined ||
        versionId !== undefined))
  ) {
    return null;
  }
  return {
    kind: body.kind,
    ...present(members),
  };
}

export function costFrom(body: unknown): Cost | null {
  if (!isUnchecked(body) || typeof body.callsAModel !== "boolean") {
    return null;
  }
  if (!body.callsAModel) {
    return {};
  }
  const spend = spendFrom(body);
  return spend === null ? null : { spend };
}

export function flagFrom(said: unknown): boolean | null {
  return typeof said === "boolean" ? said : null;
}

function shownFieldFrom(body: unknown): ShownField | null {
  if (!isUnchecked(body) || typeof body.field !== "string") {
    return null;
  }
  const shown = shownFrom(body, ["field"]);
  return shown === null ? null : { field: body.field, ...shown };
}

function givenValueFrom(body: unknown): GivenValue | null {
  if (
    !isUnchecked(body) ||
    typeof body.field !== "string" ||
    typeof body.now !== "string"
  ) {
    return null;
  }
  const shown = shownOrKeptFrom(body, ["field", "now"]);
  return shown === null ? null : { field: body.field, ...shown, now: body.now };
}

function withheldFrom(body: unknown): Withheld | null {
  if (
    !isUnchecked(body) ||
    typeof body.act !== "string" ||
    typeof body.refusal !== "string"
  ) {
    return null;
  }
  const givesOtherwise =
    body.refusal === "CODE_STEP_GIVES_OTHERWISE"
      ? givesOtherwiseFrom(body)
      : undefined;
  return givesOtherwise === null
    ? null
    : {
        act: body.act,
        refusal: body.refusal,
        ...present({ givesOtherwise }),
      };
}

function givesOtherwiseFrom(body: Unchecked): GivesOtherwise | null {
  const field = textFrom(body.field);
  const readBy = optional(body, "readBy", readByFrom);
  return field === null || readBy === null
    ? null
    : { field, ...present({ readBy }) };
}

export function readByFrom(body: unknown): ReadBy | null {
  if (!isUnchecked(body)) {
    return null;
  }
  const { step, output } = body;
  if (typeof step === "string" && !("output" in body)) {
    return { step };
  }
  return typeof output === "string" && !("step" in body) ? { output } : null;
}

function stepRunsFrom(body: unknown): StepRuns | null {
  if (!isUnchecked(body) || typeof body.kind !== "string") {
    return null;
  }
  const entryId = optional(body, "entryId", textFrom);
  const name = optional(body, "name", textFrom);
  const version = optional(body, "version", countFrom);
  const versionId = optional(body, "versionId", textFrom);
  const codeStep = optional(body, "codeStep", textFrom);
  const searchWay = optional(body, "searchWay", textFrom);
  const tool = optional(body, "tool", textFrom);
  const members = {
    entryId,
    name,
    version,
    versionId,
    codeStep,
    searchWay,
    tool,
  };
  if (!allMembersRead(members)) {
    return null;
  }
  return {
    kind: body.kind,
    ...present(members),
  };
}

function whereFrom(body: unknown): Where | null {
  if (!isUnchecked(body) || typeof body.kind !== "string") {
    return null;
  }
  const reason = optional(body, "reason", textFrom);
  const source = optional(body, "source", heldSourceFrom);
  const failedSourceNoLongerMatches = optional(
    body,
    "failedSourceNoLongerMatches",
    sourceNoLongerMatchesFrom,
  );
  const failedSourceListNotHere = optional(
    body,
    "failedSourceListNotHere",
    sourceNoLongerMatchesFrom,
  );
  const tool = optional(body, "tool", textFrom);
  const searchQuestion = optional(body, "searchQuestion", textFrom);
  const searchQuestionWithheld = optional(
    body,
    "searchQuestionWithheld",
    trueFrom,
  );
  const spentUp = optional(body, "spentUp", trueFrom);
  const model = optional(body, "model", textFrom);
  const mode = optional(body, "mode", textFrom);
  const reviewing = optional(body, "reviewing", trueFrom);
  const review = optional(body, "review", textFrom);
  const number = optional(body, "number", countFrom);
  const values = optional(body, "values", (listed) =>
    listOf(listed, waitingValueFrom),
  );
  const open = optional(body, "open", flagFrom);
  const on = optional(body, "on", textFrom);
  const stopped = optional(body, "stopped", stepStoppedFrom);
  const turnedAway = optional(body, "turnedAway", (listed) =>
    listOf(listed, turnawayFrom),
  );
  const since = optional(body, "since", textFrom);
  const waitsOn = optional(body, "waitsOn", textFrom);
  const failedUnclaimed = optional(
    body,
    "failedUnclaimed",
    failedUnclaimedFrom,
  );
  const failedTooDeep = optional(body, "failedTooDeep", failedTooDeepFrom);
  const members = {
    reason,
    source,
    failedSourceNoLongerMatches,
    failedSourceListNotHere,
    tool,
    searchQuestion,
    searchQuestionWithheld,
    spentUp,
    model,
    mode,
    reviewing,
    review,
    number,
    values,
    open,
    on,
    stopped,
    turnedAway,
    since,
    waitsOn,
    failedUnclaimed,
    failedTooDeep,
  };
  if (
    !allMembersRead(members) ||
    (reason === "source_no_longer_matches") !==
      (failedSourceNoLongerMatches !== undefined) ||
    (reason === "source_list_not_here") !==
      (failedSourceListNotHere !== undefined) ||
    ((failedSourceNoLongerMatches !== undefined ||
      failedSourceListNotHere !== undefined) &&
      body.kind !== "failed") ||
    (source !== undefined &&
      (body.kind !== "held_back" || reason !== "code_step_not_held")) ||
    (reason === "too_deep") !== (failedTooDeep !== undefined) ||
    (reason === "unclaimed_value") !== (failedUnclaimed !== undefined) ||
    (reason === "tool_not_offered") !== (tool !== undefined) ||
    (tool !== undefined &&
      (body.kind !== "failed" ||
        model === undefined ||
        reviewing !== undefined)) ||
    (searchQuestionWithheld !== undefined && searchQuestion !== undefined) ||
    ((searchQuestion !== undefined || searchQuestionWithheld !== undefined) &&
      (body.kind !== "held_back" ||
        (reason !== "too_long" && reason !== "turned_away")))
  ) {
    return null;
  }
  return { kind: body.kind, ...present(members) };
}

function heldSourceFrom(body: unknown): HeldSource | null {
  if (!isUnchecked(body)) {
    return null;
  }
  const runId = textFrom(body.runId);
  const runNumber = countFrom(body.runNumber);
  const stepId = textFrom(body.stepId);
  const name = textFrom(body.name);
  const codeStep = textFrom(body.codeStep);
  return runId !== null &&
    runNumber !== null &&
    runNumber > 0 &&
    stepId !== null &&
    name !== null &&
    codeStep !== null
    ? { runId, runNumber, stepId, name, codeStep }
    : null;
}

function sourceNoLongerMatchesFrom(
  body: unknown,
): SourceNoLongerMatches | null {
  if (!isUnchecked(body)) {
    return null;
  }
  const source = heldSourceFrom(body.source);
  const field = textFrom(body.field);
  return source !== null && field !== null ? { source, field } : null;
}

/** Sent as digits; one past what a number holds exactly is refused rather than read rounded. */
function failedTooDeepFrom(body: unknown): { readonly most: number } | null {
  if (
    !isUnchecked(body) ||
    typeof body.most !== "string" ||
    !DIGITS.test(body.most)
  ) {
    return null;
  }
  const most = Number(body.most);
  return Number.isSafeInteger(most) && most > 0 ? { most } : null;
}

function failedUnclaimedFrom(body: unknown): Where["failedUnclaimed"] | null {
  if (!isUnchecked(body) || Object.keys(body).length !== 1) {
    return null;
  }
  if (Object.hasOwn(body, "term")) {
    return typeof body.term === "string" || body.term === null
      ? { term: body.term }
      : null;
  }
  return body.withheld === true ? { withheld: true } : null;
}

/** Present only as true; one sent as false is no member the server sends. */
export function trueFrom(said: unknown): true | null {
  return said === true ? true : null;
}

/** What the model said and whether it was cut come together; where they are withheld, neither comes. */
export function turnawayFrom(body: unknown): Turnaway | null {
  if (
    !isUnchecked(body) ||
    typeof body.at !== "string" ||
    typeof body.sentAgain !== "boolean"
  ) {
    return null;
  }
  const { at, sentAgain } = body;
  const withheld = optional(body, "withheld", trueFrom);
  if (withheld !== undefined) {
    return withheld === null || "said" in body || "cut" in body
      ? null
      : { at, withheld, sentAgain };
  }
  if (!("said" in body)) {
    return "cut" in body ? null : { at, sentAgain };
  }
  return typeof body.said === "string" && typeof body.cut === "boolean"
    ? { at, said: body.said, cut: body.cut, sentAgain }
    : null;
}

function waitingValueFrom(body: unknown): WaitingValue | null {
  return isUnchecked(body) && typeof body.field === "string"
    ? { field: body.field }
    : null;
}

function stepStoppedFrom(body: unknown): StepStopped | null {
  if (
    !isUnchecked(body) ||
    typeof body.what !== "string" ||
    typeof body.at !== "string"
  ) {
    return null;
  }
  const by = optional(body, "by", personFrom);
  return by === null
    ? null
    : { what: body.what, at: body.at, ...present({ by }) };
}

function takesFromFrom(body: unknown): TakesFrom | null {
  if (!isUnchecked(body) || typeof body.input !== "string") {
    return null;
  }
  const from = fromFrom(body.from);
  const sourcePartId = optional(body, "sourcePartId", textFrom);
  return from === null || sourcePartId === null
    ? null
    : { input: body.input, from, ...present({ sourcePartId }) };
}

function triesFrom(body: unknown): StepRow["tries"] | null {
  if (!isUnchecked(body)) {
    return null;
  }
  const current = countFrom(body.current);
  const declared = countFrom(body.declared);
  const beyond = flagFrom(body.beyond);
  return current === null || declared === null || beyond === null
    ? null
    : { current, declared, beyond };
}

function nextFrom(body: unknown): NextTry | null {
  if (!isUnchecked(body)) {
    return null;
  }
  const number = countFrom(body.number);
  const beyond = flagFrom(body.beyond);
  return number === null || beyond === null ? null : { number, beyond };
}
