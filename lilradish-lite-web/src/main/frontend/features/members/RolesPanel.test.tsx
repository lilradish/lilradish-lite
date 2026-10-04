import { buttonClasses } from "@mui/material/Button";
import { ThemeProvider } from "@mui/material/styles";
import { act, render, screen, waitFor, within } from "@testing-library/react";
import { userEvent } from "@testing-library/user-event";
import { useState, type ReactNode } from "react";
import { describe, expect, it, vi } from "vitest";

import type { MemberPanel } from "../../api/groups/{groupId}/members";
import { useAction } from "../../lib/request/useAction";
import { theme } from "../../lib/theme/theme";
import { requestsTo, serving, type Reply } from "../../testutil/answering";
import { drawnAtDepth, drawnOf } from "../../testutil/depths";
import { noticesIn } from "../../testutil/notices";
import type { Asked, Outcome } from "./changes";
import { RolesPanel } from "./RolesPanel";

const GROUP = "00000003-0000-4000-8000-0000000009c1";

const SUBJECT = "00000002-0000-4000-8000-0000000009c1";

const MEMBER = `/api/groups/${GROUP}/members/${SUBJECT}`;

const GLINN: MemberPanel = {
  subjectId: SUBJECT,
  userId: "0009c1",
  displayName: "Glinn Havven",
  roles: new Set(["operator"]),
  lastChangingRoles: new Set(),
  removable: true,
};

const LAST_OWNER: MemberPanel = {
  ...GLINN,
  roles: new Set(["owner", "operator"]),
  lastChangingRoles: new Set(["owner"]),
  removable: false,
};

const HOLDING_NOTHING: MemberPanel = { ...GLINN, roles: new Set() };

const LAST_CHANGER =
  "Nobody else here may change this group's membership, so what lets them is not taken away.";

/** As the server writes a member, which is not the shape this side holds one in. */
function documentOf(member: MemberPanel): string {
  return JSON.stringify({
    ...member,
    roles: [...member.roles],
    lastChangingRoles: [...member.lastChangingRoles],
  });
}

function themed({ children }: { readonly children: ReactNode }) {
  return <ThemeProvider theme={theme}>{children}</ThemeProvider>;
}

interface Reading {
  readonly member?: MemberPanel;
  readonly mayChange?: boolean;
  readonly routes?: Readonly<Record<string, readonly Reply[]>>;
}

/**
 * The panel over the real action it is handed, held as a page holds it: the
 * holder settles every change, keeps who asked, and hands a refusal back to
 * the panel. Only the server is stood in for.
 */
function reading({
  member = GLINN,
  mayChange = true,
  routes = {},
}: Reading = {}) {
  const sent = serving(routes);
  const settled = vi.fn();
  const told = vi.fn();
  let standingMoved: (may: boolean) => void = () => {};
  function Holder() {
    const [asked, setAsked] = useState<Asked | null>(null);
    const [may, setMay] = useState(mayChange);
    standingMoved = setMay;
    const changing = useAction<Outcome>(settled);
    return (
      <RolesPanel
        groupId={GROUP}
        member={member}
        changing={changing}
        waiting={false}
        mayChange={may}
        refused={
          changing.problem !== null &&
          asked !== null &&
          asked.place !== "bringIn"
            ? { place: asked.place, problem: changing.problem }
            : null
        }
        onAsk={(asking) => {
          told(asking);
          setAsked(asking);
        }}
      />
    );
  }
  render(<Holder />, { wrapper: themed });
  return {
    sent,
    settled,
    told,
    async mayChangeNoLonger() {
      await act(async () => standingMoved(false));
    },
  };
}

function roleRows(): string[][] {
  return within(screen.getByRole("list"))
    .getAllByRole("listitem")
    .map((row) => [
      row.querySelector("bdi")?.textContent ?? "",
      within(row).getByText(/^(Held|Not held)$/).textContent ?? "",
      String(
        within(row).queryByRole<HTMLInputElement>("switch")?.checked ?? "",
      ),
    ]);
}

