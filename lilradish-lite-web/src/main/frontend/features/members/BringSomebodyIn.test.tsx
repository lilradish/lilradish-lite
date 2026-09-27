import { ThemeProvider } from "@mui/material/styles";
import { act, render, screen, waitFor, within } from "@testing-library/react";
import { userEvent } from "@testing-library/user-event";
import { useState, type ReactNode } from "react";
import { describe, expect, it, vi } from "vitest";

import { useAction } from "../../lib/request/useAction";
import { theme } from "../../lib/theme/theme";
import { requestsTo, serving, type Reply } from "../../testutil/answering";
import { BringSomebodyIn } from "./BringSomebodyIn";
import type { Asked, Outcome } from "./changes";

/** How long typing has to pause before what was typed is sent. */
const QUIET_MS = 300;

const GROUP = "00000003-0000-4000-8000-0000000009d1";

const SEARCH = `GET /api/groups/${GROUP}/pool/search`;

const MEMBERS = `POST /api/groups/${GROUP}/members`;

const OLIVE = {
  subjectId: "00000002-0000-4000-8000-0000000009d1",
  userId: "0009d1",
  displayName: "Olive Out",
};

const OLIVE_TAKEN_ON = {
  ...OLIVE,
  roles: ["owner", "operator"],
  lastChangingRoles: [],
  removable: true,
};

const FOUND: Reply = [JSON.stringify({ items: [OLIVE], more: false }), 200];

function themed({ children }: { readonly children: ReactNode }) {
  return <ThemeProvider theme={theme}>{children}</ThemeProvider>;
}

/** The control and its dialog over the real action they are handed, held as a page holds it. */
function bringing(routes: Readonly<Record<string, readonly Reply[]>> = {}) {
  const sent = serving({ [SEARCH]: [FOUND], ...routes });
  const settled = vi.fn();
  const told = vi.fn();
  const moved = vi.fn();
  function Holder() {
    const [asked, setAsked] = useState<Asked | null>(null);
    const changing = useAction<Outcome>(settled);
    return (
      <BringSomebodyIn
        groupId={GROUP}
        changing={changing}
        refused={asked?.place === "bringIn" ? changing.problem : null}
        onAsk={(asking) => {
          told(asking);
          setAsked(asking);
        }}
        onMoved={moved}
      />
    );
  }
  render(<Holder />, { wrapper: themed });
  return { sent, settled, told, moved };
}

