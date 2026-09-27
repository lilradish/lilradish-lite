import { ThemeProvider } from "@mui/material/styles";
import { render, screen, waitFor } from "@testing-library/react";
import { userEvent } from "@testing-library/user-event";
import type { ReactNode } from "react";
import { describe, expect, it, vi } from "vitest";

import type { Entry } from "../../api/groups/{groupId}/{kind}";
import { useAction } from "../../lib/request/useAction";
import { theme } from "../../lib/theme/theme";
import { requestsTo, serving, type Reply } from "../../testutil/answering";
import { noticesIn } from "../../testutil/notices";
import { LIBRARY_KINDS } from "./libraryKinds";
import { StartOne } from "./StartOne";

const GROUP = "00000003-0000-4000-8000-000000000b21";

const QUESTIONS = `/api/groups/${GROUP}/questions`;

const STARTED = {
  entryId: "00000006-0000-4000-8000-000000000b21",
  kind: "question",
  name: "Triage",
  acts: ["rename"],
  versions: [],
};

const NAME_LIMIT =
  "A name is one to 128 characters on one line: no space but single plain ones between words, none at either end, and something in it that shows.";

const PURPOSE_LIMIT =
  "What it is for is at most 512 characters on one line, with something in it that shows.";

function themed({ children }: { readonly children: ReactNode }) {
  return <ThemeProvider theme={theme}>{children}</ThemeProvider>;
}

/** The control over the real action it is handed, as the page hands it one. */
function starting(routes: Readonly<Record<string, readonly Reply[]>> = {}) {
  const sent = serving(routes);
  const settled = vi.fn<(entry: Entry) => void>();
  function Holder() {
    const action = useAction<Entry>(settled);
    return (
      <StartOne groupId={GROUP} of={LIBRARY_KINDS.question} starting={action} />
    );
  }
  render(<Holder />, { wrapper: themed });
  return { sent, settled };
}

async function opened() {
  await userEvent.click(
    screen.getByRole("button", { name: "Start a question" }),
  );
  return screen.getByRole("dialog", { name: "Start a question" });
}

function nameBox(): HTMLElement {
  return screen.getByRole("textbox", { name: "Name" });
}

function start(): HTMLElement {
  return screen.getByRole("button", { name: "Start" });
}

describe("StartOne", () => {
  it("names what it starts by the page's kind, and opens on nothing typed", async () => {
    starting();

    await opened();

    expect(nameBox()).toHaveValue("");
    expect(screen.getByRole("textbox", { name: "What it is for" })).toHaveValue(
      "",
    );
    expect(document.activeElement).toBe(nameBox());
  });

  it.each([
    ["no name is typed", "", "", NAME_LIMIT],
    ["the name is one an entry cannot have", " Triage", "", NAME_LIMIT],
    [
      "what it is for is more than one line holds",
      "Triage",
      `Sorts${String.fromCodePoint(0x2028)}claims`,
      PURPOSE_LIMIT,
    ],
  ])(
    "holds the start while %s, saying so under the control and sending nothing",
    async (_case, name, purpose, reason) => {
      const { sent } = starting();
      await opened();

      if (name !== "") {
        await userEvent.type(nameBox(), name);
      }
      if (purpose !== "") {
        await userEvent.type(
          screen.getByRole("textbox", { name: "What it is for" }),
          purpose,
        );
      }
      start().focus();
      await userEvent.keyboard("{Enter}");

      expect(start()).toHaveAttribute("aria-disabled", "true");
      expect(noticesIn(start().parentElement!)).toEqual([
        { severity: "info", words: reason },
      ]);
      expect(requestsTo(sent)).toEqual([]);
    },
  );

  /** Nothing typed of what it is for says nothing of it, sent as no purpose rather than an empty one. */
  it("starts the entry with its name and no purpose where none was typed, and shuts once it is started", async () => {
    const { sent, settled } = starting({
      [`POST ${QUESTIONS}`]: [[JSON.stringify(STARTED), 201]],
    });
    await opened();

    await userEvent.type(nameBox(), "Triage");
    await userEvent.click(start());

    await waitFor(() => expect(settled).toHaveBeenCalledTimes(1));
    expect(settled.mock.calls[0]![0].entryId).toBe(STARTED.entryId);
    expect(JSON.parse(String(sent.mock.calls[0]![1]?.body))).toEqual({
      name: "Triage",
      purpose: null,
    });
    await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
  });

  it("sends what it is for as typed where something was", async () => {
    const { sent } = starting({
      [`POST ${QUESTIONS}`]: [[JSON.stringify(STARTED), 201]],
    });
    await opened();

    await userEvent.type(nameBox(), "Triage");
    await userEvent.type(
      screen.getByRole("textbox", { name: "What it is for" }),
      "Sorts claims.",
    );
    await userEvent.click(start());

    await waitFor(() =>
      expect(requestsTo(sent)).toEqual([`POST ${QUESTIONS}`]),
    );
    expect(JSON.parse(String(sent.mock.calls[0]![1]?.body))).toEqual({
      name: "Triage",
      purpose: "Sorts claims.",
    });
  });

  it.each([
    [
      "the name another entry of the kind has",
      "ENTRY_NAME_TAKEN",
      409,
      "Another entry of this kind in this group already has that name.",
    ],
    [
      "the act, as the rule it asks",
      "ACT_NOT_PERMITTED",
      403,
      "A group's entries are started, written and submitted only by a role in it that may write one.",
    ],
  ])(
    "says a refusal of %s in the dialog, which stays open",
    async (_case, code, status, said) => {
      const { settled } = starting({
        [`POST ${QUESTIONS}`]: [[JSON.stringify({ code }), status]],
      });
      await opened();

      await userEvent.type(nameBox(), "Triage");
      await userEvent.click(start());

      expect(await screen.findByRole("alert")).toHaveTextContent(said);
      expect(screen.getByRole("dialog")).toBeInTheDocument();
      expect(settled).not.toHaveBeenCalled();
    },
  );
});
