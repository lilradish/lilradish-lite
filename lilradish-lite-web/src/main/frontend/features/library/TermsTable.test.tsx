import { ThemeProvider } from "@mui/material/styles";
import { act, render, screen, waitFor, within } from "@testing-library/react";
import { userEvent } from "@testing-library/user-event";
import { useEffect, useState, type ReactNode } from "react";
import { describe, expect, it, vi } from "vitest";

import type { ListTerm } from "../../api/groups/{groupId}/{kind}/{entryId}/versions/{versionId}";
import type { Worded } from "../../api/groups/{groupId}/{kind}/{entryId}/versions/{versionId}/terms";
import type { TermWay } from "../../api/groups/{groupId}/{kind}/{entryId}/versions/{versionId}/terms/{termId}/{way}";
import { RequestFailed } from "../../api/problem";
import { useAction } from "../../lib/request/useAction";
import { theme } from "../../lib/theme/theme";
import { TermsTable } from "./TermsTable";

const BILLING: ListTerm = {
  termId: "0000000c-0000-4000-8000-000000000e11",
  term: "Billing",
  meaning: "Money taken wrongly.",
};

const DELIVERY: ListTerm = {
  termId: "0000000c-0000-4000-8000-000000000e12",
  term: "Delivery",
  meaning: "Late, lost or broken in transit.",
};

const REFUNDS: ListTerm = {
  termId: "0000000c-0000-4000-8000-000000000e13",
  term: "Refunds",
  meaning: "Money owed back.",
};

const LIST = [BILLING, DELIVERY, REFUNDS];

const ALIKE =
  "Alike to a term above it, whatever the case either is written in. Submitting refuses it.";

function termsNumbering(count: number): ListTerm[] {
  return Array.from({ length: count }, (_, index) => ({
    termId: `term-${index + 1}`,
    term: `Term number ${index + 1}`,
    meaning: "Numbered.",
  }));
}

function setApart(words: string): string {
  return `${String.fromCodePoint(0x2068)}${words}${String.fromCodePoint(0x2069)}`;
}

function themed({ children }: { readonly children: ReactNode }) {
  return <ThemeProvider theme={theme}>{children}</ThemeProvider>;
}

/**
 * The table as a host draws it, over the real action it is handed, and a list kept the way the server keeps
 * one: each change answered with every term as it then reads, in order, a term added last under a key of its
 * own. Where `refusing` is given, every change is refused with it and nothing is kept, and a refusal can take
 * writing away, as the page reading the version again does.
 */
function editing(
  terms: readonly ListTerm[],
  host: {
    readonly editable?: boolean;
    readonly waiting?: boolean;
    readonly focusOnArrival?: boolean;
    readonly refusing?: string;
    readonly closeOnRefusal?: boolean;
  } = {},
) {
  let kept = terms;
  let made = 0;
  const answered = (next: readonly ListTerm[]) => {
    if (host.refusing !== undefined) {
      return Promise.reject(
        new RequestFailed({ status: 409, code: host.refusing }),
      );
    }
    kept = next;
    return Promise.resolve(kept);
  };
  const changes = {
    add: vi.fn((worded: Worded) => {
      made += 1;
      return answered([
        ...kept,
        { termId: `added-${made}`, term: worded.term, meaning: worded.meaning },
      ]);
    }),
    reword: vi.fn((termId: string, worded: Worded) =>
      answered(
        kept.map((term) =>
          term.termId === termId ? { termId, ...worded } : term,
        ),
      ),
    ),
    remove: vi.fn((termId: string) =>
      answered(kept.filter((term) => term.termId !== termId)),
    ),
    move: vi.fn((termId: string, way: TermWay) => {
      const at = kept.findIndex((term) => term.termId === termId);
      const to = way === "up" ? at - 1 : at + 1;
      const next = [...kept];
      [next[at], next[to]] = [next[to]!, next[at]!];
      return answered(next);
    }),
  };
  const readAfresh = vi.fn<() => void>();
  // The page reading the list again, as it does after some refusals, with no write of the table's.
  const page = { readAgain: (_read: readonly ListTerm[]) => {} };
  function Host() {
    const [held, setHeld] = useState(terms);
    const [open, setOpen] = useState(host.editable ?? true);
    const writing = useAction<readonly ListTerm[]>(setHeld, () =>
      setOpen(host.closeOnRefusal !== true && open),
    );
    useEffect(() => {
      page.readAgain = setHeld;
    }, []);
    return (
      <TermsTable
        terms={held}
        editable={open}
        waiting={host.waiting ?? false}
        rule="author_entry"
        action={writing}
        changes={changes}
        focusOnArrival={host.focusOnArrival ?? false}
        onReadAfresh={readAfresh}
      />
    );
  }
  render(<Host />, { wrapper: themed });
  return {
    changes,
    readAfresh,
    kept: () => kept,
    readAgain: (read: readonly ListTerm[]) => act(() => page.readAgain(read)),
  };
}

