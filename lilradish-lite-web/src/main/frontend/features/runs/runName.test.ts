import { describe, expect, it } from "vitest";

import CASES from "./runName.cases.json";
import { runNameFits } from "./runName";

/**
 * The page's half of the limit, run over the one table the server's half is
 * run over too (`TypedLimitsIntegrationSpec`).
 */
interface Case {
  readonly typed: string;
  readonly times?: number;
  readonly accepted: boolean;
}

describe("runNameFits", () => {
  it("holds cases to judge, an empty table agreeing with anything", () => {
    expect(CASES.name.length).toBeGreaterThan(0);
  });

  it.each(CASES.name as readonly Case[])(
    "judges %j as the server's run name does",
    (each) => {
      expect(runNameFits(each.typed.repeat(each.times ?? 1))).toBe(
        each.accepted,
      );
    },
  );
});
