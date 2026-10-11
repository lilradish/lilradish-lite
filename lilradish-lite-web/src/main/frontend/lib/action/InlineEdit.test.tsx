import { ThemeProvider } from "@mui/material/styles";
import TextField from "@mui/material/TextField";
import {
  act,
  fireEvent,
  render,
  screen,
  waitFor,
} from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { useState } from "react";
import { describe, expect, it, vi } from "vitest";

import { theme } from "../theme/theme";
import { useAction } from "../request/useAction";
import { InlineEdit } from "./InlineEdit";

describe("InlineEdit", () => {
  it("keeps its maximum width while typing, inherits the read type and holds two token-sized controls inside the box", async () => {
    function Editing() {
      const [value, setValue] = useState("Invented claim");
      return (
        <div
          style={{
            fontSize: "20px",
            fontWeight: 650,
            lineHeight: "28px",
            color: "rgb(88, 102, 108)",
          }}
        >
          <InlineEdit
            open
            characters={64}
            readValue="Invented claim"
            action={{ running: false, problem: null, run: vi.fn() }}
            act={() => Promise.resolve(null)}
            onShut={vi.fn()}
            refusal={null}
          >
            <TextField
              multiline
              error
              slotProps={{ htmlInput: { "aria-label": "Name" } }}
              value={value}
              onChange={(event) => setValue(event.target.value)}
            />
          </InlineEdit>
        </div>
      );
    }
    render(<Editing />, {
      wrapper: ({ children }) => (
        <ThemeProvider theme={theme}>{children}</ThemeProvider>
      ),
    });
    const input = screen.getByRole("textbox", { name: "Name" });
    expect(input.tagName).toBe("TEXTAREA");
    const form = input.closest("form")!;
    const width = getComputedStyle(form).width;
    expect(width).toContain("640px");
    expect(getComputedStyle(form)).toMatchObject({
      marginBlockStart: "-5px",
      marginBlockEnd: "-5px",
      marginInlineStart: "-10px",
      marginInlineEnd: "-5px",
    });
    expect(form).toHaveAttribute("title", "Enter to save, Esc to cancel.");
    expect(getComputedStyle(form)).toMatchObject({
      fontSize: "20px",
      fontWeight: "650",
      lineHeight: "28px",
    });
    expect(getComputedStyle(input)).toMatchObject({
      fontSize: "20px",
      fontWeight: "650",
      lineHeight: "28px",
    });
    expect(
      getComputedStyle(input.closest(".MuiOutlinedInput-root")!).color,
    ).toBe("rgb(88, 102, 108)");
    const controls = [
      screen.getByRole("button", { name: "Save" }),
      screen.getByRole("button", { name: "Cancel" }),
    ];
    for (const control of controls) {
      expect(control).toHaveStyle({
        width: "24px",
        height: "24px",
        color: "rgb(88, 102, 108)",
      });
      expect(control.querySelector("svg")).toHaveStyle({
        width: "14px",
        height: "14px",
      });
      expect(control.closest("form")).toBe(form);
    }
    await userEvent.type(input, " revised");
    expect(input).toHaveValue("Invented claim revised");
    const inner = input.closest(".MuiOutlinedInput-root")!;
    const drawnInner = getComputedStyle(inner);
    expect([
      drawnInner.padding,
      drawnInner.borderWidth,
      drawnInner.outlineStyle,
      drawnInner.boxShadow,
    ]).toEqual(["0px", "0px", "none", "none"]);
    const fieldset = getComputedStyle(inner.querySelector("fieldset")!);
    expect([
      fieldset.borderWidth,
      fieldset.padding,
      fieldset.outlineStyle,
    ]).toEqual(["0px", "0px", "none"]);
    const outerBox = input.closest("form")!.firstElementChild!;
    const outer = getComputedStyle(outerBox);
    expect([outer.borderWidth, outer.boxShadow]).toEqual(["1px", ""]);
    expect(outerBox).toHaveStyle({
      borderColor: "rgb(28, 91, 129)",
    });
    expect(getComputedStyle(form).width).toBe(width);
    expect(screen.queryByRole("dialog")).toBeNull();
  });
  it("closes an unchanged value on leaving, saves a changed value once, and cancels without a blur write", async () => {
    const user = userEvent.setup();
    const sent = vi.fn(() => new Promise<string>(() => {}));
    const shut = vi.fn();
    function Editing() {
      const [value, setValue] = useState("Claim");
      const [open, setOpen] = useState(true);
      const action = useAction<string>(() => {});
      return (
        <>
          <InlineEdit
            open={open}
            action={action}
            act={sent}
            unchanged={value === "Claim"}
            onShut={() => {
              shut();
              setOpen(false);
            }}
            refusal={null}
          >
            <TextField
              label="Name"
              value={value}
              onChange={(event) => setValue(event.target.value)}
              autoFocus
            />
          </InlineEdit>
          <button onClick={() => setOpen(true)}>Open</button>
          <button>Outside</button>
        </>
      );
    }
    render(
      <ThemeProvider theme={theme}>
        <Editing />
      </ThemeProvider>,
    );
    await user.click(screen.getByRole("button", { name: "Outside" }));
    expect(shut).toHaveBeenCalledTimes(1);
    expect(sent).not.toHaveBeenCalled();
    await user.click(screen.getByRole("button", { name: "Open" }));
    await user.type(screen.getByRole("textbox", { name: "Name" }), " revised");
    await user.click(screen.getByRole("button", { name: "Cancel" }));
    expect(shut).toHaveBeenCalledTimes(2);
    expect(sent).not.toHaveBeenCalled();
    await user.click(screen.getByRole("button", { name: "Open" }));
    const input = screen.getByRole("textbox", { name: "Name" });
    fireEvent.submit(input.closest("form")!);
    fireEvent.blur(input, {
      relatedTarget: screen.getByRole("button", { name: "Outside" }),
    });
    await waitFor(() => expect(sent).toHaveBeenCalledTimes(1));
    expect(screen.getByRole("button", { name: "Save" })).toBeDisabled();
    expect(shut).toHaveBeenCalledTimes(2);
  });

  it.each(["{Enter}", "{Shift>}{Enter}{/Shift}"])(
    "saves an automatically growing text value with %s without inserting a newline, after composition has ended",
    async (key) => {
      const user = userEvent.setup();
      const run = vi.fn();
      const cancel = vi.fn();
      render(
        <ThemeProvider theme={theme}>
          <InlineEdit
            open
            action={{ run, running: false, problem: null }}
            act={() => Promise.resolve(null)}
            onShut={cancel}
            refusal={null}
          >
            <TextField multiline label="Name" defaultValue="Claim" autoFocus />
          </InlineEdit>
        </ThemeProvider>,
      );
      const input = screen.getByRole("textbox", { name: "Name" });
      expect(input.tagName).toBe("TEXTAREA");
      expect(input).not.toHaveAttribute("rows");
      fireEvent.compositionStart(input);
      fireEvent.keyDown(input, { key: "Enter", isComposing: true });
      fireEvent.keyDown(input, { key: "Escape", isComposing: true });
      expect(run).not.toHaveBeenCalled();
      expect(cancel).not.toHaveBeenCalled();
      fireEvent.compositionEnd(input);
      await user.keyboard(key);
      expect(run).toHaveBeenCalledOnce();
      expect(input).toHaveValue("Claim");
      expect(cancel).not.toHaveBeenCalled();
    },
  );

  it("does not commit a composing value on leaving or cancel it through a composing Escape", async () => {
    const user = userEvent.setup();
    const run = vi.fn();
    const cancel = vi.fn();
    render(
      <ThemeProvider theme={theme}>
        <InlineEdit
          open
          action={{ run, running: false, problem: null }}
          act={() => Promise.resolve(null)}
          onShut={cancel}
          refusal={null}
        >
          <TextField label="Name" defaultValue="Claim" autoFocus />
        </InlineEdit>
        <button>Outside</button>
      </ThemeProvider>,
    );
    const input = screen.getByRole("textbox", { name: "Name" });
    fireEvent.compositionStart(input);
    fireEvent.keyDown(input, { key: "Escape", isComposing: true });
    await user.click(screen.getByRole("button", { name: "Outside" }));
    expect(run).not.toHaveBeenCalled();
    expect(cancel).not.toHaveBeenCalled();
    expect(input).toHaveValue("Claim");
    fireEvent.compositionEnd(input);
    await user.click(input);
    await user.click(screen.getByRole("button", { name: "Outside" }));
    await waitFor(() => expect(run).toHaveBeenCalledTimes(1));
  });
  it("saves by Enter, holds both controls and Escape while sent, and closes only when the answer lands", async () => {
    const user = userEvent.setup();
    let answer!: (value: string) => void;
    const sent = vi.fn(
      () =>
        new Promise<string>((resolve) => {
          answer = resolve;
        }),
    );
    const settled = vi.fn();
    const cancelled = vi.fn();
    function Editing() {
      const [name, setName] = useState("Claim");
      const [open, setOpen] = useState(true);
      const action = useAction<string>((value) => {
        settled(value);
        setOpen(false);
      });
      return (
        <InlineEdit
          open={open}
          action={action}
          act={() => sent()}
          onShut={() => {
            cancelled();
            setOpen(false);
          }}
          refusal={null}
        >
          <TextField
            label="Name"
            value={name}
            onChange={(event) => setName(event.target.value)}
            autoFocus
          />
        </InlineEdit>
      );
    }
    render(
      <ThemeProvider theme={theme}>
        <Editing />
      </ThemeProvider>,
    );
    await user.type(
      screen.getByRole("textbox", { name: "Name" }),
      " revised{Enter}",
    );
    expect(sent).toHaveBeenCalledTimes(1);
    expect(screen.getByRole("button", { name: "Save" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "Cancel" })).toBeDisabled();
    await user.keyboard("{Escape}{Enter}");
    expect(cancelled).not.toHaveBeenCalled();
    expect(sent).toHaveBeenCalledTimes(1);
    await act(async () => answer("Saved"));
    await waitFor(() =>
      expect(settled).toHaveBeenCalledExactlyOnceWith("Saved"),
    );
    expect(screen.queryByRole("textbox")).toBeNull();
    expect(screen.queryByRole("dialog")).toBeNull();
  });

  it("cancels by Escape without a request, and keeps a held value from saving by Enter or the tick", async () => {
    const user = userEvent.setup();
    const run = vi.fn();
    const cancel = vi.fn();
    render(
      <ThemeProvider theme={theme}>
        <InlineEdit
          open
          action={{ run, running: false, problem: null }}
          act={() => Promise.resolve(null)}
          onShut={cancel}
          reason={{ severity: "warning", words: "The name is too long." }}
          refusal={<p>A refusal stays here.</p>}
        >
          <TextField label="Name" defaultValue="Claim" autoFocus />
        </InlineEdit>
      </ThemeProvider>,
    );
    await user.keyboard("{Enter}");
    expect(screen.getByRole("button", { name: "Save" })).toBeDisabled();
    expect(run).not.toHaveBeenCalled();
    expect(screen.getByText("A refusal stays here.")).toBeVisible();
    await user.keyboard("{Escape}");
    expect(cancel).toHaveBeenCalledTimes(1);
    expect(run).not.toHaveBeenCalled();
  });

  it.each(["leave", "submit then leave"])(
    "does not request return focus after %s moves the keyboard elsewhere",
    async (how) => {
      const user = userEvent.setup();
      let answer!: (value: string) => void;
      const sent = vi.fn(
        () =>
          new Promise<string>((resolve) => {
            answer = resolve;
          }),
      );
      const shut = vi.fn();
      function Editing() {
        const [open, setOpen] = useState(true);
        const action = useAction<string>(() => {});
        return (
          <>
            <InlineEdit
              open={open}
              action={action}
              act={sent}
              onShut={(returnFocus) => {
                shut(returnFocus);
                setOpen(false);
              }}
              refusal={null}
            >
              <TextField label="Name" defaultValue="Claim revised" autoFocus />
            </InlineEdit>
            <button>Elsewhere</button>
          </>
        );
      }
      render(
        <ThemeProvider theme={theme}>
          <Editing />
        </ThemeProvider>,
      );
      if (how === "submit then leave") await user.keyboard("{Enter}");
      await user.click(screen.getByRole("button", { name: "Elsewhere" }));
      expect(sent).toHaveBeenCalledOnce();
      await act(async () => answer("Saved"));
      expect(shut.mock.calls).toEqual([[false]]);
      expect(screen.queryByRole("textbox", { name: "Name" })).toBeNull();
      expect(screen.getByRole("button", { name: "Elsewhere" })).toHaveFocus();
    },
  );
});
