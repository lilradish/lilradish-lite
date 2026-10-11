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
  "Use 1 to 128 characters on one line, with at least one visible character.";

const SENT = {
  runId: RUN_ID,
  number: 7,
  name: "Claim from Avo",
  workflow: {
    versionName: null,
    entryId: "00000006-0000-4000-8000-000000000c71",
    name: "Handle a claim",
    version: 3,
  },
  runsAbove: [],
  startedAt: "2026-09-24T08:00:00Z",
  conversationEvents: [],
  state: "running",
  spend: { sent: "0", cameBack: "0", spent: "0", cameBackUnknown: false },
  ceiling: { raiseNeedsApproval: false },
  stateActs: ["rename"],
};

const CLAIM = runFrom(SENT)!;

function themed({ children }: { readonly children: ReactNode }) {
  return <ThemeProvider theme={theme}>{children}</ThemeProvider>;
}

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
    dialog: screen.getByRole("textbox", { name: "Name" }).closest("form")!,
  };
}

function nameBox(): HTMLElement {
  return screen.getByRole("textbox", { name: "Name" });
}

function rename(): HTMLElement {
  return screen.getByRole("button", { name: "Save" });
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

    expect(nameBox()).toHaveValue("Claim from Avo");
    expect(nameBox().tagName).toBe("TEXTAREA");
    expect(document.activeElement).toBe(nameBox());
    expect(rename()).toBeDisabled();
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

      if (typed.length === 129) {
        const user = userEvent.setup();
        const input = nameBox();
        await user.clear(input);
        await user.paste(typed.slice(0, -1));
        expect(input).toHaveValue(typed.slice(0, -1));
        expect(rename()).toBeEnabled();
        expect(requestsTo(sent)).toEqual([]);
        await user.type(input, typed.slice(-1));
        expect(input).toHaveValue(typed);
      } else {
        await renamedTo(typed);
      }

      expect(rename()).toBeDisabled();
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

    await waitFor(() =>
      expect(screen.queryByRole("textbox", { name: "Name" })).toBeNull(),
    );
    expect(requestsTo(sent)).toEqual([`PATCH ${ADDRESS}`]);
    expect(JSON.parse(String(sent.mock.calls[0]![1]?.body))).toEqual({
      name: " Claim  again",
    });
    expect(settled).toHaveBeenCalledOnce();
  });

  it("says a refusal of the name under the box, and stays open", async () => {
    const { dialog, sent } = renaming({
      [`PATCH ${ADDRESS}`]: [
        [JSON.stringify({ code: "RUN_NAME_UNUSABLE" }), 400],
      ],
    });
    expect(within(dialog).queryByRole("alert")).toBeNull();

    await renamedTo("Claim again");
    await userEvent.click(document.body);

    expect(await within(dialog).findByRole("alert")).toHaveTextContent(
      "Use 1 to 128 characters on one line, with at least one visible character.",
    );
    expect(screen.getByRole("textbox", { name: "Name" }).closest("form")!).toBe(
      dialog,
    );
    expect(nameBox()).toHaveValue("Claim again");
    await waitFor(() => expect(nameBox()).toHaveFocus());
    expect(requestsTo(sent)).toEqual([`PATCH ${ADDRESS}`]);
    expect(JSON.parse(String(sent.mock.calls[0]![1]?.body))).toEqual({
      name: "Claim again",
    });
  });

  it("can still save after a Cancel pointer press is released over the name without cancelling", async () => {
    const user = userEvent.setup();
    const { sent, settled } = renaming({
      [`PATCH ${ADDRESS}`]: [
        [JSON.stringify({ ...SENT, name: "Claim again" }), 200],
      ],
    });
    await renamedTo("Claim again");
    await user.pointer([
      {
        target: screen.getByRole("button", { name: "Cancel" }),
        keys: "[MouseLeft>]",
      },
      { target: nameBox() },
      { target: nameBox(), keys: "[/MouseLeft]" },
    ]);
    expect(nameBox()).toHaveValue("Claim again");
    expect(requestsTo(sent)).toEqual([]);
    await user.click(rename());
    await waitFor(() =>
      expect(screen.queryByRole("textbox", { name: "Name" })).toBeNull(),
    );
    expect(requestsTo(sent)).toEqual([`PATCH ${ADDRESS}`]);
    expect(JSON.parse(String(sent.mock.calls[0]![1]?.body))).toEqual({
      name: "Claim again",
    });
    expect(settled).toHaveBeenCalledOnce();
  });
});
