import {
  fireEvent,
  render as renderReact,
  screen,
} from "@testing-library/react";
import { useState, type ReactElement } from "react";
import { ThemeProvider } from "@mui/material/styles";
import { theme } from "../theme/theme";
import { describe, expect, it, vi } from "vitest";

import { InstructionText } from "./InstructionText";

function render(ui: ReactElement) {
  return renderReact(<ThemeProvider theme={theme}>{ui}</ThemeProvider>);
}

const TAKES = [
  { name: "complaint", label: "The complaint" },
  { name: "sender", label: "Who sent it" },
];

function editing(text: string, readOnly = false) {
  const changed = vi.fn<(text: string) => void>();
  function Editing() {
    const [typed, setTyped] = useState(text);
    return (
      <InstructionText
        text={typed}
        takes={TAKES}
        editing={{
          label: "The instruction",
          readOnly,
          invalid: false,
          change: (next) => {
            changed(next);
            setTyped(next);
          },
        }}
      />
    );
  }
  render(<Editing />);
  return {
    box: screen.getByRole("textbox", { name: "The instruction" }),
    changed,
  };
}

function type(box: HTMLElement, text: string) {
  box.focus();
  box.textContent = text;
  const range = document.createRange();
  range.selectNodeContents(box);
  range.collapse(false);
  window.getSelection()!.removeAllRanges();
  window.getSelection()!.addRange(range);
  fireEvent.input(box);
}

