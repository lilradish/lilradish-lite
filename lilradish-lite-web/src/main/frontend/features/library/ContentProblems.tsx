import Box from "@mui/material/Box";
import Typography from "@mui/material/Typography";
import { useId } from "react";

import type { DeclaredField } from "../../api/declaration";
import type {
  ContentPart,
  ContentProblem,
  ContentProblemCode,
} from "../../api/groups/{groupId}/{kind}/{entryId}/versions";
import type {
  Binding,
  ListTerm,
  QuestionVersion,
  ReferenceListVersion,
  Step,
  WorkflowVersion,
} from "../../api/groups/{groupId}/{kind}/{entryId}/versions/{versionId}";
import { say, type MessageId } from "../../i18n/app";
import { isolatedInText } from "../../lib/direction/isolated";
import ASKING from "./askingLimit.json";

// Its markers taken away, so it is named a list outright.
const LIST_SX = { listStyleType: "none", m: 0, mb: 2, p: 0 };

const PARTS = {
  instruction: "contentPart.instruction",
  takes: "contentPart.takes",
  gives: "contentPart.gives",
  steps: "contentPart.steps",
  helper: "contentPart.helper",
  terms: "contentPart.terms",
  asking: "contentPart.asking",
} as const satisfies Record<ContentPart, MessageId>;

const PROBLEMS = {
  instruction_missing: "contentProblem.instruction_missing",
  nothing_given_back: "contentProblem.nothing_given_back",
  name_repeated: "contentProblem.name_repeated",
  longest_missing: "contentProblem.longest_missing",
  list_missing: "contentProblem.list_missing",
  most_missing: "contentProblem.most_missing",
  limit_past_largest: "contentProblem.limit_past_largest",
  no_fields_held: "contentProblem.no_fields_held",
  standing_missing: "contentProblem.standing_missing",
  floor_missing: "contentProblem.floor_missing",
  no_steps: "contentProblem.no_steps",
  step_name_repeated: "contentProblem.step_name_repeated",
  runs_missing: "contentProblem.runs_missing",
  pin_elsewhere: "contentProblem.pin_elsewhere",
  code_step_not_published: "contentProblem.code_step_not_published",
  code_step_not_declared: "contentProblem.code_step_not_declared",
  code_step_takes_and_gives_nothing:
    "contentProblem.code_step_takes_and_gives_nothing",
  code_step_list_missing: "contentProblem.code_step_list_missing",
  code_step_list_not_yet_in_service:
    "contentProblem.code_step_list_not_yet_in_service",
  code_step_list_retired: "contentProblem.code_step_list_retired",
  producer_missing: "contentProblem.producer_missing",
  producer_not_held: "contentProblem.producer_not_held",
  producer_mode_not_offered: "contentProblem.producer_mode_not_offered",
  tries_missing: "contentProblem.tries_missing",
  reviewer_not_held: "contentProblem.reviewer_not_held",
  reviewer_mode_not_offered: "contentProblem.reviewer_mode_not_offered",
  model_gives_nothing: "contentProblem.model_gives_nothing",
  discriminator_missing: "contentProblem.discriminator_missing",
  discriminator_not_term: "contentProblem.discriminator_not_term",
  no_cases: "contentProblem.no_cases",
  case_target_missing: "contentProblem.case_target_missing",
  case_not_offered: "contentProblem.case_not_offered",
  case_repeated: "contentProblem.case_repeated",
  case_gives_otherwise: "contentProblem.case_gives_otherwise",
  input_unbound: "contentProblem.input_unbound",
  target_unknown: "contentProblem.target_unknown",
  target_bound_twice: "contentProblem.target_bound_twice",
  pointer_into_many: "contentProblem.pointer_into_many",
  source_unknown: "contentProblem.source_unknown",
  source_not_earlier: "contentProblem.source_not_earlier",
  source_does_not_fit: "contentProblem.source_does_not_fit",
  source_may_be_empty: "contentProblem.source_may_be_empty",
  constant_does_not_fit: "contentProblem.constant_does_not_fit",
  constant_too_long: "contentProblem.constant_too_long",
  constant_conceals: "contentProblem.constant_conceals",
  output_unbound: "contentProblem.output_unbound",
  output_not_from_step: "contentProblem.output_not_from_step",
  helper_missing: "contentProblem.helper_missing",
  helper_not_held: "contentProblem.helper_not_held",
  helper_mode_not_offered: "contentProblem.helper_mode_not_offered",
  no_terms: "contentProblem.no_terms",
  term_repeated: "contentProblem.term_repeated",
  asking_past_largest: "contentProblem.asking_past_largest",
  takes_past_largest: "contentProblem.takes_past_largest",
  code_step_review_past_largest: "contentProblem.code_step_review_past_largest",
} as const satisfies Record<ContentProblemCode, MessageId>;

