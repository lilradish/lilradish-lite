import { ThemeProvider } from "@mui/material/styles";
import { act, render, screen, waitFor, within } from "@testing-library/react";
import { userEvent } from "@testing-library/user-event";
import type { ReactNode } from "react";
import { describe, expect, it, vi } from "vitest";

import { useAction } from "../../lib/request/useAction";
import { theme } from "../../lib/theme/theme";
import { requestsTo, serving, type Reply } from "../../testutil/answering";
import { deferred } from "../../testutil/deferred";
import { noticesIn } from "../../testutil/notices";
import type { Outcome } from "./changes";
import { CreateAGroup } from "./CreateAGroup";

/** How long typing has to pause before what was typed is sent. */
const QUIET_MS = 300;

const ADA = {
  subjectId: "00000002-0000-4000-8000-000000000130",
  userId: "000130",
  displayName: "Ada Lovelace",
};

const NAMELESS = {
  subjectId: "00000002-0000-4000-8000-000000000150",
  userId: "000150",
};

const PAYROLL = {
  groupId: "00000003-0000-4000-8000-000000000701",
  key: "PAYROLL",
  name: "Payroll",
  canBeAdministered: true,
  memberCount: 1,
};

const FOUND: Reply = [
  JSON.stringify({ items: [ADA, NAMELESS], more: false }),
  200,
];

const NAME_LIMIT =
  "A group's name is one to 128 characters on one line: no space but single plain ones between words, none at either end, and something in it that shows.";

const KEY_LIMIT = "Two to sixteen English letters and nothing else.";

const KEY_HINT =
  "What everything cites the group by. Held in capitals, and never changed.";

const PICK_FIRST = "Pick who may change its membership first.";

function themed({ children }: { readonly children: ReactNode }) {
  return <ThemeProvider theme={theme}>{children}</ThemeProvider>;
}

/**
 * The control and its dialog over the real action they are handed, held as a
 * page holds it, beside another change the same action can run. Only the
 * server is stood in for.
 */
function creating(routes: Readonly<Record<string, readonly Reply[]>> = {}) {
  const sent = serving({ "GET /api/pool/search": [FOUND], ...routes });
  const settled = vi.fn();
  const otherChange = deferred<Outcome>();
  function Holder() {
    const changing = useAction<Outcome>(settled);
    return (
      <>
        <CreateAGroup changing={changing} />
        <button onClick={() => changing.run(() => otherChange.promise)}>
          Another change
        </button>
      </>
    );
  }
  render(<Holder />, { wrapper: themed });
  return { sent, settled, otherChange };
}

async function opened() {
  await userEvent.click(screen.getByRole("button", { name: "Create a group" }));
  return screen.getByRole("dialog", { name: "Create a group" });
}

/** Typed into the pool's search, and the pause that hands it on waited out in real time. */
async function searchedFor(text: string) {
  await userEvent.type(
    screen.getByRole("searchbox", { name: "User number or name" }),
    text,
  );
  await act(async () => {
    await new Promise((resolve) => setTimeout(resolve, QUIET_MS));
  });
}

function nameBox(): HTMLElement {
  return screen.getByRole("textbox", { name: "Name" });
}

function keyBox(): HTMLElement {
  return screen.getByRole("textbox", { name: "Key" });
}

function create(): HTMLElement {
  return screen.getByRole("button", { name: "Create" });
}

/** Whichever of a name and a key is given, typed in; nothing is typed for one not given. */
async function typedIn(name: string, key: string) {
  if (name !== "") {
    await userEvent.type(nameBox(), name);
  }
  if (key !== "") {
    await userEvent.type(keyBox(), key);
  }
}

/** A name, a key, and Ada picked: everything the control holds out for. */
async function filledIn() {
  await typedIn("Payroll", "payroll");
  await searchedFor("a");
  await userEvent.click(
    await screen.findByRole("radio", { name: "000130 Ada Lovelace" }),
  );
}

