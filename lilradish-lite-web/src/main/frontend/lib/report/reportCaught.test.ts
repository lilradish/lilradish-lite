import { expectConsoleErrors } from "../../testutil/consoleErrors";
import type { ErrorInfo } from "react";
import { describe, it } from "vitest";

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
    reportRouterError(BROKEN, WHERE);

    expectConsoleErrors([BROKEN]);
  });

  it("leaves a render error to the root's own handler, which is already sent it", () => {
    const errorInfo: ErrorInfo = { componentStack: "\n    at RunsPage" };

    reportRouterError(BROKEN, { ...WHERE, errorInfo });

    expectConsoleErrors();
  });
});
