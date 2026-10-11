/**
 * Ids say what a sentence is about rather than where it appears: the same words
 * are shown by several screens, and an id naming one of them is wrong on the
 * rest.
 *
 * The two catalogues split on one mechanical rule: an id is `LIB_EN` when a
 * module under `lib/` says it, and `APP_EN` otherwise. Nothing under `lib/` may
 * reach into the business half, so what it says has to be sayable without one.
 * The prefix names the topic, not the side.
 *
 * Each topic of `APP_EN` is held whole by one file under `en/`.
 */

import { joined } from "./catalogues";
import { CONTENT_PROBLEMS_EN } from "./en/contentProblems";
import { DECLARATION_EN } from "./en/declaration";
import { FILL_EN } from "./en/fill";
import { GROUPS_EN } from "./en/groups";
import { LIB_EN } from "./en/lib";
import { LIBRARY_EN } from "./en/library";
import { MEASUREMENTS_EN } from "./en/measurements";
import { MEMBERS_EN } from "./en/members";
import { PEOPLE_EN } from "./en/people";
import { QUESTION_EN } from "./en/question";
import { REFERENCE_LIST_EN } from "./en/referenceList";
import { REFUSAL_EN } from "./en/refusal";
import { RUN_EN } from "./en/run";
import { SHELL_EN } from "./en/shell";
import { STEP_EN } from "./en/step";
import { WORK_EN } from "./en/work";
import { WORKFLOW_EN } from "./en/workflow";

export { LIB_EN };

export const APP_EN = joined([
  CONTENT_PROBLEMS_EN,
  DECLARATION_EN,
  FILL_EN,
  GROUPS_EN,
  LIBRARY_EN,
  MEASUREMENTS_EN,
  MEMBERS_EN,
  PEOPLE_EN,
  QUESTION_EN,
  REFERENCE_LIST_EN,
  REFUSAL_EN,
  RUN_EN,
  SHELL_EN,
  STEP_EN,
  WORK_EN,
  WORKFLOW_EN,
]);
