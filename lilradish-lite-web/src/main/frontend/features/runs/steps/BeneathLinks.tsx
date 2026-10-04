import Link from "@mui/material/Link";
import { Link as RouterLink, useLocation } from "react-router";

import type { Beneath } from "../../../api/groups/{groupId}/runs/{runId}/steps";
import { say } from "../../../i18n/app";
import { isolatedInText } from "../../../lib/direction/isolated";
import { listQuery, runHref, stepHref, WITHIN_THE_RUN } from "../runAddress";

export function BeneathLinks({
  groupId,
  beneath,
}: {
  readonly groupId: string;
  readonly beneath: Beneath;
}) {
  const { search } = useLocation();
  return (
    <>
      {say("step.startedRunBeneath", { number: beneath.number })}{" "}
      <Link
        component={RouterLink}
        to={`${runHref(groupId, beneath.runId)}${listQuery(search)}`}
      >
        {say("step.openRunBeneath", { number: beneath.number })}
      </Link>
      {beneath.runs.name === undefined ||
      beneath.runs.version === undefined ? null : (
        <>
          {" "}
          {say("run.workflowVersion", {
            name: isolatedInText(beneath.runs.name),
            version: beneath.runs.version,
          })}
        </>
      )}
      {beneath.at === undefined ? null : (
        <>
          {" "}
          <Link
            component={RouterLink}
            to={`${stepHref(groupId, beneath.at.runId, beneath.at.stepId)}${listQuery(search)}`}
            state={WITHIN_THE_RUN}
          >
            {isolatedInText(beneath.at.name)}
          </Link>
        </>
      )}
    </>
  );
}
