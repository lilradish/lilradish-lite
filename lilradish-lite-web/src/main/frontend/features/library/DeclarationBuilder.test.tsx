import { ThemeProvider } from "@mui/material/styles";
import { render, screen, waitFor, within } from "@testing-library/react";
import { userEvent } from "@testing-library/user-event";
import { useState, type ReactNode } from "react";
import { describe, expect, it, vi } from "vitest";

import type {
  DeclaredField,
  ListVersion,
  SentField,
} from "../../api/declaration";
import { RequestFailed } from "../../api/problem";
import { useAction } from "../../lib/request/useAction";
import { theme } from "../../lib/theme/theme";
import { DeclarationBuilder } from "./DeclarationBuilder";
import { draftOf, type Demands, type DraftField } from "./declarationDrafts";
import QUESTION from "./questionDemands.json";
import WORKFLOW from "./workflowDemands.json";

const TAKES = QUESTION.takes as Demands;

const GIVES = QUESTION.gives as Demands;

/** A half whose values stood where they were made, as a workflow's is. */
const STOOD = WORKFLOW.gives as Demands;

const OLDER = "00000007-0000-4000-8000-000000000c31";

const NEWER = "00000007-0000-4000-8000-000000000c32";

const CHANNELS = "00000007-0000-4000-8000-000000000c33";

const LISTS: readonly ListVersion[] = [
  { name: "Categories", versionId: NEWER, number: 2 },
  { name: "Channels", versionId: CHANNELS, number: 1 },
];

const COMPLAINT: DeclaredField = {
  fieldId: "0000000b-0000-4000-8000-000000000c31",
  name: "complaint",
  label: "The complaint",
  help: "As written.",
  kind: "text",
  longest: 4000,
  many: false,
  mustBeGiven: true,
};

const ADDRESSES: DeclaredField = {
  fieldId: "0000000b-0000-4000-8000-000000000c33",
  name: "addresses",
  kind: "text",
  many: true,
  most: 3,
  mustBeGiven: true,
};

const SENDER: DeclaredField = {
  fieldId: "0000000b-0000-4000-8000-000000000c32",
  name: "sender",
  kind: "fields",
  many: false,
  mustBeGiven: false,
  fields: [ADDRESSES],
};

const RECEIVED: DeclaredField = {
  fieldId: "0000000b-0000-4000-8000-000000000c35",
  name: "received",
  kind: "moment",
  many: false,
  mustBeGiven: true,
};

const CATEGORY: DeclaredField = {
  fieldId: "0000000b-0000-4000-8000-000000000c34",
  name: "category",
  kind: "term",
  many: false,
  list: {
    name: "Categories",
    versionId: OLDER,
    number: 1,
    standing: "in_service",
    newer: { versionId: NEWER, number: 2 },
  },
  mustBeGiven: true,
  stands: "above_confidence",
  floor: 80,
};

function setApart(words: string): string {
  return `${String.fromCodePoint(0x2068)}${words}${String.fromCodePoint(0x2069)}`;
}

function themed({ children }: { readonly children: ReactNode }) {
  return <ThemeProvider theme={theme}>{children}</ThemeProvider>;
}

/** A field sent back as the server would read it, every member it was sent, under a key of its own. */
function declaredAs(sent: SentField, index: number): DeclaredField {
  const list = LISTS.find((each) => each.versionId === sent.list);
  return {
    fieldId: `saved-${index}-${sent.name}`,
    name: sent.name,
    kind: sent.kind,
    many: sent.many,
    mustBeGiven: sent.mustBeGiven,
    ...(sent.label === null ? {} : { label: sent.label }),
    ...(sent.help === null ? {} : { help: sent.help }),
    ...(sent.most === null ? {} : { most: sent.most }),
    ...(sent.longest == null ? {} : { longest: sent.longest }),
    ...(list === undefined
      ? {}
      : { list: { ...list, standing: "in_service" } }),
    ...(sent.stands == null ? {} : { stands: sent.stands }),
    ...(sent.floor == null ? {} : { floor: sent.floor }),
    ...(sent.fields === undefined
      ? {}
      : { fields: sent.fields.map(declaredAs) }),
  };
}

