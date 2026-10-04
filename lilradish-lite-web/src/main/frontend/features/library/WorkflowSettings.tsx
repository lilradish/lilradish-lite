import InfoOutlinedIcon from "@mui/icons-material/InfoOutlined";
import Box from "@mui/material/Box";
import IconButton from "@mui/material/IconButton";
import Tooltip from "@mui/material/Tooltip";
import FormControlLabel from "@mui/material/FormControlLabel";
import Switch from "@mui/material/Switch";
import TextField from "@mui/material/TextField";
import Typography from "@mui/material/Typography";
import { useEffect, useId, useState } from "react";

import type { WorkflowVersion } from "../../api/groups/{groupId}/{kind}/{entryId}/versions/{versionId}";
import type { SentChoice } from "../../api/groups/{groupId}/{kind}/{entryId}/versions/{versionId}/steps";
import { ProblemView } from "../../app/ProblemView";
import { say } from "../../i18n/app";
import { ActButton } from "../../lib/action/ActButton";
import { Disclosure } from "../../lib/action/Disclosure";
import { Part } from "../../lib/layout/Part";
import { DEPTH_SX, HEADING_SX } from "../../lib/layout/parts";
import { Notice } from "../../lib/notice/Notice";
import type { Action } from "../../lib/request/useAction";
import { ceilingFits } from "../runs/ceilingTyped";
import { VERSION_ACT_RULES } from "./actRules";
import { problemSaid } from "./ContentProblems";
import { ModelControls, modelSaid } from "./ModelControls";
import { useDraftPartStatus } from "./useDraftStatus";

const ROW_SX = {
  display: "flex",
  flexWrap: "wrap",
  gap: 2,
  alignItems: "center",
  mb: 1,
};

type SaveCeiling = (
  ceiling: string | null,
  keepsOwnCeiling: boolean,
  raiseNeedsApproval: boolean,
  signal: AbortSignal,
) => Promise<WorkflowVersion>;

type SaveHelp = (
  mayBeHelped: boolean,
  helper: SentChoice | null,
  signal: AbortSignal,
) => Promise<WorkflowVersion>;

export function RunSettings({
  workflow,
  editable,
  spending,
  helping,
  waiting,
  saveCeiling,
  saveHelp,
}: {
  readonly workflow: WorkflowVersion;
  readonly editable: boolean;
  readonly spending: Action<WorkflowVersion>;
  readonly helping: Action<WorkflowVersion>;
  readonly waiting: (own: Action<WorkflowVersion>) => boolean;
  readonly saveCeiling: SaveCeiling;
  readonly saveHelp: SaveHelp;
}) {
  const headingId = useId();
  const contentId = useId();
  const [shown, setShown] = useState(false);
  const [ceilingUnsaved, setCeilingUnsaved] = useState(false);
  const [helpUnsaved, setHelpUnsaved] = useState(false);
  const ceiling =
    workflow.ceiling === undefined
      ? say("workflow.ceilingNoneShort")
      : say("workflow.ceilingShort", { ceiling: workflow.ceiling });
  const help = !workflow.mayBeHelped
    ? say("workflow.notHelpedShort")
    : workflow.helper === undefined
      ? say("workflow.helperUnchosenShort")
      : say("workflow.helpedByShort", {
          model: modelSaid(workflow.helper.model, workflow.helper.mode ?? ""),
        });
  return (
    <Box component="section" aria-labelledby={headingId} sx={DEPTH_SX[2]}>
      <Box sx={{ display: "flex", alignItems: "center", gap: 1 }}>
        <Typography id={headingId} variant="h3" sx={HEADING_SX}>
          <Disclosure
            open={shown}
            controls={contentId}
            onToggle={() => setShown((was) => !was)}
          >
            {say("workflow.runSettings")}
          </Disclosure>
        </Typography>
        {editable && !shown && (ceilingUnsaved || helpUnsaved) ? (
          <Typography component="span" variant="body2" color="text.secondary">
            {say("draft.unsaved")}
          </Typography>
        ) : null}
        <Tooltip title={say("workflow.ceilingHint")}>
          <IconButton size="small" aria-label={say("workflow.ceilingHint")}>
            <InfoOutlinedIcon />
          </IconButton>
        </Tooltip>
      </Box>
      <Typography variant="body2" sx={{ color: "text.secondary" }}>
        {say("workflow.runSettingsSummary", { ceiling, help })}
      </Typography>
      {workflow.problems
        .filter((problem) => problem.part === "helper")
        .map((problem, index) => (
          <Notice key={`${index}:${problem.code}`} severity="warning">
            {problemSaid(problem)}
          </Notice>
        ))}
      {spending.problem === null ? null : (
        <ProblemView
          problem={spending.problem}
          rule={{ inGroup: VERSION_ACT_RULES.write }}
        />
      )}
      {helping.problem === null ? null : (
        <ProblemView
          problem={helping.problem}
          rule={{ inGroup: VERSION_ACT_RULES.write }}
        />
      )}
      <div id={contentId} hidden={!shown}>
        <CeilingPart
          workflow={workflow}
          editable={editable}
          action={spending}
          waiting={waiting(spending)}
          save={saveCeiling}
          onUnsaved={setCeilingUnsaved}
        />
        <HelpPart
          workflow={workflow}
          editable={editable}
          action={helping}
          waiting={waiting(helping)}
          save={saveHelp}
          onUnsaved={setHelpUnsaved}
        />
      </div>
    </Box>
  );
}

