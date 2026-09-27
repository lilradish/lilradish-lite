import Box from "@mui/material/Box";
import FormControlLabel from "@mui/material/FormControlLabel";
import Switch from "@mui/material/Switch";
import TextField from "@mui/material/TextField";
import Typography from "@mui/material/Typography";
import { useId, useState } from "react";

import type { WorkflowVersion } from "../../api/groups/{groupId}/{kind}/{entryId}/versions/{versionId}";
import type { SentChoice } from "../../api/groups/{groupId}/{kind}/{entryId}/versions/{versionId}/steps";
import { ProblemView } from "../../app/ProblemView";
import { say } from "../../i18n/app";
import { ActButton } from "../../lib/action/ActButton";
import { Notice } from "../../lib/notice/Notice";
import type { Action } from "../../lib/request/useAction";
import { ceilingFits } from "../runs/ceilingTyped";
import { VERSION_ACT_RULES } from "./actRules";
import { problemSaid } from "./ContentProblems";
import { ModelControls, modelSaid } from "./ModelControls";

const PART_SX = { mb: 3 };

const HEADING_SX = { mb: 1 };

const ROW_SX = {
  display: "flex",
  flexWrap: "wrap",
  gap: 2,
  alignItems: "center",
  mb: 1,
};

/**
 * The most a run may spend, which may be none, and whether a run keeps its own beneath another and a raise waits
 * on approving; set together, and read where the draft may not be written.
 */
export function CeilingPart({
  workflow,
  editable,
  action,
  waiting,
  save,
}: {
  readonly workflow: WorkflowVersion;
  readonly editable: boolean;
  readonly action: Action<WorkflowVersion>;
  /** Whether another write of the host's, or its read, is out, which saving this would be refused beside. */
  readonly waiting: boolean;
  readonly save: (
    ceiling: string | null,
    keepsOwnCeiling: boolean,
    raiseNeedsApproval: boolean,
    signal: AbortSignal,
  ) => Promise<WorkflowVersion>;
}) {
  const headingId = useId();
  const read = {
    typed: workflow.ceiling ?? "",
    keeps: workflow.keepsOwnCeiling,
    raise: workflow.raiseNeedsApproval,
  };
  const readAs = JSON.stringify(read);
  const [typed, setTyped] = useState(read);
  const [shownAs, setShownAs] = useState(readAs);
  const [asked, setAsked] = useState(false);
  if (readAs !== shownAs) {
    setShownAs(readAs);
    setTyped(read);
  }
  const fits = typed.typed === "" || ceilingFits(typed.typed);
  const running = action.running;
  return (
    <Box component="section" aria-labelledby={headingId} sx={PART_SX}>
      <Typography id={headingId} variant="h6" component="h3" sx={HEADING_SX}>
        {say("workflow.ceilingPart")}
      </Typography>
      {editable ? (
        <>
          <Box sx={ROW_SX}>
            <TextField
              size="small"
              label={say("workflow.ceiling")}
              value={typed.typed}
              error={!fits}
              helperText={say(
                fits ? "workflow.ceilingHint" : "refusal.CEILING_UNUSABLE",
              )}
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
              waiting={waiting}
              reason={
                !fits
                  ? { severity: "info", words: say("refusal.CEILING_UNUSABLE") }
                  : JSON.stringify(typed) === readAs
                    ? { severity: "info", words: say("workflow.unchanged") }
                    : undefined
              }
              act={(signal) => {
                setAsked(true);
                return save(
                  typed.typed === "" ? null : typed.typed,
                  typed.keeps,
                  typed.raise,
                  signal,
                );
              }}
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
      {!asked || action.problem === null ? null : (
        <ProblemView
          problem={action.problem}
          rule={{ inGroup: VERSION_ACT_RULES.write }}
        />
      )}
    </Box>
  );
}

/**
 * Whether a run may be helped, and the model and mode helping it where it may be; a model chosen is kept while
 * help is switched off, and sent only with help switched on.
 */
export function HelpPart({
  workflow,
  editable,
  action,
  waiting,
  save,
}: {
  readonly workflow: WorkflowVersion;
  readonly editable: boolean;
  readonly action: Action<WorkflowVersion>;
  /** Whether another write of the host's, or its read, is out, which saving this would be refused beside. */
  readonly waiting: boolean;
  readonly save: (
    mayBeHelped: boolean,
    helper: SentChoice | null,
    signal: AbortSignal,
  ) => Promise<WorkflowVersion>;
}) {
  const headingId = useId();
  const read = {
    may: workflow.mayBeHelped,
    model: workflow.helper?.model ?? "",
    mode: workflow.helper?.mode ?? "",
  };
  const readAs = JSON.stringify(read);
  const [typed, setTyped] = useState(read);
  const [shownAs, setShownAs] = useState(readAs);
  const [asked, setAsked] = useState(false);
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
  const problems = workflow.problems.filter(
    (problem) => problem.part === "helper",
  );
  const running = action.running;
  return (
    <Box component="section" aria-labelledby={headingId} sx={PART_SX}>
      <Typography id={headingId} variant="h6" component="h3" sx={HEADING_SX}>
        {say("workflow.helpPart")}
      </Typography>
      {problems.map((problem, index) => (
        <Notice key={`${index}:${problem.code}`} severity="warning">
          {problemSaid(problem)}
        </Notice>
      ))}
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
              waiting={waiting}
              reason={
                unchanged
                  ? { severity: "info", words: say("workflow.unchanged") }
                  : undefined
              }
              act={(signal) => {
                setAsked(true);
                return save(typed.may, helper, signal);
              }}
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
      {!asked || action.problem === null ? null : (
        <ProblemView
          problem={action.problem}
          rule={{ inGroup: VERSION_ACT_RULES.write }}
        />
      )}
    </Box>
  );
}
