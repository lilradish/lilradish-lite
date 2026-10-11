import DeleteOutlinedIcon from "@mui/icons-material/DeleteOutlined";
import Box from "@mui/material/Box";
import Checkbox from "@mui/material/Checkbox";
import FormControlLabel from "@mui/material/FormControlLabel";
import MenuItem from "@mui/material/MenuItem";
import Typography from "@mui/material/Typography";
import { createContext, useContext, useEffect, useRef } from "react";

import type { DeclaredField } from "../../api/declaration";
import type { ContentProblem } from "../../api/groups/{groupId}/{kind}/{entryId}/versions";
import type { WorkflowVersion } from "../../api/groups/{groupId}/{kind}/{entryId}/versions/{versionId}";
import type { VersionDetail } from "../../api/groups/{groupId}/versions/{versionId}";
import { ProblemView } from "../../app/ProblemView";
import { say, type MessageId } from "../../i18n/app";
import { Press, type Reason } from "../../lib/action/Press";
import type { Action } from "../../lib/request/useAction";
import type { DraftField } from "./declarationDrafts";
import { IconPress } from "../../lib/action/IconPress";
import { isolatedInText } from "../../lib/direction/isolated";
import {
  BESIDE_LABELLED_SX,
  LabelledTextField,
  LINES_BESIDE_LABELLED_SX,
} from "../../lib/form/LabelledTextField";
import { GROUP_SX, HEADING_SX } from "../../lib/layout/parts";
import { Notice } from "../../lib/notice/Notice";
import { BindingControls, Inputs } from "./BindingControls";
import { problemSaid } from "./ContentProblems";
import { DeclarationBuilder } from "./DeclarationBuilder";
import { PinNote, versionShown } from "./PinNote";
import { EditorVersionPicker, useVersionDetail } from "./EditorVersionPicker";
import {
  boundTo,
  caseTookVersion,
  freshCase,
  mostCases,
  termFits,
  unusedKey,
  WORKFLOW_GIVES,
  type BindingDraft,
  type CaseDraft,
  type Named,
  type RouteDraft,
  type SourceDraft,
  type SourceOffered,
  type TargetRow,
} from "./workflowDrafts";

const ROW_SX = {
  display: "flex",
  flexWrap: "wrap",
  gap: 2,
  alignItems: "flex-start",
  mb: 2,
};

const QUIET_SX = { color: "text.secondary" };

const CHOSEN_BY = {
  name: "",
  kind: "term",
  many: false,
  mustBeGiven: false,
} as const;

/** Where focus goes once the cases a press changed are drawn: a case, or the cases' heading. */
type Focus = { readonly key: string } | "heading";

/** A route's halves are typed into the flow and saved with it, as one unsaved part. */
export const FLOW_UNIT = "flow";

/** Brings the route's step into view, before a half of it puts the keyboard on a field. */
export const FlowFieldLocationContext = createContext<(() => void) | null>(
  null,
);

export const FlowFieldMoveContext = createContext<{
  readonly action: Action<WorkflowVersion>;
  readonly waiting: boolean;
  readonly reason?: Reason;
  readonly save: (
    stepKey: string,
    gives: readonly DraftField[],
    signal: AbortSignal,
    focus: { readonly key: string; readonly by: -1 | 1 },
  ) => Promise<WorkflowVersion>;
} | null>(null);

