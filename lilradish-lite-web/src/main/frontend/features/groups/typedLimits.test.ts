import { describe, expect, it } from "vitest";

import CASES from "./typedLimits.cases.json";
import { keyFits } from "./typedLimits";

/**
 * The page's half of the limit, run over the one table the server's half is
 * run over too (`TypedLimitsIntegrationSpec`): a case the two decide
 * differently fails on one side or the other.
 */
interface Case {
  readonly typed: string;
  readonly times?: number;
  readonly accepted: boolean;
}

function typedIn(each: Case): string {
  return each.typed.repeat(each.times ?? 1);
}

describe("keyFits", () => {
  it("holds cases to judge, an empty table agreeing with anything", () => {
    expect(CASES.key.length).toBeGreaterThan(0);
  });

  it.each(CASES.key as readonly Case[])(
    "judges %j as the server's key does",
    (each) => {
      expect(keyFits(typedIn(each))).toBe(each.accepted);
    },
  );
});