/**
 * The most a run may spend, which may be none, and whether a run keeps its own beneath another and a raise waits
 * on approving; set together, and read where the draft may not be written.
 */
function CeilingPart({
  workflow,
  editable,
  action,
  waiting,
  save,
  onUnsaved,
}: {
  readonly workflow: WorkflowVersion;
  readonly editable: boolean;
  readonly action: Action<WorkflowVersion>;
  /** Whether another write of the host's, or its read, is out, which saving this would be refused beside. */
  readonly waiting: boolean;
  readonly save: SaveCeiling;
  readonly onUnsaved: (unsaved: boolean) => void;
}) {
  const read = {
    typed: workflow.ceiling?.toString() ?? "",
    keeps: workflow.keepsOwnCeiling,
    raise: workflow.raiseNeedsApproval,
  };
  const readAs = JSON.stringify(read);
  const [typed, setTyped] = useState(read);
  const [shownAs, setShownAs] = useState(readAs);
  if (readAs !== shownAs) {
    setShownAs(readAs);
    setTyped(read);
  }
  const fits = typed.typed === "" || ceilingFits(typed.typed);
  const running = action.running;
  const unsaved = JSON.stringify(typed) !== readAs;
  useDraftPartStatus("ceiling", unsaved, running || waiting);
  useEffect(() => onUnsaved(unsaved), [onUnsaved, unsaved]);
  return (
    <Part
      title={say("workflow.ceilingPart")}
      actions={
        editable && unsaved ? (
          <Typography component="span" variant="body2" color="text.secondary">
            {say("draft.unsaved")}
          </Typography>
        ) : undefined
      }
      level="h4"
      depth={3}
    >
      {editable ? (
        <>
          <Box sx={ROW_SX}>
            <TextField
              size="small"
              label={say("workflow.ceiling")}
              value={typed.typed}
              error={!fits}
              helperText={fits ? undefined : say("refusal.CEILING_UNUSABLE")}
              slotProps={{
                htmlInput: { inputMode: "numeric", readOnly: running },
              }}
              onChange={(edit) => {
                if (!running) {
                  setTyped({ ...typed, typed: edit.target.value });
                }
              }}
            />
          </Box>
          <FormControlLabel
            control={
              <Switch
                checked={typed.keeps}
                onChange={(_event, checked) => {
                  if (!running) {
                    setTyped({ ...typed, keeps: checked });
                  }
                }}
              />
            }
            label={say("workflow.keepsOwn")}
          />
          <FormControlLabel
            control={
              <Switch
                checked={typed.raise}
                onChange={(_event, checked) => {
                  if (!running) {
                    setTyped({ ...typed, raise: checked });
                  }
                }}
              />
            }
            label={say("workflow.raiseNeedsApproval")}
          />
          <Box>
            <ActButton
              action={action}
              variant={unsaved ? "contained" : undefined}
              waiting={waiting}
              reason={
                !fits
                  ? {
                      severity: "warning",
                      words: say("refusal.CEILING_UNUSABLE"),
                    }
                  : !unsaved
                    ? { severity: "info", words: say("workflow.unchanged") }
                    : undefined
              }
              act={(signal) =>
                save(
                  typed.typed === "" ? null : typed.typed,
                  typed.keeps,
                  typed.raise,
                  signal,
                )
              }
            >
              {say("workflow.saveCeiling")}
            </ActButton>
          </Box>
          {running ? (
            <Notice severity="info">{say("workflow.underway")}</Notice>
          ) : null}
        </>
      ) : (
        <>
          <Typography variant="body2">
            {workflow.ceiling === undefined
              ? say("workflow.ceilingNone")
              : say("workflow.ceilingSaid", { ceiling: workflow.ceiling })}
          </Typography>
          {workflow.keepsOwnCeiling ? (
            <Typography variant="body2">{say("workflow.keepsOwn")}</Typography>
          ) : null}
          {workflow.raiseNeedsApproval ? (
            <Typography variant="body2">
              {say("workflow.raiseNeedsApproval")}
            </Typography>
          ) : null}
        </>
      )}
    </Part>
  );
}

