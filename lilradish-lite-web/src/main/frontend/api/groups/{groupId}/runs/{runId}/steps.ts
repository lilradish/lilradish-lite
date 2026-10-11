import {
  allMembersRead,
  countFrom,
  flagFrom,
  isUnchecked,
  listOf,
  optional,
  present,
  textFrom,
  wordsIn,
  type Unchecked,
} from "../../../../../lib/request/document";
import { get } from "../../../../../lib/request/http";
import { readFieldsFrom, type ReadField } from "../../../../filling";
import {
  givesOtherwiseFrom,
  stateRefusalFrom,
  type GivesOtherwise,
  type StateRefusal,
} from "../../../../stepActs";
import {
  gaveBackFrom,
  givenValueFrom,
  type GaveBack,
  type GivenValue,
} from "../../../../stepGivenBack";
import { whoFrom, type Who } from "../../../../stepProducersAndReviewers";
import {
  takesFromFrom,
  wentInFrom,
  type TakesFrom,
  type WentIn,
} from "../../../../stepSources";
import { costFrom, type Cost } from "../../../../stepTries";
import {
  lengthFrom,
  nextFrom,
  whereFrom,
  type Length,
  type NextTry,
  type Where,
} from "../../../../stepWhereabouts";
import { atRun } from "../{runId}";
import { isVersionName } from "../../{kind}";

export interface RunHeader {
  readonly runId: string;
  readonly number: number;
  readonly versionId: string;
  readonly state: string;
  readonly stateActs: ReadonlySet<string> | null;
  readonly progress: { readonly done: number; readonly of: number };
}

/** Only to be read, so a field of a kind this build does not know is said so rather than refusing the read. */
export interface Declared {
  readonly takes: readonly ReadField[];
  readonly gives: readonly ReadField[];
}

export interface StepRuns {
  readonly versionName: string | null;
  readonly kind: string;
  readonly entryId?: string;
  readonly name?: string;
  readonly version?: number;
  readonly versionId?: string;
  readonly codeStep?: string;
  readonly searchWay?: string;
  readonly tool?: string;
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
    readonly label?: string;
  };
}

export interface StepRow {
  readonly stepId: string;
  readonly order: number;
  readonly name: string;
  readonly label?: string;
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
  /** What went into the try waiting on a person's review, before permission and self checks. */
  readonly wentIn?: readonly WentIn[];
  readonly next?: NextTry;
  /** Kept as they came, a spelling unknown here included, as `StepAct` says. */
  readonly stateActs: ReadonlySet<string> | null;
  readonly producedByUserId: string | null;
  readonly producedByKind: string | null;
  readonly stateRefusals: readonly StateRefusal[] | null;
  readonly givesOtherwise?: GivesOtherwise;
  readonly letGoThen?: ReadonlySet<string>;
  readonly waitsOnStarter?: string;
  readonly length?: Length;
}

export interface RunSteps {
  readonly run: RunHeader;
  /** Keys are version UUIDs, code-step names, `route:<stepId>` or `search:<way>`, as the source requires. */
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
  const acts = body.stateActs === null ? null : wordsIn(body.stateActs);
  if (
    typeof runId !== "string" ||
    typeof versionId !== "string" ||
    typeof state !== "string" ||
    number === null ||
    done === null ||
    of === null ||
    (body.stateActs !== null && acts === null)
  ) {
    return null;
  }
  return {
    runId,
    number,
    versionId,
    state,
    stateActs: acts === null ? null : new Set(acts),
    progress: { done, of },
  };
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

export function stepRowFrom(body: unknown): StepRow | null {
  if (!isUnchecked(body)) {
    return null;
  }
  const { stepId, name, state } = body;
  const label = optional(body, "label", textFrom);
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
  const length = optional(body, "length", lengthFrom);
  const acts = body.stateActs === null ? null : wordsIn(body.stateActs);
  const producedByUserId =
    body.producedByUserId === null ? null : textFrom(body.producedByUserId);
  const producedByKind =
    body.producedByKind === null ? null : textFrom(body.producedByKind);
  const stateRefusals =
    body.stateRefusals === null
      ? null
      : listOf(body.stateRefusals, stateRefusalFrom);
  const givesOtherwise = optional(body, "givesOtherwise", givesOtherwiseFrom);
  const letGoThen = optional(body, "letGoThen", (listed) => {
    const words = wordsIn(listed);
    return words === null ? null : new Set(words);
  });
  const waitsOnStarter = optional(body, "waitsOnStarter", textFrom);
  const members = {
    label,
    producer,
    reviewer,
    where,
    tries,
    gaveBack,
    wentIn,
    next,
    length,
    givesOtherwise,
    letGoThen,
    waitsOnStarter,
  };
  if (
    typeof stepId !== "string" ||
    typeof name !== "string" ||
    typeof state !== "string" ||
    order === null ||
    runs === null ||
    beneath === null ||
    takesFrom === null ||
    cost === null ||
    (body.stateActs !== null && acts === null) ||
    (body.producedByUserId !== null && producedByUserId === null) ||
    (body.producedByKind !== null && producedByKind === null) ||
    (body.stateRefusals !== null && stateRefusals === null) ||
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
    stateActs: acts === null ? null : new Set(acts),
    producedByUserId,
    producedByKind,
    stateRefusals,
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
  const label = optional(body, "label", textFrom);
  return number === null || label === null
    ? null
    : {
        runId: body.runId,
        number,
        stepId: body.stepId,
        name: body.name,
        ...present({ label }),
      };
}

function stepRunsFrom(body: unknown): StepRuns | null {
  if (
    !isUnchecked(body) ||
    typeof body.kind !== "string" ||
    !isVersionName(body.versionName)
  ) {
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
    versionName: body.versionName,
    ...present(members),
  };
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
