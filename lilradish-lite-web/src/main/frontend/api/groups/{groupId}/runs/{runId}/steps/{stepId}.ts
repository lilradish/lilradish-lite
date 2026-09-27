import { atSegment } from "../../../../../../lib/request/address";
import {
  countFrom,
  isUnchecked,
  listOf,
  optional,
  present,
  textFrom,
} from "../../../../../../lib/request/document";
import { get } from "../../../../../../lib/request/http";
import {
  fillFieldsFrom,
  readFieldsFrom,
  type FillField,
} from "../../../../../filling";
import { personFrom, type Person } from "../../../../../person";
import { atRun } from "../../{runId}";
import {
  costFrom,
  declarationsFrom,
  flagFrom,
  rereadFrom,
  runHeaderFrom,
  shownOrKeptFrom,
  stepRowFrom,
  turnawayFrom,
  wentInFrom,
  whoFrom,
  type Cost,
  type Declared,
  type RunHeader,
  type ShownOrKept,
  type StepRow,
  type Turnaway,
  type WentIn,
  type Who,
} from "../steps";

/** Where a value stands within its try, spelt and held level as `RunState` is; none only of what came out. */
export type Standing =
  "stands" | "waiting_on_review" | "refused" | "refused_for_length" | "none";

/** How a try ended, spelt and held level as `Standing` is. */
export type Ended =
  | "open"
  | "stands"
  | "refused_on_review"
  | "refused_for_length"
  | "waiting"
  | "did_not_fit"
  | "errored"
  | "nothing_came_back";

/** Why a model's answer did not fit, spelt and held level as `Standing` is. */
export type DidNotFitReason =
  | "not_the_shape"
  | "field_missing"
  | "field_unknown"
  | "nothing_given"
  | "not_its_kind"
  | "too_long"
  | "too_many"
  | "not_a_term"
  | "unkeepable"
  | "too_long_to_keep"
  | "confidence_missing"
  | "confidence_unasked"
  | "confidence_not_a_percent"
  | "undecided"
  | "words_missing"
  | "words_too_long"
  | "words_unkeepable"
  | "cut_off"
  | "not_kept_as_it_came";

/** What went wrong in code, or what a model's call said went wrong, which is withheld where models are not read. */
export type WentWrong =
  | { readonly detail: string; readonly cut: boolean }
  | { readonly withheld: true };

export interface CameOut {
  readonly field: string;
  /** Only where it stands, in the try it stands in. */
  readonly standing?: ShownOrKept;
  readonly now: string;
}

export type TriedValue = ShownOrKept & {
  readonly field: string;
  readonly now: string;
  /** How sure a model was of it, from 0 to 100; only where it was asked, and never where what it said is withheld. */
  readonly confidence?: number;
  readonly decision?: Decision;
};

/** `withheld` says words were refused with that the reader may not read, where a model said them. */
interface Decision {
  readonly outcome: string;
  readonly why?: string;
  readonly withheld?: true;
}

export interface Try {
  readonly number: number;
  readonly beyond: boolean;
  /** Absent where the system asked. */
  readonly askedBy?: Person;
  readonly producedBy: Who;
  readonly why?: string;
  readonly values: readonly TriedValue[];
  readonly review: {
    readonly asked: boolean;
    readonly by?: Who;
    readonly wentWrong?: boolean;
  };
  readonly ended: string;
  /** Why a model's answer did not fit, kept as it came, a spelling unknown here included. */
  readonly didNotFit?: string;
  readonly wentWrong?: WentWrong;
  /** What code gave back that did not fit, as it came back. */
  readonly returned?: string;
  /** Each time a call to produce it was turned away, oldest first. */
  readonly turnedAway?: readonly Turnaway[];
  readonly cost: Cost;
}

/** What answering the step here puts to the reader: the try an answer fills, and a control to each field it gives. */
export interface Answering {
  readonly number: number;
  readonly beyond: boolean;
  /** What the question tells whoever answers it; a code step tells nothing. */
  readonly instruction?: string;
  /** For a code step, as its release declares them now. */
  readonly gives: readonly FillField[];
  /** The try refused before it, each value with the words it was refused with. */
  readonly lastRefused?: {
    readonly number: number;
    readonly values: readonly TriedValue[];
  };
}

export interface StepAnswer {
  readonly run: RunHeader;
  readonly declarations: ReadonlyMap<string, Declared>;
  readonly step: StepRow;
  readonly wentIn?: readonly WentIn[];
  readonly wentInFrom?: string;
  readonly cameOut?: readonly CameOut[];
  /** Oldest first. */
  readonly triesMade: readonly Try[];
  /** Only where the reader may answer it, and this build can draw every field it gives. */
  readonly answering?: Answering;
  /** Only while the run is running; never under a second, which would read without pause. */
  readonly rereadAfterSeconds?: number;
}

export function readStep(
  groupId: string,
  runId: string,
  stepId: string,
  signal: AbortSignal,
): Promise<StepAnswer> {
  return atRun(groupId, runId, (run) =>
    atSegment(`${run}/steps`, stepId, (step) =>
      get(step, signal, stepAnswerFrom),
    ),
  );
}

