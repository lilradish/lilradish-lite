import { describe, expect, it, onTestFinished, vi } from "vitest";

import type { DeclaredField } from "../../api/declaration";
import type { WorkflowVersion } from "../../api/groups/{groupId}/{kind}/{entryId}/versions/{versionId}";
import * as workflowDrafts from "./workflowDrafts";
import {
  boundTo,
  flowOf,
  namedVersions,
  removedStep,
  movedStep,
  type SourceDraft,
  sentFlowOf,
  type FlowDraft,
} from "./workflowDrafts";
import { flowChangeAt, outputRowsOf, type FlowChange } from "./workflowUnsaved";

const SUMMARY: DeclaredField = {
  fieldId: "f1",
  name: "summary",
  kind: "text",
  longest: 1000,
  many: false,
  mustBeGiven: true,
};

const WORKFLOW: WorkflowVersion = {
  revision: 3,
  takes: [],
  gives: [SUMMARY],
  steps: ["tidy", "file", "send"].map((name, index) => ({
    stepId: `s${index + 1}`,
    name,
    runs: { kind: "code_step", codeStep: `${name}_it` },
    producer: { kind: "code" },
    bindings: [],
  })),
  outputs: [],
  keepsOwnCeiling: false,
  raiseNeedsApproval: false,
  mayBeHelped: false,
  problems: [],
  sendsPast: [],
  offered: { codeSteps: [], searchWays: [], models: [] },
  terms: new Map(),
};

const NAMED = namedVersions(WORKFLOW);

const SAVED = flowOf(WORKFLOW);

function changeAt(
  flow: FlowDraft,
  keptSteps: readonly string[] = [],
  saved: FlowDraft = SAVED,
): FlowChange {
  const sent = sentFlowOf(flow, WORKFLOW, NAMED);
  const savedSent = sentFlowOf(saved, WORKFLOW, NAMED);
  return flowChangeAt({
    flow,
    saved,
    sent,
    savedSent,
    stepsUnsaved:
      JSON.stringify(sent.steps) !== JSON.stringify(savedSent.steps),
    keptSteps: new Set(keptSteps),
    workflow: WORKFLOW,
    named: NAMED,
  });
}

function renamed(flow: FlowDraft, key: string): FlowDraft {
  return {
    ...flow,
    steps: flow.steps.map((step) =>
      step.key === key ? { ...step, label: "Renamed" } : step,
    ),
  };
}

function filled(flow: FlowDraft, target: string): FlowDraft {
  return {
    ...flow,
    outputs: boundTo(flow.outputs, target, {
      from: "constant",
      typed: '"Written out"',
    }),
  };
}

describe("outputRowsOf", () => {
  it("shares source paths across a hundred outputs and reads revised declarations in the next batch", () => {
    const fields: DeclaredField[] = Array.from({ length: 100 }, (_, index) => ({
      ...SUMMARY,
      lines: "one",
      fieldId: `field-${index}`,
      name: `field_${index}`,
    }));
    const workflow: WorkflowVersion = {
      ...WORKFLOW,
      gives: fields,
      steps: [
        {
          stepId: "writer",
          name: "writer",
          runs: { kind: "code_step", codeStep: "write_fields" },
          producer: { kind: "code" },
          bindings: [],
        },
      ],
      outputs: fields.map((field, index) => ({
        bindingId: `output-${index}`,
        target: field.name,
        source: { step: 0, path: "field_0" },
      })),
      offered: {
        ...WORKFLOW.offered,
        codeSteps: [{ name: "write_fields", takes: [], gives: fields }],
      },
    };
    let traversed = 0;
    const batch = workflowDrafts.bindingFlagsFor;
    const measured = vi
      .spyOn(workflowDrafts, "bindingFlagsFor")
      .mockImplementation((flow, position, sources) =>
        batch(
          flow,
          position,
          sources.map((source) => ({
            ...source,
            fields: source.fields?.map((field) => ({
              ...field,
              get mustBeGiven() {
                traversed += 1;
                return field.mustBeGiven;
              },
            })),
          })),
        ),
      );
    onTestFinished(() => measured.mockRestore());
    const flow = flowOf(workflow);
    const named = namedVersions(workflow);
    const initial = outputRowsOf(flow, workflow, named);
    expect(traversed).toBe(100);
    expect(
      initial.sources.map((source) => ({
        value: source.value,
        fields: source.fields?.map((field) => field.name),
      })),
    ).toEqual([{ value: "writer", fields: fields.map((field) => field.name) }]);
    expect(
      initial.rows.map((row) => ({
        path: row.path,
        binding: row.binding,
        flags: row.flags,
      })),
    ).toEqual(
      fields.map((field, index) => ({
        path: field.name,
        binding: flow.outputs[index],
        flags: [],
      })),
    );
    const changed = {
      ...flow,
      outputs: boundTo(flow.outputs, "field_0", {
        from: "step",
        stepKey: "writer",
        path: "field_1",
      }),
    };
    traversed = 0;
    const edited = outputRowsOf(changed, workflow, named);
    expect(traversed).toBe(100);
    expect(
      edited.rows.map((row) => ({
        path: row.path,
        binding: row.binding,
        flags: row.flags,
      })),
    ).toEqual(
      fields.map((field, index) => ({
        path: field.name,
        binding:
          index === 0
            ? {
                target: "field_0",
                source: { from: "step", stepKey: "writer", path: "field_1" },
              }
            : flow.outputs[index],
        flags: [],
      })),
    );
    const revised: WorkflowVersion = {
      ...workflow,
      offered: {
        ...workflow.offered,
        codeSteps: [
          {
            name: "write_fields",
            takes: [],
            gives: fields.map((field, index) =>
              index === 1
                ? { ...field, mustBeGiven: false, lines: "several" }
                : field,
            ),
          },
        ],
      },
      gives: fields.map((field) => ({ ...field, lines: "one" })),
    };
    traversed = 0;
    const reread = outputRowsOf(changed, revised, named);
    expect(traversed).toBe(100);
    expect(reread.rows.map((row) => row.flags)).toEqual(
      fields.map((_field, index) =>
        index === 0
          ? [
              "contentProblem.source_does_not_fit",
              "contentProblem.source_may_be_empty",
            ]
          : [],
      ),
    );
    expect(outputRowsOf(changed, workflow, named).rows).toEqual(edited.rows);
    expect(flow.outputs).toEqual(initial.rows.map((row) => row.binding));
  });
});

