import Box from "@mui/material/Box";
import Typography from "@mui/material/Typography";

import { writtenSinceRead } from "../../api/groups/{groupId}/{kind}/{entryId}/versions/{versionId}";
import type { Problem } from "../../api/problem";
import { ProblemView } from "../../app/ProblemView";
import type { GroupRule } from "../../app/standing/actRules";
import { say } from "../../i18n/app";
import { Press } from "../../lib/action/Press";

const AFRESH_SX = { mb: 2 };

const HINT_SX = { mb: 1 };

/**
 * A write to a draft's content refused, said where it was asked. Where somebody wrote the draft since the page
 * read it, what is typed stays, and reading it afresh is offered: only pressing that lets go of it.
 */
export function WriteRefused({
  problem,
  rule,
  waiting,
  onReadAfresh,
}: {
  readonly problem: Problem;
  /** What writing asks, which a refusal of it is said as. */
  readonly rule: GroupRule;
  /** Whether another write of the host's is out, whose answer drawing the parts anew would lose. */
  readonly waiting: boolean;
  readonly onReadAfresh: () => void;
}) {
  return (
    <>
      <ProblemView problem={problem} rule={{ inGroup: rule }} />
      {writtenSinceRead(problem) ? (
        <Box sx={AFRESH_SX}>
          <Typography variant="body2" sx={HINT_SX}>
            {say("draft.readAfreshHint")}
          </Typography>
          <Press unavailable={waiting} onPress={onReadAfresh}>
            {say("draft.readAfresh")}
          </Press>
        </Box>
      ) : null}
    </>
  );
}
