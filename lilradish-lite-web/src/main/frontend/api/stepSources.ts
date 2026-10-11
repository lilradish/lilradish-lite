import {
  allMembersRead,
  countFrom,
  isUnchecked,
  listOf,
  optional,
  present,
  textFrom,
} from "../lib/request/document";
import { shownOrKeptFrom, type ShownOrKept } from "./shownValues";

export interface From {
  readonly kind: string;
  readonly runId?: string;
  readonly runNumber?: number;
  readonly path?: string;
  readonly stepId?: string;
  readonly name?: string;
  readonly label?: string;
  /** From an earlier step, the version it pins, which is what `RunSteps.declarations` holds it by. */
  readonly versionId?: string;
  /** From an earlier code step, the code step it runs, which is what `RunSteps.declarations` holds it by. */
  readonly codeStep?: string;
  readonly searchWay?: string;
}

export interface TakesFrom {
  readonly input: string;
  readonly from: From;
}

export interface InputSource {
  readonly targetPath: string;
  readonly from: From;
}

export type InputFrom = {
  readonly input: string;
} & (
  | { readonly from: From; readonly sources?: never }
  | { readonly from?: never; readonly sources: readonly InputSource[] }
);

export type WentIn = ShownOrKept & InputFrom;

export function wentInFrom(body: unknown): WentIn | null {
  if (!isUnchecked(body)) {
    return null;
  }
  const source = inputFrom(body);
  const shown = shownOrKeptFrom(body, ["input", "from", "sources"]);
  return source === null || shown === null ? null : { ...source, ...shown };
}

export function inputFrom(body: unknown): InputFrom | null {
  if (!isUnchecked(body) || typeof body.input !== "string") {
    return null;
  }
  if ("sourcePartId" in body || "from" in body === "sources" in body) {
    return null;
  }
  if ("from" in body) {
    const from = fromFrom(body.from);
    return from === null ? null : { input: body.input, from };
  }
  if (!Array.isArray(body.sources) || body.sources.length === 0) {
    return null;
  }
  const sources = listOf(body.sources, inputSourceFrom);
  return sources === null ? null : { input: body.input, sources };
}

function inputSourceFrom(body: unknown): InputSource | null {
  if (
    !isUnchecked(body) ||
    typeof body.targetPath !== "string" ||
    body.targetPath.split(".").some((part) => part.length === 0)
  ) {
    return null;
  }
  const from = fromFrom(body.from);
  if (from === null) {
    return null;
  }
  switch (from.kind) {
    case "step":
      if (
        from.runId === undefined ||
        from.runNumber === undefined ||
        from.stepId === undefined
      ) {
        return null;
      }
      break;
    case "constant":
      if (from.stepId !== undefined) {
        return null;
      }
      break;
    case "run_input":
      if (from.stepId !== undefined) {
        return null;
      }
      break;
    default:
      return null;
  }
  return { targetPath: body.targetPath, from };
}

function fromFrom(body: unknown): From | null {
  if (!isUnchecked(body) || typeof body.kind !== "string") {
    return null;
  }
  const runId = optional(body, "runId", textFrom);
  const runNumber = optional(body, "runNumber", countFrom);
  const path = optional(body, "path", textFrom);
  const stepId = optional(body, "stepId", textFrom);
  const name = optional(body, "name", textFrom);
  const label = optional(body, "label", textFrom);
  const versionId = optional(body, "versionId", textFrom);
  const codeStep = optional(body, "codeStep", textFrom);
  const searchWay = optional(body, "searchWay", textFrom);
  const members = {
    runId,
    runNumber,
    path,
    stepId,
    name,
    label,
    versionId,
    codeStep,
    searchWay,
  };
  if (
    !allMembersRead(members) ||
    (runId === undefined) !== (runNumber === undefined) ||
    (searchWay !== undefined &&
      (body.kind !== "step" || codeStep !== undefined))
  ) {
    return null;
  }
  return {
    kind: body.kind,
    ...present(members),
  };
}

export function takesFromFrom(body: unknown): TakesFrom | null {
  if (!isUnchecked(body) || typeof body.input !== "string") {
    return null;
  }
  const from = fromFrom(body.from);
  return from === null || "sourcePartId" in body
    ? null
    : { input: body.input, from };
}
