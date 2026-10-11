import Link from "@mui/material/Link";
import { VersionLabel } from "../../../lib/text/VersionLabel";
import { Link as RouterLink, useLocation } from "react-router";

import type { Beneath } from "../../../api/groups/{groupId}/runs/{runId}/steps";
import type { GroupStanding } from "../../../api/standing";
import { say } from "../../../i18n/app";
import { visible } from "../../../i18n/visible";
import { isolatedInText } from "../../../lib/direction/isolated";
import { listQuery, runHref, stepHref, WITHIN_THE_RUN } from "../runAddress";

export function BeneathLinks({
  group,
  beneath,
}: {
  readonly group: GroupStanding;
  readonly beneath: Beneath;
}) {
  const { search } = useLocation();
  return (
    <>
      {say("step.startedRunBeneath", { number: beneath.number })}{" "}
      <Link
        component={RouterLink}
        to={`${runHref(group, beneath.runId)}${listQuery(search)}`}
      >
        {say("step.openRunBeneath", { number: beneath.number })}
      </Link>
      {beneath.runs.name === undefined ||
      beneath.runs.version === undefined ? null : (
        <>
          {" "}
          {visible("run.workflowVersion", {
            label: (
              <VersionLabel
                entry={beneath.runs.name}
                number={beneath.runs.version}
                name={beneath.runs.versionName}
              />
            ),
          })}
        </>
      )}
      {beneath.at === undefined ? null : (
        <>
          {" "}
          <Link
            component={RouterLink}
            to={`${stepHref(group, beneath.at.runId, beneath.at.stepId)}${listQuery(search)}`}
            state={WITHIN_THE_RUN}
          >
            {isolatedInText(beneath.at.label ?? beneath.at.name)}
          </Link>
        </>
      )}
    </>
  );
}
