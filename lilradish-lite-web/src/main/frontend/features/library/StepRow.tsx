import ArrowDownwardIcon from "@mui/icons-material/ArrowDownward";
import ArrowUpwardIcon from "@mui/icons-material/ArrowUpward";
import DeleteOutlinedIcon from "@mui/icons-material/DeleteOutlined";
import Box from "@mui/material/Box";
import Link from "@mui/material/Link";
import TableCell from "@mui/material/TableCell";
import TableRow from "@mui/material/TableRow";
import { memo } from "react";
import { Link as RouterLink } from "react-router";

import type {
  OfferedCodeStep,
  OfferedSearchWay,
} from "../../api/groups/{groupId}/{kind}/{entryId}/versions/{versionId}";
import { say } from "../../i18n/app";
import { visible } from "../../i18n/visible";
import { IconPress } from "../../lib/action/IconPress";
import { FIGURE_SETTING } from "../../lib/collection/SortableTable";
import { isolatedInText } from "../../lib/direction/isolated";
import { HEADER_ACTS_SX } from "../../lib/layout/parts";
import { modelSaid } from "./ModelControls";
import { PinNote } from "./PinNote";
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
  return step.name === ""
    ? say("workflow.unnamed", { position: position + 1 })
    : isolatedInText(step.name);
}

export function calledVisibleOf(
  step: StepDraft,
  position: number,
  hintColor: "text.secondary" | "inherit" = "text.secondary",
) {
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
): string | null {
  const runs = step.runs;
  switch (runs.kind) {
    case "none":
      return say("workflow.runsNothing");
    case "question":
    case "workflow": {
      const version = named.get(runs.version);
      return version === undefined
        ? say("workflow.runsNothing")
        : say("workflow.runsVersion", {
            name: isolatedInText(version.name),
            number: version.number,
          });
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
  searchWays: readonly OfferedSearchWay[],
): string {
  const gives = givenBy(step, named, codeSteps, searchWays);
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
  focusAfterTakingOut,
  picked,
  href,
  named,
  codeSteps,
  searchWays,
  editable,
  running,
  onRegisterRow,
  onRegisterOpen,
  onMove,
  onTakeOut,
  onTakeVersion,
}: {
  readonly step: StepDraft;
  /** Counted from zero. */
  readonly position: number;
  readonly last: boolean;
  /** The step whose link the keyboard goes to once this one is taken out, where any is left. */
  readonly focusAfterTakingOut: string | undefined;
  readonly picked: boolean;
  readonly href: string;
  readonly named: ReadonlyMap<string, Named>;
  readonly codeSteps: readonly OfferedCodeStep[];
  readonly searchWays: readonly OfferedSearchWay[];
  readonly editable: boolean;
  readonly running: boolean;
  readonly onRegisterRow: (key: string, row: HTMLElement | null) => void;
  readonly onRegisterOpen: (key: string, open: HTMLElement | null) => void;
  readonly onMove: (key: string, position: number, by: -1 | 1) => void;
  readonly onTakeOut: (
    position: number,
    focusAfter: string | undefined,
    wasPicked: boolean,
  ) => void;
  readonly onTakeVersion: (key: string, versionId: string) => void;
}) {
  const called = calledOf(step, position);
  const pinned = pinnedOf(step, named);
  const up = say("workflow.up", { name: called });
  const down = say("workflow.down", { name: called });
  const takeOut = say("workflow.remove", { name: called });
  return (
    <TableRow
      ref={(row: HTMLTableRowElement | null) => onRegisterRow(step.key, row)}
      selected={picked}
    >
      <TableCell {...FIGURE_SETTING}>{position + 1}</TableCell>
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
        {editable ? (
          <Box sx={HEADER_ACTS_SX}>
            <Box component="span" data-part="up">
              <IconPress
                icon={<ArrowUpwardIcon />}
                label={up}
                unavailable={running || position === 0}
                onPress={() => onMove(step.key, position, -1)}
              >
                {say("workflow.upTip")}
              </IconPress>
            </Box>
            <Box component="span" data-part="down">
              <IconPress
                icon={<ArrowDownwardIcon />}
                label={down}
                unavailable={running || last}
                onPress={() => onMove(step.key, position, 1)}
              >
                {say("workflow.downTip")}
              </IconPress>
            </Box>
            <IconPress
              icon={<DeleteOutlinedIcon />}
              warns
              label={takeOut}
              unavailable={running}
              onPress={() => onTakeOut(position, focusAfterTakingOut, picked)}
            >
              {say("workflow.removeTip")}
            </IconPress>
          </Box>
        ) : null}
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
      <TableCell>{producerSaid(step)}</TableCell>
      <TableCell>{reviewedSaid(step)}</TableCell>
      <TableCell>{givesSaid(step, named, codeSteps, searchWays)}</TableCell>
    </TableRow>
  );
});