/** What a version holds as the page last read it, which a problem's place is found in by its keys. */
export interface ReadContent {
  readonly kind: "question" | "workflow" | "reference_list";
  /** None for a reference list. */
  readonly takes: readonly DeclaredField[];
  readonly gives: readonly DeclaredField[];
  /** In the order they run; none but for a workflow. */
  readonly steps: readonly Step[];
  readonly outputs: readonly Binding[];
  /** In the order the list gives them; none but for a reference list. */
  readonly terms: readonly ListTerm[];
}

/** What the page names a version's content by, whichever part of it was last written. */
export function contentOf(
  version: QuestionVersion | WorkflowVersion | ReferenceListVersion,
): ReadContent {
  if ("steps" in version) {
    return {
      kind: "workflow",
      takes: version.takes,
      gives: version.gives,
      steps: version.steps,
      outputs: version.outputs,
      terms: [],
    };
  }
  if ("terms" in version) {
    return {
      kind: "reference_list",
      takes: [],
      gives: [],
      steps: [],
      outputs: [],
      terms: version.terms,
    };
  }
  return {
    kind: "question",
    takes: version.takes,
    gives: version.gives,
    steps: [],
    outputs: [],
    terms: [],
  };
}

/**
 * Every place a submission was refused for, in the server's order, each level found by its key in what was read:
 * a field named down to it, a term with its place. A part, a problem or a place the page cannot name is still listed.
 */
export function ContentProblems({
  problems,
  content,
}: {
  readonly problems: readonly ContentProblem[] | null;
  readonly content: ReadContent | null;
}) {
  const labelId = useId();
  if (problems === null) {
    return null;
  }
  return (
    <>
      <Typography id={labelId} variant="subtitle2" component="p">
        {say("contentProblems.label")}
      </Typography>
      <Box component="ul" role="list" aria-labelledby={labelId} sx={LIST_SX}>
        {problems.map((problem, index) => (
          <li
            key={`${index}:${problem.code}:${problem.bindingId ?? problem.caseId ?? problem.stepId ?? problem.fieldId ?? problem.termId ?? problem.part}`}
          >
            {placeSaid(problem, content)}
          </li>
        ))}
      </Box>
    </>
  );
}

/** What is wrong, by the words for it; one this build has none for is said generally. */
export function problemWords(problem: ContentProblem): MessageId {
  return Object.hasOwn(PROBLEMS, problem.code)
    ? PROBLEMS[problem.code as ContentProblemCode]
    : "contentProblem.unknown";
}

/** What is wrong, in words carrying every figure the problem's words say; wherever a problem is shown, it is said so. */
export function problemSaid(problem: ContentProblem): string {
  if (problem.code === "asking_past_largest") {
    return askingPastSaid(problem.excess, problem.stepId !== undefined);
  }
  if (problem.code === "code_step_review_past_largest") {
    return codeStepReviewPastSaid(problem.excess);
  }
  if (problem.code === "limit_past_largest") {
    return say("contentProblem.limitPastLargestOf", { most: ASKING.mostSent });
  }
  if (problem.code === "takes_past_largest") {
    return say("contentProblem.takesPastLargestOf", { most: ASKING.mostSent });
  }
  return problem.code === "constant_too_long" && problem.excess !== undefined
    ? say("contentProblem.constantTooLongBy", { excess: problem.excess })
    : say(problemWords(problem));
}

/**
 * Its words say by how much, so one arriving without that is said as a problem this build cannot word; at a step,
 * it is the step's review that could send too much.
 */
function askingPastSaid(excess: number | undefined, atStep: boolean): string {
  if (excess === undefined) {
    return say("contentProblem.unknown");
  }
  // Past a double's exact integers the count arrived rounded, so only that it passed them is said.
  return excess > Number.MAX_SAFE_INTEGER
    ? say(
        atStep
          ? "contentProblem.reviewPastLargestUncounted"
          : "contentProblem.asking_past_largest_uncounted",
        { max: Number.MAX_SAFE_INTEGER, most: ASKING.mostSent },
      )
    : say(
        atStep
          ? "contentProblem.reviewPastLargest"
          : "contentProblem.asking_past_largest",
        { excess, most: ASKING.mostSent },
      );
}

/** Said at the step, which names it: by how much its code step's review could send too much, as asking's is said. */
function codeStepReviewPastSaid(excess: number | undefined): string {
  if (excess === undefined) {
    return say("contentProblem.unknown");
  }
  return excess > Number.MAX_SAFE_INTEGER
    ? say("contentProblem.codeStepReviewPastLargestUncounted", {
        max: Number.MAX_SAFE_INTEGER,
        most: ASKING.mostSent,
      })
    : say("contentProblem.code_step_review_past_largest", {
        excess,
        most: ASKING.mostSent,
      });
}

