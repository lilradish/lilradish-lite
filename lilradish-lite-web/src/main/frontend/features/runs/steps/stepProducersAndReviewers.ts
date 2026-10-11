import type { Who } from "../../../api/stepProducersAndReviewers";
import { say } from "../../../i18n/app";
import { isolatedInText } from "../../../lib/direction/isolated";
import { nameOrNumber } from "../../../lib/text/names";

/** A person by name where one is named; a model by its name and mode. */
export function whoSaid(who: Who): string {
  switch (who.kind) {
    case "person":
      return who.person === undefined
        ? say("step.aPerson")
        : isolatedInText(nameOrNumber(who.person));
    case "code":
      return say("step.code");
    case "model":
      if (who.model === undefined) {
        return say("step.unknown");
      }
      return who.mode === undefined
        ? say("workflow.modelAsItIs", { model: who.model })
        : say("workflow.modelInMode", { model: who.model, mode: who.mode });
    default:
      return say("step.unknown");
  }
}