describe("flowChangeAt", () => {
  it.each([
    ["the first step changed", renamed(SAVED, "s2"), ["s3"], "s2"],
    ["the first step holding a kept change", SAVED, ["s3", "s2"], "s2"],
    [
      "a step changed before one holding a kept change",
      renamed(SAVED, "s3"),
      ["s1"],
      "s3",
    ],
  ] as const)("takes the reader to %s", (_case, flow, kept, key) => {
    expect(changeAt(flow, kept)).toEqual({
      at: "step",
      key,
      ...(_case === "the first step holding a kept change"
        ? {}
        : { control: { part: "label" } }),
    });
  });

  it("takes the reader to the steps as a whole where some were only taken out, and to no output", () => {
    const change = changeAt(removedStep(SAVED, 1));

    expect(change).toEqual({ at: "steps" });
  });

  it.each([
    ["a value it gives back filled otherwise", "summary", "summary"],
    [
      "a value it no longer gives back filled otherwise, to their heading",
      "gone",
      null,
    ],
  ] as const)(
    "takes the reader, where no step changed, to %s",
    (_case, target, path) => {
      expect(changeAt(filled(SAVED, target))).toEqual({ at: "output", path });
    },
  );

  it.each([
    [
      "source kind",
      { from: "input", path: "summary" },
      { from: "step", stepKey: "s1", path: "summary" },
      "from",
    ],
    [
      "source step",
      { from: "step", stepKey: "s1", path: "summary" },
      { from: "step", stepKey: "s3", path: "summary" },
      "from",
    ],
    [
      "path only",
      { from: "input", path: "summary" },
      { from: "input", path: "note" },
      "which",
    ],
    [
      "typed constant",
      { from: "constant", typed: '"Saved"' },
      { from: "constant", typed: '"Changed"' },
      "typed",
    ],
    [
      "server-read constant",
      { from: "constant", typed: '"Saved"', read: '"Saved"' },
      { from: "constant", typed: '"Saved"', read: '"Changed"' },
      "typed",
    ],
  ] satisfies readonly (readonly [string, SourceDraft, SourceDraft, string])[])(
    "locates the changed %s control in a step binding and an output",
    (_case, before, after, control) => {
      const saved = {
        ...SAVED,
        outputs: [{ target: "summary", source: before }],
        steps: SAVED.steps.map((step) =>
          step.key === "s2"
            ? { ...step, bindings: [{ target: "summary", source: before }] }
            : step,
        ),
      };
      const stepChanged = {
        ...saved,
        steps: saved.steps.map((step) =>
          step.key === "s2"
            ? { ...step, bindings: [{ target: "summary", source: after }] }
            : step,
        ),
      };
      expect(changeAt(stepChanged, [], saved)).toEqual({
        at: "step",
        key: "s2",
        control: { part: "binding", path: "summary", bindingControl: control },
      });
      expect(
        changeAt(
          { ...saved, outputs: [{ target: "summary", source: after }] },
          [],
          saved,
        ),
      ).toEqual({ at: "output", path: "summary", control });
    },
  );

  it("locates a moved row without claiming a particular control changed", () => {
    expect(changeAt(movedStep(SAVED, 1, -1))).toEqual({
      at: "step",
      key: "s1",
    });
  });
});
