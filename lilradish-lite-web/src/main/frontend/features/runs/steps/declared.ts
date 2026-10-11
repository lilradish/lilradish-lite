import type { ReadField } from "../../../api/filling";
import type {
  Declared,
  StepRow,
  StepRuns,
} from "../../../api/groups/{groupId}/runs/{runId}/steps";
import type { From } from "../../../api/stepSources";
import { say } from "../../../i18n/app";
import { isolatedInText } from "../../../lib/direction/isolated";
import { spokenName, stepTitle } from "./stepTitle";

export interface Declaring {
  readonly runId: string;
  readonly declarations: ReadonlyMap<string, Declared>;
  readonly runVersionId: string;
  readonly steps: readonly StepRow[] | undefined;
  readonly step?: StepRow;
}

/** A read may omit declarations, including when it does not hold a code step's release. */
export function declaredFor(
  step: StepRow,
  declarations: ReadonlyMap<string, Declared>,
): Declared | undefined {
  return step.runs.kind === "route" &&
    step.runs.versionId === undefined &&
    step.runs.codeStep === undefined &&
    step.runs.searchWay === undefined
    ? declarations.get(`route:${step.stepId}`)
    : declaredBy(step.runs, declarations);
}

function declaredBy(
  source: StepRuns | From,
  declarations: ReadonlyMap<string, Declared>,
): Declared | undefined {
  const key =
    source.versionId ??
    (source.searchWay === undefined
      ? source.codeStep
      : `search:${source.searchWay}`);
  if (key !== undefined) {
    return declarations.get(key);
  }
  return source.kind === "step" &&
    "stepId" in source &&
    source.stepId !== undefined
    ? declarations.get(`route:${source.stepId}`)
    : undefined;
}

/** The field at names joined by dots, from the first level down; none where nothing is declared there. */
export function fieldAt(
  fields: readonly ReadField[] | undefined,
  path: string,
): ReadField | undefined {
  let found: ReadField | undefined;
  let level = fields;
  for (const name of path.split(".")) {
    found = level?.find((each) => each.name === name);
    level = found?.fields;
  }
  return found;
}

/**
 * Each level by its label, or by its name where it has none, from the first level down; a name nothing declares
 * is read as a person reads it.
 */
export function labelAt(
  fields: readonly ReadField[] | undefined,
  path: string,
): string {
  const said: string[] = [];
  let level = fields;
  for (const name of path.split(".")) {
    const found = level?.find((each) => each.name === name);
    said.push(isolatedInText(found?.label ?? spokenName(name)));
    level = found?.fields;
  }
  return said.reduce((outer, inner) =>
    say("step.fieldWithin", { outer, inner }),
  );
}

/**
 * Where an input comes from, each field by its label where what it is of is declared in the read; by its name where
 * it is not.
 */
export function fromSaid(from: From, declaring: Declaring): string {
  const { declarations, runVersionId } = declaring;
  if (from.kind === "constant") {
    return say("step.fromConstant");
  }
  if (from.path === undefined) {
    return say("step.unknown");
  }
  if (from.kind === "run_input") {
    if (
      from.runId !== undefined &&
      from.runNumber !== undefined &&
      from.runId !== declaring.runId
    ) {
      return say("step.fromRunInputOf", {
        field: isolatedInText(from.path),
        number: from.runNumber,
      });
    }
    return say("step.fromRunInput", {
      field: labelAt(declarations.get(runVersionId)?.takes, from.path),
    });
  }
  if (from.kind !== "step" || from.name === undefined) {
    return say("step.unknown");
  }
  const field = labelAt(declaredBy(from, declarations)?.gives, from.path);
  const step = isolatedInText(from.label ?? spokenName(from.name));
  return from.runId !== undefined &&
    from.runNumber !== undefined &&
    from.runId !== declaring.runId
    ? say("step.fromStepOfRun", { field, step, number: from.runNumber })
    : say("step.fromStep", { field, step });
}

export function laterStepSaid(
  stepId: string,
  declaring: Declaring,
): string | null {
  if (declaring.steps === undefined) {
    return say("step.erroredForLaterStep");
  }
  const later = declaring.steps.find((each) => each.stepId === stepId);
  return later === undefined ? null : isolatedInText(stepTitle(later));
}

export function outputSaid(path: string, declaring: Declaring): string {
  return labelAt(
    declaring.declarations.get(declaring.runVersionId)?.gives,
    path,
  );
}
