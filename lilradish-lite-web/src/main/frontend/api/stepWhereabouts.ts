import {
  allMembersRead,
  countFrom,
  flagFrom,
  trueFrom,
  isUnchecked,
  listOf,
  optional,
  present,
  textFrom,
} from "../lib/request/document";
import { DIGITS, digitsFrom } from "./runFacts";
import { personFrom, type Person } from "./person";
import { takesFromFrom, type From } from "./stepSources";
import { turnawayFrom, type Turnaway } from "./stepTries";

/** Estimates from the same filled request; each part is rounded on its own. */
export interface Length {
  readonly holdId: string;
  readonly model: string;
  readonly limitUnits: string;
  readonly wholeUnits: string;
  readonly excessUnits: string;
  readonly fixedUnits: string;
  readonly tellsWhatHappened: boolean;
  readonly values: readonly LengthValue[];
}

export interface LengthValue {
  readonly valueId?: string;
  readonly inputs: readonly string[];
  readonly units: string;
  readonly previousUnits?: string;
  readonly from: From;
  readonly canBeMadeAgain: boolean;
  readonly fromSubRun: boolean;
  readonly triesSpent: boolean;
  readonly mayRefuse: boolean;
  readonly used: readonly LengthUsed[];
}

interface LengthUsed {
  readonly runId: string;
  readonly runNumber: number;
  readonly stepId: string;
  readonly name: string;
  readonly label?: string;
  readonly choseBranch: boolean;
}

export function lengthFrom(body: unknown): Length | null {
  if (!isUnchecked(body)) {
    return null;
  }
  const { holdId, model } = body;
  const limitUnits = digitsFrom(body.limitUnits);
  const wholeUnits = digitsFrom(body.wholeUnits);
  const excessUnits = digitsFrom(body.excessUnits);
  const fixedUnits = digitsFrom(body.fixedUnits);
  const tellsWhatHappened = flagFrom(body.tellsWhatHappened);
  const values = listOf(body.values, lengthValueFrom);
  return typeof holdId === "string" &&
    typeof model === "string" &&
    limitUnits !== null &&
    wholeUnits !== null &&
    excessUnits !== null &&
    fixedUnits !== null &&
    tellsWhatHappened !== null &&
    values !== null
    ? {
        holdId,
        model,
        limitUnits,
        wholeUnits,
        excessUnits,
        fixedUnits,
        tellsWhatHappened,
        values,
      }
    : null;
}

function lengthValueFrom(body: unknown): LengthValue | null {
  if (!isUnchecked(body)) {
    return null;
  }
  const valueId = optional(body, "valueId", textFrom);
  const inputs = listOf(body.inputs, textFrom);
  const units = digitsFrom(body.units);
  const previousUnits = optional(body, "previousUnits", digitsFrom);
  const source = takesFromFrom({ input: "", from: body.from });
  const canBeMadeAgain = flagFrom(body.canBeMadeAgain);
  const fromSubRun = flagFrom(body.fromSubRun);
  const triesSpent = flagFrom(body.triesSpent);
  const mayRefuse = flagFrom(body.mayRefuse);
  const used = listOf(body.used, lengthUsedFrom);
  if (
    valueId === null ||
    inputs === null ||
    inputs.length === 0 ||
    inputs.some(
      (path) =>
        path.length === 0 || path.split(".").some((part) => part.length === 0),
    ) ||
    units === null ||
    previousUnits === null ||
    source === null ||
    canBeMadeAgain === null ||
    fromSubRun === null ||
    triesSpent === null ||
    mayRefuse === null ||
    used === null ||
    (mayRefuse &&
      (valueId === undefined ||
        !canBeMadeAgain ||
        fromSubRun ||
        used.length > 0))
  ) {
    return null;
  }
  return {
    ...present({ valueId, previousUnits }),
    inputs,
    units,
    from: source.from,
    canBeMadeAgain,
    fromSubRun,
    triesSpent,
    mayRefuse,
    used,
  };
}

function lengthUsedFrom(body: unknown): LengthUsed | null {
  if (!isUnchecked(body)) {
    return null;
  }
  const { runId, stepId, name } = body;
  const label = optional(body, "label", textFrom);
  const runNumber = countFrom(body.runNumber);
  const choseBranch = flagFrom(body.choseBranch);
  return typeof runId === "string" &&
    typeof stepId === "string" &&
    typeof name === "string" &&
    label !== null &&
    runNumber !== null &&
    choseBranch !== null
    ? { runId, runNumber, stepId, name, ...present({ label }), choseBranch }
    : null;
}

/** Whom a step waits on, spelt and held level as `StepState` is. */
export type WaitsOn = "review_at_gate" | "model" | "answer_step" | "starter";

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
  | "source_list_not_here"
  | "search_question_no_longer_matches";

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

/** Every member but `kind` belongs to some kinds only; each is kept as it came, a kind unknown here included. */
export interface Where {
  readonly kind: string;
  readonly reason?: string;
  readonly source?: HeldSource;
  readonly failedSourceNoLongerMatches?: SourceNoLongerMatches;
  readonly failedSourceListNotHere?: SourceNoLongerMatches;
  readonly tool?: string;
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
  readonly label?: string;
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

export interface NextTry {
  readonly number: number;
  /** Whether it goes past the tries the step declares. */
  readonly beyond: boolean;
}

export function whereFrom(body: unknown): Where | null {
  if (
    !isUnchecked(body) ||
    typeof body.kind !== "string" ||
    ["searchQuestion", "searchQuestionLines", "searchQuestionWithheld"].some(
      (name) => Object.hasOwn(body, name),
    )
  ) {
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
        reviewing !== undefined))
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
  const label = optional(body, "label", textFrom);
  const codeStep = textFrom(body.codeStep);
  return runId !== null &&
    runNumber !== null &&
    runNumber > 0 &&
    stepId !== null &&
    name !== null &&
    label !== null &&
    codeStep !== null
    ? { runId, runNumber, stepId, name, codeStep, ...present({ label }) }
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

export function nextFrom(body: unknown): NextTry | null {
  if (!isUnchecked(body)) {
    return null;
  }
  const number = countFrom(body.number);
  const beyond = flagFrom(body.beyond);
  return number === null || beyond === null ? null : { number, beyond };
}