describe("RolesPanel", () => {
  /** Every role the group has, whether or not it is held, and never only the highest held. */
  it("lists every role the group has, whether the member holds each, and a control giving it or taking it away", () => {
    reading({ member: { ...GLINN, roles: new Set(["operator", "owner"]) } });

    expect(
      screen.getByRole("heading", { level: 3, name: "Roles" }),
    ).toBeInTheDocument();
    expect(roleRows()).toEqual([
      ["Operator", "Held", "true"],
      ["Overseer", "Not held", "false"],
      ["Owner", "Held", "true"],
    ]);
  });

  /** Taking a role away and taking somebody out warn; giving a role undoes nothing and is drawn as any other. */
  it("draws taking a role away and removing the member in the warning colour, and giving a role in none", () => {
    reading();

    expect(
      screen.getByRole("switch", { name: "Operator" }).parentElement,
    ).toHaveClass("MuiSwitch-colorWarning");
    expect(
      screen.getByRole("switch", { name: "Overseer" }).parentElement,
    ).toHaveClass("MuiSwitch-colorPrimary");
    expect(
      screen.getByRole("button", { name: "Remove from the group" }),
    ).toHaveClass(buttonClasses.colorWarning);
  });

  /** Spelt by whoever published it; shown, and offered nothing this build cannot address. */
  it("shows a role this build has no words for as it was spelt, held and offering nothing", () => {
    reading({ member: { ...GLINN, roles: new Set(["captain"]) } });

    expect(roleRows().at(-1)).toEqual(["captain", "Held", ""]);
  });

  /** Shown to a reader who may see the members and not change them, and offering nothing. */
  it("offers nothing, and no removal, to a reader who may not change the membership", () => {
    const { sent } = reading({ mayChange: false });

    expect(roleRows()).toEqual([
      ["Operator", "Held", ""],
      ["Overseer", "Not held", ""],
      ["Owner", "Not held", ""],
    ]);
    expect(screen.queryAllByRole("button")).toEqual([]);
    expect(noticesIn(document.body)).toEqual([]);
    expect(sent).not.toHaveBeenCalled();
  });

  it("gives a role not held, and settles as the group now holds the member", async () => {
    const given = { ...GLINN, roles: new Set(["operator", "overseer"]) };
    const { sent, settled, told } = reading({
      routes: { [`PUT ${MEMBER}/roles/overseer`]: [[documentOf(given), 200]] },
    });

    await userEvent.click(screen.getByRole("switch", { name: "Overseer" }));

    await waitFor(() => expect(settled).toHaveBeenCalledOnce());
    expect(settled).toHaveBeenCalledWith({ changed: given });
    expect(told).toHaveBeenCalledWith({ place: "roles", of: GLINN });
    expect(requestsTo(sent)).toEqual([`PUT ${MEMBER}/roles/overseer`]);
  });

  it("takes a role held away, and settles as the group now holds the member", async () => {
    const { sent, settled } = reading({
      routes: {
        [`DELETE ${MEMBER}/roles/operator`]: [
          [documentOf(HOLDING_NOTHING), 200],
        ],
      },
    });

    await userEvent.click(screen.getByRole("switch", { name: "Operator" }));

    await waitFor(() =>
      expect(settled).toHaveBeenCalledWith({ changed: HOLDING_NOTHING }),
    );
    expect(requestsTo(sent)).toEqual([`DELETE ${MEMBER}/roles/operator`]);
  });

  it("disables taking the last role that lets anybody change membership and omits removal", async () => {
    const { sent } = reading({ member: LAST_OWNER });

    expect(roleRows()).toEqual([
      ["Operator", "Held", "true"],
      ["Overseer", "Not held", "false"],
      ["Owner", "Held", "true"],
    ]);
    const owner = screen.getByRole<HTMLInputElement>("switch", {
      name: "Owner",
    });
    expect(owner).toBeChecked();
    expect(owner).toBeDisabled();
    expect(screen.getByRole("switch", { name: "Operator" })).toBeEnabled();
    await userEvent.click(owner.closest(".MuiSwitch-root")!);
    expect(owner).toBeChecked();
    expect(requestsTo(sent)).toEqual([]);
    expect(
      screen.queryByRole("button", { name: "Remove from the group" }),
    ).toBeNull();
    expect(noticesIn(document.body)).toEqual([
      { severity: "info", words: LAST_CHANGER },
    ]);
  });

  /**
   * Taking somebody out is the server's to judge, apart from any one role:
   * holding two roles that may change the membership, neither is the last,
   * and still nobody else may.
   */
  it("offers no removal of a member the server says may not be taken out, whatever roles are offered", () => {
    reading({
      member: {
        ...GLINN,
        roles: new Set(["owner", "overseer"]),
        lastChangingRoles: new Set(),
        removable: false,
      },
    });

    expect(roleRows().map((row) => row[2])).toEqual(["false", "true", "true"]);
    expect(
      screen.queryByRole("button", { name: "Remove from the group" }),
    ).toBeNull();
    expect(noticesIn(document.body)).toEqual([
      { severity: "info", words: LAST_CHANGER },
    ]);
  });

  /** The control the keyboard was on goes with the right to use it, and the keyboard stays in the panel. */
  it("puts the keyboard on the roles' heading when the control it was on goes with the right to change them", async () => {
    const { mayChangeNoLonger } = reading();
    screen.getByRole("switch", { name: "Overseer" }).focus();

    await mayChangeNoLonger();

    expect(screen.queryAllByRole("button")).toEqual([]);
    expect(document.activeElement).toBe(
      screen.getByRole("heading", { level: 3, name: "Roles" }),
    );
  });

  it("leaves the keyboard where it is when it was on nothing the right to change them took away", async () => {
    const { mayChangeNoLonger } = reading();
    const elsewhere = document.createElement("button");
    document.body.append(elsewhere);
    elsewhere.focus();

    await mayChangeNoLonger();

    expect(document.activeElement).toBe(elsewhere);
    elsewhere.remove();
  });

  /** The list is named by the heading above it, so it is read as the member's roles. */
  it("names the list of roles by its heading", () => {
    reading();

    expect(screen.getByRole("list")).toHaveAccessibleName("Roles");
  });

  it("sets the roles apart as a region on a ground of its own, leaving the member's removal outside it", () => {
    reading();

    const roles = screen.getByRole("region", { name: "Roles" });

    expect(screen.getAllByRole("region")).toEqual([roles]);
    expect(drawnOf(roles)).toEqual(drawnAtDepth(3));
    expect(roles).toContainElement(screen.getByRole("list"));
    expect(roles).not.toContainElement(
      screen.getByRole("button", { name: "Remove from the group" }),
    );
  });

  /** Nothing to withhold is said to a reader offered nothing to withhold. */
  it("says nothing of the last who may change the membership to a reader who may not change it", () => {
    reading({ member: LAST_OWNER, mayChange: false });

    expect(noticesIn(document.body)).toEqual([]);
  });

  /** Their last role just taken, the panel stays on them, and a role given makes them a member again. */
  it("shows somebody holding nothing as a member no longer, offering every role and no removal", async () => {
    const back = { ...GLINN, roles: new Set(["overseer"]) };
    const { settled } = reading({
      member: HOLDING_NOTHING,
      routes: { [`PUT ${MEMBER}/roles/overseer`]: [[documentOf(back), 200]] },
    });

    expect(noticesIn(document.body)).toEqual([
      {
        severity: "info",
        words:
          "No longer a member of this group. Giving a role here makes them one again.",
      },
    ]);
    expect(roleRows().map((row) => row[2])).toEqual([
      "false",
      "false",
      "false",
    ]);
    expect(
      screen.queryByRole("button", { name: "Remove from the group" }),
    ).toBeNull();

    await userEvent.click(screen.getByRole("switch", { name: "Overseer" }));

    await waitFor(() =>
      expect(settled).toHaveBeenCalledWith({ changed: back }),
    );
  });

  it("cancels removal without sending a request", async () => {
    const { sent } = reading();
    await userEvent.click(
      screen.getByRole("button", { name: "Remove from the group" }),
    );
    await userEvent.click(screen.getByRole("button", { name: "Cancel" }));
    await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
    expect(requestsTo(sent)).toEqual([]);
  });

  it("removes the member from the group, and settles as their removal", async () => {
    const { sent, settled, told } = reading({
      routes: { [`DELETE ${MEMBER}`]: [["", 204]] },
    });

    await userEvent.click(
      screen.getByRole("button", { name: "Remove from the group" }),
    );
    expect(
      screen.getByRole("dialog", {
        name: /Remove.*Glinn Havven.*from the group\?/,
      }),
    ).toHaveTextContent(
      "They will hold no role here. Bringing them back means taking them on again.",
    );
    expect(requestsTo(sent)).toEqual([]);
    await userEvent.click(screen.getByRole("button", { name: "Remove" }));

    await waitFor(() =>
      expect(settled).toHaveBeenCalledWith({ removed: GLINN }),
    );
    expect(told).toHaveBeenCalledWith({ place: "removal", of: GLINN });
    expect(requestsTo(sent)).toEqual([`DELETE ${MEMBER}`]);
  });

  /** Said beside the control that asked, in the words of the rule inside the group. */
  it.each([
    [
      "a role",
      "Overseer",
      `PUT ${MEMBER}/roles/overseer`,
      '{"code":"ACT_NOT_PERMITTED"}',
      403,
      "A group's membership is changed only by a role in it that may change it.",
    ],
    [
      "a removal",
      "Remove from the group",
      `DELETE ${MEMBER}`,
      '{"code":"LAST_MEMBERSHIP_CHANGER"}',
      409,
      "Nobody else in this group could change its membership.",
    ],
  ])(
    "says a refused change to %s beside it, in the reader's words",
    async (_case, control, route, body, status, said) => {
      const { settled } = reading({ routes: { [route]: [[body, status]] } });

      await userEvent.click(
        screen.getByRole(_case === "a role" ? "switch" : "button", {
          name: control,
        }),
      );
      if (_case === "a removal") {
        await userEvent.click(screen.getByRole("button", { name: "Remove" }));
      }

      expect(await screen.findByRole("alert")).toHaveTextContent(said);
      expect(settled).not.toHaveBeenCalled();
    },
  );
});
