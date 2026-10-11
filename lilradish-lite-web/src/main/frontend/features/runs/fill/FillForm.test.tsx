import { buttonClasses } from "@mui/material/Button";
import { ThemeProvider } from "@mui/material/styles";
import {
  act,
  createEvent,
  fireEvent,
  render,
  screen,
  within,
} from "@testing-library/react";
import { userEvent } from "@testing-library/user-event";
import { useCallback, useEffect, useState } from "react";
import { describe, expect, it, vi } from "vitest";

import type { FillField, FillReason } from "../../../api/filling";
import { codePointsIn } from "../../../lib/filling/writing";
import { isolatedInText } from "../../../lib/direction/isolated";
import { ModelProse } from "../../../lib/text/ModelProse";
import { theme } from "../../../lib/theme/theme";
import {
  draftFrom,
  draftOf,
  draftReader,
  placedAt,
  type LevelDraft,
} from "./fillDrafts";
import {
  FillForm,
  placeId,
  type Drafted,
  type Left,
  type Unread,
} from "./FillForm";

function visibleFileName(name: string): HTMLElement {
  const names = [...document.querySelectorAll("bdi")].filter(
    (element) =>
      element.closest("[hidden]") === null && element.textContent === name,
  );
  expect(names).toHaveLength(1);
  return names[0];
}

function fileStatus(
  name: RegExp,
  words: RegExp,
  root: ParentNode = document,
): HTMLElement | null {
  return (
    [...root.querySelectorAll("bdi")]
      .map((part) => part.parentElement)
      .find(
        (part) =>
          part !== null &&
          name.test(part.firstElementChild?.textContent ?? "") &&
          words.test(part.textContent ?? ""),
      ) ?? null
  );
}

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

const ATTACHMENT: FillField = {
  name: "attachment",
  label: "Attachment",
  kind: "file",
  types: [".txt", ".json"],
  maximumBytes: 8,
  mustBeGiven: true,
};

const ATTACHMENTS: FillField = {
  ...ATTACHMENT,
  name: "attachments",
  label: "Attachments",
  most: 2,
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
  initial,
}: {
  readonly fields: readonly FillField[];
  readonly marks: ReadonlyMap<string, FillReason>;
  readonly onDraft: Drafted;
  readonly onDrafted: (draft: LevelDraft) => void;
  readonly onLeave: Left;
  readonly onUnread: Unread;
  readonly initial?: LevelDraft;
}) {
  const [draft, setDraft] = useState(() => initial ?? draftOf(fields));
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
  initial?: LevelDraft,
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
        initial={initial}
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
  const names: Readonly<Record<string, string>> = {
    Contact: "Contact · contact",
    Category: "Category · category",
    Urgent: "Urgent · urgent",
    At: "At · at",
    Tags: "Tags · tags",
    Attachments: "Attachments · attachments",
    Answers: "Answers · answers",
  };
  return screen.getByRole("group", { name: names[name] ?? name });
}

describe("placeId", () => {
  it("gives each place one id holding no whitespace, by which its control is found, and two places two", () => {
    filling();

    const email = placeId("filling", ["contact", "email"]);

    expect(email).not.toMatch(/[\t\n\f\r ]/);
    expect(document.getElementById(email)).toBe(
      within(group("Contact")).getByRole("textbox", { name: "Email · email" }),
    );
    expect(placeId("filling", ["tags", 0])).not.toBe(
      placeId("filling", ["tags", "0"]),
    );
    expect(placeId("filling", ["a-b"])).not.toBe(
      placeId("filling", ["a", "b"]),
    );
  });
});

describe("many terms", () => {
  it.each([false, true])(
    "keeps exactly five terms as individual choices, many %s",
    async (many) => {
      const terms = Array.from({ length: 5 }, (_, index) => ({
        term: `Route ${index + 1}`,
        meaning: `Destination ${index + 1}`,
      }));
      const { drafted } = filling([
        { ...CATEGORY, ...(many ? { most: 2 } : {}), terms: { terms } },
      ]);
      expect(screen.queryByRole("combobox")).toBeNull();
      expect(screen.getAllByRole(many ? "checkbox" : "radio")).toHaveLength(
        6 - Number(many),
      );
      await userEvent.click(
        screen.getByRole(many ? "checkbox" : "radio", {
          name: /Route 3.*Destination 3/,
        }),
      );
      if (many)
        await userEvent.click(
          screen.getByRole("checkbox", { name: /Route 4.*Destination 4/ }),
        );
      expect(drafted().category).toEqual(
        many ? ["Route 3", "Route 4"] : "Route 3",
      );
    },
  );

  it.each([false, true])(
    "names the dropdown for more than five terms and accepts a keyboard choice, many %s",
    async (many) => {
      const terms = Array.from({ length: 6 }, (_, index) => ({
        term: `Route ${index + 1}`,
        meaning: `Destination ${index + 1}`,
      }));
      const { drafted } = filling([
        { ...CATEGORY, ...(many ? { most: 2 } : {}), terms: { terms } },
      ]);
      const select = screen.getByRole("combobox", {
        name: "Category · category",
      });
      await act(async () => {
        select.focus();
      });
      await userEvent.keyboard("{ArrowDown}");
      const list = await screen.findByRole("listbox");
      await userEvent.click(
        within(list).getByRole("option", { name: /Route 3.*Destination 3/ }),
      );
      if (many)
        await userEvent.click(
          within(list).getByRole("option", { name: /Route 4.*Destination 4/ }),
        );
      expect(drafted().category).toEqual(
        many ? ["Route 3", "Route 4"] : "Route 3",
      );
      if (many) await userEvent.keyboard("{Escape}");
      expect(select).toHaveFocus();
    },
  );
  it("offers at most five terms as independent cards, enforcing the maximum and retaining selection when one is removed", async () => {
    const form = filling([{ ...CATEGORY, most: 1 }]);
    const billing = screen.getByRole("checkbox", { name: /Billing/ });
    const delivery = screen.getByRole("checkbox", { name: /Delivery/ });
    expect(screen.queryByRole("combobox")).toBeNull();
    await userEvent.click(billing);
    expect(form.drafted()).toMatchObject({ category: ["Billing"] });
    expect(billing).toBeChecked();
    expect(delivery).toBeDisabled();
    await userEvent.click(billing);
    expect(form.drafted()).toMatchObject({ category: [] });
    expect(delivery).toBeEnabled();
    await userEvent.click(delivery);
    expect(form.drafted()).toMatchObject({ category: ["Delivery"] });
    expect(
      screen.getByRole("group", { name: "Category · category" }),
    ).toHaveAccessibleDescription(expect.stringContaining("1 / 1"));
  });

  it("offers more than five terms in one multi-select, preserving selected terms and disabling only additions at the limit", async () => {
    const terms = [
      "Billing",
      "Delivery",
      "Packing",
      "Quality",
      "Return",
      "Other",
    ].map((term) => ({ term, meaning: `${term} concern.` }));
    const form = filling([{ ...CATEGORY, most: 2, terms: { terms } }]);
    await userEvent.click(screen.getByRole("combobox"));
    await userEvent.click(screen.getByRole("option", { name: /Billing/ }));
    await userEvent.click(screen.getByRole("option", { name: /Quality/ }));
    expect(form.drafted()).toMatchObject({ category: ["Billing", "Quality"] });
    expect(screen.getByRole("option", { name: /Delivery/ })).toHaveAttribute(
      "aria-disabled",
      "true",
    );
    expect(screen.getByRole("option", { name: /Billing/ })).toHaveAttribute(
      "aria-selected",
      "true",
    );
    await userEvent.click(screen.getByRole("option", { name: /Billing/ }));
    expect(form.drafted()).toMatchObject({ category: ["Quality"] });
    expect(
      screen.getByRole("option", { name: /Delivery/ }),
    ).not.toHaveAttribute("aria-disabled", "true");
    await userEvent.keyboard("{Escape}");
    expect(screen.getByRole("combobox")).toHaveTextContent("Quality");
  });
});

