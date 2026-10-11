import Link from "@mui/material/Link";
import { Link as RouterLink, useLocation } from "react-router";

import type { ReadField } from "../../../api/filling";
import type { Run } from "../../../api/groups/{groupId}/runs/{runId}";
import type { Declared } from "../../../api/groups/{groupId}/runs/{runId}/steps";
import type { GroupStanding } from "../../../api/standing";
import type { From } from "../../../api/stepSources";
import { say } from "../../../i18n/app";
import { isolatedInText } from "../../../lib/direction/isolated";
import { Field, Fields } from "../../../lib/form/Fields";
import { listQuery, runHref, stepHref, WITHIN_THE_RUN } from "../runAddress";
import { fieldAt, fromSaid, labelAt } from "./declared";

export function StartedWithSources({
  group,
  run,
  takes,
  declarations,
  versionId,
}: {
  readonly group: GroupStanding;
  readonly run: Run;
  readonly takes: readonly ReadField[] | undefined;
  readonly declarations: ReadonlyMap<string, Declared>;
  readonly versionId: string;
}) {
  const { search } = useLocation();
  if (run.startedWithFrom === undefined || run.startedWithFrom.length === 0) {
    return null;
  }
  const declaring = {
    runId: run.runId,
    declarations,
    runVersionId: versionId,
    steps: undefined,
  };
  const sourceLine = (input: string, from: From) => {
    const source =
      from.kind === "run_input" && from.runId !== run.runId
        ? from.path === undefined
          ? say("step.unknown")
          : from.runNumber === undefined
            ? say("step.fromRunInput", {
                field: isolatedInText(from.path),
              })
            : say("step.fromRunInputOf", {
                field: isolatedInText(from.path),
                number: from.runNumber,
              })
        : fromSaid(from, declaring);
    const address =
      from.runId === undefined
        ? undefined
        : from.stepId === undefined
          ? runHref(group, from.runId)
          : stepHref(group, from.runId, from.stepId);
    return (
      <Field key={input} label={input}>
        {address === undefined ? (
          source
        ) : (
          <Link
            component={RouterLink}
            to={`${address}${listQuery(search)}`}
            state={from.stepId === undefined ? undefined : WITHIN_THE_RUN}
          >
            {source}
          </Link>
        )}
      </Field>
    );
  };
  return (
    <Fields>
      {run.startedWithFrom.map((each) =>
        each.sources === undefined ? (
          sourceLine(labelAt(takes, each.input), each.from)
        ) : (
          <Field key={each.input} label={labelAt(takes, each.input)}>
            <>
              <div>{say("step.putTogetherFromThese")}</div>
              <Fields>
                {each.sources.map(({ targetPath, from }) =>
                  sourceLine(
                    labelAt(fieldAt(takes, each.input)?.fields, targetPath),
                    from,
                  ),
                )}
              </Fields>
            </>
          </Field>
        ),
      )}
    </Fields>
  );
}
