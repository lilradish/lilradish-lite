import Link from "@mui/material/Link";
import Table from "@mui/material/Table";
import TableBody from "@mui/material/TableBody";
import TableCell from "@mui/material/TableCell";
import TableHead from "@mui/material/TableHead";
import TableRow from "@mui/material/TableRow";
import Typography from "@mui/material/Typography";

import type { ReadField } from "../../../api/filling";
import type { Try } from "../../../api/groups/{groupId}/runs/{runId}/steps/{stepId}";
import { say } from "../../../i18n/app";
import { RowPanel } from "../../../lib/collection/RowPanel";
import { CostDrawn } from "../spend";
import { endedSaid, reviewSaid, whoSaid } from "./stepStates";
import { TryPanel } from "./TryPanel";

/**
 * One row per try, oldest first, which is the order they happened in, so nothing sorts it; a row picked opens
 * everything of that try beside the list, or over it where there is no room.
 */
export function TriesList({
  tries,
  allowed,
  heldTry,
  field,
  gives,
  labelledBy,
  picked,
  onPick,
}: {
  readonly tries: readonly Try[];
  /** How many tries the step allows, where it makes any. */
  readonly allowed: number | undefined;
  /** The try a hold on its being turned away holds, if one does. */
  readonly heldTry: number | undefined;
  /** The value whose part in each try is drawn; none where the step gives nothing back. */
  readonly field: string | undefined;
  readonly gives: readonly ReadField[] | undefined;
  readonly labelledBy: string;
  /** The number of the try open, if any is. */
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
          <TableCell>{say("step.try")}</TableCell>
          <TableCell>{say("step.producedBy")}</TableCell>
          <TableCell>{say("step.reviewedBy")}</TableCell>
          <TableCell>{say("step.howItEnded")}</TableCell>
          <TableCell>{say("step.cost")}</TableCell>
        </TableRow>
      </TableHead>
      <TableBody>
        {tries.map((each) => (
          <TableRow key={each.number} selected={each.number === picked}>
            <TableCell component="th" scope="row">
              <Link
                component="button"
                type="button"
                aria-current={each.number === picked || undefined}
                onClick={() => onPick(each.number)}
              >
                {trySaid(each, allowed)}
              </Link>
            </TableCell>
            <TableCell>{whoSaid(each.producedBy)}</TableCell>
            <TableCell>{reviewSaid(each.review)}</TableCell>
            <TableCell>{endedSaid(each.ended)}</TableCell>
            <TableCell>
              <CostDrawn cost={each.cost} />
            </TableCell>
          </TableRow>
        ))}
      </TableBody>
    </Table>
  );
  return (
    <RowPanel
      open={shown !== undefined}
      onClose={() => onPick(null)}
      title={shown === undefined ? "" : trySaid(shown, allowed)}
      headingLevel="h4"
      list={table}
      listLabel={say("step.theTries")}
    >
      {shown === undefined ? null : (
        <TryPanel
          aTry={shown}
          field={field}
          gives={gives}
          turnawaysWithHold={shown.number === heldTry}
        />
      )}
    </RowPanel>
  );
}

/** Which one of how many the step allows. */
function trySaid(aTry: Try, allowed: number | undefined): string {
  if (allowed === undefined) {
    return say("step.tryNumber", { number: aTry.number });
  }
  return say(aTry.beyond ? "step.tryBeyond" : "step.tryOf", {
    number: aTry.number,
    allowed,
  });
}
