import { ThemeProvider } from "@mui/material/styles";
import { render, screen, within } from "@testing-library/react";
import { userEvent } from "@testing-library/user-event";
import { useState, type ReactNode } from "react";
import { describe, expect, it } from "vitest";

import type { MessageId } from "../../i18n/app";
import { theme } from "../../lib/theme/theme";
import { BindingControls } from "./BindingControls";
import type {
  BindingDraft,
  Shape,
  SourceDraft,
  SourceOffered,
} from "./workflowDrafts";

const TEXT: Shape = {
  name: "note",
  kind: "text",
  many: false,
  mustBeGiven: true,
  longest: 100,
};

const SOURCES: readonly SourceOffered[] = [
  {
    value: "input",
    words: "What the workflow takes",
    fields: [
      { ...TEXT, name: "short", longest: 50 },
      { ...TEXT, name: "long", longest: 500 },
      { ...TEXT, name: "count", kind: "number" },
    ],
  },
];

function themed({ children }: { readonly children: ReactNode }) {
  return <ThemeProvider theme={theme}>{children}</ThemeProvider>;
}

/** The controls over a host holding the binding, whose last source the test reads. */
function binding(
  target: Shape,
  source: SourceDraft,
  editable = true,
  flags: readonly MessageId[] = [],
) {
  const held: { source: SourceDraft } = { source };
  function Host() {
    const [bound, setBound] = useState<BindingDraft>({
      target: "note",
      source,
    });
    return (
      <BindingControls
        label="note"
        target={target}
        binding={bound}
        sources={SOURCES}
        constant
        editable={editable}
        flags={flags}
        onChange={(next) => {
          held.source = next;
          setBound({ target: "note", source: next });
        }}
      />
    );
  }
  render(<Host />, { wrapper: themed });
  return held;
}

function group(): HTMLElement {
  return screen.getByRole("group", { name: "note" });
}

describe("BindingControls", () => {
  it("offers only the fields that fit what is filled, keeping one chosen already in sight", async () => {
    binding(TEXT, { from: "input", path: "long" });

    await userEvent.click(
      within(group()).getByRole("combobox", { name: "Which" }),
    );

    expect(
      screen.getAllByRole("option").map((option) => option.textContent),
    ).toEqual(["None chosen yet", "long", "short"]);
  });

  it("switches a constant filling a yes or no, starting at no", async () => {
    const held = binding({ ...TEXT, kind: "yes_no" }, { from: "none" });

    await userEvent.click(
      within(group()).getByRole("combobox", { name: "From" }),
    );
    await userEvent.click(screen.getByRole("option", { name: "A constant" }));
    expect(held.source).toEqual({ from: "constant", typed: "false" });
    expect(within(group()).queryByRole("textbox")).toBeNull();

    await userEvent.click(within(group()).getByRole("switch", { name: "Yes" }));

    expect(held.source).toEqual({ from: "constant", typed: "true" });
  });

  it("holds a constant filling a number as typed in its box, every digit of it", async () => {
    const held = binding(
      { ...TEXT, kind: "number" },
      { from: "constant", typed: "" },
    );

    await userEvent.type(
      within(group()).getByRole("textbox", { name: "The number" }),
      "12345678901234567890123456789012345678",
    );

    expect(held.source).toEqual({
      from: "constant",
      typed: "12345678901234567890123456789012345678",
    });
  });

  it("says against a number typed that the server would refuse", async () => {
    binding({ ...TEXT, kind: "number" }, { from: "constant", typed: "" });
    const box = within(group()).getByRole("textbox", { name: "The number" });

    await userEvent.type(box, "1e5");

    expect(box).toHaveAttribute("aria-invalid", "true");
    expect(group()).toHaveAccessibleDescription(
      "A number is written in digits, with a point before any fraction, and nothing else; a zero is never written with a minus sign.",
    );
  });

  it("says nothing against a number typed that the server takes", async () => {
    binding({ ...TEXT, kind: "number" }, { from: "constant", typed: "" });
    const box = within(group()).getByRole("textbox", { name: "The number" });

    await userEvent.type(box, "12.5");

    expect(box).toHaveAttribute("aria-invalid", "false");
    expect(group()).not.toHaveAttribute("aria-describedby");
  });

  it("says against a constant of text one holding what a model may not be sent", () => {
    binding(TEXT, {
      from: "constant",
      typed: "a" + String.fromCodePoint(0x202e) + "b",
    });

    expect(group()).toHaveAccessibleDescription(
      "What was written holds a direction control, which text sent to a model may not hold.",
    );
  });

  it("says a constant as it was read nothing against, however it is typed here", () => {
    binding(
      { ...TEXT, kind: "number" },
      { from: "constant", typed: "1e21", read: "1e21" },
    );

    expect(group()).not.toHaveAttribute("aria-describedby");
  });

  it("says each thing that does not hold against it once", () => {
    binding(TEXT, { from: "none" }, true, [
      "contentProblem.input_unbound",
      "contentProblem.input_unbound",
    ]);

    expect(within(group()).getAllByText("Nothing fills it yet.")).toHaveLength(
      1,
    );
  });

  it("reads a constant filling a yes or no in words where it may not be written, and offers no control", () => {
    binding(
      { ...TEXT, kind: "yes_no" },
      { from: "constant", typed: "true" },
      false,
    );

    expect(screen.getByText("note")).toBeInTheDocument();
    expect(screen.getByText(/A constant: Yes/)).toBeInTheDocument();
    expect(screen.queryByRole("switch")).toBeNull();
    expect(screen.queryByRole("combobox")).toBeNull();
  });
});
