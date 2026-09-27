import { ThemeProvider } from "@mui/material/styles";
import { act, render, screen, waitFor, within } from "@testing-library/react";
import { userEvent } from "@testing-library/user-event";
import { useState, type ReactNode } from "react";
import { describe, expect, it, vi } from "vitest";

import { useAction } from "../../lib/request/useAction";
import { theme } from "../../lib/theme/theme";
import { requestsTo, serving, type Reply } from "../../testutil/answering";
import { deferred } from "../../testutil/deferred";
import { noticesIn } from "../../testutil/notices";
import { AddSomebody } from "./AddSomebody";
import type { Asked, Outcome } from "./changes";

/** How long typing has to pause before what was typed is sent. */
const QUIET_MS = 300;

const ADA = { userId: "000130", displayName: "Ada Lovelace" };

const GRACE = {
  userId: "000140",
  displayName: "Grace Hopper",
  subjectId: "00000002-0000-4000-8000-000000000140",
};

const ADA_BROUGHT_IN = {
  ...ADA,
  subjectId: "00000002-0000-4000-8000-000000000130",
  estateRoles: [],
  lastGrantingRoles: [],
  groups: [],
  seeded: false,
};

const FOUND: Reply = [
  JSON.stringify({ items: [ADA, GRACE], more: false }),
  200,
];

function themed({ children }: { readonly children: ReactNode }) {
  return <ThemeProvider theme={theme}>{children}</ThemeProvider>;
}

/**
 * The control and its dialog over the real action they are handed, held as a
 * page holds it — the holder settles every change and keeps who asked — beside
 * another change the same action can run. Only the server is stood in for.
 */
function adding(routes: Readonly<Record<string, readonly Reply[]>> = {}) {
  const sent = serving({ "GET /api/people": [FOUND], ...routes });
  const settled = vi.fn();
  const told = vi.fn();
  const otherChange = deferred<Outcome>();
  function Holder() {
    const [asked, setAsked] = useState<Asked | null>(null);
    const changing = useAction<Outcome>(settled);
    return (
      <>
        <AddSomebody
          changing={changing}
          refused={asked?.place === "bringIn" ? changing.problem : null}
          onAsk={(asking) => {
            told(asking);
            setAsked(asking);
          }}
        />
        <button onClick={() => changing.run(() => otherChange.promise)}>
          Another change
        </button>
      </>
    );
  }
  render(<Holder />, { wrapper: themed });
  return { sent, settled, told, otherChange };
}

async function opened() {
  await userEvent.click(screen.getByRole("button", { name: "Add somebody" }));
  return screen.getByRole("dialog", { name: "Add somebody" });
}

/** Typed, and the pause that hands it on waited out in real time. */
async function searchedFor(text: string) {
  await userEvent.type(
    screen.getByRole("searchbox", { name: "User number or name" }),
    text,
  );
  await act(async () => {
    await new Promise((resolve) => setTimeout(resolve, QUIET_MS));
  });
}

function bringIn(): HTMLElement {
  return screen.getByRole("button", { name: "Bring into the pool" });
}

