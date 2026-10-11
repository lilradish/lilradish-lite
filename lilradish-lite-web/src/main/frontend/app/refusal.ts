// Here and not in `lib/` because the lookup below reads a *server* refusal code
// out of the *business* catalogue.

import {
  BROKEN_HERE,
  NOT_A_PROBLEM_DOCUMENT,
  NOT_AN_ADDRESS,
  STOPPED_HERE,
  type Problem,
} from "../api/problem";
import { say, type MessageId, type WordedRefusal } from "../i18n/app";
import { isUnchecked } from "../lib/request/document";
import { isolatedInText } from "../lib/direction/isolated";
import { versionLabelText } from "../lib/text/VersionLabel";
import { APP_EN } from "../i18n/en";
import { ruleSaid, type Rule } from "./standing/actRules";
import { joinedInSentence } from "../features/people/wordLists";

const ACT_NOT_PERMITTED: WordedRefusal = "ACT_NOT_PERMITTED";

const GROUP_NOT_IN_VIEW: WordedRefusal = "GROUP_NOT_IN_VIEW";

/**
 * `status` is read before `code`, as `Problem` requires: the two code
 * namespaces are only disjoint by convention, and a code compared across both
 * is a served one answered with a sentence about this side. A problem carrying
 * no status is never a refusal — the request was stopped here, was never sent
 * because its address named nothing, never came back at all, or this side's
 * own code broke on the way.
 *
 * The lookup underneath is closed the same way, but structurally: every id it
 * can reach was written for a served code, because what this side says for
 * itself lives in another namespace. A server is then free to send a code spelt
 * like any of those sentences without naming one.
 *
 * Always a sentence, never a code with its underscores opened up: `NOT_IN_VIEW`
 * as a heading is a machine word wearing a space. The one thing the reader is
 * not told is that this side had no words — that belongs in the disclosure,
 * beside the code it was looking for.
 *
 * `rule` is what the refused request asked, where the caller knows it: a
 * refusal of the act is then said as that rule rather than as any act's.
 */
export function refusalSentence(
  problem: Problem,
  rule?: Rule,
  searchWay?: string,
): string {
  if (rule !== undefined && refusesTheAct(problem)) {
    return ruleSaid(rule);
  }
  if (problem.status === undefined) {
    if (problem.code === STOPPED_HERE) {
      return say("failure.stopped");
    }
    if (problem.code === BROKEN_HERE) {
      return say("failure.broken");
    }
    return problem.code === NOT_AN_ADDRESS
      ? say("failure.notAnAddress")
      : say("failure.unreachable");
  }
  // The one code of this side's read where a server's would be: it is minted
  // with the status of the response whose body could not be read as a document.
  if (problem.code === NOT_A_PROBLEM_DOCUMENT) {
    return say(
      problem.status >= 200 && problem.status < 300
        ? "failure.unreadableAnswer"
        : "failure.unreadable",
    );
  }
  if (
    problem.code === "SEARCH_QUESTION_SHAPE_LOCKED" ||
    problem.code === "SEARCH_WAY_NOT_HELD"
  ) {
    if (
      typeof searchWay === "string" &&
      /^[a-z][a-z0-9_]{0,62}$/.test(searchWay)
    ) {
      return say(
        problem.code === "SEARCH_QUESTION_SHAPE_LOCKED"
          ? "refusal.SEARCH_QUESTION_SHAPE_LOCKED"
          : "refusal.SEARCH_WAY_NOT_HELD",
        { way: isolatedInText(searchWay) },
      );
    }
    return say(
      problem.code === "SEARCH_WAY_NOT_HELD"
        ? "failure.searchWayNotNamed"
        : "failure.unworded",
    );
  }
  if (problem.code === "SEARCH_QUESTION_DOES_NOT_MATCH") {
    const facts = problem.extensions;
    if (
      !isUnchecked(facts) ||
      !Object.hasOwn(facts, "searchWay") ||
      typeof facts.searchWay !== "string" ||
      !/^[a-z][a-z0-9_]{0,62}$/.test(facts.searchWay)
    ) {
      return say("failure.unworded");
    }
    const way = isolatedInText(facts.searchWay);
    if (
      !Object.hasOwn(facts, "questionName") &&
      !Object.hasOwn(facts, "questionNumber")
    ) {
      return say("refusal.SEARCH_QUESTION_DOES_NOT_MATCH", { way });
    }
    if (
      !Object.hasOwn(facts, "questionName") ||
      !Object.hasOwn(facts, "questionNumber") ||
      typeof facts.questionName !== "string" ||
      facts.questionName.length === 0 ||
      facts.questionName.trim() !== facts.questionName ||
      typeof facts.questionNumber !== "number" ||
      !Number.isSafeInteger(facts.questionNumber) ||
      facts.questionNumber < 1
    ) {
      return say("failure.unworded");
    }
    return say("failure.searchQuestionDoesNotMatchNamed", {
      question: versionLabelText({
        entry: facts.questionName,
        number: facts.questionNumber,
        name: null,
      }),
      way,
    });
  }
  if (
    problem.code === "INSTRUCTION_NAMES_UNKNOWN" ||
    problem.code === "INPUT_STILL_MENTIONED"
  ) {
    const names = problem.names;
    if (names === undefined || names.length === 0)
      return say("failure.unworded");
    return say(`refusal.${problem.code}`, {
      count: names.length,
      names: joinedInSentence(names.map((name) => `{{${name}}}`)),
    });
  }
  const id = `refusal.${problem.code}`;
  return Object.hasOwn(APP_EN, id)
    ? say(id as MessageId)
    : say("failure.unworded");
}

/** Whether the server refused the act itself, which says the reader's standing has moved. */
export function refusesTheAct(problem: Problem): boolean {
  return problem.status !== undefined && problem.code === ACT_NOT_PERMITTED;
}

/**
 * The server says the reader is outside this group, so their standing moved:
 * the frame is still drawing a group they have left.
 */
export function refusesTheGroup(problem: Problem): boolean {
  return problem.status !== undefined && problem.code === GROUP_NOT_IN_VIEW;
}

/** Whether a refusal inside a group says the reader's standing has moved under the page. */
export function movesTheReader(problem: Problem): boolean {
  return refusesTheAct(problem) || refusesTheGroup(problem);
}

/** Whether what a refusal says asks for the same write to be sent again, which nothing is left to be once it reads as saved. */
export function asksToSendAgain(problem: Problem): boolean {
  if (problem.status === undefined) return false;
  return (
    problem.code === "BODY_SENT_TOO_SLOWLY" ||
    problem.code === "SERVICE_BUSY" ||
    (problem.code === NOT_A_PROBLEM_DOCUMENT &&
      problem.status >= 200 &&
      problem.status < 300)
  );
}
