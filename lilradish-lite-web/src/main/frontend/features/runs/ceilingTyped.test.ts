import { describe, expect, it } from "vitest";

import CASES from "./ceilingTyped.cases.json";
import { ceilingFits } from "./ceilingTyped";

/**
 * The page's half of the limit, run over the one table the server's half is
 * run over too (`TypedLimitsIntegrationSpec`).
 */
interface Case {
  readonly typed: string;
  readonly accepted: boolean;
}

describe("ceilingFits", () => {
  it("holds cases to judge, an empty table agreeing with anything", () => {
    expect(CASES.ceiling.length).toBeGreaterThan(0);
  });

  it.each(CASES.ceiling as readonly Case[])(
    "judges %j as the server's ceiling does",
    (each) => {
      expect(ceilingFits(each.typed)).toBe(each.accepted);
    },
  );
});
