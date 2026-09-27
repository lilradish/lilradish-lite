import type { ReactNode } from "react";

import type { StepRow } from "../../../api/groups/{groupId}/runs/{runId}/steps";
import type {
  Ended,
  StepAnswer,
} from "../../../api/groups/{groupId}/runs/{runId}/steps/{stepId}";
import type { Problem } from "../../../api/problem";
import { ProblemView } from "../../../app/ProblemView";
import type { Rule } from "../../../app/standing/actRules";
import { say } from "../../../i18n/app";
import { Notice } from "../../../lib/notice/Notice";
import { whoSaid } from "./stepStates";

const YIELDED: ReadonlySet<string> = new Set([
  "stands",
  "waiting",
  "refused_on_review",
  "refused_for_length",
] satisfies readonly Ended[]);

/**
 * Whether a refusal about try `sentFor` is stale: only a later try owed makes it so, and not the one the refusal's
 * own read showed.
 */
export function staleFor(
  step: StepRow,
  sentFor: number | null,
  readAgainAt: number | null,
): boolean {
  const owed = step.next?.number;
  return owed !== undefined && owed !== sentFor && owed !== readAgainAt;
}

/**
 * Who acted on try `number` first, where the step read again names them: whoever answered it, or else whoever
 * asked for it; null where it names nobody.
 */
export function actedFirstSaid(
  answer: StepAnswer,
  number: number | null,
): string | null {
  const acted = answer.triesMade.find((each) => each.number === number);
  if (acted === undefined) {
    return null;
  }
  const producer = acted.producedBy;
  if (
    YIELDED.has(acted.ended) &&
    producer.kind === "person" &&
    producer.person !== undefined
  ) {
    return say("step.answeredAlready", { name: whoSaid(producer) });
  }
  return acted.askedBy === undefined
    ? null
    : say("step.askedAlready", {
        name: whoSaid({ kind: "person", person: acted.askedBy }),
      });
}

/**
 * What a refusal says: nothing while a refusal `movedOn` takes as the step having moved on is read again, then who
 * acted first where the read names them; otherwise the refusal's own sentence, one of the act said as `rule`.
 */
export function movedOnNotice(
  problem: Problem | null,
  movedOn: (problem: Problem) => boolean,
  rereading: boolean,
  firstSaid: string | null,
  rule: Rule,
): ReactNode {
  if (problem === null) {
    return null;
  }
  if (movedOn(problem)) {
    if (rereading) {
      return null;
    }
    if (firstSaid !== null) {
      return (
        <Notice severity="info" alert>
          {firstSaid}
        </Notice>
      );
    }
  }
  return <ProblemView problem={problem} rule={rule} />;
}