describe("AddSomebody", () => {
  /** The directory is every user, and a picker that opened showing it would publish them. */
  it("opens a dialog that asks for nobody until something is typed, with the box ready to type in", async () => {
    const { sent } = adding();

    const dialog = await opened();

    expect(document.activeElement).toBe(
      within(dialog).getByRole("searchbox", { name: "User number or name" }),
    );
    expect(within(dialog).queryAllByRole("radio")).toEqual([]);
    expect(sent).not.toHaveBeenCalled();
  });

  it("searches the directory with what was typed, offering nobody already in the pool and saying they are here", async () => {
    const { sent } = adding();
    await opened();

    await searchedFor("a");

    const offered = await screen.findAllByRole("radio");
    expect(offered.map((each) => each.closest("label")?.textContent)).toEqual([
      "000130 Ada Lovelace",
    ]);
    expect(
      within(screen.getByRole("list")).getByRole("listitem"),
    ).toHaveTextContent("000140 Grace HopperAlready in the pool.");
    expect(requestsTo(sent)).toEqual(["GET /api/people?search=a"]);
  });

  it("offers somebody the directory holds no name for by their user number alone, inventing nothing", async () => {
    adding({
      "GET /api/people": [
        [JSON.stringify({ items: [{ userId: "000150" }], more: false }), 200],
      ],
    });
    await opened();

    await searchedFor("000150");

    const offered = await screen.findAllByRole("radio");
    expect(offered.map((each) => each.closest("label")?.textContent)).toEqual([
      "000150",
    ]);
  });

  it("says more were found than it shows, and asks for more to be typed", async () => {
    adding({
      "GET /api/people": [[JSON.stringify({ items: [ADA], more: true }), 200]],
    });
    await opened();

    await searchedFor("a");

    expect(
      await screen.findByText(
        "More were found than the 1 shown. Type more to narrow them down.",
      ),
    ).toBeInTheDocument();
  });

  it("holds the control that brings somebody in until somebody is picked, saying so", async () => {
    const { sent } = adding();
    await opened();

    bringIn().focus();
    await userEvent.keyboard("{Enter}");

    expect(bringIn()).toHaveAttribute("aria-disabled", "true");
    expect(bringIn()).toHaveAccessibleDescription("Pick somebody first.");
    expect(sent).not.toHaveBeenCalled();
  });

  /**
   * Beside the control it holds, among the dialog's actions: that is where a
   * reader looks when it will not press, and what it describes. The choice it
   * points at is named on its own above.
   */
  it("says why it holds the control as an information notice right under that control, among the actions", async () => {
    adding();
    const dialog = await opened();

    const holds = bringIn().parentElement!;
    const reason = within(holds).getByText("Pick somebody first.");

    expect(noticesIn(holds)).toEqual([
      { severity: "info", words: "Pick somebody first." },
    ]);
    expect(
      bringIn().compareDocumentPosition(reason) &
        Node.DOCUMENT_POSITION_FOLLOWING,
    ).not.toBe(0);
    expect(
      within(dialog)
        .getByRole("searchbox", { name: "User number or name" })
        .compareDocumentPosition(reason) & Node.DOCUMENT_POSITION_FOLLOWING,
    ).not.toBe(0);
    expect(holds.contains(within(dialog).getByRole("searchbox"))).toBe(false);
    expect(screen.queryByRole("alert")).toBeNull();
  });

  it("brings the person picked into the pool by their user number, settles as who the pool now holds, and closes", async () => {
    const { sent, settled, told } = adding({
      "POST /api/pool/people": [[JSON.stringify(ADA_BROUGHT_IN), 201]],
    });
    await opened();
    await searchedFor("a");
    await userEvent.click(
      await screen.findByRole("radio", { name: "000130 Ada Lovelace" }),
    );

    await userEvent.click(bringIn());

    await waitFor(() =>
      expect(settled).toHaveBeenCalledExactlyOnceWith({
        broughtIn: {
          ...ADA_BROUGHT_IN,
          estateRoles: new Set(),
          lastGrantingRoles: new Set(),
        },
      }),
    );
    expect(told).toHaveBeenCalledExactlyOnceWith({ place: "bringIn" });
    expect(requestsTo(sent)).toEqual([
      "GET /api/people?search=a",
      "POST /api/pool/people",
    ]);
    expect(JSON.parse(String(sent.mock.calls[1]![1]?.body))).toEqual({
      userId: "000130",
    });
    await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
  });

  /**
   * The server carries the request out whether or not anybody is still
   * listening, so a dialog shut on it would leave the pool changed and the
   * page saying nothing of it.
   */
  it("cannot be abandoned while somebody is being brought in, by its own control, Escape or the shade", async () => {
    const answer = deferred<readonly [string, number]>();
    const { sent, settled } = adding({
      "POST /api/pool/people": [answer.promise],
    });
    const dialog = await opened();
    await searchedFor("a");
    await userEvent.click(
      await screen.findByRole("radio", { name: "000130 Ada Lovelace" }),
    );
    await userEvent.click(bringIn());
    const abandon = screen.getByRole("button", { name: "Cancel" });

    abandon.focus();
    await userEvent.keyboard("{Enter}[Space]{Escape}");
    // The dialog's own container, around it and under the shade: a press on it
    // is a press on the shade.
    await userEvent.click(dialog.parentElement!);
    // Long enough for a dialog that had been shut to have gone.
    await act(async () => {
      await new Promise((resolve) =>
        setTimeout(resolve, theme.transitions.duration.leavingScreen * 2),
      );
    });

    expect(abandon).toHaveAttribute("aria-disabled", "true");
    expect(abandon).toHaveAccessibleDescription(
      "They are being brought in, and that can no longer be called back.",
    );
    expect(noticesIn(abandon.parentElement!)).toEqual([
      {
        severity: "warning",
        words:
          "They are being brought in, and that can no longer be called back.",
      },
    ]);
    expect(screen.getByRole("dialog", { name: "Add somebody" })).toBe(dialog);
    expect(settled).not.toHaveBeenCalled();

    await act(async () => answer.settle([JSON.stringify(ADA_BROUGHT_IN), 201]));

    await waitFor(() => expect(settled).toHaveBeenCalledOnce());
    expect(requestsTo(sent)).toEqual([
      "GET /api/people?search=a",
      "POST /api/pool/people",
    ]);
  });

  it.each([
    ["PERSON_ALREADY_IN_POOL", 409, "That person is already in the pool."],
    [
      "USER_NOT_IN_DIRECTORY",
      400,
      "Nobody in the directory has that user number.",
    ],
  ])(
    "says in place why %s refused it, and stays open with the pick in hand",
    async (code, status, sentence) => {
      const { settled } = adding({
        "POST /api/pool/people": [[JSON.stringify({ code }), status]],
      });
      const dialog = await opened();
      await searchedFor("a");
      await userEvent.click(
        await screen.findByRole("radio", { name: "000130 Ada Lovelace" }),
      );

      await userEvent.click(bringIn());

      expect(await within(dialog).findByRole("alert")).toHaveTextContent(
        sentence,
      );
      expect(settled).not.toHaveBeenCalled();
      expect(screen.getByRole("dialog", { name: "Add somebody" })).toBe(dialog);
      expect(
        screen.getByRole("radio", { name: "000130 Ada Lovelace" }),
      ).toBeChecked();
    },
  );

  it("abandons it from its own control, bringing nobody in", async () => {
    const { sent, settled } = adding();
    await opened();
    await searchedFor("a");
    await userEvent.click(
      await screen.findByRole("radio", { name: "000130 Ada Lovelace" }),
    );

    await userEvent.click(screen.getByRole("button", { name: "Cancel" }));

    await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
    expect(settled).not.toHaveBeenCalled();
    expect(requestsTo(sent)).toEqual(["GET /api/people?search=a"]);
  });

  /** Any change out under the same action holds it: two at once would settle in whichever order they land. */
  it("holds Add somebody while another change is out, opening nothing", async () => {
    const { otherChange } = adding();
    await userEvent.click(
      screen.getByRole("button", { name: "Another change" }),
    );
    const add = screen.getByRole("button", { name: "Add somebody" });

    add.focus();
    await userEvent.keyboard("{Enter}[Space]");

    expect(add).toHaveAttribute("aria-disabled", "true");
    expect(screen.queryByRole("dialog")).toBeNull();

    await act(async () =>
      otherChange.settle({
        changed: {
          ...ADA_BROUGHT_IN,
          estateRoles: new Set(),
          lastGrantingRoles: new Set(),
        },
      }),
    );

    expect(add).not.toHaveAttribute("aria-disabled");
  });

  /** Only what this opening asked is this opening's to say. */
  it("says no refusal of an earlier opening when opened again", async () => {
    adding({
      "POST /api/pool/people": [['{"code":"PERSON_ALREADY_IN_POOL"}', 409]],
    });
    await opened();
    await searchedFor("a");
    await userEvent.click(
      await screen.findByRole("radio", { name: "000130 Ada Lovelace" }),
    );
    await userEvent.click(bringIn());
    await screen.findByRole("alert");
    await userEvent.click(screen.getByRole("button", { name: "Cancel" }));
    await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());

    const dialog = await opened();

    expect(within(dialog).queryByRole("alert")).toBeNull();
  });

  it("opens again with nothing typed, nobody picked, and nothing asked", async () => {
    const { sent } = adding();
    await opened();
    await searchedFor("a");
    await userEvent.click(
      await screen.findByRole("radio", { name: "000130 Ada Lovelace" }),
    );
    await userEvent.click(screen.getByRole("button", { name: "Cancel" }));
    await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());

    const dialog = await opened();

    expect(
      within(dialog).getByRole("searchbox", { name: "User number or name" }),
    ).toHaveValue("");
    expect(within(dialog).queryAllByRole("radio")).toEqual([]);
    expect(bringIn()).toHaveAttribute("aria-disabled", "true");
    expect(requestsTo(sent)).toEqual(["GET /api/people?search=a"]);
  });

  /** Focus is back on the control as it shuts, and a keyboard can open it again before it has gone. */
  it("opens afresh even when opened again while the last one is still leaving", async () => {
    adding();
    await opened();
    await searchedFor("a");
    await userEvent.click(
      await screen.findByRole("radio", { name: "000130 Ada Lovelace" }),
    );
    await userEvent.click(screen.getByRole("button", { name: "Cancel" }));
    const leaving = screen.queryByRole("dialog", { hidden: true });

    await userEvent.keyboard("{Enter}");

    expect(leaving).not.toBeNull();
    const dialog = screen.getByRole("dialog", { name: "Add somebody" });
    expect(
      within(dialog).getByRole("searchbox", { name: "User number or name" }),
    ).toHaveValue("");
    expect(within(dialog).queryAllByRole("radio")).toEqual([]);
  });
});
