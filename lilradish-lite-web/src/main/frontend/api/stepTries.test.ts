import { describe, expect, it } from "vitest";

import { readByFrom, tryFrom, tryDetailsFrom } from "./stepTries";

describe("readByFrom", () => {
  it.each([
    { step: "later" },
    { output: "receipt.total" },
    { step: "" },
    { output: "" },
  ])("keeps exactly one reader %j", (reader) => {
    expect(readByFrom({ ...reader, ignored: true })).toEqual(reader);
    expect(readByFrom({ ...reader, ignored: true })).not.toHaveProperty(
      "ignored",
    );
  });

  it.each([
    undefined,
    null,
    [],
    "reader",
    {},
    { step: "later", output: "receipt" },
    { step: "later", output: undefined },
    { step: null },
    { output: 1 },
  ])("refuses %j", (reader) => {
    expect(readByFrom(reader)).toBeNull();
  });

  it("uses the same exclusive reader at a try's code-error boundary", () => {
    const body = {
      number: 1,
      wentWrongPresent: false,
      returnedPresent: false,
      declinedPresent: false,
      unreadableResponsePresent: false,
      modelReplyPresent: false,
      beyond: false,
      producedBy: { kind: "code" },
      values: [],
      review: {
        asked: false,
        declinedPresent: false,
        unreadableResponsePresent: false,
        modelReplyPresent: false,
      },
      ended: "errored",
      cost: { callsAModel: false },
      erroredFor: {
        reason: "gives_otherwise",
        field: "receipt",
        readBy: { output: "total" },
      },
    };
    expect(tryFrom(body)?.erroredFor).toEqual(body.erroredFor);
    expect(
      tryFrom({
        ...body,
        erroredFor: {
          ...body.erroredFor,
          readBy: { output: "total", step: "later" },
        },
      }),
    ).toBeNull();
  });
});
describe("a try's length refusal", () => {
  const held = {
    run: { runId: "held-run", number: 7 },
    stepId: "held-step",
    stepName: "check_receipt",
  };
  const decision = {
    outcome: "refused",
    why: "Made again, shorter.",
    whyLines: "several",
    lengthUnits: "9007199254740993",
    excessUnits: "1",
    lengthHeld: held,
  };
  const body = {
    number: 1,
    beyond: false,
    wentWrongPresent: false,
    returnedPresent: false,
    declinedPresent: false,
    unreadableResponsePresent: false,
    modelReplyPresent: false,
    producedBy: { kind: "code" },
    review: {
      asked: false,
      declinedPresent: false,
      unreadableResponsePresent: false,
      modelReplyPresent: false,
    },
    ended: "refused_for_length",
    cost: { callsAModel: false },
    values: [
      {
        field: "receipt",
        now: "refused_for_length",
        value: "Imaginary receipt",
        decision,
      },
    ],
  };

  it("keeps the historical quantities as digits and the exact held-step address", () => {
    const read = tryFrom(body);
    expect(read?.values[0]?.decision).toEqual(decision);
    expect(read?.values[0]?.decision?.lengthUnits).not.toBe(9007199254740993n);
    expect(read?.values[0]?.decision?.lengthHeld?.run.runId).toBe("held-run");
  });

  it.each([
    { lengthUnits: undefined },
    { excessUnits: undefined },
    { lengthHeld: undefined },
    { lengthUnits: "01" },
    { excessUnits: "0" },
    { lengthHeld: { ...held, run: null } },
    { outcome: "assured" },
  ])("rejects an incomplete or contradictory length refusal %j", (changed) => {
    expect(
      tryFrom({
        ...body,
        values: [{ ...body.values[0], decision: { ...decision, ...changed } }],
      }),
    ).toBeNull();
    expect(tryFrom(body)?.values[0]?.decision).toEqual(decision);
  });
});

