import { screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

import { reportCaught } from "./lib/report/reportCaught";
import { serving } from "./testutil/answering";

const BROKEN = vi.hoisted(
  () => new TypeError("Cannot read properties of undefined (reading 'name')"),
);

vi.mock("./lib/report/reportCaught", async (importOriginal) => {
  const actual =
    await importOriginal<typeof import("./lib/report/reportCaught")>();
  return { ...actual, reportCaught: vi.fn(actual.reportCaught) };
});

vi.mock("./app/SystemPage", () => ({
  SystemPage: () => {
    throw BROKEN;
  },
}));

/**
 * The application as a browser starts it, which is not through `act`: left on,
 * React writes a warning of its own to the console for every update.
 */
async function startingAt(path: string) {
  vi.stubGlobal("IS_REACT_ACT_ENVIRONMENT", false);
  serving({
    "GET /api/standing": [
      [JSON.stringify({ acts: ["check_soundness"], groups: [] }), 200],
    ],
  });
  document.body.innerHTML = '<div id="root"></div>';
  window.history.replaceState(null, "", path);
  await import("./main");
}

describe("main", () => {
  it("reports an error a screen throws once, through the one entry, and nothing else writes it down", async () => {
    const written = vi.spyOn(console, "error").mockImplementation(() => {});

    await startingAt("/system/soundness");

    expect(
      await screen.findByRole("heading", {
        level: 1,
        name: "Something went wrong on this page.",
      }),
    ).toBeInTheDocument();
    expect(vi.mocked(reportCaught)).toHaveBeenCalledOnce();
    expect(vi.mocked(reportCaught).mock.calls[0]?.[0]).toBe(BROKEN);
    expect(written.mock.calls).toEqual([[BROKEN]]);
  });
});
