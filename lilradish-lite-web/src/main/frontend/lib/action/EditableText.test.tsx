import { ThemeProvider } from "@mui/material/styles";
import { fireEvent, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";

import { styledClassOf, styleRulesOf } from "../../testutil/writtenRules";
import { theme } from "../theme/theme";
import { EditableText } from "./EditableText";

const SAID = "Read the complaint.\nSay why it was refused.";

function drawn() {
  const onEdit = vi.fn<() => void>();
  render(
    <ThemeProvider theme={theme}>
      <EditableText label="Edit the instruction" onEdit={onEdit}>
        <p>{SAID}</p>
      </EditableText>
      <p>Elsewhere on the page.</p>
      <button>Outside</button>
    </ThemeProvider>,
  );
  const control = screen.getByRole("button", { name: "Edit the instruction" });
  const block = control.parentElement!;
  return {
    onEdit,
    control,
    block,
    text: block.querySelector("p")!,
    elsewhere: screen.getByText("Elsewhere on the page."),
  };
}

function selectOpeningWordOf(paragraph: HTMLElement) {
  const text = paragraph.firstChild!;
  const range = document.createRange();
  range.setStart(text, 0);
  range.setEnd(text, 4);
  window.getSelection()!.removeAllRanges();
  window.getSelection()!.addRange(range);
}

afterEach(() => window.getSelection()?.removeAllRanges());

describe("EditableText", () => {
  it("reads its text as content outside its edit control, a button named by what pressing it does", () => {
    const { control, block, text } = drawn();

    expect(control.tagName).toBe("BUTTON");
    expect(control).toHaveAccessibleName("Edit the instruction");
    expect(control.textContent).toBe("");
    expect(control.querySelector("svg")).toHaveAttribute("aria-hidden", "true");
    expect(text.textContent).toBe(SAID);
    expect(text.closest('button, [role="button"]')).toBeNull();
    expect(block).not.toHaveAttribute("role");
    expect(block).not.toHaveAttribute("tabindex");
  });

  it.each(["{Enter}", " "])(
    "opens from the keyboard with %j once its edit control is reached, and not on another key",
    async (key) => {
      const user = userEvent.setup();
      const { onEdit, control } = drawn();
      await user.tab();
      expect(control).toHaveFocus();

      await user.keyboard("a");
      expect(onEdit).not.toHaveBeenCalled();
      await user.keyboard(key);

      expect(onEdit).toHaveBeenCalledTimes(1);
    },
  );

  it("opens on a click on its text while nothing is selected", async () => {
    const { onEdit, text } = drawn();

    await userEvent.click(text);

    expect(onEdit).toHaveBeenCalledTimes(1);
  });

  it("leaves a pointer press ending a selection within its own text to the selection, opening nothing, while its edit control still opens it", () => {
    const { onEdit, block, control, text } = drawn();
    selectOpeningWordOf(text);

    fireEvent.pointerDown(text);
    fireEvent.click(text, { detail: 1 });

    expect(onEdit).not.toHaveBeenCalled();
    expect(window.getSelection()!.toString()).toBe("Read");
    // user-event's keyboard clears the selection, so the press is
    // sent as the click a key on the control makes, with no pointer before it.
    fireEvent.keyDown(block, { key: "Enter" });
    fireEvent.click(control);
    expect(onEdit).toHaveBeenCalledTimes(1);
    expect(window.getSelection()!.toString()).toBe("Read");
  });

  it("opens from its edit control, and on a pointer press on its text, while text elsewhere on the page is selected", () => {
    const { onEdit, control, text, elsewhere } = drawn();
    selectOpeningWordOf(elsewhere);
    expect(window.getSelection()!.toString()).toBe("Else");

    fireEvent.click(control);
    expect(onEdit).toHaveBeenCalledTimes(1);
    expect(window.getSelection()!.toString()).toBe("Else");
    fireEvent.pointerDown(text);
    fireEvent.click(text, { detail: 1 });

    expect(onEdit).toHaveBeenCalledTimes(2);
  });

  it("opens on a click that comes with no pointer press, as a screen reader or script sends it, even over a selection within its text", () => {
    const { onEdit, text } = drawn();
    selectOpeningWordOf(text);

    fireEvent.click(text, { detail: 1 });

    expect(onEdit).toHaveBeenCalledTimes(1);
  });

  it("rests as plain text, with no ground, no edge, no ring and no pencil seen, its margin cancelling its padding and edge so the text stands where it would unedited", () => {
    const { block, control } = drawn();

    const resting = getComputedStyle(block);
    expect([
      resting.backgroundColor,
      resting.borderStyle,
      resting.borderWidth,
      resting.borderColor,
      resting.borderRadius,
      resting.cursor,
      getComputedStyle(control).opacity,
    ]).toEqual([
      "rgba(0, 0, 0, 0)",
      "solid",
      "1px",
      "rgba(0, 0, 0, 0)",
      "8px",
      "text",
      "0",
    ]);
    expect(resting.outlineStyle).not.toBe("solid");
    expect([
      resting.paddingTop,
      resting.paddingRight,
      resting.paddingBottom,
      resting.paddingLeft,
      resting.marginTop,
      resting.marginRight,
      resting.marginBottom,
      resting.marginLeft,
    ]).toEqual([
      "8px",
      "10px",
      "8px",
      "10px",
      "-9px",
      "-11px",
      "-9px",
      "-11px",
    ]);
    expect(
      styleRulesOf(block).map(({ rule }) =>
        rule.style.getPropertyValue("user-select"),
      ),
    ).not.toContain("none");
  });

  it("draws the whole block, once the keyboard reaches its edit control, on the row-hover ground with the card edge and the act-coloured ring outside it, the 14px pencil at its top right seen", async () => {
    const { block, control } = drawn();

    await userEvent.tab();

    expect(control).toHaveFocus();
    expect(control).toHaveClass("Mui-focusVisible");
    const reached = getComputedStyle(block);
    expect([
      reached.backgroundColor,
      reached.borderColor,
      reached.outlineStyle,
      reached.outlineWidth,
      reached.outlineColor,
      reached.outlineOffset,
    ]).toEqual([
      "rgb(243, 248, 250)",
      "rgb(214, 224, 228)",
      "solid",
      "2px",
      "rgb(28, 91, 129)",
      "2px",
    ]);
    const pencil = getComputedStyle(control.querySelector("svg")!);
    expect([
      getComputedStyle(control).opacity,
      getComputedStyle(control).color,
      getComputedStyle(control).float,
      pencil.width,
      pencil.height,
    ]).toEqual(["1", "rgb(88, 102, 108)", "right", "14px", "14px"]);
    expect(getComputedStyle(control).outlineStyle).not.toBe("solid");
  });

  it("draws the same ground, edge and pencil under a pointer that can hover, and never under a touch, leaving the ring to the keyboard", () => {
    const { block } = drawn();
    const generated = styledClassOf(block);

    const pointed = styleRulesOf(block).filter(({ rule }) =>
      rule.selectorText.includes(":hover"),
    );

    expect(
      pointed.map(({ media, rule }) => [media, rule.selectorText]),
    ).toEqual([
      ["(hover: hover)", `.${generated}:hover`],
      ["(hover: hover)", `.${generated}:hover>.EditableText-control`],
    ]);
    expect([
      pointed[0]!.rule.style.backgroundColor,
      pointed[0]!.rule.style.borderColor,
      pointed[0]!.rule.style.outlineStyle,
      pointed[1]!.rule.style.opacity,
    ]).toEqual(["rgb(243, 248, 250)", "rgb(214, 224, 228)", "", "1"]);
    expect(
      styleRulesOf(block)
        .filter(({ media }) => media === null)
        .map(({ rule }) => rule.selectorText)
        .filter((selector) => selector.includes(":hover")),
    ).toEqual([]);
  });
});
