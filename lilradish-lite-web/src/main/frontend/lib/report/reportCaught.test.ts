import type { ErrorInfo } from "react";
import { describe, expect, it, vi } from "vitest";

import { reportRouterError } from "./reportCaught";

const BROKEN = new TypeError(
  "Cannot read properties of undefined (reading 'id')",
);

const WHERE = {
  location: {
    pathname: "/groups/g-1/runs",
    search: "",
    hash: "",
    state: null,
    key: "default",
  },
  params: { groupId: "g-1" },
  pattern: "/groups/:groupId/runs",
};

describe("reportRouterError", () => {
  it("reports an error that never rendered, a loader's or an action's, exactly once", () => {
    const written = vi.spyOn(console, "error").mockImplementation(() => {});

    reportRouterError(BROKEN, WHERE);

    expect(written.mock.calls).toEqual([[BROKEN]]);
  });

  it("leaves a render error to the root's own handler, which is already sent it", () => {
    const written = vi.spyOn(console, "error").mockImplementation(() => {});
    const errorInfo: ErrorInfo = { componentStack: "\n    at RunsPage" };

    reportRouterError(BROKEN, { ...WHERE, errorInfo });

    expect(written).not.toHaveBeenCalled();
  });
});
