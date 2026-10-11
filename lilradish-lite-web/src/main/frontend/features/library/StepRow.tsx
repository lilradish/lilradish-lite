import ArrowDownwardIcon from "@mui/icons-material/ArrowDownward";
import ArrowUpwardIcon from "@mui/icons-material/ArrowUpward";
import Box from "@mui/material/Box";
import { buttonClasses } from "@mui/material/Button";
import Link from "@mui/material/Link";
import IconButton from "@mui/material/IconButton";
import Tooltip from "@mui/material/Tooltip";
import TableCell from "@mui/material/TableCell";
import TableRow from "@mui/material/TableRow";
import { memo, type ReactNode } from "react";
import { Link as RouterLink } from "react-router";

import type { OfferedCodeStep } from "../../api/groups/{groupId}/{kind}/{entryId}/versions/{versionId}";
import { say } from "../../i18n/app";
import { visible } from "../../i18n/visible";
import { readersIntl } from "../../i18n/intl";
import { heldOr } from "../../lib/action/Press";
import { FIGURE_SETTING } from "../../lib/collection/SortableTable";
import { isolatedInText } from "../../lib/direction/isolated";
import { TOKENS } from "../../lib/theme/tokens";
import { modelSaid } from "./ModelControls";
import { PinNote, versionShown } from "./PinNote";
import { producerSaid } from "./StepEditor";
import { StepKindTag } from "./StepKindTag";
import { givenBy, type Named, type StepDraft } from "./workflowDrafts";

const RUNS_SX = {
  display: "flex",
  flexDirection: "column",
  alignItems: "flex-start",
  gap: 0.5,
};

export function calledOf(step: StepDraft, position: number): string {
  if (step.label !== "") return isolatedInText(step.label);
  return step.name === ""
    ? say("workflow.unnamed", { position: position + 1 })
    : isolatedInText(step.name);
}

export function calledVisibleOf(
  step: StepDraft,
  position: number,
  hintColor: "text.secondary" | "inherit" = "text.secondary",
) {
  if (step.label !== "") return <bdi>{step.label}</bdi>;
  return step.name === "" ? (
    visible("workflow.unnamedVisible", {
      name: (
        <bdi>{say("workflow.defaultName", { position: position + 1 })}</bdi>
      ),
      hint: (
        <Box
          component="span"
          sx={{ typography: "systemHint", color: hintColor }}
        >
          {say("name.notNamed")}
        </Box>
      ),
    })
  ) : (
    <bdi>{step.name}</bdi>
  );
}

export function kindSetApart(step: StepDraft) {
  return step.runs.kind === "none" ? undefined : (
    <StepKindTag kind={step.runs.kind} />
  );
}

/** What the kind above it leaves unsaid: which one it runs, of a route nothing more. */
function runsSaid(
  step: StepDraft,
  named: ReadonlyMap<string, Named>,
): ReactNode {
  const runs = step.runs;
  switch (runs.kind) {
    case "none":
      return say("workflow.runsNothing");
    case "question":
    case "workflow": {
      const version = named.get(runs.version);
      return version === undefined
        ? say("workflow.runsNothing")
        : versionShown(version, runs.version);
    }
    case "code_step":
      return runs.codeStep === "" ? say("workflow.runsNothing") : runs.codeStep;
    case "search":
      return runs.searchWay === ""
        ? say("workflow.runsNothing")
        : runs.searchWay;
    case "route":
      return null;
  }
}

function reviewedSaid(step: StepDraft): string {
  if (
    step.runs.kind !== "question" &&
    step.runs.kind !== "code_step" &&
    step.runs.kind !== "search"
  ) {
    return say("workflow.producesNobody");
  }
  return step.reviewer === "model"
    ? modelSaid(step.reviewerModel, step.reviewerMode)
    : say("workflow.producesPerson");
}

function givesSaid(
  step: StepDraft,
  named: ReadonlyMap<string, Named>,
  codeSteps: readonly OfferedCodeStep[],
): string {
  const gives = givenBy(step, named, codeSteps);
  return gives === undefined
    ? say("workflow.givesUnknown")
    : say("workflow.givesCount", { count: gives.length });
}

function pinnedOf(
  step: StepDraft,
  named: ReadonlyMap<string, Named>,
): Named["pinned"] {
  return step.runs.kind === "question" || step.runs.kind === "workflow"
    ? named.get(step.runs.version)?.pinned
    : undefined;
}

