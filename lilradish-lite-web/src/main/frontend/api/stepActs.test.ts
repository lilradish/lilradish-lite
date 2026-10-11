import { describe, expect, it } from "vitest";

import { givesOtherwiseFrom, stateRefusalFrom } from "./stepActs";

describe("stateRefusalFrom and givesOtherwiseFrom", () => {
  it("keeps unknown future spellings for the wording layer without unknown outer fields", () => {
    expect(
      stateRefusalFrom({
        act: "future_act",
        refusal: "FUTURE_REFUSAL",
        ignored: true,
      }),
    ).toEqual({ act: "future_act", refusal: "FUTURE_REFUSAL" });
  });

  it.each([{ step: "later" }, { output: "receipt.total" }, undefined])(
    "reads a mismatch's reader %j",
    (readBy) => {
      const body = {
        act: "answer",
        refusal: "CODE_STEP_GIVES_OTHERWISE",
        field: "receipt",
        ...(readBy === undefined ? {} : { readBy }),
      };
      expect(stateRefusalFrom(body)).toEqual({
        act: "answer",
        refusal: "CODE_STEP_GIVES_OTHERWISE",
      });
      expect(givesOtherwiseFrom(body)).toEqual({
        field: "receipt",
        ...(readBy === undefined ? {} : { readBy }),
      });
    },
  );

  it.each([
    null,
    {},
    { step: "later", output: "receipt" },
    { step: "later", output: undefined },
    { step: 7 },
    { output: null },
  ])("rejects an invalid or ambiguous mismatch reader %j", (readBy) => {
    expect(
      givesOtherwiseFrom({
        act: "answer",
        refusal: "CODE_STEP_GIVES_OTHERWISE",
        field: "receipt",
        readBy,
      }),
    ).toBeNull();
  });

  it("keeps mismatch detail separate from the refusal and validates its own fields", () => {
    const body = {
      act: "answer",
      refusal: "RUN_STOPPED",
      field: 7,
      readBy: null,
    };
    const read = stateRefusalFrom(body);
    expect(read).toEqual({ act: "answer", refusal: "RUN_STOPPED" });
    expect(read).not.toHaveProperty("givesOtherwise");
    expect(givesOtherwiseFrom(body)).toBeNull();
  });

  it.each([
    null,
    [],
    {},
    { act: 1, refusal: "RUN_STOPPED" },
    { act: "answer", refusal: 1 },
  ])("rejects %j", (body) => {
    expect(stateRefusalFrom(body)).toBeNull();
  });
  it.each([null, [], {}, { field: 7 }, { readBy: { step: "later" } }])(
    "rejects an unnamed or malformed code field %j",
    (body) => {
      expect(givesOtherwiseFrom(body)).toBeNull();
    },
  );
});