function part(): HTMLElement {
  return screen.getByRole("region", { name: "The terms" });
}

function table(): HTMLElement {
  return within(part()).getByRole("table", { name: "The terms" });
}

/** Every body row's cells' words, a text box's value standing for its cell. */
function rowsRead(): string[][] {
  return within(table())
    .getAllByRole("row")
    .slice(1)
    .map((row) =>
      within(row)
        .getAllByRole("cell")
        .slice(0, 2)
        .map(
          (cell) =>
            cell.querySelector("input")?.value ?? cell.textContent ?? "",
        ),
    );
}

function termBox(position: number): HTMLElement {
  return within(table()).getByRole("textbox", { name: `Term ${position}` });
}

function meaningBox(position: number): HTMLElement {
  return within(table()).getByRole("textbox", {
    name: `What term ${position} means`,
  });
}

function onTerm(act: string, term: string): HTMLElement {
  return within(table()).getByRole("button", {
    name: `${act}: ${setApart(term)}`,
  });
}

async function typingNewTerm(): Promise<void> {
  await userEvent.click(
    within(part()).getByRole("textbox", { name: "New term" }),
  );
  await userEvent.paste("Returns");
  await userEvent.click(
    within(part()).getByRole("textbox", { name: "What the new term means" }),
  );
  await userEvent.paste("Goods sent back.");
}

/** Long enough for a line the table said to be put in: its region waits two frames to be heard. */
async function waitForFrames(): Promise<void> {
  await act(
    () =>
      new Promise<void>((resolve) =>
        requestAnimationFrame(() =>
          requestAnimationFrame(() => requestAnimationFrame(() => resolve())),
        ),
      ),
  );
}

function heading(): HTMLElement {
  return screen.getByRole("heading", { level: 3, name: "The terms" });
}