async function opened() {
  await userEvent.click(
    screen.getByRole("button", { name: "Bring somebody in" }),
  );
  return screen.getByRole("dialog", { name: "Bring somebody in" });
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

function takeOn(): HTMLElement {
  return screen.getByRole("button", { name: "Take them on" });
}

describe("BringSomebodyIn", () => {
  /** The pool is not this page's subject, and a picker that opened showing it would publish it. */
  it("opens a dialog that offers nobody until something is typed, with a choice of every role and none chosen", async () => {
    const { sent } = bringing();

    const dialog = await opened();

    expect(document.activeElement).toBe(
      within(dialog).getByRole("searchbox", { name: "User number or name" }),
    );
    expect(within(dialog).queryAllByRole("radio")).toEqual([]);
    const roles = within(dialog).getByRole("group", { name: "Roles here" });
    expect(
      within(roles)
        .getAllByRole("checkbox")
        .map((each) => [
          each.closest("label")?.textContent,
          (each as HTMLInputElement).checked,
        ]),
    ).toEqual([
      ["Operator", false],
      ["Overseer", false],
      ["Owner", false],
    ]);
    expect(sent).not.toHaveBeenCalled();
  });

  it("searches the pool from inside the group with what was typed", async () => {
    const { sent } = bringing();
    await opened();

    await searchedFor("ol");

    const offered = await screen.findAllByRole("radio");
    expect(offered.map((each) => each.closest("label")?.textContent)).toEqual([
      "0009d1 Olive Out",
    ]);
    expect(requestsTo(sent)).toEqual([
      `GET /api/groups/${GROUP}/pool/search?search=ol`,
    ]);
  });

  /** A search refused for what the reader may do, or for the group, says their standing moved under the page. */
  it.each([
    ['{"code":"ACT_NOT_PERMITTED"}', 403, 1],
    ['{"code":"GROUP_NOT_IN_VIEW"}', 404, 1],
    ['{"code":"PEOPLE_SEARCH_UNUSABLE"}', 400, 0],
  ])(
    "says the refused search %s in the dialog, and tells the page the reader moved where it says so",
    async (body, status, told) => {
      const { moved } = bringing({ [SEARCH]: [[body, status]] });
      const dialog = await opened();

      await searchedFor("ol");

      expect(await within(dialog).findByRole("alert")).toBeInTheDocument();
      expect(moved).toHaveBeenCalledTimes(told);
      expect(within(dialog).queryAllByRole("radio")).toEqual([]);
    },
  );

  /** Nobody picked, then nobody holding anything: each is said, in the order the dialog asks for them. */
  it("holds the control that takes somebody on until somebody and a role are picked, saying which is missing", async () => {
    const { sent } = bringing();
    await opened();

    expect(takeOn()).toHaveAttribute("aria-disabled", "true");
    expect(takeOn()).toHaveAccessibleDescription("Pick somebody first.");

    await searchedFor("ol");
    await userEvent.click(await screen.findByRole("radio"));

    expect(takeOn()).toHaveAttribute("aria-disabled", "true");
    expect(takeOn()).toHaveAccessibleDescription(
      "Pick at least one role: somebody holding nothing here is not a member.",
    );

    await userEvent.click(screen.getByRole("checkbox", { name: "Owner" }));

    expect(takeOn()).not.toHaveAttribute("aria-disabled", "true");
    expect(requestsTo(sent)).toEqual([
      `GET /api/groups/${GROUP}/pool/search?search=ol`,
    ]);
  });

  it("takes the person picked on holding the roles chosen, settles as the group now holds them, and closes", async () => {
    const { sent, settled, told } = bringing({
      [MEMBERS]: [[JSON.stringify(OLIVE_TAKEN_ON), 201]],
    });
    await opened();
    await searchedFor("ol");
    await userEvent.click(await screen.findByRole("radio"));
    await userEvent.click(screen.getByRole("checkbox", { name: "Owner" }));
    await userEvent.click(screen.getByRole("checkbox", { name: "Overseer" }));
    await userEvent.click(screen.getByRole("checkbox", { name: "Overseer" }));
    await userEvent.click(screen.getByRole("checkbox", { name: "Operator" }));

    await userEvent.click(takeOn());

    await waitFor(() => expect(settled).toHaveBeenCalledOnce());
    expect(settled).toHaveBeenCalledWith({
      broughtIn: {
        ...OLIVE,
        roles: new Set(["owner", "operator"]),
        lastChangingRoles: new Set(),
        removable: true,
      },
    });
    expect(told).toHaveBeenCalledWith({ place: "bringIn" });
    const posted = sent.mock.calls.find(([, init]) => init?.method === "POST")!;
    expect(JSON.parse(String(posted[1]?.body))).toEqual({
      subjectId: OLIVE.subjectId,
      roles: ["operator", "owner"],
    });
    await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
  });

  it("says a refusal inside the dialog that asked, and keeps it open", async () => {
    const { settled } = bringing({
      [MEMBERS]: [['{"code":"PERSON_ALREADY_IN_GROUP"}', 409]],
    });
    const dialog = await opened();
    await searchedFor("ol");
    await userEvent.click(await screen.findByRole("radio"));
    await userEvent.click(screen.getByRole("checkbox", { name: "Operator" }));

    await userEvent.click(takeOn());

    expect(await within(dialog).findByRole("alert")).toHaveTextContent(
      "That person already holds a role in this group.",
    );
    expect(screen.getByRole("dialog")).toBe(dialog);
    expect(settled).not.toHaveBeenCalled();
  });

  /** Each opening starts from nothing typed, nobody picked and no role chosen. */
  it("opens afresh each time, with no role chosen from the last", async () => {
    bringing();
    await opened();
    await userEvent.click(screen.getByRole("checkbox", { name: "Owner" }));
    await userEvent.click(screen.getByRole("button", { name: "Cancel" }));
    await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());

    await opened();

    expect(screen.getByRole("checkbox", { name: "Owner" })).not.toBeChecked();
  });
});
