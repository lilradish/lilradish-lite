import { render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router";
import { describe, expect, it, vi } from "vitest";

import type { EntryKind, Version } from "../../api/groups/{groupId}/{kind}";
import { requestsTo, serving } from "../../testutil/answering";
import { laidOutAt } from "../../testutil/layout";
import { VersionContent } from "./VersionContent";

const GROUP = "00000003-0000-4000-8000-000000000c11";

const ENTRY = "00000006-0000-4000-8000-000000000c11";

const VERSION: Version = {
  versionId: "00000007-0000-4000-8000-000000000c11",
  number: 1,
  standing: "in_service",
  writers: [],
  writtenByMigration: true,
  acts: new Set(),
  pinnedBy: [],
};

function drawn(kind: EntryKind) {
  return render(
    <MemoryRouter>
      <VersionContent
        kind={kind}
        groupId={GROUP}
        entryId={ENTRY}
        version={VERSION}
        readCount={0}
        onRefused={vi.fn()}
        onWritten={vi.fn()}
        onShown={vi.fn()}
      />
    </MemoryRouter>,
  );
}

describe("VersionContent", () => {
  it.each([
    [
      "question",
      "questions",
      {
        revision: 1,
        instruction: "Say which category.",
        takes: [],
        gives: [],
        lists: [],
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

      drawn(kind);

      expect(await screen.findByText(said)).toBeInTheDocument();
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
                lists: [],
                questions: [],
                workflows: [],
                codeSteps: [],
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
