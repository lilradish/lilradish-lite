import { ThemeProvider } from "@mui/material/styles";
import { render, screen, waitFor, within } from "@testing-library/react";
import { userEvent } from "@testing-library/user-event";
import { useState, type ReactNode } from "react";
import { describe, expect, it, vi } from "vitest";

import { runFrom, type Run } from "../../api/groups/{groupId}/runs/{runId}";
import { useAction } from "../../lib/request/useAction";
import { theme } from "../../lib/theme/theme";
import { requestsTo, serving, type Reply } from "../../testutil/answering";
import { RenameRun } from "./RenameRun";

const GROUP = "00000003-0000-4000-8000-000000000c71";

const RUN_ID = "00000008-0000-4000-8000-000000000c71";

const ADDRESS = `/api/groups/${GROUP}/runs/${RUN_ID}`;

const LIMIT =
  "A run's name is one to 128 characters on one line, with something in it that shows.";

const SENT = {
  runId: RUN_ID,
  number: 7,
  name: "Claim from Ada",
  workflow: {
    entryId: "00000006-0000-4000-8000-000000000c71",
    name: "Handle a claim",
    version: 3,
  },
  startedAt: "2026-09-24T08:00:00Z",
  state: "running",
  spend: { sent: "0", cameBack: "0", spent: "0", cameBackUnknown: false },
  ceiling: { raiseNeedsApproval: false },
  acts: ["rename"],
};

const CLAIM = runFrom(SENT)!;

function themed({ children }: { readonly children: ReactNode }) {
  return <ThemeProvider theme={theme}>{children}</ThemeProvider>;
}

/** The dialog over the real action it is handed, opened on the run, shut as the page shuts it. */
function renaming(routes: Readonly<Record<string, readonly Reply[]>> = {}) {
  const sent = serving(routes);
  const settled = vi.fn();
  function Holder() {
    const [open, setOpen] = useState(true);
    const action = useAction<Run>(settled);
    return (
      <RenameRun
        groupId={GROUP}
        run={CLAIM}
        open={open}
        changing={action}
        onShut={() => setOpen(false)}
      />
    );
  }
  render(<Holder />, { wrapper: themed });
  return {
    sent,
    settled,
    dialog: screen.getByRole("dialog", { name: "Rename this run" }),
  };
}

function nameBox(): HTMLElement {
  return screen.getByRole("textbox", { name: "Name" });
}

function rename(): HTMLElement {
  return screen.getByRole("button", { name: "Rename" });
}

async function renamedTo(name: string) {
  await userEvent.clear(nameBox());
  if (name !== "") {
    await userEvent.type(nameBox(), name);
  }
}

describe("RenameRun", () => {
  it("opens holding the name as it stands, and offers no rename until it is another", () => {
    renaming();

    expect(nameBox()).toHaveValue("Claim from Ada");
    expect(document.activeElement).toBe(nameBox());
    expect(rename()).toHaveAttribute("aria-disabled", "true");
    expect(rename()).toHaveAccessibleDescription("Change the name first.");
  });

  it.each([
    ["nothing", ""],
    ["spaces alone", "   "],
    ["a name past its bound", "n".repeat(129)],
  ])(
    "holds back %s, saying what a run's name is where it is typed",
    async (_case, typed) => {
      const { sent } = renaming();

      await renamedTo(typed);

      expect(rename()).toHaveAttribute("aria-disabled", "true");
      expect(rename()).toHaveAccessibleDescription(LIMIT);
      expect(requestsTo(sent)).toEqual([]);
    },
  );

  /** Two runs may share a name, so a name is sent spaced exactly as typed. */
  it("sends the name as typed, and shuts once the run answers", async () => {
    const { sent, settled } = renaming({
      [`PATCH ${ADDRESS}`]: [
        [JSON.stringify({ ...SENT, name: " Claim  again" }), 200],
      ],
    });

    await renamedTo(" Claim  again");
    await userEvent.click(rename());

    await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
    expect(requestsTo(sent)).toEqual([`PATCH ${ADDRESS}`]);
    expect(JSON.parse(String(sent.mock.calls[0]![1]?.body))).toEqual({
      name: " Claim  again",
    });
    expect(settled).toHaveBeenCalledOnce();
  });

  it("says a refusal of the name under the box, and stays open", async () => {
    const { dialog } = renaming({
      [`PATCH ${ADDRESS}`]: [
        [JSON.stringify({ code: "RUN_NAME_UNUSABLE" }), 400],
      ],
    });
    expect(within(dialog).queryByRole("alert")).toBeNull();

    await renamedTo("Claim again");
    await userEvent.click(rename());

    expect(await within(dialog).findByRole("alert")).toHaveTextContent(LIMIT);
    expect(screen.getByRole("dialog", { name: "Rename this run" })).toBe(
      dialog,
    );
  });
});
