import { ThemeProvider } from "@mui/material/styles";
import Typography from "@mui/material/Typography";
import TextField from "@mui/material/TextField";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import type { ReactNode } from "react";
import { expect, it, vi } from "vitest";
import { styledClassOf, styleRulesOf } from "../../testutil/writtenRules";
import { theme } from "../theme/theme";
import { InlineEditableValue } from "./InlineEditableValue";
import { InlineEdit } from "./InlineEdit";

it("offers the actual value to pointer and keyboard, while a held value cannot open", async () => {
  const user = userEvent.setup();
  const edit = vi.fn();
  const { rerender } = render(
    <ThemeProvider theme={theme}>
      <InlineEditableValue value="Invented claim" onEdit={edit} />
      <button>Outside</button>
    </ThemeProvider>,
  );
  const value = screen.getByRole("button", {
    name: "Invented claim, editable",
  });
  expect(value).toHaveTextContent("Invented claim");
  expect(value).not.toHaveAttribute("title");
  expect(screen.queryByText("Enter to save, Esc to cancel.")).toBeNull();
  expect(value.querySelector("svg")).toHaveAttribute("aria-hidden", "true");
  await user.tab();
  expect(value).toHaveFocus();
  await user.keyboard("{Enter} ");
  await user.click(value);
  expect(edit).toHaveBeenCalledTimes(3);
  rerender(
    <ThemeProvider theme={theme}>
      <InlineEditableValue value="Invented claim" onEdit={edit} disabled />
      <button>Outside</button>
    </ThemeProvider>,
  );
  expect(value).toBeDisabled();
  await user.tab();
  expect(screen.getByRole("button", { name: "Outside" })).toHaveFocus();
  expect(edit).toHaveBeenCalledTimes(3);
});

it.each([14, 24, 32])(
  "keeps the read and edit box at the same token geometry under a %spx reading",
  (size) => {
    const read = (
      <InlineEditableValue
        value="Invented claim"
        characters={128}
        onEdit={vi.fn()}
      />
    );
    const editing = (
      <InlineEdit
        open
        characters={128}
        action={{ running: false, problem: null, run: vi.fn() }}
        act={() => Promise.resolve(null)}
        onShut={vi.fn()}
        refusal={null}
      >
        <TextField
          multiline
          defaultValue="Invented claim"
          slotProps={{ htmlInput: { "aria-label": "Name" } }}
        />
      </InlineEdit>
    );
    const underReading = (children: ReactNode) => (
      <ThemeProvider theme={theme}>
        <div
          style={{ fontSize: `${size}px`, fontWeight: 650, lineHeight: "32px" }}
        >
          {children}
        </div>
      </ThemeProvider>
    );
    const { rerender } = render(underReading(read));
    const button = screen.getByRole("button", {
      name: "Invented claim, editable",
    });
    const before = getComputedStyle(button);
    const geometry = [
      before.width,
      before.padding,
      before.gridTemplateColumns,
      before.columnGap,
      before.marginInlineStart,
      before.marginInlineEnd,
    ];
    rerender(underReading(editing));
    const input = screen.getByRole("textbox", { name: "Name" });
    const form = getComputedStyle(input.closest("form")!);
    const box = getComputedStyle(input.closest("form")!.firstElementChild!);
    expect([
      form.width,
      box.padding,
      box.gridTemplateColumns,
      box.columnGap,
      form.marginInlineStart,
      form.marginInlineEnd,
    ]).toEqual(geometry);
    expect(getComputedStyle(input).fontSize).toBe(`${size}px`);
    expect(getComputedStyle(input).fontWeight).toBe("650");
    expect(getComputedStyle(input).lineHeight).toBe("32px");
  },
);

