import { atSegment } from "../../../../../../lib/request/address";
import {
  allMembersRead,
  countFrom,
  flagFrom,
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
import { cameOutFrom, type CameOut } from "../../../../../stepGivenBack";
import { wentInFrom, type WentIn } from "../../../../../stepSources";
import {
  triedValueFrom,
  tryFrom,
  type TriedValue,
  type Try,
} from "../../../../../stepTries";
import { atRun } from "../../{runId}";
import {
  declarationsFrom,
  rereadFrom,
  runHeaderFrom,
  stepRowFrom,
  type Declared,
  type RunHeader,
  type StepRow,
} from "../steps";

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
  /** Only where the resource offers an answer and this build can draw every field it gives. */
  readonly answering?: Answering;
  /** How the question the step pins lays out what it takes, as its version holds it; only where it says. */
  readonly drawing?: string;
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
  const drawing = optional(body, "drawing", (said) =>
    said === "" ? null : textFrom(said),
  );
  const rereadAfterSeconds = rereadFrom(body);
  const members = {
    wentIn,
    wentInFrom: wentInFromSaid,
    cameOut,
    answering,
    drawing,
    rereadAfterSeconds,
  };
  if (
    run === null ||
    declarations === null ||
    step === null ||
    triesMade === null ||
    (triesMade !== null &&
      triesMade.some(
        (tried) =>
          tried.search !== undefined &&
          (step?.runs.kind !== "search" || tried.producedBy.kind !== "model"),
      )) ||
    !allMembersRead(members)
  ) {
    return null;
  }
  return {
    run,
    declarations,
    step,
    triesMade,
    ...present(members),
  };
}

/**
 * Undefined if a field cannot be drawn: an answer must fill every field, so is
 * withheld as a whole. The rest of the page still reads.
 */
function answeringFrom(body: unknown): Answering | undefined | null {
  if (!isUnchecked(body)) {
    return null;
  }
  const number = countFrom(body.number);
  const beyond = flagFrom(body.beyond);
  const instruction = optional(body, "instruction", textFrom);
  const lastRefused = optional(body, "lastRefused", lastRefusedFrom);
  const members = { instruction, lastRefused };
  if (
    number === null ||
    beyond === null ||
    readFieldsFrom(body.gives) === null ||
    !allMembersRead(members) ||
    [
      "searchQuestion",
      "searchQuestionLines",
      "searchQuestionSent",
      "searchQuestionWithheld",
    ].some((key) => Object.hasOwn(body, key))
  ) {
    return null;
  }
  const gives = fillFieldsFrom(body.gives);
  return gives === null
    ? undefined
    : {
        number,
        beyond,
        gives,
        ...present(members),
      };
}

function lastRefusedFrom(body: unknown): Answering["lastRefused"] | null {
  if (!isUnchecked(body)) {
    return null;
  }
  const number = countFrom(body.number);
  const values = listOf(body.values, triedValueFrom);
  return number === null || values === null ? null : { number, values };
}
