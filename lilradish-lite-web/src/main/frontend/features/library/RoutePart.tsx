import Box from "@mui/material/Box";
import Checkbox from "@mui/material/Checkbox";
import FormControlLabel from "@mui/material/FormControlLabel";
import MenuItem from "@mui/material/MenuItem";
import TextField from "@mui/material/TextField";
import Typography from "@mui/material/Typography";
import { useEffect, useRef } from "react";

import type { DeclaredField } from "../../api/declaration";
import type { ContentProblem } from "../../api/groups/{groupId}/{kind}/{entryId}/versions";
import type { WorkflowVersion } from "../../api/groups/{groupId}/{kind}/{entryId}/versions/{versionId}";
import { say, type MessageId } from "../../i18n/app";
import { Press } from "../../lib/action/Press";
import { isolatedInText } from "../../lib/direction/isolated";
import { Notice } from "../../lib/notice/Notice";
import { BindingControls, Inputs } from "./BindingControls";
import { problemSaid } from "./ContentProblems";
import { DeclarationBuilder } from "./DeclarationBuilder";
import { PinNote, versionSaid } from "./PinNote";
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

const PART_SX = { mb: 3 };

const ROW_SX = {
  display: "flex",
  flexWrap: "wrap",
  gap: 2,
  alignItems: "center",
  mb: 2,
};

const CASE_SX = {
  border: 1,
  borderColor: "divider",
  borderRadius: 1,
  p: 2,
  mb: 1,
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

export function RoutePart({
  runs,
  stepId,
  workflow,
  named,
  sources,
  chosenBy,
  terms,
  rowsOf,
  editable,
  change,
}: {
  readonly runs: RouteDraft;
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
}) {
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
    <Box sx={PART_SX}>
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
      <DeclarationBuilder
        title={say("workflow.routeGives")}
        heading="h5"
        demands={WORKFLOW_GIVES}
        fields={readGives}
        lists={workflow.offered.lists}
        editable={editable}
        controlled={{
          drafts: runs.gives,
          change: (gives) => change({ ...runs, gives }),
        }}
      />
      <Typography
        ref={heading}
        tabIndex={-1}
        variant="subtitle1"
        component="h5"
      >
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
          sx={CASE_SX}
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
            workflow={workflow}
            named={named}
            editable={editable}
            change={(changed) => setCase(routeCase.key, changed)}
          />
          <Inputs
            heading="workflow.caseInputs"
            level="h6"
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
            <Press
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
            </Press>
          ) : null}
        </Box>
      ))}
      {editable ? (
        <Press
          reason={
            runs.cases.length >= most
              ? { severity: "info", words: say("refusal.CASES_PAST_TERMS") }
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
    </Box>
  );
}

function CaseControls({
  routeCase,
  others,
  fallbackElsewhere,
  terms,
  workflow,
  named,
  editable,
  change,
}: {
  readonly routeCase: CaseDraft;
  readonly others: readonly CaseDraft[];
  readonly fallbackElsewhere: boolean;
  readonly terms: readonly string[] | undefined;
  readonly workflow: WorkflowVersion;
  readonly named: ReadonlyMap<string, Named>;
  readonly editable: boolean;
  readonly change: (changed: (routeCase: CaseDraft) => CaseDraft) => void;
}) {
  const offered = workflow.offered.workflows;
  const choices = [
    ...(routeCase.workflow === "" ||
    offered.some((each) => each.versionId === routeCase.workflow)
      ? []
      : [routeCase.workflow]),
    ...offered.map((each) => each.versionId),
  ];
  const pinned = named.get(routeCase.workflow)?.pinned;
  if (!editable) {
    return (
      <Box sx={ROW_SX}>
        <Typography variant="body2">{caseCalled(routeCase)}</Typography>
        <Typography variant="body2">
          {say("workflow.caseWorkflow")}
          {": "}
          {versionSaid(named.get(routeCase.workflow), routeCase.workflow)}
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
        <TextField
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
        <TextField
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
        </TextField>
      )}
      <TextField
        select
        size="small"
        label={say("workflow.caseWorkflow")}
        value={routeCase.workflow}
        onChange={(edit) =>
          change((now) => ({
            ...now,
            workflow: edit.target.value,
            bindings: [],
          }))
        }
      >
        <MenuItem value="">{say("workflow.versionNone")}</MenuItem>
        {choices.map((versionId) => (
          <MenuItem key={versionId} value={versionId}>
            {versionSaid(named.get(versionId), versionId)}
          </MenuItem>
        ))}
      </TextField>
      {pinned === undefined ? null : (
        <PinNote
          pinned={pinned}
          take={(versionId) =>
            change((now) => caseTookVersion(now, versionId, named))
          }
        />
      )}
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
