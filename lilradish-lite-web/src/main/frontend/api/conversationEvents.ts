import {
  isUnchecked,
  listOf,
  optional,
  severalFrom,
  textFrom,
} from "../lib/request/document";
import { isShownValue, type ShownValues } from "./filling";
import { personFrom, type Person } from "./person";
import { digitsFrom, numberedRunFrom, type NumberedRun } from "./runFacts";

interface DatedEvent {
  readonly id: string;
  readonly at: string;
}

type EventValue = { readonly field: string } & (
  { readonly value: ShownValues[string] } | { readonly asKept: string | null }
);

export type ReviewDecision =
  | { readonly field: string; readonly outcome: "assured" }
  | {
      readonly field: string;
      readonly outcome: "refused";
      readonly why: string;
      readonly whyLines: "several";
      readonly lengthUnits?: string;
      readonly excessUnits?: string;
    };

export interface HeldStep {
  readonly run: NumberedRun;
  readonly stepId: string;
  readonly stepName: string;
  readonly label?: string;
}

interface OnStep extends DatedEvent {
  readonly stepId: string;
  readonly run: NumberedRun;
}

interface ByPerson extends DatedEvent {
  readonly by: Person;
  readonly run: NumberedRun;
}

interface CeilingEvent extends ByPerson {
  readonly kind:
    | "ceiling_changed"
    | "ceiling_asked"
    | "ceiling_approved"
    | "ceiling_refused"
    | "ceiling_withdrawn";
  readonly from?: string;
  readonly to?: string;
}

export type ConversationEvent =
  | (OnStep & { readonly kind: "step_reached" })
  | (OnStep & {
      readonly kind: "answered_by_hand";
      readonly by: Person;
      readonly number: number;
      readonly values: readonly EventValue[];
      readonly why?: string;
    })
  | (OnStep & {
      readonly kind: "reviewed";
      readonly by: Person;
      readonly number: number;
      readonly decisions: readonly ReviewDecision[];
    })
  | (OnStep & {
      readonly kind: "refused_for_length";
      readonly held: HeldStep;
      readonly by: Person;
      readonly number: number;
      readonly decisions: readonly ReviewDecision[];
    })
  | CeilingEvent
  | (DatedEvent & {
      readonly kind: "ceiling_superseded";
      readonly run: NumberedRun;
      readonly from?: string;
      readonly to?: string;
    })
  | (ByPerson & { readonly kind: "stopped" | "opened_again" })
  | (DatedEvent & {
      readonly kind: "stopped_by_ceiling";
      readonly run: NumberedRun;
      readonly ceilings: readonly NumberedRun[];
    });

