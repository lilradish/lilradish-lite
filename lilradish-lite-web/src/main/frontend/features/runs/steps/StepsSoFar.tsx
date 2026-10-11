import Box from "@mui/material/Box";
import Button from "@mui/material/Button";
import Typography from "@mui/material/Typography";
import Tooltip from "@mui/material/Tooltip";
import { useId, useLayoutEffect, useRef, useState } from "react";

import type { StepRow } from "../../../api/groups/{groupId}/runs/{runId}/steps";
import { say } from "../../../i18n/app";
import { isolatedInText } from "../../../lib/direction/isolated";
import { PANEL_PART_SX } from "../../../lib/layout/SidePanelHost";
import { stepAnchor } from "../runAddress";
import { StepStateBadge } from "./StepStateBadge";
import { stepTitle } from "./stepTitle";
import { stepStateTone } from "./stepStates";

const LIST_SX = {
  m: 0,
  p: 0,
  listStyle: "none",
  display: "flex",
  flexDirection: "column",
  gap: "10px",
};
const ROW_SX = {
  textAlign: "start",
  display: "grid",
  gridTemplateColumns: "22px minmax(0, 1fr)",
  columnGap: "10px",
  alignItems: "start",
  width: "100%",
  minHeight: 36,
  p: "8px 12px",
  color: "text.primary",
  typography: "body1",
};
const NAME_SX = {
  display: "-webkit-box",
  WebkitLineClamp: 2,
  WebkitBoxOrient: "vertical",
  overflow: "hidden",
};
const NAME_POINTED_SX = {
  color: "ink.primary",
  textDecoration: "underline",
  textUnderlineOffset: "3px",
};
const NUMBER_SX = {
  width: 22,
  height: 22,
  borderRadius: "50%",
  border: 1,
  borderColor: "ink.controlEdge",
  bgcolor: "background.paper",
  color: "ink.secondary",
  typography: "statusPill",
  display: "flex",
  alignItems: "center",
  justifyContent: "center",
  mt: "1px",
};

/** Kept in view by the panel it is drawn in, which scrolls it with the rest. */
export function StepsSoFar({ steps }: { readonly steps: readonly StepRow[] }) {
  const titleId = useId();
  return (
    <Box component="nav" aria-labelledby={titleId} sx={PANEL_PART_SX}>
      <Typography id={titleId} variant="h3">
        {say("step.stepsSoFar")}
      </Typography>
      <Box component="ol" role="list" sx={LIST_SX}>
        {steps.map((step, index) => (
          <li key={step.stepId}>
            <StepItem step={step} number={index + 1} />
          </li>
        ))}
      </Box>
    </Box>
  );
}

function StepItem({
  step,
  number,
}: {
  readonly step: StepRow;
  readonly number: number;
}) {
  const name = stepTitle(step);
  const nameRef = useRef<HTMLSpanElement>(null);
  const [truncated, setTruncated] = useState(false);
  const unreached = step.state === "not_started";
  const tone = stepStateTone(step.state);
  const active =
    step.state === "running" ||
    step.state === "waiting" ||
    step.state === "held_back";
  useLayoutEffect(() => {
    const text = nameRef.current;
    if (text === null) return;
    const measure = () => setTruncated(text.scrollHeight > text.clientHeight);
    measure();
    if (typeof ResizeObserver === "undefined") return;
    const observer = new ResizeObserver(measure);
    observer.observe(text);
    return () => observer.disconnect();
  }, [name]);
  const contents = (
    <>
      <Box
        component="span"
        aria-hidden
        sx={{
          ...NUMBER_SX,
          ...(unreached
            ? { borderColor: "surface.cardEdge", color: "text.secondary" }
            : {}),
        }}
      >
        {number}
      </Box>
      <Box component="span" sx={{ minWidth: 0 }}>
        <Box
          component="span"
          ref={nameRef}
          sx={{
            ...NAME_SX,
            typography: unreached ? "stepListUnstartedName" : "stepListName",
            ...(unreached ? { color: "text.secondary" } : {}),
            ".MuiButtonBase-root:hover &": NAME_POINTED_SX,
          }}
        >
          {isolatedInText(name)}
        </Box>
        <Box
          component="span"
          sx={{ display: "block", typography: "stepListState", mt: "2px" }}
        >
          <StepStateBadge state={step.state} />
        </Box>
      </Box>
    </>
  );
  const row = unreached ? (
    <Box component="span" sx={ROW_SX}>
      {contents}
    </Box>
  ) : (
    <Button
      variant="text"
      color="inherit"
      sx={(given) => {
        const ground =
          !active || tone === undefined
            ? "transparent"
            : "edge" in given.palette.state[tone]
              ? given.palette.surface.subtle
              : given.palette.state[tone].ground;
        return {
          ...ROW_SX,
          bgcolor: ground,
          cursor: "pointer",
          "&:hover": { bgcolor: ground, "--variant-textBg": ground },
        };
      }}
      onFocus={(event) =>
        event.currentTarget.scrollIntoView({ block: "nearest" })
      }
      onClick={() => {
        const message = document.getElementById(stepAnchor(step.stepId));
        message?.scrollIntoView({ block: "nearest" });
        message?.focus({ preventScroll: true });
      }}
    >
      {contents}
    </Button>
  );
  return (
    <Tooltip title={truncated ? name : ""} describeChild>
      {row}
    </Tooltip>
  );
}