/** The builder as a host draws it, over the real action it is handed, drawing what each save answers. */
function building(
  demands: Demands,
  fields: readonly DeclaredField[],
  editable: boolean,
  answer: (
    fields: readonly SentField[],
  ) => Promise<readonly DeclaredField[]> = (sent) =>
    Promise.resolve(sent.map(declaredAs)),
  closeOnRefusal = false,
  host: { readonly waiting?: boolean; readonly focusOnArrival?: boolean } = {},
) {
  const saved = vi.fn(answer);
  const readAfresh = vi.fn<() => void>();
  const shown: { fields: readonly DeclaredField[] } = { fields };
  function Host() {
    const [held, setHeld] = useState(fields);
    const [open, setOpen] = useState(editable);
    const saving = useAction<readonly DeclaredField[]>(
      (answered) => {
        shown.fields = answered;
        setHeld(answered);
      },
      () => setOpen(!closeOnRefusal && open),
    );
    return (
      <DeclarationBuilder
        title="What it takes"
        heading="h3"
        demands={demands}
        fields={held}
        lists={LISTS}
        editable={open}
        waiting={host.waiting ?? false}
        saveLabel="Save what it takes"
        underway="Saving."
        rule="author_entry"
        action={saving}
        save={(sent) => saved(sent)}
        focusOnArrival={host.focusOnArrival ?? false}
        onReadAfresh={readAfresh}
      />
    );
  }
  render(<Host />, { wrapper: themed });
  return { saved, shown, readAfresh };
}

function part(): HTMLElement {
  return screen.getByRole("region", { name: "What it takes" });
}

function heading(): HTMLElement {
  return screen.getByRole("heading", { level: 3, name: "What it takes" });
}

function row(name: string): HTMLElement {
  return screen.getByRole("group", { name });
}

function legendOf(name: string): HTMLElement {
  return row(name).querySelector("legend")!;
}

function save(): HTMLElement {
  return screen.getByRole("button", { name: "Save what it takes" });
}

function pressIn(name: string, button: string): Promise<void> {
  return userEvent.click(
    within(row(name)).getByRole("button", {
      name: `${button} ${setApart(name)}`,
    }),
  );
}

function moving(name: string, way: "up" | "down"): Promise<void> {
  return userEvent.click(
    within(row(name)).getByRole("button", {
      name: `Move ${setApart(name)} ${way}`,
    }),
  );
}