describe("InstructionText", () => {
  it("keeps reference and suggestion separators in the secondary ink without changing the spoken spaces or raw reference", () => {
    const { box, changed } = editing("Read {{complaint}}.");
    const marker = box.querySelector('[data-reference="complaint"]')!;
    const dot = marker.querySelector("span")!;
    expect(marker.textContent).toBe("The complaint \u00b7 {{complaint}}");
    expect(dot.textContent).toBe("\u00b7");
    expect(dot).toHaveStyle({
      color: "rgb(88, 102, 108)",
      fontWeight: "400",
    });
    type(box, "Read {{");
    const choice = screen.getByRole("option", {
      name: "The complaint \u00b7 {{complaint}}",
    });
    expect(choice.querySelector("span")).toHaveStyle({
      color: "rgb(88, 102, 108)",
      fontWeight: "400",
    });
    fireEvent.click(choice);
    expect(changed).toHaveBeenLastCalledWith("Read {{complaint}}");
    expect(box).toHaveTextContent("The complaint \u00b7 {{complaint}}");
  });
  it.each([
    ["references", "{{complaint}}".repeat(630) + "ab"],
    ["private-use characters", String.fromCodePoint(0xf0000).repeat(8192)],
  ])(
    "keeps a full valid instruction of %s marked and copies its complete raw spelling",
    (_case, raw) => {
      const { box, changed } = editing("Read.");
      type(box, raw);
      expect(changed).toHaveBeenLastCalledWith(raw);
      expect(box.querySelector("[data-written]")).not.toBeNull();
      const range = document.createRange();
      range.selectNodeContents(box);
      window.getSelection()!.removeAllRanges();
      window.getSelection()!.addRange(range);
      const setData = vi.fn();
      fireEvent.copy(box, { clipboardData: { setData } });
      expect(setData).toHaveBeenCalledExactlyOnceWith("text/plain", raw);
      expect(changed).toHaveBeenCalledTimes(1);
      expect(box).toHaveAttribute("aria-invalid", "false");
    },
  );

  it("marks references while drawing hidden instruction and label characters instead of applying them", () => {
    render(
      <InstructionText
        text={"Read \u202e{{complaint}} then \\{{sender}}."}
        takes={[{ name: "complaint", label: "The\u200b complaint" }]}
      />,
    );
    expect(screen.getAllByRole("img")).toHaveLength(2);
    expect(document.body.textContent).toContain(
      "Read U+202ETheU+200B complaint · {{complaint}}",
    );
    expect(document.body.textContent).toContain("then {{sender}}.");
    expect(document.body.textContent).not.toContain("\u202e");
    expect(document.body.textContent).not.toContain("\u200b");
  });

  it("counts the hidden-character limit across the text on both sides of a marked input", () => {
    render(
      <InstructionText
        text={"a\u200b".repeat(500) + "{{complaint}}" + "b\u200b".repeat(501)}
        takes={TAKES}
      />,
    );
    expect(screen.queryByRole("img")).toBeNull();
    expect(document.body.textContent).toContain("1,001");
    expect(document.body.textContent).toContain(
      "The complaint · {{complaint}}",
    );
    expect(document.body.textContent).not.toContain("\u200b");
  });

  it("marks labels and names while showing unknown references in place and escaped braces as literal text", () => {
    render(
      <InstructionText
        text={"Read {{complaint}}\n{{a.b}} \\{{sender}} {{unfinished"}
        takes={TAKES}
      />,
    );
    expect(screen.getByText("{{complaint}}")).toBeInTheDocument();
    expect(screen.getByText("{{a.b}}")).toBeInTheDocument();
    expect(screen.queryByText("Who sent it · {{sender}}")).toBeNull();
    expect(document.body.textContent).toContain("{{sender}} {{unfinished");
  });

  it("keeps raw references, escapes, multiline text and its trailing newline rather than displayed labels", () => {
    const { box, changed } = editing("Read.");
    const raw = "Read {{complaint}}.\n\\{{sender}}\n";
    type(box, raw);
    expect(changed).toHaveBeenLastCalledWith(raw);
    expect(box).toHaveTextContent("The complaint · {{complaint}}");
    const selection = window.getSelection()!.getRangeAt(0);
    expect(selection.endContainer).toBe(box.lastChild);
    expect(selection.endOffset).toBe((box.lastChild!.textContent ?? "").length);
  });

  it("offers top-level names after typed braces and inserts the selected reference with the keyboard", () => {
    const { box, changed } = editing("Read.");
    type(box, "Read {{");
    expect(screen.getAllByRole("option")).toHaveLength(2);
    fireEvent.keyDown(box, { key: "ArrowDown" });
    fireEvent.keyDown(box, { key: "Enter" });
    expect(changed).toHaveBeenLastCalledWith("Read {{sender}}");
    expect(box).toHaveTextContent("Who sent it · {{sender}}");
    expect(screen.queryByRole("listbox")).toBeNull();
  });

  it("dismisses suggestions without changing the typed text, and escaped braces offer nothing", () => {
    const { box, changed } = editing("Read.");
    type(box, "{{");
    fireEvent.keyDown(box, { key: "Escape" });
    expect(screen.queryByRole("listbox")).toBeNull();
    expect(changed).toHaveBeenLastCalledWith("{{");
    type(box, "\\{{");
    expect(screen.queryByRole("listbox")).toBeNull();
  });

  it("keeps suggestion buttons out of the tab order and closes them when the editor loses focus", () => {
    const { box, changed } = editing("Read.");
    type(box, "Read {{");
    expect(document.activeElement).toBe(box);
    for (const option of screen.getAllByRole("option")) {
      expect(option).toHaveAttribute("tabindex", "-1");
      expect(getComputedStyle(option).minWidth).toBe("0px");
    }
    fireEvent.blur(box);
    expect(screen.queryByRole("listbox")).toBeNull();
    expect(changed).toHaveBeenLastCalledWith("Read {{");
  });

  it("selects a suggestion with the mouse without leaving the editor's keyboard focus", () => {
    const { box, changed } = editing("Read.");
    type(box, "Read {{");
    const option = screen.getByRole("option", { name: /Who sent it/ });
    fireEvent.mouseDown(option);
    fireEvent.click(option);
    expect(changed).toHaveBeenLastCalledWith("Read {{sender}}");
    expect(document.activeElement).toBe(box);
    expect(screen.queryByRole("listbox")).toBeNull();
    type(box, "Next {{");
    fireEvent.keyDown(box, { key: "ArrowDown" });
    expect(box).toHaveAttribute(
      "aria-activedescendant",
      screen.getAllByRole("option")[1]!.id,
    );
    fireEvent.keyDown(box, { key: "Escape" });
    expect(box).not.toHaveAttribute("aria-activedescendant");
    expect(document.activeElement).toBe(box);
  });

  it("pastes plain text without letting markup become editor nodes or change a reference's raw spelling", () => {
    const { box, changed } = editing("Read.");
    type(box, "Read ");
    fireEvent.paste(box, {
      clipboardData: {
        getData: (kind: string) =>
          kind === "text/plain" ? "<img> {{complaint}}\n" : "<img src=x>",
      },
    });
    expect(changed).toHaveBeenLastCalledWith("Read <img> {{complaint}}\n");
    expect(box.querySelector("img")).toBeNull();
    expect(box).toHaveTextContent("<img> The complaint · {{complaint}}");
  });

  it("leaves an active composition's DOM in place until it ends, then marks its raw text", () => {
    const { box, changed } = editing("Read.");
    fireEvent.compositionStart(box);
    type(box, "\u65e5\u672c\u8a9e {{complaint}}");
    expect(box.textContent).toBe("\u65e5\u672c\u8a9e {{complaint}}");
    fireEvent.compositionEnd(box);
    expect(changed).toHaveBeenLastCalledWith(
      "\u65e5\u672c\u8a9e {{complaint}}",
    );
    expect(box).toHaveTextContent("The complaint · {{complaint}}");
  });

  it("does not accept input or paste while saving", () => {
    const { box, changed } = editing("Read {{complaint}}.", true);
    fireEvent.input(box);
    fireEvent.paste(box, { clipboardData: { getData: () => "changed" } });
    expect(changed).not.toHaveBeenCalled();
    expect(box).toHaveAttribute("aria-readonly", "true");
  });

  it("copies a marked reference as its raw spelling and cuts just the selected text", () => {
    const { box, changed } = editing("Read {{complaint}}.\n");
    box.focus();
    const range = document.createRange();
    range.selectNode(box.querySelector('[data-reference="complaint"]')!);
    window.getSelection()!.removeAllRanges();
    window.getSelection()!.addRange(range);
    const setData = vi.fn();
    fireEvent.copy(box, { clipboardData: { setData } });
    expect(setData).toHaveBeenLastCalledWith("text/plain", "{{complaint}}");
    expect(changed).not.toHaveBeenCalled();
    fireEvent.cut(box, { clipboardData: { setData } });
    expect(setData).toHaveBeenLastCalledWith("text/plain", "{{complaint}}");
    expect(changed).toHaveBeenLastCalledWith("Read .\n");
    expect(window.getSelection()!.anchorOffset).toBe(5);
  });
});