it.each([false, true])(
  "reads the value, held %s, in the body ink at full opacity, with a decorative pen, and offers a pointer and a hover ground only while it can open",
  (held) => {
    render(
      <ThemeProvider theme={theme}>
        <Typography component="div" sx={{ color: "text.primary" }}>
          <InlineEditableValue
            value="Invented claim"
            onEdit={vi.fn()}
            disabled={held}
          />
        </Typography>
      </ThemeProvider>,
    );
    const value = screen.getByRole("button", {
      name: "Invented claim, editable",
    });
    const drawn = getComputedStyle(value);
    const generated = [...value.classList].find((name) =>
      name.startsWith("css-"),
    );
    const own = [...document.styleSheets]
      .flatMap((sheet) => [...sheet.cssRules])
      .filter(
        (rule): rule is CSSStyleRule =>
          rule instanceof CSSStyleRule && rule.selectorText === `.${generated}`,
      );

    expect(own.map((rule) => rule.style.color)).toContain("inherit");
    expect(own.map((rule) => rule.style.color)).not.toContain(
      "rgb(128, 143, 150)",
    );
    expect(getComputedStyle(value.parentElement!).color).toBe(
      "rgb(25, 32, 36)",
    );
    expect([drawn.backgroundColor, drawn.borderColor, drawn.opacity]).toEqual([
      "rgba(0, 0, 0, 0)",
      "rgba(0, 0, 0, 0)",
      "1",
    ]);
    expect(value.querySelector("svg")).toHaveAttribute("aria-hidden", "true");
    expect([drawn.cursor, drawn.pointerEvents]).toEqual(
      held ? ["default", "none"] : ["pointer", "auto"],
    );
  },
);

it("draws the value, once the keyboard reaches it, on the row-hover ground with the card edge for its border and the act-coloured ring outside it, and with neither before", async () => {
  render(
    <ThemeProvider theme={theme}>
      <InlineEditableValue value="Invented claim" onEdit={vi.fn()} />
    </ThemeProvider>,
  );
  const value = screen.getByRole("button", {
    name: "Invented claim, editable",
  });
  const resting = getComputedStyle(value);
  expect(resting.backgroundColor).not.toBe("rgb(243, 248, 250)");
  expect(resting.borderColor).toBe("rgba(0, 0, 0, 0)");
  expect(resting.outlineStyle).not.toBe("solid");

  await userEvent.tab();

  const reached = getComputedStyle(value);
  expect([
    reached.backgroundColor,
    reached.borderStyle,
    reached.borderWidth,
    reached.borderColor,
    reached.outlineStyle,
    reached.outlineWidth,
    reached.outlineColor,
    reached.outlineOffset,
  ]).toEqual([
    "rgb(243, 248, 250)",
    "solid",
    "1px",
    "rgb(214, 224, 228)",
    "solid",
    "2px",
    "rgb(28, 91, 129)",
    "calc(var(--_focusVisible-offset, 1) * 2px)",
  ]);
});

it("draws the value under a pointer that can hover on the row-hover ground with the card edge for its border, never under a touch, leaving the outline to the keyboard ring", () => {
  render(
    <ThemeProvider theme={theme}>
      <InlineEditableValue value="Invented claim" onEdit={vi.fn()} />
    </ThemeProvider>,
  );
  const value = screen.getByRole("button", {
    name: "Invented claim, editable",
  });
  const generated = styledClassOf(value);
  const pointed = styleRulesOf(value).filter(({ rule }) =>
    rule.selectorText.includes(`.${generated}:hover`),
  );

  expect(pointed.map(({ media, rule }) => [media, rule.selectorText])).toEqual([
    ["(hover: hover)", `.${generated}:hover`],
    ["(hover: hover)", `.${generated}:hover>svg`],
  ]);
  expect(pointed[0]!.rule.style.backgroundColor).toBe("rgb(243, 248, 250)");
  expect(pointed[0]!.rule.style.borderColor).toBe("rgb(214, 224, 228)");
  expect(pointed[0]!.rule.style.outlineStyle).toBe("");
  expect(pointed[0]!.rule.style.outlineColor).toBe("");
});

it("draws the value the keyboard reaches on the same ground and edge as under the pointer, at any pointer, written apart from the hover", () => {
  render(
    <ThemeProvider theme={theme}>
      <InlineEditableValue value="Invented claim" onEdit={vi.fn()} />
    </ThemeProvider>,
  );
  const value = screen.getByRole("button", {
    name: "Invented claim, editable",
  });
  const generated = styledClassOf(value);

  const reached = styleRulesOf(value).filter(
    ({ rule }) =>
      rule.selectorText === `.${generated}.Mui-focusVisible` &&
      rule.style.backgroundColor !== "",
  );

  expect(reached.map(({ media }) => media)).toEqual([null]);
  expect([
    reached[0]!.rule.style.backgroundColor,
    reached[0]!.rule.style.borderColor,
  ]).toEqual(["rgb(243, 248, 250)", "rgb(214, 224, 228)"]);
  expect(reached[0]!.rule.selectorText).not.toContain(":hover");
});
