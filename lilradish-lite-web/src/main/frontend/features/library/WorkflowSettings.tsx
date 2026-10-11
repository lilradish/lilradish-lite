import Box from "@mui/material/Box";
import FormControlLabel from "@mui/material/FormControlLabel";
import Switch from "@mui/material/Switch";
import TextField from "@mui/material/TextField";
import Typography from "@mui/material/Typography";
import {
  useEffect,
  useId,
  useLayoutEffect,
  useRef,
  useState,
  type RefObject,
} from "react";

import type { WorkflowVersion } from "../../api/groups/{groupId}/{kind}/{entryId}/versions/{versionId}";
import type { SentChoice } from "../../api/groups/{groupId}/{kind}/{entryId}/versions/{versionId}/steps";
import { say } from "../../i18n/app";
import { Disclosure } from "../../lib/action/Disclosure";
import { InfoMark } from "../../lib/action/InfoMark";
import { SaveButton } from "../../lib/action/SaveButton";
import { UnsavedMark } from "../../lib/layout/UnsavedMark";
import { Part } from "../../lib/layout/Part";
import { DEPTH_SX, HEADING_SX } from "../../lib/layout/parts";
import { Notice } from "../../lib/notice/Notice";
import type { Action } from "../../lib/request/useAction";
import { ceilingFits } from "../runs/ceilingTyped";
import { countSaid } from "../runs/spend";
import { VERSION_ACT_RULES } from "./actRules";
import { problemSaid } from "./ContentProblems";
import { ModelControls, modelSaid } from "./ModelControls";
import { locatePart, useDraftPartStatus } from "./useDraftStatus";
import { useHeardRefusal, type HeardRefusal } from "./useHeardRefusal";
import { WriteRefused } from "./WriteRefused";

