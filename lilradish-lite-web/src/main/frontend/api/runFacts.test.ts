import { describe, expect, it } from "vitest";

import { cameOutFrom } from "./stepGivenBack";
import { runFrom } from "./groups/{groupId}/runs/{runId}";
import { digitsFrom, numberedRunFrom, spendFrom } from "./runFacts";

describe("spendFrom", () => {
  const spend = {
    sent: "9007199254740993",
    cameBack: "1",
    spent: "9007199254740994",
    cameBackUnknown: false,
  };

  it.each([undefined, "wholly", "partly"] as const)(
    "reads a spend measured %s without rounding",
    (measuredHere) => {
      const body = {
        ...spend,
        ...(measuredHere === undefined ? {} : { measuredHere }),
        ignored: "kept elsewhere",
      };
      const read = spendFrom(body);
      expect(read).toEqual({
        ...spend,
        ...(measuredHere === undefined ? {} : { measuredHere }),
      });
      expect(read).not.toHaveProperty("ignored");
      expect(Object.hasOwn(read!, "measuredHere")).toBe(
        measuredHere !== undefined,
      );
    },
  );

  it.each([
    null,
    undefined,
    [],
    "spend",
    {},
    { ...spend, sent: 2 },
    { ...spend, spent: "01" },
    { ...spend, cameBackUnknown: 0 },
    { ...spend, measuredHere: null },
    { ...spend, measuredHere: "unknown" },
  ])("refuses %j", (body) => {
    expect(spendFrom(body)).toBeNull();
  });
});

describe("numberedRunFrom", () => {
  it.each([0, -0, -1, Number.MAX_SAFE_INTEGER, Number.MIN_SAFE_INTEGER])(
    "keeps the safe-integer domain %s and an empty identifier",
    (number) => {
      const read = numberedRunFrom({ runId: "", number, extra: true });
      expect(read).toEqual({ runId: "", number });
      expect(Object.is(read!.number, number)).toBe(true);
      expect(read).not.toHaveProperty("extra");
    },
  );

  it.each([
    undefined,
    null,
    [],
    "run",
    {},
    { runId: 1, number: 2 },
    { runId: "r", number: "2" },
    { runId: "r", number: 0.5 },
    { runId: "r", number: NaN },
    { runId: "r", number: Infinity },
    { runId: "r", number: Number.MAX_SAFE_INTEGER + 1 },
  ])("reads %j as no numbered run", (body) => {
    expect(numberedRunFrom(body)).toBeNull();
  });

  it("takes one snapshot of a changing inherited identifier", () => {
    let reads = 0;
    const body = Object.create({
      get runId() {
        return ++reads === 1 ? "first" : "second";
      },
    });
    body.number = 7;
    expect(numberedRunFrom(body)).toEqual({ runId: "first", number: 7 });
    expect(reads).toBe(1);
  });

  it("reads both snapshot members before deciding whether the identifier is valid", () => {
    const failure = new Error("imaginary number getter failed");
    const body = {
      runId: null,
      get number() {
        throw failure;
      },
    };
    expect(() => numberedRunFrom(body)).toThrow(failure);
  });

  it("reads a proxy identifier and number once in that order", () => {
    const reads: PropertyKey[] = [];
    const body = new Proxy(
      { runId: "first", number: 7 },
      {
        get(target, key, receiver) {
          reads.push(key);
          return Reflect.get(target, key, receiver);
        },
      },
    );
    expect(numberedRunFrom(body)).toEqual({ runId: "first", number: 7 });
    expect(reads).toEqual(["runId", "number"]);
  });

  it("uses the same single snapshot at the public came-out boundary", () => {
    let reads = 0;
    const fromRun = {
      get runId() {
        if (++reads > 1) throw new Error("second identifier read");
        return "child";
      },
      number: 8,
    };
    expect(cameOutFrom({ field: "receipt", now: "stands", fromRun })).toEqual({
      field: "receipt",
      now: "stands",
      fromRun: { runId: "child", number: 8 },
    });
    expect(reads).toBe(1);
  });

  it("keeps the snapshot at the public run ceiling boundary", () => {
    let reads = 0;
    const heldBy = {
      get runId() {
        return ++reads === 1 ? "root" : "changed";
      },
      number: 7,
    };
    const read = runFrom({
      runId: "child",
      number: 8,
      startedAt: "2026-10-07T08:00:00Z",
      state: "running",
      workflow: {
        versionName: null,
        entryId: "imaginary-workflow",
        name: "Handle an imaginary receipt",
        version: 1,
      },
      spend: { sent: "0", cameBack: "0", spent: "0", cameBackUnknown: false },
      ceiling: { heldBy },
      stateActs: [],
      conversationEvents: [],
      runsAbove: [],
    });
    expect(read?.ceiling).toEqual({ heldBy: { runId: "root", number: 7 } });
    expect(reads).toBe(1);
  });
});
describe("digitsFrom", () => {
  it.each(["0", "1", "9007199254740993", "9".repeat(300)])(
    "keeps %s as exact digits",
    (digits) => {
      expect(digitsFrom(digits)).toBe(digits);
    },
  );

  it.each([
    undefined,
    null,
    false,
    0,
    1,
    "",
    "01",
    "-1",
    "+1",
    "1.0",
    " 1",
    "1 ",
    "١",
  ])("rejects %j", (value) => {
    expect(digitsFrom(value)).toBeNull();
  });

  it("rejects a final line break outside the digits", () => {
    expect(digitsFrom("1\n")).toBeNull();
  });
});