function placeSaid(
  problem: ContentProblem,
  content: ReadContent | null,
): string {
  const part =
    problem.part === "gives" && content?.kind === "workflow"
      ? say("contentPart.workflowGives")
      : Object.hasOwn(PARTS, problem.part)
        ? say(PARTS[problem.part as ContentPart])
        : problem.part;
  const said = problemSaid(problem);
  const terms = problem.part === "terms" ? (content?.terms ?? []) : [];
  const at =
    problem.termId === undefined
      ? -1
      : terms.findIndex((held) => held.termId === problem.termId);
  if (at >= 0) {
    return say("contentProblems.atTerm", {
      part,
      position: at + 1,
      term: isolatedInText(terms[at]!.term),
      problem: said,
    });
  }
  const steps = content?.steps ?? [];
  if (problem.bindingId !== undefined) {
    const where = bindingSaid(problem.bindingId, content);
    if (where !== null) {
      return say("contentProblems.atBinding", { part, where, problem: said });
    }
  }
  if (problem.stepId !== undefined) {
    const step = steps.find((each) => each.stepId === problem.stepId);
    if (step !== undefined) {
      const stepName = isolatedInText(step.name);
      const routeCase = step.runs?.cases?.find(
        (each) => each.caseId === problem.caseId,
      );
      const caseName =
        routeCase === undefined ? null : caseSaid(routeCase.term);
      const takes =
        problem.caseId === undefined ? step.runs?.takes : routeCase?.takes;
      const down =
        problem.fieldId === undefined
          ? null
          : namesDownTo(takes ?? [], problem.fieldId);
      const field = down?.map(isolatedInText).join(".");
      if (field !== undefined) {
        return caseName === null
          ? say("contentProblems.atInput", {
              part,
              step: stepName,
              field,
              problem: said,
            })
          : say("contentProblems.atCaseInput", {
              part,
              step: stepName,
              case: caseName,
              field,
              problem: said,
            });
      }
      return caseName === null
        ? say("contentProblems.atStep", { part, step: stepName, problem: said })
        : say("contentProblems.atCase", {
            part,
            step: stepName,
            case: caseName,
            problem: said,
          });
    }
  }
  const down =
    problem.fieldId === undefined || content === null
      ? null
      : namesDownTo(fieldsOfPart(problem.part, content), problem.fieldId);
  return down === null
    ? say("contentProblems.at", { part, problem: said })
    : say("contentProblems.atField", {
        part,
        field: down.map(isolatedInText).join("."),
        problem: said,
      });
}

/** The fields a part holds by their own keys: a half's, or every route's what it gives back. */
function fieldsOfPart(
  part: string,
  content: ReadContent,
): readonly DeclaredField[] {
  if (part === "takes" || part === "gives") {
    return content[part];
  }
  if (part === "steps") {
    return content.steps.flatMap((step) =>
      step.runs?.kind === "route" ? (step.runs.gives ?? []) : [],
    );
  }
  return [];
}

/** Where a binding is, said by what it fills, or by the route choosing by it; none where no read binding has the key. */
function bindingSaid(
  bindingId: string,
  content: ReadContent | null,
): string | null {
  const steps = content?.steps ?? [];
  const output = content?.outputs.find(
    (binding) => binding.bindingId === bindingId,
  );
  if (output !== undefined) {
    return say("contentProblems.boundTo", {
      target: isolatedInText(output.target ?? ""),
    });
  }
  for (const step of steps) {
    const stepName = isolatedInText(step.name);
    if (step.runs?.discriminator?.bindingId === bindingId) {
      return say("contentProblems.chosenBy", { step: stepName });
    }
    const own = step.bindings.find(
      (binding) => binding.bindingId === bindingId,
    );
    if (own !== undefined) {
      return say("contentProblems.stepBoundTo", {
        step: stepName,
        target: isolatedInText(own.target ?? ""),
      });
    }
    for (const routeCase of step.runs?.cases ?? []) {
      const bound = routeCase.bindings.find(
        (binding) => binding.bindingId === bindingId,
      );
      if (bound !== undefined) {
        return say("contentProblems.caseBoundTo", {
          step: stepName,
          case: caseSaid(routeCase.term),
          target: isolatedInText(bound.target ?? ""),
        });
      }
    }
  }
  return null;
}

function caseSaid(term: string | undefined): string {
  return term === undefined
    ? say("contentProblems.fallback")
    : say("contentProblems.caseOn", { term: isolatedInText(term) });
}

/** Each level's name down to the field keyed so, with its place where a fellow shares the name; null where none is. */
function namesDownTo(
  level: readonly DeclaredField[],
  fieldId: string,
): string[] | null {
  for (const [index, field] of level.entries()) {
    const below =
      field.fieldId === fieldId ? [] : namesDownTo(field.fields ?? [], fieldId);
    if (below !== null) {
      const shared = level.some(
        (fellow) => fellow !== field && fellow.name === field.name,
      );
      const name = shared
        ? say("contentProblems.sameNamed", {
            name: field.name,
            position: index + 1,
          })
        : field.name;
      return [name, ...below];
    }
  }
  return null;
}
