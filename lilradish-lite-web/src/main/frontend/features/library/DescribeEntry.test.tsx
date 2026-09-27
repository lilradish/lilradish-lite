import { ThemeProvider } from "@mui/material/styles";
import { render, screen, waitFor } from "@testing-library/react";
import { userEvent } from "@testing-library/user-event";
import { useState, type ReactNode } from "react";
import { describe, expect, it, vi } from "vitest";

import type { Entry } from "../../api/groups/{groupId}/{kind}";
import { renameEntry } from "../../api/groups/{groupId}/{kind}/{entryId}";
import { useAction } from "../../lib/request/useAction";
import { theme } from "../../lib/theme/theme";
import { requestsTo, serving, type Reply } from "../../testutil/answering";
import { noticesIn } from "../../testutil/notices";
import { DescribeEntry } from "./DescribeEntry";

const GROUP = "00000003-0000-4000-8000-000000000b31";

const TRIAGE: Entry = {
  entryId: "00000006-0000-4000-8000-000000000b31",
  kind: "question",
  name: "Triage",
  purpose: "Sorts claims.",
  acts: new Set(["rename"]),
  versions: [],
};

const ADDRESS = `/api/groups/${GROUP}/questions/${TRIAGE.entryId}`;

const NAME_LIMIT =
  "A name is one to 128 characters on one line: no space but single plain ones between words, none at either end, and something in it that shows.";

const PURPOSE_LIMIT =
  "What it is for is at most 512 characters on one line, with something in it that shows.";

function themed({ children }: { readonly children: ReactNode }) {
  return <ThemeProvider theme={theme}>{children}</ThemeProvider>;
}

/** The dialog as renaming opens it, over the real action it is handed, shut as a page shuts it. */
function renaming(
  entry: Entry,
  routes: Readonly<Record<string, readonly Reply[]>> = {},
) {
  const sent = serving(routes);
  const settled = vi.fn<(entry: Entry) => void>();
  function Holder() {
    const [open, setOpen] = useState(true);
    const changing = useAction<Entry>(settled);
    return (
      <DescribeEntry
        open={open}
        title="Rename this entry"
        actLabel="Rename"
        underway="entry.renameUnderway"
        rule="approve_entry"
        described={entry}
        send={(described, signal) =>
          renameEntry(GROUP, "question", entry.entryId, described, signal)
        }
        action={changing}
        onShut={() => setOpen(false)}
      />
    );
  }
  render(<Holder />, { wrapper: themed });
  return { sent, settled };
}

function nameBox(): HTMLElement {
  return screen.getByRole("textbox", { name: "Name" });
}

function purposeBox(): HTMLElement {
  return screen.getByRole("textbox", { name: "What it is for" });
}

function rename(): HTMLElement {
  return screen.getByRole("button", { name: "Rename" });
}

async function pressedHeld() {
  rename().focus();
  await userEvent.keyboard("{Enter}");
}

describe("DescribeEntry", () => {
  it("opens holding the name and what it is for as they stand, and holds sending both as they stand", async () => {
    const { sent } = renaming(TRIAGE);

    await pressedHeld();

    expect(nameBox()).toHaveValue("Triage");
    expect(purposeBox()).toHaveValue("Sorts claims.");
    expect(document.activeElement).not.toBe(document.body);
    expect(noticesIn(rename().parentElement!)).toEqual([
      { severity: "info", words: "Change the name or what it is for first." },
    ]);
    expect(requestsTo(sent)).toEqual([]);
  });

  it("opens an entry saying nothing of what it is for with that box empty", () => {
    const { purpose: _said, ...unsaid } = TRIAGE;
    renaming(unsaid);

    expect(purposeBox()).toHaveValue("");
  });

  it.each([
    [
      "the name is one an entry cannot have",
      "Triage ",
      "Sorts claims.",
      NAME_LIMIT,
    ],
    [
      "what it is for is more than one line holds",
      "Triage",
      `Sorts${String.fromCodePoint(0x2028)}claims`,
      PURPOSE_LIMIT,
    ],
  ])(
    "holds sending while %s, saying so under the control and sending nothing",
    async (_case, name, purpose, reason) => {
      const { sent } = renaming(TRIAGE);

      await userEvent.clear(nameBox());
      await userEvent.type(nameBox(), name);
      await userEvent.clear(purposeBox());
      await userEvent.type(purposeBox(), purpose);
      await pressedHeld();

      expect(noticesIn(rename().parentElement!)).toEqual([
        { severity: "info", words: reason },
      ]);
      expect(requestsTo(sent)).toEqual([]);
    },
  );

  /** Emptying what it is for is a change: from then on it says nothing of it. */
  it("sends the name and what it is for as one change, an emptied purpose as none, and shuts once it lands", async () => {
    const { sent, settled } = renaming(TRIAGE, {
      [`PATCH ${ADDRESS}`]: [
        [JSON.stringify({ ...TRIAGE, name: "Intake", acts: ["rename"] }), 200],
      ],
    });

    await userEvent.clear(nameBox());
    await userEvent.type(nameBox(), "Intake");
    await userEvent.clear(purposeBox());
    await userEvent.click(rename());

    await waitFor(() => expect(settled).toHaveBeenCalledTimes(1));
    expect(JSON.parse(String(sent.mock.calls[0]![1]?.body))).toEqual({
      name: "Intake",
      purpose: null,
    });
    expect(settled.mock.calls[0]![0].name).toBe("Intake");
    await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
  });

  it.each([
    [
      "the name another entry of the kind has",
      "ENTRY_NAME_TAKEN",
      409,
      "Another entry of this kind in this group already has that name.",
    ],
    [
      "the act, as the rule it was handed and no other",
      "ACT_NOT_PERMITTED",
      403,
      "A group's entries are put into service, and a run's raised ceiling approved or refused, only by a role in it that may approve an entry.",
    ],
  ])(
    "says a refusal of %s in the dialog, which stays open",
    async (_case, code, status, said) => {
      const { settled } = renaming(TRIAGE, {
        [`PATCH ${ADDRESS}`]: [[JSON.stringify({ code }), status]],
      });

      await userEvent.clear(nameBox());
      await userEvent.type(nameBox(), "Intake");
      await userEvent.click(rename());

      expect(await screen.findByRole("alert")).toHaveTextContent(said);
      expect(screen.getByRole("dialog")).toBeInTheDocument();
      expect(settled).not.toHaveBeenCalled();
    },
  );
});