export function stepAnswerFrom(body: unknown): StepAnswer | null {
  if (!isUnchecked(body)) {
    return null;
  }
  const run = runHeaderFrom(body.run);
  const declarations = declarationsFrom(body.declarations);
  const step = stepRowFrom(body.step);
  const wentIn = optional(body, "wentIn", (listed) =>
    listOf(listed, wentInFrom),
  );
  const wentInFromSaid = optional(body, "wentInFrom", textFrom);
  const cameOut = optional(body, "cameOut", (listed) =>
    listOf(listed, cameOutFrom),
  );
  const triesMade = listOf(body.triesMade, tryFrom);
  const answering = optional(body, "answering", answeringFrom);
  const rereadAfterSeconds = rereadFrom(body);
  if (
    run === null ||
    declarations === null ||
    step === null ||
    triesMade === null ||
    [wentIn, wentInFromSaid, cameOut, answering, rereadAfterSeconds].includes(
      null,
    )
  ) {
    return null;
  }
  return {
    run,
    declarations,
    step,
    triesMade,
    ...present({
      wentIn,
      wentInFrom: wentInFromSaid,
      cameOut,
      answering,
      rereadAfterSeconds,
    }),
  };
}

function cameOutFrom(body: unknown): CameOut | null {
  if (
    !isUnchecked(body) ||
    typeof body.field !== "string" ||
    typeof body.now !== "string"
  ) {
    return null;
  }
  const standing = optional(body, "standing", (said) =>
    isUnchecked(said) ? shownOrKeptFrom(said) : null,
  );
  if (standing === null) {
    return null;
  }
  return { field: body.field, now: body.now, ...present({ standing }) };
}

function tryFrom(body: unknown): Try | null {
  if (!isUnchecked(body) || !isUnchecked(body.review)) {
    return null;
  }
  const { ended } = body;
  const number = countFrom(body.number);
  const beyond = flagFrom(body.beyond);
  const askedBy = optional(body, "askedBy", personFrom);
  const producedBy = whoFrom(body.producedBy);
  const why = optional(body, "why", textFrom);
  const values = listOf(body.values, triedValueFrom);
  const asked = flagFrom(body.review.asked);
  const by = optional(body.review, "by", whoFrom);
  const reviewWentWrong = optional(body.review, "wentWrong", flagFrom);
  const didNotFit = optional(body, "didNotFit", textFrom);
  const wentWrong = optional(body, "wentWrong", wentWrongFrom);
  const returned = optional(body, "returned", textFrom);
  const turnedAway = optional(body, "turnedAway", (listed) =>
    listOf(listed, turnawayFrom),
  );
  const cost = costFrom(body.cost);
  if (
    typeof ended !== "string" ||
    number === null ||
    beyond === null ||
    producedBy === null ||
    values === null ||
    asked === null ||
    cost === null ||
    [
      askedBy,
      why,
      by,
      reviewWentWrong,
      didNotFit,
      wentWrong,
      returned,
      turnedAway,
    ].includes(null)
  ) {
    return null;
  }
  return {
    number,
    beyond,
    producedBy,
    values,
    review: { asked, ...present({ by, wentWrong: reviewWentWrong }) },
    ended,
    cost,
    ...present({ askedBy, why, didNotFit, wentWrong, returned, turnedAway }),
  };
}

function triedValueFrom(body: unknown): TriedValue | null {
  if (
    !isUnchecked(body) ||
    typeof body.field !== "string" ||
    typeof body.now !== "string"
  ) {
    return null;
  }
  const shown = shownOrKeptFrom(body);
  const confidence = optional(body, "confidence", confidenceFrom);
  const decision = optional(body, "decision", decisionFrom);
  if (shown === null || confidence === null || decision === null) {
    return null;
  }
  return {
    field: body.field,
    now: body.now,
    ...shown,
    ...present({ confidence, decision }),
  };
}

/** A whole number from 0 to 100, the one scale a model says how sure it is on. */
function confidenceFrom(said: unknown): number | null {
  const percent = countFrom(said);
  return percent !== null && percent >= 0 && percent <= 100 ? percent : null;
}

function decisionFrom(body: unknown): Decision | null {
  if (!isUnchecked(body) || typeof body.outcome !== "string") {
    return null;
  }
  const why = optional(body, "why", textFrom);
  const withheld = optional(body, "withheld", (said): true | null =>
    said === true ? true : null,
  );
  if (why === null || withheld === null) {
    return null;
  }
  return { outcome: body.outcome, ...present({ why, withheld }) };
}

/** Said with whether it was cut, or withheld with neither: exactly one of the two. */
function wentWrongFrom(body: unknown): WentWrong | null {
  if (!isUnchecked(body)) {
    return null;
  }
  if ("withheld" in body) {
    return body.withheld === true && !("detail" in body) && !("cut" in body)
      ? { withheld: true }
      : null;
  }
  return typeof body.detail === "string" && typeof body.cut === "boolean"
    ? { detail: body.detail, cut: body.cut }
    : null;
}

/**
 * None where a field it gives is one this build cannot draw, as an answer here fills every one of them, so the
 * answer is not offered; the rest of the page is read all the same.
 */
function answeringFrom(body: unknown): Answering | undefined | null {
  if (!isUnchecked(body)) {
    return null;
  }
  const number = countFrom(body.number);
  const beyond = flagFrom(body.beyond);
  const instruction = optional(body, "instruction", textFrom);
  const lastRefused = optional(body, "lastRefused", lastRefusedFrom);
  if (
    number === null ||
    beyond === null ||
    readFieldsFrom(body.gives) === null ||
    [instruction, lastRefused].includes(null)
  ) {
    return null;
  }
  const gives = fillFieldsFrom(body.gives);
  return gives === null
    ? undefined
    : { number, beyond, gives, ...present({ instruction, lastRefused }) };
}

function lastRefusedFrom(body: unknown): Answering["lastRefused"] | null {
  if (!isUnchecked(body)) {
    return null;
  }
  const number = countFrom(body.number);
  const values = listOf(body.values, triedValueFrom);
  return number === null || values === null ? null : { number, values };
}
