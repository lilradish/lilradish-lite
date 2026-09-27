// Here and not in `lib/` because the lookup below reads a *server* refusal code
// out of the *business* catalogue — and `ProblemView` and `Async` are here for
// the same reason: each ends in this call.

import {
  BROKEN_HERE,
  NOT_A_PROBLEM_DOCUMENT,
  NOT_AN_ADDRESS,
  STOPPED_HERE,
  type Problem,
} from "../api/problem";
import { say, type MessageId, type WordedRefusal } from "../i18n/app";
import { APP_EN } from "../i18n/en";
import { ruleSaid, type Rule } from "./standing/actRules";

const ACT_NOT_PERMITTED: WordedRefusal = "ACT_NOT_PERMITTED";

const GROUP_NOT_IN_VIEW: WordedRefusal = "GROUP_NOT_IN_VIEW";

/**
 * What to tell a person a refusal means.
 *
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
export function refusalSentence(problem: Problem, rule?: Rule): string {
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
    return say("failure.unreadable");
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
 * Whether the server answered that the group is none the reader is in, which
 * says the same of their standing: the frame is still drawing a group they
 * have left.
 */
export function refusesTheGroup(problem: Problem): boolean {
  return problem.status !== undefined && problem.code === GROUP_NOT_IN_VIEW;
}

/** Whether a refusal inside a group says the reader's standing has moved under the page. */
export function movesTheReader(problem: Problem): boolean {
  return refusesTheAct(problem) || refusesTheGroup(problem);
}