describe("summary and numbered materials", () => {
  it.each([false, true])(
    "refuses two carriers for one declined answer in review=%s",
    (reviewing) => {
      const raw = {
        text: "Invented original refusal answer",
        altered: true,
        truncated: true,
      };
      const declined = { content: raw };
      const evidence = { declined, modelReply: raw };
      expect(
        tryDetailsFrom(
          reviewing
            ? { number: 1, review: evidence }
            : { number: 1, ...evidence },
        ),
      ).toBeNull();
      const single = reviewing
        ? { number: 1, review: { declined } }
        : { number: 1, declined };
      expect(tryDetailsFrom(single)).toEqual(single);
    },
  );

  it.each([false, true])(
    "keeps an empty original reply beside refusal words in review=%s",
    (reviewing) => {
      const evidence = {
        declined: {
          words: { text: "Invented refusal", altered: false, truncated: true },
          wordsLines: "several",
        },
        modelReply: { text: "", altered: true, truncated: false },
      };
      const body = reviewing
        ? { number: 1, review: evidence }
        : { number: 1, ...evidence };
      expect(tryDetailsFrom(body)).not.toBeNull();
      expect(tryDetailsFrom(body)).toMatchObject({
        ...(reviewing
          ? { review: { modelReply: evidence.modelReply } }
          : { modelReply: evidence.modelReply }),
      });
    },
  );

  const flags = {
    wentWrongPresent: false,
    returnedPresent: false,
    declinedPresent: false,
    unreadableResponsePresent: false,
    modelReplyPresent: false,
  };
  const review = {
    asked: false,
    declinedPresent: false,
    unreadableResponsePresent: false,
    modelReplyPresent: false,
  };
  const summary = {
    number: 1,
    beyond: false,
    producedBy: { kind: "code" },
    values: [],
    review,
    ended: "errored",
    cost: { callsAModel: false },
    ...flags,
  };

  it.each(Object.keys(flags))(
    "requires the explicit %s summary fact",
    (member) => {
      const missing: Record<string, unknown> = { ...summary };
      delete missing[member];
      expect(tryFrom(missing)).toBeNull();
      expect(tryFrom({ ...summary, [member]: "false" })).toBeNull();
    },
  );

  it.each([
    "declinedPresent",
    "unreadableResponsePresent",
    "modelReplyPresent",
  ])("requires the explicit review %s fact", (member) => {
    const missing: Record<string, unknown> = { ...review };
    delete missing[member];
    expect(tryFrom({ ...summary, review: missing })).toBeNull();
  });

  it.each(["returned", "declined", "unreadableResponse", "modelReply"])(
    "rejects inline %s in a summary",
    (member) => {
      expect(tryFrom({ ...summary, [member]: {} })).toBeNull();
    },
  );

  it.each(["declined", "unreadableResponse", "modelReply"])(
    "rejects inline review %s in a summary",
    (member) => {
      expect(
        tryFrom({ ...summary, review: { ...review, [member]: {} } }),
      ).toBeNull();
    },
  );

  it.each(["", '{"imaginary":true}', "**Original**\n\\u202e"])(
    "keeps the complete model reply %j without parsing or removing an empty string",
    (text) => {
      const body = {
        number: 1,
        modelReply: { text, altered: false, truncated: false },
        review: { modelReply: { text, altered: true, truncated: true } },
      };
      expect(tryDetailsFrom(body)).toEqual(body);
    },
  );

  it("keeps empty code evidence and its exact truncation fact", () => {
    const body = {
      number: 1,
      returned: "",
      wentWrong: { detail: "", cut: true },
    };
    expect(tryDetailsFrom(body)).toEqual(body);
    expect(
      tryDetailsFrom({ number: 1, wentWrong: { withheld: true } }),
    ).toBeNull();
    expect(
      tryFrom({
        ...summary,
        wentWrong: { detail: "Imaginary large fault", cut: false },
      }),
    ).toBeNull();
    expect(
      tryFrom({
        ...summary,
        producedBy: { kind: "model", model: "Imaginary model" },
        wentWrong: { withheld: true },
      })?.wentWrong,
    ).toEqual({ withheld: true });
  });

  it.each([false, true])(
    "keeps all four unreadable facts including stopped=%s for both purposes",
    (stopped) => {
      const raw = {
        text: "Imaginary raw envelope",
        altered: false,
        truncated: true,
        stopped,
      };
      const body = {
        number: 1,
        unreadableResponse: raw,
        review: { unreadableResponse: raw },
      };
      expect(tryDetailsFrom(body)).toEqual(body);
      const { stopped: ignored, ...missing } = raw;
      expect(ignored).toBe(stopped);
      expect(
        tryDetailsFrom({ number: 1, unreadableResponse: missing }),
      ).toBeNull();
      expect(
        tryDetailsFrom({
          number: 1,
          unreadableResponse: { ...raw, extra: true },
        }),
      ).toBeNull();
    },
  );

  it.each([
    undefined,
    null,
    [],
    0,
    { number: 0 },
    { number: -1 },
    { number: 1.1 },
    { number: 1000000000 },
    { number: "1" },
    { number: 1, values: [] },
    { number: 1, modelReply: null },
    { number: 1, modelReply: { text: "", altered: false } },
    { number: 1, review: { asked: false } },
  ])("refuses malformed numbered materials %j", (body) => {
    expect(tryDetailsFrom(body)).toBeNull();
  });

  it("accepts a number-only result and an empty review with no invented raw evidence", () => {
    expect(tryDetailsFrom({ number: 999999999 })).toEqual({
      number: 999999999,
    });
    expect(tryDetailsFrom({ number: 1, review: {} })).toEqual({
      number: 1,
      review: {},
    });
    expect(tryFrom({ ...summary, search: {} })).not.toBeNull();
    expect(
      tryFrom({
        ...summary,
        search: { question: "Imaginary inline history", lines: "several" },
      }),
    ).toBeNull();
  });
});
