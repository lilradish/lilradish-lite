import Box from "@mui/material/Box";
import Link from "@mui/material/Link";
import { Link as RouterLink, useLocation } from "react-router";

import type { ReadField } from "../../../api/filling";
import type { Run } from "../../../api/groups/{groupId}/runs/{runId}";
import type {
  Declared,
  From,
} from "../../../api/groups/{groupId}/runs/{runId}/steps";
import { say } from "../../../i18n/app";
import { isolatedInText } from "../../../lib/direction/isolated";
import { listQuery, runHref, stepHref, WITHIN_THE_RUN } from "../runAddress";
import { fieldAt, fromSaid, labelAt } from "./declared";

export function StartedWithSources({
  groupId,
  run,
  takes,
  declarations,
  versionId,
}: {
  readonly groupId: string;
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
          ? runHref(groupId, from.runId)
          : stepHref(groupId, from.runId, from.stepId);
    const words = say("step.takes", { input, from: source });
    return address === undefined ? (
      words
    ) : (
      <Link
        component={RouterLink}
        to={`${address}${listQuery(search)}`}
        state={from.stepId === undefined ? undefined : WITHIN_THE_RUN}
      >
        {words}
      </Link>
    );
  };
  return (
    <Box component="ul">
      {run.startedWithFrom.map((each) => (
        <li key={each.input}>
          {each.sources === undefined ? (
            sourceLine(labelAt(takes, each.input), each.from)
          ) : (
            <>
              {labelAt(takes, each.input)}
              <Box component="div">{say("step.putTogetherFromThese")}</Box>
              <Box component="ul">
                {each.sources.map(({ targetPath, from }) => (
                  <li key={targetPath}>
                    {sourceLine(
                      labelAt(fieldAt(takes, each.input)?.fields, targetPath),
                      from,
                    )}
                  </li>
                ))}
              </Box>
            </>
          )}
        </li>
      ))}
    </Box>
  );
}