export function RoutePart({
  runs,
  stepKey,
  stepId,
  workflow,
  named,
  sources,
  chosenBy,
  terms,
  rowsOf,
  editable,
  change,
  scope,
  resolve,
  details,
}: {
  readonly runs: RouteDraft;
  readonly stepKey?: string;
  readonly stepId: string | null;
  readonly workflow: WorkflowVersion;
  readonly named: ReadonlyMap<string, Named>;
  readonly sources: readonly SourceOffered[];
  /** What does not hold of what it chooses by. */
  readonly chosenBy: readonly MessageId[];
  /** The terms the list it chooses by offers, none where the page does not know them. */
  readonly terms: readonly string[] | undefined;
  readonly rowsOf: (
    takes: readonly DeclaredField[],
    bindings: readonly BindingDraft[],
    problems: readonly ContentProblem[],
  ) => TargetRow[];
  readonly editable: boolean;
  readonly change: (runs: RouteDraft) => void;
  readonly scope: string;
  readonly details: ReadonlyMap<string, VersionDetail>;
  readonly resolve: (
    detail: VersionDetail | null,
    change: (runs: RouteDraft, named: ReadonlyMap<string, Named>) => RouteDraft,
  ) => void;
}) {
  const locate = useContext(FlowFieldLocationContext);
  const moving = useContext(FlowFieldMoveContext);
  const heading = useRef<HTMLHeadingElement>(null);
  const cases = useRef(new Map<string, HTMLElement>());
  const pending = useRef<Focus | null>(null);
  useEffect(() => {
    const focus = pending.current;
    pending.current = null;
    if (focus === "heading") {
      heading.current?.focus();
    } else if (focus !== null) {
      cases.current.get(focus.key)?.focus();
    }
  }, [runs.cases]);
  const read = workflow.steps.find((each) => each.stepId === stepId)?.runs;
  const readGives = read?.kind === "route" ? (read.gives ?? []) : [];
  const setCase = (key: string, changed: (routeCase: CaseDraft) => CaseDraft) =>
    change({
      ...runs,
      cases: runs.cases.map((routeCase) =>
        routeCase.key === key ? changed(routeCase) : routeCase,
      ),
    });
  const caseRows = (routeCase: CaseDraft): TargetRow[] | undefined => {
    if (routeCase.workflow === "") {
      return [];
    }
    const takes = named.get(routeCase.workflow)?.takes;
    return takes === undefined
      ? undefined
      : rowsOf(
          takes,
          routeCase.bindings,
          workflow.problems.filter(
            (problem) => problem.caseId === routeCase.caseId,
          ),
        );
  };
  const most = mostCases(terms);
  const fallback = runs.cases.find((routeCase) => routeCase.fallback)?.key;
  return (
    <>
      <Box sx={GROUP_SX}>
        <BindingControls
          label={say("workflow.chosenBy")}
          target={CHOSEN_BY}
          binding={{ target: "", source: runs.discriminator }}
          sources={sources}
          constant={false}
          editable={editable}
          flags={chosenBy}
          onChange={(discriminator: SourceDraft) =>
            change({ ...runs, discriminator, discriminatorId: undefined })
          }
        />
      </Box>
      <DeclarationBuilder
        title={say("workflow.routeGives")}
        heading="h5"
        depth={3}
        demands={WORKFLOW_GIVES}
        fields={readGives}
        scope={`${scope}:gives`}
        editable={editable}
        controlled={{
          drafts: runs.gives,
          details,
          change: (gives) => change({ ...runs, gives }),
          unit: FLOW_UNIT,
          move:
            moving === null || stepKey === undefined
              ? undefined
              : {
                  action: moving.action,
                  waiting: moving.waiting,
                  reason: moving.reason,
                  save: (gives, signal, focus) =>
                    moving.save(stepKey, gives, signal, focus),
                },
          onLocate: locate ?? undefined,
          resolve: (detail, changed) =>
            resolve(detail, (now) => ({ ...now, gives: changed(now.gives) })),
        }}
      />
      <Typography ref={heading} tabIndex={-1} variant="h5" sx={HEADING_SX}>
        {say("workflow.cases")}
      </Typography>
      {runs.cases.length === 0 ? (
        <Typography variant="body2" sx={QUIET_SX}>
          {say("workflow.casesNone")}
        </Typography>
      ) : null}
      {runs.cases.map((routeCase, index) => (
        <Box
          key={routeCase.key}
          ref={(held: HTMLElement | null) => {
            if (held === null) {
              cases.current.delete(routeCase.key);
            } else {
              cases.current.set(routeCase.key, held);
            }
          }}
          tabIndex={-1}
          role="group"
          aria-label={caseCalled(routeCase)}
          sx={GROUP_SX}
        >
          {workflow.problems
            .filter(
              (problem) =>
                problem.caseId === routeCase.caseId &&
                problem.fieldId === undefined &&
                problem.bindingId === undefined,
            )
            .map((problem, at) => (
              <Notice key={`${at}:${problem.code}`} severity="warning">
                {problemSaid(problem)}
              </Notice>
            ))}
          <CaseControls
            routeCase={routeCase}
            others={runs.cases.filter((each) => each.key !== routeCase.key)}
            fallbackElsewhere={
              fallback !== undefined && fallback !== routeCase.key
            }
            terms={terms}
            named={named}
            editable={editable}
            change={(changed) => setCase(routeCase.key, changed)}
            scope={`${scope}:${routeCase.key}:${runs.cases.map((each) => each.key).join(",")}`}
            resolve={(detail, changed) =>
              resolve(detail, (now, resolved) => ({
                ...now,
                cases: now.cases.map((each) =>
                  each.key === routeCase.key ? changed(each, resolved) : each,
                ),
              }))
            }
          />
          <Inputs
            heading="workflow.caseInputs"
            level="h6"
            depth={4}
            rows={caseRows(routeCase)}
            sources={sources}
            editable={editable}
            change={(target, source) =>
              setCase(routeCase.key, (now) => ({
                ...now,
                bindings: boundTo(now.bindings, target, source),
              }))
            }
            unknown="workflow.inputsUnknown"
          />
          {editable ? (
            <IconPress
              icon={<DeleteOutlinedIcon />}
              unavailable={false}
              label={say("workflow.removeCaseCalled", {
                name: caseCalled(routeCase),
              })}
              onPress={() => {
                const next = runs.cases[index + 1] ?? runs.cases[index - 1];
                pending.current =
                  next === undefined ? "heading" : { key: next.key };
                change({
                  ...runs,
                  cases: runs.cases.filter(
                    (each) => each.key !== routeCase.key,
                  ),
                });
              }}
            >
              {say("workflow.removeCase")}
            </IconPress>
          ) : null}
        </Box>
      ))}
      {editable ? (
        <Press
          reason={
            runs.cases.length >= most
              ? { severity: "warning", words: say("refusal.CASES_PAST_TERMS") }
              : undefined
          }
          onPress={() => {
            const added = freshCase(
              unusedKey(
                "case",
                runs.cases.map((each) => each.key),
              ),
            );
            pending.current = { key: added.key };
            change({ ...runs, cases: [...runs.cases, added] });
          }}
        >
          {say("workflow.addCase")}
        </Press>
      ) : null}
    </>
  );
}

