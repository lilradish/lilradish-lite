import { ThemeProvider } from "@mui/material/styles";
import { fireEvent, render, screen, within } from "@testing-library/react";
import { userEvent } from "@testing-library/user-event";
import { useCallback, useEffect, useState } from "react";
import { describe, expect, it, vi } from "vitest";

import type { FillField, FillReason } from "../../../api/filling";
import { codePointsIn } from "../../../lib/filling/writing";
import { theme } from "../../../lib/theme/theme";
import { draftOf, placedAt, type LevelDraft } from "./fillDrafts";
import {
  FillForm,
  placeId,
  type Drafted,
  type Left,
  type Unread,
} from "./FillForm";

vi.mock("../../../lib/filling/writing", async (importOriginal) => {
  const actual =
    await importOriginal<typeof import("../../../lib/filling/writing")>();
  return { ...actual, codePointsIn: vi.fn(actual.codePointsIn) };
});

const COMPLAINT: FillField = {
  name: "complaint",
  label: "Complaint",
  help: "What went wrong, in the customer's words.",
  kind: "text",
  longest: 10,
  mustBeGiven: true,
};

const ORDER_REFERENCE: FillField = {
  name: "order_reference",
  kind: "number",
  mustBeGiven: false,
};

const DUE: FillField = { name: "due", kind: "date", mustBeGiven: false };

const AT: FillField = {
  name: "at",
  label: "At",
  kind: "moment",
  mustBeGiven: false,
};

const URGENT: FillField = {
  name: "urgent",
  label: "Urgent",
  kind: "yes_no",
  mustBeGiven: true,
};

const CATEGORY: FillField = {
  name: "category",
  label: "Category",
  kind: "term",
  mustBeGiven: false,
  terms: {
    terms: [
      { term: "Billing", meaning: "A charge is what is disputed." },
      { term: "Delivery", meaning: "It came late or not at all." },
    ],
    note: "Pick the one the customer named first.",
  },
};

const TAGS: FillField = {
  name: "tags",
  label: "Tags",
  help: "Words to find it by.",
  kind: "text",
  longest: 6,
  most: 2,
  mustBeGiven: false,
};

const CONTACT: FillField = {
  name: "contact",
  label: "Contact",
  kind: "fields",
  mustBeGiven: false,
  fields: [
    {
      name: "email",
      label: "Email",
      kind: "text",
      longest: 20,
      mustBeGiven: true,
    },
  ],
};

const FIELDS = [
  COMPLAINT,
  ORDER_REFERENCE,
  DUE,
  AT,
  URGENT,
  CATEGORY,
  TAGS,
  CONTACT,
];

const NO_MARKS: ReadonlyMap<string, FillReason> = new Map();

/** Set apart from the words around it, as a label within a sentence is. */
const isolatedName = (name: string) =>
  `${String.fromCodePoint(0x2068)}${name}${String.fromCodePoint(0x2069)}`;

/**
 * The form over a draft of its own, placing each change as the page does, with handlers that stay the same from
 * one change to the next as the page's do; every change is also handed to `onDraft`, and the draft it makes to
 * `onDrafted`.
 */
function Filled({
  fields,
  marks,
  onDraft,
  onDrafted,
  onLeave,
  onUnread,
}: {
  readonly fields: readonly FillField[];
  readonly marks: ReadonlyMap<string, FillReason>;
  readonly onDraft: Drafted;
  readonly onDrafted: (draft: LevelDraft) => void;
  readonly onLeave: Left;
  readonly onUnread: Unread;
}) {
  const [draft, setDraft] = useState(() => draftOf(fields));
  const placing = useCallback<Drafted>(
    (path, next, takenAway) => {
      onDraft(path, next, takenAway);
      setDraft((prior) => placedAt(prior, path, next));
    },
    [onDraft],
  );
  useEffect(() => onDrafted(draft), [draft, onDrafted]);
  return (
    <FillForm
      formId="filling"
      fields={fields}
      draft={draft}
      marks={marks}
      onDraft={placing}
      onLeave={onLeave}
      onUnread={onUnread}
    />
  );
}

