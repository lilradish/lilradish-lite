import CssBaseline from "@mui/material/CssBaseline";
import Link from "@mui/material/Link";
import { ThemeProvider } from "@mui/material/styles";
import { render, screen, within } from "@testing-library/react";
import { userEvent } from "@testing-library/user-event";
import { describe, expect, it } from "vitest";

import { theme } from "../theme/theme";
import { Identifier } from "./Identifier";
import { KeyTag } from "./KeyTag";

function chipOf(element: Element) {
  const style = getComputedStyle(element);
  return [
    style.backgroundColor,
    style.borderTopStyle,
    style.borderTopWidth,
    style.borderBottomWidth,
    style.borderTopColor,
    style.borderRadius,
    style.color,
    style.fontFamily,
    style.fontSize,
    style.fontWeight,
    style.lineHeight,
    style.paddingLeft,
  ];
}

describe("Identifier", () => {
  it("sets a run key apart as the same chip a key tag is, without changing the link that opens it", async () => {
    render(
      <ThemeProvider theme={theme}>
        <CssBaseline />
        <Link href="/demo">
          <Identifier value="Demo Sample" />
        </Link>
        <KeyTag>SUPPORT</KeyTag>
      </ThemeProvider>,
    );

    const link = screen.getByRole("link", { name: "Demo Sample" });
    const tag = within(link).getByText("Demo Sample");
    const key = screen.getByText("SUPPORT").parentElement!;
    expect(tag.tagName).toBe("SPAN");
    expect(tag.parentElement).toBe(link);
    expect(chipOf(tag)).toEqual(chipOf(key));
    expect(chipOf(tag)).toEqual([
      "rgb(255, 255, 255)",
      "solid",
      "1px",
      "1px",
      "rgb(194, 205, 210)",
      "4px",
      "rgb(75, 87, 93)",
      "ui-monospace, monospace",
      "11.5px",
      "400",
      "18px",
      "5px",
    ]);
    expect(screen.queryByRole("button")).toBeNull();

    await userEvent.tab();

    expect(link).toHaveFocus();
    expect(tag).not.toHaveFocus();
    const linkClass = [...link.classList].find((name) =>
      name.startsWith("css-"),
    );
    const rings = [...document.styleSheets]
      .flatMap((sheet) => [...sheet.cssRules])
      .filter(
        (rule): rule is CSSStyleRule =>
          rule instanceof CSSStyleRule &&
          (rule.selectorText === `.${linkClass}.Mui-focusVisible` ||
            rule.selectorText === ":focus-visible:not(.MuiButtonBase-root)"),
      );
    expect(rings).toHaveLength(2);
    for (const ring of rings)
      expect([
        ring.style.outlineStyle,
        ring.style.outlineWidth,
        ring.style.outlineColor,
      ]).toEqual(["solid", "2px", "rgb(28, 91, 129)"]);
  });

  it("grounds the chip in the picked colour under the pointer or while its link has the keyboard, and a key tag never", () => {
    render(
      <ThemeProvider theme={theme}>
        <Link href="/demo">
          <Identifier value="Demo Sample" />
        </Link>
        <KeyTag>SUPPORT</KeyTag>
      </ThemeProvider>,
    );

    const generated = (element: Element) =>
      [...element.classList].find((name) => name.startsWith("css-"))!;
    const tag = generated(screen.getByText("Demo Sample"));
    const key = generated(screen.getByText("SUPPORT").parentElement!);
    const rules = [...document.styleSheets]
      .flatMap((sheet) => [...sheet.cssRules])
      .filter((rule): rule is CSSStyleRule => rule instanceof CSSStyleRule);
    const pointed = rules.filter((rule) =>
      rule.selectorText.includes(`.${tag}:hover`),
    );

    expect(pointed.map((rule) => rule.selectorText)).toEqual([
      `.${tag}:hover,.MuiLink-root:focus-visible .${tag}`,
    ]);
    expect(pointed[0]!.style.backgroundColor).toBe("rgb(218, 235, 247)");
    expect(
      rules.filter((rule) => rule.selectorText.includes(`.${key}:`)),
    ).toEqual([]);
  });
});
