import { describe, expect, it } from "vitest";

import { lengthFrom } from "./stepWhereabouts";

const VALUE = {
  valueId: "value-1",
  inputs: ["ticket", "context.summary"],
  units: "9007199254740993",
  from: {
    kind: "step",
    runId: "run-1",
    runNumber: 1,
    stepId: "step-1",
    name: "summarise",
    path: "summary",
  },
  canBeMadeAgain: true,
  fromSubRun: false,
  triesSpent: false,
  mayRefuse: true,
  used: [],
};

const LENGTH = {
  holdId: "hold-1",
  model: "small",
  limitUnits: "9223372036854775807",
  wholeUnits: "9223372036854775808",
  excessUnits: "1",
  fixedUnits: "0",
  tellsWhatHappened: true,
  values: [VALUE],
};

describe("a held request's length", () => {
  it("keeps a used step's display label beside its unchanged identity, and refuses malformed labels", () => {
    const used = {
      runId: "run-2",
      runNumber: 2,
      stepId: "step-2",
      name: "file_it",
      label: "確認\u202e",
      choseBranch: false,
    };
    const body = {
      ...LENGTH,
      values: [{ ...VALUE, mayRefuse: false, used: [used] }],
    };
    expect(lengthFrom(body)?.values[0]?.used).toEqual([used]);
    for (const label of [null, 1, false, [], {}]) {
      expect(
        lengthFrom({
          ...body,
          values: [{ ...body.values[0]!, used: [{ ...used, label }] }],
        }),
      ).toBeNull();
    }
  });
  it("keeps a historical contribution in exact digits, independently of the current contribution", () => {
    expect(
      lengthFrom({
        ...LENGTH,
        values: [{ ...VALUE, previousUnits: "9007199254741003" }],
      })?.values[0]?.previousUnits,
    ).toBe("9007199254741003");
    for (const previousUnits of [null, 1, "01", "-1", "1.0"]) {
      expect(
        lengthFrom({ ...LENGTH, values: [{ ...VALUE, previousUnits }] }),
      ).toBeNull();
    }
  });

  it("keeps exact digits and all places filled by one source", () => {
    expect(lengthFrom(LENGTH)).toEqual(LENGTH);
    expect(lengthFrom({ ...LENGTH, values: [] })).toEqual({
      ...LENGTH,
      values: [],
    });
  });

  it.each(["limitUnits", "wholeUnits", "excessUnits", "fixedUnits"])(
    "refuses malformed %s",
    (field) => {
      for (const malformed of [undefined, null, 1, "01", "-1", "1.0", "one"]) {
        expect(lengthFrom({ ...LENGTH, [field]: malformed })).toBeNull();
      }
    },
  );

  it("reads a request that fits the current model while still awaiting sending, and refuses malformed or contradictory rows", () => {
    expect(
      lengthFrom({ ...LENGTH, excessUnits: "0", values: [] })?.excessUnits,
    ).toBe("0");
    for (const changed of [
      { inputs: [] },
      { inputs: ["context..summary"] },
      { units: "01" },
      { from: null },
      { valueId: undefined },
      { canBeMadeAgain: false },
      { fromSubRun: true },
      { triesSpent: undefined },
      {
        used: [
          {
            runId: "run-2",
            runNumber: 2,
            stepId: "step-2",
            name: "file_it",
            choseBranch: false,
          },
        ],
      },
    ]) {
      expect(
        lengthFrom({ ...LENGTH, values: [{ ...VALUE, ...changed }] }),
      ).toBeNull();
    }
  });

  it("reads fixed inputs without a value identifier and used producers without an action", () => {
    const { valueId: _valueId, ...withoutValueId } = VALUE;
    const fixed = {
      ...withoutValueId,
      from: { kind: "constant" },
      canBeMadeAgain: false,
      mayRefuse: false,
    };
    const used = {
      ...VALUE,
      mayRefuse: false,
      used: [
        {
          runId: "run-2",
          runNumber: 2,
          stepId: "step-2",
          name: "route_it",
          choseBranch: true,
        },
      ],
    };
    expect(lengthFrom({ ...LENGTH, values: [fixed, used] })?.values).toEqual([
      { ...fixed },
      used,
    ]);
  });
});
