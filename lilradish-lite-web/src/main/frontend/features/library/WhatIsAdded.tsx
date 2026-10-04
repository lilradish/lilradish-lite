import InfoOutlinedIcon from "@mui/icons-material/InfoOutlined";
import Box from "@mui/material/Box";
import IconButton from "@mui/material/IconButton";
import Tooltip from "@mui/material/Tooltip";
import Typography from "@mui/material/Typography";
import { useId, useState, type ReactNode } from "react";

import { say } from "../../i18n/app";
import { Disclosure } from "../../lib/action/Disclosure";
import { DEPTH_SX, HEADING_SX } from "../../lib/layout/parts";

const QUIET_SX = { color: "text.secondary" };

/** What a model is asked for on top of a question's instruction, as the server worked it out; only read. */
export function WhatIsAdded({
  added,
}: {
  /** Absent where the declared answer is not whole yet. */
  readonly added: string | undefined;
}) {
  const headingId = useId();
  return added === undefined ? (
    <AddedShell
      headingId={headingId}
      heading={say("question.added")}
      summary={say("question.addedNotYet")}
    />
  ) : (
    <AvailableAdded added={added} headingId={headingId} />
  );
}

function AvailableAdded({
  added,
  headingId,
}: {
  readonly added: string;
  readonly headingId: string;
}) {
  const contentId = useId();
  const [shown, setShown] = useState(false);
  return (
    <AddedShell
      headingId={headingId}
      heading={
        <Disclosure
          open={shown}
          controls={contentId}
          onToggle={() => setShown((was) => !was)}
        >
          {say("question.added")}
        </Disclosure>
      }
      summary={say("question.addedSummary")}
    >
      <div id={contentId}>
        {shown ? (
          <Box
            component="pre"
            dir="auto"
            aria-labelledby={headingId}
            sx={{
              whiteSpace: "pre-wrap",
              overflowWrap: "anywhere",
              typography: "identifier",
            }}
          >
            {added}
          </Box>
        ) : null}
      </div>
    </AddedShell>
  );
}

function AddedShell({
  headingId,
  heading,
  summary,
  children,
}: {
  readonly headingId: string;
  readonly heading: ReactNode;
  readonly summary: string;
  readonly children?: ReactNode;
}) {
  return (
    <Box component="section" aria-labelledby={headingId} sx={DEPTH_SX[2]}>
      <Box sx={{ display: "flex", alignItems: "center", gap: 1 }}>
        <Typography id={headingId} variant="h3" sx={HEADING_SX}>
          {heading}
        </Typography>
        <Tooltip title={say("question.addedHint")}>
          <IconButton size="small" aria-label={say("question.addedHint")}>
            <InfoOutlinedIcon />
          </IconButton>
        </Tooltip>
      </Box>
      <Typography variant="body2" sx={QUIET_SX}>
        {summary}
      </Typography>
      {children}
    </Box>
  );
}
