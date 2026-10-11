import { describe, expect, it } from "vitest";

import { conversationEventFrom } from "./conversationEvents";

const dated = { id: "imaginary-act", at: "2026-10-07T08:00:00Z" };
const run = { runId: "imaginary-run", number: 7 };
const by = { userId: "imaginary-reader", displayName: "Ari" };
const onStep = { ...dated, run, stepId: "imaginary-step" };
const decision = {
  field: "receipt",
  outcome: "refused",
  why: "Keep the imaginary receipt",
  whyLines: "several",
};

describe("conversationEventFrom", () => {
  it("retains the historically held display name without replacing the held step's machine identity", () => {
    const event = {
      ...onStep,
      kind: "refused_for_length",
      by,
      number: 1,
      decisions: [
        { ...decision, lengthUnits: "9007199254740993", excessUnits: "1" },
      ],
      held: {
        run: { runId: "child", number: 8 },
        stepId: "held",
        stepName: "check_receipt",
        label: "領収書を確認\u202e",
      },
    };

    expect(conversationEventFrom(event)).toEqual(event);
    const { label: _label, ...withoutLabel } = event.held;
    expect(conversationEventFrom({ ...event, held: withoutLabel })).toEqual({
      ...event,
      held: withoutLabel,
    });
  });

  it.each([null, 7, false, [], { value: "Shown" }])(
    "refuses malformed held display metadata rather than accepting a partial fact: %j",
    (label) => {
      expect(
        conversationEventFrom({
          ...onStep,
          kind: "refused_for_length",
          by,
          number: 1,
          decisions: [{ ...decision, lengthUnits: "10", excessUnits: "1" }],
          held: {
            run: { runId: "child", number: 8 },
            stepId: "held",
            stepName: "check_receipt",
            label,
          },
        }),
      ).toBeNull();
    },
  );
  it.each([
    { ...onStep, kind: "step_reached" },
    {
      ...onStep,
      kind: "answered_by_hand",
      by,
      number: 1,
      values: [
        { field: "receipt", value: "An imaginary receipt was checked." },
      ],
      why: "Checked the label",
    },
    {
      ...onStep,
      kind: "reviewed",
      by,
      number: 1,
      decisions: [{ field: "receipt", outcome: "assured" }, decision],
    },
    {
      ...onStep,
      kind: "refused_for_length",
      by,
      number: 1,
      decisions: [{ ...decision, lengthUnits: "10", excessUnits: "1" }],
      held: {
        run: { runId: "child", number: 8 },
        stepId: "held",
        stepName: "check_receipt",
      },
    },
    ...[
      "ceiling_changed",
      "ceiling_asked",
      "ceiling_approved",
      "ceiling_refused",
      "ceiling_withdrawn",
    ].map((kind) => ({
      ...dated,
      kind,
      run,
      by,
      from: "9007199254740993",
      to: "9007199254740994",
    })),
    { ...dated, kind: "ceiling_superseded", run, from: "9007199254740993" },
    { ...dated, kind: "stopped", run, by },
    { ...dated, kind: "opened_again", run, by },
    {
      ...dated,
      kind: "stopped_by_ceiling",
      run,
      ceilings: [run, { runId: "child", number: 8 }],
    },
  ])("reads a $kind event and leaves an unknown outer member out", (event) => {
    const read = conversationEventFrom({ ...event, ignored: true });
    expect(read).toEqual(event);
    expect(read).not.toHaveProperty("ignored");
  });

  it.each([
    undefined,
    null,
    [],
    "event",
    {},
    { ...onStep, kind: "future_act" },
    { ...onStep, kind: "step_reached", run: null },
    { ...onStep, kind: "step_reached", stepId: null },
    { ...onStep, kind: "answered_by_hand", by, number: 0, values: [] },
    { ...onStep, kind: "reviewed", by, number: 1, decisions: [] },
    {
      ...onStep,
      kind: "reviewed",
      by,
      number: 1,
      decisions: [{ ...decision, why: " " }],
    },
    {
      ...onStep,
      kind: "reviewed",
      by,
      number: 1,
      decisions: [{ field: "receipt", outcome: "assured", why: "extra" }],
    },
    {
      ...onStep,
      kind: "reviewed",
      by,
      number: 1,
      decisions: [{ ...decision, whyLines: null }],
    },
    {
      ...onStep,
      kind: "refused_for_length",
      by,
      number: 1,
      decisions: [{ field: "receipt", outcome: "assured" }],
      held: { run, stepId: "held", stepName: "check_receipt" },
    },
    { ...dated, kind: "ceiling_changed", run, by, from: "01" },
    { ...dated, kind: "ceiling_superseded", run, by },
    { ...dated, kind: "stopped_by_ceiling", run, ceilings: [] },
    { ...dated, kind: "stopped_by_ceiling", run, ceilings: [run], by },
  ])("rejects an unreadable event %j", (body) => {
    expect(conversationEventFrom(body)).toBeNull();
  });

  it("retains empty hand values and a value's earlier shape without treating it as a live value", () => {
    const empty = {
      ...onStep,
      kind: "answered_by_hand",
      by,
      number: 1,
      values: [],
    };
    expect(conversationEventFrom(empty)).toEqual(empty);
    const read = conversationEventFrom({
      ...empty,
      values: [{ field: "receipt", earlierShape: true, value: '{"old":1}' }],
    });
    expect(read).toEqual({
      ...empty,
      values: [{ field: "receipt", asKept: '{"old":1}' }],
    });
    expect(read).not.toHaveProperty("values.0.value");
  });

  it("does not manufacture optional bounds of a ceiling change", () => {
    const read = conversationEventFrom({
      ...dated,
      kind: "ceiling_changed",
      run,
      by,
    });
    expect(read).toEqual({ ...dated, kind: "ceiling_changed", run, by });
    expect(read).not.toHaveProperty("from");
    expect(read).not.toHaveProperty("to");
  });

  it.each([
    {},
    { lengthUnits: "1" },
    { excessUnits: "1" },
    { lengthUnits: "-1", excessUnits: "1" },
    { lengthUnits: "1", excessUnits: "0" },
    { lengthUnits: 1, excessUnits: "1" },
  ])(
    "refuses missing or unusable historical length quantities %j",
    (quantities) => {
      const event = {
        ...onStep,
        kind: "refused_for_length",
        by,
        number: 1,
        held: { run, stepId: "held", stepName: "check_receipt" },
        decisions: [{ ...decision, ...quantities }],
      };
      expect(conversationEventFrom(event)).toBeNull();
      expect(
        conversationEventFrom({
          ...event,
          decisions: [{ ...decision, lengthUnits: "0", excessUnits: "1" }],
        }),
      ).toEqual({
        ...event,
        decisions: [{ ...decision, lengthUnits: "0", excessUnits: "1" }],
      });
    },
  );
});