function CaseControls({
  routeCase,
  others,
  fallbackElsewhere,
  terms,
  named,
  editable,
  change,
  scope,
  resolve,
}: {
  readonly routeCase: CaseDraft;
  readonly others: readonly CaseDraft[];
  readonly fallbackElsewhere: boolean;
  readonly terms: readonly string[] | undefined;
  readonly named: ReadonlyMap<string, Named>;
  readonly editable: boolean;
  readonly change: (changed: (routeCase: CaseDraft) => CaseDraft) => void;
  readonly scope: string;
  readonly resolve: (
    detail: VersionDetail | null,
    change: (
      routeCase: CaseDraft,
      named: ReadonlyMap<string, Named>,
    ) => CaseDraft,
  ) => void;
}) {
  const pinned = named.get(routeCase.workflow)?.pinned;
  const newer = useVersionDetail({
    kind: "workflow",
    scope: `${scope}:${routeCase.workflow}`,
    disabled: !editable,
    onRead: (detail) =>
      resolve(detail, (now, resolved) =>
        caseTookVersion(now, detail?.version.versionId ?? "", resolved),
      ),
  });
  if (!editable) {
    return (
      <Box sx={ROW_SX}>
        <Typography variant="body2">{caseCalled(routeCase)}</Typography>
        <Typography variant="body2">
          {say("workflow.caseWorkflow")}
          {": "}
          {versionShown(named.get(routeCase.workflow), routeCase.workflow)}
        </Typography>
        {pinned === undefined ? null : <PinNote pinned={pinned} />}
      </Box>
    );
  }
  const taken = new Set(
    others.filter((each) => !each.fallback).map((each) => each.term),
  );
  const repeated = taken.has(routeCase.term);
  return (
    <Box sx={ROW_SX}>
      <FormControlLabel
        sx={BESIDE_LABELLED_SX}
        control={
          <Checkbox
            checked={routeCase.fallback}
            disabled={fallbackElsewhere}
            onChange={(_event, checked) =>
              change((now) => ({ ...now, fallback: checked }))
            }
          />
        }
        label={say("workflow.fallback")}
      />
      {routeCase.fallback ? null : terms === undefined ? (
        <LabelledTextField
          size="small"
          label={say("workflow.term")}
          value={routeCase.term}
          error={
            repeated || (routeCase.term !== "" && !termFits(routeCase.term))
          }
          helperText={
            repeated
              ? say("refusal.CASE_TERM_REPEATED")
              : routeCase.term !== "" && !termFits(routeCase.term)
                ? say("workflow.termLimit")
                : undefined
          }
          slotProps={{ htmlInput: { dir: "auto" } }}
          onChange={(edit) =>
            change((now) => ({ ...now, term: edit.target.value }))
          }
        />
      ) : (
        <LabelledTextField
          select
          size="small"
          label={say("workflow.term")}
          value={routeCase.term}
          error={repeated}
          helperText={repeated ? say("refusal.CASE_TERM_REPEATED") : undefined}
          onChange={(edit) =>
            change((now) => ({ ...now, term: edit.target.value }))
          }
        >
          <MenuItem value="">{say("workflow.termNone")}</MenuItem>
          {[
            ...(routeCase.term === "" || terms.includes(routeCase.term)
              ? []
              : [routeCase.term]),
            ...terms.filter(
              (term) => term === routeCase.term || !taken.has(term),
            ),
          ].map((term) => (
            <MenuItem key={term} value={term}>
              <bdi>{term}</bdi>
            </MenuItem>
          ))}
        </LabelledTextField>
      )}
      <EditorVersionPicker
        kind="workflow"
        label={say("workflow.caseWorkflow")}
        value={routeCase.workflow}
        noneLabel={say("workflow.versionNone")}
        scope={`${scope}:${routeCase.workflow}`}
        selected={pinned}
        onPick={(detail) =>
          resolve(detail, (now) => ({
            ...now,
            workflow: detail?.version.versionId ?? "",
            bindings: [],
          }))
        }
      />
      {pinned === undefined ? null : (
        <Box sx={LINES_BESIDE_LABELLED_SX}>
          <PinNote pinned={pinned} take={newer.read} />
        </Box>
      )}
      {newer.problem === null ? null : <ProblemView problem={newer.problem} />}
    </Box>
  );
}

function caseCalled(routeCase: CaseDraft): string {
  return routeCase.fallback
    ? say("workflow.fallbackSaid")
    : routeCase.term === ""
      ? say("workflow.caseOnNothing")
      : say("workflow.caseSaid", { term: isolatedInText(routeCase.term) });
}
