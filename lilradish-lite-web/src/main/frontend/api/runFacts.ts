import { isUnchecked, optional } from "../lib/request/document";

/** Another run of the tree, by the number a reader knows it by. */
export interface NumberedRun {
  readonly runId: string;
  readonly number: number;
}

/**
 * Every count is digits in a string, as the server sends it: one past what a
 * number holds exactly would arrive already rounded.
 */
export interface Spend {
  readonly sent: string;
  readonly cameBack: string;
  /** What was sent and what came back, added; what a ceiling is held against. */
  readonly spent: string;
  /** Whether a call counted came back saying nothing, or is not back yet. */
  readonly cameBackUnknown: boolean;
  /** Whether every call, or only some, was counted by this system rather than a model. */
  readonly measuredHere?: "wholly" | "partly";
}

export const DIGITS = /^(0|[1-9][0-9]*)$/;

export function spendFrom(body: unknown): Spend | null {
  if (!isUnchecked(body)) {
    return null;
  }
  const { sent, cameBack, spent, cameBackUnknown } = body;
  const sentDigits = digitsFrom(sent);
  const cameBackDigits = digitsFrom(cameBack);
  const spentDigits = digitsFrom(spent);
  const measuredHere = optional(
    body,
    "measuredHere",
    (said): Spend["measuredHere"] | null =>
      said === "wholly" || said === "partly" ? said : null,
  );
  return sentDigits !== null &&
    cameBackDigits !== null &&
    spentDigits !== null &&
    typeof cameBackUnknown === "boolean" &&
    measuredHere !== null
    ? {
        sent: sentDigits,
        cameBack: cameBackDigits,
        spent: spentDigits,
        cameBackUnknown,
        ...(measuredHere === undefined ? {} : { measuredHere }),
      }
    : null;
}

export function numberedRunFrom(body: unknown): NumberedRun | null {
  if (!isUnchecked(body)) {
    return null;
  }
  const { runId, number } = body;
  return typeof runId === "string" && Number.isSafeInteger(number)
    ? { runId, number: number as number }
    : null;
}

export function digitsFrom(said: unknown): string | null {
  return typeof said === "string" && DIGITS.test(said) ? said : null;
}
