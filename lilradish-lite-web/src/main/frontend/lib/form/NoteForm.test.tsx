import { ThemeProvider } from "@mui/material/styles";
import { fireEvent, render, screen } from "@testing-library/react";
import { userEvent } from "@testing-library/user-event";
import type { ReactNode } from "react";
import { describe, expect, it, vi } from "vitest";

import type { Reason } from "../action/Press";
import { theme } from "../theme/theme";
import { NoteForm, type NoteAct } from "./NoteForm";

const DECISION = {
  label: "Decision note",
  hint: "why this attempt passes or does not",
};

const NOT_YET: Reason = { severity: "info", words: "Not offered here yet." };

function themed({ children }: { readonly children: ReactNode }) {
  return <ThemeProvider theme={theme}>{children}</ThemeProvider>;
}

/**
 * A fresh element on every render: React skips re-rendering a subtree handed
 * the very same element back, and a rerender that does nothing proves nothing.
 */
function formOf(over: {
  note?: { label: string; hint: string; required?: boolean } | null;
  acts?: readonly NoteAct[];
  busy?: boolean;
}) {
  return (
    <NoteForm
      note={over.note === undefined ? DECISION : over.note}
      acts={over.acts ?? [{ label: "Approve", act: vi.fn() }]}
      busy={over.busy ?? false}
    />
  );
}

function form(over: Parameters<typeof formOf>[0]) {
  return render(formOf(over), { wrapper: themed });
}

function unavailable(label: string): boolean {
  const button = screen.getByRole("button", { name: label });
  return button.getAttribute("aria-disabled") === "true";
}

describe("NoteForm", () => {
  it("withholds the act until a required note says something", async () => {
    const act = vi.fn();
    form({
      note: { ...DECISION, required: true },
      acts: [{ label: "Rerun", act }],
    });
    // Drawn with `pointer-events: none`, which userEvent will not click
    // through; a reader's own pointer is under no such rule.
    fireEvent.click(screen.getByRole("button", { name: "Rerun" }));

    await userEvent.type(screen.getByRole("textbox"), "   ");
    const withSpacesAlone = unavailable("Rerun");
    await userEvent.type(screen.getByRole("textbox"), "try the next model");

    expect(withSpacesAlone).toBe(true);
    expect(unavailable("Rerun")).toBe(false);
    expect(act).not.toHaveBeenCalled();
  });

  /** The field is marked required already, and a second word for it would say it twice. */
  it("says nothing beside an act waiting on a required note", () => {
    const { container } = form({
      note: { ...DECISION, required: true },
      acts: [{ label: "Rerun", act: vi.fn() }],
    });

    expect(unavailable("Rerun")).toBe(true);
    expect(screen.getByRole("button", { name: "Rerun" })).not.toHaveAttribute(
      "aria-describedby",
    );
    expect(container.querySelector("button + span")).toBeNull();
  });

  it("stops every act while one of them is in flight", () => {
    const approve = vi.fn();
    const reject = vi.fn();
    form({
      busy: true,
      acts: [
        { label: "Approve", act: approve },
        { label: "Reject", act: reject },
      ],
    });

    for (const label of ["Approve", "Reject"]) {
      fireEvent.click(screen.getByRole("button", { name: label }));
    }

    expect(unavailable("Approve")).toBe(true);
    expect(unavailable("Reject")).toBe(true);
    expect(approve).not.toHaveBeenCalled();
    expect(reject).not.toHaveBeenCalled();
  });

  /**
   * The act just pressed is the one the form turns busy, and a disabled
   * control is no longer somewhere focus can be — which this environment does
   * not act out, so the attribute stands for the focus kept.
   */
  it("keeps focus on the act that was pressed once the form turns busy", () => {
    const acts = [{ label: "Approve", act: vi.fn() }];
    const { rerender } = form({ acts });
    const approve = screen.getByRole("button", { name: "Approve" });
    approve.focus();

    rerender(formOf({ acts, busy: true }));

    expect(screen.getByRole("button", { name: "Approve" })).toBe(approve);
    expect(document.activeElement).toBe(approve);
    expect(approve).toHaveAttribute("aria-disabled", "true");
    expect(approve).not.toBeDisabled();
  });

  it("lets one act be unavailable without taking the others with it", () => {
    form({
      acts: [
        { label: "Approve", act: vi.fn() },
        { label: "Override", act: vi.fn(), reason: NOT_YET },
      ],
    });

    expect(unavailable("Approve")).toBe(false);
    expect(unavailable("Override")).toBe(true);
  });

  it("says why an act cannot be pressed, beside it and as its description", () => {
    form({
      acts: [
        { label: "Approve", act: vi.fn() },
        { label: "Override", act: vi.fn(), reason: NOT_YET },
      ],
    });

    expect(
      screen.getByRole("button", { name: "Override" }),
    ).toHaveAccessibleDescription(NOT_YET.words);
    expect(screen.getByText(NOT_YET.words)).toBeVisible();
    expect(screen.getByRole("button", { name: "Approve" })).not.toHaveAttribute(
      "aria-describedby",
    );
  });

  /**
   * An act with its reason under it stands taller than one without; lined up
   * along the bottom, the buttons would stand at different heights. Laid out
   * by the flex rules alone, which this environment computes but never applies.
   */
  it("lines its field and acts up along their tops", () => {
    const { container } = form({
      acts: [
        { label: "Approve", act: vi.fn() },
        { label: "Override", act: vi.fn(), reason: NOT_YET },
      ],
    });

    const row = getComputedStyle(container.firstElementChild!);

    expect(row.alignItems).toBe("flex-start");
    expect(row.flexWrap).toBe("wrap");
  });

  it("hands the act what was written", async () => {
    const act = vi.fn();
    form({ acts: [{ label: "Reject", act }] });

    await userEvent.type(screen.getByRole("textbox"), "the total is wrong");
    await userEvent.click(screen.getByRole("button", { name: "Reject" }));

    expect(act).toHaveBeenCalledWith("the total is wrong");
    expect(act).toHaveBeenCalledTimes(1);
  });

  /**
   * The note is the reviewer's reasoning and an act may be refused, so losing
   * it on click means writing it twice. Clearing here reads like the obvious
   * tidy-up, which is why it needs something holding it down.
   */
  it("keeps the note after an act, because the act may not have landed", async () => {
    form({ acts: [{ label: "Reject", act: vi.fn() }] });

    await userEvent.type(screen.getByRole("textbox"), "the total is wrong");
    await userEvent.click(screen.getByRole("button", { name: "Reject" }));

    expect(screen.getByRole("textbox")).toHaveValue("the total is wrong");
  });

  it("offers no field, and no note, where the act takes none", async () => {
    const act = vi.fn();
    form({ note: null, acts: [{ label: "Rerun", act }] });

    expect(screen.queryByRole("textbox")).toBeNull();

    await userEvent.click(screen.getByRole("button", { name: "Rerun" }));

    expect(act).toHaveBeenCalledWith("");
  });
});