function filling(
  fields: readonly FillField[] = FIELDS,
  marks: ReadonlyMap<string, FillReason> = NO_MARKS,
) {
  const onDraft = vi.fn<Drafted>();
  const onDrafted = vi.fn<(draft: LevelDraft) => void>();
  const onLeave = vi.fn<Left>();
  const onUnread = vi.fn<Unread>();
  const { container } = render(
    <ThemeProvider theme={theme}>
      <Filled
        fields={fields}
        marks={marks}
        onDraft={onDraft}
        onDrafted={onDrafted}
        onLeave={onLeave}
        onUnread={onUnread}
      />
    </ThemeProvider>,
  );
  return {
    container,
    drafted: (): LevelDraft => onDrafted.mock.lastCall![0],
    touched: () => onDraft.mock.lastCall![0],
    takenAway: () => onDraft.mock.lastCall![2],
    left: () => onLeave.mock.calls.map(([path]) => path),
    unread: () => onUnread.mock.lastCall,
  };
}

function addOneTo(label: string): HTMLElement {
  return within(group(label)).getByRole("button", {
    name: `Add one to ${isolatedName(label)}`,
  });
}

function group(name: string): HTMLElement {
  return screen.getByRole("group", { name });
}

describe("placeId", () => {
  it("gives each place one id holding no whitespace, by which its control is found, and two places two", () => {
    filling();

    const email = placeId("filling", ["contact", "email"]);

    expect(email).not.toMatch(/[\t\n\f\r ]/);
    expect(document.getElementById(email)).toBe(
      within(group("Contact")).getByRole("textbox", { name: "Email" }),
    );
    expect(placeId("filling", ["tags", 0])).not.toBe(
      placeId("filling", ["tags", "0"]),
    );
    expect(placeId("filling", ["a-b"])).not.toBe(
      placeId("filling", ["a", "b"]),
    );
  });
});

