import type { ModelMeasured, SystemMeasured } from "../../api/measurements";
import { say, type MessageId } from "../../i18n/app";
import { intl, readersIntl } from "../../i18n/intl";
import type { Ordering } from "../../lib/collection/ordering";

/** A figure's own name, which is the column it is shown in and sorted by. */
export type ModelColumn = keyof ModelMeasured;

export type SystemColumn = keyof SystemMeasured;

/** On the one column no row of either table leaves empty. */
export const BY_MODEL: Ordering<"model"> = {
  column: "model",
  descending: false,
};

type CountOf<T> = {
  [K in keyof T]: T[K] extends number ? K : never;
}[keyof T];

/** One row per model and mode; every column sorts, and a row opens nothing. */
export function modelColumns() {
  return [
    modelColumn<ModelMeasured>(),
    {
      label: say("measurements.mode"),
      cell: (row: ModelMeasured) =>
        row.mode === null ? "" : modeSaid(row.mode),
      sortKey: "mode",
    },
    countColumn<ModelMeasured>("measurements.productions", "productions"),
    countColumn<ModelMeasured>(
      "measurements.refusedOnReview",
      "refusedOnReview",
    ),
    countColumn<ModelMeasured>("measurements.reviews", "reviews"),
    countColumn<ModelMeasured>("measurements.refusing", "refusing"),
    countColumn<ModelMeasured>("measurements.didNotFit", "didNotFit"),
  ] as const;
}

/** One row per model a call has been sent to; every column sorts, and a row opens nothing. */
export function systemColumns() {
  return [
    modelColumn<SystemMeasured>(),
    countColumn<SystemMeasured>("measurements.wentWrong", "wentWrong"),
    countColumn<SystemMeasured>("measurements.neverCameBack", "neverCameBack"),
    countColumn<SystemMeasured>("measurements.turnedAway", "turnedAway"),
  ] as const;
}

/** Encoded rather than joined: a name may hold any separator, and no mode is not an empty one. */
export function modelAndMode(row: ModelMeasured): string {
  return JSON.stringify([row.model, row.mode]);
}

export function modelOf(row: SystemMeasured): string {
  return row.model;
}

/**
 * A name by UTF-16 code unit, which is what `<` compares and differs from code
 * point order past U+FFFF; no mode before any, and rows alike keep their order.
 */
export function inOrder<K extends string, T extends Record<K, Sortable>>(
  rows: readonly T[],
  order: Ordering<K>,
): T[] {
  const direction = order.descending ? -1 : 1;
  return [...rows].sort(
    (left, right) =>
      direction * compared(left[order.column], right[order.column]),
  );
}

type Sortable = string | number | null;

function compared(left: Sortable, right: Sortable): number {
  if (left === right) {
    return 0;
  }
  if (left === null || right === null) {
    return left === null ? -1 : 1;
  }
  return left < right ? -1 : 1;
}

function modelColumn<T extends { readonly model: string }>() {
  return {
    label: say("measurements.model"),
    cell: (row: T) => row.model,
    sortKey: "model",
  } as const;
}

/** The deployment's word where the catalogue has none for it; asked first, since formatting a missing id reports it. */
function modeSaid(mode: string): string {
  const id = `mode.${mode}`;
  return Object.hasOwn(intl.messages, id) ? intl.formatMessage({ id }) : mode;
}

/** Grouped in the reader's own way. */
function countColumn<T>(label: MessageId, key: CountOf<T>) {
  return {
    label: say(label),
    cell: (row: T) => readersIntl.formatNumber(row[key] as number),
    sortKey: key,
  } as const;
}
