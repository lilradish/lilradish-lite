import { buttonClasses } from "@mui/material/Button";
import { ThemeProvider } from "@mui/material/styles";
import { act, render, screen, waitFor, within } from "@testing-library/react";
import { userEvent } from "@testing-library/user-event";
import { useState, type ReactNode } from "react";
import { describe, expect, it, vi } from "vitest";

import type { PoolPersonPanel } from "../../api/pool/people";
import { useAction } from "../../lib/request/useAction";
import { theme } from "../../lib/theme/theme";
import { requestsTo, serving, type Reply } from "../../testutil/answering";
import { deferred } from "../../testutil/deferred";
import { drawnAtDepth, drawnOf } from "../../testutil/depths";
import { noticesIn } from "../../testutil/notices";
import type { Asked, Outcome } from "./changes";
import { PersonPanel } from "./PersonPanel";

const SUBJECT = "00000002-0000-4000-8000-000000000140";

const PERSON = `/api/pool/people/${SUBJECT}`;

const GLINN: PoolPersonPanel = {
  subjectId: SUBJECT,
  userId: "000140",
  displayName: "Glinn Havven",
  estateRoles: new Set(["steward"]),
  lastGrantingRoles: new Set(),
  groups: [],
  seeded: false,
};

const HOLDING_NOTHING: PoolPersonPanel = { ...GLINN, estateRoles: new Set() };

/** As the server writes a person, which is not the shape this side holds one in. */
function documentOf(person: PoolPersonPanel): string {
  return JSON.stringify({
    ...person,
    estateRoles: [...person.estateRoles],
    lastGrantingRoles: [...person.lastGrantingRoles],
  });
}

/** Words set apart inside a sentence, bracketed by the isolate controls written as code points. */
function setApart(words: string): string {
  return `${String.fromCodePoint(0x2068)}${words}${String.fromCodePoint(0x2069)}`;
}

function themed({ children }: { readonly children: ReactNode }) {
  return <ThemeProvider theme={theme}>{children}</ThemeProvider>;
}

interface Reading {
  readonly person?: PoolPersonPanel;
  readonly mayChangeRoles?: boolean;
  readonly waiting?: boolean;
  readonly routes?: Readonly<Record<string, readonly Reply[]>>;
}

/**
 * The panel over the real action it is handed, held as a page holds it: the
 * holder settles every change, keeps who asked, and hands a refusal back to
 * the panel while that person is the one shown. The person can be read again.
 * Only the server is stood in for.
 */