describe("text lines", () => {
  it("draws one line as an input and several or older text as a textarea", () => {
    filling([
      { ...COMPLAINT, lines: "one" },
      { ...COMPLAINT, name: "details", label: "Details", lines: "several" },
      { ...COMPLAINT, name: "older", label: "Older" },
    ]);

    expect(
      screen.getByRole("textbox", { name: "Complaint · complaint" }).tagName,
    ).toBe("INPUT");
    expect(
      screen.getByRole("textbox", { name: "Details · details" }).tagName,
    ).toBe("TEXTAREA");
    expect(screen.getByRole("textbox", { name: "Older · older" }).tagName).toBe(
      "TEXTAREA",
    );
  });

  it("refuses pasted and dropped line breaks before an input can change its value", () => {
    const { drafted } = filling([{ ...COMPLAINT, lines: "one", longest: 20 }]);
    const input = screen.getByRole("textbox", {
      name: "Complaint · complaint",
    });
    fireEvent.change(input, { target: { value: "Demo Sample" } });
    const newline = String.fromCodePoint(0x0a);
    const paste = createEvent.paste(input, {
      clipboardData: { getData: () => `a${newline}b` },
    });

    fireEvent(input, paste);

    expect(paste.defaultPrevented).toBe(true);
    expect(input).toHaveValue("Demo Sample");
    expect(drafted().complaint).toBe("Demo Sample");
    expect(input).toHaveAccessibleDescription(
      /This field takes a single line; remove the line break\./,
    );

    const drop = createEvent.drop(input, {
      dataTransfer: { getData: () => `c${newline}d` },
    });
    fireEvent(input, drop);
    expect(drop.defaultPrevented).toBe(true);
    expect(input).toHaveValue("Demo Sample");
    expect(drafted().complaint).toBe("Demo Sample");

    const keypress = createEvent.keyPress(input, {
      key: "Enter",
      which: 10,
      charCode: 10,
    });
    fireEvent(input, keypress);
    expect(keypress.defaultPrevented).toBe(true);
    expect(input).toHaveValue("Demo Sample");
  });

  it("ends a rejected paste when a valid edit is made, even if the old value returns", () => {
    const { drafted } = filling([{ ...COMPLAINT, lines: "one" }]);
    const input = screen.getByRole("textbox", {
      name: "Complaint · complaint",
    });
    fireEvent.change(input, { target: { value: "A" } });
    const paste = createEvent.paste(input, {
      clipboardData: {
        getData: () => `A${String.fromCodePoint(0x0a)}B`,
      },
    });

    fireEvent(input, paste);
    expect(paste.defaultPrevented).toBe(true);
    expect(input).toHaveValue("A");
    expect(drafted().complaint).toBe("A");
    expect(input).toHaveAttribute("aria-invalid", "true");

    fireEvent.change(input, { target: { value: "B" } });
    expect(input).toHaveAttribute("aria-invalid", "false");
    fireEvent.change(input, { target: { value: "A" } });
    expect(drafted().complaint).toBe("A");
    expect(input).toHaveAttribute("aria-invalid", "false");
    expect(input).not.toHaveAccessibleDescription(/remove the line break/);
  });

  it("restores the caller's mark after a valid edit ends a rejected paste", () => {
    filling(
      [{ ...COMPLAINT, lines: "one" }],
      new Map([['["complaint"]', "too_long" as const]]),
    );
    const input = screen.getByRole("textbox", {
      name: "Complaint · complaint",
    });
    fireEvent.change(input, { target: { value: "A" } });
    fireEvent.paste(input, {
      clipboardData: {
        getData: () => `A${String.fromCodePoint(0x0a)}B`,
      },
    });
    expect(input).toHaveAccessibleDescription(/remove the line break/);

    fireEvent.change(input, { target: { value: "B" } });
    expect(input).toHaveAccessibleDescription(/longer than this field takes/);
    expect(input).not.toHaveAccessibleDescription(/remove the line break/);
  });
});

