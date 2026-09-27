import { describe, expect, it } from "vitest";

import { answering, refusalOf } from "../testutil/answering";
import { readMeasurements } from "./measurements";
import { NOT_A_PROBLEM_DOCUMENT } from "./problem";

const AS_IT_IS = {
  model: "sorter",
  mode: null,
  productions: 9007199254740991,
  refusedOnReview: 3,
  reviews: 0,
  refusing: 0,
  didNotFit: 1,
};

const IN_A_MODE = { ...AS_IT_IS, mode: "careful", productions: 12 };

const CALLS = {
  model: "sorter",
  wentWrong: 2,
  neverCameBack: 0,
  turnedAway: 5,
};

describe("readMeasurements", () => {
  it("asks for the measurements, and reads every row of both tables as it came", async () => {
    const sent = answering([
      JSON.stringify({ models: [AS_IT_IS, IN_A_MODE], system: [CALLS] }),
      200,
    ]);

    const measured = await readMeasurements(new AbortController().signal);

    expect(sent.mock.calls.map(([address]) => address)).toEqual([
      "/api/measurements",
    ]);
    expect(measured).toEqual({
      models: [AS_IT_IS, IN_A_MODE],
      system: [CALLS],
    });
  });

  it("reads nothing ever called as two empty tables", async () => {
    answering([JSON.stringify({ models: [], system: [] }), 200]);

    expect(await readMeasurements(new AbortController().signal)).toEqual({
      models: [],
      system: [],
    });
  });

  it.each([
    [
      "a mode left out rather than sent as null",
      { ...AS_IT_IS, mode: undefined },
    ],
    ["a mode that is no text", { ...AS_IT_IS, mode: 1 }],
    ["no model", { ...AS_IT_IS, model: undefined }],
    [
      "a count past what a number holds exactly",
      { ...AS_IT_IS, productions: 9007199254740992 },
    ],
    ["a count below none", { ...AS_IT_IS, reviews: -1 }],
    ["a count with a fraction", { ...AS_IT_IS, refusing: 0.5 }],
    ["a count sent as digits", { ...AS_IT_IS, didNotFit: "1" }],
  ])(
    "refuses the whole answer where a model's row has %s",
    async (_case, row) => {
      answering([
        JSON.stringify({ models: [IN_A_MODE, row], system: [CALLS] }),
        200,
      ]);

      const failure = await refusalOf(
        readMeasurements(new AbortController().signal),
      );

      expect(failure.problem).toEqual({
        status: 200,
        code: NOT_A_PROBLEM_DOCUMENT,
      });
    },
  );

  it.each([
    ["no model", { ...CALLS, model: null }],
    ["no count of calls that went wrong", { ...CALLS, wentWrong: undefined }],
    [
      "a count of calls that never came back below none",
      { ...CALLS, neverCameBack: -2 },
    ],
    [
      "a count of turnings away that is no number",
      { ...CALLS, turnedAway: true },
    ],
  ])(
    "refuses the whole answer where this system's row has %s",
    async (_case, row) => {
      answering([
        JSON.stringify({ models: [IN_A_MODE], system: [CALLS, row] }),
        200,
      ]);

      const failure = await refusalOf(
        readMeasurements(new AbortController().signal),
      );

      expect(failure.problem).toEqual({
        status: 200,
        code: NOT_A_PROBLEM_DOCUMENT,
      });
    },
  );

  it.each([
    ["no table of models", { system: [] }],
    ["no table of this system", { models: [] }],
    ["no document at all", []],
  ])("refuses an answer holding %s", async (_case, body) => {
    answering([JSON.stringify(body), 200]);

    const failure = await refusalOf(
      readMeasurements(new AbortController().signal),
    );

    expect(failure.problem).toEqual({
      status: 200,
      code: NOT_A_PROBLEM_DOCUMENT,
    });
  });

  it("carries a refusal of the act as the server's own code", async () => {
    answering([JSON.stringify({ code: "ACT_NOT_PERMITTED" }), 403]);

    const failure = await refusalOf(
      readMeasurements(new AbortController().signal),
    );

    expect(failure.problem).toEqual({ status: 403, code: "ACT_NOT_PERMITTED" });
  });
});
