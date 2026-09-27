import type { ReadField } from "../../../api/filling";
import type {
  Declared,
  From,
  StepRow,
  StepRuns,
  TakesFrom,
} from "../../../api/groups/{groupId}/runs/{runId}/steps";
import { say } from "../../../i18n/app";
import { isolatedInText } from "../../../lib/direction/isolated";
import { spokenName } from "./stepTitle";

/** What a read names of the run's declarations, by the run's own version among them. */
export interface Declaring {
  readonly declarations: ReadonlyMap<string, Declared>;
  readonly runVersionId: string;
}

/**
 * What the version a step runs declares, or the code step it runs; none where it runs neither, or one the read does
 * not name, as a workflow's version and a code step its release does not hold are not.
 */
export function declaredFor(
  step: StepRow,
  declarations: ReadonlyMap<string, Declared>,
): Declared | undefined {
  return declaredBy(step.runs, declarations);
}

/** A version's identifier and a code step's name never spell alike, so the read holds both by one key. */
function declaredBy(
  source: StepRuns | From,
  declarations: ReadonlyMap<string, Declared>,
): Declared | undefined {
  const key = source.versionId ?? source.codeStep;
  return key === undefined ? undefined : declarations.get(key);
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
    said.push(isolatedInText(found?.label ?? found?.name ?? spokenName(name)));
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
      declaredFor(step, declaring.declarations)?.takes,
      taken.input,
    ),
    from: fromSaid(taken.from, declaring),
  });
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
    return say("step.fromRunInput", {
      field: labelAt(declarations.get(runVersionId)?.takes, from.path),
    });
  }
  if (from.kind !== "step" || from.name === undefined) {
    return say("step.unknown");
  }
  return say("step.fromStep", {
    field: labelAt(declaredBy(from, declarations)?.gives, from.path),
    step: isolatedInText(spokenName(from.name)),
  });
}
