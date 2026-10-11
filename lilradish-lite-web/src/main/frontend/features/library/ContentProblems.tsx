import Box from "@mui/material/Box";
import Typography from "@mui/material/Typography";
import { Children, Fragment, useId, type ReactElement } from "react";

import type { DeclaredField, ListVersion } from "../../api/declaration";
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
  RunsKind,
  WorkflowVersion,
} from "../../api/groups/{groupId}/{kind}/{entryId}/versions/{versionId}";
import { say, type MessageId } from "../../i18n/app";
import { intl } from "../../i18n/intl";
import { visible } from "../../i18n/visible";
import { isolatedInText } from "../../lib/direction/isolated";
import { VersionLabel, versionLabelText } from "../../lib/text/VersionLabel";
import { FieldName } from "../../lib/form/FieldName";
import { joinedInSentence } from "../people/wordLists";
import { spokenName } from "../runs/steps/stepTitle";
import ASKING from "./askingLimit.json";
import { sizeSaid } from "../../lib/text/sizeSaid";

// Its markers taken away, so it is named a list outright.
const LIST_SX = { listStyleType: "none", m: 0, mb: 2, p: 0 };

const FIELD_LIST = new Intl.ListFormat(intl.defaultLocale, {
  type: "conjunction",
});

const PARTS = {
  instruction: "contentPart.instruction",
  drawing: "contentPart.drawing",
  takes: "contentPart.takes",
  gives: "contentPart.gives",
  steps: "contentPart.steps",
  helper: "contentPart.helper",
  terms: "contentPart.terms",
  asking: "contentPart.asking",
} as const satisfies Record<ContentPart, MessageId>;

const CASE_OUTPUT_PROBLEMS = {
  case_gives_undeclared: "contentProblem.case_gives_undeclared",
  case_gives_other_kind: "contentProblem.case_gives_other_kind",
  case_gives_past_limit: "contentProblem.case_gives_past_limit",
  case_may_give_nothing: "contentProblem.case_may_give_nothing",
} as const satisfies Partial<Record<ContentProblemCode, MessageId>>;