describe("FillForm", () => {
  it("draws one control to each field in declared order and what a field holds under it, each read by its label, or its name with underscores as spaces", () => {
    const { container } = filling();

    const said = [...container.querySelectorAll(".MuiFormLabel-root bdi")].map(
      (label) => label.textContent,
    );

    expect(said).toEqual([
      "Complaint",
      "order reference",
      "due",
      "At",
      "Urgent",
      "Category",
      "Tags",
      "Contact",
      "Email",
    ]);
  });

  it("says a field's help under its control, and how long its text may run beside how long it runs", async () => {
    filling();
    const complaint = screen.getByRole("textbox", { name: "Complaint" });

    await userEvent.type(complaint, "Leaks");

    expect(complaint).toHaveAccessibleDescription(
      "What went wrong, in the customer's words. 5 / at most 10",
    );
    expect(complaint).toHaveAttribute("dir", "auto");
  });

  it("keeps text typed past its limit whole rather than cutting it", async () => {
    const { drafted } = filling();

    await userEvent.type(
      screen.getByRole("textbox", { name: "Complaint" }),
      "Leaks everywhere",
    );

    expect(drafted().complaint).toBe("Leaks everywhere");
    expect(
      screen.getByRole("textbox", { name: "Complaint" }),
    ).toHaveAccessibleDescription(
      "What went wrong, in the customer's words. 16 / at most 10",
    );
  });

  it("draws again only the control typed in, every other one, within fields as well, drawn as it was", async () => {
    filling();
    await userEvent.type(
      within(group("Contact")).getByRole("textbox", { name: "Email" }),
      "ada@example.org",
    );
    vi.mocked(codePointsIn).mockClear();

    await userEvent.type(
      screen.getByRole("textbox", { name: "Complaint" }),
      "K",
    );

    expect(vi.mocked(codePointsIn).mock.calls).toEqual([["K"]]);
    expect(
      within(group("Contact")).getByRole("textbox", { name: "Email" }),
    ).toHaveAccessibleDescription("15 / at most 20");
  });

  it("says why a value does not fit where it is marked, first under its control, and marks no other", () => {
    filling(FIELDS, new Map([['["complaint"]', "too_long" as const]]));

    const complaint = screen.getByRole("textbox", { name: "Complaint" });
    expect(complaint).toHaveAttribute("aria-invalid", "true");
    expect(complaint).toHaveAccessibleDescription(
      "This is longer than this field takes. What went wrong, in the customer's words. 0 / at most 10",
    );
    expect(
      screen.getByRole("textbox", { name: "order reference" }),
    ).toHaveAttribute("aria-invalid", "false");
  });

  it("asks for a number on a keyboard that has a minus sign, and says how it is written", () => {
    filling();

    const number = screen.getByRole("textbox", { name: "order reference" });

    expect(number).toHaveAttribute("inputmode", "text");
    expect(number).toHaveAccessibleDescription(
      "Written in digits, with a point before any fraction, such as 1234.5, in at most 38 digits in all.",
    );
  });

  it("asks for a date as a date, from the first day of year one to the last of 9999", () => {
    const { container } = filling([DUE]);

    const due = container.querySelector("input[type=date]")!;

    expect(due).toHaveAttribute("min", "0001-01-01");
    expect(due).toHaveAttribute("max", "9999-12-31");
  });

  it("says a day the browser holds and cannot read as it is left, and says so no longer once what it holds is a day", () => {
    const { container, unread, left } = filling([DUE]);
    const due = container.querySelector<HTMLInputElement>("input[type=date]")!;
    const badInput = vi.spyOn(due.validity, "badInput", "get");
    expect(unread()).toEqual([["due"], false]);

    badInput.mockReturnValue(true);
    fireEvent.blur(due);
    expect(unread()).toEqual([["due"], true]);
    expect(left()).toEqual([["due"]]);

    badInput.mockReturnValue(false);
    fireEvent.change(due, { target: { value: "2026-07-01" } });
    expect(unread()).toEqual([["due"], false]);
  });

  it("offers yes and no to choose between, and nothing chosen, where it must be given", () => {
    filling();

    const radios = within(group("Urgent")).getAllByRole("radio");

    expect(radios.map((radio) => radio.getAttribute("value"))).toEqual([
      "true",
      "false",
    ]);
    expect(radios.some((radio) => (radio as HTMLInputElement).checked)).toBe(
      false,
    );
  });

  it("offers each term with what it means under the list's note, and nothing to type", async () => {
    const { drafted } = filling();
    const category = group("Category");

    await userEvent.click(
      within(category).getByRole("radio", { name: /Delivery/ }),
    );

    expect(
      within(category).getByText("Pick the one the customer named first."),
    ).toBeInTheDocument();
    expect(
      within(category).getByRole("radio", {
        name: /It came late or not at all\./,
      }),
    ).toBeChecked();
    expect(
      within(category).getByRole("radio", { name: "Leave empty" }),
    ).not.toBeChecked();
    expect(within(category).queryByRole("textbox")).toBeNull();
    expect(drafted().category).toBe("Delivery");
  });

  it("leaves a term that need not be given empty until one is chosen, and offers no such choice where it must be given", () => {
    filling();

    expect(
      within(group("Category")).getByRole("radio", { name: "Leave empty" }),
    ).toBeChecked();
    expect(
      within(group("Urgent")).queryByRole("radio", { name: "Leave empty" }),
    ).toBeNull();
  });

  it("names a choice by its field and describes it by why it does not fit, saying it is wrong and must be given where so", () => {
    filling(FIELDS, new Map([['["urgent"]', "missing" as const]]));

    const urgent = screen.getByRole("radiogroup", { name: "Urgent" });
    const category = screen.getByRole("radiogroup", { name: "Category" });

    expect(urgent).toHaveAccessibleDescription("This must be given.");
    expect(urgent).toHaveAttribute("aria-invalid", "true");
    expect(urgent).toHaveAttribute("aria-required", "true");
    expect(category).toHaveAccessibleDescription("");
    expect(category).not.toHaveAttribute("aria-invalid");
    expect(category).not.toHaveAttribute("aria-required");
  });

  it("describes a moment's date, time and offset by why it does not fit, and none of them where it fits", () => {
    filling(FIELDS, new Map([['["at"]', "malformed" as const]]));
    const at = group("At");
    const controls = [
      within(at).getByLabelText("Date"),
      within(at).getByLabelText("Time"),
      within(at).getByRole("combobox", { name: "Offset" }),
    ];

    for (const control of controls) {
      expect(control).toHaveAccessibleDescription(
        "This is not written as this field takes it.",
      );
      expect(control).toHaveAttribute("aria-invalid", "true");
    }
  });

  it("describes no moment's control as wrong where nothing is marked", () => {
    filling();
    const at = group("At");

    expect(
      within(at).getByRole("combobox", { name: "Offset" }),
    ).toHaveAccessibleDescription("");
    expect(
      within(at).getByRole("combobox", { name: "Offset" }),
    ).not.toHaveAttribute("aria-invalid");
    expect(within(at).getByLabelText("Date")).toHaveAttribute(
      "aria-invalid",
      "false",
    );
  });

  it("names fields and many by their field, and describes them by why they do not fit and their help", () => {
    filling(FIELDS, new Map([['["contact"]', "missing" as const]]));

    expect(group("Contact")).toHaveAccessibleDescription("This must be given.");
    expect(group("Tags")).toHaveAccessibleDescription(
      "Words to find it by. 0 / at most 2",
    );
  });

  it("says a place is left once the keyboard leaves it, and a moment only once it leaves the date, the time and the offset", async () => {
    const { left } = filling([COMPLAINT, AT]);

    await userEvent.click(screen.getByRole("textbox", { name: "Complaint" }));
    await userEvent.click(within(group("At")).getByLabelText("Date"));
    expect(left()).toEqual([["complaint"]]);

    await userEvent.click(within(group("At")).getByLabelText("Time"));
    expect(left()).toEqual([["complaint"]]);

    await userEvent.click(screen.getByRole("textbox", { name: "Complaint" }));
    expect(left()).toEqual([["complaint"], ["at"]]);
  });

  it("says a moment is not left while its offset is chosen from the menu drawn outside it, and left once the keyboard leaves it after", async () => {
    const { left, drafted } = filling([COMPLAINT, AT]);
    const at = group("At");

    await userEvent.click(within(at).getByLabelText("Date"));
    await userEvent.click(within(at).getByRole("combobox", { name: "Offset" }));
    await userEvent.click(screen.getByRole("option", { name: "+05:45" }));
    expect(left()).toEqual([]);
    expect(drafted().at).toEqual({ date: "", time: "", offset: "+05:45" });

    await userEvent.click(screen.getByRole("textbox", { name: "Complaint" }));
    expect(left()).toEqual([["at"]]);
  });

  it("starts many with none, adds one to fill, and offers no more once it holds its most", async () => {
    const { drafted, takenAway } = filling();
    const tags = group("Tags");
    expect(within(tags).getByText("None yet.")).toBeInTheDocument();

    await userEvent.click(addOneTo("Tags"));
    await userEvent.type(
      within(tags).getByRole("textbox", { name: `${isolatedName("Tags")} 1` }),
      "kettle",
    );
    await userEvent.click(addOneTo("Tags"));

    expect(drafted().tags).toEqual(["kettle", ""]);
    expect(takenAway()).toBeUndefined();
    expect(within(tags).queryByRole("button", { name: /Add one/ })).toBeNull();
    expect(within(tags).queryByText("None yet.")).toBeNull();
    expect(within(tags).getByText("2 / at most 2")).toBeInTheDocument();
  });

  it("says the help of many once, over all of them, and not again under each", async () => {
    filling();
    const tags = group("Tags");

    await userEvent.click(addOneTo("Tags"));

    expect(within(tags).getAllByText("Words to find it by.")).toHaveLength(1);
    expect(
      within(tags).getByRole("textbox", { name: `${isolatedName("Tags")} 1` }),
    ).toHaveAccessibleDescription("0 / at most 6");
  });

  it("draws each one of many as one that must be given, though the field may hold none", async () => {
    const answers: FillField = {
      name: "answers",
      label: "Answers",
      kind: "yes_no",
      most: 2,
      mustBeGiven: false,
    };
    filling([TAGS, answers]);

    await userEvent.click(addOneTo("Tags"));
    await userEvent.click(addOneTo("Answers"));

    expect(
      screen.getByRole("textbox", { name: `${isolatedName("Tags")} 1` }),
    ).toBeRequired();
    const answer = screen.getByRole("radiogroup", {
      name: `${isolatedName("Answers")} 1`,
    });
    expect(answer).toHaveAttribute("aria-required", "true");
    expect(
      within(answer).queryByRole("radio", { name: "Leave empty" }),
    ).toBeNull();
  });

  it("takes away the one of many it names, keeping the others in their order and each control its own", async () => {
    const { drafted, touched, takenAway } = filling();
    const tags = group("Tags");
    await userEvent.click(addOneTo("Tags"));
    await userEvent.type(
      within(tags).getByRole("textbox", { name: `${isolatedName("Tags")} 1` }),
      "first",
    );
    await userEvent.click(addOneTo("Tags"));
    const second = within(tags).getByRole("textbox", {
      name: `${isolatedName("Tags")} 2`,
    });
    await userEvent.type(second, "second");

    await userEvent.click(
      within(tags).getByRole("button", {
        name: `Take away ${isolatedName("Tags")} 1`,
      }),
    );

    expect(drafted().tags).toEqual(["second"]);
    expect(touched()).toEqual(["tags"]);
    expect(takenAway()).toBe(0);
    expect(within(tags).getAllByRole("textbox")).toEqual([second]);
    expect(second).toHaveAccessibleName(`${isolatedName("Tags")} 1`);
    expect(addOneTo("Tags")).toBeInTheDocument();
  });

  it("takes the keyboard to the control of the one added, even where adding it took Add one away", async () => {
    filling();
    const tags = group("Tags");

    await userEvent.click(addOneTo("Tags"));
    expect(
      within(tags).getByRole("textbox", { name: `${isolatedName("Tags")} 1` }),
    ).toHaveFocus();

    await userEvent.click(addOneTo("Tags"));
    expect(
      within(tags).getByRole("textbox", { name: `${isolatedName("Tags")} 2` }),
    ).toHaveFocus();
    expect(within(tags).queryByRole("button", { name: /Add one/ })).toBeNull();
  });

  it("takes the keyboard to the next one's Take away once one is taken away, and to the group named and described by the field once none follows", async () => {
    filling();
    const tags = group("Tags");
    await userEvent.click(addOneTo("Tags"));
    await userEvent.click(addOneTo("Tags"));

    await userEvent.click(
      within(tags).getByRole("button", {
        name: `Take away ${isolatedName("Tags")} 1`,
      }),
    );
    expect(
      within(tags).getByRole("button", {
        name: `Take away ${isolatedName("Tags")} 1`,
      }),
    ).toHaveFocus();

    await userEvent.click(
      within(tags).getByRole("button", {
        name: `Take away ${isolatedName("Tags")} 1`,
      }),
    );
    expect(within(tags).queryByRole("textbox")).toBeNull();
    expect(tags).toHaveFocus();
    expect(tags).toHaveAccessibleDescription(
      "Words to find it by. 0 / at most 2",
    );
    expect(tags.querySelector("legend")).not.toHaveFocus();
  });

  it("draws the fields a field holds under it, a change to one naming where it stands", async () => {
    const { drafted, touched } = filling();

    await userEvent.type(
      within(group("Contact")).getByRole("textbox", { name: "Email" }),
      "a",
    );

    expect(drafted().contact).toEqual({ email: "a" });
    expect(touched()).toEqual(["contact", "email"]);
  });

  it("starts a moment's offset at the reader's own on the day chosen, following it until another is chosen", async () => {
    const { drafted } = filling();
    const at = group("At");
    const offset = within(at).getByRole("combobox", { name: "Offset" });

    fireEvent.change(within(at).getByLabelText("Date"), {
      target: { value: "2026-07-01" },
    });
    fireEvent.change(within(at).getByLabelText("Time"), {
      target: { value: "09:30" },
    });
    expect(offset).toHaveTextContent("+02:00");

    fireEvent.change(within(at).getByLabelText("Date"), {
      target: { value: "2026-12-01" },
    });
    expect(offset).toHaveTextContent("+01:00");

    await userEvent.click(offset);
    await userEvent.click(screen.getByRole("option", { name: "-03:30" }));
    fireEvent.change(within(at).getByLabelText("Date"), {
      target: { value: "2026-07-01" },
    });

    expect(offset).toHaveTextContent("-03:30");
    expect(drafted().at).toEqual({
      date: "2026-07-01",
      time: "09:30",
      offset: "-03:30",
    });
  });

  it("offers every quarter hour either way as far as fourteen hours, and never a zero offset with a minus sign", async () => {
    filling();

    await userEvent.click(
      within(group("At")).getByRole("combobox", { name: "Offset" }),
    );
    const offsets = screen
      .getAllByRole("option")
      .map((option) => option.textContent);

    expect(offsets).toHaveLength(113);
    expect(offsets[0]).toBe("-14:00");
    expect(offsets.at(-1)).toBe("+14:00");
    expect(offsets).toContain("+05:45");
    expect(offsets).not.toContain("-00:00");
  });
});
