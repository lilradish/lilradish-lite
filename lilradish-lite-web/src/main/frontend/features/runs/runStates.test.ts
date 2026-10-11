import { describe, expect, it, vi } from "vitest";

import type { Run } from "../../api/groups/{groupId}/runs/{runId}";
import { numberedRunFrom } from "../../api/runFacts";
import { runWhereSaid, stoppedByCeilingsSaid } from "./runStates";

const RUN: Run = {
  runId: "current",
  number: 7,
  runsAbove: [],
  workflow: {
    versionName: null,
    entryId: "workflow",
    name: "Demo Sample",
    version: 1,
  },
  startedAt: "2026-09-24T08:00:00Z",
  state: "stopped",
  stopped: {
    at: "2026-09-24T08:15:00Z",
    by: { userId: "reader", displayName: "Avo" },
  },
  spend: { sent: "0", cameBack: "0", spent: "0", cameBackUnknown: false },
  ceiling: { raiseNeedsApproval: false },
  stateActs: new Set(),
  conversationEvents: [],
};

describe("runWhereSaid", () => {
  it("shows the current done or running state even when a historical stop remains", () => {
    expect(runWhereSaid({ ...RUN, state: "done" })).toBe("Done");
    expect(runWhereSaid({ ...RUN, state: "running" })).toBe("Running");
    expect(runWhereSaid(RUN, new Date("2026-09-24T09:00:00Z"))).toMatch(
      /^Stopped by/,
    );
  });

  it("names each child ceiling once alongside its own ceiling", () => {
    const words = stoppedByCeilingsSaid(
      RUN.runId,
      [
        { runId: RUN.runId, number: 7 },
        { runId: "child-a", number: 8 },
        { runId: "child-b", number: 9 },
      ],
      "at noon",
    );
    expect(words).toBe(
      "Stopped: its usage limit and the limits of runs 8 and 9 reached, at noon",
    );
    expect(words).not.toContain("run 7");
  });
});

describe("stoppedByCeilingsSaid reader numbers", () => {
  async function inLanguage(
    locale: string,
    verify: (read: typeof stoppedByCeilingsSaid) => void,
  ): Promise<void> {
    const language = Object.getOwnPropertyDescriptor(navigator, "language");
    Object.defineProperty(navigator, "language", {
      configurable: true,
      value: locale,
    });
    vi.resetModules();
    try {
      const { stoppedByCeilingsSaid: read } = await import("./runStates");
      verify(read);
    } finally {
      if (language === undefined) {
        Reflect.deleteProperty(navigator, "language");
      } else {
        Object.defineProperty(navigator, "language", language);
      }
      vi.resetModules();
    }
  }

  it.each(
    (
      [
        [1234, "1.234", ["1", "234"]],
        [-1234, "-1.234", ["-1", "234"]],
        [-0, "0", ["0"]],
        [
          Number.MAX_SAFE_INTEGER,
          "9.007.199.254.740.991",
          ["9", "007", "199", "254", "740", "991"],
        ],
        [
          Number.MIN_SAFE_INTEGER,
          "-9.007.199.254.740.991",
          ["-9", "007", "199", "254", "740", "991"],
        ],
      ] as const
    ).flatMap(([number, german, french]) =>
      ["de-DE", "fr-FR"].flatMap((locale) =>
        [false, true].map((own) => ({ number, german, french, locale, own })),
      ),
    ),
  )(
    "formats one other run $number for $locale with own ceiling $own",
    async ({ number, german, french, locale, own }) => {
      await inLanguage(locale, (read) => {
        const other = numberedRunFrom({ runId: "child-a", number });
        expect(other).not.toBeNull();
        if (Object.is(number, -0)) {
          expect(Object.is(other!.number, -0)).toBe(true);
        }
        const shown =
          locale === "de-DE"
            ? german
            : french.join(String.fromCodePoint(0x202f));
        const ceilings = [
          ...(own ? [{ runId: "current", number: 7 }] : []),
          other!,
        ];

        expect(read("current", ceilings, "at noon")).toBe(
          own
            ? `Stopped: its usage limit and the limit of run ${shown} reached, at noon`
            : `Stopped: usage limit of run ${shown} reached, at noon`,
        );
      });
    },
  );

  it.each([
    ["de-DE", false],
    ["de-DE", true],
    ["fr-FR", false],
    ["fr-FR", true],
  ] as const)(
    "formats exact child numbers for %s with own ceiling %s",
    async (locale, own) => {
      await inLanguage(locale, (read) => {
        const separator =
          locale === "de-DE" ? "." : String.fromCodePoint(0x202f);
        const first = ["1", "234"].join(separator);
        const last = ["9", "007", "199", "254", "740", "991"].join(separator);
        const ceilings = [
          ...(own ? [{ runId: "current", number: 7 }] : []),
          { runId: "child-a", number: 1234 },
          { runId: "child-b", number: Number.MAX_SAFE_INTEGER },
        ];

        expect(read("current", ceilings, "at noon")).toBe(
          own
            ? `Stopped: its usage limit and the limits of runs ${first} and ${last} reached, at noon`
            : `Stopped: usage limits of runs ${first} and ${last} reached, at noon`,
        );
      });
    },
  );

  it.each(["de-DE", "fr-FR"])(
    "keeps negative and negative-zero numbered runs for %s",
    async (locale) => {
      await inLanguage(locale, (read) => {
        const separator =
          locale === "de-DE" ? "." : String.fromCodePoint(0x202f);
        const negative =
          "-" + ["9", "007", "199", "254", "740", "991"].join(separator);
        const first = numberedRunFrom({
          runId: "child-a",
          number: Number.MIN_SAFE_INTEGER,
        });
        const last = numberedRunFrom({ runId: "child-b", number: -0 });
        expect(first).not.toBeNull();
        expect(last).not.toBeNull();
        expect(Object.is(last!.number, -0)).toBe(true);

        expect(read("current", [first!, last!], "at noon")).toBe(
          `Stopped: usage limits of runs ${negative} and 0 reached, at noon`,
        );
      });
    },
  );
});