describe("TermsTable", () => {
  it("reads each term and what it means in the order given where they may not be written, and offers nothing to change", () => {
    editing([REFUNDS, BILLING], { editable: false });

    expect(
      within(table())
        .getAllByRole("columnheader")
        .map((each) => each.textContent),
    ).toEqual(["Term", "What it means"]);
    expect(rowsRead()).toEqual([
      ["Refunds", "Money owed back."],
      ["Billing", "Money taken wrongly."],
    ]);
    expect(part().querySelectorAll("input, button")).toHaveLength(0);
  });

  it("says a list holding no term so, rather than drawing an empty table", () => {
    editing([], { editable: false });

    expect(part()).toHaveTextContent("It holds no term yet.");
    expect(within(part()).queryByRole("table")).toBeNull();
  });

  it("draws each term in text boxes where they may be written, under a column of changes", () => {
    editing(LIST);

    expect(
      within(table())
        .getAllByRole("columnheader")
        .map((each) => each.textContent),
    ).toEqual(["Term", "What it means", "Changes"]);
    expect(rowsRead()).toEqual(
      LIST.map(({ term, meaning }) => [term, meaning]),
    );
    expect(within(table()).getAllByRole("textbox")).toHaveLength(
      LIST.length * 2,
    );
  });

  it("holds moving the first term up and the last down, and no other move", () => {
    editing(LIST);

    expect(onTerm("Move up", "Billing")).toHaveAttribute(
      "aria-disabled",
      "true",
    );
    expect(onTerm("Move down", "Billing")).not.toHaveAttribute("aria-disabled");
    expect(onTerm("Move up", "Refunds")).not.toHaveAttribute("aria-disabled");
    expect(onTerm("Move down", "Refunds")).toHaveAttribute(
      "aria-disabled",
      "true",
    );
    expect(onTerm("Move up", "Delivery")).not.toHaveAttribute("aria-disabled");
    expect(onTerm("Move down", "Delivery")).not.toHaveAttribute(
      "aria-disabled",
    );
  });

  it("offers no save on a term before it is changed", () => {
    editing(LIST);

    expect(within(table()).queryAllByRole("button", { name: /^Save/ })).toEqual(
      [],
    );
    expect(
      within(table()).getAllByRole("button", { name: /^Remove/ }),
    ).toHaveLength(LIST.length);
  });

  it("adds a term as typed after the last, and empties the boxes it was typed in", async () => {
    const { changes } = editing(LIST);
    const term = within(part()).getByRole("textbox", { name: "New term" });
    const meaning = within(part()).getByRole("textbox", {
      name: "What the new term means",
    });

    await userEvent.type(term, "Returns");
    await userEvent.type(meaning, "Goods sent back.");
    await userEvent.click(
      within(part()).getByRole("button", { name: "Add the term" }),
    );

    await waitFor(() =>
      expect(rowsRead().at(-1)).toEqual(["Returns", "Goods sent back."]),
    );
    expect(changes.add.mock.calls.map(([worded]) => worded)).toEqual([
      { term: "Returns", meaning: "Goods sent back." },
    ]);
    expect(rowsRead()).toHaveLength(4);
    expect(term).toHaveValue("");
    expect(meaning).toHaveValue("");
  });

  it.each([
    [
      "no term",
      "",
      "Goods sent back.",
      "Write a term and what it means first.",
    ],
    [
      "nothing it means",
      "Returns",
      "",
      "Write a term and what it means first.",
    ],
    [
      "a term the server would refuse",
      "Goods  back",
      "Goods sent back.",
      "A term is one to 128 characters on one line: no space but single plain ones between words, none at either end, and something in it that shows.",
    ],
    [
      "a term holding a character that shows nothing",
      `Bill${String.fromCodePoint(0x200b)}ing`,
      "Money taken wrongly.",
      "What was written holds a character that shows nothing, which a term may not hold.",
    ],
    [
      "a meaning the server would refuse",
      "Returns",
      `Sent ${String.fromCodePoint(0x202e)}back`,
      "What was written holds a direction control, which text sent to a model may not hold.",
    ],
  ])(
    "holds adding %s, saying why beside the control, and sends nothing",
    async (_case, term, meaning, why) => {
      const { changes } = editing(LIST);
      await userEvent.click(
        within(part()).getByRole("textbox", { name: "New term" }),
      );
      await userEvent.paste(term);
      await userEvent.click(
        within(part()).getByRole("textbox", {
          name: "What the new term means",
        }),
      );
      await userEvent.paste(meaning);
      const add = within(part()).getByRole("button", { name: "Add the term" });

      add.focus();
      await userEvent.keyboard("{Enter}");

      expect(add).toHaveAttribute("aria-disabled", "true");
      expect(add).toHaveAccessibleDescription(why);
      expect(changes.add).not.toHaveBeenCalled();
    },
  );

  /** Said before anything typed is, since nothing typed would be taken. */
  it.each([
    [
      256,
      "A reference list holds at most 256 terms. Remove one to add another.",
    ],
    [255, "Write a term and what it means first."],
  ])(
    "holds adding to a list of %i terms for the reason beside the control, and sends nothing",
    async (count, why) => {
      const { changes } = editing(termsNumbering(count));
      const add = within(part()).getByRole("button", { name: "Add the term" });

      add.focus();
      await userEvent.keyboard("{Enter}");

      expect(add).toHaveAttribute("aria-disabled", "true");
      expect(add).toHaveAccessibleDescription(why);
      expect(changes.add).not.toHaveBeenCalled();
    },
    // Some 256 rows of text boxes take seconds to draw under jsdom.
    15_000,
  );

  it("says a term refused for a list already full where it was asked, and keeps what was typed", async () => {
    editing(LIST, { refusing: "LIST_TOO_LARGE" });
    await typingNewTerm();
    const add = within(part()).getByRole("button", { name: "Add the term" });

    add.focus();
    await userEvent.keyboard("{Enter}");

    expect(await within(part()).findByRole("alert")).toHaveTextContent(
      "This reference list holds as many terms as a list may already; nothing was added.",
    );
    expect(
      within(part()).getByRole("textbox", { name: "New term" }),
    ).toHaveValue("Returns");
    expect(rowsRead()).toHaveLength(LIST.length);
  });

  it("saves a term changed, and what it means, as one write, and offers no save once it lands", async () => {
    const { changes } = editing(LIST);

    await userEvent.type(termBox(2), " times");
    await userEvent.clear(meaningBox(2));
    await userEvent.type(meaningBox(2), "Late.");
    await userEvent.click(onTerm("Save", "Delivery"));

    await waitFor(() =>
      expect(rowsRead()[1]).toEqual(["Delivery times", "Late."]),
    );
    expect(
      changes.reword.mock.calls.map(([termId, worded]) => [termId, worded]),
    ).toEqual([
      [DELIVERY.termId, { term: "Delivery times", meaning: "Late." }],
    ]);
    expect(within(table()).queryByRole("button", { name: /^Save/ })).toBeNull();
  });

  it("puts the keyboard back in a saved term's own box once the save lands", async () => {
    editing(LIST);
    await userEvent.type(termBox(2), " times");

    await userEvent.click(onTerm("Save", "Delivery"));

    await waitFor(() => expect(termBox(2)).toHaveFocus());
    expect(heading()).not.toHaveFocus();
  });

  it("holds a term's save where the server would refuse it, saying why under the box and beside the control, and sends nothing", async () => {
    const { changes } = editing(LIST);
    await userEvent.type(termBox(1), " ");
    const save = onTerm("Save", "Billing");

    save.focus();
    await userEvent.keyboard("{Enter}");

    const why =
      "A term is one to 128 characters on one line: no space but single plain ones between words, none at either end, and something in it that shows.";
    expect(save).toHaveAttribute("aria-disabled", "true");
    expect(save).toHaveAccessibleDescription(why);
    expect(termBox(1)).toHaveAccessibleDescription(why);
    expect(changes.reword).not.toHaveBeenCalled();
  });

  it.each([
    ["up", "Move up", "Delivery", [DELIVERY, BILLING, REFUNDS]],
    ["down", "Move down", "Delivery", [BILLING, REFUNDS, DELIVERY]],
  ] as const)("moves a term %s by one place", async (way, act, term, after) => {
    const { changes } = editing(LIST);

    await userEvent.click(onTerm(act, term));

    await waitFor(() =>
      expect(rowsRead()).toEqual(
        after.map(({ term: word, meaning }) => [word, meaning]),
      ),
    );
    expect(changes.move.mock.calls).toEqual([
      [DELIVERY.termId, way, expect.any(AbortSignal)],
    ]);
  });

  /** Moving down takes the pressed row itself out and puts it back, which drops its focus under jsdom as in a browser. */
  it.each([
    ["Move up", [DELIVERY, BILLING, REFUNDS]],
    ["Move down", [BILLING, REFUNDS, DELIVERY]],
  ] as const)(
    "keeps the keyboard on %s once the term has moved",
    async (act, after) => {
      editing(LIST);
      const pressed = onTerm(act, "Delivery");
      pressed.focus();

      await userEvent.keyboard("{Enter}");

      await waitFor(() =>
        expect(rowsRead().map(([word]) => word)).toEqual(
          after.map(({ term }) => term),
        ),
      );
      expect(pressed).toHaveFocus();
      expect(onTerm(act, "Delivery")).toBe(pressed);
    },
  );

  it("removes a term, and no other", async () => {
    const { changes } = editing(LIST);

    await userEvent.click(onTerm("Remove", "Delivery"));

    await waitFor(() =>
      expect(rowsRead()).toEqual([
        ["Billing", "Money taken wrongly."],
        ["Refunds", "Money owed back."],
      ]),
    );
    expect(changes.remove.mock.calls.map(([termId]) => termId)).toEqual([
      DELIVERY.termId,
    ]);
  });

  it("puts the keyboard on the heading once a removal lands", async () => {
    editing(LIST);

    await userEvent.click(onTerm("Remove", "Delivery"));

    await waitFor(() => expect(heading()).toHaveFocus());
    expect(rowsRead()).toHaveLength(LIST.length - 1);
  });

  it("marks a term the server judged alike to one above it with what submitting will do, and no other", () => {
    editing([BILLING, { ...DELIVERY, term: "BILLING", alikeEarlier: true }]);

    expect(termBox(2)).toHaveAccessibleDescription(ALIKE);
    expect(termBox(1)).not.toHaveAccessibleDescription(ALIKE);
  });

  /** Each change is saved as it is pressed, so the server's mark follows the save rather than the keystroke. */
  it("marks nothing as it is typed, however alike to a term above it", async () => {
    editing(LIST);
    const added = within(part()).getByRole("textbox", { name: "New term" });

    await userEvent.clear(termBox(2));
    await userEvent.type(termBox(2), "Billing");
    await userEvent.type(added, "billing");

    expect(termBox(2)).toHaveValue("Billing");
    expect(termBox(2)).not.toHaveAccessibleDescription(ALIKE);
    expect(added).not.toHaveAccessibleDescription(ALIKE);
  });

  it("marks a term once a read of the list says the server judges it alike", () => {
    const { readAgain } = editing(LIST);

    readAgain([BILLING, { ...DELIVERY, alikeEarlier: true }, REFUNDS]);

    expect(termBox(2)).toHaveAccessibleDescription(ALIKE);
    expect(termBox(3)).not.toHaveAccessibleDescription(ALIKE);
  });

  it("keeps what is typed over one term while another change lands, and what is typed for a new one", async () => {
    editing(LIST);
    await userEvent.type(meaningBox(1), " Twice.");
    await userEvent.type(
      within(part()).getByRole("textbox", { name: "New term" }),
      "Returns",
    );

    await userEvent.click(onTerm("Move down", "Delivery"));

    await waitFor(() => expect(rowsRead()[2]![0]).toBe("Delivery"));
    expect(meaningBox(1)).toHaveValue("Money taken wrongly. Twice.");
    expect(
      within(part()).getByRole("textbox", { name: "New term" }),
    ).toHaveValue("Returns");
  });

  it("lets go of what is typed over a term once that term reads otherwise, and keeps it over one that reads as it did", async () => {
    const { readAgain } = editing(LIST);
    await userEvent.type(meaningBox(1), " Twice.");
    await userEvent.type(meaningBox(2), " Often.");

    readAgain([{ ...BILLING, meaning: "Charged wrongly." }, DELIVERY, REFUNDS]);

    expect(meaningBox(1)).toHaveValue("Charged wrongly.");
    expect(meaningBox(2)).toHaveValue(
      "Late, lost or broken in transit. Often.",
    );
  });

  it("keeps the rows as typed and as ordered where a change is refused for the list written since it was read", async () => {
    const { readAfresh, kept } = editing(LIST, {
      refusing: "DRAFT_WRITTEN_SINCE_READ",
    });
    await userEvent.type(meaningBox(1), " Twice.");

    await userEvent.click(onTerm("Move down", "Billing"));

    expect(await within(part()).findByRole("alert")).toHaveTextContent(
      "Somebody changed this draft since it was read; nothing was saved.",
    );
    expect(rowsRead()).toEqual([
      ["Billing", "Money taken wrongly. Twice."],
      ["Delivery", "Late, lost or broken in transit."],
      ["Refunds", "Money owed back."],
    ]);
    expect(kept()).toEqual(LIST);
    expect(readAfresh).not.toHaveBeenCalled();
  });

  it("reads the list afresh where it was written since it was read only once Reload is pressed", async () => {
    const { readAfresh } = editing(LIST, {
      refusing: "DRAFT_WRITTEN_SINCE_READ",
    });
    await userEvent.click(onTerm("Move down", "Billing"));
    const reload = await within(part()).findByRole("button", {
      name: "Reload",
    });
    expect(readAfresh).not.toHaveBeenCalled();

    await userEvent.click(reload);

    expect(readAfresh).toHaveBeenCalledTimes(1);
  });

  it.each([
    [
      "an added term",
      async () => {
        await typingNewTerm();
        await userEvent.click(
          within(part()).getByRole("button", { name: "Add the term" }),
        );
      },
      `${setApart("Returns")} is added as the last term.`,
    ],
    [
      "a saved term",
      async () => {
        await userEvent.type(termBox(2), " times");
        await userEvent.click(onTerm("Save", "Delivery"));
      },
      `${setApart("Delivery times")} is saved.`,
    ],
    [
      "a term moved down, with its new place",
      () => userEvent.click(onTerm("Move down", "Billing")),
      `${setApart("Billing")} is moved to place 2.`,
    ],
    [
      "a term moved up, with its new place",
      () => userEvent.click(onTerm("Move up", "Refunds")),
      `${setApart("Refunds")} is moved to place 2.`,
    ],
    [
      "a removed term",
      () => userEvent.click(onTerm("Remove", "Delivery")),
      `${setApart("Delivery")} is removed.`,
    ],
  ])(
    "says %s in the table's own line once the write has landed",
    async (_case, change, words) => {
      editing(LIST);
      const line = within(part()).getByRole("status");

      await change();

      await waitFor(() => expect(line).toHaveTextContent(words));
      expect(within(part()).getAllByRole("status")).toEqual([line]);
    },
  );

  it.each([
    [
      "a change to a term",
      "remove" as const,
      () => userEvent.click(onTerm("Remove", "Delivery")),
    ],
    [
      "an added term",
      "add" as const,
      async () => {
        await typingNewTerm();
        await userEvent.click(
          within(part()).getByRole("button", { name: "Add the term" }),
        );
      },
    ],
  ])(
    "lets go of what a landed write said once %s is asked, so it stands beside no refusal of that",
    async (_case, change, press) => {
      const { changes } = editing(LIST);
      const line = within(part()).getByRole("status");
      await userEvent.click(onTerm("Move down", "Billing"));
      await waitFor(() =>
        expect(line).toHaveTextContent(
          `${setApart("Billing")} is moved to place 2.`,
        ),
      );
      changes[change].mockImplementationOnce(() =>
        Promise.reject(
          new RequestFailed({ status: 409, code: "VERSION_STANDING_REFUSES" }),
        ),
      );

      await press();

      expect(await within(part()).findByRole("alert")).toHaveTextContent(
        "That version's standing does not admit this.",
      );
      await waitForFrames();
      expect(line).toHaveTextContent(/^$/);
    },
  );

  it("says nothing in the table's own line where a write is refused", async () => {
    editing(LIST, { refusing: "VERSION_STANDING_REFUSES" });

    await userEvent.click(onTerm("Remove", "Delivery"));

    await within(part()).findByRole("alert");
    await waitForFrames();
    expect(within(part()).getByRole("status")).toHaveTextContent(/^$/);
  });

  it("holds every change while another write of the host's is out, which it would be refused beside, and sends nothing", async () => {
    const { changes } = editing(LIST, { waiting: true });
    await userEvent.type(termBox(1), "s");
    const held = [
      onTerm("Save", "Billing"),
      onTerm("Move down", "Billing"),
      onTerm("Remove", "Billing"),
      within(part()).getByRole("button", { name: "Add the term" }),
    ];

    for (const control of held) {
      control.focus();
      await userEvent.keyboard("{Enter}");
    }

    held.forEach((control) =>
      expect(control).toHaveAttribute("aria-disabled", "true"),
    );
    expect(changes.reword).not.toHaveBeenCalled();
    expect(changes.move).not.toHaveBeenCalled();
    expect(changes.remove).not.toHaveBeenCalled();
    expect(changes.add).not.toHaveBeenCalled();
  });

  /** The page may read the version again after a refusal and find it no longer written here. */
  it("keeps a refused change said once the terms may no longer be written, offering nothing to write or reload", async () => {
    editing(LIST, {
      refusing: "VERSION_STANDING_REFUSES",
      closeOnRefusal: true,
    });

    await userEvent.click(onTerm("Remove", "Delivery"));

    expect(await within(part()).findByRole("alert")).toHaveTextContent(
      "That version's standing does not admit this.",
    );
    expect(within(part()).queryByRole("textbox")).toBeNull();
    expect(within(part()).queryByRole("button", { name: "Reload" })).toBeNull();
  });

  it("puts the keyboard on the heading once a refusal leaves the terms no longer to be written", async () => {
    editing(LIST, {
      refusing: "VERSION_STANDING_REFUSES",
      closeOnRefusal: true,
    });

    await userEvent.click(onTerm("Remove", "Delivery"));

    await waitFor(() => expect(heading()).toHaveFocus());
    expect(within(part()).queryByRole("button")).toBeNull();
  });

  it.each([
    [true, "is on its heading"],
    [false, "is left where it was"],
  ])(
    "where the table is drawn afresh by a press inside it (%s), the keyboard %s",
    (arriving) => {
      editing(LIST, { focusOnArrival: arriving });

      expect(heading() === document.activeElement).toBe(arriving);
    },
  );
});
