import Button from "@mui/material/Button";
import Table from "@mui/material/Table";
import TableBody from "@mui/material/TableBody";
import TableCell from "@mui/material/TableCell";
import TableHead from "@mui/material/TableHead";
import TableRow from "@mui/material/TableRow";
import Typography from "@mui/material/Typography";

import type { Declared } from "../../../api/groups/{groupId}/runs/{runId}/steps";
import type { Declaring } from "./declared";
import type { Try } from "../../../api/stepTries";
import type { GroupStanding } from "../../../api/standing";
import { say } from "../../../i18n/app";
import { RowPanel } from "../../../lib/collection/RowPanel";
import { CostDrawn } from "../spend";
import { whoSaid } from "./stepProducersAndReviewers";
import { tryBeyondVisible, tryResultVisible, reviewVisible } from "./stepTries";
import { TryPanel } from "./TryPanel";

/** One row per try, oldest first, which is the order they happened in, so nothing sorts it. */
export function TriesList({
  tries,
  allowed,
  field,
  declared,
  declaring,
  group,
  labelledBy,
  picked,
  onPick,
}: {
  readonly tries: readonly Try[];
  readonly allowed: number | undefined;
  readonly field: string | undefined;
  readonly declared: Declared | undefined;
  readonly declaring: Declaring;
  readonly group: GroupStanding;
  readonly labelledBy: string;
  readonly picked: number | null;
  readonly onPick: (number: number | null) => void;
}) {
  const shown = tries.find((each) => each.number === picked);
  if (tries.length === 0) {
    return <Typography variant="body2">{say("step.noTries")}</Typography>;
  }
  const table = (
    <Table size="small" aria-labelledby={labelledBy}>
      <TableHead>
        <TableRow>
          <TableCell data-compact="true">{say("step.try")}</TableCell>
          <TableCell data-compact="true">{say("step.producedBy")}</TableCell>
          <TableCell data-compact="true">{say("step.reviewedBy")}</TableCell>
          <TableCell>{say("step.howItEnded")}</TableCell>
          <TableCell data-compact="true">{say("run.usage")}</TableCell>
        </TableRow>
      </TableHead>
      <TableBody>
        {tries.map((each) => (
          <TableRow key={each.number} selected={each.number === picked}>
            <TableCell component="th" scope="row" data-compact="true">
              <Button
                variant="text"
                color="inherit"
                size="small"
                type="button"
                aria-current={each.number === picked || undefined}
                onClick={() => onPick(each.number)}
              >
                {each.beyond && allowed !== undefined
                  ? tryBeyondVisible(each.number, allowed)
                  : trySaid(each, allowed)}
              </Button>
            </TableCell>
            <TableCell data-compact="true">
              {whoSaid(each.producedBy)}
            </TableCell>
            <TableCell data-compact="true">
              {reviewVisible(each.review)}
            </TableCell>
            <TableCell>{tryResultVisible(each, field)}</TableCell>
            <TableCell data-compact="true">
              <CostDrawn cost={each.cost} />
            </TableCell>
          </TableRow>
        ))}
      </TableBody>
    </Table>
  );
  return (
    <RowPanel
      over
      open={shown !== undefined}
      onClose={() => onPick(null)}
      title={shown === undefined ? "" : trySaid(shown, allowed)}
      visibleTitle={
        shown?.beyond === true && allowed !== undefined
          ? tryBeyondVisible(shown.number, allowed)
          : undefined
      }
      headingLevel="h3"
      list={table}
      listLabel={say("step.theTries")}
    >
      {shown === undefined ? null : (
        <TryPanel
          aTry={shown}
          field={field}
          declared={declared}
          declaring={declaring}
          group={group}
        />
      )}
    </RowPanel>
  );
}

function trySaid(aTry: Try, allowed: number | undefined): string {
  if (allowed === undefined) {
    return say("step.tryNumber", { number: aTry.number });
  }
  return say(aTry.beyond ? "step.tryBeyond" : "step.tryOf", {
    number: aTry.number,
    allowed,
  });
}
