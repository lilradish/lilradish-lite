import type { ReadField } from "../../../api/filling";
import type {
  Declared,
  From,
  StepRow,
  StepRuns,
  TakesFrom,
  WentIn,
} from "../../../api/groups/{groupId}/runs/{runId}/steps";
import { say } from "../../../i18n/app";
import { isolatedInText } from "../../../lib/direction/isolated";
import { spokenName, stepTitle } from "./stepTitle";

/** What a read names of the run's declarations, by the run's own version among them. */
export interface Declaring {
  readonly runId: string;
  readonly declarations: ReadonlyMap<string, Declared>;
  readonly runVersionId: string;
  readonly steps: readonly StepRow[] | undefined;
  readonly step?: StepRow;
}

/**
 * What the version a step runs declares, or the code step it runs; none where it runs neither, or one the read does
 * not name, as a workflow's version and a code step its release does not hold are not.
 */
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

/** One input of a step, and where it comes from. */
export function takenSaid(
  taken: TakesFrom,
  step: StepRow,
  declaring: Declaring,
): string {
  return say("step.takes", {
    input: labelAt(
      step.runs.kind === "search"
        ? sourceFields(taken.from, declaring)
        : declaredFor(step, declaring.declarations)?.takes,
      taken.input,
    ),
    from: fromSaid(taken.from, declaring),
  });
}

function sourceFields(
  from: From,
  declaring: Declaring,
): readonly ReadField[] | undefined {
  return from.kind === "run_input"
    ? declaring.declarations.get(declaring.runVersionId)?.takes
    : from.kind === "step"
      ? declaredBy(from, declaring.declarations)?.gives
      : undefined;
}

/** Search inputs keep their planned container's shape even when its leaves came from other runs. */
export function inputFields(
  each: WentIn,
  takes: readonly ReadField[] | undefined,
  declaring: Declaring,
): readonly ReadField[] | undefined {
  if (declaring.step?.runs.kind !== "search") {
    return takes;
  }
  const planned =
    each.sourcePartId === undefined
      ? undefined
      : declaring.step.takesFrom.find(
          (part) => part.sourcePartId === each.sourcePartId,
        );
  return planned === undefined
    ? undefined
    : sourceFields(planned.from, declaring);
}

/**
 * Where an input comes from, each field by its label where what it is of is declared in the read; by its name where
 * it is not, as what a workflow's version gives back never is.
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
  const step = isolatedInText(spokenName(from.name));
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