describe("DeclarationBuilder", () => {
  it("reads each field where it may not be written, the fields another holds under it, and offers nothing to change", () => {
    building(TAKES, [COMPLAINT, SENDER], false);

    const first = within(part()).getByRole("list", { name: "What it takes" });
    const rows = within(first).getAllByRole("listitem");
    expect(rows.map((each) => each.firstChild?.textContent)).toEqual([
      "The complaint complaint",
      "sender sender",
      "addresses addresses",
    ]);
    expect(rows[0]).toHaveTextContent(
      "Text, At most 4000 characters, Holds one, Must be given",
    );
    expect(rows[0]).toHaveTextContent("As written.");
    expect(rows[1]).toHaveTextContent(
      "Fields of its own, Holds one, May be left empty",
    );
    expect(within(rows[1]!).getByRole("list")).toContainElement(rows[2]!);
    expect(rows[2]).toHaveTextContent(
      "Text, How long not said yet, Holds many, at most 3, Must be given",
    );
    expect(part().querySelectorAll("input, textarea, button")).toHaveLength(0);
  });

  it("reads a value given back that must be given by that, and by the confidence it stands above", () => {
    building(GIVES, [CATEGORY], false);

    const row = within(part()).getByRole("listitem");
    expect(row).toHaveTextContent(
      `A term, From ${setApart("Categories")}, version 1, Holds one, Must be given, Stands above 80 percent`,
    );
    expect(row).not.toHaveTextContent("May be left empty");
  });

  it("reads a value given back that may be left empty as such, saying what is not chosen of it yet", () => {
    const { longest: _unsaidLongest, ...unsaid } = COMPLAINT;
    building(GIVES, [{ ...unsaid, mustBeGiven: false }], false);

    const row = within(part()).getByRole("listitem");
    expect(row).toHaveTextContent(
      "Text, How long not said yet, Holds one, May be left empty, What it takes to stand not said yet",
    );
    expect(row).not.toHaveTextContent("Must be given");
  });

  it("says a half declaring nothing declares nothing, and holds a save that would change nothing", () => {
    building(TAKES, [], true);

    expect(part()).toHaveTextContent("Nothing is declared.");
    expect(save()).toHaveAttribute("aria-disabled", "true");
    expect(part()).toHaveTextContent("Change something first.");
  });

  /** Each field a group of its own named by its name, holding the controls its kind and depth take. */
  it("draws a row per field where it may be written, each with the controls its kind and depth take", () => {
    building(TAKES, [COMPLAINT, SENDER], true);

    expect(
      within(part()).getByRole("list", { name: "What it takes" }),
    ).toContainElement(row("complaint"));
    expect(
      within(row("complaint")).getByRole("textbox", { name: "Name" }),
    ).toHaveValue("complaint");
    expect(
      within(row("complaint")).getByRole("textbox", {
        name: "How long, in characters",
      }),
    ).toHaveValue("4000");
    expect(
      within(row("complaint")).getByRole("checkbox", { name: "Must be given" }),
    ).toBeChecked();
    expect(
      within(row("complaint")).queryByRole("combobox", {
        name: "What it takes to stand",
      }),
    ).toBeNull();
    expect(row("sender")).toContainElement(row("addresses"));
    expect(
      within(row("addresses")).getByRole("textbox", { name: "At most" }),
    ).toHaveValue("3");
    expect(
      within(row("sender")).getByRole("button", {
        name: `Add a field inside ${setApart("sender")}`,
      }),
    ).toBeInTheDocument();
    expect(save()).toHaveAttribute("aria-disabled", "true");
  });

  it("asks a value given back what it takes to stand, and a field it holds only whether it must be given", () => {
    building(GIVES, [{ ...SENDER, stands: "never" }], true);

    expect(
      within(row("sender")).getAllByRole("combobox", {
        name: "What it takes to stand",
      }),
    ).toHaveLength(1);
    expect(
      within(row("addresses")).queryByRole("combobox", {
        name: "What it takes to stand",
      }),
    ).toBeNull();
    expect(
      within(row("addresses")).getByRole("checkbox", { name: "Must be given" }),
    ).toBeChecked();
  });

  it("asks above which confidence once a value given back stands above a stated one, and asks no field it holds", async () => {
    building(GIVES, [{ ...SENDER, stands: "never" }], true);

    await userEvent.click(
      within(row("sender")).getAllByRole("combobox", {
        name: "What it takes to stand",
      })[0]!,
    );
    await userEvent.click(
      screen.getByRole("option", { name: "Stands above a stated confidence" }),
    );

    expect(
      within(row("sender")).getAllByRole("textbox", {
        name: "Above, in percent",
      }),
    ).toHaveLength(1);
    expect(
      within(row("addresses")).queryByRole("textbox", {
        name: "Above, in percent",
      }),
    ).toBeNull();
  });

  /** What each depth is asked is the host's to say, so a half no value of which stands asks no field that. */
  it("asks at each depth only what the host's demands ask there", () => {
    building(STOOD, [{ ...SENDER, stands: "never" }], true);

    expect(
      within(part()).getAllByRole("checkbox", { name: "Must be given" }),
    ).toHaveLength(2);
    expect(
      within(part()).queryByRole("combobox", {
        name: "What it takes to stand",
      }),
    ).toBeNull();
    expect(
      within(row("addresses")).getByRole("textbox", { name: "At most" }),
    ).toBeInTheDocument();
  });

  it("sends whether a value given back must be given beside what it takes to stand", async () => {
    const { saved } = building(GIVES, [CATEGORY], true);

    await userEvent.click(
      within(row("category")).getByRole("checkbox", { name: "Must be given" }),
    );
    await userEvent.click(save());

    expect(saved).toHaveBeenCalledTimes(1);
    expect(saved.mock.calls[0]![0][0]).toMatchObject({
      mustBeGiven: false,
      stands: "above_confidence",
      floor: 80,
    });
  });

  /** A pin not the newest in service says so beside it, and the picker offers the newer beside the one pinned. */
  it("offers a field of terms the lists that may be pinned now, keeps the one pinned, and says where a newer is in service", async () => {
    building(GIVES, [CATEGORY], true);

    expect(row("category")).toHaveTextContent(
      "Version 2 of this list is in service and newer.",
    );
    expect(row("category")).not.toHaveTextContent("retired");
    await userEvent.click(
      within(row("category")).getByRole("combobox", { name: "From" }),
    );
    expect(
      screen.getAllByRole("option").map((each) => each.textContent),
    ).toEqual([
      "None chosen yet",
      `${setApart("Categories")}, version 1`,
      `${setApart("Categories")}, version 2`,
      `${setApart("Channels")}, version 1`,
    ]);

    await userEvent.click(
      screen.getByRole("option", {
        name: `${setApart("Categories")}, version 2`,
      }),
    );

    expect(row("category")).not.toHaveTextContent("is in service and newer");
  });

  /** A draft keeps a pin retired since; only submitting refuses it, which is what the row says. */
  it.each([
    [
      { versionId: NEWER, number: 2 },
      "The version pinned is retired, and version 2 is in service. It is kept while this is a draft, and submitting refuses it.",
    ],
    [
      undefined,
      "The version pinned is retired. It is kept while this is a draft, and submitting refuses it.",
    ],
  ])(
    "says a pin retired since is kept until submitting, and names the newest in service where one is",
    async (newer, said) => {
      const { saved } = building(
        GIVES,
        [
          {
            ...CATEGORY,
            list: {
              name: "Categories",
              versionId: OLDER,
              number: 1,
              standing: "retired",
              ...(newer === undefined ? {} : { newer }),
            },
          },
        ],
        true,
      );

      expect(row("category")).toHaveTextContent(said);
      expect(row("category")).not.toHaveTextContent("is in service and newer");

      await userEvent.type(
        within(row("category")).getByRole("textbox", { name: "Label" }),
        "Category",
      );
      await userEvent.click(save());

      expect(saved.mock.calls[0]![0][0]!.list).toBe(OLDER);
      expect(saved.mock.calls[0]![0][0]!.fieldId).toBe(CATEGORY.fieldId);
    },
  );

  /** What is sent is exactly the half as written: a field added, named, made a moment and moved up. */
  it("sends the half whole as written, a field added, changed and moved, and draws what the save answered", async () => {
    const { saved, shown } = building(TAKES, [COMPLAINT], true);

    await userEvent.click(screen.getByRole("button", { name: "Add a field" }));
    await userEvent.type(
      within(row("Field 2, not named yet")).getByRole("textbox", {
        name: "Name",
      }),
      "received",
    );
    await userEvent.click(
      within(row("received")).getByRole("combobox", { name: "What it is" }),
    );
    await userEvent.click(screen.getByRole("option", { name: "A moment" }));
    await userEvent.click(
      within(row("received")).getByRole("checkbox", { name: "Must be given" }),
    );
    await moving("received", "up");
    await userEvent.click(save());

    expect(saved).toHaveBeenCalledTimes(1);
    expect(saved.mock.calls[0]![0]).toEqual([
      {
        fieldId: null,
        name: "received",
        label: null,
        help: null,
        kind: "moment",
        many: false,
        most: null,
        mustBeGiven: false,
      },
      {
        fieldId: COMPLAINT.fieldId,
        name: "complaint",
        label: "The complaint",
        help: "As written.",
        kind: "text",
        many: false,
        most: null,
        longest: 4000,
        mustBeGiven: true,
      },
    ]);
    await waitFor(() =>
      expect(shown.fields.map((field) => field.fieldId)).toEqual([
        "saved-0-received",
        "saved-1-complaint",
      ]),
    );
    expect(
      within(row("received")).getByRole("combobox", { name: "What it is" }),
    ).toHaveTextContent("A moment");
    expect(save()).toHaveAttribute("aria-disabled", "true");
  });

  it("removes a field and the fields it holds, and adds one inside a field of fields", async () => {
    const { saved } = building(TAKES, [COMPLAINT, SENDER], true);

    await userEvent.click(
      within(row("sender")).getByRole("button", {
        name: `Add a field inside ${setApart("sender")}`,
      }),
    );
    expect(within(row("sender")).getAllByRole("group")).toHaveLength(2);
    await pressIn("complaint", "Remove");
    await userEvent.type(
      within(row("Field 2, not named yet")).getByRole("textbox", {
        name: "Name",
      }),
      "postcode",
    );
    await userEvent.click(save());

    expect(saved.mock.calls[0]![0].map((field) => field.name)).toEqual([
      "sender",
    ]);
    expect(
      saved.mock.calls[0]![0][0]!.fields!.map((field) => field.name),
    ).toEqual(["addresses", "postcode"]);
    expect(screen.queryByRole("group", { name: "complaint" })).toBeNull();
  });

  /** Neither end drops its control, which would drop the focus on it with it. */
  it("draws both moves on every row, unavailable at either end, and moves nothing past one", async () => {
    const { saved } = building(TAKES, [COMPLAINT, RECEIVED], true);
    const up = within(row("complaint")).getByRole("button", {
      name: `Move ${setApart("complaint")} up`,
    });
    const down = within(row("received")).getByRole("button", {
      name: `Move ${setApart("received")} down`,
    });

    expect(up).toHaveAttribute("aria-disabled", "true");
    expect(down).toHaveAttribute("aria-disabled", "true");
    expect(
      within(row("complaint")).getByRole("button", {
        name: `Move ${setApart("complaint")} down`,
      }),
    ).not.toHaveAttribute("aria-disabled");

    up.focus();
    await userEvent.keyboard("{Enter}");
    down.focus();
    await userEvent.keyboard("{Enter}");

    expect(save()).toHaveAttribute("aria-disabled", "true");
    expect(saved).not.toHaveBeenCalled();
  });

  /** Moving re-inserts a row, which takes focus from whatever it held; focus follows the row moved. */
  it.each([
    ["complaint", "down"],
    ["received", "up"],
  ] as const)(
    "keeps focus on the move pressed after moving %s %s",
    async (name, way) => {
      building(TAKES, [COMPLAINT, RECEIVED], true);

      await moving(name, way);

      const moved = within(part())
        .getAllByRole("group")
        .map((each) => each.querySelector("legend")!.textContent);
      expect(moved).toEqual(["received", "complaint"]);
      expect(
        within(row(name)).getByRole("button", {
          name: `Move ${setApart(name)} ${way}`,
        }),
      ).toHaveFocus();
    },
  );

  it("puts focus on the row after the one removed, else the one before, else the one holding it, else the heading", async () => {
    building(TAKES, [COMPLAINT, SENDER, RECEIVED], true);

    await pressIn("sender", "Remove");
    expect(legendOf("received")).toHaveFocus();

    await pressIn("received", "Remove");
    expect(legendOf("complaint")).toHaveFocus();

    await pressIn("complaint", "Remove");
    expect(heading()).toHaveFocus();
  });

  it("puts focus on the row holding a field removed where it held no other", async () => {
    building(TAKES, [SENDER], true);

    await pressIn("addresses", "Remove");

    expect(legendOf("sender")).toHaveFocus();
  });

  it("puts focus in the name of a field added, on the first level or inside another", async () => {
    building(TAKES, [SENDER], true);

    await userEvent.click(screen.getByRole("button", { name: "Add a field" }));
    expect(
      within(row("Field 2, not named yet")).getByRole("textbox", {
        name: "Name",
      }),
    ).toHaveFocus();

    await userEvent.click(
      within(row("sender")).getByRole("button", {
        name: `Add a field inside ${setApart("sender")}`,
      }),
    );
    const inside = within(row("sender")).getByRole("group", {
      name: "Field 2, not named yet",
    });
    expect(within(inside).getByRole("textbox", { name: "Name" })).toHaveFocus();
  });

  /** Unnamed rows are told apart by their place, and the one holding the save is marked as the one. */
  it("names each unnamed row by its place, and marks the row holding the save with why", async () => {
    building(TAKES, [COMPLAINT], true);

    await userEvent.click(screen.getByRole("button", { name: "Add a field" }));
    await userEvent.click(screen.getByRole("button", { name: "Add a field" }));

    expect(row("Field 2, not named yet")).toHaveAccessibleDescription(
      "This field keeps the half from being saved: A field's name is one to 63 lowercase English letters, digits and underscores, starting with a letter.",
    );
    expect(row("Field 3, not named yet")).not.toHaveAccessibleDescription();
    expect(row("complaint")).not.toHaveAccessibleDescription();
    expect(save()).toHaveAttribute("aria-disabled", "true");
  });

  it("warns before a field holding fields is made another kind that its fields are then left out", () => {
    building(TAKES, [SENDER], true);

    expect(
      within(row("sender")).getAllByRole("combobox", { name: "What it is" })[0],
    ).toHaveAccessibleDescription(
      "Changing what it is leaves out the fields it holds; they are sent again only if it holds fields once more.",
    );
    expect(
      within(row("addresses")).getByRole("combobox", { name: "What it is" }),
    ).not.toHaveAccessibleDescription();
  });

  /** An edit made while a save is out would be neither sent nor kept once the save answers. */
  it("takes no edit while a save is out, and every row's controls stay where focus can be", async () => {
    let answer: (fields: readonly DeclaredField[]) => void = () => {};
    const { saved } = building(
      TAKES,
      [COMPLAINT],
      true,
      () =>
        new Promise((resolve) => {
          answer = resolve;
        }),
    );
    const name = within(row("complaint")).getByRole("textbox", {
      name: "Name",
    });
    await userEvent.type(name, "_text");
    await userEvent.click(save());

    await userEvent.type(name, "_more");
    for (const button of [
      within(row("complaint_text")).getByRole("button", {
        name: `Remove ${setApart("complaint_text")}`,
      }),
      screen.getByRole("button", { name: "Add a field" }),
    ]) {
      button.focus();
      await userEvent.keyboard("{Enter}");
    }
    const boxes = ["Holds many", "Must be given"].map((checkbox) =>
      within(row("complaint_text")).getByRole("checkbox", { name: checkbox }),
    );
    for (const box of boxes) {
      await userEvent.click(box);
    }

    expect(name).toHaveValue("complaint_text");
    expect(name).toHaveAttribute("readonly");
    expect(boxes.map((box) => (box as HTMLInputElement).checked)).toEqual([
      false,
      true,
    ]);
    for (const box of boxes) {
      expect(box).toHaveAttribute("aria-readonly", "true");
    }
    expect(within(part()).getAllByRole("group")).toHaveLength(1);
    expect(
      within(row("complaint_text")).getByRole("button", {
        name: `Remove ${setApart("complaint_text")}`,
      }),
    ).toHaveAttribute("aria-disabled", "true");
    answer([COMPLAINT]);
    await waitFor(() => expect(name).not.toHaveAttribute("readonly"));
    expect(
      within(row("complaint_text")).getByRole("checkbox", {
        name: "Holds many",
      }),
    ).toHaveAttribute("aria-readonly", "false");
    expect(saved).toHaveBeenCalledTimes(1);
  });

  /** Refused where it is typed as well as where it arrives, and the reason is said under the save it holds. */
  it.each([
    [
      "Name",
      "Complaint",
      "A field's name is one to 63 lowercase English letters, digits and underscores, starting with a letter.",
    ],
    [
      "How long, in characters",
      "0",
      "A limit is a whole number from 1 to 2147483647.",
    ],
    [
      "How long, in characters",
      "2147483648",
      "A limit is a whole number from 1 to 2147483647.",
    ],
    [
      "Label",
      "l".repeat(129),
      "A label is at most 128 characters on one line, with something in it that shows.",
    ],
  ])(
    "holds the save back while a %s is one the server refuses, saying why",
    async (control, typed, why) => {
      const { saved } = building(TAKES, [COMPLAINT], true);
      const box = within(row("complaint")).getByRole("textbox", {
        name: control,
      });

      await userEvent.clear(box);
      await userEvent.type(box, typed);
      save().focus();
      await userEvent.keyboard("{Enter}");

      expect(saved).not.toHaveBeenCalled();
      expect(save()).toHaveAttribute("aria-disabled", "true");
      expect(part()).toHaveTextContent(why);
    },
  );

  it("says a refused save beside the half that asked it, under the rule writing asks, and keeps what was typed", async () => {
    building(TAKES, [COMPLAINT], true, () =>
      Promise.reject(
        new RequestFailed({ status: 400, code: "FIELD_NAME_UNUSABLE" }),
      ),
    );
    const name = within(row("complaint")).getByRole("textbox", {
      name: "Name",
    });

    await userEvent.type(name, "_reference");
    await userEvent.click(save());

    expect(await within(part()).findByRole("alert")).toHaveTextContent(
      "A field's name is one to 63 lowercase English letters, digits and underscores, starting with a letter.",
    );
    expect(name).toHaveValue("complaint_reference");
    expect(within(part()).queryByRole("button", { name: "Reload" })).toBeNull();
  });

  /** Somebody else's write is not undone by this one, nor is what was typed here thrown away unasked. */
  it("offers to read the half afresh where the draft was written since it was read, keeping what was typed until that is pressed", async () => {
    const { readAfresh } = building(TAKES, [COMPLAINT], true, () =>
      Promise.reject(
        new RequestFailed({ status: 409, code: "DRAFT_WRITTEN_SINCE_READ" }),
      ),
    );
    const name = within(row("complaint")).getByRole("textbox", {
      name: "Name",
    });
    await userEvent.type(name, "_reference");
    await userEvent.click(save());
    expect(await within(part()).findByRole("alert")).toHaveTextContent(
      "Somebody changed this draft since it was read; nothing was saved.",
    );
    expect(name).toHaveValue("complaint_reference");
    expect(readAfresh).not.toHaveBeenCalled();

    await userEvent.click(
      within(part()).getByRole("button", { name: "Reload" }),
    );

    expect(readAfresh).toHaveBeenCalledTimes(1);
  });

  it("holds the save while another write of the host's is out, which it would be refused beside, and sends nothing", async () => {
    const { saved } = building(TAKES, [COMPLAINT], true, undefined, false, {
      waiting: true,
    });

    await userEvent.type(
      within(row("complaint")).getByRole("textbox", { name: "Name" }),
      "_text",
    );
    save().focus();
    await userEvent.keyboard("{Enter}");

    expect(save()).toHaveAttribute("aria-disabled", "true");
    expect(saved).not.toHaveBeenCalled();
  });

  it.each([
    [true, "is on its heading"],
    [false, "is left where it was"],
  ])(
    "where the half is drawn afresh by a press inside it (%s), the keyboard %s",
    (arriving) => {
      building(TAKES, [COMPLAINT], true, undefined, false, {
        focusOnArrival: arriving,
      });

      expect(heading() === document.activeElement).toBe(arriving);
    },
  );

  /** The page may read the version again after a refusal and find it no longer written here. */
  it("keeps a refused save said once the half may no longer be written, and puts focus on its heading", async () => {
    building(
      TAKES,
      [COMPLAINT],
      true,
      () =>
        Promise.reject(
          new RequestFailed({ status: 409, code: "VERSION_STANDING_REFUSES" }),
        ),
      true,
    );

    await userEvent.type(
      within(row("complaint")).getByRole("textbox", { name: "Name" }),
      "_text",
    );
    await userEvent.click(save());

    expect(await within(part()).findByRole("alert")).toHaveTextContent(
      "That version's standing does not admit this.",
    );
    expect(within(part()).queryByRole("textbox")).toBeNull();
    expect(heading()).toHaveFocus();
  });

  /** A route's half is sent with the steps, so the page holds it and the builder only edits it. */
  it("hands every edit to a host holding the half, and offers no save of its own", async () => {
    const changed = vi.fn<(drafts: DraftField[]) => void>();
    function Host() {
      const [drafts, setDrafts] = useState<readonly DraftField[]>([
        draftOf(COMPLAINT),
      ]);
      return (
        <DeclarationBuilder
          title="What it takes"
          heading="h3"
          demands={TAKES}
          fields={[COMPLAINT]}
          lists={LISTS}
          editable
          controlled={{
            drafts,
            change: (next) => {
              changed(next);
              setDrafts(next);
            },
          }}
        />
      );
    }
    render(<Host />, { wrapper: themed });

    await userEvent.click(screen.getByRole("button", { name: "Add a field" }));
    await userEvent.type(
      within(row("Field 2, not named yet")).getByRole("textbox", {
        name: "Name",
      }),
      "note",
    );

    expect(changed).toHaveBeenLastCalledWith([
      expect.objectContaining({
        fieldId: COMPLAINT.fieldId,
        name: "complaint",
      }),
      expect.objectContaining({ fieldId: null, name: "note" }),
    ]);
    expect(row("note")).toBeInTheDocument();
    expect(within(part()).queryByRole("button", { name: /^Save/ })).toBeNull();
  });

  it("hands a host holding a half whether each field must be given", async () => {
    const changed = vi.fn<(drafts: DraftField[]) => void>();
    render(
      <DeclarationBuilder
        title="What it takes"
        heading="h5"
        demands={STOOD}
        fields={[SENDER]}
        lists={LISTS}
        editable
        controlled={{ drafts: [draftOf(SENDER)], change: changed }}
      />,
      { wrapper: themed },
    );

    await userEvent.click(
      within(row("addresses")).getByRole("checkbox", { name: "Must be given" }),
    );

    const read = draftOf(SENDER);
    expect(changed).toHaveBeenCalledTimes(1);
    expect(changed).toHaveBeenCalledWith([
      { ...read, fields: [{ ...read.fields[0]!, mustBeGiven: false }] },
    ]);
  });

  it.each([
    ["h3", 3, 5],
    ["h5", 5, 3],
  ] as const)(
    "titles the half at the level it is handed, %s, and at no other",
    (heading, level, other) => {
      render(
        <DeclarationBuilder
          title="What it takes"
          heading={heading}
          demands={STOOD}
          fields={[SENDER]}
          lists={LISTS}
          editable
          controlled={{ drafts: [draftOf(SENDER)], change: vi.fn() }}
        />,
        { wrapper: themed },
      );

      expect(
        screen.getByRole("heading", { level, name: "What it takes" }),
      ).toBeInTheDocument();
      expect(
        screen.queryByRole("heading", { level: other, name: "What it takes" }),
      ).toBeNull();
    },
  );
});
