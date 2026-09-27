import {
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
  isFillValue,
  readFieldsFrom,
  type FillValue,
  type ReadField,
} from "../../../../filling";
import { personFrom, type Person } from "../../../../person";
import { atRun, spendFrom, type Spend } from "../{runId}";

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
  "entry_stopped" | "too_long" | "turned_away" | "code_step_not_held";

/** Why a step failed, its tries spent or a failure written down, spelt and held level as `StepState` is. */
export type FailureReason =
  | "tries_spent"
  | "uncuttable_length"
  | "unclaimed_value"
  | "model_not_deployed";

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
  | "no_longer_declared";

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
export type Shown = { readonly value: FillValue } | { readonly withheld: true };

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
  /** Where the model turned it away as what may be spent with it is used up. */
  readonly spentUp?: true;
  /** The model a step failed for not being held, and the mode it was named in, where it runs in one. */
  readonly model?: string;
  readonly mode?: string;
  /** Where that model was the one to review it, rather than to produce it. */
  readonly reviewing?: true;
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
  readonly path?: string;
  readonly stepId?: string;
  readonly name?: string;
  /** From an earlier step, the version it pins, which is what `declarations` holds it by. */
  readonly versionId?: string;
  /** From an earlier code step, the code step it runs, which is what `declarations` holds it by. */
  readonly codeStep?: string;
}

export interface TakesFrom {
  readonly input: string;
  readonly from: From;
}

/** No spend where it calls no model: it cost nothing, which is not a count of nought. */
export interface Cost {
  readonly spend?: Spend;
}

interface NextTry {
  readonly number: number;
  /** Whether it goes past the tries the step declares. */
  readonly beyond: boolean;
}

/** An act the step would offer somebody and does not offer the reader, and the refusal pressing it would meet. */
export interface Withheld {
  readonly act: string;
  readonly refusal: string;
}

export interface StepRow {
  readonly stepId: string;
  readonly order: number;
  readonly name: string;
  readonly runs: StepRuns;
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

export type WentIn = ShownOrKept & {
  readonly input: string;
  readonly from: From;
};

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
  if (
    typeof stepId !== "string" ||
    typeof name !== "string" ||
    typeof state !== "string" ||
    order === null ||
    runs === null ||
    takesFrom === null ||
    cost === null ||
    acts === null ||
    withheld === null ||
    [producer, reviewer, where, tries, gaveBack, wentIn, next].includes(null)
  ) {
    return null;
  }
  return {
    stepId,
    order,
    name,
    runs,
    state,
    takesFrom,
    cost,
    acts,
    withheld,
    ...present({
      producer,
      reviewer,
      where,
      tries,
      gaveBack,
      wentIn,
      next,
    }),
  };
}

export function wentInFrom(body: unknown): WentIn | null {
  if (!isUnchecked(body) || typeof body.input !== "string") {
    return null;
  }
  const from = fromFrom(body.from);
  const shown = shownOrKeptFrom(body);
  return from === null || shown === null
    ? null
    : { input: body.input, from, ...shown };
}

/** A value, or its being withheld: exactly one of the two, where neither or both is no value this side can say. */
function shownFrom(body: Unchecked): Shown | null {
  const withheld = optional(body, "withheld", trueFrom);
  if (withheld === null) {
    return null;
  }
  if (!("value" in body)) {
    return withheld === undefined ? null : { withheld };
  }
  return withheld === undefined && isFillValue(body.value)
    ? { value: body.value }
    : null;
}

/**
 * As `shownFrom`, or a value flagged as given in an earlier shape: text or none, never withheld, and never read as
 * a value of any field.
 */
export function shownOrKeptFrom(body: Unchecked): ShownOrKept | null {
  const earlierShape = optional(body, "earlierShape", trueFrom);
  if (earlierShape === undefined) {
    return shownFrom(body);
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
  const path = optional(body, "path", textFrom);
  const stepId = optional(body, "stepId", textFrom);
  const name = optional(body, "name", textFrom);
  const versionId = optional(body, "versionId", textFrom);
  const codeStep = optional(body, "codeStep", textFrom);
  if ([path, stepId, name, versionId, codeStep].includes(null)) {
    return null;
  }
  return {
    kind: body.kind,
    ...present({ path, stepId, name, versionId, codeStep }),
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
  const shown = shownFrom(body);
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
  const shown = shownOrKeptFrom(body);
  return shown === null ? null : { field: body.field, ...shown, now: body.now };
}

function withheldFrom(body: unknown): Withheld | null {
  return isUnchecked(body) &&
    typeof body.act === "string" &&
    typeof body.refusal === "string"
    ? { act: body.act, refusal: body.refusal }
    : null;
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
  if ([entryId, name, version, versionId, codeStep].includes(null)) {
    return null;
  }
  return {
    kind: body.kind,
    ...present({ entryId, name, version, versionId, codeStep }),
  };
}

function whereFrom(body: unknown): Where | null {
  if (!isUnchecked(body) || typeof body.kind !== "string") {
    return null;
  }
  const reason = optional(body, "reason", textFrom);
  const spentUp = optional(body, "spentUp", trueFrom);
  const model = optional(body, "model", textFrom);
  const mode = optional(body, "mode", textFrom);
  const reviewing = optional(body, "reviewing", trueFrom);
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
  const members = {
    reason,
    spentUp,
    model,
    mode,
    reviewing,
    number,
    values,
    open,
    on,
    stopped,
    turnedAway,
    since,
    waitsOn,
  };
  if (Object.values(members).includes(null)) {
    return null;
  }
  return { kind: body.kind, ...present(members) };
}

/** Present only as true; one sent as false is no member the server sends. */
function trueFrom(said: unknown): true | null {
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
  return from === null ? null : { input: body.input, from };
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