// A Save drawn only while something is unsaved is gone once it saves, and
// the keyboard it held would fall to the page: it goes to the part's title.
function headingTakesKeyboard<T>(
  section: RefObject<HTMLElement | null>,
  heading: RefObject<HTMLHeadingElement | null>,
) {
  return (answer: T) => {
    if (section.current?.contains(document.activeElement))
      heading.current?.focus();
    return answer;
  };
}

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
  const pendingFocus = useRef<HTMLElement | null>(null);
  useLayoutEffect(() => {
    if (shown && pendingFocus.current !== null) {
      locatePart(pendingFocus.current);
      pendingFocus.current = null;
    }
  }, [shown]);
  const locate = (target: HTMLElement | null) => {
    if (target === null) return;
    if (shown) locatePart(target);
    else {
      pendingFocus.current = target;
      setShown(true);
    }
  };
  const [ceilingUnsaved, setCeilingUnsaved] = useState(false);
  const [helpUnsaved, setHelpUnsaved] = useState(false);
  const spendingRefusal = useRef<HTMLDivElement>(null);
  const helpingRefusal = useRef<HTMLDivElement>(null);
  const spendingHeard = useHeardRefusal(
    spending,
    ceilingUnsaved,
    spendingRefusal,
  );
  const helpingHeard = useHeardRefusal(helping, helpUnsaved, helpingRefusal);
  const ceiling =
    workflow.ceiling === undefined
      ? say("workflow.ceilingNoneShort")
      : say("workflow.ceilingShort", { ceiling: countSaid(workflow.ceiling) });
  const help = !workflow.mayBeHelped
    ? say("workflow.notHelpedShort")
    : workflow.helper === undefined
      ? say("workflow.helperUnchosenShort")
      : say("workflow.helpedByShort", {
          model: modelSaid(workflow.helper.model, workflow.helper.mode ?? ""),
        });
  return (
    <Box component="section" aria-labelledby={headingId} sx={DEPTH_SX[2]}>
      <Box
        sx={{ display: "flex", alignItems: "center", gap: 1, ...HEADING_SX }}
      >
        <Typography id={headingId} variant="h3">
          <Disclosure
            open={shown}
            controls={contentId}
            onToggle={() => setShown((was) => !was)}
          >
            {say("workflow.runSettings")}
          </Disclosure>
        </Typography>
        <InfoMark
          about={say("workflow.runSettingsAbout")}
          hint={say("workflow.runSettingsHint")}
        />
        {editable && !shown && (ceilingUnsaved || helpUnsaved) ? (
          <UnsavedMark />
        ) : null}
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
      {[
        { heard: spendingHeard, action: spending, refusalAt: spendingRefusal },
        { heard: helpingHeard, action: helping, refusalAt: helpingRefusal },
      ].map(({ heard, action, refusalAt }, at) =>
        heard.problem === null ? null : (
          <div key={at} ref={refusalAt} tabIndex={-1}>
            <WriteRefused
              settled={heard.settled}
              problem={heard.problem}
              rule={VERSION_ACT_RULES.write}
              waiting={waiting(action)}
            />
          </div>
        ),
      )}
      <div id={contentId} hidden={!shown}>
        <CeilingPart
          workflow={workflow}
          editable={editable}
          action={spending}
          heard={spendingHeard}
          waiting={waiting(spending)}
          save={saveCeiling}
          onUnsaved={setCeilingUnsaved}
          onLocate={locate}
        />
        <HelpPart
          workflow={workflow}
          editable={editable}
          action={helping}
          heard={helpingHeard}
          waiting={waiting(helping)}
          save={saveHelp}
          onUnsaved={setHelpUnsaved}
          onLocate={locate}
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
  heard,
  waiting,
  save,
  onUnsaved,
  onLocate,
}: {
  readonly workflow: WorkflowVersion;
  readonly editable: boolean;
  readonly action: Action<WorkflowVersion>;
  readonly heard: HeardRefusal;
  /** Whether another write of the host's, or its read, is out, which saving this would be refused beside. */
  readonly waiting: boolean;
  readonly save: SaveCeiling;
  readonly onUnsaved: (unsaved: boolean) => void;
  readonly onLocate: (target: HTMLElement | null) => void;
}) {
  const inputId = useId();
  const section = useRef<HTMLElement>(null);
  const heading = useRef<HTMLHeadingElement>(null);
  const controls = useRef<HTMLDivElement>(null);
  const hintId = useId();
  const helperId = `${inputId}-helper-text`;
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
  useDraftPartStatus("ceiling", unsaved, running || waiting, {
    saving: running,
    node: section,
    locate: () => onLocate(controls.current),
  });
  useEffect(() => onUnsaved(unsaved), [onUnsaved, unsaved]);
  return (
    <Part
      title={say("workflow.ceilingPart")}
      unsaved={editable && unsaved}
      level="h4"
      depth={3}
      headingRef={heading}
      ref={section}
    >
      {editable ? (
        <Box ref={controls}>
          <Box sx={ROW_SX}>
            <Box sx={{ width: "100%" }}>
              <Box
                component="label"
                htmlFor={inputId}
                sx={{
                  display: "block",
                  typography: "controlLabel",
                  color: "text.secondary",
                  mb: "4px",
                }}
              >
                {say("workflow.ceiling")}
              </Box>
              <Box
                id={hintId}
                sx={{
                  typography: "supportingProse",
                  color: "text.secondary",
                  mb: "6px",
                }}
              >
                {say("workflow.ceilingHint")}
              </Box>
              <TextField
                id={inputId}
                sx={{ width: 240, maxWidth: "100%" }}
                size="small"
                fullWidth
                value={typed.typed}
                error={!fits}
                helperText={fits ? undefined : say("refusal.CEILING_UNUSABLE")}
                slotProps={{
                  htmlInput: {
                    inputMode: "numeric",
                    readOnly: running,
                    "aria-describedby": `${hintId}${fits ? "" : ` ${helperId}`}`,
                  },
                }}
                onChange={(edit) => {
                  if (!running) {
                    setTyped({ ...typed, typed: edit.target.value });
                  }
                }}
              />
            </Box>
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
            <SaveButton
              unsaved={unsaved}
              action={action}
              waiting={waiting}
              reason={
                fits
                  ? undefined
                  : {
                      severity: "warning",
                      words: say("refusal.CEILING_UNUSABLE"),
                    }
              }
              act={(signal) => {
                heard.sent();
                return save(
                  typed.typed === "" ? null : typed.typed,
                  typed.keeps,
                  typed.raise,
                  signal,
                ).then(headingTakesKeyboard(section, heading));
              }}
            >
              {say("workflow.saveCeiling")}
            </SaveButton>
          </Box>
          {running ? (
            <Notice severity="info">{say("workflow.underway")}</Notice>
          ) : null}
        </Box>
      ) : (
        <>
          <Typography variant="body2">
            {workflow.ceiling === undefined
              ? say("workflow.ceilingNone")
              : say("workflow.ceilingSaid", {
                  ceiling: countSaid(workflow.ceiling),
                })}
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
  heard,
  waiting,
  save,
  onUnsaved,
  onLocate,
}: {
  readonly workflow: WorkflowVersion;
  readonly editable: boolean;
  readonly action: Action<WorkflowVersion>;
  readonly heard: HeardRefusal;
  /** Whether another write of the host's, or its read, is out, which saving this would be refused beside. */
  readonly waiting: boolean;
  readonly save: SaveHelp;
  readonly onUnsaved: (unsaved: boolean) => void;
  readonly onLocate: (target: HTMLElement | null) => void;
}) {
  const section = useRef<HTMLElement>(null);
  const heading = useRef<HTMLHeadingElement>(null);
  const controls = useRef<HTMLDivElement>(null);
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
  useDraftPartStatus("help", !unchanged, running || waiting, {
    saving: running,
    node: section,
    locate: () => onLocate(controls.current),
  });
  useEffect(() => onUnsaved(!unchanged), [onUnsaved, unchanged]);
  return (
    <Part
      title={say("workflow.helpPart")}
      unsaved={editable && !unchanged}
      level="h4"
      depth={3}
      headingRef={heading}
      ref={section}
    >
      {editable ? (
        <Box ref={controls}>
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
            <SaveButton
              unsaved={!unchanged}
              action={action}
              waiting={waiting}
              act={(signal) => {
                heard.sent();
                return save(typed.may, helper, signal).then(
                  headingTakesKeyboard(section, heading),
                );
              }}
            >
              {say("workflow.saveHelp")}
            </SaveButton>
          </Box>
          {running ? (
            <Notice severity="info">{say("workflow.underway")}</Notice>
          ) : null}
        </Box>
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