export function conversationEventFrom(body: unknown): ConversationEvent | null {
  if (
    !isUnchecked(body) ||
    typeof body.id !== "string" ||
    typeof body.kind !== "string" ||
    typeof body.at !== "string"
  ) {
    return null;
  }
  const stepId = optional(body, "stepId", textFrom);
  const held = optional(body, "held", heldFrom);
  const by = optional(body, "by", personFrom);
  const number = optional(body, "number", (value) =>
    Number.isSafeInteger(value) && (value as number) > 0
      ? (value as number)
      : null,
  );
  const values = optional(body, "values", (listed) =>
    listOf(listed, eventValueFrom),
  );
  const decisions = optional(body, "decisions", (listed) =>
    listOf(listed, decisionFrom),
  );
  const run = optional(body, "run", numberedRunFrom);
  const from = optional(body, "from", digitsFrom);
  const to = optional(body, "to", digitsFrom);
  const ceilings = optional(body, "ceilings", (listed) =>
    listOf(listed, numberedRunFrom),
  );
  const why = optional(body, "why", textFrom);
  if (
    stepId === null ||
    held === null ||
    by === null ||
    number === null ||
    values === null ||
    decisions === null ||
    run === null ||
    from === null ||
    to === null ||
    ceilings === null ||
    why === null
  ) {
    return null;
  }
  const dated = { id: body.id, at: body.at };
  switch (body.kind) {
    case "step_reached":
      return stepId !== undefined && run !== undefined
        ? { ...dated, kind: body.kind, stepId, run }
        : null;
    case "answered_by_hand":
      return stepId !== undefined &&
        by !== undefined &&
        number !== undefined &&
        values !== undefined &&
        run !== undefined
        ? {
            ...dated,
            kind: body.kind,
            stepId,
            by,
            number,
            values,
            run,
            ...(why === undefined ? {} : { why }),
          }
        : null;
    case "reviewed":
      return stepId !== undefined &&
        by !== undefined &&
        number !== undefined &&
        decisions !== undefined &&
        decisions.length > 0 &&
        decisions.every(
          (decision) =>
            decision.outcome === "assured" ||
            decision.lengthUnits === undefined,
        ) &&
        run !== undefined
        ? { ...dated, kind: body.kind, stepId, by, number, decisions, run }
        : null;
    case "refused_for_length":
      return stepId !== undefined &&
        held !== undefined &&
        by !== undefined &&
        number !== undefined &&
        decisions !== undefined &&
        decisions.length > 0 &&
        decisions.every(
          (decision) =>
            decision.outcome === "refused" &&
            decision.lengthUnits !== undefined,
        ) &&
        run !== undefined
        ? {
            ...dated,
            kind: body.kind,
            stepId,
            held,
            by,
            number,
            decisions,
            run,
          }
        : null;
    case "ceiling_changed":
    case "ceiling_asked":
    case "ceiling_approved":
    case "ceiling_refused":
    case "ceiling_withdrawn":
      return run !== undefined && by !== undefined
        ? {
            ...dated,
            kind: body.kind,
            run,
            by,
            ...(from === undefined ? {} : { from }),
            ...(to === undefined ? {} : { to }),
          }
        : null;
    case "ceiling_superseded":
      return run !== undefined && by === undefined
        ? {
            ...dated,
            kind: body.kind,
            run,
            ...(from === undefined ? {} : { from }),
            ...(to === undefined ? {} : { to }),
          }
        : null;
    case "stopped":
    case "opened_again":
      return by !== undefined && run !== undefined
        ? { ...dated, kind: body.kind, by, run }
        : null;
    case "stopped_by_ceiling":
      return run !== undefined &&
        by === undefined &&
        ceilings !== undefined &&
        ceilings.length > 0
        ? { ...dated, kind: body.kind, run, ceilings }
        : null;
    default:
      return null;
  }
}

export function heldFrom(body: unknown): HeldStep | null {
  if (
    !isUnchecked(body) ||
    typeof body.stepId !== "string" ||
    typeof body.stepName !== "string"
  ) {
    return null;
  }
  const run = numberedRunFrom(body.run);
  const label = optional(body, "label", textFrom);
  return run === null || label === null
    ? null
    : {
        run,
        stepId: body.stepId,
        stepName: body.stepName,
        ...(label === undefined ? {} : { label }),
      };
}

function eventValueFrom(body: unknown): EventValue | null {
  if (!isUnchecked(body) || typeof body.field !== "string") {
    return null;
  }
  if (body.earlierShape === true && typeof body.value === "string") {
    return { field: body.field, asKept: body.value };
  }
  return isShownValue(body.value)
    ? { field: body.field, value: body.value }
    : null;
}

function decisionFrom(body: unknown): ReviewDecision | null {
  if (
    !isUnchecked(body) ||
    typeof body.field !== "string" ||
    (body.outcome !== "assured" && body.outcome !== "refused")
  ) {
    return null;
  }
  const why = optional(body, "why", textFrom);
  const whyLines = optional(body, "whyLines", severalFrom);
  const lengthUnits = optional(body, "lengthUnits", digitsFrom);
  const excessUnits = optional(body, "excessUnits", digitsFrom);
  if (
    why === null ||
    whyLines === null ||
    lengthUnits === null ||
    excessUnits === null ||
    (lengthUnits !== undefined) !== (excessUnits !== undefined) ||
    excessUnits === "0"
  ) {
    return null;
  }
  if (body.outcome === "assured") {
    return why === undefined &&
      whyLines === undefined &&
      lengthUnits === undefined
      ? { field: body.field, outcome: "assured" }
      : null;
  }
  return why !== undefined && why.trim().length > 0 && whyLines !== undefined
    ? {
        field: body.field,
        outcome: "refused",
        why,
        whyLines,
        ...(lengthUnits === undefined || excessUnits === undefined
          ? {}
          : { lengthUnits, excessUnits }),
      }
    : null;
}
