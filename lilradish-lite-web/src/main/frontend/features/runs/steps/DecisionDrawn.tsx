import type { TriedValue } from "../../../api/groups/{groupId}/runs/{runId}/steps/{stepId}";
import { say } from "../../../i18n/app";
import { HiddenShownIn } from "../../../lib/text/HiddenShownIn";
import { decisionSaid } from "./stepStates";

export function DecisionDrawn({
  decision,
}: {
  readonly decision: TriedValue["decision"] & object;
}) {
  return decision.outcome === "refused" && decision.why !== undefined ? (
    <HiddenShownIn
      hint
      sentence={(why) => say("step.refusedWith", { why })}
      text={decision.why}
    />
  ) : (
    decisionSaid(decision)
  );
}
