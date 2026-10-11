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
import { deferred } from "../../testutil/deferred";
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
  "No one else here can change who is in this group, so they can't be removed, and the role that lets them do it can't be withdrawn.";

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
  readonly waiting?: boolean;
  readonly routes?: Readonly<Record<string, readonly Reply[]>>;
}

/** The panel over the real action, held as a page holds it; only the server is stood in for. */
function reading({
  member = GLINN,
  mayChange = true,
  waiting = false,
  routes = {},
}: Reading = {}) {
  const sent = serving(routes);
  const settled = vi.fn();
  const told = vi.fn();
  let standingMoved: (may: boolean) => void = () => {};
  function Holder() {
    const [asked, setAsked] = useState<Asked | null>(null);
    const [may, setMay] = useState(mayChange);
    const [shown, setShown] = useState(member);
    standingMoved = setMay;
    const changing = useAction<Outcome>((outcome) => {
      settled(outcome);
      if ("changed" in outcome) setShown(outcome.changed);
    });
    return (
      <RolesPanel
        groupId={GROUP}
        member={shown}
        changing={changing}
        waiting={waiting}
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

/** Each role's row as the reader reads it: its words, whether it is held, and the control at its end. */
function roleRows(): string[][] {
  return within(screen.getByRole("list"))
    .getAllByRole("listitem")
    .map((row) => [
      row.querySelector("bdi")?.textContent ?? "",
      within(row).getByText(/^(Held|Not held)$/).textContent ?? "",
      within(row).queryByRole("button")?.textContent ?? "",
    ]);
}

function roleControls(): HTMLElement[] {
  return within(screen.getByRole("list")).getAllByRole("button");
}

describe("RolesPanel", () => {
  it("rules the existing roles off from the title without adding person facts", () => {
    reading();
    const part = screen.getByRole("region", { name: "Roles" });
    expect(part.parentElement!).toHaveStyle({
      borderTopStyle: "solid",
      borderTopWidth: "1px",
      marginTop: "18px",
      paddingTop: "18px",
      marginLeft: "calc(-1 * var(--card-side-padding))",
      paddingLeft: "var(--card-side-padding)",
    });
    expect(part.querySelector("dl")).toBeNull();
    expect(part).not.toContainElement(
      screen.getByRole("button", { name: "Remove from the group" }),
    );
  });
  /** Every role the group has, whether or not it is held, and never only the highest held. */
  it("lists every role the group has, whether the member holds each, and a button withdrawing it or granting it", () => {
    reading({ member: { ...GLINN, roles: new Set(["operator", "owner"]) } });

    expect(
      screen.getByRole("heading", { level: 3, name: "Roles" }),
    ).toBeInTheDocument();
    expect(roleRows()).toEqual([
      ["Operator", "Held", "Withdraw Operator"],
      ["Overseer", "Not held", "Grant Overseer"],
      ["Owner", "Held", "Withdraw Owner"],
    ]);
    expect(screen.queryAllByRole("switch")).toEqual([]);
    expect(screen.queryAllByRole("checkbox")).toEqual([]);
  });

  /** Withdrawing a role and taking somebody out warn; granting a role undoes nothing and is drawn as any other. */
  it("draws withdrawing a role and removing the member in the warning colour, and granting a role in none", () => {
    reading();

    expect(
      screen
        .getAllByRole("button")
        .filter((each) => each.classList.contains(buttonClasses.colorWarning))
        .map((each) => each.textContent),
    ).toEqual(["Withdraw Operator", "Remove from the group"]);
    expect(screen.getByRole("button", { name: "Grant Overseer" })).toHaveClass(
      buttonClasses.outlined,
      buttonClasses.colorPrimary,
    );
    expect(
      screen.getByRole("button", { name: "Withdraw Operator" }),
    ).toHaveClass(buttonClasses.outlined);
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

  it("grants a role not held, and settles as the group now holds the member", async () => {
    const given = { ...GLINN, roles: new Set(["operator", "overseer"]) };
    const { sent, settled, told } = reading({
      routes: { [`PUT ${MEMBER}/roles/overseer`]: [[documentOf(given), 200]] },
    });

    await userEvent.click(
      screen.getByRole("button", { name: "Grant Overseer" }),
    );

    await waitFor(() => expect(settled).toHaveBeenCalledOnce());
    expect(settled).toHaveBeenCalledWith({ changed: given });
    expect(told).toHaveBeenCalledWith({ place: "roles", of: GLINN });
    expect(requestsTo(sent)).toEqual([`PUT ${MEMBER}/roles/overseer`]);
  });

  it("withdraws a role held, and settles as the group now holds the member", async () => {
    const { sent, settled } = reading({
      routes: {
        [`DELETE ${MEMBER}/roles/operator`]: [
          [documentOf(HOLDING_NOTHING), 200],
        ],
      },
    });

    await userEvent.click(
      screen.getByRole("button", { name: "Withdraw Operator" }),
    );

    await waitFor(() =>
      expect(settled).toHaveBeenCalledWith({ changed: HOLDING_NOTHING }),
    );
    expect(requestsTo(sent)).toEqual([`DELETE ${MEMBER}/roles/operator`]);
  });

  /** A second change out at once would be answered in whichever order the two land. */
  it.each([
    ["something else the reader can see is out", true, []],
    ["one change is out", false, [`PUT ${MEMBER}/roles/overseer`]],
  ] as const)(
    "holds every role control while %s, and asks nothing more when one is pressed",
    async (_case, waiting, asked) => {
      const held = deferred<readonly [string, number]>();
      const { sent } = reading({
        waiting,
        routes: { [`PUT ${MEMBER}/roles/overseer`]: [held.promise] },
      });
      if (!waiting) {
        await userEvent.click(
          screen.getByRole("button", { name: "Grant Overseer" }),
        );
      }

      for (const control of roleControls()) {
        await act(() => control.focus());
        await userEvent.keyboard("{Enter}[Space]");
      }

      expect(
        roleControls().map((each) => each.getAttribute("aria-disabled")),
      ).toEqual(["true", "true", "true"]);
      expect(requestsTo(sent)).toEqual(asked);
    },
  );

  it("reads the last role as a fact, offers no way to take it, and omits removal", async () => {
    const { sent } = reading({ member: LAST_OWNER });

    expect(roleRows()).toEqual([
      ["Operator", "Held", "Withdraw Operator"],
      ["Overseer", "Not held", "Grant Overseer"],
      ["Owner", "Held", ""],
    ]);
    expect(screen.queryByRole("button", { name: "Withdraw Owner" })).toBeNull();
    expect(
      screen.getByRole("button", { name: "Withdraw Operator" }),
    ).not.toHaveAttribute("aria-disabled");
    await userEvent.click(screen.getByText("Owner"));
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

    expect(roleRows().map((row) => row[2])).toEqual([
      "Grant Operator",
      "Withdraw Overseer",
      "Withdraw Owner",
    ]);
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
    screen.getByRole("button", { name: "Grant Overseer" }).focus();

    await mayChangeNoLonger();

    expect(screen.queryAllByRole("button")).toEqual([]);
    expect(document.activeElement).toBe(
      screen.getByRole("heading", { level: 3, name: "Roles" }),
    );
  });

  /** The server may answer a role granted as one nobody else could change the membership without, which offers it no longer. */
  it("puts the keyboard on the roles' heading when the control just pressed is no longer offered once the change answers", async () => {
    const answer: MemberPanel = {
      ...GLINN,
      roles: new Set(["operator", "owner"]),
      lastChangingRoles: new Set(["owner"]),
      removable: false,
    };
    const { settled } = reading({
      routes: { [`PUT ${MEMBER}/roles/owner`]: [[documentOf(answer), 200]] },
    });
    screen.getByRole("button", { name: "Grant Owner" }).focus();

    await userEvent.keyboard("{Enter}");

    await waitFor(() => expect(settled).toHaveBeenCalledOnce());
    expect(roleRows().at(-1)).toEqual(["Owner", "Held", ""]);
    expect(document.activeElement).toBe(
      screen.getByRole("heading", { level: 3, name: "Roles" }),
    );
  });

  it("leaves the keyboard on the control just pressed where it is still offered once the change answers", async () => {
    const answer = { ...GLINN, roles: new Set(["operator", "overseer"]) };
    const { settled } = reading({
      routes: { [`PUT ${MEMBER}/roles/overseer`]: [[documentOf(answer), 200]] },
    });
    screen.getByRole("button", { name: "Grant Overseer" }).focus();

    await userEvent.keyboard("{Enter}");

    await waitFor(() => expect(settled).toHaveBeenCalledOnce());
    const turned = screen.getByRole("button", { name: "Withdraw Overseer" });
    expect(document.activeElement).toBe(turned);
    expect(turned).not.toHaveAttribute("aria-disabled");
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
          "No longer a member of this group. Granting a role here makes them one again.",
      },
    ]);
    expect(roleRows().map((row) => row[2])).toEqual([
      "Grant Operator",
      "Grant Overseer",
      "Grant Owner",
    ]);
    expect(
      screen.queryByRole("button", { name: "Remove from the group" }),
    ).toBeNull();

    await userEvent.click(
      screen.getByRole("button", { name: "Grant Overseer" }),
    );

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
      "Grant Overseer",
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

      await userEvent.click(screen.getByRole("button", { name: control }));
      if (_case === "a removal") {
        await userEvent.click(screen.getByRole("button", { name: "Remove" }));
      }

      expect(await screen.findByRole("alert")).toHaveTextContent(said);
      expect(settled).not.toHaveBeenCalled();
    },
  );

  /** Said in place, under the roles it was asked of, and never behind a mark to be pointed at. */
  it("says a withdrawal refused for leaving nobody else who may change the membership under the roles, and settles as nothing", async () => {
    const { sent, settled } = reading({
      routes: {
        [`DELETE ${MEMBER}/roles/operator`]: [
          ['{"code":"LAST_MEMBERSHIP_CHANGER"}', 409],
        ],
      },
    });

    await userEvent.click(
      screen.getByRole("button", { name: "Withdraw Operator" }),
    );

    const refusal = await screen.findByRole("alert");
    expect(refusal).toHaveTextContent(
      "Nobody else in this group could change its membership.",
    );
    expect(refusal).toBeVisible();
    expect(screen.getByRole("region", { name: "Roles" })).toContainElement(
      refusal,
    );
    expect(
      screen.getByRole("list").compareDocumentPosition(refusal) &
        Node.DOCUMENT_POSITION_FOLLOWING,
    ).not.toBe(0);
    expect(screen.getAllByRole("alert")).toHaveLength(1);
    expect(settled).not.toHaveBeenCalled();
    expect(roleRows()[0]).toEqual(["Operator", "Held", "Withdraw Operator"]);
    expect(requestsTo(sent)).toEqual([`DELETE ${MEMBER}/roles/operator`]);
  });
});
