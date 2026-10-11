import { render, screen } from "@testing-library/react";
import { userEvent } from "@testing-library/user-event";
import { ThemeProvider } from "@mui/material/styles";
import type { ReactNode } from "react";
import { MemoryRouter } from "react-router";
import { describe, expect, it, vi } from "vitest";

import type { EntryKind, Version } from "../../api/groups/{groupId}/{kind}";
import { StandingProvider } from "../../app/standing/StandingContext";
import { answered, inGroup } from "../../testutil/standingRead";
import { requestsTo, serving } from "../../testutil/answering";
import { laidOutAt } from "../../testutil/layout";
import { VersionContent } from "./VersionContent";
import { theme } from "../../lib/theme/theme";

function themed({ children }: { readonly children: ReactNode }) {
  return <ThemeProvider theme={theme}>{children}</ThemeProvider>;
}

const GROUP = "00000003-0000-4000-8000-000000000c11";

const ENTRY = "00000006-0000-4000-8000-000000000c11";

const VERSION: Version = {
  versionName: null,
  versionId: "00000007-0000-4000-8000-000000000c11",
  number: 1,
  standing: "in_service",
  writers: [],
  writtenByMigration: true,
  stateActs: new Set(),
  pinnedBy: [],
};

function drawn(
  kind: EntryKind,
  wrapDocument?: (
    content: import("react").ReactNode,
  ) => import("react").ReactNode,
) {
  return render(
    <MemoryRouter>
      <StandingProvider
        read={answered(
          [],
          [inGroup(GROUP, "CLAIMS", "Claims", ["read_membership"])],
        )}
      >
        <VersionContent
          kind={kind}
          wrapDocument={wrapDocument}
          groupId={GROUP}
          entryId={ENTRY}
          version={VERSION}
          readCount={0}
          onRefused={vi.fn()}
          onWritten={vi.fn()}
          onShown={vi.fn()}
        />
      </StandingProvider>
    </MemoryRouter>,
    { wrapper: themed },
  );
}

describe("VersionContent", () => {
  it.each([
    [
      "question",
      "questions",
      {
        revision: 1,
        searchWays: [],
        searchShapeNeedsRefresh: false,
        instruction: "Say which category.",
        takes: [],
        gives: [],
      },
      "textbox",
      "The instruction",
    ],
    [
      "reference_list",
      "reference-lists",
      { revision: 1, note: "Pick the narrowest.", terms: [] },
      "textbox",
      "The note",
    ],
    [
      "workflow",
      "workflows",
      {
        revision: 1,
        takes: [],
        gives: [],
        steps: [],
        outputs: [],
        keepsOwnCeiling: false,
        raiseNeedsApproval: false,
        mayBeHelped: false,
        problems: [],
        sendsPast: [],
        offered: {
          codeSteps: [],
          searchWays: [],
          models: [],
        },
        terms: {},
      },
      "button",
      "Add a step",
    ],
  ] satisfies [EntryKind, string, object, "textbox" | "button", string][])(
    "recomputes a %s's editor from current standing and current state facts without reading either again",
    async (kind, segment, held, role, name) => {
      const address = `/api/groups/${GROUP}/${segment}/${ENTRY}/versions/${VERSION.versionId}`;
      const sent = serving({
        [`GET ${address}`]: [[JSON.stringify(held), 200]],
      });
      const version: Version = {
        ...VERSION,
        standing: "draft",
        stateActs: new Set(["write"]),
      };
      const draw = (mayWrite: boolean, current: Version = version) => (
        <MemoryRouter>
          <StandingProvider
            read={answered(
              [],
              [
                inGroup(GROUP, "CLAIMS", "Claims", [
                  "read_membership",
                  ...(mayWrite ? ["author_entry"] : []),
                ]),
              ],
            )}
          >
            <VersionContent
              kind={kind}
              groupId={GROUP}
              entryId={ENTRY}
              version={current}
              readCount={0}
              onRefused={vi.fn()}
              onWritten={vi.fn()}
              onShown={vi.fn()}
            />
          </StandingProvider>
        </MemoryRouter>
      );
      laidOutAt(1200, 1200);
      const page = render(draw(true), { wrapper: themed });
      if (role === "textbox") {
        await userEvent.click(
          await screen.findByRole("button", {
            name:
              kind === "question" ? "Edit the instruction" : "Edit the note",
          }),
        );
      }
      expect(await screen.findByRole(role, { name })).toBeVisible();

      page.rerender(draw(false));
      expect(screen.queryByRole(role, { name })).toBeNull();
      expect(screen.queryAllByRole("button", { name: /^Save/ })).toHaveLength(
        0,
      );

      page.rerender(draw(true));
      expect(screen.getByRole(role, { name })).toBeVisible();
      page.rerender(draw(true, { ...version, stateActs: null }));
      expect(screen.queryByRole(role, { name })).toBeNull();
      expect(screen.queryAllByRole("button", { name: /^Save/ })).toHaveLength(
        0,
      );
      expect(requestsTo(sent)).toEqual([`GET ${address}`]);
    },
  );

  it.each([
    [
      "question",
      "questions",
      {
        revision: 1,
        searchWays: [],
        searchShapeNeedsRefresh: false,
        instruction: "Say which category.",
        takes: [],
        gives: [],
      },
      "Say which category.",
    ],
    [
      "reference_list",
      "reference-lists",
      { revision: 1, note: "Pick the narrowest.", terms: [] },
      "Pick the narrowest.",
    ],
  ] satisfies [EntryKind, string, object, string][])(
    "draws a %s's version with its own editor, which reads what it holds",
    async (kind, segment, held, said) => {
      const address = `/api/groups/${GROUP}/${segment}/${ENTRY}/versions/${VERSION.versionId}`;
      const sent = serving({
        [`GET ${address}`]: [[JSON.stringify(held), 200]],
      });

      drawn(kind, (content) => (
        <section aria-label="Document">{content}</section>
      ));

      const shown = await screen.findByText(said);
      expect(shown).toBeInTheDocument();
      expect(screen.getByRole("region", { name: "Document" })).toContainElement(
        shown,
      );
      expect(requestsTo(sent)).toEqual([`GET ${address}`]);
    },
  );

  it("draws a workflow's version with its own editor, which reads what it holds", async () => {
    const sent = serving({
      [`GET /api/groups/${GROUP}/workflows/${ENTRY}/versions/${VERSION.versionId}`]:
        [
          [
            JSON.stringify({
              revision: 1,
              takes: [],
              gives: [],
              steps: [],
              outputs: [],
              keepsOwnCeiling: false,
              raiseNeedsApproval: false,
              mayBeHelped: false,
              problems: [],
              sendsPast: [],
              offered: {
                codeSteps: [],
                searchWays: [],
                models: [],
              },
              terms: {},
            }),
            200,
          ],
        ],
    });

    laidOutAt(1200, 1200);
    drawn("workflow");

    expect(await screen.findByText("No step yet.")).toBeInTheDocument();
    expect(requestsTo(sent)).toEqual([
      `GET /api/groups/${GROUP}/workflows/${ENTRY}/versions/${VERSION.versionId}`,
    ]);
  });
});