describe("file controls", () => {
  it.each([false, true])(
    "keeps keyboard picking and removal on the visible file chooser, many %s",
    async (many) => {
      const of: FillField = {
        name: "attachment",
        label: "Attachment",
        kind: "file",
        types: [".txt"],
        maximumBytes: 8,
        mustBeGiven: false,
        ...(many ? { most: 2 } : {}),
      };
      const { container, drafted } = filling([of]);
      const chooser = screen.getByRole("button", {
        name: many ? "Choose files" : "Choose a file",
      });
      const native =
        container.querySelector<HTMLInputElement>('input[type="file"]')!;
      await userEvent.tab();
      expect(chooser).toHaveFocus();
      expect(native).toHaveAttribute("tabindex", "-1");
      const clicked = vi.fn();
      native.addEventListener("click", clicked);
      await userEvent.keyboard("{Enter}");
      await userEvent.keyboard(" ");
      expect(clicked).toHaveBeenCalledTimes(2);
      await userEvent.tab();
      expect(native).not.toHaveFocus();
      const file = new File(["x"], "Imaginary note.txt", {
        type: "text/plain",
      });
      await userEvent.upload(native, file);
      expect(drafted().attachment).toEqual(
        many ? [{ pending: file }] : { pending: file },
      );
      await userEvent.click(
        screen.getByRole("button", { name: /Remove.*Imaginary note.txt/ }),
      );
      expect(drafted().attachment).toEqual(many ? [] : null);
      expect(chooser).toHaveFocus();
      expect(
        screen.queryByRole("button", { name: /Remove.*Imaginary note.txt/ }),
      ).toBeNull();
    },
  );
  it("takes native dropped files in order, draws shared cards without download links and rejects an invalid whole batch", async () => {
    const user = userEvent.setup();
    const { drafted, unread } = filling([ATTACHMENTS]);
    const area = screen.getByRole("group", {
      name: "Attachments · attachments",
    });
    const first = new File(["one"], "Order.txt", { type: "text/plain" });
    const second = new File(["{}"], "Invoice.json", {
      type: "application/json",
    });
    const over = createEvent.dragOver(area, {
      dataTransfer: { files: [first, second], dropEffect: "none" },
    });
    fireEvent(area, over);
    expect(over.defaultPrevented).toBe(true);
    fireEvent.drop(area, { dataTransfer: { files: [first, second] } });
    expect(drafted().attachments).toEqual([
      { pending: first },
      { pending: second },
    ]);
    expect(within(area).queryByRole("link")).toBeNull();
    expect(
      within(area).getAllByTestId("InsertDriveFileOutlinedIcon"),
    ).toHaveLength(2);
    expect(area.textContent!.indexOf("Order.txt")).toBeLessThan(
      area.textContent!.indexOf("Invoice.json"),
    );
    const bad = new File(["no"], "Picture.png", { type: "image/png" });
    fireEvent.drop(area, { dataTransfer: { files: [bad] } });
    expect(drafted().attachments).toEqual([
      { pending: first },
      { pending: second },
    ]);
    expect(unread()).toEqual([["attachments"], true]);
    expect(area).toHaveTextContent("No files from this selection were added.");
    expect(area).toHaveTextContent("Picture.png");
    await user.click(
      within(area).getByRole("button", {
        name: `Remove ${isolatedName("Order.txt")}`,
      }),
    );
    expect(drafted().attachments).toEqual([{ pending: second }]);
    expect(unread()).toEqual([["attachments"], false]);
  });
  it("shows a single file field's extension and size limits, holds a selected browser file, then removes it", () => {
    const file = new File(["data"], "Demo Sample.txt", { type: "text/plain" });
    const { drafted, touched } = filling([ATTACHMENT]);
    const input = screen.getByLabelText(/Attachment/) as HTMLInputElement;

    expect(input).toHaveAttribute("accept", ".txt,.json");
    expect(
      screen.getAllByText(
        `Drop a file here or choose one · ${isolatedInText(".txt, .json")} · up to 8 B`,
      ),
    ).toHaveLength(1);
    expect(drafted().attachment).toBeNull();

    fireEvent.change(input, { target: { files: [file] } });

    expect(drafted().attachment).toEqual({ pending: file });
    expect(touched()).toEqual(["attachment"]);
    expect(visibleFileName("Demo Sample.txt")).toBeVisible();
    expect(screen.queryByText(/awaits upload/)).toBeNull();
    expect(input).toHaveAccessibleDescription(
      `Drop a file here or choose one · ${isolatedInText(".txt, .json")} · up to 8 B`,
    );

    fireEvent.click(
      screen.getByRole("button", { name: /Remove.*Demo Sample.txt/ }),
    );
    expect(drafted().attachment).toBeNull();
    expect(screen.queryByText(/awaits upload/)).toBeNull();
    expect(screen.getByRole("button", { name: "Choose a file" })).toHaveFocus();
  });

  it.each([
    [false, "Drop a file here or choose one", "up to 1.5 MB"],
    [
      true,
      "Drop files here or choose them",
      "up to 2 files, each up to 1.5 MB",
    ],
  ])(
    "says a size limit past a kilobyte in the largest unit it reaches, many %s",
    (many, opening, limit) => {
      filling([
        { ...(many ? ATTACHMENTS : ATTACHMENT), maximumBytes: 1_572_864 },
      ]);

      expect(
        screen.getAllByText(
          `${opening} \xb7 ${isolatedInText(".txt, .json")} \xb7 ${limit}`,
        ),
      ).toHaveLength(1);
      expect(screen.queryByText(/1,572,864|1,536/)).toBeNull();
    },
  );

  it.each(["", "text/plain;", "image/png"])(
    "accepts the filename extension without using the browser MIME claim %s",
    (type) => {
      const file = new File(["x"], "Demo Sample.TXT", { type });
      const { drafted } = filling([ATTACHMENT]);
      const input = screen.getByLabelText(/Attachment/);

      fireEvent.change(input, { target: { files: [file] } });

      expect(drafted().attachment).toEqual({ pending: file });
      expect(visibleFileName("Demo Sample.TXT")).toBeVisible();
      expect(screen.queryByText(/awaits upload/)).toBeNull();
      expect(input).not.toHaveAttribute("aria-invalid", "true");
    },
  );

  it.each([
    ["missing extension", new File(["x"], "Demo Sample"), /No file extension/],
    ["trailing dot", new File(["x"], "Demo Sample."), /No file extension/],
    [
      "wrong extension",
      new File(["x"], "Demo Sample.png", { type: "image/png" }),
      /doesn't have an extension this field accepts/,
    ],
    [
      "too large",
      new File(["123456789"], "Demo Sample.txt", { type: "text/plain" }),
      /larger than this field allows/,
    ],
    [
      "bad name",
      new File(["x"], "Demo\nSample.txt", { type: "text/plain" }),
      /needs a one-line name/,
    ],
  ])("names a selected file with %s immediately", (_case, file, message) => {
    const { drafted } = filling([ATTACHMENT]);
    const input = screen.getByLabelText(/Attachment/);

    fireEvent.change(input, { target: { files: [file] } });

    expect(drafted().attachment).toBeNull();
    expect(screen.getByText(message)).toBeInTheDocument();
    const status = fileStatus(/Demo/, message);
    expect(status).toBeVisible();
    expect(getComputedStyle(status!.lastElementChild!).fontStyle).toBe(
      "italic",
    );
    expect(screen.queryByText(/awaits upload/)).toBeNull();
    expect(input).toHaveAttribute("aria-invalid", "true");

    const dismiss = screen.getByRole("button", {
      name: "Dismiss rejected files",
    });
    expect(dismiss).toHaveClass(
      buttonClasses.outlined,
      buttonClasses.colorInherit,
    );
    dismiss.focus();
    expect(dismiss).toHaveFocus();
    fireEvent.click(dismiss);
    expect(screen.queryByText(message)).toBeNull();
    expect(drafted().attachment).toBeNull();
    expect(input).not.toHaveAttribute("aria-invalid", "true");
    expect(screen.getByRole("button", { name: "Choose a file" })).toHaveFocus();
  });

  it("keeps several selected files in order and removes one without changing the other", () => {
    const first = new File(["one"], "Demo Sample one.txt", {
      type: "text/plain",
    });
    const second = new File(["two"], "Demo Sample two.txt", {
      type: "text/plain",
    });
    const { drafted, takenAway } = filling([ATTACHMENTS]);
    const input = screen.getByLabelText(/Choose files for/);

    fireEvent.change(input, { target: { files: [first, second] } });

    expect(drafted().attachments).toEqual([
      { pending: first },
      { pending: second },
    ]);
    expect(screen.getByText("2 / 2")).toBeInTheDocument();
    expect(visibleFileName("Demo Sample one.txt")).toBeVisible();
    expect(visibleFileName("Demo Sample two.txt")).toBeVisible();
    expect(screen.queryByText(/is selected/)).toBeNull();

    fireEvent.click(
      screen.getByRole("button", { name: /Remove.*Demo Sample one.txt/ }),
    );
    expect(drafted().attachments).toEqual([{ pending: second }]);
    expect(takenAway()).toBe(0);
    expect(fileStatus(/Demo Sample one.txt/, /is selected/)).toBeNull();
    expect(screen.getByRole("button", { name: "Choose files" })).toHaveFocus();
  });

  it("names an invalid file in a multi-file selection and leaves the prior order alone", () => {
    const first = new File(["one"], "Demo Sample first.txt", {
      type: "text/plain",
    });
    const wrong = new File(["two"], "Demo Sample second.png", {
      type: "image/png",
    });
    const { drafted, unread } = filling([ATTACHMENTS]);
    const input = screen.getByLabelText(/Choose files for/);

    fireEvent.change(input, { target: { files: [first] } });
    fireEvent.change(input, { target: { files: [wrong] } });

    expect(drafted().attachments).toEqual([{ pending: first }]);
    expect(unread()).toEqual([["attachments"], true]);
    expect(
      fileStatus(
        /Demo Sample second.png/,
        /doesn't have an extension this field accepts/,
      ),
    ).toBeVisible();
    expect(input).toHaveAttribute("aria-invalid", "true");
  });

  it("rejects a multi-file selection above its count by the excess file's name without adding any of it", () => {
    const files = ["one", "two", "three"].map(
      (word) =>
        new File(["x"], `Demo Sample ${word}.txt`, { type: "text/plain" }),
    );
    const { drafted, unread } = filling([ATTACHMENTS]);
    const input = screen.getByLabelText(/Choose files for/);

    fireEvent.change(input, { target: { files } });

    expect(drafted().attachments).toEqual([]);
    expect(unread()).toEqual([["attachments"], true]);
    expect(fileStatus(/Demo Sample three.txt/, /would exceed/)).toBeVisible();
    expect(
      screen.getByText(/No files from this selection were added/),
    ).toBeInTheDocument();

    const dismiss = screen.getByRole("button", {
      name: "Dismiss rejected files",
    });
    expect(dismiss).toHaveClass(
      buttonClasses.outlined,
      buttonClasses.colorInherit,
    );
    fireEvent.click(dismiss);
    expect(unread()).toEqual([["attachments"], false]);
    expect(screen.queryByText(/would exceed/)).toBeNull();
    expect(screen.getByRole("button", { name: "Choose files" })).toHaveFocus();
  });

  it("pages through a large rejected selection without drawing more than one page", () => {
    const files = Array.from(
      { length: 160 },
      (_, index) =>
        new File(["x"], `Demo Sample ${index}.txt`, { type: "text/plain" }),
    );
    const { drafted, unread } = filling([ATTACHMENTS]);
    const input = screen.getByLabelText(/Choose files for/);

    fireEvent.change(input, { target: { files } });

    expect(drafted().attachments).toEqual([]);
    expect(unread()).toEqual([["attachments"], true]);
    expect(screen.getAllByText(/would exceed/)).toHaveLength(50);
    expect(fileStatus(/Demo Sample 2.txt/, /would exceed/)).toBeVisible();
    expect(fileStatus(/Demo Sample 159.txt/, /would exceed/)).toBeNull();
    const previous = screen.getByRole("button", {
      name: "Show previous rejected files.",
    });
    const more = screen.getByRole("button", {
      name: /Show 50 more rejected files/,
    });
    expect(previous).toHaveAttribute("aria-disabled", "true");
    expect(previous).toHaveClass(
      buttonClasses.colorInherit,
      buttonClasses.sizeSmall,
    );
    expect(more).toHaveClass(
      buttonClasses.colorInherit,
      buttonClasses.sizeSmall,
    );
    fireEvent.click(
      screen.getByRole("button", { name: /Show 50 more rejected files/ }),
    );
    expect(screen.getAllByText(/would exceed/)).toHaveLength(50);
    expect(fileStatus(/Demo Sample 2.txt/, /would exceed/)).toBeNull();
    expect(fileStatus(/Demo Sample 52.txt/, /would exceed/)).toBeVisible();
    fireEvent.click(
      screen.getByRole("button", { name: /Show 50 more rejected files/ }),
    );
    expect(screen.getAllByText(/would exceed/)).toHaveLength(50);
    expect(fileStatus(/Demo Sample 102.txt/, /would exceed/)).toBeVisible();
    fireEvent.click(
      screen.getByRole("button", { name: /Show 8 more rejected files/ }),
    );
    expect(screen.getAllByText(/would exceed/)).toHaveLength(8);
    expect(fileStatus(/Demo Sample 159.txt/, /would exceed/)).toBeVisible();
    expect(fileStatus(/Demo Sample 102.txt/, /would exceed/)).toBeNull();
    expect(
      screen.getByRole("button", {
        name: "All rejected files have been shown.",
      }),
    ).toHaveAttribute("aria-disabled", "true");
    fireEvent.click(
      screen.getByRole("button", { name: "Show previous rejected files." }),
    );
    expect(screen.getAllByText(/would exceed/)).toHaveLength(50);
    expect(fileStatus(/Demo Sample 102.txt/, /would exceed/)).toBeVisible();
    expect(fileStatus(/Demo Sample 159.txt/, /would exceed/)).toBeNull();
    fireEvent.click(
      screen.getByRole("button", { name: "Show previous rejected files." }),
    );
    fireEvent.click(
      screen.getByRole("button", { name: "Show previous rejected files." }),
    );
    expect(screen.getAllByText(/would exceed/)).toHaveLength(50);
    expect(fileStatus(/Demo Sample 2.txt/, /would exceed/)).toBeVisible();
    expect(fileStatus(/Demo Sample 52.txt/, /would exceed/)).toBeNull();
    expect(drafted().attachments).toEqual([]);
  });

  it("shows hidden filename controls as code points in selected, retained and rejected states", () => {
    const turn = String.fromCodePoint(0x202e);
    const name = `Demo${turn}Sample.txt`;
    const file = new File(["x"], name, { type: "text/plain" });
    const { container } = filling([ATTACHMENT, ATTACHMENTS]);
    const single = screen.getByLabelText(/Attachment\b/);
    const many = screen.getByLabelText(/Choose files for/);

    fireEvent.change(single, { target: { files: [file] } });
    expect(visibleFileName("DemoU+202ESample.txt")).toBeVisible();
    expect(screen.queryByText(/awaits upload/)).toBeNull();
    const takeAway = screen.getByRole("button", {
      name: /Remove.*U\+202E/,
    });
    expect(takeAway).toBeVisible();
    expect(takeAway).toHaveAccessibleName(/Remove.*U\+202E/);
    expect(container.textContent).not.toContain(turn);

    fireEvent.change(many, {
      target: {
        files: [
          new File(["x"], `Demo${turn}Sample.png`, { type: "image/png" }),
        ],
      },
    });
    expect(
      fileStatus(/DemoU\+202ESample.png/, /doesn't have an extension/),
    ).toBeVisible();
    expect(container.textContent).not.toContain(turn);

    const kept = { id: "stored", name, size: 1 };
    const retained = draftFrom([ATTACHMENT], { attachment: kept });
    const onDraft = vi.fn();
    const { container: prior } = render(
      <ThemeProvider theme={theme}>
        <FillForm
          formId="retained-control"
          fields={[ATTACHMENT]}
          draft={retained}
          marks={NO_MARKS}
          onDraft={onDraft}
          onLeave={vi.fn()}
          onUnread={vi.fn()}
        />
      </ThemeProvider>,
    );
    const retainedName = [...prior.querySelectorAll("bdi")].filter(
      (each) =>
        each.closest("[hidden]") === null &&
        each.textContent === "DemoU+202ESample.txt",
    );
    expect(retainedName).toHaveLength(1);
    expect(retainedName[0]).toBeVisible();
    expect(prior.textContent).not.toContain(turn);
    expect(JSON.stringify(retained)).toContain(name);
  });

  it("keeps an overlong native filename's data separate from the omitted-marker explanation", () => {
    const turn = String.fromCodePoint(0x202e);
    const name = `${Array.from({ length: 1001 }, () => `a${turn}`).join("")}.txt`;
    const file = new File(["x"], name, { type: "text/plain" });
    const { container, drafted } = filling([ATTACHMENT, ATTACHMENTS]);

    fireEvent.change(screen.getByLabelText(/Attachment\b/), {
      target: { files: [file] },
    });
    const rejected = fileStatus(/a+/, /needs a one-line name/);
    expect(rejected).toBeVisible();
    expect(rejected!.querySelector("bdi")).not.toHaveTextContent("left out");
    expect(rejected!.querySelector("span")).toHaveTextContent("left out");
    expect(rejected).toHaveTextContent(
      "1,001 invisible or text-direction characters",
    );
    expect(container.textContent).not.toContain(turn);
    expect(drafted().attachment).toBeNull();

    fireEvent.change(screen.getByLabelText(/Choose files for/), {
      target: {
        files: [
          new File(["x"], "Demo Sample 1.txt", { type: "text/plain" }),
          new File(["x"], "Demo Sample 2.txt", { type: "text/plain" }),
          file,
        ],
      },
    });
    expect(fileStatus(/a+/, /would exceed the number/)).toBeVisible();
    expect(drafted().attachments).toHaveLength(0);
    expect(container.textContent).not.toContain(turn);
  });

  it("accepts a selected file with a legal media-type parameter in single and many controls", () => {
    const file = new File(["x"], "Demo Sample.txt", {
      type: 'text/plain; charset="utf-8"',
    });
    const { drafted } = filling([ATTACHMENT, ATTACHMENTS]);
    fireEvent.change(screen.getByLabelText(/Attachment\b/), {
      target: { files: [file] },
    });
    fireEvent.change(screen.getByLabelText(/Choose files for/), {
      target: { files: [file] },
    });

    expect(drafted().attachment).toEqual({ pending: file });
    expect(drafted().attachments).toEqual([{ pending: file }]);
    expect(
      screen.queryByText(
        /invalid file type|doesn't have an extension this field accepts/,
      ),
    ).toBeNull();
  });

  it("shows retained file metadata without presenting its id as a new browser file", () => {
    const kept = {
      id: "retained-file",
      name: "Demo Sample kept.txt",
      size: 4,
    };
    const draft = draftFrom([ATTACHMENT], { attachment: kept });
    render(
      <ThemeProvider theme={theme}>
        <FillForm
          formId="retained"
          fields={[ATTACHMENT]}
          draft={draft}
          marks={NO_MARKS}
          onDraft={vi.fn()}
          onLeave={vi.fn()}
          onUnread={vi.fn()}
        />
      </ThemeProvider>,
    );

    expect(draft.attachment).toEqual({ kept, originalIndex: 0 });
    expect(visibleFileName("Demo Sample kept.txt")).toBeVisible();
    expect(screen.queryByText(/was uploaded earlier/)).toBeNull();
    expect(screen.queryByText(/retained-file/)).toBeNull();
    expect(screen.getByLabelText(/Attachment/)).toHaveValue("");
  });

  it("draws a file field nested among ordinary fields through the same form", () => {
    const nested: FillField = {
      name: "details",
      kind: "fields",
      mustBeGiven: false,
      fields: [ATTACHMENT],
    };
    const { drafted } = filling([nested]);
    const input = screen.getByLabelText(/Attachment/);
    const file = new File(["x"], "Demo Sample.txt", { type: "text/plain" });

    fireEvent.change(input, { target: { files: [file] } });

    expect(drafted().details).toEqual({ attachment: { pending: file } });
    expect(visibleFileName("Demo Sample.txt")).toBeVisible();
    expect(screen.queryByText(/awaits upload/)).toBeNull();
  });
});

describe("FillForm", () => {
  it("draws one control to each field in declared order and what a field holds under it, each read by its label, or its name with underscores as spaces", () => {
    const { container } = filling();

    const said = [...container.querySelectorAll(".MuiFormLabel-root")]
      .filter((label) => label.querySelector("bdi") !== null)
      .map((label) => {
        const copied = label.cloneNode(true) as HTMLElement;
        for (const hidden of copied.querySelectorAll('[aria-hidden="true"]'))
          hidden.remove();
        return copied.textContent?.trim();
      });

    expect(said).toEqual([
      "Complaint · complaint",
      "order reference",
      "due",
      "At · at",
      "Urgent · urgent",
      "Category · category",
      "Tags · tags",
      "Contact · contact",
      "Email · email",
    ]);
  });

  it("says a field's help under its control, and how long its text may run beside how long it runs", async () => {
    filling();
    const complaint = screen.getByRole("textbox", {
      name: "Complaint · complaint",
    });

    await userEvent.type(complaint, "Leaks");

    expect(complaint).toHaveAccessibleDescription(
      "What went wrong, in the customer's words. 5 / 10",
    );
    expect(complaint).toHaveAttribute("dir", "auto");
  });

  it("keeps each text, number and date control described by its complete formatted message and help when shown as written", async () => {
    const fields = [
      COMPLAINT,
      { ...ORDER_REFERENCE, help: "Use the reference on the receipt." },
      { ...DUE, help: "Use the delivery date on the receipt." },
    ];
    const messages = fields.map(
      (field) =>
        `Check **${field.name}**.\n\n- Read the receipt.\n- Keep both details.`,
    );
    render(
      <ThemeProvider theme={theme}>
        <FillForm
          formId="filling"
          fields={fields}
          draft={draftOf(fields)}
          marks={NO_MARKS}
          beside={
            new Map(
              fields.map((field, index) => [
                field.name,
                <ModelProse
                  key={field.name}
                  text={messages[index]!}
                  plain={<bdi>{messages[index]}</bdi>}
                />,
              ]),
            )
          }
          onDraft={vi.fn<Drafted>()}
          onLeave={vi.fn<Left>()}
          onUnread={vi.fn<Unread>()}
        />
      </ThemeProvider>,
    );

    for (const [index, field] of fields.entries()) {
      const control = document.getElementById(
        placeId("filling", [field.name]),
      )!;
      const descriptionId = control.getAttribute("aria-describedby")!;
      const description = document.getElementById(descriptionId)!;
      expect(
        within(description).getByText(field.name, { selector: "strong" }),
      ).toBeVisible();
      expect(description.querySelector("p")?.textContent).toBe(
        `Check ${field.name}.`,
      );
      expect(
        within(description)
          .getAllByRole("listitem")
          .map((item) => item.textContent),
      ).toEqual(["Read the receipt.", "Keep both details."]);
      expect(description).toHaveTextContent(field.help!);
      if (field.kind === "text")
        expect(description).toHaveTextContent("0 / 10");
      expect(control).toHaveAccessibleDescription(
        new RegExp(`Check ${field.name}.*Read the receipt.*Keep both details`),
      );

      await userEvent.click(
        within(description).getByRole("button", { name: "Show as written" }),
      );

      expect(control).toHaveAttribute("aria-describedby", descriptionId);
      expect(description.querySelector("bdi")?.textContent).toBe(
        messages[index],
      );
      expect(description).toHaveTextContent(field.help!);
      expect(control).toHaveAccessibleDescription(
        expect.stringContaining(`Check **${field.name}**.`),
      );
      expect(within(description).queryByRole("list")).not.toBeInTheDocument();
    }
  });

  it("keeps overlong text as typed, marks it immediately and shows its full count", async () => {
    const { drafted } = filling();

    await userEvent.type(
      screen.getByRole("textbox", { name: "Complaint · complaint" }),
      "Leaks everywhere",
    );

    expect(drafted().complaint).toBe("Leaks everywhere");
    expect(
      screen.getByRole("textbox", { name: "Complaint · complaint" }),
    ).toHaveAccessibleDescription(
      "This is longer than this field takes. What went wrong, in the customer's words. 16 / 10",
    );
    expect(
      screen.getByRole("textbox", { name: "Complaint · complaint" }),
    ).toHaveValue("Leaks everywhere");
  });

  it("counts astral characters once, marks an overlong paste, and clears the warning when it is replaced within the limit", async () => {
    const field = { ...COMPLAINT, longest: 2 };
    const { drafted } = filling([field]);
    const complaint = screen.getByRole("textbox", {
      name: "Complaint · complaint",
    }) as HTMLTextAreaElement;
    const astral = String.fromCodePoint(0x1f642);

    await userEvent.type(complaint, `${astral}A`);
    expect(drafted().complaint).toBe(`${astral}A`);
    expect(complaint).toHaveAccessibleDescription(/2 \/ 2/);

    await userEvent.paste("B");
    expect(drafted().complaint).toBe(`${astral}AB`);
    expect(complaint).toHaveValue(`${astral}AB`);
    expect(complaint).toHaveAccessibleDescription(
      /This is longer than this field takes/,
    );
    expect(complaint).toHaveFocus();

    complaint.setSelectionRange(astral.length, astral.length + 2);
    await userEvent.paste("C");
    expect(drafted().complaint).toBe(`${astral}C`);
    expect(complaint).toHaveAccessibleDescription(
      "What went wrong, in the customer's words. 2 / 2",
    );
  });

  it("draws again only the control typed in, every other one, within fields as well, drawn as it was", async () => {
    filling();
    await userEvent.type(
      within(group("Contact")).getByRole("textbox", { name: "Email · email" }),
      "avo@example.org",
    );
    vi.mocked(codePointsIn).mockClear();

    await userEvent.type(
      screen.getByRole("textbox", { name: "Complaint · complaint" }),
      "K",
    );

    expect(vi.mocked(codePointsIn).mock.calls).toEqual([["K"], ["K"]]);
    expect(
      within(group("Contact")).getByRole("textbox", { name: "Email · email" }),
    ).toHaveAccessibleDescription("15 / 20");
  });

  it("says why a value does not fit where it is marked, first under its control, and marks no other", () => {
    filling(FIELDS, new Map([['["complaint"]', "too_long" as const]]));

    const complaint = screen.getByRole("textbox", {
      name: "Complaint · complaint",
    });
    expect(complaint).toHaveAttribute("aria-invalid", "true");
    expect(complaint).toHaveAccessibleDescription(
      "This is longer than this field takes. What went wrong, in the customer's words. 0 / 10",
    );
    expect(
      screen.getByRole("textbox", {
        name: "order reference",
      }),
    ).toHaveAttribute("aria-invalid", "false");
  });

  it("says a line break refusal at its field and leaves every other field unmarked", () => {
    filling(FIELDS, new Map([['["complaint"]', "line_break" as const]]));

    const complaint = screen.getByRole("textbox", {
      name: "Complaint · complaint",
    });
    expect(complaint).toHaveAttribute("aria-invalid", "true");
    expect(complaint).toHaveAccessibleDescription(
      "This field takes a single line; remove the line break. What went wrong, in the customer's words. 0 / 10",
    );
    expect(
      screen.getByRole("textbox", {
        name: "order reference",
      }),
    ).toHaveAttribute("aria-invalid", "false");
  });

  it("asks for a number on a keyboard that has a minus sign, and says how it is written", () => {
    filling();

    const number = screen.getByRole("textbox", {
      name: "order reference",
    });

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

    const urgent = screen.getByRole("radiogroup", { name: "Urgent · urgent" });
    const category = screen.getByRole("radiogroup", {
      name: "Category · category",
    });

    expect(urgent).toHaveAccessibleDescription("Required.");
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
        "This isn't in the format this field expects. Give the time to the second, with up to six digits after the point.",
      );
      expect(control).toHaveAttribute("aria-invalid", "true");
    }
  });

  it("describes no moment's control as wrong where nothing is marked", () => {
    filling();
    const at = group("At");

    expect(
      within(at).getByRole("combobox", { name: "Offset" }),
    ).toHaveAccessibleDescription(
      "Give the time to the second, with up to six digits after the point.",
    );
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

    expect(group("Contact")).toHaveAccessibleDescription("Required.");
    expect(group("Tags")).toHaveAccessibleDescription(
      "Words to find it by. 0 / 2",
    );
  });

  it("says a place is left once the keyboard leaves it, and a moment only once it leaves the date, the time and the offset", async () => {
    const { left } = filling([COMPLAINT, AT]);

    await userEvent.click(
      screen.getByRole("textbox", { name: "Complaint · complaint" }),
    );
    await userEvent.click(within(group("At")).getByLabelText("Date"));
    expect(left()).toEqual([["complaint"]]);

    await userEvent.click(within(group("At")).getByLabelText("Time"));
    expect(left()).toEqual([["complaint"]]);

    await userEvent.click(
      screen.getByRole("textbox", { name: "Complaint · complaint" }),
    );
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

    await userEvent.click(
      screen.getByRole("textbox", { name: "Complaint · complaint" }),
    );
    expect(left()).toEqual([["at"]]);
  });

  it("starts many with none, adds one to fill, and offers no more once it holds its most", async () => {
    const { drafted, takenAway } = filling();
    const tags = group("Tags");
    expect(within(tags).getByText("None yet.")).toBeInTheDocument();

    await userEvent.click(addOneTo("Tags"));
    await userEvent.type(
      within(tags).getByRole("textbox", {
        name: `${isolatedName("Tags")} 1 · tags`,
      }),
      "kettle",
    );
    await userEvent.click(addOneTo("Tags"));

    expect(drafted().tags).toEqual(["kettle", ""]);
    expect(takenAway()).toBeUndefined();
    expect(within(tags).queryByRole("button", { name: /Add one/ })).toBeNull();
    expect(within(tags).queryByText("None yet.")).toBeNull();
    expect(within(tags).getByText("2 / 2")).toBeInTheDocument();
  });

  it("says the help of many once, over all of them, and not again under each", async () => {
    filling();
    const tags = group("Tags");

    await userEvent.click(addOneTo("Tags"));

    expect(within(tags).getAllByText("Words to find it by.")).toHaveLength(1);
    expect(
      within(tags).getByRole("textbox", {
        name: `${isolatedName("Tags")} 1 · tags`,
      }),
    ).toHaveAccessibleDescription("0 / 6");
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
      screen.getByRole("textbox", { name: `${isolatedName("Tags")} 1 · tags` }),
    ).toBeRequired();
    const answer = screen.getByRole("radiogroup", {
      name: `${isolatedName("Answers")} 1 · answers`,
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
      within(tags).getByRole("textbox", {
        name: `${isolatedName("Tags")} 1 · tags`,
      }),
      "first",
    );
    await userEvent.click(addOneTo("Tags"));
    const second = within(tags).getByRole("textbox", {
      name: `${isolatedName("Tags")} 2 · tags`,
    });
    await userEvent.type(second, "second");

    await userEvent.click(
      within(tags).getByRole("button", {
        name: `Remove ${isolatedName("Tags")} 1`,
      }),
    );

    expect(drafted().tags).toEqual(["second"]);
    expect(touched()).toEqual(["tags"]);
    expect(takenAway()).toBe(0);
    expect(within(tags).getAllByRole("textbox")).toEqual([second]);
    expect(second).toHaveAccessibleName(`${isolatedName("Tags")} 1 · tags`);
    expect(addOneTo("Tags")).toBeInTheDocument();
  });

  it("takes the keyboard to the control of the one added, even where adding it took Add one away", async () => {
    filling();
    const tags = group("Tags");

    await userEvent.click(addOneTo("Tags"));
    expect(
      within(tags).getByRole("textbox", {
        name: `${isolatedName("Tags")} 1 · tags`,
      }),
    ).toHaveFocus();

    await userEvent.click(addOneTo("Tags"));
    expect(
      within(tags).getByRole("textbox", {
        name: `${isolatedName("Tags")} 2 · tags`,
      }),
    ).toHaveFocus();
    expect(within(tags).queryByRole("button", { name: /Add one/ })).toBeNull();
  });

  it("takes the keyboard to the next one's Remove once one is taken away, and to the group named and described by the field once none follows", async () => {
    filling();
    const tags = group("Tags");
    await userEvent.click(addOneTo("Tags"));
    await userEvent.click(addOneTo("Tags"));

    await userEvent.click(
      within(tags).getByRole("button", {
        name: `Remove ${isolatedName("Tags")} 1`,
      }),
    );
    expect(
      within(tags).getByRole("button", {
        name: `Remove ${isolatedName("Tags")} 1`,
      }),
    ).toHaveFocus();

    await userEvent.click(
      within(tags).getByRole("button", {
        name: `Remove ${isolatedName("Tags")} 1`,
      }),
    );
    expect(within(tags).queryByRole("textbox")).toBeNull();
    expect(tags).toHaveFocus();
    expect(tags).toHaveAccessibleDescription("Words to find it by. 0 / 2");
    expect(tags.querySelector("legend")).not.toHaveFocus();
  });

  it("draws the fields a field holds under it, a change to one naming where it stands", async () => {
    const { drafted, touched } = filling();

    await userEvent.type(
      within(group("Contact")).getByRole("textbox", { name: "Email · email" }),
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

  it("shows a four-digit fractional time and keeps a six-digit edit exactly, while seven does not fit", () => {
    const initial = draftFrom([AT], {
      at: "2026-07-01T09:30:01.1234+05:43",
    });
    const { drafted } = filling([AT], NO_MARKS, initial);
    const time = within(group("At")).getByRole("textbox", { name: "Time" });

    expect(time).toHaveValue("09:30:01.1234");
    expect(draftReader()([AT], drafted(), new Set()).values.at).toBe(
      "2026-07-01T09:30:01.1234+05:43",
    );

    fireEvent.change(time, { target: { value: "09:30:01.123456" } });
    expect(draftReader()([AT], drafted(), new Set()).values.at).toBe(
      "2026-07-01T09:30:01.123456+05:43",
    );

    fireEvent.change(time, { target: { value: "09:30:01.1234567" } });
    expect(time).toHaveValue("09:30:01.1234567");
    expect(draftReader()([AT], drafted(), new Set()).problems).toEqual([
      { path: ["at"], reason: "malformed" },
    ]);
  });

  it("offers a standing non-quarter-hour offset once in order without changing the usual choices", async () => {
    filling(
      [AT],
      NO_MARKS,
      draftFrom([AT], { at: "2026-07-01T09:30:01+05:43" }),
    );
    const offset = within(group("At")).getByRole("combobox", {
      name: "Offset",
    });

    await userEvent.click(offset);
    const unusual = screen
      .getAllByRole("option")
      .map((each) => each.textContent);
    expect(unusual).toHaveLength(114);
    expect(unusual.filter((each) => each === "+05:43")).toHaveLength(1);
    expect(unusual.indexOf("+05:43")).toBeLessThan(unusual.indexOf("+05:45"));

    await userEvent.click(screen.getByRole("option", { name: "+05:45" }));
    await userEvent.click(offset);
    const usual = screen.getAllByRole("option").map((each) => each.textContent);
    expect(usual).toHaveLength(113);
    expect(usual).not.toContain("+05:43");
    expect(usual).not.toContain("-00:00");
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