const PROBLEMS = {
  instruction_missing: "contentProblem.instruction_missing",
  instruction_names_unknown: "contentProblem.instruction_names_unknown",
  drawing_names_unknown: "contentProblem.drawing_names_unknown",
  nothing_given_back: "contentProblem.nothing_given_back",
  name_repeated: "contentProblem.name_repeated",
  longest_missing: "contentProblem.longest_missing",
  file_size_missing: "contentProblem.file_size_missing",
  file_types_too_many: "refusal.FILE_TYPES_TOO_MANY",
  question_takes_file: "contentProblem.question_takes_file",
  list_missing: "contentProblem.list_missing",
  most_missing: "contentProblem.most_missing",
  file_limit_past_deployment: "contentProblem.file_limit_past_deployment",
  file_total_past_deployment: "contentProblem.file_total_past_deployment",
  limit_past_largest: "contentProblem.limit_past_largest",
  no_fields_held: "contentProblem.no_fields_held",
  standing_missing: "contentProblem.standing_missing",
  floor_missing: "contentProblem.floor_missing",
  no_steps: "contentProblem.no_steps",
  step_name_repeated: "contentProblem.step_name_repeated",
  step_label_repeated: "contentProblem.step_label_repeated",
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
  step_file_made_by_model: "contentProblem.step_file_made_by_model",
  step_file_reviewed_by_model: "contentProblem.step_file_reviewed_by_model",
  discriminator_missing: "contentProblem.discriminator_missing",
  discriminator_not_term: "contentProblem.discriminator_not_term",
  no_cases: "contentProblem.no_cases",
  case_target_missing: "contentProblem.case_target_missing",
  case_not_offered: "contentProblem.case_not_offered",
  case_repeated: "contentProblem.case_repeated",
  ...CASE_OUTPUT_PROBLEMS,
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
  search_review_past_largest: "contentProblem.search_review_past_largest",
  search_tool_not_offered: "contentProblem.search_tool_not_offered",
  search_way_not_held: "contentProblem.search_way_not_held",
  search_question_no_longer_matches:
    "contentProblem.search_question_no_longer_matches",
  search_standing_missing: "contentProblem.search_standing_missing",
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
 * Each listed place a submission was refused for, in the server's order, each level found by its key in what was read.
 * A part, a problem or a place the page cannot name is still listed.
 */
export function ContentProblems({
  problems,
  problemTotal,
  content,
}: {
  readonly problemTotal?: number | null;
  readonly problems: readonly ContentProblem[] | null;
  readonly content: ReadContent | null;
}) {
  const labelId = useId();
  if (problems === null) {
    return null;
  }
  return (
    <>
      <Typography id={labelId} variant="subtitle2">
        {say("contentProblems.label")}
      </Typography>
      <Box component="ul" role="list" aria-labelledby={labelId} sx={LIST_SX}>
        {problems.map((problem, index) => {
          const place = placeSaid(problem, content);
          return (
            <li
              key={`${index}:${problem.code}:${problem.bindingId ?? problem.caseId ?? problem.stepId ?? problem.fieldId ?? problem.termId ?? problem.part}`}
              aria-label={place.words}
            >
              {place.node}
            </li>
          );
        })}
      </Box>
      {problemTotal !== null &&
      problemTotal !== undefined &&
      problemTotal > problems.length ? (
        <Typography component="p">
          {say("contentProblems.more", {
            count: problemTotal - problems.length,
          })}
        </Typography>
      ) : null}
    </>
  );
}

/** A problem code this build has no words for is said generally. */
export function problemWords(problem: ContentProblem): MessageId {
  return Object.hasOwn(PROBLEMS, problem.code)
    ? PROBLEMS[problem.code as ContentProblemCode]
    : "contentProblem.unknown";
}

/** What is wrong, in words carrying every figure the problem's words say; wherever a problem is shown, it is said so. */
export function problemSaid(
  problem: ContentProblem,
  kind?: RunsKind,
  searchWay?: string,
  question?: Pick<ListVersion, "name" | "number" | "versionName">,
): string {
  if (problem.code === "instruction_names_unknown") {
    return problem.name === undefined
      ? say("contentProblem.unknown")
      : say("contentProblem.instruction_names_unknown", {
          count: 1,
          names: isolatedInText(`{{${problem.name}}}`),
        });
  }
  if (problem.code === "search_question_no_longer_matches") {
    return searchWay === undefined
      ? say("contentProblem.unknown")
      : question === undefined
        ? say("contentProblem.searchQuestionNoLongerMatchesUnnamed", {
            way: isolatedInText(searchWay),
          })
        : say("contentProblem.search_question_no_longer_matches", {
            way: isolatedInText(searchWay),
            question: namedQuestion(question).words,
          });
  }
  if (problem.code === "step_label_repeated") {
    return problem.name === undefined
      ? say("contentProblem.unknown")
      : say("contentProblem.step_label_repeated", {
          name: isolatedInText(problem.name),
        });
  }
  if (problem.code === "asking_past_largest") {
    return askingPastSaid(
      problem.excess,
      problem.stepId !== undefined && kind !== "search",
    );
  }
  if (problem.code === "code_step_review_past_largest") {
    return codeStepReviewPastSaid(problem.excess);
  }
  if (problem.code === "search_review_past_largest") {
    return searchReviewPastSaid(problem.excess);
  }
  if (problem.code === "file_limit_past_deployment") {
    return problem.fileLimits === undefined
      ? say("contentProblem.unknown")
      : say("contentProblem.file_limit_past_deployment", {
          size: sizeSaid(problem.fileLimits.maximumBytes),
          count: problem.fileLimits.maximumPerField,
        });
  }
  if (problem.code === "file_total_past_deployment") {
    return problem.fileLimits === undefined
      ? say("contentProblem.unknown")
      : say("contentProblem.file_total_past_deployment", {
          total: sizeSaid(problem.fileLimits.maximumTotalBytes),
        });
  }
  if (problem.code === "limit_past_largest") {
    return say("contentProblem.limitPastLargestOf", { most: ASKING.mostSent });
  }
  if (problem.code === "takes_past_largest") {
    return say("contentProblem.takesPastLargestOf", { most: ASKING.mostSent });
  }
  if (problem.code === "search_standing_missing") {
    return problem.fields === undefined
      ? say("contentProblem.unknown")
      : say("contentProblem.search_standing_missing", {
          fields: joinedInSentence(problem.fields.map(spokenName)),
          count: problem.fields.length,
        });
  }
  if (problem.code === "drawing_names_unknown") {
    return problem.name === undefined
      ? say("contentProblem.unknown")
      : say("contentProblem.drawing_names_unknown", {
          name: isolatedInText(problem.name),
        });
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

function searchReviewPastSaid(excess: number | undefined): string {
  if (excess === undefined) return say("contentProblem.unknown");
  return excess > Number.MAX_SAFE_INTEGER
    ? say("contentProblem.searchReviewPastLargestUncounted", {
        max: Number.MAX_SAFE_INTEGER,
        most: ASKING.mostSent,
      })
    : say("contentProblem.search_review_past_largest", {
        excess,
        most: ASKING.mostSent,
      });
}

function isCaseOutputProblem(code: string): boolean {
  return Object.hasOwn(CASE_OUTPUT_PROBLEMS, code);
}

function placeSaid(
  problem: ContentProblem,
  content: ReadContent | null,
): Located {
  const part =
    problem.part === "gives" && content?.kind === "workflow"
      ? say("contentPart.workflowGives")
      : Object.hasOwn(PARTS, problem.part)
        ? say(PARTS[problem.part as ContentPart])
        : { words: problem.part, node: <bdi>{problem.part}</bdi> };
  const runs = content?.steps.find(
    (step) => step.stepId === problem.stepId,
  )?.runs;
  const said = problemLocated(
    problem,
    runs?.kind,
    runs?.kind === "search" ? runs.searchWay : undefined,
    runs?.kind === "search" ? runs.version : undefined,
  );
  if (problem.part === "drawing") {
    return located("contentProblems.atDrawing", { part, problem: said });
  }
  const terms = problem.part === "terms" ? (content?.terms ?? []) : [];
  const at =
    problem.termId === undefined
      ? -1
      : terms.findIndex((held) => held.termId === problem.termId);
  if (at >= 0) {
    return located("contentProblems.atTerm", {
      part,
      position: at + 1,
      term: named(terms[at]!.term),
      problem: said,
    });
  }
  const steps = content?.steps ?? [];
  if (problem.bindingId !== undefined) {
    const where = bindingSaid(problem.bindingId, content);
    if (where !== null) {
      return located("contentProblems.atBinding", {
        part,
        where,
        problem: said,
      });
    }
  }
  if (problem.stepId !== undefined) {
    const step = steps.find((each) => each.stepId === problem.stepId);
    if (step !== undefined) {
      const stepName = named(step.label ?? step.name);
      const routeCase = step.runs?.cases?.find(
        (each) => each.caseId === problem.caseId,
      );
      const caseName =
        routeCase === undefined ? null : caseSaid(routeCase.term);
      if (routeCase !== undefined && isCaseOutputProblem(problem.code)) {
        const down =
          problem.fieldId === undefined
            ? null
            : (namesDownTo(routeCase.gives ?? [], problem.fieldId, true) ??
              namesDownTo(step.runs?.gives ?? [], problem.fieldId, true));
        return down === null
          ? located("contentProblems.atCase", {
              part,
              step: stepName,
              case: caseSaid(routeCase.term),
              problem: said,
            })
          : located("contentProblems.atCaseField", {
              part,
              step: stepName,
              case: caseSaid(routeCase.term),
              field: down.reduce((outer, inner) =>
                located("step.fieldWithin", { outer, inner }),
              ),
              problem: said,
            });
      }
      const takes =
        problem.caseId === undefined ? step.runs?.takes : routeCase?.takes;
      const down =
        problem.fieldId === undefined
          ? null
          : namesDownTo(takes ?? [], problem.fieldId);
      const field = down === null ? undefined : pathOf(down);
      if (field !== undefined) {
        return caseName === null
          ? located("contentProblems.atInput", {
              part,
              step: stepName,
              field,
              problem: said,
            })
          : located("contentProblems.atCaseInput", {
              part,
              step: stepName,
              case: caseName,
              field,
              problem: said,
            });
      }
      return caseName === null
        ? located("contentProblems.atStep", {
            part,
            step: stepName,
            problem: said,
          })
        : located("contentProblems.atCase", {
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
    ? located("contentProblems.at", { part, problem: said })
    : located("contentProblems.atField", {
        part,
        field: pathOf(down),
        problem: said,
      });
}

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

/** None where no read binding has the key. */
function bindingSaid(
  bindingId: string,
  content: ReadContent | null,
): Located | null {
  const steps = content?.steps ?? [];
  const output = content?.outputs.find(
    (binding) => binding.bindingId === bindingId,
  );
  if (output !== undefined) {
    return located("contentProblems.boundTo", {
      target: named(output.target ?? ""),
    });
  }
  for (const step of steps) {
    const stepName = named(step.label ?? step.name);
    if (step.runs?.discriminator?.bindingId === bindingId) {
      return located("contentProblems.chosenBy", { step: stepName });
    }
    const own = step.bindings.find(
      (binding) => binding.bindingId === bindingId,
    );
    if (own !== undefined) {
      return located("contentProblems.stepBoundTo", {
        step: stepName,
        target: named(own.target ?? ""),
      });
    }
    for (const routeCase of step.runs?.cases ?? []) {
      const bound = routeCase.bindings.find(
        (binding) => binding.bindingId === bindingId,
      );
      if (bound !== undefined) {
        return located("contentProblems.caseBoundTo", {
          step: stepName,
          case: caseSaid(routeCase.term),
          target: named(bound.target ?? ""),
        });
      }
    }
  }
  return null;
}

function caseSaid(term: string | undefined): Located {
  return term === undefined
    ? located("contentProblems.fallback")
    : located("contentProblems.caseOn", { term: named(term) });
}

/** Each level's name down to the field keyed so, with its place where a fellow shares the name; null where none is. */
function namesDownTo(
  level: readonly DeclaredField[],
  fieldId: string,
  useLabel = false,
): Located[] | null {
  for (const [index, field] of level.entries()) {
    const below =
      field.fieldId === fieldId
        ? []
        : namesDownTo(field.fields ?? [], fieldId, useLabel);
    if (below !== null) {
      const shared = level.some(
        (fellow) => fellow !== field && fellow.name === field.name,
      );
      const text = useLabel
        ? field.label && field.label !== field.name
          ? `${field.label} · ${field.name}`
          : field.name
        : field.name;
      const fieldName = useLabel
        ? {
            words: isolatedInText(text),
            node: (
              <FieldName
                name={field.name}
                label={field.label}
                context="author"
              />
            ),
          }
        : named(text);
      const name = shared
        ? located("contentProblems.sameNamed", {
            name: fieldName,
            position: index + 1,
          })
        : fieldName;
      return [
        {
          words: shared
            ? isolatedInText(
                say("contentProblems.sameNamed", {
                  name: text,
                  position: index + 1,
                }),
              )
            : name.words,
          node: name.node,
        },
        ...below,
      ];
    }
  }
  return null;
}

interface Located {
  readonly words: string;
  readonly node: ReactElement;
}

function named(words: string): Located {
  return { words: isolatedInText(words), node: <bdi>{words}</bdi> };
}

function namedQuestion(
  question: Pick<ListVersion, "name" | "number" | "versionName">,
): Located {
  const labelled = {
    entry: question.name,
    number: question.number,
    name: question.versionName,
  };
  return {
    words: versionLabelText(labelled),
    node: <VersionLabel {...labelled} />,
  };
}

function hint(words: string): ReactElement {
  return (
    <Box component="span" sx={{ typography: "systemHint" }}>
      {words}
    </Box>
  );
}

function located(
  id: MessageId,
  values?: Readonly<Record<string, string | number | Located>>,
): Located {
  const entries = Object.entries(values ?? {});
  return {
    words: say(
      id,
      Object.fromEntries(
        entries.map(([key, value]) => [
          key,
          typeof value === "object" ? value.words : value,
        ]),
      ),
    ),
    node: (
      <>
        {Children.map(
          visible(
            id,
            Object.fromEntries(
              entries.map(([key, value]) => [
                key,
                typeof value === "object" ? value.node : value,
              ]),
            ),
          ),
          (part) => (typeof part === "string" ? hint(part) : part),
        )}
      </>
    ),
  };
}

function pathOf(names: readonly Located[]): Located {
  return {
    words: names.map((name) => name.words).join("."),
    node: (
      <>
        {names.map((name, index) => (
          <Fragment key={index}>
            {index > 0 ? hint(".") : null}
            {name.node}
          </Fragment>
        ))}
      </>
    ),
  };
}

function problemLocated(
  problem: ContentProblem,
  kind?: RunsKind,
  searchWay?: string,
  question?: Pick<ListVersion, "name" | "number" | "versionName">,
): Located {
  const words = problemSaid(problem, kind, searchWay, question);
  if (
    problem.code === "instruction_names_unknown" &&
    problem.name !== undefined
  ) {
    return {
      words,
      node: located("contentProblem.instruction_names_unknown", {
        count: 1,
        names: named(`{{${problem.name}}}`),
      }).node,
    };
  }
  if (
    problem.code === "search_question_no_longer_matches" &&
    searchWay !== undefined
  ) {
    return {
      words,
      node:
        question === undefined
          ? located("contentProblem.searchQuestionNoLongerMatchesUnnamed", {
              way: named(searchWay),
            }).node
          : located("contentProblem.search_question_no_longer_matches", {
              way: named(searchWay),
              question: namedQuestion(question),
            }).node,
    };
  }
  if (problem.code === "step_label_repeated" && problem.name !== undefined) {
    return {
      words,
      node: located("contentProblem.step_label_repeated", {
        name: named(problem.name),
      }).node,
    };
  }
  if (problem.code === "drawing_names_unknown" && problem.name !== undefined) {
    return {
      words,
      node: located("contentProblem.drawing_names_unknown", {
        name: named(problem.name),
      }).node,
    };
  }
  if (
    problem.code === "search_standing_missing" &&
    problem.fields !== undefined
  ) {
    const fields = problem.fields.map(spokenName);
    const nodes = FIELD_LIST.formatToParts(fields);
    return {
      words,
      node: located("contentProblem.search_standing_missing", {
        count: fields.length,
        fields: {
          words: joinedInSentence(fields),
          node: (
            <>
              {nodes.map((part, index) => (
                <Fragment key={index}>
                  {part.type === "element" ? (
                    <bdi>{part.value}</bdi>
                  ) : (
                    hint(part.value)
                  )}
                </Fragment>
              ))}
            </>
          ),
        },
      }).node,
    };
  }
  return { words, node: hint(words) };
}
