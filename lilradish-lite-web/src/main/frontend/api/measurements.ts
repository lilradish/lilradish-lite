import { isUnchecked, listOf } from "../lib/request/document";
import { get } from "../lib/request/http";

const MEASUREMENTS = "/api/measurements";

/** One model in one mode, or run as it is; its name is as its calls named it, held or not. */
export interface ModelMeasured {
  readonly model: string;
  /** Null where it ran as it is. */
  readonly mode: string | null;
  readonly productions: number;
  readonly refusedOnReview: number;
  readonly reviews: number;
  readonly refusing: number;
  readonly didNotFit: number;
}

/** What calls to one model came to, whatever mode each asked for. */
export interface SystemMeasured {
  readonly model: string;
  readonly wentWrong: number;
  readonly neverCameBack: number;
  readonly turnedAway: number;
}

/** Everything ever counted, sent whole and in no order the reader has to keep. */
export interface Measured {
  readonly models: readonly ModelMeasured[];
  readonly system: readonly SystemMeasured[];
}

export function readMeasurements(signal: AbortSignal): Promise<Measured> {
  return get(MEASUREMENTS, signal, measuredFrom);
}

/** Built row by row; a row that cannot be read fails the whole answer rather than leaving a gap. */
function measuredFrom(body: unknown): Measured | null {
  if (!isUnchecked(body)) {
    return null;
  }
  const models = listOf(body.models, modelFrom);
  const system = listOf(body.system, systemFrom);
  return models === null || system === null ? null : { models, system };
}

function modelFrom(row: unknown): ModelMeasured | null {
  if (!isUnchecked(row)) {
    return null;
  }
  const { model, mode } = row;
  const productions = countFrom(row.productions);
  const refusedOnReview = countFrom(row.refusedOnReview);
  const reviews = countFrom(row.reviews);
  const refusing = countFrom(row.refusing);
  const didNotFit = countFrom(row.didNotFit);
  if (
    typeof model !== "string" ||
    (mode !== null && typeof mode !== "string") ||
    productions === null ||
    refusedOnReview === null ||
    reviews === null ||
    refusing === null ||
    didNotFit === null
  ) {
    return null;
  }
  return {
    model,
    mode,
    productions,
    refusedOnReview,
    reviews,
    refusing,
    didNotFit,
  };
}

function systemFrom(row: unknown): SystemMeasured | null {
  if (!isUnchecked(row)) {
    return null;
  }
  const { model } = row;
  const wentWrong = countFrom(row.wentWrong);
  const neverCameBack = countFrom(row.neverCameBack);
  const turnedAway = countFrom(row.turnedAway);
  if (
    typeof model !== "string" ||
    wentWrong === null ||
    neverCameBack === null ||
    turnedAway === null
  ) {
    return null;
  }
  return { model, wentWrong, neverCameBack, turnedAway };
}

// A count past what a number holds exactly arrives already rounded, so it is
// refused rather than shown as a figure nobody counted.
function countFrom(value: unknown): number | null {
  return typeof value === "number" && Number.isSafeInteger(value) && value >= 0
    ? value
    : null;
}
