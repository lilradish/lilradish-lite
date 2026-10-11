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
import type { Outcome } from "./changes";
import { RenameGroup } from "./RenameGroup";

const PAYROLL = {
  groupId: "00000003-0000-4000-8000-000000000701",
  key: "PAYROLL",
  name: "Payroll",
  canBeAdministered: true,
  memberCount: 2,
};

const RENAMED = { ...PAYROLL, name: "Salaries" };

const ADDRESS = `/api/groups/${PAYROLL.groupId}`;

const NAME_LIMIT =
  "A group's name is one to 128 characters on one line: no space but single plain ones between words, none at either end, and something in it that shows.";

function themed({ children }: { readonly children: ReactNode }) {
  return <ThemeProvider theme={theme}>{children}</ThemeProvider>;
}

function renaming(routes: Readonly<Record<string, readonly Reply[]>> = {}) {
  const sent = serving(routes);
  const settled = vi.fn();
  function Holder() {
    const [open, setOpen] = useState(true);
    const changing = useAction<Outcome>(settled);
    return (
      <RenameGroup
        group={PAYROLL}
        open={open}
        changing={changing}
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

/** The name box emptied and given another name. */
async function renamedTo(name: string) {
  await userEvent.clear(nameBox());
  await userEvent.type(nameBox(), name);
}

describe("RenameGroup", () => {
  it("opens holding the name as it stands, ready to be changed", () => {
    renaming();

    expect(nameBox()).toHaveValue("Payroll");
    expect(nameBox().tagName).toBe("TEXTAREA");
    expect(document.activeElement).toBe(nameBox());
  });

  /** A box that cannot be typed in is a control drawn dead; the key is said, not offered. */
  it("explains the stable key on the name control without offering to edit it", async () => {
    const { dialog } = renaming();

    expect(within(dialog).queryByRole("textbox", { name: "Key" })).toBeNull();
    await userEvent.hover(nameBox());
    const hint = await screen.findByRole("tooltip");
    expect(hint).toHaveTextContent("PAYROLL");
    expect(hint).toHaveTextContent(
      "Everything cites the group by its key, so it is never changed.",
    );
  });

  it.each([
    ["the name it has", "Payroll", "Type a name other than the one it has."],
    ["one a group cannot have", "Payroll ", NAME_LIMIT],
  ])(
    "holds the rename while the name is %s, saying so under the control",
    async (_holding, name, reason) => {
      renaming();

      await renamedTo(name);

      expect(rename()).toBeDisabled();
      expect(rename()).toHaveAccessibleDescription(reason);
      expect(nameBox().closest("form")).toHaveTextContent(reason);
      expect(
        screen.getByRole("button", { name: "Cancel" }),
      ).not.toHaveAccessibleDescription(reason);
      expect(noticesIn(nameBox().closest("form")!)).toEqual([]);
    },
  );

  it("says under the box a name a group cannot have", async () => {
    renaming();

    await renamedTo("Payroll ");

    expect(nameBox()).toHaveAttribute("aria-invalid", "true");
    expect(nameBox()).toHaveAccessibleDescription(NAME_LIMIT);
  });

  it("sends nothing when the rename it holds is pressed", async () => {
    const { sent } = renaming();
    act(() => rename().focus());

    await userEvent.keyboard("{Enter}[Space]");

    expect(sent).not.toHaveBeenCalled();
  });

  it("renames the group, sending the new name alone, settles as the group renamed, and closes", async () => {
    const { sent, settled } = renaming({
      [`PATCH ${ADDRESS}`]: [[JSON.stringify(RENAMED), 200]],
    });
    await renamedTo("Salaries");

    await userEvent.click(rename());

    await waitFor(() =>
      expect(settled).toHaveBeenCalledExactlyOnceWith({ renamed: RENAMED }),
    );
    expect(requestsTo(sent)).toEqual([`PATCH ${ADDRESS}`]);
    expect(JSON.parse(String(sent.mock.calls[0]![1]?.body))).toEqual({
      name: "Salaries",
    });
    await waitFor(() =>
      expect(screen.queryByRole("textbox", { name: "Name" })).toBeNull(),
    );
  });

  it("says in place why a name another group has refused it, and stays open with the name typed in hand", async () => {
    const { settled, dialog } = renaming({
      [`PATCH ${ADDRESS}`]: [['{"code":"GROUP_NAME_TAKEN"}', 409]],
    });
    await renamedTo("Finance");

    await userEvent.click(rename());

    expect(await within(dialog).findByRole("alert")).toHaveTextContent(
      "Another group already has that name.",
    );
    expect(settled).not.toHaveBeenCalled();
    expect(nameBox()).toHaveValue("Finance");
  });

  it("abandons it from its own control, sending nothing and settling nothing", async () => {
    const { sent, settled } = renaming();
    await renamedTo("Salaries");

    await userEvent.click(screen.getByRole("button", { name: "Cancel" }));

    await waitFor(() =>
      expect(screen.queryByRole("textbox", { name: "Name" })).toBeNull(),
    );
    expect(settled).not.toHaveBeenCalled();
    expect(sent).not.toHaveBeenCalled();
  });

  it("cannot be abandoned while the group is being renamed", async () => {
    const answer = deferred<readonly [string, number]>();
    const { dialog } = renaming({ [`PATCH ${ADDRESS}`]: [answer.promise] });
    await renamedTo("Salaries");
    await userEvent.click(rename());
    const abandon = screen.getByRole("button", { name: "Cancel" });
    act(() => abandon.focus());

    await userEvent.keyboard("{Enter}{Escape}");

    expect(abandon).toBeDisabled();
    expect(abandon).toHaveAccessibleDescription(
      "It is being renamed, and that can no longer be called back.",
    );
    expect(screen.getByRole("textbox", { name: "Name" }).closest("form")!).toBe(
      dialog,
    );
  });
});