export const StepRow = memo(function StepRow({
  step,
  position,
  last,
  picked,
  href,
  named,
  codeSteps,
  editable,
  running,
  moveHeld,
  moveReasonId,
  onRegisterRow,
  onRegisterOpen,
  onMove,
  onTakeVersion,
}: {
  readonly step: StepDraft;
  /** Counted from zero. */
  readonly position: number;
  readonly last: boolean;
  readonly picked: boolean;
  readonly href: string;
  readonly named: ReadonlyMap<string, Named>;
  readonly codeSteps: readonly OfferedCodeStep[];
  readonly editable: boolean;
  readonly running: boolean;
  readonly moveHeld?: boolean;
  readonly moveReasonId?: string;
  readonly onRegisterRow: (key: string, row: HTMLElement | null) => void;
  readonly onRegisterOpen: (key: string, open: HTMLElement | null) => void;
  readonly onMove: (key: string, position: number, by: -1 | 1) => void;
  readonly onTakeVersion: (key: string, versionId: string) => void;
}) {
  const called = calledOf(step, position);
  const pinned = pinnedOf(step, named);
  const up = say("workflow.up", { name: called });
  const down = say("workflow.down", { name: called });
  return (
    <TableRow
      ref={(row: HTMLTableRowElement | null) => onRegisterRow(step.key, row)}
      selected={picked}
    >
      <TableCell {...FIGURE_SETTING}>
        {readersIntl.formatNumber(position + 1)}
      </TableCell>
      <TableCell>
        <Link
          ref={(open: HTMLElement | null) => onRegisterOpen(step.key, open)}
          component={RouterLink}
          to={href}
          replace
          aria-label={say("workflow.open", { name: called })}
          aria-current={picked || undefined}
        >
          {calledVisibleOf(step, position)}
        </Link>
      </TableCell>
      <TableCell>
        <Box sx={RUNS_SX}>
          {kindSetApart(step)}
          {runsSaid(step, named)}
        </Box>
        {pinned === undefined ? null : (
          <PinNote
            pinned={pinned}
            called={called}
            take={
              editable && !running
                ? (versionId) => onTakeVersion(step.key, versionId)
                : undefined
            }
          />
        )}
      </TableCell>
      <TableCell data-compact="true">{producerSaid(step)}</TableCell>
      <TableCell data-compact="true">{reviewedSaid(step)}</TableCell>
      <TableCell data-compact="true">
        {givesSaid(step, named, codeSteps)}
      </TableCell>
      {editable ? (
        <TableCell data-compact="true">
          <Box
            sx={{
              display: "flex",
              justifyContent: "flex-end",
              gap: TOKENS.table.moveGap,
            }}
          >
            {([-1, 1] as const).map((by) => (
              <Box
                component="span"
                key={by}
                data-part={by < 0 ? "up" : "down"}
                sx={{
                  width: TOKENS.size.controlSmall,
                  height: TOKENS.size.controlSmall,
                  flexShrink: 0,
                }}
              >
                {(by < 0 ? position === 0 : last) ? null : (
                  <Tooltip
                    title={say(by < 0 ? "workflow.upTip" : "workflow.downTip")}
                    describeChild
                  >
                    <IconButton
                      size="small"
                      disableRipple={running || moveHeld === true}
                      aria-label={by < 0 ? up : down}
                      aria-describedby={moveHeld ? moveReasonId : undefined}
                      sx={{
                        width: TOKENS.size.controlSmall,
                        height: TOKENS.size.controlSmall,
                        color: "ink.secondary",
                        borderRadius: TOKENS.shape.controlRadius,
                        [`&.${buttonClasses.disabled}`]: {
                          pointerEvents: "auto",
                        },
                        "& .MuiSvgIcon-root": {
                          width: TOKENS.table.moveIcon,
                          height: TOKENS.table.moveIcon,
                        },
                        "&:hover, &:active": {
                          bgcolor: "act.hover",
                          color: "ink.secondary",
                        },
                      }}
                      {...heldOr(running || moveHeld === true, () =>
                        onMove(step.key, position, by),
                      )}
                    >
                      {by < 0 ? <ArrowUpwardIcon /> : <ArrowDownwardIcon />}
                    </IconButton>
                  </Tooltip>
                )}
              </Box>
            ))}
          </Box>
        </TableCell>
      ) : null}
    </TableRow>
  );
});
