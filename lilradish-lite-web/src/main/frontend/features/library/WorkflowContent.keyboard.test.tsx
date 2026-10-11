import { ThemeProvider } from "@mui/material/styles";
import { act, render, screen, waitFor, within } from "@testing-library/react";
import { userEvent } from "@testing-library/user-event";
import { RouterProvider, createMemoryRouter } from "react-router";
import { describe, expect, it, vi } from "vitest";

import type { Version } from "../../api/groups/{groupId}/{kind}";
import type { Problem } from "../../api/problem";
import { StandingProvider } from "../../app/standing/StandingContext";
import { answered, inGroup } from "../../testutil/standingRead";
import { theme } from "../../lib/theme/theme";
import { requestsTo, serving } from "../../testutil/answering";
import { laidOutAt } from "../../testutil/layout";
import { WorkflowContent } from "./WorkflowContent";
vi.mock("../../app/standing/RequireGroup", async (importOriginal) => ({
  ...(await importOriginal<typeof import("../../app/standing/RequireGroup")>()),
  useGroup: () => ({ groupId: GROUP }),
}));

const GROUP = "00000003-0000-4000-8000-000000000cb1";
const ENTRY = "00000006-0000-4000-8000-000000000cb1";
const VERSION = "00000007-0000-4000-8000-000000000cb1";
const ADDRESS = `/api/groups/${GROUP}/workflows/${ENTRY}/versions/${VERSION}`;
const GIVES_HINT =
  "What a run of this workflow gives back when it's done. It's shown as the run's result, and it's what a step gets when it runs this workflow inside another. Leave it empty for a workflow that only acts.";

const READ_ONLY: Version = {
  versionName: null,
  versionId: VERSION,
  number: 2,
  standing: "in_service",
  writers: [{ userId: "000cb1" }],
  writtenByMigration: false,
  stateActs: new Set(),
  pinnedBy: [],
};

const COMPLAINT = {
  fieldId: "0000000b-0000-4000-8000-000000000cb1",
  name: "complaint",
  kind: "text",
  longest: 4000,
  many: false,
  mustBeGiven: true,
};
const SUMMARY = {
  fieldId: "0000000b-0000-4000-8000-000000000cb2",
  name: "summary",
  kind: "text",
  longest: 1000,
  many: false,
  mustBeGiven: true,
};
const TRIAGE = {
  versionName: null,
  name: "Triage",
  versionId: "00000007-0000-4000-8000-000000000cb2",
  number: 4,
  standing: "in_service",
};
const ASKING = {
  stepId: "0000000c-0000-4000-8000-000000000cb1",
  name: "triage",
  runs: {
    kind: "question",
    version: TRIAGE,
    takes: [COMPLAINT],
    gives: [SUMMARY],
  },
  producer: { kind: "person" },
  bindings: [
    {
      bindingId: "0000000e-0000-4000-8000-000000000cb1",
      target: "complaint",
      source: { input: "complaint" },
    },
  ],
};
const WORKFLOW = {
  revision: 3,
  takes: [COMPLAINT],
  gives: [SUMMARY],
  steps: [ASKING],
  outputs: [
    {
      bindingId: "0000000e-0000-4000-8000-000000000cb2",
      target: "summary",
      source: { step: 0, path: "summary" },
    },
  ],
  ceiling: "100",
  keepsOwnCeiling: false,
  raiseNeedsApproval: false,
  mayBeHelped: false,
  problems: [{ code: "tries_missing", part: "steps", stepId: ASKING.stepId }],
  sendsPast: [],
  offered: {
    codeSteps: [],
    searchWays: [],
    models: [],
  },
  terms: {},
};

describe("WorkflowContent keyboard", () => {
  it("reaches the information from the gives heading with Tab and closes it when the keyboard leaves", async () => {
    laidOutAt(1400, 1400);
    const sent = serving({
      [`GET ${ADDRESS}`]: [[JSON.stringify(WORKFLOW), 200]],
    });
    const refused = vi.fn<(problem: Problem) => void>();
    const written = vi.fn<() => void>();
    const router = createMemoryRouter(
      [
        {
          path: "/content",
          element: (
            <WorkflowContent
              groupId={GROUP}
              entryId={ENTRY}
              version={READ_ONLY}
              editable={false}
              readCount={0}
              onRefused={refused}
              onWritten={written}
              onShown={vi.fn()}
            />
          ),
        },
      ],
      { initialEntries: ["/content"] },
    );
    render(
      <ThemeProvider theme={theme}>
        <StandingProvider
          read={answered(
            [],
            [inGroup(GROUP, "CLAIMS", "Claims", [])],
            "000cb1",
          )}
        >
          <RouterProvider router={router} />
        </StandingProvider>
      </ThemeProvider>,
    );
    await screen.findByRole("heading", { name: "The steps", level: 3 });
    const gives = screen.getByRole("region", { name: "What it gives back" });
    const title = within(gives).getByRole("heading", {
      name: "What it gives back",
      level: 3,
    });
    const information = within(gives).getByRole("button", {
      name: "About what it gives back",
    });
    expect(screen.queryByRole("tooltip")).toBeNull();
    await act(async () => title.focus());

    await userEvent.tab();

    expect(information).toHaveFocus();
    const tooltip = await screen.findByRole("tooltip");
    expect(tooltip.textContent).toBe(GIVES_HINT);
    expect(gives).not.toContainElement(tooltip);
    expect(title.textContent).toBe("What it gives back");
    await userEvent.tab();
    expect(information).not.toHaveFocus();
    await waitFor(() => expect(screen.queryByRole("tooltip")).toBeNull());
    expect(sent).toHaveBeenCalledTimes(1);
    expect(requestsTo(sent)).toEqual([`GET ${ADDRESS}`]);
    expect(written).not.toHaveBeenCalled();
    expect(refused).not.toHaveBeenCalled();
  });
});