function reading({
  person = GLINN,
  mayChangeRoles = true,
  waiting = false,
  routes = {},
}: Reading = {}) {
  const sent = serving(routes);
  const settled = vi.fn();
  const told = vi.fn();
  let readAgain: (person: PoolPersonPanel) => void = () => {};
  function Holder() {
    const [shown, setShown] = useState(person);
    readAgain = setShown;
    const [asked, setAsked] = useState<Asked | null>(null);
    const changing = useAction<Outcome>(settled);
    return (
      <PersonPanel
        person={shown}
        changing={changing}
        waiting={waiting}
        mayChangeRoles={mayChangeRoles}
        refused={
          changing.problem !== null &&
          asked !== null &&
          asked.place !== "bringIn"
            ? { place: asked.place, of: asked.of, problem: changing.problem }
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
    async readAgainAs(next: PoolPersonPanel) {
      await act(async () => readAgain(next));
    },
  };
}

function part(name: string): HTMLElement {
  return screen.getByRole("heading", { level: 3, name });
}

/** Each role's row as the reader reads it: its words, and whether it is held. */
function roleRows(): string[][] {
  const [roles] = screen.getAllByRole("list");
  return within(roles!)
    .getAllByRole("listitem")
    .map((row) => [
      row.querySelector("bdi")?.textContent ?? "",
      within(row).getByText(/^(Held|Not held)$/).textContent ?? "",
    ]);
}

function unavailable(control: HTMLElement): boolean {
  return control.getAttribute("aria-disabled") === "true";
}

function remove(): HTMLElement {
  return screen.getByRole("button", { name: "Remove from the pool" });
}

async function confirmRemove() {
  await userEvent.click(remove());
  expect(screen.getByRole("dialog")).toHaveTextContent(
    "Bringing them in again later keeps them the same person, with their past acts.",
  );
  await userEvent.click(screen.getByRole("button", { name: "Remove" }));
}

describe("PersonPanel", () => {
  it("heads its parts who they are, their estate roles and their groups, in that order", () => {
    reading();

    expect(
      screen
        .getAllByRole("heading", { level: 3 })
        .map((each) => each.textContent),
    ).toEqual(["Who they are", "Estate roles", "Groups"]);
  });

  it("sets each part apart as a region on a ground of its own, holding what it heads and leaving Remove from the pool outside", () => {
    reading();

    const parts = ["Who they are", "Estate roles", "Groups"].map((name) =>
      screen.getByRole("region", { name }),
    );

    expect(screen.getAllByRole("region")).toEqual(parts);
    expect(parts.map(drawnOf)).toEqual(parts.map(() => drawnAtDepth(3)));
    expect(parts[0]).toContainElement(screen.getByText("User number"));
    expect(parts[1]).toContainElement(screen.getAllByRole("list")[0]!);
    expect(parts.some((each) => each.contains(remove()))).toBe(false);
  });

  it("says who they are as a read without editing controls", () => {
    reading();

    const facts = screen.getByText("User number").parentElement!;
    expect(facts.tagName).toBe("DL");
    expect([...facts.children].map((each) => each.textContent)).toEqual([
      "User number",
      "000140",
      "Name",
      "Glinn Havven",
    ]);
    expect(screen.queryAllByRole("textbox")).toEqual([]);
  });

  /** Laid out as every other label and value are, so the facts read as the same kind of thing. */
  it("sets each fact's label as a term and its value as the text around it", () => {
    reading();

    expect(getComputedStyle(screen.getByText("User number")).fontWeight).toBe(
      "700",
    );
    expect(
      getComputedStyle(screen.getByText("000140").parentElement!).fontWeight,
    ).not.toBe("700");
  });

  it("says so where no name is held for them, rather than leaving it blank", () => {
    const { displayName: _, ...nameless } = GLINN;
    reading({ person: nameless });

    const facts = screen.getByText("Name").parentElement!;
    expect([...facts.children].map((each) => each.textContent)).toEqual([
      "User number",
      "000140",
      "Name",
      "No name is held for them.",
    ]);
  });

  it("isolates the name and the number from the words around them", () => {
    reading();

    expect(screen.getByText("Glinn Havven").tagName).toBe("BDI");
    expect(screen.getByText("000140").tagName).toBe("BDI");
  });

  it("lists every estate role, and whether they hold each", () => {
    reading({ mayChangeRoles: false });

    expect(roleRows()).toEqual([
      ["Steward", "Held"],
      ["Watcher", "Not held"],
    ]);
  });

  /** Some engines stop reading a list as one once its markers are taken away. */
  it("names each of its lists a list outright, their markers being taken away", () => {
    reading({ person: { ...GLINN, groups: ["Payroll"] } });

    const lists = screen.getAllByRole("list");

    expect(lists.map((each) => each.getAttribute("role"))).toEqual([
      "list",
      "list",
    ]);
    expect(lists.map((each) => getComputedStyle(each).listStyleType)).toEqual([
      "none",
      "none",
    ]);
  });

  /** Left out, it would make somebody holding it read as holding less than they do. */
  it("lists a role this build has no words for where it is held, offering no control over it", () => {
    reading({ person: { ...GLINN, estateRoles: new Set(["auditor"]) } });

    expect(roleRows()).toEqual([
      ["Steward", "Not held"],
      ["Watcher", "Not held"],
      ["auditor", "Held"],
    ]);
    expect(
      screen.getAllByRole("button").map((each) => each.textContent),
    ).toEqual(["Grant Steward", "Grant Watcher", "Remove from the pool"]);
  });

  /** Somebody else in the pool may grant as well, so nothing they hold is the last of it. */
  it("offers to withdraw what is held and grant what is not, to a reader who may grant an estate role", () => {
    reading();

    expect(
      screen.getAllByRole("button").map((each) => each.textContent),
    ).toEqual(["Withdraw Steward", "Grant Watcher", "Remove from the pool"]);
  });

  /** Withdrawing a role and taking somebody out warn; granting one undoes nothing and is drawn as any other. */
  it("draws withdrawing a role and Remove from the pool in the warning colour, and granting a role in none", () => {
    reading();

    expect(
      screen
        .getAllByRole("button")
        .filter((each) => each.classList.contains(buttonClasses.colorWarning))
        .map((each) => each.textContent),
    ).toEqual(["Withdraw Steward", "Remove from the pool"]);
    expect(screen.getByRole("button", { name: "Grant Watcher" })).toHaveClass(
      buttonClasses.colorPrimary,
    );
  });

  it("offers no withdrawal of a role nobody else in the pool could grant without, and still offers the rest", () => {
    reading({
      person: {
        ...GLINN,
        estateRoles: new Set(["steward", "watcher"]),
        lastGrantingRoles: new Set(["steward"]),
      },
    });

    expect(
      screen.getAllByRole("button").map((each) => each.textContent),
    ).toEqual(["Withdraw Watcher", "Remove from the pool"]);
    expect(roleRows()).toEqual([
      ["Steward", "Held"],
      ["Watcher", "Held"],
    ]);
  });

  it("draws no role control for a reader who may not grant an estate role", () => {
    reading({ mayChangeRoles: false });

    expect(
      screen.queryAllByRole("button").map((each) => each.textContent),
    ).toEqual(["Remove from the pool"]);
    expect(roleRows()).toEqual([
      ["Steward", "Held"],
      ["Watcher", "Not held"],
    ]);
  });

  it.each([
    ["Grant Watcher", "PUT", "watcher"],
    ["Withdraw Steward", "DELETE", "steward"],
  ])(
    "asks for %s at the person's own address, and settles as the person the answer holds",
    async (control, method, role) => {
      const answer: PoolPersonPanel = {
        ...GLINN,
        estateRoles: new Set(["steward", "watcher"]),
      };
      const { sent, settled, told } = reading({
        routes: {
          [`${method} ${PERSON}/estate-roles/${role}`]: [
            [documentOf(answer), 200],
          ],
        },
      });

      await userEvent.click(screen.getByRole("button", { name: control }));

      await waitFor(() =>
        expect(settled).toHaveBeenCalledExactlyOnceWith({ changed: answer }),
      );
      expect(told).toHaveBeenCalledExactlyOnceWith({
        place: "roles",
        of: GLINN,
      });
      expect(requestsTo(sent)).toEqual([
        `${method} ${PERSON}/estate-roles/${role}`,
      ]);
    },
  );

  /** What else the reader can see is out — the person being read again — holds every control. */
  it("holds every control while something else it is told of is out, asking nothing", async () => {
    const { sent, told } = reading({ person: HOLDING_NOTHING, waiting: true });

    for (const control of screen.getAllByRole("button")) {
      control.focus();
      await userEvent.keyboard("{Enter}");
    }

    expect(
      screen.getAllByRole("button").map((each) => unavailable(each)),
    ).toEqual([true, true, true]);
    expect(sent).not.toHaveBeenCalled();
    expect(told).not.toHaveBeenCalled();
  });

  /**
   * One change to one person at a time: a second, asked while the first is
   * out, would be answered in whichever order the two land.
   */
  it("holds every control while one change is out, and asks nothing more when one is pressed", async () => {
    const held = deferred<readonly [string, number]>();
    const { sent } = reading({
      person: HOLDING_NOTHING,
      routes: { [`PUT ${PERSON}/estate-roles/watcher`]: [held.promise] },
    });

    await userEvent.click(
      screen.getByRole("button", { name: "Grant Watcher" }),
    );
    remove().focus();
    await userEvent.keyboard("{Enter}[Space]");

    expect(
      screen.getAllByRole("button").map((each) => unavailable(each)),
    ).toEqual([true, true, true]);
    expect(requestsTo(sent)).toEqual([`PUT ${PERSON}/estate-roles/watcher`]);
  });

  it("says a withdrawal refused for leaving nobody who may grant beside the roles, and settles as nothing", async () => {
    const { settled } = reading({
      routes: {
        [`DELETE ${PERSON}/estate-roles/steward`]: [
          ['{"code":"LAST_ESTATE_ROLE_GRANTOR"}', 409],
        ],
      },
    });

    await userEvent.click(
      screen.getByRole("button", { name: "Withdraw Steward" }),
    );

    const refusal = await screen.findByRole("alert");
    expect(refusal).toHaveTextContent(
      "Withdrawing this would leave nobody who may grant an estate role.",
    );
    expect(
      part("Estate roles").compareDocumentPosition(refusal) &
        Node.DOCUMENT_POSITION_FOLLOWING,
    ).not.toBe(0);
    expect(
      refusal.compareDocumentPosition(part("Groups")) &
        Node.DOCUMENT_POSITION_FOLLOWING,
    ).not.toBe(0);
    expect(screen.getAllByRole("alert")).toHaveLength(1);
    expect(settled).not.toHaveBeenCalled();
    expect(roleRows()[0]).toEqual(["Steward", "Held"]);
  });

  /** The control knows what it asked for, so the rule said is that act's. */
  it("says a refused role change as the rule of changing estate roles", async () => {
    reading({
      routes: {
        [`PUT ${PERSON}/estate-roles/watcher`]: [
          ['{"code":"ACT_NOT_PERMITTED"}', 403],
        ],
      },
    });

    await userEvent.click(
      screen.getByRole("button", { name: "Grant Watcher" }),
    );

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "Estate roles are granted and withdrawn only by a role that may grant them.",
    );
  });

  it("names every group they are in, isolated, and nothing of what they hold in one", () => {
    reading({ person: { ...GLINN, groups: ["Payroll", "finance"] } });

    const [, groups] = screen.getAllByRole("list");
    const named = within(groups!).getAllByRole("listitem");
    expect(named.map((each) => each.textContent)).toEqual([
      "Payroll",
      "finance",
    ]);
    expect(
      named.every((each) => each.firstElementChild?.tagName === "BDI"),
    ).toBe(true);
  });

  /** Being in no group is the ordinary case for somebody new, not a fault. */
  it("says they are in no group without raising it as a fault", () => {
    reading();

    expect(screen.getByText("In no group.")).toBeInTheDocument();
    expect(screen.getAllByRole("list")).toHaveLength(1);
    expect(screen.queryByRole("alert")).toBeNull();
  });

  it.each([true, false])(
    "keeps a row's facts and removal control without an arrival notice when seeded is %s",
    (seeded) => {
      reading({ person: { ...HOLDING_NOTHING, seeded } });

      expect(screen.getByText("000140")).toBeVisible();
      expect(remove()).toBeVisible();
      expect(noticesIn(document.body)).toEqual([]);
    },
  );

  it("cancels removing somebody without sending a request", async () => {
    const { sent } = reading({ person: HOLDING_NOTHING });
    await userEvent.click(remove());
    expect(screen.getByRole("dialog")).toHaveAccessibleName(
      /Remove.*Glinn Havven.*from the pool\?/,
    );
    expect(requestsTo(sent)).toEqual([]);
    await userEvent.click(screen.getByRole("button", { name: "Cancel" }));
    await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
    expect(requestsTo(sent)).toEqual([]);
  });

  it("takes somebody holding nothing out of the pool at their own address, and settles as who went", async () => {
    const { sent, settled, told } = reading({
      person: HOLDING_NOTHING,
      routes: { [`DELETE ${PERSON}`]: [["", 204]] },
    });

    await confirmRemove();

    await waitFor(() =>
      expect(settled).toHaveBeenCalledExactlyOnceWith({
        removed: HOLDING_NOTHING,
      }),
    );
    expect(told).toHaveBeenCalledExactlyOnceWith({
      place: "removal",
      of: HOLDING_NOTHING,
    });
    await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
    expect(unavailable(remove())).toBe(false);
    expect(requestsTo(sent)).toEqual([`DELETE ${PERSON}`]);
  });

  /**
   * Drawn and not pressable, which is the only way a reader learns what has
   * to be undone before it will go.
   */
  it("holds Remove from the pool for somebody still holding roles and groups, naming each", async () => {
    const { sent } = reading({
      person: {
        ...GLINN,
        estateRoles: new Set(["watcher", "steward"]),
        groups: ["Payroll", "finance"],
      },
    });

    remove().focus();
    await userEvent.keyboard("{Enter}[Space]");

    expect(unavailable(remove())).toBe(true);
    const reason =
      `Their estate roles have to be withdrawn first: ${setApart("Steward")} and ${setApart("Watcher")}. ` +
      `They have to leave these groups first: ${setApart("Payroll")} and ${setApart("finance")}.`;
    expect(remove()).toHaveAccessibleDescription(reason);
    expect(noticesIn(remove().parentElement!)).toEqual([
      { severity: "warning", words: reason },
    ]);
    expect(sent).not.toHaveBeenCalled();
  });

  it.each([
    [
      "roles alone",
      { estateRoles: new Set(["steward"]), groups: [] },
      `Their estate role has to be withdrawn first: ${setApart("Steward")}.`,
    ],
    [
      "groups alone",
      { estateRoles: new Set<string>(), groups: ["Payroll"] },
      `They have to leave this group first: ${setApart("Payroll")}.`,
    ],
  ])("says only what still holds them, for %s", (_what, holding, reason) => {
    reading({ person: { ...GLINN, ...holding } });

    expect(remove()).toHaveAccessibleDescription(reason);
  });

  it.each([
    [
      "PERSON_HOLDS_ESTATE_ROLES",
      "Somebody holding an estate role cannot be taken out of the pool. Withdraw their estate roles first.",
    ],
    [
      "PERSON_IN_GROUPS",
      "Somebody in a group cannot be taken out of the pool until they are in none.",
    ],
  ])(
    "says why taking them out was refused with %s beneath the control, and settles as nothing",
    async (code, sentence) => {
      const { settled } = reading({
        person: HOLDING_NOTHING,
        routes: { [`DELETE ${PERSON}`]: [[JSON.stringify({ code }), 409]] },
      });

      await confirmRemove();

      const refusal = await screen.findByRole("alert");
      expect(refusal).toHaveTextContent(sentence);
      expect(
        remove().compareDocumentPosition(refusal) &
          Node.DOCUMENT_POSITION_FOLLOWING,
      ).not.toBe(0);
      expect(screen.getAllByRole("alert")).toHaveLength(1);
      expect(settled).not.toHaveBeenCalled();
    },
  );

  /** A refusal under another status is not one about what still holds them, and is not answered by reading them again. */
  it("keeps saying why taking them out was refused under another status, once read again with nothing to go", async () => {
    const { readAgainAs } = reading({
      person: HOLDING_NOTHING,
      routes: {
        [`DELETE ${PERSON}`]: [['{"code":"PERSON_IN_GROUPS"}', 400]],
      },
    });
    await confirmRemove();
    await screen.findByRole("alert");

    await readAgainAs({ ...HOLDING_NOTHING });

    expect(screen.getByRole("alert")).toBeInTheDocument();
  });

  it("says a refusal of taking them out as the rule of keeping the pool", async () => {
    reading({
      person: HOLDING_NOTHING,
      routes: {
        [`DELETE ${PERSON}`]: [['{"code":"ACT_NOT_PERMITTED"}', 403]],
      },
    });

    await confirmRemove();

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "The pool is seen and changed only by a role that may keep it.",
    );
  });

  it("keeps saying why taking them out was refused once they are read again and something still has to go", async () => {
    const { readAgainAs } = reading({
      person: HOLDING_NOTHING,
      routes: {
        [`DELETE ${PERSON}`]: [['{"code":"PERSON_IN_GROUPS"}', 409]],
      },
    });
    await confirmRemove();
    await screen.findByRole("alert");

    await readAgainAs({ ...HOLDING_NOTHING, groups: ["Payroll"] });

    expect(screen.getByRole("alert")).toHaveTextContent(
      "Somebody in a group cannot be taken out of the pool until they are in none.",
    );
    expect(remove()).toHaveAccessibleDescription(
      `They have to leave this group first: ${setApart("Payroll")}.`,
    );
  });

  /** By then nothing it named is left, and a refusal saying otherwise would be read as current. */
  it("stops saying why taking them out was refused once they are read again and nothing has to go first", async () => {
    const { readAgainAs } = reading({
      person: HOLDING_NOTHING,
      routes: {
        [`DELETE ${PERSON}`]: [['{"code":"PERSON_IN_GROUPS"}', 409]],
      },
    });
    await confirmRemove();
    await screen.findByRole("alert");

    await readAgainAs({ ...HOLDING_NOTHING });

    expect(screen.queryByRole("alert")).toBeNull();
    expect(unavailable(remove())).toBe(false);
  });
});
