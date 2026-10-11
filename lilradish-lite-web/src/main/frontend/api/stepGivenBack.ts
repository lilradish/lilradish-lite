import {
  isUnchecked,
  listOf,
  optional,
  present,
} from "../lib/request/document";
import { numberedRunFrom, type NumberedRun } from "./runFacts";
import {
  shownFrom,
  shownOrKeptFrom,
  type Shown,
  type ShownOrKept,
} from "./shownValues";

/** `field` is the field's names from the first level, joined by dots. */
type ShownField = Shown & { readonly field: string };

export type GivenValue = ShownOrKept & {
  readonly field: string;
  readonly now: string;
};

export interface GaveBack {
  readonly declares: string;
  readonly standing?: readonly ShownField[];
}

export interface CameOut {
  readonly field: string;
  /** Only where it stands, in the try it stands in. */
  readonly standing?: ShownOrKept;
  readonly fromRun?: NumberedRun;
  readonly now: string;
}

export function gaveBackFrom(body: unknown): GaveBack | null {
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

function shownFieldFrom(body: unknown): ShownField | null {
  if (!isUnchecked(body) || typeof body.field !== "string") {
    return null;
  }
  const shown = shownFrom(body, ["field"]);
  return shown === null ? null : { field: body.field, ...shown };
}

export function givenValueFrom(body: unknown): GivenValue | null {
  if (
    !isUnchecked(body) ||
    typeof body.field !== "string" ||
    typeof body.now !== "string"
  ) {
    return null;
  }
  const shown = shownOrKeptFrom(body, ["field", "now"]);
  return shown === null ? null : { field: body.field, ...shown, now: body.now };
}

export function cameOutFrom(body: unknown): CameOut | null {
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
  const fromRun = optional(body, "fromRun", numberedRunFrom);
  if (standing === null || fromRun === null) {
    return null;
  }
  return {
    field: body.field,
    now: body.now,
    ...present({ standing, fromRun }),
  };
}