/**
 * Whether a run may be helped, and the model and mode helping it where it may be; a model chosen is kept while
 * help is switched off, and sent only with help switched on.
 */
function HelpPart({
  workflow,
  editable,
  action,
  waiting,
  save,
  onUnsaved,
}: {
  readonly workflow: WorkflowVersion;
  readonly editable: boolean;
  readonly action: Action<WorkflowVersion>;
  /** Whether another write of the host's, or its read, is out, which saving this would be refused beside. */
  readonly waiting: boolean;
  readonly save: SaveHelp;
  readonly onUnsaved: (unsaved: boolean) => void;
}) {
  const read = {
    may: workflow.mayBeHelped,
    model: workflow.helper?.model ?? "",
    mode: workflow.helper?.mode ?? "",
  };
  const readAs = JSON.stringify(read);
  const [typed, setTyped] = useState(read);
  const [shownAs, setShownAs] = useState(readAs);
  if (readAs !== shownAs) {
    setShownAs(readAs);
    setTyped(read);
  }
  const helper: SentChoice | null =
    typed.may && typed.model !== ""
      ? { model: typed.model, mode: typed.mode === "" ? null : typed.mode }
      : null;
  const unchanged =
    typed.may === read.may &&
    JSON.stringify(helper) ===
      JSON.stringify(
        workflow.helper === undefined
          ? null
          : {
              model: workflow.helper.model,
              mode: workflow.helper.mode ?? null,
            },
      );
  const running = action.running;
  useDraftPartStatus("help", !unchanged, running || waiting);
  useEffect(() => onUnsaved(!unchanged), [onUnsaved, unchanged]);
  return (
    <Part
      title={say("workflow.helpPart")}
      actions={
        editable && !unchanged ? (
          <Typography component="span" variant="body2" color="text.secondary">
            {say("draft.unsaved")}
          </Typography>
        ) : undefined
      }
      level="h4"
      depth={3}
    >
      {editable ? (
        <>
          <FormControlLabel
            control={
              <Switch
                checked={typed.may}
                onChange={(_event, checked) => {
                  if (!running) {
                    setTyped({ ...typed, may: checked });
                  }
                }}
              />
            }
            label={say("workflow.mayBeHelped")}
          />
          {typed.may ? (
            <Box sx={ROW_SX}>
              <ModelControls
                models={workflow.offered.models}
                model={typed.model}
                mode={typed.mode}
                change={(model, mode) => {
                  if (!running) {
                    setTyped({ ...typed, model, mode });
                  }
                }}
              />
            </Box>
          ) : null}
          <Box>
            <ActButton
              action={action}
              variant={!unchanged ? "contained" : undefined}
              waiting={waiting}
              reason={
                unchanged
                  ? { severity: "info", words: say("workflow.unchanged") }
                  : undefined
              }
              act={(signal) => save(typed.may, helper, signal)}
            >
              {say("workflow.saveHelp")}
            </ActButton>
          </Box>
          {running ? (
            <Notice severity="info">{say("workflow.underway")}</Notice>
          ) : null}
        </>
      ) : (
        <Typography variant="body2">
          {!workflow.mayBeHelped
            ? say("workflow.notHelped")
            : workflow.helper === undefined
              ? say("workflow.helperUnchosen")
              : say("workflow.helpedBy", {
                  model: modelSaid(
                    workflow.helper.model,
                    workflow.helper.mode ?? "",
                  ),
                })}
        </Typography>
      )}
    </Part>
  );
}