describe("CreateAGroup", () => {
  /** The pool is not this page's subject, so opening shows nobody in it. */
  it("opens a dialog ready for a name, offering nobody from the pool until something is typed", async () => {
    const { sent } = creating();

    const dialog = await opened();

    expect(document.activeElement).toBe(nameBox());
    expect(
      within(dialog).getByText("Who may change its membership"),
    ).toBeInTheDocument();
    expect(within(dialog).queryAllByRole("radio")).toEqual([]);
    expect(sent).not.toHaveBeenCalled();
  });

  it("searches the pool with what was typed, offering each person found by user number and any name held", async () => {
    const { sent } = creating();
    await opened();

    await searchedFor("a");

    const offered = await screen.findAllByRole("radio");
    expect(offered.map((each) => each.closest("label")?.textContent)).toEqual([
      "000130 Ada Lovelace",
      "000150",
    ]);
    expect(requestsTo(sent)).toEqual(["GET /api/pool/search?search=a"]);
  });

  /** Nothing typed yet is nothing to be told off for; the key's hint is said before it is given. */
  it("says nothing under a box before anything is typed, beyond what a key is", async () => {
    creating();

    await opened();

    expect(nameBox()).not.toHaveAttribute("aria-invalid", "true");
    expect(keyBox()).not.toHaveAttribute("aria-invalid", "true");
    expect(keyBox()).toHaveAccessibleDescription(KEY_HINT);
  });

  /** Checked where typed as well as where it arrives. */
  it.each([
    ["name", " Payroll", NAME_LIMIT],
    ["key", "PAYROLL42", KEY_LIMIT],
  ])(
    "says under the %s box what it holds that a group cannot have",
    async (box, typed, limit) => {
      creating();
      await opened();
      const typedInto = box === "name" ? nameBox() : keyBox();

      await userEvent.type(typedInto, typed);

      expect(typedInto).toHaveAttribute("aria-invalid", "true");
      expect(typedInto).toHaveAccessibleDescription(limit);
    },
  );

  it.each([
    ["nothing", "", "", NAME_LIMIT],
    ["a name alone", "Payroll", "", KEY_LIMIT],
    ["a name and a key", "Payroll", "payroll", PICK_FIRST],
  ])(
    "holds the control that creates it where %s is given, saying under it the first thing still missing",
    async (_given, name, key, missing) => {
      creating();
      await opened();

      await typedIn(name, key);

      expect(create()).toHaveAttribute("aria-disabled", "true");
      expect(noticesIn(create().parentElement!)).toEqual([
        { severity: "info", words: missing },
      ]);
    },
  );

  it("offers the control that creates it once a name, a key and a person are all given", async () => {
    const { sent } = creating();
    await opened();

    await filledIn();

    expect(create()).not.toHaveAttribute("aria-disabled");
    expect(noticesIn(create().parentElement!)).toEqual([]);
    expect(requestsTo(sent)).toEqual(["GET /api/pool/search?search=a"]);
  });

  /** The key goes as typed: holding it in capitals is the server's, which answers with the group as held. */
  it("creates the group with the name, the key as typed and the person picked, settles as the group created, and closes", async () => {
    const { sent, settled } = creating({
      "POST /api/groups": [[JSON.stringify(PAYROLL), 201]],
    });
    await opened();
    await filledIn();

    await userEvent.click(create());

    await waitFor(() =>
      expect(settled).toHaveBeenCalledExactlyOnceWith({ created: PAYROLL }),
    );
    expect(requestsTo(sent)).toEqual([
      "GET /api/pool/search?search=a",
      "POST /api/groups",
    ]);
    expect(JSON.parse(String(sent.mock.calls[1]![1]?.body))).toEqual({
      name: "Payroll",
      key: "payroll",
      subjectId: ADA.subjectId,
    });
    await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
  });

  it.each([
    ["GROUP_KEY_TAKEN", 409, "Another group already holds that key."],
    ["GROUP_NAME_TAKEN", 409, "Another group already has that name."],
    ["PERSON_NOT_IN_POOL", 400, "That person is not in the pool."],
  ])(
    "says in place why %s refused it, and stays open with what was given in hand",
    async (code, status, sentence) => {
      const { settled } = creating({
        "POST /api/groups": [[JSON.stringify({ code }), status]],
      });
      const dialog = await opened();
      await filledIn();

      await userEvent.click(create());

      expect(await within(dialog).findByRole("alert")).toHaveTextContent(
        sentence,
      );
      expect(settled).not.toHaveBeenCalled();
      expect(screen.getByRole("dialog", { name: "Create a group" })).toBe(
        dialog,
      );
      expect(nameBox()).toHaveValue("Payroll");
      expect(keyBox()).toHaveValue("payroll");
      expect(
        screen.getByRole("radio", { name: "000130 Ada Lovelace" }),
      ).toBeChecked();
    },
  );

  /** A refusal of the act is said as the rule of the act that was asked. */
  it("says a refusal of the act as the rule of keeping the register", async () => {
    creating({
      "POST /api/groups": [['{"code":"ACT_NOT_PERMITTED"}', 403]],
    });
    const dialog = await opened();
    await filledIn();

    await userEvent.click(create());

    expect(await within(dialog).findByRole("alert")).toHaveTextContent(
      "The register of groups is seen and changed only by a role that may keep it.",
    );
  });

  it("cannot be abandoned while the group is being created, by its own control or Escape", async () => {
    const answer = deferred<readonly [string, number]>();
    creating({ "POST /api/groups": [answer.promise] });
    const dialog = await opened();
    await filledIn();
    await userEvent.click(create());
    const abandon = screen.getByRole("button", { name: "Cancel" });
    abandon.focus();

    await userEvent.keyboard("{Enter}[Space]{Escape}");
    await act(async () => {
      await new Promise((resolve) =>
        setTimeout(resolve, theme.transitions.duration.leavingScreen * 2),
      );
    });

    expect(abandon).toHaveAttribute("aria-disabled", "true");
    expect(abandon).toHaveAccessibleDescription(
      "It is being created, and that can no longer be called back.",
    );
    expect(screen.getByRole("dialog", { name: "Create a group" })).toBe(dialog);
  });

  it("settles as the group created once the answer lands, whatever was tried meanwhile", async () => {
    const answer = deferred<readonly [string, number]>();
    const { settled } = creating({ "POST /api/groups": [answer.promise] });
    await opened();
    await filledIn();
    await userEvent.click(create());
    screen.getByRole("button", { name: "Cancel" }).focus();
    await userEvent.keyboard("{Escape}");

    await act(async () => answer.settle([JSON.stringify(PAYROLL), 201]));

    await waitFor(() =>
      expect(settled).toHaveBeenCalledExactlyOnceWith({ created: PAYROLL }),
    );
  });

  it("abandons it from its own control, creating nothing", async () => {
    const { sent, settled } = creating();
    await opened();
    await filledIn();

    await userEvent.click(screen.getByRole("button", { name: "Cancel" }));

    await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
    expect(settled).not.toHaveBeenCalled();
    expect(requestsTo(sent)).toEqual(["GET /api/pool/search?search=a"]);
  });

  /** Any change out under the same action holds it: two at once would settle in whichever order they land. */
  it("holds Create a group while another change is out, opening nothing", async () => {
    creating();
    await userEvent.click(
      screen.getByRole("button", { name: "Another change" }),
    );
    const control = screen.getByRole("button", { name: "Create a group" });
    control.focus();

    await userEvent.keyboard("{Enter}[Space]");

    expect(control).toHaveAttribute("aria-disabled", "true");
    expect(screen.queryByRole("dialog")).toBeNull();
  });

  it("offers Create a group again once the other change settles", async () => {
    const { otherChange } = creating();
    await userEvent.click(
      screen.getByRole("button", { name: "Another change" }),
    );

    await act(async () => otherChange.settle({ created: PAYROLL }));

    expect(
      screen.getByRole("button", { name: "Create a group" }),
    ).not.toHaveAttribute("aria-disabled");
  });

  it("opens again with nothing typed, nobody picked, and no refusal of the last opening said", async () => {
    creating({
      "POST /api/groups": [['{"code":"GROUP_KEY_TAKEN"}', 409]],
    });
    await opened();
    await filledIn();
    await userEvent.click(create());
    await screen.findByRole("alert");
    await userEvent.click(screen.getByRole("button", { name: "Cancel" }));
    await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());

    const dialog = await opened();

    expect(nameBox()).toHaveValue("");
    expect(keyBox()).toHaveValue("");
    expect(within(dialog).queryAllByRole("radio")).toEqual([]);
    expect(within(dialog).queryByRole("alert")).toBeNull();
  });
});
