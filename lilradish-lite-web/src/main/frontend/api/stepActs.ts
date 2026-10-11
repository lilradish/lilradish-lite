import {
  isUnchecked,
  optional,
  present,
  textFrom,
} from "../lib/request/document";
import { readByFrom, type ReadBy } from "./stepTries";

export interface GivesOtherwise {
  readonly field: string;
  readonly readBy?: ReadBy;
}

/** What may be done to a step, spelt and held level as `StepState` is. */
export type StepAct = "answer" | "ask_again" | "review" | "try_sending";

/** Resource reasons only; permission and self-review are conclusions of the current standing. */
export type StateRefusalCode =
  | "RUN_STOPPED"
  | "ENTRY_STOPPED"
  | "REVIEW_NOT_A_PERSONS"
  | "ASK_AGAIN_NOT_OFFERED"
  | "CODE_STEP_GIVES_OTHERWISE";

export interface StateRefusal {
  readonly act: string;
  readonly refusal: string;
}

export function stateRefusalFrom(body: unknown): StateRefusal | null {
  return isUnchecked(body) &&
    typeof body.act === "string" &&
    typeof body.refusal === "string"
    ? { act: body.act, refusal: body.refusal }
    : null;
}

/** The resource's actual code mismatch, independent of who reads it. */
export function givesOtherwiseFrom(body: unknown): GivesOtherwise | null {
  if (!isUnchecked(body)) {
    return null;
  }
  const field = textFrom(body.field);
  const readBy = optional(body, "readBy", readByFrom);
  return field === null || readBy === null
    ? null
    : { field, ...present({ readBy }) };
}
